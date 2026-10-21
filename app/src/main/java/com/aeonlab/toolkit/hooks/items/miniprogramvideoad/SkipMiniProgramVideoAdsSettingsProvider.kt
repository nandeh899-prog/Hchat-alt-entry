package com.aeonlab.toolkit.hooks.items.miniprogramvideoad

import com.aeonlab.toolkit.ui.FeatureSettingsProvider
import com.aeonlab.toolkit.ui.SimpleFeatureSettingsProvider

class SkipMiniProgramVideoAdsSettingsProvider : SimpleFeatureSettingsProvider(
    SkipMiniProgramVideoAdsFeature.ID,
    "跳过小程序视频广告",
    "自动跳过小程序视频广告",
    FeatureSettingsProvider.CATEGORY_PRACTICAL
)
