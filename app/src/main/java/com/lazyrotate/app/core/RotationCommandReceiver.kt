package com.lazyrotate.app.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.lazyrotate.app.contract.RotationActions
import com.lazyrotate.app.contract.RotationTarget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 外部指令入口（Tasker / 快捷方式 / 通知 action / 其它应用）：
 *   `am broadcast -a com.lazyrotate.app.action.SET_ROTATION --es com.lazyrotate.app.extra.TARGET landscape`
 *
 * 用 `goAsync()` + 协程：写设置是 IO，不能占着主线程把广播超时（>10s 会被 ANR，
 * 而且要跟 `RotationController` 的挂起接口对齐）。`goAsync()` 必须在 onReceive 返回前调用，
 * 协程结束（含异常路径）一定 `finish()`，否则系统会判定广播进程卡住。
 *
 * 安全性：这个 receiver 是 exported 的，任何应用都能发这三个 action。它能做的只是改屏幕方向
 * （需要我们自己已拿到 WRITE_SETTINGS），拿不到权限时 controller 会返回 false，不会越权。
 */
class RotationCommandReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != RotationActions.ACTION_SET_ROTATION &&
            action != RotationActions.ACTION_CYCLE &&
            action != RotationActions.ACTION_FLIP
        ) {
            return
        }

        // 未知 / 缺失的 code 一律按 FOLLOW_SYSTEM 处理（RotationTarget.fromCode 的语义），不会崩。
        val target = RotationTarget.fromCode(intent.getStringExtra(RotationActions.EXTRA_TARGET))
        val appContext = context.applicationContext ?: context
        val pendingResult = goAsync()

        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                val controller = ServiceLocator.container(appContext).controller
                when (action) {
                    RotationActions.ACTION_CYCLE -> controller.cycle()
                    RotationActions.ACTION_FLIP -> controller.flip180()
                    else -> controller.apply(target)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "handle action $action failed", t)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private companion object {
        const val TAG = "RotationCommandReceiver"
    }
}
