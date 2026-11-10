package com.ttiaogou.accessibilityinspector

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
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
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.ttiaogou.accessibilityinspector.accessibility.InspectorAccessibilityService
import com.ttiaogou.accessibilityinspector.data.InspectorStore
import com.ttiaogou.accessibilityinspector.visual.VisualTreeActivity
import org.json.JSONObject

class MainActivity : Activity() {
    private lateinit var serviceStatus: TextView
    private lateinit var targetPackageInput: EditText
    private lateinit var monitorButton: Button
    private lateinit var reportSummary: TextView
    private var receiverRegistered = false
    private var pendingExportReport: String? = null

    private val reportReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = renderReportSummary()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildScreen()
        refreshStatus()
        renderReportSummary()
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
        renderReportSummary()
    }

    override fun onPause() {
        if (receiverRegistered) {
            runCatching { unregisterReceiver(reportReceiver) }
            receiverRegistered = false
        }
        super.onPause()
    }

    private fun buildScreen() {
        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(22), dp(18), dp(30))
        }
        scroll.addView(root)
        setContentView(scroll)

        root.addView(text("Acb 无障碍节点查看器 V2", 24f, true))
        root.addView(text("指定一个目标应用，只读取该应用通过 Android 无障碍接口公开的当前窗口节点。", 14f))
        root.addView(space(14))

        serviceStatus = text("无障碍服务：检查中", 15f, true)
        root.addView(serviceStatus)
        root.addView(actionButton("打开系统无障碍设置") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })

        root.addView(space(10))
        root.addView(text("目标应用包名（例如 com.immomo.momo）", 14f, true))
        targetPackageInput = EditText(this).apply {
            singleLine = true
            hint = "输入要检测的应用包名"
            setText(InspectorStore.getTargetPackage(this@MainActivity))
        }
        root.addView(targetPackageInput)
        root.addView(actionButton("保存目标应用") { saveTargetPackage(showToast = true) })

        monitorButton = actionButton("开始监测") { toggleMonitoring() }
        root.addView(monitorButton)
        root.addView(actionButton("打开可视化节点树") {
            startActivity(Intent(this, VisualTreeActivity::class.java))
        })
        root.addView(actionButton("导出 JSON 报告") { exportReport() })
        root.addView(actionButton("刷新报告摘要") { renderReportSummary() })
        root.addView(actionButton("清除本机报告") { clearReport() })

        root.addView(space(18))
        root.addView(text("最近一次采集", 19f, true))
        reportSummary = text("尚无报告。开启监测并切换到目标应用页面。", 14f)
        reportSummary.setPadding(dp(12), dp(12), dp(12), dp(12))
        reportSummary.setBackgroundColor(0xFFF1F3F5.toInt())
        root.addView(reportSummary)

        root.addView(space(12))
        root.addView(text(
            "使用步骤：1. 开启无障碍服务；2. 输入目标应用包名并保存；3. 开始监测；4. 切换到目标应用需要检查的页面；5. 返回本工具打开节点树。报告仅保存在本机，导出由你主动发起。",
            13f
        ))
        root.addView(space(8))
        root.addView(text(
            "边界说明：只能读取目标应用主动向无障碍服务公开的节点。自绘画布、受保护窗口或未暴露文本的控件可能无法读取。密码节点及其子节点的文本和描述会被过滤。",
            12f
        ))
    }

    private fun saveTargetPackage(showToast: Boolean): Boolean {
        val value = targetPackageInput.text?.toString()?.trim().orEmpty()
        val valid = value.matches(Regex("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+"))
        if (!valid) {
            if (showToast) Toast.makeText(this, "请输入有效包名，例如 com.immomo.momo。", Toast.LENGTH_LONG).show()
            return false
        }
        InspectorStore.setTargetPackage(this, value)
        if (showToast) Toast.makeText(this, "已保存目标应用：$value", Toast.LENGTH_SHORT).show()
        return true
    }

    private fun toggleMonitoring() {
        if (!isInspectorServiceEnabled()) {
            AlertDialog.Builder(this)
                .setTitle("需要开启无障碍服务")
                .setMessage("请在系统无障碍设置中启用“Acb 节点检测服务”，返回后再开始监测。")
                .setPositiveButton("打开设置") { _, _ ->
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
                .setNegativeButton("取消", null)
                .show()
            return
        }

        val currentlyMonitoring = InspectorStore.isMonitoring(this)
        if (currentlyMonitoring) {
            InspectorStore.setMonitoring(this, false)
        } else {
            if (!saveTargetPackage(showToast = false)) {
                Toast.makeText(this, "请先填写有效的目标应用包名。", Toast.LENGTH_LONG).show()
                return
            }
            InspectorStore.setMonitoring(this, true)
        }
        refreshStatus()
    }

    private fun refreshStatus() {
        val enabled = isInspectorServiceEnabled()
        val monitoring = InspectorStore.isMonitoring(this)
        serviceStatus.text = "无障碍服务：${if (enabled) "已开启" else "未开启"}\n" +
            "页面监测：${if (monitoring) "运行中" else "已停止"}\n" +
            "目标应用：${InspectorStore.getTargetPackage(this).ifBlank { "未设置" }}"
        monitorButton.text = if (monitoring) "停止监测" else "开始监测"
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

    private fun renderReportSummary() {
        if (!::reportSummary.isInitialized) return
        val raw = InspectorStore.getLastReport(this)
        if (raw.isNullOrBlank()) {
            reportSummary.text = "尚无报告。开启监测并切换到目标应用页面。"
            refreshStatus()
            return
        }
        runCatching {
            val root = JSONObject(raw)
            val stats = root.optJSONObject("stats") ?: JSONObject()
            reportSummary.text = buildString {
                append("应用：${root.optString("packageName")}\n")
                append("采集时间：${root.optLong("capturedAtMillis")}\n")
                append("节点总数：${stats.optInt("total")}\n")
                append("含文字节点：${stats.optInt("withText")}\n")
                append("可点击节点：${stats.optInt("clickable")}\n")
                append("可编辑节点：${stats.optInt("editTexts")}\n")
                append("密码字段（仅计数，内容已过滤）：${stats.optInt("passwordFields")}\n")
                if (root.optBoolean("truncated")) append("注意：达到节点数量上限，报告不完整。")
            }
        }.onFailure {
            reportSummary.text = "报告解析失败：${it.message}"
        }
        refreshStatus()
    }

    private fun exportReport() {
        val report = InspectorStore.getLastReport(this)
        if (report.isNullOrBlank()) {
            Toast.makeText(this, "没有可导出的报告。", Toast.LENGTH_SHORT).show()
            return
        }
        runCatching { JSONObject(report) }.onFailure {
            Toast.makeText(this, "报告不是有效 JSON。", Toast.LENGTH_LONG).show()
            return
        }
        pendingExportReport = report
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
            putExtra(Intent.EXTRA_TITLE, "acb-accessibility-${System.currentTimeMillis()}.json")
        }
        startActivityForResult(intent, REQUEST_CREATE_DOCUMENT)
    }

    @Deprecated("Uses the platform document picker for compatibility with the existing project.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_CREATE_DOCUMENT) return
        val report = pendingExportReport
        pendingExportReport = null
        if (resultCode != RESULT_OK || report.isNullOrBlank()) return
        val uri: Uri = data?.data ?: return
        runCatching {
            contentResolver.openOutputStream(uri, "wt")?.use {
                it.write(report.toByteArray(Charsets.UTF_8))
                it.flush()
            } ?: error("无法打开所选文件位置")
            val saved = contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                ?: error("无法验证导出文件")
            check(saved == report) { "导出内容校验不一致" }
        }.onSuccess {
            Toast.makeText(this, "JSON 报告导出并校验成功。", Toast.LENGTH_LONG).show()
        }.onFailure {
            Toast.makeText(this, "导出失败：${it.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun clearReport() {
        AlertDialog.Builder(this)
            .setTitle("清除本机报告？")
            .setMessage("只删除本工具保存的最近一次节点报告。")
            .setPositiveButton("清除") { _, _ ->
                InspectorStore.clearReport(this)
                renderReportSummary()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun actionButton(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { action() }
    }

    private fun text(value: String, size: Float, bold: Boolean = false) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(0xFF202124.toInt())
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
    }

    private fun space(dpValue: Int) = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(1, dp(dpValue))
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    companion object {
        private const val REQUEST_CREATE_DOCUMENT = 8102
    }
}
