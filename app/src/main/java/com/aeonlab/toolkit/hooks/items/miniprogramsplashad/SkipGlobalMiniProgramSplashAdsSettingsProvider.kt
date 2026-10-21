package com.aeonlab.toolkit.hooks.items.miniprogramsplashad

import com.aeonlab.toolkit.ui.FeatureSettingsProvider
import com.aeonlab.toolkit.ui.SimpleFeatureSettingsProvider

class SkipGlobalMiniProgramSplashAdsSettingsProvider : SimpleFeatureSettingsProvider(
    SkipGlobalMiniProgramSplashAdsFeature.ID,
    "跳过全局小程序开屏广告",
    "阻止所有小程序展示启动开屏广告",
    FeatureSettingsProvider.CATEGORY_PRACTICAL
)
