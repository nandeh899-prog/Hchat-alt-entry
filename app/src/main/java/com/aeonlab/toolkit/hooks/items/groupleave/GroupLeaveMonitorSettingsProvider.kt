package com.aeonlab.toolkit.hooks.items.groupleave

import com.aeonlab.toolkit.ui.FeatureSettingsProvider
import com.aeonlab.toolkit.ui.SimpleFeatureSettingsProvider

class GroupLeaveMonitorSettingsProvider : SimpleFeatureSettingsProvider(
    GroupLeaveMonitorFeature.ID,
    "进退群监控",
    "监听成员进群和退群，支持系统消息和自动回复",
    FeatureSettingsProvider.CATEGORY_PRACTICAL
)
