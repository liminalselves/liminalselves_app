package top.liminalselves.app

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.view.WindowInsetsControllerCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Android 的轻量启动门控。
 *
 * Android 主界面使用原生 WebView，因此无需先创建 FlutterEngine。这里仅检查客户端版本，
 * 随后直接进入 [NativeWebViewActivity]。
 */
class MainActivity : ComponentActivity() {
    private data class UpdatePolicy(
        val forceUpdate: Boolean,
        val currentVersion: String,
        val minVersion: String,
        val latestVersion: String,
        val downloadUrl: String?,
        val changeLog: String,
    )

    private val ioExecutor = Executors.newSingleThreadExecutor()
    private var destinationOpened = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        overridePendingTransition(0, 0)
        showLoadingView()
        checkVersionAndContinue()
    }

    private fun showLoadingView() {
        window.statusBarColor = Color.WHITE
        window.navigationBarColor = Color.WHITE
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.WHITE)
        }
        val loadingIndicator = ProgressBar(
            this,
            null,
            android.R.attr.progressBarStyle,
        ).apply {
            indeterminateTintList = ColorStateList.valueOf(APP_ACCENT_COLOR)
        }
        root.addView(
            loadingIndicator,
            FrameLayout.LayoutParams(dp(36), dp(36), Gravity.CENTER),
        )
        setContentView(root)
    }

    private fun checkVersionAndContinue() {
        val prefs = getSharedPreferences(PREFS_UPDATE_GATE, Context.MODE_PRIVATE)
        val cachedJson = prefs.getString(KEY_META_JSON, null)

        // stale-while-revalidate：有缓存时立即放行（保证启动速度），同时后台刷新缓存供下次启动使用；
        // 无缓存（首次启动）才同步等待网络请求。这样既避免每次冷启动都阻塞于网络，
        // 又能让版本更新提示在下一次启动时及时生效（而非旧 3 小时缓存的长时间滞后）。
        if (!cachedJson.isNullOrBlank()) {
            applyUpdatePolicy(cachedJson)
            refreshMetaInBackground(prefs)
            return
        }

        ioExecutor.execute {
            val freshJson = fetchMeta()
            if (freshJson != null) {
                prefs.edit()
                    .putString(KEY_META_JSON, freshJson)
                    .putLong(KEY_META_SAVED_AT, System.currentTimeMillis())
                    .apply()
            }
            val usableJson = freshJson ?: cachedJson
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (usableJson.isNullOrBlank()) {
                    launchNativeWebView()
                } else {
                    applyUpdatePolicy(usableJson)
                }
            }
        }
    }

    /** 后台刷新 meta 缓存（不阻塞当前启动），供下一次启动时及时获得最新版本信息。 */
    private fun refreshMetaInBackground(prefs: android.content.SharedPreferences) {
        ioExecutor.execute {
            val freshJson = fetchMeta()
            if (freshJson != null) {
                prefs.edit()
                    .putString(KEY_META_JSON, freshJson)
                    .putLong(KEY_META_SAVED_AT, System.currentTimeMillis())
                    .apply()
            }
        }
    }

    private fun fetchMeta(): String? {
        val endpoint = Uri.parse(BuildConfig.MISSKEY_URL).buildUpon()
            .appendPath("api")
            .appendPath("meta")
            .build()
            .toString()
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = META_CONNECT_TIMEOUT_MS
                readTimeout = META_READ_TIMEOUT_MS
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "application/json")
            }
            connection.outputStream.use {
                it.write("""{"detail":false}""".toByteArray(Charsets.UTF_8))
            }
            if (connection.responseCode !in 200..299) return null
            connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (_: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }

    private fun applyUpdatePolicy(metaJson: String) {
        val policy = parseUpdatePolicy(metaJson)
        when {
            policy == null -> launchNativeWebView()
            policy.forceUpdate -> showForceUpdateDialog(policy)
            compareVersions(policy.latestVersion, policy.currentVersion) > 0 ->
                showOptionalUpdateDialog(policy)
            else -> launchNativeWebView()
        }
    }

    private fun parseUpdatePolicy(metaJson: String): UpdatePolicy? {
        return runCatching {
            val nativeInfo = JSONObject(metaJson).optJSONObject("nativeClientAppInfo")
                ?: return@runCatching null
            val current = packageManager.getPackageInfo(packageName, 0)
                .versionName
                .orEmpty()
                .substringBefore('+')
                .ifBlank { "0.0.0" }
            val minimum = nativeInfo.optString("minRequiredAppVersion", "").trim()
            val latest = nativeInfo.optString("latestAndroidVersion", "").trim()
            val download = nativeInfo.optString("androidDownloadUrl", "")
                .trim()
                .takeIf { it.isNotEmpty() }
            UpdatePolicy(
                forceUpdate = minimum.isNotEmpty() && compareVersions(current, minimum) < 0,
                currentVersion = current,
                minVersion = minimum,
                latestVersion = latest,
                downloadUrl = download,
                changeLog = formatChangeLog(
                    nativeInfo.optJSONArray("changelog"),
                    current,
                    latest,
                ),
            )
        }.getOrNull()
    }

    private fun formatChangeLog(entries: JSONArray?, current: String, latest: String): String {
        if (entries == null) return "本次未配置更新内容。"
        val selected = mutableListOf<Pair<String, String>>()
        for (index in 0 until entries.length()) {
            val item = entries.optJSONObject(index) ?: continue
            val version = item.optString("version", "").trim()
            if (version.isEmpty()) continue
            if (compareVersions(version, current) <= 0) continue
            if (latest.isNotEmpty() && compareVersions(version, latest) > 0) continue
            selected += version to item.optString("content", "").trim()
        }
        selected.sortWith { left, right -> compareVersions(right.first, left.first) }
        if (selected.isEmpty()) return "本次未配置更新内容。"
        return selected.joinToString("\n\n") { (version, content) ->
            if (content.isEmpty()) version else "$version\n$content"
        }
    }

    private fun compareVersions(left: String, right: String): Int {
        val a = left.substringBefore('+').substringBefore('-').split('.')
        val b = right.substringBefore('+').substringBefore('-').split('.')
        val count = maxOf(a.size, b.size, 3)
        for (index in 0 until count) {
            val av = a.getOrNull(index)?.takeWhile(Char::isDigit)?.toIntOrNull() ?: 0
            val bv = b.getOrNull(index)?.takeWhile(Char::isDigit)?.toIntOrNull() ?: 0
            if (av != bv) return av.compareTo(bv)
        }
        return 0
    }

    private fun showOptionalUpdateDialog(policy: UpdatePolicy) {
        val context = ContextThemeWrapper(this, R.style.Theme_Liminal_Dialog_Host)
        val message = buildString {
            append("服务器已发布较新的客户端版本，建议更新以获得最佳体验。\n\n")
            append("最新：${policy.latestVersion}\n")
            append("当前：${policy.currentVersion}\n\n")
            append(policy.changeLog)
        }
        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle("发现新版本")
            .setMessage(message)
            .setNegativeButton("稍后") { _, _ -> launchNativeWebView() }
            .setPositiveButton("前往更新", null)
            .setOnCancelListener { launchNativeWebView() }
            .create()
        dialog.setOnShowListener {
            val updateButton = dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)
            updateButton.isEnabled = !policy.downloadUrl.isNullOrBlank()
            updateButton.setOnClickListener {
                policy.downloadUrl?.let(::openUpdateUrl)
            }
        }
        dialog.show()
    }

    private fun showForceUpdateDialog(policy: UpdatePolicy) {
        val context = ContextThemeWrapper(this, R.style.Theme_Liminal_Dialog_Host)
        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle("需要更新应用")
            .setMessage(
                "当前版本过低，无法继续使用。\n\n" +
                    "当前版本：${policy.currentVersion}\n" +
                    "最低要求：${policy.minVersion}",
            )
            .setNegativeButton("退出应用") { _, _ -> finishAffinity() }
            .setPositiveButton("获取更新", null)
            .setCancelable(false)
            .create()
        dialog.setOnShowListener {
            val updateButton = dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)
            updateButton.isEnabled = !policy.downloadUrl.isNullOrBlank()
            updateButton.setOnClickListener {
                policy.downloadUrl?.let(::openUpdateUrl)
            }
        }
        dialog.show()
    }

    private fun openUpdateUrl(rawUrl: String) {
        val uri = Uri.parse(rawUrl)
        if (uri.scheme !in setOf("http", "https")) {
            Toast.makeText(this, "更新链接无效", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            startActivity(
                Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE),
            )
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, "无法在浏览器中打开更新链接", Toast.LENGTH_SHORT).show()
        }
    }

    private fun launchNativeWebView() {
        if (destinationOpened || isFinishing || isDestroyed) return
        destinationOpened = true
        val next = Intent(this, NativeWebViewActivity::class.java).apply {
            val extraMap = intent.getStringExtra("extraMap")
            if (extraMap.isNullOrBlank()) {
                putExtra(NativeWebViewActivity.EXTRA_URL, BuildConfig.MISSKEY_URL)
            } else {
                putExtra("extraMap", extraMap)
            }
            putExtra(NativeWebViewActivity.EXTRA_UA_MARKER, EMBEDDED_UA_MARKER)
        }
        startActivity(next)
        overridePendingTransition(0, 0)
        finish()
        overridePendingTransition(0, 0)
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt().coerceAtLeast(1)

    override fun onDestroy() {
        ioExecutor.shutdownNow()
        super.onDestroy()
    }

    companion object {
        private const val EMBEDDED_UA_MARKER = "LiminalSelvesApp"
        private const val PREFS_UPDATE_GATE = "native_update_gate"
        private const val KEY_META_JSON = "meta_json"
        private const val KEY_META_SAVED_AT = "meta_saved_at"
        private const val META_CONNECT_TIMEOUT_MS = 2_500
        private const val META_READ_TIMEOUT_MS = 2_500
        private const val APP_ACCENT_COLOR = -15043608
    }
}
