package com.aeonlab.toolkit.hooks.items.groupadmin

import com.aeonlab.toolkit.ui.FeatureSettingsProvider
import com.aeonlab.toolkit.ui.SimpleFeatureSettingsProvider

class GroupAdminSettingsProvider : SimpleFeatureSettingsProvider(
    GroupAdminFeature.ID,
    "群管理",
    "按群生效的名单、豁免与踢人动作（当前为只读校准版）",
    FeatureSettingsProvider.CATEGORY_PRACTICAL
)
