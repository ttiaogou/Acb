package com.ttiaogou.accessibilityinspector.visual

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.ttiaogou.accessibilityinspector.data.InspectorStore
import org.json.JSONArray
import org.json.JSONObject

/**
 * 可视化节点树：从 InspectorStore 读取最近一次 JSON 快照。
 * 当前版本使用原生 View，避免引入 Compose 依赖。
 */
class VisualTreeActivity : Activity() {
    private lateinit var treeContainer: LinearLayout
    private lateinit var detail: TextView
    private val collapsed = mutableSetOf<Int>()
    private val nodes = mutableListOf<UiNode>()
    private var selected: UiNode? = null

    data class UiNode(
        val index: Int,
        val depth: Int,
        val parentIndex: Int?,
        val indexInParent: Int,
        val path: String,
        val json: JSONObject,
        val children: MutableList<Int> = mutableListOf()
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        loadSnapshot()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        header.addView(Button(this).apply {
            text = "刷新"
            setOnClickListener { loadSnapshot() }
        })
        header.addView(Button(this).apply {
            text = "全部展开"
            setOnClickListener { collapsed.clear(); renderTree() }
        })
        header.addView(Button(this).apply {
            text = "全部折叠"
            setOnClickListener {
                nodes.filter { it.children.isNotEmpty() }.forEach { collapsed.add(it.index) }
                renderTree()
            }
        })
        root.addView(header)

        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        val treeScroll = ScrollView(this)
        treeContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        treeScroll.addView(treeContainer)
        body.addView(treeScroll, LinearLayout.LayoutParams(0, -1, 1.15f))

        val detailScroll = ScrollView(this)
        detail = TextView(this).apply {
            textSize = 13f
            setTextIsSelectable(true)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            text = "选择一个节点查看属性"
        }
        detailScroll.addView(detail)
        body.addView(detailScroll, LinearLayout.LayoutParams(0, -1, 0.85f))
        root.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))

        root.addView(Button(this).apply {
            text = "在目标应用中高亮选中节点"
            setOnClickListener { requestHighlight() }
        })
        setContentView(root)
    }

    private fun loadSnapshot() {
        val raw = InspectorStore.getLastReport(this)
        if (raw.isNullOrBlank()) {
            Toast.makeText(this, "没有检测报告，请先返回主界面开始监测。", Toast.LENGTH_LONG).show()
            return
        }
        try {
            val root = JSONObject(raw)
            val array = root.optJSONArray("nodes") ?: JSONArray()
            nodes.clear()
            val parentStack = mutableListOf<Int>()
            val siblingCounts = mutableMapOf<Int, Int>()
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val depth = obj.optInt("depth").coerceAtLeast(0)
                while (parentStack.size > depth) parentStack.removeAt(parentStack.lastIndex)
                val parent = if (depth == 0) null else parentStack.getOrNull(depth - 1)
                val siblingIndex = if (parent == null) {
                    siblingCounts.getOrDefault(-1, 0).also { siblingCounts[-1] = it + 1 }
                } else {
                    siblingCounts.getOrDefault(parent, 0).also { siblingCounts[parent] = it + 1 }
                }
                val parentPath = parent?.let { nodes[it].path }
                val path = if (parentPath == null) siblingIndex.toString() else "$parentPath/$siblingIndex"
                val node = UiNode(
                    index = nodes.size,
                    depth = depth,
                    parentIndex = parent,
                    indexInParent = siblingIndex,
                    path = path,
                    json = obj
                )
                nodes.add(node)
                if (parent != null) nodes[parent].children.add(node.index)
                if (parentStack.size == depth) parentStack.add(node.index)
                else if (depth == 0) {
                    parentStack.clear()
                    parentStack.add(node.index)
                } else {
                    parentStack.add(node.index)
                }
            }
            collapsed.clear()
            selected = null
            detail.text = "共 ${nodes.size} 个节点\n应用：${root.optString("packageName")}\n采集时间：${root.optLong("capturedAtMillis")}"
            renderTree()
        } catch (e: Exception) {
            Toast.makeText(this, "解析节点 JSON 失败：${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun renderTree() {
        treeContainer.removeAllViews()
        val hidden = mutableSetOf<Int>()
        nodes.forEach { node ->
            if (node.parentIndex != null && node.parentIndex in hidden) {
                hidden.add(node.index)
                return@forEach
            }
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(4), dp(3), dp(4), dp(3))
                setBackgroundColor(
                    if (selected?.index == node.index) 0xFFDCEBFF.toInt()
                    else 0x00000000
                )
            }
            val toggle = TextView(this).apply {
                text = when {
                    node.children.isEmpty() -> "  "
                    node.index in collapsed -> "＋ "
                    else -> "－ "
                }
                setPadding(dp(4), dp(4), dp(4), dp(4))
                setOnClickListener {
                    if (node.children.isNotEmpty()) {
                        if (!collapsed.add(node.index)) collapsed.remove(node.index)
                        renderTree()
                    }
                }
            }
            row.addView(toggle)
            val label = TextView(this).apply {
                val cls = node.json.optString("className").substringAfterLast('.')
                val textValue = node.json.optString("text").take(42)
                text = buildString {
                    append(cls.ifBlank { "Unknown" })
                    if (textValue.isNotBlank()) append("  ·  ").append(textValue)
                    append("  #").append(node.index)
                }
                textSize = 12f
                setPadding(dp(4), dp(7), dp(4), dp(7))
                setOnClickListener {
                    selected = node
                    showDetails(node)
                    renderTree()
                }
            }
            row.addView(label, LinearLayout.LayoutParams(0, -2, 1f))
            row.setPadding(dp(8 + node.depth * 15), dp(2), dp(4), dp(2))
            treeContainer.addView(row)
            if (node.index in collapsed) node.children.forEach { hidden.add(it) }
        }
    }

    private fun showDetails(node: UiNode) {
        val j = node.json
        detail.text = """
            节点 #${node.index}
            类型：${j.optString("className")}
            文字：${j.optString("text")}
            描述：${j.optString("contentDescription")}
            资源 ID：${j.optString("viewIdResourceName")}
            路径：${node.path}
            父节点：${node.parentIndex?.let { nodes[it].path } ?: "无（根节点）"}
            层级：${node.depth}
            子节点数：${j.optInt("childCount")}
            屏幕坐标：${j.optString("boundsInScreen")}
            clickable：${j.optBoolean("clickable")}
            longClickable：${j.optBoolean("longClickable")}
            editable：${j.optBoolean("editable")}
            scrollable：${j.optBoolean("scrollable")}
            focusable：${j.optBoolean("focusable")}
            focused：${j.optBoolean("focused")}
            enabled：${j.optBoolean("enabled")}
            password：${j.optBoolean("password")}
            actions：${j.optJSONArray("actions")?.toString() ?: "[]"}
        """.trimIndent()
    }

    private fun requestHighlight() {
        val node = selected
        if (node == null) {
            Toast.makeText(this, "请先选择一个节点。", Toast.LENGTH_SHORT).show()
            return
        }
        val j = node.json
        val bounds = parseBounds(j.optString("boundsInScreen"))
        if (bounds == null || bounds.width() <= 0 || bounds.height() <= 0) {
            Toast.makeText(this, "该节点没有有效屏幕坐标。", Toast.LENGTH_LONG).show()
            return
        }
        val packageName = j.optString("packageName")
        getSharedPreferences("node_highlight", Context.MODE_PRIVATE).edit()
            .putString("package", packageName)
            .putInt("left", bounds.left)
            .putInt("top", bounds.top)
            .putInt("right", bounds.right)
            .putInt("bottom", bounds.bottom)
            .putBoolean("pending", true)
            .apply()

        val launch = packageManager.getLaunchIntentForPackage(packageName)
        if (launch == null) {
            Toast.makeText(this, "无法重新打开目标应用：$packageName", Toast.LENGTH_LONG).show()
            return
        }
        Toast.makeText(this, "正在返回目标应用；服务将在目标窗口出现时显示高亮框。", Toast.LENGTH_LONG).show()
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        startActivity(launch)
    }

    private fun parseBounds(value: String): Rect? {
        val nums = Regex("-?\\d+").findAll(value).mapNotNull { it.value.toIntOrNull() }.toList()
        if (nums.size < 4) return null
        return Rect(nums[0], nums[1], nums[2], nums[3])
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()
}
