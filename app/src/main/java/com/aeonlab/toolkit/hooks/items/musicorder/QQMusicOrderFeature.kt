package com.aeonlab.toolkit.hooks.items.musicorder

import com.aeonlab.toolkit.event.Events
import com.aeonlab.toolkit.hooks.core.BaseFeature
import com.aeonlab.toolkit.hooks.core.DexInstallScheduler
import com.aeonlab.toolkit.hooks.core.FeatureContext
import com.aeonlab.toolkit.hooks.items.script.ScriptSendButtonHook

class QQMusicOrderFeature : BaseFeature() {
    private var runtime: QQMusicOrderRuntime? = null

    override fun featureId(): String = ID

    override fun name(): String = "QQ点歌"

    override fun onFeatureInit(context: FeatureContext) {
        registerSettingsProvider(QQMusicOrderSettingsProvider())
    }

    override fun onFeatureInstall(context: FeatureContext) {
        scheduleSendButtonHook(context)
        subscribe(Events.DexReady::class.java) { scheduleSendButtonHook(context) }
        runtime = QQMusicOrderRuntime(context.hostContext(), ::logError).also { musicRuntime ->
            musicRuntime.install { subscription -> trackSubscription(subscription) }
        }
    }

    override fun onFeatureDestroy(context: FeatureContext) {
        runtime?.destroy()
        runtime = null
    }

    private fun scheduleSendButtonHook(context: FeatureContext) {
        DexInstallScheduler.schedule("shared:send_button", "聊天发送按钮", stage = DexInstallScheduler.Stage.WARMUP) {
            ScriptSendButtonHook.install(context)
        }
    }

    companion object {
        const val ID = "qq_music_order"
    }
}
