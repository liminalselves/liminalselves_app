package top.liminalselves.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.res.ColorStateList
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceError
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

    /** 无 WebView 历史可退时：首次返回仅提示，短时内第二次返回才 [finish]。 */
    private var lastExitBackPressElapsed = 0L
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        overridePendingTransition(0, 0)
        setupSystemBars()
        applyWindowRefreshPresentationHints()
        applyPersistentSystemBars()
        setupViews()
        setupWebView()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) {
                    lastExitBackPressElapsed = 0L
                    webView.goBack()
                    return
                }
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
        val url = resolveLaunchUrl(intent)
        currentUrl = url
        loadUrl(url)
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
                reconcileStartupNotificationPermission()
                injectNativePushStateFromPrefs()
                onPageReadyForPushRestore()
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
                retryFromErrorPage()
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
     * 仅在“手势起点位于顶部热区”且“WebView 已在顶端”时允许触发刷新。
     * 在父容器 onIntercept 阶段判断，规避 dispatchTouchEvent 与 SwipeRefreshLayout 的时序竞争。
     */
    private class TopEdgeSwipeRefreshLayout(context: Context) : SwipeRefreshLayout(context) {
        var hotZonePx: Int = 0
        var statusBarInsetProvider: (() -> Int)? = null
        var canChildScrollUpProvider: (() -> Boolean)? = null
        private var gestureEligible = false

        override fun onInterceptTouchEvent(ev: android.view.MotionEvent): Boolean {
            when (ev.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    val statusTop = statusBarInsetProvider?.invoke() ?: 0
                    val inHotZone = ev.y <= (statusTop + hotZonePx)
                    val canScrollUp = canChildScrollUpProvider?.invoke() ?: true
                    gestureEligible = inHotZone && !canScrollUp
                }
                android.view.MotionEvent.ACTION_UP,
                android.view.MotionEvent.ACTION_CANCEL -> {
                    gestureEligible = false
                }
            }
            return gestureEligible && super.onInterceptTouchEvent(ev)
        }

        override fun onTouchEvent(ev: android.view.MotionEvent): Boolean {
            if (!gestureEligible && !isRefreshing) return false
            if (ev.actionMasked == android.view.MotionEvent.ACTION_UP ||
                ev.actionMasked == android.view.MotionEvent.ACTION_CANCEL
            ) {
                gestureEligible = false
            }
            return super.onTouchEvent(ev)
        }
    }

    private fun recreateWebViewAndReload() {
        setupViews()
        setupWebView()
        loadUrl(currentUrl)
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

    private inner class AppNativePushBridge {
        @JavascriptInterface
        fun postMessage(msg: String?) {
            runOnUiThread {
                when (msg?.trim()) {
                    "enable" -> enableNativePushFromBridge()
                    "disable" -> disableNativePushFromBridge()
                    "query" -> injectNativePushStateFromPrefs()
                    else -> {
                        val payload = runCatching { JSONObject(msg ?: "") }.getOrNull()
                        if (payload?.optString("action") == "notify") {
                            val title = payload.optString("title", "Misskey")
                            val body = payload.optString("body", "")
                            val openPath = payload.optString("openPath", "").trim().takeIf { it.isNotEmpty() }
                            showSystemNotification(title, body, openPath)
                        }
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

    private fun enableNativePushFromBridge() {
        if (!areSystemNotificationsEnabled()) {
            dispatchNativePushState(registered = false, errorCode = "permission_denied")
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        startNativePushAndDispatch()
    }

    private fun startNativePushAndDispatch() {
        if (!areSystemNotificationsEnabled()) {
            dispatchNativePushState(registered = false, errorCode = "permission_denied")
            return
        }
        AliyunPushStarter.start(application) { ok, _, err ->
            if (!ok) {
                runOnUiThread {
                    Log.w(TAG, "native push setup failed: ${err ?: "unknown"}")
                    dispatchNativePushState(registered = false, errorCode = "push_setup_failed")
                }
                return@start
            }

            syncEnableStateWithMisskey { synced ->
                if (synced) {
                    getPrefs().edit().putBoolean(PREF_NATIVE_PUSH_ENABLED, true).apply()
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
        if (!getPrefs().getBoolean(PREF_NATIVE_PUSH_ENABLED, false)) return
        if (!areSystemNotificationsEnabled()) return
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return

        val channelId = "misskey_ws_live"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(channelId) == null) {
                val channel = NotificationChannel(
                    channelId,
                    "Misskey 实时通知",
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    description = "App 在线时通过 WebSocket 转发的实时通知"
                }
                nm.createNotificationChannel(channel)
            }
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
        val notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title.take(64))
            .setContentText(contentText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(contentText))
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        NotificationManagerCompat.from(this).notify(notificationId, notification)
    }

    private fun onPageReadyForPushRestore() {
        if (nativePushRestoreAttempted) return
        if (!getPrefs().getBoolean(PREF_NATIVE_PUSH_ENABLED, false)) return
        nativePushRestoreAttempted = true
        enableNativePushFromBridge()
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
        webView.onPause()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        applyPersistentSystemBars()
        webView.onResume()
        // 先用缓存色立即压回去，再合并恢复/焦点事件后统一从网页校正。
        applySystemBarColor(lastChromeColor)
        scheduleChromeColorRefresh(180)
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
        filePathCallback?.onReceiveValue(null)
        filePathCallback = null
        webView.removeJavascriptInterface("AppNativePush")
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

        /** 与 Flutter WebView [MisskeyWebShell] 外壳层提示一致。 */
        private const val SNACK_EXIT_BACK_HINT = "再次点击返回键退出应用"
    }
}
