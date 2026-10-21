package com.aeonlab.toolkit.hooks.items.messageblock

import com.aeonlab.toolkit.ui.FeatureSettingsProvider
import com.aeonlab.toolkit.ui.SimpleFeatureSettingsProvider

class MessageBlockSettingsProvider : SimpleFeatureSettingsProvider(
    MessageBlockFeature.ID,
    "屏蔽消息",
    "拦截指定成员或会话的消息，不显示也不弹通知",
    FeatureSettingsProvider.CATEGORY_PRACTICAL
)
