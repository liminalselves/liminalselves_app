# 阈界人格（Liminal Selves）

Misskey 移动客户端工程，包名与 iOS Bundle ID 均为 **`top.liminalselves.app`**。

## 当前架构

- **Android（0.2.0 起）：** 纯原生构建（不打包 Flutter 引擎），轻量启动门控负责版本检查，随后进入全屏 `NativeWebViewActivity`（系统 WebView）；常驻原生 WebSocket 推送；文件选择、外链确认、错误恢复、系统栏同步。**最低系统 Android 7（API 24）。**
- **iOS / macOS：** 使用 Flutter `webview_flutter` 内嵌页面。
- **Windows / Web：** 保留 Flutter 调试与不支持内嵌时的浏览器回退页面。
- **实例地址：** Android 构建通过 `-PmisskeyUrl=...` 注入（写入 `BuildConfig.MISSKEY_URL`）；iOS/桌面通过 `--dart-define=MISSKEY_URL=...`。

## 与 Misskey 仓库的关系

- **本目录**：独立 Git 仓库，专门放客户端。
- **`misskey`**：服务端与 Web，单独维护。

## Android 构建（原生，不经过 Flutter 工具链）

```powershell
# 推荐：经辅助脚本构建（自动定位 JDK 后调用 android/gradlew.bat）
.\scripts\gradle-with-jdk.ps1 assembleRelease -PmisskeyUrl=http://47.79.85.42:8888/

# 或手动指定 JDK 后直接调用 gradlew
$env:JAVA_HOME="C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot"
.\android\gradlew.bat assembleRelease -PmisskeyUrl=http://47.79.85.42:8888/
```

产物位于 `build/app/outputs/apk/release/app-release.apk`（约 2 MB）。

发布前执行：

```powershell
.\scripts\gradle-with-jdk.ps1 lintRelease
```

其他平台的 Flutter 检查：

```powershell
flutter analyze
flutter test
```

### 依赖与工具链说明

- 构建链：Gradle 9.6 / AGP 9.4.1（内置 Kotlin）/ JDK 17+ / compileSdk 36 / targetSdk 36 / minSdk 24。
- 版本号唯一来源是 `pubspec.yaml` 的 `version:` 行，Android 构建脚本直接解析（iOS 同样使用）。
- Flutter 工具再生成的 `GeneratedPluginRegistrant.java` 已在 Java 编译任务中排除，`flutter analyze/pub get` 不会破坏原生构建。
- 曾试验过内置 GeckoView 内核（0.2.0 开发期间），因麒麟设备上的合成性能与桥接复杂度回退；完整改动备份在仓库外 `../liminalselves-gecko-backup/`，需要时可参考。

### 服务端更新元数据

启动时读取 `/api/meta` 的 `nativeClientAppInfo`。0.2.0 起支持按系统版本分流（可选）：

```json
{
  "nativeClientAppInfo": {
    "latestAndroidVersion": "0.2.0",
    "androidDownloadUrl": "…下载地址…",
    "minRequiredAppVersion": "0.1.9",
    "changelog": [],
    "androidReleases": [
      { "minSdk": 24, "version": "0.2.0", "downloadUrl": "…同上…" }
    ]
  }
}
```

- 不配置 `androidReleases` 时行为与旧版完全一致（读旧字段）。
- 配置后：客户端按 `minSdk ≤ 当前 SDK ≤ maxSdk（若有）` 匹配、取 `minSdk` 最大条目；无匹配回退旧字段。可用它给不同系统版本下发不同下载地址。
