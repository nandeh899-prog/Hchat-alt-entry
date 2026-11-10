package com.aeonlab.toolkit.hooks.items.groupadmin

import android.content.Context
import com.aeonlab.toolkit.hooks.api.core.WeChatApis
import com.aeonlab.toolkit.hooks.api.contact.WeChatChatroomApi

/**
 * 角色与权限判定（规格 5.2 的角色部分；文件划分上规格把这段放在 Store，
 * 这里单独成文件是为了让「只读试跑报告」有一处集中、可测的实现）。
 *
 * 三级角色：本人 > 群主 > 代理群主/管理员。前两档来自微信真实身份
 * （登录 wxid / `getOwner`），后两档是模块自定义名单（规格 3.1：代理群主由本模块自己维护，
 * `getMemberRole` 推不出来）。
 */
enum class GroupAdminRole(val label: String) {
    SELF("本人"),
    OWNER("群主"),
    AGENT("代理群主（名单）"),
    ADMIN("管理员（名单）"),
    MEMBER("普通成员")
}

data class GroupAdminRoleVerdict(
    val room: String,
    val wxId: String,
    val name: String,
    val wechatRole: Int,
    val wechatRoleName: String,
    val role: GroupAdminRole,
    val isSelf: Boolean,
    val isOwner: Boolean,
    val isAdmin: Boolean,
    val isAgent: Boolean,
    val self: String,
    val owner: String,
    val inviter: String,
    val inviterName: String,
    val switchLines: List<String>
)

data class GroupAdminExemptVerdict(
    val exempt: Boolean,
    val by: String,
    val lines: List<String>
)

object GroupAdminRoleInspector {

    fun selfWxId(): String = try {
        WeChatApis.account()?.selfWxId().orEmpty()
    } catch (t: Throwable) {
        ""
    }

    /** 真机只读判定：微信角色与邀请人走真实 API，名单档次走本群配置。 */
    fun inspect(context: Context?, cfg: GroupConfig, room: String, wxId: String): GroupAdminRoleVerdict {
        val name = GroupAdminMemberCache.nameOf(room, wxId).ifBlank { wxId }
        val self = selfWxId()
        val owner = GroupAdminMemberCache.ownerOf(room).ifBlank {
            try {
                WeChatApis.contact().chatrooms()?.getOwner(room).orEmpty()
            } catch (t: Throwable) {
                ""
            }
        }
        val wechatRole = try {
            WeChatApis.contact().chatrooms()?.getMemberRole(room, wxId) ?: WeChatChatroomApi.ROLE_MEMBER
        } catch (t: Throwable) {
            WeChatChatroomApi.ROLE_MEMBER
        }
        val wechatRoleName = when (wechatRole) {
            WeChatChatroomApi.ROLE_OWNER -> "群主"
            WeChatChatroomApi.ROLE_ADMIN -> "管理员"
            else -> "群员"
        }
        val inviter = try {
            WeChatApis.contact().chatrooms()?.getMemberInviter(room, wxId).orEmpty()
        } catch (t: Throwable) {
            ""
        }
        val inviterName = if (inviter.isBlank()) "" else GroupAdminMemberCache.nameOf(room, inviter).ifBlank { inviter }
        val isSelf = self.isNotBlank() && self == wxId
        val isOwner = owner.isNotBlank() && owner == wxId
        val isAdmin = cfg.admins.any { it.id.isNotBlank() && it.id == wxId }
        val isAgent = cfg.agents.any { it.id.isNotBlank() && it.id == wxId }
        val role = when {
            isSelf -> GroupAdminRole.SELF
            isOwner -> GroupAdminRole.OWNER
            isAgent -> GroupAdminRole.AGENT
            isAdmin -> GroupAdminRole.ADMIN
            else -> GroupAdminRole.MEMBER
        }
        val switches = listOf(
            "白名单豁免=" + onOff(cfg.wlMaster),
            "代理可任免管理员=" + onOff(cfg.agentManageAdmin),
            "允许管理员指令=" + onOff(cfg.allowAdminCmd),
            "允许代理指令=" + onOff(cfg.allowAgentCmd)
        )
        return GroupAdminRoleVerdict(
            room = room,
            wxId = wxId,
            name = name,
            wechatRole = wechatRole,
            wechatRoleName = wechatRoleName,
            role = role,
            isSelf = isSelf,
            isOwner = isOwner,
            isAdmin = isAdmin,
            isAgent = isAgent,
            self = self,
            owner = owner,
            inviter = inviter,
            inviterName = inviterName,
            switchLines = switches
        )
    }

