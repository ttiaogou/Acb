# 集成步骤

目标仓库：`ttiaogou/Acb`，默认分支 `main`。

## 1. 添加文件

将本补丁中的三个 Kotlin 文件复制到仓库对应路径：

- `VisualTreeActivity.kt` -> `app/src/main/java/com/ttiaogou/accessibilityinspector/visual/VisualTreeActivity.kt`
- `NodeHighlightOverlay.kt` -> `app/src/main/java/com/ttiaogou/accessibilityinspector/accessibility/NodeHighlightOverlay.kt`
- `InspectorAccessibilityService.kt` -> 覆盖 `app/src/main/java/com/ttiaogou/accessibilityinspector/accessibility/InspectorAccessibilityService.kt`

## 2. 注册 Activity

在 `app/src/main/AndroidManifest.xml` 的 `<application>` 内增加：

```xml
<activity
    android:name=".visual.VisualTreeActivity"
    android:exported="false"
    android:label="节点树可视化" />
```

## 3. 在 MainActivity 增加入口

在 `MainActivity.kt` 增加 import：

```kotlin
import com.ttiaogou.accessibilityinspector.visual.VisualTreeActivity
```

在 `buildScreen()` 中合适位置增加按钮：

```kotlin
root.addView(actionButton("打开可视化节点树") {
    startActivity(Intent(this, VisualTreeActivity::class.java))
})
```

## 4. 使用流程

1. 安装并运行应用。
2. 开启无障碍服务。
3. 开始监测并切换到目标应用页面。
4. 返回检查器，点击“打开可视化节点树”。
5. 点击某个节点查看详情。
6. 点击“在目标应用中高亮选中节点”。
7. 检查器会启动目标应用；服务在目标包名窗口事件到达时显示红框。

## 5. 已知边界

- 当前节点路径由扁平列表中的 `depth` 和先序顺序重建，不改变原始 JSON schema。
- 如果采集快照已过时，节点边界可能与目标页面当前布局不一致；应先重新采集。
- 如果目标 App 不提供对应无障碍节点，检查器无法从节点树中创建它。
- 某些应用会阻止或限制无障碍覆盖层显示。
- 生产使用时建议再加目标包名白名单、报告脱敏与高亮有效期。
