package com.aeonlab.toolkit.hooks.items.conversationgroup

import com.aeonlab.toolkit.ui.FeatureSettingsProvider
import com.aeonlab.toolkit.ui.SimpleFeatureSettingsProvider

class ConversationGroupSettingsProvider : SimpleFeatureSettingsProvider(
    ConversationGroupFeature.ID,
    "聊天分组",
    "按自定义分组整理聊天，支持多级分类",
    FeatureSettingsProvider.CATEGORY_ENHANCE
)
