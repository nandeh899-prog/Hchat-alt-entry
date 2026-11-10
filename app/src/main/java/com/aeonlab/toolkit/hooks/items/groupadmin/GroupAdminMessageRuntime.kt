package com.aeonlab.toolkit.hooks.items.groupadmin

import android.content.Context
import com.aeonlab.toolkit.hooks.api.core.WeChatApis
import com.aeonlab.toolkit.hooks.api.message.WeChatMessageObserveApi
import com.aeonlab.toolkit.utils.HLog

/**
 * 消息入口：把观察到的群消息按规格 2.1 `gmHandleMsgRoom` 的顺序走一遍。
 *
 * 规格第 5 步起，24 条规则全部接线：
 * - 执行链顺序照规格 2.1：回声过滤 → 未纳管丢弃 → 全局开关 → 限时警告到期检查 → 拍一拍 →
 *   系统消息（记邀请人 / 改群名 / 改名冒充）→ 消息去重 → 本群总开关 → 发送者解析 → 豁免 →
 *   黑名单优先踢 → 60 秒懒清理 → 规则判定（规格 2.2 顺序，命中即返回）。
 * - 判定层是纯函数（[GroupAdminRuleEngine]），历史状态在 [GroupAdminRuleMemory]，
 *   系统消息解析在 [GroupAdminNoticeParser]。
 * - 群成员事件（nonadmin / leavebl）在 [GroupAdminMemberRuntime]。
 * - 未纳管的群仍然**静默丢弃**（连日志都不打）。
 */
object GroupAdminMessageRuntime {

    private const val TAG = "[AeonLab:GroupAdmin]"
    private const val TYPE_LOG_INTERVAL_MS = 5_000L
    private const val TYPE_LOG_BODY = 60

    /** 黑名单优先踢人的理由（规格 2.1 第 14 步的原文理由）。 */
    private const val BLACK_REASON = "黑名单用户"

    private const val PRUNE_INTERVAL_MS = 60_000L

    /** 入群通知缓存窗口（规格 2.4 nonadmin：`gmJoinInviter` 120 秒）。 */
    private const val INVITER_WINDOW_MS = 120_000L

    /** 最近处理过的消息 id，避免同一消息被重复投递时重复警告/重复踢。 */
    private val recentIds = HashSet<Long>()
    private val recentOrder = ArrayDeque<Long>()

    private var lastPruneAt = 0L

    /** 消息入口是否已装上（面板「运行期状态」用它确认订阅真的成功）。 */
    @Volatile
    private var installed = false

    /** 最近的处理记录（最多 20 条），只用于面板显示，不参与判定。 */
    private val recentEvents = ArrayDeque<String>()

    /** 规则历史状态：重复窗口、拍一拍窗口、禁言冷却、冒充群主限时。 */
    private val memory = GroupAdminRuleMemory()

    /** 入群通知缓存：房间 → 通知列表（用于 nonadmin 找邀请人）。 */
    private data class InviterNotice(
        val joinedName: String,
        val joinedId: String,
        val inviterName: String,
        val at: Long,
        val inviterId: String = ""
    )

    private val inviters = HashMap<String, MutableList<InviterNotice>>()

    fun isInstalled(): Boolean = installed

    fun markInstalled() {
        installed = true
        record("消息入口已接入")
    }

    fun markUninstalled() {
        installed = false
        record("消息入口已卸载")
    }

    fun recentLines(): List<String> = synchronized(recentEvents) { recentEvents.toList() }

    /** 供同包其它运行期（成员事件、面板）往最近处理记录里写一行。 */
    fun note(line: String) = record(line)

    private fun record(line: String) {
        val stamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
            .format(java.util.Date(System.currentTimeMillis()))
        synchronized(recentEvents) {
            recentEvents.addLast(stamp + " " + line)
            while (recentEvents.size > 20) recentEvents.removeFirst()
        }
    }

    fun handle(context: Context, message: WeChatMessageObserveApi.ObservedMessage) {
        try {
            handleInternal(context, message)
        } catch (t: Throwable) {
            HLog.e(TAG + " 处理消息异常", t)
        }
    }

