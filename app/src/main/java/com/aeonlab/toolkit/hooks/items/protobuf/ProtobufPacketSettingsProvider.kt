package com.aeonlab.toolkit.hooks.items.protobuf

import com.aeonlab.toolkit.ui.FeatureSettingsProvider
import com.aeonlab.toolkit.ui.SimpleFeatureSettingsProvider

class ProtobufPacketSettingsProvider : SimpleFeatureSettingsProvider(
    ProtobufPacketFeature.ID,
    "Protobuf",
    "抓包和自定义发包",
    FeatureSettingsProvider.CATEGORY_ENTERTAINMENT
)
