package com.aeonlab.toolkit.hooks.items.messageforward

import com.aeonlab.toolkit.ui.FeatureSettingsProvider
import com.aeonlab.toolkit.ui.SimpleFeatureSettingsProvider

class MessageForwardSettingsProvider : SimpleFeatureSettingsProvider(
    MessageForwardFeature.ID,
    "转发",
    "从消息、收藏和朋友圈菜单使用转发功能",
    FeatureSettingsProvider.CATEGORY_ENHANCE
)