    private fun handleInternal(context: Context, message: WeChatMessageObserveApi.ObservedMessage) {
        // 聊天指令入口（12 条）：只接管「整条消息正好是一条指令」的消息，其余走原路径。
        // 必须在下面的回声过滤之前——自己发出的指令同样要被处理（原实现靠吞掉发送按钮实现，模块没有等价 hook）。
        if (GroupAdminCommands.handle(context, message)) return

        val content = message.getContent().orEmpty()

        // 规格 2.1 第 2 步：回声过滤。
        if (message.isSend() || content.contains(GroupAdminActions.NOTICE_MARK)) return

        val room = message.getTalker().orEmpty()
        if (room.isBlank() || !room.contains("@chatroom")) return

        // 规格 2.1 第 3 步：未管理群直接丢弃。
        if (!GroupAdminStore.isManaged(context, room)) return

        // 规格 2.1 第 4 步：进群验证（只受全局开关管辖，与本群自动移除总开关无关）。
        // 原实现顺序：回声过滤 → 未管理群丢弃 → 进群验证 → 全局开关 → 拍一拍/系统消息/指令。
        if (GroupAdminSettings.isEnabled(context)) {
            val verifySender = runCatching { message.getSendTalker() }.getOrNull().orEmpty()
            val verifyIsText = runCatching { message.isText() }.getOrDefault(false) ||
                GroupAdminMessageTypes.normalizeType(message.getType()) == GroupAdminMessageTypes.TYPE_TEXT
            if (GroupAdminVerify.onMessage(context, room, verifySender, content, verifyIsText)) return
            // 可观测性：本群有人正在验证，而这条消息没被验证步消费——用于排查「成员的回答没被判」。
            if (GroupAdminVerify.hasActive(room)) {
                HLog.i(
                    TAG + " 进群验证：未被验证步消费 · 发送者=" + verifySender + " · 群 " + room +
                        " · 文本判=" + verifyIsText + " · 正文=" + content.take(80)
                )
            }
        }

        // 规格 2.1 第 5 步：全局开关关闭则全停。
        if (!GroupAdminSettings.isEnabled(context)) return

        val features = buildFeatures(message)
        val now = System.currentTimeMillis()
        logFakeSelf(room, runCatching { message.getSendTalker() }.getOrNull().orEmpty(), now)
        logImageFields(features)
        logTypeJudgement(context, room, features)

        // 规格 2.1 第 7 步：拍一拍（先于系统消息判定，拍一拍本身也是「通知类」消息）。
        if (GroupAdminMessageTypes.isPat(features)) {
            logPatBasis(room, features, true)
            handlePat(context, room, message, features, now)
            return
        }
        // 5.5.23 诊断：通知类消息没被判为拍一拍时也留一条依据，下一轮日志即可看出真拍一拍的真实字段形态。
        GroupAdminMessageTypes.normalizeType(features.type).let {
            if (it == 49 || it == 10000 || it == 10002) logPatBasis(room, features, false)
        }

        // 规格 2.1 第 8 步：系统消息（记邀请人 → 改群名 → 改名冒充群主），随后 return。
        // 5.5.20（要求 2/3）：系统模板通知（实测 type=570425393，低位归一 49）此前被当成普通 appmsg
        // 直接进规则链，导致改群名/邀请/移出等通知群管看不到；这里一并交给 handleSystem。
        if (GroupAdminMessageTypes.isSystem(features) || GroupAdminMessageTypes.isNotice(features)) {
            handleSystem(context, room, message, features, now)
            return
        }

        if (duplicate(message.getMsgId())) return

        val cfg = GroupAdminStore.group(context, room)

        // 规格 2.1 第 10 步：本群总开关。
        if (!cfg.master) return

        val sender = message.getSendTalker().orEmpty()
        if (sender.isBlank() || sender.contains("@chatroom")) return

        // 群昵称优先；缓存里只有 wxid 或没有时按需实时读一次（第 9 条「冒充群主」依赖它）
        // 5.5.29 真机取证：原实现「先取缓存 nameOf，命中就再也不看实时昵称」，改名后长期按旧昵称判定
        //（20:11 仍按旧名「群主」告警、20:25 改名后仍读旧名「云裳…」漏判）。改为实时优先，缓存兜底。
        var name = GroupAdminMemberCache.nameNow(room, sender).ifBlank {
            GroupAdminMemberCache.nameOf(room, sender).ifBlank { sender }
        }
        val self = selfWxId()
        val owner = ownerOf(room)

        // 规格 2.1 第 13 步：豁免。
        val exemptBy = GroupAdminStore.exemptBy(cfg, sender, self, owner)
        if (exemptBy.isNotEmpty()) {
            HLog.i(TAG + " 跳过：命中豁免档「" + exemptBy + "」· " + name + "（" + sender + "）· 群 " + room)
            record("跳过豁免：" + name + "（" + exemptBy + "）")
            return
        }

        // 规格 2.1 第 14 步：黑名单优先。
        val black = GroupAdminStore.blackHit(cfg, sender, now)
        if (black != null) {
            val plan = GroupAdminActions.plan(context, room, sender, name, BLACK_REASON, null, false)
            val result = GroupAdminActions.kick(context, plan)
            GroupAdminMemberCache.invalidate(room) // 移出成功 → 成员缓存立刻失效（第 14 条）
            val remaining = GroupAdminStore.blackTimeText(black.expireAt, now)
            HLog.i(
                TAG + " 黑名单优先：移出 " + name + "（" + sender + "）· 剩余 " +
                    remaining + " · 群 " + room +
                    " · 提示=" + result.sent + " 移出=" + result.kicked + " 拉黑=否"
            )
            record("黑名单优先：" + name + "（剩余 " + remaining + "）· 移出=" + result.kicked)
            return
        }

        // 规格 2.1 第 15 步：每 60 秒一次的懒清理。
        prune(context, room, now)

        // 规格 2.1 第 6 步的等价物：冒充群主限时警告的到期检查（在该成员下次发言时结算）。
        if (settleFakeOwner(context, cfg, room, sender, name, owner, now)) return

        // 5.5.31 收紧（用户 2026-11-06 批准）：疑似命中冒充类判定（昵称含「群主」或与群主同名）时，
        // 在发出警告/踢人之前对该条消息强制实时读一次群昵称，用实时值做最终判定；实时读取失败或被
        // 最小节流跳过则沿用缓存值并留一行兜底日志。只在疑似命中时发生，不会退化为每条消息实时读。
        val ownerName = if (owner.isNotBlank()) GroupAdminMemberCache.nameNow(room, owner) else ""
        if (GroupAdminNoticeParser.impersonates(name, ownerName)) {
            val forced = GroupAdminMemberCache.nameForceLive(room, sender)
            if (forced.status == GroupAdminMemberCache.STATUS_LIVE) {
                name = forced.name
            } else {
                logNickForceFallback(room, sender, name, forced.status, now)
            }
        }

        // 规格 2.1 第 16 步：规则判定。
        val ctx = buildContext(context, message, features, cfg, room, sender, name, self, owner, exemptBy, now)
        val decisions = GroupAdminRuleEngine.evaluate(ctx, cfg)
        if (decisions.isEmpty()) {
            // 5.5.23 诊断：进了规则链但没有任何规则命中 —— 与「被更早分支截走」区分开，便于下轮定位。
            logNoHit(room, features, ctx)
            return
        }
        for (decision in decisions) {
            if (decision is GroupAdminDecision.Hit) dispatch(context, room, sender, name, decision, now)
        }
    }

    // ---- 拍一拍 ----

