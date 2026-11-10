package com.lazyrotate.app.contract

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/*
 * ============================================================================
 *  FROZEN CONTRACT — 改这里的公开形状之前先读 docs/CONTRACT.md，
 *  整个 App 都是按这份契约搭起来的。
 *
 *  实现分工见 docs/CONTRACT.md 第 3 节。
 * ============================================================================
 */

/**
 * 用户可见的「旋转目标」。UI 直接展示 label；持久化用 code，禁止用 ordinal。
 */
enum class RotationTarget(val code: String, val label: String) {
    /** 交还给系统：打开自动旋转，恢复重力感应。 */
    FOLLOW_SYSTEM("follow", "跟随系统"),

    /** 锁定为当前方向（躺姿场景的主按钮）。 */
    LOCK_CURRENT("lock", "锁定当前方向"),

    /** 在已锁定方向基础上翻转 180°（躺姿换边时使用）。 */
    FLIP_180("flip", "翻转 180°"),

    /** 四个绝对方向，语义随设备自然方向解释。 */
    PORTRAIT("portrait", "竖屏"),
    LANDSCAPE("landscape", "横屏"),
    REVERSE_PORTRAIT("portrait_rev", "反向竖屏"),
    REVERSE_LANDSCAPE("landscape_rev", "反向横屏"),

    /** 直接对应 Settings.System.USER_ROTATION 的四个取值。 */
    ANGLE_0("angle_0", "0°"),
    ANGLE_90("angle_90", "90°"),
    ANGLE_180("angle_180", "180°"),
    ANGLE_270("angle_270", "270°");

    companion object {
        fun fromCode(code: String?): RotationTarget =
            values().firstOrNull { it.code == code } ?: FOLLOW_SYSTEM
    }
}

/**
 * Settings.System.USER_ROTATION 的四个合法取值（0..3）。
 * userRotationValue 必须原样写入系统设置，不要自己换算角度。
 */
enum class RotationAngle(val userRotationValue: Int, val degrees: Int) {
    ANGLE_0(0, 0),
    ANGLE_90(1, 90),
    ANGLE_180(2, 180),
    ANGLE_270(3, 270);

    /** 翻转 180°：在四个取值里 +2（mod 4）。 */
    fun flipped(): RotationAngle = values()[(ordinal + 2) % 4]

    fun isPortraitOn(naturalLandscape: Boolean): Boolean = if (naturalLandscape) {
        this == ANGLE_90 || this == ANGLE_270
    } else {
        this == ANGLE_0 || this == ANGLE_180
    }

    companion object {
        fun fromUserRotation(value: Int): RotationAngle {
            val v = ((value % 4) + 4) % 4
            return values().first { it.userRotationValue == v }
        }

        fun fromDegrees(degrees: Int): RotationAngle {
            val d = ((degrees % 360) + 360) % 360
            return values().firstOrNull { it.degrees == d } ?: ANGLE_0
        }
    }
}

/** 当前生效状态的只读快照，UI 以此渲染。 */
data class RotationStatus(
    val target: RotationTarget = RotationTarget.FOLLOW_SYSTEM,
    /** 当前解析出的绝对角度；FOLLOW_SYSTEM 时是系统实际角度。 */
    val appliedAngle: RotationAngle = RotationAngle.ANGLE_0,
    val autoRotateEnabled: Boolean = true,
    /** 锁定是否真正生效（= 已拿到 WRITE_SETTINGS 且已关闭自动旋转）。 */
    val lockEngaged: Boolean = false,
    val naturalLandscape: Boolean = true,
    val sensorEngineEnabled: Boolean = false,
    val secureTierEnabled: Boolean = false,
)

/**
 * 与系统设置的唯一接触面。所有实现必须是幂等的、可重入的。
 * 免 root 路径只使用 setAutoRotateEnabled + setUserRotation。
 */
interface RotationGateway {
    /** 是否已获得 android.permission.WRITE_SETTINGS（特殊权限，需用户手动授予）。 */
    fun hasWriteSettings(): Boolean

    /** 是否已获得 WRITE_SECURE_SETTINGS（只能通过 adb pm grant 或 Shizuku 获得）。 */
    fun hasSecureSettings(): Boolean

    /** Settings.System.ACCELEROMETER_ROTATION == 1。 */
    fun isAutoRotateEnabled(): Boolean

    /** 读取 Settings.System.USER_ROTATION，越界时按 mod 4 归一化。 */
    fun currentUserRotation(): RotationAngle

    /** 当前屏幕实际角度，来自 Display.getRotation()。 */
    fun displayRotation(): RotationAngle

    /**
     * 设备自然方向是否为横屏（平板多为 true；判断依据见 docs/CONTRACT.md）。
     * 值会被持久化，用户可在设置里覆盖。
     */
    fun naturalOrientationIsLandscape(): Boolean
    fun setNaturalOrientationIsLandscape(landscape: Boolean)

    /** 返回是否写入成功（无权限时必须返回 false，不要抛异常）。 */
    fun setAutoRotateEnabled(enabled: Boolean): Boolean

    /** 免 root 的核心写操作。返回是否写入成功。 */
    fun setUserRotation(angle: RotationAngle): Boolean

