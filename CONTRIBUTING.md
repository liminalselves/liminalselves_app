# 分支协作规范

仓库采用 Git Flow 简化版，分支分三类：

| 分支 | 定位 | 更改方式 |
| --- | --- | --- |
| `master` | 正式版本，每个提交对应一个已发布版本 | 只接受合并（PR），禁止直接提交；每次合并后打 `v<版本号>` 标签 |
| `develop` | 活跃开发主线，下一个版本在这里积累 | 建议走 PR 合并，也允许直接提交 |
| 任务分支 | 单个开发任务 | 按下文规则拉出，完成后合并并删除 |

## 任务分支

- 日常开发从 `develop` 拉出，命名 `feature/<主题>`、`fix/<问题>`、`docs/<主题>` 等，完成后合回 `develop`。
- 发布准备从 `develop` 拉出 `release/<版本号>`（如 `release/v1.1.0`），只做版本号、更新日志和发版前修复，完成后合入 `master` 并合回 `develop`。
- 线上紧急修复从 `master` 拉出 `hotfix/<版本号>`，合入 `master` 发布后再合回 `develop`。
- 任务分支保持短生命周期，合并后删除远端和本地分支。

## 提交与拉取请求

- 提交信息和 PR 标题使用中文 Conventional Commit 前缀：`feat:`、`fix:`、`refactor:`、`style:`、`docs:`、`test:`、`chore:`。
- 改动影响用户可见的行为时，同一个提交或 PR 里更新 `CHANGELOG.md` 顶部的待发版本小节，写法见 [docs/CHANGELOG-GUIDE.md](docs/CHANGELOG-GUIDE.md)。
- 推送到 `develop` / `master` 或向它们发起 PR 时，CI 会编译 APK 并跑 lint，失败时先修复再合并。产物可在 Actions 运行页下载。

发布步骤见 [docs/RELEASE.md](docs/RELEASE.md)。
