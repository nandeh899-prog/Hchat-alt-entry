package com.aeonlab.toolkit.hooks.items.theme

import com.aeonlab.toolkit.ui.FeatureSettingsProvider
import com.aeonlab.toolkit.ui.SimpleFeatureSettingsProvider

class ThemeSettingsProvider : SimpleFeatureSettingsProvider(
    ThemeFeature.ID,
    "美化主题",
    "底栏、头像、标题栏与页面背景，支持主题导出导入",
    FeatureSettingsProvider.CATEGORY_PRACTICAL
)
