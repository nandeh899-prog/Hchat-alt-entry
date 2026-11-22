package com.aeonlab.toolkit.hooks.items.theme

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * 主题库：把导入的主题文件原样保存在
 * `<filesDir>/AeonLab/theme-library/<id>/`（theme.json 或 theme.zip + state.json + name.txt），
 * 支持多份、重命名、应用、删除。应用时复用 ThemeSpec 的导入通道，所以行为与「导入主题」完全一致。
 */
object ThemeLibrary {

    data class Entry(
        val id: String,
        val name: String,
        val savedAt: Long,
        val applied: Boolean,
        val isArchive: Boolean
    )

    private const val PREF = "AeonLab_theme_library"
    private const val KEY_APPLIED = "applied_id"
    private const val NAME_FILE = "name.txt"
    private const val JSON_FILE = "theme.json"
    private const val ZIP_FILE = "theme.zip"
    private const val STATE_FILE = "state.json"
    private const val OVERRIDE_FILE = "override.json"

    private fun root(context: Context): File = File(File(context.filesDir, "AeonLab"), "theme-library")

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun appliedId(context: Context): String =
        prefs(context).getString(KEY_APPLIED, "").orEmpty()

    private fun setApplied(context: Context, id: String) {
        prefs(context).edit().putString(KEY_APPLIED, id).apply()
    }

    private fun readState(dir: File): JSONObject =
        runCatching { JSONObject(File(dir, STATE_FILE).readText()) }.getOrDefault(JSONObject())

    fun list(context: Context): List<Entry> {
        val dir = root(context)
        if (!dir.isDirectory) return emptyList()
        val applied = appliedId(context)
        return dir.listFiles().orEmpty()
            .filter { it.isDirectory && (File(it, JSON_FILE).isFile || File(it, ZIP_FILE).isFile) }
            .map { d ->
                val state = readState(d)
                Entry(
                    id = d.name,
                    name = displayName(context, d.name),
                    savedAt = state.optLong("savedAt", 0L),
                    applied = d.name == applied,
                    isArchive = File(d, ZIP_FILE).isFile
                )
            }
            .sortedByDescending { it.savedAt }
    }

    fun displayName(context: Context, id: String): String {
        val dir = File(root(context), id)
        val custom = File(dir, NAME_FILE)
        if (custom.isFile) {
            val text = runCatching { custom.readText() }.getOrDefault("").trim()
            if (text.isNotEmpty()) return text
        }
        val json = File(dir, JSON_FILE)
        if (json.isFile) {
            val n = runCatching { JSONObject(json.readText()).optString("name") }.getOrDefault("").trim()
            if (n.isNotEmpty()) return n
        }
        return id
    }

    private fun uniqueDir(context: Context, base: String): File {
        val safe = base.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifBlank { "主题" }.take(40)
        val dir = root(context)
        dir.mkdirs()
        var candidate = File(dir, safe)
        var index = 2
        candidate = File(dir, safe)
        while (candidate.exists()) {
            candidate = File(dir, "$safe ($index)")
            index++
        }
        return candidate
    }

    /** 导入时不应用，只入库；默认名取原始文件名（去扩展名）。 */
    fun import(context: Context, bytes: ByteArray, sourceName: String): Result<Entry> = runCatching {
        require(bytes.isNotEmpty()) { "文件是空的" }
        val isZip = bytes.size >= 2 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte()
        val rawName = sourceName.substringAfterLast('/').trim()
        var baseName = rawName.substringBeforeLast('.').trim()
        var parsedName = ""
        if (!isZip) {
            val text = String(bytes, Charsets.UTF_8).trim()
            require(text.startsWith("{")) { "主题文件不是 JSON，也不是 zip 主题包" }
            parsedName = runCatching { JSONObject(text).optString("name") }.getOrDefault("").trim()
        }
        if (baseName.isEmpty()) baseName = parsedName
        if (baseName.isEmpty()) baseName = "主题"
        val dir = uniqueDir(context, baseName)
        require(dir.mkdirs() || dir.isDirectory) { "无法创建主题库目录" }
        if (isZip) File(dir, ZIP_FILE).writeBytes(bytes) else File(dir, JSON_FILE).writeText(String(bytes, Charsets.UTF_8))
        File(dir, STATE_FILE).writeText(JSONObject().put("savedAt", System.currentTimeMillis()).toString())
        Entry(dir.name, baseName, System.currentTimeMillis(), false, isZip)
    }

    /**
     * 把当前设置回写到「当前应用」的那份主题里。
     * 这样在主题库条目上改过的标题栏颜色等参数不会在切换主题后又退回导入时的原值。
     */
    fun syncApplied(context: Context) {
        val id = appliedId(context)
        if (id.isEmpty()) return
        val dir = File(root(context), id)
        if (!dir.isDirectory) return
        val json = runCatching { ThemeSpec.exportJson(context) }.getOrNull() ?: return
        if (json.isBlank()) return
        runCatching { File(dir, OVERRIDE_FILE).writeText(json) }
    }

    /** 应用一份库里的主题：优先用回写过的参数，其次用导入时的原文件。 */
    fun apply(context: Context, id: String): ThemeSpec.ImportOutcome {
        val dir = File(root(context), id)
        val name = displayName(context, id)
        val override = File(dir, OVERRIDE_FILE)
        val outcome: ThemeSpec.ImportOutcome = when {
            override.isFile -> {
                val text = runCatching { override.readText() }.getOrNull()
                if (text.isNullOrBlank()) ThemeSpec.ImportOutcome.Failure("读取主题参数失败")
                else ThemeSpec.importJson(context, text, name)
            }
            File(dir, ZIP_FILE).isFile -> {
                val bytes = runCatching { File(dir, ZIP_FILE).readBytes() }.getOrNull()
                if (bytes == null) ThemeSpec.ImportOutcome.Failure("读取主题包失败")
                else ThemeSpec.importArchive(context, bytes, name)
            }
            else -> {
                val text = runCatching { File(dir, JSON_FILE).readText() }.getOrNull()
                if (text == null) ThemeSpec.ImportOutcome.Failure("读取主题文件失败")
                else ThemeSpec.importJson(context, text, name)
            }
        }
        if (outcome is ThemeSpec.ImportOutcome.Success) setApplied(context, id)
        return outcome
    }

    fun rename(context: Context, id: String, newName: String): Boolean {
        val dir = File(root(context), id)
        if (!dir.isDirectory) return false
        val name = newName.trim().take(40)
        return runCatching {
            if (name.isEmpty()) File(dir, NAME_FILE).delete() else File(dir, NAME_FILE).writeText(name)
            true
        }.getOrDefault(false)
    }

    fun delete(context: Context, id: String): Boolean {
        val dir = File(root(context), id)
        if (!dir.isDirectory) return false
        val ok = dir.deleteRecursively()
        if (appliedId(context) == id) setApplied(context, "")
        return ok
    }
}
