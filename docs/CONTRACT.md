# 工程契约（冻结）

> 本文与 `app/src/main/java/com/lazyrotate/app/contract/Contract.kt` 是**冻结接口**，
> 也是理解这套代码边界的入口。需要改动请先开 Issue 讨论，不要直接改。

## 1. 产品一句话

安卓平板屏幕方向控制 App：解决「自动旋转在躺姿下不可用」的问题。
免 root 为主路径，Shizuku/ADB 为高级档，提供快速设置磁贴、悬浮球、按应用规则、躺姿锁定与 180° 翻转。

- 应用名：**屏幕方向控制**（LazyRotate）
- applicationId / namespace：`com.lazyrotate.app`
- 代码语言/UI：Kotlin + Jetpack Compose（Material 3）

## 2. 已验证的工具链版本（2026-10 实测，不要擅自改）

| 组件 | 版本 | 说明 |
|---|---|---|
| JDK | **17**（本机实测 17.0.12） | 路径由 `tools/toolchain-paths.ps1` 的 `$JdkHome` 解析，换机器不用改本文 |
| Gradle | **9.8.1**（wrapper 目标；本地另有 9.0.0） | `services.gradle.org` 当前稳定版 |
| AGP | **9.4.1**（Google Maven 最新稳定版） | 9.5.0 只有 alpha，不要用 |
| Kotlin | **2.4.21**（最新稳定版） | 2.5.0 只有 Beta |
| Compose BOM | **2026.09.00** | 最新稳定版 |
| compileSdk | **37** | **实测必须 ≥37**：AndroidX 最新稳定版（core-ktx 1.19.1、compose ui-android 1.12.1）的 AAR metadata 强制要求 `compile against version 37 or later`，用 36 会在 `checkDebugAarMetadata` 阶段失败 |
| targetSdk | **36**（Android 16） | 行为门：Android 16 的「大屏忽略应用方向限制」由 targetSdk 决定，必须保持 36 |
| minSdk | **24** | 磁贴 API 24 起可用 |
| build-tools | 37.0.0 | 已装 |

**工具链路径统一由 `tools/toolchain-paths.ps1` 定义——换盘只改那一个文件里的 `$ToolchainRoot`，不要改其它脚本。**

| 内容 | 默认路径 | 大小 |
|---|---|---|
| Android SDK（`ANDROID_HOME`） | `D:\AndroidBuildCache\android-sdk` | 684 MB |
| Gradle 用户目录（`GRADLE_USER_HOME`） | `D:\AndroidBuildCache\gradle-home` | 1,160 MB |
| Android 用户目录（`ANDROID_USER_HOME`） | `D:\AndroidBuildCache\android-user` | 230 MB |
| 自举残留 / 构建日志（项目内） | `<proj>\.toolchain` | 小 |

> 上表是默认值。想放到别处、或换了机器，改 `tools/toolchain-paths.ps1` 的 `$ToolchainRoot` 一行即可；
> 不想改动提交上去的内容，可以写一个 `tools/local-paths.ps1` 做本机私有覆盖（该文件已 gitignore）。
> **本文档后面若出现 `.android-sdk` / `.gradle-home` / `.android-user` 这样的相对写法，一律指上表的位置。**

SDK 内含 `platforms/android-36`、`platforms/android-37.0`、`build-tools/37.0.0`、`platform-tools`（含 adb）。

