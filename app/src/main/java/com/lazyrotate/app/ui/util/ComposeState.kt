package com.lazyrotate.app.ui.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect

/**
 * 生命周期感知地收集 [StateFlow]，返回 Compose 的 [State]。
 *
 * 依赖说明：项目依赖里没有单独的 `lifecycle-runtime-compose`（`collectAsStateWithLifecycle` 住在那里），
 * 所以用 `lifecycle-runtime-ktx` 提供的 [repeatOnLifecycle] 写一个等价实现：
 * 只在 STARTED 之后收集，STOPPED 时自动取消收集，避免后台空转。
 * 没有裸的 LifecycleObserver —— 生命周期判定仍由 Lifecycle 自己负责。
 *
 * LifecycleOwner 从 [LocalContext] 取（ComponentActivity 本身就是 LifecycleOwner），
 * 这样不依赖任何「LocalLifecycleOwner 搬家」的版本差异。
 */
@Composable
fun <T> StateFlow<T>.collectAsStateLifecycleAware(): State<T> {
    val owner = LocalContext.current as? LifecycleOwner
    val source = this
    val state = remember(source) { mutableStateOf(source.value) }

    LaunchedEffect(source, owner) {
        if (owner == null) {
            // 理论上不会走到这里（Activity / Fragment 上下文都实现了 LifecycleOwner）；
            // 万一走到，退化成普通收集，保证界面能渲染而不是崩。
            source.collect { state.value = it }
        } else {
            owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                source.collect { state.value = it }
            }
        }
    }

    return state
}
