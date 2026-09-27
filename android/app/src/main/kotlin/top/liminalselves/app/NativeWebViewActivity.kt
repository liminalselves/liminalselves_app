package top.liminalselves.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.DownloadManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.res.ColorStateList
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.media.MediaScannerConnection
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.MimeTypeMap
import android.webkit.PermissionRequest
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebBackForwardList
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.pm.PackageInfoCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.NotificationCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import android.view.ContextThemeWrapper
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

class NativeWebViewActivity : ComponentActivity() {
    private data class ApiCallResult(
        val ok: Boolean,
        val statusCode: Int,
        val body: String,
    )
    private lateinit var rootContainer: TopEdgeSwipeRefreshLayout
    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar
    private lateinit var errorOverlay: View

    /** 错误页当前构建所用的深浅（与 [pageThemeDark] 不一致时在下次展示前重建）。 */
    private var errorOverlayDark = false
    private lateinit var contentFrame: FrameLayout
    private lateinit var errorUrlText: TextView
    private lateinit var errorMetaText: TextView
    private val defaultUrl = BuildConfig.MISSKEY_URL.ifBlank {
        "https://misskey.liminalselves.top/"
    }
    private val ioExecutor = Executors.newFixedThreadPool(2)
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var cameraOutputUri: Uri? = null
    /** 权限向导正在显示，避免重复拉起。 */
    private var pushSetupRunning = false
    /** 启动权限完整性核对正在同步关闭推送，避免重复请求。 */
    private var startupPushDisableRunning = false
    private var currentUrl: String = defaultUrl
    private var showingErrorPage = false
    private var loadFailedForCurrentNavigation = false

    // ─── WebSocket 后台保活 ────────────────────────────────────────────────
    /** 保活是否进行中（前台服务 + CPU/网络锁），避免重复启停。 */
    private var keepAliveActive = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    /** 原生 WebSocket 管理器：后台时绕过 WebView JS 节流，独立接收 Misskey 消息。 */
    private val nativeWsManager = NativeWsManager { title, body, openPath ->
        showSystemNotification(title, body, openPath)
    }

    /** 系统返回回调：全屏退出 / 历史回退 / 根页面双击退出。 */
    private lateinit var webBackCallback: OnBackPressedCallback

    /** 根页面首次按返回的时间：短时内第二次返回才退出。 */
    private var lastExitBackPressElapsed = 0L

    /** 后台期间保存的 WebView 导航历史：renderer 被系统回收重建时恢复返回栈。 */
    private var savedWebViewState: Bundle? = null
    private val topPullHotZoneDp = 84

    // ─── HTML5 全屏（视频等 Fullscreen API → WebChromeClient.onShowCustomView）───
    /** 当前处于全屏的 WebView 自定义视图（如私信视频点全屏时由引擎提供）。 */
    private var fullscreenCustomView: View? = null
    private var fullscreenCustomViewCallback: WebChromeClient.CustomViewCallback? = null
    // 壳层采用 Flutter 风格蓝色系；仅作用于加载进度与错误页等壳层元素，不改变站内导航逻辑。
    private val lightChromeColor = Color.parseColor("#F7F9F5")
    private val darkChromeColor = Color.parseColor("#15171C")

    /** 兜底壳色（页面未上报/加载失败）：跟随当前已知的站内深浅状态。 */
    private val defaultChromeColor: Int
        get() = if (pageThemeDark) darkChromeColor else lightChromeColor
    private val accentColor = Color.parseColor("#1A73E8")
    private val accentColorDark = Color.parseColor("#1557B0")
    // 顶部加载条使用蓝色系。
    private val progressBarColor = Color.parseColor("#1A73E8")
    private val progressBarTrackColor = Color.parseColor("#D6E4FF")
    private var lastChromeColor = lightChromeColor

    /**
     * Misskey 当前生效主题是否为深色：由网页上报的 meta theme-color（站内 @bg）
     * 亮度推导（embedded-shell 经 AppChrome 桥实时同步，含站内手动切换主题），
     * 页面未上报前以系统深浅兜底。壳层对话框/错误页/加载条等据此跟随站内深浅，
     * 而非跟随系统——站内「跟随设备」或手动选择都收敛到同一表现。
     */
    private var pageThemeDark = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastSystemBarAppliedColor: Int? = null
    private var lastSystemBarAppliedLightBars: Boolean? = null
    private var pendingChromeColor: Int? = null
    private var chromeColorApplyScheduled = false
    private var localNotificationSeq = 7000
    private val applyPendingChromeColorRunnable = Runnable {
        chromeColorApplyScheduled = false
        val c = pendingChromeColor ?: return@Runnable
        pendingChromeColor = null
        applyChromeColorNow(c)
    }

    private val fileChooserLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val cb = filePathCallback
            filePathCallback = null

