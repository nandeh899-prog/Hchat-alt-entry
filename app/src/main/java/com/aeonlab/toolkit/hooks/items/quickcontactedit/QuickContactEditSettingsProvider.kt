package com.aeonlab.toolkit.hooks.items.quickcontactedit

import com.aeonlab.toolkit.ui.FeatureSettingsProvider
import com.aeonlab.toolkit.ui.SimpleFeatureSettingsProvider

class QuickContactEditSettingsProvider : SimpleFeatureSettingsProvider(
    QuickContactEditFeature.ID,
    "快捷设置备注和标签",
    "长按好友或群聊会话、好友朋友圈头像快速修改备注和标签",
    FeatureSettingsProvider.CATEGORY_PRACTICAL
)
