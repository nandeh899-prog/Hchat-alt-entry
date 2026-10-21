package com.aeonlab.toolkit.hooks.items.moments

import com.aeonlab.toolkit.ui.FeatureSettingsProvider
import com.aeonlab.toolkit.ui.SimpleFeatureSettingsProvider

class SnsAntiRecallSettingsProvider : SimpleFeatureSettingsProvider(
    SnsAntiRecallFeature.ID,
    "朋友圈防撤回",
    "已缓存的朋友圈在对方删除或限制可见范围后继续显示",
    FeatureSettingsProvider.CATEGORY_PRACTICAL
)
