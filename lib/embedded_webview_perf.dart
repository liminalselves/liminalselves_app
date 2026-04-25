import 'package:flutter/foundation.dart';
import 'package:flutter/gestures.dart';
import 'package:flutter/widgets.dart';
import 'package:webview_flutter/webview_flutter.dart';
import 'package:webview_flutter_android/webview_flutter_android.dart';
import 'package:webview_flutter_wkwebview/webview_flutter_wkwebview.dart';

/// 关闭系统滚动条绘制、关闭少用 Web API 等，减轻合成与设置开销；不关闭缩放/地理定位，以免影响 Misskey。
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

  final Object platform = controller.platform;
  if (platform is AndroidWebViewController) {
    try {
      if (await platform.isWebViewFeatureSupported(
            WebViewFeatureType.paymentRequest,
          )) {
        await platform.setPaymentRequestEnabled(false);
      }
    } catch (e, st) {
      debugPrint('[WebView perf] Android: $e\n$st');
    }
  } else if (platform is WebKitWebViewController) {
    try {
      await platform.setAllowsLinkPreview(false);
      if (kReleaseMode) {
        await platform.setInspectable(false);
      }
    } catch (e, st) {
      debugPrint('[WebView perf] WebKit: $e\n$st');
    }
  }
}

/// Android：显式 [displayWithHybridComposition] = false（纹理 PlatformView，全屏 WebView 通常优于混合合成）。
/// iOS / macOS：使用 WebKit 专用 [PlatformWebViewWidgetCreationParams]。
Widget buildPerformanceTunedWebView(WebViewController controller) {
  final PlatformWebViewWidgetCreationParams base =
      PlatformWebViewWidgetCreationParams(
    controller: controller.platform,
    layoutDirection: TextDirection.ltr,
    gestureRecognizers: const <Factory<OneSequenceGestureRecognizer>>{},
  );

  if (WebViewPlatform.instance is AndroidWebViewPlatform) {
    return WebViewWidget.fromPlatformCreationParams(
      params: AndroidWebViewWidgetCreationParams
          .fromPlatformWebViewWidgetCreationParams(
        base,
        displayWithHybridComposition: false,
      ),
    );
  }
  if (WebViewPlatform.instance is WebKitWebViewPlatform) {
    return WebViewWidget.fromPlatformCreationParams(
      params: WebKitWebViewWidgetCreationParams
          .fromPlatformWebViewWidgetCreationParams(base),
    );
  }
  return WebViewWidget(controller: controller);
}