    private fun handlePat(
        context: Context,
        room: String,
        message: WeChatMessageObserveApi.ObservedMessage,
        features: GroupAdminMessageFeatures,
        now: Long
    ) {
        val cfg = GroupAdminStore.group(context, room)
        // 规格 2.1 第 7 步：pat 规则开着 **且** 本群总开关开着才处理。
        if (!GroupAdminRuleCatalog.ruleOf(cfg, "pat").on) return
        if (!cfg.master) return

        val patter = features.patFrom.ifBlank { message.getSendTalker().orEmpty() }.trim()
        if (patter.isBlank() || patter.contains("@chatroom")) return
        val pattedRaw = features.patPatted.filter { it.isNotBlank() && !it.contains("@chatroom") }
        // 宿主未投递 patPattedUser 时退回解析拍一拍 XML（5.5.18 修：第 7 条）
        val patted = if (pattedRaw.isNotEmpty()) pattedRaw
        else GroupAdminMessageTypes.patPattedOf(features.xml, features.content)

        // 被拍者是黑名单成员 → 当场踢（理由「黑名单用户」，不重复拉黑）。
        for (target in patted) {
            val black = GroupAdminStore.blackHit(cfg, target, now) ?: continue
            val targetName = GroupAdminMemberCache.nameOf(room, target).ifBlank { target }
            val plan = GroupAdminActions.plan(context, room, target, targetName, BLACK_REASON, null, false)
            val result = GroupAdminActions.kick(context, plan)
            val remaining = GroupAdminStore.blackTimeText(black.expireAt, now)
            HLog.i(
                TAG + " 拍一拍：被拍者 " + targetName + " 在黑名单 → 当场移出 · 剩余 " + remaining +
                    " · 群 " + room + " · 移出=" + result.kicked
            )
            record("pat 被拍者黑名单移除 " + targetName + "（剩余 " + remaining + "）")
        }

        val self = selfWxId()
        val owner = ownerOf(room)
        val exemptBy = GroupAdminStore.exemptBy(cfg, patter, self, owner)
        if (exemptBy.isNotEmpty()) return

        val patterName = GroupAdminMemberCache.nameOf(room, patter).let { n ->
            if (n.isNotBlank() && n != patter) n
            else GroupAdminMemberCache.nameNow(room, patter).ifBlank { if (n.isNotBlank()) n else patter }
        }
        val windowMinutes = GroupAdminRuleCatalog.paramInt(
            cfg, "pat", GroupAdminRuleCatalog.PARAM_PAT_WINDOW, GroupAdminRuleCatalog.DEFAULT_PAT_WINDOW
        )
        // 5.5.29 真机取证（20:19/20:21/20:23/20:25 四批）：原实现只把**第一个**被拍者计入窗口，
        // 一条拍一拍 sysmsg 里出现多个 <pattedusername> 时会漏计。改为逐个计入（取最后一次返回的计数）。
        var distinct = 0
        if (patted.isEmpty()) distinct = memory.notePat(room, patter, "", now, windowMinutes * 60_000L)
        else patted.forEach { target -> distinct = memory.notePat(room, patter, target, now, windowMinutes * 60_000L) }
        if (patted.isEmpty()) return

        val ctx = GroupAdminMessageContext(
            room = room,
            sender = patter,
            senderName = patterName,
            content = features.content,
            features = features,
            patDistinctCount = distinct,
            memberCount = GroupAdminMemberCache.members(room).size,
            now = now
        )
        val hit = GroupAdminRuleEngine.pat(ctx, cfg) ?: run {
            // 5.5.29：未达阈值也留痕（原实现在此静默 return，真机表现为「完全无响应」无法定位）。
            val limit = GroupAdminRuleCatalog.paramInt(
                cfg, "pat", GroupAdminRuleCatalog.PARAM_PAT_LIMIT, GroupAdminRuleCatalog.DEFAULT_PAT_LIMIT
            )
            logPatBelowThreshold(room, patter, patterName, distinct, limit, windowMinutes, now)
            return
        }
        // 5.5.28 按用户要求：规定时间内「拍一次多人」只记一次警告。原实现对窗口内每条拍一拍消息都会命中，
        // 真机 18:18:40–18:18:43 一次拍 6 条即打出 1/3、2/3、3/3、又 1/3、2/3 并提前踢人。
        val patKey = room + "|" + patter
        val patWindowMs = windowMinutes * 60_000L
        val lastPatWarn = patWarnedAt[patKey] ?: 0L
        if (now - lastPatWarn < patWindowMs) {
            HLog.i(
                TAG + " 拍一拍：本窗口已记过一次警告，跳过重复计数 · " + patterName + "（" + patter + "）· 距上次 " +
                    ((now - lastPatWarn) / 1000) + " 秒 · 窗口 " + windowMinutes + " 分钟"
            )
            return
        }
        patWarnedAt[patKey] = now
        if (patWarnedAt.size > 200) patWarnedAt.clear()
        dispatch(context, room, patter, patterName, hit, now)
    }

    // ---- 系统消息 ----

    private fun handleSystem(
        context: Context,
        room: String,
        message: WeChatMessageObserveApi.ObservedMessage,
        features: GroupAdminMessageFeatures,
        now: Long
    ) {
        // 5.5.18：系统消息一律留原始正文（截断），便于下一轮按真机形态核对改群名/拉人语义（第 1、9、13 条）
        runCatching {
            HLog.i(
                TAG + " 系统消息原文（" + message.kind + "）：" +
                    features.content.replace('\n', ' ').take(120)
            )
        }
        // 5.5.20：模板类通知正文落日志（实测模板含 <sysmsgtemplate>/<link_list>，供下一轮按实测解析改名模板）。
        runCatching {
            val t = if (features.xml.contains("sysmsgtemplate")) features.xml else features.content
            if (t.contains("sysmsgtemplate")) {
                HLog.i(
                    TAG + " 模板通知：xml长度=" + features.xml.length + " 正文长度=" + features.content.length +
                        " 模板片段=" + t.replace('\n', ' ').replace(Regex("\\s+"), " ").take(1500)
                )
            }
        }
        // 8-a 记邀请人（不受本群总开关限制）。
        val join = GroupAdminNoticeParser.joinNotice(features.content, features.xml)
        if (join != null) {
            val list = inviters.getOrPut(room) { ArrayList() }
            list.removeAll { now - it.at > INVITER_WINDOW_MS }
            list.add(InviterNotice(join.joinedName, join.joinedId, join.inviterName, now, join.inviterId))
            if (inviters.size > 512) {
                val keep = inviters.keys.toList().takeLast(256).toSet()
                inviters.keys.retainAll(keep)
            }
            HLog.i(
                TAG + " 记入群通知：邀请人「" + join.inviterName + "」→ 新成员「" + join.joinedName +
                    "」（" + join.joinedId + "）· 群 " + room
            )
            return
        }

        val cfg = GroupAdminStore.group(context, room)
        if (!cfg.master) return

        val rename = GroupAdminNoticeParser.renameNotice(features.content, features.xml)
        if (rename != null) {
            val self = selfWxId()
            val owner = ownerOf(room)
            val ownerName = if (owner.isNotBlank()) GroupAdminMemberCache.nameOf(room, owner) else ""
            var actor = rename.actorId.trim()
            if (actor.isEmpty()) actor = resolveByName(room, rename.actorName)
            if (actor.isEmpty()) {
                val s = message.getSendTalker().orEmpty()
                if (s.isNotBlank() && !s.contains("@chatroom")) actor = s
            }
            if (actor.isEmpty()) {
                HLog.i(
                    TAG + " 改群名通知：解析不到操作者「" + rename.actorName + "」· 群 " + room +
                        " 新群名=" + rename.newName + " 模板链接=" +
                        GroupAdminNoticeParser.templateLinks(features.xml).joinToString("、") { it.first + "=" + it.second }
                )
                HLog.i(TAG + " 改群名通知：解析不到操作者「" + rename.actorName + "」，跳过 · 群 " + room)
                return
            }
            val exemptBy = GroupAdminStore.exemptBy(cfg, actor, self, owner)
            if (exemptBy.isNotEmpty()) {
                record("改群名：" + rename.actorName + " 属豁免档（" + exemptBy + "），跳过")
                return
            }
            val actorName = GroupAdminMemberCache.nameOf(room, actor).ifBlank { rename.actorName }

            // 8-c 改名冒充群主
            if (GroupAdminNoticeParser.renamedToImpersonate(rename.newName, ownerName)) {
                val hit = fakeownerHitFor(context, cfg, room, actor, actorName, owner, now, rename.newName)
                if (hit != null) dispatch(context, room, actor, actorName, hit, now)
                return
            }

            // 8-b 擅自修改群名
            val rule = GroupAdminRuleCatalog.ruleOf(cfg, "rename")
            if (!rule.on) return
            val hit = GroupAdminDecision.Hit(
                rule = "rename",
                reason = rule.reason.ifBlank { GroupAdminRuleCatalog.defaultReason("rename") },
                action = GroupAdminHitAction.KICK_NOW,
                addBlack = true,
                unbanHours = rule.unbanHours,
                warnEnable = false,
                warnCount = 1,
                warnText = rule.warnText,
                skipWarn = true,
                detail = "系统消息：把群名改成「" + rename.newName + "」"
            )
            dispatch(context, room, actor, actorName, hit, now)
            return
        }

        // 其它系统消息（含「谁邀请了谁」的变体）不参与规则判定。
    }

