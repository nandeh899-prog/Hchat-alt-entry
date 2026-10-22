package com.aeonlab.toolkit.hooks.items.atallnotify

import com.aeonlab.toolkit.event.Events
import com.aeonlab.toolkit.hooks.api.core.WeChatApis
import com.aeonlab.toolkit.hooks.core.BaseFeature
import com.aeonlab.toolkit.hooks.core.FeatureContext
import com.aeonlab.toolkit.hooks.items.customnotify.CustomNotificationRuntime
import com.aeonlab.toolkit.utils.HLog

class AtAllNotificationBlockFeature : BaseFeature() {
    override fun featureId(): String = ID

    override fun name(): String = "屏蔽艾特所有人"

    override fun onFeatureInit(context: FeatureContext) {
        registerSettingsProvider(AtAllNotificationBlockSettingsProvider())
    }

    override fun onFeatureInstall(context: FeatureContext) {
        CustomNotificationRuntime.install(context)
        subscribe(Events.DexReady::class.java) {
            val observe = WeChatApis.message().observe()
            if (observe == null) {
                HLog.e("$TAG 消息观察 API 未就绪")
                return@subscribe
            }
            trackSubscription(observe.subscribe { message ->
                AtAllNotificationBlockRuntime.record(context.hostContext(), message)
            })
        }
    }

    override fun onFeatureDestroy(context: FeatureContext) {
        AtAllNotificationBlockRuntime.clear()
    }

    companion object {
        const val ID = "block_at_all_notification"
        private const val TAG = "[AeonLab:BlockAtAllNotification]"
    }
}
