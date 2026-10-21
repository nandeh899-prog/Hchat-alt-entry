package com.aeonlab.toolkit.hooks.items.atallnotify

import com.aeonlab.toolkit.ui.FeatureSettingsProvider
import com.aeonlab.toolkit.ui.SimpleFeatureSettingsProvider

class AtAllNotificationBlockSettingsProvider : SimpleFeatureSettingsProvider(
    AtAllNotificationBlockFeature.ID,
    "屏蔽艾特所有人",
    "按选择的群聊拦截艾特所有人通知",
    FeatureSettingsProvider.CATEGORY_PRACTICAL
)
