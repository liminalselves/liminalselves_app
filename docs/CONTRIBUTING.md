# 分支协作规范

仓库采用 Git Flow 简化版，分支分三类：

| 分支 | 定位 | 更改方式 |
| --- | --- | --- |
| `master` | 正式版本，每个提交对应一个已发布版本 | 只接受 PR 合并（分支保护强制），每次合并后打 `v<版本号>` 标签 |
| `develop` | 活跃开发主线，下一个版本在这里积累 | 建议走 PR 合并，也允许直接提交 |
| 任务分支 | 单个开发任务 | 按下文规则拉出，完成后合并并删除 |

## 任务分支

- 日常开发从 `develop` 拉出，命名 `feat/<主题>`、`fix/<问题>`、`docs/<主题>` 等，完成后合回 `develop`。
- 发布准备从 `develop` 拉出 `release/v<版本号>`，线上紧急修复从 `master` 拉出 `hotfix/v<版本号>`，步骤见 [RELEASE.md](RELEASE.md)。
- 任务分支保持短生命周期，合并后删除远端和本地分支。

## 提交与拉取请求

- 提交信息和 PR 标题使用中文 Conventional Commit 前缀：`feat:`、`fix:`、`perf:`、`refactor:`、`style:`、`docs:`、`test:`、`chore:`。
- 改动影响用户可见的行为时，同一个提交或 PR 里更新 `docs/CHANGELOG.md` 顶部的 `## [Unreleased]` 小节，写法见 [CHANGELOG-GUIDE.md](CHANGELOG-GUIDE.md)。
- 推送到 `develop` / `master` 或向它们发起 PR 时，CI 会编译 APK 并跑 lint，失败时先修复再合并。产物在 Actions 运行页下载。

## 文档位置

除仓库根目录的 `README.md` 与 `AGENTS.md` 外，所有文档放在 `docs/`。
