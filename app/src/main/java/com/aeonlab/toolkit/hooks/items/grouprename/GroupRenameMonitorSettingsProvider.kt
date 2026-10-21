package com.aeonlab.toolkit.hooks.items.grouprename

import com.aeonlab.toolkit.ui.FeatureSettingsProvider
import com.aeonlab.toolkit.ui.SimpleFeatureSettingsProvider

class GroupRenameMonitorSettingsProvider : SimpleFeatureSettingsProvider(
    GroupRenameMonitorFeature.ID,
    "改名监控",
    "监控群成员修改群内昵称，支持系统消息、文本和卡片提醒",
    FeatureSettingsProvider.CATEGORY_PRACTICAL
)
