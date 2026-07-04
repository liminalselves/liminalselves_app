import 'dart:convert';

import 'package:flutter/foundation.dart'
    show TargetPlatform, defaultTargetPlatform, kIsWeb;
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:http/http.dart' as http;
import 'package:package_info_plus/package_info_plus.dart';
import 'package:url_launcher/url_launcher.dart';
import 'package:version/version.dart';

const MethodChannel _externalUrlChannel = MethodChannel(
  'top.liminalselves.app/native_webview',
);

class OptionalShellUpdateHint {
  const OptionalShellUpdateHint({
    required this.latestVersion,
    required this.localVersion,
    required this.updateEntries,
    this.downloadUrl,
  });

  final String latestVersion;
  final String localVersion;
  final List<AppUpdateEntry> updateEntries;
  final String? downloadUrl;
}

class AppUpdateEntry {
  const AppUpdateEntry({required this.version, required this.content});

  final String version;
  final String content;
}

class ForceShellUpdateInfo {
  const ForceShellUpdateInfo({
    required this.minRequiredVersion,
    required this.currentVersion,
    this.downloadUrl,
  });

  final String minRequiredVersion;
  final String currentVersion;
  final String? downloadUrl;
}

class ShellStartupOutcome {
  const ShellStartupOutcome({
    required this.forceBlocked,
    this.force,
    this.optional,
    this.accentColor,
    this.metaFetchFailed = false,
  });

  final bool forceBlocked;
  final ForceShellUpdateInfo? force;
  final OptionalShellUpdateHint? optional;
  final Color? accentColor;
  final bool metaFetchFailed;
}

String _semverCore(String packageVersion) {
  final i = packageVersion.indexOf('+');
  return (i >= 0 ? packageVersion.substring(0, i) : packageVersion).trim();
}

Color? _parseThemeColorHex(String? raw) {
  if (raw == null || raw.isEmpty) return null;
  final s = raw.trim();
  if (s.length != 7 || !s.startsWith('#')) return null;
  final hex = int.tryParse(s.substring(1), radix: 16);
  if (hex == null) return null;
  return Color(0xFF000000 | hex);
}

Version? _tryParseVersion(String raw) {
  final t = raw.trim();
  if (t.isEmpty) return null;
  try {
    return Version.parse(t);
  } catch (_) {
    return null;
  }
}

List<AppUpdateEntry> _parseUpdateEntries(dynamic raw) {
  if (raw is! List) return const [];
  final entries = <AppUpdateEntry>[];
  for (final item in raw) {
    if (item is! Map) continue;
    final version = (item['version'] as String?)?.trim() ?? '';
    final content = (item['content'] as String?) ?? '';
    if (version.isEmpty) continue;
    entries.add(AppUpdateEntry(version: version, content: content));
  }
  entries.sort((a, b) {
    final av = _tryParseVersion(a.version);
    final bv = _tryParseVersion(b.version);
    if (av != null && bv != null) {
      if (bv > av) return 1;
      if (bv < av) return -1;
      return 0;
    }
    return b.version.compareTo(a.version);
  });
  return entries;
}

List<AppUpdateEntry> _pickUpdateEntriesInRange({
  required List<AppUpdateEntry> all,
  required Version? localVersion,
  required Version? latestVersion,
}) {
  final out = <AppUpdateEntry>[];
  for (final e in all) {
    final ev = _tryParseVersion(e.version);
    if (ev == null) continue;
    if (localVersion != null && ev <= localVersion) continue;
    if (latestVersion != null && ev > latestVersion) continue;
    out.add(e);
  }
  return out;
}

Future<bool> _openExternalUrl(String rawUrl) async {
  final url = rawUrl.trim();
  final uri = Uri.tryParse(url);
  if (uri == null || uri.scheme.isEmpty || uri.host.isEmpty) return false;

  if (!kIsWeb && defaultTargetPlatform == TargetPlatform.android) {
    try {
      final opened = await _externalUrlChannel.invokeMethod<bool>(
        'openExternalUrl',
        {'url': uri.toString()},
      );
      if (opened == true) return true;
    } on PlatformException {
      // Fall back to url_launcher below.
    }
  }

  try {
    return await launchUrl(uri, mode: LaunchMode.externalApplication);
  } catch (_) {
    return false;
  }
}

Future<bool> _openUpdateUrl(BuildContext context, String rawUrl) async {
  final opened = await _openExternalUrl(rawUrl);
  if (!opened && context.mounted) {
    ScaffoldMessenger.maybeOf(
      context,
    )?.showSnackBar(const SnackBar(content: Text('无法在浏览器中打开更新链接')));
  }
  return opened;
}

/// POST `/api/meta`，与 Misskey 前端一致；失败时返回 null（不阻断壳，避免离线不可用）。
Uri _metaApiUri(String misskeyBaseUrl) {
  final b = Uri.parse(misskeyBaseUrl);
  final segs = b.pathSegments.where((s) => s.isNotEmpty).toList();
  return b.replace(pathSegments: [...segs, 'api', 'meta']);
}

