package com.aeonlab.toolkit.hooks.items.theme

import android.app.Activity
import android.content.Intent
import android.net.Uri
import com.aeonlab.toolkit.utils.HLog
import com.aeonlab.toolkit.xposed.compat.XC_MethodHook
import com.aeonlab.toolkit.xposed.compat.XposedBridge
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 主题的图片选择、主题文件导入导出。
 *
 * 走系统文档选择器（ACTION_OPEN_DOCUMENT / ACTION_CREATE_DOCUMENT），
 * 结果通过 Hook onActivityResult 取回——模块跑在微信进程里，没有自己的 Activity 可挂 registerForActivityResult。
 */
object ThemeFileIo {
    private const val TAG = "[AeonLab:ThemeFile]"
    private const val REQ_IMAGE = 0x7A20
    private const val REQ_IMPORT = 0x7A21
    private const val REQ_EXPORT = 0x7A22
    private const val MAX_IMPORT_BYTES = 96 * 1024 * 1024

    private data class Pending(
        val activity: WeakReference<Activity>,
        val slot: String = "",
        val fileName: String = "",
        val payload: ByteArray? = null,
        val onImage: ((String?, String?) -> Unit)? = null,
        val onImported: ((ByteArray?, String?, String?) -> Unit)? = null,
        val onExported: ((String?) -> Unit)? = null,
        val processing: AtomicBoolean = AtomicBoolean(false)
    )

    private val pending = ConcurrentHashMap<Int, Pending>()
    private val hookedClasses = ConcurrentHashMap.newKeySet<Class<*>>()

    @JvmStatic
    fun pickImage(activity: Activity, slot: String, onResult: (String?, String?) -> Unit) {
        if (pending.containsKey(REQ_IMAGE)) {
            onResult(null, "上一个选图还没结束，请稍后再试")
            return
        }
        pending[REQ_IMAGE] = Pending(WeakReference(activity), slot = slot, onImage = onResult)
        hookActivity(activity)
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { activity.startActivityForResult(intent, REQ_IMAGE) }
            .onFailure {
                pending.remove(REQ_IMAGE)
                onResult(null, "无法打开系统选图界面：${it.message ?: it.javaClass.simpleName}")
            }
    }

    @JvmStatic
    fun importThemeFile(activity: Activity, onResult: (ByteArray?, String?, String?) -> Unit) {
        if (pending.containsKey(REQ_IMPORT)) {
            onResult(null, null, "上一个导入还没结束，请稍后再试")
            return
        }
        pending[REQ_IMPORT] = Pending(WeakReference(activity), onImported = onResult)
        hookActivity(activity)
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { activity.startActivityForResult(intent, REQ_IMPORT) }
            .onFailure {
                pending.remove(REQ_IMPORT)
                onResult(null, null, "无法打开系统文件选择器：${it.message ?: it.javaClass.simpleName}")
            }
    }

    @JvmStatic
    fun exportThemeFile(
        activity: Activity,
        fileName: String,
        content: String,
        onResult: (String?) -> Unit
    ) {
        if (pending.containsKey(REQ_EXPORT)) {
            onResult("上一个导出还没结束，请稍后再试")
            return
        }
        pending[REQ_EXPORT] = Pending(
            WeakReference(activity),
            fileName = fileName,
            payload = content.toByteArray(Charsets.UTF_8),
            onExported = onResult
        )
        hookActivity(activity)
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = ThemeSpec.MIME_JSON
            putExtra(Intent.EXTRA_TITLE, fileName)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { activity.startActivityForResult(intent, REQ_EXPORT) }
            .onFailure {
                pending.remove(REQ_EXPORT)
                onResult("无法打开系统保存界面：${it.message ?: it.javaClass.simpleName}")
            }
    }

    private fun hookActivity(activity: Activity) {
        var current: Class<*>? = activity.javaClass
        while (current != null && Activity::class.java.isAssignableFrom(current)) {
            hookResult(current)
            current = current.superclass
        }
        hookResult(Activity::class.java)
    }