    /** 冒充群主的命中构造：限时警告（GRACE_WARN）或到期直接踢。 */
    private fun fakeownerHitFor(
        context: Context,
        cfg: GroupConfig,
        room: String,
        actor: String,
        actorName: String,
        owner: String,
        now: Long,
        detailName: String
    ): GroupAdminDecision.Hit? {
        val rule = GroupAdminRuleCatalog.ruleOf(cfg, "fakeowner")
        if (!rule.on) return null
        val grace = GroupAdminRuleCatalog.paramInt(
            cfg, "fakeowner", GroupAdminRuleCatalog.PARAM_FAKE_GRACE, GroupAdminRuleCatalog.DEFAULT_FAKE_GRACE
        )
        runCatching { logFakeBasis(room, actorName, owner, "改名路径") }
        val due = memory.fakeDue(room, actor, now)
        val ctx = GroupAdminMessageContext(
            room = room,
            sender = actor,
            senderName = actorName,
            renameToOwnerName = true,
            fakeGraceExpired = due,
            now = now
        )
        val hit = GroupAdminRuleEngine.fakeowner(ctx, cfg) ?: return null
        return hit.copy(detail = hit.detail + "（系统消息改名「" + detailName + "」，限时 " + grace + " 分钟）")
    }

    /** 冒名限时警告到期结算：改名正常则解除，仍冒充且到期则踢。 */
    private fun settleFakeOwner(
        context: Context,
        cfg: GroupConfig,
        room: String,
        sender: String,
        name: String,
        owner: String,
        now: Long
    ): Boolean {
        if (!memory.hasFakePending(room, sender)) return false
        if (!GroupAdminRuleCatalog.ruleOf(cfg, "fakeowner").on) {
            memory.clearFake(room, sender)
            return false
        }
        val ownerName = if (owner.isNotBlank()) GroupAdminMemberCache.nameOf(room, owner) else ""
        // 5.5.22 前置（用户要求）：必须取到发送者**群昵称**才做冒充判定；落到 wxid 回退说明昵称未取到，
        // 此时不判冒充（避免用 @ 名单或正文里的名字参与比较）。
        if (!nickKnown(name, sender)) {
            memory.clearFake(room, sender)
            HLog.i(TAG + " 冒充判定跳过：未取到发送者群昵称（sender=" + sender + " 昵称=" + name + "）· 群 " + room)
            return false
        }
        val impersonating = GroupAdminNoticeParser.impersonates(name, ownerName)
        if (!impersonating) {
            memory.clearFake(room, sender)
            record("冒充群主警告解除：" + name + " 已改回昵称")
            return false
        }
        if (!memory.fakeDue(room, sender, now)) return false
        val ctx = GroupAdminMessageContext(
            room = room,
            sender = sender,
            senderName = name,
            senderNameHasOwner = nickKnown(name, sender) &&
                GroupAdminNoticeParser.impersonates(name, ownerName),
            senderNameEqualsOwner = GroupAdminNoticeParser.normalizeName(ownerName).isNotEmpty() &&
                GroupAdminNoticeParser.normalizeName(name) == GroupAdminNoticeParser.normalizeName(ownerName),
            fakeGraceExpired = true,
            now = now
        )
        val hit = GroupAdminRuleEngine.fakeowner(ctx, cfg) ?: return false
        memory.clearFake(room, sender)
        dispatch(context, room, sender, name, hit, now)
        return true
    }

    // ---- 入群通知查询（给 [GroupAdminMemberRuntime] 用） ----

    /** 取 120 秒内的入群通知；按 wxid 优先、昵称其次匹配。 */
    fun takeInviter(room: String, joinedName: String, joinedId: String): GroupAdminNoticeParser.JoinNotice? {
        val now = System.currentTimeMillis()
        val list = inviters[room] ?: return null
        list.removeAll { now - it.at > INVITER_WINDOW_MS }
        // 5.5.20：实测「邀请者：」通知里没有被邀请人姓名/ wxid，只能按时间窗与进群事件配对。
        val match = list.lastOrNull { joinedId.isNotBlank() && it.joinedId == joinedId }
            ?: list.lastOrNull { it.joinedName.isNotBlank() && it.joinedName == joinedName }
            ?: list.lastOrNull { it.joinedName.isBlank() && now - it.at <= INVITER_WINDOW_MS }
            ?: return null
        HLog.i(
            TAG + " 邀请人配对：新成员=" + joinedName + "（" + joinedId + "）← 邀请人=" + match.inviterName +
                "（" + match.inviterId + "）· 间隔 " + (now - match.at) / 1000 + " 秒"
        )
        return GroupAdminNoticeParser.JoinNotice(
            inviterName = match.inviterName,
            joinedName = match.joinedName,
            joinedId = match.joinedId,
            inviterId = match.inviterId
        )
    }

    private fun resolveByName(room: String, name: String): String {
        val target = name.trim()
        if (target.isEmpty()) return ""
        val members = GroupAdminMemberCache.members(room)
        members.firstOrNull { it.name.trim() == target }?.let { return it.id }
        members.firstOrNull { GroupAdminMemberCache.nameOf(room, it.id).trim() == target }?.let { return it.id }
        return ""
    }

    // ---- 上下文与执行 ----

    /**
     * 昵称是否真的取到了群昵称（5.5.22）：为空、等于 wxid、或形如 wxid_ 都视为「未取到」。
     * 未取到时冒充群主判定直接跳过，绝不用 @ 名单/正文参与比较。
     */
    private fun nickKnown(name: String, sender: String): Boolean =
        name.isNotBlank() && name != sender && !name.startsWith("wxid_")

    /** 本群当前开启的规则（5.5.21：判型日志里带上，便于一眼看出「没生效」是没开规则还是判型失败）。 */
    private fun enableList(): String {
        val ctx = lastContext ?: return "（未取到配置）"
        val cfg = GroupAdminStore.group(ctx, lastRoom)
        val on = GroupAdminRuleCatalog.DISPLAY_ORDER.filter { GroupAdminRuleCatalog.ruleOf(cfg, it).on }
        return if (on.isEmpty()) "（全部关闭）" else on.joinToString("、")
    }

    @Volatile
    private var lastContext: Context? = null

    @Volatile
    private var lastRoom: String = ""

