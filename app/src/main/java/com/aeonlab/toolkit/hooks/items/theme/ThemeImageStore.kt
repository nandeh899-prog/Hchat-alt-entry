package com.aeonlab.toolkit.hooks.items.theme

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.aeonlab.toolkit.utils.HLog
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlin.math.max

/**
 * 主题图片落地：底栏图标与页面背景都存成本地 PNG 文件，
 * 设置里只保存绝对路径，导出时再读成 base64 写进主题 JSON。
 */
object ThemeImageStore {
    private const val TAG = "[AeonLab:ThemeImage]"
    private const val MAX_EDGE_PX = 1600
    private const val SLOT_DIR_NAME = "theme"

    const val SLOT_SPLASH = "background-splash"
    const val SLOT_HOME = "background-home"
    const val SLOT_CONTACTS = "background-contacts"
    const val SLOT_DISCOVER = "background-discover"
    const val SLOT_ME = "background-me"

    @JvmStatic
    fun slotForTabIcon(index: Int): String = "tab-icon-$index"

    @JvmStatic
    fun saveFromUri(context: Context, slot: String, uri: Uri): String? {
        val bitmap = runCatching {
            context.contentResolver.openInputStream(uri)?.use { decodeScaled(it) }
        }.getOrNull()
        if (bitmap == null) {
            HLog.e("$TAG 读取图片失败: slot=$slot uri=$uri")
            return null
        }
        return saveBitmap(context, slot, bitmap)
    }

    @JvmStatic
    fun saveFromBytes(context: Context, slot: String, bytes: ByteArray): String? {
        val bitmap = runCatching { decodeScaled(bytes) }.getOrNull()
        if (bitmap == null) {
            HLog.e("$TAG 解码主题图片失败: slot=$slot bytes=${bytes.size}")
            return null
        }
        return saveBitmap(context, slot, bitmap)
    }

    @JvmStatic
    fun saveBitmap(context: Context, slot: String, source: Bitmap): String? {
        val directory = directory(context)
        if (!directory.isDirectory && !directory.mkdirs()) {
            HLog.e("$TAG 创建主题图片目录失败: ${directory.absolutePath}")
            source.recycle()
            return null
        }
        val scaled = scale(source)
        val target = File(directory, "${sanitize(slot)}-${System.currentTimeMillis()}-${UUID.randomUUID()}.png")
        return try {
            FileOutputStream(target).use { output ->
                check(scaled.compress(Bitmap.CompressFormat.PNG, 100, output))
                output.flush()
            }
            if (target.isFile && target.length() > 0L) target.absolutePath else null
        } catch (error: Throwable) {
            HLog.e("$TAG 写入主题图片失败: ${target.absolutePath}", error)
            target.delete()
            null
        } finally {
            if (scaled !== source) scaled.recycle()
            source.recycle()
        }
    }

    @JvmStatic
    fun loadBitmap(path: String?): Bitmap? {
        val value = path?.trim().orEmpty()
        if (value.isEmpty()) return null
        val file = File(value)
        if (!file.isFile || file.length() <= 0L) return null
        return runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull()
    }

    @JvmStatic
    fun readBytes(path: String?): ByteArray? {
        val value = path?.trim().orEmpty()
        if (value.isEmpty()) return null
        val file = File(value)
        if (!file.isFile || file.length() <= 0L) return null
        return runCatching { file.readBytes() }.getOrNull()
    }

    @JvmStatic
    fun delete(context: Context, path: String?) {
        val value = path?.trim().orEmpty()
        if (value.isEmpty()) return
        val root = runCatching { directory(context).canonicalFile }.getOrNull() ?: return
        val target = runCatching { File(value).canonicalFile }.getOrNull() ?: return
        if (target.parentFile != root) return
        runCatching { if (target.exists()) target.delete() }
    }

    @JvmStatic
    fun directory(context: Context): File =
        File(File(context.filesDir, "AeonLab"), SLOT_DIR_NAME)

    private fun decodeScaled(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }

    private fun decodeScaled(input: java.io.InputStream): Bitmap? {
        val bytes = input.readBytes()
        return decodeScaled(bytes)
    }

    private fun sampleSize(width: Int, height: Int): Int {
        var sample = 1
        var longest = max(width, height)
        while (longest / sample > MAX_EDGE_PX * 2) {
            sample *= 2
            longest = max(width, height)
        }
        return sample
    }

    private fun scale(source: Bitmap): Bitmap {
        val longest = max(source.width, source.height)
        if (longest <= MAX_EDGE_PX) return source
        val ratio = MAX_EDGE_PX.toFloat() / longest.toFloat()
        val width = (source.width * ratio).toInt().coerceAtLeast(1)
        val height = (source.height * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(source, width, height, true)
    }

    private fun sanitize(slot: String): String =
        slot.trim().replace(Regex("[^A-Za-z0-9_-]"), "_").ifEmpty { "image" }
}
