package com.aeonlab.toolkit.hooks.items.moments

import com.aeonlab.toolkit.ui.FeatureSettingsProvider
import com.aeonlab.toolkit.ui.SimpleFeatureSettingsProvider

class MomentsBottomDetailSettingsProvider : SimpleFeatureSettingsProvider(
    MomentsBottomDetailFeature.ID,
    "朋友圈底部详情",
    "自定义朋友圈底部时间和详情格式",
    FeatureSettingsProvider.CATEGORY_PRACTICAL
)
