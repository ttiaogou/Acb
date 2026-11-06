package com.ttiaogou.accessibilityinspector.accessibility

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject

private const val MAX_NODES = 3000
private const val MAX_DEPTH = 80

data class NodeStats(
    var total: Int = 0,
    var withText: Int = 0,
    var withDescription: Int = 0,
    var buttonLike: Int = 0,
    var editTexts: Int = 0,
    var clickable: Int = 0,
    var longClickable: Int = 0,
    var scrollable: Int = 0,
    var focusable: Int = 0,
    var checkable: Int = 0,
    var resourceIds: Int = 0,
    var imageLike: Int = 0
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("total", total)
        put("withText", withText)
        put("withDescription", withDescription)
        put("buttonLike", buttonLike)
        put("editTexts", editTexts)
        put("clickable", clickable)
        put("longClickable", longClickable)
        put("scrollable", scrollable)
        put("focusable", focusable)
        put("checkable", checkable)
        put("resourceIds", resourceIds)
        put("imageLike", imageLike)
    }
}

data class NodeRecord(
    val index: Int,
    val depth: Int,
    val className: String,
    val text: String,
    val contentDescription: String,
    val viewId: String,
    val packageName: String,
    val bounds: String,
    val clickable: Boolean,
    val longClickable: Boolean,
    val editable: Boolean,
    val scrollable: Boolean,
    val focusable: Boolean,
    val focused: Boolean,
    val enabled: Boolean,
    val checkable: Boolean,
    val checked: Boolean,
    val selected: Boolean,
    val password: Boolean,
    val childCount: Int,
    val actions: List<String>
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("index", index)
        put("depth", depth)
        put("className", className)
        put("text", text)
        put("contentDescription", contentDescription)
        put("viewIdResourceName", viewId)
        put("packageName", packageName)
        put("boundsInScreen", bounds)
        put("clickable", clickable)
        put("longClickable", longClickable)
        put("editable", editable)
        put("scrollable", scrollable)
        put("focusable", focusable)
        put("focused", focused)
        put("enabled", enabled)
        put("checkable", checkable)
        put("checked", checked)
        put("selected", selected)
        put("password", password)
        put("childCount", childCount)
        put("actions", JSONArray(actions))
    }
}

data class InspectionSnapshot(
    val capturedAtMillis: Long,
    val packageName: String,
    val rootClassName: String,
    val stats: NodeStats,
    val nodes: List<NodeRecord>,
    val truncated: Boolean
) {
    fun toJsonString(): String = JSONObject().apply {
        put("schemaVersion", 1)
        put("capturedAtMillis", capturedAtMillis)
        put("packageName", packageName)
        put("rootClassName", rootClassName)
        put("truncated", truncated)
        put("stats", stats.toJson())
        put("nodes", JSONArray().apply {
            nodes.forEach { put(it.toJson()) }
        })
    }.toString(2)
}

object NodeInspector {
    fun inspect(root: AccessibilityNodeInfo, fallbackPackageName: String): InspectionSnapshot {
        val stats = NodeStats()
        val records = ArrayList<NodeRecord>()
        walk(root, 0, fallbackPackageName, records, stats)

        return InspectionSnapshot(
            capturedAtMillis = System.currentTimeMillis(),
            packageName = safeString { root.packageName?.toString() }.ifBlank { fallbackPackageName },
            rootClassName = safeString { root.className?.toString() },
            stats = stats,
            nodes = records,
            truncated = stats.total >= MAX_NODES
        )
    }

    private fun walk(
        node: AccessibilityNodeInfo,
        depth: Int,
        fallbackPackageName: String,
        records: MutableList<NodeRecord>,
        stats: NodeStats
    ) {
        if (depth > MAX_DEPTH || records.size >= MAX_NODES) return

        val className = safeString { node.className?.toString() }
        val text = safeString { node.text?.toString() }
        val description = safeString { node.contentDescription?.toString() }
        val viewId = safeString { node.viewIdResourceName }
        val packageName = safeString { node.packageName?.toString() }.ifBlank { fallbackPackageName }
        val bounds = Rect().also { rect ->
            runCatching { node.getBoundsInScreen(rect) }
        }.toShortString()
        val actions = runCatching {
            node.actionList.map { action ->
                val label = action.label?.toString()?.takeIf { it.isNotBlank() }
                if (label == null) "id:${action.id}" else "id:${action.id} $label"
            }
        }.getOrDefault(emptyList())

        val record = NodeRecord(
            index = records.size,
            depth = depth,
            className = className,
            text = text,
            contentDescription = description,
            viewId = viewId,
            packageName = packageName,
            bounds = bounds,
            clickable = safeBoolean { node.isClickable },
            longClickable = safeBoolean { node.isLongClickable },
            editable = safeBoolean { node.isEditable },
            scrollable = safeBoolean { node.isScrollable },
            focusable = safeBoolean { node.isFocusable },
            focused = safeBoolean { node.isFocused },
            enabled = safeBoolean { node.isEnabled },
            checkable = safeBoolean { node.isCheckable },
            checked = safeBoolean { node.isChecked },
            selected = safeBoolean { node.isSelected },
            password = safeBoolean { node.isPassword },
            childCount = safeInt { node.childCount },
            actions = actions
        )
        records.add(record)

        stats.total++
        if (text.isNotBlank()) stats.withText++
        if (description.isNotBlank()) stats.withDescription++
        if (className.contains("Button", ignoreCase = true)) stats.buttonLike++
        if (className.contains("EditText", ignoreCase = true) || record.editable) stats.editTexts++
        if (record.clickable) stats.clickable++
        if (record.longClickable) stats.longClickable++
        if (record.scrollable) stats.scrollable++
        if (record.focusable) stats.focusable++
        if (record.checkable) stats.checkable++
        if (viewId.isNotBlank()) stats.resourceIds++
        if (className.contains("Image", ignoreCase = true)) stats.imageLike++

        val childCount = record.childCount
        for (index in 0 until childCount) {
            if (records.size >= MAX_NODES) break
            val child = runCatching { node.getChild(index) }.getOrNull() ?: continue
            walk(child, depth + 1, fallbackPackageName, records, stats)
        }
    }

    private inline fun safeString(block: () -> String?): String =
        runCatching { block().orEmpty() }.getOrDefault("")

    private inline fun safeBoolean(block: () -> Boolean): Boolean =
        runCatching(block).getOrDefault(false)

    private inline fun safeInt(block: () -> Int): Int =
        runCatching(block).getOrDefault(0)
}
