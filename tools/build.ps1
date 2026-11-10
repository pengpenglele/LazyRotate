<#
.SYNOPSIS
  一键构建 LazyRotate 的 debug APK。

.DESCRIPTION
  工具链路径统一由 tools\toolchain-paths.ps1 定义（当前指向 D 盘，不占项目所在盘）：
    GRADLE_USER_HOME  = <ToolchainRoot>\gradle-home
    ANDROID_HOME      = <ToolchainRoot>\android-sdk
    ANDROID_USER_HOME = <ToolchainRoot>\android-user
  要换盘只改 toolchain-paths.ps1 里的 $ToolchainRoot 一行。
  构建完成后把 APK 复制到 <proj>\dist\app-debug.apk，并打印大小、sha256 与 badging 摘要。

  注意：本机 PowerShell 的 schannel 是坏的，本脚本只用 gradlew（Java/JSSE）联网，
  不使用 Invoke-WebRequest / curl。

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File tools\build.ps1
.EXAMPLE
  powershell -ExecutionPolicy Bypass -File tools\build.ps1 -Task :app:assembleDebug -Stacktrace
.EXAMPLE
  powershell -ExecutionPolicy Bypass -File tools\build.ps1 -Jdk 'C:\Program Files\Java\jdk-17'
#>
[CmdletBinding()]
param(
    # 要执行的 Gradle 任务，默认打包 debug APK
    [string]$Task = ':app:assembleDebug',
    # 加 --stacktrace，构建配置报错时更有用
    [switch]$Stacktrace,
    # 只构建、不复制到 dist
    [switch]$NoDist,
    # 保留 Gradle 守护进程（默认 --no-daemon，避免留下常驻后台进程）
    [switch]$Daemon,
    # JDK 目录；默认取 toolchain-paths.ps1 解析出的 $JdkHome
    [string]$Jdk
)

$ErrorActionPreference = 'Stop'

$proj     = Split-Path -Parent $PSScriptRoot
$gradlew  = Join-Path $proj 'gradlew.bat'
. (Join-Path $PSScriptRoot 'toolchain-paths.ps1')
$jdk         = if ($Jdk) { $Jdk } else { $JdkHome }
$gradleHome  = $GradleHome
$sdk         = $AndroidSdk
$androidUser = $AndroidUser
$dist     = Join-Path $proj 'dist'
$logs     = Join-Path $proj '.toolchain\logs'

if (-not (Test-Path $gradlew)) { throw "gradlew.bat 不存在：$gradlew" }
if (-not (Test-Path (Join-Path $jdk 'bin\java.exe'))) {
    throw "JDK 不存在：$jdk`n请任选一种方式指定：设置 JAVA_HOME、在 tools\local-paths.ps1 里给 `$JdkHome 赋值、或用 -Jdk 传入 JDK 目录。"
}
$platformDir = Join-Path $sdk 'platforms'
if (-not (Get-ChildItem $platformDir -Directory -Filter 'android-37*' -ErrorAction SilentlyContinue)) {
    Write-Warning "找不到 compileSdk 37 的平台包（预期 $platformDir\android-37.0），构建可能失败。"
}

# 全部写在脚本进程内，不污染调用者的 shell
$env:JAVA_HOME        = $jdk
$env:GRADLE_USER_HOME = $gradleHome
$env:ANDROID_HOME     = $sdk
$env:ANDROID_SDK_ROOT = $sdk
$env:ANDROID_USER_HOME = $androidUser
New-Item -ItemType Directory -Force -Path $gradleHome, $androidUser, $logs | Out-Null

# 同步 local.properties 给 Android Studio 用：
# 命令行构建走上面的 ANDROID_HOME 环境变量，IDE 则读这里的 sdk.dir。
# （本文件是机器相关的，不应提交到版本库；每次构建会自动刷新，所以换盘后不会失效。）
$localProps = Join-Path $proj 'local.properties'
"sdk.dir=$($sdk -replace '\\','/')" | Set-Content -Path $localProps -Encoding ASCII

$gradleArgs = @($Task, '--console=plain')
if ($Stacktrace) { $gradleArgs += '--stacktrace' }
if (-not $Daemon) { $gradleArgs += '--no-daemon' }

$stamp   = Get-Date -Format 'yyyyMMdd-HHmmss'
$logFile = Join-Path $logs ("build-{0}.log" -f $stamp)

Write-Output "=== gradlew $($gradleArgs -join ' ') ==="
Write-Output "    JAVA_HOME        = $env:JAVA_HOME"
Write-Output "    GRADLE_USER_HOME = $env:GRADLE_USER_HOME"
Write-Output "    ANDROID_HOME     = $env:ANDROID_HOME"
Write-Output "    log              = $logFile"

# 通过 cmd /c 做重定向：Windows PowerShell 5.1 会把 native 命令的 stderr 当成 ErrorRecord，
# 在 $ErrorActionPreference='Stop' 下会误判为致命错误（Gradle 的 file-watcher 警告就走 stderr）。
$quotedGradlew = '"' + $gradlew + '"'
$argLine = ($gradleArgs | ForEach-Object { if ($_ -match '\s') { '"' + $_ + '"' } else { $_ } }) -join ' '
$cmdLine = '{0} {1} > "{2}" 2>&1' -f $quotedGradlew, $argLine, $logFile
cmd /c $cmdLine
$code = $LASTEXITCODE
# 只回显尾部，避免刷屏；完整日志在 $logFile
Get-Content -Path $logFile -Tail 40 | ForEach-Object { Write-Output $_ }

if ($code -ne 0) {
    Write-Error "Gradle 构建失败（exit $code）。完整日志：$logFile"
    exit $code
}
Write-Output "=== BUILD OK（完整日志：$logFile）==="

if ($NoDist) { exit 0 }

$apk = Join-Path $proj 'app\build\outputs\apk\debug\app-debug.apk'
if (-not (Test-Path $apk)) { throw "构建成功但找不到 APK：$apk" }

New-Item -ItemType Directory -Force -Path $dist | Out-Null
$distApk = Join-Path $dist 'app-debug.apk'
Copy-Item $apk $distApk -Force

$fi   = Get-Item $distApk
$hash = (Get-FileHash $distApk -Algorithm SHA256).Hash.ToLower()
Write-Output "=== APK ==="
Write-Output ("path   : {0}" -f $fi.FullName)
Write-Output ("size   : {0} bytes ({1:N2} MB)" -f $fi.Length, ($fi.Length / 1MB))
Write-Output ("sha256 : {0}" -f $hash)

$aapt2 = Join-Path $sdk 'build-tools\37.0.0\aapt2.exe'
if (Test-Path $aapt2) {
    Write-Output "=== aapt2 dump badging ==="
    & $aapt2 dump badging $distApk 2>&1 |
        Where-Object { $_ -match '^(package|sdkVersion|targetSdkVersion|uses-permission|application-label|launchable-activity)' } |
        ForEach-Object { Write-Output $_ }
} else {
    Write-Warning "找不到 aapt2：$aapt2"
}
