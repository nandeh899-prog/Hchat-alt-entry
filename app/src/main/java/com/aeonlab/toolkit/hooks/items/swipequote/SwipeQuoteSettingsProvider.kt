package com.aeonlab.toolkit.hooks.items.swipequote

import com.aeonlab.toolkit.ui.FeatureSettingsProvider
import com.aeonlab.toolkit.ui.SimpleFeatureSettingsProvider

class SwipeQuoteSettingsProvider : SimpleFeatureSettingsProvider(
    SwipeQuoteFeature.ID,
    "滑动手势",
    "左滑引用，右滑或长按菜单复读消息",
    FeatureSettingsProvider.CATEGORY_PRACTICAL
)
