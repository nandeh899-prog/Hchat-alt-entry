package com.aeonlab.toolkit.hooks.items.autovieworiginal

import com.aeonlab.toolkit.ui.FeatureSettingsProvider
import com.aeonlab.toolkit.ui.SimpleFeatureSettingsProvider

class AutoViewOriginalSettingsProvider : SimpleFeatureSettingsProvider(
    AutoViewOriginalFeature.ID,
    "自动查看原图",
    "打开聊天图片或视频时自动查看原图或原视频",
    FeatureSettingsProvider.CATEGORY_PRACTICAL
)
