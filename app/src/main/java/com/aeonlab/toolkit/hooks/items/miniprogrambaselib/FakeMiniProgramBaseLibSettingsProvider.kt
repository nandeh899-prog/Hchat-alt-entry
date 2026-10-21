package com.aeonlab.toolkit.hooks.items.miniprogrambaselib

import com.aeonlab.toolkit.ui.FeatureSettingsProvider
import com.aeonlab.toolkit.ui.SimpleFeatureSettingsProvider

class FakeMiniProgramBaseLibSettingsProvider : SimpleFeatureSettingsProvider(
    FakeMiniProgramBaseLibFeature.ID,
    "兼容低版本小程序",
    "伪装启动基础库版本并阻止官方升级页",
    FeatureSettingsProvider.CATEGORY_PRACTICAL
)