    @Synchronized
    private fun hookResult(clazz: Class<*>) {
        if (!hookedClasses.add(clazz)) return
        runCatching {
            XposedBridge.hookAllMethods(clazz, "onActivityResult", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val requestCode = param.args.getOrNull(0) as? Int ?: return
                    val request = pending[requestCode] ?: return
                    val activity = request.activity.get()
                    if (activity == null) {
                        pending.remove(requestCode, request)
                        return
                    }
                    if (param.thisObject !== activity) return
                    val resultCode = param.args.getOrNull(1) as? Int ?: return
                    val data = param.args.getOrNull(2) as? Intent
                    val uri = data?.data
                    if (resultCode != Activity.RESULT_OK || uri == null) {
                        if (pending.remove(requestCode, request)) {
                            when (requestCode) {
                                REQ_IMAGE -> request.onImage?.invoke(null, "已取消选择图片")
                                REQ_IMPORT -> request.onImported?.invoke(null, null, "已取消导入")
                                REQ_EXPORT -> request.onExported?.invoke("已取消导出")
                            }
                        }
                        return
                    }
                    if (!request.processing.compareAndSet(false, true)) return
                    runCatching {
                        activity.contentResolver.takePersistableUriPermission(
                            uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION
                        )
                    }
                    val appContext = activity.applicationContext ?: activity
                    Thread({
                        handle(activity, appContext, requestCode, request, uri)
                    }, "AeonLab-ThemeFile").start()
                }
            })
        }.onFailure {
            hookedClasses.remove(clazz)
            HLog.e("$TAG 挂载 onActivityResult 失败: ${it.javaClass.name}", it)
        }
    }

    private fun handle(
        activity: Activity,
        appContext: android.content.Context,
        requestCode: Int,
        request: Pending,
        uri: Uri
    ) {
        when (requestCode) {
            REQ_IMAGE -> {
                val path = ThemeImageStore.saveFromUri(appContext, request.slot, uri)
                deliver(activity, requestCode, request) {
                    if (path == null) {
                        request.onImage?.invoke(null, "图片读取或解码失败，换一张试试")
                    } else {
                        request.onImage?.invoke(path, null)
                    }
                }
            }

            REQ_IMPORT -> {
                val bytes = runCatching {
                    appContext.contentResolver.openInputStream(uri)?.use { input ->
                        val data = input.readBytes()
                        if (data.size > MAX_IMPORT_BYTES) null else data
                    }
                }.getOrNull()
                val name = queryDisplayName(appContext, uri)
                deliver(activity, requestCode, request) {
                    if (bytes == null) {
                        request.onImported?.invoke(null, name, "读取主题文件失败（文件过大或无法访问）")
                    } else {
                        request.onImported?.invoke(bytes, name, null)
                    }
                }
            }

            REQ_EXPORT -> {
                val payload = request.payload ?: ByteArray(0)
                val error = runCatching {
                    appContext.contentResolver.openOutputStream(uri)?.use { output ->
                        output.write(payload)
                        output.flush()
                    } ?: error("无法写入目标文件")
                }.exceptionOrNull()
                deliver(activity, requestCode, request) {
                    request.onExported?.invoke(
                        error?.let { "导出失败：${it.message ?: it.javaClass.simpleName}" }
                    )
                }
            }
        }
    }

    private fun deliver(
        activity: Activity,
        requestCode: Int,
        request: Pending,
        block: () -> Unit
    ) {
        activity.runOnUiThread {
            if (!pending.remove(requestCode, request)) return@runOnUiThread
            runCatching(block).onFailure { HLog.e("$TAG 回调处理失败: ${it.message}", it) }
        }
    }

    private fun queryDisplayName(context: android.content.Context, uri: Uri): String {
        val cursor = runCatching {
            context.contentResolver.query(uri, null, null, null, null)
        }.getOrNull() ?: return uri.lastPathSegment.orEmpty()
        return cursor.use {
            val index = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && it.moveToFirst()) it.getString(index).orEmpty() else uri.lastPathSegment.orEmpty()
        }
    }
}
