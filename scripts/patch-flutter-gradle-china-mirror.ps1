# 将 Flutter SDK 内 packages/flutter_tools/gradle/settings.gradle.kts 改为使用阿里云 Maven，
# 避免 :gradle 复合构建从 dl.google.com 拉取依赖时出现 TLS 握手失败（国内常见）。
# 用法：在仓库根目录执行  powershell -ExecutionPolicy Bypass -File scripts/patch-flutter-gradle-china-mirror.ps1

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$localProps = Join-Path $root "android\local.properties"
if (-not (Test-Path $localProps)) {
    Write-Error "未找到 android\local.properties，请先配置 flutter.sdk"
}
$props = Get-Content $localProps -Raw
if ($props -notmatch "flutter\.sdk\s*=\s*(.+)") {
    Write-Error "local.properties 中未设置 flutter.sdk"
}
$flutterSdk = $Matches[1].Trim().TrimEnd('\', '/')
$target = Join-Path $flutterSdk "packages\flutter_tools\gradle\settings.gradle.kts"
if (-not (Test-Path $target)) {
    Write-Error "未找到: $target"
}

$content = @'
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // Patched for CN network: avoid dl.google.com TLS issues (run scripts/patch-flutter-gradle-china-mirror.ps1)
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/central") }
        mavenCentral()
    }
}
'@

Set-Content -Path $target -Value $content -Encoding UTF8
Write-Host "已写入: $target"
