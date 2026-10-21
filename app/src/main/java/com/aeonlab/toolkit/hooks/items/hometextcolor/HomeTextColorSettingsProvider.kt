package com.aeonlab.toolkit.hooks.items.hometextcolor

import com.aeonlab.toolkit.ui.FeatureSettingsProvider
import com.aeonlab.toolkit.ui.SimpleFeatureSettingsProvider

class HomeTextColorSettingsProvider : SimpleFeatureSettingsProvider(
    HomeTextColorFeature.ID,
    "首页文字颜色",
    "自定义微信首页四个页签的标题和副标题颜色",
    FeatureSettingsProvider.CATEGORY_PRACTICAL
)
