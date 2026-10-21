package com.aeonlab.toolkit.hooks.api.ui;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

/** Shared AeonLab mark (ring + letter A) used by injected menus and the floating entry. */
public final class AeonLabAgentIconDrawable extends Drawable {

    public enum Frame {
        ROUNDED_RECTANGLE,
        CIRCLE
    }

    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF frameRect = new RectF();
    private final Frame frame;

    public AeonLabAgentIconDrawable(int color, Frame frame) {
        this.frame = frame;
        strokePaint.setColor(color);
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeCap(Paint.Cap.ROUND);
        strokePaint.setStrokeJoin(Paint.Join.ROUND);
    }

    @Override
    public void draw(Canvas canvas) {
        Rect bounds = getBounds();
        float size = Math.min(bounds.width(), bounds.height());
        if (size <= 0f) return;
        float left = bounds.left + (bounds.width() - size) / 2f;
        float top = bounds.top + (bounds.height() - size) / 2f;

        float stroke = Math.max(2.0f, size * 0.055f);
        strokePaint.setStrokeWidth(stroke);

        // 外框：圆环 / 圆角方环
        frameRect.set(
                left + size * 0.12f,
                top + size * 0.12f,
                left + size * 0.88f,
                top + size * 0.88f
        );
        if (frame == Frame.CIRCLE) {
            canvas.drawOval(frameRect, strokePaint);
        } else {
            float radius = size * 0.16f;
            canvas.drawRoundRect(frameRect, radius, radius, strokePaint);
        }

        // 字母 A：两条斜边 + 一条横杠
        float cx = left + size * 0.50f;
        float apexY = top + size * 0.34f;
        float footY = top + size * 0.68f;
        float halfBottom = size * 0.15f;
        float leftX = cx - halfBottom;
        float rightX = cx + halfBottom;

        canvas.drawLine(cx, apexY, leftX, footY, strokePaint);
        canvas.drawLine(cx, apexY, rightX, footY, strokePaint);

        float barY = top + size * 0.555f;
        float ratio = (barY - apexY) / (footY - apexY);
        float barHalf = halfBottom * ratio;
        canvas.drawLine(cx - barHalf, barY, cx + barHalf, barY, strokePaint);
    }

    @Override
    public void setAlpha(int alpha) {
        strokePaint.setAlpha(alpha);
        invalidateSelf();
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
        strokePaint.setColorFilter(colorFilter);
        invalidateSelf();
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }

    @Override
    public int getIntrinsicWidth() {
        return 96;
    }

    @Override
    public int getIntrinsicHeight() {
        return 96;
    }
}
