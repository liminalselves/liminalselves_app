import 'dart:async' show unawaited;
import 'dart:convert';
import 'dart:math' as math;

import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:package_info_plus/package_info_plus.dart';
import 'package:permission_handler/permission_handler.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:url_launcher/url_launcher.dart';
import 'package:webview_flutter/webview_flutter.dart';

import 'webview_web_register_stub.dart'
    if (dart.library.html) 'webview_web_register_web.dart';

import 'android_native_webview_launcher.dart';
import 'embedded_webview_perf.dart';
import 'keep_alive_service.dart';
import 'mobile_push.dart';
import 'shell_version_check.dart';
import 'webview_file_selector.dart';

/// 默认 Misskey 实例；后续可改为设置页或 `--dart-define=MISSKEY_URL=...`。
const String kMisskeyBaseUrl = String.fromEnvironment(
  'MISSKEY_URL',
  defaultValue: 'https://misskey.liminalselves.top/',
);

/// 仅允许在 WebView 内打开的站点：*.liminalselves.top 与 liminalselves.top。
bool _isLiminalselvesHttpHost(String host) {
  final h = host.toLowerCase();
  return h == 'liminalselves.top' || h.endsWith('.liminalselves.top');
}

Uri _effectiveNavigationUri(String requestUrl) {
  final parsed = Uri.parse(requestUrl);
  if (parsed.hasScheme && parsed.host.isNotEmpty) return parsed;
  return Uri.parse(kMisskeyBaseUrl).resolve(requestUrl);
}

/// 为 true 时允许在 **Flutter Web** 内用 iframe 加载 Misskey（需本机/实例已允许被嵌入，例如已去掉 X-Frame-Options）。
/// 默认 false：Web 仍走「新标签打开」说明页，与服务器头是否已改无关。
const bool kAllowWebEmbed = bool.fromEnvironment(
  'ALLOW_WEB_EMBED',
  defaultValue: false,
);

/// 须与 Misskey 前端 `EMBEDDED_APP_SHELL_USER_AGENT_MARKER` 一致，用于区分 App WebView 与普通浏览器。
const String kEmbeddedAppUserAgentMarker = 'LiminalSelvesApp';

/// 与 Misskey `MkPushNotificationAllowButton` 中 `CustomEvent` 名称一致。
const String _kLiminalNativePushEvent = 'liminal-native-push';

/// 与 Misskey `main-boot.ts` 中监听名称一致；触发站内 `os.alert`（MkDialog）。
const String _kLiminalNativePushAlertEvent = 'liminal-native-push-alert';

/// Misskey 通过 `AppNativePush.postMessage(...)` 调用；仅 Android 原生推送逻辑，无额外 Flutter UI。
String? _userAgentWithEmbeddedShellMarker() {
  const m = kEmbeddedAppUserAgentMarker;
  switch (defaultTargetPlatform) {
    case TargetPlatform.android:
      return 'Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36 $m';
    case TargetPlatform.iOS:
      return 'Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1 $m';
    case TargetPlatform.macOS:
      return 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Safari/605.1.15 $m';
    default:
      return null;
  }
}

bool get _inlineWebViewSupported {
  if (kIsWeb) return true;
  switch (defaultTargetPlatform) {
    case TargetPlatform.android:
    case TargetPlatform.iOS:
    case TargetPlatform.macOS:
      return true;
    default:
      return false;
  }
}

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  registerWebViewForWeb();
  runApp(const LiminalRootApp());
}

const _kFlutterBlue = Color(0xFF1A73E8);

Widget _whiteSystemBars({required Widget child}) {
  return AnnotatedRegion<SystemUiOverlayStyle>(
    value: const SystemUiOverlayStyle(
      statusBarColor: Colors.white,
      systemNavigationBarColor: Colors.white,
      statusBarIconBrightness: Brightness.dark,
      systemNavigationBarIconBrightness: Brightness.dark,
      statusBarBrightness: Brightness.light,
    ),
    child: child,
  );
}

class LiminalRootApp extends StatefulWidget {
  const LiminalRootApp({super.key});

  @override
  State<LiminalRootApp> createState() => _LiminalRootAppState();
}

class _LiminalRootAppState extends State<LiminalRootApp> {
  ShellStartupOutcome? _outcome;

  @override
  void initState() {
    super.initState();
    unawaited(_bootstrap());
  }

  Future<void> _bootstrap() async {
    final pkg = await PackageInfo.fromPlatform();
    final o = await evaluateShellVersionPolicy(
      misskeyBaseUrl: kMisskeyBaseUrl,
      packageInfo: pkg,
    );
    if (!mounted) return;
    setState(() => _outcome = o);
  }

