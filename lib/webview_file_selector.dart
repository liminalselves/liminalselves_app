import 'package:file_picker/file_picker.dart';
import 'package:flutter/foundation.dart';
import 'package:image_picker/image_picker.dart';
import 'package:webview_flutter/webview_flutter.dart';
import 'package:webview_flutter_android/webview_flutter_android.dart';

/// 为 Android WebView 注册 `<input type="file">` / 文件上传回调，否则会无法打开系统文件选择器。
Future<void> configureAndroidWebViewFileSelector(WebViewController controller) async {
  if (kIsWeb || defaultTargetPlatform != TargetPlatform.android) return;
  final platform = controller.platform;
  if (platform is! AndroidWebViewController) return;
  await platform.setOnShowFileSelector(_pickFilesForAndroidWebView);
}

/// 返回 `file://` 形式的 URI 字符串列表，供 WebView 回传给页面。
Future<List<String>> _pickFilesForAndroidWebView(FileSelectorParams params) async {
  try {
    if (params.isCaptureEnabled) {
      final x = await ImagePicker().pickImage(source: ImageSource.camera);
      if (x == null) return <String>[];
      return <String>[Uri.file(x.path).toString()];
    }

    if (params.mode == FileSelectorMode.save) {
      final path = await FilePicker.saveFile(
        fileName: params.filenameHint ?? 'download',
        bytes: Uint8List(0),
      );
      if (path == null || path.isEmpty) return <String>[];
      return <String>[Uri.file(path).toString()];
    }

    final allowMulti = params.mode == FileSelectorMode.openMultiple;
    final result = await FilePicker.pickFiles(
      allowMultiple: allowMulti,
      type: FileType.any,
      withData: false,
    );
    if (result == null || result.files.isEmpty) return <String>[];

    final out = <String>[];
    for (final f in result.files) {
      final p = f.path;
      if (p != null && p.isNotEmpty) {
        out.add(Uri.file(p).toString());
      }
    }
    return out;
  } catch (e, st) {
    debugPrint('[WebView file selector] $e\n$st');
    return <String>[];
  }
}
