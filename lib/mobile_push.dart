import 'dart:convert';

import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';
import 'package:http/http.dart' as http;
import 'package:permission_handler/permission_handler.dart';
import 'package:webview_flutter/webview_flutter.dart';

const MethodChannel _pushChannel = MethodChannel('top.liminalselves.app/push');

/// [SharedPreferences] 中是否启用原生推送（仅 Android 使用）。
const String kPrefNativePushEnabled = 'native_push_enabled';

/// Misskey 与壳约定（仅文档）：
/// - `AppNativePush.postMessage('enable' | 'disable' | 'query')`
/// - 壳向页面派发 `CustomEvent('liminal-native-push', { detail: { registered: bool } })`
/// - 因通知权限自动关闭推送时：`CustomEvent('liminal-native-push-alert')`（站内 `os.alert`）
/// - 应用信息：`window.__LIMINAL_APP_INFO__` + `liminal-app-info-updated`（版本与实例 meta 核对在 Misskey 设置 → 应用内完成）

/// 当前通知权限是否已授予（仅 Android；非 Android 为 false）。
Future<bool> isAndroidNotificationPermissionGranted() async {
  if (kIsWeb || defaultTargetPlatform != TargetPlatform.android) return false;
  final s = await Permission.notification.status;
  return s.isGranted;
}

/// Android 系统层「应用通知总开关」状态（区别于运行时 POST_NOTIFICATIONS 权限）。
Future<bool> isAndroidSystemNotificationsEnabled() async {
  if (kIsWeb || defaultTargetPlatform != TargetPlatform.android) return false;
  try {
    final enabled = await _pushChannel.invokeMethod<bool>('areNotificationsEnabled');
    return enabled ?? false;
  } catch (_) {
    return false;
  }
}

Future<String?> getAndroidPushDeviceId() async {
  if (kIsWeb || defaultTargetPlatform != TargetPlatform.android) return null;
  try {
    return await _pushChannel.invokeMethod<String>('getDeviceId');
  } catch (_) {
    return null;
  }
}

/// 请求通知权限（Android 13+）并在通过后启动阿里云 SDK；失败返回 null。
Future<String?> startAndroidNativePushAfterPermission() async {
  if (kIsWeb || defaultTargetPlatform != TargetPlatform.android) return null;
  final status = await Permission.notification.request();
  if (!status.isGranted) {
    debugPrint('[mobile-push] notification permission denied');
    return null;
  }
  if (!await isAndroidSystemNotificationsEnabled()) {
    debugPrint('[mobile-push] system notifications disabled');
    return null;
  }
  try {
    final id = await _pushChannel.invokeMethod<String>('startNativePush');
    if (id == null || id.isEmpty) return null;
    return id;
  } on PlatformException catch (e) {
    debugPrint('[mobile-push] startNativePush failed: ${e.code} ${e.message}');
    return null;
  }
}

Future<void> stopAndroidNativePush() async {
  if (kIsWeb || defaultTargetPlatform != TargetPlatform.android) return;
  try {
    await _pushChannel.invokeMethod<void>('stopNativePush');
  } catch (_) {
    /* 通道未就绪 */
  }
}

/// 向 Misskey 注销本机 deviceId（关闭推送开关时调用）。
Future<void> unregisterMobilePushWithMisskey({
  required WebViewController controller,
  required String apiOrigin,
  required String deviceId,
}) async {
  if (kIsWeb || defaultTargetPlatform != TargetPlatform.android) return;
  final base =
      apiOrigin.endsWith('/') ? apiOrigin.substring(0, apiOrigin.length - 1) : apiOrigin;
  final uri = Uri.parse('$base/api/mobile-push/unregister');
  final token = await _readMisskeyToken(controller);
  if (token == null || token.isEmpty) {
    debugPrint('[mobile-push] unregister skipped: no token');
    return;
  }
  try {
    final res = await http.post(
      uri,
      headers: {
        'Content-Type': 'application/json; charset=utf-8',
        'Authorization': 'Bearer $token',
      },
      body: jsonEncode({
        'i': token,
        'deviceId': deviceId,
      }),
    );
    if (res.statusCode == 200) {
      debugPrint('[mobile-push] Misskey /api/mobile-push/unregister OK');
    } else {
      debugPrint('[mobile-push] unregister HTTP ${res.statusCode}: ${res.body}');
    }
  } catch (e, st) {
    debugPrint('[mobile-push] unregister error: $e\n$st');
  }
}

/// 在阿里云 SDK 已上报 deviceId 且用户已登录 Misskey（localStorage `account`）后，向实例注册原生推送。
Future<void> tryRegisterMobilePushWithMisskey({
  required WebViewController controller,
  required String apiOrigin,
}) async {
  if (kIsWeb || defaultTargetPlatform != TargetPlatform.android) return;

  String? deviceId;
  for (var i = 0; i < 40; i++) {
    try {
      deviceId = await _pushChannel.invokeMethod<String>('getDeviceId');
    } catch (_) {
      /* 通道尚未就绪 */
    }
    if (deviceId != null && deviceId.isNotEmpty) break;
    await Future<void>.delayed(const Duration(milliseconds: 500));
  }
  if (deviceId == null || deviceId.isEmpty) {
    debugPrint(
      '[mobile-push] skip register: no deviceId (enable native push in app menu first)',
    );
    return;
  }

  final base =
      apiOrigin.endsWith('/') ? apiOrigin.substring(0, apiOrigin.length - 1) : apiOrigin;
  final uri = Uri.parse('$base/api/mobile-push/register');

  for (var j = 0; j < 90; j++) {
    final token = await _readMisskeyToken(controller);
    if (token != null && token.isNotEmpty) {
      try {
        final res = await http.post(
          uri,
          headers: {
            'Content-Type': 'application/json; charset=utf-8',
            'Authorization': 'Bearer $token',
          },
          body: jsonEncode({
            'i': token,
            'deviceId': deviceId,
            'platform': 'android',
          }),
        );
        if (res.statusCode == 200) {
          debugPrint('[mobile-push] Misskey /api/mobile-push/register OK');
          return;
        }
        if (res.statusCode == 401) {
          debugPrint(
            '[mobile-push] register 401: token invalid or revoked. '
            'Re-login in Misskey inside the app, or check WebView localStorage account.token.',
          );
          return;
        }
        debugPrint('[mobile-push] register HTTP ${res.statusCode}: ${res.body}');
      } catch (e, st) {
        debugPrint('[mobile-push] register request error: $e\n$st');
      }
    }
    await Future<void>.delayed(const Duration(seconds: 2));
  }
  debugPrint('[mobile-push] gave up: login token missing or register never returned 200 (see logs above)');
}

Future<String?> _readMisskeyToken(WebViewController c) async {
  try {
    final v = await c.runJavaScriptReturningResult(
      r"(function(){try{var r=localStorage.getItem('account');return r?r:'';}catch(e){return '';}})()",
    );
    if (v is! String) return null;
    var raw = v.trim();
    if (raw.isEmpty) return null;
    dynamic decoded = raw;
    for (var k = 0; k < 4; k++) {
      if (decoded is String) {
        try {
          decoded = jsonDecode(decoded);
        } catch (_) {
          return null;
        }
      } else {
        break;
      }
    }
    final map = decoded is Map<String, dynamic> ? decoded : null;
    final token = map?['token'];
    if (token is String && token.isNotEmpty) return token.trim();
  } catch (_) {
    /* WebView 未就绪或 JSON 无效 */
  }
  return null;
}
