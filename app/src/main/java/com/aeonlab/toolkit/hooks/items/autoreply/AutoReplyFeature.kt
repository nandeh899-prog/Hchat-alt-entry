package com.aeonlab.toolkit.hooks.items.autoreply

import com.aeonlab.toolkit.event.Events
import com.aeonlab.toolkit.hooks.api.core.WeChatApis
import com.aeonlab.toolkit.hooks.core.BaseFeature
import com.aeonlab.toolkit.hooks.core.FeatureContext
import com.aeonlab.toolkit.hooks.items.script.ScriptNewFriendHook
import com.aeonlab.toolkit.utils.HLog

class AutoReplyFeature : BaseFeature() {
    override fun featureId(): String = ID

    override fun name(): String = "自动回复"

    override fun onFeatureInit(context: FeatureContext) {
        registerSettingsProvider(AutoReplySettingsProvider())
    }

    override fun onFeatureInstall(context: FeatureContext) {
        subscribe(Events.DexReady::class.java) {
            val observe = WeChatApis.message().observe()
            if (observe == null) {
                HLog.e("$TAG 消息观察 API 未就绪")
            } else {
                trackSubscription(observe.subscribe { message ->
                    AutoReplyRuntime.handleMessage(context.hostContext(), message)
                })
            }
            ScriptNewFriendHook.install(context)
            trackSubscription(ScriptNewFriendHook.subscribe { event ->
                AutoReplyRuntime.handleNewFriend(context.hostContext(), event.wxid, event.ticket, event.scene)
            })
        }
    }

    companion object {
        const val ID = "auto_reply"
        private const val TAG = "[AeonLab:AutoReply]"
    }
}
