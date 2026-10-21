package com.aeonlab.toolkit.hooks.items.tablet

import com.aeonlab.toolkit.ui.FeatureSettingsProvider
import com.aeonlab.toolkit.ui.SimpleFeatureSettingsProvider

class WeChatTabletSettingsProvider : SimpleFeatureSettingsProvider(
    WeChatTabletFeature.ID,
    "平板模式",
    "伪装平板登录微信",
    FeatureSettingsProvider.CATEGORY_PRACTICAL
)
