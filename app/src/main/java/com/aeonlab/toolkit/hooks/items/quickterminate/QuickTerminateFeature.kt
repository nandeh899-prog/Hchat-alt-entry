package com.aeonlab.toolkit.hooks.items.quickterminate

import android.content.Context
import android.os.Process
import com.aeonlab.toolkit.hooks.core.BaseFeature
import com.aeonlab.toolkit.hooks.core.FeatureContext
import com.aeonlab.toolkit.preferences.HchatStorage

class QuickTerminateFeature : BaseFeature() {
    override fun featureId(): String = ID

    override fun name(): String = "快捷终止"

    override fun onFeatureInit(context: FeatureContext) {
        registerSettingsProvider(QuickTerminateSettingsProvider())
    }

    override fun onFeatureInstall(context: FeatureContext) = Unit

    companion object {
        const val ID = "quick_terminate"
    }
}

object QuickTerminateRuntime {
    @JvmStatic
    fun isEnabled(context: Context?): Boolean {
        if (context == null) return false
        return HchatStorage.preferences(context, QuickTerminateSettings.PREFS_NAME)
            .getBoolean(QuickTerminateSettings.KEY_ENABLE, QuickTerminateSettings.DEFAULT_ENABLE)
    }

    @JvmStatic
    fun terminateCurrentProcess() {
        Process.killProcess(Process.myPid())
        System.exit(0)
    }
}
