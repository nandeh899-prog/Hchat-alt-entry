package com.aeonlab.toolkit.hooks.items.scheduledtask

import com.aeonlab.toolkit.ui.FeatureSettingsProvider
import com.aeonlab.toolkit.ui.SimpleFeatureSettingsProvider

class ScheduledTaskSettingsProvider : SimpleFeatureSettingsProvider(
    ScheduledTaskFeature.ID,
    "定时任务",
    "按计划时间发送聊天消息或发布朋友圈",
    FeatureSettingsProvider.CATEGORY_ENHANCE
)
