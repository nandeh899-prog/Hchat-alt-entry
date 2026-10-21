package com.aeonlab.toolkit.hooks.items.patblock

import com.aeonlab.toolkit.ui.FeatureSettingsProvider
import com.aeonlab.toolkit.ui.SimpleFeatureSettingsProvider

class PatBlockSettingsProvider : SimpleFeatureSettingsProvider(
    PatBlockFeature.ID,
    "禁止拍一拍",
    "双击聊天头像时不发送拍一拍",
    FeatureSettingsProvider.CATEGORY_PRACTICAL
)
