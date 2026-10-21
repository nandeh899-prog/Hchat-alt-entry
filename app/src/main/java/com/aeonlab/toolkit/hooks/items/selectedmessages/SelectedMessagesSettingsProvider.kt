package com.aeonlab.toolkit.hooks.items.selectedmessages

import com.aeonlab.toolkit.ui.FeatureSettingsProvider
import com.aeonlab.toolkit.ui.SimpleFeatureSettingsProvider

class SelectedMessagesSettingsProvider : SimpleFeatureSettingsProvider(
    SelectedMessagesFeature.ID,
    "群发助手",
    "从多选消息菜单群发或定时转发聊天记录",
    FeatureSettingsProvider.CATEGORY_ENHANCE
)
