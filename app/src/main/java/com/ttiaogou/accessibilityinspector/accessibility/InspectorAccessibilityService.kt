package com.ttiaogou.accessibilityinspector.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import com.ttiaogou.accessibilityinspector.data.InspectorStore

class InspectorAccessibilityService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private var pendingPackageName: String = ""

    private val captureRunnable = Runnable {
        captureCurrentWindow(pendingPackageName)
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (!InspectorStore.isMonitoring(this)) return

        val eventPackage = event.packageName?.toString().orEmpty()
        if (eventPackage.isBlank() || eventPackage == packageName) return

        pendingPackageName = eventPackage
        // Debounce frequent content-change events so a single page does not create dozens of reports.
        handler.removeCallbacks(captureRunnable)
        handler.postDelayed(captureRunnable, 450L)
    }

    private fun captureCurrentWindow(fallbackPackageName: String) {
        if (!InspectorStore.isMonitoring(this)) return

        val root = try {
            rootInActiveWindow
        } catch (_: SecurityException) {
            null
        } catch (_: RuntimeException) {
            null
        } ?: return

        val actualPackage = runCatching { root.packageName?.toString().orEmpty() }.getOrDefault("")
        if (actualPackage == packageName) return

        val snapshot = runCatching {
            NodeInspector.inspect(root, fallbackPackageName)
        }.getOrNull() ?: return

        val json = runCatching { snapshot.toJsonString() }.getOrNull() ?: return
        InspectorStore.saveReport(this, json)

        sendBroadcast(
            Intent(ACTION_REPORT_UPDATED).setPackage(packageName)
        )
    }

    override fun onInterrupt() {
        handler.removeCallbacks(captureRunnable)
    }

    override fun onDestroy() {
        handler.removeCallbacks(captureRunnable)
        super.onDestroy()
    }

    companion object {
        const val ACTION_REPORT_UPDATED =
            "com.ttiaogou.accessibilityinspector.ACTION_REPORT_UPDATED"
    }
}
