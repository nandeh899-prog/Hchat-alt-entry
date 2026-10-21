package com.aeonlab.toolkit.hooks.items.roundavatar

import com.aeonlab.toolkit.ui.FeatureSettingsProvider
import com.aeonlab.toolkit.ui.SimpleFeatureSettingsProvider

class RoundAvatarSettingsProvider : SimpleFeatureSettingsProvider(
    RoundAvatarFeature.ID,
    "圆角头像",
    "统一设置微信头像的圆角弧度",
    FeatureSettingsProvider.CATEGORY_PRACTICAL
)
