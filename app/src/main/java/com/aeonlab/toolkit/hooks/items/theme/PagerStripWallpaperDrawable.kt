package com.aeonlab.toolkit.hooks.items.theme

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.view.ViewGroup

/**
 * 617：随 ViewPager 划动连续平移的壁纸底衬。
 *
 * 616 的做法是「每层各画一张窗口锚定图」，切页时靠一层层重录，落定那一刻才换成下页的图，
 * 于是肉眼看到的是「图突然换成另一张、跳一下」（13:06 用户的结论）。
 * 这里是反过来：**一张 canvas、按页分格**。第 i 格画第 i 页的图，整层按 host.scrollX 平移，
 * 划到哪儿就露出哪一格的哪一段，落定前后是同一份 Bitmap、同一套 cover 矩阵，不存在换图。
 *
 * 每格的 cover 仍以**窗口**（pager 自身）宽高为基准，不按格子重算，所以各页之间的缩放一致。
 */
class PagerStripWallpaperDrawable(private val host: ViewGroup) : Drawable() {

    private companion object {
        /** 676：未开启背景的页格画这个不透明原生底，挡住窗口壁纸层的透出。 */
        const val OFF_FILL_GREY = 0xffededed.toInt()
    }

    /**
     * 1029：空页格的填充色。
     *
     * 背景：676 起空页格一律画 #EDEDED，用来挡住窗口壁纸透出。副作用是「关掉页面背景」之后，
     * 通讯录这类列表最后一行下面那一段（内容盖不住的区域）就露出一条 #EDEDED 灰带。
     * 用户 710d 时代的预期是「关背景时窗口底改白」，所以这里改成可调：
     * 主背景开关关闭 → 白色；只关单页（其它页有壁纸）→ 仍用原生灰。
     */
    @Volatile
    private var offFillColor = OFF_FILL_GREY

    fun setOffFillColor(color: Int) {
        if (offFillColor == color) return
        offFillColor = color
        invalidateSelf()
    }

    private val slots = arrayOfNulls<Bitmap>(8)
    private val overlays = IntArray(8)
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    private val matrix = Matrix()

    fun setSlot(index: Int, bitmap: Bitmap?, overlay: Int = 0) {
        if (index !in slots.indices) return
        slots[index] = bitmap
        overlays[index] = overlay
        invalidateSelf()
    }

    fun slotBitmap(index: Int): Bitmap? = if (index in slots.indices) slots[index] else null

    /** 676：叠加页在前台时把壁纸格全部清掉（连着 fill 一起），离开后再由主题重铺。 */
    fun clearSlots() {
        for (i in slots.indices) slots[i] = null
        invalidateSelf()
    }

    fun filledCount(): Int = slots.count { it != null }

    override fun draw(canvas: Canvas) {
        val w = host.width
        val h = host.height
        if (w <= 0 || h <= 0) return
        val sx = host.scrollX.toFloat()
        for (i in slots.indices) {
            val cellLeft = i * w - sx
            if (cellLeft + w <= 0f || cellLeft >= w.toFloat()) continue
            val bmp = slots[i]
            if (bmp == null || bmp.width <= 0 || bmp.height <= 0) {
                // 676：这一页没设图（该页开关关闭）→ 画不透明原生底。
                // 之前空槽不画，页面上没被内容盖住的地方就透出窗口那层壁纸（用户 11-23 08:34 截图：
                // 只开主页时通讯录/发现底部仍见壁纸）。这里只动我们自己的绘制层，不写宿主属性。
                paint.alpha = 255
                paint.color = offFillColor
                canvas.drawRect(cellLeft, 0f, cellLeft + w, h.toFloat(), paint)
                continue
            }
            canvas.save()
            canvas.clipRect(cellLeft, 0f, cellLeft + w, h.toFloat())
            val scale = maxOf(w.toFloat() / bmp.width.toFloat(), h.toFloat() / bmp.height.toFloat())
            matrix.reset()
            matrix.setScale(scale, scale)
            matrix.postTranslate((w - bmp.width * scale) / 2f + cellLeft, (h - bmp.height * scale) / 2f)
            canvas.drawBitmap(bmp, matrix, paint)
            val ov = overlays[i]
            if (ov != 0) {
                val a = paint.alpha
                paint.alpha = 255
                paint.color = ov
                canvas.drawRect(cellLeft, 0f, cellLeft + w, h.toFloat(), paint)
                paint.alpha = a
            }
            canvas.restore()
        }
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
