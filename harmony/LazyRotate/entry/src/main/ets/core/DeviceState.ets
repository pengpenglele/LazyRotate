import { display, window } from '@kit.ArkUI';
import { RotationTarget, flippedTarget } from './Contract';

/** 屏幕状态快照（只读）。 */
export interface DisplayInfo {
  rotation: number;
  orientation: display.Orientation;
  width: number;
  height: number;
  densityDPI: number;
}

export function readDisplayInfo(): DisplayInfo {
  try {
    const d: display.Display = display.getDefaultDisplaySync();
    return {
      rotation: d.rotation,
      orientation: d.orientation,
      width: d.width,
      height: d.height,
      densityDPI: d.densityDPI
    };
  } catch (error) {
    return {
      rotation: 0,
      orientation: display.Orientation.PORTRAIT,
      width: 0,
      height: 0,
      densityDPI: 0
    };
  }
}

export function orientationLabel(orientation: display.Orientation): string {
  switch (orientation) {
    case display.Orientation.LANDSCAPE:
      return '横屏';
    case display.Orientation.PORTRAIT_INVERTED:
      return '反向竖屏';
    case display.Orientation.LANDSCAPE_INVERTED:
      return '反向横屏';
    default:
      return '竖屏';
  }
}

export function rotationLabel(rotation: number): string {
  switch (rotation) {
    case 1:
      return '90°';
    case 2:
      return '180°';
    case 3:
      return '270°';
    default:
      return '0°';
  }
}

/**
 * 当前「视觉方向」。
 *
 * 为什么不直接用 display.rotation：rotation 是相对**屏幕自然方向**的顺时针角度，
 * 平板的自然方向通常是横屏，把 rotation=0 当成竖屏会把方向锁反
 * （详见 docs/鸿蒙工程-静态校验报告.md FAIL-2）。
 *
 * 这里用「当前显示宽高比」判定横竖（宽高是当前旋转后的实际值），
 * 再用 display.orientation 判定是否反向；若两者语义不一致则做一次横竖互换，
 * 因此无论 display.orientation 是「视觉方向」还是「相对自然方向」都能得到正确结果。
 */
export function currentWindowOrientation(): window.Orientation {
  const info: DisplayInfo = readDisplayInfo();
  let orientation: display.Orientation = info.orientation;
  const hasSize: boolean = info.width > 0 && info.height > 0;
  if (hasSize) {
    const visualLandscape: boolean = info.width >= info.height;
    const namedLandscape: boolean =
      orientation === display.Orientation.LANDSCAPE || orientation === display.Orientation.LANDSCAPE_INVERTED;
    if (namedLandscape !== visualLandscape) {
      if (visualLandscape) {
        orientation = orientation === display.Orientation.PORTRAIT
          ? display.Orientation.LANDSCAPE
          : display.Orientation.LANDSCAPE_INVERTED;
      } else {
        orientation = orientation === display.Orientation.LANDSCAPE
          ? display.Orientation.PORTRAIT
          : display.Orientation.PORTRAIT_INVERTED;
      }
    }
  }
  switch (orientation) {
    case display.Orientation.LANDSCAPE:
      return window.Orientation.LANDSCAPE;
    case display.Orientation.PORTRAIT_INVERTED:
      return window.Orientation.PORTRAIT_INVERTED;
    case display.Orientation.LANDSCAPE_INVERTED:
      return window.Orientation.LANDSCAPE_INVERTED;
    default:
      return window.Orientation.PORTRAIT;
  }
}

/** 当前视觉方向对应的固定方向目标（用于「锁定当前」）。 */
export function currentRotationTarget(): RotationTarget {
  switch (currentWindowOrientation()) {
    case window.Orientation.LANDSCAPE:
      return RotationTarget.LANDSCAPE;
    case window.Orientation.PORTRAIT_INVERTED:
      return RotationTarget.PORTRAIT_INVERTED;
    case window.Orientation.LANDSCAPE_INVERTED:
      return RotationTarget.LANDSCAPE_INVERTED;
    default:
      return RotationTarget.PORTRAIT;
  }
}

/** 当前视觉方向翻转 180° 后的固定方向目标（用于「翻转 180°」）。 */
export function flippedCurrentRotationTarget(): RotationTarget {
  return flippedTarget(currentRotationTarget());
}

/**
 * 订阅屏幕变化。
 *
 * 说明：`display.on('change')` 回调携带的是**屏幕 ID**，不是旋转角度；
 * 这里只把它当「有变化」的信号，具体状态由调用方重新 `readDisplayInfo()` 读取。
 * 返回值可用于 `unsubscribeDisplayChange()` 取消订阅。
 */
export function subscribeDisplayChange(onChange: (screenId: number) => void): (data: number) => void {
  const callback = (screenId: number): void => {
    onChange(screenId);
  };
  display.on('change', callback);
  return callback;
}

export function unsubscribeDisplayChange(callback: (data: number) => void): void {
  display.off('change', callback);
}
