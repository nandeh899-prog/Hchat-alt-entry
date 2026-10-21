package com.aeonlab.toolkit.hooks.items.musicorder

import com.aeonlab.toolkit.ui.FeatureSettingsProvider
import com.aeonlab.toolkit.ui.SimpleFeatureSettingsProvider

class QQMusicOrderSettingsProvider : SimpleFeatureSettingsProvider(
    QQMusicOrderFeature.ID,
    "QQ点歌",
    "搜索 QQ 音乐并发送音乐卡片或歌曲语音，可同时发送",
    FeatureSettingsProvider.CATEGORY_ENTERTAINMENT
)
