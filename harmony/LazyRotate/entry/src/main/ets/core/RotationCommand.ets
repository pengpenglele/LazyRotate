import { Want } from '@kit.AbilityKit';
import { RotationTarget, isKnownTargetCode, parseRotationTarget } from './Contract';

export enum CommandKind {
  TARGET = 'target',
  FLIP = 'flip',
  FOLLOW_SYSTEM = 'follow_system'
}

export interface RotationCommand {
  kind: CommandKind;
  target: RotationTarget;
  raw: string;
}

const ACTION_SET_ROTATION: string = 'com.lazyrotate.app.action.SET_ROTATION';
const ACTION_FLIP: string = 'com.lazyrotate.app.action.FLIP';
const ACTION_FOLLOW_SYSTEM: string = 'com.lazyrotate.app.action.FOLLOW_SYSTEM';

/**
 * 解析外部指令（hdc aa start / 其它应用 startAbility）。
 *
 * 示例：
 *   hdc shell aa start -a EntryAbility -b com.lazyrotate.app \
 *       -A com.lazyrotate.app.action.SET_ROTATION --ps target landscape
 *   hdc shell aa start -a EntryAbility -b com.lazyrotate.app \
 *       -A com.lazyrotate.app.action.FLIP
 */
export function parseRotationCommand(want: Want): RotationCommand | null {
  const action: string = want.action ?? '';
  let rawTarget: string = '';
  const params = want.parameters;
  if (params !== undefined) {
    const value = params['target'];
    if (typeof value === 'string') {
      rawTarget = value as string;
    }
  }

  if (action === ACTION_FLIP) {
    return { kind: CommandKind.FLIP, target: RotationTarget.AUTO, raw: action };
  }
  if (action === ACTION_FOLLOW_SYSTEM) {
    return { kind: CommandKind.FOLLOW_SYSTEM, target: RotationTarget.AUTO, raw: action };
  }
  if (rawTarget.length > 0) {
    if (!isKnownTargetCode(rawTarget)) {
      // 未知方向：忽略并保持现状（「不确定就不动」），不得回落成「跟随系统」
      return null;
    }
    return {
      kind: CommandKind.TARGET,
      target: parseRotationTarget(rawTarget),
      raw: rawTarget
    };
  }
  if (action === ACTION_SET_ROTATION) {
    // 只给了 action 没给 target：忽略，避免把用户当前锁定悄悄改成「跟随系统」
    return null;
  }
  return null;
}

/** 给 AppStorage 传递的短指令编码。 */
export function encodeCommand(command: RotationCommand): string {
  if (command.kind === CommandKind.FLIP) {
    return 'flip';
  }
  if (command.kind === CommandKind.FOLLOW_SYSTEM) {
    return 'auto';
  }
  return command.target.toString();
}
