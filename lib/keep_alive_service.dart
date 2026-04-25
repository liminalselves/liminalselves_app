import 'package:flutter/services.dart';

/// 控制 Android 前台服务以保持 App 在后台存活
class KeepAliveService {
  static const _channel = MethodChannel('top.liminalselves.app/keepalive');

  /// 启动前台服务，保持 App 存活
  static Future<bool> start() async {
    try {
      final result = await _channel.invokeMethod<bool>('start');
      return result ?? false;
    } catch (e) {
      print('[KeepAlive] start failed: $e');
      return false;
    }
  }

  /// 停止前台服务
  static Future<bool> stop() async {
    try {
      final result = await _channel.invokeMethod<bool>('stop');
      return result ?? false;
    } catch (e) {
      print('[KeepAlive] stop failed: $e');
      return false;
    }
  }
}
