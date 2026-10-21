package com.aeonlab.toolkit.hooks.items.script

import com.aeonlab.toolkit.ui.FeatureSettingsProvider
import com.aeonlab.toolkit.ui.SimpleFeatureSettingsProvider

class ScriptPluginSettingsProvider : SimpleFeatureSettingsProvider(
    ScriptPluginFeature.ID,
    "插件总开关",
    "启动时自动加载已启用插件",
    FeatureSettingsProvider.CATEGORY_ENHANCE
)
