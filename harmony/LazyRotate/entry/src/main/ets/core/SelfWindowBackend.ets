import { common } from '@kit.AbilityKit';
import { window } from '@kit.ArkUI';
import { BusinessError } from '@kit.BasicServicesKit';
import { RotationBackend, RotationOutcome, RotationTarget } from './Contract';
import { currentRotationTarget } from './DeviceState';

/**
 * public SDK 可用的唯一后端：只影响本应用自己的主窗口。
 *
 * 依据：鸿蒙窗口旋转只对应用主窗口生效（非主窗口调用不生效），
 * 且没有面向三方应用的全局旋转写入接口。详见 docs/鸿蒙版-可行性评估.md。
 *
 * 「跟随系统」使用 AUTO_ROTATION_RESTRICTED：
 * 文档明确只有带 RESTRICTED 的自动旋转枚举**受控制中心旋转锁定开关控制**
 * （AUTO_ROTATION 反而不受控制），这与本应用「引导用户去控制中心开旋转锁定」的叙事一致。
 * 见 docs/鸿蒙工程-静态校验报告.md FAIL-1。
 */
export class SelfWindowBackend implements RotationBackend {
  readonly id: string = 'self-window';
  readonly displayName: string = '本应用窗口';
  readonly canAffectOtherApps: boolean = false;

  private readonly context: common.UIAbilityContext;

  constructor(context: common.UIAbilityContext) {
    this.context = context;
  }

  isAvailable(): boolean {
    return true;
  }

  async apply(target: RotationTarget): Promise<RotationOutcome> {
    try {
      const mainWindow: window.Window = await window.getLastWindow(this.context);
      await mainWindow.setPreferredOrientation(SelfWindowBackend.toOrientation(target));

      if (target === RotationTarget.AUTO) {
        return {
          ok: true,
          target: target,
          backend: this.displayName,
          message: '已交还系统：跟随重力，并受控制中心旋转锁定开关控制'
        };
      }

      // 回读校验：setPreferredOrientation 在自由多窗/分屏/被系统锁覆盖时会「静默不生效」，
      // 不能只凭调用成功就告诉用户「已应用」。
      await SelfWindowBackend.delay(400);
      const actual: RotationTarget = currentRotationTarget();
      if (actual === target) {
        return {
          ok: true,
          target: target,
          backend: this.displayName,
          message: `已应用：${SelfWindowBackend.label(target)}（仅本应用窗口）`
        };
      }
      return {
        ok: false,
        target: target,
        backend: this.displayName,
        message: `未确认生效：请求 ${SelfWindowBackend.label(target)}，当前仍是 ${SelfWindowBackend.label(actual)}（可能处于自由多窗/分屏，或被系统旋转锁定覆盖）`
      };
    } catch (error) {
      const err = error as BusinessError;
      return {
        ok: false,
        target: target,
        backend: this.displayName,
        message: `设置失败：${err.message ?? err.code}`
      };
    }
  }

  static toOrientation(target: RotationTarget): window.Orientation {
    switch (target) {
      case RotationTarget.PORTRAIT:
        return window.Orientation.PORTRAIT;
      case RotationTarget.LANDSCAPE:
        return window.Orientation.LANDSCAPE;
      case RotationTarget.PORTRAIT_INVERTED:
        return window.Orientation.PORTRAIT_INVERTED;
      case RotationTarget.LANDSCAPE_INVERTED:
        return window.Orientation.LANDSCAPE_INVERTED;
      default:
        return window.Orientation.AUTO_ROTATION_RESTRICTED;
    }
  }

  private static label(target: RotationTarget): string {
    switch (target) {
      case RotationTarget.PORTRAIT:
        return '竖屏';
      case RotationTarget.LANDSCAPE:
        return '横屏';
      case RotationTarget.PORTRAIT_INVERTED:
        return '反向竖屏';
      case RotationTarget.LANDSCAPE_INVERTED:
        return '反向横屏';
      default:
        return '跟随系统';
    }
  }

  private static delay(ms: number): Promise<void> {
    return new Promise<void>((resolve: (value: void) => void) => {
      setTimeout(() => {
        resolve();
      }, ms);
    });
  }
}
