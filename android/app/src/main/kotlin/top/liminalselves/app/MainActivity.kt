package top.liminalselves.app

import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val pushChannelName = "top.liminalselves.app/push"
    private val nativeWebViewChannelName = "top.liminalselves.app/native_webview"
    private val keepAliveChannelName = "top.liminalselves.app/keepalive"

    override fun onResume() {
        super.onResume()
        applyWindowRefreshPresentationHints()
    }

    /**
     * 与 Dart 侧 [flutter_displaymode] 互补：
     * - API 30+：[LayoutParams.preferredRefreshRate] 向系统声明窗口倾向的最高刷新率；
     * - API 35+：尝试关闭「省电型自适应刷新」倾向（需系统支持；反射避免低 compileSdk 无法编译）。
     */
    private fun applyWindowRefreshPresentationHints() {
        if (Build.VERSION.SDK_INT >= 35) {
            try {
                val m = android.view.Window::class.java.getMethod(
                    "setFrameRatePowerSavingsBalanced",
                    Boolean::class.javaPrimitiveType,
                )
                m.invoke(window, false)
            } catch (e: Throwable) {
                Log.w(TAG, "setFrameRatePowerSavingsBalanced: ${e.message}")
            }
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        try {
            val display = display ?: return
            var maxHz = display.refreshRate
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                for (mode in display.supportedModes) {
                    if (mode.refreshRate > maxHz) maxHz = mode.refreshRate
                }
            }
            val attrs = window.attributes
            attrs.preferredRefreshRate = maxHz
            window.attributes = attrs
        } catch (e: Throwable) {
            Log.w(TAG, "preferredRefreshRate: ${e.message}")
        }
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, pushChannelName)
            .setMethodCallHandler { call, result ->
                when (call.method) {
                    "getDeviceId" -> result.success(AliyunPushHolder.deviceId)
                    "areNotificationsEnabled" -> {
                        result.success(NotificationManagerCompat.from(this).areNotificationsEnabled())
                    }
                    "startNativePush" -> {
                        mainHandler.post {
                            AliyunPushStarter.start(application) { ok, deviceId, err ->
                                mainHandler.post {
                                    if (ok && deviceId != null) {
                                        result.success(deviceId)
                                    } else {
                                        result.error("PUSH", err ?: "startNativePush failed", null)
                                    }
                                }
                            }
                        }
                    }
                    "stopNativePush" -> {
                        AliyunPushStarter.stop()
                        result.success(null)
                    }
                    else -> result.notImplemented()
                }
            }

        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, nativeWebViewChannelName)
            .setMethodCallHandler { call, result ->
                when (call.method) {
                    "open" -> {
                        val args = call.arguments as? Map<*, *>
                        val url = args?.get("url") as? String
                        val marker = args?.get("userAgentMarker") as? String
                        if (url.isNullOrBlank()) {
                            result.error("ARG", "url is required", null)
                            return@setMethodCallHandler
                        }
                        val intent = Intent(this, NativeWebViewActivity::class.java).apply {
                            putExtra(NativeWebViewActivity.EXTRA_URL, url)
                            putExtra(NativeWebViewActivity.EXTRA_UA_MARKER, marker ?: "")
                        }
                        startActivity(intent)
                        overridePendingTransition(0, 0)
                        finish()
                        overridePendingTransition(0, 0)
                        result.success(true)
                    }
                    else -> result.notImplemented()
                }
            }

        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, keepAliveChannelName)
            .setMethodCallHandler { call, result ->
                when (call.method) {
                    "start" -> {
                        val intent = Intent(this, KeepAliveService::class.java).apply {
                            action = KeepAliveService.ACTION_START
                        }
                        startForegroundService(intent)
                        result.success(true)
                    }
                    "stop" -> {
                        val intent = Intent(this, KeepAliveService::class.java).apply {
                            action = KeepAliveService.ACTION_STOP
                        }
                        startService(intent)
                        result.success(true)
                    }
                    else -> result.notImplemented()
                }
            }
    }

    companion object {
        private const val TAG = "MainActivity"
    }
}
