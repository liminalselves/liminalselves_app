package top.liminalselves.app

import android.util.Log
import androidx.multidex.MultiDex
import androidx.multidex.MultiDexApplication

/**
 * 按阿里云官方要求：PushServiceFactory.init 必须在 Application.onCreate 主线程执行。
 */
class LiminalApplication : MultiDexApplication() {
    override fun attachBaseContext(base: android.content.Context?) {
        super.attachBaseContext(base)
        MultiDex.install(this)
    }

    override fun onCreate() {
        super.onCreate()
        val err = AliyunPushStarter.preInitInApplication(this)
        if (err != null) {
            Log.w("LiminalApplication", "Aliyun pre-init warning: $err")
        }
    }
}
