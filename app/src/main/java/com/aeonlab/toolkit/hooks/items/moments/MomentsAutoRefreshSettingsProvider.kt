package com.aeonlab.toolkit.hooks.items.moments

import com.aeonlab.toolkit.ui.FeatureSettingsProvider
import com.aeonlab.toolkit.ui.SimpleFeatureSettingsProvider

class MomentsAutoRefreshSettingsProvider : SimpleFeatureSettingsProvider(
    MomentsAutoRefreshFeature.ID,
    "朋友圈自动刷新",
    "按设定间隔获取新的朋友圈内容",
    FeatureSettingsProvider.CATEGORY_PRACTICAL
)
