package com.aeonlab.toolkit.hooks.items.groupadmin

import com.aeonlab.toolkit.event.Events
import com.aeonlab.toolkit.hooks.api.core.WeChatApis
import com.aeonlab.toolkit.hooks.core.BaseFeature
import com.aeonlab.toolkit.hooks.core.FeatureContext
import com.aeonlab.toolkit.utils.HLog

/**
 * 群管理助手功能壳。
 *
 * 第 4 步起装上消息入口：只在「全局开关 + 本群纳入管理」双重前提下处理消息，
 * 未纳管的群静默丢弃；本轮真正会执行的规则只有 `long`（超长消息）一条，
 * 外加规格 2.1 第 14 步的黑名单优先踢人。踢人链路（先发告别语 → 移出群 → 拉黑）
 * 本轮才接线，其余规则、指令、进群验证仍未接入。
 */
class GroupAdminFeature : BaseFeature() {

    override fun featureId(): String = ID

    override fun name(): String = "群管理助手"

    override fun onFeatureInit(context: FeatureContext) {
        registerSettingsProvider(GroupAdminSettingsProvider())
    }

    override fun onFeatureInstall(context: FeatureContext) {
        subscribe(Events.DexReady::class.java) {
            val observe = runCatching { WeChatApis.messageObserve() }.getOrNull()
            if (observe == null || !observe.isAvailable()) {
                HLog.e(TAG + " 消息观察 API 未就绪：规则不会生效，面板仍可只读试跑")
                return@subscribe
            }
            runCatching { observe.install() }
                .onFailure { HLog.e(TAG + " 消息监听安装失败", it) }
            trackSubscription(observe.subscribe { message ->
                GroupAdminMessageRuntime.handle(context.hostContext(), message)
            })
            GroupAdminMessageRuntime.markInstalled()
            val memberReady = runCatching { GroupAdminMemberRuntime.install(context) }
                .onFailure { HLog.e(TAG + " 群成员事件安装失败", it) }
                .getOrDefault(false)
            HLog.i(
                TAG + " 消息入口已接入：已接入规则=" +
                    GroupAdminRuleCatalog.IMPLEMENTED.joinToString("、") +
                    "（另有规格 2.1 第 14 步的黑名单优先踢人）；群成员事件入口=" +
                    (if (memberReady) "已接入（nonadmin / leavebl）" else "未接入")
            )
        }
    }

    override fun onFeatureDestroy(context: FeatureContext) {
        GroupAdminMessageRuntime.markUninstalled()
        HLog.i(TAG + " 消息入口已随功能卸载")
    }

    companion object {
        const val ID = "group_admin"
        private const val TAG = "[AeonLab:GroupAdmin]"
    }
}
