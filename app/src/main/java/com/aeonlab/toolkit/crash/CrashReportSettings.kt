package com.aeonlab.toolkit.crash

import android.content.Context
import com.aeonlab.toolkit.preferences.AeonLabStorage

object CrashReportSettings {
    const val PREFS_NAME = "AeonLab_crash_report_config"
    const val KEY_ENABLE = "crash_report_enable"
    const val DEFAULT_ENABLE = false

    @JvmStatic
    fun isEnabled(context: Context): Boolean {
        return AeonLabStorage.preferences(context, PREFS_NAME)
            .getBoolean(KEY_ENABLE, DEFAULT_ENABLE)
    }
}
