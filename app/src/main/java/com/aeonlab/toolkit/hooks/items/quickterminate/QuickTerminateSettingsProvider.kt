package com.aeonlab.toolkit.hooks.items.quickterminate

import com.aeonlab.toolkit.ui.FeatureSettingsProvider
import com.aeonlab.toolkit.ui.SimpleFeatureSettingsProvider

class QuickTerminateSettingsProvider : SimpleFeatureSettingsProvider(
    QuickTerminateFeature.ID,
    "快捷终止",
    "从微信右上角加号菜单快速结束微信进程",
    FeatureSettingsProvider.CATEGORY_PRACTICAL
)
