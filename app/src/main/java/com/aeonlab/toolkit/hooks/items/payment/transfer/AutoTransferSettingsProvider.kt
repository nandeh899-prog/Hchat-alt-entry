package com.aeonlab.toolkit.hooks.items.payment.transfer

import com.aeonlab.toolkit.ui.FeatureSettingsProvider
import com.aeonlab.toolkit.ui.SimpleFeatureSettingsProvider

class AutoTransferSettingsProvider : SimpleFeatureSettingsProvider(
    AutoTransferFeature.ID,
    "自动收款",
    "自动领取微信转账",
    FeatureSettingsProvider.CATEGORY_PRACTICAL
)
