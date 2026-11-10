package com.lazyrotate.app.ui.util

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.text.Collator
import java.util.Locale

/** 已安装应用（只保留给用户看的两样东西，图标按需加载，列表不持有 Bitmap）。 */
data class InstalledApp(
    val packageName: String,
    val label: String,
)

/**
 * 列出有启动器入口的应用。
 *
 * 清单里用 `<queries><intent MAIN/LAUNCHER>` 放开了可见性（不是 QUERY_ALL_PACKAGES），
 * 所以这里用 ACTION_MAIN + CATEGORY_LAUNCHER 查询恰好覆盖得到。
 * 包名去重、隐藏自己、按本地化名称排序。
 */
@Suppress("DEPRECATION")
fun loadLauncherApps(context: Context): List<InstalledApp> {
    val packageManager = context.packageManager
    val probe = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    val resolved = runCatching { packageManager.queryIntentActivities(probe, 0) }
        .getOrNull()
        .orEmpty()

    val self = context.packageName
    val unique = LinkedHashMap<String, InstalledApp>()
    for (info in resolved) {
        val activityInfo = info.activityInfo ?: continue
        val packageName = activityInfo.packageName ?: continue
        if (packageName == self || unique.containsKey(packageName)) continue
        val label = runCatching { info.loadLabel(packageManager)?.toString() }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: packageName
        unique[packageName] = InstalledApp(packageName, label)
    }

    val collator = Collator.getInstance(Locale.getDefault())
    return unique.values.sortedWith(Comparator { left, right -> collator.compare(left.label, right.label) })
}

/** 读取某个包的应用名；应用被卸载 / 不可见时返回 null。 */
@Suppress("DEPRECATION")
fun appLabel(context: Context, packageName: String?): String? {
    val pkg = packageName ?: return null
    val packageManager = context.packageManager
    val info = runCatching { packageManager.getApplicationInfo(pkg, 0) }.getOrNull() ?: return null
    return runCatching { packageManager.getApplicationLabel(info)?.toString() }
        .getOrNull()
        ?.takeIf { it.isNotBlank() }
}

/**
 * 应用图标 -> Compose ImageBitmap。
 * 直接用 android.graphics 画到 Bitmap，不依赖 core-ktx 的转换扩展（那个在不同版本里签名/弃用状态不一致）。
 */
fun appIconBitmap(context: Context, packageName: String, sizePx: Int): ImageBitmap? {
    val drawable = runCatching { context.packageManager.getApplicationIcon(packageName) }
        .getOrNull()
        ?: return null
    return runCatching {
        val size = sizePx.coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, size, size)
        drawable.draw(canvas)
        bitmap.asImageBitmap()
    }.getOrNull()
}

/** 版本名（不引用 BuildConfig，避免未开启 buildConfig 时编译失败）。 */
@Suppress("DEPRECATION")
fun appVersionName(context: Context): String? = runCatching {
    context.packageManager.getPackageInfo(context.packageName, 0).versionName
}.getOrNull()
