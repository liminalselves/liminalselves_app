# 发布流程

CI 定义在 `.github/workflows/android.yml`：

| 触发 | 行为 |
| --- | --- |
| 推送 `develop` / `master`，或向它们发起 PR | 编译 release APK 并跑 `lintRelease`，APK 作为 artifact 上传（名为 `LiminalSelves_v<版本>-<提交>.apk`），用于预发布测试 |
| 推送 `v*` 标签 | 校验标签在 `master` 上、与 `pubspec.yaml` 版本一致、`CHANGELOG.md` 有对应小节 → 正式签名构建 → 创建 GitHub Release 并附上 `LiminalSelves_v<版本>.apk`，说明取自 CHANGELOG 小节 |
| Actions 页手动运行 | 同分支构建 |

PR 和未配置签名 secrets 时 APK 使用 debug 签名，只能用于测试，不能覆盖安装正式版。

## 版本号

唯一来源是 `pubspec.yaml` 的 `version: x.y.z+n`：`x.y.z` 是对外版本名，与标签 `vx.y.z` 和 CHANGELOG 标题一致；`n` 是 Android versionCode，每次发版加 1。

按语义化版本递增：不兼容的大改动升主版本，新增功能升次版本，只有修复升修订号。

## 发版步骤

1. 在 `develop` 上确认待发功能已合入，CI 通过，并用 CI 产出的 APK 完成真机验证。
2. 从 `develop` 拉出 `release/v<版本号>`：
   - 修改 `pubspec.yaml` 的 `version`。
   - 通读 `CHANGELOG.md` 待发小节，补概述、合并重复条目，标题改为 `## <版本号>`。
   - 只允许发版前修复，不再加新功能。
3. 向 `master` 发起 PR，CI 通过后合并。
4. 在 `master` 的合并提交上打标签并推送：

   ```powershell
   git switch master
   git pull
   git tag -a v1.0.0 -m "v1.0.0"
   git push origin v1.0.0
   ```

5. 等 CI 创建 GitHub Release，下载 APK 抽查一次安装。
6. 把 `master` 合回 `develop`（或直接合并 release 分支），删除 release 分支。
7. 需要应用内提示更新时，在服务端 `nativeClientAppInfo` 填写新版本号与 Release 中的 APK 下载地址（字段见 [README.md](../README.md#服务端更新元数据)）。

紧急修复走 `hotfix/v<版本号>`，从 `master` 拉出，其余步骤相同。

发布后发现 Release 说明写错：直接在 GitHub 上编辑，同时改 CHANGELOG 保持一致。重新推送同名标签前先删掉远端标签和 Release，标签一旦对外发布不建议重用。

## 仓库配置

签名 secrets（仓库 Settings → Secrets and variables → Actions → Secrets）：

| Secret | 内容 |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | `android/upload-keystore.jks` 的 base64 |
| `ANDROID_KEYSTORE_PASSWORD` | 密钥库密码（`key.properties` 的 `storePassword`） |
| `ANDROID_KEY_ALIAS` | 密钥别名（`keyAlias`） |
| `ANDROID_KEY_PASSWORD` | 密钥密码（`keyPassword`） |

生成 base64（PowerShell，在 `android/` 下）：

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("upload-keystore.jks")) | Set-Clipboard
```

可选变量（同页 Variables）：`MISSKEY_URL`，覆盖构建内置的实例地址；不设时使用 `android/app/build.gradle.kts` 中的默认正式地址。

`master` 分支保护（Settings → Branches）：要求通过 PR 合并，必需检查选 `android / build`。

密钥库必须备份：丢失后无法再发出能覆盖安装的更新，用户只能卸载重装。
