package com.aeonlab.toolkit.ui.miuix

import android.app.Activity
import android.app.AlertDialog
import android.graphics.BitmapFactory
import android.content.Context
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.text.font.FontWeight
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import com.aeonlab.toolkit.hooks.items.theme.ThemeFileIo
import com.aeonlab.toolkit.hooks.items.theme.ThemeLibrary
import com.aeonlab.toolkit.hooks.items.theme.ThemeImageStore
import com.aeonlab.toolkit.hooks.items.theme.ThemeSettings
import com.aeonlab.toolkit.hooks.items.theme.ThemeSnapshot
import com.aeonlab.toolkit.hooks.items.theme.ThemeSpec
import com.aeonlab.toolkit.hooks.items.theme.ThemeSync
import com.aeonlab.toolkit.ui.FeatureSettingsProvider
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.SmallTitle

@Composable
internal fun ThemeMiuixPage(
    context: Context,
    provider: FeatureSettingsProvider,
    onBack: () -> Unit
) {
    var snapshot by remember { mutableStateOf(ThemeSettings.snapshot(context)) }
    var library by remember { mutableStateOf(ThemeLibrary.list(context)) }
    val listState = rememberLazyListState()
    val scrollBehavior = MiuixScrollBehavior()
    val activity = context as? Activity

    // 每次都基于「最新落盘的设置」改，避免用界面里捕获的旧 snapshot 覆盖掉上一次的改动
    //（667 四个页面开关只生效最后一个，就是这个原因）。
    fun update(block: (ThemeSnapshot) -> ThemeSnapshot) {
        val fresh = ThemeSettings.snapshot(context)
        ThemeSettings.write(context, block(fresh))
        snapshot = ThemeSettings.snapshot(context)
        ThemeLibrary.syncApplied(context)
    }

    fun persist(next: ThemeSnapshot) = update { next }

    fun importBytes(bytes: ByteArray, sourceName: String) {
        val outcome = if (bytes.size >= 2 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte()) {
            ThemeSpec.importArchive(context, bytes, sourceName)
        } else {
            ThemeSpec.importJson(context, runCatching { String(bytes, Charsets.UTF_8) }.getOrDefault(""), sourceName)
        }
        when (outcome) {
            is ThemeSpec.ImportOutcome.Success -> {
                snapshot = ThemeSettings.snapshot(context)
                ThemeSync.applyRoundAvatar(context, snapshot.roundAvatar)
                val detail = buildString {
                    appendLine("已导入：${outcome.applied.joinToString("、")}")
                    if (outcome.warnings.isNotEmpty()) {
                        appendLine()
                        appendLine("提示 ${outcome.warnings.size} 条：")
                        outcome.warnings.take(6).forEach { appendLine("· $it") }
                    }
                }.trim()
                showThemeDialog(context, "导入成功", detail)
            }

            is ThemeSpec.ImportOutcome.Failure -> showThemeDialog(context, "导入失败", outcome.reason)
        }
    }

    PageScaffold(
        title = provider.title(),
        largeTitle = provider.title(),
        scrollBehavior = scrollBehavior,
        bottomBar = {
            BottomActionBar(
                primaryText = "保存设置",
                onPrimaryClick = {
                    ThemeSettings.write(context, snapshot)
                    ThemeLibrary.syncApplied(context)
                    ThemeSync.applyRoundAvatar(context, snapshot.roundAvatar)
                    Toast.makeText(context, "已保存，回到微信首页立即生效", Toast.LENGTH_SHORT).show()
                },
                secondaryText = "返回",
                onSecondaryClick = onBack
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
            state = listState,
            contentPadding = PaddingValues(
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 84.dp
            )
        ) {
            item { SmallTitle(text = "主题") }
            item {
                SettingsCard {
                    SwitchRow(
                        checked = snapshot.enabled,
                        title = "启用美化主题",
                        summary = "关闭后不再改微信界面，已改的设置会保留"
                    ) { checked ->
                        update { it.copy(enabled = checked) }
                    }
                    InsetDivider()
                    ActionRow(title = "导出主题", summary = exportSummary(snapshot)) {
                        if (activity == null) {
                            showThemeDialog(context, "导出失败", "当前页面不在 Activity 里，无法调起系统保存界面")
                            return@ActionRow
                        }
                        ThemeSettings.write(context, snapshot)
                        val json = runCatching { ThemeSpec.exportJson(context) }.getOrElse {
                            showThemeDialog(context, "导出失败", "生成主题文件失败：${it.message ?: it.javaClass.simpleName}")
                            return@ActionRow
                        }
                        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.ROOT).format(Date())
                        ThemeFileIo.exportThemeFile(
                            activity,
                            "Hchat主题-$stamp.hctheme.json",
                            json
                        ) { error ->
                            if (error != null) {
                                showThemeDialog(context, "导出失败", error)
                            } else {
                                Toast.makeText(context, "主题已导出", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
            }

            item { SmallTitle(modifier = Modifier.padding(top = 10.dp), text = "主题库") }
            item {
                SettingsCard {
                    ActionRow(
                        title = "导入主题",
                        summary = if (library.isEmpty()) "导入后会存进主题库并立即应用" else "已保存 ${library.size} 份，导入后立即应用"
                    ) {
                        if (activity == null) {
                            showThemeDialog(context, "导入失败", "当前页面不在 Activity 里，无法调起系统文件选择器")
                            return@ActionRow
                        }
                        ThemeFileIo.importThemeFile(activity) { bytes, name, error ->
                            when {
                                error != null -> showThemeDialog(context, "导入失败", error)
                                bytes == null -> showThemeDialog(context, "导入失败", "没有读到文件内容")
                                else -> ThemeLibrary.import(context, bytes, name.orEmpty())
                                    .onSuccess { entry ->
                                        when (val out = ThemeLibrary.apply(context, entry.id)) {
                                            is ThemeSpec.ImportOutcome.Success -> {
                                                snapshot = ThemeSettings.snapshot(context)
                                                library = ThemeLibrary.list(context)
                                                ThemeSync.applyRoundAvatar(context, snapshot.roundAvatar)
                                                showThemeDialog(context, "已导入并应用", "「${entry.name}」已存进主题库并应用")
                                            }
                                            is ThemeSpec.ImportOutcome.Failure -> {
                                                library = ThemeLibrary.list(context)
                                                showThemeDialog(context, "已存进主题库，但应用失败", out.reason)
                                            }
                                        }
                                    }
                                    .onFailure { showThemeDialog(context, "导入失败", it.message ?: it.javaClass.simpleName) }
                            }
                        }
                    }
                }
            }
            library.forEach { entry ->
                item {
                    SettingsCard {
                        ActionRow(title = entry.name, summary = librarySummary(entry)) {
                            showLibraryActions(
                                context = context,
                                entryName = entry.name,
                                onApply = {
                                    when (val outcome = ThemeLibrary.apply(context, entry.id)) {
                                        is ThemeSpec.ImportOutcome.Success -> {
                                            snapshot = ThemeSettings.snapshot(context)
                                            library = ThemeLibrary.list(context)
                                            ThemeSync.applyRoundAvatar(context, snapshot.roundAvatar)
                                            Toast.makeText(context, "已应用「${entry.name}」", Toast.LENGTH_SHORT).show()
                                        }
                                        is ThemeSpec.ImportOutcome.Failure -> showThemeDialog(context, "应用失败", outcome.reason)
                                    }
                                },
                                onRename = {
                                    showRenameDialog(context, entry.name) { newName ->
                                        ThemeLibrary.rename(context, entry.id, newName)
                                        library = ThemeLibrary.list(context)
                                    }
                                },
                                onDelete = {
                                    showConfirmDialog(context, "删除主题", "确定从主题库删除「${entry.name}」？（不会改变当前已应用的设置）") {
                                        ThemeLibrary.delete(context, entry.id)
                                        library = ThemeLibrary.list(context)
                                    }
                                }
                            )
                        }
                    }
                }
            }

            if (snapshot.enabled) {
                item { SmallTitle(modifier = Modifier.padding(top = 10.dp), text = "行遮罩") }
                item {
                    var strength by remember { mutableStateOf(snapshot.rowMaskStrength) }
                    SettingsCard {
                        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {
                            Text(
                                text = "行遮罩强度 $strength%",
                                color = MiuixTheme.colorScheme.onSurface,
                                fontWeight = FontWeight.Medium
                            )
                            Slider(
                                value = strength.toFloat(),
                                onValueChange = { strength = it.toInt().coerceIn(0, 100) },
                                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                                valueRange = 0f..100f,
                                steps = 99,
                                onValueChangeFinished = { update { it.copy(rowMaskStrength = strength) } }
                            )
                        }
                    }
                }
            }
            if (snapshot.enabled) {
                item { SmallTitle(modifier = Modifier.padding(top = 10.dp), text = "Tab（底栏）") }
                item {
                    SettingsCard {
                        SwitchRow(
                            checked = snapshot.tabEnabled,
                            title = "启用底栏主题",
                            summary = "底栏背景色、文字色与图标"
                        ) { checked ->
                            update { it.copy(tabEnabled = checked) }
                        }
                        InsetDivider()
                        SwitchRow(
                            checked = snapshot.tabHideNative,
                            title = "隐藏原生底栏",
                            summary = "与底栏主题互相独立，不开底栏主题也能隐藏"
                        ) { checked ->
                            update { it.copy(tabHideNative = checked) }
                        }
                        if (snapshot.tabEnabled) {
                            InsetDivider()
                            ColorPickerRow(
                                title = "底栏背景色",
                                summary = "留空跟随微信原生",
                                value = snapshot.tabBackgroundColor,
                                allowGradient = false,
                                onReset = { update { it.copy(tabBackgroundColor = "") } }
                            ) { value ->
                                update { it.copy(tabBackgroundColor = value.trim().take(9)) }
                            }
                            InsetDivider()
                            ColorPickerRow(
                                title = "底栏文字颜色",
                                summary = "未选中项的文字颜色",
                                value = snapshot.tabTextColor,
                                allowGradient = false,
                                onReset = { update { it.copy(tabTextColor = "") } }
                            ) { value ->
                                update { it.copy(tabTextColor = value.trim().take(9)) }
                            }
                            InsetDivider()
                            ColorPickerRow(
                                title = "底栏选中颜色",
                                summary = "当前页签的文字颜色",
                                value = snapshot.tabSelectedTextColor,
                                allowGradient = false,
                                onReset = { update { it.copy(tabSelectedTextColor = "") } }
                            ) { value ->
                                update { it.copy(tabSelectedTextColor = value.trim().take(9)) }
                            }
                        }
                    }
                }
                if (snapshot.tabEnabled) {
                    item { SmallTitle(modifier = Modifier.padding(top = 10.dp), text = "底栏图标") }
                    item {
                        SettingsCard {
                            tabIconTitles.forEachIndexed { index, title ->
                                if (index > 0) InsetDivider()
                                ActionRow(
                                    title = title,
                                    summary = imageSummary(snapshot.tabIcons.getOrElse(index) { "" })
                                ) {
                                    pickImage(context, ThemeImageStore.slotForTabIcon(index)) { path ->
                                        val icons = snapshot.tabIcons.toMutableList()
                                        while (icons.size < 4) icons.add("")
                                        val old = icons[index]
                                        icons[index] = path
                                        ThemeImageStore.delete(context, old)
                                        update { it.copy(tabIcons = icons) }
                                    }
                                }
                            }
                        }
                    }
                }

                item { SmallTitle(modifier = Modifier.padding(top = 10.dp), text = "头像") }
                item {
                    SettingsCard {
                        SwitchRow(
                            checked = snapshot.roundAvatar,
                            title = "圆形头像",
                            summary = "与「圆角头像」共用同一套头像 Hook，开关会同步过去"
                        ) { checked ->
                            ThemeSync.applyRoundAvatar(context, checked)
                            update { it.copy(roundAvatar = checked) }
                        }
                    }
                }

                item { SmallTitle(modifier = Modifier.padding(top = 10.dp), text = "标题栏（ActionBar）") }
                item {
                    SettingsCard {
                        SwitchRow(
                            checked = snapshot.actionBarHide,
                            title = "隐藏 ActionBar",
                            summary = "隐藏微信首页标题栏"
                        ) { checked ->
                            update { it.copy(actionBarHide = checked) }
                        }
                        InsetDivider()
                        ColorPickerRow(
                            title = "标题栏主色",
                            summary = "ActionBar 背景色，留空跟随原生",
                            value = snapshot.actionBarBackgroundColor,
                            allowGradient = false,
                            onReset = { update { it.copy(actionBarBackgroundColor = "") } }
                        ) { value ->
                            update { it.copy(actionBarBackgroundColor = value.trim().take(9)) }
                        }
                        InsetDivider()
                        ColorPickerRow(
                            title = "标题颜色",
                            summary = "标题栏第一段文字颜色",
                            value = snapshot.actionBarTitleColor,
                            allowGradient = false,
                            onReset = { update { it.copy(actionBarTitleColor = "") } }
                        ) { value ->
                            update { it.copy(actionBarTitleColor = value.trim().take(9)) }
                        }
                        InsetDivider()
                        ColorPickerRow(
                            title = "副标题颜色",
                            summary = "标题栏其余文字颜色",
                            value = snapshot.actionBarSubtitleColor,
                            allowGradient = false,
                            onReset = { update { it.copy(actionBarSubtitleColor = "") } }
                        ) { value ->
                            update { it.copy(actionBarSubtitleColor = value.trim().take(9)) }
                        }
                    }
                }

                item { SmallTitle(modifier = Modifier.padding(top = 10.dp), text = "设置页面背景图片") }
                item {
                    SettingsCard {
                        SwitchRow(
                            checked = snapshot.backgroundEnabled,
                            title = "启用页面背景",
                            summary = "微信 / 通讯录 / 发现 / 我 四个页面各用一张图"
                        ) { checked ->
                            update { it.copy(backgroundEnabled = checked) }
                        }
                        if (snapshot.backgroundEnabled) {
                            InsetDivider()
                            ActionRow(title = "设置页面背景图片", summary = backgroundSummary(snapshot)) {
                                AlertDialog.Builder(context)
                                    .setTitle("设置页面背景图片")
                                    .setItems(pageEntries.map { pageLabel(snapshot, it) }.toTypedArray()) { _, which ->
                                        showPageDialog(context, pageEntries[which], { ThemeSettings.snapshot(context) }) { block -> update(block) }
                                    }
                                    .setNegativeButton("返回", null)
                                    .show()
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun exportSummary(snapshot: ThemeSnapshot): String {
    val items = ArrayList<String>()
    if (snapshot.tabEnabled) items.add("底栏")
    if (snapshot.roundAvatar) items.add("圆形头像")
    if (snapshot.actionBarHide) items.add("隐藏 ActionBar")
    if (snapshot.backgroundPaths().any { it.isNotBlank() }) items.add("页面背景")
    return if (items.isEmpty()) "当前没有勾选主题项" else "当前包含：" + items.joinToString("、")
}

private fun imageSummary(path: String): String =
    if (path.trim().isEmpty()) "未设置，点击选择图片" else "已设置，点击更换"

private fun pickImage(context: Context, slot: String, onSaved: (String) -> Unit) {
    val activity = context as? Activity
    if (activity == null) {
        Toast.makeText(context, "当前页面不在 Activity 里，无法选图", Toast.LENGTH_SHORT).show()
        return
    }
    ThemeFileIo.pickImage(activity, slot) { path, error ->
        if (error != null) {
            showThemeDialog(context, "选择图片失败", error)
        } else if (path != null) {
            onSaved(path)
        }
    }
}

private fun showThemeDialog(context: Context, title: String, message: String) {
    runCatching {
        AlertDialog.Builder(context)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("知道了") { dialog, _ -> dialog.dismiss() }
            .show()
    }
}

private val tabIconTitles = listOf("微信页图标", "通讯录页图标", "发现页图标", "我的页图标")

private val backgroundSlots = listOf(
    BackgroundSlot(
        ThemeImageStore.SLOT_SPLASH,
        "设置微信启动图片",
        { snapshot: ThemeSnapshot -> snapshot.backgroundSplash },
        { snapshot: ThemeSnapshot, path: String? -> snapshot.copy(backgroundSplash = path.orEmpty()) }
    ),
    BackgroundSlot(
        ThemeImageStore.SLOT_HOME,
        "设置主页背景图片",
        { snapshot -> snapshot.backgroundHome },
        { snapshot, path -> snapshot.copy(backgroundHome = path.orEmpty()) }
    ),
    BackgroundSlot(
        ThemeImageStore.SLOT_CONTACTS,
        "设置通讯录页面背景图片",
        { snapshot -> snapshot.backgroundContacts },
        { snapshot, path -> snapshot.copy(backgroundContacts = path.orEmpty()) }
    ),
    BackgroundSlot(
        ThemeImageStore.SLOT_DISCOVER,
        "设置发现页面背景图片",
        { snapshot -> snapshot.backgroundDiscover },
        { snapshot, path -> snapshot.copy(backgroundDiscover = path.orEmpty()) }
    ),
    BackgroundSlot(
        ThemeImageStore.SLOT_ME,
        "设置我页面背景图片",
        { snapshot -> snapshot.backgroundMe },
        { snapshot, path -> snapshot.copy(backgroundMe = path.orEmpty()) }
    )
)

private class BackgroundSlot(
    val first: String,
    val second: String,
    val third: (ThemeSnapshot) -> String,
    val fourth: (ThemeSnapshot, String?) -> ThemeSnapshot
)

private fun librarySummary(entry: ThemeLibrary.Entry): String {
    val state = if (entry.applied) "当前已应用" else "未应用"
    val kind = if (entry.isArchive) "zip 主题包" else "主题文件"
    if (entry.savedAt <= 0L) return "$state · $kind"
    val stamp = SimpleDateFormat("MM-dd HH:mm", Locale.ROOT).format(Date(entry.savedAt))
    return "$state · $kind · $stamp"
}

private fun showLibraryActions(
    context: Context,
    entryName: String,
    onApply: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    runCatching {
        AlertDialog.Builder(context)
            .setTitle(entryName)
            .setItems(arrayOf("应用这个主题", "重命名", "删除")) { dialog, which ->
                dialog.dismiss()
                when (which) {
                    0 -> onApply()
                    1 -> onRename()
                    2 -> onDelete()
                }
            }
            .show()
    }
}

private fun showRenameDialog(context: Context, current: String, onConfirm: (String) -> Unit) {
    runCatching {
        val input = EditText(context).apply {
            setText(current)
            setSelection(current.length)
            setSingleLine()
        }
        AlertDialog.Builder(context)
            .setTitle("重命名主题")
            .setView(input)
            .setPositiveButton("确定") { _, _ -> onConfirm(input.text?.toString().orEmpty()) }
            .setNegativeButton("取消", null)
            .show()
    }
}

private fun showConfirmDialog(context: Context, title: String, message: String, onConfirm: () -> Unit) {
    runCatching {
        AlertDialog.Builder(context)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("删除") { _, _ -> onConfirm() }
            .setNegativeButton("取消", null)
            .show()
    }
}

private class PageEntry(
    val title: String,
    val slot: String,
    val path: (ThemeSnapshot) -> String,
    val withPath: (ThemeSnapshot, String) -> ThemeSnapshot,
    val enabled: (ThemeSnapshot) -> Boolean,
    val withEnabled: (ThemeSnapshot, Boolean) -> ThemeSnapshot
)

private val pageEntries = listOf(
    PageEntry(
        "启动图", ThemeImageStore.SLOT_SPLASH,
        { it.backgroundSplash }, { s, p -> s.copy(backgroundSplash = p) },
        { it.pageSplash }, { s, v -> s.copy(pageSplash = v) }
    ),
    PageEntry(
        "主页（微信）", ThemeImageStore.SLOT_HOME,
        { it.backgroundHome }, { s, p -> s.copy(backgroundHome = p) },
        { it.pageHome }, { s, v -> s.copy(pageHome = v) }
    ),
    PageEntry(
        "通讯录", ThemeImageStore.SLOT_CONTACTS,
        { it.backgroundContacts }, { s, p -> s.copy(backgroundContacts = p) },
        { it.pageContacts }, { s, v -> s.copy(pageContacts = v) }
    ),
    PageEntry(
        "发现", ThemeImageStore.SLOT_DISCOVER,
        { it.backgroundDiscover }, { s, p -> s.copy(backgroundDiscover = p) },
        { it.pageDiscover }, { s, v -> s.copy(pageDiscover = v) }
    ),
    PageEntry(
        "我", ThemeImageStore.SLOT_ME,
        { it.backgroundMe }, { s, p -> s.copy(backgroundMe = p) },
        { it.pageMe }, { s, v -> s.copy(pageMe = v) }
    )
)

private fun backgroundSummary(snapshot: ThemeSnapshot): String {
    val on = pageEntries.count { it.enabled(snapshot) }
    val set = pageEntries.count { it.path(snapshot).isNotBlank() }
    return "$on/${pageEntries.size} 页开启 · 已设 $set 张图"
}

private fun pageLabel(snapshot: ThemeSnapshot, entry: PageEntry): String {
    val state = if (entry.enabled(snapshot)) "开启" else "关闭"
    val image = if (entry.path(snapshot).isBlank()) "未设图" else "已设图"
    return "${entry.title}（$state · $image）"
}

private fun showPageDialog(
    context: Context,
    entry: PageEntry,
    current: () -> ThemeSnapshot,
    update: ((ThemeSnapshot) -> ThemeSnapshot) -> Unit
) {
    val snapshot = current()
    val density = context.resources.displayMetrics.density
    fun px(value: Int): Int = (value * density).toInt()
    val box = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(px(20), px(10), px(20), px(4))
    }
    box.addView(
        Switch(context).apply {
            text = "开启"
            isChecked = entry.enabled(snapshot)
            setOnCheckedChangeListener { _, checked -> update { s -> entry.withEnabled(s, checked) } }
        }
    )
    box.addView(
        TextView(context).apply {
            val path = current().let(entry.path)
            text = if (path.isBlank()) "当前图片：未设置" else "当前图片：已设置"
            setPadding(0, px(8), 0, px(8))
        }
    )
    val imagePath = entry.path(snapshot)
    val bitmap = if (imagePath.isBlank()) null else runCatching { BitmapFactory.decodeFile(imagePath) }.getOrNull()
    if (bitmap != null) {
        box.addView(
            ImageView(context).apply {
                setImageBitmap(bitmap)
                adjustViewBounds = true
                scaleType = ImageView.ScaleType.CENTER_CROP
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, px(200))
            }
        )
    }
    val actions = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(0, px(6), 0, 0)
    }
    actions.addView(
        Button(context).apply {
            text = "换背景图"
            setOnClickListener {
                val old = current().let(entry.path)
                pickImage(context, entry.slot) { picked ->
                    ThemeImageStore.delete(context, old)
                    update { s -> entry.withPath(s, picked) }
                }
            }
        },
        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
    )
    actions.addView(
        Button(context).apply {
            text = "预览"
            setOnClickListener { showPreview(context, entry.title, current().let(entry.path)) }
        },
        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
    )
    box.addView(actions)
    AlertDialog.Builder(context)
        .setTitle(entry.title)
        .setView(box)
        .setPositiveButton("确定", null)
        .setNegativeButton("取消", null)
        .show()
}

private fun showPreview(context: Context, title: String, path: String) {
    if (path.isBlank()) {
        showThemeDialog(context, title, "还没有设置背景图")
        return
    }
    val bitmap = runCatching { BitmapFactory.decodeFile(path) }.getOrNull()
    if (bitmap == null) {
        showThemeDialog(context, title, "图片读取失败：$path")
        return
    }
    val view = ImageView(context).apply {
        setImageBitmap(bitmap)
        adjustViewBounds = true
        setPadding(24, 24, 24, 24)
    }
    runCatching {
        AlertDialog.Builder(context)
            .setTitle(title)
            .setView(view)
            .setPositiveButton("关闭", null)
            .show()
    }
}
