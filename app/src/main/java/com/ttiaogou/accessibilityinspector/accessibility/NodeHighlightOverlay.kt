package com.ttiaogou.accessibilityinspector.accessibility

import android.accessibilityservice.AccessibilityService
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager

/** Short-lived red outline drawn by the enabled accessibility service. */
class NodeHighlightOverlay(private val service: AccessibilityService) {
    private val windowManager = service.getSystemService(WindowManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var overlay: HighlightView? = null
    private var hideRunnable: Runnable? = null

    fun show(left: Int, top: Int, right: Int, bottom: Int) {
        handler.post {
            removeOverlay()
            val width = (right - left).coerceAtLeast(2)
            val height = (bottom - top).coerceAtLeast(2)
            val view = HighlightView()
            overlay = view
            val params = WindowManager.LayoutParams(
                width,
                height,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = left
                y = top
            }
            runCatching { windowManager.addView(view, params) }
                .onFailure { overlay = null }
            val task = Runnable { removeOverlay() }
            hideRunnable = task
            handler.postDelayed(task, 2200L)
        }
    }

    fun hide() = handler.post { removeOverlay() }

    private fun removeOverlay() {
        val pendingHide = hideRunnable
        if (pendingHide != null) handler.removeCallbacks(pendingHide)
        hideRunnable = null
        val old = overlay ?: return
        overlay = null
        runCatching { windowManager.removeView(old) }
    }

    private inner class HighlightView : View(service) {
        private val density = resources.displayMetrics.density
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.RED
            style = Paint.Style.STROKE
            strokeWidth = 4f * density
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val inset = paint.strokeWidth / 2f
            canvas.drawRect(inset, inset, width - inset, height - inset, paint)
        }
    }
}
