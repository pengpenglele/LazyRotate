# Bootstrap the Android build toolchain.
#
# Layout (single source of truth: tools/toolchain-paths.ps1 — change the drive there, not here):
#   big caches -> $ToolchainRoot (default D:\AndroidBuildCache):
#                 android-sdk / android-user / gradle-home
#   scratch    -> <proj>\.toolchain: downloader classes, the commandlinetools archive, logs
#   cleanup    -> tools/clean-toolchain.ps1 -DryRun   (prints the exact paths, then deletes)
#
# Why Java does the downloading:
#   Windows schannel is unusable in this restricted network environment (SEC_E_NO_CREDENTIALS), so
#   PowerShell/curl cannot open HTTPS sockets. The JDK's own JSSE stack can,
#   and both `android.exe` (the 2026 replacement for sdkmanager) and Gradle are
#   Java/Go binaries that do not depend on schannel.
#
# NOTE 2026: `sdkmanager` is deprecated. Google renamed the entry point to
# `android.exe` and `android sdk ...` replaces `sdkmanager ...`. The
# commandlinetools archive also no longer ships a `bin/` level, so the scripts
# are re-homed into cmdline-tools/latest/bin to match the layout the launcher
# expects when it derives the SDK root from its own path.

$ErrorActionPreference = 'Stop'

$proj      = Split-Path -Parent $PSScriptRoot
. (Join-Path $PSScriptRoot 'toolchain-paths.ps1')
$jdk       = $JdkHome
$sdk       = $AndroidSdk
$userHome  = $AndroidUser
$tools     = Join-Path $proj '.toolchain'
$latest    = Join-Path $sdk 'cmdline-tools\latest'
$java      = Join-Path $jdk 'bin\java.exe'
$javac     = Join-Path $jdk 'bin\javac.exe'

New-Item -ItemType Directory -Force -Path $sdk, $userHome, $tools, (Join-Path $userHome 'bin') | Out-Null

$env:JAVA_HOME        = $jdk
$env:ANDROID_HOME     = $sdk
$env:ANDROID_SDK_ROOT = $sdk
$env:ANDROID_USER_HOME = $userHome
$env:ANDROID_AVD_HOME  = Join-Path $userHome 'avd'

Write-Output "=== [1/5] compile the JDK-based downloader ==="
& $javac -d (Join-Path $tools 'classes') (Join-Path $proj 'tools\Download.java')
if ($LASTEXITCODE -ne 0) { throw "javac failed" }

function Fetch([string]$url, [string]$out, [long]$min) {
    Write-Output "--- fetch $url"
    & $java -cp (Join-Path $tools 'classes') Download $url $out $min
    if ($LASTEXITCODE -ne 0) { throw "download failed: $url" }
}

Write-Output "=== [2/5] resolve newest commandlinetools-win archive ==="
$repoXml = Join-Path $tools 'repository2-3.xml'
Fetch 'https://dl.google.com/android/repository/repository2-3.xml' $repoXml 100000
$xmlText = Get-Content $repoXml -Raw
$hits = [regex]::Matches($xmlText, 'commandlinetools-win-(\d+)_latest\.zip')
if ($hits.Count -eq 0) { throw "no commandlinetools-win entry in repository2-3.xml" }
$best    = ($hits | ForEach-Object { [int]$_.Groups[1].Value } | Sort-Object -Descending | Select-Object -First 1)
$zipName = "commandlinetools-win-${best}_latest.zip"
$zipUrl  = "https://dl.google.com/android/repository/$zipName"
Write-Output "chosen: $zipName"

Write-Output "=== [3/5] download + unpack cmdline-tools ==="
$zipPath = Join-Path $tools $zipName
Fetch $zipUrl $zipPath 1000000
$unpack = Join-Path $tools 'cmdtools-unpack'
if (Test-Path $unpack) { Remove-Item $unpack -Recurse -Force }
Expand-Archive -Path $zipPath -DestinationPath $unpack -Force
if (Test-Path $latest) { Remove-Item $latest -Recurse -Force }
New-Item -ItemType Directory -Force -Path (Split-Path $latest) | Out-Null
$inner = Get-ChildItem $unpack -Directory | Select-Object -First 1
Move-Item $inner.FullName $latest -Force
# 2026 archives are flat: re-home the launchers into bin/ so that
# `%SCRIPT_DIR%..\..` resolves to <sdk>\cmdline-tools and the SDK root is found.
New-Item -ItemType Directory -Force -Path (Join-Path $latest 'bin') | Out-Null
Get-ChildItem $latest -File | Where-Object { $_.Extension -in '.bat', '.exe' } |
    ForEach-Object { Move-Item $_.FullName (Join-Path $latest 'bin') -Force }
Write-Output "cmdline-tools installed at $latest"

Write-Output "=== [4/5] install platform-tools / platform / build-tools ==="
$androidCli = Join-Path $latest 'bin\android.exe'
if (-not (Test-Path $androidCli)) { throw "android.exe missing" }
# 2026: android-17 用 minor SDK 版本机制，稳定平台包是 platforms/android-36 与 platforms/android-37.0。
# 注意 `platforms/android-37` 不存在（会报 Package not found），落盘目录名带点；AGP 9.4.1 能识别它。
& $androidCli --sdk="$sdk" sdk install 'platform-tools' 'platforms/android-36' 'platforms/android-37.0' 'build-tools/37.0.0' 2>&1 |
    Select-Object -Last 10
if ($LASTEXITCODE -ne 0) { throw "sdk install failed" }

Write-Output "=== [5/5] verify ==="
Get-ChildItem $sdk -Directory | Select-Object -ExpandProperty Name
$jar36 = Join-Path $sdk 'platforms\android-36\android.jar'
if (-not (Test-Path $jar36)) { throw "platform android-36 android.jar missing -> build would fail" }
Write-Output "OK: $jar36 ($([math]::Round((Get-Item $jar36).Length/1MB,1)) MB)"
Write-Output "SDK_BOOTSTRAP_OK"
