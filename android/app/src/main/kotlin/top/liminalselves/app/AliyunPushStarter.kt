package top.liminalselves.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.graphics.Color
import android.os.Build
import android.util.Log
import com.alibaba.sdk.android.push.CommonCallback
import com.alibaba.sdk.android.push.noonesdk.PushServiceFactory

private const val TAG = "AliyunPushStarter"

/**
 * 依据官方接入要求：PushServiceFactory.init 必须在 Application.onCreate 主线程调用。
 * 本对象提供：
 * - preInitInApplication: 仅做 init（不建立长连接）
 * - start: 用户同意后执行 register / turnOn
 */
object AliyunPushStarter {
    @Volatile
    private var sdkInitialized = false

    fun preInitInApplication(application: Application): String? {
        return try {
            if (sdkInitialized) return null
            // 官方建议：在 Application.onCreate 主线程执行 init(context)。
            // AppKey/AppSecret 由 AndroidManifest meta-data 提供。
            PushServiceFactory.init(application)
            createMisskeyNotificationChannel(application)
            sdkInitialized = true
            null
        } catch (e: Throwable) {
            Log.e(TAG, "Aliyun pre-init failed", e)
            formatThrowableForUi(e)
        }
    }

    /**
     * 在主线程调用。成功时 [onResult] 参数为 (true, deviceId, null)，失败为 (false, null, message)。
     */
    fun start(application: Application, onResult: (Boolean, String?, String?) -> Unit) {
        try {
            if (!sdkInitialized) {
                val err = preInitInApplication(application)
                if (err != null) {
                    onResult(false, null, err)
                    return
                }
            }
            val push = PushServiceFactory.getCloudPushService()
            val existing = push.deviceId?.takeIf { it.isNotBlank() } ?: AliyunPushHolder.deviceId
            if (!existing.isNullOrBlank()) {
                push.turnOnPushChannel(object : CommonCallback {
                    override fun onSuccess(response: String?) {
                        AliyunPushHolder.deviceId = existing
                        Log.i(TAG, "Aliyun push channel on ok deviceId=$existing")
                        onResult(true, existing, null)
                    }

                    override fun onFailed(errorCode: String?, errorMessage: String?) {
                        val msg = listOfNotNull(errorCode, errorMessage).joinToString(" ")
                        Log.w(TAG, "Aliyun turnOnPushChannel failed: $msg")
                        // Fallback to register to recover from broken state.
                        doRegister(application, onResult)
                    }
                })
                return
            }

            doRegister(application, onResult)
        } catch (e: Throwable) {
            Log.e(TAG, "Aliyun push start failed", e)
            onResult(false, null, formatThrowableForUi(e))
        }
    }

    fun stop() {
        try {
            val push = PushServiceFactory.getCloudPushService()
            push.turnOffPushChannel(object : CommonCallback {
                override fun onSuccess(response: String?) {
                    Log.i(TAG, "Aliyun push channel off ok")
                }

                override fun onFailed(errorCode: String?, errorMessage: String?) {
                    val msg = listOfNotNull(errorCode, errorMessage).joinToString(" ")
                    Log.w(TAG, "Aliyun turnOffPushChannel failed: $msg")
                }
            })
        } catch (e: Throwable) {
            Log.w(TAG, "Aliyun push stop failed: ${e.message}")
        }
    }

    private fun doRegister(application: Application, onResult: (Boolean, String?, String?) -> Unit) {
        val push = PushServiceFactory.getCloudPushService()
        push.register(application, object : CommonCallback {
            override fun onSuccess(response: String?) {
                val id = push.deviceId
                AliyunPushHolder.deviceId = id
                Log.i(TAG, "Aliyun push register ok deviceId=$id")
                if (id.isNullOrEmpty()) {
                    onResult(false, null, "deviceId 为空")
                } else {
                    onResult(true, id, null)
                }
            }

            override fun onFailed(errorCode: String?, errorMessage: String?) {
                val msg = listOfNotNull(errorCode, errorMessage).joinToString(" ")
                Log.w(TAG, "Aliyun register failed: $msg")
                onResult(false, null, msg.ifEmpty { "register failed" })
            }
        })
    }

    private fun formatThrowableForUi(t: Throwable): String {
        val simple = t::class.java.simpleName.ifBlank { t::class.java.name }
        val msg = t.message?.takeIf { it.isNotBlank() } ?: "<no-message>"
        return "$simple: $msg"
    }

    private fun createMisskeyNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            "misskey_push",
            "Misskey",
            NotificationManager.IMPORTANCE_HIGH,
        )
        channel.description = "Misskey 通知"
        channel.enableLights(true)
        channel.lightColor = Color.GREEN
        nm.createNotificationChannel(channel)
    }
}
