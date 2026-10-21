package com.aeonlab.toolkit.hooks.items.chattime

import com.aeonlab.toolkit.ui.FeatureSettingsProvider
import com.aeonlab.toolkit.ui.SimpleFeatureSettingsProvider

class ChatTimeStyleSettingsProvider : SimpleFeatureSettingsProvider(
    ChatTimeStyleFeature.ID,
    "会话时间样式",
    "自定义或隐藏聊天记录中的微信时间",
    FeatureSettingsProvider.CATEGORY_PRACTICAL
)
