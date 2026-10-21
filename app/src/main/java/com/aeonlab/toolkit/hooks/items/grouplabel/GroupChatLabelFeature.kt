package com.aeonlab.toolkit.hooks.items.grouplabel

import com.aeonlab.toolkit.hooks.core.BaseFeature
import com.aeonlab.toolkit.hooks.core.FeatureContext

class GroupChatLabelFeature : BaseFeature() {
    override fun featureId(): String = ID

    override fun name(): String = "群聊标签"

    override fun onFeatureInit(context: FeatureContext) {
        registerSettingsProvider(GroupChatLabelSettingsProvider())
    }

    override fun onFeatureInstall(context: FeatureContext) = Unit

    companion object {
        const val ID = "group_chat_label"
    }
}
