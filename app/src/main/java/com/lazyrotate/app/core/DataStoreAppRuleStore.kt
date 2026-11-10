package com.lazyrotate.app.core

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.lazyrotate.app.contract.AppRule
import com.lazyrotate.app.contract.AppRuleStore
import com.lazyrotate.app.contract.RotationTarget
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * 按应用规则存储（DataStore Preferences）。
 *
 * 为什么不用 kotlinx.serialization / JSON：契约要求不新增依赖，而且 label 是用户可见文本，
 * 可能包含 `|`、换行、`:` 等在简单分隔方案里会炸的字符。这里用 percent-encoding 逐字段转义，
 * 规则间用 `\n`、字段间用 `|` 分隔：转义之后这两个字符不可能出现在字段内容里，解析天然无歧义。
 *
 * 规则顺序会被保留（stringSet 是无序的，所以刻意存成单个字符串而不是 Set）。
 */
private val Context.coreRuleDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "lazy_rotate_rules",
)

class DataStoreAppRuleStore(context: Context) : AppRuleStore {

    private val appContext: Context = context.applicationContext ?: context
    private val store: DataStore<Preferences> = appContext.coreRuleDataStore

    override val rules: Flow<List<AppRule>> = store.data
        .map { prefs -> decode(prefs[KEY_RULES]) }
        .catch { throwable ->
            // 规则文件损坏时不能让 UI / 无障碍服务崩，退化成「没有规则」。
            Log.w(TAG, "rules decode failed, degrade to empty", throwable)
            emit(emptyList())
        }

    override suspend fun upsert(rule: AppRule) {
        store.edit { prefs ->
            val current = decode(prefs[KEY_RULES])
            val next = current.filterNot { it.packageName == rule.packageName } + rule
            prefs[KEY_RULES] = encode(next)
        }
    }

    override suspend fun remove(packageName: String) {
        store.edit { prefs ->
            val next = decode(prefs[KEY_RULES]).filterNot { it.packageName == packageName }
            if (next.isEmpty()) prefs.remove(KEY_RULES) else prefs[KEY_RULES] = encode(next)
        }
    }

    override suspend fun ruleFor(packageName: String): AppRule? = try {
        decode(store.data.first()[KEY_RULES]).firstOrNull { it.packageName == packageName }
    } catch (t: Throwable) {
        // 读失败时按「没有规则」处理：按应用规则的降级必须是静默的。
        null
    }

    // ---------------------------------------------------------------- 编解码

    private fun encode(rules: List<AppRule>): String = rules.joinToString(RULE_SEPARATOR) { rule ->
        listOf(
            rule.packageName,
            rule.label,
            rule.target.code,
            if (rule.enabled) FLAG_TRUE else FLAG_FALSE,
        ).joinToString(FIELD_SEPARATOR) { escape(it) }
    }

    private fun decode(raw: String?): List<AppRule> {
        if (raw.isNullOrEmpty()) return emptyList()
        return raw.split(RULE_SEPARATOR).mapNotNull { line ->
            if (line.isEmpty()) return@mapNotNull null
            val parts = line.split(FIELD_SEPARATOR)
            if (parts.size < FIELD_COUNT) return@mapNotNull null
            val packageName = unescape(parts[0])
            if (packageName.isEmpty()) return@mapNotNull null
            AppRule(
                packageName = packageName,
                label = unescape(parts[1]),
                // 未知 code 一律退回 FOLLOW_SYSTEM，绝不因为规则文件脏了就崩。
                target = RotationTarget.fromCode(unescape(parts[2])),
                enabled = parts[3] == FLAG_TRUE,
            )
        }
    }

    private fun escape(value: String): String = try {
        URLEncoder.encode(value, CHARSET)
    } catch (t: Throwable) {
        // UTF-8 在 Android 上必然存在；这里只是防御性兜底（宁可丢字符也不要崩）。
        value.replace(FIELD_SEPARATOR, "").replace(RULE_SEPARATOR, " ")
    }

    private fun unescape(value: String): String = try {
        URLDecoder.decode(value, CHARSET)
    } catch (t: Throwable) {
        value
    }

    private companion object {
        const val TAG = "DataStoreAppRuleStore"
        const val CHARSET = "UTF-8"
        const val RULE_SEPARATOR = "\n"
        const val FIELD_SEPARATOR = "|"
        const val FIELD_COUNT = 4
        const val FLAG_TRUE = "1"
        const val FLAG_FALSE = "0"

        val KEY_RULES = stringPreferencesKey("app_rules_v1")
    }
}
