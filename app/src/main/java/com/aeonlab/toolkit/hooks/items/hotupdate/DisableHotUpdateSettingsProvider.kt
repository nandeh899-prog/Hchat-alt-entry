package com.aeonlab.toolkit.hooks.items.hotupdate

import com.aeonlab.toolkit.ui.FeatureSettingsProvider
import com.aeonlab.toolkit.ui.SimpleFeatureSettingsProvider

class DisableHotUpdateSettingsProvider : SimpleFeatureSettingsProvider(
    DisableHotUpdateFeature.ID,
    "屏蔽热更新",
    "阻止微信加载和应用热更新补丁",
    FeatureSettingsProvider.CATEGORY_PRACTICAL
)
