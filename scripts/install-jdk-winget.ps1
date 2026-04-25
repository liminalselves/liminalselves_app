# Installs JDK 17 via winget (Microsoft Build of OpenJDK). Run once; then reopen terminal and use gw.ps1 / flutter.
# Requires: Windows 10/11 with winget (App Installer from Microsoft Store).
$ErrorActionPreference = "Stop"

Write-Host "Installing JDK 17 (Microsoft OpenJDK) via winget..." -ForegroundColor Cyan
winget install --id Microsoft.OpenJDK.17 -e --accept-source-agreements --accept-package-agreements
if ($LASTEXITCODE -ne 0) {
    Write-Host "winget failed. Try manually: winget search openjdk" -ForegroundColor Yellow
    exit $LASTEXITCODE
}

$found = $null
if (Test-Path "C:\Program Files\Microsoft") {
    $found = Get-ChildItem "C:\Program Files\Microsoft" -Directory -Filter "jdk*" -ErrorAction SilentlyContinue |
        Where-Object { Test-Path (Join-Path $_.FullName "bin\java.exe") } |
        Sort-Object Name -Descending |
        Select-Object -First 1
    if ($found) { $found = $found.FullName }
}

if ($found) {
    [Environment]::SetEnvironmentVariable("JAVA_HOME", $found, "User")
    Write-Host "Set user JAVA_HOME to: $found" -ForegroundColor Green
    Write-Host "Close this terminal, open a new one, then: cd android; .\gw.ps1 --stop" -ForegroundColor Green
} else {
    Write-Host "JDK installed but path not auto-detected. Search for java.exe under C:\Program Files\Microsoft\, then set JAVA_HOME to its parent folder (above bin)." -ForegroundColor Yellow
}
