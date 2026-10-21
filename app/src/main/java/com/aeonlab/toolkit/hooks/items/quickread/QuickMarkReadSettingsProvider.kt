package com.aeonlab.toolkit.hooks.items.quickread

import com.aeonlab.toolkit.ui.FeatureSettingsProvider
import com.aeonlab.toolkit.ui.SimpleFeatureSettingsProvider

class QuickMarkReadSettingsProvider : SimpleFeatureSettingsProvider(
    QuickMarkReadFeature.ID,
    "快捷已读",
    "拖拽未读角标或加号菜单一键已读",
    FeatureSettingsProvider.CATEGORY_PRACTICAL
)
