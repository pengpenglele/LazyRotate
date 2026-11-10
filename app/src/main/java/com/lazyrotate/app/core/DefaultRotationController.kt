package com.lazyrotate.app.core

import android.content.Context
import android.database.ContentObserver
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import com.lazyrotate.app.contract.RotationAngle
import com.lazyrotate.app.contract.RotationController
import com.lazyrotate.app.contract.RotationGateway
import com.lazyrotate.app.contract.RotationStatus
import com.lazyrotate.app.contract.RotationTarget
import com.lazyrotate.app.contract.SensorOrientationEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * [RotationTarget] → 绝对角度 → 系统写操作的唯一实现。
 * 磁贴、悬浮球、通知 action、广播、按应用规则全部走这里，不允许任何入口自己写设置（契约第 6 条）。
 *
 * 状态来源有两路：
 *   1. 自己写完之后立即重读系统（读回真值，不靠本地猜）；
 *   2. ContentObserver（Settings.System）+ DisplayListener（显示角度）被动刷新，
 *      这样用户在系统「快速设置」里改了自动旋转、或系统自己变了角度，UI 也能跟上。
 *
 * 还有一个特殊驱动者：**躺姿引擎**（见 [onEngineSuggestion]）。它拿出的不是「用户选择」而是
 * 「更好的自动旋转」，所以它接管时**不**持久化目标、**不**碰 `lastForcedAngle`，
 * 状态上表现为 `target = FOLLOW_SYSTEM` + `autoRotateEnabled = false` + `lockEngaged = false` +
 * `sensorEngineEnabled = true`。用户一旦显式锁定（持久化目标 != FOLLOW_SYSTEM），引擎立即闭嘴。
 */
