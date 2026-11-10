import { sensor } from '@kit.SensorServiceKit';

/** 躺姿引擎采样结果。 */
export interface LieDownSample {
  /** 屏幕是否接近水平（躺姿）：此时重力几乎全部落在屏幕法线方向。 */
  lying: boolean;
  /** 重力在屏幕平面内的分量大小（m/s²）。越小越接近水平。 */
  inPlane: number;
  /** 屏幕平面相对水平面的倾角（度）。 */
  tiltDeg: number;
  /** 躺姿置信度 0..1；不足时 UI 明确显示「不确定」。 */
  confidence: number;
}

/**
 * 躺姿引擎（重力传感器）。
 *
 * 说明
 * - GRAVITY 传感器无需运行时权限；失败时上层显示「传感器不可用」。
 * - 阈值是合理初值（inPlane < 4 m/s²、|z| > 7 m/s²），建议真机躺姿实测微调。
 * - 本工程只能把结论应用到「本应用窗口」；全局锁定需要系统签名（见可行性评估文档）。
 */
export class LieDownEngine {
  private active: boolean = false;
  private callback: ((sample: LieDownSample) => void) | undefined = undefined;
  private lastNotifyAt: number = 0;
  private lastLying: boolean = false;

  isRunning(): boolean {
    return this.active;
  }

  start(callback: (sample: LieDownSample) => void): boolean {
    if (this.active) {
      this.callback = callback;
      return true;
    }
    this.callback = callback;
    try {
      sensor.on(sensor.SensorId.GRAVITY, (data: sensor.GravityResponse) => {
        const sample = LieDownEngine.evaluate(data.x, data.y, data.z);
        const now = Date.now();
        const changed = sample.lying !== this.lastLying;
        if (changed || now - this.lastNotifyAt >= 400) {
          this.lastNotifyAt = now;
          this.lastLying = sample.lying;
          const cb = this.callback;
          if (cb !== undefined) {
            cb(sample);
          }
        }
      }, { interval: 200000000 });
      this.active = true;
      return true;
    } catch (error) {
      this.active = false;
      return false;
    }
  }

  stop(): void {
    if (!this.active) {
      return;
    }
    try {
      sensor.off(sensor.SensorId.GRAVITY);
    } catch (error) {
      // 传感器已不可用时忽略
    }
    this.active = false;
    this.callback = undefined;
  }

  static evaluate(x: number, y: number, z: number): LieDownSample {
    const inPlane: number = Math.sqrt(x * x + y * y);
    const absZ: number = Math.abs(z);
    const tiltDeg: number = Math.atan2(inPlane, absZ) * 180 / Math.PI;
    const lying: boolean = absZ > 7.0 && inPlane < 4.0;
    let confidence: number = 0;
    if (lying) {
      confidence = Math.max(0, Math.min(1, (4.0 - inPlane) / 4.0));
    }
    return {
      lying: lying,
      inPlane: inPlane,
      tiltDeg: tiltDeg,
      confidence: confidence
    };
  }
}
