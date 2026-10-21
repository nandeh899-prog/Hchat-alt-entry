package com.aeonlab.toolkit.hooks.items.membertitle

import com.aeonlab.toolkit.ui.FeatureSettingsProvider
import com.aeonlab.toolkit.ui.SimpleFeatureSettingsProvider

class MemberTitleSettingsProvider : SimpleFeatureSettingsProvider(
    MemberTitleFeature.ID,
    "群员头衔",
    "在群聊昵称左侧显示群主、管理员、群员或自定义头衔",
    FeatureSettingsProvider.CATEGORY_ENTERTAINMENT
)
