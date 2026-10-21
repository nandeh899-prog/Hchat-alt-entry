package com.aeonlab.toolkit.hooks.items.emojisave

import com.aeonlab.toolkit.ui.FeatureSettingsProvider
import com.aeonlab.toolkit.ui.SimpleFeatureSettingsProvider

class EmojiSaveSettingsProvider : SimpleFeatureSettingsProvider(
    EmojiSaveFeature.ID,
    "保存表情",
    "长按聊天表情后保存原始文件到本地",
    FeatureSettingsProvider.CATEGORY_PRACTICAL
)
