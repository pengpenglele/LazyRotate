import { common } from '@kit.AbilityKit';
import { deviceInfo, settings } from '@kit.BasicServicesKit';

/**
 * 系统旋转设置：只读探测 + 跳转设置页。
 *
 * 注意
 * - 读取 `ACCELEROMETER_ROTATION_STATUS` 可能因权限/数据项缺失而失败，失败时返回 'unknown'。
 * - 写入系统设置需要 ohos.permission.MANAGE_SETTINGS（仅系统应用），本工程不申请。
 * - `settings.openDisplaySettingsPage` 从 API 26 开始提供，仅在 Phone/Tablet/PC/2in1 生效。
 */

export const AUTO_ROTATION_UNKNOWN: string = 'unknown';

export function readAutoRotationStatus(context: common.UIAbilityContext): string {
  try {
    const value: string = settings.getValueSync(
      context,
      settings.general.ACCELEROMETER_ROTATION_STATUS,
      AUTO_ROTATION_UNKNOWN
    );
    return value;
  } catch (error) {
    return AUTO_ROTATION_UNKNOWN;
  }
}

export function autoRotationLabel(value: string): string {
  if (value === '1') {
    return '已开启';
  }
  if (value === '0') {
    return '已关闭';
  }
  return '未知（系统可能未开放读取）';
}

/** 打开系统「显示和亮度」页面；返回是否已成功拉起。 */
export function openDisplaySettingsPage(context: common.UIAbilityContext): boolean {
  try {
    if (deviceInfo.sdkApiVersion >= 26) {
      settings.openDisplaySettingsPage(context);
      return true;
    }
  } catch (error) {
    return false;
  }
  return false;
}

/** 当前设备 API 级别，用于 UI 上标注能力。 */
export function currentApiVersion(): number {
  return deviceInfo.sdkApiVersion;
}
