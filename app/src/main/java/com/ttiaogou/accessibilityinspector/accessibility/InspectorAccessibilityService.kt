package com.ttiaogou.accessibilityinspector.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.ttiaogou.accessibilityinspector.data.InspectorStore

class InspectorAccessibilityService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private var pendingPackageName: String = ""
    private lateinit var highlightOverlay: NodeHighlightOverlay

    private val captureRunnable = Runnable { captureCurrentWindow(pendingPackageName) }

    override fun onServiceConnected() {
        super.onServiceConnected()
        highlightOverlay = NodeHighlightOverlay(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val eventPackage = event.packageName?.toString().orEmpty()
        if (eventPackage.isBlank() || eventPackage == packageName) return

        // 用户从检查器返回目标应用时，显示刚才选中的节点边框。
        showPendingHighlightIfMatches(eventPackage)

        if (!InspectorStore.isMonitoring(this)) return
        pendingPackageName = eventPackage
        handler.removeCallbacks(captureRunnable)
        handler.postDelayed(captureRunnable, CAPTURE_DELAY_MS)
    }

    private fun showPendingHighlightIfMatches(eventPackage: String) {
        val prefs = getSharedPreferences("node_highlight", MODE_PRIVATE)
        if (!prefs.getBoolean("pending", false)) return
        if (prefs.getString("package", null) != eventPackage) return

        val left = prefs.getInt("left", 0)
        val top = prefs.getInt("top", 0)
        val right = prefs.getInt("right", 0)
        val bottom = prefs.getInt("bottom", 0)
        prefs.edit().putBoolean("pending", false).apply()

        if (right > left && bottom > top) {
            if (::highlightOverlay.isInitialized) {
                highlightOverlay.show(left, top, right, bottom)
            }
        }
    }

    private fun captureCurrentWindow(expectedPackageName: String) {
        if (!InspectorStore.isMonitoring(this)) return
        if (expectedPackageName.isBlank() || expectedPackageName == packageName) return

        val root = try { rootInActiveWindow } catch (_: Exception) { null } ?: return
        try {
            val actualPackage = runCatching { root.packageName?.toString().orEmpty() }.getOrDefault("")
            if (actualPackage.isNotBlank() && actualPackage != expectedPackageName) return
            if (actualPackage == packageName) return

            val snapshot = runCatching {
                NodeInspector.inspect(root, actualPackage.ifBlank { expectedPackageName })
            }.getOrNull() ?: return
            val json = runCatching { snapshot.toJsonString() }.getOrNull() ?: return
            if (!InspectorStore.saveReport(this, json)) {
                Log.e(TAG, "Failed to save accessibility report for $expectedPackageName")
                return
            }
            sendBroadcast(Intent(ACTION_REPORT_UPDATED).setPackage(packageName))
        } finally {
            // AccessibilityNodeInfo 的生命周期由 Android 框架管理。
        }
    }

    override fun onInterrupt() {
        handler.removeCallbacks(captureRunnable)
        if (::highlightOverlay.isInitialized) highlightOverlay.hide()
    }

    override fun onDestroy() {
        handler.removeCallbacks(captureRunnable)
        if (::highlightOverlay.isInitialized) highlightOverlay.hide()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "InspectorService"
        private const val CAPTURE_DELAY_MS = 450L
        const val ACTION_REPORT_UPDATED =
            "com.ttiaogou.accessibilityinspector.ACTION_REPORT_UPDATED"
    }
}
