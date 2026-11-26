# 更新日志

本项目的所有重要变更均记录于此。

格式基于 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，版本号遵循[语义化版本](https://semver.org/lang/zh-CN/)。书写规范见 [CHANGELOG-GUIDE.md](CHANGELOG-GUIDE.md)。

## [Unreleased]

## [1.1.1] - 2026-11-26

版本维护发布，客户端功能与运行行为较 1.1.0 无变化。

## [1.1.0] - 2026-11-22

### 变更

- 精简应用资源与调试日志，安装包体积由 1.66 MB 缩减至 1.41 MB。
- 优化应用启动与页面加载流程，缩短冷启动耗时并降低运行资源占用。
- Android 10 及以上设备开启推送时，不再启用 Wi-Fi 低延迟模式，避免影响前台网络传输与定位。

### 修复

- 修复开启推送后切换深浅色模式时，后台残留重复消息连接并持续耗电的问题。

## [1.0.0] - 2026-11-01

首个正式版本。

[Unreleased]: https://github.com/liminalselves/liminalselves_app/compare/v1.1.1...HEAD
[1.1.1]: https://github.com/liminalselves/liminalselves_app/compare/v1.1.0...v1.1.1
[1.1.0]: https://github.com/liminalselves/liminalselves_app/compare/v1.0.0...v1.1.0
[1.0.0]: https://github.com/liminalselves/liminalselves_app/releases/tag/v1.0.0
