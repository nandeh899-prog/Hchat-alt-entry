package com.aeonlab.toolkit.hooks.items.groupnicknamecolor

import com.aeonlab.toolkit.ui.FeatureSettingsProvider
import com.aeonlab.toolkit.ui.SimpleFeatureSettingsProvider

class GroupNicknameColorSettingsProvider : SimpleFeatureSettingsProvider(
    GroupNicknameColorSettings.FEATURE_ID,
    "群昵称自定义颜色",
    "自定义群聊成员昵称的颜色和粗细",
    FeatureSettingsProvider.CATEGORY_ENTERTAINMENT
)
