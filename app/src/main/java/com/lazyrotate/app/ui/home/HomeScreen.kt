@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.lazyrotate.app.ui.home

import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lazyrotate.app.R
import com.lazyrotate.app.contract.AppRule
import com.lazyrotate.app.contract.PermissionSnapshot
import com.lazyrotate.app.contract.RotationAngle
import com.lazyrotate.app.contract.RotationStatus
import com.lazyrotate.app.contract.RotationTarget
import com.lazyrotate.app.ui.components.AppIcon
import com.lazyrotate.app.ui.components.BigActionButton
import com.lazyrotate.app.ui.components.BodyHint
import com.lazyrotate.app.ui.components.ConfidenceBar
import com.lazyrotate.app.ui.components.InfoBanner
import com.lazyrotate.app.ui.components.SectionCard
import com.lazyrotate.app.ui.components.StatusPill
import com.lazyrotate.app.ui.components.StatusTile
import com.lazyrotate.app.ui.components.TargetChip
import com.lazyrotate.app.ui.components.TargetPickerDialog
import com.lazyrotate.app.ui.util.PermissionTarget
import com.lazyrotate.app.ui.util.appLabel
import com.lazyrotate.app.ui.util.openPermissionSettings

/**
 * 主页。产品第一优先级是「躺着看平板」：
 * 第一屏就是两个 132dp 高的大按钮 —— 锁定当前方向 / 翻转 180°。
 * 其余是次要入口：快捷方向、绝对角度、躺姿引擎、前台应用规则。
 */
@Composable
fun HomeScreen(
    status: RotationStatus,
    permissions: PermissionSnapshot,
    rules: List<AppRule>,
    foregroundPackage: String?,
    sensorEnabled: Boolean,
    sensorConfidence: Float,
    sensorSuggested: RotationAngle?,
    wide: Boolean,
    onApplyTarget: (RotationTarget) -> Unit,
    onFlip: () -> Unit,
    onSetSensorEnabled: (Boolean) -> Unit,
    onSaveRule: (String, String, RotationTarget) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        StatusCard(status = status, wide = wide)

        if (wide) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    PrimaryActions(
                        permissions = permissions,
                        wide = true,
                        onApplyTarget = onApplyTarget,
                        onFlip = onFlip,
                    )
                    QuickTargets(status = status, onApplyTarget = onApplyTarget)
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    SensorCard(
                        sensorEnabled = sensorEnabled,
                        confidence = sensorConfidence,
                        suggested = sensorSuggested,
                        onSetSensorEnabled = onSetSensorEnabled,
                        onApplyTarget = onApplyTarget,
                    )
                    ForegroundCard(
                        foregroundPackage = foregroundPackage,
                        rules = rules,
                        onSaveRule = onSaveRule,
                    )
                }
            }
        } else {
            PrimaryActions(
                permissions = permissions,
                wide = false,
                onApplyTarget = onApplyTarget,
                onFlip = onFlip,
            )
            QuickTargets(status = status, onApplyTarget = onApplyTarget)
            SensorCard(
                sensorEnabled = sensorEnabled,
                confidence = sensorConfidence,
                suggested = sensorSuggested,
                onSetSensorEnabled = onSetSensorEnabled,
                onApplyTarget = onApplyTarget,
            )
            ForegroundCard(
                foregroundPackage = foregroundPackage,
                rules = rules,
                onSaveRule = onSaveRule,
            )
        }

        Spacer(Modifier.height(8.dp))
    }
}

