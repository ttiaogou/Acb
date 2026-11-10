package com.ttiaogou.accessibilityinspector.visual

import android.app.Activity
import android.content.Context
import android.graphics.Rect
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.ttiaogou.accessibilityinspector.data.InspectorStore
import org.json.JSONArray
import org.json.JSONObject

class VisualTreeActivity : Activity() {
    private data class UiNode(
        val index: Int,
        val depth: Int,
        val path: String,
        val parentPath: String?,
        val packageName: String,
        val json: JSONObject,
        val children: MutableList<UiNode> = mutableListOf()
    )

    private lateinit var treeContainer: LinearLayout
    private lateinit var detailsView: TextView
    private lateinit var searchInput: EditText
    private lateinit var headerView: TextView
    private val nodes = mutableListOf<UiNode>()
    private val roots = mutableListOf<UiNode>()
    private val collapsedPaths = mutableSetOf<String>()
    private var selectedNode: UiNode? = null
    private var targetPackageName: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildScreen()
        loadSnapshot()
    }

    private fun buildScreen() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }
        headerView = TextView(this).apply {
            textSize = 16f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(0xFF202124.toInt())
        }
        root.addView(headerView)

        searchInput = EditText(this).apply {
            singleLine = true
            hint = "搜索节点文字、类型或资源 ID"
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    renderTree()
                }
                override fun afterTextChanged(s: Editable?) = Unit
            })
        }
        root.addView(searchInput)

        val controls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        controls.addView(smallButton("刷新") { loadSnapshot() })
        controls.addView(smallButton("全部展开") {
            collapsedPaths.clear()
            renderTree()
        })
        controls.addView(smallButton("全部折叠") {
            nodes.filter { it.children.isNotEmpty() }.forEach { collapsedPaths.add(it.path) }
            renderTree()
        })
        root.addView(controls)

        val treeScroll = ScrollView(this)
        treeContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(4), 0, dp(4))
        }
        treeScroll.addView(treeContainer)
        root.addView(treeScroll, LinearLayout.LayoutParams(-1, 0, 1f))

        val divider = View(this).apply { setBackgroundColor(0xFFCFD8DC.toInt()) }
        root.addView(divider, LinearLayout.LayoutParams(-1, dp(1)))

        detailsView = TextView(this).apply {
            textSize = 12f
            setTextColor(0xFF202124.toInt())
            setTextIsSelectable(true)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            text = "选择节点查看详细属性。"
        }
        val detailsScroll = ScrollView(this)
        detailsScroll.addView(detailsView)
        root.addView(detailsScroll, LinearLayout.LayoutParams(-1, dp(220)))

        root.addView(actionButton("保存高亮请求并切回目标应用") { requestHighlight() })
        root.addView(actionButton("返回") { finish() })
        setContentView(root)
    }

    private fun loadSnapshot() {
        val raw = InspectorStore.getLastReport(this)
        if (raw.isNullOrBlank()) {
            headerView.text = "没有检测报告。请先返回主界面，开始监测目标应用。"
            nodes.clear()
            roots.clear()
            renderTree()
            detailsView.text = "暂无节点数据。"
            return
        }

        try {
            val report = JSONObject(raw)
            targetPackageName = report.optString("packageName")
            val array = report.optJSONArray("nodes") ?: JSONArray()
            nodes.clear()
            roots.clear()
            val byPath = linkedMapOf<String, UiNode>()
            val fallbackStack = mutableListOf<String>()
            val siblingCount = mutableMapOf<String, Int>()

            for (i in 0 until array.length()) {
                val json = array.optJSONObject(i) ?: continue
                val depth = json.optInt("depth", 0).coerceAtLeast(0)
                while (fallbackStack.size > depth) fallbackStack.removeAt(fallbackStack.lastIndex)
                val parentFromJson = if (json.has("parentPath") && !json.isNull("parentPath")) {
                    json.optString("parentPath").takeIf { it.isNotBlank() }
                } else null
                val parentPath = parentFromJson ?: if (depth > 0) fallbackStack.getOrNull(depth - 1) else null
                val siblingKey = parentPath ?: "<root>"
                val inferredIndex = siblingCount.getOrDefault(siblingKey, 0)
                siblingCount[siblingKey] = inferredIndex + 1
                val indexInParent = json.optInt("indexInParent", inferredIndex)
                val path = json.optString("path").takeIf { it.isNotBlank() }
                    ?: if (parentPath == null) indexInParent.toString() else "$parentPath/$indexInParent"

                val node = UiNode(
                    index = json.optInt("index", i),
                    depth = depth,
                    path = path,
                    parentPath = parentPath,
                    packageName = json.optString("packageName", targetPackageName),
                    json = json
                )
                nodes.add(node)
                byPath[path] = node
                if (fallbackStack.size == depth) fallbackStack.add(path)
                else if (fallbackStack.size > depth) {
                    fallbackStack[depth] = path
                    while (fallbackStack.size > depth + 1) fallbackStack.removeAt(fallbackStack.lastIndex)
                } else {
                    while (fallbackStack.size < depth) fallbackStack.add("")
                    fallbackStack.add(path)
                }
            }

            nodes.forEach { node ->
                val parent = node.parentPath?.let { byPath[it] }
                if (parent != null) parent.children.add(node) else roots.add(node)
            }

            collapsedPaths.clear()
            // 初始展开根节点，较深层级默认折叠，避免一次渲染数千行。
            nodes.filter { it.depth >= 1 && it.children.isNotEmpty() }
                .forEach { collapsedPaths.add(it.path) }
            selectedNode = null
            detailsView.text = "选择节点查看详细属性。\n应用：$targetPackageName\n节点数：${nodes.size}"
            headerView.text = "应用：$targetPackageName    节点：${nodes.size}    schema：${report.optInt("schemaVersion", 1)}"
            renderTree()
        } catch (error: Exception) {
            headerView.text = "节点报告解析失败：${error.message}"
            Toast.makeText(this, "报告解析失败：${error.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun renderTree() {
        if (!::treeContainer.isInitialized) return
        treeContainer.removeAllViews()
        val query = searchInput.text?.toString()?.trim()?.lowercase().orEmpty()
        if (roots.isEmpty()) {
            treeContainer.addView(textView("暂无节点。", 13f))
            return
        }
        roots.forEach { renderNode(it, query) }
    }

    private fun nodeMatches(node: UiNode, query: String): Boolean {
        if (query.isBlank()) return true
        val j = node.json
        val own = listOf(
            j.optString("className"),
            j.optString("text"),
            j.optString("contentDescription"),
            j.optString("viewIdResourceName"),
            node.path
        ).joinToString(" ").lowercase()
        return own.contains(query) || node.children.any { nodeMatches(it, query) }
    }

    private fun renderNode(node: UiNode, query: String) {
        if (!nodeMatches(node, query)) return
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4 + node.depth * 13), dp(2), dp(4), dp(2))
            setBackgroundColor(
                if (selectedNode?.path == node.path) 0xFFDCEBFF.toInt() else 0x00000000
            )
        }
        val toggle = TextView(this).apply {
            text = when {
                node.children.isEmpty() -> "  "
                node.path in collapsedPaths && query.isBlank() -> "＋"
                else -> "－"
            }
            textSize = 14f
            setPadding(dp(5), dp(7), dp(5), dp(7))
            setOnClickListener {
                if (node.children.isNotEmpty()) {
                    if (!collapsedPaths.add(node.path)) collapsedPaths.remove(node.path)
                    renderTree()
                }
            }
        }
        row.addView(toggle)
        val label = TextView(this).apply {
            val cls = node.json.optString("className").substringAfterLast('.')
            val value = node.json.optString("text").replace('\n', ' ').take(46)
            text = buildString {
                append(cls.ifBlank { "Unknown" })
                if (value.isNotBlank()) append("  ·  ").append(value)
                append("  [").append(node.index).append(']')
            }
            textSize = 12f
            setTextColor(0xFF202124.toInt())
            setPadding(dp(4), dp(7), dp(4), dp(7))
            setOnClickListener {
                selectedNode = node
                showDetails(node)
                renderTree()
            }
        }
        row.addView(label, LinearLayout.LayoutParams(0, -2, 1f))
        row.setOnClickListener {
            selectedNode = node
            showDetails(node)
            renderTree()
        }
        treeContainer.addView(row)

        val shouldExpand = query.isNotBlank() || node.path !in collapsedPaths
        if (shouldExpand) node.children.forEach { renderNode(it, query) }
    }

    private fun showDetails(node: UiNode) {
        val j = node.json
        detailsView.text = buildString {
            append("节点 #${node.index}\n")
            append("类型：${j.optString("className")}\n")
            append("文字：${if (j.optBoolean("password")) "[已过滤：密码字段]" else j.optString("text")}\n")
            append("描述：${if (j.optBoolean("password")) "[已过滤：密码字段]" else j.optString("contentDescription")}\n")
            append("资源 ID：${j.optString("viewIdResourceName")}\n")
            append("路径：${node.path}\n")
            append("父节点：${node.parentPath ?: "无（根节点）"}\n")
            append("层级：${node.depth}\n")
            append("同级索引：${j.optInt("indexInParent")}\n")
            append("子节点数：${j.optInt("childCount")}\n")
            append("屏幕坐标：${j.optString("boundsInScreen")}\n")
            append("可点击：${j.optBoolean("clickable")}\n")
            append("可长按：${j.optBoolean("longClickable")}\n")
            append("可编辑：${j.optBoolean("editable")}\n")
            append("可滚动：${j.optBoolean("scrollable")}\n")
            append("可聚焦：${j.optBoolean("focusable")}\n")
            append("已启用：${j.optBoolean("enabled")}\n")
            append("密码字段：${j.optBoolean("password")}\n")
            append("操作：${j.optJSONArray("actions")?.toString() ?: "[]"}")
        }
    }

    private fun requestHighlight() {
        val node = selectedNode
        if (node == null) {
            Toast.makeText(this, "请先选择一个节点。", Toast.LENGTH_SHORT).show()
            return
        }
        if (targetPackageName.isBlank()) {
            Toast.makeText(this, "报告中没有目标应用包名。", Toast.LENGTH_LONG).show()
            return
        }
        val bounds = parseBounds(node.json.optString("boundsInScreen"))
        if (bounds == null || bounds.width() <= 0 || bounds.height() <= 0) {
            Toast.makeText(this, "该节点没有有效屏幕坐标。", Toast.LENGTH_LONG).show()
            return
        }

        getSharedPreferences("node_highlight", Context.MODE_PRIVATE).edit()
            .putString("package", targetPackageName)
            .putInt("left", bounds.left)
            .putInt("top", bounds.top)
            .putInt("right", bounds.right)
            .putInt("bottom", bounds.bottom)
            .putLong("requested_at", System.currentTimeMillis())
            .putBoolean("pending", true)
            .apply()

        Toast.makeText(
            this,
            "高亮请求已保存。请切换回 $targetPackageName，红框将在对应页面出现约 2.2 秒。",
            Toast.LENGTH_LONG
        ).show()
        finish()
    }

    private fun parseBounds(value: String): Rect? {
        val numbers = Regex("-?\\d+").findAll(value)
            .mapNotNull { it.value.toIntOrNull() }
            .toList()
        if (numbers.size < 4) return null
        return Rect(numbers[0], numbers[1], numbers[2], numbers[3])
    }

    private fun smallButton(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        textSize = 11f
        isAllCaps = false
        setPadding(dp(2), 0, dp(2), 0)
        setOnClickListener { action() }
    }

    private fun actionButton(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { action() }
    }

    private fun textView(value: String, size: Float) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(0xFF202124.toInt())
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()
}
