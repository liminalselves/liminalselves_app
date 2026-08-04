# Misskey 前端行为对齐：App 壳层推送架构升级

> 壳层（Android WebView 壳，包名 `top.liminalselves.app`）已完成推送架构重构。
> 本文档描述前端需要**对齐的行为与文案**，避免与壳层新行为不一致。

---

## 1. 架构变更摘要

| 旧方案 | 新方案 |
|--------|--------|
| 阿里云 EMAS 离线推送（需 `aliyun-emas-services.json`） | 原生 OkHttp WebSocket 常驻连接（绕过 WebView JS 节流） |
| 壳层在 `onPause/onResume` 启停保活 | 推送开关开启 + 已登录 → 常驻运行，不随前后台/熄屏启停 |
| WebView JS 通过 `AppNativePush.postMessage({action:"notify"})` 弹系统通知 | JS 通知桥接**已废弃**，系统通知统一由原生 WS 触发 |
| 点"启用"直接调 EMAS 注册 | 点"启用"拉起**壳层权限向导页**（5 步逐项检查） |

**关键点：** 壳层现在是"推送主通道"，Misskey 前端只负责按钮 UI 和状态同步。

---

## 2. 壳层 ↔ 前端协议（不变）

前端通过 `window.AppNativePush.postMessage(msg)` 发指令：

| `msg` | 壳层行为 |
|-------|---------|
| `"enable"` | 拉起权限向导 → 完成后 dispatch `registered:true` |
| `"disable"` | 立即停止推送 → dispatch `registered:false` |
| `"query"` | 查询当前状态 → dispatch `registered:true/false` |

壳层通过 `CustomEvent('liminal-native-push', { detail: { registered, errorCode? } })` 回传状态。

前端监听器（`MkPushNotificationAllowButton.vue`）**无需改动**。

---

## 3. 前端需要改动的地方

### 3.1 文案更新（3 个 locale 文件）

以下 key 的旧文案提到"阿里云"或"系统设置"，与新行为不符，需更新。

#### `zh-CN.yml`

| key | 旧值 | 新值 |
|-----|------|------|
| `nativePushAndroidOnlyDescription` | `本应用的原生推送（阿里云通道与服务器注册）目前仅在 Android 版壳中实现。…` | `本应用的原生推送（后台常驻消息连接）目前仅在 Android 版壳中实现。若使用 iOS 或桌面壳，此按钮无法完成注册；请改用 Android 客户端或等待后续版本。` |
| `nativePushEnableFailedPermission` | `未授予通知权限。请在系统设置中允许本应用发送通知后，再试一次。` | `权限设置未完成（可能中途取消，或个别权限未开启）。请再次启用，并按照引导页逐项完成全部权限设置。` |
| `nativePushEnableFailedPushSetup` | `通知权限已通过，但推送服务未就绪。请确认已正确配置阿里云推送（assets 内 aliyun-emas-services.json），并查看设备日志中的 AliyunPush 相关输出。` | `服务器开关同步失败（可能是网络波动）。请检查网络连接后重试。` |

#### `en-US.yml`

| key | 新值 |
|-----|------|
| `nativePushAndroidOnlyDescription` | `Native push (the resident background message connection) is currently implemented only in the Android shell. On iOS or desktop shells this button cannot complete registration; use the Android build or wait for a future release.` |
| `nativePushEnableFailedPermission` | `Permission setup was not completed (it may have been canceled, or some permissions were left off). Please enable it again and complete every step in the setup guide.` |
| `nativePushEnableFailedPushSetup` | `Failed to sync the push setting with the server (possibly a transient network issue). Check your network connection and try again.` |

#### `ja-JP.yml`

| key | 新值 |
|-----|------|
| `nativePushAndroidOnlyDescription` | `ネイティブプッシュ（バックグラウンド常駐メッセージ接続）は現状 Android 版シェルでのみ実装されています。iOS やデスクトップ版シェルではこのボタンで登録できません。Android 版を使うか、今後のリリースをお待ちください。` |
| `nativePushEnableFailedPermission` | `権限の設定が完了していません（途中でキャンセルしたか、一部の権限が未許可の可能性があります）。もう一度有効にして、ガイドに従いすべての権限設定を完了してください。` |
| `nativePushEnableFailedPushSetup` | `サーバー側の設定同期に失敗しました（ネットワークが不安定な可能性があります）。ネットワーク接続を確認してから、もう一度お試しください。` |

### 3.2 错误码语义变化

| errorCode | 旧含义 | 新含义 |
|-----------|--------|--------|
| `push_setup_failed` | EMAS 注册失败 / `aliyun-emas-services.json` 缺失 | 服务端 `enableAppPush` 开关同步失败（网络/服务端问题） |
| `permission_denied` | 通知权限未授予 | 权限向导未完成（取消/未通过） |

前端 `alertShellPushError` 的 switch 分支**结构不变**，但文案需按上表更新。

### 3.3 可选：清理废弃的 notify 桥接发送（非必须）

`common.vue` 和 `main-boot.ts` 中仍通过 `AppNativePush.postMessage(JSON.stringify({action:"notify",...}))` 发送通知。壳层现在**静默忽略**这些消息，不会重复弹窗。

建议保留发送（向后兼容），或在确认壳层全部升级后移除。移除位置：

- `packages/frontend/src/ui/_common_/common.vue` — `onNotification` 中 `bridge?.postMessage?.(...)` 段
- `packages/frontend/src/boot/main-boot.ts` — `main.on('newChatMessage', ...)` 中 `bridge?.postMessage?.(...)` 段

---

## 4. 不需要改动的部分

- `MkPushNotificationAllowButton.vue` 的按钮逻辑、事件监听、`shellPushEnablePending` 状态机 —— **全部兼容新架构，无需修改**
- `isEmbeddedAppShell()` 判定 —— 不变
- `supported` / `pushRegistrationInServer` 状态 —— 不变
- `unsubscribe`（disable）流程 —— 不变

---

## 5. 验证清单

- [ ] 在 Android 壳内打开设置 → 消息 → 关闭 APP 推送 → 再开启
- [ ] 应弹出壳层权限向导（5 步），完成全部步骤后按钮变为"停用"
- [ ] 中途取消 → 按钮回退到"启用"，弹出更新后的错误提示
- [ ] 切后台 + 小号发消息 → 收到系统通知（分级：私信弹窗 / 互动静默）
- [ ] 文案中不再出现"阿里云"、"aliyun-emas-services.json"

---

**壳层代码位置：** `c:\Users\35581\Desktop\liminalselves\android\app\src\main\kotlin\top\liminalselves\app\`
- `PermissionSetupActivity.kt` — 权限向导
- `NativeWsManager.kt` — 原生 WS
- `NativeWebViewActivity.kt` — 生命周期 + 通知分发
