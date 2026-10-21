package com.aeonlab.toolkit.hooks.items.fakevoiceduration

import com.aeonlab.toolkit.ui.FeatureSettingsProvider
import com.aeonlab.toolkit.ui.SimpleFeatureSettingsProvider

class FakeVoiceDurationSettingsProvider : SimpleFeatureSettingsProvider(
    FakeVoiceDurationFeature.ID,
    "伪造语音时长",
    "自定义微信显示的语音时长",
    FeatureSettingsProvider.CATEGORY_PRACTICAL
)
