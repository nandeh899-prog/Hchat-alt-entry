package com.aeonlab.toolkit.crash

import com.aeonlab.toolkit.ui.FeatureSettingsProvider
import com.aeonlab.toolkit.ui.SimpleFeatureSettingsProvider

class CrashReportSettingsProvider : SimpleFeatureSettingsProvider(
    ID,
    "捕获异常日志",
    "记录微信异常并在下次启动时显示日志",
    FeatureSettingsProvider.CATEGORY_ENTERTAINMENT
) {
    companion object {
        const val ID = "crash_report"
    }
}
