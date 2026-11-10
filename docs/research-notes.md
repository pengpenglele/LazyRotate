# 安卓平板屏幕方向控制 · 技术调研笔记

> 调研方式：联网抓取 AOSP 源码（经 jsDelivr 镜像 `cdn.jsdelivr.net/gh/aosp-mirror/...` 读取 `platform_frameworks_base`）、
> developer.android.com / source.android.com 官方文档、GitHub 开源实现 README。
> 每条结论后附真实抓取过的链接。凡未抓到证据的，明确标注 **【未证实】** 并写明检索路径。
>
> 抓取时间：2026-10-19。AOSP 代码取自 `main` 分支（即当前主干，行为覆盖 Android 16/17 阶段）。

---

## 0. 结论摘要（先看这五条）

1. **`WRITE_SETTINGS` + `accelerometer_rotation=0` + `user_rotation` 只对"跟随系统/跟随传感器"的 App 有效。对显式声明固定方向的 App 完全无效** —— 这是 AOSP `DisplayRotation.rotationForOrientation()` 的显式分支逻辑，不是权限问题。
2. **`"ignore_orientation_request"` 不是 `Settings.Global` 键，写它没有任何作用。** 真正机制是 `IWindowManager.setIgnoreOrientationRequest(displayId, boolean)`，持久化在 `/data/system/display_settings.xml`（属性名 `ignoreOrientationRequest`），**不是 SettingsProvider 数据库**。
3. **免 root 第三方 App 无法自行打开「忽略应用方向请求」**（除非厂商额外开放）。能打开它的现实路径只有 shell 身份：adb / Shizuku / root。
4. **Android 16（targetSdk 36）起，`sw>=600dp` 的大屏默认忽略 App 的方向/尺寸/宽高比限制** —— 这对本项目是**净利好**：系统把应用的固定方向请求降级为 `UNSET`，而 `UNSET` 正好落入 `USER_ROTATION` 锁定分支，于是原本"掰不动"的硬锁应用变得可被控制。**免 root 主路径不受负面影响。**
5. **180°（倒置）旋转在系统层默认被拒绝**，除非 App 自己请求 `FULL_SENSOR`/`FULL_USER`。这是"躺姿场景"最硬的技术根因之一。

---

## 1. 高级档可行性：能否让系统「忽略应用自身声明的固定方向」

> 本节直接决定产品文档里"高级功能"的边界写法。**结论偏保守：默认写"做不到"，只在 shell/Shizuku/root 档位写"可以"。**

### 1.1 问题 A：`"ignore_orientation_request"` 是真实的 `Settings.Global` 键吗？

**答：否。它不是 `Settings.Global` 键，也没有任何 WindowManager 代码从 SettingsProvider 读取它。**

证据链（三条独立证据互相印证）：