/** 顶部状态卡：当前目标 / 实际角度 / 自动旋转 / 锁定是否生效。 */
@Composable
private fun StatusCard(status: RotationStatus, wide: Boolean) {
    SectionCard(title = stringResource(R.string.status_title)) {
        val targetValue = status.target.label
        val angleValue = stringResource(R.string.degree_format, status.appliedAngle.degrees)
        val autoValue = stringResource(
            if (status.autoRotateEnabled) R.string.status_auto_on else R.string.status_auto_off,
        )
        val lockValue = stringResource(
            if (status.lockEngaged) R.string.status_lock_engaged else R.string.status_lock_idle,
        )

        if (wide) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatusTile(
                    label = stringResource(R.string.status_target),
                    value = targetValue,
                    modifier = Modifier.weight(1f),
                )
                StatusTile(
                    label = stringResource(R.string.status_angle),
                    value = angleValue,
                    modifier = Modifier.weight(1f),
                    emphasized = true,
                )
                StatusTile(
                    label = stringResource(R.string.status_auto_rotate),
                    value = autoValue,
                    modifier = Modifier.weight(1f),
                )
                StatusTile(
                    label = stringResource(R.string.status_lock),
                    value = lockValue,
                    modifier = Modifier.weight(1f),
                    emphasized = status.lockEngaged,
                )
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatusTile(
                        label = stringResource(R.string.status_target),
                        value = targetValue,
                        modifier = Modifier.weight(1f),
                    )
                    StatusTile(
                        label = stringResource(R.string.status_angle),
                        value = angleValue,
                        modifier = Modifier.weight(1f),
                        emphasized = true,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatusTile(
                        label = stringResource(R.string.status_auto_rotate),
                        value = autoValue,
                        modifier = Modifier.weight(1f),
                    )
                    StatusTile(
                        label = stringResource(R.string.status_lock),
                        value = lockValue,
                        modifier = Modifier.weight(1f),
                        emphasized = status.lockEngaged,
                    )
                }
            }
        }

        StatusPill(
            text = stringResource(
                if (status.naturalLandscape) {
                    R.string.status_natural_landscape
                } else {
                    R.string.status_natural_portrait
                },
            ),
            positive = true,
        )
    }
}

/** 缺 WRITE_SETTINGS 时的醒目引导 + 两个大按钮。 */
@Composable
private fun PrimaryActions(
    permissions: PermissionSnapshot,
    wide: Boolean,
    onApplyTarget: (RotationTarget) -> Unit,
    onFlip: () -> Unit,
) {
    val context = LocalContext.current
    val goAuthorize = {
        openPermissionSettings(context, PermissionTarget.WRITE_SETTINGS)
    }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (!permissions.writeSettings) {
            InfoBanner(
                title = stringResource(R.string.home_permission_title),
                body = stringResource(R.string.home_permission_body),
                actionLabel = stringResource(R.string.action_go_authorize),
                onAction = goAuthorize,
                onClick = goAuthorize,
            )
        }

        if (wide) {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                LockButton(modifier = Modifier.weight(1f), onApplyTarget = onApplyTarget)
                FlipButton(modifier = Modifier.weight(1f), onFlip = onFlip)
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                LockButton(modifier = Modifier.fillMaxWidth(), onApplyTarget = onApplyTarget)
                FlipButton(modifier = Modifier.fillMaxWidth(), onFlip = onFlip)
            }
        }
    }
}

@Composable
private fun LockButton(modifier: Modifier, onApplyTarget: (RotationTarget) -> Unit) {
    BigActionButton(
        title = RotationTarget.LOCK_CURRENT.label,
        subtitle = stringResource(R.string.home_lock_hint),
        iconRes = R.drawable.ui_ic_lock,
        onClick = { onApplyTarget(RotationTarget.LOCK_CURRENT) },
        modifier = modifier,
        emphasized = true,
    )
}

@Composable
private fun FlipButton(modifier: Modifier, onFlip: () -> Unit) {
    BigActionButton(
        title = RotationTarget.FLIP_180.label,
        subtitle = stringResource(R.string.home_flip_hint),
        iconRes = R.drawable.ui_ic_flip,
        onClick = onFlip,
        modifier = modifier,
        emphasized = false,
    )
}

/** 快捷方向 + 四个绝对角度。用横向滚动排，窄屏也不挤爆。 */
@Composable
private fun QuickTargets(
    status: RotationStatus,
    onApplyTarget: (RotationTarget) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SectionCard(title = stringResource(R.string.home_quick_title)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                QuickTargetOptions.forEach { target ->
                    TargetChip(
                        label = target.label,
                        selected = status.target == target,
                        onClick = { onApplyTarget(target) },
                    )
                }
            }
        }

        SectionCard(title = stringResource(R.string.home_angles_title)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AngleTargetOptions.forEach { target ->
                    TargetChip(
                        label = target.label,
                        selected = status.target == target,
                        onClick = { onApplyTarget(target) },
                    )
                }
            }
        }
    }
}

