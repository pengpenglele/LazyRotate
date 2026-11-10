package com.lazyrotate.app.core

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.lazyrotate.app.contract.ForegroundAppMonitor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 按应用规则的「前台包名」来源。
 *
 * 隐私边界（刻意写在这里，方便 review）：**只读事件里的 packageName**，
 * 不调用 `getRootInActiveWindow()`、不读窗口内容、不注入手势，`canRetrieveWindowContent` 在
 * res/xml/accessibility_service_config.xml 里也是 false。
 *
 * 事件是高频的，所以做去重：只有包名真的变化时才更新状态并查规则（`setForegroundPackage` 返回是否变化）。
 * 输入法窗口会频繁抢焦点，按 Settings.Secure 里的 IME 列表过滤掉（带缓存，避免每个事件都读设置）。
 */
class RotationAccessibilityService : AccessibilityService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var imePackages: Set<String> = emptySet()
    private var imePackagesLoadedAtMs = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        ForegroundAppBridge.onConnected()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val safeEvent = event ?: return
        val type = safeEvent.eventType
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            type != AccessibilityEvent.TYPE_WINDOWS_CHANGED
        ) {
            return
        }
        val incomingPackage = safeEvent.packageName?.toString()?.takeIf { it.isNotEmpty() } ?: return
        if (incomingPackage == packageName) return
        if (isInputMethodPackage(incomingPackage)) return
        if (!ForegroundAppBridge.setForegroundPackage(incomingPackage)) return
        scope.launch { applyRuleFor(incomingPackage) }
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        ForegroundAppBridge.onDisconnected()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        ForegroundAppBridge.onDisconnected()
        scope.cancel()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- 内部实现

    private suspend fun applyRuleFor(packageName: String) {
        try {
            val container = ServiceLocator.container(this)
            val rule = container.ruleStore.ruleFor(packageName) ?: return
            if (!rule.enabled) return
            // FOLLOW_SYSTEM 也走同一条路：把自动旋转交还系统。
            container.controller.apply(rule.target)
        } catch (t: Throwable) {
            // 无障碍服务崩掉会被系统直接停用，任何异常都必须吞掉。
            Log.w(TAG, "apply rule failed for $packageName", t)
        }
    }

    private fun isInputMethodPackage(packageName: String): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (now - imePackagesLoadedAtMs > IME_CACHE_MS) {
            imePackages = loadInputMethodPackages()
            imePackagesLoadedAtMs = now
        }
        return imePackages.contains(packageName)
    }

    /** 只读 Settings.Secure 的 IME 名单，不碰任何窗口内容。 */
    private fun loadInputMethodPackages(): Set<String> {
        val result = mutableSetOf<String>()
        try {
            val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_INPUT_METHODS)
            if (!enabled.isNullOrEmpty()) {
                enabled.split(':').forEach { entry -> packageOfComponent(entry)?.let(result::add) }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "read ENABLED_INPUT_METHODS failed", t)
        }
        try {
            val defaultIme = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
            packageOfComponent(defaultIme)?.let(result::add)
        } catch (t: Throwable) {
            Log.w(TAG, "read DEFAULT_INPUT_METHOD failed", t)
        }
        return result
    }

    /** "com.foo.bar/.ImeService" → "com.foo.bar" */
    private fun packageOfComponent(value: String?): String? {
        if (value.isNullOrEmpty()) return null
        val pkg = value.substringBefore('/').trim()
        return pkg.takeIf { it.isNotEmpty() && it != "null" }
    }

    private companion object {
        const val TAG = "RotationA11yService"
        const val IME_CACHE_MS = 60_000L
    }
}

/**
 * 服务与组合根之间的静态桥。
 *
 * 为什么需要它：`ForegroundAppMonitor` 要能在**没有**服务实例时也被注入（容器先于服务创建），
 * 而系统只创建/销毁服务实例。桥只暴露 StateFlow，任何人都不能反向驱动它。
 */
internal object ForegroundAppBridge {

    private val _foregroundPackage = MutableStateFlow<String?>(null)
    val foregroundPackage: StateFlow<String?> = _foregroundPackage.asStateFlow()

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    /** 返回包名是否真的发生了变化（调用方据此决定要不要查规则）。 */
    fun setForegroundPackage(packageName: String?): Boolean {
        if (_foregroundPackage.value == packageName) return false
        _foregroundPackage.value = packageName
        return true
    }

    fun onConnected() {
        _connected.value = true
    }

    fun onDisconnected() {
        _connected.value = false
        // 服务没连接时前台包名必须是 null（契约要求），否则 UI 会显示过期的应用。
        _foregroundPackage.value = null
    }
}

/**
 * [ForegroundAppMonitor] 的免 root 实现：数据源就是 [RotationAccessibilityService] 的静态桥。
 * 无障碍没开启时 `foregroundPackage` 恒为 null、`isRunning` 为 false，不崩、也不去反复拉起设置页。
 */
class AccessibilityForegroundAppMonitor : ForegroundAppMonitor {
    override val foregroundPackage: StateFlow<String?> = ForegroundAppBridge.foregroundPackage
    override val isRunning: Boolean get() = ForegroundAppBridge.connected.value
}
