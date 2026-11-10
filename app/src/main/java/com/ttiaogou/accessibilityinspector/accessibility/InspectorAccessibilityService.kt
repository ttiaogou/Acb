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
    private var pendingPackageName = ""
    private lateinit var highlightOverlay: NodeHighlightOverlay

    private val captureRunnable = Runnable { captureCurrentWindow(pendingPackageName) }

    override fun onServiceConnected() {
        super.onServiceConnected()
        highlightOverlay = NodeHighlightOverlay(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val eventPackage = event.packageName?.toString().orEmpty()
        if (eventPackage.isBlank() || eventPackage == packageName) return

        showPendingHighlightIfMatches(eventPackage)

        if (!InspectorStore.isMonitoring(this)) return
        val targetPackage = InspectorStore.getTargetPackage(this)
        if (targetPackage.isBlank() || eventPackage != targetPackage) return

        pendingPackageName = eventPackage
        handler.removeCallbacks(captureRunnable)
        handler.postDelayed(captureRunnable, CAPTURE_DELAY_MS)
    }

    private fun showPendingHighlightIfMatches(eventPackage: String) {
        val prefs = getSharedPreferences(HIGHLIGHT_PREFS, MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_PENDING, false)) return
        if (prefs.getString(KEY_PACKAGE, null) != eventPackage) return

        val requestedAt = prefs.getLong(KEY_REQUESTED_AT, 0L)
        prefs.edit().putBoolean(KEY_PENDING, false).apply()
        if (System.currentTimeMillis() - requestedAt > HIGHLIGHT_TTL_MS) return

        val left = prefs.getInt(KEY_LEFT, 0)
        val top = prefs.getInt(KEY_TOP, 0)
        val right = prefs.getInt(KEY_RIGHT, 0)
        val bottom = prefs.getInt(KEY_BOTTOM, 0)
        if (right > left && bottom > top && ::highlightOverlay.isInitialized) {
            highlightOverlay.show(left, top, right, bottom)
        }
    }

    private fun captureCurrentWindow(expectedPackageName: String) {
        if (!InspectorStore.isMonitoring(this)) return
        if (expectedPackageName.isBlank()) return
        if (InspectorStore.getTargetPackage(this) != expectedPackageName) return

        val root = try { rootInActiveWindow } catch (_: Exception) { null } ?: return
        try {
            val actualPackage = runCatching { root.packageName?.toString().orEmpty() }
                .getOrDefault("")
            if (actualPackage != expectedPackageName) return

            val snapshot = runCatching {
                NodeInspector.inspect(root, actualPackage)
            }.getOrElse { error ->
                Log.e(TAG, "Node inspection failed", error)
                return
            }
            val json = runCatching { snapshot.toJsonString() }.getOrElse { error ->
                Log.e(TAG, "JSON serialization failed", error)
                return
            }
            if (!InspectorStore.saveReport(this, json)) {
                Log.e(TAG, "Failed to save accessibility report for $expectedPackageName")
                return
            }
            sendBroadcast(Intent(ACTION_REPORT_UPDATED).setPackage(packageName))
        } catch (error: Exception) {
            Log.e(TAG, "Capture failed", error)
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
        private const val HIGHLIGHT_PREFS = "node_highlight"
        private const val KEY_PENDING = "pending"
        private const val KEY_PACKAGE = "package"
        private const val KEY_LEFT = "left"
        private const val KEY_TOP = "top"
        private const val KEY_RIGHT = "right"
        private const val KEY_BOTTOM = "bottom"
        private const val KEY_REQUESTED_AT = "requested_at"
        private const val HIGHLIGHT_TTL_MS = 15_000L

        const val ACTION_REPORT_UPDATED =
            "com.ttiaogou.accessibilityinspector.ACTION_REPORT_UPDATED"
    }
}
