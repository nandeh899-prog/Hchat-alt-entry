package com.aeonlab.toolkit.hooks.items.autooriginal

import com.aeonlab.toolkit.ui.FeatureSettingsProvider
import com.aeonlab.toolkit.ui.SimpleFeatureSettingsProvider

class AutoOriginalImageSettingsProvider : SimpleFeatureSettingsProvider(
    AutoOriginalImageFeature.ID,
    "自动勾选原图",
    "发送聊天图片时自动勾选原图",
    FeatureSettingsProvider.CATEGORY_PRACTICAL
)
