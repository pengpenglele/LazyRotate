# 屏幕方向控制 LazyRotate

[![Android build](https://github.com/pengpenglele/LazyRotate/actions/workflows/android.yml/badge.svg)](https://github.com/pengpenglele/LazyRotate/actions/workflows/android.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

> 安卓平板屏幕方向控制 App。解决「自动旋转在躺姿下不好用」——躺着手持平板时屏幕乱转、换边后画面倒过来、想锁又锁不住。

**核心思路**：系统的自动旋转只靠重力矢量判断方向，而躺着把平板举在面前时，屏幕接近水平、重力在屏幕平面上的投影趋近于零，系统其实是在猜。本 App 不去猜得更聪明，而是**让用户直接指定方向**，并保证「不确定时保持现状」。

---

## 功能一览

| 功能 | 说明 |
|---|---|
| 🔒 锁定当前方向 | 一键把当前方向固定，此后不随传感器变化 |
| ⟳ 翻转 180° | 侧躺换边时一键把画面翻过来 |
| ↩ 跟随系统 | 交还控制权，恢复自动旋转 |
| 绝对方向 | 0°/90°/180°/270°，以及横屏/竖屏/反向横屏/反向竖屏 |
| 快速设置磁贴 | 下拉通知栏一键操作 |
| 悬浮球 | 不离开当前应用即可操作，支持拖动与位置记忆 |
| 常驻通知 | 通知栏内的锁定/翻转/跟随按钮 |
| 按应用规则 | 为指定应用固定方向，切换应用时自动套用 |
| 躺姿引擎 | 置信度不足时明确「保持现状」，消除来回乱转 |
| 外部指令 | 供 Tasker / 快捷方式 / `adb am start` 调用 |

**免 root**，只需要授予一个「修改系统设置」权限（一次授权长期有效）。

---

## 鸿蒙版（HarmonyOS 7 / API 26）

仓库内新增 `harmony/LazyRotate`，是同一产品在鸿蒙 7 上的移植工程（ArkTS + Stage 模型，DevEco Studio 26 打开）。

**先说结论**：鸿蒙**不允许**普通第三方应用全局锁定或翻转其它 App 的方向——
写系统旋转设置需要 `ohos.permission.MANAGE_SETTINGS`（系统应用级），
全局旋转接口 `screen.setScreenRotationLocked` / `screen.setOrientation` 是系统接口（非系统应用调用返回 202）。
MDM Kit（API 26）没有旋转策略，无障碍注入能力自 API 12 起也不再开放。
因此鸿蒙版能落地的是：**本应用窗口方向控制 + 躺姿建议 + 系统设置引导 + 外部 Want 指令 + 可插拔特权后端（系统签名时启用）**。

| 文档 | 内容 |
|---|---|
| [鸿蒙版-可行性评估](docs/鸿蒙版-可行性评估.md) | ★ 五条路线逐条评估（普通应用 / 系统签名 / MDM / 无障碍 / hdc 自动化）、能力对照矩阵、系统签名落地条件与代码示例 |
| [鸿蒙工程-构建与运行](docs/鸿蒙工程-构建与运行.md) | DevEco 打开/签名/安装、配置版本兜底方案、hdc 指令、真机验证清单、FAQ |
| [鸿蒙工程-静态校验报告](docs/鸿蒙工程-静态校验报告.md) | 无 SDK 环境下的静态审查结论与风险清单 |
| [harmony/LazyRotate](harmony/LazyRotate) | 工程本体（README 内有能力边界与目录说明） |

> 本工程不宣称「鸿蒙版可以全局锁定」——平台做不到，详见可行性评估第 0 节。

---

## 快速开始

### 方式一：从源码构建

```powershell
# Windows 一键构建（JDK / SDK / Gradle 缓存路径统一由 tools\toolchain-paths.ps1 解析）
.\tools\build.ps1
# 产物：app\build\outputs\apk\debug\app-debug.apk，同时复制到 dist\
```

其它平台直接走 Gradle：`./gradlew :app:assembleDebug`（需要 JDK 17 与 Android SDK，`ANDROID_HOME` 指向 SDK）。
用 Android Studio 打开本目录也可以直接构建（工程是标准的 Gradle 结构）。

### 方式二：安装

```powershell
# 仓库不提交二进制产物，先按上一节构建（需已开启 USB 调试）。
# Windows 的构建脚本会把产物同时复制到 dist\；其它平台产物在 app\build\outputs\apk\debug\
adb install -r dist\app-debug.apk
```

### 首次使用（必须做）

1. 打开 App → 点击引导条前往系统页面，打开 **「修改系统设置」** 权限（这是唯一的硬门槛）。
2. 返回 App，状态卡会显示已就绪。
3. 想用悬浮球 → 再授予 **「显示在其他应用上层」**。
4. 想用「按应用规则」→ 再开启本 App 的 **无障碍服务**（可选，只读取前台应用包名，不读屏幕内容）。

详细步骤与常见问题见 [用户手册](docs/用户手册.md)。

---

## 三种用法（躺着的时候用前两种）

| 入口 | 怎么用 |
|---|---|
| 快速设置磁贴 | 下拉通知栏 → 点磁贴，循环：跟随系统 → 锁定当前 → 翻转 180° |
| 悬浮球 | 单击=循环，双击=翻转 180°，长按=打开 App，拖动=移动并记住位置 |
| 主界面 | 配置权限、按应用规则、躺姿引擎 |

外部调用示例：

```bash
adb shell am start -a com.lazyrotate.app.action.SET_ROTATION --es TARGET landscape
adb shell am broadcast -a com.lazyrotate.app.action.FLIP
```

---

## 目录结构

```
.
├── app/                                   # 应用模块
│   ├── build.gradle.kts
│   └── src/main/
│       ├── AndroidManifest.xml            # 组件、权限、<queries>
│       ├── java/com/lazyrotate/app/
│       │   ├── contract/Contract.kt       # 冻结接口（领域契约）
│       │   ├── core/                      # 系统集成：写设置、磁贴、悬浮球、通知、无障碍、传感器
│       │   ├── ui/                        # Compose 界面
│       │   └── MainActivity.kt
│       └── res/                           # 字符串、主题、图标、无障碍配置
├── docs/
│   ├── 屏幕方向控制App-需求与技术方案.md    # ★ 主文档：需求、可行性、设计、风险、测试
│   ├── research-notes.md                  # 联网调研笔记（版本矩阵、竞品、合规）
│   ├── CONTRACT.md                        # 工程契约：接口冻结、模块归属、版本与踩坑
│   ├── 用户手册.md                         # 安装、授权、日常使用、FAQ
│   ├── 鸿蒙版-可行性评估.md                 # ★ 鸿蒙 7 移植：五条路线评估、能力边界、系统签名条件
│   ├── 鸿蒙工程-构建与运行.md               # 鸿蒙工程构建/签名/安装/真机验证清单
│   └── 鸿蒙工程-静态校验报告.md             # 鸿蒙工程静态审查结论与风险清单
├── harmony/LazyRotate/                    # 鸿蒙 7（API 26）工程：ArkTS + Stage 模型
│   └── entry/src/main/ets/                # core（后端/契约/传感器）+ pages（三 Tab + 悬浮球）
├── tools/
│   ├── toolchain-paths.ps1                # ★ 工具链路径的唯一来源（换盘只改这里）
│   ├── local-paths.ps1                    # 本机私有覆盖（已 gitignore，不存在则用默认值）
│   ├── setup-android-sdk.ps1              # 自举 Android SDK（无需 Android Studio）
│   ├── build.ps1                          # 一键构建（自动刷新 local.properties）
│   ├── clean-toolchain.ps1                # 清理工具链：-DryRun 预演 / -KeepCaches 只清项目内
│   ├── verify-harmony.py                  # 鸿蒙工程静态校验（JSON5/资源/import/ArkTS 卫生）
│   ├── md_to_docx.py                      # 主文档 Markdown -> Word
│   ├── verify_docx_order.py               # 校验 Word 包结构与 XML 元素顺序
│   ├── Download.java                      # JDK 版 HTTPS 下载器（PowerShell TLS 不可用时用）
│   └── NetProbe.java                      # JDK 版 HTTPS 连通性探针（排查网络限制用）
├── .github/workflows/android.yml          # CI：推提交自动 assembleDebug 并上传 APK
├── .gitignore / .gitattributes
├── LICENSE                                # MIT
└── README.md
```

**不提交的内容**：`dist/`（APK 产物）、`app/build/`、`.gradle/`、`.kotlin/`、`.toolchain/`、`local.properties`、`tools/local-paths.ps1`、`docs/*.docx`（由 `tools/md_to_docx.py` 生成）。

**大块头缓存不在项目里**，默认在 `D:\AndroidBuildCache\`（约 2.07 GB）：

```
D:\AndroidBuildCache\
├── gradle-home/    1,160 MB   Gradle 分发版 + 依赖缓存
├── android-sdk/      684 MB   SDK：platforms / build-tools / platform-tools / cmdline-tools
└── android-user/     230 MB   SDK CLI 用户目录
```

> 上表是默认布局。换机器只要改 `tools/toolchain-paths.ps1` 的 `$ToolchainRoot` 一行，或者写个
> `tools/local-paths.ps1` 做本机私有覆盖（该文件已 gitignore）。用 Android Studio 打开工程则完全不需要这些脚本，
> IDE 自己管理 SDK 与 Gradle 缓存。

---

## 构建环境

| 组件 | 版本 |
|---|---|
| JDK | 17 |
| Gradle | 9.8.1 |
| Android Gradle Plugin | 9.4.1 |
| Kotlin | 2.4.21 |
| Compose BOM | 2026.09.00 |
| compileSdk / targetSdk / minSdk | 37 / 36 / 24 |

`targetSdk 36` 是有意为之：Android 16 起，以 API 36 为目标的应用在小宽度 ≥600dp 的大屏上会被系统**忽略自身声明的方向限制**——这正是本项目最需要的系统级变化。

### 本机构建踩坑记录（可复现）

1. **PowerShell 的 HTTPS 不可用**：部分受限网络环境的 Windows 上 `schannel: SEC_E_NO_CREDENTIALS`，`Invoke-WebRequest` / `curl` 全部失败，但 JDK 的 JSSE 正常。因此 SDK 自举与依赖下载都走 Java 侧（`tools/Download.java`、`gradlew`）。
2. **`sdkmanager` 已弃用**：2026 版命令行工具改用 `cmdline-tools/latest/bin/android.exe`，`android sdk ...` 取代 `sdkmanager ...`；`--licenses` 也不再需要。
3. **新版归档没有 `bin/` 层**：`commandlinetools-win-*_latest.zip` 解包后启动脚本直接在根目录，需要移入 `bin/` 才能让它正确推导 SDK 根目录（脚本已自动处理）。
4. **Android 17 的平台包名带 minor 版本**：稳定包是 `platforms/android-37.0`（不是 `platforms/android-37`，后者会报 `Package ... not found`），落盘目录名带点。我一度以为 `compileSdk = 37` 解析不到这个带点目录，**实测是错的：AGP 9.4.1 能正常识别它**，所以最终配置是 `compileSdk = 37` + `targetSdk = 36`（`targetSdk` 才是"大屏忽略方向限制"这个行为门的开关）。
5. **`compileSdk` 必须 ≥ 37**：最新稳定版 AndroidX（core-ktx 1.19.1、compose ui-android 1.12.1）的 AAR metadata 强制要求 `compile against version 37 or later`，用 36 会在 `checkDebugAarMetadata` 阶段失败。
6. **AGP 9 内置 Kotlin**：不能再应用 `org.jetbrains.kotlin.android`（AGP 9.0 起 fatal），只应用 `com.android.application` + `org.jetbrains.kotlin.plugin.compose`；Kotlin 版本由 AGP 内置（2.4.21），jvmTarget 自动跟随 `compileOptions = 17`。
7. 工具链路径统一由 [tools/toolchain-paths.ps1](tools/toolchain-paths.ps1) 定义，**换盘只改其中的 `$ToolchainRoot` 一行**（本机私有差异写 `tools/local-paths.ps1`，已 gitignore）；大块头缓存放项目外、小块头留项目内，`.\tools\clean-toolchain.ps1 -DryRun` 可先预览再清理，不污染系统环境（不改 PATH、不写注册表、不装服务）。

---

## 已知限制（重要，请先读）

- **对「自己在代码里写死了方向」的应用无效**。这是 Android 的架构级隔离：第三方应用无权修改另一个应用的窗口方向声明，与权限多少无关。典型是部分视频、游戏与国内 App。
  - 绕过方式一：在 Android 16+ 的大屏设备上，这类限制本身已被系统放开（需应用以 API 36 为目标，多数新版应用已满足）。
  - 绕过方式二（高级，需电脑执行一次）。在电脑上执行下面任一条：
    ```bash
    # 全局忽略应用自身的方向请求（重启后仍保留）
    adb shell cmd window set-ignore-orientation-request true
    # 更彻底：系统无条件采用你设定的方向，连写死方向的应用也能强制旋转（代价：可能出现黑边）
    adb shell cmd window fixed-to-user-rotation enabled
    ```
    ⚠️ 这些开关**无法由 App 自己打开**：它由系统 `WindowManagerService` 持有，实现是隐藏的 AIDL 接口，状态落在 `/data/system/display_settings.xml`，且只对 shell 身份开放——**授予 `WRITE_SECURE_SETTINGS` 也不够**。App 内「设置」页提供可复制的命令，方便你操作。
- **本 App 不申请网络权限**，因此不可能上传任何数据——隐私可以从权限列表直接验证。
- APK 目前是 **debug 签名**，仅供自用/测试；正式分发需自行配置 release 签名。
- 躺姿引擎的阈值（置信度 0.25、迟滞 700ms）是合理初值，建议在真机躺姿下实测微调。

---

## 隐私

| 项目 | 状态 |
|---|---|
| 网络权限 | ❌ 未申请（技术上无法联网） |
| 应用列表权限 | ❌ 未申请 `QUERY_ALL_PACKAGES`，用 `<queries>` 精确放开启动器应用 |
| 无障碍服务 | 可选；只读取前台应用**包名**，`canRetrieveWindowContent=false`，不读内容、不点击 |
| 数据存储 | 仅本机 DataStore，且已关闭云备份与设备迁移 |

---

## 文档索引

- [需求与技术方案](docs/屏幕方向控制App-需求与技术方案.md) —— 痛点根因、可行性边界、版本矩阵、架构、风险、测试用例
- [用户手册](docs/用户手册.md) —— 安装、授权、三种用法、FAQ
- [调研笔记](docs/research-notes.md) —— 联网调研的事实与来源
- [工程契约](docs/CONTRACT.md) —— 接口冻结、模块归属、工具链版本与踩坑

鸿蒙版：

- [鸿蒙版-可行性评估](docs/鸿蒙版-可行性评估.md) —— 鸿蒙 7 / API 26 能力边界、五条路线评估、系统签名落地条件
- [鸿蒙工程-构建与运行](docs/鸿蒙工程-构建与运行.md) —— DevEco 构建、签名、hdc 安装与外部指令、真机验证清单
- [鸿蒙工程-静态校验报告](docs/鸿蒙工程-静态校验报告.md) —— 无 SDK 环境下的静态审查结论与风险清单

---

## 许可证

[MIT](LICENSE) —— 可自由使用、修改、分发，包括商用，保留版权声明即可。

欢迎开 Issue 反馈问题、提 PR 改进。

