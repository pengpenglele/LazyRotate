package com.lazyrotate.app.core

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import com.lazyrotate.app.contract.RotationAngle
import com.lazyrotate.app.contract.SensorOrientationEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * 躺姿传感器引擎。
 *
 * 与系统自动旋转的区别正是产品要解决的问题：
 *   1. 只信「重力在屏幕平面上的投影」。投影强度 = confidence，低于阈值直接发 **null**（= 保持现状）。
 *      躺着手持平板时重力几乎垂直于屏幕（指向屏幕内/外），此时任何角度推断都是猜，猜就会来回翻转。
 *   2. 迟滞：同一个候选方向要连续稳定 [STABLE_WINDOW_MS] 才输出，且窗口内角度不能漂到别的象限。
 *   3. 静止判定：|g| 明显偏离 9.81（晃动/自由落体）时不给建议；可选注册 TYPE_ROTATION_VECTOR，
 *      用 yaw 角速度判断「用户正在主动转动设备」，转动期间保持现状。
 *
 * **关于磁力计/yaw 的取舍（重要）**：设备接近水平时，yaw 只能告诉你「相对磁北转了多少」，
 * 无法知道用户躺在哪一侧、眼睛朝哪边——用它猜方向必然错。所以这里绝不拿磁力计当方向来源，
 * 只在用户显式打开 `KEY_SENSOR_ASSIST` 时用它做「是否正在转动」的抑制信号，缺磁力计时自然退化为
 * 纯重力 + 迟滞方案（依然安全）。
 *
 * 角度换算（与 display/USER_ROTATION 的 0..3 语义一致，对自然横屏/竖屏设备同样成立）：
 *   屏幕朝上平放时的设备坐标系下，重力 (gx,gy,gz) →
 *     angle = atan2(-gx, gy)；0°→ANGLE_0, 90°→ANGLE_90, 180°→ANGLE_180, 270°→ANGLE_270。
 */
class SensorOrientationEngineImpl(context: Context) : SensorOrientationEngine, SensorEventListener {

    private val appContext: Context = context.applicationContext ?: context
    private val prefs = CorePreferences.get(appContext)

    private val sensorManager: SensorManager? =
        appContext.getSystemService(Context.SENSOR_SERVICE) as? SensorManager

    /** TYPE_GRAVITY 是融合结果、抖动小；没有就退回原始加速度计。 */
    private val gravitySensor: Sensor? =
        sensorManager?.getDefaultSensor(Sensor.TYPE_GRAVITY)
            ?: sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    /** 可选辅助，只用于「是否正在转动」的静止判定，不参与方向推断。 */
    private val rotationVectorSensor: Sensor? =
        sensorManager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

    /** 已注册的传感器，保证 start()/stop() 成对、不泄漏监听器。 */
    private val registeredSensors = mutableListOf<Sensor>()

    private val _suggested = MutableStateFlow<RotationAngle?>(null)
    override val suggested: StateFlow<RotationAngle?> = _suggested.asStateFlow()

    private val _confidence = MutableStateFlow(0f)
    override val confidence: StateFlow<Float> = _confidence.asStateFlow()

    @Volatile
    private var running = false
    override val isRunning: Boolean get() = running

    // 迟滞状态（回调都在主线程，无需加锁）。
    private var pendingAngle: RotationAngle? = null
    private var pendingSinceMs = 0L

    // 静止判定（旋转矢量辅助）。16 个元素是防御性的：不同版本 getRotationMatrixFromVector 写入 9 或 16 个。
    private val rotationMatrix = FloatArray(16)
    private val orientationAngles = FloatArray(3)
    private var lastYawDeg = Double.NaN
    private var lastYawAtMs = 0L

    @Volatile
    private var motionHoldUntilMs = 0L

