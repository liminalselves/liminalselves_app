# 阈界人格（Liminal Selves）

Misskey 移动客户端工程，Android 包名与 iOS Bundle ID 均为 **`top.liminalselves.app`**。

## 当前架构

- **Android：** 纯原生构建（不打包 Flutter 引擎）。`MainActivity` 作为轻量启动门控负责版本检查，随后进入全屏 `NativeWebViewActivity`（系统 WebView）；支持常驻原生 WebSocket 推送（`NativeWsManager` + `KeepAliveService`）、5 步推送权限向导（`PermissionSetupActivity`）、文件选择与拍照上传、HTTP/blob/data 下载落盘、外链确认、错误恢复与系统栏跟随。**最低系统 Android 7（API 24）。**
- **iOS：** 使用 Flutter `webview_flutter` 内嵌页面（`lib/main.dart` 亦兼容 macOS 逻辑；仓库当前包含 `ios/` 工程，暂未生成 `macos/` 宿主目录）。
- **Windows / Web：** 保留 Flutter 调试与不支持内嵌时的浏览器回退页面（Web 端传入 `--dart-define=ALLOW_WEB_EMBED=true` 时可启用 iframe 嵌入，默认走外部打开引导页）。
- **实例地址：** 默认地址为 `https://misskey.liminalselves.top/`。Android 构建可通过 `-PmisskeyUrl=...` 覆盖（写入 `BuildConfig.MISSKEY_URL`）；Flutter 端通过 `--dart-define=MISSKEY_URL=...` 覆盖。
- **与 Misskey 仓库的关系：** 本仓库只放客户端；服务端与 Web 在 `misskey` 仓库单独维护，两边的嵌入行为合同见 [docs/misskey-embedded-ux-contract.md](docs/misskey-embedded-ux-contract.md)。

## 文档

| 文档 | 内容 |
| --- | --- |
| [docs/CONTRIBUTING.md](docs/CONTRIBUTING.md) | 分支模型（master / develop / 任务分支）与提交规范 |
| [docs/RELEASE.md](docs/RELEASE.md) | 版本号、两段式发布流程、CI 自动构建与签名配置 |
| [docs/CHANGELOG.md](docs/CHANGELOG.md) | 更新日志 |
| [docs/CHANGELOG-GUIDE.md](docs/CHANGELOG-GUIDE.md) | 更新日志书写规范 |
| [docs/misskey-embedded-ux-contract.md](docs/misskey-embedded-ux-contract.md) | 与 Misskey 前端的跨仓库嵌入协议 |

## Android 构建（原生，不经过 Flutter 工具链）

正式包由 CI 构建（见 [docs/RELEASE.md](docs/RELEASE.md)）。本地常用命令（不传 `-PmisskeyUrl` 时使用默认正式实例地址）：

```powershell
# 推荐：经辅助脚本构建（自动定位 JDK 后调用 android/gradlew.bat）
.\scripts\gradle-with-jdk.ps1 assembleRelease
# 若需指向测试实例：
.\scripts\gradle-with-jdk.ps1 assembleRelease -PmisskeyUrl=https://your-instance.example/

# 快速 Kotlin 编译验证与 lint 检查
.\scripts\gradle-with-jdk.ps1 :app:compileReleaseKotlin --no-daemon
.\scripts\gradle-with-jdk.ps1 lintRelease

# 或手动指定 JDK 后直接调用 gradlew
$env:JAVA_HOME="C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot"
.\android\gradlew.bat assembleRelease
```

- 产物位于 `build/app/outputs/apk/release/app-release.apk`（约 1.4 MB）。
- `android/gradlew.bat` 与 `gradle-wrapper.jar` 未纳入 Git 版本控制；若新克隆仓库尚未生成本地 wrapper，可先运行一次 `flutter pub get`，或直接安装 Gradle 9.6 后执行 `gradle -p android assembleRelease`。
- 本地正式签名需将 `android/key.properties.example` 复制为 `android/key.properties` 并配置密钥库；未配置时 `assembleRelease` 将自动回退为 debug 签名。

其他平台的 Flutter 检查：

```powershell
flutter analyze
flutter test
```

### 依赖与工具链说明

- 构建链：Gradle 9.6.0 / AGP 9.4.1（内置 Kotlin）/ JDK 17+ / compileSdk 36 / targetSdk 36 / minSdk 24（CI 使用 Temurin JDK 21）。
- 版本号唯一来源是 `pubspec.yaml` 的 `version:` 行（`x.y.z+n`），Android 与 iOS 构建均直接解析该字段。
- Flutter 工具再生成的 `GeneratedPluginRegistrant.java` 已在 Java 编译任务中排除，`flutter analyze` / `flutter pub get` 不会破坏原生构建。
- `branding/` 存放应用图标与开屏源图（浅色与深色两套），按 `pubspec.yaml` 配置通过 `dart run flutter_launcher_icons` 与 `dart run flutter_native_splash:create` 生成（Android 开屏资源已压缩为 WebP）。
- 曾试验过内置 GeckoView 内核（0.2.0 开发期间），因麒麟设备上的合成性能与桥接复杂度回退；完整改动备份在仓库外 `../liminalselves-gecko-backup/`，需要时可参考。

### 服务端更新元数据

启动时向实例请求 `POST /api/meta`（请求体 `{"detail":false}`）并读取其中的 `nativeClientAppInfo`，Android 支持按系统版本分流（可选）：

```json
{
  "nativeClientAppInfo": {
    "latestAndroidVersion": "1.1.1",
    "androidDownloadUrl": "…Android 下载地址…",
    "latestIosVersion": "1.1.1",
    "iosDownloadUrl": "…iOS 下载地址…",
    "minRequiredAppVersion": "0.1.9",
    "changelog": [
      { "version": "1.1.1", "content": "更新说明文本" }
    ],
    "androidReleases": [
      {
        "minSdk": 24,
        "maxSdk": 36,
        "version": "1.1.1",
        "minRequiredVersion": "0.1.9",
        "downloadUrl": "…Android 下载地址…",
        "changelog": [
          { "version": "1.1.1", "content": "可选的分组专属说明" }
        ]
      }
    ]
  }
}
```

- **Android 分流规则：** 未配置 `androidReleases` 时读取顶层字段；配置后按 `minSdk（默认 1）≤ 当前 SDK ≤ maxSdk（若配置）` 筛选，取 `minSdk` 最大条目，条目的未配置字段回退到顶层对应字段（`version` → `latestAndroidVersion`、`minRequiredVersion` → `minRequiredAppVersion`、`downloadUrl` → `androidDownloadUrl`、`changelog` → 顶层 `changelog`）。
- **更新弹窗展示规则：** 当本地版本低于最低要求版本时阻断进入主页面并强制更新；不低于最低要求但低于最新版本时弹出可关闭提示，展示版本号高于本地且不超过最新版本的全部 `changelog` 条目。
