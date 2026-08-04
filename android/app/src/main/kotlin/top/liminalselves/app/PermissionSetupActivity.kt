package top.liminalselves.app

import android.Manifest
import android.animation.ValueAnimator
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.NotificationManagerCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

/**
 * 推送权限设置向导：逐项引导用户开启推送所需权限，每步从系统页返回后自动验证。
 *
 * Android 通知权限是分层的：总开关之外，每个通知类别（渠道）可单独关闭。
 * 因此向导按顺序检查全部 5 项：
 * 1. 通知总权限（Android 13+ 运行时申请，拒绝则引导到设置页）
 * 2. 电池优化豁免（保障常驻 WS 不被 Doze 断网）
 * 3. 私信消息渠道：需开启且重要性 HIGH（对应 MIUI"悬浮通知"，私信弹窗依赖）
 * 4. 互动通知渠道：需开启（提及/回复/反应等静默通知）
 * 5. 保活服务渠道：需开启（前台服务常驻通知，部分 ROM 关闭渠道会影响保活）
 *
 * 每一项：展示说明 → 用户点按钮 → 跳转系统页面 → 返回 App 后自动验证，
 * 未开启则停留当前步骤提示重试；全部就绪后返回 RESULT_OK。
 */
class PermissionSetupActivity : ComponentActivity() {

    companion object {
        private const val STEP_NOTIFICATION = 0
        private const val STEP_BATTERY = 1
        private const val STEP_CHANNEL_CHAT = 2
        private const val STEP_CHANNEL_NOTIFY = 3
        private const val STEP_CHANNEL_KEEPALIVE = 4
        private const val TOTAL_STEPS = 5

        // 与 NativeWebViewActivity 壳层一致的配色
        private const val BG_COLOR = "#F7F9F5"
        private const val ACCENT = "#1A73E8"
        private const val ACCENT_DARK = "#1557B0"
        private const val TITLE_COLOR = "#162033"
        private const val BODY_COLOR = "#637083"
        private const val PROGRESS_TRACK = "#E6EBF0"
        private const val SECONDARY_BG = "#EAF2FF"
        private const val ERROR_COLOR = "#D93025"
    }

    private lateinit var stepBadge: TextView
    private lateinit var progressTrack: FrameLayout
    private lateinit var progressFill: View
    private lateinit var contentBlock: LinearLayout
    private lateinit var stepTitle: TextView
    private lateinit var stepDesc: TextView
    private lateinit var hintText: TextView
    private lateinit var goButton: Button

    private var currentStep = -1
    private var firstRender = true
    /** 已发起跳转、等待从系统设置页返回（点击按钮时置位）。 */
    private var returnedFromSettings = false
    /** 是否真的离开过本页面（onPause 时置位）：防止跳转转场瞬间的伪 onResume 提前消费检测。 */
    private var leftForSettings = false
    /** 通知运行时权限是否已尝试过弹窗（被拒后改为引导设置页）。 */
    private var notifRuntimeRequested = false

    private val mainHandler = Handler(Looper.getMainLooper())
    private var progressAnimator: ValueAnimator? = null

    /**
     * 返回验证任务：延迟执行，给系统提交设置页变更的缓冲时间，
     * 避免部分 ROM 异步生效导致刚返回时误判为“未开启”。
     */
    private val resumeCheckRunnable = Runnable {
        if (isFinishing || isDestroyed) return@Runnable
        if (currentStep >= 0 && isStepSatisfied(currentStep)) {
            advance()
        } else if (currentStep >= 0) {
            showHint("还没检测到开启，请再点一次")
        }
    }

