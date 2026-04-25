# 阈界人格（Liminal Selves）— 更新记录

本文档记录面向测试用户与发布渠道的版本说明。当前包名：`top.liminalselves.app`。

---

## 0.1.4+6 — 启动屏、Logo 与 Android 壳（2026-04-25）

**对外版本名：** `0.1.4`  
**构建号（Android versionCode）：** `6`

### 变更摘要

- **启动屏底图：** 将白底由位图拉伸改为纯色，避免在部分 ROM 上刘海/导航区漏边、透出壁纸；Android 12+ 启动主题补全 `windowBackground` 与系统栏色，与系统 SplashScreen 过渡衔接更稳。
- **开屏 Logo：** 按屏幕最小宽度分档（`values` 与 `values-sw*dp`）控制 Logo 容器尺寸，居中显示且等比缩放，减少过小或过大、内容被裁切的情况。
- **外链跳转确认（NativeWebViewActivity）：** 对非 `liminalselves.top` / `*.liminalselves.top` 的 http(s) 链接在系统浏览器打开前需确认。对话框使用 `MaterialAlertDialog`，并通过 `ContextThemeWrapper` 注入完整 Material 3 主题，与站内 Flutter 更新类弹窗层级一致，避免系统怀旧样式，并消除因 Activity 基座非 Material 导致属性解析失败而闪退的问题。
- **返回防误退：** 当 WebView 无历史可退时，首次按返回仅提示（Toast / SnackBar），约 2 秒内再次按返回才结束应用；有历史时仍先页面回退。嵌入 Flutter 的 WebView 壳与全屏原生 WebView 行为对齐。

## 0.1.3+5 — 体验与更新内容增强（2026-04-14）

**对外版本名：** `0.1.3`  
**构建号（Android versionCode）：** `5`

### 变更摘要

- **系统栏一致性：** 上下系统栏常驻显示，并在切后台/回前台、主题变化时与页面背景色持续同步；增加缓存兜底与去重/合并，减少抖动与重复刷新。
- **加载期视觉：** Flutter 开屏与过渡加载阶段统一白色系统栏与白底加载；加载进度条改为 Flutter 风格蓝色。
- **更新内容：** 升级提示弹窗支持展示“高于当前版本”的所有更新内容（由服务器侧 changelog 条目聚合）；站内「设置 → 应用」页可直接查看更新日志列表。
