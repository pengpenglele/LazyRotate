package com.lazyrotate.app.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.lazyrotate.app.R
import com.lazyrotate.app.contract.RotationActions
import com.lazyrotate.app.contract.RotationStatus
import com.lazyrotate.app.contract.RotationTarget

/**
 * 常驻通知与权限提示通知的唯一构造点。
 *
 * 为什么独立成文件：悬浮球服务的通知、权限缺失提示都要用同一套 channel / 图标 / action，
 * 分散写容易导致 channel 名字不一致（那会直接创建出两个通知渠道）。
 *
 * 三个 action（锁定当前 / 翻转 180° / 跟随系统）都指向 [RotationCommandReceiver]，
 * 也就是全部回到同一个 [com.lazyrotate.app.contract.RotationController]，没有第二套写设置逻辑。
 */
object NotificationFactory {

    const val CHANNEL_ID_CORE = "lazy_rotate_core"

    /** 悬浮球前台服务通知。 */
    const val NOTIFICATION_ID_OVERLAY = 1101

    /** 「缺权限」等一次性提示。 */
    const val NOTIFICATION_ID_ALERT = 1102

    /** 打开应用用字符串常量而不是 `MainActivity::class`：core 不依赖 UI 层的类，避免交叉编译依赖。 */
    private const val MAIN_ACTIVITY_CLASS = "com.lazyrotate.app.MainActivity"

    private const val REQUEST_CODE_OPEN_APP = 11
    private const val REQUEST_CODE_COMMAND_BASE = 21
    private const val PENDING_INTENT_FLAGS =
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

    /** 幂等：渠道已存在就直接返回。channel 名称来自 strings_core.xml。 */
    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        try {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                ?: return
            if (manager.getNotificationChannel(CHANNEL_ID_CORE) != null) return
            val channel = NotificationChannel(
                CHANNEL_ID_CORE,
                context.getString(R.string.core_notif_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = context.getString(R.string.core_notif_channel_description)
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            manager.createNotificationChannel(channel)
        } catch (t: Throwable) {
            Log.w(TAG, "create notification channel failed", t)
        }
    }

    /** 悬浮球前台服务的常驻通知：显示当前是否锁定，并带上三个一键 action。 */
    fun buildForeground(context: Context, status: RotationStatus): Notification {
        ensureChannel(context)
        val locked = status.lockEngaged
        return NotificationCompat.Builder(context, CHANNEL_ID_CORE)
            .setSmallIcon(R.drawable.core_ic_notification)
            .setContentTitle(context.getString(R.string.core_notif_title))
            .setContentText(
                context.getString(
                    if (locked) R.string.core_notif_text_locked else R.string.core_notif_text_unlocked,
                ),
            )
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(openAppPendingIntent(context))
            .addAction(
                R.drawable.core_ic_notification,
                context.getString(R.string.core_notif_action_lock),
                commandPendingIntent(
                    context,
                    RotationActions.ACTION_SET_ROTATION,
                    RotationTarget.LOCK_CURRENT.code,
                ),
            )
            .addAction(
                R.drawable.core_ic_notification,
                context.getString(R.string.core_notif_action_flip),
                commandPendingIntent(context, RotationActions.ACTION_FLIP, null),
            )
            .addAction(
                R.drawable.core_ic_notification,
                context.getString(R.string.core_notif_action_follow),
                commandPendingIntent(
                    context,
                    RotationActions.ACTION_SET_ROTATION,
                    RotationTarget.FOLLOW_SYSTEM.code,
                ),
            )
            .build()
    }

    /**
     * 没有悬浮窗权限时的提示。悬浮球服务会在 startForeground 之后立刻调用它再 stopSelf，
     * 这样既满足「前台服务必须startForeground」的系统要求，又不会崩、用户还能看懂原因。
     */
    fun notifyOverlayPermissionMissing(context: Context) {
        try {
            ensureChannel(context)
            val text = context.getString(R.string.core_overlay_missing_text)
            val notification = NotificationCompat.Builder(context, CHANNEL_ID_CORE)
                .setSmallIcon(R.drawable.core_ic_notification)
                .setContentTitle(context.getString(R.string.core_overlay_missing_title))
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true)
                .setContentIntent(openAppPendingIntent(context))
                .build()
            val manager = NotificationManagerCompat.from(context)
            if (manager.areNotificationsEnabled()) {
                manager.notify(NOTIFICATION_ID_ALERT, notification)
            }
        } catch (t: Throwable) {
            // 用户没给通知权限时到这里：静默即可，悬浮球本来就已经停掉了。
            Log.w(TAG, "post overlay-permission notification failed", t)
        }
    }

    // ---------------------------------------------------------------- 内部实现

    private fun openAppPendingIntent(context: Context): PendingIntent {
        // 用 launch intent 而不是显式类名，core 就不需要编译期依赖 UI 层的 MainActivity。
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
                component = ComponentName(context.packageName, MAIN_ACTIVITY_CLASS)
            }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(context, REQUEST_CODE_OPEN_APP, intent, PENDING_INTENT_FLAGS)
    }

    private fun commandPendingIntent(
        context: Context,
        action: String,
        targetCode: String?,
    ): PendingIntent {
        val intent = Intent(context, RotationCommandReceiver::class.java).setAction(action)
        if (targetCode != null) {
            intent.putExtra(RotationActions.EXTRA_TARGET, targetCode)
        }
        // PendingIntent 的相等性不看 extras，所以必须让 requestCode 区分开。
        val requestCode = REQUEST_CODE_COMMAND_BASE + action.hashCode() * 31 +
            (targetCode?.hashCode() ?: 0)
        return PendingIntent.getBroadcast(context, requestCode, intent, PENDING_INTENT_FLAGS)
    }

    private const val TAG = "NotificationFactory"
}
