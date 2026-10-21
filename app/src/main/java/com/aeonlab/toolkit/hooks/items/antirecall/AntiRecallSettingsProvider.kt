package com.aeonlab.toolkit.hooks.items.antirecall

import com.aeonlab.toolkit.ui.FeatureSettingsProvider
import com.aeonlab.toolkit.ui.SimpleFeatureSettingsProvider

class AntiRecallSettingsProvider : SimpleFeatureSettingsProvider(
    AntiRecallFeature.ID,
    "防撤回",
    "保留被撤回的消息",
    FeatureSettingsProvider.CATEGORY_PRACTICAL
)
