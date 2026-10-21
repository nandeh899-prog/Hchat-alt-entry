package com.aeonlab.toolkit.hooks.items.hideavatar

import com.aeonlab.toolkit.ui.FeatureSettingsProvider
import com.aeonlab.toolkit.ui.SimpleFeatureSettingsProvider

class HideChatAvatarSettingsProvider : SimpleFeatureSettingsProvider(
    HideChatAvatarFeature.ID,
    "隐藏头像",
    "分别隐藏聊天中自己或对方的头像",
    FeatureSettingsProvider.CATEGORY_PRACTICAL
)