            val uris: Array<Uri> = when {
                result.resultCode != RESULT_OK -> emptyArray()
                result.data?.clipData != null -> {
                    val clip = result.data!!.clipData!!
                    Array(clip.itemCount) { i -> clip.getItemAt(i).uri }
                }
                result.data?.data != null -> arrayOf(result.data!!.data!!)
                cameraOutputUri != null -> arrayOf(cameraOutputUri!!)
                else -> emptyArray()
            }
            // 每次选择流程结束即清空，避免残留的拍照输出 URI 串扰下一次非拍照选择
            cameraOutputUri = null
            if (cb == null) return@registerForActivityResult
            cb.onReceiveValue(uris)
        }

    /** 权限向导完成后才真正启用推送；取消/未完成则保持关闭。 */
    private val pushSetupLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            pushSetupRunning = false
            if (result.resultCode == RESULT_OK) {
                startNativePushAndDispatch()
            } else {
                dispatchNativePushState(registered = false, errorCode = "permission_denied")
            }
        }

    // ─── WebView 权限（WebRTC getUserMedia 等）──────────────────────────────
    /** 等待运行时权限结果的 WebView 权限请求（同一时刻最多一个，新请求会使旧的被拒绝）。 */
    private var pendingWebPermissionRequest: PermissionRequest? = null

    private val webPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            val request = pendingWebPermissionRequest
            pendingWebPermissionRequest = null
            if (request == null) return@registerForActivityResult
            // 只授予运行时权限获批对应的 Web 资源，其余（含未知资源）全部拒绝
            val grantedResources = request.resources.filter { resource ->
                val mapped = when (resource) {
                    PermissionRequest.RESOURCE_VIDEO_CAPTURE -> Manifest.permission.CAMERA
                    PermissionRequest.RESOURCE_AUDIO_CAPTURE -> Manifest.permission.RECORD_AUDIO
                    else -> null
                }
                mapped != null && grants[mapped] == true
            }
            if (grantedResources.isEmpty()) {
                request.deny()
            } else {
                request.grant(grantedResources.toTypedArray())
            }
        }

    /** 页面/Activity 销毁时清理未决的 WebView 权限请求。 */
    private fun denyPendingWebPermission() {
        pendingWebPermissionRequest?.deny()
        pendingWebPermissionRequest = null
    }

    // ─── 文件下载 ───────────────────────────────────────────────────────────
    // 存储权限获批后需要重放的下载动作（授权弹窗期间挂起，授予后继续执行）。
    private var pendingDownloadAction: (() -> Unit)? = null

    private val storagePermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val action = pendingDownloadAction
            pendingDownloadAction = null
            if (action == null) return@registerForActivityResult
            if (granted) {
                action()
            } else {
                showToastIfForeground("存储权限被拒绝，无法保存文件")
            }
        }

    /**
     * 写公共下载目录的统一权限门控：API 29+ 经 MediaStore 写入自身贡献的文件无需权限；
     * API ≤ 28 直写公共目录需要 WRITE_EXTERNAL_STORAGE，未授予时先申请，获批后重放
     * [action]（blob 场景届时仍可从 shim 缓存读取 Blob 引用）。
     */
    private fun ensurePublicDownloadPermissionThen(action: () -> Unit) {
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P ||
            checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        ) {
            action()
            return
        }
        pendingDownloadAction = action
        storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
    }

    private val downloadCompleteReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
            val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
            if (id < 0) return
            val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            // ACTION_DOWNLOAD_COMPLETE 对成功/失败都会发出，须查状态区分
            val cursor = dm.query(DownloadManager.Query().setFilterById(id))
            cursor?.use { c ->
                if (!c.moveToFirst()) return
                val statusIndex = c.getColumnIndex(DownloadManager.COLUMN_STATUS)
                if (statusIndex >= 0 && c.getInt(statusIndex) != DownloadManager.STATUS_SUCCESSFUL) {
                    val reasonIndex = c.getColumnIndex(DownloadManager.COLUMN_REASON)
                    val reason = if (reasonIndex >= 0) c.getString(reasonIndex).orEmpty() else ""
                    showToastIfForeground(if (reason.isBlank()) "下载失败" else "下载失败: $reason")
                    return
                }
                // 与 blob/data 下载反馈保持一致：完成后提示实际落盘文件名（含冲突自动改名后的名字）
                val uri = dm.getUriForDownloadedFile(id) ?: return
                var savedName: String? = null
                runCatching {
                    contentResolver.query(
                        uri,
                        arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
                        null,
                        null,
                        null,
                    )?.use { nameCursor ->
                        if (nameCursor.moveToFirst()) savedName = nameCursor.getString(0)
                    }
                }
                val savedLabel = savedName?.takeIf { it.isNotBlank() } ?: "文件"
                if (LiminalApplication.isAppInForeground()) {
                    showToastIfForeground("已保存到 Download/: $savedLabel")
                } else {
                    notifyDownloadComplete(savedLabel, uri, dm.getMimeTypeForDownloadedFile(id))
                }
            }
        }
    }

    /**
     * 统一的用户反馈通道：仅前台以 Toast 提示；后台静默
     * （系统通知由各自的机制负责，Toast 不越过当前应用弹出）。
     */
    private fun showToastIfForeground(message: String) {
        if (!LiminalApplication.isAppInForeground()) return
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    /**
     * 后台下载完成的系统通知（前台走 Toast，避免双提示）：静默入栏、点击打开文件。
     * 复用互动通知渠道（LOW，静默）；打开失败时通知仅作提示（无可点击动作）。
     */
    private fun notifyDownloadComplete(fileName: String, uri: Uri?, mimeType: String?) {
        if (LiminalApplication.isAppInForeground()) return
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ensureMisskeyChannels(this)
        }
        val builder = NotificationCompat.Builder(this, CHANNEL_MISSKEY_NOTIFY)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("下载完成")
            .setContentText(fileName)
            .setStyle(NotificationCompat.BigTextStyle().bigText("已保存到 Download/: $fileName"))
            .setAutoCancel(true)
            .setSilent(true)
        if (uri != null) {
            val openIntent = runCatching {
                PendingIntent.getActivity(
                    this,
                    fileName.hashCode(),
                    Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, mimeType ?: "*/*")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    },
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            }.getOrNull()
            openIntent?.let { builder.setContentIntent(it) }
        }
        NotificationManagerCompat.from(this).notify(fileName.hashCode(), builder.build())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 站内深浅初始值：尚无网页上报，先按系统深浅兜底（msk 默认「跟随设备」），
        // 页面就绪后由 meta theme-color（AppChrome 桥实时上报，含用户站内手动切换的主题）纠正。
        pageThemeDark = isSystemNightMode()
        lastChromeColor = defaultChromeColor
        overridePendingTransition(0, 0)
        setupSystemBars()
        applyWindowRefreshPresentationHints()
        applyPersistentSystemBars()
        setupViews()
        setupWebView()
        // ACTION_DOWNLOAD_COMPLETE 由系统下载服务（另一应用）发出，Android 13+ 上
        // NOT_EXPORTED 注册收不到该广播（低版本 ContextCompat 回退为无 flag 注册才能收到），
        // 必须用 EXPORTED；该广播受系统保护，安全性不受影响。
        ContextCompat.registerReceiver(
            this,
            downloadCompleteReceiver,
            IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
            ContextCompat.RECEIVER_EXPORTED,
        )
        webBackCallback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // 1) 视频等 HTML5 全屏：返回键先退出全屏而非退出页面
                if (exitFullscreenCustomViewIfActive()) return
                // 2) WebView 自身历史可退 → 引擎内回退（SPA pushState 历史亦由引擎统一管理）
                if (webView.canGoBack()) {
                    lastExitBackPressElapsed = 0L
                    webView.goBack()
                    return
                }
                // 3) 根页面（无历史）：首次返回仅提示，2 秒内再次返回才退出应用
                val now = SystemClock.elapsedRealtime()
                if (lastExitBackPressElapsed != 0L && now - lastExitBackPressElapsed <= EXIT_CONFIRM_MS) {
                    finish()
                    return
                }
                lastExitBackPressElapsed = now
                Toast.makeText(this@NativeWebViewActivity, EXIT_BACK_HINT, Toast.LENGTH_SHORT).show()
            }
        }
        onBackPressedDispatcher.addCallback(this, webBackCallback)
        // 系统回收/旋转重建 Activity 时 WebView 历史不会自动恢复，
        // 优先从实例状态恢复导航历史，避免返回键/站内返回按钮失效。
        val url = resolveLaunchUrl(intent)
        currentUrl = url
        if (restoreWebViewHistory(savedInstanceState)) {
            webView.reload()
        } else {
            loadUrl(url)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (!::webView.isInitialized) return
        // 仅真正的深链（通知点击携带 extraMap.openUrl）才导航到目标页；
        // 普通回前台重入（无 extraMap）不重新加载页面，保留用户当前浏览状态。
        if (intent.getStringExtra("extraMap").isNullOrBlank()) return
        val url = resolveLaunchUrl(intent)
        if (url.isBlank() || url == defaultUrl) return
        currentUrl = url
        loadUrl(url)
    }

    /**
     * 普通启动：EXTRA_URL。
     * 阿里云推送点击（AndroidOpenType=ACTIVITY）：[extraMap] JSON 内含 openUrl。
     */
    private fun resolveLaunchUrl(incoming: Intent): String {
        incoming.getStringExtra(EXTRA_URL)?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        val raw = incoming.getStringExtra("extraMap") ?: return defaultUrl
        val openUrl = runCatching {
            JSONObject(raw).optString("openUrl", "").trim()
        }.getOrNull().orEmpty()
        if (openUrl.isEmpty() || !isTrustedMisskeyOpenUrl(openUrl)) return defaultUrl
        return openUrl
    }

    private fun isTrustedMisskeyOpenUrl(url: String): Boolean {
        val u = Uri.parse(url)
        val scheme = u.scheme ?: return false
        if (scheme != "http" && scheme != "https") return false
        val host = u.host ?: return false
        val defHost = Uri.parse(defaultUrl).host
        if (host == defHost) return true
        val stored = getPrefs().getString(PREF_LAST_MISSKEY_ORIGIN, null) ?: return false
        val sh = runCatching { Uri.parse(stored).host }.getOrNull() ?: return false
        return host == sh
    }

    /** WebView 权限请求的来源校验：仅允许可信 Misskey origin（与外链守卫同一信任域）。 */
    private fun isTrustedMisskeyPermissionOrigin(origin: Uri?): Boolean {
        if (origin == null) return false
        val scheme = origin.scheme?.lowercase() ?: return false
        if (scheme != "http" && scheme != "https") return false
        val host = origin.host?.lowercase() ?: return false
        val defHost = Uri.parse(defaultUrl).host?.lowercase()
        if (defHost != null && host == defHost) return true
        val stored = getPrefs().getString(PREF_LAST_MISSKEY_ORIGIN, null) ?: return false
        val storedHost = runCatching { Uri.parse(stored).host?.lowercase() }.getOrNull() ?: return false
        return host == storedHost
    }

    private fun persistTrustedMisskeyOrigin(pageUrl: String?) {
        if (pageUrl.isNullOrBlank()) return
        val u = Uri.parse(pageUrl)
        val host = u.host ?: return
        val scheme = u.scheme ?: "https"
        val port = u.port
        val origin = if (port == -1 || port == 80 && scheme == "http" || port == 443 && scheme == "https") {
            "$scheme://$host"
        } else {
            "$scheme://$host:$port"
        }
        getPrefs().edit().putString(PREF_LAST_MISSKEY_ORIGIN, origin).apply()
    }

    private fun setupSystemBars() {
        // 证据导向：透明状态栏在部分机型需同时满足 edge-to-edge + drawsSystemBarBackgrounds。
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        // OEM/系统在切后台回来时可能重置导航栏颜色；用缓存色立即兜底，后续再由网页同步纠正。
        applySystemBarColor(lastChromeColor)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // 避免系统为“对比度增强”强行把导航栏改成白/灰（部分 ROM 会触发）。
            window.isNavigationBarContrastEnforced = false
            window.isStatusBarContrastEnforced = false
        }
    }

    private fun applyWindowRefreshPresentationHints() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        runCatching {
            val currentDisplay = display ?: return
            val maxHz = currentDisplay.supportedModes.maxOfOrNull { it.refreshRate }
                ?: currentDisplay.refreshRate
            val attrs = window.attributes
            if (attrs.preferredRefreshRate != maxHz) {
                attrs.preferredRefreshRate = maxHz
                window.attributes = attrs
            }
        }.onFailure {
            Log.w(TAG, "preferredRefreshRate: ${it.message}")
        }
    }

    private fun applyPersistentSystemBars() {
        val controller = WindowCompat.getInsetsController(window, window.decorView) ?: return
        controller.show(WindowInsetsCompat.Type.systemBars())
    }

    // ─── HTML5 全屏（视频等 Fullscreen API）─────────────────────────────────

    /** 进入 HTML5 全屏：把 WebView 提供的视图加到 DecorView 铺满全屏并隐藏系统栏。 */
    private fun showFullscreenCustomView(view: View, callback: WebChromeClient.CustomViewCallback) {
        if (fullscreenCustomView === view) return
        if (fullscreenCustomView != null) {
            // 已有全屏视图未退出：按约定丢弃本次回调，避免叠层
            runCatching { callback.onCustomViewHidden() }
            return
        }
        fullscreenCustomView = view
        fullscreenCustomViewCallback = callback
        // 视频全屏期间保持屏幕常亮
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // 加到 DecorView（而非 contentFrame：后者在 rootContainer 内会被 insets padding 缩进，
        // 加到 DecorView 才能真正覆盖状态栏/导航栏区域）。
        (window.decorView as ViewGroup).addView(
            view,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        // 沉浸式隐藏系统栏；从屏幕边缘滑动以瞬时浮层临时唤出
        WindowCompat.getInsetsController(window, view).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    /** 退出 HTML5 全屏：移除全屏视图，恢复系统栏与常规壳层。 */
    private fun hideFullscreenCustomView() {
        val view = fullscreenCustomView ?: return
        fullscreenCustomView = null
        fullscreenCustomViewCallback = null
        (view.parent as? ViewGroup)?.removeView(view)
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        applyPersistentSystemBars()
        applySystemBarColor(lastChromeColor)
    }

    /**
     * 返回键等**主动**退出全屏：清理视图后调用回调通知 WebView（同步 DOM 全屏状态）；
     * WebView 主动调 onHideCustomView 的路径不再回调，避免循环。返回是否消费了本次返回。
     */
    private fun exitFullscreenCustomViewIfActive(): Boolean {
        if (fullscreenCustomView == null) return false
        val callback = fullscreenCustomViewCallback
        hideFullscreenCustomView()
        callback?.let { runCatching { it.onCustomViewHidden() } }
        return true
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val marker = intent.getStringExtra(EXTRA_UA_MARKER).orEmpty()
        with(webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            mediaPlaybackRequiresUserGesture = false
            builtInZoomControls = false
            displayZoomControls = false
            setSupportZoom(false)
            setSupportMultipleWindows(true)
            userAgentString = "${userAgentString ?: ""} $marker".trim()
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // waived=false：不可见时渲染器跟随应用进程优先级而非降为“可随时回收”。
            // 此前为 true 时，内存紧张的 ROM（华为等）几乎每次切后台都会杀掉渲染器，
            // 回前台触发 onRenderProcessGone → 整页重建刷新。
            webView.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_BOUND, false)
        }
        webView.overScrollMode = WebView.OVER_SCROLL_IF_CONTENT_SCROLLS
        // 深色模式下底色同步压黑：避免站点应用深色主题前的白屏闪变
        // （Chromium 对「主题偏好深色且未设底色」的默认同样取黑）。
        webView.setBackgroundColor(
            if (isSystemNightMode()) {
                getColor(android.R.color.background_dark)
            } else {
                getColor(android.R.color.background_light)
            },
        )
        // 避免 WebView 自行对 status bar inset 做额外 padding，造成顶端出现留白条。
        ViewCompat.setOnApplyWindowInsetsListener(webView) { v, insets ->
            v.setPadding(0, 0, 0, 0)
            insets
        }
        webView.addJavascriptInterface(AppNativePushBridge(), "AppNativePush")
        webView.addJavascriptInterface(AppChromeBridge(), "AppChrome")
        webView.addJavascriptInterface(AppNavBridge(), "AppNav")
        webView.addJavascriptInterface(AppDownloadBridge(), "AppDownload")
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?,
            ): Boolean {
                if (request == null) return false
                if (!request.isForMainFrame) return false
                val uri = request.url ?: return false
                return handleNavigation(uri)
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                currentUrl = url ?: currentUrl
                loadFailedForCurrentNavigation = false
                // 服务端初始 meta theme-color 是实例强调色，不能拿来染系统栏；
                // 页面加载阶段保持壳层深浅底色，等 Misskey theme.ts 应用真实主题后由 AppChrome 上报。
                applySystemBarColor(defaultChromeColor)
                rootContainer.isRefreshing = true
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                rootContainer.isRefreshing = false
                if (loadFailedForCurrentNavigation) return
                persistTrustedMisskeyOrigin(url)
                injectLiminalAppInfo()
                installBlobDownloadShim()
                injectNativePushStateFromPrefs()
                snapshotWebViewHistory()
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?,
            ) {
                if (request?.isForMainFrame == true) {
                    loadFailedForCurrentNavigation = true
                    val failingUrl = request.url?.toString().orEmpty().ifBlank { currentUrl }
                    showLoadErrorPage(
                        view,
                        failingUrl,
                        error?.errorCode?.toString().orEmpty(),
                        error?.description?.toString().orEmpty(),
                    )
                }
            }

            override fun onReceivedHttpError(
                view: WebView?,
                request: WebResourceRequest?,
                errorResponse: WebResourceResponse?,
            ) {
                if (request?.isForMainFrame == true) {
                    loadFailedForCurrentNavigation = true
                    val failingUrl = request.url?.toString().orEmpty().ifBlank { currentUrl }
                    val statusCode = errorResponse?.statusCode ?: -1
                    val reason = errorResponse?.reasonPhrase.orEmpty()
                    showLoadErrorPage(
                        view,
                        failingUrl,
                        statusCode.toString(),
                        if (reason.isBlank()) "HTTP response code failure" else reason,
                    )
                }
            }

            override fun onRenderProcessGone(
                view: WebView?,
                detail: RenderProcessGoneDetail?,
            ): Boolean {
                val didCrash = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    detail?.didCrash()
                } else {
                    null
                }
                Log.w(TAG, "WebView render process gone. didCrash=$didCrash")
                rootContainer.isRefreshing = false
                view?.destroy()
                recreateWebViewAndReload()
                return true
            }
        }
        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                progressBar.progress = newProgress
                progressBar.visibility = if (newProgress in 1..99) View.VISIBLE else View.GONE
            }

            override fun onPermissionRequest(request: PermissionRequest?) {
                if (request == null) return
                // 只信任站内（Misskey 实例）origin 发起的权限请求
                if (!isTrustedMisskeyPermissionOrigin(request.origin)) {
                    Log.w(TAG, "Web permission request from untrusted origin: ${request.origin}")
                    request.deny()
                    return
                }
                // 仅处理摄像头/麦克风采集；未知资源一律拒绝
                val mapped = request.resources.mapNotNull { resource ->
                    when (resource) {
                        PermissionRequest.RESOURCE_VIDEO_CAPTURE -> resource to Manifest.permission.CAMERA
                        PermissionRequest.RESOURCE_AUDIO_CAPTURE -> resource to Manifest.permission.RECORD_AUDIO
                        else -> null
                    }
                }
                if (mapped.isEmpty()) {
                    request.deny()
                    return
                }
                val missing = mapped.map { it.second }
                    .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
                    .distinct()
                if (missing.isEmpty()) {
                    // 只授予已映射且已获批的资源（请求中可能混有未知资源，不得一并授予）
                    request.grant(mapped.map { it.first }.toTypedArray())
                    return
                }
                // 单个未决请求：新请求到来时先拒绝旧的
                pendingWebPermissionRequest?.deny()
                pendingWebPermissionRequest = request
                webPermissionLauncher.launch(missing.toTypedArray())
            }

            /**
             * 拦截 window.open()：Misskey 前端更新弹窗等场景会用 window.open 打开链接，
             * WebView 默认不支持多窗口会静默丢弃。这里提取目标 URL：
             * 官方域名及构建注入的实例同源地址在当前 WebView 内加载；其余地址弹确认后在系统浏览器打开。
             */
            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: android.os.Message?,
            ): Boolean {
                val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false
                // 创建一个临时 WebView 只为获取目标 URL。
                // 注意：必须在所有路径上销毁，否则会泄漏原生资源并可能钉住整个 Activity。
                val tempWebView = WebView(this@NativeWebViewActivity)
                tempWebView.webViewClient = object : WebViewClient() {
                    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                        // 无论 url 是否为空都先销毁，避免 url==null 时提前 return 导致泄漏
                        view?.stopLoading()
                        view?.destroy()
                        if (url != null) {
                            handleWindowOpenUrl(url)
                        }
                    }

                    override fun onReceivedError(
                        view: WebView?,
                        request: WebResourceRequest?,
                        error: WebResourceError?,
                    ) {
                        // 导航加载失败（不会触发 onPageStarted 后续流程）时也要清理
                        if (request?.isForMainFrame == true) {
                            view?.stopLoading()
                            view?.destroy()
                        }
                    }
                }
                transport.webView = tempWebView
                resultMsg.sendToTarget()
                return true
            }

            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?,
            ): Boolean {
                this@NativeWebViewActivity.filePathCallback?.onReceiveValue(null)
                this@NativeWebViewActivity.filePathCallback = filePathCallback
                launchFileChooser(fileChooserParams)
                return true
            }

            // HTML5 全屏（Fullscreen API）：视频全屏按钮等场景，把引擎给出的视图铺满窗口。
            // 不实现这两个回调时 requestFullscreen() 会静默失败（浏览器正常、WebView 无反应）。
            override fun onShowCustomView(view: View?, callback: WebChromeClient.CustomViewCallback?) {
                if (view == null || callback == null) return
                showFullscreenCustomView(view, callback)
            }

            // 个别 ROM/旧引擎会走带方向参数的重载，行为保持一致。
            override fun onShowCustomView(
                view: View?,
                requestedOrientation: Int,
                callback: WebChromeClient.CustomViewCallback?,
            ) {
                if (view == null || callback == null) return
                showFullscreenCustomView(view, callback)
            }

            override fun onHideCustomView() {
                hideFullscreenCustomView()
            }
        }
        webView.setDownloadListener { url, userAgent, contentDisposition, mimetype, _ ->
            handleWebViewDownload(url, userAgent, contentDisposition, mimetype)
        }
    }

    private fun setupViews() {
        // prefers-color-scheme 跟随系统：必须用 DayNight 主题的 Context 创建 WebView
        // （Chromium 从该 Context 主题的 isLightTheme 推导深浅色），Activity 本身的
        // Theme.Light 保持壳层浅色不变。系统深浅色切换会触发 Activity 重建，
        // 届时以新配置重新解析该主题，站内 matchMedia 即得到正确结果。
        webView = WebView(ContextThemeWrapper(this, R.style.Theme_Liminal_WebView))
        rootContainer = TopEdgeSwipeRefreshLayout(this).apply {
            hotZonePx = dp(topPullHotZoneDp)
            statusBarInsetProvider = {
                ViewCompat.getRootWindowInsets(window.decorView)
                    ?.getInsets(WindowInsetsCompat.Type.statusBars())
                    ?.top ?: 0
            }
            canChildScrollUpProvider = { webView.canScrollVertically(-1) }
            setColorSchemeColors(accentColor, accentColorDark)
            setProgressViewOffset(false, dp(18), dp(62))
            setOnRefreshListener {
                if (showingErrorPage) {
                    retryFromErrorPage()
                } else {
                    webView.reload()
                }
            }
            setBackgroundColor(defaultChromeColor)
        }
        // 透明状态栏下，把安全区显式变成顶部 padding，避免刘海/状态栏遮挡内容。
        ViewCompat.setOnApplyWindowInsetsListener(rootContainer) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.navigationBars(),
            )
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            // 键盘弹出时取更大的底部 inset，避免输入框被 IME 遮挡。
            val bottom = maxOf(bars.bottom, ime.bottom)
            v.setPadding(0, bars.top, 0, bottom)
            insets
        }
        val frame = FrameLayout(this)
        contentFrame = frame
        progressBar = ProgressBar(
            this,
            null,
            android.R.attr.progressBarStyleHorizontal,
        ).apply {
            max = 100
            visibility = View.GONE
            progressTintList = ColorStateList.valueOf(progressBarColor)
        }
        frame.addView(
            webView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        frame.addView(
            progressBar,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                dp(2),
            ),
        )
        errorOverlayDark = pageThemeDark
        errorOverlay = buildErrorOverlay(pageThemeDark)
        frame.addView(
            errorOverlay,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        rootContainer.addView(
            frame,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        applyShellPalette()
        setContentView(rootContainer)
    }

    /** 壳层随站内深浅变化的调色板应用：刷新指示器圆底与顶部进度条轨道。 */
    private fun applyShellPalette() {
        rootContainer.setProgressBackgroundColorSchemeColor(
            if (pageThemeDark) Color.parseColor("#22262E") else Color.WHITE,
        )
        progressBar.progressBackgroundTintList = ColorStateList.valueOf(
            if (pageThemeDark) Color.parseColor("#1F3A66") else progressBarTrackColor,
        )
    }

    /** 错误页调色板：跟随站内深浅（[buildErrorOverlay] 按当前 [pageThemeDark] 选择）。 */
    private data class ErrorPalette(
        val title: Int,
        val body: Int,
        val cardBg: Int,
        val cardStroke: Int,
        val cardText: Int,
        val meta: Int,
        val markAccent: Int,
        val secondaryBg: Int,
        val secondaryText: Int,
    )

    private fun errorPalette(dark: Boolean): ErrorPalette = if (dark) {
        ErrorPalette(
            title = Color.parseColor("#E8EBF2"),
            body = Color.parseColor("#A6AEBE"),
            cardBg = Color.parseColor("#1F232B"),
            cardStroke = Color.parseColor("#303643"),
            cardText = Color.parseColor("#C5CBD8"),
            meta = Color.parseColor("#7C8496"),
            markAccent = Color.parseColor("#8AB4F8"),
            secondaryBg = Color.parseColor("#1E2A3D"),
            secondaryText = Color.parseColor("#8AB4F8"),
        )
    } else {
        ErrorPalette(
            title = Color.parseColor("#162033"),
            body = Color.parseColor("#637083"),
            cardBg = Color.WHITE,
            cardStroke = Color.parseColor("#E5EAF1"),
            cardText = Color.parseColor("#405064"),
            meta = Color.parseColor("#8A95A6"),
            markAccent = accentColor,
            secondaryBg = Color.parseColor("#EAF2FF"),
            secondaryText = accentColorDark,
        )
    }

    private fun buildErrorOverlay(dark: Boolean): View {
        val palette = errorPalette(dark)
        val root = FrameLayout(this).apply {
            setBackgroundColor(if (dark) darkChromeColor else lightChromeColor)
            visibility = View.GONE
            isClickable = true
            isFocusable = true
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(30), dp(24), dp(28))
        }

        val mark = TextView(this).apply {
            text = "!"
            gravity = Gravity.CENTER
            textSize = 30f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(palette.markAccent)
            background = roundedRect(palette.cardBg, dp(28), palette.cardStroke, 1)
        }
        content.addView(
            mark,
            LinearLayout.LayoutParams(dp(78), dp(78)).apply {
                bottomMargin = dp(22)
            },
        )

        content.addView(
            TextView(this).apply {
                text = "暂时无法连接"
                gravity = Gravity.CENTER
                textSize = 24f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(palette.title)
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )

        content.addView(
            TextView(this).apply {
                text = "页面没有成功加载。请检查网络状态，或稍后重试。"
                gravity = Gravity.CENTER
                textSize = 15f
                setTextColor(palette.body)
                setLineSpacing(0f, 1.18f)
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = dp(12)
            },
        )

        errorUrlText = TextView(this).apply {
            textSize = 13f
            setTextColor(palette.cardText)
            setLineSpacing(0f, 1.12f)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = roundedRect(palette.cardBg, dp(8), palette.cardStroke, 1)
        }
        content.addView(
            errorUrlText,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = dp(22)
            },
        )

        errorMetaText = TextView(this).apply {
            gravity = Gravity.CENTER
            textSize = 12f
            setTextColor(palette.meta)
        }
        content.addView(
            errorMetaText,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = dp(10)
            },
        )

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        actions.addView(
            makeErrorButton("浏览器打开", primary = false, palette = palette) {
                runCatching { openExternal(Uri.parse(currentUrl)) }
            },
            LinearLayout.LayoutParams(0, dp(46), 1f).apply {
                marginEnd = dp(5)
            },
        )
        actions.addView(
            makeErrorButton("重试", primary = true, palette = palette) { retryFromErrorPage() },
            LinearLayout.LayoutParams(0, dp(46), 1f).apply {
                marginStart = dp(5)
            },
        )
        content.addView(
            actions,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = dp(26)
            },
        )

        root.addView(
            content,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER,
            ),
        )
        return root
    }

    private fun makeErrorButton(
        label: String,
        primary: Boolean,
        palette: ErrorPalette,
        onClick: () -> Unit,
    ): Button {
        return Button(this).apply {
            text = label
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            isAllCaps = false
            setTextColor(if (primary) Color.WHITE else palette.secondaryText)
            background = if (primary) {
                roundedRect(accentColor, dp(8), accentColor, 0)
            } else {
                roundedRect(palette.secondaryBg, dp(8), Color.TRANSPARENT, 0)
            }
            setOnClickListener { onClick() }
        }
    }

    private fun roundedRect(
        fill: Int,
        radiusPx: Int,
        strokeColor: Int,
        strokeWidthPx: Int,
    ): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(fill)
            cornerRadius = radiusPx.toFloat()
            if (strokeWidthPx > 0) {
                setStroke(strokeWidthPx, strokeColor)
            }
        }
    }

    private class TopEdgeSwipeRefreshLayout(context: Context) : SwipeRefreshLayout(context) {
        var hotZonePx = 0
        var statusBarInsetProvider: (() -> Int)? = null
        var canChildScrollUpProvider: (() -> Boolean)? = null

        private var gestureEligible = false
        private var downX = 0f
        private var downY = 0f
        private var directionLock = 0
        private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
        private val verticalTriggerSlop = (touchSlop * 1.8f).toInt()

        override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    val statusBarTop = statusBarInsetProvider?.invoke() ?: 0
                    val startsAtTop = event.y <= statusBarTop + hotZonePx
                    val canScrollUp = canChildScrollUpProvider?.invoke() ?: true
                    gestureEligible = startsAtTop && !canScrollUp
                    downX = event.x
                    downY = event.y
                    directionLock = 0
                }

                MotionEvent.ACTION_MOVE -> {
                    if (gestureEligible && directionLock == 0) {
                        val dx = kotlin.math.abs(event.x - downX)
                        val dy = kotlin.math.abs(event.y - downY)
                        if (dx <= touchSlop && dy <= verticalTriggerSlop) return false

                        directionLock = if (dy > dx && dy > 0f) 1 else -1
                        if (directionLock == -1) gestureEligible = false
                    }
                }

                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> resetGesture()
            }
            if (!gestureEligible || directionLock == -1) return false
            return super.onInterceptTouchEvent(event)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (!gestureEligible && !isRefreshing) return false
            val handled = super.onTouchEvent(event)
            if (event.actionMasked == MotionEvent.ACTION_UP ||
                event.actionMasked == MotionEvent.ACTION_CANCEL
            ) {
                resetGesture()
            }
            return handled
        }

        private fun resetGesture() {
            gestureEligible = false
            directionLock = 0
        }
    }

    private fun recreateWebViewAndReload() {
        // 旧 WebView 即将销毁：未决权限请求一并拒绝
        denyPendingWebPermission()
        setupViews()
        setupWebView()
        // renderer 被系统回收：优先恢复后台时保存的历史栈（返回键/站内返回才有效），
        // 无可用历史时回退到直接加载当前 URL。
        if (restoreWebViewHistory(savedWebViewState)) {
            webView.reload()
        } else {
            loadUrl(currentUrl)
        }
    }

    /**
     * 保存当前 WebView 导航历史到内存：后台期间 renderer 可能被系统回收，
     * WebView 重建后靠 [restoreWebViewHistory] 恢复返回栈，
     * 避免"明明不在首页却提示再次点击返回键退出"。
     */
    private fun snapshotWebViewHistory() {
        if (isFinishing || !::webView.isInitialized) return
        savedWebViewState = null
        if (webView.copyBackForwardList().size <= 1) return
        val bundle = Bundle()
        if (invokeWebViewStateMethod("saveState", bundle) != null) {
            savedWebViewState = bundle
        }
    }

    /** 从 [state] 恢复 WebView 导航历史；至少恢复出可退条目才视为成功。 */
    private fun restoreWebViewHistory(state: Bundle?): Boolean {
        if (state == null || !::webView.isInitialized) return false
        val result = invokeWebViewStateMethod("restoreState", state)
        val restoredCount = when (result) {
            is Int -> result
            is WebBackForwardList -> result.size
            else -> 0
        }
        return restoredCount > 1
    }

    /**
     * 反射调用 WebView.saveState/restoreState：API 35+ 返回 WebBackForwardList，
     * 旧版本返回 int，直接编译调用会因方法描述符（返回类型）不一致在旧设备抛
     * NoSuchMethodError，故统一经反射调用并按实际返回类型解释。
     */
    private fun invokeWebViewStateMethod(name: String, bundle: Bundle): Any? {
        return try {
            WebView::class.java
                .getMethod(name, Bundle::class.java)
                .invoke(webView, bundle)
        } catch (t: Throwable) {
            null
        }
    }

    private fun applyChromeColorNow(color: Int) {
        // 由页面背景亮度推导站内深浅：壳层对话框/错误页/加载条等据此跟随
        // Misskey 实际生效的主题（站内切换主题会触发 AppChrome 上报，实时联动）。
        if (!isLightColor(color) != pageThemeDark) {
            pageThemeDark = !isLightColor(color)
            applyShellPalette()
        }
        if (color == lastChromeColor) return
        rootContainer.setBackgroundColor(color)
        applySystemBarColor(color)
    }

    private fun applySystemBarColor(color: Int) {
        lastChromeColor = color
        val lightBars = isLightColor(color)
        if (lastSystemBarAppliedColor == color &&
            lastSystemBarAppliedLightBars == lightBars
        ) {
            return
        }
        lastSystemBarAppliedColor = color
        lastSystemBarAppliedLightBars = lightBars

        window.statusBarColor = color
        window.navigationBarColor = color
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.isAppearanceLightStatusBars = lightBars
        controller.isAppearanceLightNavigationBars = lightBars
    }

    private fun isLightColor(color: Int): Boolean {
        val r = Color.red(color)
        val g = Color.green(color)
        val b = Color.blue(color)
        // YIQ luma approximation
        return ((r * 299) + (g * 587) + (b * 114)) / 1000 >= 168
    }

    private fun parseJsColor(raw: String?): Int? {
        if (raw.isNullOrBlank()) return null
        val s = raw.trim().removePrefix("\"").removeSuffix("\"")
            .replace("\\\"", "\"")
            .replace("\\\\", "\\")
            .trim()
        if (s.isBlank() || s == "null") return null
        return runCatching { Color.parseColor(s) }.getOrNull()
            ?: runCatching {
                val rgb = Regex("""rgba?\((\d+),\s*(\d+),\s*(\d+)""")
                    .find(s) ?: return@runCatching null
                val (r, g, b) = rgb.destructured
                Color.rgb(r.toInt(), g.toInt(), b.toInt())
            }.getOrNull()
    }

    /** 对话框宿主主题：亮/暗跟随 Misskey 当前生效主题（而非系统）。 */
    private fun dialogHostStyle(): Int =
        if (pageThemeDark) {
            R.style.Theme_Liminal_Dialog_Host_Dark
        } else {
            R.style.Theme_Liminal_Dialog_Host_Light
        }

    /** 构建注入的实例按同源匹配，避免同一主机的其他端口被误当作站内页面。 */
    private fun isInternalMisskeyUrl(uri: Uri): Boolean {
        val scheme = uri.scheme?.lowercase() ?: return false
        if (scheme != "http" && scheme != "https") return false
        val host = uri.host?.lowercase() ?: return false
        if (host == "liminalselves.top" || host.endsWith(".liminalselves.top")) return true

        val configured = Uri.parse(defaultUrl)
        return scheme == configured.scheme?.lowercase() &&
            host == configured.host?.lowercase() &&
            effectivePort(uri) == effectivePort(configured)
    }

    private fun effectivePort(uri: Uri): Int =
        if (uri.port != -1) uri.port else when (uri.scheme?.lowercase()) {
            "http" -> 80
            "https" -> 443
            else -> -1
        }

    private fun handleNavigation(uri: Uri): Boolean {
        val scheme = uri.scheme?.lowercase() ?: return false
        if (scheme in setOf("javascript", "about", "blob", "data")) return false
        if (scheme !in setOf("http", "https")) {
            openExternal(uri)
            return true
        }
        if (isInternalMisskeyUrl(uri)) return false
        // ContextThemeWrapper 给对话框一个完整的 Material 3 Theme，
        // 隔离 Activity 原有主题，避免 MaterialAlertDialogBuilder 找不到 Material 属性而崩溃。
        val dialogCtx = ContextThemeWrapper(this, dialogHostStyle())
        MaterialAlertDialogBuilder(dialogCtx)
            .setTitle("即将离开站点")
            .setMessage("当前链接不在应用站点内，将在系统默认浏览器中打开：\n\n${uri}")
            .setNegativeButton("取消", null)
            .setPositiveButton("在浏览器中打开") { _, _ -> openExternal(uri) }
            .show()
        return true
    }

    /**
     * 处理 window.open() 拦截到的 URL：
     * 官方域名及构建注入的实例同源地址在当前 WebView 内加载；
     * 站外地址弹出确认对话框后在系统浏览器打开，避免隐式跳转。
     * about:blank 等内部协议（前端用于占位窗口等）不触发离站确认，静默忽略。
     */
    private fun handleWindowOpenUrl(rawUrl: String) {
        val uri = Uri.parse(rawUrl)
        val scheme = uri.scheme?.lowercase()
        if (scheme in setOf("about", "javascript", "blob", "data")) return
        if (isInternalMisskeyUrl(uri)) {
            // 站内地址：在当前 WebView 内打开
            webView.loadUrl(rawUrl)
            return
        }
        // 站外域名：弹确认后在系统浏览器打开
        val dialogCtx = ContextThemeWrapper(this, dialogHostStyle())
        MaterialAlertDialogBuilder(dialogCtx)
            .setTitle("即将离开站点")
            .setMessage("当前链接不在应用站点内，将在系统默认浏览器中打开：\n\n${rawUrl}")
            .setNegativeButton("取消", null)
            .setPositiveButton("在浏览器中打开") { _, _ -> openExternal(uri) }
            .show()
    }

    private fun openExternal(uri: Uri) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE))
        } catch (_: ActivityNotFoundException) {
            // 无可处理该链接的应用（常见为无浏览器）：给出明确反馈而非静默失败
            showToastIfForeground("无法在系统中打开该链接")
        }
    }

    private fun showLoadErrorPage(
        view: WebView?,
        failingUrl: String,
        code: String,
        description: String,
    ) {
        if (failingUrl.startsWith("data:", ignoreCase = true)) return
        loadFailedForCurrentNavigation = true
        showingErrorPage = true
        currentUrl = failingUrl
        rootContainer.isRefreshing = false
        progressBar.visibility = View.GONE
        view?.stopLoading()
        webView.visibility = View.INVISIBLE
        applySystemBarColor(defaultChromeColor)
        if (::errorOverlay.isInitialized) {
            // 站内深浅在错误页构建后变化过：按当前状态重建，保证错误页与站内主题一致。
            if (errorOverlayDark != pageThemeDark) {
                errorOverlayDark = pageThemeDark
                contentFrame.removeView(errorOverlay)
                errorOverlay = buildErrorOverlay(pageThemeDark)
                contentFrame.addView(
                    errorOverlay,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT,
                    ),
                )
            }
            errorUrlText.text = failingUrl
            errorMetaText.text = formatLoadError(code, description)
            errorOverlay.visibility = View.VISIBLE
            errorOverlay.bringToFront()
        }
    }

    private fun hideLoadErrorPage() {
        if (::webView.isInitialized) {
            webView.visibility = View.VISIBLE
        }
        if (::errorOverlay.isInitialized) {
            errorOverlay.visibility = View.GONE
        }
        showingErrorPage = false
    }

    private fun retryFromErrorPage() {
        loadUrl(currentUrl)
    }

    private fun loadUrl(url: String) {
        val target = url.ifBlank { defaultUrl }
        currentUrl = target
        hideLoadErrorPage()
        loadFailedForCurrentNavigation = false
        webView.visibility = View.VISIBLE
        rootContainer.isRefreshing = true
        progressBar.progress = 5
        progressBar.visibility = View.VISIBLE
        webView.stopLoading()
        webView.loadUrl(target)
    }

    private fun formatLoadError(code: String, description: String): String {
        val trimmedCode = code.trim()
        val trimmedDescription = description.trim()
        val httpCode = trimmedCode.toIntOrNull()
        if (httpCode != null && httpCode > 0) {
            return "HTTP ERROR $httpCode"
        }
        return when {
            trimmedCode.equals("NETWORK", ignoreCase = true) -> "网络连接失败"
            trimmedDescription.contains("ERR_HTTP_RESPONSE_CODE_FAILURE", ignoreCase = true) -> "HTTP ERROR"
            trimmedDescription.contains("java.lang.", ignoreCase = true) -> "网络连接失败"
            trimmedDescription.contains("Throwable", ignoreCase = true) -> "网络连接失败"
            trimmedDescription.isNotBlank() -> trimmedDescription.take(80)
            trimmedCode.isNotBlank() -> trimmedCode.take(80)
            else -> "页面暂时无法加载"
        }
    }

    private fun launchFileChooser(params: WebChromeClient.FileChooserParams?) {
        val accept = params?.acceptTypes?.filter { it.isNotBlank() }?.toTypedArray() ?: emptyArray()
        val openIntent = Intent(Intent.ACTION_GET_CONTENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = if (accept.isEmpty()) "*/*" else accept.first()
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, params?.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE)
            if (accept.isNotEmpty()) {
                putExtra(Intent.EXTRA_MIME_TYPES, accept)
            }
        }
        val intents = mutableListOf<Intent>()
        if (params?.isCaptureEnabled == true) {
            val camera = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
            val output = createCameraOutputUri()
            if (output != null) {
                cameraOutputUri = output
                camera.putExtra(MediaStore.EXTRA_OUTPUT, output)
                camera.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                intents += camera
            }
        }
        val chooser = Intent(Intent.ACTION_CHOOSER).apply {
            putExtra(Intent.EXTRA_INTENT, openIntent)
            putExtra(Intent.EXTRA_TITLE, "选择文件")
            if (intents.isNotEmpty()) {
                putExtra(Intent.EXTRA_INITIAL_INTENTS, intents.toTypedArray())
            }
        }
        fileChooserLauncher.launch(chooser)
    }

    private fun createCameraOutputUri(): Uri? {
        return runCatching {
            val dir = File(getExternalFilesDir(Environment.DIRECTORY_PICTURES), "capture").apply { mkdirs() }
            val file = File(dir, "capture_${System.currentTimeMillis()}.jpg")
            FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        }.getOrNull()
    }

    // ─── 文件下载实现 ─────────────────────────────────────────────────────────

    private fun handleWebViewDownload(
        url: String,
        userAgent: String?,
        contentDisposition: String?,
        mimetype: String?,
    ) {
        val fileName = parseDownloadFileName(url, contentDisposition, mimetype)
        val mime = mimetype?.takeIf { it.isNotBlank() }
            ?: guessMimeType(fileName, url)

        // 开始即给出反馈：HTTP 的进度通知要等系统拉起才有，blob/data 完全无系统反馈
        showToastIfForeground("开始下载：$fileName")

        // 三类下载统一保存到公共 Download/ 目录，权限策略一致（29+ 免权限，≤28 先申请再重放）。
        when {
            url.startsWith("blob:") -> ensurePublicDownloadPermissionThen { downloadBlobViaJs(url, fileName, mime) }
            url.startsWith("data:") -> ensurePublicDownloadPermissionThen { saveDataUrl(url, fileName, mime) }
            else -> ensurePublicDownloadPermissionThen { startDownloadWithManager(url, fileName, mime, userAgent) }
        }
    }

    /** 使用系统 DownloadManager 执行下载（通知栏进度、断点续传）。 */
    private fun startDownloadWithManager(
        url: String,
        fileName: String,
        mimeType: String,
        userAgent: String?,
    ) {
        runCatching {
            val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val request = DownloadManager.Request(Uri.parse(url)).apply {
                setMimeType(mimeType)
                userAgent?.let { addRequestHeader("User-Agent", it) }
                setTitle(fileName)
                setDescription("正在下载…")
                // 只保留下载中的进度通知；完成反馈由统一的前台 Toast 给出，
                // 避免系统完成通知 + Toast 双重提示。
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                // 保存到公共 Download 目录（/storage/emulated/0/Download/），
                // DownloadManager 有特权写入公共目录，无需额外权限；文件名冲突由 DM 自动追加后缀。
                setDestinationInExternalPublicDir(
                    Environment.DIRECTORY_DOWNLOADS,
                    fileName,
                )
                setAllowedOverMetered(true)
                setAllowedOverRoaming(false)
            }
            dm.enqueue(request)
        }.onFailure {
            Log.w(TAG, "DownloadManager enqueue failed: ${it.message}")
            showToastIfForeground("下载失败: ${it.message}")
        }
    }

    /**
     * 注入 Blob 下载 shim：包装 URL.createObjectURL，为每个 blob: URL 保留 Blob 引用
     * （有界缓存，防止大 Blob 长期滞留内存）。
     *
     * 背景：Misskey 前端导出文件的做法是 createObjectURL → a.click() →
     * setTimeout(0) 内 revokeObjectURL。原生 DownloadListener 回调与 evaluateJavascript
     * 注入均为异步往返，必然晚于 revoke 执行，此时再 fetch(blob:URL) 会抛
     * "Failed to fetch"。而 revoke 只注销 URL 映射、不销毁 Blob 本体，
     * 因此在创建时持有 Blob 引用，下载时即可绕过已被注销的 URL 直接读取。
     * 幂等：已安装则跳过；页面重载后 JS 上下文重置，onPageFinished 会重新注入。
     */
    private fun installBlobDownloadShim() {
        val js = """
            (function() {
              if (window.__LIMINAL_BLOB_SHIM_INSTALLED__) return;
              window.__LIMINAL_BLOB_SHIM_INSTALLED__ = true;
              var MAX_ENTRIES = 16;
              var MAX_TOTAL_BYTES = 32 * 1024 * 1024;
              var entries = [];
              var origCreate = URL.createObjectURL.bind(URL);
              URL.createObjectURL = function(blob) {
                var url = origCreate(blob);
                try {
                  if (blob && typeof blob.size === 'number') {
                    entries.push({ url: url, blob: blob });
                    var total = 0;
                    for (var i = 0; i < entries.length; i++) total += entries[i].blob.size;
                    while (entries.length > 0 &&
                           (entries.length > MAX_ENTRIES || total > MAX_TOTAL_BYTES)) {
                      var evicted = entries.shift();
                      total -= evicted.blob.size;
                    }
                  }
                } catch (_) {}
                return url;
              };
              window.__LIMINAL_BLOB_STORE__ = {
                // 非破坏性读取：低版本 Android 存储权限弹窗会延迟重放下载流程，
                // 届时需再次取到同一 Blob；缓存本身有界，条目靠逐出回收。
                peek: function(url) {
                  for (var i = 0; i < entries.length; i++) {
                    if (entries[i].url === url) return entries[i].blob;
                  }
                  return null;
                },
              };
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    /**
     * blob: URL 无法被 DownloadManager 处理，转为 base64 后回传原生层保存。
     * 优先从 [installBlobDownloadShim] 缓存的 Blob 引用直接读取——页面通常在触发
     * 下载后立即 revokeObjectURL，之后再 fetch 该 URL 会失败；缓存未命中
     * （shim 未注入或条目被逐出）时回退到 fetch 路径。
     */
    private fun downloadBlobViaJs(blobUrl: String, fileName: String, mimeType: String) {
        val escapedUrl = blobUrl.replace("\\", "\\\\").replace("'", "\\'")
        val escapedName = fileName.replace("\\", "\\\\").replace("'", "\\'")
        val escapedMime = mimeType.replace("\\", "\\\\").replace("'", "\\'")
        val js = """
            (function() {
              function deliver(blob) {
                var reader = new FileReader();
                reader.onloadend = function() {
                  var base64 = reader.result.split(',')[1] || '';
                  window.AppDownload && window.AppDownload.onBlobReady(base64, '$escapedName', '$escapedMime');
                };
                reader.onerror = function() {
                  window.AppDownload && window.AppDownload.onBlobError('read blob failed');
                };
                reader.readAsDataURL(blob);
              }
          var tracked = null;
          try {
            tracked = window.__LIMINAL_BLOB_STORE__ && window.__LIMINAL_BLOB_STORE__.peek('$escapedUrl');
          } catch (_) {}
              if (tracked) { deliver(tracked); return; }
              fetch('$escapedUrl')
                .then(function(r) { return r.blob(); })
                .then(deliver)
                .catch(function(e) {
                  window.AppDownload && window.AppDownload.onBlobError(e.message || 'fetch failed');
                });
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    /** data: URL 直接解码后保存到公共下载目录。 */
    private fun saveDataUrl(dataUrl: String, fileName: String, mimeType: String) {
        ioExecutor.execute {
            val bytes = runCatching {
                android.util.Base64.decode(dataUrl.substringAfter(","), android.util.Base64.DEFAULT)
            }.getOrElse {
                mainHandler.post {
                    showToastIfForeground("保存失败: ${it.message}")
                }
                return@execute
            }
            saveBytesToPublicDownloads(bytes, fileName, mimeType)
        }
    }

    /**
     * 将下载得到的字节保存到公共下载目录（/storage/emulated/0/Download/），与 HTTP 下载
     * 走系统 DownloadManager 的落盘位置保持一致：
     * - API 29+：经 MediaStore.Downloads 写入自身贡献的文件，无需存储权限；同名文件
     *   由 MediaStore 自动追加序号，写入完成后读回实际文件名用于提示。
     * - API ≤ 28：直写公共目录（调用前需已通过 [ensurePublicDownloadPermissionThen]
     *   获得权限），并主动触发媒体扫描让文件立即可见。
     * 须在后台线程调用。
     */
    private fun saveBytesToPublicDownloads(bytes: ByteArray, fileName: String, mimeType: String) {
        runCatching {
            var savedUri: Uri? = null
            val savedName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = contentResolver
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(MediaStore.Downloads.MIME_TYPE, mimeType)
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val contentUri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: throw IllegalStateException("MediaStore insert failed")
                resolver.openOutputStream(contentUri)?.use { it.write(bytes) }
                    ?: throw IllegalStateException("openOutputStream failed")
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(contentUri, values, null, null)
                savedUri = contentUri
                // 同名文件由 MediaStore 自动追加序号，读回实际文件名用于提示
                resolver.query(
                    contentUri,
                    arrayOf(MediaStore.Downloads.DISPLAY_NAME),
                    null,
                    null,
                    null,
                )?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: fileName
            } else {
                @Suppress("DEPRECATION")
                val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (!dir.exists()) dir.mkdirs()
                val file = File(dir, uniqueFileName(dir, fileName))
                FileOutputStream(file).use { it.write(bytes) }
                MediaScannerConnection.scanFile(this, arrayOf(file.absolutePath), arrayOf(mimeType), null)
                file.name
            }
            mainHandler.post {
                if (LiminalApplication.isAppInForeground()) {
                    showToastIfForeground("已保存到 Download/: $savedName")
                } else {
                    notifyDownloadComplete(savedName, savedUri, mimeType)
                }
            }
        }.onFailure {
            Log.w(TAG, "Save public download failed: ${it.message}")
            mainHandler.post {
                showToastIfForeground("保存失败: ${it.message}")
            }
        }
    }

    /** 从 Content-Disposition / URL 中解析文件名。 */
    private fun parseDownloadFileName(
        url: String,
        contentDisposition: String?,
        mimeType: String?,
    ): String {
        // 优先从 Content-Disposition 提取
        contentDisposition?.let { cd ->
            val filenameStar = Regex("filename\\*=(?:UTF-8''|utf-8'')(.+?)(?:;|$)", RegexOption.IGNORE_CASE)
                .find(cd)?.groupValues?.get(1)?.trim()
            if (filenameStar != null) {
                return runCatching { java.net.URLDecoder.decode(filenameStar, "UTF-8") }.getOrDefault(filenameStar)
            }
            val filename = Regex("filename=\"?([^\";\n]+)\"?", RegexOption.IGNORE_CASE)
                .find(cd)?.groupValues?.get(1)?.trim()
            if (!filename.isNullOrBlank()) return filename
        }
        // 回退到 URLUtil
        val guessed = URLUtil.guessFileName(url, contentDisposition, mimeType)
        if (guessed.isNotBlank() && guessed != "downloadfile") return guessed
        // 最后兜底
        val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType ?: "application/octet-stream") ?: "bin"
        return "download_${System.currentTimeMillis()}.$ext"
    }

    private fun guessMimeType(fileName: String, url: String): String {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            ?: if (url.startsWith("data:")) {
                url.substringAfter("data:").substringBefore(";").substringBefore(",").ifBlank { "application/octet-stream" }
            } else {
                "application/octet-stream"
            }
    }

    /** 文件名冲突时自动追加序号（在 [dir] 内探测）。 */
    private fun uniqueFileName(dir: File?, name: String): String {
        if (dir == null) return name
        val base = name.substringBeforeLast('.', name)
        val ext = name.substringAfterLast('.', "")
        var candidate = name
        var seq = 1
        while (File(dir, candidate).exists()) {
            candidate = if (ext.isNotBlank()) "${base}($seq).$ext" else "${base}($seq)"
            seq++
        }
        return candidate
    }

    /** blob 下载桥：接收 JS 层回传的 base64 数据并保存到公共下载目录。 */
    private inner class AppDownloadBridge {
        @JavascriptInterface
        fun onBlobReady(base64: String?, fileName: String?, mimeType: String?) {
            val data = base64.orEmpty()
            val name = fileName?.takeIf { it.isNotBlank() } ?: "download_${System.currentTimeMillis()}"
            val mime = mimeType?.takeIf { it.isNotBlank() } ?: "application/octet-stream"
            if (data.isEmpty()) {
                runOnUiThread { showToastIfForeground("下载失败: 空数据") }
                return
            }
            ioExecutor.execute {
                runCatching {
                    val bytes = android.util.Base64.decode(data, android.util.Base64.DEFAULT)
                    saveBytesToPublicDownloads(bytes, name, mime)
                }.onFailure {
                    mainHandler.post {
                        showToastIfForeground("保存失败: ${it.message}")
                    }
                }
            }
        }

        @JavascriptInterface
        fun onBlobError(error: String?) {
            runOnUiThread {
                showToastIfForeground("下载失败: ${error ?: "unknown"}")
            }
        }
    }

    private inner class AppNativePushBridge {
        @JavascriptInterface
        fun postMessage(msg: String?) {
            runOnUiThread {
                when (msg?.trim()) {
                    "enable" -> enableNativePushFromBridge()
                    "disable" -> disableNativePushFromBridge()
                    "query" -> injectNativePushStateFromPrefs()
                    else -> {
                        // {action:"notify"} 桥接已废弃：系统通知统一由常驻原生 WS 负责，
                        // 避免 WebView JS 与原生 WS 双通道重复弹窗（Misskey 前端仍会发，此处静默忽略）。
                    }
                }
            }
        }
    }

    private inner class AppChromeBridge {
        @JavascriptInterface
        fun postColor(raw: String?) {
            val color = parseJsColor(raw) ?: return
            if (color == lastChromeColor) return
            pendingChromeColor = color
            if (!chromeColorApplyScheduled) {
                chromeColorApplyScheduled = true
                runOnUiThread {
                    // 合并短时间多次颜色变化（主题切换/DOM 抖动），只应用最后一次。
                    mainHandler.postDelayed(applyPendingChromeColorRunnable, 80)
                }
            }
        }
    }

    /**
     * 路由状态桥：接收 Web 侧（Misskey utility/embedded-shell.ts）经 mainRouter
     * 主动上报的 `{ fullPath, route }`。仅用于同步 currentUrl（通知/错误页等
     * 场景需要准确 URL）；返回键行为完全由 WebView 历史决定，
     * 不再维护任何硬编码路由集合，也不 patch history。
     */
    private inner class AppNavBridge {
        @JavascriptInterface
        fun onRouteChange(json: String?) {
            val obj = runCatching { JSONObject(json.orEmpty()) }.getOrNull() ?: return
            val fullPath = obj.optString("fullPath", "")
            if (!fullPath.startsWith("/")) return
            val route = obj.optString("route", "")
            runOnUiThread {
                // 同步 currentUrl，避免刷新/通知等场景使用滞后值
                val base = currentUrl.let {
                    val u = Uri.parse(it)
                    val scheme = u.scheme ?: "https"
                    val host = u.host ?: return@let it
                    val port = u.port
                    if (port == -1) "$scheme://$host" else "$scheme://$host:$port"
                }
                currentUrl = base + fullPath
                Log.d(TAG, "Route change: $fullPath route=$route")
            }
        }
    }

    /** 开启推送前必须完成旧版五项权限向导。 */
    private fun enableNativePushFromBridge() {
        if (pushSetupRunning) return
        readMisskeyToken { token ->
            if (token.isNullOrBlank()) {
                dispatchNativePushState(registered = false, errorCode = "not_logged_in")
                return@readMisskeyToken
            }
            pushSetupRunning = true
            pushSetupLauncher.launch(
                Intent(this, PermissionSetupActivity::class.java)
                    .putExtra(PermissionSetupActivity.EXTRA_DARK, pageThemeDark),
            )
        }
    }

    /** 服务端 enableAppPush 开关同步结果。 */
    private enum class PushSyncResult {
        /** 开关写入成功 */
        SUCCESS,
        /** 未登录（读不到 token），未发起请求 */
        NOT_LOGGED_IN,
        /** 已登录但请求失败（网络/服务端问题） */
        FAILED,
    }

    /** 从服务端查询 enableAppPush 的结果。 */
    private enum class PushServerState {
        ENABLED,
        DISABLED,
        /** 未登录（读不到 token），未发起请求 */
        NOT_LOGGED_IN,
        /** 已登录但查询失败（网络/服务端问题） */
        UNREACHABLE,
    }

    private fun startNativePushAndDispatch() {
        updateAppPushFlagOnMisskey(true) { result ->
            when (result) {
                PushSyncResult.SUCCESS -> {
                    getPrefs().edit().putBoolean(PREF_NATIVE_PUSH_ENABLED, true).apply()
                    ensureNativePushRunning()
                    dispatchNativePushState(registered = true, errorCode = null)
                }
                PushSyncResult.NOT_LOGGED_IN ->
                    dispatchNativePushState(registered = false, errorCode = "not_logged_in")
                PushSyncResult.FAILED ->
                    dispatchNativePushState(registered = false, errorCode = "push_setup_failed")
            }
        }
    }

    private fun disableNativePushFromBridge() {
        // 先同步服务端开关，成功后再本地停用，避免“已停用但刷新又显示启用”
        updateAppPushFlagOnMisskey(false) { result ->
            when (result) {
                PushSyncResult.SUCCESS -> {
                    getPrefs().edit().putBoolean(PREF_NATIVE_PUSH_ENABLED, false).apply()
                    // 开关关闭：立即停止常驻推送（原生 WS + 前台服务），避免无推送时仍耗电
                    stopNativePushAll()
                    dispatchNativePushState(registered = false, errorCode = null)
                }
                PushSyncResult.NOT_LOGGED_IN -> {
                    // 未登录无服务端开关可同步：直接按停用处理
                    getPrefs().edit().putBoolean(PREF_NATIVE_PUSH_ENABLED, false).apply()
                    stopNativePushAll()
                    dispatchNativePushState(registered = false, errorCode = null)
                }
                PushSyncResult.FAILED -> {
                    dispatchNativePushState(registered = true, errorCode = "push_setup_failed")
                }
            }
        }
    }

    private fun injectNativePushStateFromPrefs() {
        fetchEnableAppPushFromMisskey { state ->
            when (state) {
                PushServerState.NOT_LOGGED_IN -> {
                    dispatchNativePushState(registered = false, errorCode = null)
                }
                PushServerState.UNREACHABLE -> {
                    dispatchNativePushState(registered = false, errorCode = "push_setup_failed")
                }
                PushServerState.DISABLED -> {
                    getPrefs().edit().putBoolean(PREF_NATIVE_PUSH_ENABLED, false).apply()
                    stopNativePushAll()
                    dispatchNativePushState(registered = false, errorCode = null)
                }
                PushServerState.ENABLED -> {
                    if (!areAllPushPermissionsReady()) {
                        disablePushForIncompletePermissions()
                        return@fetchEnableAppPushFromMisskey
                    }
                    getPrefs().edit().putBoolean(PREF_NATIVE_PUSH_ENABLED, true).apply()
                    ensureNativePushRunning()
                    dispatchNativePushState(registered = true, errorCode = null)
                }
            }
        }
    }

    /** 每次启动检查旧版五项权限是否完整。 */
    private fun areAllPushPermissionsReady(): Boolean {
        ensureMisskeyChannels(this)
        KeepAliveService.ensureChannel(this)
        if (!NotificationManagerCompat.from(this).areNotificationsEnabled()) return false
        val power = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!power.isIgnoringBatteryOptimizations(packageName)) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val chatReady = manager.getNotificationChannel(CHANNEL_MISSKEY_CHAT)?.importance
                ?.let { it >= NotificationManager.IMPORTANCE_HIGH } == true
            val notifyReady = manager.getNotificationChannel(CHANNEL_MISSKEY_NOTIFY)?.importance
                ?.let { it > NotificationManager.IMPORTANCE_NONE } == true
            val keepAliveReady = manager.getNotificationChannel(KeepAliveService.CHANNEL_ID)?.importance
                ?.let { it > NotificationManager.IMPORTANCE_NONE } == true
            if (!chatReady || !notifyReady || !keepAliveReady) return false
        }
        return true
    }

    /** 权限不完整时同步关闭服务器开关和本地推送，并通知 Web 说明原因。 */
    private fun disablePushForIncompletePermissions() {
        if (startupPushDisableRunning) return
        startupPushDisableRunning = true
        updateAppPushFlagOnMisskey(false) {
            startupPushDisableRunning = false
            getPrefs().edit().putBoolean(PREF_NATIVE_PUSH_ENABLED, false).apply()
            stopNativePushAll()
            dispatchNativePushState(registered = false, errorCode = null)
            webView.evaluateJavascript(
                "window.dispatchEvent(new CustomEvent('$EVENT_NATIVE_PUSH_ALERT'));",
                null,
            )
        }
    }

    private fun dispatchNativePushState(registered: Boolean, errorCode: String?, errorMessage: String? = null) {
        val detail = JSONObject().apply {
            put("registered", registered)
            if (!errorCode.isNullOrBlank()) put("errorCode", errorCode)
            if (!errorMessage.isNullOrBlank()) put("errorMessage", errorMessage)
        }
        val js = "window.dispatchEvent(new CustomEvent('$EVENT_NATIVE_PUSH', { detail: $detail }));"
        webView.evaluateJavascript(js, null)
    }

    private fun injectLiminalAppInfo() {
        val pkg = packageManager.getPackageInfo(packageName, 0)
        val appLabel = packageManager.getApplicationLabel(applicationInfo).toString()
        val map = JSONObject().apply {
            put("appName", appLabel)
            put("version", pkg.versionName ?: "")
            put("buildNumber", PackageInfoCompat.getLongVersionCode(pkg).toString())
            put("packageName", packageName)
            put("platform", "android")
            put("sdkInt", Build.VERSION.SDK_INT)
        }
        val js = "window.__LIMINAL_APP_INFO__ = $map; window.dispatchEvent(new CustomEvent('liminal-app-info-updated'));"
        webView.evaluateJavascript(js, null)
    }

    private fun areSystemNotificationsEnabled(): Boolean {
        return NotificationManagerCompat.from(this).areNotificationsEnabled()
    }

    /** 仅允许站内路径，防止 open redirect。 */
    private fun isSafeMisskeyOpenPath(path: String): Boolean {
        val p = path.trim()
        if (!p.startsWith("/")) return false
        if (p.contains("..")) return false
        return true
    }

    private fun absoluteUrlForMisskeyPath(path: String): String {
        val u = Uri.parse(currentUrl)
        val def = Uri.parse(defaultUrl)
        val scheme = u.scheme ?: def.scheme ?: "https"
        val host = u.host ?: def.host ?: return def.toString().trimEnd('/')
        val port = u.port
        val origin = if (port == -1 || port == 80 && scheme == "http" || port == 443 && scheme == "https") {
            "$scheme://$host"
        } else {
            "$scheme://$host:$port"
        }
        val normalizedPath = path.trim().let { if (it.startsWith("/")) it else "/$it" }
        return origin.trimEnd('/') + normalizedPath
    }

    private fun showSystemNotification(title: String, body: String, openPath: String? = null) {
        // 前台抑制：App 在前台时站内已实时展示通知内容，不重复发系统通知
        if (LiminalApplication.isAppInForeground()) {
            Log.i(TAG, "showSystemNotification skipped: app in foreground")
            return
        }
        // 以本地 pref 为门控：只有用户明确启用了 App 推送才弹系统通知
        if (!getPrefs().getBoolean(PREF_NATIVE_PUSH_ENABLED, false)) {
            Log.w(TAG, "showSystemNotification skipped: PREF_NATIVE_PUSH_ENABLED is false")
            return
        }
        if (!areSystemNotificationsEnabled()) {
            Log.w(TAG, "showSystemNotification skipped: system notifications disabled")
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "showSystemNotification skipped: POST_NOTIFICATIONS not granted")
            return
        }
        Log.i(TAG, "showSystemNotification: title=$title, body=${body.take(50)}")

        // 分级通知：私信（openPath 以 /chat 开头）走高优先级渠道（弹窗+声音+震动，仿微信），
        // 其余 Misskey 通知走低优先级渠道（仅通知栏，不弹窗不震动）。
        val isChat = openPath?.startsWith("/chat") == true
        val channelId = if (isChat) CHANNEL_MISSKEY_CHAT else CHANNEL_MISSKEY_NOTIFY
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ensureMisskeyChannels(this)
        }

        val targetUrl = openPath?.takeIf { isSafeMisskeyOpenPath(it) }?.let { absoluteUrlForMisskeyPath(it) } ?: currentUrl

        val openIntent = Intent(this, MainActivity::class.java).apply {
            putExtra("extraMap", JSONObject().put("openUrl", targetUrl).toString())
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val notificationId = localNotificationSeq++
        val pendingIntent = PendingIntent.getActivity(
            this,
            notificationId,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val contentText = body.trim().ifBlank { "你有一条新通知" }.take(200)
        val builder = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title.take(64))
            .setContentText(contentText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(contentText))
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
        if (isChat) {
            // 私信：弹窗（heads-up）+ 声音 + 震动，仿微信
            builder.setPriority(NotificationCompat.PRIORITY_HIGH)
                .setDefaults(NotificationCompat.DEFAULT_ALL)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        } else {
            // 互动通知：静默进通知栏，不弹窗不震动
            builder.setPriority(NotificationCompat.PRIORITY_LOW)
                .setSilent(true)
        }

        NotificationManagerCompat.from(this).notify(notificationId, builder.build())
    }

    private fun updateAppPushFlagOnMisskey(enabled: Boolean, cb: (PushSyncResult) -> Unit) {
        val origin = getMisskeyOrigin()
        readMisskeyToken { token ->
            if (token.isNullOrBlank()) {
                cb(PushSyncResult.NOT_LOGGED_IN)
                return@readMisskeyToken
            }
            ioExecutor.execute {
                val result = callMisskeyPushApi(
                    "$origin/api/i/update",
                    token,
                    JSONObject().apply {
                        put("i", token)
                        put("enableAppPush", enabled)
                    },
                )
                mainHandler.post {
                    if (!isFinishing && !isDestroyed) {
                        cb(if (result.ok) PushSyncResult.SUCCESS else PushSyncResult.FAILED)
                    }
                }
            }
        }
    }

    private fun readMisskeyToken(cb: (String?) -> Unit) {
        webView.evaluateJavascript(
            "(function(){try{var r=localStorage.getItem('account');return r?r:'';}catch(e){return '';}})();",
        ) { raw ->
            val v = raw?.trim()?.removePrefix("\"")?.removeSuffix("\"")
                ?.replace("\\\"", "\"")
                ?.replace("\\\\", "\\")
            if (v.isNullOrBlank()) {
                cb(null)
                return@evaluateJavascript
            }
            runCatching {
                val obj = JSONObject(v)
                cb(obj.opt("token") as? String)
            }.onFailure { cb(null) }
        }
    }

    private fun fetchEnableAppPushFromMisskey(cb: (PushServerState) -> Unit) {
        val origin = getMisskeyOrigin()
        readMisskeyToken { token ->
            if (token.isNullOrBlank()) {
                cb(PushServerState.NOT_LOGGED_IN)
                return@readMisskeyToken
            }
            ioExecutor.execute {
                val result = callMisskeyPushApi(
                    "$origin/api/i",
                    token,
                    JSONObject().apply {
                        put("i", token)
                    },
                )
                if (!result.ok) {
                    mainHandler.post {
                        if (!isFinishing && !isDestroyed) cb(PushServerState.UNREACHABLE)
                    }
                    return@execute
                }
                val enabled = runCatching {
                    JSONObject(result.body).optBoolean("enableAppPush", false)
                }.getOrNull()
                mainHandler.post {
                    if (!isFinishing && !isDestroyed) {
                        cb(if (enabled == true) PushServerState.ENABLED else PushServerState.DISABLED)
                    }
                }
            }
        }
    }

    private fun getMisskeyOrigin(): String {
        return Uri.parse(intent.getStringExtra(EXTRA_URL) ?: defaultUrl).let {
            "${it.scheme}://${it.host}${if (it.port > 0) ":${it.port}" else ""}"
        }
    }

    private fun callMisskeyPushApi(url: String, token: String, body: JSONObject): ApiCallResult {
        return runCatching {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15_000
                readTimeout = 15_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Authorization", "Bearer $token")
            }
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = conn.responseCode
            val responseBody = runCatching {
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                stream?.bufferedReader()?.use { it.readText() } ?: ""
            }.getOrDefault("")
            conn.disconnect()
            ApiCallResult(code in 200..299, code, responseBody)
        }.getOrElse {
            Log.w(TAG, "Misskey push API failed: ${it.message}")
            ApiCallResult(false, -1, it.message ?: "")
        }
    }

    private fun getPrefs() = getSharedPreferences("FlutterSharedPreferences", Context.MODE_PRIVATE)

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt().coerceAtLeast(1)

    /** 系统是否处于深色模式（跟随 uiMode 配置；切换会触发 Activity 重建后重新求值）。 */
    private fun isSystemNightMode(): Boolean =
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    override fun onPause() {
        // 原生 WS 与前台服务常驻，不随前后台切换启停（避免熄屏/点亮反复启停）；
        // 这里只负责 WebView 自身的暂停/恢复。
        if (isFinishing) {
            stopNativePushAll()
        }
        // 保存导航历史（须在 renderer 仍存活时）：后台期间 renderer 可能被系统回收，
        // 重建 WebView 后靠它恢复返回栈（系统返回键与站内返回按钮才有效）。
        if (!isFinishing) {
            snapshotWebViewHistory()
        }
        webView.onPause()
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // WebView 不会随 Activity 重建自动恢复历史；把已保存的历史并入实例状态，
        // 覆盖旋转/系统回收重建场景（进程被杀时由 [savedWebViewState] 兜底）。
        savedWebViewState?.let { outState.putAll(it) }
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
        applyPersistentSystemBars()
        // 自愈：若前台服务曾被系统回收但推送开关仍开启，回前台时恢复。
        ensureNativePushRunning()
        // 颜色以缓存值立即恢复；网页主题的实际变化经 AppChrome 桥实时上报，无需轮询。
        applySystemBarColor(lastChromeColor)
    }

    /**
     * 常驻推送基础设施：前台服务（提升进程优先级 + 常驻通知）
     * + Partial WakeLock（保持 CPU 运行，使原生 WS 心跳不被系统休眠中断）
     * + WifiLock（保持 WiFi 活跃）。
     * 推送开关开启期间持续运行，仅用户关闭开关/退出登录/退出 App 时停止；
     * 不再随前后台/熄屏切换启停。
     */
    private fun startWebSocketKeepAlive() {
        if (keepAliveActive) return
        keepAliveActive = true
        runCatching {
            val intent = Intent(this, KeepAliveService::class.java)
                .setAction(KeepAliveService.ACTION_START)
            ContextCompat.startForegroundService(this, intent)
        }.onFailure { Log.w(TAG, "start KeepAliveService failed: ${it.message}") }
        runCatching {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "liminal:ws-keepalive").apply {
                setReferenceCounted(false)
                // 常驻模式：不设超时，随 stopWebSocketKeepAlive 释放
                if (!isHeld) acquire()
            }
        }.onFailure { Log.w(TAG, "acquire WakeLock failed: ${it.message}") }
        runCatching {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            } else {
                @Suppress("DEPRECATION")
                WifiManager.WIFI_MODE_FULL_HIGH_PERF
            }
            wifiLock = wm.createWifiLock(mode, "liminal:ws-wifi").apply {
                setReferenceCounted(false)
                if (!isHeld) acquire()
            }
        }.onFailure { Log.w(TAG, "acquire WifiLock failed: ${it.message}") }
        Log.i(TAG, "Push keep-alive started (persistent)")
    }

    private fun stopWebSocketKeepAlive() {
        if (!keepAliveActive) return
        keepAliveActive = false
        runCatching {
            val intent = Intent(this, KeepAliveService::class.java)
                .setAction(KeepAliveService.ACTION_STOP)
            startService(intent)
        }
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
        runCatching { wifiLock?.takeIf { it.isHeld }?.release() }
        wifiLock = null
        Log.i(TAG, "Push keep-alive stopped")
    }

    /**
     * 确保常驻推送正在运行（幂等）：推送开关开启 + 已登录（有 token）时，
     * 启动前台服务与原生 WS。未登录时静默等待，页面加载完成后会再次尝试。
     */
    private fun ensureNativePushRunning() {
        if (isFinishing || isDestroyed) return
        if (!getPrefs().getBoolean(PREF_NATIVE_PUSH_ENABLED, false)) return
        readMisskeyToken { token ->
            if (isFinishing || isDestroyed) return@readMisskeyToken
            if (!getPrefs().getBoolean(PREF_NATIVE_PUSH_ENABLED, false)) return@readMisskeyToken
            if (token.isNullOrBlank()) {
                // 未登录：若原生 WS 仍在用旧 token 跑则停掉，等登录后由页面加载回调拉起
                if (nativeWsManager.isRunning()) {
                    Log.i(TAG, "Token gone (logged out?), stopping native push")
                    stopNativePushAll()
                }
                return@readMisskeyToken
            }
            getPrefs().edit().putString(PREF_LAST_MISSKEY_TOKEN, token).apply()
            // start 幂等：同 token+origin 直接返回；身份变化（换账号/换实例）时由
            // manager 内部取消旧连接并按新身份重连，因此这里无条件调用。
            nativeWsManager.start(token, getMisskeyOrigin())
            // 前台服务可能被系统回收过：补拉一次（幂等）
            if (!keepAliveActive) startWebSocketKeepAlive()
        }
    }

    /** 停止常驻推送：原生 WS + 前台服务/锁全部关闭（关开关/退出登录/退出 App）。 */
    private fun stopNativePushAll() {
        nativeWsManager.stop()
        stopWebSocketKeepAlive()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            applyPersistentSystemBars()
            applySystemBarColor(lastChromeColor)
        }
    }

    override fun onDestroy() {
        // 全屏视图仍挂着时先退出，避免 DecorView 泄漏与回调悬挂
        exitFullscreenCustomViewIfActive()
        mainHandler.removeCallbacks(applyPendingChromeColorRunnable)
        // 页面销毁：未决的 WebView 权限请求必须显式拒绝，避免回调悬挂
        denyPendingWebPermission()
        pushSetupRunning = false
        startupPushDisableRunning = false
        // 常驻模式：仅 Activity finish（用户退出 App）时停止推送；
        // 系统回收重建场景不停止，交由 onPause(isFinishing) 处理。
        if (isFinishing) {
            stopNativePushAll()
        }
        runCatching { unregisterReceiver(downloadCompleteReceiver) }
        filePathCallback?.onReceiveValue(null)
        filePathCallback = null
        webView.removeJavascriptInterface("AppNativePush")
        webView.removeJavascriptInterface("AppChrome")
        webView.removeJavascriptInterface("AppNav")
        webView.removeJavascriptInterface("AppDownload")
        webView.stopLoading()
        webView.webChromeClient = WebChromeClient()
        webView.webViewClient = WebViewClient()
        webView.destroy()
        ioExecutor.shutdownNow()
        super.onDestroy()
    }


    companion object {
        const val EXTRA_URL = "extra_url"
        const val EXTRA_UA_MARKER = "extra_ua_marker"
        private const val PREF_NATIVE_PUSH_ENABLED = "flutter.native_push_enabled"
        /** 与默认主机不同时，用于校验阿里云 extraMap.openUrl 是否可信 */
        private const val PREF_LAST_MISSKEY_ORIGIN = "liminal_last_misskey_origin"
        private const val EVENT_NATIVE_PUSH = "liminal-native-push"
        private const val EVENT_NATIVE_PUSH_ALERT = "liminal-native-push-alert"
        private const val EXIT_CONFIRM_MS = 2000L
        private const val EXIT_BACK_HINT = "再按一次返回键退出应用"
        private const val TAG = "NativeWebViewActivity"

        /** 私信消息渠道：高优先级（弹窗+声音+震动）。供权限向导共用。 */
        const val CHANNEL_MISSKEY_CHAT = "misskey_chat_v2"

        /** 互动通知渠道：低优先级（仅通知栏，不弹窗不震动）。供权限向导共用。 */
        const val CHANNEL_MISSKEY_NOTIFY = "misskey_notify_v2"

        /** 确保私信渠道存在（幂等），供 [PermissionSetupActivity] 权限向导共用。 */
        fun ensureChatChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL_MISSKEY_CHAT) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_MISSKEY_CHAT,
                        "私信消息",
                        NotificationManager.IMPORTANCE_HIGH,
                    ).apply {
                        description = "私信消息：弹窗提醒，声音与震动"
                        enableVibration(true)
                        enableLights(true)
                    },
                )
            }
        }

        /** 确保互动通知渠道存在（幂等），供 [PermissionSetupActivity] 权限向导共用。 */
        fun ensureNotifyChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL_MISSKEY_NOTIFY) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_MISSKEY_NOTIFY,
                        "互动通知",
                        NotificationManager.IMPORTANCE_LOW,
                    ).apply {
                        description = "提及/回复/反应/关注等互动通知：仅通知栏展示，不弹窗不震动"
                        setShowBadge(true)
                        setSound(null, null)
                        enableVibration(false)
                    },
                )
            }
        }

        /**
         * 清理历史遗留渠道（幂等、低成本）：
         * - misskey_push：阿里云 EMAS 推送渠道，EMAS 已停用；
         * - misskey_ws_live / misskey_ws_live_v2：旧单渠道方案已废弃；
         * - liminalselves_keepalive：旧保活渠道，已被 v2 取代。
         */
        fun cleanupLegacyChannels(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            listOf("misskey_push", "misskey_ws_live", "misskey_ws_live_v2", "liminalselves_keepalive").forEach { id ->
                runCatching { nm.deleteNotificationChannel(id) }
            }
        }

        /** 确保 Misskey 相关渠道全部就绪（遗留清理 + 私信 + 互动），供权限向导与启动检查共用。 */
        fun ensureMisskeyChannels(context: Context) {
            cleanupLegacyChannels(context)
            ensureChatChannel(context)
            ensureNotifyChannel(context)
        }

        /** 缓存的 Misskey token：供 WebView 暂停后原生 WS 启动兜底使用。 */
        private const val PREF_LAST_MISSKEY_TOKEN = "liminal_last_misskey_token"
    }
}
