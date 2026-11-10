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

/**
 * 由无障碍服务创建的短暂屏幕边框。
 * 使用 TYPE_ACCESSIBILITY_OVERLAY，不需要普通悬浮窗权限。
 */
class NodeHighlightOverlay(private val service: AccessibilityService) {
    private val wm = service.getSystemService(WindowManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var view: HighlightView? = null

    fun show(left: Int, top: Int, right: Int, bottom: Int) {
        handler.post {
            hideNow()
            val width = (right - left).coerceAtLeast(2)
            val height = (bottom - top).coerceAtLeast(2)
            val v = HighlightView()
            view = v
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
            runCatching { wm.addView(v, params) }
            handler.postDelayed({ hideNow() }, 2200L)
        }
    }

    fun hide() = handler.post { hideNow() }

    private fun hideNow() {
        val old = view ?: return
        view = null
        runCatching { wm.removeView(old) }
    }

    private inner class HighlightView : View(service) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.RED
            style = Paint.Style.STROKE
            strokeWidth = 6f * resources.displayMetrics.density
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val inset = paint.strokeWidth / 2f
            canvas.drawRect(inset, inset, width - inset, height - inset, paint)
        }
    }
}
