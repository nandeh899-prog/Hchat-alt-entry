package com.aeonlab.toolkit.hooks.items.groupadmin

import android.content.Context
import com.aeonlab.toolkit.hooks.api.core.WeChatApis

/**
 * 写操作集中在这一层（对齐规格 5.1 的 GroupAdminActions）。
 *
 * 第 4 步起 [kick] / [warn] 已被消息入口（GroupAdminMessageRuntime）真正调用：
 * 只在「全局开关开 + 本群纳入管理开 + 发送者未被豁免 + 命中规则或黑名单」时执行；
 * 面板上的「只读试跑」入口仍然只调 [dryRun]，不发送、不踢人、不写配置。
 */
object GroupAdminActions {

    /**
     * 自己发出的文本带的零宽标记（本模块的 `GM_NOTICE_MARK` 常量）：
     * 回声过滤靠它把自己发的告别语/警告挡掉，避免「移出群聊」这类词被自己再触发一次。
     */
    const val NOTICE_MARK = "\u200b"

    private fun marked(text: String): String =
        if (text.contains(NOTICE_MARK)) text else text + NOTICE_MARK

    data class KickPlan(
        val room: String,
        val wxId: String,
        val name: String,
        val notify: Boolean,
        val farewell: String,
        val reason: String,
        val addBlack: Boolean,
        val unbanHours: Int,
        val rule: String?
    ) {
        fun messageText(): String =
            if (reason.isBlank()) farewell else farewell + " " + reason

        fun blackText(): String = when {
            !addBlack -> "不写黑名单"
            unbanHours <= 0 -> "写黑名单：永久"
            else -> "写黑名单：" + unbanHours + " 小时"
        }
    }

    data class KickResult(
        val sent: Boolean,
        val kicked: Boolean,
        val blacked: Boolean,
        val steps: List<String>
    )

    /**
     * 组装一次踢人计划：告别语与拉黑时长取自本群配置，reason 空则用该规则的理由，
     * 规则也没配就用「违反群规」。
     */
    fun plan(
        context: Context?,
        room: String,
        wxId: String,
        name: String,
        reason: String,
        rule: String?,
        addBlack: Boolean,
        hours: Int? = null
    ): KickPlan {
        val cfg = GroupAdminStore.group(context, room)
        val ruleCfg = rule?.let { cfg.rules[it] }
        val resolvedHours = hours ?: ruleCfg?.unbanHours ?: 0
        val text = reason.ifBlank { ruleCfg?.reason.orEmpty() }.ifBlank { "违反群规" }
        return KickPlan(
            room = room,
            wxId = wxId,
            name = name,
            notify = cfg.notify,
            farewell = cfg.farewell.ifBlank { GroupAdminSettings.defaultFarewell(context) },
            reason = text,
            addBlack = addBlack,
            unbanHours = resolvedHours,
            rule = rule
        )
    }

    fun send(room: String, text: String): Boolean {
        if (room.isBlank() || text.isBlank()) return false
        return try {
            WeChatApis.messages()?.sendText(room, text) == true
        } catch (t: Throwable) {
            false
        }
    }

    fun sendAt(room: String, wxId: String, text: String): Boolean {
        if (room.isBlank() || text.isBlank()) return false
        return try {
            val api = WeChatApis.messages() ?: return false
            if (wxId.isBlank()) api.sendText(room, text) else api.sendAt(room, wxId, text)
        } catch (t: Throwable) {
            false
        }
    }

    /** 发警告：带零宽标记，避免自己的警告文本被回声过滤漏掉后再触发规则。 */
    fun warn(room: String, wxId: String, text: String): Boolean = sendAt(room, wxId, marked(text))

    /**
     * 真执行。顺序照规格 2.3 的 `gmKick`：**先发告别语 → 再 delChatroomMember → 最后按 addBlack 拉黑**。
     * 顺序约束的原因写死在代码里：踢完成员已不在成员列表，@ 目标解析不到，提示会退化成没有 @ 的纯文本。
     */
    fun kick(context: Context?, plan: KickPlan): KickResult {
        val steps = ArrayList<String>()
        var sent = false
        if (plan.notify) {
            sent = sendAt(plan.room, plan.wxId, marked(plan.messageText()))
            steps.add("1) 先发告别语（" + (if (sent) "成功" else "失败") + "）：" + plan.messageText())
        } else {
            steps.add("1) 跳过告别语：本群「动作后在群里提示」是关闭状态")
        }
        val kicked = try {
            WeChatApis.contact().chatrooms()?.delChatroomMember(plan.room, plan.wxId) == true
        } catch (t: Throwable) {
            false
        }
        steps.add("2) delChatroomMember(" + plan.name + " / " + plan.wxId + ")：" + (if (kicked) "已移出" else "未生效"))
        var blacked = false
        if (plan.addBlack && plan.wxId.isNotBlank()) {
            val now = System.currentTimeMillis()
            GroupAdminStore.update(context, plan.room) { cfg ->
                GroupAdminStore.addBlack(cfg, plan.wxId, plan.name, plan.unbanHours, now)
            }
            blacked = true
            steps.add("3) " + plan.blackText())
        } else {
            steps.add("3) " + plan.blackText())
        }
        return KickResult(sent = sent, kicked = kicked, blacked = blacked, steps = steps)
    }

    /** 只读试跑：输出真执行时会走的每一步，不做任何发送、踢人或写配置。 */
    fun dryRun(context: Context?, plan: KickPlan): List<String> {
        val cfg = GroupAdminStore.group(context, plan.room)
        val lines = ArrayList<String>()
        lines.add("房间：" + plan.room + " · 纳入管理=" + onOff(cfg.master) + " · 动作后提示=" + onOff(cfg.notify))
        lines.add("目标：" + plan.name + "（" + plan.wxId + "）")
        if (plan.notify) {
            lines.add("1) 先发告别语（@目标）：「" + plan.messageText() + "」")
            lines.add("   —— 必须先发：踢完成员不在列表里，@ 解析不到目标")
        } else {
            lines.add("1) 跳过告别语：本群「动作后在群里提示」是关闭状态")
        }
        lines.add("2) delChatroomMember(" + plan.room + ", " + plan.wxId + ")")
        lines.add("3) " + plan.blackText() + "（黑名单只比 wxid，到期自动失效）")
        lines.add("以上为只读试跑，未发送、未踢人、未写配置。")
        return lines
    }

    /**
     * 「将执行（只读试跑）」区块的内容（纯函数，JVM 单测覆盖）。
     *
     * 返回非空表示**不该再列三步**：豁免命中时真执行不会有任何动作，黑名单命中时真执行走的是
     * 黑名单优先分支（不加黑），两者都跟「1) 先发告别语 2) delChatroomMember 3) 写黑名单」这套
     * 通用三步不一致——上一版就是在这里跟结论自相矛盾。
     *
     * 只有既不豁免也不在黑名单时才返回空列表，由 [dryRun] 列出三步。
     */
    fun previewLines(exemptBy: String, blackTimeText: String?): List<String> = when {
        exemptBy.isNotBlank() -> listOf("不执行任何动作（原因：命中「" + exemptBy + "」档）")
        blackTimeText != null -> listOf(
            "不执行规则动作（原因：命中黑名单，按黑名单优先处理）",
            "→ 真执行时：先发告别语 → 移出群聊（" + blackTimeText + "，不重复拉黑）"
        )
        else -> emptyList()
    }

    private fun onOff(value: Boolean): String = if (value) "开" else "关"
}
