/**
 * 冻结契约：方向模型、后端接口与纯函数映射。
 *
 * 设计要点
 * - 方向目标用字符串枚举表示，持久化只存字符串（禁止存 ordinal）。
 * - 后端接口 `RotationBackend` 是唯一的方向执行入口：当前实现是「本应用窗口」，
 *   系统签名版本可在不改 UI 的前提下替换为全局后端。
 * - 纯函数（映射/翻转/标签）集中在这里，方便单测式核对。
 */

export enum RotationTarget {
  /** 跟随系统（恢复自动旋转） */
  AUTO = 'auto',
  PORTRAIT = 'portrait',
  LANDSCAPE = 'landscape',
  PORTRAIT_INVERTED = 'portrait_inverted',
  LANDSCAPE_INVERTED = 'landscape_inverted'
}

export enum RotationSource {
  UI = 'ui',
  FLOATING_BALL = 'floating_ball',
  EXTERNAL_COMMAND = 'external_command',
  LIE_DOWN_ENGINE = 'lie_down_engine',
  RESTORE = 'restore'
}

export interface RotationOutcome {
  ok: boolean;
  target: RotationTarget;
  backend: string;
  message: string;
}

export interface RotationBackend {
  readonly id: string;
  readonly displayName: string;
  /** 是否能影响本应用之外的窗口/应用。public SDK 下恒为 false。 */
  readonly canAffectOtherApps: boolean;
  isAvailable(): boolean;
  apply(target: RotationTarget): Promise<RotationOutcome>;
}

/** 解析外部指令传入的方向字符串，未知值回落到 AUTO（仅用于偏好读取等可信场景）。 */
export function parseRotationTarget(raw: string): RotationTarget {
  switch (raw.trim().toLowerCase()) {
    case 'portrait':
    case 'vertical':
      return RotationTarget.PORTRAIT;
    case 'landscape':
    case 'horizontal':
      return RotationTarget.LANDSCAPE;
    case 'portrait_inverted':
    case 'portrait-inverted':
    case 'reverse_portrait':
      return RotationTarget.PORTRAIT_INVERTED;
    case 'landscape_inverted':
    case 'landscape-inverted':
    case 'reverse_landscape':
      return RotationTarget.LANDSCAPE_INVERTED;
    case 'auto':
    case 'follow':
    case 'follow_system':
      return RotationTarget.AUTO;
    default:
      return RotationTarget.AUTO;
  }
}

/**
 * 外部指令白名单校验：未知方向的指令必须「忽略并保持现状」，
 * 不能回落成「跟随系统」把用户当前锁定悄悄改掉（与安卓版「不确定就不动」一致）。
 */
export function isKnownTargetCode(raw: string): boolean {
  switch (raw.trim().toLowerCase()) {
    case 'portrait':
    case 'vertical':
    case 'landscape':
    case 'horizontal':
    case 'portrait_inverted':
    case 'portrait-inverted':
    case 'reverse_portrait':
    case 'landscape_inverted':
    case 'landscape-inverted':
    case 'reverse_landscape':
    case 'auto':
    case 'follow':
    case 'follow_system':
      return true;
    default:
      return false;
  }
}

export function rotationTargetLabel(target: RotationTarget): string {
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

export function rotationTargetHint(target: RotationTarget): string {
  switch (target) {
    case RotationTarget.AUTO:
      return '交还系统：跟随重力，并受控制中心旋转锁定开关控制';
    case RotationTarget.PORTRAIT:
    case RotationTarget.LANDSCAPE:
      return '固定方向，不随传感器变化';
    case RotationTarget.PORTRAIT_INVERTED:
    case RotationTarget.LANDSCAPE_INVERTED:
      return '反向固定：侧躺换边时用来把画面翻过来';
    default:
      return '';
  }
}

/**
 * 在给定方向上翻转 180°。
 *
 * 注意：不要用 `display.rotation + 180` 推导——rotation 是相对「屏幕自然方向」的角度，
 * 平板的自然方向通常是横屏，直接映射会把横屏锁成竖屏。方向来源统一走
 * `DeviceState.currentRotationTarget()`（基于 display.orientation + 宽高比校正）。
 */
export function flippedTarget(target: RotationTarget): RotationTarget {
  switch (target) {
    case RotationTarget.PORTRAIT:
      return RotationTarget.PORTRAIT_INVERTED;
    case RotationTarget.PORTRAIT_INVERTED:
      return RotationTarget.PORTRAIT;
    case RotationTarget.LANDSCAPE:
      return RotationTarget.LANDSCAPE_INVERTED;
    case RotationTarget.LANDSCAPE_INVERTED:
      return RotationTarget.LANDSCAPE;
    default:
      return RotationTarget.PORTRAIT_INVERTED;
  }
}
