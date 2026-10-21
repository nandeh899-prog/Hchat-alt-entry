package com.aeonlab.toolkit.hooks.items.messageaffix

import com.aeonlab.toolkit.ui.FeatureSettingsProvider
import com.aeonlab.toolkit.ui.SimpleFeatureSettingsProvider

class MessageAffixSettingsProvider : SimpleFeatureSettingsProvider(
    MessageAffixFeature.ID,
    "发送文本格式",
    "自定义聊天文字消息的发送格式",
    FeatureSettingsProvider.CATEGORY_PRACTICAL
)
