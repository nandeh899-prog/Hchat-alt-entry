package com.aeonlab.toolkit.hooks.api.ui

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable

/**
 * 首页顶部入口的自绘图标：一个圆环 + 环内三个点。
 *
 * 为什么要自绘、为什么加圆环：[微x] 那个入口是裸的三点（微信原生 overflow）。
 * 我们的入口如果也画裸三点，用户根本分不清哪一个是微信的、哪一个是 AeonLab 的。
 * 所以这里用「外环 + 三点」做区分，并且整张图按 square 居中缩放，
 * 交给宿主 ActionBar 自己排版，就能和微x 的入口自动错开，不需要算坐标。
 */
class AeonLabMenuDotDrawable(private val iconColor: Int = Color.WHITE) : Drawable() {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun draw(canvas: Canvas) {
        val bounds = bounds
        val size = minOf(bounds.width(), bounds.height()).toFloat()
        if (size <= 0f) return
        val centerX = bounds.exactCenterX()
        val centerY = bounds.exactCenterY()
        paint.color = iconColor
        // 外环：线宽按尺寸比例，避免不同 dpi / ActionBar 高度下粗细跳变
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = maxOf(2f, size * 0.055f)
        canvas.drawCircle(centerX, centerY, size * 0.38f, paint)
        // 三个点：横向等距，与微x 的裸三点在视觉上明确区分
        paint.style = Paint.Style.FILL
        val dotRadius = size * 0.075f
        val gap = size * 0.17f
        canvas.drawCircle(centerX - gap, centerY, dotRadius, paint)
        canvas.drawCircle(centerX, centerY, dotRadius, paint)
        canvas.drawCircle(centerX + gap, centerY, dotRadius, paint)
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
