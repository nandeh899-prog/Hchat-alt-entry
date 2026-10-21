package com.aeonlab.toolkit.hooks.items.shortvideo

import com.aeonlab.toolkit.ui.FeatureSettingsProvider
import com.aeonlab.toolkit.ui.SimpleFeatureSettingsProvider

class FinderMediaDownloadSettingsProvider : SimpleFeatureSettingsProvider(
    FinderMediaDownloadFeature.ID,
    "视频号媒体下载",
    "在视频号分享菜单增加复制链接和下载入口",
    FeatureSettingsProvider.CATEGORY_PRACTICAL
)
