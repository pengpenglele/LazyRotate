@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.lazyrotate.app.ui.settings

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lazyrotate.app.R
import com.lazyrotate.app.contract.PermissionSnapshot
import com.lazyrotate.app.ui.components.BodyHint
import com.lazyrotate.app.ui.components.MonoText
import com.lazyrotate.app.ui.components.PermissionRow
import com.lazyrotate.app.ui.components.ScreenTitle
import com.lazyrotate.app.ui.components.SectionCard
import com.lazyrotate.app.ui.components.StatusPill
import com.lazyrotate.app.ui.components.SwitchRow
import com.lazyrotate.app.ui.components.ThinDivider
import com.lazyrotate.app.ui.util.PermissionTarget
import com.lazyrotate.app.ui.util.appVersionName
import com.lazyrotate.app.ui.util.copyToClipboard
import com.lazyrotate.app.ui.util.openPermissionSettings

/**
 * 设置页：逐项权限状态 + 高级档 + 设备开关 + 关于与免责声明。
 *
 * 权限只做「引导」，不在这里申请运行时权限：WRITE_SETTINGS / 悬浮窗 / 无障碍 / 通知
 * 都是跳系统页让用户自己决定，这也是 Play 政策允许的做法。
 * 宽屏（平板 / 手机横屏）排成两列，窄屏单列。
 */
@Composable
fun SettingsScreen(
    permissions: PermissionSnapshot,
    naturalLandscape: Boolean,
    overlayEnabled: Boolean,
    onSetNaturalLandscape: (Boolean) -> Unit,
    onSetOverlayEnabled: (Boolean) -> Unit,
    onRefresh: () -> Unit,
    wide: Boolean,
    modifier: Modifier = Modifier,
) {
    // LocalContext.current 是 @Composable 属性读取，必须在组合上下文里取，
    // 不能放进 remember { } 的非组合 lambda。
    val context = LocalContext.current
    val versionName = remember(context) { appVersionName(context) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        ScreenTitle(text = stringResource(R.string.nav_settings))

        if (wide) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    PermissionsSection(permissions = permissions, onRefresh = onRefresh)
                    SecureTierSection(permissions = permissions)
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    DeviceSection(
                        permissions = permissions,
                        naturalLandscape = naturalLandscape,
                        overlayEnabled = overlayEnabled,
                        onSetNaturalLandscape = onSetNaturalLandscape,
                        onSetOverlayEnabled = onSetOverlayEnabled,
                    )
                    TipsSection()
                    AboutSection(versionName = versionName)
                }
            }
        } else {
            PermissionsSection(permissions = permissions, onRefresh = onRefresh)
            SecureTierSection(permissions = permissions)
            DeviceSection(
                permissions = permissions,
                naturalLandscape = naturalLandscape,
                overlayEnabled = overlayEnabled,
                onSetNaturalLandscape = onSetNaturalLandscape,
                onSetOverlayEnabled = onSetOverlayEnabled,
            )
            TipsSection()
            AboutSection(versionName = versionName)
        }

        Spacer(Modifier.height(8.dp))
    }
}

/** 四类常规权限：每项都有「去授权 / 去查看」按钮，跳对应的系统页。 */
@Composable
private fun PermissionsSection(
    permissions: PermissionSnapshot,
    onRefresh: () -> Unit,
) {
    val context = LocalContext.current
    SectionCard(title = stringResource(R.string.settings_permission_section)) {
        BodyHint(stringResource(R.string.settings_permission_hint))

        PermissionRow(
            title = stringResource(R.string.settings_perm_write_title),
            description = stringResource(R.string.settings_perm_write_desc),
            granted = permissions.writeSettings,
            actionLabel = stringResource(
                if (permissions.writeSettings) R.string.action_view_settings else R.string.action_go_authorize,
            ),
            onAction = { openPermissionSettings(context, PermissionTarget.WRITE_SETTINGS) },
        )
        ThinDivider()
        PermissionRow(
            title = stringResource(R.string.settings_perm_overlay_title),
            description = stringResource(R.string.settings_perm_overlay_desc),
            granted = permissions.overlay,
            actionLabel = stringResource(
                if (permissions.overlay) R.string.action_view_settings else R.string.action_go_authorize,
            ),
            onAction = { openPermissionSettings(context, PermissionTarget.OVERLAY) },
        )
        ThinDivider()
        PermissionRow(
            title = stringResource(R.string.settings_perm_notification_title),
            description = stringResource(R.string.settings_perm_notification_desc),
            granted = permissions.notifications,
            actionLabel = stringResource(
                if (permissions.notifications) R.string.action_view_settings else R.string.action_go_authorize,
            ),
            onAction = { openPermissionSettings(context, PermissionTarget.NOTIFICATIONS) },
        )
        ThinDivider()
        PermissionRow(
            title = stringResource(R.string.settings_perm_accessibility_title),
            description = stringResource(R.string.settings_perm_accessibility_desc),
            granted = permissions.accessibility,
            actionLabel = stringResource(
                if (permissions.accessibility) R.string.action_view_settings else R.string.action_go_authorize,
            ),
            onAction = { openPermissionSettings(context, PermissionTarget.ACCESSIBILITY) },
        )

        TextButton(onClick = onRefresh) {
            Text(stringResource(R.string.action_retry))
        }
    }
}