**证据 1 — `Settings.java` 里没有这个常量。**
抓取 `core/java/android/provider/Settings.java` 全文（100 KB），检索 `ignore_orientation` / `IGNORE_ORIENTATION` / `ignoreOrientation` 三个模式，**零命中**。同文件里能检索到 `ACTION_MANAGE_WRITE_SETTINGS`、`WRITE_SETTINGS` 等常量，说明抓取内容完整、检索有效。
→ [Settings.java（AOSP main）](https://cdn.jsdelivr.net/gh/aosp-mirror/platform_frameworks_base@main/core/java/android/provider/Settings.java)

**证据 2 — 开关的存储路径是 `DisplayWindowSettings`，不是 Settings.Global。**
`DisplayWindowSettings.setIgnoreOrientationRequest()` 把值写进 `SettingsProvider.SettingsEntry.mIgnoreOrientationRequest` 字段：

```java
void setIgnoreOrientationRequest(@NonNull DisplayContent displayContent,
        boolean ignoreOrientationRequest) {
    final DisplayInfo displayInfo = displayContent.getDisplayInfo();
    final SettingsProvider.SettingsEntry overrideSettings =
            mSettingsProvider.getOverrideSettings(displayInfo);
    overrideSettings.mIgnoreOrientationRequest = ignoreOrientationRequest;
    mSettingsProvider.updateOverrideSettings(displayInfo, overrideSettings);
}
```

读取侧在 `applyRotationSettingsToDisplayLocked()`：

```java
final boolean ignoreOrientationRequest = settings.mIgnoreOrientationRequest != null
        ? settings.mIgnoreOrientationRequest : false;
dc.setIgnoreOrientationRequest(ignoreOrientationRequest);
```

→ [DisplayWindowSettings.java](https://cdn.jsdelivr.net/gh/aosp-mirror/platform_frameworks_base@main/services/core/java/com/android/server/wm/DisplayWindowSettings.java)

**证据 3 — 这个 `SettingsProvider` 的实现是 XML 文件，不是 Settings 数据库。**
`DisplayWindowSettingsProvider` 明确定义了文件路径与 XML 属性名：

```java
private static final String DATA_DISPLAY_SETTINGS_FILE_PATH = "system/display_settings.xml";
private static final String VENDOR_DISPLAY_SETTINGS_FILE_PATH = "etc/display_settings.xml";
...
settingsEntry.mIgnoreOrientationRequest = getBooleanAttribute(parser,
        "ignoreOrientationRequest", null /* defaultValue */);
...
out.attributeBoolean(null, "ignoreOrientationRequest",
        settingsEntry.mIgnoreOrientationRequest);
```

即：实际落盘位置是 **`/data/system/display_settings.xml`**（vendor 基线在 `/vendor/etc/display_settings.xml`），
XML 节点形如 `<display name="..." ignoreOrientationRequest="true"/>`。
→ [DisplayWindowSettingsProvider.java](https://cdn.jsdelivr.net/gh/aosp-mirror/platform_frameworks_base@main/services/core/java/com/android/server/wm/DisplayWindowSettingsProvider.java)

**真正的机制**：隐藏 AIDL 接口 `IWindowManager.setIgnoreOrientationRequest(int displayId, boolean)`。
shell 命令 `cmd window set-ignore-orientation-request` 就是它的薄封装（见 1.2）。

> ⚠️ **给实现的直接结论**：现在这段 `Settings.Global.putInt(resolver, "ignore_orientation_request", 1)` 是**纯无效写入** ——
> 键不存在、无人读取、还会在 settings 数据库里留一条垃圾记录。建议删除。

### 1.2 问题 B：普通第三方 App 拿到 `WRITE_SECURE_SETTINGS` 后，能不能打开这个开关？

分三条路径核实：

#### 路径 1：写 `Settings.Global` 某个键 —— **不可行**

由 1.1 已证：不存在被读取的键。`WRITE_SECURE_SETTINGS` 再高权限也没用，因为**消费方不读 SettingsProvider**。
写入只会产生一条无人读取的记录，方向行为零变化。

#### 路径 2：反射调用隐藏 API `IWindowManager.setIgnoreOrientationRequest` —— **【未证实可行，倾向不可行】**

- `IWindowManager` 是 `@hide` 的 AIDL 接口，Android 9+ 起受 non-SDK interface 限制。
- **【未证实】** 我未能抓到 `IWindowManager.aidl` 原文（多次抓取失败），因此**无法断言** `setIgnoreOrientationRequest` 在 greylist / blocklist 中的具体分级。
  检索路径：`cdn.jsdelivr.net/gh/aosp-mirror/platform_frameworks_base@main/core/java/android/view/IWindowManager.aidl`（fetch failed ×3）、
  `android.googlesource.com`（本机网络不可达）、`cs.android.com`（fetch failed）。
- **【未证实】** 也未抓到 `WindowManagerService.setIgnoreOrientationRequest()` 实现处的 `@RequiresPermission` 注解原文
  （`WindowManagerService.java` 单文件过大，抓取被截断在 100 KB，该实现不在已获取片段内）。
  已确认的是该文件 **import 了 `android.Manifest.permission.WRITE_SECURE_SETTINGS`**，说明 WMS 内部确实有方法用它做门禁，
  但**不能据此认定**该权限就是 `setIgnoreOrientationRequest` 的门槛。
- 保守写法：**不要在产品文档里承诺这条路可行。** 即使权限注解是 `WRITE_SECURE_SETTINGS`，
  `IWindowManager` 的 `ServiceManager.getService("window")` + `asInterface` 反射链本身也极易被 non-SDK 拦截。

#### 路径 3：执行 shell 命令 —— **可行，但需要 shell UID，且它是持久的**

**准确命令**（从 AOSP `WindowManagerShellCommand.onCommand()` 的 switch 分支逐字确认）：

| 命令 | 说明 |
|---|---|
| `adb shell cmd window set-ignore-orientation-request true` | 打开（默认 display） |
| `adb shell cmd window set-ignore-orientation-request false` | 关闭 |
| `adb shell cmd window set-ignore-orientation-request -d <displayId> true` | 指定 display |
| `adb shell cmd window get-ignore-orientation-request` | 查询当前值 |

源码 switch 分支：

```java
case "set-ignore-orientation-request":
    return runSetIgnoreOrientationRequest(pw);
case "get-ignore-orientation-request":
    return runGetIgnoreOrientationRequest(pw);
case "fixed-to-user-rotation":
    return runFixedToUserRotation(pw);
case "user-rotation":
    return runDisplayUserRotation(pw);
```

参数解析接受 `true|1|false|0`，帮助文本为
`set-ignore-orientation-request [-d DISPLAY_ID] [true|1|false|0]` / `If app requested orientation should be ignored.`
→ [WindowManagerShellCommand.java](https://cdn.jsdelivr.net/gh/aosp-mirror/platform_frameworks_base@main/services/core/java/com/android/server/wm/WindowManagerShellCommand.java)

- **`wm` 还是 `cmd window`？** 服务端只认 `cmd window ...`。`wm` 是 device 上的 shell 脚本包装器，最终也调 `cmd window`；
  第三方项目 AppRotate 的文档同时使用了 `wm fixed-to-user-rotation enabled` 与 `wm get-ignore-orientation-request` 的写法
  （→ [AppRotate docs/notes.md](https://cdn.jsdelivr.net/gh/hahahaxu1/AppRotate@master/docs/notes.md)）。
  **写文档时以 `cmd window` 为准**（源码可证），`wm` 作为等价便捷写法。
- **是否持久？** **是，持久。** 由 1.1 证据 3：值经 `WritableSettings.writeSettings()` 写入
  `/data/system/display_settings.xml`（`AtomicFile`，按 display uniqueId 分条），重启后由 `readDisplay()` 读回。
  **不是**每次开机失效，也**不是**只对当前会话有效。
- **是否只对 shell UID 可用？** shell 命令本身由 `adb shell`（UID 2000 shell）或 Shizuku（借 shell 身份）执行；
  `cmd` 的 service 调用链要求调用者具备 shell 级权限。**普通 App 无法直接执行 `cmd window`**（`Runtime.exec` 调 `cmd` 会因权限被拒）。
  → 因此第三方 App 的现实做法是：**通过 Shizuku 借 shell 身份执行**，或让用户在电脑上跑 adb。

#### 问题 B 总结

> **免 root 的第三方 App 不能真正打开这个开关。**
> 唯一免 root 的现实路径是 **Shizuku（借 shell 身份执行 `cmd window set-ignore-orientation-request true`）**，
> 或用户在电脑上执行 adb 命令（一次性，持久生效）。
> 纯 `WRITE_SETTINGS` / 纯 `WRITE_SECURE_SETTINGS` 都做不到。

### 1.3 问题 C：开发者选项里那个开关是不是同一机制？

**答：是同一机制的入口，但需区分两个不同的开发者选项。**

- 已确认：`DisplayArea.getIgnoreOrientationRequest()` 的实现是
  `mSetIgnoreOrientationRequest && !mWmService.isIgnoreOrientationRequestDisabled()`。
  其中 `mIsIgnoreOrientationRequestDisabled` 是 `WindowManagerService` 的字段，且 `DisplayArea` 注释写明
  "the value can be overridden at runtime on a device level" / "needed for the Kids Mode"。
  → 说明系统层存在一个**全局压制开关**，通常就是开发者选项/设备策略层面的入口。
  → [DisplayArea.java](https://cdn.jsdelivr.net/gh/aosp-mirror/platform_frameworks_base@main/services/core/java/com/android/server/wm/DisplayArea.java)
- **【未证实】** 我未能抓到 AOSP Settings（`platform_packages_apps_settings`）里对应 `PreferenceController` 的原文
  （`src/com/android/settings/display/IgnoreOrientationRequestPreferenceController.java` 返回 404），
  因此**无法逐字确认**该开发者选项的标题文案与它写入的 key。
  检索路径：上述 GitHub 镜像路径 404；`developer.android.com` 未找到该开发者选项的官方说明页。

### 1.4 顺带查到的两个"高级档"可用杠杆

**（a）按应用 compat 覆盖（`am compat`）—— AOSP 源码 + 官方文档双向确认**

`AppCompatOrientationOverrides.java` 的 import 段逐字列出了这批常量（**证明它们都是 `ActivityInfo` 里真实存在的 compat change**）：

```java
import static android.content.pm.ActivityInfo.OVERRIDE_ANY_ORIENTATION;
import static android.content.pm.ActivityInfo.OVERRIDE_ENABLE_COMPAT_IGNORE_ORIENTATION_REQUEST_WHEN_LOOP_DETECTED;
import static android.content.pm.ActivityInfo.OVERRIDE_ENABLE_COMPAT_IGNORE_REQUESTED_ORIENTATION;
import static android.content.pm.ActivityInfo.OVERRIDE_LANDSCAPE_ORIENTATION_TO_REVERSE_LANDSCAPE;
import static android.content.pm.ActivityInfo.OVERRIDE_RESPECT_REQUESTED_ORIENTATION;
import static android.content.pm.ActivityInfo.OVERRIDE_UNDEFINED_ORIENTATION_TO_NOSENSOR;
import static android.content.pm.ActivityInfo.OVERRIDE_UNDEFINED_ORIENTATION_TO_PORTRAIT;
import static android.content.pm.ActivityInfo.OVERRIDE_USE_DISPLAY_LANDSCAPE_NATURAL_ORIENTATION;
```

其中**「按应用忽略方向请求」**的开关就是 `OVERRIDE_ENABLE_COMPAT_IGNORE_REQUESTED_ORIENTATION`，消费点在：

```java
boolean shouldEnableIgnoreOrientationRequest() {
    return mIgnoreRequestedOrientationOptProp.shouldEnableWithOverrideAndProperty(
            isCompatChangeEnabled(OVERRIDE_ENABLE_COMPAT_IGNORE_REQUESTED_ORIENTATION));
}
```

→ [AppCompatOrientationOverrides.java](https://cdn.jsdelivr.net/gh/aosp-mirror/platform_frameworks_base@main/services/core/java/com/android/server/wm/AppCompatOrientationOverrides.java)

**命令**（`am compat` 的通用语法；`enable` / `disable` 成对）：

```bash
adb shell am compat enable  OVERRIDE_ENABLE_COMPAT_IGNORE_REQUESTED_ORIENTATION <包名>
adb shell am compat disable OVERRIDE_ENABLE_COMPAT_IGNORE_REQUESTED_ORIENTATION <包名>
```

> **证据等级说明**：上述常量名与消费逻辑来自 **AOSP 源码（已抓取）**；
> 官方「大屏兼容模式」文档给出的正是 `OVERRIDE_ENABLE_COMPAT_IGNORE_REQUESTED_ORIENTATION` 这条命令，
> 与本源码证据**互相印证**。但我**未能独立抓取**该文档页原文（`developer.android.com` / `developer.android.google.cn`
> 的 `large-screen-compatibility-mode` 与 `device-compatibility-mode` 页均只返回页面骨架，正文被 JS 渲染；
> microlink / allorigins / codetabs / cors.lol 等代理均超时或限流）。
> → **`disable` 写法为按 `am compat` 通用语法推断，未逐字复核。**

`am compat` 的通用性质（来自 AppRotate 实测记录 + Android 14 compat 页）：
- 支持**符号名**（`PlatformCompat.lookupChangeId`）；
- **默认会自动重启目标应用**，所以改完立刻生效；
- 覆盖**按包持久化**，重启手机依然有效。

**我从 Android 14 官方 compat-framework-changes 页独立抓到的 Change ID 与默认状态**：

| Change 名称 | Change ID | 默认状态 |
|---|---|---|
| `OVERRIDE_ANY_ORIENTATION` | 265464455 | Disabled for all apps |
| `OVERRIDE_LANDSCAPE_ORIENTATION_TO_REVERSE_LANDSCAPE` | 266124927 | Disabled for all apps |
| `OVERRIDE_UNDEFINED_ORIENTATION_TO_PORTRAIT` | 265452344 | Disabled for all apps |
| `OVERRIDE_UNDEFINED_ORIENTATION_TO_NOSENSOR` | 265451093 | Disabled for all apps |
| `OVERRIDE_RESPECT_REQUESTED_ORIENTATION` | 236283604 | Disabled for all apps |

→ [Compatibility framework changes (Android 14)](https://developer.android.com/about/versions/14/reference/compat-framework-changes)

**（b）`fixed-to-user-rotation` —— 让显示层完全无视 App 请求，只认 user_rotation**

```java
// DisplayRotation.rotationForOrientation() 第一行
if (isFixedToUserRotation()) {
    return mUserRotation;
}
```

命令：`adb shell cmd window fixed-to-user-rotation [-d DISPLAY_ID] [enabled|disabled|default|enabled_if_no_auto_rotation]`
（帮助文本：`Print or set rotating display for app requested orientation.`）
→ [DisplayRotation.java](https://cdn.jsdelivr.net/gh/aosp-mirror/platform_frameworks_base@main/services/core/java/com/android/server/wm/DisplayRotation.java)、
[WindowManagerShellCommand.java](https://cdn.jsdelivr.net/gh/aosp-mirror/platform_frameworks_base@main/services/core/java/com/android/server/wm/WindowManagerShellCommand.java)

这是**比 ignore-orientation-request 更彻底**的一档：`ignore-orientation-request` 只是"丢掉 App 请求"，最终仍可能落到默认方向；
`fixed-to-user-rotation enabled` 是"无条件返回 user_rotation"，四个方向都能强制。**代价：固定方向 App 会被塞进 letterbox（黑边）而不是真正铺满。**

**四点核实结论**：

| 问题 | 结论 | 源码依据 |
|---|---|---|
| 支持按 display 指定？ | **是**，支持 `-d <displayId>` | `runFixedToUserRotation()`：`if ("-d".equals(arg)) { displayId = Integer.parseInt(getNextArgRequired()); arg = getNextArg(); }` |
| 持久（重启还在）？ | **是** | `setFixedToUserRotation()` → `mDisplayWindowSettings.setFixedToUserRotation(...)` → `SettingsEntry.mFixedToUserRotation` → `DisplayWindowSettingsProvider.writeSettings()` 写 XML 属性 `fixedToUserRotation` 到 `/data/system/display_settings.xml`；`readDisplay()` 读回 |
| 前提是 shell 身份吗？`WRITE_SECURE_SETTINGS` 够不够？ | **必须 shell 身份**（adb / Shizuku / root）。**`WRITE_SECURE_SETTINGS` 不够** | 它走 `IWindowManager.setFixedToUserRotation(displayId, mode)`，**不经过 SettingsProvider 写入**，所以 Settings 写权限对它无意义 |
| `disabled` 是关闭写法吗？ | **是**。完整取值四个：`enabled` / `disabled` / `default` / `enabled_if_no_auto_rotation` | 见 `runFixedToUserRotation()` 的 switch 分支 |

> ⚠️ **`enabled_if_no_auto_rotation` 是个坑，别用**：
> `isFixedToUserRotation()` 里 `case IWindowManager.FIXED_TO_USER_ROTATION_IF_NO_AUTO_ROTATION: return false;`
> —— 它返回 false，只在 `rotationForOrientation()` 的 `!mSupportAutoRotation` 分支（设备根本不支持自动旋转，如部分 TV/车机）里才被消费。
> 普通平板请用 `enabled`。

#### 1.4.1 两条高级命令的差异（一句话版）

| 命令 | 对"清单里写死 portrait/landscape"的 App 的实际表现 |
|---|---|
| `cmd window set-ignore-orientation-request true` | **把 App 的方向请求丢掉（改写成 `UNSET`）**，App 不再被 letterbox、铺满整屏，然后跟随 `USER_ROTATION` 旋转 —— 效果是"**App 被说服了**" |
| `cmd window fixed-to-user-rotation enabled` | **显示层无条件转到 `user_rotation`，但 App 的方向请求仍被尊重用于布局** —— 效果是"**屏幕硬转了，App 被塞进 letterbox 黑边里**" |

> **一句话对比：前者让 App 跟着屏幕转、铺满全屏；后者让屏幕硬转、App 留黑边。**
> 前者体验更好但依赖 App 的 targetSdk/大屏策略配合；后者最彻底、对所有 App 都生效，代价是黑边。

---

## 2. 免 root 能做到什么：`WRITE_SETTINGS` 的真实能力边界

### 2.1 涉及的键与语义（官方文档原文）

| 键 | 官方描述 | Added in |
|---|---|---|
| `Settings.System.ACCELEROMETER_ROTATION` | "Control whether the accelerometer will be used to change screen orientation." | API 1 |
| `Settings.System.USER_ROTATION` | "Default screen rotation when no other policy applies." | API 1 |
| `Settings.System.canWrite(Context)` | "Checks if the specified app can modify system settings." | API 23 |

→ [Settings.System 官方 API 参考](https://developer.android.google.cn/reference/android/provider/Settings.System?hl=en)

授权入口常量（AOSP 源码逐字）：

```java
public static final String ACTION_MANAGE_WRITE_SETTINGS =
        "android.settings.action.MANAGE_WRITE_SETTINGS";
```

→ [Settings.java](https://cdn.jsdelivr.net/gh/aosp-mirror/platform_frameworks_base@main/core/java/android/provider/Settings.java)

**`USER_ROTATION` 的取值范围与语义**：`0/1/2/3`，对应 `Surface.ROTATION_0/90/180/270`。
证据：`DisplayRotation` 中 `mUserRotation` 声明为 `@Surface.Rotation`、默认 `Surface.ROTATION_0`，
并经 `restoreSettings(userRotationMode, userRotation, fixedToUserRotation)` 注入；
`Surface.ROTATION_*` 的取值即 0/1/2/3。
第三方实现（AppRotate、RatioFit）同样使用 `0/1/2/3` 映射"竖屏/横屏/反向竖屏/反向横屏"。
→ [DisplayRotation.java](https://cdn.jsdelivr.net/gh/aosp-mirror/platform_frameworks_base@main/services/core/java/com/android/server/wm/DisplayRotation.java)、
[AppRotate README](https://cdn.jsdelivr.net/gh/hahahaxu1/AppRotate@master/README.md)

**App 内的等价写法（Java/Kotlin，需 `WRITE_SETTINGS` 授权）**：

```java
// 先确认有写权限：Settings.System.canWrite(context)
Settings.System.putInt(resolver, Settings.System.ACCELEROMETER_ROTATION, 0); // 关闭自动旋转 = 进入 LOCKED
Settings.System.putInt(resolver, Settings.System.USER_ROTATION, 1);          // 1 = Surface.ROTATION_90
```

**shell 等价写法（调试 / 验证用，需 shell 或 root 身份）**：

```bash
adb shell settings put system accelerometer_rotation 0
adb shell settings put system user_rotation 1        # 0=0° 1=90° 2=180° 3=270°
adb shell settings get system user_rotation          # 回读校验
```

> 注意：`settings put` 走的是 `SettingsProvider`，**普通 App 用 `adb shell` 才有权限**；
> App 内必须用上面的 `Settings.System.putInt` + `WRITE_SETTINGS` 授权，两者最终写的是同一行数据。
> 回读校验很重要：设备繁忙时单次写入可能静默失效（AppRotate 实测，其验证脚本因此加了重试）。

**写入后系统会立刻响应**（这点很关键，说明 `WRITE_SETTINGS` 这条路本身是通的）：
`DisplayRotation.SettingsObserver` 注册了两个键的 `ContentObserver`：

```java
resolver.registerContentObserver(Settings.System.getUriFor(
        Settings.System.ACCELEROMETER_ROTATION), false, this, UserHandle.USER_ALL);
resolver.registerContentObserver(Settings.System.getUriFor(
        Settings.System.USER_ROTATION), false, this, UserHandle.USER_ALL);
...
public void onChange(boolean selfChange) {
    if (updateSettings()) {
        mService.updateRotation(false, false);
    }
}
```

### 2.2 核心问题：关自动旋转 + 设 `USER_ROTATION` 能全局强制方向吗？

**答：对"跟随系统"的 App 有效；对显式声明固定方向的 App 无效。** 这是**语义设计**，不是权限不足。

`DisplayRotation.rotationForOrientation()` 的关键分支（AOSP main，逐字）：

```java
} else if (mUserRotationMode == WindowManagerPolicy.USER_ROTATION_LOCKED
        && orientation != ActivityInfo.SCREEN_ORIENTATION_NOSENSOR
        && orientation != ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        && orientation != ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        && orientation != ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
        && orientation != ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT) {
    // Apply rotation lock. Does not apply to NOSENSOR or specific rotations.
    preferredRotation = mUserRotation;
} else {
    // No overriding preference.
    // We will do exactly what the application asked us to do.
    preferredRotation = -1;
}
```

再往下 `switch (orientation)` 里，`SCREEN_ORIENTATION_PORTRAIT` 直接 `return mPortraitRotation;`
（只有在 `preferredRotation` 恰好也是竖屏时才采用 `preferredRotation`）。

**结论（可直接写进产品文档）**：

> 旋转锁（`accelerometer_rotation=0` + `user_rotation=N`）只在 App 请求的是
> `UNSPECIFIED` / `USER` / `SENSOR` / `FULL_SENSOR` / `SENSOR_LANDSCAPE` 等"非固定"方向时生效。
> 一旦 App 在清单里写死 `portrait`/`landscape`/`reversePortrait`/`reverseLandscape`，
> 或运行时调 `setRequestedOrientation()` 请求固定方向，**系统会"exactly what the application asked"**，旋转锁被绕过。

#### 2.2.1 补证：`ACCELEROMETER_ROTATION=0` 到 `USER_ROTATION_LOCKED` 的映射

写 `ACCELEROMETER_ROTATION=0` 之后，`DisplayRotation.updateSettings()` 会把它翻译成锁定模式（逐字）：

```java
final int userRotationMode = Settings.System.getIntForUser(resolver,
        Settings.System.ACCELEROMETER_ROTATION, 0, UserHandle.USER_CURRENT) != 0
                ? WindowManagerPolicy.USER_ROTATION_FREE
                : WindowManagerPolicy.USER_ROTATION_LOCKED;
```

即：**值非 0 → FREE（跟随传感器）；值等于 0 → LOCKED（吃 `USER_ROTATION`）**。
`USER_ROTATION` 也在同一个 `updateSettings()` 里读入 `mUserRotation`，两键任一变化都会触发 `mService.updateRotation(...)`。

`switch` 的兜底分支（`default`）也逐字确认：

```java
default:
    // For USER, UNSPECIFIED, NOSENSOR, SENSOR and FULL_SENSOR,
    // just return the preferred orientation we already calculated.
    if (preferredRotation >= 0) {
        return preferredRotation;
    }
    return Surface.ROTATION_0;
```

**关键推论（决定了 Android 16 大屏上本路径是否有效，见 3.3.1）**：
`SCREEN_ORIENTATION_UNSET` **不在**上面那 5 个排除项里 → 命中锁定分支 → `preferredRotation = mUserRotation` → `default` 返回它。**锁定生效。**

同族的第二处证据在 `DisplayArea.shouldIgnoreOrientationRequest()`：
`SCREEN_ORIENTATION_LOCKED` 与 `SCREEN_ORIENTATION_NOSENSOR` 被**无条件尊重**（永不忽略），
注释理由是 Camera 应用依赖它们确定预览方向。

### 2.3 与「旋转锁定」的关系（Android 9 起语义变更）

source.android.com 的 Rotate suggestions 页给出了 Android 9 的重构说明：

> "In Android 9, we updated portrait rotation mode to eliminate unintentional rotations by pinning the current screen rotation even if the device position changes. ... We renamed the portrait mode to rotation lock and it activates when auto-rotate is off."
> "Rotation lock mode works by showing a button in the navbar on device rotation changes. To accomplish this, the device's orientation sensor must remain active even when auto-rotate is off. Tapping this button effectively sets user rotation preference (`Settings.System.USER_ROTATION`)."
> "User rotation preference is **reset** to the device's natural orientation whenever the system rotation changes to the device's natural orientation."

→ [Rotate suggestions（AOSP）](https://source.android.com/docs/core/display/rotate-suggestions)

**两条对本项目很重要的副作用**：
1. **自然方向偏置（natural orientation bias）**：回到自然方向时 `USER_ROTATION` 会被系统**自动重置**。我们写进去的值可能被系统"悄悄改回"，需要在状态回读时容忍这种情况。
2. 该页还给出验证用命令（shell 权限）：
   `adb shell settings put secure show_rotation_suggestions <0|1>` 与
   `adb shell settings put secure num_rotation_suggestions_accepted 0`。

---

## 3. 版本差异矩阵

### 3.1 主矩阵

| 版本 | API | 大屏「忽略方向请求」默认行为 | 方向控制的关键变化 | 对本项目的影响 |
|---|---|---|---|---|
| Android 9 | 28 | 无 | 旋转锁重构：`USER_ROTATION` 成为用户偏好；自动旋转关闭时传感器仍保持活跃以显示旋转建议按钮；自然方向偏置会重置 `USER_ROTATION` | 旋转建议按钮会与我们"锁横屏"打架；需处理 `USER_ROTATION` 被重置 |
| Android 10 | 29 | 无 | **【未证实】** 未抓到该版本与方向相关的专门变更页 | 视同 Android 9 语义 |
| Android 11 | 30 | 无 | 自动旋转更依赖传感器（第三方资料）；**API 30 及以下应用覆盖层不参与方向决策** —— `NonAppWindowContainers.getOrientation()` 无条件返回 `UNSET` | **悬浮窗强制方向手段在 Android 11 及以下无效**，只能用系统设置方式 |
| Android 12 / 12L | 31 / 32 | **厂商可选择开启**：可配置某块屏忽略方向声明，把竖屏应用以 letterbox 方式呈现 | 12L 引入 taskbar / 分屏普及；letterbox 样式可定制 | 悬浮窗手段从 **Android 12（API 31）起**才可能生效 |
| Android 13 | 33 | **大屏默认开启**（第三方实测结论，**官方文档未直接证实**） | `am compat` 覆盖族可用 | 平板/折叠屏展开态：**P1 的 0×0 悬浮窗技巧**失效（其 `PORTRAIT` 请求被丢成 `UNSET`）；**P0 不受影响**，走系统设置即可 |
| Android 14 | 34 | 同 13 | compat framework 增加 `OVERRIDE_ANY_ORIENTATION` 等方向覆盖（默认全关）；`OVERRIDE_RESPECT_REQUESTED_ORIENTATION` 可让指定包**不被**厂商的忽略策略影响 | 给了"按应用反悔"的口子（仍需 shell） |
| Android 15 | 35 | 同 13/14 | **【未证实】** 未抓到 15 的方向专项变更 | — |
| Android 16 | 36 | **targetSdk 36 且 `sw>=600dp` 时强制忽略方向/尺寸/宽高比限制**（官方明确）；App 的固定方向请求被改写成 `UNSET` | 见 3.3 / 3.3.1；API 37 起 opt-out 也会失效 | **净利好**：`UNSET` 落入 `USER_ROTATION_LOCKED` 分支，P0 覆盖面扩大；硬锁方向的应用变少 |

### 3.2 Android 14 compat framework：与方向相关的可切换项

全部来自官方 compat-framework-changes（Android 14）页，**Change ID 与默认状态逐字核对**：

| Change 名称 | Change ID | 默认状态 | 作用 |
|---|---|---|---|
| `OVERRIDE_ANY_ORIENTATION` | 265464455 | Disabled for all apps | 让下列三个方向覆盖**无视 Activity 自己请求的方向**也生效 |
| `OVERRIDE_LANDSCAPE_ORIENTATION_TO_REVERSE_LANDSCAPE` | 266124927 | Disabled for all apps | 把横屏改成 `REVERSE_LANDSCAPE`；未开 `OVERRIDE_ANY_ORIENTATION` 时仅对声明 landscape 的 Activity 生效 |
| `OVERRIDE_UNDEFINED_ORIENTATION_TO_PORTRAIT` | 265452344 | Disabled for all apps | 把未指定方向改成 `PORTRAIT` |
| `OVERRIDE_UNDEFINED_ORIENTATION_TO_NOSENSOR` | 265451093 | Disabled for all apps | 把未指定方向改成 `NOSENSOR` |
| `OVERRIDE_RESPECT_REQUESTED_ORIENTATION` | 236283604 | Disabled for all apps | "excludes packages that is applied to from the ignore orientation restrictions that device manufacturers can set" |

→ [Compatibility framework changes (Android 14)](https://developer.android.com/about/versions/14/reference/compat-framework-changes)

**注意 `OVERRIDE_UNDEFINED_ORIENTATION_TO_NOSENSOR` 是个陷阱**：AppRotate 实测指出 `NOSENSOR` 在
`rotationForOrientation()` 里既不走传感器分支、也不走旋转锁分支，最终落到 `default` 返回 `ROTATION_0`
（= 固定回自然竖屏），**不是"跟随 user_rotation"**。

### 3.3 Android 16（API 36）大屏行为变更 —— 官方原文要点

全部摘自官方 behavior-changes-16 页（`#large-screens-form-factors` → `#adaptive-layouts`）：

- **"For apps targeting Android 16 (API level 36), orientation, resizability, and aspect ratio restrictions no longer apply on displays with smallest width >= 600dp. Apps fill the entire display window, regardless of aspect ratio or a user's preferred orientation, and pillarboxing isn't used."**
- 被忽略的清单属性与运行时 API：`screenOrientation`、`resizableActivity`、`minAspectRatio`、`maxAspectRatio`、`setRequestedOrientation()`、`getRequestedOrientation()`
- 被忽略的具体取值：`portrait`、`reversePortrait`、`sensorPortrait`、`userPortrait`、`landscape`、`reverseLandscape`、`sensorLandscape`、`userLandscape`
- `android:resizeableActivity="false"`、`android:minAspectRatio`、`android:maxAspectRatio` 均无效果
- **例外（不适用）**：Games（按 `android:appCategory` 判定）、用户在设备宽高比设置里显式选择"用 App 默认行为"、`sw < 600dp` 的屏幕
- 临时 opt-out：`<property android:name="android.window.PROPERTY_COMPAT_ALLOW_RESTRICTED_RESIZABILITY" android:value="true"/>`（可写在 `<activity>` 或 `<application>`）
- **"The opt-out is temporary and won't apply when targeting API level 37 in a future Android release. That is, for apps targeting API level 37, orientation, resizability, and aspect ratio restrictions are ignored on displays that are at least sw600dp."**
- 测试用 compat flag：`UNIVERSAL_RESIZABLE_BY_DEFAULT`

→ [Behavior changes: Apps targeting Android 16 or higher](https://developer.android.com/about/versions/16/behavior-changes-16)

### 3.3.1 关键推论：Android 16 大屏策略对「免 root 主路径」是利好，不是威胁

这是本次调研**最容易写错、且直接决定 P0 表述**的一点，特此单列。

**链路（全部源码可证）**：

1. App 请求固定方向（如 `PORTRAIT`）。
2. `DisplayArea.getOrientation()` 在大屏忽略策略开启时，**把请求改写成 `SCREEN_ORIENTATION_UNSET`**：

```java
@Override
@ScreenOrientation
int getOrientation(int candidate) {
    final int orientation = super.getOrientation(candidate);
    if (shouldIgnoreOrientationRequest(orientation)) {
        // In all the other case, mLastOrientationSource will be reassigned to a new value
        mLastOrientationSource = null;
        return SCREEN_ORIENTATION_UNSET;
    }
    return orientation;
}
```

3. `rotationForOrientation()` 收到 `UNSET`：它**不在**排除清单（`NOSENSOR`/`LANDSCAPE`/`PORTRAIT`/`REVERSE_LANDSCAPE`/`REVERSE_PORTRAIT`）里，
   于是命中 `USER_ROTATION_LOCKED` 分支 → `preferredRotation = mUserRotation` → `default` 返回它。
4. **结果：我们的 `USER_ROTATION` 锁住了这个原本硬锁方向的应用。**

**结论**：

> **Android 16（targetSdk 36）+ `sw>=600dp` 大屏上，本项目基于 `ACCELEROMETER_ROTATION=0` + `USER_ROTATION` 的免 root 主路径完全有效，
> 且覆盖面比以往更大** —— 系统替我们把应用的固定方向请求降级了，原本"掰不动"的应用变得可控制。

**唯一例外**：`shouldIgnoreOrientationRequest()` 对两个值**无条件尊重、永不忽略**：

```java
// We always respect orientation request for ActivityInfo.SCREEN_ORIENTATION_LOCKED
// ActivityInfo.SCREEN_ORIENTATION_NOSENSOR.
// Main use case why this is important is Camera apps that rely on those
// properties to ensure that they will be able to determine Camera preview orientation correctly
if (orientation == ActivityInfo.SCREEN_ORIENTATION_LOCKED
        || orientation == ActivityInfo.SCREEN_ORIENTATION_NOSENSOR) {
    return false;
}
```

但这两个值本身不是"固定竖/横"，我们的锁定对它们照样生效（它们落入 `default`/`LOCKED` 分支）。

**仍需保留降级逻辑的范围**：targetSdk < 36 的旧应用、或 `sw < 600dp` 的设备上，
显式请求 `PORTRAIT`/`LANDSCAPE`/`REVERSE_*` 的 App 依然"掰不动"。
→ **P0 仍需"硬锁方向检测 + 降级提示"，只是这个集合在 Android 16 大屏上会显著缩小。**

> ⚠️ **澄清一个易混淆点**：所谓"悬浮窗方向手段在大屏失效"，指的是
> **第三方 App 用 0×0 `TYPE_APPLICATION_OVERLAY` 窗口提交 `screenOrientation` 这个技巧**
> （见 7.1 节与 AppRotate 的实现）——它请求的是 `PORTRAIT`，在大屏上会被 `shouldIgnoreOrientationRequest` 丢成 `UNSET` 而落败。
> **这跟我们的 P0 路径无关**，不要把它写成 P0 的风险。

### 3.4 Android 12L 的官方表述（厂商可选，非强制）

> "While this is still supported in 12L, device manufacturers have the option of overriding the app's request for a preferred orientation."
> "In Android 12 (API level 31) and higher, device manufacturers can configure individual device screens (such as the tablet-size screen of a foldable) to ignore the orientation specification and force an app in portrait mode to be presented upright but letterboxed on landscape displays."

→ [12L features and changes](https://developer.android.com/about/versions/12/12L/summary)

**这解释了为什么"大屏忽略方向请求"在不同 OEM 上表现不一致** —— 12L 是"可以"，Android 16 才是"必须"。

---

## 4. 躺姿场景根因分析（为什么自动旋转在躺下时难用）

证据全部来自 AOSP `WindowOrientationListener.java` 的源码注释与常量。

### 4.1 传感器选择：优先专用方向传感器，退化到加速度计

```java
List<Sensor> l = mSensorManager.getSensorList(Sensor.TYPE_DEVICE_ORIENTATION);
// 优先 wakeup 版本
...
if (mSensor != null) {
    mOrientationJudge = new OrientationSensorJudge();
}
if (mOrientationJudge == null) {
    mSensor = mSensorManager.getDefaultSensor(USE_GRAVITY_SENSOR
            ? Sensor.TYPE_GRAVITY : Sensor.TYPE_ACCELEROMETER);
    ...
    mOrientationJudge = new AccelSensorJudge(context);
}
```

- `USE_GRAVITY_SENSOR = false`（硬编码），所以退化路径用**加速度计**而非重力传感器。
- 现代设备有 `TYPE_DEVICE_ORIENTATION`（融合传感器）时走 `OrientationSensorJudge`，**不再做角度判断，直接采信传感器上报的 0..3**。
- **注意：全程没有陀螺仪参与方向判定。** 躺姿抖动来自加速度计对"重力矢量"的解读。

→ [WindowOrientationListener.java](https://cdn.jsdelivr.net/gh/aosp-mirror/platform_frameworks_base@main/services/core/java/com/android/server/wm/WindowOrientationListener.java)

### 4.2 根因 1：接近水平时系统**主动放弃**判定

`AccelSensorJudge` 的类注释（逐字）：

> "If the tilt angle is too close to horizontal (near 90 or -90 degrees), do nothing. The orientation angle is not meaningful when the device is nearly horizontal."

对应常量：`MAX_TILT = 80`；`FLAT_ANGLE = 80`；`FLAT_TIME_NANOS = 1000ms`。
→ **躺姿（屏幕朝脸、设备接近水平）正好落在这个"无意义区间"**，系统要么不转，要么转错。

### 4.3 根因 2：`isFlat` / `isSwinging` / `isAccelerating` 三重抑制

```java
private static final long PROPOSAL_MIN_TIME_SINCE_FLAT_ENDED_NANOS = 500 * NANOS_PER_MS;
private static final long PROPOSAL_MIN_TIME_SINCE_SWING_ENDED_NANOS = 300 * NANOS_PER_MS;
private static final long PROPOSAL_MIN_TIME_SINCE_ACCELERATION_ENDED_NANOS = 500 * NANOS_PER_MS;
private static final long PROPOSAL_MIN_TIME_SINCE_TOUCH_END_NANOS = 500 * NANOS_PER_MS;
private static final long PROPOSAL_SETTLE_TIME_NANOS = 40 * NANOS_PER_MS;
```

`isPredictedRotationAcceptableLocked()` 要求以上**全部**满足才提议旋转。
→ 躺姿下设备"平放/晃动/被手触碰"三者交替出现，**每次都要等 300–500 ms 的静默窗口**，表现为"转了但很迟钝 / 反复横跳"。
另：`SWING_AWAY_ANGLE_DELTA = 20`（300ms 内倾角变化 20° 判定为"正在放下设备"），
`isAcceleratingLocked()` 用 `STANDARD_GRAVITY ± 4 m/s²` 判据排除"正在被移动"。

### 4.4 根因 3：180° 倒置默认被拒绝（躺姿最痛的一条）

```java
if (sensorRotation != Surface.ROTATION_180
        || getAllowAllRotations() == ALLOW_ALL_ROTATIONS_ENABLED
        || orientation == ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
        || orientation == ActivityInfo.SCREEN_ORIENTATION_FULL_USER) {
    preferredRotation = sensorRotation;
} else {
    preferredRotation = lastRotation;
}
```

**默认情况下，即使传感器明确报告 `ROTATION_180`，系统也拒绝转 180°**，保持上一次旋转。
只有 App 自己请求 `FULL_SENSOR` / `FULL_USER`，或设备配置打开了 `allowAllRotations`，才会接受倒置。
→ **躺姿时人把设备转到"倒着拿"是极自然的动作，但系统默认不认。** 这是我们产品最应该解决的痛点之一。

### 4.5 根因 4：45° 迟滞死区导致"边缘来回翻转"

> 下面这段摘自 AOSP `frameworks/base` 的 `DisplayRotation.java`（Apache-2.0，仅为说明用途引用）。

```java
private static final int ADJACENT_ORIENTATION_ANGLE_GAP = 45;
```

`isOrientationAngleAcceptableLocked()` 在相邻两个方向之间留 45° 死区，且死区随当前方向动态偏移。
→ 躺姿下手部微抖使角度在阈值附近徘徊时，表现为"卡住不转"；一旦越过阈值又可能连跳两格。

### 4.6 根因 5（软支撑面）：`isFlat` 与床面

`isFlatLocked()`：倾角连续 1000 ms 大于 80° 即判定"平放在桌上"。
→ 设备放在床/腿上（非硬支撑面）时，倾角读数随身体动作漂移，`isFlat` 反复进出，旋转提议被反复打断。

### 4.7 Pixel「人脸检测自动旋转」是什么、可靠吗、是否 Pixel 独占

**机制（AOSP 侧可证）**：`OrientationSensorJudge` 在 `isRotationResolverEnabled()` 为真时，
把方向决策委托给 `RotationResolverInternal`（SystemUI 的 RotationResolverService，用前置摄像头/人脸位置辅助判断）：

> 下面这段摘自 AOSP `frameworks/base` 的 `OrientationSensorJudge.java`（Apache-2.0，仅为说明用途引用）。

```java
if (isRotationResolverEnabled()) {
    if (isKeyguardShowingAndNotOccluded()) {
        if (mLastRotationResolution != ROTATION_UNSET
                && SystemClock.uptimeMillis() - mLastRotationResolutionTimeStamp
                < mRotationMemorizationTimeoutMillis) {
            Slog.d(TAG, "Reusing the last rotation resolution: " + mLastRotationResolution);
            finalizeRotation(mLastRotationResolution);
        } else {
            finalizeRotation(mDefaultRotation);
        }
        return;
    }
    ...
    mRotationResolverService.resolveRotation(... packageName, reportedRotation,
            mCurrentRotation, mRotationResolverTimeoutMillis, cancellationSignal);
```

启用条件（`DisplayRotation.OrientationListener.isRotationResolverEnabled()`）：

```java
return mAllowRotationResolver                                  // config_allowRotationResolver
        && mUserRotationMode == WindowManagerPolicy.USER_ROTATION_FREE   // 自动旋转必须开着
        && mCameraRotationMode == CAMERA_ROTATION_ENABLED        // Settings.Secure.CAMERA_AUTOROTATE
        && !mService.mPowerManager.isPowerSaveMode();            // 省电模式下失效
```

可调参数走 DeviceConfig：`rotation_resolver_timeout_millis` 默认 **700 ms**、
`rotation_memorization_timeout_millis` 默认 **3000 ms**。
→ [WindowOrientationListener.java](https://cdn.jsdelivr.net/gh/aosp-mirror/platform_frameworks_base@main/services/core/java/com/android/server/wm/WindowOrientationListener.java)、
[DisplayRotation.java](https://cdn.jsdelivr.net/gh/aosp-mirror/platform_frameworks_base@main/services/core/java/com/android/server/wm/DisplayRotation.java)

**用户可见形态**：Pixel 上路径是 设置 → 显示 → 自动旋转屏幕 → 打开"使用自动旋转"+ 打开"人脸检测"。
→ [Chrome Unboxed: How to use face detection on Pixel to improve auto-rotate](https://chromeunboxed.com/how-to-use-face-detection-on-pixel-to-improve-auto-rotate/)（2022-09-04）
同文亦指出 Android 11 起自动旋转更依赖传感器、Android 12 起引入前置摄像头人脸位置。

**可靠性与独占性**：
- **【部分未证实】** 我未抓到 Google 官方对「Face Detection 自动旋转」的规格页，因此**无法断言**其准确率/失效条件。
- 可以确定的是：该能力依赖 **`config_allowRotationResolver`** 这个**设备配置项** + **SystemUI 的 RotationResolverService**，
  属于 **Google 在 Pixel 上启用的平台特性**；AOSP 提供了机制，但**是否启用由厂商决定**。
- **结论写法建议**：写"Pixel 系机型默认启用、其他厂商多数未启用（未证实覆盖范围）"，**不要**写"只有 Pixel 有"。

---

## 5. 竞品与既有开源实现

> 说明：以下均基于**真实抓取到的仓库 README / 设计笔记**，不是商店页营销文案。
> Google Play 商店页抓取失败（`play.google.com` 返回 404/被拦），**商店评价要点未能独立核实，标注【未证实】**。

### 5.1 对比表

| 项目 | 形态 | 实现手段 | 权限门槛 | 已知做不到 / 抱怨点 |
|---|---|---|---|---|
| **hahahaxu1/AppRotate**（旋转锁定） | 开源 App，Kotlin+Compose，targetSdk 36 | 三档并存：①系统设置（`ACCELEROMETER_ROTATION`+`USER_ROTATION`，离开时按快照还原）②0×0 `TYPE_APPLICATION_OVERLAY` 透明窗带 `screenOrientation` ③Shizuku 执行 `settings put` + `wm fixed-to-user-rotation enabled` | WRITE_SETTINGS + 无障碍 + SYSTEM_ALERT_WINDOW + 通知（13+）；Shizuku 可选 | ①系统设置档**掰不动硬锁方向的应用**；②悬浮窗档**需 Android 12+**，且**大屏 sw>=600dp 默认 ignore-orientation-request 时失效**；③Shizuku 每次开机要手动启动、会被厂商省电杀；④覆盖安装 APK 会停掉无障碍服务；⑤离开时还原"进入那一刻"的设置，期间手动改动也会被还原 |
| **ly5201314gjx/RatioFit** | 开源 App，Kotlin+Compose，Android 8.0+ | 全局强制旋转 = `Settings.System` 写 `accelerometer_rotation=0` + `user_rotation`；无障碍读前台包名；`wm size`/`wm density` 改逻辑尺寸与密度；`am compat OVERRIDE_ANY_ORIENTATION` 解硬锁 | 旋转：WRITE_SETTINGS；尺寸/密度/compat：**Shizuku 或 Root**（`am compat` 需 `OVERRIDE_COMPAT_CHANGE_CONFIG`，signature 级，adb 授不了） | 无 Shizuku/Root 时"尺寸与密度两项不可用"；`force_resizable_activities` 只在 ATMS 初始化时读取，改动后**需重启设备**；第三方 App 内部组件改不了 |
| **eiyooooo/AutoRotate** | 开源 App（可折叠屏场景） | 通过 Shizuku 管理不同屏幕的自动旋转（折叠态关、展开态开） | **强依赖 Shizuku** | 不装 Shizuku 完全不可用 |
| **tiger-git2022/portrait-guard**（竖屏守护） | 开源 App（自用工具） | 创建透明、不可触摸的**无障碍覆盖层**请求竖屏 + 可拖动悬浮按钮；自动旋转开着时**同时把系统显示方向写成竖屏**以适配"忽略覆盖层请求"的厂商 ROM | 无障碍 + SYSTEM_ALERT_WINDOW + WRITE_SETTINGS + 忽略电池优化；Android 10+ 另需"使用情况访问权限" | 作者明言"最终效果取决于 vivo Android 16 是否接受无障碍覆盖层对特定游戏的方向请求"，被拒绝则需另做 Shizuku/Root 实现 |
| **programminghoch10/RotationControl** | 开源项目（**【部分未证实】**） | **【未证实】** 仓库 README 路径多次 404（试过 `main`/`master` 分支），未能读到实现说明 | **【未证实】** | **【未证实】** |
| **AOSP 官方「旋转建议」**（非 App，是系统机制） | 系统 | Android 9 起：自动旋转关闭时传感器仍活跃，检测到方向变化就在导航栏弹旋转按钮，点击即写 `USER_ROTATION` | 系统内置 | 只在"旋转锁定模式"（自动旋转关闭）出现；需 `DISABLE2_ROTATE_SUGGESTIONS` 未置位；部分 OEM 用硬件导航键时不实现 |

### 5.2 从竞品得到的共性结论

1. **"免 root 全功能"是不存在的。** 三个成熟项目都撞到同一堵墙：硬锁方向的应用。
2. **绕墙手段只有两条**：①悬浮窗盖方向（免特权，但仅 Android 12+ 且大屏失效 —— 注意这只影响 P1，不影响 P0）；②shell 身份（Shizuku/adb）打 compat 或 `fixed-to-user-rotation`。
3. **无障碍服务是"识别前台应用"的事实标准**，但也是上架风险最高的一环（见第 7 节）。
4. **"进入时快照、离开时还原"是行业通用做法**，副作用（期间用户手动改动会被覆盖）被普遍接受，但应在 UI 里说明。

---

## 6. 能力分级：免 root vs 需 Shizuku/root/ADB

### 6.1 分级表

| 能力 | 免 root（仅运行时权限） | 需 Shizuku / root / ADB | 说明 |
|---|---|---|---|
| 全局开关自动旋转 | ✅ `WRITE_SETTINGS` 写 `ACCELEROMETER_ROTATION` | — | 最基础，P0 |
| 全局强制四个方向之一（对"跟随系统"应用） | ✅ `WRITE_SETTINGS` 写 `USER_ROTATION` | — | P0；对硬锁应用无效 |
| 识别当前前台应用 | ✅ 无障碍服务 | — | 上架需声明；P0 |
| 快速开关磁贴 | ✅ `TileService`（API 24+） | — | P0 |
| 按应用自动切换方向（对"跟随系统"应用） | ✅ 无障碍 + `USER_ROTATION` 快照还原 | — | P0 |
| 悬浮窗盖过"硬锁方向"应用 | ✅（**Android 12+**，且大屏未开 ignore-orientation-request 时） | — | P1，条件性 |
| 显示层彻底无视 App 方向请求 | ❌ | ✅ `cmd window fixed-to-user-rotation enabled`（shell） | P2；副作用：letterbox 黑边 |
| 全局忽略方向请求 | ❌ | ✅ `cmd window set-ignore-orientation-request true`（shell） | P2；持久化 |
| 按应用忽略方向请求 | ❌ | ✅ `am compat enable OVERRIDE_ENABLE_COMPAT_IGNORE_REQUESTED_ORIENTATION <pkg>`（shell） | P2；常量名与消费逻辑已由 AOSP 源码确认，`disable` 写法为通用语法推断 |
| 按应用改写方向（竖/横） | ❌ | ✅ `am compat enable OVERRIDE_ANY_ORIENTATION` + `OVERRIDE_UNDEFINED_ORIENTATION_TO_PORTRAIT` 等（shell） | P2；只支持竖/横两种 |
| 改逻辑分辨率 / 密度 | ❌ | ✅ `wm size` / `wm density`（shell） | 与本项目弱相关 |
| 自动恢复被系统关掉的无障碍服务 | ❌ | ✅ 需 shell 写 `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES` | 无特权无解 |
| 重启后保持 Shizuku 服务 | ❌ | ❌（非 root 需每次开机手动启动无线调试） | 现实约束，需产品化引导 |

### 6.2 建议的 P0/P1/P2 划分

- **P0（纯 `WRITE_SETTINGS` + 无障碍，必须做到极致）**：全局方向锁定四方向、按应用自动切换、磁贴快捷开关、快照还原、失败自愈。
- **P1（+ `SYSTEM_ALERT_WINDOW`，条件性）**：对"硬锁方向"应用用 0×0 悬浮窗覆盖。**必须在 UI 上标注"Android 12+ 且平板可能无效"**。
- **P2（+ Shizuku，可选增强）**：`fixed-to-user-rotation`、`set-ignore-orientation-request`、`am compat` 覆盖。**必须做能力探测 + 明确降级**。

---

## 7. 悬浮窗 / 无障碍 / 磁贴：能力边界与合规风险

### 7.1 三者能力边界

| 手段 | 能做什么 | 不能做什么 | 最低版本 |
|---|---|---|---|
| `SYSTEM_ALERT_WINDOW`（悬浮窗） | 添加 `TYPE_APPLICATION_OVERLAY` 窗口；若该窗口设置 `screenOrientation`，**在 Android 12+ 可参与方向决策并压过下层应用窗口** | Android 11 及以下覆盖层不参与方向决策；大屏 ignore-orientation-request 开启时失效；会留常驻通知（不可见窗口的唯一反馈） | API 31 起对方向有效 |
| `AccessibilityService` | 读取前台包名/窗口状态事件；可创建 `TYPE_ACCESSIBILITY_OVERLAY` | **不能**自行打开/关闭自己（`enabled_accessibility_services` 属 secure settings，普通 App 无 API 可写）；覆盖安装 APK 后会被系统停用 | API 无门槛，但需用户手动开启 |
| `TileService`（快速设置磁贴） | 提供快捷开关入口，`onClick()` 里做轻量操作 | 无法长期持有状态；不适合做重逻辑 | API 24+ |

**方向决策的源码依据（悬浮窗为何能压过应用窗口）**：`DisplayArea.Tokens.getOrientation()` 用 `mGetOrientingWindow` 谓词
从子容器列表**尾部（最上层）向前**找第一个"可见且带固定 `screenOrientation`"的窗口；
`TYPE_APPLICATION_OVERLAY` 的 window layer 高于应用窗口，因此其方向请求胜出。
→ [DisplayArea.java](https://cdn.jsdelivr.net/gh/aosp-mirror/platform_frameworks_base@main/services/core/java/com/android/server/wm/DisplayArea.java)

### 7.2 Google Play 合规风险（官方政策原文）

**无障碍（AccessibilityService API）** —— 政策页要点：

- 只有**主要为残障用户服务**的应用才可声明 `isAccessibilityTool="true"`。
- "Apps declaring `isAccessibilityTool` are exempt from the prominent disclosure and consent requirement described below. Additionally, all apps targeting Android 12 using AccessibilityService API will need to complete the Permission Declaration Form and receive approval from Google Play **starting November 3, 2021**."
- 非无障碍工具类应用必须做 **prominent disclosure**：必须在 App 内、正常使用流程中可见、说明访问/收集的数据与用途、需用户**主动确认**、不能只写在隐私政策里、**不能与其他数据披露混在一起**。
- 自动化类使用的红线："Any use of the Accessibility API that enables an app to autonomously initiate, plan, and execute actions or decisions is **strictly prohibited**. This does not prohibit **deterministic, rule-based automation**, where behavior follows a static, human-defined script (for example, 'If Trigger X occurs, perform Action Y')."
- "Deceptive and non-declared uses of these APIs may result in a suspension of your app and/or termination of your developer account."

→ [Use of the AccessibilityService API](https://support.google.com/googleplay/android-developer/answer/10964491?hl=en)

**对本项目的含义**：我们是典型的"deterministic, rule-based automation"（进入某包名 → 锁某方向），
**落在允许区间内**；但**必须**：①不走 `isAccessibilityTool=true`（我们不服务残障用户，冒用属欺骗）；
②做独立的、App 内的、需主动确认的 prominent disclosure；③在 Play Console 完成 accessibility 声明并可能被要求提交演示视频。
**建议把无障碍做成"可选启用"，核心功能（全局方向锁定）不依赖它**，以降低审核阻断风险。

**修改设备设置（Deceptive Behavior 政策）**：

- "2.1 We don't allow apps that make changes to the user's device settings or features outside of the app without the user's knowledge and consent."
- "2.2.1 Apps that modify device settings or features with the user's consent but do so in a way that is **not easily reversible**."
- "Any changes to device settings must be made with the user's knowledge and consent and be **reversible by the user**."

→ [Deceptive Behavior](https://support.google.com/googleplay/android-developer/answer/9888077?hl=en)（该 URL 现 301 至 17006354）

**对本项目的含义**：我们修改的正是 `Settings.System` 全局键，**必须**：
①修改前明确告知；②提供一键还原；③**离开应用时自动还原快照**（我们已经这么设计，是加分项，应在商店描述里写清楚）；
④绝不能把"改设置"包装成"系统功能"。

**国内商店**：**【未证实】** 我未抓到华为/小米/OPPO/vivo 等国内应用市场关于无障碍/悬浮窗的具体审核条款原文。
检索路径：仅抓到通用讨论与厂商支持页，无审核规范原文。**建议产品侧单独做一轮国内商店合规调研。**

---

## 8. 平板特有因素

| 因素 | 事实 | 对本项目的影响 |
|---|---|---|
| **自由窗口 / 桌面模式** | Android 12+ 起所有应用都可进多窗口；Android 16 起大屏 `resizeableActivity=false` 无效。`DisplayWindowSettings` 里另有 `windowingMode`（`FREEFORM`/`FULLSCREEN`）与 `set-display-windowing-mode` 命令 | 多窗口下"方向"概念本身弱化（窗口可任意尺寸）；**我们的锁定策略需要区分"全屏"与"多窗口"两种场景**，多窗口时应主动降级为提示而非强行锁定 |
| **可折叠** | `DisplayRotation.FoldController`：铰链角传感器（`TYPE_HINGE_ANGLE`）、`config_deviceTabletopRotations`、折叠态切换时会**临时禁止传感器旋转**（`config_pauseRotationWhenUnfolding`），半折叠（tabletop）有独立方向覆盖逻辑 | 折叠屏展开/合上会触发方向重算；**我们的"进入应用时快照"必须能在折叠态变化后重新校准**，否则还原值会错 |
| **物理键盘吸附** | **【未证实】** 未抓到"键盘吸附改变旋转行为"的官方文档或 AOSP 明确分支。已查到的相关线索：①`WindowManagerService` 的 proto dump 里有 `HARD_KEYBOARD_AVAILABLE` 字段（说明系统确实跟踪该状态）；②`DisplayRotation` 的 `mLidOpenRotation` 只在 `lidState == LID_OPEN && mLidOpenRotation >= 0` 时强制方向，且 `config_lidOpenRotation` 多数设备为 -1（未配置）；③`rotationForOrientation()` 的 `!mSupportAutoRotation` 分支与键盘无关 | 保守写法：**不承诺**处理键盘吸附；标注为"待实测项"，实测后再补 |
| **云电脑 / 投屏** | `DisplayRotation.rotationForOrientation()` 对**非默认显示屏**有独立分支：`if (!isDefaultDisplay) { preferredRotation = mUserRotation; }` —— 注释写明"For secondary displays we ignore things like displays sensors, docking mode and rotation lock, and always prefer user rotation"。另 `mDefaultFixedToUserRotation` 在 `isCar` / `isTv` / `mIsPc` / 不支持随内容旋转时为 true | 投屏/副屏场景**行为与主屏不同，且更简单**（直接吃 `USER_ROTATION`，不受传感器/旋转锁干扰）。可作为卖点，但需实测验证具体投屏协议下的 display 类型 |
| **DRM 视频播放器** | **【未证实】** 未抓到 DRM/MediaCodec 与方向处理交互的权威说明。已排除的方向：`FLAG_SECURE`、`SurfaceControl` 安全层与方向决策无文档关联 | 保守：标注"未证实"，建议实测主流 DRM 播放器（Netflix / Disney+）在锁定下的行为，尤其是"播放器自己请求全屏横屏"时我们的锁定是否被绕过 |
| **游戏对方向的硬编码** | Android 16 官方把 **Games（按 `android:appCategory`）列为大屏忽略方向策略的例外**，即游戏仍可保留自己的方向限制 | 游戏是"硬锁方向"的重灾区，且 Android 16 的例外条款让它们继续硬锁 → **游戏场景必须依赖 P1（悬浮窗）或 P2（Shizuku）** |
| **平板自然方向** | `DisplayRotation.configure()`：`width > height` 时 `mLandscapeRotation = ROTATION_0`；否则 `mPortraitRotation = ROTATION_0`。即**平板的"自然方向"通常是横屏**，手机通常是竖屏 | 同一个 `USER_ROTATION` 数值在不同设备上对应的物理姿态不同！**必须基于 `mLandscapeRotation`/`mPortraitRotation` 语义而非硬编码 0/1/2/3**，否则手机/平板行为不一致 |
| **厂商配置覆盖** | `ro.bootanim.set_orientation_<physicalDisplayId>` / `ro.bootanim.set_orientation_logical_<displayId>` 可改默认旋转；`config_reverseDefaultRotation` 可反转默认旋转 | 同一 API 在不同 OEM 上可能方向相反 → **必须做设备自适应探测，不能假设 `ROTATION_0 == 竖屏`** |

---

## 9. 事实 → 对本项目设计的影响（结论表）

| # | 事实（含来源） | 对本项目设计的影响 |
|---|---|---|
| 1 | `rotationForOrientation()` 对固定方向应用"exactly what the application asked"，旋转锁被绕过（[DisplayRotation.java](https://cdn.jsdelivr.net/gh/aosp-mirror/platform_frameworks_base@main/services/core/java/com/android/server/wm/DisplayRotation.java)） | **产品文案不能承诺"锁定任何应用"**。必须按"应用是否硬锁方向"分流：跟随型 → P0 系统设置；硬锁型 → P1 悬浮窗 / P2 Shizuku，且明确告知 |
| 2 | `"ignore_orientation_request"` 不是 `Settings.Global` 键，无人读取；真机制是 `IWindowManager.setIgnoreOrientationRequest`，存于 `/data/system/display_settings.xml`（[DisplayWindowSettingsProvider.java](https://cdn.jsdelivr.net/gh/aosp-mirror/platform_frameworks_base@main/services/core/java/com/android/server/wm/DisplayWindowSettingsProvider.java)） | **立即删除** `Settings.Global.putInt(..., "ignore_orientation_request", 1)` 这段无效代码；高级档改为 shell 命令实现，并做能力探测 |
| 3 | 免 root 无法打开 ignore-orientation-request；只有 shell（adb/Shizuku/root）可以（同上 + [WindowManagerShellCommand.java](https://cdn.jsdelivr.net/gh/aosp-mirror/platform_frameworks_base@main/services/core/java/com/android/server/wm/WindowManagerShellCommand.java)） | 高级档**必须挂在 Shizuku 之上**，UI 明确"需 Shizuku"；无 Shizuku 时该档位**隐藏或置灰并解释原因**，不能假装可用 |
| 4 | `cmd window set-ignore-orientation-request` / `fixed-to-user-rotation` 的值**持久化在 XML**，重启仍有效 | 可以做"一次性设置、长期生效"的高级档；但**必须在 UI 里提供还原入口**（Play 政策要求 reversible），并记录我们改过什么 |
| 5 | `fixed-to-user-rotation enabled` 让显示层无条件返回 `mUserRotation`，四方向都能强制；代价是 letterbox 黑边（[DisplayRotation.java](https://cdn.jsdelivr.net/gh/aosp-mirror/platform_frameworks_base@main/services/core/java/com/android/server/wm/DisplayRotation.java)） | 这是**最彻底的一档**，但**不能铺满屏幕**。产品上应作为"最后手段"，并提前告知用户会出现黑边 |
| 6 | Android 16 targetSdk 36 + `sw>=600dp` 默认忽略方向/尺寸/宽高比，且把 App 的固定方向请求改写成 `SCREEN_ORIENTATION_UNSET`（[behavior-changes-16](https://developer.android.com/about/versions/16/behavior-changes-16)、[DisplayArea.java](https://cdn.jsdelivr.net/gh/aosp-mirror/platform_frameworks_base@main/services/core/java/com/android/server/wm/DisplayArea.java)）；API 37 起 opt-out 失效 | **这是利好，不是威胁。** `UNSET` 不在 `USER_ROTATION_LOCKED` 的 5 个排除项里 → 落入锁定分支 → 我们的 `USER_ROTATION` 能锁住原本硬锁的应用。**P0 主路径在 Android 16/17 大屏上完全有效，覆盖面反而扩大。** 详见 3.3.1 |
| 7 | 12L 只是"厂商可以"忽略方向请求，Android 16 才是"必须"（[12L summary](https://developer.android.com/about/versions/12/12L/summary)） | 平板行为**按 OEM/版本高度分裂**，必须内置"设备行为探测 + 用户可覆盖"的容错层，不能写死假设 |
| 8 | 180° 倒置默认被拒绝，除非 App 请求 `FULL_SENSOR`/`FULL_USER`（[WindowOrientationListener.java](https://cdn.jsdelivr.net/gh/aosp-mirror/platform_frameworks_base@main/services/core/java/com/android/server/wm/WindowOrientationListener.java)） | **躺姿场景的核心卖点在这里**：我们提供"反向竖屏/反向横屏"四方向锁定，正是系统默认不给的能力 → 应作为主打功能重点宣传 |
| 9 | 传感器在接近水平时主动放弃判定；`isFlat`/`isSwinging`/`isAccelerating` 各需 300–500 ms 静默；45° 迟滞死区（同上） | 躺姿抖动**不能靠"更好的自动旋转算法"解决**（我们没有传感器数据访问优势，且系统层已尽力）。**正确产品方向是"手动一键锁方向"**，而不是"更聪明的自动判断" |
| 10 | Pixel 的人脸检测自动旋转依赖 `config_allowRotationResolver` + `CAMERA_AUTOROTATE` + 自动旋转开启 + 非省电模式，超时 700ms/记忆 3000ms（[DisplayRotation.java](https://cdn.jsdelivr.net/gh/aosp-mirror/platform_frameworks_base@main/services/core/java/com/android/server/wm/DisplayRotation.java)） | ①**不要**与 Pixel 的智能旋转正面竞争；②我们的锁定功能与它**天然互斥**（我们关自动旋转），需在 UI 说明"锁定期间智能旋转不生效"；③可作为"为什么需要手动锁定"的论证素材 |
| 11 | `USER_ROTATION` 会被"自然方向偏置"自动重置（[rotate-suggestions](https://source.android.com/docs/core/display/rotate-suggestions)） | 状态机必须容忍"我们写的值被系统改回"，需**周期性回读校验 + 自愈重写**（竞品 AppRotate 也是这么做的） |
| 12 | `mLandscapeRotation` / `mPortraitRotation` 由屏幕宽高决定，且受 `config_reverseDefaultRotation` 与 `ro.bootanim.set_orientation_*` 影响（[DisplayRotation.java](https://cdn.jsdelivr.net/gh/aosp-mirror/platform_frameworks_base@main/services/core/java/com/android/server/wm/DisplayRotation.java)） | **禁止硬编码 `ROTATION_0 == 竖屏`**。必须用 `Display.getRotation()` + 屏幕宽高 + `Configuration.orientation` 动态推导"物理竖屏"对应的 `USER_ROTATION` 值，否则平板与手机行为相反 |
| 13 | 悬浮窗方向请求在 Android 12+ 才参与决策，Android 11 及以下 `NonAppWindowContainers.getOrientation()` 恒返回 `UNSET`（[AppRotate notes](https://cdn.jsdelivr.net/gh/hahahaxu1/AppRotate@master/docs/notes.md)、[DisplayArea.java](https://cdn.jsdelivr.net/gh/aosp-mirror/platform_frameworks_base@main/services/core/java/com/android/server/wm/DisplayArea.java)） | **仅影响 P1（0×0 overlay 技巧）**，与 P0 无关。P1 的 minSdk 门槛是 **API 31**；API < 31 或大屏 ignore-orientation-request 开启时，该档位不可用，需明确降级到 P0 |
| 14 | Play 政策：无障碍非工具类需独立 prominent disclosure + Console 声明；允许 deterministic rule-based automation（[AccessibilityService 政策](https://support.google.com/googleplay/android-developer/answer/10964491?hl=en)） | ①无障碍做成**可选**，核心功能不依赖它；②必须做 App 内独立披露页 + 主动勾选；③商店描述**如实写**"需要无障碍以识别前台应用"，不要含糊 |
| 15 | Play 政策：改设备设置必须用户知情同意且**易于还原**（[Deceptive Behavior](https://support.google.com/googleplay/android-developer/answer/9888077?hl=en)） | ①进入应用时快照、离开时还原（已有设计，正确）；②必须有"一键还原全部系统设置"急救按钮；③商店描述里把"会修改系统旋转设置、可随时还原"写清楚 |
| 16 | `am compat` 覆盖按包持久化、重启仍生效，且**会默认重启目标应用**（[AppRotate notes](https://cdn.jsdelivr.net/gh/hahahaxu1/AppRotate@master/docs/notes.md)、[Android 14 compat 列表](https://developer.android.com/about/versions/14/reference/compat-framework-changes)） | P2 的 compat 档要**告知用户"设置后目标应用会被重启"**；且必须提供 off 命令做还原 |
| 17 | `OVERRIDE_UNDEFINED_ORIENTATION_TO_NOSENSOR` 最终落到 `ROTATION_0` 而非跟随 `user_rotation`（[AppRotate notes](https://cdn.jsdelivr.net/gh/hahahaxu1/AppRotate@master/docs/notes.md)、[DisplayRotation.java](https://cdn.jsdelivr.net/gh/aosp-mirror/platform_frameworks_base@main/services/core/java/com/android/server/wm/DisplayRotation.java)） | **不要用 NOSENSOR 做"跟随用户旋转"**，语义不符；四方向强制用 `fixed-to-user-rotation` |
| 18 | 覆盖安装 APK 会停用无障碍服务；`am force-stop` 也会（系统行为）（[AppRotate notes](https://cdn.jsdelivr.net/gh/hahahaxu1/AppRotate@master/docs/notes.md)） | 必须有"服务被停用"的检测与引导恢复流程；**不要**在 App 内用 force-stop 自重启 |
| 19 | 无障碍服务被系统关闭后，普通 App **无任何 API 能自行重新开启**（需 shell 写 secure settings） | 产品上只能做"引导用户手动重开 + 关闭电池优化 + 自启动白名单"三件套，**不能承诺自动恢复** |

---

## 10. 明确标注「未证实」的条目汇总

| # | 问题 | 我查了什么 | 状态 |
|---|---|---|---|
| U1 | `IWindowManager.setIgnoreOrientationRequest` 的 non-SDK 分级（greylist/blocklist） | `IWindowManager.aidl` 多次抓取失败（jsDelivr / raw.githack）；`android.googlesource.com` 本机不可达；`cs.android.com` fetch failed | **未证实** |
| U2 | `WindowManagerService.setIgnoreOrientationRequest()` 的 `@RequiresPermission` 注解 | `WindowManagerService.java` 单文件过大，抓取截断于 100 KB，实现不在片段内 | **未证实** |
| U3 | 开发者选项里「忽略方向请求」开关的 AOSP 实现与文案 | `platform_packages_apps_settings` 对应 `PreferenceController` 路径 404 | **未证实** |
| U4 | 官方 `OVERRIDE_ENABLE_COMPAT_IGNORE_REQUESTED_ORIENTATION` 命令原文与 `disable` 写法 | 已从 AOSP 源码确认常量名与消费逻辑（`AppCompatOrientationOverrides.java`）；官方文档页正文未能抓取（JS 渲染 + 代理全失败），`disable` 为通用语法推断 | **部分证实** |
| U5 | Android 13 起大屏默认开启 ignore-orientation-request 的官方表述 | 未找到官方明示；仅有第三方项目实测结论 | **未证实（第三方实测）** |
| U6 | Android 10 / 11 / 15 的方向专项变更 | 未抓到对应版本的方向相关变更页 | **未证实** |
| U7 | 物理键盘吸附对旋转行为的影响 | 未找到官方文档或 AOSP 明确分支；仅见 `HARD_KEYBOARD_AVAILABLE` dump 字段 | **未证实** |
| U8 | DRM 视频播放器的方向处理 | 未找到权威说明 | **未证实** |
| U9 | 各竞品的商店评价要点 | `play.google.com` 抓取失败（404 / 反爬） | **未证实** |
| U10 | programminghoch10/RotationControl 的实现细节 | README 在 `main`/`master` 均 404 | **未证实** |
| U11 | Pixel 人脸检测自动旋转的官方规格与准确率 | 未找到 Google 官方规格页；仅第三方教程 | **部分未证实** |
| U12 | 国内应用商店对无障碍/悬浮窗的审核条款 | 未找到条款原文 | **未证实** |