  @override
  Widget build(BuildContext context) {
    const fallbackSeed = _kFlutterBlue;
    if (_outcome == null) {
      return MaterialApp(
        title: '阈界人格',
        debugShowCheckedModeBanner: false,
        theme: ThemeData(
          colorScheme: ColorScheme.fromSeed(seedColor: fallbackSeed),
          useMaterial3: true,
        ),
        home: _whiteSystemBars(
          child: const Scaffold(
            backgroundColor: Colors.white,
            body: SafeArea(
              child: Center(
                child: CircularProgressIndicator(color: _kFlutterBlue),
              ),
            ),
          ),
        ),
      );
    }

    final o = _outcome!;
    final seed = fallbackSeed;
    final theme = ThemeData(
      colorScheme: ColorScheme.fromSeed(seedColor: seed),
      useMaterial3: true,
    );

    if (o.forceBlocked && o.force != null) {
      return MaterialApp(
        title: '阈界人格',
        debugShowCheckedModeBanner: false,
        theme: theme,
        home: ForceShellUpdatePage(info: o.force!),
      );
    }

    return MaterialApp(
      title: '阈界人格',
      debugShowCheckedModeBanner: false,
      theme: theme,
      home: MisskeyWebShell(optionalShellUpdateHint: o.optional),
    );
  }
}

class MisskeyWebShell extends StatefulWidget {
  const MisskeyWebShell({super.key, this.optionalShellUpdateHint});

  final OptionalShellUpdateHint? optionalShellUpdateHint;

  @override
  State<MisskeyWebShell> createState() => _MisskeyWebShellState();
}

