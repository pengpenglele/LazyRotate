package com.lazyrotate.app.core

import android.Manifest
import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.display.DisplayManager
import android.provider.Settings
import android.util.Log
import android.view.Display
import android.view.Surface
import android.view.WindowManager
import com.lazyrotate.app.contract.RotationAngle
import com.lazyrotate.app.contract.RotationGateway

/**
 * 免 root 的唯一可靠路径：`Settings.System`。
 *
 * P0 写序（决定成败的地方）：
 *   1. `ACCELEROMETER_ROTATION = 0`（关闭自动旋转；系统只在自动旋转关闭时才会采纳 USER_ROTATION）
 *   2. `USER_ROTATION = 0..3`（强制全局方向）
 * 两个键都属于 `android.permission.WRITE_SETTINGS`（特殊权限，用户经系统设置页手动授予）。
 *
 * 所有写操作都幂等，且**无权限时返回 false 不抛异常**（契约第 6 条）——
 * 部分 ROM 的 SettingsProvider 在越权写入时抛 SecurityException 而不是返回 false，所以全部 try/catch。
 */
class SettingsRotationGateway(context: Context) : RotationGateway {

    private val appContext: Context = context.applicationContext ?: context
    private val resolver: ContentResolver = appContext.contentResolver
    private val prefs = CorePreferences.get(appContext)

    override fun hasWriteSettings(): Boolean = try {
        Settings.System.canWrite(appContext)
    } catch (t: Throwable) {
        Log.w(TAG, "canWrite(WRITE_SETTINGS) probe failed", t)
        false
    }

    override fun hasSecureSettings(): Boolean = hasPermission(Manifest.permission.WRITE_SECURE_SETTINGS)

    override fun isAutoRotateEnabled(): Boolean = try {
        Settings.System.getInt(resolver, Settings.System.ACCELEROMETER_ROTATION, DEFAULT_AUTO_ROTATE) == 1
    } catch (t: Throwable) {
        Log.w(TAG, "read ACCELEROMETER_ROTATION failed", t)
        true
    }

    override fun currentUserRotation(): RotationAngle = try {
        // 键不存在（部分 ROM 首次开机没有这条记录）时退回真实屏幕角度，比为 0 更有用且同样诚实。
        val value = Settings.System.getInt(
            resolver,
            Settings.System.USER_ROTATION,
            displayRotation().userRotationValue,
        )
        RotationAngle.fromUserRotation(value)
    } catch (t: Throwable) {
        Log.w(TAG, "read USER_ROTATION failed", t)
        displayRotation()
    }

    override fun displayRotation(): RotationAngle = RotationAngle.fromUserRotation(rawDisplayRotation())

    /**
     * 自然方向判定：用户覆盖 > 相机传感器朝向启发式 > 平板尺寸兜底。
     * 结果会持久化，用户在设置里改过一次之后就不再自动覆盖。
     */
    override fun naturalOrientationIsLandscape(): Boolean {
        if (prefs.contains(CorePreferences.KEY_NATURAL_LANDSCAPE)) {
            return prefs.getBoolean(CorePreferences.KEY_NATURAL_LANDSCAPE, DEFAULT_NATURAL_LANDSCAPE)
        }
        val detected = detectNaturalLandscape()
        prefs.edit().putBoolean(CorePreferences.KEY_NATURAL_LANDSCAPE, detected).apply()
        return detected
    }

    override fun setNaturalOrientationIsLandscape(landscape: Boolean) {
        // 写入即视为用户覆盖：之后 naturalOrientationIsLandscape() 直接返回该值。
        prefs.edit().putBoolean(CorePreferences.KEY_NATURAL_LANDSCAPE, landscape).apply()
    }

    override fun setAutoRotateEnabled(enabled: Boolean): Boolean {
        if (!hasWriteSettings()) return false
        return putSystemInt(Settings.System.ACCELEROMETER_ROTATION, if (enabled) 1 else 0)
    }

    override fun setUserRotation(angle: RotationAngle): Boolean {
        if (!hasWriteSettings()) return false
        return putSystemInt(Settings.System.USER_ROTATION, angle.userRotationValue)
    }

