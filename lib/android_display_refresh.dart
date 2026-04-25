import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter/widgets.dart';
import 'package:flutter_displaymode/flutter_displaymode.dart';

/// 在 [supported] 里选出应尽量高的刷新率模式。
///
/// [FlutterDisplayMode.setHighRefreshRate] 只在「与 [active] 宽高完全一致」的模式里挑最高 Hz；
/// 冷启动或 [main] 阶段 [active] 尺寸与列表项对不上时，会**完全选不中**高刷模式。
/// 此处先取全局最高 Hz，若并列则优先保持当前 [active] 分辨率。
DisplayMode _pickBestMode(List<DisplayMode> modes, DisplayMode active) {
  DisplayMode? globalBest;
  DisplayMode? sameResBest;
  for (final DisplayMode m in modes) {
    if (m.id == 0) continue;
    if (globalBest == null || m.refreshRate > globalBest.refreshRate) {
      globalBest = m;
    }
    if (m.width == active.width && m.height == active.height) {
      if (sameResBest == null || m.refreshRate > sameResBest.refreshRate) {
        sameResBest = m;
      }
    }
  }
  final DisplayMode g = globalBest ?? active;
  final DisplayMode? s = sameResBest;
  if (s != null && s.refreshRate == g.refreshRate) {
    return s;
  }
  return g;
}

/// 请求系统使用更高刷新率（在支持设备上）。应在 **首帧之后** 调用，必要时从 [resumed] 再调用一次。
Future<void> applyAndroidBestDisplayMode() async {
  if (kIsWeb || defaultTargetPlatform != TargetPlatform.android) return;
  try {
    final List<DisplayMode> modes = await FlutterDisplayMode.supported;
    final DisplayMode active = await FlutterDisplayMode.active;
    final DisplayMode pick = _pickBestMode(modes, active);
    if (pick.id == 0 || pick.refreshRate <= 0) return;

    await FlutterDisplayMode.setPreferredMode(pick);

    if (kDebugMode) {
      final DisplayMode after = await FlutterDisplayMode.active;
      final DisplayMode pref = await FlutterDisplayMode.preferred;
      debugPrint(
        '[DisplayMode] active=$active → preferredMode=$pick '
        'activeAfter=$after preferred=$pref',
      );
    }
  } catch (e, st) {
    debugPrint('[DisplayMode] applyAndroidBestDisplayMode: $e\n$st');
  }
}

/// 等一帧且略延迟后再 [applyAndroidBestDisplayMode]，避免 Activity / Display 未稳定。
void scheduleAndroidDisplayModeAfterUiReady() {
  if (kIsWeb || defaultTargetPlatform != TargetPlatform.android) return;
  WidgetsBinding.instance.addPostFrameCallback((_) {
    unawaited(
      Future<void>.delayed(
        const Duration(milliseconds: 120),
        applyAndroidBestDisplayMode,
      ),
    );
    /// 部分机型在首帧后仍会改 Display 模式；稍后再设一次，与 [MainActivity] 的窗口 hint 叠加。
    unawaited(
      Future<void>.delayed(
        const Duration(milliseconds: 900),
        applyAndroidBestDisplayMode,
      ),
    );
  });
}
