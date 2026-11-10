import { AbilityConstant, UIAbility, Want } from '@kit.AbilityKit';
import { window } from '@kit.ArkUI';
import { BusinessError } from '@kit.BasicServicesKit';
import { hilog } from '@kit.PerformanceAnalysisKit';
import { encodeCommand, parseRotationCommand } from '../core/RotationCommand';

const DOMAIN: number = 0x0000;
const TAG: string = 'LazyRotate';

/**
 * 入口 Ability。
 *
 * 外部指令不直接写窗口：统一落到 AppStorage 的 pendingCommand，
 * 由页面在 onPageShow 时消费，避免窗口尚未创建/页面未挂载时设置方向失败。
 */
export default class EntryAbility extends UIAbility {
  onCreate(want: Want, launchParam: AbilityConstant.LaunchParam): void {
    this.consumeCommand(want);
  }

  onNewWant(want: Want, launchParam: AbilityConstant.LaunchParam): void {
    this.consumeCommand(want);
  }

  onWindowStageCreate(windowStage: window.WindowStage): void {
    windowStage.loadContent('pages/Index', (err: BusinessError) => {
      if (err.code !== 0) {
        hilog.error(DOMAIN, TAG, 'loadContent failed: %{public}s', JSON.stringify(err));
        return;
      }
      hilog.info(DOMAIN, TAG, 'loadContent succeeded');
    });
  }

  private consumeCommand(want: Want): void {
    const command = parseRotationCommand(want);
    if (command === null) {
      return;
    }
    AppStorage.setOrCreate<string>('pendingCommand', encodeCommand(command));
    hilog.info(DOMAIN, TAG, 'pending command: %{public}s', encodeCommand(command));
  }
}
