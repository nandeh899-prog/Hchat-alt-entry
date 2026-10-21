package com.aeonlab.toolkit.hooks.items.fakelocation

import com.aeonlab.toolkit.ui.FeatureSettingsProvider
import com.aeonlab.toolkit.ui.SimpleFeatureSettingsProvider

class FakeLocationSettingsProvider : SimpleFeatureSettingsProvider(
    FakeLocationFeature.ID,
    "虚拟定位",
    "将微信获取到的位置固定为预设经纬度",
    FeatureSettingsProvider.CATEGORY_PRACTICAL
)