/**
 * 高级档：**不是开关，是引导卡片**。
 *
 * 「忽略应用自身的方向请求」「无条件采用用户方向」这两项能力在公开 SDK 下对第三方应用不可达：
 * 真正的开关由 WindowManagerService 持有，只能由 shell 身份通过 `cmd window` 操作
 * （契约里 RotationGateway.setIgnoreOrientationRequest 也恒返回 false）。
 * 所以这里只做三件事：显示权限状态、给出可整行复制的电脑端命令、把边界说清楚。
 * 刻意不提供 toggle，也不在 App 内调用那个方法——否则就是在骗用户。
 */
@Composable
private fun SecureTierSection(permissions: PermissionSnapshot) {
    val globalCommand = stringResource(R.string.adb_cmd_set_ignore_orientation_request)
    val forceCommand = stringResource(R.string.adb_cmd_fixed_to_user_rotation)
    val perAppCommand = stringResource(R.string.adb_ignore_orientation_request)

    SectionCard(
        title = stringResource(R.string.settings_perm_secure_title),
        trailing = {
            StatusPill(
                text = stringResource(
                    if (permissions.secureSettings) R.string.action_granted else R.string.action_not_granted,
                ),
                positive = permissions.secureSettings,
            )
        },
    ) {
        BodyHint(stringResource(R.string.settings_perm_secure_desc))
        BodyHint(stringResource(R.string.settings_secure_honest_note))

        // ① 全局忽略应用自己请求的方向（最推荐）
        CommandBlock(
            label = stringResource(R.string.settings_secure_cmd_global_label),
            command = globalCommand,
            note = stringResource(R.string.settings_secure_cmd_global_note),
        )

        // ② 无条件采用用户设定方向（更彻底，但可能 letterbox）
        CommandBlock(
            label = stringResource(R.string.settings_secure_cmd_force_label),
            command = forceCommand,
            note = stringResource(R.string.settings_secure_cmd_force_note),
        )

        // ③ 按应用生效（未完全核实，文案里如实标注）
        CommandBlock(
            label = stringResource(R.string.settings_secure_cmd_app_label),
            command = perAppCommand,
            note = stringResource(R.string.settings_secure_cmd_app_note),
        )
    }
}

/** 一条命令：小标题 + 等宽命令块 + 说明 + 复制按钮。 */
@Composable
private fun CommandBlock(
    label: String,
    command: String,
    note: String,
) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    MonoText(text = command)
    BodyHint(note)
    CopyCommandButton(command = command)
}

/** 「复制命令」按钮：复制整行到剪贴板并给 Toast 反馈。 */
@Composable
private fun CopyCommandButton(command: String) {
    val context = LocalContext.current
    TextButton(
        onClick = {
            copyToClipboard(context = context, label = "adb", text = command)
            Toast.makeText(
                context,
                context.getString(R.string.settings_perm_secure_copied),
                Toast.LENGTH_SHORT,
            ).show()
        },
    ) {
        Icon(
            painter = painterResource(R.drawable.ui_ic_copy),
            contentDescription = null,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.action_copy_command))
    }
}

/** 设备级开关：自然方向覆盖 + 悬浮球。 */
@Composable
private fun DeviceSection(
    permissions: PermissionSnapshot,
    naturalLandscape: Boolean,
    overlayEnabled: Boolean,
    onSetNaturalLandscape: (Boolean) -> Unit,
    onSetOverlayEnabled: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    SectionCard(title = stringResource(R.string.settings_device_section)) {
        SwitchRow(
            title = stringResource(R.string.settings_natural_landscape_title),
            description = stringResource(R.string.settings_natural_landscape_desc),
            checked = naturalLandscape,
            onCheckedChange = onSetNaturalLandscape,
        )
        ThinDivider()
        SwitchRow(
            title = stringResource(R.string.settings_overlay_title),
            description = stringResource(R.string.settings_overlay_desc),
            checked = overlayEnabled,
            onCheckedChange = { checked ->
                if (checked && !permissions.overlay) {
                    // 没权限时不要让开关「假装打开」，直接带去授权。
                    openPermissionSettings(context, PermissionTarget.OVERLAY)
                } else {
                    onSetOverlayEnabled(checked)
                }
            },
        )
        if (!permissions.overlay) {
            BodyHint(
                text = stringResource(R.string.settings_overlay_need_permission),
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/** 快速入口提示：磁贴与悬浮球。 */
@Composable
private fun TipsSection() {
    SectionCard(title = stringResource(R.string.settings_tips_section)) {
        Row(verticalAlignment = Alignment.Top) {
            Text(text = "•  ", style = MaterialTheme.typography.bodySmall)
            BodyHint(
                text = stringResource(R.string.settings_tip_tile),
                modifier = Modifier.weight(1f),
            )
        }
        Row(verticalAlignment = Alignment.Top) {
            Text(text = "•  ", style = MaterialTheme.typography.bodySmall)
            BodyHint(
                text = stringResource(R.string.settings_tip_overlay),
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** 关于与免责声明：把免 root 的边界说清楚，别让用户以为是 bug。 */
@Composable
private fun AboutSection(versionName: String?) {
    SectionCard(title = stringResource(R.string.settings_about_section)) {
        Text(
            text = stringResource(R.string.settings_about_version, versionName ?: "—"),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(R.string.settings_about_limitation_title),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        BodyHint(stringResource(R.string.settings_about_limitation_body))
        BodyHint(stringResource(R.string.settings_about_privacy))
    }
}
