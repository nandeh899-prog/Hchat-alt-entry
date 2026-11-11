package com.aeonlab.toolkit.hooks.items.theme

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import com.aeonlab.toolkit.preferences.AeonLabStorage

/**
 * 美化主题的本地设置（主题数据落地格式见 [ThemeSpec]）。
 *
 * 设计原则：所有字段都存字符串，空串表示「未设置 / 跟随微信原生」，
 * 这样导出成 JSON 时可以直接对应到主题文件里的同名字段。
 */
object ThemeSettings {
    const val PREFS_NAME = "AeonLab_beautify_theme_config"

    const val KEY_ENABLE = "theme_enable"
    const val KEY_NAME = "theme_name"
    const val KEY_AUTHOR = "theme_author"
    const val KEY_NOTE = "theme_note"

    /** 底栏（Tab） */
    const val KEY_TAB_ENABLE = "theme_tab_enable"
    const val KEY_TAB_HIDE = "theme_tab_hide_native"
    const val KEY_TAB_BG_COLOR = "theme_tab_bg_color"
    const val KEY_TAB_TEXT_COLOR = "theme_tab_text_color"
    const val KEY_TAB_SELECTED_COLOR = "theme_tab_selected_color"
    val TAB_ICON_KEYS = listOf(
        "theme_tab_icon_wechat",
        "theme_tab_icon_contacts",
        "theme_tab_icon_discover",
        "theme_tab_icon_me"
    )

    /** 头像 */
    const val KEY_ROUND_AVATAR = "theme_round_avatar"

    /** 标题栏 ActionBar */
    const val KEY_ACTIONBAR_HIDE = "theme_actionbar_hide"
    const val KEY_ACTIONBAR_BG_COLOR = "theme_actionbar_bg_color"
    const val KEY_ACTIONBAR_TITLE_COLOR = "theme_actionbar_title_color"
    const val KEY_ACTIONBAR_SUBTITLE_COLOR = "theme_actionbar_subtitle_color"

    /** 页面背景图片 */
    const val KEY_BG_ENABLE = "theme_background_enable"
    const val KEY_BG_SPLASH = "theme_bg_splash"
    const val KEY_BG_HOME = "theme_bg_home"
    const val KEY_BG_CONTACTS = "theme_bg_contacts"
    const val KEY_BG_DISCOVER = "theme_bg_discover"
    const val KEY_BG_ME = "theme_bg_me"

    val BACKGROUND_KEYS = listOf(KEY_BG_SPLASH, KEY_BG_HOME, KEY_BG_CONTACTS, KEY_BG_DISCOVER, KEY_BG_ME)

    fun preferences(context: Context): SharedPreferences =
        AeonLabStorage.preferences(context, PREFS_NAME)

    fun enabled(context: Context): Boolean = runCatching {
        preferences(context).getBoolean(KEY_ENABLE, false)
    }.getOrDefault(false)

    fun snapshot(context: Context): ThemeSnapshot {
        val sp = preferences(context)
        return ThemeSnapshot(
            enabled = sp.getBoolean(KEY_ENABLE, false),
            name = sp.getString(KEY_NAME, "").orEmpty(),
            author = sp.getString(KEY_AUTHOR, "").orEmpty(),
            note = sp.getString(KEY_NOTE, "").orEmpty(),
            tabEnabled = sp.getBoolean(KEY_TAB_ENABLE, true),
            tabHideNative = sp.getBoolean(KEY_TAB_HIDE, false),
            tabBackgroundColor = sp.getString(KEY_TAB_BG_COLOR, "").orEmpty(),
            tabTextColor = sp.getString(KEY_TAB_TEXT_COLOR, "").orEmpty(),
            tabSelectedTextColor = sp.getString(KEY_TAB_SELECTED_COLOR, "").orEmpty(),
            tabIcons = TAB_ICON_KEYS.map { sp.getString(it, "").orEmpty() },
            roundAvatar = sp.getBoolean(KEY_ROUND_AVATAR, false),
            actionBarHide = sp.getBoolean(KEY_ACTIONBAR_HIDE, false),
            actionBarBackgroundColor = sp.getString(KEY_ACTIONBAR_BG_COLOR, "").orEmpty(),
            actionBarTitleColor = sp.getString(KEY_ACTIONBAR_TITLE_COLOR, "").orEmpty(),
            actionBarSubtitleColor = sp.getString(KEY_ACTIONBAR_SUBTITLE_COLOR, "").orEmpty(),
            backgroundEnabled = sp.getBoolean(KEY_BG_ENABLE, true),
            backgroundSplash = sp.getString(KEY_BG_SPLASH, "").orEmpty(),
            backgroundHome = sp.getString(KEY_BG_HOME, "").orEmpty(),
            backgroundContacts = sp.getString(KEY_BG_CONTACTS, "").orEmpty(),
            backgroundDiscover = sp.getString(KEY_BG_DISCOVER, "").orEmpty(),
            backgroundMe = sp.getString(KEY_BG_ME, "").orEmpty()
        )
    }

