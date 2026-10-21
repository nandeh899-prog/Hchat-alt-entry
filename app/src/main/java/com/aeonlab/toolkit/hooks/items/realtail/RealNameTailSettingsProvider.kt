package com.aeonlab.toolkit.hooks.items.realtail

import com.aeonlab.toolkit.ui.FeatureSettingsProvider
import com.aeonlab.toolkit.ui.SimpleFeatureSettingsProvider

class RealNameTailSettingsProvider : SimpleFeatureSettingsProvider(
    RealNameTailFeature.ID,
    "实名尾字",
    "群聊里自动补查并显示实名尾字",
    FeatureSettingsProvider.CATEGORY_ENTERTAINMENT
)
