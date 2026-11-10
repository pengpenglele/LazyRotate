package com.lazyrotate.app.ui.util

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast

/**
 * 权限 / 系统设置页跳转。全部走公开的 Intent，不申请额外权限。
 * 任何跳转失败都吞掉异常并给一个友好的兜底页，绝不让界面崩。
 */
enum class PermissionTarget {
    WRITE_SETTINGS,
    OVERLAY,
    NOTIFICATIONS,
    ACCESSIBILITY,
    APP_DETAILS,
}

private fun packageUri(context: Context): Uri =
    Uri.fromParts("package", context.packageName, null)

fun permissionIntent(context: Context, target: PermissionTarget): Intent = when (target) {
    PermissionTarget.WRITE_SETTINGS ->
        Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, packageUri(context))

    PermissionTarget.OVERLAY ->
        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, packageUri(context))

    PermissionTarget.NOTIFICATIONS ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        } else {
            // API 24/25 没有应用级通知页，退到应用详情页让用户手动进去。
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri(context))
        }

    PermissionTarget.ACCESSIBILITY ->
        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)

    PermissionTarget.APP_DETAILS ->
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri(context))
}

/** 打开某个系统设置页；失败时退回到系统设置首页。 */
fun openPermissionSettings(context: Context, target: PermissionTarget) {
    openSafely(context, permissionIntent(context, target))
}

/**
 * 安全的 startActivity：加 NEW_TASK（Context 可能是 Application），
 * 捕获 ActivityNotFoundException 后退回系统设置首页，再不行给个 Toast。
 */
fun openSafely(context: Context, intent: Intent) {
    val safeIntent = Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(safeIntent)
    } catch (e: ActivityNotFoundException) {
        val fallback = Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(fallback)
        } catch (ignored: ActivityNotFoundException) {
            Toast.makeText(context, android.R.string.cancel, Toast.LENGTH_SHORT).show()
        }
    } catch (e: SecurityException) {
        // 个别 ROM 会限制设置页跳转，同样不做崩溃处理。
        Toast.makeText(context, android.R.string.cancel, Toast.LENGTH_SHORT).show()
    }
}

/** 复制文本到系统剪贴板（不依赖任何 Compose 剪贴板 API，避免版本差异）。 */
fun copyToClipboard(context: Context, label: String, text: String): Boolean {
    val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        ?: return false
    return runCatching {
        manager.setPrimaryClip(ClipData.newPlainText(label, text))
    }.isSuccess
}