**平台包名（实测，2026-10，结论已修正）**：Android 17 采用 minor SDK 版本机制，稳定平台包名是
`platforms/android-37.0`（**不是** `platforms/android-37`，后者会报 `Package ... not found`），
落盘目录名就是带点的 `platforms\android-37.0`。
⚠️ **早期判断已推翻**：我曾以为 `compileSdk = 37` 会解析不到这个带点目录，实测**是错的**——
**AGP 9.4.1 能正常识别 `platforms\android-37.0`**，probe 工程已用 `compileSdk = 37` 成功出包
（badging: `compileSdkVersion='37'`）。所以工程配置为 `compileSdk = 37` + `targetSdk = 36`。
`<android-sdk>\.sdk\`（即 `D:\AndroidBuildCache\android-sdk\.sdk`）是 2026 版 CLI 的内部状态目录，忽略即可。

**AGP 9 的另一个坑（实测）**：AGP 9.0 起**内置 Kotlin 支持**，不能再应用 `org.jetbrains.kotlin.android`，
否则构建 fatal：
`The 'org.jetbrains.kotlin.android' plugin is no longer required for Kotlin support since AGP 9.0.`
正确写法是只应用 `com.android.application` + `org.jetbrains.kotlin.plugin.compose`（2.4.21）；
Kotlin 版本无需声明（AGP 9.4.1 内置即 2.4.21），jvmTarget 自动跟随 `compileOptions = 17`（产物 class major=61）。

```powershell
# 安装/补装 SDK 组件的正确写法（sdkmanager 已弃用）
. tools\toolchain-paths.ps1
& "$AndroidSdk\cmdline-tools\latest\bin\android.exe" --sdk="$AndroidSdk" `
    sdk install 'platforms/android-36' 'platforms/android-37.0' 'build-tools/37.0.0' 'platform-tools'
```

**本机网络注意**：在部分受限网络环境下的 Windows 机器上 PowerShell 的 TLS 可能不可用（schannel `SEC_E_NO_CREDENTIALS`），
`Invoke-WebRequest` / `curl` 会失败，但 Java 的 JSSE 正常，`gradlew` 和 `android.exe` 都能正常联网
（这也是 `tools/Download.java` 存在的原因）。普通机器上直接用 `Invoke-WebRequest` 也可以。
所有缓存都落在 `tools/toolchain-paths.ps1` 定义的位置（默认 `D:\AndroidBuildCache`）。

## 3. 目录职责

| 路径 | 职责 |
|---|---|
| `AndroidManifest.xml` | 组件、权限与 `<queries>` 声明 |
| `app/src/main/java/com/lazyrotate/app/contract/**` | 冻结的领域契约（`Contract.kt`） |
| `app/src/main/java/com/lazyrotate/app/core/**` | 系统集成：写设置、磁贴、悬浮球、通知、无障碍、传感器 |
| `app/src/main/java/com/lazyrotate/app/ui/**`、`MainActivity.kt` | Compose 界面 |
| `app/src/main/res/xml/**` | 无障碍服务配置 |
| `app/src/main/res/values/`、`app/src/main/res/drawable/` | 字符串、主题、颜色、图标等资源 |
| `settings.gradle.kts`、`build.gradle.kts`、`app/build.gradle.kts`、`gradle.properties`、`gradle/**`、`gradlew*` | 构建配置 |
| `tools/**` | 构建、自举与校验脚本 |
| `docs/**`、`README.md` | 文档 |

不允许新建其它顶层目录，改动尽量收在同一个模块内。

## 4. 冻结的资源名（清单/配置里已经引用，名字必须一字不差）

| 资源名 | 所在文件 | 要求 |
|---|---|---|
| `@style/Theme.LazyRotate` | `res/values/themes.xml` | 父主题用平台主题（如 `android:Theme.Material.Light.NoActionBar` 或 Material3 的 DayNight），不要引入 AppCompat |
| `@string/app_name` | `res/values/strings.xml` | 值：`屏幕方向控制` |
| `@string/accessibility_service_description` | `res/values/strings.xml` | 无障碍服务描述，一句话说明「只读取当前前台应用包名」 |
| `@string/accessibility_service_summary` | `res/values/strings.xml` | 同上，短版 |
| `@drawable/ui_launcher_icon` | `res/drawable/ui_launcher_icon.xml` | 矢量图，桌面图标 |
| `@drawable/core_ic_tile` | `res/drawable/core_ic_tile.xml` | 矢量图，快速设置磁贴图标 |
| `@drawable/core_ic_bubble` | `res/drawable/core_ic_bubble.xml` | 矢量图，悬浮球 |
| `@drawable/core_ic_notification` | `res/drawable/core_ic_notification.xml` | 矢量图，通知小图标（必须是纯白单色剪影） |
| `@xml/accessibility_service_config` | `res/xml/accessibility_service_config.xml` | 无障碍服务配置 |

