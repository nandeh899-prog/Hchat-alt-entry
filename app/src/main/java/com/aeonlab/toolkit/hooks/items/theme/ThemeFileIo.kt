package com.aeonlab.toolkit.hooks.items.theme

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
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
 *
 * 这里维护的 `pending` 就是一个「当前有一个选择器在跑」的标志位。它只要有一条路径忘了复位，
 * 之后点导入就会一直弹「上一个导入还没结束」——5.5.53 真机就是这样卡死的，所以现在：
 * 1. 结果处理的每个分支都从 [finish] 收尾，异常也走 finally 里同一条路径；
 * 2. 每次登记都排一个 [PICKER_TIMEOUT_MS] 的超时兜底，到点无论之前发生了什么都会复位。
 */
object ThemeFileIo {
    private const val TAG = "[AeonLab:ThemeFile]"
    private const val REQ_IMAGE = 0x7A20
    private const val REQ_IMPORT = 0x7A21
    private const val REQ_EXPORT = 0x7A22
    private const val MAX_IMPORT_BYTES = 96 * 1024 * 1024

    /** 一次选择/导入最多占用标志位多久，超过就复位，避免异常路径把导入永久卡死。 */
    private const val PICKER_TIMEOUT_MS = 5 * 60 * 1000L

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
    private val timeoutHandler = Handler(Looper.getMainLooper())

    @JvmStatic
    fun pickImage(activity: Activity, slot: String, onResult: (String?, String?) -> Unit) {
        if (pending.containsKey(REQ_IMAGE)) {
            onResult(null, "上一个选图还没结束，请稍后再试")
            return
        }
        val request = Pending(WeakReference(activity), slot = slot, onImage = onResult)
        pending[REQ_IMAGE] = request
        scheduleTimeout(REQ_IMAGE, request)
        hookActivity(activity)
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { activity.startActivityForResult(intent, REQ_IMAGE) }
            .onFailure {
                if (pending.remove(REQ_IMAGE, request)) {
                    onResult(null, "无法打开系统选图界面：${it.message ?: it.javaClass.simpleName}")
                }
            }
    }

    @JvmStatic
    fun importThemeFile(activity: Activity, onResult: (ByteArray?, String?, String?) -> Unit) {
        if (pending.containsKey(REQ_IMPORT)) {
            onResult(null, null, "上一个导入还没结束，请稍后再试")
            return
        }
        val request = Pending(WeakReference(activity), onImported = onResult)
        pending[REQ_IMPORT] = request
        scheduleTimeout(REQ_IMPORT, request)
        hookActivity(activity)
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { activity.startActivityForResult(intent, REQ_IMPORT) }
            .onFailure {
                if (pending.remove(REQ_IMPORT, request)) {
                    onResult(null, null, "无法打开系统文件选择器：${it.message ?: it.javaClass.simpleName}")
                }
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
        val request = Pending(
            WeakReference(activity),
            fileName = fileName,
            payload = content.toByteArray(Charsets.UTF_8),
            onExported = onResult
        )
        pending[REQ_EXPORT] = request
        scheduleTimeout(REQ_EXPORT, request)
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
                if (pending.remove(REQ_EXPORT, request)) {
                    onResult("无法打开系统保存界面：${it.message ?: it.javaClass.simpleName}")
                }
            }
    }

