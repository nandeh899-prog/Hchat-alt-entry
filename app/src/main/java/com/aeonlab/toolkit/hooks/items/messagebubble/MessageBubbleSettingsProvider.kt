package com.aeonlab.toolkit.hooks.items.messagebubble

import com.aeonlab.toolkit.ui.FeatureSettingsProvider
import com.aeonlab.toolkit.ui.SimpleFeatureSettingsProvider

class MessageBubbleSettingsProvider : SimpleFeatureSettingsProvider(
    MessageBubbleFeature.ID,
    "消息气泡",
    "分别替换聊天左右侧气泡并适配深色模式",
    FeatureSettingsProvider.CATEGORY_PRACTICAL
)