/** 躺姿引擎：置信度 + 建议角度。建议为 null 时给正面文案「保持现状」。 */
@Composable
private fun SensorCard(
    sensorEnabled: Boolean,
    confidence: Float,
    suggested: RotationAngle?,
    onSetSensorEnabled: (Boolean) -> Unit,
    onApplyTarget: (RotationTarget) -> Unit,
) {
    SectionCard(
        title = stringResource(R.string.home_sensor_title),
        trailing = {
            Switch(checked = sensorEnabled, onCheckedChange = onSetSensorEnabled)
        },
    ) {
        BodyHint(stringResource(R.string.home_sensor_desc))

        if (!sensorEnabled) {
            return@SectionCard
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            BodyHint(
                text = stringResource(R.string.home_sensor_confidence),
                modifier = Modifier.weight(1f),
            )
            Text(
                text = stringResource(R.string.percent_format, (confidence.coerceIn(0f, 1f) * 100).toInt()),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
        }
        ConfidenceBar(confidence = confidence)
        BodyHint(stringResource(R.string.home_sensor_confidence_hint))

        Row(verticalAlignment = Alignment.CenterVertically) {
            BodyHint(
                text = stringResource(R.string.home_sensor_suggested),
                modifier = Modifier.weight(1f),
            )
            if (suggested == null) {
                StatusPill(text = stringResource(R.string.home_sensor_hold), positive = true)
            } else {
                Text(
                    text = stringResource(R.string.degree_format, suggested.degrees),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }

        if (suggested == null) {
            BodyHint(stringResource(R.string.home_sensor_hold_hint))
        } else {
            TextButton(onClick = { onApplyTarget(targetForAngle(suggested)) }) {
                Text(stringResource(R.string.action_adopt_suggestion))
            }
        }
    }
}

/** 前台应用：拿不到包名时引导去开无障碍；拿到时可直接建规则。 */
@Composable
private fun ForegroundCard(
    foregroundPackage: String?,
    rules: List<AppRule>,
    onSaveRule: (String, String, RotationTarget) -> Unit,
) {
    val context = LocalContext.current
    var showPicker by remember { mutableStateOf(false) }

    SectionCard(title = stringResource(R.string.home_foreground_title)) {
        if (foregroundPackage == null) {
            BodyHint(stringResource(R.string.home_foreground_need_accessibility))
            OutlinedButton(
                onClick = {
                    openPermissionSettings(context, PermissionTarget.ACCESSIBILITY)
                },
            ) {
                Icon(
                    painter = painterResource(R.drawable.ui_ic_open),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.home_foreground_action_accessibility))
            }
            return@SectionCard
        }

        val label = remember(foregroundPackage) { appLabel(context, foregroundPackage) }
        val displayLabel = label ?: foregroundPackage

        Row(verticalAlignment = Alignment.CenterVertically) {
            AppIcon(packageName = foregroundPackage, label = displayLabel)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = displayLabel,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = foregroundPackage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        val existing = rules.firstOrNull { it.packageName == foregroundPackage }
        if (existing != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusPill(
                    text = stringResource(R.string.home_foreground_rule_current, existing.target.label),
                    positive = existing.enabled,
                )
            }
        } else {
            TextButton(onClick = { showPicker = true }) {
                Icon(
                    painter = painterResource(R.drawable.ui_ic_apps),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.home_foreground_action_add_rule))
            }
        }
    }

    if (showPicker && foregroundPackage != null) {
        val label = appLabel(context, foregroundPackage) ?: foregroundPackage
        TargetPickerDialog(
            title = stringResource(R.string.rules_picker_title, label),
            subtitle = stringResource(R.string.rules_picker_subtitle),
            selected = rules.firstOrNull { it.packageName == foregroundPackage }?.target,
            onDismiss = { showPicker = false },
            onPick = { target ->
                showPicker = false
                onSaveRule(foregroundPackage, label, target)
            },
        )
    }
}

private fun targetForAngle(angle: RotationAngle): RotationTarget = when (angle) {
    RotationAngle.ANGLE_0 -> RotationTarget.ANGLE_0
    RotationAngle.ANGLE_90 -> RotationTarget.ANGLE_90
    RotationAngle.ANGLE_180 -> RotationTarget.ANGLE_180
    RotationAngle.ANGLE_270 -> RotationTarget.ANGLE_270
}

/** 快捷方向：跟随系统 + 竖/横 + 反向竖/横。 */
private val QuickTargetOptions = listOf(
    RotationTarget.FOLLOW_SYSTEM,
    RotationTarget.PORTRAIT,
    RotationTarget.LANDSCAPE,
    RotationTarget.REVERSE_PORTRAIT,
    RotationTarget.REVERSE_LANDSCAPE,
)

/** 绝对角度，直接对应 Settings.System.USER_ROTATION。 */
private val AngleTargetOptions = listOf(
    RotationTarget.ANGLE_0,
    RotationTarget.ANGLE_90,
    RotationTarget.ANGLE_180,
    RotationTarget.ANGLE_270,
)
