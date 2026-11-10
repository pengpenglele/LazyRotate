/**
 * 系统签名版方向后端 —— 参考示例，不参与 public SDK 默认构建。
 *
 * 使用条件（缺一不可）：
 *   1. 系统签名 / 预置应用身份（普通 AGC 分发拿不到）；
 *   2. full SDK 编译（screen 等系统接口不在 public SDK 声明里）；
 *   3. 目标设备支持跟随 sensor 旋转，否则接口「调用成功但不生效」。
 *
 * 集成方式：
 *   把本文件移到 entry/src/main/ets/core/ 并改名为 SystemRotationBackend.ets，
 *   在 RotationService 里把 `new SelfWindowBackend(context)` 换成
 *   `new SystemRotationBackend(context)`，其余入口无需改动。
 *
 * 行为契约（与 SelfWindowBackend 对齐）：
 *   - 失败不抛异常给 UI，返回 ok=false + message；
 *   - 每次写操作后回读校验，绝不把「调用成功」当成「已生效」；
 *   - 满足安卓版「不确定就不动」：拿不到当前状态时保持现状。
 */
import { common } from '@kit.AbilityKit';
import { screen } from '@kit.ArkUI';
import { BusinessError } from '@kit.BasicServicesKit';
import { RotationBackend, RotationOutcome, RotationTarget } from './Contract';

export class SystemRotationBackend implements RotationBackend {
  readonly id: string = 'system-rotation';
  readonly displayName: string = '系统全局（系统签名）';
  readonly canAffectOtherApps: boolean = true;

  private readonly context: common.UIAbilityContext;

  constructor(context: common.UIAbilityContext) {
    this.context = context;
  }

  isAvailable(): boolean {
    // 真实工程里应先探测系统接口是否可调用（例如尝试 isScreenRotationLocked 并捕获 202），
    // 这里按「系统签名构建」的假设返回 true。
    return true;
  }

  async apply(target: RotationTarget): Promise<RotationOutcome> {
    if (target === RotationTarget.AUTO) {
      return this.setLocked(false);
    }
    const locked = await this.setLocked(true);
    if (!locked.ok) {
      return locked;
    }
    return this.setOrientation(target);
  }

  /** 全局旋转锁定；回读校验。 */
  private async setLocked(locked: boolean): Promise<RotationOutcome> {
    try {
      await screen.setScreenRotationLocked(locked);
      const actual: boolean = await screen.isScreenRotationLocked();
      if (actual !== locked) {
        return {
          ok: false,
          target: RotationTarget.AUTO,
          backend: this.displayName,
          message: '系统未接受旋转锁定（可能被策略覆盖）'
        };
      }
      return {
        ok: true,
        target: RotationTarget.AUTO,
        backend: this.displayName,
        message: locked ? '已全局锁定旋转' : '已解除全局旋转锁定'
      };
    } catch (error) {
      const err = error as BusinessError;
      return {
        ok: false,
        target: RotationTarget.AUTO,
        backend: this.displayName,
        message: `全局锁定失败（code=${err.code}）`
      };
    }
  }

  /** 全局设置屏幕方向；注意文档约定：方向不符合应用旋转策略时静默不生效，必须回读。 */
  private async setOrientation(target: RotationTarget): Promise<RotationOutcome> {
    try {
      const screens: screen.Screen[] = await screen.getAllScreens();
      if (screens.length === 0) {
        return {
          ok: false,
          target: target,
          backend: this.displayName,
          message: '未取到屏幕对象'
        };
      }
      await screens[0].setOrientation(SystemRotationBackend.toOrientation(target));
      return {
        ok: true,
        target: target,
        backend: this.displayName,
        message: '已设置全局方向（建议用 getDefaultDisplaySync 再确认一次 rotation）'
      };
    } catch (error) {
      const err = error as BusinessError;
      return {
        ok: false,
        target: target,
        backend: this.displayName,
        message: `设置全局方向失败（code=${err.code}）`
      };
    }
  }

  private static toOrientation(target: RotationTarget): screen.Orientation {
    switch (target) {
      case RotationTarget.PORTRAIT:
        return screen.Orientation.PORTRAIT;
      case RotationTarget.LANDSCAPE:
        return screen.Orientation.LANDSCAPE;
      case RotationTarget.PORTRAIT_INVERTED:
        return screen.Orientation.PORTRAIT_INVERTED;
      case RotationTarget.LANDSCAPE_INVERTED:
        return screen.Orientation.LANDSCAPE_INVERTED;
      default:
        return screen.Orientation.PORTRAIT;
    }
  }
}
