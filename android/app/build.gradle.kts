import java.util.Properties
import java.util.Base64
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    id("com.android.application")
    id("kotlin-android")
    // The Flutter Gradle Plugin must be applied after the Android and Kotlin Gradle plugins.
    id("dev.flutter.flutter-gradle-plugin")
}

val keystoreProperties = Properties()
val keystorePropertiesFile = rootProject.file("key.properties")
if (keystorePropertiesFile.exists()) {
    keystorePropertiesFile.inputStream().use { keystoreProperties.load(it) }
}

val dartDefines = (project.findProperty("dart-defines") as? String)
    .orEmpty()
    .split(',')
    .mapNotNull { encoded ->
        runCatching {
            String(Base64.getDecoder().decode(encoded), Charsets.UTF_8)
        }.getOrNull()
    }
    .associate { define ->
        val separator = define.indexOf('=')
        if (separator < 0) define to "" else define.substring(0, separator) to define.substring(separator + 1)
    }
val misskeyUrl = dartDefines["MISSKEY_URL"]
    ?.takeIf { it.isNotBlank() }
    ?: "https://misskey.liminalselves.top/"
val escapedMisskeyUrl = misskeyUrl
    .replace("\\", "\\\\")
    .replace("\"", "\\\"")

// 阿里云 EMAS 推送凭证：从 key.properties（已 gitignore）读取，构建时注入 Manifest，
// 避免把 appkey/appsecret 硬编码进被版本控制的 AndroidManifest.xml。
// 当前推送已停用（阿里云应用已删除），凭证留空；SDK 与接口代码保留，
// 重建应用后只需在 key.properties 填入新值即可恢复推送。
val aliyunAppKey = keystoreProperties.getProperty("aliyunAppKey").orEmpty()
val aliyunAppSecret = keystoreProperties.getProperty("aliyunAppSecret").orEmpty()

android {
    namespace = "top.liminalselves.app"
    compileSdk = flutter.compileSdkVersion
    // 不指定 ndkVersion：避免 AGP 向 Google 拉取 NDK（你当前网络对 dl.google.com TLS 易失败）。
    // 本工程仅为 WebView 壳，一般无需 NDK；若以后加入含 JNI 的插件再恢复或本地安装 NDK。

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    defaultConfig {
        // TODO: Specify your own unique Application ID (https://developer.android.com/studio/build/application-id.html).
        applicationId = "top.liminalselves.app"
        // You can update the following values to match your application needs.
        // For more information, see: https://flutter.dev/to/review-gradle-config.
        minSdk = flutter.minSdkVersion
        targetSdk = flutter.targetSdkVersion
        versionCode = flutter.versionCode
        versionName = flutter.versionName
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
        multiDexEnabled = true
        buildConfigField("String", "MISSKEY_URL", "\"$escapedMisskeyUrl\"")
        manifestPlaceholders["ALIYUN_APP_KEY"] = aliyunAppKey
        manifestPlaceholders["ALIYUN_APP_SECRET"] = aliyunAppSecret
    }

    buildFeatures {
        buildConfig = true
    }

    lint {
        // Flutter tool rewrites local.properties with Windows paths before each build.
        disable += "PropertyEscape"
    }

    packaging {
        jniLibs {
            // Aliyun Push release 下偶发 SPI 组件初始化失败，强制 legacy 打包避免 so 装载路径差异。
            useLegacyPackaging = true
        }
    }

    signingConfigs {
        if (keystorePropertiesFile.exists()) {
            create("release") {
                keyAlias = keystoreProperties.getProperty("keyAlias")!!
                keyPassword = keystoreProperties.getProperty("keyPassword")!!
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile")!!)
                storePassword = keystoreProperties.getProperty("storePassword")!!
            }
        }
    }

    buildTypes {
        release {
            signingConfig = if (keystorePropertiesFile.exists()) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }
}

dependencies {
    // 外链确认对话框：Material 3，与 Flutter Material 壳风格一致（避免 android.app.AlertDialog 系统怀旧样式）。
    implementation("com.google.android.material:material:1.12.0")
    // 版本号请对照 EMAS「Android SDK 版本说明」固定使用，勿用 3.+
    // aliyun-emas-services.json 里 use_maven=true 时通常用 Maven 即可；OneSDK 离线目录里的 aar 仅在无法访问 Maven 时使用。
    implementation("com.aliyun.ams:alicloud-android-push:3.8.4")
    implementation("androidx.multidex:multidex:2.0.1")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    // 原生 WebSocket：后台保活时绕过 WebView JS 节流，独立接收 Misskey Streaming 消息
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}

flutter {
    source = "../.."
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
}

afterEvaluate {
    tasks.matching {
        it.name.startsWith("create") && it.name.endsWith("MainDexClassList")
    }.configureEach {
        doLast {
            val outFile = outputs.files.singleFile
            val keepFile = file("multidex.keep")
            if (keepFile.exists()) {
                outFile.appendText("\n")
                outFile.appendText(keepFile.readText(Charsets.UTF_8))
            }
        }
    }
}
