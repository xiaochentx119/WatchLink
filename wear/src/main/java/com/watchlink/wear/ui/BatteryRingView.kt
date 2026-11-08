package com.watchlink.wear.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.TypedValue
import android.view.View

/** 电量圆环，纯 Canvas 绘制。 */
class BatteryRingView(context: Context) : View(context) {

    var percent: Int = -1
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    var charging: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    private val stroke = Ui.dp(context, 4f).toFloat()
    private val rect = RectF()

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = stroke
        color = Ui.TRACK
    }

    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = stroke
    }

    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Ui.TEXT
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val size = Ui.dp(context, 84f)
        setMeasuredDimension(size, size)
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val radius = (minOf(width, height) - stroke) / 2f
        rect.set(cx - radius, cy - radius, cx + radius, cy + radius)

        canvas.drawArc(rect, 0f, 360f, false, trackPaint)

        val known = percent >= 0
        if (known && percent > 0) {
            arcPaint.color = when {
                percent <= 15 -> Ui.RED
                percent <= 30 -> Ui.AMBER
                else -> Ui.ACCENT
            }
            canvas.drawArc(rect, -90f, 360f * percent / 100f, false, arcPaint)
        }

        valuePaint.textSize = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP, 22f, resources.displayMetrics
        )
        canvas.drawText(
            if (known) "$percent%" else "--",
            cx,
            cy + valuePaint.textSize * 0.35f,
            valuePaint
        )

        labelPaint.textSize = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP, 9f, resources.displayMetrics
        )
        labelPaint.color = if (charging) Ui.ACCENT else Ui.DIM
        canvas.drawText(
            if (charging) "充电中" else "电量",
            cx,
            cy + valuePaint.textSize * 0.35f + labelPaint.textSize * 1.6f,
            labelPaint
        )
    }
}
