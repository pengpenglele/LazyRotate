@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.lazyrotate.app.ui.rules

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lazyrotate.app.R
import com.lazyrotate.app.contract.AppRule
import com.lazyrotate.app.contract.RotationTarget
import com.lazyrotate.app.ui.components.AppIcon
import com.lazyrotate.app.ui.components.EmptyState
import com.lazyrotate.app.ui.components.ScreenTitle
import com.lazyrotate.app.ui.components.SectionHeader
import com.lazyrotate.app.ui.components.TargetPickerDialog
import com.lazyrotate.app.ui.util.InstalledApp
import com.lazyrotate.app.ui.util.loadLauncherApps
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 按应用规则页。
 *
 * 上半部分是已配置的规则（可改方向 / 启停 / 删除），
 * 下半部分是可搜索的已安装应用列表（点一行即可为它指定方向）。
 * 应用可见性靠清单里的 <queries>，没有用 QUERY_ALL_PACKAGES。
 */
@Composable
fun AppRulesScreen(
    rules: List<AppRule>,
    onSaveRule: (String, String, RotationTarget) -> Unit,
    onEditRule: (AppRule, RotationTarget) -> Unit,
    onSetRuleEnabled: (AppRule, Boolean) -> Unit,
    onDeleteRule: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var pickerApp by remember { mutableStateOf<InstalledApp?>(null) }
    var pickerRule by remember { mutableStateOf<AppRule?>(null) }

    val installedApps = rememberInstalledApps()
    val loading = installedApps == null
    val allApps = installedApps.orEmpty()

    // LazyColumn 的 key 必须唯一，否则运行时抛 "Key was already used"。
    // 规则存储在 upsert 里已按包名去重，但磁盘上的规则文件可能是旧版本写的、或被手工改过，
    // 所以这里再按包名兜底去重一次（保留先出现的那条），保证界面绝不因脏数据崩溃。
    val uniqueRules = remember(rules) { rules.distinctBy { it.packageName } }
    val rulePackages = remember(uniqueRules) { uniqueRules.map { it.packageName }.toSet() }
    val filteredRules = remember(uniqueRules, query) {
        uniqueRules.filter { matches(it.label, it.packageName, query) }
    }
    val filteredApps = remember(allApps, rulePackages, query) {
        allApps.filter { app ->
            app.packageName !in rulePackages && matches(app.label, app.packageName, query)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        ScreenTitle(text = stringResource(R.string.nav_rules))
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            placeholder = { Text(stringResource(R.string.rules_search_hint)) },
            leadingIcon = {
                Icon(
                    painter = painterResource(R.drawable.ui_ic_search),
                    contentDescription = null,
                )
            },
        )
        Spacer(Modifier.height(12.dp))

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(bottom = 20.dp),
        ) {
            if (filteredRules.isNotEmpty()) {
                item(key = "header_rules") {
                    SectionHeader(text = stringResource(R.string.rules_section_rules, filteredRules.size))
                }
                items(items = filteredRules, key = { "rule_${it.packageName}" }) { rule ->
                    RuleRow(
                        rule = rule,
                        onEditTarget = { pickerRule = rule },
                        onSetEnabled = { enabled -> onSetRuleEnabled(rule, enabled) },
                        onDelete = { onDeleteRule(rule.packageName) },
                    )
                }
            } else if (rules.isEmpty() && query.isBlank()) {
                item(key = "rules_empty") {
                    EmptyState(text = stringResource(R.string.rules_empty))
                }
            }

            item(key = "header_apps") {
                SectionHeader(
                    text = stringResource(R.string.rules_section_apps, filteredApps.size),
                    modifier = Modifier.padding(top = 6.dp),
                )
            }

            when {
                loading -> item(key = "apps_loading") {
                    EmptyState(text = stringResource(R.string.rules_loading))
                }

                allApps.isEmpty() -> item(key = "apps_none") {
                    EmptyState(text = stringResource(R.string.rules_no_apps))
                }

                filteredApps.isEmpty() -> item(key = "apps_no_match") {
                    EmptyState(text = stringResource(R.string.rules_no_match))
                }

                else -> items(items = filteredApps, key = { "app_${it.packageName}" }) { app ->
                    AppRow(
                        app = app,
                        onClick = { pickerApp = app },
                    )
                }
            }
        }
    }

    val app = pickerApp
    if (app != null) {
        TargetPickerDialog(
            title = stringResource(R.string.rules_picker_title, app.label),
            subtitle = stringResource(R.string.rules_picker_subtitle),
            selected = null,
            onDismiss = { pickerApp = null },
            onPick = { target ->
                pickerApp = null
                onSaveRule(app.packageName, app.label, target)
            },
        )
    }

    val rule = pickerRule
    if (rule != null) {
        TargetPickerDialog(
            title = stringResource(R.string.rules_picker_title, rule.label),
            subtitle = stringResource(R.string.rules_picker_subtitle),
            selected = rule.target,
            onDismiss = { pickerRule = null },
            onPick = { target ->
                pickerRule = null
                onEditRule(rule, target)
            },
        )
    }
}

@Composable
private fun RuleRow(
    rule: AppRule,
    onEditTarget: () -> Unit,
    onSetEnabled: (Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.30f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AppIcon(packageName = rule.packageName, label = rule.label)
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = rule.label,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = stringResource(R.string.rules_rule_target, rule.target.label),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Switch(checked = rule.enabled, onCheckedChange = onSetEnabled)
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onEditTarget) {
                    Text(stringResource(R.string.rules_pick_target))
                }
                IconButton(onClick = onDelete) {
                    Icon(
                        painter = painterResource(R.drawable.ui_ic_delete),
                        contentDescription = stringResource(R.string.rules_delete_rule, rule.label),
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

@Composable
private fun AppRow(
    app: InstalledApp,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppIcon(packageName = app.packageName, label = app.label, size = 36.dp)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = app.label,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = app.packageName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.rules_pick_target),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/** 安装应用列表只在进入页面时读一次（IO 线程），不做轮询。 */
@Composable
private fun rememberInstalledApps(): List<InstalledApp>? {
    val context = LocalContext.current
    val state by produceState<List<InstalledApp>?>(initialValue = null, key1 = context) {
        value = withContext(Dispatchers.IO) { loadLauncherApps(context) }
    }
    return state
}

private fun matches(label: String, packageName: String, query: String): Boolean {
    val q = query.trim()
    if (q.isEmpty()) return true
    return label.contains(q, ignoreCase = true) || packageName.contains(q, ignoreCase = true)
}
