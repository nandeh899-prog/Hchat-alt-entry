package com.aeonlab.toolkit.hooks.items.typingreport

import com.aeonlab.toolkit.ui.FeatureSettingsProvider
import com.aeonlab.toolkit.ui.SimpleFeatureSettingsProvider

class TypingReportBlockSettingsProvider : SimpleFeatureSettingsProvider(
    TypingReportBlockFeature.ID,
    "拦截正在输入上报",
    "输入文字时不向对方显示正在输入状态",
    FeatureSettingsProvider.CATEGORY_PRACTICAL
)
