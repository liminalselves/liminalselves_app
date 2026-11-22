# 更新日志书写规范

`docs/CHANGELOG.md` 写给用户看，遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)（版本号用语义化版本）。每个版本小节会由发版 CI 原样截取为 GitHub Release 说明（见 [RELEASE.md](RELEASE.md)），也可照抄到服务端的应用内更新日志。技术细节写进提交记录和文档，不进 CHANGELOG。

## 结构

```markdown
## [Unreleased]

## [1.1.0] - 2026-11-22

### 新增

- 新增某某功能，可以在某处使用。

### 变更

- 某个已有功能的用法或体验发生了变化。

### 修复

- 曾经出错的地方，现在正常了。

[Unreleased]: https://github.com/liminalselves/liminalselves_app/compare/v1.1.0...HEAD
[1.1.0]: https://github.com/liminalselves/liminalselves_app/compare/v1.0.0...v1.1.0
[1.0.0]: https://github.com/liminalselves/liminalselves_app/releases/tag/v1.0.0
```

1. **Unreleased**：文件顶部常驻 `## [Unreleased]` 小节。开发期间把新条目写在这里；发布前把内容移动到新的版本小节，Unreleased 留空。
2. **版本标题**：`## [x.y.z] - YYYY-MM-DD`，版本号不带 `v`，日期是发布日。CI 按这一行找小节，找不到或为空时发版失败。
3. **分组**：只用 Keep a Changelog 的六个类别，按这个顺序，没有内容的不写：
   `### 新增`（新功能）、`### 变更`（已有功能的变化）、`### 弃用`、`### 移除`、`### 修复`、`### 安全`。
4. **条目**：每条以 `- ` 开头，一条一句，句号结尾。
5. **底部链接**：文件末尾为每个版本维护 compare/tag 链接引用；新发布一个版本就把 `[Unreleased]` 的 compare 起点改为新版本号，并加一行 `[x.y.z]`。

## 写法

写用户能感受到的变化，说清楚在哪里、有什么不同。

| 不要这样写 | 改成 |
| --- | --- |
| 修复 WebView textZoom 跟随系统字号 | 系统字体调大后，App 内文字会额外放大。 |
| 重构 NativeWsManager 重连逻辑 | 网络恢复后更快收到新通知。 |
| 升级 AGP 到 9.4.1 | 不写（用户感受不到）。 |
| 修复若干已知问题 | 写出具体问题；确实零碎时写「修复了一些显示和操作上的小问题。」 |

不要出现：

- 文件路径、类名、接口名、配置项和命令。
- 内部术语和英文缩写（如 WebView、SDK、API、R8、壳层）。Android、Misskey、Wi-Fi 这类大家认识的产品名词可以用。
- 提交前缀、PR 编号、作者名。
- 反引号、链接、图片、表格、多级列表和表情符号。

用词：称呼用户用「你」；不写「全新」「大幅」「极致」，能写数字就写数字。

## 发布前自查

- [ ] Unreleased 内容已移入 `## [x.y.z] - 当天日期` 小节。
- [ ] 标题、标签、pubspec 版本号一致。
- [ ] 分组只用六个标准类别。
- [ ] 每条一句话，句号结尾，没有路径、接口名和英文缩写。
- [ ] 底部链接引用已更新。
