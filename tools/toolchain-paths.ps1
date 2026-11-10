<#
  工具链路径的【唯一来源】。

  设计：本文件用"点源"(dot-source) 方式被其它脚本引用：

      . (Join-Path $PSScriptRoot 'toolchain-paths.ps1')

  点源后，下面这些变量会出现在【调用者的作用域】里，直接使用即可。
  换盘 / 换目录只改本文件，不要再去改 build.ps1 / setup-android-sdk.ps1 / clean-toolchain.ps1。

  为什么分成两个位置：
    - 大块头（Gradle 缓存 / Android SDK / Android 用户目录）放 $ToolchainRoot，不占项目所在盘；
    - 小块头（.toolchain 里的自举脚本、探针工程、构建日志）留在项目内，方便跟随项目一起删。

  本机路径差异不要改本文件提交上去：把私有覆盖写进 tools\local-paths.ps1（已 gitignore），
  里面可以重新赋值 $ToolchainRoot / $JdkHome。
#>

# ↓↓↓ 换盘只改这一行 ↓↓↓
$ToolchainRoot = 'D:\AndroidBuildCache'

# JDK 17：优先跟随当前 shell 的 JAVA_HOME，否则用 $ToolchainRoot\jdk17。
$JdkHome = if ($env:JAVA_HOME) { $env:JAVA_HOME } else { Join-Path $ToolchainRoot 'jdk17' }

# 本机私有覆盖（不提交）：可以改 $ToolchainRoot / $JdkHome
$localOverride = Join-Path $PSScriptRoot 'local-paths.ps1'
if (Test-Path $localOverride) { . $localOverride }

$GradleHome  = Join-Path $ToolchainRoot 'gradle-home'    # GRADLE_USER_HOME：Gradle 分发版 + 依赖缓存
$AndroidSdk  = Join-Path $ToolchainRoot 'android-sdk'    # ANDROID_HOME / ANDROID_SDK_ROOT
$AndroidUser = Join-Path $ToolchainRoot 'android-user'   # ANDROID_USER_HOME