class _MisskeyWebShellState extends State<MisskeyWebShell>
    with WidgetsBindingObserver {
  WebViewController? _controller;
  bool _useEmbeddedWebView = false;
  bool _launchingNativeAndroid = false;
  bool _nativeAndroidLaunchFailed = false;
  bool _optionalShellUpdateDialogScheduled = false;

  /// 仅驱动顶部细进度条，避免 [onProgress] 高频触发整页 [setState] 导致卡顿。
  final ValueNotifier<int> _loadProgressNotifier = ValueNotifier(0);
  final ValueNotifier<bool> _pageReadyNotifier = ValueNotifier(false);

  /// 固定 [Listenable.merge] 实例，避免每次 [build] 新建导致 [ListenableBuilder] 反复换监听目标。
  late final Listenable _embeddedLoadUiListenable = Listenable.merge([
    _loadProgressNotifier,
    _pageReadyNotifier,
  ]);

  /// 已与原生 WebView 同步过的 [ColorScheme.surface]，深浅色切换时再应用一次。
  Color? _webViewSurfaceSynced;

  /// 避免 SPA 多次 [onPageFinished] 重复执行恢复逻辑。
  bool _androidPushRestoreAttempted = false;

  /// 每次进程启动只做一次的「推送已开则核对通知权限」检查。
  bool _androidPushPermissionStartupChecked = false;

  /// 进入后台时刻；用于 [resumed] 时判断是否需要刷新 WebView（缓解长时间挂起后的白屏/卡死）。
  DateTime? _lastPausedAt;
  int _webResumeRecoverGeneration = 0;

  /// 嵌入 WebView 已无历史时的「再按一次返回退出」防抖（与原生 [NativeWebViewActivity] 一致）。
  DateTime? _lastExitBackPressAt;

  static const Duration _kExitConfirmWindow = Duration(seconds: 2);

  static const String _kExitBackSnackMessage = '再次点击返回键退出应用';

  @override
  void dispose() {
    if (_useEmbeddedWebView && !kIsWeb) {
      WidgetsBinding.instance.removeObserver(this);
    }
    _loadProgressNotifier.dispose();
    _pageReadyNotifier.dispose();
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    super.didChangeAppLifecycleState(state);
    if (!_useEmbeddedWebView || kIsWeb) return;
    if (state == AppLifecycleState.paused ||
        state == AppLifecycleState.hidden) {
      _lastPausedAt = DateTime.now();
      // 进入后台时启动前台服务，保持 WebSocket 连接
      unawaited(KeepAliveService.start());
      return;
    }
    if (state == AppLifecycleState.resumed) {
      final t = _lastPausedAt;
      _lastPausedAt = null;
      // 回到前台时停止前台服务
      unawaited(KeepAliveService.stop());
      if (t != null) {
        final gap = DateTime.now().difference(t);
        unawaited(_recoverWebViewAfterBackground(gap));
      }
    }
  }

  /// Android / iOS WebView 从后台恢复后先探活，仅在 JS 已无响应时刷新。
  /// 不按后台时长强制刷新，避免中断或重放仍在进行的非幂等请求。
  Future<void> _recoverWebViewAfterBackground(
    Duration backgroundDuration,
  ) async {
    if (kIsWeb || !_useEmbeddedWebView) return;
    final c = _controller;
    if (!mounted || c == null) return;

    const minGap = Duration(seconds: 30);
    if (backgroundDuration < minGap) return;

    final token = ++_webResumeRecoverGeneration;

    try {
      await c
          .runJavaScriptReturningResult('true')
          .timeout(const Duration(seconds: 2));
    } catch (e, st) {
      if (!mounted || token != _webResumeRecoverGeneration) return;
      debugPrint(
        '[WebShell] resume probe failed (${backgroundDuration.inSeconds}s): $e\n$st',
      );
      try {
        await c.reload();
      } catch (e2) {
        debugPrint('[WebShell] reload after probe failure: $e2');
      }
    }
  }

  /// [WebView] 的 [onProgress] 极频繁；只把变化传递到 [ValueNotifier]，并约按 10% 阶梯更新，减轻 UI 压力。
  void _applyNavigationProgress(int value) {
    final v = value.clamp(0, 100);
    final cur = _loadProgressNotifier.value;
    if (v == cur) return;
    if (v == 0 || v >= 100 || (v ~/ 10) != (cur ~/ 10)) {
      _loadProgressNotifier.value = v;
    }
  }

  @override
  void initState() {
    super.initState();
    if (!kIsWeb && defaultTargetPlatform == TargetPlatform.android) {
      _launchingNativeAndroid = true;
      WidgetsBinding.instance.addPostFrameCallback((_) {
        unawaited(_startAndroidLaunchFlow());
      });
      return;
    }
    final platformOk =
        _inlineWebViewSupported && WebViewPlatform.instance != null;
    _useEmbeddedWebView = platformOk && (!kIsWeb || kAllowWebEmbed);
    if (_useEmbeddedWebView && !kIsWeb) {
      WidgetsBinding.instance.addObserver(this);
    }
    _scheduleOptionalShellUpdateDialog();
    if (!_useEmbeddedWebView) return;

    unawaited(_initEmbeddedWebView());
  }

  Future<void> _openAndroidNativeShell() async {
    try {
      final ok = await openAndroidNativeWebView(
        url: kMisskeyBaseUrl,
        userAgentMarker: kEmbeddedAppUserAgentMarker,
      );
      if (!ok && mounted) {
        setState(() => _nativeAndroidLaunchFailed = true);
      }
    } catch (_) {
      if (mounted) {
        setState(() => _nativeAndroidLaunchFailed = true);
      }
    }
  }

  Future<void> _startAndroidLaunchFlow() async {
    if (!mounted) return;
    final h = widget.optionalShellUpdateHint;
    if (h != null) {
      final openedUpdate = await showOptionalShellUpdateDialog(context, h);
      if (openedUpdate) return;
    }
    if (!mounted) return;
    await _openAndroidNativeShell();
  }

  void _scheduleOptionalShellUpdateDialog() {
    final h = widget.optionalShellUpdateHint;
    if (h == null || _optionalShellUpdateDialogScheduled) return;
    _optionalShellUpdateDialogScheduled = true;
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!mounted) return;
      unawaited(showOptionalShellUpdateDialog(context, h));
    });
  }

  /// 仅在有可滚动内容时允许边缘 overscroll，减少无意义的光效合成（部分机型上略省 GPU）。
  Future<void> _tuneWebViewOverScroll(WebViewController controller) async {
    if (kIsWeb) return;
    try {
      await controller.setOverScrollMode(
        WebViewOverScrollMode.ifContentScrolls,
      );
    } catch (e, st) {
      debugPrint('[WebShell] setOverScrollMode: $e\n$st');
    }
  }

  Future<void> _syncWebViewSurfaceColor(
    WebViewController controller,
    Color surface,
  ) async {
    if (kIsWeb) return;
    try {
      await controller.setBackgroundColor(surface);
    } catch (e, st) {
      debugPrint('[WebShell] setBackgroundColor: $e\n$st');
    }
  }

  Future<void> _initEmbeddedWebView() async {
    final uri = Uri.parse(kMisskeyBaseUrl);
    final controller = WebViewController();

    if (!kIsWeb) {
      controller
        ..setJavaScriptMode(JavaScriptMode.unrestricted)
        ..addJavaScriptChannel(
          'AppNativePush',
          onMessageReceived: (JavaScriptMessage message) {
            final c = _controller;
            if (c == null) return;
            unawaited(_handleAppNativePushMessage(message.message, c));
          },
        )
        ..setNavigationDelegate(
          NavigationDelegate(
            onNavigationRequest: _handleNavigationDelegateRequest,
            onProgress: _applyNavigationProgress,
            onPageStarted: (_) {
              _pageReadyNotifier.value = false;
              _loadProgressNotifier.value = 0;
            },
            onPageFinished: (_) {
              _loadProgressNotifier.value = 100;
              _pageReadyNotifier.value = true;
              if (!kIsWeb &&
                  (defaultTargetPlatform == TargetPlatform.android ||
                      defaultTargetPlatform == TargetPlatform.iOS)) {
                unawaited(_afterEmbeddedMobileWebViewPageFinished(controller));
              }
            },
          ),
        );
      if (defaultTargetPlatform == TargetPlatform.android) {
        await configureAndroidWebViewFileSelector(controller);
      }
      await tuneEmbeddedWebViewPerformance(controller);
      unawaited(_tuneWebViewOverScroll(controller));
    }

    final ua = _userAgentWithEmbeddedShellMarker();
    if (ua != null) {
      await controller.setUserAgent(ua);
    }

    await controller.loadRequest(uri);
    if (kIsWeb && mounted) {
      _loadProgressNotifier.value = 100;
      _pageReadyNotifier.value = true;
    }
    if (!mounted) return;
    setState(() => _controller = controller);
  }

  /// [errorCode] 仅用于「用户点启用」等需提示的场景；[query] 同步状态时不要带 code，避免误弹窗。
  Future<void> _dispatchNativePushStateToWeb(
    WebViewController c, {
    required bool registered,
    String? errorCode,
  }) async {
    try {
      final detail = <String, dynamic>{'registered': registered};
      if (errorCode != null) {
        detail['errorCode'] = errorCode;
      }
      final jsonLiteral = jsonEncode(detail);
      await c.runJavaScript(
        "window.dispatchEvent(new CustomEvent('$_kLiminalNativePushEvent', { detail: $jsonLiteral }));",
      );
    } catch (e, st) {
      debugPrint('[AppNativePush] dispatch state failed: $e\n$st');
    }
  }

  Future<void> _injectNativePushStateFromPrefs(WebViewController c) async {
    if (kIsWeb || defaultTargetPlatform != TargetPlatform.android) return;
    final prefs = await SharedPreferences.getInstance();
    final enabledInSystem = await isAndroidSystemNotificationsEnabled();
    await _dispatchNativePushStateToWeb(
      c,
      registered:
          (prefs.getBool(kPrefNativePushEnabled) ?? false) && enabledInSystem,
    );
  }

  Future<void> _handleAppNativePushMessage(
    String? msg,
    WebViewController c,
  ) async {
    if (kIsWeb) return;
    final m = msg?.trim();

    /// iOS / macOS：暂无与 Android 对等的原生推送通道，但必须应答 [query]，否则前端一直收不到状态。
    if (defaultTargetPlatform != TargetPlatform.android) {
      switch (m) {
        case 'query':
        case 'disable':
          await _dispatchNativePushStateToWeb(c, registered: false);
        case 'enable':
          await _dispatchNativePushStateToWeb(
            c,
            registered: false,
            errorCode: 'android_only',
          );
        default:
          break;
      }
      return;
    }

    switch (m) {
      case 'enable':
        final ok = await _nativePushEnableFromBridge();
        if (ok) {
          await _dispatchNativePushStateToWeb(c, registered: true);
        } else {
          final enabledInSystem = await isAndroidSystemNotificationsEnabled();
          await _dispatchNativePushStateToWeb(
            c,
            registered: false,
            errorCode: enabledInSystem
                ? 'push_setup_failed'
                : 'permission_denied',
          );
        }
      case 'disable':
        await _nativePushDisableFromBridge();
        await _dispatchNativePushStateToWeb(c, registered: false);
      case 'query':
        await _injectNativePushStateFromPrefs(c);
      default:
        break;
    }
  }

  Future<bool> _nativePushEnableFromBridge() async {
    // Check notification permission first
    if (!kIsWeb && defaultTargetPlatform == TargetPlatform.android) {
      final granted = await isAndroidNotificationPermissionGranted();
      final enabledInSystem = await isAndroidSystemNotificationsEnabled();
      if (!granted || !enabledInSystem) {
        debugPrint(
          '[AppNativePush] notification permission/system switch not ready',
        );
        return false;
      }
    }

    final prefs = await SharedPreferences.getInstance();
    final apiOrigin = Uri.parse(kMisskeyBaseUrl).origin;
    final id = await startAndroidNativePushAfterPermission();
    if (id == null) return false;
    await prefs.setBool(kPrefNativePushEnabled, true);
    final c = _controller;
    if (c != null) {
      unawaited(
        tryRegisterMobilePushWithMisskey(controller: c, apiOrigin: apiOrigin),
      );
      // Notify Misskey that app push is enabled (WebSocket preferred push)
      try {
        await c.runJavaScript('''
          (async () => {
            await fetch('$apiOrigin/api/i/update', {
              method: 'POST',
              headers: {
                'Content-Type': 'application/json',
                'Authorization': localStorage.getItem('i') ? 'Bearer ' + localStorage.getItem('i') : ''
              },
              body: JSON.stringify({ enableAppPush: true })
            });
          })();
        ''');
      } catch (e) {
        debugPrint('[AppNativePush] failed to notify Misskey: $e');
      }
    }
    return true;
  }

  Future<void> _nativePushDisableFromBridge({WebViewController? web}) async {
    final prefs = await SharedPreferences.getInstance();
    final apiOrigin = Uri.parse(kMisskeyBaseUrl).origin;
    final c = web ?? _controller;
    await stopAndroidNativePush();
    await prefs.setBool(kPrefNativePushEnabled, false);
    _androidPushRestoreAttempted = false;

    // Notify Misskey that app push is disabled
    if (c != null) {
      try {
        await c.runJavaScript('''
          (async () => {
            await fetch('$apiOrigin/api/i/update', {
              method: 'POST',
              headers: {
                'Content-Type': 'application/json',
                'Authorization': localStorage.getItem('i') ? 'Bearer ' + localStorage.getItem('i') : ''
              },
              body: JSON.stringify({ enableAppPush: false })
            });
          })();
        ''');
      } catch (e) {
        debugPrint('[AppNativePush] failed to notify Misskey: $e');
      }
    }
  }

  Future<void> _injectLiminalAppInfo(WebViewController c) async {
    if (kIsWeb) return;
    if (defaultTargetPlatform != TargetPlatform.android &&
        defaultTargetPlatform != TargetPlatform.iOS) {
      return;
    }
    try {
      final p = await PackageInfo.fromPlatform();
      final map = <String, Object>{
        'appName': p.appName,
        'version': p.version,
        'buildNumber': p.buildNumber,
        'packageName': p.packageName,
        'platform': defaultTargetPlatform == TargetPlatform.android
            ? 'android'
            : 'ios',
      };
      final jsonLiteral = jsonEncode(map);
      await c.runJavaScript(
        'window.__LIMINAL_APP_INFO__ = $jsonLiteral; '
        "window.dispatchEvent(new CustomEvent('liminal-app-info-updated'));",
      );
    } catch (e, st) {
      debugPrint('[Liminal] inject __LIMINAL_APP_INFO__ failed: $e\n$st');
    }
  }

  Future<void> _afterEmbeddedMobileWebViewPageFinished(
    WebViewController controller,
  ) async {
    await _injectLiminalAppInfo(controller);
    if (defaultTargetPlatform != TargetPlatform.android) return;
    if (!_androidPushPermissionStartupChecked) {
      _androidPushPermissionStartupChecked = true;
      await _reconcileStartupNotificationPermission(controller);
    }
    await _injectNativePushStateFromPrefs(controller);
    await _onEmbeddedPageReadyForPush(controller);
  }

  /// 推送开关已开时：若无通知权限则请求；仍拒绝则关闭推送并注销，省推送成本。
  Future<void> _reconcileStartupNotificationPermission(
    WebViewController controller,
  ) async {
    if (kIsWeb || defaultTargetPlatform != TargetPlatform.android) return;
    final prefs = await SharedPreferences.getInstance();
    if (!(prefs.getBool(kPrefNativePushEnabled) ?? false)) return;

    if (await isAndroidSystemNotificationsEnabled()) return;

    final afterRequest = await Permission.notification.request();
    if (afterRequest.isGranted && await isAndroidSystemNotificationsEnabled()) {
      return;
    }

    debugPrint(
      '[AppNativePush] startup: notification permission missing/denied; '
      'disabling native push to save cost',
    );
    await _nativePushDisableFromBridge(web: controller);
    await _dispatchNativePushStateToWeb(controller, registered: false);
    await _dispatchNativePushPermissionAlertToWeb(controller);
  }

  Future<void> _dispatchNativePushPermissionAlertToWeb(
    WebViewController c,
  ) async {
    try {
      await c.runJavaScript(
        "window.dispatchEvent(new CustomEvent('$_kLiminalNativePushAlertEvent'));",
      );
    } catch (e, st) {
      debugPrint(
        '[AppNativePush] liminal-native-push-alert dispatch failed: $e\n$st',
      );
    }
  }

  /// 用户此前已在 App 内启用推送时：补一次阿里云 + Misskey 注册（不弹 Flutter UI）。
  Future<void> _onEmbeddedPageReadyForPush(WebViewController controller) async {
    final prefs = await SharedPreferences.getInstance();
    final want = prefs.getBool(kPrefNativePushEnabled) ?? false;
    if (!want || _androidPushRestoreAttempted) return;
    _androidPushRestoreAttempted = true;
    final id = await startAndroidNativePushAfterPermission();
    if (!mounted || id == null) return;
    unawaited(
      tryRegisterMobilePushWithMisskey(
        controller: controller,
        apiOrigin: Uri.parse(kMisskeyBaseUrl).origin,
      ),
    );
  }

  Future<void> _openInExternalBrowser() async {
    final uri = Uri.parse(kMisskeyBaseUrl);
    final ok = await launchUrl(uri, mode: LaunchMode.externalApplication);
    if (!ok && mounted) {
      ScaffoldMessenger.of(
        context,
      ).showSnackBar(const SnackBar(content: Text('无法在浏览器中打开链接')));
    }
  }

  /// 主框架离开 *.liminalselves.top 时：提示并在系统浏览器打开；子框架（iframe）仍由内嵌页处理。
  Future<NavigationDecision> _handleNavigationDelegateRequest(
    NavigationRequest request,
  ) async {
    if (!request.isMainFrame) {
      return NavigationDecision.navigate;
    }

    final uri = _effectiveNavigationUri(request.url);
    final scheme = uri.scheme.toLowerCase();

    if (scheme == 'javascript' ||
        scheme == 'about' ||
        scheme == 'blob' ||
        scheme == 'data') {
      return NavigationDecision.navigate;
    }

    if (scheme != 'http' && scheme != 'https') {
      if (await canLaunchUrl(uri)) {
        await launchUrl(uri, mode: LaunchMode.externalApplication);
      }
      return NavigationDecision.prevent;
    }

    if (_isLiminalselvesHttpHost(uri.host)) {
      return NavigationDecision.navigate;
    }

    if (!mounted) return NavigationDecision.prevent;

    final go = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('即将离开站点'),
        content: SingleChildScrollView(
          child: SelectableText(
            '当前链接不在 liminalselves.top 域内，将在系统默认浏览器中打开：\n\n'
            '${uri.toString()}',
          ),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(ctx).pop(false),
            child: const Text('取消'),
          ),
          FilledButton(
            onPressed: () => Navigator.of(ctx).pop(true),
            child: const Text('在浏览器中打开'),
          ),
        ],
      ),
    );

    if (go == true && mounted) {
      if (await canLaunchUrl(uri)) {
        await launchUrl(uri, mode: LaunchMode.externalApplication);
      } else if (mounted) {
        ScaffoldMessenger.of(
          context,
        ).showSnackBar(const SnackBar(content: Text('无法打开该链接')));
      }
    }
    return NavigationDecision.prevent;
  }

  String _fallbackExplanation() {
    if (kIsWeb) {
      return '当前为 Flutter Web：默认**不**在页面内嵌 Misskey（与服务器是否已关 X-Frame-Options **无关**，应用并未检测响应头）。'
          '请点下方按钮在新标签打开本地站点；或运行 '
          '`flutter run -d chrome --dart-define=ALLOW_WEB_EMBED=true --dart-define=MISSKEY_URL=...` '
          '在已允许 iframe 嵌入时再试内嵌。'
          '调试 WebView 更推荐 `flutter run -d windows` 或 Android 模拟器/真机。';
    }
    return '此处无法内嵌网页（例如 Windows / Linux 桌面、或未注册 WebView 的测试环境）。'
        '请用浏览器打开，或使用 flutter run 在 Android / iOS 设备上运行。';
  }

  @override
  Widget build(BuildContext context) {
    if (_launchingNativeAndroid) {
      if (_nativeAndroidLaunchFailed) {
        return Scaffold(
          appBar: AppBar(title: const Text('阈界人格')),
          body: Center(
            child: Padding(
              padding: const EdgeInsets.all(24),
              child: Column(
                mainAxisAlignment: MainAxisAlignment.center,
                children: [
                  Text(
                    '无法打开原生 WebView 页面，请重试或改用浏览器打开。',
                    textAlign: TextAlign.center,
                    style: Theme.of(context).textTheme.bodyLarge,
                  ),
                  const SizedBox(height: 24),
                  FilledButton.icon(
                    onPressed: () => unawaited(_openAndroidNativeShell()),
                    icon: const Icon(Icons.refresh),
                    label: const Text('重试'),
                  ),
                  const SizedBox(height: 12),
                  FilledButton.tonalIcon(
                    onPressed: _openInExternalBrowser,
                    icon: const Icon(Icons.open_in_browser),
                    label: const Text('在浏览器中打开'),
                  ),
                ],
              ),
            ),
          ),
        );
      }
      return _whiteSystemBars(
        child: const Scaffold(
          backgroundColor: Colors.white,
          body: SafeArea(
            child: Center(
              child: CircularProgressIndicator(color: _kFlutterBlue),
            ),
          ),
        ),
      );
    }

    if (!_useEmbeddedWebView) {
      return Scaffold(
        appBar: AppBar(title: const Text('阈界人格')),
        body: Center(
          child: Padding(
            padding: const EdgeInsets.all(24),
            child: Column(
              mainAxisAlignment: MainAxisAlignment.center,
              children: [
                Text(
                  _fallbackExplanation(),
                  textAlign: TextAlign.center,
                  style: Theme.of(context).textTheme.bodyLarge,
                ),
                const SizedBox(height: 16),
                SelectableText(
                  kMisskeyBaseUrl,
                  style: Theme.of(context).textTheme.bodySmall,
                ),
                const SizedBox(height: 24),
                FilledButton.icon(
                  onPressed: _openInExternalBrowser,
                  icon: const Icon(Icons.open_in_browser),
                  label: const Text('在浏览器中打开'),
                ),
              ],
            ),
          ),
        ),
      );
    }

    if (_controller == null) {
      return _whiteSystemBars(
        child: const Scaffold(
          backgroundColor: Colors.white,
          body: SafeArea(
            child: Center(
              child: CircularProgressIndicator(color: _kFlutterBlue),
            ),
          ),
        ),
      );
    }

    final controller = _controller!;
    final surface = Theme.of(context).colorScheme.surface;
    if (_webViewSurfaceSynced != surface) {
      _webViewSurfaceSynced = surface;
      unawaited(_syncWebViewSurfaceColor(controller, surface));
    }
    final vp = MediaQuery.viewPaddingOf(context);

    /// 状态栏/刘海：只占位，网页不画入；顶缘叠一层用于下拉刷新（仅窄条接拖拽，其余点击穿透 WebView）。
    final statusBarInset = _statusBarTopInset(vp);
    final implicitPullEnabled =
        !kIsWeb &&
        (defaultTargetPlatform == TargetPlatform.android ||
            defaultTargetPlatform == TargetPlatform.iOS ||
            defaultTargetPlatform == TargetPlatform.macOS);

    /// 叠层高度：需容纳下拉提示胶囊的绘制溢出；点击穿透区在其下，不挡网页。
    const implicitPullCatchHeight = 72.0;
    final belowChromeTop = statusBarInset;
    final sideBottom = EdgeInsets.only(
      left: vp.left,
      right: vp.right,
      bottom: vp.bottom,
    );

    return PopScope(
      canPop: false,
      onPopInvokedWithResult: (didPop, result) async {
        if (didPop) return;
        final canBack = await controller.canGoBack();
        if (!context.mounted) return;
        if (canBack) {
          _lastExitBackPressAt = null;
          await controller.goBack();
          return;
        }
        final now = DateTime.now();
        final prev = _lastExitBackPressAt;
        if (prev != null && now.difference(prev) <= _kExitConfirmWindow) {
          SystemNavigator.pop();
          return;
        }
        _lastExitBackPressAt = now;
        if (!context.mounted) return;
        ScaffoldMessenger.of(context)
          ..hideCurrentSnackBar()
          ..showSnackBar(
            const SnackBar(
              content: Text(_kExitBackSnackMessage),
              duration: _kExitConfirmWindow,
            ),
          );
      },
      child: Scaffold(
        /// 须为 true：否则键盘弹出时 body 不收缩，WebView 仍铺满全屏，站内输入框易被遮挡。
        /// Android 已 `windowSoftInputMode=adjustResize`，与此配合后 WebView 高度会随键盘减小。
        resizeToAvoidBottomInset: true,
        backgroundColor: surface,
        body: Stack(
          fit: StackFit.expand,
          children: [
            Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                if (statusBarInset > 0) SizedBox(height: statusBarInset),
                Expanded(
                  child: Padding(
                    padding: sideBottom,

                    /// 全屏 WebView 为 PlatformView，外层 [RepaintBoundary] 易多一层合成/显存，通常得不偿失。
                    child: buildPerformanceTunedWebView(controller),
                  ),
                ),
              ],
            ),
            if (implicitPullEnabled)
              Positioned(
                left: 0,
                right: 0,
                top: statusBarInset,
                height: implicitPullCatchHeight,
                child: _ImplicitTopPullRefresh(
                  onRefresh: () => controller.reload(),
                ),
              ),
            ListenableBuilder(
              listenable: _embeddedLoadUiListenable,
              builder: (context, _) {
                final ready = _pageReadyNotifier.value;
                final p = _loadProgressNotifier.value;
                if (ready && p >= 100) return const SizedBox.shrink();
                return Positioned(
                  left: 0,
                  right: 0,
                  top: belowChromeTop,
                  child: RepaintBoundary(
                    child: LinearProgressIndicator(
                      value: p / 100.0,
                      minHeight: 2,
                      color: _kFlutterBlue,
                      backgroundColor: Color(0x261A73E8),
                    ),
                  ),
                );
              },
            ),
          ],
        ),
      ),
    );
  }
}

