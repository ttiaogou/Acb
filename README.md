# Acb Visual Inspector V2 — 集成补丁

本补丁为 `ttiaogou/Acb` 增加一个基于原生 Android View 的节点可视化页面：

- 按 `depth` 重建父子关系并生成路径；
- 点击节点显示属性详情；
- 支持节点展开/折叠、全部展开/折叠；
- 保存所选节点的 bounds；
- 返回目标应用后由无障碍服务绘制约 2.2 秒的红色边框；
- 不引入 Compose 依赖。

## 文件

- `VisualTreeActivity.kt`：新增节点树与详情界面
- `NodeHighlightOverlay.kt`：新增无障碍覆盖层高亮
- `InspectorAccessibilityService.kt`：服务替换版本
- `INTEGRATION.md`：必须执行的合并步骤

## 注意

这是基于仓库当前源码结构制作的集成补丁，尚未在 Android Studio/真机编译验证。应用包名、Activity 声明和 Android 版本差异应在集成后检查。高亮坐标依赖目标应用窗口与屏幕坐标系一致；部分多窗口、刘海屏或系统缩放场景需要真机校准。
