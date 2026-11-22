# 仓库协作规则（AI 代理必读）

- 分支与提交规范见 [docs/CONTRIBUTING.md](docs/CONTRIBUTING.md)。`master` 只经 PR 合并；开发在 `develop` 或从它拉出的任务分支上进行。
- 除 `README.md` 与本文件外，文档一律放在 `docs/`。

## 发布（两段式，完整步骤见 [docs/RELEASE.md](docs/RELEASE.md)）

1. **发布前准备**：仅在 `develop` 上、用户明确要求时执行：版本号 + `docs/CHANGELOG.md` → 校对文档 → 清理无用/临时文件 → 提交、推送、开 PR（`release/v<版本>` → `master`）→ 等 CI 全部通过 → 审查后**停止**，PR 保持开启，不合并、不打标签。
2. **发布**：仅在用户明确下达时执行：合并 PR → 在 `master` 打 `v<版本>` 标签并推送（CI 创建 Release 并标记 Latest）→ 确认产物 → `master` 合回 `develop` 并推送，删除 release 分支。
3. 用户没有明确说出对应指令时，不得自行进入或连续执行这两段。

## 验证

- Android 编译验证：`.\scripts\gradle-with-jdk.ps1 :app:compileReleaseKotlin --no-daemon`；完整打包仅在用户要求或发布流程中由 CI 完成。
- 性能优化不得改变页面行为、推送、下载、主题与返回键等既有表现。
