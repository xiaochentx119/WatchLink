package com.watchlink.wear.ui

import android.content.Context
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import kotlin.math.abs

/**
 * 极简左右滑动分页容器（替代 ViewPager，省掉一整个依赖）。
 */
class SwipePager(context: Context) : FrameLayout(context) {

    private val pages = ArrayList<View>()
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    var currentPage = 0
        private set

    var onPageChanged: ((Int) -> Unit)? = null

    private var lastX = 0f
    private var lastY = 0f
    private var capturing = false

    fun addPage(page: View) {
        page.visibility = if (pages.isEmpty()) View.VISIBLE else View.GONE
        addView(page)
        pages.add(page)
    }

    fun showPage(index: Int) {
        val target = index.coerceIn(0, pages.lastIndex)
        if (target == currentPage || pages.isEmpty()) return
        val from = pages[currentPage]
        val to = pages[target]
        val distance = Ui.dp(context, 26f).toFloat()

        to.visibility = View.VISIBLE
        to.alpha = 0f
        to.translationX = if (target > currentPage) distance else -distance
        to.animate().alpha(1f).translationX(0f).setDuration(150).start()

        from.animate().alpha(0f).setDuration(110).withEndAction {
            from.visibility = View.GONE
            from.alpha = 1f
            from.translationX = 0f
        }.start()

        currentPage = target
        onPageChanged?.invoke(target)
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = ev.x
                lastY = ev.y
                capturing = false
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = ev.x - lastX
                val dy = ev.y - lastY
                if (abs(dx) > touchSlop * 2 && abs(dx) > abs(dy) * 1.6f) {
                    capturing = true
                }
            }
        }
        return capturing
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = ev.x
                lastY = ev.y
                return true
            }
            MotionEvent.ACTION_UP -> {
                val dx = ev.x - lastX
                val threshold = Ui.dp(context, 36f)
                if (abs(dx) > threshold) {
                    if (dx < 0) showPage(currentPage + 1) else showPage(currentPage - 1)
                }
                capturing = false
            }
            MotionEvent.ACTION_CANCEL -> capturing = false
        }
        return true
    }
}
