import java.io.File
import java.util.Properties

// 与 Flutter Gradle 插件一致，供 io.flutter:* 引擎 AAR 解析（不在 Google Maven / 阿里云）
val localProps = Properties().apply {
    file("local.properties").inputStream().use { load(it) }
}
val flutterSdkPathForSettings =
    localProps.getProperty("flutter.sdk")?.trim()
        ?: error("flutter.sdk not set in local.properties")

val engineRealm: String = run {
    val realmFile = File(flutterSdkPathForSettings, "bin${File.separator}cache${File.separator}engine.realm")
    if (!realmFile.exists()) return@run ""
    val r = realmFile.readText().trim()
    if (r.isEmpty()) "" else "$r/"
}

pluginManagement {
    // 此块单独编译：无顶层 import，须用全限定名；也不能引用外层 val
    val flutterSdkForInclude = java.util.Properties().let { p ->
        file("local.properties").inputStream().use { p.load(it) }
        p.getProperty("flutter.sdk")?.trim() ?: error("flutter.sdk not set in local.properties")
    }
    includeBuild("$flutterSdkForInclude/packages/flutter_tools/gradle")

    repositories {
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
        maven { url = uri("https://maven.aliyun.com/repository/central") }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        // Flutter 引擎（arm64_v8a_debug、flutter_embedding_debug 等）；须与 FlutterPlugin.kt 中 URL 一致
        val storageEnv = System.getenv("FLUTTER_STORAGE_BASE_URL")
        if (!storageEnv.isNullOrBlank()) {
            maven { url = uri("$storageEnv/${engineRealm}download.flutter.io") }
        } else {
            maven { url = uri("https://storage.flutter-io.cn/${engineRealm}download.flutter.io") }
            maven { url = uri("https://storage.googleapis.com/${engineRealm}download.flutter.io") }
        }
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/central") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        maven { url = uri("https://maven.aliyun.com/nexus/content/repositories/releases/") }
        maven { url = uri("https://developer.huawei.com/repo/") }
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    id("dev.flutter.flutter-plugin-loader") version "1.0.0"
    id("com.android.application") version "8.11.1" apply false
    id("org.jetbrains.kotlin.android") version "2.2.20" apply false
}

include(":app")