    @Synchronized
    override fun start() {
        if (running) return
        val manager = sensorManager ?: return
        val primary = gravitySensor ?: return
        val primaryOk = try {
            manager.registerListener(this, primary, SensorManager.SENSOR_DELAY_UI)
        } catch (t: Throwable) {
            Log.w(TAG, "register gravity listener failed", t)
            false
        }
        if (!primaryOk) return
        registeredSensors.add(primary)

        val assistEnabled = prefs.getBoolean(
            CorePreferences.KEY_SENSOR_ASSIST,
            CorePreferences.DEFAULT_SENSOR_ASSIST,
        )
        if (assistEnabled) {
            rotationVectorSensor?.let { sensor ->
                val assistOk = try {
                    manager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_UI)
                } catch (t: Throwable) {
                    Log.w(TAG, "register rotation-vector listener failed", t)
                    false
                }
                if (assistOk) registeredSensors.add(sensor)
            }
        }
        running = true
    }

    @Synchronized
    override fun stop() {
        try {
            // 一次注销该 listener 的全部注册，保证配对。
            sensorManager?.unregisterListener(this)
        } catch (t: Throwable) {
            Log.w(TAG, "unregister listener failed", t)
        }
        registeredSensors.clear()
        running = false
        resetOutput()
    }

    override fun onSensorChanged(event: SensorEvent?) {
        val safeEvent = event ?: return
        when (safeEvent.sensor.type) {
            Sensor.TYPE_GRAVITY, Sensor.TYPE_ACCELEROMETER -> onGravitySample(safeEvent)
            Sensor.TYPE_ROTATION_VECTOR -> onRotationVectorSample(safeEvent)
            else -> Unit
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    // ---------------------------------------------------------------- 重力主路径

    private fun onGravitySample(event: SensorEvent) {
        val values = event.values
        if (values.size < 3) return
        val gx = values[0]
        val gy = values[1]
        val gz = values[2]

        val magnitude = sqrt(gx * gx + gy * gy + gz * gz)
        if (magnitude < MIN_VALID_MAGNITUDE) return

        val nowMs = event.timestamp / NANOS_PER_MILLI

        val inPlane = sqrt(gx * gx + gy * gy)
        val computedConfidence = (inPlane / magnitude).coerceIn(0f, 1f)
        // 置信度永远反映真实读数（UI 要能看到「现在不可信」），与是否处于 hold 窗口无关。
        _confidence.value = computedConfidence

        // 正在被主动转动：保持现状（既不换方向也不发 null），等手停下来再判定。
        if (nowMs < motionHoldUntilMs) return

        val stillEnough = abs(magnitude - GRAVITY_EARTH) <= STILL_TOLERANCE
        if (computedConfidence < MIN_CONFIDENCE || !stillEnough) {
            // 屏幕接近水平（躺着看）、或设备在晃：不确定就不动。
            clearSuggestion()
            return
        }

        val angleDeg = normalizeDegrees(Math.toDegrees(atan2(-gx.toDouble(), gy.toDouble())))
        val candidate = RotationAngle.fromDegrees(
            (Math.round(angleDeg / QUADRANT_DEGREES) * QUADRANT_DEGREES.toLong()).toInt(),
        )
        advanceHysteresis(candidate, angleDeg, nowMs)
    }

    private fun advanceHysteresis(candidate: RotationAngle, angleDeg: Double, nowMs: Long) {
        if (pendingAngle != candidate) {
            // 候选刚变化：先保持旧建议，等它稳满窗口再说（这就是迟滞）。
            pendingAngle = candidate
            pendingSinceMs = nowMs
            return
        }
        if (nowMs - pendingSinceMs < STABLE_WINDOW_MS) return
        if (angularDistanceDegrees(angleDeg, candidate.degrees.toDouble()) > MAX_DRIFT_DEGREES) {
            // 窗口内角度漂到另一个象限了，重新计时。
            pendingAngle = null
            pendingSinceMs = nowMs
            return
        }
        if (_suggested.value != candidate) {
            _suggested.value = candidate
        }
    }

    private fun clearSuggestion() {
        pendingAngle = null
        pendingSinceMs = 0L
        if (_suggested.value != null) {
            _suggested.value = null
        }
    }

    // ---------------------------------------------------------------- 可选静止判定

    private fun onRotationVectorSample(event: SensorEvent) {
        val values = event.values
        if (values.size < 3) return
        val nowMs = event.timestamp / NANOS_PER_MILLI
        val ok = try {
            SensorManager.getRotationMatrixFromVector(rotationMatrix, values)
            SensorManager.getOrientation(rotationMatrix, orientationAngles)
            true
        } catch (t: Throwable) {
            Log.w(TAG, "rotation vector decode failed", t)
            false
        }
        if (!ok) return

        val yawDeg = Math.toDegrees(orientationAngles[0].toDouble())
        if (!lastYawDeg.isNaN()) {
            val dtMs = nowMs - lastYawAtMs
            if (dtMs in 1..MAX_YAW_DT_MS) {
                val deltaDeg = abs(normalizeSignedDelta(yawDeg - lastYawDeg))
                val ratePerSecond = deltaDeg / (dtMs / 1000.0)
                if (ratePerSecond > MAX_YAW_RATE_DEG_PER_SEC) {
                    // 记入 hold 窗口：转动过程中不产生新建议，避免跟着手抖来回跳。
                    motionHoldUntilMs = nowMs + MOTION_HOLD_MS
                }
            }
        }
        lastYawDeg = yawDeg
        lastYawAtMs = nowMs
    }

    private fun resetOutput() {
        pendingAngle = null
        pendingSinceMs = 0L
        motionHoldUntilMs = 0L
        lastYawDeg = Double.NaN
        lastYawAtMs = 0L
        _suggested.value = null
        _confidence.value = 0f
    }

    // ---------------------------------------------------------------- 数学工具

    private fun normalizeDegrees(value: Double): Double {
        var v = value % 360.0
        if (v < 0) v += 360.0
        return v
    }

    private fun normalizeSignedDelta(deltaDeg: Double): Double {
        var d = deltaDeg
        while (d > 180.0) d -= 360.0
        while (d < -180.0) d += 360.0
        return d
    }

    private fun angularDistanceDegrees(a: Double, b: Double): Double =
        abs(normalizeSignedDelta(a - b))

    private companion object {
        const val TAG = "SensorOrientationEngine"

        /** 用字面量，避免依赖额外常量。 */
        const val GRAVITY_EARTH = 9.80665f

        /** |g| 偏离重力加速度超过这个值 → 认为在晃动/自由落体，样本不可信。 */
        const val STILL_TOLERANCE = 2.5f

        const val MIN_VALID_MAGNITUDE = 1f

        /**
         * 重力在屏幕平面内投影占比的下限（≈ 偏离竖直面 63°）。
         * 低于它 → suggested = null。这是「躺姿不来回翻转」的核心闸门，可按实测手感微调。
         */
        const val MIN_CONFIDENCE = 0.45f

        /** 迟滞窗口：同一候选方向需连续稳定 700ms 才切换。 */
        const val STABLE_WINDOW_MS = 700L

        /** 稳定窗口内允许的角度漂移，超过就重新计时。 */
        const val MAX_DRIFT_DEGREES = 35.0

        const val QUADRANT_DEGREES = 90.0
        const val NANOS_PER_MILLI = 1_000_000L

        /** yaw 角速度超过 60°/s 视为「正在被主动转动」。 */
        const val MAX_YAW_RATE_DEG_PER_SEC = 60.0
        const val MOTION_HOLD_MS = 250L
        const val MAX_YAW_DT_MS = 500L
    }
}