/// 状态栏下方的**透明**热区：无常驻 UI；下拉时在上缘浮现轻量动画与中文提示，松手过阈值则刷新。
///
/// 使用 [Listener] + [HitTestBehavior.translucent]，使指针事件**同时**命中本层与下层 [WebViewWidget]：
/// 点击、链接一般由网页处理；本层根据 [PointerMoveEvent.delta] 累计向下位移实现下拉刷新。
/// （少数机型上 PlatformView 与 translucent 组合行为可能不一致，若点击仍被挡需再改用注入 JS 等方案。）
class _ImplicitTopPullRefresh extends StatefulWidget {
  const _ImplicitTopPullRefresh({required this.onRefresh});

  final Future<void> Function() onRefresh;

  @override
  State<_ImplicitTopPullRefresh> createState() =>
      _ImplicitTopPullRefreshState();
}

class _ImplicitTopPullRefreshState extends State<_ImplicitTopPullRefresh> {
  static const double _triggerDistance = 56;

  /// 逻辑上的下拉距离（每帧更新）；[_pullNotifier] 限频刷新 UI，减轻高刷屏幕上的 ListenableBuilder 压力。
  double _pullLogical = 0;
  DateTime? _lastPullUiTick;

  /// 下拉位移：用 [ValueNotifier] 避免每次 pointer move 都 [setState] 重建整棵子树（易掉帧）。
  final ValueNotifier<double> _pullNotifier = ValueNotifier(0);
  final ValueNotifier<bool> _loadingNotifier = ValueNotifier(false);
  int? _activePointer;

