package com.lazyrotate.app.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.lazyrotate.app.contract.RotationTarget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 开机恢复上次的锁定状态（**可选功能，默认关闭**）。
 *
 * 为什么默认关闭：Android 15+ 对开机后台行为限制很严，而且「开机就偷偷改系统旋转」对用户来说很意外。
 * 只有在用户显式打开 `KEY_RESTORE_ON_BOOT` 时才动作，且任何失败都静默跳过、绝不弹错、绝不启动前台服务
 * （Android 15 起 BOOT_COMPLETED 基本不允许后台启动 FGS）。
 *
 * 用 `goAsync()`：写设置要读 SharedPreferences + 落盘 Settings，不能占着 onReceive 的主线程。
 */
class BootCompletedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_LOCKED_BOOT_COMPLETED) {
            return
        }
        val appContext = context.applicationContext ?: context

        val target = try {
            val prefs = CorePreferences.get(appContext)
            if (!prefs.getBoolean(CorePreferences.KEY_RESTORE_ON_BOOT, CorePreferences.DEFAULT_RESTORE_ON_BOOT)) {
                return
            }
            val savedCode = prefs.getString(CorePreferences.KEY_LAST_TARGET, null) ?: return
            RotationTarget.fromCode(savedCode)
        } catch (t: Throwable) {
            // 直接启动（未解锁）阶段读凭据加密存储会失败：静默跳过。
            Log.w(TAG, "read restore preference failed, skip", t)
            return
        }
        if (target == RotationTarget.FOLLOW_SYSTEM) return

        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                ServiceLocator.container(appContext).controller.apply(target)
            } catch (t: Throwable) {
                Log.w(TAG, "restore rotation on boot failed, skip", t)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private companion object {
        const val TAG = "BootCompletedReceiver"
    }
}
