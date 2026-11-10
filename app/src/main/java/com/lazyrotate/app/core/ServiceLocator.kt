package com.lazyrotate.app.core

import android.content.Context
import com.lazyrotate.app.contract.AppContainer
import com.lazyrotate.app.contract.AppRuleStore
import com.lazyrotate.app.contract.ForegroundAppMonitor
import com.lazyrotate.app.contract.PermissionInspector
import com.lazyrotate.app.contract.RotationController
import com.lazyrotate.app.contract.RotationGateway
import com.lazyrotate.app.contract.SensorOrientationEngine

/**
 * 组合根（手写 DI，不引入 Hilt/Koin，契约第 5 条）。
 *
 * 本项目没有自定义 Application 类，所以这里必须能**懒初始化**：
 *   - `container(context)` 第一次调用时构建，之后返回同一个实例；
 *   - 可重复调用（广播、磁贴、服务、UI 各自调用都安全）；
 *   - 线程安全（双重检查 + @Volatile，synchronized 只走一次）；
 *   - 永远使用 applicationContext，不持有 Activity/Service 引用，避免泄漏。
 *
 * `init(context)` 与 `container(context)` 等价，只是给 Application / Service 的语义化入口。
 */
object ServiceLocator {

    @Volatile
    private var cached: AppContainer? = null

    private val lock = Any()

    /** 语义化入口：供（未来可能出现的）Application 或服务在启动时预热。 */
    fun init(context: Context): AppContainer = container(context)

    fun container(context: Context): AppContainer {
        cached?.let { return it }
        synchronized(lock) {
            cached?.let { return it }
            val appContext = context.applicationContext ?: context
            val built = DefaultAppContainer(appContext)
            cached = built
            return built
        }
    }

    /** 仅测试用：清空单例，让下一个 container() 重新构建。 */
    fun resetForTesting() {
        synchronized(lock) {
            cached = null
        }
    }
}

/**
 * 依赖装配顺序即生命周期顺序：gateway 无依赖 → sensorEngine 无依赖 →
 * controller 依赖前两者 → 其余各自独立。
 */
private class DefaultAppContainer(context: Context) : AppContainer {

    private val appContext: Context = context.applicationContext ?: context

    private val rotationGateway: RotationGateway = SettingsRotationGateway(appContext)

    override val gateway: RotationGateway get() = rotationGateway

    override val sensorEngine: SensorOrientationEngine = SensorOrientationEngineImpl(appContext)

    /** 磁贴/悬浮球/通知/广播/按应用规则的唯一写入口。 */
    override val controller: RotationController =
        DefaultRotationController(appContext, rotationGateway, sensorEngine)

    override val ruleStore: AppRuleStore = DataStoreAppRuleStore(appContext)

    override val permissions: PermissionInspector = PermissionInspectorImpl(appContext)

    override val foreground: ForegroundAppMonitor = AccessibilityForegroundAppMonitor()
}