    /** 冒充群主命中依据日志（5.5.21）：同群同昵称 10 秒内只打一条，避免刷屏。 */
    private val fakeLogAt = HashMap<String, Long>()

    /**
     * 5.5.26 冒充自检（按用户要求：测试环境已确认「最新版 + 框架重新勾选 + 强停微信 + 非群主账号」，
     * 因此不再用环境/豁免解释）。本行**正负都打**，用于确认冒充群主一项的实际落点：
     * 昵称是否取到、核心名是否等于群主称呼、是否与群主同名——命中与否都能从这一行判定。
     */
    private fun logFakeSelf(room: String, sender: String, now: Long) {
        val nick = runCatching { GroupAdminMemberCache.nameNow(room, sender) }.getOrDefault("")
        val key = room + "|" + nick
        if (now - (fakeSelfLogAt[key] ?: 0L) < 10_000L) return
        fakeSelfLogAt[key] = now
        if (fakeSelfLogAt.size > 200) fakeSelfLogAt.clear()
        val core = GroupAdminNoticeParser.coreName(nick)
        HLog.i(
            TAG + " 冒充自检：发送者=" + sender + " 昵称=" + nick + " 核心名=" + core +
                " 群主称呼=" + GroupAdminNoticeParser.OWNER_WORD +
                " 昵称整体即群主=" + (core == GroupAdminNoticeParser.OWNER_WORD)
        )
    }

    /**
     * 5.5.28 图片字段取证（按用户「缺样本只列需补样本、不做猜测式修法」的要求）：
     * 把图片消息 XML 里实际出现的**属性名与节点名**（不含任何值）打出来，用于判断
     * 「个人二维码／二维码截图」与普通图片是否存在可判别字段。含节流，避免刷日志。
     */
    private val nickForceLogAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** 5.5.31：强制实时校验被节流或读取失败时的兜底留痕（10 秒节流，避免刷日志）。 */
    private fun logNickForceFallback(room: String, sender: String, name: String, status: String, now: Long) {
        val key = room + "|" + sender + "|nickforce"
        if (now - (nickForceLogAt[key] ?: 0L) < 10_000L) return
        nickForceLogAt[key] = now
        if (nickForceLogAt.size > 200) nickForceLogAt.clear()
        HLog.i(
            TAG + " 昵称强校验兜底：状态=" + status + " 沿用昵称=「" + name + "」 发送者=" + sender + " 群 " + room
        )
    }

    private fun logImageFields(f: GroupAdminMessageFeatures) {
        if (!GroupAdminMessageTypes.isImage(f)) return
        val now = System.currentTimeMillis()
        if (now - (imgFieldLogAt["img"] ?: 0L) < 3_000L) return
        imgFieldLogAt["img"] = now
        val body = f.content
        val attrs = Regex("[<\\s]+([A-Za-z][A-Za-z0-9_]{1,30})\\s*=").findAll(body).map { it.groupValues[1] }.toSortedSet()
        val nodes = Regex("</?([A-Za-z][A-Za-z0-9_]{1,30})").findAll(body).map { it.groupValues[1] }.toSortedSet()
        HLog.i(TAG + " 图片字段取证：属性=" + attrs + " 节点=" + nodes + " xml长度=" + f.xml.length)
    }

    /** 5.5.29：拍一拍未达阈值时也留下依据（同 room|patter 10 秒一条），避免「无响应」无从定位。 */
    private fun logPatBelowThreshold(
        room: String, patter: String, patterName: String,
        distinct: Int, limit: Int, windowMinutes: Int, now: Long
    ) {
        val key = room + "|" + patter
        if (now - (patBelowLogAt[key] ?: 0L) < 10_000L) return
        patBelowLogAt[key] = now
        HLog.i(
            TAG + " 拍一拍：未达阈值不告警 · 拍者=" + patterName + "（" + patter + "）· 窗口 " + windowMinutes +
                " 分钟内已计 " + distinct + " 人 < 阈值 " + limit + " · 本条只记录不计警告 · 群 " + room
        )
    }

    private fun logFakeBasis(
        room: String,
        name: String,
        ownerName: String,
        from: String = "消息路径",
        atList: List<String> = emptyList()
    ) {
        val key = room + "|" + name
        val now = System.currentTimeMillis()
        if (now - (fakeLogAt[key] ?: 0L) < 10_000L) return
        fakeLogAt[key] = now
        HLog.i(
            TAG + " 冒充判定（" + from + "）：昵称来源=群成员资料（非正文、非@名单） 昵称=「" + name +
                "」 群主昵称=「" + ownerName + "」 依据=" + GroupAdminNoticeParser.impersonateBasis(name, ownerName) +
                " 核心名=「" + GroupAdminNoticeParser.coreName(name) + "」" +
                " 归一化昵称=「" + GroupAdminNoticeParser.normalizeName(name) + "」" +
                " @名单=[" + atList.joinToString("、") + "]（不参与冒充判定）" +
                " 命中=" + GroupAdminNoticeParser.impersonates(name, ownerName)
        )
    }

    private fun patXmlField(content: String, tag: String): String {
        if (content.isEmpty()) return ""
        val m = Regex(
            "<" + tag + ">\\s*(?:<!\\[CDATA\\[)?([^<\\]]+?)(?:\\]\\]>)?\\s*</" + tag + ">",
            RegexOption.IGNORE_CASE
        ).find(content)
        return m?.groupValues?.getOrNull(1)?.trim().orEmpty()
    }

    /** 判型日志（5.5.19 要求 6）：每条被评估的消息记录 type/appType/flags，便于下一轮直接取证。 */
    private val typeLogAt = HashMap<String, Long>()
    private val dupLogAt = HashMap<Long, Long>()
    private val fakeSelfLogAt = HashMap<String, Long>()
    private val patWarnedAt = HashMap<String, Long>()
    private val patBelowLogAt = HashMap<String, Long>()
    private val imgFieldLogAt = HashMap<String, Long>()

