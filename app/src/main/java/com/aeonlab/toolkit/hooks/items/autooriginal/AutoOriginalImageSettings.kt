package com.aeonlab.toolkit.hooks.items.autooriginal

import android.content.Context
import com.aeonlab.toolkit.preferences.AeonLabStorage

object AutoOriginalImageSettings {
    const val PREFS_NAME = "AeonLab_auto_original_image_config"
    const val KEY_ENABLE = "auto_original_image_enable"
    const val DEFAULT_ENABLE = false

    fun preferences(context: Context) = AeonLabStorage.preferences(context, PREFS_NAME)
}
