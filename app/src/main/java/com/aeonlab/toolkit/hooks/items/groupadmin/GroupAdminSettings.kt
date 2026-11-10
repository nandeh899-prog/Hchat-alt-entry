package com.aeonlab.toolkit.hooks.items.groupadmin

import android.content.Context
import android.content.SharedPreferences
import com.aeonlab.toolkit.preferences.AeonLabStorage

/**
 * 群管理助手配置层。键名与规格 4.3 一致，沿用模块既有约定：
 * 底层 [AeonLabStorage.preferences]，FastKV，目录 AeonLab、文件前缀 AeonLab_。
 */
object GroupAdminSettings {

    const val PREFS_NAME = "AeonLab_group_admin_config"

    /** 全局开关。 */
    const val KEY_ENABLE = "group_admin_enable"

    /** 已纳管群：JSON [{room,name}]，仅用于面板快捷入口。 */
    const val KEY_GROUPS = "group_admin_groups"

    /** 按群配置：JSON { "<room>": GroupConfig }。 */
    const val KEY_CONFIGS = "group_admin_group_configs"

    /** 进群验证：JSON { "<room>": VerifyConfig }，字段名 enable/questionList/maxErr/timeLimit/text*。 */
    const val KEY_VERIFY = "group_admin_verify"

    /** 模板：JSON [Template]（第 9 步使用）。 */
    const val KEY_TEMPLATES = "group_admin_templates"

    /** 警告计数：JSON { "room|uid|rule": count }（第 4 步使用）。 */
    const val KEY_WARN = "group_admin_warn"

    /** 全局默认告别语，按群可用 farewell 覆盖。 */
    const val KEY_FAREWELL = "group_admin_farewell"

    const val DEFAULT_ENABLE = false
    const val DEFAULT_FAREWELL = "拜拜~"

    fun preferences(context: Context): SharedPreferences =
        AeonLabStorage.preferences(context, PREFS_NAME)

    fun isEnabled(context: Context?): Boolean {
        if (context == null) return DEFAULT_ENABLE
        return runCatching { preferences(context).getBoolean(KEY_ENABLE, DEFAULT_ENABLE) }
            .getOrDefault(DEFAULT_ENABLE)
    }

    fun setEnabled(context: Context?, value: Boolean) {
        if (context == null) return
        runCatching { preferences(context).edit().putBoolean(KEY_ENABLE, value).apply() }
    }

    fun defaultFarewell(context: Context?): String {
        if (context == null) return DEFAULT_FAREWELL
        return runCatching { preferences(context).getString(KEY_FAREWELL, DEFAULT_FAREWELL) }
            .getOrNull()?.takeIf { it.isNotBlank() } ?: DEFAULT_FAREWELL
    }
}