    private fun logTypeJudgement(ctx: Context, room: String, f: GroupAdminMessageFeatures) {
        lastContext = ctx
        lastRoom = room
        val now = System.currentTimeMillis()
        val key = room + "|" + GroupAdminMessageTypes.normalizeType(f.type) + "|" + GroupAdminMessageTypes.appTypeOf(f)
        val last = typeLogAt[key] ?: 0L
        if (now - last < TYPE_LOG_INTERVAL_MS) return   // 同类消息 5 秒内只记一条，避免刷日志
        typeLogAt[key] = now
        if (typeLogAt.size > 200) typeLogAt.clear()
        val kind = buildString {
            append("text=").append(GroupAdminMessageTypes.isText(f))
            append(" image=").append(GroupAdminMessageTypes.isImage(f))
            append(" video=").append(GroupAdminMessageTypes.isVideo(f))
            append(" voice=").append(GroupAdminMessageTypes.isVoice(f))
            append(" emoji=").append(GroupAdminMessageTypes.isEmoji(f))
            append(" sysEmojiText=").append(GroupAdminMessageTypes.isSystemEmojiText(f))
            append(" card=").append(GroupAdminMessageTypes.isCard(f))
            append(" music=").append(GroupAdminMessageTypes.isMusic(f))
            append(" mini=").append(GroupAdminMessageTypes.isMini(f))
            append(" link=").append(GroupAdminMessageTypes.isLink(f))
            append(" note=").append(GroupAdminMessageTypes.isNote(f))
            append(" file=").append(GroupAdminMessageTypes.isFile(f))
            append(" qrcode=").append(GroupAdminMessageTypes.isQrcode(f))
            append(" pat=").append(GroupAdminMessageTypes.isPat(f))
            append(" system=").append(GroupAdminMessageTypes.isSystem(f))
            append(" redpacket=").append(GroupAdminMessageTypes.isRedPacket(f))
            append(" transfer=").append(GroupAdminMessageTypes.isTransfer(f))
            append(" voip=").append(GroupAdminMessageTypes.isVoip(f))
            append(" location=").append(GroupAdminMessageTypes.isLocation(f))
            append(" videoNumber=").append(GroupAdminMessageTypes.isVideoNumber(f))
        }
        HLog.i(
            TAG + " 判型 type=" + f.type + " norm=" + GroupAdminMessageTypes.normalizeType(f.type) +
                " appType=" + GroupAdminMessageTypes.appTypeOf(f) +
                " 生效规则=" + enableList() +
                " flags=" + f.flags + " xml长度=" + f.xml.length +
                " 正文=" + f.content.take(bodyLimit(f)).replace("\n", " ") + " | " + kind
        )
    }

    /**
     * 判型日志正文长度（5.5.23 按用户要求）：图片/名片/二维码相关类型的正文（XML）加长到 1200 字，
     * 用于找「截图型二维码」的可判别特征字段；其余类型保持 400 字，避免刷日志。
     */
    private fun bodyLimit(f: GroupAdminMessageFeatures): Int {
        if (GroupAdminMessageTypes.isImage(f) || GroupAdminMessageTypes.isCard(f)) return 1200
        return 400
    }

    /** 拍一拍判定依据日志（5.5.23）：同群同类型 60 秒一条。 */
    private val patLogAt = HashMap<String, Long>()

    private fun logPatBasis(room: String, f: GroupAdminMessageFeatures, isPat: Boolean) {
        val key = room + "|" + GroupAdminMessageTypes.normalizeType(f.type) + "|" + isPat
        val now = System.currentTimeMillis()
        if (now - (patLogAt[key] ?: 0L) < 60_000L) return
        patLogAt[key] = now
        if (patLogAt.size > 200) patLogAt.clear()
        HLog.i(
            TAG + " 拍一拍判定：判为拍一拍=" + isPat + " type=" + f.type +
                " norm=" + GroupAdminMessageTypes.normalizeType(f.type) +
                " appType=" + GroupAdminMessageTypes.appTypeOf(f) +
                " 宿主标志=" + f.has(GroupAdminMessageTypes.F_PAT) +
                " patFrom=「" + f.patFrom + "」 patPatted=" + f.patPatted.size +
                " 正文标记=" + GroupAdminMessageTypes.hasPatText(f) +
                " 名片=" + GroupAdminMessageTypes.isCard(f) +
                " xml长度=" + f.xml.length + " flags=" + f.flags
        )
    }

    /** 规则链未命中日志（5.5.23）：同群 30 秒一条，带上已开启规则清单。 */
    private val noHitLogAt = HashMap<String, Long>()

    private fun logNoHit(room: String, f: GroupAdminMessageFeatures, ctx: GroupAdminMessageContext) {
        val now = System.currentTimeMillis()
        if (now - (noHitLogAt[room] ?: 0L) < 30_000L) return
        noHitLogAt[room] = now
        if (noHitLogAt.size > 200) noHitLogAt.clear()
        // 5.5.24：同时打出「类型层判定」与「文本/引用短路」——用于区分
        //   (a) 类型没识别（判定=false）、(b) 规则未开启、(c) 被 isTextLike 短路（media() 第一道门）。
        // 5.5.25：文本/引用消息若谓词成立却被「文本门」跳过，直接标出来（表情一类问题的落点）。
        val textGate = if (GroupAdminMessageTypes.isTextLike(f)) {
            val ids = ArrayList<String>()
            if (GroupAdminMessageTypes.isEmoji(f)) ids.add("emoji")
            if (GroupAdminMessageTypes.isNote(f)) ids.add("note")
            if (GroupAdminMessageTypes.isCard(f)) ids.add("card")
            if (GroupAdminMessageTypes.isImage(f)) ids.add("image")
            if (GroupAdminMessageTypes.isQrcode(f)) ids.add("qrcode")
            if (ids.isEmpty()) "" else " 文本门跳过=[" + ids.joinToString("、") + "]"
        } else ""
        val media = "card=" + GroupAdminMessageTypes.isCard(f) +
            " mini=" + GroupAdminMessageTypes.isMini(f) +
            " article=" + GroupAdminMessageTypes.isArticle(f) +
            " note=" + GroupAdminMessageTypes.isNote(f) +
            " music=" + GroupAdminMessageTypes.isMusic(f) +
            " emoji=" + GroupAdminMessageTypes.isEmoji(f) +
            " image=" + GroupAdminMessageTypes.isImage(f) +
            " qrcode=" + GroupAdminMessageTypes.isQrcode(f) +
            " video=" + GroupAdminMessageTypes.isVideo(f) +
            " voice=" + GroupAdminMessageTypes.isVoice(f) +
            " location=" + GroupAdminMessageTypes.isLocation(f) +
            " file=" + GroupAdminMessageTypes.isFile(f) +
            " redpacket=" + GroupAdminMessageTypes.isRedPacket(f) +
            " transfer=" + GroupAdminMessageTypes.isTransfer(f) +
            " textLike=" + GroupAdminMessageTypes.isTextLike(f)
        HLog.i(
            TAG + " 未命中任何规则：type=" + f.type + " norm=" + GroupAdminMessageTypes.normalizeType(f.type) +
                " appType=" + GroupAdminMessageTypes.appTypeOf(f) + " flags=" + f.flags +
                " 文本=" + ctx.isText + " 豁免=" + ctx.exemptBy + " 静音冷却OK=" + ctx.muteCooldownOk +
                " 发送者=" + ctx.sender + " 昵称=" + ctx.senderName +
                " 核心名=" + GroupAdminNoticeParser.coreName(ctx.senderName) +
                " 媒体判定[" + media + "]" + textGate + " 已开启=" + enableList()
        )
    }

