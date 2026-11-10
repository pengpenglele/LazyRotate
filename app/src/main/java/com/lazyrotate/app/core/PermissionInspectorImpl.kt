package com.lazyrotate.app.core

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityManager
import com.lazyrotate.app.contract.PermissionInspector
import com.lazyrotate.app.contract.PermissionSnapshot
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 权限状态探测。UI 的授权引导流程只依赖这里，因此每个探针都必须「失败即 false、绝不抛异常」。
 *
 * P0 只需 writeSettings（`Settings.System.canWrite`）——那是免 root 路径唯一的门槛。
 * 其余四项（无障碍 / 悬浮窗 / 通知 / WRITE_SECURE_SETTINGS）都是可选能力，缺了就降级。
 *
 * [flow] 的更新靠 ContentObserver：WRITE_SETTINGS、无障碍开关、悬浮窗开关在系统设置里改完回来时，
 * Settings 数据库都会变更，观察者能及时把新状态推给 UI，不需要 Activity onResume 手动刷新（虽然 UI 也可以调）。
 */
class PermissionInspectorImpl(context: Context) : PermissionInspector {

    private val appContext: Context = context.applicationContext ?: context
    private val resolver: ContentResolver = appContext.contentResolver

    private val _flow = MutableStateFlow(PermissionSnapshot())
    override val flow: Flow<PermissionSnapshot> = _flow.asStateFlow()

    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean, uri: Uri?) {
            _flow.value = compute()
        }
    }

    init {
        _flow.value = compute()
        try {
            resolver.registerContentObserver(Settings.System.CONTENT_URI, true, observer)
            resolver.registerContentObserver(Settings.Secure.CONTENT_URI, true, observer)
        } catch (t: Throwable) {
            Log.w(TAG, "register permission observer failed", t)
        }
    }

    override fun snapshot(): PermissionSnapshot = compute().also { _flow.value = it }

    // ---------------------------------------------------------------- 各探针

    private fun compute(): PermissionSnapshot = PermissionSnapshot(
        writeSettings = canWriteSystemSettings(),
        accessibility = isAccessibilityEnabled(),
        overlay = canDrawOverlays(),
        notifications = canPostNotifications(),
        secureSettings = hasPermission(Manifest.permission.WRITE_SECURE_SETTINGS),
    )

    private fun canWriteSystemSettings(): Boolean = try {
        Settings.System.canWrite(appContext)
    } catch (t: Throwable) {
        Log.w(TAG, "Settings.System.canWrite failed", t)
        false
    }

    private fun canDrawOverlays(): Boolean = try {
        Settings.canDrawOverlays(appContext)
    } catch (t: Throwable) {
        Log.w(TAG, "Settings.canDrawOverlays failed", t)
        false
    }

    /**
     * API < 33 没有 POST_NOTIFICATIONS 运行时权限，视为已授权（通知是否真的显示由系统开关决定）。
     */
    private fun canPostNotifications(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return hasPermission(Manifest.permission.POST_NOTIFICATIONS)
    }

    /**
     * 两条路都查一遍：Settings.Secure 字符串是最权威的事实（系统自己写的），
     * AccessibilityManager 列表是它的投影。任一条命中即算已开启。
     */
    private fun isAccessibilityEnabled(): Boolean =
        isEnabledBySecureSetting() || isEnabledByAccessibilityManager()

    private fun isEnabledBySecureSetting(): Boolean = try {
        val enabled = Settings.Secure.getInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0) == 1
        if (!enabled) {
            false
        } else {
            val raw = Settings.Secure.getString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            if (raw.isNullOrEmpty()) {
                false
            } else {
                val component = ComponentName(appContext, RotationAccessibilityService::class.java)
                val flattened = component.flattenToString()
                val shortName = component.flattenToShortString()
                raw.split(':').any { entry ->
                    entry.equals(flattened, ignoreCase = true) || entry.equals(shortName, ignoreCase = true)
                }
            }
        }
    } catch (t: Throwable) {
        Log.w(TAG, "read accessibility setting failed", t)
        false
    }

    private fun isEnabledByAccessibilityManager(): Boolean = try {
        val manager = appContext.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
        if (manager == null || !manager.isEnabled) {
            false
        } else {
            val component = ComponentName(appContext, RotationAccessibilityService::class.java)
            val flattened = component.flattenToString()
            val shortName = component.flattenToShortString()
            manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                .any { info ->
                    val serviceInfo = info?.resolveInfo?.serviceInfo
                    if (serviceInfo == null) {
                        false
                    } else {
                        val installed = ComponentName(serviceInfo.packageName, serviceInfo.name)
                        installed.flattenToString() == flattened ||
                            installed.flattenToShortString() == shortName
                    }
                }
        }
    } catch (t: Throwable) {
        Log.w(TAG, "AccessibilityManager probe failed", t)
        false
    }

    private fun hasPermission(permission: String): Boolean = try {
        appContext.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    } catch (t: Throwable) {
        false
    }

    private companion object {
        const val TAG = "PermissionInspector"
    }
}
