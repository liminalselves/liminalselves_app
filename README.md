# 阈界人格（Liminal Selves）

Misskey 移动客户端工程，包名与 iOS Bundle ID 均为 **`top.liminalselves.app`**，与阿里云 EMAS 控制台填写一致。

## 当前架构

- **Android：** 轻量原生启动门控负责版本检查，随后进入全屏 `NativeWebViewActivity`；集成文件选择、外链确认、错误恢复、系统栏同步和阿里云 EMAS 推送。
- **iOS / macOS：** 使用 Flutter `webview_flutter` 内嵌页面。
- **Windows / Web：** 保留 Flutter 调试与不支持内嵌时的浏览器回退页面。
- **实例地址：** 通过 `--dart-define=MISSKEY_URL=...` 注入，Android 会同步写入 `BuildConfig.MISSKEY_URL`。

## 与 Misskey 仓库的关系

- **本目录**：独立 Git 仓库，专门放客户端。
- **`misskey`**：服务端与 Web，单独维护。服务端已实现：
  - 设备注册 API（`mobile-push/register`、`mobile-push/unregister` 等）；
  - 阿里云推送发送通道（`AliyunMobilePushService`）；
  - **管理员在 Web 后台「设置」中配置 RAM AccessKey 与 EMAS AppKey**（不再使用服务器 `default.yml`）。  
  服务端说明见：**`misskey/docs/aliyun-mobile-push.md`**。

## 本地运行

```powershell
cd liminalselves
flutter pub get
flutter run --dart-define=MISSKEY_URL=http://47.79.85.42:8888/
```

## Android 正式打包

```powershell
flutter build apk --release --dart-define=MISSKEY_URL=http://47.79.85.42:8888/
```

产物位于 `build/app/outputs/flutter-apk/app-release.apk`。当前发布流程只生成 APK，不生成 AAB。

发布前执行：

```powershell
flutter analyze
flutter test
.\android\gradlew.bat -p android lintRelease
```

## 服务端配置

Misskey 实例管理员需在 **控制面板 → 设置** 中填写阿里云 **AccessKey** 与 **EMAS AppKey**，并确保 AppKey 与客户端配置一致。服务端说明见 `misskey/docs/aliyun-mobile-push.md`。

## 已下载的 SDK 压缩包

建议放在仓库外或 `third_party/emas/`（并在 `.gitignore` 中忽略若许可不允许提交），在 Gradle / Xcode 里用**相对路径**引用，避免二进制进主分支体积过大。