    private val notifPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                advance()
            } else {
                // 运行时弹窗被拒（含"不再询问"）：引导到系统设置页手动开启
                showHint("请在打开的页面里允许通知")
                openNotificationSettings()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 系统栏配色由专用主题 Theme.Liminal.Wizard 声明（透明栏 + 浅色图标），
        // 此处仅确保 edge-to-edge 下图标外观为深色；窗口内边距由 buildUi 中的 insets 监听消费。
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
        // 先确保全部渠道已创建，后续才能检查各渠道的开关状态
        NativeWebViewActivity.ensureMisskeyChannels(this)
        KeepAliveService.ensureChannel(this)
        setContentView(buildUi())
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                cancelSetup()
            }
        })
        advance()
    }

    override fun onPause() {
        super.onPause()
        // 记录“真的离开过”：只有离开过再返回才验证，
        // 避免跳转转场瞬间的伪 onResume 在用户还没操作前就提前检测。
        if (returnedFromSettings) leftForSettings = true
    }

    override fun onResume() {
        super.onResume()
        // 必须同时满足：已发起跳转 + 真的离开过再返回；否则是转场伪 resume，继续等待
        if (!returnedFromSettings || !leftForSettings) return
        returnedFromSettings = false
        leftForSettings = false
        // 从设置页返回：延迟验证，给系统提交设置变更留缓冲时间
        mainHandler.removeCallbacks(resumeCheckRunnable)
        mainHandler.postDelayed(resumeCheckRunnable, 600)
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(resumeCheckRunnable)
        progressAnimator?.cancel()
        super.onDestroy()
    }

    // ─── 步骤检查 ──────────────────────────────────────────────────────────

    /** 推进到下一个未满足的步骤；全部满足则返回成功。 */
    private fun advance() {
        currentStep = nextUnsatisfiedStep()
        if (currentStep == -1) {
            setResult(RESULT_OK)
            finish()
            return
        }
        render(currentStep)
    }

    private fun nextUnsatisfiedStep(): Int {
        for (step in 0 until TOTAL_STEPS) {
            if (!isStepSatisfied(step)) return step
        }
        return -1
    }

    private fun isStepSatisfied(step: Int): Boolean {
        return when (step) {
            STEP_NOTIFICATION -> NotificationManagerCompat.from(this).areNotificationsEnabled()
            STEP_BATTERY -> {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                pm.isIgnoringBatteryOptimizations(packageName)
            }
            // 私信需 HIGH（MIUI“悬浮通知”开启后 importance 会升为 HIGH）；渠道被用户关闭时 importance = NONE
            STEP_CHANNEL_CHAT -> channelImportance(NativeWebViewActivity.CHANNEL_MISSKEY_CHAT) >=
                NotificationManager.IMPORTANCE_HIGH
            STEP_CHANNEL_NOTIFY -> channelImportance(NativeWebViewActivity.CHANNEL_MISSKEY_NOTIFY) >
                NotificationManager.IMPORTANCE_NONE
            STEP_CHANNEL_KEEPALIVE -> channelImportance(KeepAliveService.CHANNEL_ID) >
                NotificationManager.IMPORTANCE_NONE
            else -> true
        }
    }

    private fun channelImportance(channelId: String): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return NotificationManager.IMPORTANCE_DEFAULT
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        return nm.getNotificationChannel(channelId)?.importance ?: NotificationManager.IMPORTANCE_NONE
    }

    // ─── 渲染 ──────────────────────────────────────────────────────────────

    /** 每步的标题 / 说明 / 按钮文案（精简、口语化）。 */
    private fun stepContent(step: Int): Triple<String, String, String> = when (step) {
        STEP_NOTIFICATION -> Triple(
            "允许通知",
            "开启后才能收到私信和互动提醒。",
            "开启通知",
        )
        STEP_BATTERY -> Triple(
            "允许后台运行",
            "锁屏或切到后台时也能持续收消息。",
            "允许后台运行",
        )
        STEP_CHANNEL_CHAT -> Triple(
            "私信弹窗提醒",
            "在「私信消息」里打开悬浮通知，私信会弹窗提醒。",
            "前往设置",
        )
        STEP_CHANNEL_NOTIFY -> Triple(
            "互动通知",
            "在「互动通知」里允许通知，收到提及、回复等提醒。",
            "前往设置",
        )
        else -> Triple(
            "保持后台连接",
            "在「保活服务」里允许通知，该通知已最小化，不打扰你。",
            "前往设置",
        )
    }

    private fun render(step: Int) {
        hintText.visibility = View.GONE
        animateProgress(step)
        val (title, desc, btn) = stepContent(step)
        val badge = "${step + 1}"

        if (firstRender) {
            firstRender = false
            stepBadge.text = badge
            stepTitle.text = title
            stepDesc.text = desc
            goButton.text = btn
            // 入场动画：淡入 + 轻微上移
            contentBlock.alpha = 0f
            contentBlock.translationY = dp(18).toFloat()
            contentBlock.animate()
                .alpha(1f).translationY(0f)
                .setDuration(320)
                .setInterpolator(DecelerateInterpolator())
                .start()
            return
        }

        // 步骤切换：先淡出，替换文案后淡入 + 上移，形成前进感
        contentBlock.animate().cancel()
        contentBlock.animate()
            .alpha(0f).translationY(dp(10).toFloat())
            .setDuration(110)
            .withEndAction {
                if (isFinishing || isDestroyed) return@withEndAction
                stepBadge.text = badge
                stepTitle.text = title
                stepDesc.text = desc
                goButton.text = btn
                contentBlock.translationY = dp(14).toFloat()
                contentBlock.animate()
                    .alpha(1f).translationY(0f)
                    .setDuration(240)
                    .setInterpolator(DecelerateInterpolator())
                    .start()
            }
            .start()
    }

    /** 进度条填充宽度平滑过渡到 (step+1)/TOTAL。 */
    private fun animateProgress(step: Int) {
        progressTrack.post {
            val trackWidth = progressTrack.width
            if (trackWidth <= 0) return@post
            val targetWidth = (trackWidth * (step + 1).toFloat() / TOTAL_STEPS).toInt()
            val startWidth = progressFill.layoutParams.width.coerceAtLeast(0)
            progressAnimator?.cancel()
            progressAnimator = ValueAnimator.ofInt(startWidth, targetWidth).apply {
                duration = 320
                interpolator = DecelerateInterpolator()
                addUpdateListener { anim ->
                    val w = anim.animatedValue as Int
                    progressFill.layoutParams = progressFill.layoutParams.apply { width = w }
                }
                start()
            }
        }
    }

    // ─── 各步骤跳转 ────────────────────────────────────────────────────────

    private fun onGoClick() {
        // 取消可能挂起的返回验证，避免与新的跳转流程竞争
        mainHandler.removeCallbacks(resumeCheckRunnable)
        when (currentStep) {
            STEP_NOTIFICATION -> requestNotificationPermission()
            STEP_BATTERY -> requestBatteryExemption()
            STEP_CHANNEL_CHAT -> openChannelSettings(NativeWebViewActivity.CHANNEL_MISSKEY_CHAT)
            STEP_CHANNEL_NOTIFY -> openChannelSettings(NativeWebViewActivity.CHANNEL_MISSKEY_NOTIFY)
            STEP_CHANNEL_KEEPALIVE -> openChannelSettings(KeepAliveService.CHANNEL_ID)
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && !notifRuntimeRequested) {
            notifRuntimeRequested = true
            notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        // 已尝试过运行时弹窗（或低版本系统）：引导设置页
        openNotificationSettings()
    }

    private fun openNotificationSettings() {
        returnedFromSettings = true
        runCatching {
            startActivity(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, packageName),
            )
        }.onFailure {
            runCatching {
                startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                        .setData(Uri.parse("package:$packageName")),
                )
            }.onFailure {
                Toast.makeText(this, "无法打开通知设置页，请手动在系统设置中开启", Toast.LENGTH_LONG).show()
                returnedFromSettings = false
            }
        }
    }

    private fun requestBatteryExemption() {
        returnedFromSettings = true
        runCatching {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.parse("package:$packageName")),
            )
        }.onFailure {
            // 部分设备不支持直接弹豁免框：降级到电池优化列表页（字面量 action，兼容全部 API）
            runCatching {
                startActivity(Intent("android.settings.IGNORE_BATTERY_OPTIMIZATIONS_SETTINGS"))
            }.onFailure {
                Toast.makeText(this, "无法打开电池设置页，请手动将本应用设为\"无限制\"", Toast.LENGTH_LONG).show()
                returnedFromSettings = false
            }
        }
    }

    /** 打开指定通知类别（渠道）的设置页；不支持时降级到应用通知总设置页。 */
    private fun openChannelSettings(channelId: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            advance()
            return
        }
        returnedFromSettings = true
        runCatching {
            startActivity(
                Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                    .putExtra(Settings.EXTRA_CHANNEL_ID, channelId),
            )
        }.onFailure {
            runCatching {
                startActivity(
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, packageName),
                )
            }.onFailure {
                Toast.makeText(this, "无法打开通知类别设置页", Toast.LENGTH_LONG).show()
                returnedFromSettings = false
            }
        }
    }

    private fun cancelSetup() {
        setResult(RESULT_CANCELED)
        finish()
    }

    private fun showHint(msg: String) {
        hintText.text = msg
        hintText.visibility = View.VISIBLE
    }

    // ─── UI ────────────────────────────────────────────────────────────────

    private fun buildUi(): FrameLayout {
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor(BG_COLOR))
        }
        // 系统栏适配：消费状态栏/导航栏 insets 作为内边距，避免内容被遮挡（edge-to-edge）
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
        ViewCompat.requestApplyInsets(root)

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(20), dp(24), dp(20))
        }

        // 顶部：区块标签
        content.addView(
            TextView(this).apply {
                text = "开启推送"
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.parseColor(BODY_COLOR))
            },
            LinearLayout.LayoutParams(-2, -2),
        )

        // 进度条：圆角轨道 + 动画填充
        progressTrack = FrameLayout(this).apply {
            background = roundedRect(Color.parseColor(PROGRESS_TRACK), dp(6), Color.TRANSPARENT, 0)
        }
        progressFill = View(this).apply {
            background = roundedRect(Color.parseColor(ACCENT), dp(6), Color.TRANSPARENT, 0)
        }
        progressTrack.addView(
            progressFill,
            FrameLayout.LayoutParams(0, -1, Gravity.START),
        )
        content.addView(
            progressTrack,
            LinearLayout.LayoutParams(-1, dp(6)).apply { topMargin = dp(14) },
        )

        // 中部内容块（步骤徽章 + 标题 + 说明 + 提示），步骤切换时做动画
        contentBlock = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        stepBadge = TextView(this).apply {
            text = "1"
            gravity = Gravity.CENTER
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor(ACCENT))
            background = roundedRect(Color.parseColor(SECONDARY_BG), dp(18), Color.TRANSPARENT, 0)
        }
        contentBlock.addView(stepBadge, LinearLayout.LayoutParams(dp(60), dp(60)))
        stepTitle = TextView(this).apply {
            gravity = Gravity.CENTER
            textSize = 24f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor(TITLE_COLOR))
        }
        contentBlock.addView(stepTitle, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(22) })
        stepDesc = TextView(this).apply {
            gravity = Gravity.CENTER
            textSize = 15f
            setTextColor(Color.parseColor(BODY_COLOR))
            setLineSpacing(0f, 1.3f)
        }
        contentBlock.addView(stepDesc, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        hintText = TextView(this).apply {
            gravity = Gravity.CENTER
            textSize = 13f
            setTextColor(Color.parseColor(ERROR_COLOR))
            setLineSpacing(0f, 1.2f)
            visibility = View.GONE
        }
        contentBlock.addView(hintText, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })

        // 上部留白
        content.addView(LinearLayout(this), LinearLayout.LayoutParams(-1, 0, 1f))
        content.addView(contentBlock, LinearLayout.LayoutParams(-1, -2))
        // 下部留白（让内容偏中上，按钮沉底）
        content.addView(LinearLayout(this), LinearLayout.LayoutParams(-1, 0, 1.2f))

        // 底部按钮：取消（幽灵）+ 主操作（实心，带涟漪）
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        actions.addView(
            makeWizardButton("取消", primary = false) { cancelSetup() },
            LinearLayout.LayoutParams(0, dp(50), 1f).apply { marginEnd = dp(10) },
        )
        goButton = makeWizardButton("开启通知", primary = true) { onGoClick() }
        actions.addView(
            goButton,
            LinearLayout.LayoutParams(0, dp(50), 2f),
        )
        content.addView(actions, LinearLayout.LayoutParams(-1, -2))

        root.addView(content, FrameLayout.LayoutParams(-1, -1))
        return root
    }

    private fun makeWizardButton(label: String, primary: Boolean, onClick: () -> Unit): Button {
        val bg = if (primary) {
            roundedRect(Color.parseColor(ACCENT), dp(12), Color.parseColor(ACCENT), 0)
        } else {
            roundedRect(Color.parseColor(SECONDARY_BG), dp(12), Color.TRANSPARENT, 0)
        }
        val rippleColor = if (primary) Color.parseColor(ACCENT_DARK) else Color.parseColor(ACCENT)
        return Button(this).apply {
            text = label
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            isAllCaps = false
            setTextColor(if (primary) Color.WHITE else Color.parseColor(ACCENT_DARK))
            background = RippleDrawable(ColorStateList.valueOf(rippleColor), bg, null)
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

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt().coerceAtLeast(1)
}