    /**
     * 豁免链，顺序与 gmIsExempt 完全一致：
     * 本人 → 群主 → 管理员名单 → 代理名单 → 白名单（仅当 wlMaster 为真）。
     *
     * 判定只走 GroupAdminStore.exemptBy（单一实现，JVM 单测覆盖），
     * 这里只把命中过程渲染成可读依据，不重复实现一遍顺序。
     */
    fun exempt(cfg: GroupConfig, verdict: GroupAdminRoleVerdict): GroupAdminExemptVerdict {
        val by = GroupAdminStore.exemptBy(cfg, verdict.wxId, verdict.self, verdict.owner)
        val tiers = listOf(
            Triple("本人", "本人", "登录 wxid=" + verdict.self.ifBlank { "未取到" }),
            Triple("群主", "群主", "群主=" + verdict.owner.ifBlank { "未取到" }),
            Triple("管理员名单", "管理员名单", cfg.admins.size.toString() + " 条，按 wxid 匹配"),
            Triple("代理群主名单", "代理群主名单", cfg.agents.size.toString() + " 条，按 wxid 匹配"),
            Triple(
                "白名单", "白名单",
                if (cfg.wlMaster) cfg.white.size.toString() + " 条，按 wxid 匹配"
                else "本群「白名单豁免」已关闭，跳过本档"
            )
        )
        val lines = ArrayList<String>()
        for ((label, tier, base) in tiers) {
            val hit = by == tier
            lines.add((if (hit) "✓ " else "✗ ") + label + "：" +
                (if (hit) base + " → 命中 " + verdict.wxId else base))
            if (hit) break
        }
        return GroupAdminExemptVerdict(by.isNotEmpty(), by, lines)
    }

    /** 单人只读试跑报告：角色 → 邀请人 → 豁免链 → 黑名单 → 将执行的步骤 → 结论。 */
    fun report(context: Context?, cfg: GroupConfig, room: String, wxId: String): List<String> {
        val verdict = inspect(context, cfg, room, wxId)
        val lines = ArrayList<String>()
        lines.add("【目标】" + verdict.name + "（" + verdict.wxId + "）")
        lines.add("【微信角色】getMemberRole=" + verdict.wechatRole + "（" + verdict.wechatRoleName + "）")
        lines.add(
            "【三级判定】" + verdict.role.label +
                "（本人=" + yesNo(verdict.isSelf) + " · 群主=" + yesNo(verdict.isOwner) +
                " · 代理名单=" + yesNo(verdict.isAgent) + " · 管理员名单=" + yesNo(verdict.isAdmin) + "）"
        )
        lines.add("【邀请人】getMemberInviter=" + (verdict.inviter.ifBlank { "（无）" }) +
            (if (verdict.inviterName.isNotBlank()) "（" + verdict.inviterName + "）" else ""))
        lines.add("【本群开关】" + verdict.switchLines.joinToString(" · "))
        lines.add("【豁免链】")
        val exempt = exempt(cfg, verdict)
        lines.addAll(exempt.lines)
        val now = System.currentTimeMillis()
        val hit = GroupAdminStore.blackHit(cfg, wxId, now)
        lines.add(
            "【黑名单】" + if (hit == null) "未命中（名单 " + cfg.black.size + " 条，只比 wxid）"
            else "命中：" + GroupAdminStore.label(hit) + " · " + GroupAdminStore.blackTimeText(hit.expireAt, now)
        )
        lines.add("【将执行（只读试跑）】")
        val preview = GroupAdminActions.previewLines(
            if (exempt.exempt) exempt.by else "",
            hit?.let { GroupAdminStore.blackTimeText(it.expireAt, now) }
        )
        if (preview.isEmpty()) {
            val plan = GroupAdminActions.plan(context, room, wxId, verdict.name, "", null, true)
            lines.addAll(GroupAdminActions.dryRun(context, plan))
        } else {
            lines.addAll(preview)
            lines.add("以上为只读试跑，未发送、未踢人、未写配置。")
        }
        val longRule = GroupAdminRuleCatalog.ruleOf(cfg, GroupAdminRuleCatalog.LONG)
        val longLimit = GroupAdminRuleCatalog.paramInt(
            cfg,
            GroupAdminRuleCatalog.LONG,
            GroupAdminRuleCatalog.PARAM_LONG_LIMIT,
            GroupAdminRuleCatalog.DEFAULT_LONG_LIMIT
        )
        val conclusion = when {
            exempt.exempt -> "被豁免（命中「" + exempt.by + "」），真执行时不做任何动作"
            hit != null -> "黑名单命中：他下一条消息会被直接移出群（先发告别语 → 移出群聊，按黑名单优先处理，不重复拉黑）"
            !cfg.master -> "未纳入管理：本群不处理任何消息，真执行时也不会有动作"
            longRule.on -> "未豁免、未命中黑名单：他在群里发超过 " + longLimit + " 字的文本时命中规则 long，" +
                (if (longRule.warnEnable) "先警告 " + longRule.warnCount + " 次再踢" else "直接踢并按规则时长拉黑")
            else -> "未豁免、未命中黑名单：本群规则 long 当前是关闭的，真执行时不会被处理"
        }
        lines.add("【结论】" + conclusion)
        return lines
    }