    /** 本地版 bodyContent()：群聊正文形如成员 wxid 加冒号换行再加正文，判定前剥掉（与参照同款）。 */
    private fun localBody(raw: String, group: Boolean): String {
        if (!group) return raw
        val i = raw.indexOf(":\n")
        return if (i > 0) raw.substring(i + 2) else raw
    }

    private fun buildFeatures(message: WeChatMessageObserveApi.ObservedMessage): GroupAdminMessageFeatures {
        val raw = message.getContent().orEmpty()
        // 5.5.19：与参照 WeChatMessage.bodyContent() 同款，群聊正文带成员前缀，判定前剥掉。
        val stored = runCatching { message.storedMessage }.getOrNull()
        val content = (stored?.let { runCatching { it.bodyContent() }.getOrNull() } ?: "")
            .ifBlank { localBody(raw, message.isGroupChat()) }
        val xml = runCatching { message.xml }.getOrNull().orEmpty()
        val flags = HashSet<String>(24)
        // 以下每一条都对应参照 WeChatMessage 的同名谓词（屏蔽消息用的就是这套）。
        if (message.isText()) flags.add(GroupAdminMessageTypes.F_TEXT)
        if (message.isQuote()) flags.add(GroupAdminMessageTypes.F_QUOTE)
        if (message.isImage()) flags.add(GroupAdminMessageTypes.F_IMAGE)
        if (message.isVoice()) flags.add(GroupAdminMessageTypes.F_VOICE)
        if (message.isVideo()) flags.add(GroupAdminMessageTypes.F_VIDEO)
        if (message.isEmoji()) flags.add(GroupAdminMessageTypes.F_EMOJI)
        if (message.isLocation()) flags.add(GroupAdminMessageTypes.F_LOCATION)
        if (message.isFile()) flags.add(GroupAdminMessageTypes.F_FILE)
        if (message.isLink()) flags.add(GroupAdminMessageTypes.F_LINK)
        if (message.isMusic()) flags.add(GroupAdminMessageTypes.F_MUSIC)
        if (message.isNote()) flags.add(GroupAdminMessageTypes.F_NOTE)
        if (message.isShareCard()) flags.add(GroupAdminMessageTypes.F_CARD)
        if (message.isRedPacket()) flags.add(GroupAdminMessageTypes.F_REDPACKET)
        if (message.isTransfer()) flags.add(GroupAdminMessageTypes.F_TRANSFER)
        if (message.isVoip()) flags.add(GroupAdminMessageTypes.F_VOIP)
        if (message.isSystem()) flags.add(GroupAdminMessageTypes.F_SYSTEM)
        if (message.isPat()) flags.add(GroupAdminMessageTypes.F_PAT)
        if (message.isVideoNumberVideo()) flags.add(GroupAdminMessageTypes.F_VIDEONUMBER)
        return GroupAdminMessageFeatures(
            type = message.getType(),
            content = content,
            rawContent = raw,
            xml = xml,
            appType = GroupAdminMessageTypes.appType(content, raw.ifBlank { xml }),
            flags = flags,
            atList = runCatching { message.getAtUserList() }.getOrNull().orEmpty(),
            atAllFlag = runCatching { message.isNotifyAll() || message.isAnnounceAll() }.getOrDefault(false),
            atCountInText = GroupAdminNoticeParser.atCountInText(content),
            patFrom = runCatching { message.patFromUser }.getOrNull().orEmpty()
                .ifBlank { patXmlField(content, "fromusername") },
            patPatted = runCatching { message.patPattedUser }.getOrNull().orEmpty()
                .ifBlank { patXmlField(content, "pattedusername") }
                .split(',').map { it.trim() }.filter { it.isNotEmpty() }
        )
    }

    private fun buildContext(
        context: Context,
        message: WeChatMessageObserveApi.ObservedMessage,
        features: GroupAdminMessageFeatures,
        cfg: GroupConfig,
        room: String,
        sender: String,
        name: String,
        self: String,
        owner: String,
        exemptBy: String,
        now: Long
    ): GroupAdminMessageContext {
        val ownerName = if (owner.isNotBlank()) GroupAdminMemberCache.nameOf(room, owner) else ""
        val atList = features.atList
        val atDistinct = maxOf(atList.size, features.atCountInText)
        val isTextMessage = GroupAdminMessageTypes.isPlainText(message.getType(), features.content, features.appType)
        // 历史类状态：重复窗口与禁言冷却（规则关着就不消耗状态）。
        val repeatCount = if (GroupAdminRuleCatalog.ruleOf(cfg, "repeat").on && isTextMessage) {
            memory.noteRepeat(room, sender, features.content, now)
        } else {
            1
        }
        val muteOn = GroupAdminRuleCatalog.ruleOf(cfg, "mute").on
        val cooldown = if (muteOn) {
            GroupAdminRuleCatalog.paramInt(
                cfg, "mute", GroupAdminRuleCatalog.PARAM_MUTE_CD, GroupAdminRuleCatalog.DEFAULT_MUTE_CD
            )
        } else {
            0
        }
        val muteOk = if (muteOn) memory.muteAllowed(room, sender, now, cooldown) else false
        // 5.5.21（用户口径）：冒充群主命中依据写进日志——含「群主」/与群主完全同名两类都要能看出判据。
        runCatching {
            if (nickKnown(name, sender) && GroupAdminNoticeParser.impersonates(name, ownerName)) {
                logFakeBasis(room, name, ownerName, "消息路径", atList)
            }
        }
        return GroupAdminMessageContext(
            room = room,
            sender = sender,
            senderName = name,
            isSelf = sender.isNotBlank() && sender == self,
            isOwner = owner.isNotBlank() && sender == owner,
            isAgent = cfg.agents.any { it.id == sender },
            isAdmin = cfg.admins.any { it.id == sender },
            isWhite = cfg.white.any { it.id == sender },
            exempt = exemptBy.isNotEmpty(),
            exemptBy = exemptBy,
            msgType = message.getType(),
            appType = features.appType,
            content = features.content,
            atList = atList,
            atCountInText = features.atCountInText,
            memberCount = GroupAdminMemberCache.members(room).size,
            // 5.5.18 修：只有真·纯文本才算文本，表情包/卡片不参与超长等文本判定（第 2、10 条）
            isText = GroupAdminMessageTypes.isPlainText(message.getType(), features.content, features.appType) &&
                !GroupAdminRuleEngine.isMarkup(features.content),
            now = now,
            features = features,
            atDistinct = atDistinct,
            atAll = features.atAllFlag,
            repeatCount = repeatCount,
            patDistinctCount = 1,
            muteCooldownOk = muteOk,
            senderNameHasOwner = nickKnown(name, sender) &&
                GroupAdminNoticeParser.impersonates(name, ownerName),
            senderNameEqualsOwner = GroupAdminNoticeParser.normalizeName(ownerName).isNotEmpty() &&
                GroupAdminNoticeParser.normalizeName(name) == GroupAdminNoticeParser.normalizeName(ownerName),
            renameToOwnerName = false,
            renameRoomBySender = false
        )
    }

