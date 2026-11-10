<#
.SYNOPSIS
  删除本项目自举出来的工具链，把磁盘还原成"没装过工具链"的状态。

.DESCRIPTION
  工具链现在分两处（见 tools\toolchain-paths.ps1）：
    项目内（小块头）：<proj>\.toolchain      自举脚本产物、探针工程、构建日志
    D 盘（大块头）  ：<ToolchainRoot>\gradle-home / android-sdk / android-user

  安全阀（重要）：只允许删【白名单里逐字列出的绝对路径】里的目录，
  路径必须完全相等（不是"以它开头"），所以不可能误删到别的东西。

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File tools\clean-toolchain.ps1 -DryRun
.EXAMPLE
  powershell -ExecutionPolicy Bypass -File tools\clean-toolchain.ps1
.EXAMPLE
  # 只清项目内的自举残留和构建产物，保留 D 盘的下载缓存（下次构建不用重新下载）
  powershell -ExecutionPolicy Bypass -File tools\clean-toolchain.ps1 -KeepCaches
#>
[CmdletBinding()]
param(
    # 只打印将要删除的绝对路径，不真删
    [switch]$DryRun,
    # 保留 D 盘的大块头缓存，只清项目内的残留与构建产物
    [switch]$KeepCaches,
    # 顺带删掉编译产物目录（app\build、build）
    [switch]$IncludeBuildDirs
)

$ErrorActionPreference = 'Stop'

$proj = (Resolve-Path (Split-Path -Parent $PSScriptRoot)).Path
. (Join-Path $PSScriptRoot 'toolchain-paths.ps1')

# 白名单：每一项都是"要么删、要么不动"的确切绝对路径
$targets = @(
    @{ Path = (Join-Path $proj '.toolchain'); Desc = '自举残留（脚本产物 / 探针工程 / 日志）' }
)
if (-not $KeepCaches) {
    $targets += @{ Path = $GradleHome;  Desc = 'Gradle 用户目录（依赖缓存 + wrapper 分发包）' }
    $targets += @{ Path = $AndroidSdk;  Desc = 'Android SDK（platforms / build-tools / platform-tools / cmdline-tools）' }
    $targets += @{ Path = $AndroidUser; Desc = 'Android 用户目录（SDK CLI 状态）' }
}
if ($IncludeBuildDirs) {
    $targets += @{ Path = (Join-Path $proj 'app\build'); Desc = '编译中间产物' }
    $targets += @{ Path = (Join-Path $proj 'build');     Desc = '根项目构建产物' }
}

function Get-DirSizeMB([string]$path) {
    try {
        $bytes = (Get-ChildItem -LiteralPath $path -Recurse -Force -File -ErrorAction SilentlyContinue |
                  Measure-Object -Property Length -Sum).Sum
        if (-not $bytes) { return 0 }
        return [math]::Round($bytes / 1MB, 1)
    } catch { return -1 }
}

Write-Output "工作区根目录  ：$proj"
Write-Output "工具链根目录  ：$ToolchainRoot"
Write-Output ""
Write-Output "将要删除以下绝对路径："

$toDelete = @()
foreach ($t in $targets) {
    $full = [System.IO.Path]::GetFullPath($t.Path).TrimEnd('\')

    # 安全阀 1：绝不能是盘符根或空路径
    if ($full -match '^[A-Za-z]:$' -or [string]::IsNullOrWhiteSpace($full)) {
        throw "拒绝删除盘符根：$full"
    }
    # 安全阀 2：必须恰好等于白名单里的某一项（逐字比较），不做任何通配/前缀匹配
    $allowed = $targets | ForEach-Object { [System.IO.Path]::GetFullPath($_.Path).TrimEnd('\') }
    if ($allowed -notcontains $full) { throw "拒绝删除白名单之外的路径：$full" }
    # 安全阀 3：不能是工作区根目录本身
    if ($full -eq $proj.TrimEnd('\')) { throw "拒绝删除工作区根目录：$full" }

    $exists = Test-Path -LiteralPath $full
    Write-Output ("  [{0}] {1}" -f $(if ($exists) { '存在' } else { '不存在' }), $full)
    Write-Output ("         {0}  大小 {1} MB" -f $t.Desc, $(if ($exists) { Get-DirSizeMB $full } else { 0 }))
    if ($exists) { $toDelete += $full }
}

if ($toDelete.Count -eq 0) { Write-Output "`n没有需要删除的目录。"; exit 0 }
if ($DryRun) { Write-Output "`n-DryRun：未删除任何内容。"; exit 0 }

Write-Output ""
foreach ($p in $toDelete) {
    Write-Output "删除 $p ..."
    Remove-Item -LiteralPath $p -Recurse -Force
}
Write-Output "完成：已删除 $($toDelete.Count) 个目录。"
if (-not $KeepCaches) {
    Write-Output "提示：磁盘缓存已清空，下次构建前需要先跑 tools\setup-android-sdk.ps1 重新自举 SDK。"
} else {
    Write-Output "提示：已保留 D 盘缓存，直接跑 tools\build.ps1 即可重新构建。"
}
