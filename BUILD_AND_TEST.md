# 构建与验收清单

## 编译

- [ ] Android Studio 使用 JDK 17+
- [ ] 安装 Android SDK Platform 36
- [ ] Gradle 同步成功（AGP 8.13.2 / Kotlin Android Plugin 2.3.21）
- [ ] `:app:assembleDebug` 成功
- [ ] 安装 debug APK 成功

## 节点可视化功能

- [ ] 开启无障碍服务并设置目标应用包名
- [ ] 进入目标应用页面，生成节点快照
- [ ] 节点树按 parentPath/path 组成父子层级，可展开、折叠
- [ ] 搜索文字、contentDescription、className、资源 ID、包名、路径和 nodeKey
- [ ] 使用“可点击 / 可编辑 / 可滚动”筛选节点
- [ ] 点击树节点后，详情区域显示节点属性和快照内唯一 nodeKey
- [ ] 屏幕映射绘制节点 boundsInScreen；可点击节点用橙色边框，选中节点用红色边框
- [ ] 点击屏幕映射中的节点矩形，可以反向选中节点树项目
- [ ] 空坐标及宽高为零的矩形不绘制；部分越界矩形按屏幕边界裁剪显示
- [ ] 切回目标应用同一页面后，Accessibility overlay 显示短暂红框

## 数据安全与回归

- [ ] 导出 JSON 后能使用文本编辑器正常打开
- [ ] 用测试密码输入框确认报告中没有密码文本或描述
- [ ] 切换应用时确认白名单外应用不会生成报告
- [ ] 列表滚动或页面变化后重新采集，确认坐标来自新快照

## 当前验证状态

本次修改完成源码级静态检查；当前交付环境没有可用 Android SDK/Gradle wrapper，因此尚未执行 Android Gradle 编译、APK 安装或真机触控/坐标对齐验证。请在 Android Studio 按上述清单验收。
