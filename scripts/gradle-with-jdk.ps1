# Finds JDK when needed, then runs android/gradlew.bat
# Usage: .\scripts\gradle-with-jdk.ps1 --stop
param(
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$GradleArgs
)

$ErrorActionPreference = "Stop"

function Test-JavaHome([string]$Path) {
    if (-not $Path) { return $false }
    return Test-Path -LiteralPath (Join-Path $Path "bin\java.exe")
}

function Add-Candidate($List, $Path) {
    if (-not $Path) { return }
    $p = $Path.TrimEnd('\')
    if ($List -notcontains $p) { [void]$List.Add($p) }
}

$repoRoot = Split-Path $PSScriptRoot -Parent
$androidDir = Join-Path $repoRoot "android"
$gradlew = Join-Path $androidDir "gradlew.bat"

if (-not (Test-Path $gradlew)) {
    Write-Error "gradlew.bat not found: $gradlew"
    exit 1
}

$candidates = [System.Collections.ArrayList]@()

# --- collect paths to try (order: explicit env first, then file-based list) ---
$javaHome = $null

foreach ($scope in @("Process", "User", "Machine")) {
    $jh = [Environment]::GetEnvironmentVariable("JAVA_HOME", $scope)
    if (Test-JavaHome $jh) {
        $javaHome = $jh
        break
    }
}

if (-not $javaHome) {
    $javaCmd = Get-Command java -ErrorAction SilentlyContinue
    if ($javaCmd -and $javaCmd.Source) {
        $binDir = Split-Path -Parent $javaCmd.Source
        if ((Split-Path -Leaf $binDir) -eq "bin") {
            $cand = Split-Path -Parent $binDir
            if (Test-JavaHome $cand) { $javaHome = $cand }
        }
    }
}

if (-not $javaHome) {
    Add-Candidate $candidates "C:\Program Files\Android\Android Studio\jbr"
    Add-Candidate $candidates "C:\Program Files\Android\Android Studio Preview\jbr"
    Add-Candidate $candidates "C:\Program Files\JetBrains\Android Studio\jbr"
    Add-Candidate $candidates "C:\Program Files\JetBrains\Android Studio Preview\jbr"
    Add-Candidate $candidates "$env:LocalAppData\Android\Sdk\jbr"
    Add-Candidate $candidates "D:\AndroidSdk\jbr"

    $localProps = Join-Path $androidDir "local.properties"
    if (Test-Path $localProps) {
        foreach ($line in Get-Content $localProps -Encoding UTF8) {
            if ($line -match '^\s*sdk\.dir\s*=\s*(.+)\s*$') {
                $sdkDir = $Matches[1].Trim().Trim('"')
                $sdkDir = $sdkDir -replace '\\\\', '\'
                Add-Candidate $candidates (Join-Path $sdkDir "jbr")
                break
            }
        }
    }

    foreach ($root in @("C:\Program Files\Java", "C:\Program Files\Eclipse Adoptium")) {
        if (-not (Test-Path -LiteralPath $root)) { continue }
        Get-ChildItem -LiteralPath $root -Directory -ErrorAction SilentlyContinue | ForEach-Object {
            Add-Candidate $candidates $_.FullName
        }
    }

    $msJdkRoot = "C:\Program Files\Microsoft"
    if (Test-Path -LiteralPath $msJdkRoot) {
        Get-ChildItem -LiteralPath $msJdkRoot -Directory -Filter "jdk*" -ErrorAction SilentlyContinue | ForEach-Object {
            Add-Candidate $candidates $_.FullName
        }
    }

    foreach ($j in $candidates) {
        if (Test-JavaHome $j) {
            $javaHome = $j
            break
        }
    }
}

if (-not $javaHome) {
    Write-Host "JDK not found (java.exe). Gradle needs a JDK on this PC." -ForegroundColor Yellow
    Write-Host "Quick install (needs winget): from repo root run: .\scripts\install-jdk-winget.ps1" -ForegroundColor Yellow
    Write-Host "Or set user env JAVA_HOME to JDK root (folder that contains bin\java.exe), then open a new terminal." -ForegroundColor Yellow
    Write-Host "Or install Android Studio; its bundled JBR is often under Android Studio\jbr" -ForegroundColor Yellow
    exit 1
}

$env:JAVA_HOME = $javaHome
$env:Path = "$(Join-Path $javaHome 'bin');$env:Path"
Write-Host "JAVA_HOME=$javaHome" -ForegroundColor DarkGray

Push-Location $androidDir
try {
    & $gradlew @GradleArgs
    exit $LASTEXITCODE
} finally {
    Pop-Location
}