    fun write(context: Context, snapshot: ThemeSnapshot) {
        val sp = preferences(context)
        val editor = sp.edit()
            .putBoolean(KEY_ENABLE, snapshot.enabled)
            .putString(KEY_NAME, snapshot.name)
            .putString(KEY_AUTHOR, snapshot.author)
            .putString(KEY_NOTE, snapshot.note)
            .putBoolean(KEY_TAB_ENABLE, snapshot.tabEnabled)
            .putBoolean(KEY_TAB_HIDE, snapshot.tabHideNative)
            .putString(KEY_TAB_BG_COLOR, snapshot.tabBackgroundColor)
            .putString(KEY_TAB_TEXT_COLOR, snapshot.tabTextColor)
            .putString(KEY_TAB_SELECTED_COLOR, snapshot.tabSelectedTextColor)
            .putBoolean(KEY_ROUND_AVATAR, snapshot.roundAvatar)
            .putBoolean(KEY_ACTIONBAR_HIDE, snapshot.actionBarHide)
            .putString(KEY_ACTIONBAR_BG_COLOR, snapshot.actionBarBackgroundColor)
            .putString(KEY_ACTIONBAR_TITLE_COLOR, snapshot.actionBarTitleColor)
            .putString(KEY_ACTIONBAR_SUBTITLE_COLOR, snapshot.actionBarSubtitleColor)
            .putBoolean(KEY_BG_ENABLE, snapshot.backgroundEnabled)
            .putString(KEY_BG_SPLASH, snapshot.backgroundSplash)
            .putString(KEY_BG_HOME, snapshot.backgroundHome)
            .putString(KEY_BG_CONTACTS, snapshot.backgroundContacts)
            .putString(KEY_BG_DISCOVER, snapshot.backgroundDiscover)
            .putString(KEY_BG_ME, snapshot.backgroundMe)
        TAB_ICON_KEYS.forEachIndexed { index, key ->
            editor.putString(key, snapshot.tabIcons.getOrElse(index) { "" })
        }
        editor.apply()
    }

    /** 解析 #RGB / #RRGGBB / #AARRGGBB，失败返回 null（不静默改成默认色）。 */
    fun parseColor(value: String?): Int? {
        val text = value?.trim().orEmpty()
        if (text.isEmpty()) return null
        return runCatching { Color.parseColor(text) }.getOrNull()
    }

    fun hasColor(value: String?): Boolean = parseColor(value) != null

    fun normalizeColor(value: String?): String {
        val text = value?.trim().orEmpty()
        if (text.isEmpty()) return ""
        return if (parseColor(text) == null) "" else text.uppercase()
    }
}

/** 一份主题数据的完整快照，导出、导入、应用都以它为唯一来源。 */
data class ThemeSnapshot(
    val enabled: Boolean = false,
    val name: String = "",
    val author: String = "",
    val note: String = "",
    val tabEnabled: Boolean = true,
    val tabHideNative: Boolean = false,
    val tabBackgroundColor: String = "",
    val tabTextColor: String = "",
    val tabSelectedTextColor: String = "",
    val tabIcons: List<String> = listOf("", "", "", ""),
    val roundAvatar: Boolean = false,
    val actionBarHide: Boolean = false,
    val actionBarBackgroundColor: String = "",
    val actionBarTitleColor: String = "",
    val actionBarSubtitleColor: String = "",
    val backgroundEnabled: Boolean = true,
    val backgroundSplash: String = "",
    val backgroundHome: String = "",
    val backgroundContacts: String = "",
    val backgroundDiscover: String = "",
    val backgroundMe: String = ""
) {
    fun backgroundPaths(): List<String> = listOf(
        backgroundSplash,
        backgroundHome,
        backgroundContacts,
        backgroundDiscover,
        backgroundMe
    )

    fun isEmpty(): Boolean {
        return !enabled &&
            !roundAvatar &&
            !actionBarHide &&
            !tabEnabled && !tabHideNative &&
            !backgroundEnabled &&
            tabBackgroundColor.isBlank() &&
            tabTextColor.isBlank() &&
            tabSelectedTextColor.isBlank() &&
            actionBarBackgroundColor.isBlank() &&
            actionBarTitleColor.isBlank() &&
            actionBarSubtitleColor.isBlank() &&
            tabIcons.all { it.isBlank() } &&
            backgroundPaths().all { it.isBlank() }
    }
}
