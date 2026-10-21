package com.aeonlab.toolkit.hooks.items.grouprename

import com.aeonlab.toolkit.event.Events
import com.aeonlab.toolkit.hooks.core.BaseFeature
import com.aeonlab.toolkit.hooks.core.FeatureContext
import com.aeonlab.toolkit.hooks.items.groupleave.GroupLeaveMonitorHighlighter

class GroupRenameMonitorFeature : BaseFeature() {
    private var runtime: GroupRenameMonitorRuntime? = null

    override fun featureId(): String = ID

    override fun name(): String = "改名监控"

    override fun onFeatureInit(context: FeatureContext) {
        registerSettingsProvider(GroupRenameMonitorSettingsProvider())
    }

    override fun onFeatureInstall(context: FeatureContext) {
        GroupLeaveMonitorHighlighter.installProfileLinkClick(context, ::logError)
        val monitor = GroupRenameMonitorRuntime(context, ::logError)
        runtime = monitor
        monitor.install { subscription ->
            trackSubscription(subscription)
        }
        subscribe(Events.DexReady::class.java) {
            monitor.preloadSnapshots()
        }
    }

    companion object {
        const val ID = "group_rename_monitor"
    }
}
