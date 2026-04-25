import 'package:flutter/services.dart';

const MethodChannel _nativeWebViewChannel = MethodChannel(
  'top.liminalselves.app/native_webview',
);

Future<bool> openAndroidNativeWebView({
  required String url,
  required String userAgentMarker,
}) async {
  final ok = await _nativeWebViewChannel.invokeMethod<bool>('open', {
    'url': url,
    'userAgentMarker': userAgentMarker,
  });
  return ok ?? false;
}