    /**
     * 高级档（P2）。**诚实实现：恒返回 false。**
     *
     * 为什么不做任何写入：
     *  - 「忽略应用自身的方向请求」的开关由 WindowManagerService 持有，公开 SDK 完全没有入口；
     *    android-37 的 `Settings.Global` / `Settings.Secure` 里没有对应常量，AOSP 也只把它暴露给
     *    shell 级的 `wm set-ignore-orientation-request` 与隐藏的 IWindowManager（第三方应用无法调用）。
     *    用户可见入口是 Android 12L+ 的开发者选项，按应用更只能靠
     *    `adb shell am compat enable OVERRIDE_ENABLE_COMPAT_IGNORE_REQUESTED_ORIENTATION <包名>`。
     *  - 写 `Settings.Global` 里的猜测键**不会有任何效果**（WMS 根本不读它），
     *    但 `putInt` 会返回 true —— 那等于骗调用方「已生效」，比返回 false 更糟。
     *
     * 因此这里不写入任何设置，直接返回 false，让 UI 把它当作「需要电脑执行一次 ADB 命令」的引导。
     * 签名保留是为了将来用 Shizuku/root 真正实现时有统一入口。
     */
    override fun setIgnoreOrientationRequest(ignore: Boolean): Boolean = false

    // ---------------------------------------------------------------- 内部实现

    private fun putSystemInt(key: String, value: Int): Boolean = try {
        Settings.System.putInt(resolver, key, value)
    } catch (t: Throwable) {
        Log.w(TAG, "write $key=$value failed", t)
        false
    }

    private fun hasPermission(permission: String): Boolean = try {
        appContext.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    } catch (t: Throwable) {
        Log.w(TAG, "permission probe failed: $permission", t)
        false
    }

    /** 只走公开 API：DisplayManager 默认显示器的 rotation，语义与 USER_ROTATION 的 0..3 一致。 */
    private fun rawDisplayRotation(): Int {
        return try {
            val manager = appContext.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
            manager?.getDisplay(Display.DEFAULT_DISPLAY)?.rotation ?: legacyDisplayRotation()
        } catch (t: Throwable) {
            Log.w(TAG, "read display rotation failed", t)
            legacyDisplayRotation()
        }
    }

    @Suppress("DEPRECATION")
    private fun legacyDisplayRotation(): Int {
        return try {
            val wm = appContext.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            wm?.defaultDisplay?.rotation ?: Surface.ROTATION_0
        } catch (t: Throwable) {
            Surface.ROTATION_0
        }
    }

    /**
     * 传感器朝向启发式：后置相机 SENSOR_ORIENTATION 为 90/270 说明模组「竖装」→ 自然竖屏；
     * 0/180 说明「横装」→ 自然横屏。探测失败（无相机 / 无权限 / ROM 异常）时返回 null，
     * 退回 `smallestScreenWidthDp >= 600` 的平板判定。宁可给保守兜底，也不要抛异常。
     */
    private fun detectNaturalLandscape(): Boolean {
        detectFromCameraSensor()?.let { return it }
        return try {
            appContext.resources.configuration.smallestScreenWidthDp >= TABLET_SW_DP
        } catch (t: Throwable) {
            DEFAULT_NATURAL_LANDSCAPE
        }
    }

    private fun detectFromCameraSensor(): Boolean? = try {
        val hasCamera = appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)
        val manager = appContext.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
        if (!hasCamera || manager == null) {
            null
        } else {
            val cameraId = manager.cameraIdList.firstOrNull()
            val orientation = cameraId?.let {
                manager.getCameraCharacteristics(it).get(CameraCharacteristics.SENSOR_ORIENTATION)
            }
            when (orientation) {
                90, 270 -> false
                0, 180 -> true
                else -> null
            }
        }
    } catch (t: Throwable) {
        Log.w(TAG, "camera sensor orientation probe failed", t)
        null
    }

    private companion object {
        const val TAG = "SettingsRotationGateway"

        /** 设置项缺失时的系统默认值：自动旋转默认开。 */
        const val DEFAULT_AUTO_ROTATE = 1

        /** 探测不出自然方向时的兜底：手机居多，先按竖屏。 */
        const val DEFAULT_NATURAL_LANDSCAPE = false

        const val TABLET_SW_DP = 600
    }
}
