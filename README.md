# Acb Visual Inspector V2

基于原仓库 `https://github.com/ttiaogou/Acb` 的节点采集思路和已交付文件整理的独立 Android Studio 工程。当前版本聚焦于无障碍节点可视化，不包含 AI 回复或消息发送功能。

## 功能

- 用户主动开启 AccessibilityService。
- 必须指定一个目标应用包名；只采集该包名对应的当前窗口。
- 监听窗口变化并合并短时间内的连续事件，约 450ms 后采集快照。
- 读取 className、text、contentDescription、resource ID、屏幕坐标、父子路径、点击/编辑/滚动等属性。
- 通过 `isPassword` 或常见密码 `inputType` 识别的密码节点及其后代节点，其 text/contentDescription 不写入报告；只保留 password 标志和计数。
- 在可视化页面按层级展开/折叠节点，支持按文字、类型、资源 ID、路径搜索。
- 选择节点查看详细属性。
- 保存高亮请求，用户切回目标应用后由无障碍覆盖层显示约 2.2 秒边框。
- 节点报告只写入应用私有目录；用户主动选择文件位置时才导出 JSON。

## 打开工程

1. 安装 Android Studio，使用 JDK 17 或更高版本。
2. 在 Android Studio 选择 **Open**，打开本目录（包含根目录 `settings.gradle.kts` 的目录）。
3. Android Studio 首次同步时会下载 Android Gradle Plugin、Kotlin Gradle Plugin 和 Gradle 依赖。
4. 在 SDK Manager 安装 Android SDK Platform 36 和对应 Build Tools。
5. 运行 `app` 配置，将应用安装到测试设备。

项目配置：AGP 8.13.2、Kotlin Android Plugin 2.3.21、Gradle 8.13、compileSdk 36、minSdk 23、targetSdk 34、JVM 17。此交付未包含 Gradle Wrapper JAR；通过 Android Studio 打开并同步，或在本机安装 Gradle 8.13 后从项目根目录执行 `gradle :app:assembleDebug`。

## 使用

1. 启动应用，打开系统无障碍设置，启用“Acb 节点检测服务”。
2. 在主界面输入目标应用包名。例如陌陌：`com.immomo.momo`。
3. 点击“开始监测”。
4. 切换到目标应用的目标页面；窗口内容变化后自动生成本机快照。
5. 返回 Acb，点击“打开可视化节点树”。展开节点、搜索并选择目标节点。
6. 点击“保存高亮请求并切回目标应用”。随后从最近任务切回目标应用的同一页面；服务会在匹配包名出现时显示短暂红框。
7. 需要保存报告时，在主界面点击“导出 JSON 报告”，再使用系统文件选择器选择保存位置。

## 重要限制

- 只能读取目标应用实际向无障碍服务公开的节点。自绘画布、受保护窗口及未公开的文本无法被本工具恢复。
- 节点路径是某次快照中的树路径，不是跨页面/滚动持久化 ID。
- 高亮使用采集快照的屏幕坐标；如果目标应用布局发生变化或滚动，红框可能不再指向原节点。请在选中节点后尽快切回同一页面。
- 无障碍覆盖层和后台服务行为受 Android 版本、厂商系统和目标应用实现影响，必须在实际设备上验证。
- 工具默认不读取非目标应用；目标包名不能为空。
- 节点文本可能包含聊天、个人或其他敏感信息。报告保存在应用私有目录，导出前应检查内容；不要将报告上传到不可信位置。

## 构建状态

交付环境没有 Android SDK、Gradle 安装和 Android 真机，因而无法在此处完成 `assembleDebug` 或真机测试。该工程已完成文件结构和配置整合，但仍需在 Android Studio 中同步、编译和设备验证。不能将本包视为已通过编译或测试的 APK。
