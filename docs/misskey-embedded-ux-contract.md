# Misskey 嵌入式壳（App WebView）跨仓库 UX 协议

> 版本：v8（2026-10-04）
>
> 壳层：`liminalselves/android/app/src/main/kotlin/top/liminalselves/app/`
> Web：`misskey/packages/frontend/src/`
>
> 本文档是两仓库嵌入行为的唯一权威合同。

## 1. 职责边界

- Misskey 负责站内导航、Streaming 实时状态、抽屉、时间线刷新、业务弹层和主题。
- Android 壳负责 WebView 生命周期、顶缘下拉整页重载、系统通知、后台原生 WebSocket、文件能力、系统栏、权限向导和下载落盘；Misskey 仍负责站内时间线刷新。
- 不修改 Misskey 现有底栏、抽屉项目、导航目标和信息架构。
- Android 原生 WS 只负责后台系统通知，不能替代 Misskey Web Streaming。

### 1.1 启动版本检查

- 正常冷启动时，`MainActivity` 必须向 `BuildConfig.MISSKEY_URL` 对应的 `/api/meta` 读取版本策略；`-PmisskeyUrl=...` 同时决定 WebView 地址和版本检查地址。
- 同一实例已有缓存时（仅缓存 `nativeClientAppInfo` 片段）可先按缓存快速进入并后台刷新；首次启动或切换构建注入的实例地址时必须等待该实例返回，开发包与正式包不得互用策略缓存。
- 支持通过 `androidReleases` 按系统 `minSdk` / `maxSdk` 分流下发独立的 `version`、`minRequiredVersion`、`downloadUrl`、`changelog`，未配置或未命中时回退顶层 `minRequiredAppVersion`、`latestAndroidVersion`、`androidDownloadUrl`、`changelog`。
- 当前版本低于最低要求版本时阻断进入 WebView；未低于最低版本但低于最新版本时，启动时显示可关闭的更新提示及下载入口。

## 2. Web ↔ Android 桥

| 桥 | 方法 | 说明 |
|---|---|---|
| `AppNativePush` | `postMessage('enable'\|'disable'\|'query')` | 推送开关（历史 `{action:"notify"}` 已废弃并静默忽略） |
| `AppChrome` | `postColor(cssColor)` | Misskey 已应用主题的页面背景色，不是服务端初始强调色 |
| `AppNav` | `onRouteChange(json)` | `{ fullPath, route }`，同步当前地址 |
| `AppDownload` | `onBlobReady(base64, fileName, mimeType)` / `onBlobError(error)` | blob 下载 |

Android → Web：

- `window.__LIMINAL_APP_INFO__` 与 `liminal-app-info-updated`：页面加载完成时注入 `{ appName, version, buildNumber, packageName, platform, sdkInt }`（供站内核对客户端信息；iOS 端注入前 5 个字段）；
- `liminal-native-push`：推送开关状态，`detail` 为 `{ registered: boolean, errorCode?: string, errorMessage?: string }`；
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

### 4.1 单任务与回前台

- 桌面图标、最近任务、消息通知和保活通知唤起 App 时，已有任务必须复用，不额外创建最近任务卡片。`MainActivity` 与网页页继承相同的应用默认任务归属，`NativeWebViewActivity` 使用 `singleTask`。
- 两类 App 通知统一通过 `MainActivity.createLaunchIntent` 生成 `MAIN/LAUNCHER + NEW_TASK + SINGLE_TOP` 入口；`NEW_TASK` 优先查找已有任务，并不等于强制新建。普通唤起不使用清栈标志；若启动页被重新创建且不是任务根，无深链时直接退场，保留网页和权限向导当前状态。
- 消息通知额外使用 `CLEAR_TOP` 保证深链送达，仍经过版本门控，再把 `extraMap.openUrl` 交给已有网页页；启动检查或更新弹窗期间收到新通知只更新待跳转目标，不重复检查或弹窗，普通重复点击不得覆盖待处理深链。明确的通知跳转可结束网页页上方的壳内页面，但不重建已有网页实例。
- 普通回前台不重新加载页面；`NativeWebViewActivity.onNewIntent` 仅处理真正的通知深链。渲染器不可见时不降为可随时回收；系统已杀进程时允许恢复页面，但仍复用原任务。

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
- 整个 App 不可见时，由原生 WS 订阅 `main` 频道发送系统通知，覆盖 `notification`（帖子/提及/回复/引用/转发/反应/关注/关注请求/投票结束）、`newChatMessage`（私信 `/chat/user/<id>`、群聊 `/chat/room/<id>`）与 `newAgentMessage`（智能体消息 `/chat/agent/<id>`）。
- 通知分级：`openPath` 以 `/chat` 开头的消息（含私信、群聊、智能体消息）走高优先级私信渠道 `misskey_chat_v2`（弹窗 + 声音 + 震动）；其余互动通知走低优先级互动渠道 `misskey_notify_v2`（静默）；前台保活走 `liminalselves_keepalive_v2`（最低优先级）。
- 账号 token/origin 变化时原生 WS 必须重连，旧 socket 回调不得恢复旧连接；系统重建 Activity 时须释放旧实例的 WS 连接与保活锁，避免重复连接。

