package com.lazyrotate.app.ui

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.os.Build
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lazyrotate.app.R
import com.lazyrotate.app.contract.AppContainer
import com.lazyrotate.app.contract.AppRule
import com.lazyrotate.app.contract.PermissionSnapshot
import com.lazyrotate.app.contract.RotationAngle
import com.lazyrotate.app.contract.RotationStatus
import com.lazyrotate.app.contract.RotationTarget
import com.lazyrotate.app.core.ServiceLocator
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 一次性操作反馈，界面把它翻成 Snackbar 文案。 */
enum class UiEvent(@StringRes val messageRes: Int) {
    MISSING_WRITE_SETTINGS(R.string.msg_missing_write_settings),
    ACTION_FAILED(R.string.msg_action_failed),
    RULE_SAVED(R.string.msg_rule_saved),
    RULE_DELETED(R.string.msg_rule_deleted),
    OVERLAY_FAILED(R.string.msg_overlay_failed),
    SENSOR_NEED_PERMISSION(R.string.msg_sensor_need_permission),
}

/**
 * 界面唯一的业务入口。
 *
 * 依赖全部来自 core 模块提供的 [ServiceLocator.container]（契约冻结的签名），
 * 这里只调用契约里的方法，不自己实现任何 Rotate 逻辑，也不 new 具体实现类。
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val container: AppContainer = ServiceLocator.container(application)

    // ---------- 契约状态 ----------

    /** 控制器状态：目标、实际角度、自动旋转、锁是否生效。 */
    val status: StateFlow<RotationStatus> = container.controller.status

    /** 按应用规则。冷启动时先给空列表，规则为空也必须能渲染。 */
    val rules: StateFlow<List<AppRule>> = container.ruleStore.rules
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList<AppRule>())

    /** 前台应用包名；无障碍没开时核心会一直给 null。 */
    val foregroundPackage: StateFlow<String?> = container.foreground.foregroundPackage

    /** 躺姿引擎输出：建议角度为 null = 「保持现状」，这是特性。 */
    val sensorSuggested: StateFlow<RotationAngle?> = container.sensorEngine.suggested
    val sensorConfidence: StateFlow<Float> = container.sensorEngine.confidence

    // ---------- 界面自己的存活状态 ----------

    private val _permissions = MutableStateFlow(safePermissionSnapshot())
    val permissions: StateFlow<PermissionSnapshot> = _permissions.asStateFlow()

    private val _sensorEnabled = MutableStateFlow(
        runCatching { container.sensorEngine.isRunning }.getOrDefault(false),
    )
    val sensorEnabled: StateFlow<Boolean> = _sensorEnabled.asStateFlow()

    private val _naturalLandscape = MutableStateFlow(
        runCatching { container.gateway.naturalOrientationIsLandscape() }.getOrDefault(true),
    )
    val naturalLandscape: StateFlow<Boolean> = _naturalLandscape.asStateFlow()

    /** 悬浮球是否在跑：契约里没有提供状态，UI 侧按自己发过的启动/停止意图记着。 */
    private val _overlayEnabled = MutableStateFlow(false)
    val overlayEnabled: StateFlow<Boolean> = _overlayEnabled.asStateFlow()

    private val _events = MutableSharedFlow<UiEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<UiEvent> = _events.asSharedFlow()

    init {
        // 权限变化（用户在系统设置里授予后回来）同步到界面。
        viewModelScope.launch {
            container.permissions.flow
                .catch { _permissions.value = safePermissionSnapshot() }
                .collect { _permissions.value = it }
        }
        // 冷启动先把系统真实状态拉一遍（幂等，不会写设置）。
        viewModelScope.launch { runCatching { container.controller.refresh() } }
    }

    // ---------- 动作：旋转 ----------

    fun applyTarget(target: RotationTarget) {
        viewModelScope.launch {
            val ok = runCatching { container.controller.apply(target) }.getOrDefault(false)
            if (!ok) reportFailure()
        }
    }

    fun flip() {
        viewModelScope.launch {
            val ok = runCatching { container.controller.flip180() }.getOrDefault(false)
            if (!ok) reportFailure()
        }
    }

    fun cycle() {
        viewModelScope.launch {
            val ok = runCatching { container.controller.cycle() }.getOrDefault(false)
            if (!ok) reportFailure()
        }
    }

    // ---------- 动作：躺姿引擎 ----------

    fun setSensorEnabled(enabled: Boolean) {
        runCatching {
            if (enabled) container.sensorEngine.start() else container.sensorEngine.stop()
        }.onFailure {
            _events.tryEmit(UiEvent.ACTION_FAILED)
        }
        _sensorEnabled.value = enabled
        if (enabled && !_permissions.value.writeSettings) {
            _events.tryEmit(UiEvent.SENSOR_NEED_PERMISSION)
        }
    }

    /** 便捷开关（磁贴 / 悬浮球之外的第三个入口可能用到）。 */
    fun toggleSensor() {
        setSensorEnabled(!_sensorEnabled.value)
    }

    // ---------- 动作：按应用规则 ----------

    fun saveRule(packageName: String, label: String, target: RotationTarget) {
        viewModelScope.launch {
            val ok = runCatching {
                container.ruleStore.upsert(
                    AppRule(
                        packageName = packageName,
                        label = label,
                        target = target,
                        enabled = true,
                    ),
                )
            }.isSuccess
            _events.tryEmit(if (ok) UiEvent.RULE_SAVED else UiEvent.ACTION_FAILED)
        }
    }

    fun setRuleEnabled(rule: AppRule, enabled: Boolean) {
        viewModelScope.launch {
            val ok = runCatching { container.ruleStore.upsert(rule.copy(enabled = enabled)) }.isSuccess
            if (!ok) _events.tryEmit(UiEvent.ACTION_FAILED)
        }
    }

    /** 只改方向，保持原来的启用状态。 */
    fun setRuleTarget(rule: AppRule, target: RotationTarget) {
        viewModelScope.launch {
            val ok = runCatching { container.ruleStore.upsert(rule.copy(target = target)) }.isSuccess
            if (!ok) _events.tryEmit(UiEvent.ACTION_FAILED)
        }
    }

    fun deleteRule(packageName: String) {
        viewModelScope.launch {
            val ok = runCatching { container.ruleStore.remove(packageName) }.isSuccess
            _events.tryEmit(if (ok) UiEvent.RULE_DELETED else UiEvent.ACTION_FAILED)
        }
    }

    // ---------- 动作：悬浮球 ----------

    /**
     * 悬浮球服务由 core 模块实现，但契约里没有开/关接口；
     * 这里按清单里冻结的组件名显式启动/停止，不走隐式广播，也不在后台自启
     * （只可能由用户在本界面的前台点击触发）。
     */
    fun setOverlayEnabled(enabled: Boolean) {
        val app = getApplication<Application>()
        val intent = Intent().setComponent(
            ComponentName(app.packageName, OVERLAY_SERVICE_CLASS),
        )
        val ok = runCatching {
            if (enabled) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    app.startForegroundService(intent)
                } else {
                    app.startService(intent)
                }
            } else {
                app.stopService(intent)
            }
        }.isSuccess
        if (ok) {
            _overlayEnabled.value = enabled
        } else {
            _events.tryEmit(UiEvent.OVERLAY_FAILED)
        }
    }

    /** 便捷开关。 */
    fun toggleOverlay() {
        setOverlayEnabled(!_overlayEnabled.value)
    }

    // ---------- 动作：设备 / 权限 ----------

    fun setNaturalLandscape(landscape: Boolean) {
        val ok = runCatching { container.gateway.setNaturalOrientationIsLandscape(landscape) }.isSuccess
        _naturalLandscape.value = landscape
        viewModelScope.launch { runCatching { container.controller.refresh() } }
        if (!ok) _events.tryEmit(UiEvent.ACTION_FAILED)
    }

    /** 从系统重新读权限与真实旋转状态（每次回到前台调用）。 */
    fun refreshAll() {
        _permissions.value = safePermissionSnapshot()
        viewModelScope.launch { runCatching { container.controller.refresh() } }
    }

    // ---------- 内部 ----------

    private fun safePermissionSnapshot(): PermissionSnapshot =
        runCatching { container.permissions.snapshot() }.getOrDefault(PermissionSnapshot())

    private fun reportFailure() {
        val event = if (_permissions.value.writeSettings) {
            UiEvent.ACTION_FAILED
        } else {
            UiEvent.MISSING_WRITE_SETTINGS
        }
        _events.tryEmit(event)
    }

    private companion object {
        /** 与 AndroidManifest.xml 里冻结的组件名一致。 */
        const val OVERLAY_SERVICE_CLASS = "com.lazyrotate.app.core.FloatingOverlayService"
    }
}