  @override
  void dispose() {
    _pullNotifier.dispose();
    _loadingNotifier.dispose();
    super.dispose();
  }

  void _syncPullNotifierThrottled() {
    final now = DateTime.now();
    if (_lastPullUiTick != null &&
        now.difference(_lastPullUiTick!) < const Duration(milliseconds: 24)) {
      return;
    }
    _lastPullUiTick = now;
    final v = _pullLogical;
    if (_pullNotifier.value != v) {
      _pullNotifier.value = v;
    }
  }

  Future<void> _finishPointerGesture(int pointer) async {
    if (_activePointer != pointer) return;
    _activePointer = null;
    _pullNotifier.value = _pullLogical;
    final pull = _pullLogical;
    if (!mounted || _loadingNotifier.value) return;
    if (pull >= _triggerDistance) {
      _pullLogical = 0;
      _pullNotifier.value = 0;
      _loadingNotifier.value = true;
      try {
        await widget.onRefresh();
      } finally {
        if (mounted) _loadingNotifier.value = false;
      }
    } else {
      _pullLogical = 0;
      _pullNotifier.value = 0;
    }
  }

  @override
  Widget build(BuildContext context) {
    return Stack(
      clipBehavior: Clip.none,
      alignment: Alignment.topCenter,
      children: [
        Positioned.fill(
          child: Listener(
            behavior: HitTestBehavior.translucent,
            onPointerDown: (e) {
              if (_loadingNotifier.value) return;
              _activePointer = e.pointer;
              _pullLogical = 0;
              _lastPullUiTick = null;
              _pullNotifier.value = 0;
            },
            onPointerMove: (e) {
              if (_loadingNotifier.value || _activePointer != e.pointer) return;
              final dy = e.delta.dy;
              final dx = e.delta.dx;
              var cur = _pullLogical;
              if (dy <= 0) {
                if (cur > 0) {
                  _pullLogical = math.max(0.0, cur + dy);
                  _syncPullNotifierThrottled();
                }
                return;
              }
              if (dx.abs() > dy * 1.15) {
                return;
              }
              _pullLogical = (cur + dy).clamp(0.0, _triggerDistance * 1.35);
              _syncPullNotifierThrottled();
            },
            onPointerUp: (e) => unawaited(_finishPointerGesture(e.pointer)),
            onPointerCancel: (e) => unawaited(_finishPointerGesture(e.pointer)),
            child: const SizedBox.expand(),
          ),
        ),
        ListenableBuilder(
          listenable: Listenable.merge([_pullNotifier, _loadingNotifier]),
          builder: (context, _) {
            final pull = _pullNotifier.value;
            final loading = _loadingNotifier.value;
            if (!loading && pull <= 4) {
              return const SizedBox.shrink();
            }
            final scheme = Theme.of(context).colorScheme;
            final t = Theme.of(context).textTheme;
            final opacity = (pull / _triggerDistance).clamp(0.0, 1.0);
            final eased = Curves.easeOut.transform(opacity);
            return Positioned(
              top: math.min(pull * 0.45, 40),
              left: 0,
              right: 0,
              child: RepaintBoundary(
                child: IgnorePointer(
                  child: Center(
                    child: Container(
                      decoration: BoxDecoration(
                        color: scheme.surface.withValues(alpha: 0.94),
                        borderRadius: BorderRadius.circular(22),
                        border: Border.all(
                          color: scheme.outline.withValues(alpha: 0.28),
                        ),
                        boxShadow: [
                          BoxShadow(
                            color: Colors.black.withValues(alpha: 0.14),
                            blurRadius: 18,
                            offset: const Offset(0, 6),
                          ),
                        ],
                      ),
                      child: Padding(
                        padding: const EdgeInsets.symmetric(
                          horizontal: 16,
                          vertical: 12,
                        ),
                        child: Column(
                          mainAxisSize: MainAxisSize.min,
                          children: [
                            if (loading)
                              SizedBox(
                                width: 26,
                                height: 26,
                                child: CircularProgressIndicator(
                                  strokeWidth: 2.5,
                                  color: scheme.primary,
                                  backgroundColor: scheme.primary.withValues(
                                    alpha: 0.12,
                                  ),
                                ),
                              )
                            else
                              Transform.rotate(
                                angle: pull * 0.045,
                                child: Icon(
                                  Icons.refresh_rounded,
                                  size: 26,
                                  color: scheme.primary.withValues(
                                    alpha: 0.55 + eased * 0.45,
                                  ),
                                ),
                              ),
                            if (loading) ...[
                              const SizedBox(height: 8),
                              Text(
                                '正在刷新…',
                                style: t.labelLarge?.copyWith(
                                  color: scheme.onSurface,
                                  fontWeight: FontWeight.w600,
                                  height: 1.2,
                                ),
                              ),
                            ] else if (pull > 8) ...[
                              const SizedBox(height: 6),
                              Opacity(
                                opacity: eased,
                                child: Text(
                                  pull >= _triggerDistance * 0.92
                                      ? '松开即可刷新'
                                      : '继续下拉刷新',
                                  style: t.labelLarge?.copyWith(
                                    color: scheme.onSurface,
                                    fontWeight: FontWeight.w600,
                                    height: 1.2,
                                  ),
                                ),
                              ),
                            ],
                          ],
                        ),
                      ),
                    ),
                  ),
                ),
              ),
            );
          },
        ),
      ],
    );
  }
}

/// 刘海/状态栏高度：网页不画进该区域；此区域**不**绑定下拉手势（交给系统）。
double _statusBarTopInset(EdgeInsets viewPadding) {
  final t = viewPadding.top;
  if (t > 0) return t;
  if (!kIsWeb &&
      (defaultTargetPlatform == TargetPlatform.android ||
          defaultTargetPlatform == TargetPlatform.iOS)) {
    return 24;
  }
  return 0;
}
