package com.aeonlab.toolkit.ui.miuix

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import com.aeonlab.toolkit.hooks.items.theme.ThemeFileIo
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
    val listState = rememberLazyListState()
    val scrollBehavior = MiuixScrollBehavior()
    val activity = context as? Activity

    fun persist(next: ThemeSnapshot) {
        ThemeSettings.write(context, next)
        snapshot = ThemeSettings.snapshot(context)
    }

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
                        persist(snapshot.copy(enabled = checked))
                    }
                    InsetDivider()
                    ActionRow(
                        title = "主题文件",
                        summary = "导出 / 导入主题（.hctheme.json，可与 MDWechat 配置互通）"
                    ) {
                        showThemeDialog(
                            context,
                            "主题文件",
                            "导出会在系统文件选择器里生成一个自包含的主题文件（图片以 base64 内联）。\n" +
                                "导入支持 Hchat 主题文件，也支持手机里现成的 zip 主题包（扩展名是 .zip 或被改成 .txt 的 zip 都可以）。"
                        )
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
                    InsetDivider()
                    ActionRow(title = "导入主题", summary = "选择 .hctheme.json / .json / 主题 zip 包") {
                        if (activity == null) {
                            showThemeDialog(context, "导入失败", "当前页面不在 Activity 里，无法调起系统文件选择器")
                            return@ActionRow
                        }
                        ThemeFileIo.importThemeFile(activity) { bytes, name, error ->
                            when {
                                error != null -> showThemeDialog(context, "导入失败", error)
                                bytes == null -> showThemeDialog(context, "导入失败", "没有读到文件内容")
                                else -> importBytes(bytes, name.orEmpty())
                            }
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
                            persist(snapshot.copy(tabEnabled = checked))
                        }
                        if (snapshot.tabEnabled) {
                            InsetDivider()
                            SwitchRow(
                                checked = snapshot.tabHideNative,
                                title = "隐藏原生底栏",
                                summary = "完全隐藏微信底栏（可从侧滑/返回操作切页）"
                            ) { checked ->
                                persist(snapshot.copy(tabHideNative = checked))
                            }
                            InsetDivider()
                            ColorPickerRow(
                                title = "底栏背景色",
                                summary = "留空跟随微信原生",
                                value = snapshot.tabBackgroundColor,
                                allowGradient = false,
                                onReset = { persist(snapshot.copy(tabBackgroundColor = "")) }
                            ) { value ->
                                persist(snapshot.copy(tabBackgroundColor = value.trim().take(9)))
                            }
                            InsetDivider()
                            ColorPickerRow(
                                title = "底栏文字颜色",
                                summary = "未选中项的文字颜色",
                                value = snapshot.tabTextColor,
                                allowGradient = false,
                                onReset = { persist(snapshot.copy(tabTextColor = "")) }
                            ) { value ->
                                persist(snapshot.copy(tabTextColor = value.trim().take(9)))
                            }
                            InsetDivider()
                            ColorPickerRow(
                                title = "底栏选中颜色",
                                summary = "当前页签的文字颜色",
                                value = snapshot.tabSelectedTextColor,
                                allowGradient = false,
                                onReset = { persist(snapshot.copy(tabSelectedTextColor = "")) }
                            ) { value ->
                                persist(snapshot.copy(tabSelectedTextColor = value.trim().take(9)))
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
                                        persist(snapshot.copy(tabIcons = icons))
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
                            persist(snapshot.copy(roundAvatar = checked))
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
                            persist(snapshot.copy(actionBarHide = checked))
                        }
                        InsetDivider()
                        ColorPickerRow(
                            title = "标题栏主色",
                            summary = "ActionBar 背景色，留空跟随原生",
                            value = snapshot.actionBarBackgroundColor,
                            allowGradient = false,
                            onReset = { persist(snapshot.copy(actionBarBackgroundColor = "")) }
                        ) { value ->
                            persist(snapshot.copy(actionBarBackgroundColor = value.trim().take(9)))
                        }
                        InsetDivider()
                        ColorPickerRow(
                            title = "标题颜色",
                            summary = "标题栏第一段文字颜色",
                            value = snapshot.actionBarTitleColor,
                            allowGradient = false,
                            onReset = { persist(snapshot.copy(actionBarTitleColor = "")) }
                        ) { value ->
                            persist(snapshot.copy(actionBarTitleColor = value.trim().take(9)))
                        }
                        InsetDivider()
                        ColorPickerRow(
                            title = "副标题颜色",
                            summary = "标题栏其余文字颜色",
                            value = snapshot.actionBarSubtitleColor,
                            allowGradient = false,
                            onReset = { persist(snapshot.copy(actionBarSubtitleColor = "")) }
                        ) { value ->
                            persist(snapshot.copy(actionBarSubtitleColor = value.trim().take(9)))
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
                            persist(snapshot.copy(backgroundEnabled = checked))
                        }
                        if (snapshot.backgroundEnabled) {
                            backgroundSlots.forEach { slot ->
                                InsetDivider()
                                ActionRow(title = slot.second, summary = imageSummary(slot.third(snapshot))) {
                                    pickImage(context, slot.first) { path ->
                                        ThemeImageStore.delete(context, slot.third(snapshot))
                                        persist(slot.fourth(snapshot, path))
                                    }
                                }
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
