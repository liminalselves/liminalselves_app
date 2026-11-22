# 发布流程

## CI

定义在 `.github/workflows/android.yml`：

| 触发 | 行为 |
| --- | --- |
| 推送 `develop`，或向 `develop` / `master` 发起 PR | 编译 release APK 并跑 `lintRelease`，APK 作为 artifact 上传（`LiminalSelves_v<版本>-<提交>.apk`），用于预发布测试 |
| 推送 `master`（release/hotfix PR 合并） | 校验版本未发布过、CHANGELOG 有对应小节 → 正式签名构建 → 在当前提交创建 `v<版本>` 标签和 GitHub Release，附 `LiminalSelves_v<版本>.apk`，说明取自 CHANGELOG 小节并标记为 Latest |
| Actions 页手动运行 | 同分支构建，不发布 |

`master` 推送即发布：CI 读取 `pubspec.yaml` 版本号，如果对应 tag 已存在且指向别的提交（说明合并前忘了升版本号），会在构建前就失败。不再需要手动打 tag。

PR 构建同样使用正式签名（secrets 对本仓库分支可见；来自 fork 的 PR 拿不到 secrets，会退回 debug 签名并给出警告）。

## 版本号

唯一来源是 `pubspec.yaml` 的 `version: x.y.z+n`：`x.y.z` 是对外版本名，与标签 `vx.y.z` 和 CHANGELOG 标题一致；`n` 是 Android versionCode，每次发版加 1。

按语义化版本递增：不兼容的大改动升主版本，新增功能升次版本，只有修复升修订号。

## 发布分两段

两段都只在用户明确下达对应指令时执行，不得自行进入或连续执行。

### 一、发布前准备

前提：位于 `develop`，用户明确要求「做发布前准备」。

1. **版本号与更新日志**：从 `develop` 拉出 `release/v<版本>`；改 `pubspec.yaml` 的 `version`（versionCode +1）；按 [CHANGELOG-GUIDE.md](CHANGELOG-GUIDE.md) 把 `docs/CHANGELOG.md` 的 Unreleased 内容整理进 `## [<版本>] - <当天日期>` 小节，并更新底部链接。
2. **校对文档**：README 与 `docs/` 中的构建命令、版本、行为说明与代码一致；文档只放在 `docs/`（README 除外）。
3. **清理仓库**：删除无用代码、死资源、临时脚本和调试残留。
4. **提交与 PR**：提交（中文 Conventional Commit），推送 release 分支，向 `master` 开 PR，标题 `chore: 发布 v<版本>`，正文用 CHANGELOG 小节；等 CI 全部通过，失败就修复后重推。
5. **审查后停止**：自审 PR 的完整 diff，确认可以发布后停下，PR 保持开启，不合并、不打标签。

### 二、发布

前提：用户明确下达「发布」。

1. **合并 PR**：确认 CI 仍为通过，用 merge commit 合并（不 squash，保留 develop 历史）。
2. **等发版 CI**：master 推送自动校验版本、正式签名构建并创建 `v<版本>` 标签和 GitHub Release；确认 Release 附有 APK 且仓库首页的 Latest 指向它。
3. **基线统一**：把 `master` 合回 `develop` 并推送（正常情况下是快进），删除本地和远端的 release 分支。

发布后如需应用内提示更新：在服务端 `nativeClientAppInfo` 填写新版本号与 Release 中的 APK 下载地址（字段见 [README.md](../README.md#服务端更新元数据)）。

紧急修复走 `hotfix/v<版本>`，从 `master` 拉出，其余步骤相同，发布后同样合回 `develop`。

Release 说明写错：直接在 GitHub 上编辑，同时改 CHANGELOG 保持一致。已对外发布的标签不重用；同一版本重复推送 master 会因「版本已发布」校验失败，如需重发必须先升版本号。

## 仓库配置（已完成）

签名 secrets（Settings → Secrets and variables → Actions → Secrets），值来自本地 `android/key.properties` 与 `android/upload-keystore.jks`：

| Secret | 内容 |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | `upload-keystore.jks` 的 base64 |
| `ANDROID_KEYSTORE_PASSWORD` | `storePassword` |
| `ANDROID_KEY_ALIAS` | `keyAlias` |
| `ANDROID_KEY_PASSWORD` | `keyPassword` |

更换密钥库后重新写入（Git Bash，在 `android/` 下）：

```bash
base64 -w0 upload-keystore.jks | gh secret set ANDROID_KEYSTORE_BASE64
```

可选变量（同页 Variables）：`MISSKEY_URL`，覆盖构建内置的实例地址；不设时用 `android/app/build.gradle.kts` 中的默认正式地址。

`master` 分支保护：必须经 PR 合并，必需检查 `build` 通过，对管理员同样生效，禁止强推和删除。

密钥库必须备份：丢失后无法再发出能覆盖安装的更新，用户只能卸载重装。
