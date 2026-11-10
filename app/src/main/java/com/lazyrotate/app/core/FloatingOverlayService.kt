package com.lazyrotate.app.core

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.ImageView
import android.widget.Toast
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import com.lazyrotate.app.R
import com.lazyrotate.app.contract.RotationStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 悬浮球：不离开当前应用就能锁定 / 翻转。
 *
 *  单击 = `cycle()`，双击 = `flip180()`，长按 = 打开应用并停止服务，拖动 = 移动并持久化坐标。
 *
 * 权限与启动约束：
 *  - 悬浮窗权限（SYSTEM_ALERT_WINDOW / `Settings.canDrawOverlays`）没有时**不崩**：
 *    先 startForeground（系统要求 startForegroundService 后必须尽快进前台，否则会 ANR/崩），
 *    再发一条「缺权限」通知，然后 stopSelf。
 *  - Android 14+ 禁止后台启动前台服务，所以只允许由 UI/磁贴等前台操作调用 [start]，本服务自己不拉起自己。
 *
 * 通知渠道走 [NotificationFactory]（API 26+ 必须建 channel，channel 名来自 strings_core.xml）。
 */
class FloatingOverlayService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val prefs: SharedPreferences
        get() = CorePreferences.get(this)

    private var windowManager: WindowManager? = null
    private var bubbleView: View? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val container = ServiceLocator.container(this)
        val notification = NotificationFactory.buildForeground(this, container.controller.status.value)
        if (!startForegroundSafely(notification)) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!canDrawOverlays()) {
            NotificationFactory.notifyOverlayPermissionMissing(this)
            stopSelf()
            return START_NOT_STICKY
        }
        if (bubbleView == null) {
            attachBubble()
            observeStatus()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        val view = bubbleView
        val manager = windowManager
        if (view != null && manager != null) {
            try {
                manager.removeView(view)
            } catch (t: Throwable) {
                Log.w(TAG, "remove overlay view failed", t)
            }
        }
        bubbleView = null
        windowManager = null
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (t: Throwable) {
            Log.w(TAG, "stopForeground failed", t)
        }
        super.onDestroy()
    }

    // ---------------------------------------------------------------- 前台通知

    private fun startForegroundSafely(notification: Notification): Boolean = try {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        ServiceCompat.startForeground(
            this,
            NotificationFactory.NOTIFICATION_ID_OVERLAY,
            notification,
            type,
        )
        true
    } catch (t: Throwable) {
        // Android 12+ 后台启动 FGS 会抛 ForegroundServiceStartNotAllowedException；
        // 契约要求「失败就静默降级」，不要弹崩溃。
        Log.w(TAG, "startForeground failed", t)
        false
    }

    private fun observeStatus() {
        scope.launch {
            ServiceLocator.container(this@FloatingOverlayService).controller.status
                .collect { status -> updateForegroundNotification(status) }
        }
    }

    private fun updateForegroundNotification(status: RotationStatus) {
        try {
            val manager = NotificationManagerCompat.from(this)
            if (manager.areNotificationsEnabled()) {
                // 同一个 id 覆盖式更新，仍然是本服务的 FGS 通知。
                manager.notify(
                    NotificationFactory.NOTIFICATION_ID_OVERLAY,
                    NotificationFactory.buildForeground(this, status),
                )
            }
        } catch (t: Throwable) {
            Log.w(TAG, "update foreground notification failed", t)
        }
    }

    // ---------------------------------------------------------------- 悬浮球

    @Suppress("DEPRECATION")
    private fun attachBubble() {
        val manager = getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        if (manager == null) {
            stopSelf()
            return
        }
        val metrics = resources.displayMetrics
        val sizePx = (BUBBLE_SIZE_DP * metrics.density).roundToInt()
        val marginPx = (BUBBLE_MARGIN_DP * metrics.density).roundToInt()
        val defaultX = (metrics.widthPixels - sizePx - marginPx).coerceAtLeast(0)
        val defaultY = (metrics.heightPixels * DEFAULT_Y_RATIO).toInt()

        val view = ImageView(this).apply {
            setImageResource(R.drawable.core_ic_bubble)
            contentDescription = getString(R.string.core_bubble_content_description)
            alpha = BUBBLE_ALPHA
        }

        // API 26 起 TYPE_PHONE 对 targetSdk>=26 的应用不可用，必须走 TYPE_APPLICATION_OVERLAY。
        val windowType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            sizePx,
            sizePx,
            windowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = readSavedCoordinate(CorePreferences.KEY_OVERLAY_X, defaultX, metrics.widthPixels - sizePx)
            y = readSavedCoordinate(CorePreferences.KEY_OVERLAY_Y, defaultY, metrics.heightPixels - sizePx)
        }

        attachTouchHandler(view, params)
        try {
            manager.addView(view, params)
        } catch (t: Throwable) {
            // 极少数 ROM 即便 hasDrawOverlays 为 true 也会拒绝 addView。
            Log.w(TAG, "add overlay view failed", t)
            stopSelf()
            return
        }
        windowManager = manager
        bubbleView = view
    }

    /** 屏幕转过之后旧坐标可能已经出界，读回来先夹到可视范围内。 */
    private fun readSavedCoordinate(key: String, default: Int, maxValue: Int): Int {
        if (!prefs.contains(key)) return default
        return prefs.getInt(key, default).coerceIn(0, maxValue.coerceAtLeast(0))
    }

    private fun saveBubblePosition(x: Int, y: Int) {
        prefs.edit().putInt(CorePreferences.KEY_OVERLAY_X, x).putInt(CorePreferences.KEY_OVERLAY_Y, y).apply()
    }

    private fun canDrawOverlays(): Boolean = try {
        Settings.canDrawOverlays(this)
    } catch (t: Throwable) {
        Log.w(TAG, "canDrawOverlays probe failed", t)
        false
    }

    private fun attachTouchHandler(view: View, params: WindowManager.LayoutParams) {
        val gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                onBubbleClick()
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                onBubbleDoubleClick()
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                onBubbleLongPress()
            }
        })
        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop

        view.setOnTouchListener(object : View.OnTouchListener {
            private var downRawX = 0f
            private var downRawY = 0f
            private var startX = 0
            private var startY = 0
            private var dragging = false

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                gestureDetector.onTouchEvent(event)
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downRawX = event.rawX
                        downRawY = event.rawY
                        startX = params.x
                        startY = params.y
                        dragging = false
                        return true
                    }

                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.rawX - downRawX
                        val dy = event.rawY - downRawY
                        if (!dragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                            dragging = true
                        }
                        if (dragging) {
                            params.x = startX + dx.roundToInt()
                            params.y = startY + dy.roundToInt()
                            try {
                                windowManager?.updateViewLayout(v, params)
                            } catch (t: Throwable) {
                                Log.w(TAG, "move overlay failed", t)
                            }
                        }
                        return true
                    }

                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        if (dragging) saveBubblePosition(params.x, params.y)
                        return true
                    }

                    else -> return false
                }
            }
        })
    }

    private fun onBubbleClick() {
        scope.launch {
            val container = ServiceLocator.container(this@FloatingOverlayService)
            if (!container.controller.cycle()) {
                showToast(R.string.core_toast_cycle_failed)
            }
        }
    }

    private fun onBubbleDoubleClick() {
        scope.launch {
            val container = ServiceLocator.container(this@FloatingOverlayService)
            if (!container.controller.flip180()) {
                showToast(R.string.core_toast_cycle_failed)
            }
        }
    }

    private fun onBubbleLongPress() {
        openApp()
        stopSelf()
    }

    private fun showToast(messageRes: Int) {
        try {
            Toast.makeText(this, messageRes, Toast.LENGTH_SHORT).show()
        } catch (t: Throwable) {
            Log.w(TAG, "toast failed", t)
        }
    }

    private fun openApp() {
        try {
            val intent = packageManager.getLaunchIntentForPackage(packageName) ?: return
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            startActivity(intent)
        } catch (t: Throwable) {
            // Android 10+ 后台启动 Activity 受限时会抛异常，静默即可。
            Log.w(TAG, "open app failed", t)
        }
    }

    companion object {
        private const val TAG = "FloatingOverlayService"

        private const val BUBBLE_SIZE_DP = 56f
        private const val BUBBLE_MARGIN_DP = 12f
        private const val BUBBLE_ALPHA = 0.92f
        private const val DEFAULT_Y_RATIO = 0.35f

        /**
         * 启动悬浮球。**只允许用户在前台操作时调用**（UI 按钮 / 磁贴），
         * Android 14+ 从后台调用会抛 ForegroundServiceStartNotAllowedException。
         */
        fun start(context: Context) {
            val intent = Intent(context, FloatingOverlayService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, FloatingOverlayService::class.java))
        }
    }
}
