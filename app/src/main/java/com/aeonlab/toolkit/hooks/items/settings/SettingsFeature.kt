package com.aeonlab.toolkit.hooks.items.settings

import com.aeonlab.toolkit.crash.CrashReportSettingsProvider
import com.aeonlab.toolkit.dexkit.SettingsDexFinder
import com.aeonlab.toolkit.hooks.api.ui.SettingsInjector
import com.aeonlab.toolkit.hooks.core.DexInstallScheduler
import com.aeonlab.toolkit.hooks.core.Feature
import com.aeonlab.toolkit.hooks.core.FeatureContext
import com.aeonlab.toolkit.hooks.items.quickread.QuickMarkReadRuntime
import com.aeonlab.toolkit.hooks.items.quickterminate.QuickTerminateRuntime
import com.aeonlab.toolkit.ui.FeatureSettingsProvider
import com.aeonlab.toolkit.utils.HLog

/**
 * 微信内设置入口注入功能。
 */
class SettingsFeature : Feature {
    override fun featureId(): String = ID

    override fun name(): String = "设置入口"

    @Throws(Throwable::class)
    override fun onInit(context: FeatureContext) {
        context.uiRegistry().registerProvider(SettingsEntryProvider())
        context.uiRegistry().registerProvider(PluginAgentEntryProvider())
        context.uiRegistry().registerProvider(CrashReportSettingsProvider())
    }

    @Throws(Throwable::class)
    override fun install(context: FeatureContext) {
        DexInstallScheduler.runDexKitTask {
            installSettingsHook(context)
        }
    }

    private fun installSettingsHook(context: FeatureContext) {
        try {
            val settingsDexFinder = SettingsDexFinder(
                context.dexKitBridge(),
                context.hostClassLoader(),
                context.hostContext()
            )
            val entrySettings = SettingsEntrySettings(context.hostContext())
            settingsDexFinder.resolveAll(
                entrySettings.plusMenuEnabled() ||
                    entrySettings.pluginAgentPlusMenuEnabled() ||
                    QuickMarkReadRuntime.isPlusMenuEnabled(context.hostContext()) ||
                    QuickTerminateRuntime.isEnabled(context.hostContext())
            )

            val injector = SettingsInjector(
                context.hostContext(),
                context.hostClassLoader(),
                settingsDexFinder,
                entrySettings
            )
            injector.hookSettings()
        } catch (e: Throwable) {
            HLog.e("[AeonLab:Settings] 设置入口安装失败", e)
        }
    }

    companion object {
        const val ID = "settings"
    }
}

class SettingsEntryProvider : FeatureSettingsProvider {
    override fun featureId(): String = SettingsFeature.ID

    override fun title(): String = "入口设置"

    override fun subtitle(): String = "控制加号菜单和长按加号入口"

    override fun category(): String = FeatureSettingsProvider.CATEGORY_PRACTICAL
}

class PluginAgentEntryProvider : FeatureSettingsProvider {
    override fun featureId(): String = ID

    override fun title(): String = "插件 Agent 入口"

    override fun subtitle(): String = "控制右上角加号菜单中的 Agent 入口"

    override fun category(): String = FeatureSettingsProvider.CATEGORY_PRACTICAL

    companion object {
        const val ID = "plugin_agent_entry"
    }
}
