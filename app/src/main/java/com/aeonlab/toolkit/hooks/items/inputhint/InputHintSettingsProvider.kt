package com.aeonlab.toolkit.hooks.items.inputhint

import com.aeonlab.toolkit.ui.FeatureSettingsProvider
import com.aeonlab.toolkit.ui.SimpleFeatureSettingsProvider

class InputHintSettingsProvider : SimpleFeatureSettingsProvider(
    InputHintFeature.ID,
    "输入框提示",
    "自定义聊天输入框没有文字时显示的提示内容",
    FeatureSettingsProvider.CATEGORY_PRACTICAL
)
