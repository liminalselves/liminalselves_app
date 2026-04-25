import org.gradle.api.JavaVersion
import org.gradle.api.tasks.compile.JavaCompile

// 子工程（Flutter 插件等）若仍用 Java 8，在较新 JDK 上会触发「源值/目标值 8 已过时」；统一到 17 与 :app 一致。
// 不可对 Android 模块使用 javac --release，会与 AGP 的 bootclasspath 冲突（issuetracker 278800528）。
subprojects {
    tasks.withType<JavaCompile>().configureEach {
        sourceCompatibility = JavaVersion.VERSION_17.toString()
        targetCompatibility = JavaVersion.VERSION_17.toString()
    }
}

allprojects {
    // 依赖仓库见 settings.gradle.kts dependencyResolutionManagement（避免在此声明 google() 直连 dl.google.com）
}

// Flutter 插件（如 package_info_plus）的 android/build.gradle 里常见
// buildscript { repositories { google() } }，会直连 dl.google.com；在国内或部分网络下 TLS 易握手失败。
// pluginManagement 无法覆盖各子工程的 buildscript 仓库，故在子工程脚本执行前注入镜像，使 AGP 等 classpath 优先从镜像解析。
subprojects {
    beforeEvaluate {
        buildscript.repositories.apply {
            maven { url = uri("https://maven.aliyun.com/repository/google") }
            maven { url = uri("https://maven.aliyun.com/repository/central") }
            maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
            maven { url = uri("https://maven.aliyun.com/repository/public") }
        }
    }
}

val newBuildDir: Directory =
    rootProject.layout.buildDirectory
        .dir("../../build")
        .get()
rootProject.layout.buildDirectory.value(newBuildDir)

subprojects {
    val newSubprojectBuildDir: Directory = newBuildDir.dir(project.name)
    project.layout.buildDirectory.value(newSubprojectBuildDir)
}
subprojects {
    project.evaluationDependsOn(":app")
}

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}