    /**
     * 命中后的执行：警告 / 直接踢 / 限时警告 / 只拉黑。
     * 踢人一律走 [GroupAdminActions.kick]（先发告别语 → 再移出 → 再拉黑）。
     */
    private fun dispatch(
        context: Context,
        room: String,
        sender: String,
        name: String,
        hit: GroupAdminDecision.Hit,
        now: Long
    ) {
        when (hit.action) {
            GroupAdminHitAction.GRACE_WARN -> {
                val sent = GroupAdminActions.warn(room, sender, hit.warnText)
                val graceMinutes = GroupAdminRuleCatalog.paramInt(
                    GroupAdminStore.group(context, room), "fakeowner",
                    GroupAdminRuleCatalog.PARAM_FAKE_GRACE, GroupAdminRuleCatalog.DEFAULT_FAKE_GRACE
                )
                memory.setFakePending(room, sender, System.currentTimeMillis(), graceMinutes * 60_000L)
                HLog.i(
                    TAG + " 命中规则「" + hit.rule + "」→ 限时警告（" + graceMinutes + " 分钟）：" + hit.warnText +
                        " · 群 " + room + " · 目标 " + name + "（" + sender + "）· 发送=" + sent + " · " + hit.detail
                )
                record("规则 " + hit.rule + " 限时警告 " + graceMinutes + " 分钟：" + name)
            }

            GroupAdminHitAction.BLACKLIST_ONLY -> {
                GroupAdminStore.update(context, room) { cfg ->
                    GroupAdminStore.addBlack(cfg, sender, name, hit.unbanHours, now)
                }
                val text = if (hit.unbanHours <= 0) "永久" else hit.unbanHours.toString() + " 小时"
                HLog.i(TAG + " 命中规则「" + hit.rule + "」→ 只拉黑（" + text + "）：" + name + "（" + sender + "）")
                record("规则 " + hit.rule + " 拉黑 " + name + "（" + text + "）")
            }

            GroupAdminHitAction.KICK_NOW -> {
                GroupAdminStore.warnReset(context, room, sender, hit.rule)
                kickNow(context, room, sender, name, hit)
            }

            GroupAdminHitAction.GM_HIT -> {
                // 5.5.19 修（要求 4）：警告次数与规则一一对应（room|wxId|rule），且**始终计数**；
                // 警告开关只决定是否发提示语，不再让「开关关闭」变成「一次即踢」。
                val count = GroupAdminStore.warnInc(context, room, sender, hit.rule)
                HLog.i(
                    TAG + " 计数：规则=" + hit.rule + " 当前=" + count + "/" + hit.warnCount +
                        " 警告开关=" + hit.warnEnable + " 计数键=" + room + "|" + sender + "|" + hit.rule
                )
                when (val step = GroupAdminRuleEngine.hitStep(hit, count)) {
                    is GroupAdminHitStep.Warn -> {
                        val sent = if (hit.warnEnable) GroupAdminActions.warn(room, sender, step.text) else false
                        HLog.i(
                            TAG + " 命中规则「" + hit.rule + "」→ 警告 " + step.now + "/" + step.need +
                                "：" + step.text + " · 群 " + room + " · 目标 " + name + "（" + sender + "）" +
                                " · 发送=" + sent + "（警告开关=" + hit.warnEnable + "）· " + hit.detail
                        )
                        record("规则 " + hit.rule + " 警告 " + step.now + "/" + step.need + "：" + name)
                    }

                    GroupAdminHitStep.Kick -> {
                        GroupAdminStore.warnReset(context, room, sender, hit.rule)
                        kickNow(context, room, sender, name, hit)
                    }
                }
            }
        }
    }

    private fun kickNow(
        context: Context,
        room: String,
        sender: String,
        name: String,
        hit: GroupAdminDecision.Hit
    ) {
        val plan = GroupAdminActions.plan(
            context, room, sender, name, hit.reason, hit.rule, hit.addBlack, hit.unbanHours
        )
        val result = GroupAdminActions.kick(context, plan)
        HLog.i(
            TAG + " 命中规则「" + hit.rule + "」→ 踢人：" + hit.detail + " · 理由「" + hit.reason +
                "」· 群 " + room + " · 目标 " + name + "（" + sender + "）" +
                " · 提示=" + result.sent + " 移出=" + result.kicked + " 拉黑=" + result.blacked
        )
        record(
            "规则 " + hit.rule + " 踢人：" + name + "（" + hit.detail + "）· 提示=" +
                result.sent + " 移出=" + result.kicked + " 拉黑=" + result.blacked
        )
    }

    private fun prune(context: Context, room: String, now: Long) {
        if (now - lastPruneAt < PRUNE_INTERVAL_MS) return
        lastPruneAt = now
        memory.prune(now)
        var removed = 0
        GroupAdminStore.update(context, room) { cfg -> removed = GroupAdminStore.purgeExpired(cfg, now) }
        if (removed > 0) {
            HLog.i(TAG + " 懒清理：群 " + room + " 清理到期黑名单 " + removed + " 条")
            record("懒清理：清理到期黑名单 " + removed + " 条")
        }
    }

    private fun duplicate(msgId: Long): Boolean {
        if (msgId <= 0L) return false
        synchronized(recentIds) {
            if (!recentIds.add(msgId)) {
                // 5.5.24 诊断：被去重短路时留下痕迹（同 msgId 30 秒内只打一条）。
                val now = System.currentTimeMillis()
                if (now - (dupLogAt[msgId] ?: 0L) > 30_000L) {
                    dupLogAt[msgId] = now
                    if (dupLogAt.size > 200) dupLogAt.clear()
                    HLog.i(TAG + " 跳过：msgId 重复 · msgId=" + msgId)
                }
                return true
            }
            recentOrder.addLast(msgId)
            while (recentOrder.size > 64) recentIds.remove(recentOrder.removeFirst())
            return false
        }
    }

    private fun selfWxId(): String = try {
        WeChatApis.account()?.selfWxId().orEmpty()
    } catch (t: Throwable) {
        ""
    }

    /** 群主 wxid：优先用成员缓存（面板刷新过就有），否则现查一次并顺手触发一次异步刷新。 */
    private fun ownerOf(room: String): String {
        val cached = GroupAdminMemberCache.ownerOf(room)
        if (cached.isNotBlank()) return cached
        if (!GroupAdminMemberCache.isLoaded(room)) {
            runCatching { GroupAdminMemberCache.refreshAsync(room) { } }
        }
        return try {
            WeChatApis.contact().chatrooms()?.getOwner(room).orEmpty()
        } catch (t: Throwable) {
            ""
        }
    }

    /** 供面板「运行期状态」显示，便于真机确认入口是否真的接上了。 */
    fun tag(): String = TAG
}
