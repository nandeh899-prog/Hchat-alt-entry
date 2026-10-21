package com.aeonlab.toolkit.ui

import android.content.Context
import com.aeonlab.toolkit.ui.miuix.MiuixSettingsPage

object SettingsUI {
    @JvmStatic
    fun show(context: Context) {
        MiuixSettingsPage.show(context)
    }

    @JvmStatic
    fun showScriptPluginAgent(context: Context) {
        MiuixSettingsPage.showScriptPluginAgent(context)
    }

    @JvmStatic
    fun showFeature(context: Context, featureId: String): Boolean {
        return MiuixSettingsPage.showFeature(context, featureId)
    }
}
