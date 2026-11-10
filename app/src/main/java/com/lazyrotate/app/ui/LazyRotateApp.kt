@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.lazyrotate.app.ui

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.lazyrotate.app.R
import com.lazyrotate.app.contract.AppRule
import com.lazyrotate.app.contract.PermissionSnapshot
import com.lazyrotate.app.contract.RotationAngle
import com.lazyrotate.app.contract.RotationStatus
import com.lazyrotate.app.ui.home.HomeScreen
import com.lazyrotate.app.ui.rules.AppRulesScreen
import com.lazyrotate.app.ui.settings.SettingsScreen
import com.lazyrotate.app.ui.util.PermissionTarget
import com.lazyrotate.app.ui.util.collectAsStateLifecycleAware
import com.lazyrotate.app.ui.util.openPermissionSettings
import kotlinx.coroutines.flow.collect

/** 三个顶层页面。 */
enum class AppDestination(
    @StringRes val labelRes: Int,
    @DrawableRes val iconRes: Int,
) {
    HOME(R.string.nav_control, R.drawable.ui_ic_rotate),
    RULES(R.string.nav_rules, R.drawable.ui_ic_apps),
    SETTINGS(R.string.nav_settings, R.drawable.ui_ic_settings),
}

/**
 * 应用外壳：
 * - 宽度 >= 600dp（平板 / 手机横屏）：左侧 NavigationRail + 内容区多列
 * - 更窄：底部 NavigationBar + 单列
 * 横竖屏都走同一套代码，旋转不会丢当前 tab。
 */
@Composable
fun LazyRotateApp(viewModel: MainViewModel) {
    val context = LocalContext.current

    val status by viewModel.status.collectAsStateLifecycleAware()
    val rules by viewModel.rules.collectAsStateLifecycleAware()
    val permissions by viewModel.permissions.collectAsStateLifecycleAware()
    val foregroundPackage by viewModel.foregroundPackage.collectAsStateLifecycleAware()
    val sensorEnabled by viewModel.sensorEnabled.collectAsStateLifecycleAware()
    val sensorConfidence by viewModel.sensorConfidence.collectAsStateLifecycleAware()
    val sensorSuggested by viewModel.sensorSuggested.collectAsStateLifecycleAware()
    val naturalLandscape by viewModel.naturalLandscape.collectAsStateLifecycleAware()
    val overlayEnabled by viewModel.overlayEnabled.collectAsStateLifecycleAware()

    val wide = LocalConfiguration.current.screenWidthDp >= 600

    var selectedIndex by rememberSaveable { mutableStateOf(0) }
    val destinations = AppDestination.entries
    // 恢复出来的 index 万一越界（枚举变过 / 脏数据）也不崩，退回控制页。
    val currentDestination = destinations.getOrNull(selectedIndex) ?: AppDestination.HOME
    val snackbarHostState = remember { SnackbarHostState() }

    // 一次性操作反馈：缺权限时给「去授权」动作，直接跳系统页。
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            val needsWriteSettings =
                event == UiEvent.MISSING_WRITE_SETTINGS || event == UiEvent.SENSOR_NEED_PERMISSION
            val result = snackbarHostState.showSnackbar(
                message = context.getString(event.messageRes),
                actionLabel = if (needsWriteSettings) {
                    context.getString(R.string.action_go_authorize)
                } else {
                    null
                },
                withDismissAction = !needsWriteSettings,
                duration = SnackbarDuration.Short,
            )
            if (needsWriteSettings && result == SnackbarResult.ActionPerformed) {
                openPermissionSettings(context, PermissionTarget.WRITE_SETTINGS)
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        bottomBar = {
            if (!wide) {
                NavigationBar {
                    destinations.forEachIndexed { index, destination ->
                        NavigationBarItem(
                            selected = selectedIndex == index,
                            onClick = { selectedIndex = index },
                            icon = {
                                Icon(
                                    painter = painterResource(destination.iconRes),
                                    contentDescription = null,
                                )
                            },
                            label = { Text(stringResource(destination.labelRes)) },
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        if (wide) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) {
                NavigationRail {
                    Text(
                        text = stringResource(R.string.app_name),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                    destinations.forEachIndexed { index, destination ->
                        NavigationRailItem(
                            selected = selectedIndex == index,
                            onClick = { selectedIndex = index },
                            icon = {
                                Icon(
                                    painter = painterResource(destination.iconRes),
                                    contentDescription = null,
                                )
                            },
                            label = { Text(stringResource(destination.labelRes)) },
                        )
                    }
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                ) {
                    DestinationContent(
                        destination = currentDestination,
                        viewModel = viewModel,
                        status = status,
                        rules = rules,
                        permissions = permissions,
                        foregroundPackage = foregroundPackage,
                        sensorEnabled = sensorEnabled,
                        sensorConfidence = sensorConfidence,
                        sensorSuggested = sensorSuggested,
                        naturalLandscape = naturalLandscape,
                        overlayEnabled = overlayEnabled,
                        wide = true,
                    )
                }
            }
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) {
                DestinationContent(
                    destination = currentDestination,
                    viewModel = viewModel,
                    status = status,
                    rules = rules,
                    permissions = permissions,
                    foregroundPackage = foregroundPackage,
                    sensorEnabled = sensorEnabled,
                    sensorConfidence = sensorConfidence,
                    sensorSuggested = sensorSuggested,
                    naturalLandscape = naturalLandscape,
                    overlayEnabled = overlayEnabled,
                    wide = false,
                )
            }
        }
    }
}

@Composable
private fun DestinationContent(
    destination: AppDestination,
    viewModel: MainViewModel,
    status: RotationStatus,
    rules: List<AppRule>,
    permissions: PermissionSnapshot,
    foregroundPackage: String?,
    sensorEnabled: Boolean,
    sensorConfidence: Float,
    sensorSuggested: RotationAngle?,
    naturalLandscape: Boolean,
    overlayEnabled: Boolean,
    wide: Boolean,
) {
    when (destination) {
        AppDestination.HOME -> HomeScreen(
            status = status,
            permissions = permissions,
            rules = rules,
            foregroundPackage = foregroundPackage,
            sensorEnabled = sensorEnabled,
            sensorConfidence = sensorConfidence,
            sensorSuggested = sensorSuggested,
            wide = wide,
            onApplyTarget = viewModel::applyTarget,
            onFlip = viewModel::flip,
            onSetSensorEnabled = viewModel::setSensorEnabled,
            onSaveRule = viewModel::saveRule,
        )

        AppDestination.RULES -> AppRulesScreen(
            rules = rules,
            onSaveRule = viewModel::saveRule,
            onEditRule = viewModel::setRuleTarget,
            onSetRuleEnabled = viewModel::setRuleEnabled,
            onDeleteRule = viewModel::deleteRule,
        )

        AppDestination.SETTINGS -> SettingsScreen(
            permissions = permissions,
            naturalLandscape = naturalLandscape,
            overlayEnabled = overlayEnabled,
            onSetNaturalLandscape = viewModel::setNaturalLandscape,
            onSetOverlayEnabled = viewModel::setOverlayEnabled,
            onRefresh = viewModel::refreshAll,
            wide = wide,
        )
    }
}
