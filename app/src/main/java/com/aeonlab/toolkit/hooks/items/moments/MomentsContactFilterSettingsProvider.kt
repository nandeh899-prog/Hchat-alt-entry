package com.aeonlab.toolkit.hooks.items.moments

import com.aeonlab.toolkit.ui.FeatureSettingsProvider
import com.aeonlab.toolkit.ui.SimpleFeatureSettingsProvider

class MomentsContactFilterSettingsProvider : SimpleFeatureSettingsProvider(
    MomentsContactFilterFeature.ID,
    "朋友圈过滤",
    "按好友范围过滤朋友圈内容",
    FeatureSettingsProvider.CATEGORY_PRACTICAL
)
