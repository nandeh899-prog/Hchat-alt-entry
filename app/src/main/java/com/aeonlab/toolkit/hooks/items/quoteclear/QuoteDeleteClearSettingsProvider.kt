package com.aeonlab.toolkit.hooks.items.quoteclear

import com.aeonlab.toolkit.ui.FeatureSettingsProvider
import com.aeonlab.toolkit.ui.SimpleFeatureSettingsProvider

class QuoteDeleteClearSettingsProvider : SimpleFeatureSettingsProvider(
    QuoteDeleteClearFeature.ID,
    "删除键清引用",
    "输入框为空时按删除键取消引用消息",
    FeatureSettingsProvider.CATEGORY_PRACTICAL
)
