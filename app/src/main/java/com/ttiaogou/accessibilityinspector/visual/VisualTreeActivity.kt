package com.ttiaogou.accessibilityinspector.visual

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.ttiaogou.accessibilityinspector.data.InspectorStore
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.min

class VisualTreeActivity : Activity() {
    private enum class NodeFilter { ALL, CLICKABLE, EDITABLE, SCROLLABLE }

    private data class UiNode(
        val key: String,
        val index: Int,
        val depth: Int,
        val path: String,
        val parentPath: String?,
        val packageName: String,
        val json: JSONObject,
        val children: MutableList<UiNode> = mutableListOf()
    ) {
        fun bounds(): Rect? = parseNodeBounds(json.optString("boundsInScreen"))
        fun isClickTarget(): Boolean = json.optBoolean("clickable") || hasAction(16)
        fun hasAction(actionId: Int): Boolean {
            val actions = json.optJSONArray("actions") ?: return false
            return (0 until actions.length()).any { i ->
                Regex("(?:^|\\s)id:${actionId}(?:\\s|$)").containsMatchIn(actions.optString(i))
            }
        }
    }

    private lateinit var treeContainer: LinearLayout
    private lateinit var detailsView: TextView
    private lateinit var searchInput: EditText
    private lateinit var headerView: TextView
    private lateinit var screenMap: ScreenMapView
    private val nodes = mutableListOf<UiNode>()
    private val roots = mutableListOf<UiNode>()
    private val collapsedPaths = mutableSetOf<String>()
    private var selectedNode: UiNode? = null
    private var targetPackageName: String = ""
    private var activeFilter = NodeFilter.ALL
    private val filterButtons = mutableMapOf<NodeFilter, Button>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildScreen()
        loadSnapshot()
    }

    private fun buildScreen() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        headerView = TextView(this).apply {
            textSize = 15f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(0xFF202124.toInt())
            setPadding(0, dp(2), 0, dp(4))
        }
        root.addView(headerView)

        searchInput = EditText(this).apply {
            isSingleLine = true
            hint = "搜索文字、描述、类型、资源 ID 或路径"
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = renderTree()
                override fun afterTextChanged(s: Editable?) = Unit
            })
        }
        root.addView(searchInput)

        val controls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        controls.addView(smallButton("刷新") { loadSnapshot() })
        controls.addView(smallButton("展开") { collapsedPaths.clear(); renderTree() })
        controls.addView(smallButton("折叠") {
            nodes.filter { it.children.isNotEmpty() }.forEach { collapsedPaths.add(it.path) }
            renderTree()
        })
        root.addView(controls)

        val filterScroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        val filters = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        filters.addView(filterButton("全部", NodeFilter.ALL))
        filters.addView(filterButton("可点击", NodeFilter.CLICKABLE))
        filters.addView(filterButton("可编辑", NodeFilter.EDITABLE))
        filters.addView(filterButton("可滚动", NodeFilter.SCROLLABLE))
        filterScroll.addView(filters)
        root.addView(filterScroll)

        root.addView(TextView(this).apply {
            text = "屏幕映射（点选矩形可定位节点；红框为当前选中节点）"
            textSize = 11f
            setTextColor(0xFF5F6368.toInt())
            setPadding(dp(2), dp(4), 0, dp(3))
        })
        screenMap = ScreenMapView(this)
        root.addView(screenMap, LinearLayout.LayoutParams(-1, dp(166)))

        val treeTitle = TextView(this).apply {
            text = "节点树"
            textSize = 13f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(0xFF202124.toInt())
            setPadding(dp(2), dp(5), 0, dp(2))
        }
        root.addView(treeTitle)
        val treeScroll = ScrollView(this)
        treeContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(2), 0, dp(2))
        }
        treeScroll.addView(treeContainer)
        root.addView(treeScroll, LinearLayout.LayoutParams(-1, 0, 1f))

        root.addView(View(this).apply { setBackgroundColor(0xFFCFD8DC.toInt()) }, LinearLayout.LayoutParams(-1, dp(1)))
        detailsView = TextView(this).apply {
            textSize = 11.5f
            setTextColor(0xFF202124.toInt())
            setTextIsSelectable(true)
            setPadding(dp(8), dp(6), dp(8), dp(6))
            text = "选择节点查看详细属性。"
        }
        val detailsScroll = ScrollView(this)
        detailsScroll.addView(detailsView)
        root.addView(detailsScroll, LinearLayout.LayoutParams(-1, dp(138)))

        root.addView(actionButton("保存高亮请求并切回目标应用") { requestHighlight() })
        root.addView(actionButton("返回") { finish() })
        setContentView(root)
    }

    private fun filterButton(label: String, filter: NodeFilter) = Button(this).apply {
        text = label
        textSize = 10f
        isAllCaps = false
        setPadding(dp(4), 0, dp(4), 0)
        filterButtons[filter] = this
        setOnClickListener {
            activeFilter = filter
            renderTree()
        }
    }

    private fun loadSnapshot() {
        val raw = InspectorStore.getLastReport(this)
        if (raw.isNullOrBlank()) {
            headerView.text = "没有检测报告。请先返回主界面，开始监测目标应用。"
            nodes.clear(); roots.clear(); selectedNode = null
            renderTree()
            detailsView.text = "暂无节点数据。"
            screenMap.invalidate()
            return
        }

        try {
            val report = JSONObject(raw)
            targetPackageName = report.optString("packageName")
            val array = report.optJSONArray("nodes") ?: JSONArray()
            nodes.clear(); roots.clear()
            val byPath = linkedMapOf<String, UiNode>()
            val fallbackStack = mutableListOf<String>()
            val siblingCount = mutableMapOf<String, Int>()

            for (i in 0 until array.length()) {
                val json = array.optJSONObject(i) ?: continue
                val depth = json.optInt("depth", 0).coerceAtLeast(0)
                while (fallbackStack.size > depth) fallbackStack.removeAt(fallbackStack.lastIndex)
                val parentFromJson = if (json.has("parentPath") && !json.isNull("parentPath"))
                    json.optString("parentPath").takeIf { it.isNotBlank() } else null
                val parentPath = parentFromJson ?: if (depth > 0) fallbackStack.getOrNull(depth - 1) else null
                val siblingKey = parentPath ?: "<root>"
                val inferredIndex = siblingCount.getOrDefault(siblingKey, 0)
                siblingCount[siblingKey] = inferredIndex + 1
                val indexInParent = json.optInt("indexInParent", inferredIndex)
                val path = json.optString("path").takeIf { it.isNotBlank() }
                    ?: if (parentPath == null) indexInParent.toString() else "$parentPath/$indexInParent"
                val snapshotIndex = nodes.size
                val node = UiNode(
                    key = "node_$snapshotIndex",
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
                if (parent != null && parent !== node) parent.children.add(node) else roots.add(node)
            }
            collapsedPaths.clear()
            nodes.filter { it.depth >= 1 && it.children.isNotEmpty() }.forEach { collapsedPaths.add(it.path) }
            selectedNode = null
            val clickableCount = nodes.count { it.isClickTarget() }
            headerView.text = "${targetPackageName}  ·  ${nodes.size} 节点  ·  ${clickableCount} 可点击"
            detailsView.text = "选择节点查看详细属性。\n快照时间：${report.optLong("capturedAtMillis")}\n节点树中的 nodeKey 仅在本次快照内唯一。"
            screenMap.invalidate()
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
            updateFilterButtonStates()
            return
        }
        roots.forEach { renderNode(it, query) }
        if (treeContainer.childCount == 0) treeContainer.addView(textView("没有符合筛选条件的节点。", 12f))
        screenMap.invalidate()
        updateFilterButtonStates()
    }

    private fun updateFilterButtonStates() {
        filterButtons.forEach { (filter, button) ->
            button.alpha = if (filter == activeFilter) 1f else 0.62f
            button.isSelected = filter == activeFilter
        }
    }

    private fun nodeOwnMatches(node: UiNode, query: String): Boolean {
        val j = node.json
        val queryMatches = query.isBlank() || listOf(
            j.optString("className"), j.optString("text"), j.optString("contentDescription"),
            j.optString("viewIdResourceName"), j.optString("packageName"), node.path, node.key
        ).joinToString(" ").lowercase().contains(query)
        val filterMatches = when (activeFilter) {
            NodeFilter.ALL -> true
            NodeFilter.CLICKABLE -> node.isClickTarget()
            NodeFilter.EDITABLE -> j.optBoolean("editable") || j.optString("className").contains("EditText", true)
            NodeFilter.SCROLLABLE -> j.optBoolean("scrollable")
        }
        return queryMatches && filterMatches
    }

    private fun subtreeMatches(node: UiNode, query: String): Boolean =
        nodeOwnMatches(node, query) || node.children.any { subtreeMatches(it, query) }

    private fun renderNode(node: UiNode, query: String) {
        if (!subtreeMatches(node, query)) return
        val selected = selectedNode?.key == node.key
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4 + node.depth * 12), dp(1), dp(3), dp(1))
            setBackgroundColor(if (selected) 0xFFDCEBFF.toInt() else Color.TRANSPARENT)
        }
        val toggle = TextView(this).apply {
            text = when {
                node.children.isEmpty() -> "  "
                node.path in collapsedPaths && query.isBlank() && activeFilter == NodeFilter.ALL -> "＋"
                else -> "－"
            }
            textSize = 13f
            setPadding(dp(4), dp(5), dp(4), dp(5))
            setOnClickListener {
                if (node.children.isNotEmpty()) {
                    if (!collapsedPaths.add(node.path)) collapsedPaths.remove(node.path)
                    renderTree()
                }
            }
        }
        row.addView(toggle)
        val label = TextView(this).apply {
            val cls = node.json.optString("className").substringAfterLast('.').ifBlank { "Unknown" }
            val value = (node.json.optString("text").ifBlank { node.json.optString("contentDescription") })
                .replace('\n', ' ').take(34)
            val badges = buildList {
                if (node.isClickTarget()) add("可点击")
                if (node.json.optBoolean("longClickable")) add("长按")
                if (node.json.optBoolean("editable")) add("输入")
                if (node.json.optBoolean("scrollable")) add("滚动")
            }
            text = buildString {
                append(cls)
                if (value.isNotBlank()) append(" · ").append(value)
                if (badges.isNotEmpty()) append("  【").append(badges.joinToString("/")).append("】")
                append("  #").append(node.key.removePrefix("node_"))
            }
            textSize = 11.5f
            setTextColor(if (node.isClickTarget()) 0xFF9A4D00.toInt() else 0xFF202124.toInt())
            setPadding(dp(3), dp(6), dp(3), dp(6))
            setOnClickListener { selectNode(node) }
        }
        row.addView(label, LinearLayout.LayoutParams(0, -2, 1f))
        row.setOnClickListener { selectNode(node) }
        treeContainer.addView(row)

        val shouldExpand = query.isNotBlank() || activeFilter != NodeFilter.ALL || node.path !in collapsedPaths
        if (shouldExpand) node.children.forEach { renderNode(it, query) }
    }

    private fun selectNode(node: UiNode) {
        selectedNode = node
        var ancestorPath = node.parentPath
        while (ancestorPath != null) {
            collapsedPaths.remove(ancestorPath)
            ancestorPath = nodes.firstOrNull { it.path == ancestorPath }?.parentPath
        }
        showDetails(node)
        screenMap.invalidate()
        renderTree()
    }

    private fun showDetails(node: UiNode) {
        val j = node.json
        detailsView.text = buildString {
            append("节点标识：${node.key}（本次快照内唯一）\n")
            append("类型：${j.optString("className")}\n")
            append("文字：${if (j.optBoolean("password")) "[已过滤：密码字段]" else j.optString("text")}\n")
            append("描述：${if (j.optBoolean("password")) "[已过滤：密码字段]" else j.optString("contentDescription")}\n")
            append("资源 ID：${j.optString("viewIdResourceName")}\n")
            append("包名：${node.packageName}\n")
            append("路径：${node.path}\n")
            append("父节点：${node.parentPath ?: "无（根节点）"}\n")
            append("层级：${node.depth}  同级索引：${j.optInt("indexInParent")}  子节点数：${j.optInt("childCount")}\n")
            append("屏幕坐标：${j.optString("boundsInScreen")}\n")
            append("可点击：${node.isClickTarget()}  可长按：${j.optBoolean("longClickable")}\n")
            append("可编辑：${j.optBoolean("editable")}  可滚动：${j.optBoolean("scrollable")}\n")
            append("可聚焦：${j.optBoolean("focusable")}  已聚焦：${j.optBoolean("focused")}\n")
            append("已启用：${j.optBoolean("enabled")}  可选中：${j.optBoolean("checkable")}  已选中：${j.optBoolean("checked")}\n")
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
        val bounds = node.bounds()
        if (bounds == null || bounds.width() <= 0 || bounds.height() <= 0) {
            Toast.makeText(this, "该节点没有有效屏幕坐标。", Toast.LENGTH_LONG).show()
            return
        }
        getSharedPreferences("node_highlight", Context.MODE_PRIVATE).edit()
            .putString("package", targetPackageName)
            .putInt("left", bounds.left).putInt("top", bounds.top)
            .putInt("right", bounds.right).putInt("bottom", bounds.bottom)
            .putLong("requested_at", System.currentTimeMillis())
            .putBoolean("pending", true).apply()
        Toast.makeText(this, "高亮请求已保存。请切换回 $targetPackageName，红框将在对应页面出现约 2.2 秒。", Toast.LENGTH_LONG).show()
        finish()
    }

    private inner class ScreenMapView(context: Context) : View(context) {
        private val screenWidth = resources.displayMetrics.widthPixels.coerceAtLeast(1).toFloat()
        private val screenHeight = resources.displayMetrics.heightPixels.coerceAtLeast(1).toFloat()
        private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF202A35.toInt(); style = Paint.Style.FILL }
        private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFB0BEC5.toInt(); style = Paint.Style.STROKE; strokeWidth = dp(1).toFloat() }
        private val nodePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF8FA3B8.toInt(); style = Paint.Style.STROKE; strokeWidth = dp(1).toFloat() }
        private val clickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFB74D.toInt(); style = Paint.Style.STROKE; strokeWidth = dp(1.5f).toFloat() }
        private val selectedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.RED; style = Paint.Style.STROKE; strokeWidth = dp(2.5f).toFloat() }
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = dp(10).toFloat() }
        private var scale = 1f
        private var offsetX = 0f
        private var offsetY = 0f
        private var frame = RectFCompat(0f, 0f, 0f, 0f)

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            canvas.drawColor(0xFFF1F3F5.toInt())
            val availableW = (width - dp(16)).coerceAtLeast(1).toFloat()
            val availableH = (height - dp(10)).coerceAtLeast(1).toFloat()
            scale = min(availableW / screenWidth, availableH / screenHeight)
            val mappedW = screenWidth * scale
            val mappedH = screenHeight * scale
            offsetX = (width - mappedW) / 2f
            offsetY = (height - mappedH) / 2f
            frame = RectFCompat(offsetX, offsetY, offsetX + mappedW, offsetY + mappedH)
            canvas.drawRect(frame.left, frame.top, frame.right, frame.bottom, backgroundPaint)
            canvas.drawRect(frame.left, frame.top, frame.right, frame.bottom, framePaint)

            nodes.filter { it.key != selectedNode?.key }.forEach { node ->
                val rect = node.bounds() ?: return@forEach
                val mapped = mapRect(rect) ?: return@forEach
                val paint = if (node.isClickTarget()) clickPaint else nodePaint
                canvas.drawRect(mapped.left, mapped.top, mapped.right, mapped.bottom, paint)
            }
            selectedNode?.bounds()?.let { rect ->
                mapRect(rect)?.let { mapped ->
                    canvas.drawRect(mapped.left, mapped.top, mapped.right, mapped.bottom, selectedPaint)
                }
            }
            canvas.drawText("橙色：可点击  红色：当前选中", dp(5).toFloat(), (height - dp(5)).toFloat(), textPaint)
        }

        private fun mapRect(rect: Rect): RectFCompat? {
            if (rect.width() <= 0 || rect.height() <= 0) return null
            val left = (offsetX + rect.left * scale).coerceIn(frame.left, frame.right)
            val top = (offsetY + rect.top * scale).coerceIn(frame.top, frame.bottom)
            val right = (offsetX + rect.right * scale).coerceIn(frame.left, frame.right)
            val bottom = (offsetY + rect.bottom * scale).coerceIn(frame.top, frame.bottom)
            return if (right > left && bottom > top) RectFCompat(left, top, right, bottom) else null
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (event.action != MotionEvent.ACTION_UP || scale <= 0f) return true
            if (!frame.contains(event.x, event.y)) return true
            val x = ((event.x - offsetX) / scale).toInt()
            val y = ((event.y - offsetY) / scale).toInt()
            val hit = nodes.asSequence()
                .mapNotNull { node -> node.bounds()?.let { rect -> if (rect.contains(x, y)) node to rect else null } }
                .sortedWith(compareByDescending<Pair<UiNode, Rect>> { it.first.depth }
                    .thenBy { it.second.width().toLong() * it.second.height().toLong() })
                .firstOrNull()?.first
            if (hit != null) selectNode(hit)
            return true
        }
    }

    private data class RectFCompat(val left: Float, val top: Float, val right: Float, val bottom: Float) {
        fun contains(x: Float, y: Float) = x >= left && x <= right && y >= top && y <= bottom
    }

    private fun smallButton(label: String, action: () -> Unit) = Button(this).apply {
        text = label; textSize = 10f; isAllCaps = false
        setPadding(dp(2), 0, dp(2), 0)
        setOnClickListener { action() }
    }

    private fun actionButton(label: String, action: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false
        setOnClickListener { action() }
    }

    private fun textView(value: String, size: Float) = TextView(this).apply {
        text = value; textSize = size; setTextColor(0xFF202124.toInt())
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()
    private fun dp(value: Float): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

}

private fun parseNodeBounds(value: String): Rect? {
    val numbers = Regex("-?\\d+").findAll(value).mapNotNull { it.value.toIntOrNull() }.toList()
    if (numbers.size < 4) return null
    val rect = Rect(numbers[0], numbers[1], numbers[2], numbers[3])
    return rect.takeIf { it.width() > 0 && it.height() > 0 }
}
