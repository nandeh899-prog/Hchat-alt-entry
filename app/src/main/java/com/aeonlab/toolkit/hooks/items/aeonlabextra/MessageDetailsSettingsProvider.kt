package com.aeonlab.toolkit.hooks.items.aeonlabextra

import com.aeonlab.toolkit.ui.FeatureSettingsProvider
import com.aeonlab.toolkit.ui.SimpleFeatureSettingsProvider

class MessageDetailsSettingsProvider : SimpleFeatureSettingsProvider(
    FEATURE_ID,
    "消息显示时间",
    "在聊天消息旁显示时间、类型等详情",
    FeatureSettingsProvider.CATEGORY_PRACTICAL
) {
    companion object {
        const val FEATURE_ID = "aeonlab_message_time"
    }
}
