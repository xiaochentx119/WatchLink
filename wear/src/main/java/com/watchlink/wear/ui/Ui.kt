package com.watchlink.wear.ui

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/** 手表端的轻量 UI 工具（不依赖 Compose，尽量少占内存）。 */
object Ui {

    const val SURFACE = 0xFF151B26.toInt()
    const val SURFACE_ALT = 0xFF1F2838.toInt()
    const val TRACK = 0xFF26303F.toInt()
    const val TEXT = 0xFFF2F6FF.toInt()
    const val DIM = 0xFF9AA8BF.toInt()
    const val ACCENT = 0xFF3DDC97.toInt()
    const val BLUE = 0xFF7FB2FF.toInt()
    const val AMBER = 0xFFFFC55C.toInt()
    const val RED = 0xFFFF6B6B.toInt()
    const val FAINT = 0xFF5A677D.toInt()

    fun dp(context: Context, value: Float): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, value, context.resources.displayMetrics
    ).toInt()

    fun rounded(context: Context, color: Int, radiusDp: Float): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(color)
            cornerRadius = dp(context, radiusDp).toFloat()
        }

    fun text(context: Context, sizeSp: Float, color: Int, bold: Boolean = false): TextView =
        TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
            setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
            includeFontPadding = false
            isSingleLine = true
            gravity = Gravity.CENTER_VERTICAL
        }

    fun card(context: Context, radiusDp: Float = 15f): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(context, SURFACE, radiusDp)
            val p = dp(context, 10f)
            setPadding(p, p, p, p)
        }

    fun spacer(context: Context, widthDp: Float = 0f, weight: Float = 0f): View =
        View(context).apply {
            layoutParams = if (weight > 0f) {
                LinearLayout.LayoutParams(0, 1).apply { this.weight = weight }
            } else {
                LinearLayout.LayoutParams(dp(context, widthDp), 1)
            }
        }

    fun pageLayout(context: Context): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
}
