# 阈界人格（Liminal Selves）

Flutter 工程，包名与 iOS Bundle ID 均为 **`top.liminalselves.app`**，与阿里云 EMAS 控制台填写一致。

## 与 Misskey 仓库的关系

- **本目录**：独立 Git 仓库，专门放客户端。
- **`misskey`**：服务端与 Web，继续单独维护；推送注册接口等后续在 Misskey 后端扩展。

## 本地运行

```bash
cd liminalselves
flutter pub get
flutter run
```

## 下一步（按顺序）

1. **初始化 Git 远程**：在代码托管平台建空仓库，`git remote add origin ...` 后首次推送。
2. **接入阿里云 EMAS SDK**：按官方文档把 Android（Gradle / AAR）与 iOS（Pod 或 Framework）接到 `android/`、`ios/`；Flutter 侧常用 **MethodChannel** 把「设备标识 / Token」暴露给 Dart。
3. **WebView**：在 `pubspec.yaml` 增加 `webview_flutter`（或等价方案），首屏加载你的 Misskey 站点 URL；深链与通知点击后再 `loadRequest` 到目标路径。
4. **Misskey 后端**：增加「移动端 deviceToken 注册」API，并在 `PushNotificationService` 中增加阿里云发送通道（可与现有 Web Push 并存）。

## 已下载的 SDK 压缩包

建议放在仓库外或 `third_party/emas/`（并在 `.gitignore` 中忽略若许可不允许提交），在 Gradle / Xcode 里用**相对路径**引用，避免二进制进主分支体积过大。
