import org.gradle.api.tasks.compile.JavaCompile
import java.util.Properties

plugins {
    id("com.android.application")
}

val keystoreProperties = Properties()
val keystorePropertiesFile = rootProject.file("key.properties")
if (keystorePropertiesFile.exists()) {
    keystorePropertiesFile.inputStream().use { keystoreProperties.load(it) }
}

// Android 版本唯一来源仍是 pubspec.yaml 的 version 行（iOS 侧同样读取）。
val pubspecVersion = rootProject.file("../pubspec.yaml")
    .readLines(Charsets.UTF_8)
    .first { it.trimStart().startsWith("version:") }
    .substringAfter(':')
    .trim()
val appVersionName = pubspecVersion.substringBefore('+')
val appVersionCode = pubspecVersion.substringAfter('+').toInt()

// 实例地址通过 -PmisskeyUrl=... 注入（替代原 Flutter 的 --dart-define）。
val misskeyUrl = (project.findProperty("misskeyUrl") as? String)
    ?.takeIf { it.isNotBlank() }
    ?: "https://misskey.liminalselves.top/"
val escapedMisskeyUrl = misskeyUrl
    .replace("\\", "\\\\")
    .replace("\"", "\\\"")

android {
    namespace = "top.liminalselves.app"
    compileSdk = 36

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    defaultConfig {
        applicationId = "top.liminalselves.app"
        minSdk = 24
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
        buildConfigField("String", "MISSKEY_URL", "\"$escapedMisskeyUrl\"")
    }

    buildFeatures {
        buildConfig = true
    }

    lint {
        // Flutter 工具会以未转义的 Windows 路径重写 local.properties；原生构建不再读取该文件。
        disable += "PropertyEscape"
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

tasks.withType<JavaCompile>().configureEach {
    // Flutter 工具（analyze/pub get）会再生该文件，Android 原生构建不使用它。
    exclude("io/flutter/plugins/GeneratedPluginRegistrant.java")
}

dependencies {
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    // 外链确认对话框：Material 3，与壳层风格一致。
    implementation("com.google.android.material:material:1.12.0")
    // 原生 WebSocket：后台保活时绕过 WebView JS 节流，独立接收 Misskey Streaming 消息
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