    /** 超时兜底：到点还挂着就直接复位标志并回调失败，绝不让它永久占位。 */
    private fun scheduleTimeout(requestCode: Int, request: Pending) {
        timeoutHandler.postDelayed({
            if (!pending.remove(requestCode, request)) return@postDelayed
            HLog.e("$TAG 选择器 ${PICKER_TIMEOUT_MS / 1000} 秒没有结果（code=$requestCode），已复位导入标志")
            notifyFailure(requestCode, request, "等待系统选择器超时，已复位导入状态，请重新点一次导入")
        }, PICKER_TIMEOUT_MS)
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
                    // 整段包一层：任何异常都要能把标志位收掉，否则一次异常就是永久卡死。
                    runCatching { handleResult(requestCode, request, param) }
                        .onFailure { error ->
                            HLog.e("$TAG 处理选择结果异常（code=$requestCode）", error)
                            if (pending.remove(requestCode, request)) {
                                notifyFailure(
                                    requestCode,
                                    request,
                                    "处理失败：${error.message ?: error.javaClass.simpleName}"
                                )
                            }
                        }
                }
            })
        }.onFailure {
            hookedClasses.remove(clazz)
            HLog.e("$TAG 挂载 onActivityResult 失败: ${it.javaClass.name}", it)
        }
    }

    private fun handleResult(requestCode: Int, request: Pending, param: XC_MethodHook.MethodHookParam) {
        val activity = request.activity.get()
        if (activity == null) {
            // Activity 已经回收，没人能接这个结果了，直接复位，别留着一个永远等不到的标志位。
            if (pending.remove(requestCode, request)) {
                HLog.e("$TAG 选择结果回来时页面已回收（code=$requestCode），已复位标志")
            }
            return
        }
        if (param.thisObject !== activity) {
            // 不是发起选择的那个页面：结果可能来自别的 Activity，标志位留给真正的回调或超时兜底。
            HLog.i("$TAG 选择结果来自 ${param.thisObject?.javaClass?.simpleName ?: "?"}，非发起页面，交给超时兜底")
            return
        }
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

    private fun handle(
        activity: Activity,
        appContext: android.content.Context,
        requestCode: Int,
        request: Pending,
        uri: Uri
    ) {
        // 先算出「要回调什么」，再统一在 deliver 里复位标志；中间任何一步抛异常都退化成一条失败回调。
        val callback: () -> Unit = runCatching {
            when (requestCode) {
                REQ_IMAGE -> {
                    val path = ThemeImageStore.saveFromUri(appContext, request.slot, uri)
                    val notify: () -> Unit = {
                        if (path == null) {
                            request.onImage?.invoke(null, "图片读取或解码失败，换一张试试")
                        } else {
                            request.onImage?.invoke(path, null)
                        }
                    }
                    notify
                }

                REQ_IMPORT -> {
                    val bytes = runCatching {
                        appContext.contentResolver.openInputStream(uri)?.use { input ->
                            val data = input.readBytes()
                            if (data.size > MAX_IMPORT_BYTES) null else data
                        }
                    }.getOrNull()
                    val name = runCatching { queryDisplayName(appContext, uri) }
                        .getOrDefault(uri.lastPathSegment.orEmpty())
                    val notify: () -> Unit = {
                        if (bytes == null) {
                            request.onImported?.invoke(null, name, "读取主题文件失败（文件过大或无法访问）")
                        } else {
                            request.onImported?.invoke(bytes, name, null)
                        }
                    }
                    notify
                }

                REQ_EXPORT -> {
                    val payload = request.payload ?: ByteArray(0)
                    val error = runCatching {
                        appContext.contentResolver.openOutputStream(uri)?.use { output ->
                            output.write(payload)
                            output.flush()
                        } ?: error("无法写入目标文件")
                    }.exceptionOrNull()
                    val notify: () -> Unit = {
                        request.onExported?.invoke(
                            error?.let { "导出失败：${it.message ?: it.javaClass.simpleName}" }
                        )
                    }
                    notify
                }

                else -> {
                    val notify: () -> Unit = {}
                    notify
                }
            }
        }.getOrElse { error ->
            HLog.e("$TAG 处理选择结果失败（code=$requestCode）", error)
            val notify: () -> Unit = {
                notifyFailure(requestCode, request, "处理失败：${error.message ?: error.javaClass.simpleName}")
            }
            notify
        }
        deliver(activity, requestCode, request, callback)
    }

    private fun deliver(
        activity: Activity,
        requestCode: Int,
        request: Pending,
        block: () -> Unit
    ) {
        runCatching {
            activity.runOnUiThread {
                if (!pending.remove(requestCode, request)) return@runOnUiThread
                runCatching(block).onFailure { HLog.e("$TAG 回调处理失败: ${it.message}", it) }
            }
        }.onFailure { error ->
            // runOnUiThread 在 Activity 销毁后可能直接抛，标志位同样必须收掉。
            HLog.e("$TAG 回调投递失败（code=$requestCode）", error)
            if (pending.remove(requestCode, request)) {
                notifyFailure(requestCode, request, "回调投递失败：${error.message ?: error.javaClass.simpleName}")
            }
        }
    }

    private fun notifyFailure(requestCode: Int, request: Pending, message: String) {
        when (requestCode) {
            REQ_IMAGE -> request.onImage?.invoke(null, message)
            REQ_IMPORT -> request.onImported?.invoke(null, null, message)
            REQ_EXPORT -> request.onExported?.invoke(message)
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
