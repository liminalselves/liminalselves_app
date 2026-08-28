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
import android.view.View
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
    private lateinit var swipeRefresh: TopEdgeSwipeRefreshLayout
    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar
    private lateinit var errorOverlay: View
    private lateinit var errorUrlText: TextView
    private lateinit var errorMetaText: TextView
    private val defaultUrl = BuildConfig.MISSKEY_URL.ifBlank {
        "https://misskey.liminalselves.top/"
    }
    private val ioExecutor = Executors.newFixedThreadPool(2)
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var cameraOutputUri: Uri? = null
    private var nativePushRestoreAttempted = false
    private var startupPermissionChecked = false
    private var pendingNotificationPermissionFromStartup = false
    private var currentUrl: String = defaultUrl
    private var showingErrorPage = false
    private var loadFailedForCurrentNavigation = false

    // ─── WebSocket 后台保活 ────────────────────────────────────────────────
    /** 保活是否进行中（前台服务 + CPU/网络锁），避免重复启停。 */
    private var keepAliveActive = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    /** Misskey WebSocket 当前是否处于 open 状态（由 JS 桥上报），用于保活效果观测。 */
    @Volatile
    private var wsConnected = false
    /** 原生 WebSocket 管理器：后台时绕过 WebView JS 节流，独立接收 Misskey 消息。 */
    private val nativeWsManager = NativeWsManager { title, body, openPath ->
        showSystemNotification(title, body, openPath)
    }

    /** 无 WebView 历史可退时：首次返回仅提示，短时内第二次返回才 [finish]。 */
    private var lastExitBackPressElapsed = 0L

    /** 后台期间保存的 WebView 导航历史：renderer 被系统回收重建时恢复返回栈。 */
    private var savedWebViewState: Bundle? = null
    private val topPullHotZoneDp = 84
    // 壳层采用 Flutter 风格蓝色系；仅作用于加载与下拉反馈，不改变站内导航逻辑。
    private val defaultChromeColor = Color.parseColor("#F7F9F5")
    private val accentColor = Color.parseColor("#1A73E8")
    private val accentColorDark = Color.parseColor("#1557B0")
    private val accentTrackColor = Color.parseColor("#D6E4FF")
    // 顶部加载条与下拉刷新均使用蓝色系。
    private val progressBarColor = Color.parseColor("#1A73E8")
    private val progressBarTrackColor = Color.parseColor("#D6E4FF")
    private var lastChromeColor: Int = defaultChromeColor
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
    private val refreshChromeColorRunnable = Runnable {
        if (!isFinishing && !isDestroyed && ::webView.isInitialized) {
            updateChromeColorFromWebPage()
        }
    }

    private val fileChooserLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val cb = filePathCallback
            filePathCallback = null
            if (cb == null) return@registerForActivityResult

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
            cb.onReceiveValue(uris)
        }

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startNativePushAndDispatch()
            } else {
                dispatchNativePushState(registered = false, errorCode = "permission_denied")
                if (pendingNotificationPermissionFromStartup) {
                    disableNativePushFromBridge()
                    dispatchNativePushAlert()
                }
            }
            pendingNotificationPermissionFromStartup = false
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
                Toast.makeText(this, "存储权限被拒绝，无法保存文件", Toast.LENGTH_SHORT).show()
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
            val uri = dm.getUriForDownloadedFile(id) ?: return
            Toast.makeText(this@NativeWebViewActivity, "下载完成", Toast.LENGTH_SHORT).show()
            // 尝试打开文件
            runCatching {
                val openIntent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, dm.getMimeTypeForDownloadedFile(id) ?: "*/*")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(openIntent)
            }.onFailure {
                Log.w(TAG, "Cannot open downloaded file: ${it.message}")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        overridePendingTransition(0, 0)
        setupSystemBars()
        applyWindowRefreshPresentationHints()
        applyPersistentSystemBars()
        setupViews()
        setupWebView()
        registerReceiver(
            downloadCompleteReceiver,
            IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
            Context.RECEIVER_NOT_EXPORTED,
        )
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // 实时从 WebView 引擎获取当前 URL，避免 SPA pushState 后 currentUrl 滞后
                val actualUrl = webView.url ?: currentUrl
                val path = Uri.parse(actualUrl).path.orEmpty()
                val atSectionRoot = isSectionRootPath(path)

                // 非板块根且有历史 → 正常回退
                if (!atSectionRoot && webView.canGoBack()) {
                    lastExitBackPressElapsed = 0L
                    webView.goBack()
                    return
                }
                // 板块根或无历史：双击退出
                val now = SystemClock.elapsedRealtime()
                if (lastExitBackPressElapsed != 0L &&
                    now - lastExitBackPressElapsed <= EXIT_CONFIRM_MS
                ) {
                    finish()
                    return
                }
                lastExitBackPressElapsed = now
                Toast.makeText(
                    this@NativeWebViewActivity,
                    SNACK_EXIT_BACK_HINT,
                    Toast.LENGTH_SHORT,
                ).show()
            }
        })
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
        val url = resolveLaunchUrl(intent)
        if (url.isBlank()) return
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
            webView.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_BOUND, true)
        }
        webView.overScrollMode = WebView.OVER_SCROLL_IF_CONTENT_SCROLLS
        webView.setBackgroundColor(getColor(android.R.color.background_light))
        // 避免 WebView 自行对 status bar inset 做额外 padding，造成顶端出现留白条。
        ViewCompat.setOnApplyWindowInsetsListener(webView) { v, insets ->
            v.setPadding(0, 0, 0, 0)
            insets
        }
        webView.addJavascriptInterface(AppNativePushBridge(), "AppNativePush")
        webView.addJavascriptInterface(AppChromeBridge(), "AppChrome")
        webView.addJavascriptInterface(AppNavBridge(), "AppNav")
        webView.addJavascriptInterface(AppDownloadBridge(), "AppDownload")
        webView.addJavascriptInterface(AppWsBridge(), "AppWs")
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
                swipeRefresh.isRefreshing = true
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                swipeRefresh.isRefreshing = false
                if (loadFailedForCurrentNavigation) return
                persistTrustedMisskeyOrigin(url)
                installChromeColorSyncBridge()
                updateChromeColorFromWebPage()
                injectLiminalAppInfo()
                installSpaNavWatcher()
                installWsStateWatcher()
                installBlobDownloadShim()
                reconcileStartupNotificationPermission()
                injectNativePushStateFromPrefs()
                onPageReadyForPushRestore()
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
                swipeRefresh.isRefreshing = false
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
                request?.grant(request.resources)
            }

            /**
             * 拦截 window.open()：Misskey 前端更新弹窗等场景会用 window.open 打开链接，
             * WebView 默认不支持多窗口会静默丢弃。这里提取目标 URL：
             * 站内域名（*.liminalselves.top）在当前 WebView 内加载；站外域名弹确认后在系统浏览器打开。
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
        }
        webView.setDownloadListener { url, userAgent, contentDisposition, mimetype, _ ->
            handleWebViewDownload(url, userAgent, contentDisposition, mimetype)
        }
    }

    private fun setupViews() {
        webView = WebView(this)
        swipeRefresh = TopEdgeSwipeRefreshLayout(this).apply {
            hotZonePx = dp(topPullHotZoneDp)
            statusBarInsetProvider = {
                ViewCompat.getRootWindowInsets(window.decorView)
                    ?.getInsets(WindowInsetsCompat.Type.statusBars())
                    ?.top ?: 0
            }
            canChildScrollUpProvider = { webView.canScrollVertically(-1) }
            isEnabled = true
            // 下拉刷新旋转指示器：统一品牌色，避免默认黄/蓝。
            setColorSchemeColors(accentColor, accentColorDark)
            setProgressBackgroundColorSchemeColor(Color.WHITE)
            // 只下移刷新指示器位置，保持当前触发/回弹手感不变。
            setProgressViewOffset(false, dp(18), dp(62))
            setOnRefreshListener {
                if (showingErrorPage) {
                    retryFromErrorPage()
                } else {
                    // 使用 webView.reload() 而非 loadUrl(currentUrl)：
                    // SPA pushState 路由不触发 onPageStarted，currentUrl 可能滞后；
                    // reload() 始终重新加载 WebView 引擎实际跟踪的当前 URL。
                    webView.reload()
                }
            }
        }
        // 透明状态栏下，把安全区显式变成顶部 padding，避免刘海/状态栏遮挡内容。
        swipeRefresh.setBackgroundColor(defaultChromeColor)
        ViewCompat.setOnApplyWindowInsetsListener(swipeRefresh) { v, insets ->
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
        progressBar = ProgressBar(
            this,
            null,
            android.R.attr.progressBarStyleHorizontal,
        ).apply {
            max = 100
            visibility = View.GONE
            progressTintList = ColorStateList.valueOf(progressBarColor)
            progressBackgroundTintList = ColorStateList.valueOf(progressBarTrackColor)
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
        errorOverlay = buildErrorOverlay()
        frame.addView(
            errorOverlay,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        swipeRefresh.addView(
            frame,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        setContentView(swipeRefresh)
    }

    private fun buildErrorOverlay(): View {
        val root = FrameLayout(this).apply {
            setBackgroundColor(defaultChromeColor)
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
            setTextColor(accentColor)
            background = roundedRect(Color.WHITE, dp(28), Color.parseColor("#E5EAF1"), 1)
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
                setTextColor(Color.parseColor("#162033"))
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
                setTextColor(Color.parseColor("#637083"))
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
            setTextColor(Color.parseColor("#405064"))
            setLineSpacing(0f, 1.12f)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = roundedRect(Color.WHITE, dp(8), Color.parseColor("#E5EAF1"), 1)
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
            setTextColor(Color.parseColor("#8A95A6"))
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
            makeErrorButton("浏览器打开", primary = false) {
                runCatching { openExternal(Uri.parse(currentUrl)) }
            },
            LinearLayout.LayoutParams(0, dp(46), 1f).apply {
                marginEnd = dp(5)
            },
        )
        actions.addView(
            makeErrorButton("重试", primary = true) { retryFromErrorPage() },
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

    private fun makeErrorButton(label: String, primary: Boolean, onClick: () -> Unit): Button {
        return Button(this).apply {
            text = label
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            isAllCaps = false
            setTextColor(if (primary) Color.WHITE else accentColorDark)
            background = if (primary) {
                roundedRect(accentColor, dp(8), accentColor, 0)
            } else {
                roundedRect(Color.parseColor("#EAF2FF"), dp(8), Color.TRANSPARENT, 0)
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

    /**
     * 仅在"手势起点位于顶部热区"且"WebView 已在顶端"时允许触发刷新。
     * 在父容器 onIntercept 阶段判断，规避 dispatchTouchEvent 与 SwipeRefreshLayout 的时序竞争。
     *
     * 方向锁定机制：手势超过 touch slop 后根据轨迹角度判断水平/垂直意图，
     * 水平意图为主时永久拒绝拦截，避免与页面内横向导航栏滑动冲突。
     */
    private class TopEdgeSwipeRefreshLayout(context: Context) : SwipeRefreshLayout(context) {
        var hotZonePx: Int = 0
        var statusBarInsetProvider: (() -> Int)? = null
        var canChildScrollUpProvider: (() -> Boolean)? = null
    
        private var gestureEligible = false
        private var downX = 0f
        private var downY = 0f
        private val touchSlop = android.view.ViewConfiguration.get(context).scaledTouchSlop
        /** 热区内使用更大的触发阈值（1.8 倍 touch slop），容忍水平滑动时的垂直抖动。 */
        private val verticalTriggerSlop = (touchSlop * 1.8f).toInt()
        /** 方向锁定状态：0=未确定, 1=垂直(允许刷新), -1=水平(拒绝刷新) */
        private var directionLock = 0
    
        override fun onInterceptTouchEvent(ev: android.view.MotionEvent): Boolean {
            when (ev.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    val statusTop = statusBarInsetProvider?.invoke() ?: 0
                    val inHotZone = ev.y <= (statusTop + hotZonePx)
                    val canScrollUp = canChildScrollUpProvider?.invoke() ?: true
                    gestureEligible = inHotZone && !canScrollUp
                    downX = ev.x
                    downY = ev.y
                    directionLock = 0
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    if (gestureEligible && directionLock == 0) {
                        val dx = Math.abs(ev.x - downX)
                        val dy = Math.abs(ev.y - downY)
                        if (dx > touchSlop || dy > verticalTriggerSlop) {
                            directionLock = if (dx > dy) {
                                // 水平意图为主 → 拒绝拦截，让 WebView 处理横向滚动
                                -1
                            } else if (dy > dx * 0.577f) {
                                // 垂直分量占优（角度 > 30° 偏离水平）→ 允许刷新
                                1
                            } else {
                                -1
                            }
                            if (directionLock == -1) {
                                gestureEligible = false
                            }
                        } else {
                            // 尚未超过阈值，不拦截，继续观察
                            return false
                        }
                    }
                }
                android.view.MotionEvent.ACTION_UP,
                android.view.MotionEvent.ACTION_CANCEL -> {
                    gestureEligible = false
                    directionLock = 0
                }
            }
            if (!gestureEligible) return false
            // 方向已锁定为水平时不拦截
            if (directionLock == -1) return false
            return super.onInterceptTouchEvent(ev)
        }
    
        override fun onTouchEvent(ev: android.view.MotionEvent): Boolean {
            if (!gestureEligible && !isRefreshing) return false
            if (ev.actionMasked == android.view.MotionEvent.ACTION_UP ||
                ev.actionMasked == android.view.MotionEvent.ACTION_CANCEL
            ) {
                gestureEligible = false
                directionLock = 0
            }
            return super.onTouchEvent(ev)
        }
    }

    private fun recreateWebViewAndReload() {
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

    private fun updateChromeColorFromWebPage() {
        val js = """
            (function() {
              try {
                // 只取“页面背景色”，不要用 header/theme-color（Misskey 常为强调绿，会把系统栏染绿/黄）。
                var roots = [document.body, document.documentElement];
                for (var i = 0; i < roots.length; i++) {
                  var el = roots[i];
                  if (!el) continue;
                  var c = getComputedStyle(el).backgroundColor;
                  if (!c) continue;
                  if (c === 'rgba(0, 0, 0, 0)' || c === 'transparent') continue;
                  return c;
                }
              } catch (_) {}
              return '';
            })();
        """.trimIndent()
        webView.evaluateJavascript(js) { raw ->
            val parsed = parseJsColor(raw)
            val color = parsed ?: defaultChromeColor
            if (color == lastChromeColor) return@evaluateJavascript
            runOnUiThread { applyChromeColorNow(color) }
        }
    }

    private fun applyChromeColorNow(color: Int) {
        if (color == lastChromeColor) return
        swipeRefresh.setBackgroundColor(color)
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

    private fun installChromeColorSyncBridge() {
        val js = """
            (function() {
              if (window.__LIMINAL_CHROME_SYNC_INSTALLED__) return;
              window.__LIMINAL_CHROME_SYNC_INSTALLED__ = true;
              function pickColor() {
                try {
                  var roots = [document.body, document.documentElement];
                  for (var i = 0; i < roots.length; i++) {
                    var el = roots[i];
                    if (!el) continue;
                    var c = getComputedStyle(el).backgroundColor;
                    if (!c) continue;
                    if (c === 'rgba(0, 0, 0, 0)' || c === 'transparent') continue;
                    return c;
                  }
                } catch (_) {}
                return '';
              }
              var lastColor = '';
              var pending = 0;
              function notify() {
                try {
                  var c = pickColor();
                  if (c === lastColor) return;
                  lastColor = c;
                  if (window.AppChrome && window.AppChrome.postColor) {
                    window.AppChrome.postColor(c || '');
                  }
                } catch (_) {}
              }
              function scheduleNotify() {
                if (pending) clearTimeout(pending);
                pending = setTimeout(function() {
                  pending = 0;
                  notify();
                }, 160);
              }
              // 恢复/首次渲染期经常先拿到透明色，做 2 帧重试。
              notify();
              requestAnimationFrame(scheduleNotify);
              var mo = new MutationObserver(scheduleNotify);
              var options = {attributes:true, attributeFilter:['class', 'style', 'data-theme']};
              if (document.documentElement) mo.observe(document.documentElement, options);
              if (document.body) mo.observe(document.body, options);
              document.addEventListener('visibilitychange', function() {
                if (!document.hidden) scheduleNotify();
              }, true);
              window.addEventListener('focus', scheduleNotify, true);
              window.addEventListener('pageshow', scheduleNotify, true);
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
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

    private fun handleNavigation(uri: Uri): Boolean {
        val scheme = uri.scheme?.lowercase() ?: return false
        if (scheme in setOf("javascript", "about", "blob", "data")) return false
        if (scheme !in setOf("http", "https")) {
            openExternal(uri)
            return true
        }
        val host = uri.host?.lowercase().orEmpty()
        if (host == "liminalselves.top" || host.endsWith(".liminalselves.top")) {
            return false
        }
        // ContextThemeWrapper 给对话框一个完整的 Material 3 Theme，
        // 隔离 Activity 原有主题，避免 MaterialAlertDialogBuilder 找不到 Material 属性而崩溃。
        val dialogCtx = ContextThemeWrapper(this, R.style.Theme_Liminal_Dialog_Host)
        MaterialAlertDialogBuilder(dialogCtx)
            .setTitle("即将离开站点")
            .setMessage("当前链接不在 liminalselves.top 域内，将在系统默认浏览器中打开：\n\n${uri}")
            .setNegativeButton("取消", null)
            .setPositiveButton("在浏览器中打开") { _, _ -> openExternal(uri) }
            .show()
        return true
    }

    /**
     * 处理 window.open() 拦截到的 URL：
     * 站内域名（liminalselves.top 及其子域名）在当前 WebView 内加载；
     * 站外域名弹出确认对话框后在系统浏览器打开，避免隐式跳转。
     */
    private fun handleWindowOpenUrl(rawUrl: String) {
        val uri = Uri.parse(rawUrl)
        val host = uri.host?.lowercase().orEmpty()
        if (host == "liminalselves.top" || host.endsWith(".liminalselves.top")) {
            // 站内域名：在当前 WebView 内打开
            webView.loadUrl(rawUrl)
            return
        }
        // 站外域名：弹确认后在系统浏览器打开
        val dialogCtx = ContextThemeWrapper(this, R.style.Theme_Liminal_Dialog_Host)
        MaterialAlertDialogBuilder(dialogCtx)
            .setTitle("即将离开站点")
            .setMessage("当前链接不在 liminalselves.top 域内，将在系统默认浏览器中打开：\n\n${rawUrl}")
            .setNegativeButton("取消", null)
            .setPositiveButton("在浏览器中打开") { _, _ -> openExternal(uri) }
            .show()
    }

    private fun openExternal(uri: Uri) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE))
        } catch (_: ActivityNotFoundException) {
            // ignore
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
        swipeRefresh.isRefreshing = false
        progressBar.visibility = View.GONE
        view?.stopLoading()
        webView.visibility = View.INVISIBLE
        applySystemBarColor(defaultChromeColor)
        if (::errorOverlay.isInitialized) {
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
        swipeRefresh.isRefreshing = true
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
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
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
            Toast.makeText(this, "开始下载: $fileName", Toast.LENGTH_SHORT).show()
        }.onFailure {
            Log.w(TAG, "DownloadManager enqueue failed: ${it.message}")
            Toast.makeText(this, "下载失败: ${it.message}", Toast.LENGTH_SHORT).show()
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
                    Toast.makeText(this, "保存失败: ${it.message}", Toast.LENGTH_SHORT).show()
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
                Toast.makeText(this, "已保存到 Download/: $savedName", Toast.LENGTH_SHORT).show()
            }
        }.onFailure {
            Log.w(TAG, "Save public download failed: ${it.message}")
            mainHandler.post {
                Toast.makeText(this, "保存失败: ${it.message}", Toast.LENGTH_SHORT).show()
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
                runOnUiThread { Toast.makeText(this@NativeWebViewActivity, "下载失败: 空数据", Toast.LENGTH_SHORT).show() }
                return
            }
            ioExecutor.execute {
                runCatching {
                    val bytes = android.util.Base64.decode(data, android.util.Base64.DEFAULT)
                    saveBytesToPublicDownloads(bytes, name, mime)
                }.onFailure {
                    mainHandler.post {
                        Toast.makeText(this@NativeWebViewActivity, "保存失败: ${it.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        @JavascriptInterface
        fun onBlobError(error: String?) {
            runOnUiThread {
                Toast.makeText(this@NativeWebViewActivity, "下载失败: ${error ?: "unknown"}", Toast.LENGTH_SHORT).show()
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
     * SPA 路由变化桥：Misskey 使用 pushState 做客户端路由，
     * 当用户前进到"板块根路径"时清空 WebView 历史栈，
     * 使返回键表现为"回上级/退出"而非"倒带每一步"。
     */
    private inner class AppNavBridge {
        @JavascriptInterface
        fun onNavigateTo(path: String?) {
            val p = path?.trim().orEmpty()
            if (p.isEmpty()) return
            runOnUiThread {
                // 同步 currentUrl，避免下拉刷新/通知等场景使用滞后值
                val base = currentUrl.let {
                    val u = Uri.parse(it)
                    val scheme = u.scheme ?: "https"
                    val host = u.host ?: return@let it
                    val port = u.port
                    if (port == -1) "$scheme://$host" else "$scheme://$host:$port"
                }
                currentUrl = base + (if (p.startsWith("/")) p else "/$p")
            }
        }
    }

    /**
     * 注入 SPA 路由监听：monkey-patch history.pushState，
     * 仅在前进导航（pushState）时通知原生层，popstate（后退）不触发。
     */
    private fun installSpaNavWatcher() {
        val js = """
            (function() {
              if (window.__LIMINAL_NAV_WATCHER_INSTALLED__) return;
              window.__LIMINAL_NAV_WATCHER_INSTALLED__ = true;
              var lastPath = location.pathname;
              function notify(path) {
                try {
                  if (window.AppNav && window.AppNav.onNavigateTo) {
                    window.AppNav.onNavigateTo(path || '');
                  }
                } catch (_) {}
              }
              var origPush = history.pushState.bind(history);
              history.pushState = function(state, title, url) {
                origPush(state, title, url);
                var newPath = location.pathname;
                if (newPath !== lastPath) {
                  lastPath = newPath;
                  notify(newPath);
                }
              };
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    /** 判断路径是否为"板块根"——在这些路径上按返回键视为"退出"而非"回退"。 */
    private fun isSectionRootPath(path: String): Boolean {
        val normalized = path.trimEnd('/')
        return normalized.isEmpty() || normalized in SECTION_ROOT_PATHS
    }

    /**
     * WebSocket 状态桥：接收 JS 层上报的 Misskey WS 连接状态（open/close/error）。
     * 仅用于可观测性日志；常驻模式下保活不再依赖 WebView 的 WS 状态。
     */
    private inner class AppWsBridge {
        @JavascriptInterface
        fun onState(state: String?, url: String?) {
            val s = state.orEmpty()
            wsConnected = (s == "open")
            Log.i(TAG, "Misskey WebSocket state=$s url=${url.orEmpty()}")
        }
    }

    /**
     * 注入 WebSocket 状态监听：包装 window.WebSocket 构造函数，
     * 为 Misskey 建立的每条 WS 连接附加 open/close/error 监听并上报原生层。
     * 幂等：已安装则跳过；页面重载后 JS 上下文重置，onPageFinished 会重新注入。
     */
    private fun installWsStateWatcher() {
        val js = """
            (function() {
              if (window.__LIMINAL_WS_WATCHER_INSTALLED__) return;
              window.__LIMINAL_WS_WATCHER_INSTALLED__ = true;
              var OrigWS = window.WebSocket;
              function notify(state, url) {
                try {
                  if (window.AppWs && window.AppWs.onState) {
                    window.AppWs.onState(state, url || '');
                  }
                } catch (_) {}
              }
              function PatchedWS(url, protocols) {
                var ws = (protocols === undefined) ? new OrigWS(url) : new OrigWS(url, protocols);
                try {
                  ws.addEventListener('open', function() { notify('open', url); });
                  ws.addEventListener('close', function() { notify('close', url); });
                  ws.addEventListener('error', function() { notify('error', url); });
                } catch (_) {}
                return ws;
              }
              PatchedWS.prototype = OrigWS.prototype;
              // 保持 constructor 恒等（ws.constructor === window.WebSocket），提高包装透明度；
              // 注：无法修复 class X extends WebSocket 的子类化场景（当前 Misskey 未使用）。
              PatchedWS.prototype.constructor = PatchedWS;
              PatchedWS.CONNECTING = OrigWS.CONNECTING;
              PatchedWS.OPEN = OrigWS.OPEN;
              PatchedWS.CLOSING = OrigWS.CLOSING;
              PatchedWS.CLOSED = OrigWS.CLOSED;
              window.WebSocket = PatchedWS;
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    /**
     * 开启推送：拉起权限设置向导（逐项引导 + 返回验证），
     * 向导完成后才执行实际启用并回报 Misskey 真实状态。
     */
    private fun enableNativePushFromBridge() {
        if (pushSetupRunning) return
        pushSetupRunning = true
        pushSetupLauncher.launch(Intent(this, PermissionSetupActivity::class.java))
    }

    /** 是否正在运行权限向导，避免重复拉起。 */
    private var pushSetupRunning = false

    private val pushSetupLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            pushSetupRunning = false
            if (result.resultCode == RESULT_OK) {
                // 向导确认全部权限就绪：执行实际启用（服务端开关 + 常驻推送）
                startNativePushAndDispatch()
            } else {
                // 用户取消或中途退出：回报未启用，前端开关状态回退
                dispatchNativePushState(registered = false, errorCode = "permission_denied")
            }
        }

    private fun startNativePushAndDispatch() {
        if (!areSystemNotificationsEnabled()) {
            dispatchNativePushState(registered = false, errorCode = "permission_denied")
            return
        }
        AliyunPushStarter.start(application) { ok, _, err ->
            if (!ok) {
                // EMAS 未配置或初始化失败：跳过设备注册，仅同步服务端 enableAppPush 开关。
                // 这样"APP推送通知"开关仍可作为全局推送总开关控制 WS 保活，
                // 只是没有阿里云离线推送能力（在线时 WS 消息照常送达）。
                Log.i(TAG, "EMAS unavailable, skipping device registration; enabling server push flag only")
                updateAppPushFlagOnMisskey(true) { updated ->
                    runOnUiThread {
                        if (updated) {
                            getPrefs().edit().putBoolean(PREF_NATIVE_PUSH_ENABLED, true).apply()
                            // 开关打开：立即拉起常驻推送（原生 WS + 前台服务）
                            ensureNativePushRunning()
                            dispatchNativePushState(registered = true, errorCode = null)
                        } else {
                            dispatchNativePushState(registered = false, errorCode = "push_setup_failed")
                        }
                    }
                }
                return@start
            }

            syncEnableStateWithMisskey { synced ->
                if (synced) {
                    getPrefs().edit().putBoolean(PREF_NATIVE_PUSH_ENABLED, true).apply()
                    // 开关打开：立即拉起常驻推送（原生 WS + 前台服务）
                    runOnUiThread { ensureNativePushRunning() }
                    dispatchNativePushState(registered = true, errorCode = null)
                } else {
                    // 服务端未同步成功时回滚本地状态
                    AliyunPushStarter.stop()
                    getPrefs().edit().putBoolean(PREF_NATIVE_PUSH_ENABLED, false).apply()
                    dispatchNativePushState(registered = false, errorCode = "push_setup_failed")
                }
            }
        }
    }

    private fun disableNativePushFromBridge() {
        // 先同步服务端开关，成功后再本地停用，避免“已停用但刷新又显示启用”
        updateAppPushFlagOnMisskey(false) { ok ->
            if (!ok) {
                dispatchNativePushState(registered = true, errorCode = "push_setup_failed")
                return@updateAppPushFlagOnMisskey
            }
            AliyunPushStarter.stop()
            getPrefs().edit().putBoolean(PREF_NATIVE_PUSH_ENABLED, false).apply()
            nativePushRestoreAttempted = false
            // 开关关闭：立即停止常驻推送（原生 WS + 前台服务），避免无推送时仍耗电
            stopNativePushAll()
            dispatchNativePushState(registered = false, errorCode = null)
        }
    }

    private fun injectNativePushStateFromPrefs() {
        fetchEnableAppPushFromMisskey { serverEnabled ->
            // 不做本地兜底：状态以服务端为准；服务端不可达时视为未启用
            if (serverEnabled == null) {
                dispatchNativePushState(registered = false, errorCode = "push_setup_failed")
                return@fetchEnableAppPushFromMisskey
            }

            // 按钮状态只反映服务端 enableAppPush，避免“已启用但刷新回到启用按钮”
            // 系统通知权限只影响实际投递能力，不应反向覆盖服务端开关显示
            getPrefs().edit().putBoolean(PREF_NATIVE_PUSH_ENABLED, serverEnabled).apply()
            // 服务端同步后：对齐常驻推送状态（开启则拉起，关闭则全部停止）
            if (serverEnabled) ensureNativePushRunning() else stopNativePushAll()
            val permissionError = if (serverEnabled && !areSystemNotificationsEnabled()) "permission_denied" else null
            dispatchNativePushState(registered = serverEnabled, errorCode = permissionError)
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

    private fun dispatchNativePushAlert() {
        webView.evaluateJavascript(
            "window.dispatchEvent(new CustomEvent('$EVENT_NATIVE_PUSH_ALERT'));",
            null,
        )
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
        }
        val js = "window.__LIMINAL_APP_INFO__ = $map; window.dispatchEvent(new CustomEvent('liminal-app-info-updated'));"
        webView.evaluateJavascript(js, null)
    }

    private fun reconcileStartupNotificationPermission() {
        if (startupPermissionChecked) return
        startupPermissionChecked = true
        if (!getPrefs().getBoolean(PREF_NATIVE_PUSH_ENABLED, false)) return
        if (!areSystemNotificationsEnabled()) {
            disableNativePushFromBridge()
            dispatchNativePushAlert()
            return
        }
        if (Build.VERSION.SDK_INT < 33) return
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        pendingNotificationPermissionFromStartup = true
        requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
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
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            ensureNotificationChannels(nm)
        }

        val targetUrl = openPath?.takeIf { isSafeMisskeyOpenPath(it) }?.let { absoluteUrlForMisskeyPath(it) } ?: currentUrl

        val openIntent = Intent(this, NativeWebViewActivity::class.java).apply {
            putExtra(EXTRA_URL, targetUrl)
            putExtra(EXTRA_UA_MARKER, intent.getStringExtra(EXTRA_UA_MARKER).orEmpty())
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

    /**
     * 确保通知渠道就绪（幂等）：私信渠道 HIGH（弹窗+声音+震动）、互动渠道 LOW（静默）。
     * 注意：渠道创建后重要性只能被用户降、不能被代码升，降级场景由权限向导页引导。
     */
    private fun ensureNotificationChannels(nm: NotificationManager) {
        ensureMisskeyChannels(this)
    }

    private fun onPageReadyForPushRestore() {
        // 页面加载完成（含登录/切换账号后）：对齐常驻推送状态。
        // 每次页面就绪都检查，以覆盖"开启开关时未登录→登录后自动拉起"的边界；
        // ensureNativePushRunning 幂等，重复调用无副作用。
        if (!getPrefs().getBoolean(PREF_NATIVE_PUSH_ENABLED, false)) return
        nativePushRestoreAttempted = true
        ensureNativePushRunning()
    }

    private fun registerMobilePushWithMisskey(deviceId: String, cb: (Boolean) -> Unit) {
        val origin = getMisskeyOrigin()
        readMisskeyToken { token ->
            if (token.isNullOrBlank()) {
                cb(false)
                return@readMisskeyToken
            }
            ioExecutor.execute {
                val result = callMisskeyPushApi(
                    "$origin/api/mobile-push/register",
                    token,
                    JSONObject().apply {
                        put("i", token)
                        put("deviceId", deviceId)
                        put("platform", "android")
                    },
                )
                mainHandler.post {
                    if (!isFinishing && !isDestroyed) cb(result.ok)
                }
            }
        }
    }

    private fun updateAppPushFlagOnMisskey(enabled: Boolean, cb: (Boolean) -> Unit) {
        val origin = getMisskeyOrigin()
        readMisskeyToken { token ->
            if (token.isNullOrBlank()) {
                cb(false)
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
                    if (!isFinishing && !isDestroyed) cb(result.ok)
                }
            }
        }
    }

    private fun syncEnableStateWithMisskey(cb: (Boolean) -> Unit) {
        val deviceId = AliyunPushHolder.deviceId
        if (deviceId.isNullOrBlank()) {
            cb(false)
            return
        }
        // 先注册设备，再启用服务端开关，避免出现“服务端已开启但设备未注册”导致离线推送失效
        registerMobilePushWithMisskey(deviceId) { registered ->
            if (!registered) {
                cb(false)
                return@registerMobilePushWithMisskey
            }
            updateAppPushFlagOnMisskey(true) { updated ->
                cb(updated)
            }
        }
    }

    private fun unregisterMobilePushWithMisskey(deviceId: String?) {
        if (deviceId.isNullOrBlank()) return
        val origin = Uri.parse(intent.getStringExtra(EXTRA_URL) ?: defaultUrl).let {
            "${it.scheme}://${it.host}${if (it.port > 0) ":${it.port}" else ""}"
        }
        readMisskeyToken { token ->
            if (token.isNullOrBlank()) return@readMisskeyToken
            ioExecutor.execute {
                callMisskeyPushApi(
                    "$origin/api/mobile-push/unregister",
                    token,
                    JSONObject().apply {
                        put("i", token)
                        put("deviceId", deviceId)
                    },
                )
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

    private fun fetchEnableAppPushFromMisskey(cb: (Boolean?) -> Unit) {
        val origin = getMisskeyOrigin()
        readMisskeyToken { token ->
            if (token.isNullOrBlank()) {
                cb(null)
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
                        if (!isFinishing && !isDestroyed) cb(null)
                    }
                    return@execute
                }
                val enabled = runCatching {
                    JSONObject(result.body).optBoolean("enableAppPush", false)
                }.getOrNull()
                mainHandler.post {
                    if (!isFinishing && !isDestroyed) cb(enabled)
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

    override fun onPause() {
        mainHandler.removeCallbacks(refreshChromeColorRunnable)
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
        // 先用缓存色立即压回去，再合并恢复/焦点事件后统一从网页校正。
        applySystemBarColor(lastChromeColor)
        scheduleChromeColorRefresh(180)
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
            if (!nativeWsManager.isRunning()) {
                Log.i(TAG, "Starting resident native WS")
                nativeWsManager.start(token, getMisskeyOrigin())
            }
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
            scheduleChromeColorRefresh(180)
        }
    }

    private fun scheduleChromeColorRefresh(delayMs: Long) {
        mainHandler.removeCallbacks(refreshChromeColorRunnable)
        mainHandler.postDelayed(refreshChromeColorRunnable, delayMs)
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(refreshChromeColorRunnable)
        mainHandler.removeCallbacks(applyPendingChromeColorRunnable)
        // 常驻模式：仅 Activity finish（用户退出 App）时停止推送；
        // 系统回收重建场景不停止，交由 onPause(isFinishing) 处理。
        if (isFinishing) {
            stopNativePushAll()
        }
        runCatching { unregisterReceiver(downloadCompleteReceiver) }
        filePathCallback?.onReceiveValue(null)
        filePathCallback = null
        webView.removeJavascriptInterface("AppNativePush")
        webView.removeJavascriptInterface("AppDownload")
        webView.removeJavascriptInterface("AppWs")
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
        private const val TAG = "NativeWebViewActivity"

        /** 两次返回间隔不超过该值则退出 Activity（与 [Snackbar.LENGTH_SHORT] 接近）。 */
        private const val EXIT_CONFIRM_MS = 2000L

        /** 私信消息渠道：高优先级（弹窗+声音+震动）。供权限向导页共用。 */
        const val CHANNEL_MISSKEY_CHAT = "misskey_chat_v2"

        /** 互动通知渠道：低优先级（仅通知栏，不弹窗不震动）。供权限向导页共用。 */
        const val CHANNEL_MISSKEY_NOTIFY = "misskey_notify_v2"

        /** 确保私信渠道存在（幂等），供 [PermissionSetupActivity] 向导页共用。 */
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

        /** 确保互动通知渠道存在（幂等），供 [PermissionSetupActivity] 向导页共用。 */
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

        /** 确保 Misskey 相关渠道全部就绪（遗留清理 + 私信 + 互动），供向导页共用。 */
        fun ensureMisskeyChannels(context: Context) {
            cleanupLegacyChannels(context)
            ensureChatChannel(context)
            ensureNotifyChannel(context)
        }

        /** 缓存的 Misskey token：供 WebView 暂停后原生 WS 启动兜底使用。 */
        private const val PREF_LAST_MISSKEY_TOKEN = "liminal_last_misskey_token"

        /** 与 Flutter WebView [MisskeyWebShell] 外壳层提示一致。 */
        private const val SNACK_EXIT_BACK_HINT = "再次点击返回键退出应用"

        /**
         * 板块根路径集合：用户前进导航到达这些路径时清空 WebView 历史栈，
         * 使返回键表现为"回上级/退出"而非逐页倒带。
         * 路径格式：不含尾部斜杠，如 "/explore"。
         * "/" (首页) 通过 trimEnd('/') 后为空串单独判断，无需列入。
         */
        private val SECTION_ROOT_PATHS = setOf(
            "/explore",
            "/notifications",
            "/messages",
        )
    }
}
