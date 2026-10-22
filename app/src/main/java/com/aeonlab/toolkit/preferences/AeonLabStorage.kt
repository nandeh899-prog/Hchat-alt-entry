package com.aeonlab.toolkit.preferences

import android.content.Context
import android.content.SharedPreferences
import com.aeonlab.toolkit.utils.HLog
import io.fastkv.FastKV
import java.io.File
import java.util.concurrent.ConcurrentHashMap

object AeonLabStorage {
    private const val TAG = "[AeonLab:Storage]"
    private const val DIR_NAME = "AeonLab"
    private const val LEGACY_DIR_NAME = "Hchat"
    private const val FILE_PREFIX = "AeonLab_"
    private const val LEGACY_FILE_PREFIX = "Hchat_"
    private const val KEY_PREFIX = "aeonlab_"
    private const val LEGACY_KEY_PREFIX = "aeonlab_"
    private const val KEY_MIGRATE_MARK = ".keys_migrated_"
    private val MIGRATED_SUBDIRS = setOf("crash", "script_plugin_locks", "message_bubbles")
    private val cache = ConcurrentHashMap<String, SharedPreferences>()

    @JvmStatic
    fun preferences(context: Context, name: String): SharedPreferences {
        val appContext = context.applicationContext ?: context
        val cacheKey = cacheKey(appContext, name)
        cache[cacheKey]?.let { return it }

        val created = create(appContext, name)
        val existing = cache.putIfAbsent(cacheKey, created)
        return existing ?: created
    }

    @JvmStatic
    fun reopenIfFilesMissing(context: Context, name: String): SharedPreferences {
        val appContext = context.applicationContext ?: context
        val dir = storageDir(appContext)
        if (hasBackingFile(dir, name)) return preferences(appContext, name)

        val cacheKey = cacheKey(appContext, name)
        synchronized(cache) {
            if (hasBackingFile(dir, name)) return preferences(appContext, name)
            val stale = cache.remove(cacheKey)
            if (stale is FastKV) {
                runCatching { stale.close() }
                    .onFailure { HLog.e("$TAG 关闭失效配置失败: $name ${it.message}", it) }
            }
            return create(appContext, name).also { cache[cacheKey] = it }
        }
    }

    private fun create(context: Context, name: String): SharedPreferences {
        val dir = storageDir(context)
        val prefs = FastKV.Builder(dir.absolutePath, name).build()
        migrateKeys(dir, name, prefs)
        return prefs
    }

    /**
     * 一次性把旧版 `aeonlab_` 前缀配置键改写为 `aeonlab_`。
     * 值复制成功后删旧键；全部成功才写标记，失败则下次启动重试。
     */
    private fun migrateKeys(dir: File, name: String, prefs: SharedPreferences) {
        val mark = File(dir, "$KEY_MIGRATE_MARK$name")
        if (mark.exists()) return

        var moved = 0
        val result = runCatching {
            val all = prefs.all
            if (all.isEmpty()) return@runCatching true
            val editor = prefs.edit()
            for ((key, value) in all) {
                if (!key.startsWith(LEGACY_KEY_PREFIX)) continue
                val target = KEY_PREFIX + key.removePrefix(LEGACY_KEY_PREFIX)
                if (!all.containsKey(target)) putValue(editor, target, value)
                editor.remove(key)
                moved++
            }
            editor.commit()
        }

        if (result.getOrDefault(false)) {
            if (moved > 0) HLog.i("$TAG 配置键已迁移: $name 共 $moved 项")
            runCatching { mark.createNewFile() }
        } else {
            HLog.e("$TAG 配置键迁移失败，下次启动重试: $name")
        }
    }

    private fun putValue(editor: SharedPreferences.Editor, key: String, value: Any?) {
        when (value) {
            is Boolean -> editor.putBoolean(key, value)
            is Int -> editor.putInt(key, value)
            is Long -> editor.putLong(key, value)
            is Float -> editor.putFloat(key, value)
            is String -> editor.putString(key, value)
            is Set<*> -> {
                @Suppress("UNCHECKED_CAST")
                editor.putStringSet(key, value as Set<String>)
            }
            else -> Unit
        }
    }

    private fun cacheKey(context: Context, name: String): String = "${context.packageName}:$name"

    private fun hasBackingFile(dir: File, name: String): Boolean {
        return FAST_KV_SUFFIXES.any { suffix -> File(dir, name + suffix).isFile }
    }

    @JvmStatic
    fun storageDir(context: Context): File {
        val base = try {
            context.dataDir
        } catch (_: Throwable) {
            context.filesDir
        }
        val preferred = File(base, DIR_NAME)
        if (hasAnyChild(preferred)) return preferred

        // 旧版目录存在 → 一次性迁移（复制不移动，失败可回退）
        val legacy = File(base, LEGACY_DIR_NAME)
        if (hasAnyChild(legacy)) {
            val migrated = runCatching { migrateDirectory(legacy, preferred) }.getOrDefault(false)
            if (migrated) {
                HLog.i("$TAG 配置目录已迁移: ${legacy.name} -> ${preferred.name}")
                return preferred
            }
            HLog.e("$TAG 配置迁移未完成，暂用旧目录: ${legacy.absolutePath}")
            return legacy
        }

        if (!preferred.isDirectory && !preferred.mkdirs()) {
            HLog.e("$TAG 创建目录失败: ${preferred.absolutePath}")
        }
        return preferred
    }

    private fun hasAnyChild(dir: File): Boolean = dir.listFiles()?.isNotEmpty() == true

    /** 递归复制旧配置目录，文件名前缀 Hchat_ 改写为 AeonLab_；全部成功才返回 true。 */
    private fun migrateDirectory(from: File, to: File): Boolean {
        val children = from.listFiles() ?: return false
        if (!to.isDirectory && !to.mkdirs()) return false
        var ok = true
        for (child in children) {
            val targetName = if (child.name.startsWith(LEGACY_FILE_PREFIX)) {
                FILE_PREFIX + child.name.removePrefix(LEGACY_FILE_PREFIX)
            } else {
                child.name
            }
            val target = File(to, targetName)
            if (child.isDirectory) {
                // 只迁移配置类子目录；proot 环境（proot_env）等大目录必须跳过
                if (!MIGRATED_SUBDIRS.contains(child.name)) continue
                if (!migrateDirectory(child, target)) ok = false
            } else if (!copyFile(child, target)) {
                ok = false
            }
        }
        return ok
    }

    private fun copyFile(source: File, target: File): Boolean {
        if (target.isFile && target.length() == source.length()) return true
        return runCatching {
            source.inputStream().use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            target.isFile && target.length() == source.length()
        }.getOrDefault(false)
    }

    private val FAST_KV_SUFFIXES = arrayOf(".kva", ".kvb", ".kvc")
}
