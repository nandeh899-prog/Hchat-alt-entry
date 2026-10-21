package com.aeonlab.toolkit.hooks.items.messagetextcolor

import com.aeonlab.toolkit.ui.FeatureSettingsProvider
import com.aeonlab.toolkit.ui.SimpleFeatureSettingsProvider

class MessageTextColorSettingsProvider : SimpleFeatureSettingsProvider(
    MessageTextColorFeature.ID,
    "消息文本颜色",
    "自定义聊天文本消息左右侧颜色",
    FeatureSettingsProvider.CATEGORY_PRACTICAL
)
