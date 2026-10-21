package com.aeonlab.toolkit.hooks.items.conversationtabs

import com.aeonlab.toolkit.hooks.core.BaseFeature
import com.aeonlab.toolkit.hooks.core.FeatureContext

class ConversationTabsFeature : BaseFeature() {
    override fun featureId() = ID
    override fun name() = "标签分组"
    override fun onFeatureInit(context: FeatureContext) {
        registerSettingsProvider(ConversationTabsSettingsProvider())
    }
    override fun onFeatureInstall(context: FeatureContext) { ConversationTabsRuntime.initialize(context) }
    override fun onFeatureDestroy(context: FeatureContext) { ConversationTabsRuntime.destroy(context) }
    companion object {
        const val ID = "conversation_tabs"

        @JvmStatic
        fun installEarly(classLoader: ClassLoader) = ConversationTabsRuntime.installUiHooks(classLoader)
    }
}
