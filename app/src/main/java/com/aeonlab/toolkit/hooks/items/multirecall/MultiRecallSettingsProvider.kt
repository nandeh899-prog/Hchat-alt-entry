package com.aeonlab.toolkit.hooks.items.multirecall

import com.aeonlab.toolkit.ui.FeatureSettingsProvider
import com.aeonlab.toolkit.ui.SimpleFeatureSettingsProvider

class MultiRecallSettingsProvider : SimpleFeatureSettingsProvider(
    MultiRecallFeature.ID,
    "多选撤回",
    "在多选分享菜单中批量撤回自己发送的消息",
    FeatureSettingsProvider.CATEGORY_PRACTICAL
)
