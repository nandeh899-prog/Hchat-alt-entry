package com.aeonlab.toolkit.hooks.items.gameemoji

import com.aeonlab.toolkit.ui.FeatureSettingsProvider
import com.aeonlab.toolkit.ui.SimpleFeatureSettingsProvider

class GameEmojiSettingsProvider : SimpleFeatureSettingsProvider(
    GameEmojiFeature.ID,
    "指定骰子猜拳",
    "固定骰子点数和猜拳结果，或在发送时选择",
    FeatureSettingsProvider.CATEGORY_PRACTICAL
)
