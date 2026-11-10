package com.lazyrotate.app.core

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import androidx.annotation.RequiresApi
import com.lazyrotate.app.R
import com.lazyrotate.app.contract.RotationStatus
import com.lazyrotate.app.contract.RotationTarget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * 快速设置磁贴：躺姿下不用回到应用就能锁定 / 翻转。
 *
 * 点击 = `controller.cycle()`（跟随系统 → 锁定当前 → 翻转 180° → 跟随系统），
 * 和悬浮球单击完全同源，磁贴本身不写任何设置。
 *
 * 磁贴标签/状态在展开期间订阅 `controller.status`，所以用通知 action 或悬浮球改了方向，
 * 磁贴上的文字也会跟着变。
 *
 * 打开应用必须分支：Android 14（API 34）起 `startActivityAndCollapse(Intent)` 已废弃，
 * 官方要求用 PendingIntent 重载，否则在部分机型上直接抛异常 / 折叠面板后不跳转。
 */
class QuickTileService : TileService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var listeningJob: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        // 先主动 refresh()：UI 里刚授完权回来时，系统设置可能已经变了。
        listeningJob?.cancel()
        listeningJob = scope.launch {
            val container = ServiceLocator.container(this@QuickTileService)
            container.controller.refresh()
            renderTile(container.controller.status.value)
            container.controller.status.collect { status -> renderTile(status) }
        }
    }

    override fun onStopListening() {
        listeningJob?.cancel()
        listeningJob = null
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        if (isLocked) {
            // 锁屏状态不能直接写设置 / 弹 Activity，先请用户解锁。
            unlockAndRun { performCycle() }
        } else {
            performCycle()
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- 内部实现

    private fun performCycle() {
        scope.launch {
            val service = this@QuickTileService
            val container = ServiceLocator.container(service)
            if (!container.permissions.snapshot().writeSettings) {
                // 没拿到 WRITE_SETTINGS 时静默失败最让人困惑，直接提示并带用户去授权。
                Toast.makeText(service, R.string.core_toast_no_write_settings, Toast.LENGTH_LONG).show()
                openApp()
                return@launch
            }
            val ok = container.controller.cycle()
            val status = container.controller.status.value
            val messageRes = if (ok) cycleMessage(status) else R.string.core_toast_cycle_failed
            Toast.makeText(service, messageRes, Toast.LENGTH_SHORT).show()
            renderTile(status)
        }
    }

    private fun cycleMessage(status: RotationStatus): Int = when {
        !status.lockEngaged -> R.string.core_toast_following
        status.target == RotationTarget.FLIP_180 -> R.string.core_toast_flipped
        else -> R.string.core_toast_locked
    }

    private fun renderTile(status: RotationStatus) {
        val tile = qsTile ?: return
        tile.label = getString(
            if (status.lockEngaged) R.string.core_tile_label_locked else R.string.core_tile_label_unlocked,
        )
        tile.state = if (status.lockEngaged) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.icon = Icon.createWithResource(this, R.drawable.core_ic_tile)
        tile.updateTile()
    }

    @Suppress("DEPRECATION")
    private fun openApp() {
        val intent = packageManager.getLaunchIntentForPackage(packageName) ?: return
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityWithPendingIntent(intent)
        } else {
            startActivityAndCollapse(intent)
        }
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun startActivityWithPendingIntent(intent: Intent) {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        startActivityAndCollapse(pendingIntent)
    }
}
