# 阈界人格（Liminal Selves）

Flutter 工程，包名与 iOS Bundle ID 均为 **`top.liminalselves.app`**，与阿里云 EMAS 控制台填写一致。

## 与 Misskey 仓库的关系

- **本目录**：独立 Git 仓库，专门放客户端。
- **`misskey`**：服务端与 Web，单独维护。服务端已实现：
  - 设备注册 API（`mobile-push/register`、`mobile-push/unregister` 等）；
  - 阿里云推送发送通道（`AliyunMobilePushService`）；
  - **管理员在 Web 后台「设置」中配置 RAM AccessKey 与 EMAS AppKey**（不再使用服务器 `default.yml`）。  
  服务端说明见：**`misskey/docs/aliyun-mobile-push.md`**。

## 本地运行

```bash
cd liminalselves
flutter pub get
flutter run
```

## 下一步（按顺序）

1. **初始化 Git 远程**：在代码托管平台建空仓库，`git remote add origin ...` 后首次推送。
2. **接入阿里云 EMAS SDK**：按官方文档把 Android（Gradle / AAR）与 iOS（Pod 或 Framework）接到 `android/`、`ios/`；Flutter 侧用 **MethodChannel** 把设备标识暴露给 Dart，并调用 Misskey 的 **`/api/mobile-push/register`** 完成绑定。
3. **WebView**：首屏加载你的 Misskey 站点 URL；深链与通知点击后再导航到目标路径。
4. **Misskey 实例管理员**：在 **控制面板 → 设置** 中填写阿里云 **AccessKey** 与 **EMAS AppKey**（须与本工程 `aliyun-emas-services.json` 中 AppKey 一致）。详见 `misskey/docs/aliyun-mobile-push.md`。

## 已下载的 SDK 压缩包

建议放在仓库外或 `third_party/emas/`（并在 `.gitignore` 中忽略若许可不允许提交），在 Gradle / Xcode 里用**相对路径**引用，避免二进制进主分支体积过大。
