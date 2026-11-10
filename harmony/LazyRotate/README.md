# 屏幕方向控制 LazyRotate · 鸿蒙版（HarmonyOS 7 / API 26）

> 安卓版的鸿蒙移植工程。**能力边界与安卓版不同**，务必先读这一节，再读
> [可行性评估](../../docs/鸿蒙版-可行性评估.md) 与 [构建与运行说明](../../docs/鸿蒙工程-构建与运行.md)。

## 一句话边界

**鸿蒙普通第三方应用无法全局锁定/翻转其它 App 的方向。** 本工程可落地的能力是：

- ✅ 本应用窗口方向控制：竖屏 / 横屏 / 反向竖屏 / 反向横屏 / 跟随系统，一键锁定当前、翻转 180°
- ✅ 躺姿检测（重力传感器，置信度不足时明确「不确定」），建议并一键锁定
- ✅ 应用内悬浮球（可拖动、记住位置）：单击循环「跟随 → 锁定当前 → 翻转」
- ✅ 一键跳系统「显示和亮度」页（`settings.openDisplaySettingsPage`，API 26）
- ✅ 外部 Want 指令（`hdc` / 其它应用拉起），用于自动化与快捷方式
- ❌ 全局锁定其它应用 / 全局翻转 / 按应用规则 / 三方磁贴 / 跨应用悬浮球

真·全局锁定只有「系统签名/预置应用」路线（`screen.setScreenRotationLocked` 等系统接口，非系统应用返回 202）。
参考示例见 [SystemRotationBackend.example.ets](docs/SystemRotationBackend.example.ets)（**不在默认构建内**）。

## 快速开始

前置：**DevEco Studio 26.0.0+**、HarmonyOS SDK **API 26**、一台鸿蒙 7 平板/手机。

```powershell
# 1. 打开工程：DevEco Studio → File > Open → 选择本目录（harmony/LazyRotate）
# 2. 等待 Sync（首次会下载/校验 SDK 与依赖）
# 3. File > Project Structure > Signing Configs → 勾选 Automatically generate signature（需登录华为账号）
# 4. 连接设备（开启开发者模式 + USB 调试），点击 Run
```

详细步骤、配置版本不匹配的兜底方案、hdc 指令与真机验证清单，见
[鸿蒙工程-构建与运行](../../docs/鸿蒙工程-构建与运行.md)。

## 目录

```
harmony/LazyRotate/
├── AppScope/                              # 应用级配置与图标
├── build-profile.json5                    # compatibleSdkVersion = 7.0.0(26)
├── oh-package.json5
├── hvigor/                                # hvigor 版本配置
├── entry/src/main/
│   ├── module.json5                       # 入口 Ability、外部指令 skills
│   ├── ets/
│   │   ├── entryability/EntryAbility.ets  # 解析 Want 指令 → AppStorage
│   │   ├── core/                          # 契约、后端、显示状态、偏好、躺姿引擎
│   │   └── pages/Index.ets                # 单页三 Tab：控制 / 说明 / 设置
│   └── resources/                         # 字符串、颜色、图标、页面路由
└── docs/SystemRotationBackend.example.ets # 系统签名版后端参考（不参与构建）
```

## 设计约定（与安卓版对齐）

- 方向目标持久化只存字符串 code，不存 ordinal；
- 所有入口（UI / 悬浮球 / 外部指令 / 启动恢复）都走同一个 `RotationService`；
- 失败不抛异常、不误报成功：`RotationOutcome.ok = false` + 可读 message；
- 不确定就不动：躺姿置信度不足时只提示，不自动改方向；
- 不申请网络权限；不读取屏幕内容；传感器数据仅本地计算。

## 状态

- 本机无 DevEco Studio / 鸿蒙 SDK，**未做真机编译**；已完成配置与源码的静态校验（18 项通过，见
  [静态校验报告](../../docs/鸿蒙工程-静态校验报告.md)）。
- 独立审查发现的两条功能性硬伤（`AUTO_ROTATION` 不受系统旋转锁控制、`display.rotation` 在平板上映射反向）
  已修复，修复记录见报告的「附录 A2 · 修复跟进」。
- 首次在 DevEco 打开时若提示 `modelVersion` / `hvigorVersion` 版本不匹配，按
  [构建与运行说明](../../docs/鸿蒙工程-构建与运行.md) 的「兜底方案」处理即可。
