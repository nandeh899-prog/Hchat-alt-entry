package com.aeonlab.toolkit.hooks.items.moments

import com.aeonlab.toolkit.ui.FeatureSettingsProvider
import com.aeonlab.toolkit.ui.SimpleFeatureSettingsProvider

class MomentsAutoCommentSettingsProvider : SimpleFeatureSettingsProvider(
    MomentsAutoCommentFeature.ID,
    "朋友圈自动评论",
    "按好友、内容和时间规则自动评论",
    FeatureSettingsProvider.CATEGORY_PRACTICAL
)
