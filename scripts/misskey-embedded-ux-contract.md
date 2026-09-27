# Misskey 嵌入式壳（App WebView）跨仓库 UX 协议

> 版本：v6（2026-09-27）
>
> 壳层：`liminalselves/android/app/src/main/kotlin/top/liminalselves/app/`
> Web：`misskey/packages/frontend/src/`
>
> 本文档是两仓库嵌入行为的唯一权威合同。

## 1. 职责边界

- Misskey 负责站内导航、Streaming 实时状态、抽屉、时间线刷新、业务弹层和主题。
- Android 壳负责 WebView 生命周期、系统通知、后台原生 WebSocket、文件能力、系统栏、权限向导和下载落盘。
- 不修改 Misskey 现有底栏、抽屉项目、导航目标和信息架构。
- Android 原生 WS 只负责后台系统通知，不能替代 Misskey Web Streaming。

### 1.1 启动版本检查

- 正常冷启动时，`MainActivity` 必须向 `BuildConfig.MISSKEY_URL` 对应的 `/api/meta` 读取版本策略；`-PmisskeyUrl=...` 同时决定 WebView 地址和版本检查地址。
- 同一实例已有缓存时可先按缓存快速进入并后台刷新；首次启动或切换构建注入的实例地址时必须等待该实例返回，开发包与正式包不得互用策略缓存。
- 当前版本低于 `minRequiredAppVersion` 时阻断进入 WebView；未低于最低版本但低于 `latestAndroidVersion` 时，启动时显示可关闭的更新提示及下载入口。

## 2. Web → Android 桥

| 桥 | 方法 | 说明 |
|---|---|---|
| `AppNativePush` | `postMessage('enable'|'disable'|'query')` | 推送开关 |
| `AppChrome` | `postColor(cssColor)` | Misskey 已应用主题的页面背景色，不是服务端初始强调色 |
| `AppNav` | `onRouteChange(json)` | `{ fullPath, route }`，同步当前地址 |
| `AppDownload` | `onBlobReady/onBlobError` | blob 下载 |

Android → Web：

- `liminal-native-push`：推送开关状态；
- `liminal-native-push-alert`：启动检查发现权限不完整、已自动关闭推送时的说明。

## 3. Misskey Streaming

- 壳内必须始终建立并注册完整 Streaming 事件链，不受 `store.realtimeMode` 开关影响。
- 完整事件包括：通知、私信、智能体消息、账号更新、未读状态、公告、表情更新、模型配置等。
- `common.vue` 的通知监听和 `main-boot.ts` 的全局事件监听都必须生效。
- 开发模式启用公网 HMR 时，backend 必须允许 `/streaming` WebSocket upgrade，不得由 HTTP 503 兜底抢先关闭。
- 开发隧道验收：本机和公开域名的 `/streaming` 均必须完成 WebSocket OPEN。

## 4. 返回与路由

1. 全屏激活时返回先退出全屏；
2. WebView 有历史时 `goBack()`；
3. 无历史（根页面）时首次返回提示「再按一次返回键退出应用」，2 秒内再次返回才退出应用。

不使用硬编码板块路径，不 patch `history.pushState`。

**回前台不刷新**：普通重入（部分 ROM 回前台重新投递启动 Intent）不重新加载页面——`MainActivity` 检测非任务根且无深链时直接退场；`NativeWebViewActivity.onNewIntent` 仅在携带 `extraMap.openUrl` 的真深链时才导航。渲染器优先级不可见时不再降为“可随时回收”，降低后台被 ROM 杀掉后回前台整页重建的概率。

## 5. 系统栏颜色

- Activity/页面开始加载时使用壳层深浅底色：浅色 `#F7F9F5`，深色 `#15171C`。
- 禁止读取服务端初始 `meta theme-color` 作为系统栏颜色；该值可能是实例强调绿。
- 上报源为实际生效的 `--MI_THEME-bg`（页面背景色），多通道保证冷启动也能送达：theme 事件 + `<html>` style 属性监听 + 初始化/首帧/load 兜底。Android 收到后状态栏与导航栏同色跟随。

## 6. Insets 与抽屉

