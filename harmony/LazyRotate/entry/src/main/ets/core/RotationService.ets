import { common } from '@kit.AbilityKit';
import { hilog } from '@kit.PerformanceAnalysisKit';
import {
  RotationBackend,
  RotationOutcome,
  RotationSource,
  RotationTarget,
  flippedTarget
} from './Contract';
import { SelfWindowBackend } from './SelfWindowBackend';
import { AppPreferences } from './AppPreferences';
import { currentRotationTarget } from './DeviceState';

const DOMAIN: number = 0x0000;
const TAG: string = 'LazyRotate';

/**
 * 方向服务：组合根。所有入口（UI/悬浮球/外部指令/躺姿建议/启动恢复）
 * 都只通过这里执行，保证只有一套写方向的逻辑。
 *
 * 「锁定当前 / 翻转 180°」都基于 `currentRotationTarget()`（视觉方向），
 * 不使用 display.rotation 直映射——平板的自然方向是横屏，直映射会锁反。
 */
export class RotationService {
  private readonly backend: RotationBackend;
  private readonly prefs: AppPreferences;

  constructor(context: common.UIAbilityContext) {
    this.backend = new SelfWindowBackend(context);
    this.prefs = new AppPreferences(context);
    this.prefs.init(context);
  }

  getBackend(): RotationBackend {
    return this.backend;
  }

  getPreferences(): AppPreferences {
    return this.prefs;
  }

  isPersistenceReady(): boolean {
    return this.prefs.isReady();
  }

  apply(target: RotationTarget, source: RotationSource): Promise<RotationOutcome> {
    const outcome: Promise<RotationOutcome> = this.backend.apply(target).then((result: RotationOutcome) => {
      if (result.ok) {
        this.prefs.saveLastTarget(target);
      }
      hilog.info(DOMAIN, TAG, 'apply target=%{public}s source=%{public}s ok=%{public}s',
        target.toString(), source.toString(), result.ok ? 'true' : 'false');
      return result;
    });
    return outcome;
  }

  /** 锁定当前视觉方向。 */
  lockCurrent(source: RotationSource): Promise<RotationOutcome> {
    return this.apply(currentRotationTarget(), source);
  }

  /** 在当前视觉方向上翻转 180°。 */
  flip(source: RotationSource): Promise<RotationOutcome> {
    return this.apply(flippedTarget(currentRotationTarget()), source);
  }

  /** 交还系统。 */
  followSystem(source: RotationSource): Promise<RotationOutcome> {
    return this.apply(RotationTarget.AUTO, source);
  }

  /** 启动时恢复上次方向；未开启或上次为「跟随系统」时返回 null。 */
  restoreOnLaunch(): Promise<RotationOutcome> | null {
    if (!this.prefs.getRestoreOnLaunch()) {
      return null;
    }
    const target: RotationTarget = this.prefs.getLastTarget();
    if (target === RotationTarget.AUTO) {
      return null;
    }
    return this.apply(target, RotationSource.RESTORE);
  }

  getCapabilityNote(): string {
    return '当前后端只作用于「本应用窗口」：无法修改系统全局旋转，也无法影响其它应用。';
  }
}
