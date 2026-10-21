package com.aeonlab.toolkit.hooks.items.zombiecheck

import com.aeonlab.toolkit.ui.FeatureSettingsProvider
import com.aeonlab.toolkit.ui.SimpleFeatureSettingsProvider

class ZombieCheckSettingsProvider : SimpleFeatureSettingsProvider(
    ZombieCheckFeature.ID,
    "僵尸粉检测",
    "批量核验好友关系并记录异常联系人",
    FeatureSettingsProvider.CATEGORY_ENHANCE
)
