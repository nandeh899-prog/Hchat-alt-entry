package com.aeonlab.toolkit.hooks.items.quickmoments

import com.aeonlab.toolkit.ui.FeatureSettingsProvider
import com.aeonlab.toolkit.ui.SimpleFeatureSettingsProvider

class QuickMomentsSettingsProvider : SimpleFeatureSettingsProvider(
    QuickMomentsFeature.ID,
    "快捷查看朋友圈",
    "长按本人、好友或企业微信联系人会话打开个人朋友圈",
    FeatureSettingsProvider.CATEGORY_PRACTICAL
)