    /** 全群只读扫描：豁免分布、黑名单命中、到期条目、待确认条目。不做逐人反射查询。 */
    fun scan(context: Context?, cfg: GroupConfig, room: String): List<String> {
        val lines = ArrayList<String>()
        val members = GroupAdminMemberCache.members(room)
        val now = System.currentTimeMillis()
        val stat = GroupAdminMemberCache.stat(room)
        lines.add("【只读扫描】" + GroupAdminMemberCache.groupName(room).ifBlank { room })
        lines.add(
            "缓存成员：" + members.size + " 人" +
                (if (stat == null) "" else "（成员表 " + stat.memberlist + " 条 / 群昵称表 " +
                    stat.roomNames + " 条，取到名字 " + stat.named + " 人）")
        )
        if (members.isEmpty()) {
            lines.add("没有缓存成员：先返回上一页点「刷新群成员并结算」。")
            lines.add("说明：本扫描只读，不写配置、不踢人、不发消息。")
            return lines
        }
        val self = selfWxId()
        val owner = GroupAdminMemberCache.ownerOf(room)
        var bySelf = 0
        var byOwner = 0
        var byAdmin = 0
        var byAgent = 0
        var byWhite = 0
        val blackNames = ArrayList<String>()
        for (m in members) {
            when (GroupAdminStore.exemptBy(cfg, m.id, self, owner)) {
                "本人" -> bySelf++
                "群主" -> byOwner++
                "管理员名单" -> byAdmin++
                "代理群主名单" -> byAgent++
                "白名单" -> byWhite++
            }
            if (GroupAdminStore.blackHit(cfg, m.id, now) != null) blackNames.add(GroupAdminStore.label(m))
        }
        lines.add(
            "豁免：" + (bySelf + byOwner + byAdmin + byAgent + byWhite) + " 人（本人 " + bySelf +
                " / 群主 " + byOwner + " / 管理员名单 " + byAdmin + " / 代理名单 " + byAgent + " / 白名单 " + byWhite + "）"
        )
        lines.add(
            "黑名单命中：" + blackNames.size + " 人" +
                (if (blackNames.isEmpty()) "" else " → " + blackNames.take(5).joinToString("、") +
                    (if (blackNames.size > 5) " 等" else ""))
        )
        lines.add("黑名单已到期：" + GroupAdminStore.expiredBlackCount(cfg, now) + " 条（点「刷新群成员并结算」会清理）")
        lines.add("待确认条目：" + GroupAdminStore.pendingCount(cfg) + " 条（只存了昵称，结算时按唯一昵称再解析）")
        lines.add("说明：本扫描只读，不写配置、不踢人、不发消息。")
        return lines
    }

    private fun onOff(value: Boolean): String = if (value) "开" else "关"

    private fun yesNo(value: Boolean): String = if (value) "是" else "否"
}
