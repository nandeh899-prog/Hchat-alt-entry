package com.aeonlab.toolkit.hooks.items.moments

import com.aeonlab.toolkit.ui.FeatureSettingsProvider
import com.aeonlab.toolkit.ui.SimpleFeatureSettingsProvider

class MomentsUploadTailSettingsProvider : SimpleFeatureSettingsProvider(
    MomentsUploadTailFeature.ID,
    "朋友圈上传尾巴",
    "给发布的朋友圈附带 SDK ID 和 SDK 名称",
    FeatureSettingsProvider.CATEGORY_PRACTICAL
)
