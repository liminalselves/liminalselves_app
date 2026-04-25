# 在未配置 JAVA_HOME 时调用 Gradle（内部会设置 JAVA_HOME）。用法与 gradlew.bat 相同。
# 在 android 目录下: .\gw.ps1 --stop
$ErrorActionPreference = "Stop"
$delegate = Join-Path (Split-Path $PSScriptRoot -Parent) "scripts\gradle-with-jdk.ps1"
if (-not (Test-Path $delegate)) {
    Write-Error "未找到: $delegate"
    exit 1
}
& $delegate @args
exit $LASTEXITCODE