## 5. `app/build.gradle.kts` 要求

- `namespace = "com.lazyrotate.app"`，`applicationId = "com.lazyrotate.app"`
- `compileSdk = 37`、`targetSdk = 36`、`minSdk = 24`
- `buildFeatures { compose = true }` + `org.jetbrains.kotlin.plugin.compose`
- `compileOptions` / `kotlin` 都用 Java 17
- `buildTypes { debug { ... }; release { isMinifyEnabled = false } }`
- 依赖（最小集，**不要加别的**）：

```
implementation(platform("androidx.compose:compose-bom:2026.09.00"))
implementation("androidx.core:core-ktx:<最新稳定>")
implementation("androidx.activity:activity-compose:<最新稳定>")
implementation("androidx.lifecycle:lifecycle-runtime-ktx:<最新稳定>")
implementation("androidx.lifecycle:lifecycle-viewmodel-compose:<最新稳定>")
implementation("androidx.compose.ui:ui")
implementation("androidx.compose.ui:ui-tooling-preview")
implementation("androidx.compose.material3:material3")
implementation("androidx.datastore:datastore-preferences:<最新稳定>")
implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:<最新稳定>")
debugImplementation("androidx.compose.ui:ui-tooling")
```

**不要**引入 Hilt/Koin/Room/Retrofit/Shizuku 依赖：本项目用手写 `ServiceLocator` 做组合根，
Shizuku 只作为文档里的可选升级路径。确需新增依赖 → 先开 Issue 讨论。

## 6. 实现约定

- 所有写系统设置的操作必须**幂等**且**无权限时返回 false 不抛异常**；UI 据此提示。
- `RotationTarget` 持久化只用 `code`，禁止存 `ordinal`。
- 前台包名监听在无障碍未开启时返回 `null`，不得崩溃、不得反复拉起设置页。
- 悬浮球/磁贴/通知三个入口都直接调用同一个 `RotationController`，不允许各自实现一套写设置逻辑。
- 悬浮球与前台服务在 Android 14+ 受 FGS 限制：只能由用户在前台触发启动（UI 按钮/磁贴），不要在后台自启。
- 躺姿引擎（`SensorOrientationEngine`）置信度不足时必须给出 `null`：
  **「不确定就不动」正是解决躺姿来回翻转的关键**，不要为了「看起来在工作」而给低置信度结果。

## 7. 构建与验证

```powershell
# 推荐：直接用一键脚本（它点源 toolchain-paths.ps1 并设好全部变量，还会刷新 local.properties）
.\tools\build.ps1

# 想手动跑 Gradle：先点源路径文件，别再手写路径
. .\tools\toolchain-paths.ps1
$env:JAVA_HOME        = $JdkHome
$env:GRADLE_USER_HOME = $GradleHome
$env:ANDROID_HOME     = $AndroidSdk
$env:ANDROID_SDK_ROOT = $AndroidSdk
$env:ANDROID_USER_HOME = $AndroidUser
.\gradlew.bat :app:assembleDebug --no-daemon
```

产物：`app/build/outputs/apk/debug/app-debug.apk`，并复制一份到 `dist/`。

## 8. 贡献方式

- 想改冻结契约（第 2、4、5 节）：先开 Issue 说明动机和影响面，达成一致再动手。
- 发现别处代码有问题：开 Issue 或在 PR 里说明，不要在同一个 PR 里顺手改无关模块。
- 提交前务必确认能编过：Windows 用 `.\tools\build.ps1`，其它平台用 `./gradlew :app:assembleDebug`。
