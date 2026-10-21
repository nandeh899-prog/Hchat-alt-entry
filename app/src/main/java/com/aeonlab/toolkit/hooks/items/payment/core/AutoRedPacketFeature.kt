package com.aeonlab.toolkit.hooks.items.payment.core

import com.aeonlab.toolkit.event.Events
import com.aeonlab.toolkit.hooks.core.BaseFeature
import com.aeonlab.toolkit.hooks.core.DexInstallScheduler
import com.aeonlab.toolkit.hooks.core.FeatureContext

/**
 * 自动抢红包功能入口。
 */
class AutoRedPacketFeature : BaseFeature() {
    private var hooker: RedPacketHooker? = null

    override fun featureId(): String = ID

    override fun name(): String = "自动抢红包"

    override fun onFeatureInit(context: FeatureContext) {
        registerSettingsProvider(RedPacketSettingsProvider())

        subscribe(Events.RedPacketGrabbed::class.java) {
            // 预留给跨功能通知、统计等逻辑。
        }
    }

    @Throws(Throwable::class)
    override fun onFeatureInstall(context: FeatureContext) {
        scheduleInstall(context)
        subscribe(Events.DexReady::class.java) {
            scheduleInstall(context)
        }
    }

    override fun onFeatureDestroy(context: FeatureContext) {
        hooker?.dispose()
        hooker = null
    }

    private fun scheduleInstall(context: FeatureContext) {
        DexInstallScheduler.schedule(ID, name(), stage = DexInstallScheduler.Stage.WARMUP) {
            try {
                val localHooker = hooker ?: RedPacketHooker(
                    context.hostContext(),
                    context.hostClassLoader(),
                    context.dexFinder()
                ).also { hooker = it }
                for (subscription in localHooker.hookAll()) {
                    trackSubscription(subscription)
                }
                localHooker.isDexReadyEnough()
            } catch (e: Throwable) {
                RedPacketLogger.error("自动抢红包安装失败", e)
                false
            }
        }
    }

    companion object {
        const val ID = "auto_redpacket"
    }
}