    /**
     * 高级档：Android 12L+ 「忽略应用自身的方向请求」。
     *
     * **诚实结论（重要）**：该能力在公开 SDK 下对第三方应用不可达。真正的开关由
     * WindowManagerService 持有，用户可见入口是 Android 12L+ 的开发者选项，
     * 只能通过 shell 级命令操作：
     *   - 按应用：`adb shell am compat enable OVERRIDE_ENABLE_COMPAT_IGNORE_REQUESTED_ORIENTATION <包名>`
     *   - 全局：见 docs/research-notes.md「高级档可行性」一节
     * 仅凭 `WRITE_SECURE_SETTINGS` 无法调用（写 Settings.Global 的猜测键没有任何效果，
     * 因为 WMS 不读那个键）。因此：
     *
     *   **当前实现必须返回 false**，且不得写入任何「看起来像成功」的设置。
     *   界面把它当作「需要电脑执行一次 ADB 命令」的引导展示，而不是一个可切换的开关。
     *   保留该方法签名是为了将来用 Shizuku/root 真正实现时有统一入口。
     *
     * @return 恒为 false（当前实现），表示"应用自身无法完成"。
     */
    fun setIgnoreOrientationRequest(ignore: Boolean): Boolean
}

/** 业务入口：把 RotationTarget 解析成系统写操作并广播状态。 */
interface RotationController {
    val status: StateFlow<RotationStatus>

    /** 应用一个目标；返回是否成功（无权限时为 false，且必须触发 UI 提示）。 */
    suspend fun apply(target: RotationTarget): Boolean

    /** 在当前锁定方向基础上翻转 180°；未锁定时先锁定当前方向。 */
    suspend fun flip180(): Boolean

    /** 循环切换（供磁贴/悬浮球单击使用）：跟随系统 -> 锁定当前 -> 翻转 -> 跟随系统。 */
    suspend fun cycle(): Boolean

    /** 从系统重新读取真实状态（权限授予回来、系统设置被别处改动后调用）。 */
    suspend fun refresh()
}

/** 单条「按应用」规则。 */
data class AppRule(
    val packageName: String,
    val label: String,
    val target: RotationTarget,
    val enabled: Boolean = true,
)

interface AppRuleStore {
    val rules: Flow<List<AppRule>>
    suspend fun upsert(rule: AppRule)
    suspend fun remove(packageName: String)
    suspend fun ruleFor(packageName: String): AppRule?
}

/** 各项权限的当前状态，UI 的引导流程以此驱动。 */
data class PermissionSnapshot(
    val writeSettings: Boolean = false,
    val accessibility: Boolean = false,
    val overlay: Boolean = false,
    val notifications: Boolean = false,
    val secureSettings: Boolean = false,
) {
    /** P0 主路径是否可用：只需 WRITE_SETTINGS。 */
    val coreReady: Boolean get() = writeSettings
}

interface PermissionInspector {
    fun snapshot(): PermissionSnapshot
    val flow: Flow<PermissionSnapshot>
}

/**
 * 前台应用监听。免 root 实现必须用 AccessibilityService（PACKAGE_USAGE_STATS 轮询为备选）。
 * 无障碍服务未开启时 foregroundPackage 保持 null，且不得抛异常。
 */
interface ForegroundAppMonitor {
    val foregroundPackage: StateFlow<String?>
    val isRunning: Boolean
}

/**
 * 躺姿方向引擎：自己融合传感器，决定「该用哪个角度」。
 * 与系统自动旋转的区别：置信度闸门 + 迟滞 + 静止判定，避免来回翻转。
 */
interface SensorOrientationEngine {
    /** 建议角度；置信度不足时必须是 null（表示「保持现状」，这正是躺姿场景的关键）。 */
    val suggested: StateFlow<RotationAngle?>

    /** 0f..1f，重力矢量在屏幕平面上的投影强度（越大越可信）。 */
    val confidence: StateFlow<Float>

    val isRunning: Boolean
    fun start()
    fun stop()
}

/** 组合根：UI 只通过这里拿依赖，不引入 DI 框架。 */
interface AppContainer {
    val gateway: RotationGateway
    val controller: RotationController
    val ruleStore: AppRuleStore
    val permissions: PermissionInspector
    val foreground: ForegroundAppMonitor
    val sensorEngine: SensorOrientationEngine
}

/** 供 Tasker / 快捷方式 / 其它应用调用的外部指令（同时登记在 AndroidManifest 中）。 */
object RotationActions {
    const val ACTION_SET_ROTATION = "com.lazyrotate.app.action.SET_ROTATION"
    const val ACTION_CYCLE = "com.lazyrotate.app.action.CYCLE"
    const val ACTION_FLIP = "com.lazyrotate.app.action.FLIP"

    /** RotationTarget.code */
    const val EXTRA_TARGET = "com.lazyrotate.app.extra.TARGET"
}

/*
 * core 模块必须提供的具体入口（包名与签名冻结）：
 *
 *   package com.lazyrotate.app.core
 *   object ServiceLocator {
 *       fun container(context: android.content.Context): com.lazyrotate.app.contract.AppContainer
 *   }
 */