## 9. 下载与外链

- 下载开始：前台 Toast「开始下载：<文件名>」（HTTP/blob/data 一致）。
- 存储位置与权限：三类下载统一写入公共 `Download/` 目录（HTTP 经 `DownloadManager`；`blob:` 优先读取注入 shim 缓存的 Blob 引用并回退 `fetch`；`data:` 解码落盘）；Android 10+ 经 `MediaStore.Downloads` 写入免存储权限，Android 9 及以下先申请 `WRITE_EXTERNAL_STORAGE` 再执行下载。
- 下载完成：不自动打开文件；前台单条 Toast「已保存到 Download/: <文件名>」；后台发静默系统通知（复用 `misskey_notify_v2` 渠道），点击通知打开文件。
- HTTP 下载保留 `DownloadManager` 下载中进度通知。
- `about:`、`javascript:`、`blob:`、`data:` 等内部协议不触发离站确认；非 HTTP(S) 自定义 scheme 直接交由系统处理。
- 官方 `liminalselves.top` 域及 `-PmisskeyUrl=...` 注入实例的同源地址（协议、主机、端口均一致）在 WebView 内打开；其余 HTTP(S) 地址先确认再交给系统浏览器。
- 外链无法打开时必须显示错误。

## 9a. 视图缩放

- App 壳内禁用双指缩放与缩放控件（WebView `setSupportZoom(false)`）。
- Android WebView 在页面加载前显式设置 `textZoom = 100`，文字和图标字体不跟随系统字体缩放；初次创建与渲染器重建使用同一初始化入口。站内字号设置仍然有效，原生对话框与权限向导继续跟随系统字号。
- 浏览器端不受影响（viewport 未限制缩放）。

## 10. 验收清单

- [ ] 使用 `-PmisskeyUrl=...` 构建后，启动版本检查请求同一实例的 `/api/meta`；开发与正式实例缓存不互用；该实例的同源站内跳转和 `window.open()` 不弹离站确认；
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
- [ ] 切出 App 后反复从桌面图标、最近任务、保活通知返回，最近任务始终只有一张 App 卡片，页面不重新加载、浏览状态保留；权限向导中普通唤起仍停留原步骤；
- [ ] 后台连续点击消息通知时复用同一任务与网页实例，深链到正确目标；冷启动检查或更新弹窗期间重复点击通知/桌面图标不新增任务或弹窗、不丢失最后一个通知目标；
- [ ] 系统回收 App 进程后从桌面/通知返回，允许恢复页面但不新增第二个任务；正常退出后再启动仍执行版本门控；
- [ ] 双指捏合不缩放页面；
- [ ] 系统字号切到大号后冷启动或切回 App，网页文字与图标不被额外放大，管理页圆形图标保持居中；站内字号设置仍可调整文字大小；
- [ ] 根页面按返回提示「再按一次退出」，2 秒内再按才退出；
- [ ] 下载开始有 Toast，前台完成单条 Toast，后台完成有系统通知且点击可打开文件，全程不自动打开；
- [ ] 抽屉遮罩与原始版本一致；
- [ ] 上传、下载、视频全屏、返回与外链行为保持可用。
