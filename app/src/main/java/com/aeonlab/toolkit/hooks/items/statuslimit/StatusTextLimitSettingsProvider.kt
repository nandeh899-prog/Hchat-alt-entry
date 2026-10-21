package com.aeonlab.toolkit.hooks.items.statuslimit

import com.aeonlab.toolkit.ui.FeatureSettingsProvider
import com.aeonlab.toolkit.ui.SimpleFeatureSettingsProvider

class StatusTextLimitSettingsProvider : SimpleFeatureSettingsProvider(
    StatusTextLimitFeature.ID,
    "解除状态词长度限制",
    "允许个人状态词超过 10 个字",
    FeatureSettingsProvider.CATEGORY_ENTERTAINMENT
)
