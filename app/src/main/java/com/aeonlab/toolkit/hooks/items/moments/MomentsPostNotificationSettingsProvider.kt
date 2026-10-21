package com.aeonlab.toolkit.hooks.items.moments

import com.aeonlab.toolkit.ui.FeatureSettingsProvider
import com.aeonlab.toolkit.ui.SimpleFeatureSettingsProvider

class MomentsPostNotificationSettingsProvider : SimpleFeatureSettingsProvider(
    MomentsPostNotificationFeature.ID,
    "朋友圈发布通知",
    "指定好友发布朋友圈时提醒",
    FeatureSettingsProvider.CATEGORY_PRACTICAL
)
