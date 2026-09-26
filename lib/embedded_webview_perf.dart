import 'package:flutter/foundation.dart';
import 'package:flutter/gestures.dart';
import 'package:flutter/widgets.dart';
import 'package:webview_flutter/webview_flutter.dart';
import 'package:webview_flutter_wkwebview/webview_flutter_wkwebview.dart';

/// 关闭系统滚动条绘制等，减轻合成与设置开销；不关闭缩放/地理定位，以免影响 Misskey。
/// 注：Android 主线为原生 WebView 壳，不经过 Flutter WebView，此文件仅服务 iOS/macOS。
Future<void> tuneEmbeddedWebViewPerformance(WebViewController controller) async {
  if (kIsWeb) return;
  try {
    if (await controller.supportsSetScrollBarsEnabled()) {
      await controller.setVerticalScrollBarEnabled(false);
      await controller.setHorizontalScrollBarEnabled(false);
    }
  } catch (e, st) {
    debugPrint('[WebView perf] scrollbars: $e\n$st');
  }

  if (controller.platform is WebKitWebViewController) {
    try {
      await (controller.platform as WebKitWebViewController)
          .setAllowsLinkPreview(false);
      if (kReleaseMode) {
        await (controller.platform as WebKitWebViewController)
            .setInspectable(false);
      }
    } catch (e, st) {
      debugPrint('[WebView perf] WebKit: $e\n$st');
    }
  }
}

/// iOS / macOS：使用 WebKit 专用 [PlatformWebViewWidgetCreationParams]。
Widget buildPerformanceTunedWebView(WebViewController controller) {
  final PlatformWebViewWidgetCreationParams base =
      PlatformWebViewWidgetCreationParams(
    controller: controller.platform,
    layoutDirection: TextDirection.ltr,
    gestureRecognizers: const <Factory<OneSequenceGestureRecognizer>>{},
  );

  if (WebViewPlatform.instance is WebKitWebViewPlatform) {
    return WebViewWidget.fromPlatformCreationParams(
      params: WebKitWebViewWidgetCreationParams
          .fromPlatformWebViewWidgetCreationParams(base),
    );
  }
  return WebViewWidget(controller: controller);
}