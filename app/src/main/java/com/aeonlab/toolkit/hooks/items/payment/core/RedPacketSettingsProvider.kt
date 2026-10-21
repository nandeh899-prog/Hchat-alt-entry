package com.aeonlab.toolkit.hooks.items.payment.core

import com.aeonlab.toolkit.ui.FeatureSettingsProvider

class RedPacketSettingsProvider : FeatureSettingsProvider {
    override fun featureId(): String = AutoRedPacketFeature.ID

    override fun title(): String = "自动抢红包"

    override fun subtitle(): String = "自动抢红包"

    override fun category(): String = FeatureSettingsProvider.CATEGORY_PRACTICAL
}
