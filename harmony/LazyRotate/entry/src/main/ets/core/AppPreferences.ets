import { common } from '@kit.AbilityKit';
import { preferences } from '@kit.ArkData';
import { RotationTarget, parseRotationTarget } from './Contract';

const STORE_NAME: string = 'lazyrotate_store';
const KEY_LAST_TARGET: string = 'last_target';
const KEY_RESTORE_ON_LAUNCH: string = 'restore_on_launch';
const KEY_FLOAT_ENABLED: string = 'float_enabled';
const KEY_FLOAT_X: string = 'float_x';
const KEY_FLOAT_Y: string = 'float_y';
const KEY_GUIDE_READ: string = 'guide_read';

export class AppPreferences {
  private store: preferences.Preferences | undefined = undefined;

  init(context: common.UIAbilityContext): void {
    try {
      this.store = preferences.getPreferencesSync(context, { name: STORE_NAME });
    } catch (error) {
      this.store = undefined;
    }
  }

  /** 偏好存储是否可用；不可用时 UI 必须提示「本次不保存」。 */
  isReady(): boolean {
    return this.store !== undefined;
  }

  getLastTarget(): RotationTarget {
    const raw = this.getString(KEY_LAST_TARGET, RotationTarget.AUTO);
    return parseRotationTarget(raw);
  }

  saveLastTarget(target: RotationTarget): void {
    this.putString(KEY_LAST_TARGET, target);
  }

  getRestoreOnLaunch(): boolean {
    return this.getBoolean(KEY_RESTORE_ON_LAUNCH, true);
  }

  setRestoreOnLaunch(enabled: boolean): void {
    this.putBoolean(KEY_RESTORE_ON_LAUNCH, enabled);
  }

  getFloatingBallEnabled(): boolean {
    return this.getBoolean(KEY_FLOAT_ENABLED, true);
  }

  setFloatingBallEnabled(enabled: boolean): void {
    this.putBoolean(KEY_FLOAT_ENABLED, enabled);
  }

  getFloatingPosition(): number[] {
    const x = this.getNumber(KEY_FLOAT_X, -1);
    const y = this.getNumber(KEY_FLOAT_Y, -1);
    return [x, y];
  }

  saveFloatingPosition(x: number, y: number): void {
    this.putNumber(KEY_FLOAT_X, x);
    this.putNumber(KEY_FLOAT_Y, y);
  }

  getGuideRead(): boolean {
    return this.getBoolean(KEY_GUIDE_READ, false);
  }

  setGuideRead(read: boolean): void {
    this.putBoolean(KEY_GUIDE_READ, read);
  }

  private getString(key: string, defValue: string): string {
    if (this.store === undefined) {
      return defValue;
    }
    try {
      const value = this.store.getSync(key, defValue);
      if (typeof value === 'string') {
        return value;
      }
      return defValue;
    } catch (error) {
      return defValue;
    }
  }

  private putString(key: string, value: string): void {
    if (this.store === undefined) {
      return;
    }
    try {
      this.store.putSync(key, value);
      this.store.flush().catch(() => {
        // 落盘失败不影响当前会话
      });
    } catch (error) {
      // 持久化失败不影响当前会话
    }
  }

  private getBoolean(key: string, defValue: boolean): boolean {
    if (this.store === undefined) {
      return defValue;
    }
    try {
      const value = this.store.getSync(key, defValue);
      if (typeof value === 'boolean') {
        return value;
      }
      return defValue;
    } catch (error) {
      return defValue;
    }
  }

  private putBoolean(key: string, value: boolean): void {
    if (this.store === undefined) {
      return;
    }
    try {
      this.store.putSync(key, value);
      this.store.flush().catch(() => {
        // ignore
      });
    } catch (error) {
      // ignore
    }
  }

  private getNumber(key: string, defValue: number): number {
    if (this.store === undefined) {
      return defValue;
    }
    try {
      const value = this.store.getSync(key, defValue);
      if (typeof value === 'number') {
        return value;
      }
      return defValue;
    } catch (error) {
      return defValue;
    }
  }

  private putNumber(key: string, value: number): void {
    if (this.store === undefined) {
      return;
    }
    try {
      this.store.putSync(key, value);
      this.store.flush().catch(() => {
        // ignore
      });
    } catch (error) {
      // ignore
    }
  }
}
