package com.aeonlab.toolkit.hooks.items.aeonlabextra

import com.aeonlab.toolkit.ui.FeatureSettingsProvider
import com.aeonlab.toolkit.ui.SimpleFeatureSettingsProvider

class RedPacketDetailsSettingsProvider : SimpleFeatureSettingsProvider(
    FEATURE_ID,
    "红包显示详情",
    "红包详情页显示金额、个数和领取时间",
    FeatureSettingsProvider.CATEGORY_PRACTICAL
) {
    companion object {
        const val FEATURE_ID = "aeonlab_red_packet_details"
    }
}
