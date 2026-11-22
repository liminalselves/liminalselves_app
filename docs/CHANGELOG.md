# 更新日志

本项目的所有重要变更都记录在此文件中。

格式基于 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，版本号遵循[语义化版本](https://semver.org/lang/zh-CN/)。书写规范见 [CHANGELOG-GUIDE.md](CHANGELOG-GUIDE.md)。

## [Unreleased]

## [1.1.0] - 2026-11-22

### 变更

- 安装包从 1.66 MB 缩小到 1.41 MB。
- Android 10 及以上开启推送时，不再让 Wi-Fi 进入低延迟模式，前台浏览时网速和定位不受影响。

### 修复

- 开启推送后切换深浅色模式，后台会多出一条重复的消息连接并持续耗电。

## [1.0.0] - 2026-11-01

首个正式版本。

### 修复

- 系统字体调大后，App 内文字会额外放大，按钮图标偏离圆形中央。
- 从桌面或通知打开 App 时，最近任务里会出现两张卡片，且回到 App 时页面被重新加载。
- 应用检查更新期间连续点击通知，可能跳不到通知对应的页面。

[Unreleased]: https://github.com/liminalselves/liminalselves_app/compare/v1.1.0...HEAD
[1.1.0]: https://github.com/liminalselves/liminalselves_app/compare/v1.0.0...v1.1.0
[1.0.0]: https://github.com/liminalselves/liminalselves_app/releases/tag/v1.0.0