Future<Map<String, dynamic>?> fetchMisskeyMetaLite(
  String misskeyBaseUrl,
) async {
  final uri = _metaApiUri(misskeyBaseUrl);
  try {
    final res = await http
        .post(
          uri,
          headers: const {
            'Content-Type': 'application/json',
            'Accept': 'application/json',
          },
          body: jsonEncode(const {'detail': false}),
        )
        .timeout(const Duration(seconds: 20));
    if (res.statusCode < 200 || res.statusCode >= 300) return null;
    final decoded = jsonDecode(res.body);
    if (decoded is! Map<String, dynamic>) return null;
    return decoded;
  } catch (_) {
    return null;
  }
}

Future<ShellStartupOutcome> evaluateShellVersionPolicy({
  required String misskeyBaseUrl,
  required PackageInfo packageInfo,
}) async {
  final meta = await fetchMisskeyMetaLite(misskeyBaseUrl);
  if (meta == null) {
    return const ShellStartupOutcome(
      forceBlocked: false,
      metaFetchFailed: true,
    );
  }

  final accent = _parseThemeColorHex(meta['themeColor'] as String?);

  final n = meta['nativeClientAppInfo'];
  if (n is! Map<String, dynamic>) {
    return ShellStartupOutcome(forceBlocked: false, accentColor: accent);
  }

  final minRaw = (n['minRequiredAppVersion'] as String?)?.trim() ?? '';
  final localCore = _semverCore(packageInfo.version);
  final localV = _tryParseVersion(localCore);
  final minV = _tryParseVersion(minRaw);

  String? pickUrl(dynamic v) {
    if (v is! String) return null;
    final t = v.trim();
    return t.isEmpty ? null : t;
  }

  if (minRaw.isNotEmpty && minV != null && localV != null && localV < minV) {
    final dl = pickUrl(
      defaultTargetPlatform == TargetPlatform.iOS
          ? n['iosDownloadUrl']
          : n['androidDownloadUrl'],
    );
    return ShellStartupOutcome(
      forceBlocked: true,
      force: ForceShellUpdateInfo(
        minRequiredVersion: minRaw,
        currentVersion: localCore,
        downloadUrl: dl,
      ),
      accentColor: accent,
    );
  }

  OptionalShellUpdateHint? optional;
  final latestRaw =
      (defaultTargetPlatform == TargetPlatform.iOS
              ? n['latestIosVersion']
              : n['latestAndroidVersion'])
          as String?;
  final latestTrim = latestRaw?.trim() ?? '';
  if (latestTrim.isNotEmpty) {
    final latestV = _tryParseVersion(latestTrim);
    if (latestV != null && localV != null && latestV > localV) {
      final dl = pickUrl(
        defaultTargetPlatform == TargetPlatform.iOS
            ? n['iosDownloadUrl']
            : n['androidDownloadUrl'],
      );
      final allUpdates = _parseUpdateEntries(n['changelog']);
      final updates = _pickUpdateEntriesInRange(
        all: allUpdates,
        localVersion: localV,
        latestVersion: latestV,
      );
      optional = OptionalShellUpdateHint(
        latestVersion: latestTrim,
        localVersion: localCore,
        updateEntries: updates,
        downloadUrl: dl,
      );
    }
  }

  return ShellStartupOutcome(
    forceBlocked: false,
    optional: optional,
    accentColor: accent,
  );
}

/// 不可关闭：无背景点击、无返回关闭；仅「获取更新」与「退出应用」。
class ForceShellUpdatePage extends StatelessWidget {
  const ForceShellUpdatePage({super.key, required this.info});

