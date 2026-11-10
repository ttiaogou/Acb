package com.ttiaogou.accessibilityinspector.accessibility

import android.graphics.Rect
import android.text.InputType
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
    var imageLike: Int = 0,
    var passwordFields: Int = 0
) {
    fun toJson() = JSONObject().apply {
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
        put("passwordFields", passwordFields)
    }
}

data class NodeRecord(
    val index: Int,
    val depth: Int,
    val path: String,
    val parentPath: String?,
    val indexInParent: Int,
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
    fun toJson() = JSONObject().apply {
        put("index", index)
        put("depth", depth)
        put("path", path)
        put("parentPath", parentPath ?: JSONObject.NULL)
        put("indexInParent", indexInParent)
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
        put("schemaVersion", 2)
        put("capturedAtMillis", capturedAtMillis)
        put("packageName", packageName)
        put("rootClassName", rootClassName)
        put("truncated", truncated)
        put("stats", stats.toJson())
        put("nodes", JSONArray().apply { nodes.forEach { put(it.toJson()) } })
    }.toString(2)
}

object NodeInspector {
    fun inspect(root: AccessibilityNodeInfo, fallbackPackageName: String): InspectionSnapshot {
        val stats = NodeStats()
        val records = ArrayList<NodeRecord>()
        walk(
            node = root,
            depth = 0,
            path = "0",
            parentPath = null,
            indexInParent = 0,
            inheritedSensitive = false,
            fallbackPackageName = fallbackPackageName,
            records = records,
            stats = stats
        )
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
        path: String,
        parentPath: String?,
        indexInParent: Int,
        inheritedSensitive: Boolean,
        fallbackPackageName: String,
        records: MutableList<NodeRecord>,
        stats: NodeStats
    ) {
        if (depth > MAX_DEPTH || records.size >= MAX_NODES) return

        val className = safeString { node.className?.toString() }
        val isPassword = safeBoolean { node.isPassword } || isPasswordInput(node)
        // 密码标记向下继承，避免自定义密码控件把真实值放在子节点中。
        val sensitive = inheritedSensitive || isPassword
        val text = if (sensitive) "" else safeString { node.text?.toString() }
        val description = if (sensitive) "" else safeString { node.contentDescription?.toString() }
        val viewId = safeString { node.viewIdResourceName }
        val packageName = safeString { node.packageName?.toString() }.ifBlank { fallbackPackageName }
        val rect = Rect()
        runCatching { node.getBoundsInScreen(rect) }
        val actions = runCatching {
            node.actionList.map { action ->
                val label = action.label?.toString()?.takeIf { it.isNotBlank() }
                if (label == null) "id:${action.id}" else "id:${action.id} $label"
            }
        }.getOrDefault(emptyList())
        val childCount = safeInt { node.childCount }

        val record = NodeRecord(
            index = records.size,
            depth = depth,
            path = path,
            parentPath = parentPath,
            indexInParent = indexInParent,
            className = className,
            text = text,
            contentDescription = description,
            viewId = viewId,
            packageName = packageName,
            bounds = rect.toShortString(),
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
            password = isPassword || inheritedSensitive,
            childCount = childCount,
            actions = actions
        )
        records.add(record)

        stats.total++
        if (text.isNotBlank()) stats.withText++
        if (description.isNotBlank()) stats.withDescription++
        if (className.contains("Button", true)) stats.buttonLike++
        if (className.contains("EditText", true) || record.editable) stats.editTexts++
        if (record.clickable) stats.clickable++
        if (record.longClickable) stats.longClickable++
        if (record.scrollable) stats.scrollable++
        if (record.focusable) stats.focusable++
        if (record.checkable) stats.checkable++
        if (viewId.isNotBlank()) stats.resourceIds++
        if (className.contains("Image", true)) stats.imageLike++
        if (isPassword) stats.passwordFields++

        for (childIndex in 0 until childCount) {
            if (records.size >= MAX_NODES) break
            val child = runCatching { node.getChild(childIndex) }.getOrNull() ?: continue
            walk(
                node = child,
                depth = depth + 1,
                path = "$path/$childIndex",
                parentPath = path,
                indexInParent = childIndex,
                inheritedSensitive = sensitive,
                fallbackPackageName = fallbackPackageName,
                records = records,
                stats = stats
            )
        }
    }

    private fun isPasswordInput(node: AccessibilityNodeInfo): Boolean {
        val inputType = safeInt { node.inputType }
        val inputClass = inputType and InputType.TYPE_MASK_CLASS
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        return (inputClass == InputType.TYPE_CLASS_TEXT && variation in setOf(
            InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
        )) || (inputClass == InputType.TYPE_CLASS_NUMBER &&
            variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD)
    }

    private inline fun safeString(block: () -> String?): String =
        runCatching { block().orEmpty() }.getOrDefault("")
    private inline fun safeBoolean(block: () -> Boolean): Boolean =
        runCatching(block).getOrDefault(false)
    private inline fun safeInt(block: () -> Int): Int =
        runCatching(block).getOrDefault(0)
}