class DefaultRotationController(
    context: Context,
    private val gateway: RotationGateway,
    private val sensorEngine: SensorOrientationEngine,
) : RotationController {

    private val appContext: Context = context.applicationContext ?: context
    private val prefs = CorePreferences.get(appContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _status = MutableStateFlow(RotationStatus())
    override val status: StateFlow<RotationStatus> = _status.asStateFlow()

    /** 当前锁定的绝对角度。null = 还没有锁定过 / 已恢复跟随系统。 */
    @Volatile
    private var lastForcedAngle: RotationAngle? = null

    /** 当前是否由躺姿引擎驱动（引擎接管期间自动旋转被我们关掉，必须记得交还）。 */
    @Volatile
    private var engineDriving = false

    /** 引擎接管期间的守护协程：负责在「引擎被关掉」时把自动旋转交还系统。 */
    private var engineWatchdog: Job? = null

    /**
     * 系统设置变化（ACCELEROMETER_ROTATION / USER_ROTATION）后重读。
     * ContentObserver 与 DisplayListener 都注册在 app 级组合根上，进程存活期不注销——这是刻意的，
     * 单例容器本来就要活到进程结束。
     */
    private val settingsObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean, uri: Uri?) {
            scope.launch { refresh() }
        }
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) {
            scope.launch { refresh() }
        }
    }

    init {
        try {
            appContext.contentResolver.registerContentObserver(
                Settings.System.CONTENT_URI,
                true,
                settingsObserver,
            )
        } catch (t: Throwable) {
            Log.w(TAG, "register settings observer failed", t)
        }
        try {
            val manager = appContext.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
            manager?.registerDisplayListener(displayListener, Handler(Looper.getMainLooper()))
        } catch (t: Throwable) {
            Log.w(TAG, "register display listener failed", t)
        }
        scope.launch { refresh() }
        // 躺姿引擎接管：引擎给出建议时由 controller 落盘，磁贴/悬浮球/广播/规则四个入口自动受益。
        scope.launch {
            sensorEngine.suggested.collect { suggested ->
                onEngineSuggestion(suggested)
            }
        }
    }

    override suspend fun apply(target: RotationTarget): Boolean = withContext(Dispatchers.IO) {
        applyBlocking(target)
    }

    override suspend fun flip180(): Boolean = withContext(Dispatchers.IO) {
        // 未锁定时 lastForcedAngle == null，resolve() 会拿当前真实角度 +180，
        // 等价于「先锁定当前方向再翻转」，结果一致但少一次写。
        applyBlocking(RotationTarget.FLIP_180)
    }

    override suspend fun cycle(): Boolean = withContext(Dispatchers.IO) {
        val autoRotate = gateway.isAutoRotateEnabled()
        val persisted = RotationTarget.fromCode(readPersistedTargetCode())
        // 系统自动旋转开着、或我们没强制过 → 链条入口就是 FOLLOW_SYSTEM。
        val entry = if (autoRotate || persisted == RotationTarget.FOLLOW_SYSTEM) {
            RotationTarget.FOLLOW_SYSTEM
        } else {
            persisted
        }
        val next = when (entry) {
            RotationTarget.FOLLOW_SYSTEM -> RotationTarget.LOCK_CURRENT
            RotationTarget.LOCK_CURRENT -> RotationTarget.FLIP_180
            else -> RotationTarget.FOLLOW_SYSTEM
        }
        applyBlocking(next)
    }

    override suspend fun refresh() {
        _status.value = withContext(Dispatchers.IO) { readStatus() }
    }

    // ---------------------------------------------------------------- 躺姿引擎接管

    /**
     * 躺姿引擎的建议落盘。这是「信息足够就稳定切换」这句话真正的实现点。
     *
     * 为什么放在 controller 而不是 UI：磁贴/悬浮球/通知/广播/按应用规则都走 controller，
     * 引擎接线放这里，四个入口自动获得同一份行为，也不用改冻结契约。
     *
     * 语义（用户优先、幂等、不持久化）：
     *  1. `null`：引擎没有建议。只有「引擎确实已经停了（isRunning == false）」才交还系统自动旋转——
     *     置信度不足导致的 null 只是「这次不确定」，按契约必须保持现状，不能交还给系统自动旋转，
     *     否则躺姿下又会开始来回翻转（那正是本应用要消灭的 bug）。
     *  2. 引擎没在跑 → 不动作。
     *  3. 用户显式锁定过（持久化目标 != FOLLOW_SYSTEM）→ 不动作，绝不覆盖用户的选择。
     *  4. 没有 WRITE_SETTINGS → 不动作、不报错、不反复重试。
     *  5. 否则接管：关自动旋转 + 按需写 USER_ROTATION（只在与当前值不同时才写，避免写风暴）。
     *
     * **不持久化目标、不写 lastForcedAngle**：ENGINE 不是用户的选择，它等价于「更好的自动旋转」。
     * 持久化会让状态显示成某个绝对角度、lockEngaged 变 true，还会让第 3 条守卫把自己锁死。
     */
    private fun onEngineSuggestion(angle: RotationAngle?) {
        if (angle == null) {
            if (engineDriving && !sensorEngine.isRunning) releaseEngineControl()
            return
        }
        if (!sensorEngine.isRunning) return
        if (RotationTarget.fromCode(readPersistedTargetCode()) != RotationTarget.FOLLOW_SYSTEM) return
        if (!gateway.hasWriteSettings()) return

        engineDriving = true
        startEngineWatchdog()

        if (gateway.isAutoRotateEnabled()) {
            gateway.setAutoRotateEnabled(false)
        }
        if (gateway.currentUserRotation() != angle) {
            gateway.setUserRotation(angle)
        }
        _status.value = readStatus()
    }

    /**
     * 把自动旋转交还系统（引擎不再驱动时的收尾）。
     * 只在这里写 ACCELEROMETER_ROTATION=1，且 engineDriving 先置 false，保证幂等、不会反复写。
     */
    private fun releaseEngineControl() {
        if (!engineDriving) return
        engineDriving = false
        stopEngineWatchdog()
        gateway.setAutoRotateEnabled(true)
        _status.value = readStatus()
    }

    /** 只收回驱动权，不写设置（用户显式操作时的路径，applyBlocking 已经会写）。 */
    private fun stopEngineControl() {
        engineDriving = false
        stopEngineWatchdog()
    }

    /**
     * 为什么要守护协程：`suggested` 是 StateFlow，`stop()` 再置一次 null **不会**重新发射事件，
     * 所以「引擎在低置信度时已经把 suggested 置成 null、之后用户才关掉引擎」这个场景，
     * 采集器不会收到任何回调。没有这个守护，自动旋转就会永远停在关闭状态。
     * 只在引擎接管期间运行（1s 一次、不写设置），收回驱动权即结束，不会泄漏。
     */
    private fun startEngineWatchdog() {
        if (engineWatchdog?.isActive == true) return
        engineWatchdog = scope.launch {
            while (isActive) {
                delay(ENGINE_WATCHDOG_INTERVAL_MS)
                if (!engineDriving) break
                if (!sensorEngine.isRunning) {
                    releaseEngineControl()
                    break
                }
            }
        }
    }

    private fun stopEngineWatchdog() {
        engineWatchdog?.cancel()
        engineWatchdog = null
    }

    // ---------------------------------------------------------------- 内部实现

    /**
     * 把目标落到系统上。返回是否成功；无权限时 false 且不抛异常。
     *
     * 顺序是硬约束：先关自动旋转，再写 USER_ROTATION —— 系统只在加速度旋转关闭时采纳 USER_ROTATION。
     * 两个写入都是幂等的，重复 apply 同一目标不会产生副作用。
     */
    private fun applyBlocking(target: RotationTarget): Boolean {
        // 用户显式操作永远优先于躺姿引擎：先收回引擎的驱动权（引擎本身继续跑，只是不再写方向）。
        stopEngineControl()
        val success: Boolean
        if (target == RotationTarget.FOLLOW_SYSTEM) {
            success = gateway.setAutoRotateEnabled(true)
            if (success) {
                lastForcedAngle = null
                writePersistedTarget(RotationTarget.FOLLOW_SYSTEM)
            }
        } else {
            val angle = resolve(target)
            val autoRotateOff = gateway.setAutoRotateEnabled(false)
            val rotationWritten = gateway.setUserRotation(angle)
            success = autoRotateOff && rotationWritten
            if (rotationWritten) {
                lastForcedAngle = angle
                writePersistedTarget(target)
            }
        }
        _status.value = readStatus()
        return success
    }

    /**
     * [RotationTarget] → [RotationAngle]。
     *
     * 绝对方向按「自然方向」解释，并优先选与当前同侧的那个：
     *   自然横屏设备：LANDSCAPE → {0°,180°} 里与当前同侧者；PORTRAIT → {90°,270°}。
     *   自然竖屏设备：PORTRAIT  → {0°,180°} 里与当前同侧者；LANDSCAPE → {90°,270°}。
     * REVERSE_* 取同组里相反的那个（flipped() = +180°）。
     */
    private fun resolve(target: RotationTarget): RotationAngle {
        val naturalLandscape = gateway.naturalOrientationIsLandscape()
        val current = gateway.displayRotation()
        val landscapePair = if (naturalLandscape) {
            arrayOf(RotationAngle.ANGLE_0, RotationAngle.ANGLE_180)
        } else {
            arrayOf(RotationAngle.ANGLE_90, RotationAngle.ANGLE_270)
        }
        val portraitPair = if (naturalLandscape) {
            arrayOf(RotationAngle.ANGLE_90, RotationAngle.ANGLE_270)
        } else {
            arrayOf(RotationAngle.ANGLE_0, RotationAngle.ANGLE_180)
        }
        fun pick(pair: Array<RotationAngle>): RotationAngle =
            if (pair.contains(current)) current else pair[0]

        return when (target) {
            RotationTarget.FOLLOW_SYSTEM, RotationTarget.LOCK_CURRENT -> current
            RotationTarget.FLIP_180 -> (lastForcedAngle ?: current).flipped()
            RotationTarget.PORTRAIT -> pick(portraitPair)
            RotationTarget.REVERSE_PORTRAIT -> pick(portraitPair).flipped()
            RotationTarget.LANDSCAPE -> pick(landscapePair)
            RotationTarget.REVERSE_LANDSCAPE -> pick(landscapePair).flipped()
            RotationTarget.ANGLE_0 -> RotationAngle.ANGLE_0
            RotationTarget.ANGLE_90 -> RotationAngle.ANGLE_90
            RotationTarget.ANGLE_180 -> RotationAngle.ANGLE_180
            RotationTarget.ANGLE_270 -> RotationAngle.ANGLE_270
        }
    }

    /** 全部字段都从系统真值读，不做本地状态推断。 */
    private fun readStatus(): RotationStatus {
        val autoRotate = gateway.isAutoRotateEnabled()
        val hasWriteSettings = gateway.hasWriteSettings()
        val persisted = RotationTarget.fromCode(readPersistedTargetCode())

        // 进程刚起来、或系统重启后：从 USER_ROTATION 恢复「上次强制角度」。
        if (lastForcedAngle == null && !autoRotate && persisted != RotationTarget.FOLLOW_SYSTEM) {
            lastForcedAngle = gateway.currentUserRotation()
        }

        val applied = if (autoRotate) gateway.displayRotation() else gateway.currentUserRotation()
        return RotationStatus(
            target = if (autoRotate) RotationTarget.FOLLOW_SYSTEM else persisted,
            appliedAngle = applied,
            autoRotateEnabled = autoRotate,
            lockEngaged = !autoRotate && hasWriteSettings && persisted != RotationTarget.FOLLOW_SYSTEM,
            naturalLandscape = gateway.naturalOrientationIsLandscape(),
            sensorEngineEnabled = sensorEngine.isRunning,
            secureTierEnabled = gateway.hasSecureSettings(),
        )
    }

    private fun readPersistedTargetCode(): String? =
        prefs.getString(CorePreferences.KEY_LAST_TARGET, null)

    /** 只存 [RotationTarget.code]，禁止存 ordinal（契约第 6 条）。 */
    private fun writePersistedTarget(target: RotationTarget) {
        prefs.edit().putString(CorePreferences.KEY_LAST_TARGET, target.code).apply()
    }

    private companion object {
        const val TAG = "DefaultRotationController"

        /** 引擎接管期间，多久复查一次「引擎是否已被关掉」。 */
        const val ENGINE_WATCHDOG_INTERVAL_MS = 1_000L
    }
}
