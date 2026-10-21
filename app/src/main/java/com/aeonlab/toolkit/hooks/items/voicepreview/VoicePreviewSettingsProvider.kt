package com.aeonlab.toolkit.hooks.items.voicepreview

import com.aeonlab.toolkit.ui.FeatureSettingsProvider
import com.aeonlab.toolkit.ui.SimpleFeatureSettingsProvider

class VoicePreviewSettingsProvider : SimpleFeatureSettingsProvider(
    VoicePreviewFeature.ID,
    "语音消息预览",
    "长按聊天语音查看时长并播放",
    FeatureSettingsProvider.CATEGORY_PRACTICAL
)
