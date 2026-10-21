package com.aeonlab.toolkit.hooks.items.moments

import com.aeonlab.toolkit.ui.FeatureSettingsProvider
import com.aeonlab.toolkit.ui.SimpleFeatureSettingsProvider

class RemoveMomentsAdsSettingsProvider : SimpleFeatureSettingsProvider(
    RemoveMomentsAdsFeature.ID,
    "去除朋友圈广告",
    "阻止朋友圈广告信息解析和展示",
    FeatureSettingsProvider.CATEGORY_PRACTICAL
)
