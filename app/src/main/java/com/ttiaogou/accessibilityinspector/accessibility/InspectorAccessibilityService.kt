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
        // 合并短时间内连续发生的事件，减少同一页面重复生成报告。
        handler.removeCallbacks(captureRunnable)
        handler.postDelayed(captureRunnable, CAPTURE_DELAY_MS)
    }

    private fun captureCurrentWindow(expectedPackageName: String) {
        if (!InspectorStore.isMonitoring(this)) return
        if (expectedPackageName.isBlank() || expectedPackageName == packageName) return

        val root = try {
            rootInActiveWindow
        } catch (_: SecurityException) {
            null
        } catch (_: RuntimeException) {
            null
        } ?: return

        try {
            val actualPackage = runCatching {
                root.packageName?.toString().orEmpty()
            }.getOrDefault("")

            // 延迟采集期间窗口可能已经切换；包名不一致时丢弃本次采集，
            // 防止把 Launcher、系统界面或另一个 App 的节点保存到目标报告。
            if (actualPackage.isNotBlank() && actualPackage != expectedPackageName) {
                Log.d(TAG, "Skip stale accessibility event: expected=$expectedPackageName actual=$actualPackage")
                return
            }
            if (actualPackage == packageName) return

            val snapshot = runCatching {
                NodeInspector.inspect(
                    root,
                    actualPackage.ifBlank { expectedPackageName }
                )
            }.getOrNull() ?: return

            val json = runCatching { snapshot.toJsonString() }.getOrNull() ?: return
            if (!InspectorStore.saveReport(this, json)) {
                Log.e(TAG, "Failed to save accessibility report for $expectedPackageName")
                return
            }

            sendBroadcast(Intent(ACTION_REPORT_UPDATED).setPackage(packageName))
        } finally {
            // AccessibilityNodeInfo.recycle() 已弃用；由系统管理节点对象生命周期。
        }
    }

    override fun onInterrupt() {
        handler.removeCallbacks(captureRunnable)
    }

    override fun onDestroy() {
        handler.removeCallbacks(captureRunnable)
        super.onDestroy()
    }

    companion object {
        private const val TAG = "InspectorService"
        private const val CAPTURE_DELAY_MS = 450L

        const val ACTION_REPORT_UPDATED =
            "com.ttiaogou.accessibilityinspector.ACTION_REPORT_UPDATED"
    }
}
