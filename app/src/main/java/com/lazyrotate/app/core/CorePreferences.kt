package com.lazyrotate.app.core

import android.content.Context
import android.content.SharedPreferences

/**
 * 核心层的轻量偏好存储。
 *
 * 为什么是 SharedPreferences 而不是 DataStore：
 * 这里存的是几个必须**同步**读到的小状态（自然方向、上次强制目标、悬浮球坐标、开机恢复开关）。
 * `RotationGateway.naturalOrientationIsLandscape()` 是同步接口、又会被主线程调用，而 DataStore 只有挂起读，
 * 用 runBlocking 去读等于在主线程做磁盘 IO。体积大、天然是流式语义的「按应用规则」才用 DataStore
 * （见 [DataStoreAppRuleStore]）。
 */
internal object CorePreferences {

    private const val FILE_NAME = "lazy_rotate_core"

    /** 自然方向是否为横屏。自动探测结果与用户覆盖共用此键：写入了就以写入值为准（= 用户覆盖生效）。 */
    const val KEY_NATURAL_LANDSCAPE = "natural_landscape"

    /** 上次强制过的 [com.lazyrotate.app.contract.RotationTarget.code]；FOLLOW_SYSTEM 表示没有再强制。 */
    const val KEY_LAST_TARGET = "last_forced_target"

    /** 开机恢复上次锁定（默认关闭：Android 15+ 对开机后台启动限制很多，失败必须静默跳过）。 */
    const val KEY_RESTORE_ON_BOOT = "restore_on_boot"

    /** 悬浮球位置。 */
    const val KEY_OVERLAY_X = "overlay_x"
    const val KEY_OVERLAY_Y = "overlay_y"

    /** 躺姿引擎是否额外启用旋转矢量（磁力计融合）辅助做静止判定，默认关闭。 */
    const val KEY_SENSOR_ASSIST = "sensor_assist"

    const val DEFAULT_RESTORE_ON_BOOT = false
    const val DEFAULT_SENSOR_ASSIST = false

    @Volatile
    private var cached: SharedPreferences? = null

    /** 可重复调用、线程安全。 */
    fun get(context: Context): SharedPreferences {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val app = context.applicationContext ?: context
            val prefs = app.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
            cached = prefs
            return prefs
        }
    }
}