- Android 外层容器消费状态栏、导航栏和 IME inset。
- Misskey 保持原有 `env(safe-area-inset-*)` 逻辑，不再叠加 Android 下发的第二套 CSS inset。
- 抽屉模式（`.root.asDrawer`）下，navbar 四条渐进模糊伪元素用**旧语法（`rgba` + `-webkit-` 前缀）重写同一 mask**：部分系统 WebView 无法解析新式 `rgb(0 0 0 / X%)` 渐变语法，mask 失效会导致模糊带失去渐隐形状、变成整块遮挡。几何/布局/桌面侧栏均不变。

## 7. 推送权限合同

### 7.1 启用前

用户点“启用 App 推送”后必须进入五步权限向导，全部通过后才允许同步服务端 `enableAppPush=true`：

1. 通知总权限；
2. 电池优化豁免；
3. 私信通知渠道开启且 importance >= HIGH；
4. 互动通知渠道开启；
5. 保活服务渠道开启。

取消或未完成时返回 `permission_denied`，推送保持关闭。

### 7.2 每次启动/页面恢复

- 查询服务器 `enableAppPush`。
- 若服务器为开启，必须重新检查上述五项。
- 任一缺失：同步服务器 `enableAppPush=false`，清除本地启用状态，停止原生 WS、前台服务和锁，并派发 `liminal-native-push-alert`。
- 权限完整才启动原生后台推送。

### 7.3 错误码

| errorCode | 含义 |
|---|---|
| `not_logged_in` | 启用时未登录 |
| `permission_denied` | 五步向导未完成 |
| `push_setup_failed` | 服务端开关同步失败 |

## 8. 系统通知

- App 内有 started Activity 时抑制系统通知，避免站内提示和系统 Heads-up 重复。
- 整个 App 不可见时，由原生 WS 发送系统通知。
- 账号 token/origin 变化时原生 WS 必须重连，旧 socket 回调不得恢复旧连接。

## 9. 下载与外链

- 下载开始：前台 Toast「开始下载：<文件名>」（HTTP/blob/data 一致）。
- 下载完成：不自动打开文件；前台单条 Toast「已保存到 Download/: <文件名>」；后台发静默系统通知（复用互动通知渠道），点击通知打开文件。
- HTTP 下载保留 DownloadManager 下载中进度通知。
- `about:blank` 等内部协议不触发离站确认。
- 外链无法打开时必须显示错误。

## 9a. 视图缩放

- App 壳内禁用双指缩放与缩放控件（WebView `setSupportZoom(false)`）。
- 浏览器端不受影响（viewport 未限制缩放）。

## 10. 验收清单

- [ ] 使用 `-PmisskeyUrl=...` 构建后，启动版本检查请求同一实例的 `/api/meta`；开发与正式实例缓存不互用；
- [ ] 当前版本低于服务器最低版本时不可进入 WebView；仅低于最新版本时出现可关闭的启动更新提示；
- [ ] 本机和公开隧道 `/streaming` WebSocket 均 OPEN；
- [ ] 壳内即使关闭 realtimeMode，通知、私信、智能体消息、未读数、账号更新、公告仍实时更新；
- [ ] 打开抽屉时不出现上下大块模糊遮罩；
- [ ] 冷启动状态栏/导航栏不闪实例强调绿；
- [ ] 站内切换主题后系统栏跟随页面背景色；
- [ ] 启用推送必须完成五步向导；
- [ ] 启动时缺任一推送权限，服务器开关和本地推送自动关闭，并显示说明；
- [ ] 前台不弹重复系统通知，后台正常通知；
- [ ] 多账号切换后不再收到旧账号通知；
- [ ] 切出 App 后从桌面图标/最近任务返回，页面不重新加载，浏览状态保留（通知点击仍能深链到目标页）；
- [ ] 双指捏合不缩放页面；
- [ ] 根页面按返回提示「再按一次退出」，2 秒内再按才退出；
- [ ] 下载开始有 Toast，前台完成单条 Toast，后台完成有系统通知且点击可打开文件，全程不自动打开；
- [ ] 抽屉遮罩与原始版本一致；
- [ ] 上传、下载、视频全屏、返回与外链行为保持可用。