  final ForceShellUpdateInfo info;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return PopScope(
      canPop: false,
      child: Scaffold(
        body: SafeArea(
          child: Center(
            child: SingleChildScrollView(
              padding: const EdgeInsets.symmetric(horizontal: 28, vertical: 24),
              child: ConstrainedBox(
                constraints: const BoxConstraints(maxWidth: 400),
                child: Card(
                  elevation: 0,
                  shape: RoundedRectangleBorder(
                    borderRadius: BorderRadius.circular(16),
                    side: BorderSide(
                      color: scheme.outlineVariant.withValues(alpha: 0.5),
                    ),
                  ),
                  child: Padding(
                    padding: const EdgeInsets.fromLTRB(24, 28, 24, 24),
                    child: Column(
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        Container(
                          width: 56,
                          height: 56,
                          decoration: BoxDecoration(
                            color: scheme.errorContainer,
                            shape: BoxShape.circle,
                          ),
                          child: Icon(
                            Icons.system_update_alt_rounded,
                            size: 30,
                            color: scheme.onErrorContainer,
                          ),
                        ),
                        const SizedBox(height: 20),
                        Text(
                          '需要更新应用',
                          textAlign: TextAlign.center,
                          style: Theme.of(context).textTheme.titleLarge
                              ?.copyWith(fontWeight: FontWeight.w700),
                        ),
                        const SizedBox(height: 12),
                        Text(
                          '当前版本过低，无法继续使用。请更新到服务器要求的最低版本后再打开。',
                          textAlign: TextAlign.center,
                          style: Theme.of(context).textTheme.bodyMedium
                              ?.copyWith(
                                color: scheme.onSurfaceVariant,
                                height: 1.45,
                              ),
                        ),
                        const SizedBox(height: 20),
                        _monoKeyValueRow(context, '当前版本', info.currentVersion),
                        const SizedBox(height: 8),
                        _monoKeyValueRow(
                          context,
                          '最低要求',
                          info.minRequiredVersion,
                        ),
                        const SizedBox(height: 24),
                        SizedBox(
                          width: double.infinity,
                          child: FilledButton(
                            onPressed:
                                (info.downloadUrl == null ||
                                    info.downloadUrl!.isEmpty)
                                ? null
                                : () async {
                                    await _openUpdateUrl(
                                      context,
                                      info.downloadUrl!,
                                    );
                                  },
                            child: const Text('获取更新'),
                          ),
                        ),
                        const SizedBox(height: 10),
                        SizedBox(
                          width: double.infinity,
                          child: TextButton(
                            onPressed: () => SystemNavigator.pop(),
                            child: const Text('退出应用'),
                          ),
                        ),
                      ],
                    ),
                  ),
                ),
              ),
            ),
          ),
        ),
      ),
    );
  }
}

Widget _monoKeyValueRow(BuildContext context, String label, String value) {
  final scheme = Theme.of(context).colorScheme;
  return Row(
    crossAxisAlignment: CrossAxisAlignment.start,
    children: [
      SizedBox(
        width: 72,
        child: Text(
          label,
          style: Theme.of(
            context,
          ).textTheme.bodySmall?.copyWith(color: scheme.onSurfaceVariant),
        ),
      ),
      Expanded(
        child: SelectableText(
          value,
          style: Theme.of(context).textTheme.bodyMedium?.copyWith(
            fontFamily: 'monospace',
            fontFamilyFallback: const ['monospace'],
          ),
        ),
      ),
    ],
  );
}

Future<bool> showOptionalShellUpdateDialog(
  BuildContext context,
  OptionalShellUpdateHint hint,
) async {
  final scheme = Theme.of(context).colorScheme;
  if (!context.mounted) return false;
  final openedUpdate = await showDialog<bool>(
    context: context,
    barrierDismissible: true,
    builder: (ctx) => AlertDialog(
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(16)),
      icon: Container(
        width: 48,
        height: 48,
        decoration: BoxDecoration(
          color: scheme.primaryContainer,
          shape: BoxShape.circle,
        ),
        child: Icon(
          Icons.new_releases_outlined,
          color: scheme.onPrimaryContainer,
        ),
      ),
      title: const Text('发现新版本'),
      content: SingleChildScrollView(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              '服务器已发布较新的客户端版本，建议更新以获得最佳体验。',
              style: TextStyle(color: scheme.onSurfaceVariant, height: 1.45),
            ),
            const SizedBox(height: 16),
            Text(
              '最新：${hint.latestVersion}',
              style: const TextStyle(fontWeight: FontWeight.w600),
            ),
            Text(
              '当前：${hint.localVersion}',
              style: TextStyle(color: scheme.onSurfaceVariant),
            ),
            if (hint.updateEntries.isNotEmpty) ...[
              const SizedBox(height: 12),
              const Text(
                '更新内容：',
                style: TextStyle(fontWeight: FontWeight.w700),
              ),
              const SizedBox(height: 8),
              ...hint.updateEntries.map(
                (item) => Padding(
                  padding: const EdgeInsets.only(bottom: 8),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        item.version,
                        style: const TextStyle(fontWeight: FontWeight.w600),
                      ),
                      if (item.content.trim().isNotEmpty)
                        SelectableText(item.content),
                    ],
                  ),
                ),
              ),
            ] else ...[
              const SizedBox(height: 8),
              Text(
                '本次未配置更新内容。',
                style: TextStyle(color: scheme.onSurfaceVariant),
              ),
            ],
          ],
        ),
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.of(ctx).pop(),
          child: const Text('稍后'),
        ),
        FilledButton(
          onPressed: (hint.downloadUrl == null || hint.downloadUrl!.isEmpty)
              ? null
              : () async {
                  final opened = await _openUpdateUrl(
                    context,
                    hint.downloadUrl!,
                  );
                  if (opened && ctx.mounted) {
                    Navigator.of(ctx).pop(true);
                  }
                },
          child: const Text('前往更新'),
        ),
      ],
    ),
  );
  return openedUpdate == true;
}
