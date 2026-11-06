
package com.ttiaogou.accessibilityinspector

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.ttiaogou.accessibilityinspector.accessibility.InspectorAccessibilityService
import com.ttiaogou.accessibilityinspector.data.InspectorStore
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {
    private lateinit var serviceStatusView: TextView
    private lateinit var monitorButton: Button
    private lateinit var reportSummaryView: TextView
    private lateinit var nodeTreeView: TextView
    private var receiverRegistered = false

    // 在打开系统文件选择器之前暂存报告，避免返回后再次读取本机报告文件。
    private var pendingExportReport: String? = null

    private val reportReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            renderLastReport()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildScreen()
        refreshStatus()
        renderLastReport()
    }

    override fun onResume() {
        super.onResume()
        if (!receiverRegistered) {
            val filter = IntentFilter(InspectorAccessibilityService.ACTION_REPORT_UPDATED)
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(reportReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                registerReceiver(reportReceiver, filter)
            }
            receiverRegistered = true
        }
        refreshStatus()
        renderLastReport()
    }

    override fun onPause() {
        if (receiverRegistered) {
            runCatching { unregisterReceiver(reportReceiver) }
            receiverRegistered = false
        }
        super.onPause()
    }

    private fun buildScreen() {
        val scrollView = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(22), dp(18), dp(30))
        }
        scrollView.addView(root)
        setContentView(scrollView)

        root.addView(text("无障碍节点检测器", 25f, true))
        root.addView(text("读取当前窗口通过 Android 无障碍接口提供的节点，并在本机生成报告。", 14f))
        root.addView(space(14))

        serviceStatusView = text("无障碍服务状态：检查中", 15f, true)
        root.addView(serviceStatusView)
        root.addView(space(8))

        root.addView(actionButton("打开无障碍设置") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })

        monitorButton = actionButton("开始监测") { toggleMonitoring() }
        root.addView(monitorButton)
        root.addView(space(8))
        root.addView(text("使用方法：先开启上方的无障碍服务，再开始监测；切换到需要检测的 App，并打开不同页面。检测器会在页面变化后更新报告。", 13f))
        root.addView(space(8))
        root.addView(text("隐私提示：节点报告可能包含屏幕上可见的文字，例如搜索内容或聊天文本。数据只保存在本机；只有你主动导出时才会写入所选位置。", 13f))

        root.addView(space(20))
        root.addView(text("页面统计", 19f, true))
        reportSummaryView = text("尚无检测报告。开启监测并切换到目标 App 页面。", 14f)
        reportSummaryView.setPadding(dp(12), dp(12), dp(12), dp(12))
        reportSummaryView.setBackgroundColor(0xFFF1F3F5.toInt())
        root.addView(reportSummaryView)

        root.addView(space(14))
        root.addView(text("节点树预览（最多显示 180 个节点）", 19f, true))
        nodeTreeView = text("等待检测数据…", 12f)
        nodeTreeView.typeface = android.graphics.Typeface.MONOSPACE
        nodeTreeView.setTextIsSelectable(true)
        nodeTreeView.setPadding(dp(12), dp(12), dp(12), dp(12))
        nodeTreeView.setBackgroundColor(0xFFF7F7F7.toInt())
        root.addView(nodeTreeView)

        root.addView(space(14))
        root.addView(actionButton("导出 JSON 报告") { exportReport() })
        root.addView(actionButton("复制检测结果") { copyReport() })
        root.addView(actionButton("刷新报告") { renderLastReport() })
        root.addView(actionButton("清除本机报告") { clearReport() })

        root.addView(space(12))
        root.addView(text("检测范围说明：统计的是当前窗口中可由无障碍服务访问到的节点，不等同于 App 内部所有 View。某些自绘界面、Web 内容或受保护窗口可能只暴露部分节点。", 12f))
    }

    private fun toggleMonitoring() {
        if (!isInspectorServiceEnabled()) {
            AlertDialog.Builder(this)
                .setTitle("需要先开启无障碍服务")
                .setMessage("请在系统无障碍设置中启用“无障碍节点检测服务”，返回后再开始监测。")
                .setPositiveButton("打开设置") { _, _ ->
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
                .setNegativeButton("取消", null)
                .show()
            return
        }

        val newState = !InspectorStore.isMonitoring(this)
        InspectorStore.setMonitoring(this, newState)
        refreshStatus()
        Toast.makeText(this, if (newState) "已开始监测。请切换到目标 App。" else "已停止监测。", Toast.LENGTH_SHORT).show()
    }

    private fun refreshStatus() {
        val enabled = isInspectorServiceEnabled()
        val monitoring = InspectorStore.isMonitoring(this)
        serviceStatusView.text = buildString {
            append("无障碍服务：")
            append(if (enabled) "已开启" else "未开启")
            append("\n页面监测：")
            append(if (monitoring) "运行中" else "已停止")
        }
        monitorButton.text = if (monitoring) "停止监测" else "开始监测"
        monitorButton.isEnabled = true
    }

    private fun isInspectorServiceEnabled(): Boolean {
        val manager = getSystemService(AccessibilityManager::class.java) ?: return false
        val expectedClass = InspectorAccessibilityService::class.java.name
        return manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { info ->
                val serviceInfo = runCatching { info.resolveInfo?.serviceInfo }.getOrNull()
                serviceInfo?.packageName == packageName && serviceInfo.name == expectedClass
            }
    }

    private fun renderLastReport() {
        val jsonText = InspectorStore.getLastReport(this)
        if (jsonText.isNullOrBlank()) {
            reportSummaryView.text = "尚无检测报告。开启监测并切换到目标 App 页面。"
            nodeTreeView.text = "等待检测数据…"
            return
        }

        runCatching {
            val root = JSONObject(jsonText)
            val stats = root.optJSONObject("stats") ?: JSONObject()
            val capturedAt = root.optLong("capturedAtMillis", 0L)
            val dateText = if (capturedAt > 0L) {
                SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(capturedAt))
            } else "未知"

            reportSummaryView.text = buildString {
                append("目标包名：${root.optString("packageName", "未知")}\n")
                append("采集时间：$dateText\n")
                append("根节点类型：${root.optString("rootClassName", "未知")}\n")
                append("节点总数：${stats.optInt("total")}\n")
                append("含文本节点：${stats.optInt("withText")}\n")
                append("含描述节点：${stats.optInt("withDescription")}\n")
                append("按钮类节点：${stats.optInt("buttonLike")}\n")
                append("输入框/可编辑节点：${stats.optInt("editTexts")}\n")
                append("可点击节点：${stats.optInt("clickable")}\n")
                append("可长按节点：${stats.optInt("longClickable")}\n")
                append("可滚动节点：${stats.optInt("scrollable")}\n")
                append("可聚焦节点：${stats.optInt("focusable")}\n")
                append("含资源 ID 节点：${stats.optInt("resourceIds")}\n")
                append("图片类节点：${stats.optInt("imageLike")}")
                if (root.optBoolean("truncated")) append("\n提示：节点数量达到本工具上限，报告可能不完整。")
            }

            val nodes = root.optJSONArray("nodes")
            val preview = StringBuilder()
            if (nodes != null) {
                val count = minOf(nodes.length(), 180)
                for (i in 0 until count) {
                    val node = nodes.optJSONObject(i) ?: continue
                    val depth = node.optInt("depth").coerceIn(0, 20)
                    val indent = "  ".repeat(depth)
                    val className = node.optString("className").substringAfterLast('.')
                    preview.append(indent)
                        .append("[").append(node.optInt("index")).append("] ")
                        .append(className.ifBlank { "Unknown" })
                    val text = node.optString("text").take(100)
                    val description = node.optString("contentDescription").take(80)
                    val viewId = node.optString("viewIdResourceName")
                    if (text.isNotBlank()) preview.append(" text=\"").append(text).append("\"")
                    if (description.isNotBlank()) preview.append(" desc=\"").append(description).append("\"")
                    if (viewId.isNotBlank()) preview.append(" id=").append(viewId)
                    preview.append(" {click=").append(node.optBoolean("clickable"))
                        .append(", edit=").append(node.optBoolean("editable"))
                        .append(", scroll=").append(node.optBoolean("scrollable"))
                        .append("} bounds=").append(node.optString("boundsInScreen"))
                        .append('\n')
                }
                if (nodes.length() > count) {
                    preview.append("\n…还有 ").append(nodes.length() - count).append(" 个节点未在屏幕预览中显示；导出的 JSON 会包含报告内的全部节点。")
                }
            }
            nodeTreeView.text = preview.toString().ifBlank { "报告中没有节点详情。" }
        }.onFailure { error ->
            reportSummaryView.text = "报告解析失败：${error.message ?: "未知错误"}"
            nodeTreeView.text = jsonText.take(5000)
        }
    }

    /**
     * 将本机保存的完整 JSON 报告复制到系统剪贴板。
     * 复制的是原始报告数据，不是界面上最多显示 180 个节点的预览文本。
     */
    private fun copyReport() {
        val report = InspectorStore.getLastReport(this)
        if (report.isNullOrBlank()) {
            Toast.makeText(this, "目前没有可复制的检测报告。", Toast.LENGTH_SHORT).show()
            return
        }

        val reportObject = runCatching { JSONObject(report) }.getOrElse { error ->
            Toast.makeText(
                this,
                "报告格式无效，无法复制 JSON：${error.message ?: "解析失败"}",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        val clipboard = getSystemService(ClipboardManager::class.java)
        if (clipboard == null) {
            Toast.makeText(this, "无法访问系统剪贴板。", Toast.LENGTH_LONG).show()
            return
        }

        val nodeArray = reportObject.optJSONArray("nodes")
        val stats = reportObject.optJSONObject("stats")
        val nodeCount = stats?.optInt("total", nodeArray?.length() ?: 0)
            ?: (nodeArray?.length() ?: 0)
        val byteCount = report.toByteArray(Charsets.UTF_8).size

        runCatching {
            clipboard.setPrimaryClip(
                ClipData.newPlainText("无障碍节点检测报告", report)
            )
        }.onSuccess {
            Toast.makeText(
                this,
                "已复制完整检测报告（${nodeCount} 个节点，${byteCount} 字节）。",
                Toast.LENGTH_LONG
            ).show()
        }.onFailure { error ->
            Toast.makeText(
                this,
                "复制失败：${error.message ?: "未知错误"}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun exportReport() {
        val report = InspectorStore.getLastReport(this)
        if (report.isNullOrBlank()) {
            Toast.makeText(this, "目前没有可导出的报告。", Toast.LENGTH_SHORT).show()
            return
        }

        // 缓存本次导出的完整报告，文件选择器返回时不再重新读取存储文件。
        pendingExportReport = report

        val fileName = "accessibility-report-${System.currentTimeMillis()}.json"
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
            putExtra(Intent.EXTRA_TITLE, fileName)
        }
        startActivityForResult(intent, REQUEST_CREATE_DOCUMENT)
    }

    @Deprecated("Uses the document creation contract for broad Android API compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode != REQUEST_CREATE_DOCUMENT) return

        if (resultCode != RESULT_OK) {
            pendingExportReport = null
            return
        }

        val uri: Uri? = data?.data
        val report = pendingExportReport
        pendingExportReport = null

        if (uri == null || report.isNullOrBlank()) {
            Toast.makeText(this, "报告内容为空，无法导出。", Toast.LENGTH_LONG).show()
            return
        }

        runCatching {
            val bytes = report.toByteArray(Charsets.UTF_8)
            check(bytes.isNotEmpty()) { "报告内容为空，未写入文件。" }

            val outputStream = contentResolver.openOutputStream(uri, "wt")
                ?: error("无法打开所选文件位置")

            outputStream.use { stream ->
                stream.write(bytes)
                stream.flush()
            }

            // 重新读取目标文件校验实际写入结果，避免只提示成功却生成空文件。
            val savedBytes = contentResolver.openInputStream(uri)?.use { input ->
                input.readBytes()
            } ?: error("文件已写入，但无法重新读取以验证内容")

            check(savedBytes.isNotEmpty()) { "写入后文件仍然为空。" }
            check(savedBytes.size == bytes.size) {
                "文件校验失败：预期 ${bytes.size} 字节，实际 ${savedBytes.size} 字节。"
            }

            bytes.size
        }.onSuccess { byteCount ->
            Toast.makeText(
                this,
                "JSON 报告已导出并验证（${byteCount} 字节）。",
                Toast.LENGTH_LONG
            ).show()
        }.onFailure { error ->
            Toast.makeText(
                this,
                "导出失败：${error.message ?: "未知错误"}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun clearReport() {
        AlertDialog.Builder(this)
            .setTitle("清除报告？")
            .setMessage("这会删除本机保存的上一份检测报告，不会影响目标 App。")
            .setPositiveButton("清除") { _, _ ->
                InspectorStore.clearReport(this)
                renderLastReport()
                Toast.makeText(this, "报告已清除。", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun actionButton(label: String, action: () -> Unit): Button =
        Button(this).apply {
            text = label
            isAllCaps = false
            setOnClickListener { action() }
        }

    private fun text(value: String, sizeSp: Float, bold: Boolean = false): TextView =
        TextView(this).apply {
            text = value
            textSize = sizeSp
            setTextColor(0xFF202124.toInt())
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        }

    private fun space(heightDp: Int): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(1, dp(heightDp))
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()

    companion object {
        private const val REQUEST_CREATE_DOCUMENT = 4102
    }
}
