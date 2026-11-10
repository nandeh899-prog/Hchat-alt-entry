package com.aeonlab.toolkit.hooks.items.groupadmin

import android.content.Context
import com.aeonlab.toolkit.hooks.api.contact.WeChatChatroomChangeApi
import com.aeonlab.toolkit.hooks.api.core.WeChatApis
import com.aeonlab.toolkit.hooks.core.FeatureContext
import com.aeonlab.toolkit.utils.HLog

/**
 * 群成员事件运行期：接 `nonadmin`（自动移除非管理员拉的成员）与 `leavebl`（退群自动拉黑）。
 *
 * 数据来源：`WeChatApis.chatroomChanges()`（模块既有 `WeChatChatroomChangeApi`，订阅群资料/成员表
 * 变更），与 `ScriptMemberChangeHook` 同一套「快照 diff」做法：只有前后成员表真的变了才算事件，
 * 首个快照只建立基线不产生事件，5 秒内同一事件去重。
 *
 * 前提链与消息链一致：本群已纳管 → 全局开关开 → 本群总开关开 → 目标成员不在豁免档；
 * 邀请人确认不了时**不处理**（宁可漏判也不误踢），并在最近处理记录里留痕。
 */
object GroupAdminMemberRuntime {

    private const val TAG = "[AeonLab:GroupAdmin]"
    private const val DEDUP_WINDOW_MS = 5_000L
    private const val BLACK_JOIN_REASON = "黑名单成员进群"

    private val snapshots = HashMap<String, Set<String>>()
    private val recentEvents = HashMap<String, Long>()

    @Volatile
    private var installed = false

    fun isInstalled(): Boolean = installed

    fun install(context: FeatureContext): Boolean {
        if (installed) return true
        val api = runCatching { WeChatApis.chatroomChanges() }.getOrNull()
        if (api == null || !api.isAvailable()) {
            HLog.e(TAG + " 群成员变更 API 未就绪：nonadmin / leavebl 两条规则不会生效")
            return false
        }
        val host = context.hostContext()
        runCatching { preload() }
        api.subscribe { change -> runCatching { onChanged(host, change) } }
        installed = true
        GroupAdminMessageRuntime.note("群成员事件入口已接入（nonadmin / leavebl）")
        HLog.i(TAG + " 群成员事件入口已接入：规则 nonadmin、leavebl")
        return true
    }

    private fun preload() {
        val chatrooms = WeChatApis.contact().chatrooms() ?: return
        for (room in chatrooms.getChatrooms()) {
            val id = room.chatroomId.trim()
            if (id.isEmpty() || !id.contains("@chatroom")) continue
            val members = room.memberIds.asSequence().map { it.trim() }.filter { it.isNotEmpty() }
                .toCollection(LinkedHashSet())
            if (members.isEmpty()) continue
            snapshots.putIfAbsent(id, members)
        }
    }

    private fun onChanged(host: Context, change: WeChatChatroomChangeApi.ChatroomChange) {
        // 5.5.19（要求 3）：群变更回调落点——先记原始事件，便于核对「进群即踢」链路是否被触发。
        runCatching {
            HLog.i(
                TAG + " 群变更回调：room=" + change.chatroomId() + " 成员表变化=" +
                    change.mayMemberListChanged() + " 群资料变化=" + change.mayRoomDataChanged()
            )
        }
        if (!change.mayMemberListChanged()) {
            // 5.5.29 真机取证：改「我的群昵称」也会触发本回调，但成员表没变。原实现直接 return，
            // 于是成员缓存里的旧昵称长期不失效（20:25 改名后模块仍读到旧名）。定点修复：一并失效。
            if (change.mayRoomDataChanged()) GroupAdminMemberCache.invalidate(change.chatroomId().trim())
            return
        }
        val room = change.chatroomId().trim()
        if (room.isEmpty()) return
        // 5.5.32 修复：成员表变化分支同样要处理「群资料变化」（改群昵称会同时置这两位置 true，
        // 真机 18:15 日志即此形态）。原实现只在上面的「成员表未变化」分支 invalidate，
        // 本分支不重置昵称分层窗口 → 改名后 30 秒内仍可能用窗口前读到的旧昵称，导致冒充漏判。
        if (change.mayRoomDataChanged()) GroupAdminMemberCache.invalidateNick(room)
        val current = currentMembers(change, room)
        if (current.isEmpty()) return
        val previous = snapshots.put(room, current)
        pruneRecent()
        if (previous == null) {
            // 5.5.19（要求 3）：第一次见到本群（例如微信刚启动、模块内存快照被清空）时，
            // 没有「上次成员表」可比，旧实现直接 return，等于放过此刻已在群里的黑名单成员。
            // 这里改为对当前成员做一次有界黑名单结算：黑名单成员本就不该留在群里。
            runCatching { sweepBlacklist(host, room, current) }
            return
        }
        for (wxid in current - previous) {
            if (duplicate("join|" + room + "|" + wxid)) continue
            runCatching { onJoin(host, room, wxid) }
        }
        runCatching {
            HLog.i(
                TAG + " 群成员增删：新增 " + (current - previous).size + " 人、离开 " + (previous - current).size +
                    " 人 · 现有 " + current.size + " 人 · 群 " + room
            )
        }
        for (wxid in previous - current) {
            if (duplicate("leave|" + room + "|" + wxid)) continue
            // 5.5.42：离开即清验证状态（照原实现 gvOnLeave），否则旧定时器/兜底扫描会对已退群的人发超时提示
            runCatching { GroupAdminVerify.onLeave(room, wxid) }
            runCatching { onLeave(host, room, wxid) }
        }
    }

    /**
     * 进群即查黑名单：黑名单成员一进群就移出，不必等他发言（5.5.18 修：第 4 条）。
     * 与 nonadmin 规则开关无关；仍走「先发告别语 → 移出群聊 → 不重复拉黑」。
     */
    private fun kickIfBlacklisted(host: Context, room: String, member: String) {
        // 5.5.19（要求 3）：这条路径的每个提前返回都落日志，便于下一轮直接在日志里看出卡在哪一步。
        if (room.isBlank() || member.isBlank()) {
            HLog.i(TAG + " 进群黑名单判定：跳过（room/member 为空）· room=" + room + " member=" + member)
            return
        }
        if (member == selfWxId()) {
            HLog.i(TAG + " 进群黑名单判定：跳过（是自己）· " + member)
            return
        }
        val cfg = runCatching { GroupAdminStore.group(host, room) }.getOrNull()
        if (cfg == null) {
            HLog.i(TAG + " 进群黑名单判定：跳过（群配置读取失败）· 群 " + room)
            return
        }
        if (!GroupAdminStore.isManaged(host, room)) {
            HLog.i(TAG + " 进群黑名单判定：跳过（本群未纳入管理/未验证）· 群 " + room + " · 黑名单条数=" + cfg.black.size)
            return
        }
        val now = System.currentTimeMillis()
        val black = runCatching { GroupAdminStore.blackHit(cfg, member, now) }.getOrNull()
        if (black == null) {
            HLog.i(TAG + " 进群黑名单判定：未命中 · " + member + " · 群 " + room +
                " · 黑名单条数=" + cfg.black.size + " · 缓存=" + cfg.black.map { it.id + "/" + it.name }.take(5))
            return
        }
        val name = GroupAdminMemberCache.nameNow(room, member).ifBlank { member }
        val plan = GroupAdminActions.plan(host, room, member, name, BLACK_JOIN_REASON, null, false)
        val result = GroupAdminActions.kick(host, plan)
        val remaining = GroupAdminStore.blackTimeText(black.expireAt, now)
        HLog.i(
            TAG + " 进群即拉黑移除：" + name + " · 剩余 " + remaining +
                " · 群 " + room + " · 移出=" + result.kicked
        )
        GroupAdminMessageRuntime.note("进群即移除黑名单成员 " + name + "（剩余 " + remaining + "）")
        GroupAdminMemberCache.invalidate(room)
    }

    /** 对当前成员做一次黑名单结算（有界，最多 500 人；只对黑名单命中者动手）。 */
    private fun sweepBlacklist(host: Context, room: String, members: Set<String>) {
        if (!GroupAdminStore.isManaged(host, room)) {
            HLog.i(TAG + " 首次见到本群：未纳入管理，跳过黑名单扫描 · 群 " + room)
            return
        }
        var hit = 0
        for (wxid in members.take(500)) {
            if (wxid == selfWxId()) continue
            if (duplicate("join|" + room + "|" + wxid)) continue
            val before = hit
            runCatching { kickIfBlacklisted(host, room, wxid) }
            if (GroupAdminStore.blackHit(GroupAdminStore.group(host, room), wxid, System.currentTimeMillis()) != null) {
                if (hit == before) hit++
            }
        }
        HLog.i(TAG + " 首次见到本群：已扫描 " + members.size + " 名成员的黑名单 · 群 " + room)
    }

    private fun currentMembers(change: WeChatChatroomChangeApi.ChatroomChange, room: String): Set<String> {
        val ids = change.chatroom?.memberIds
            ?: WeChatApis.contact().chatrooms()?.getMemberIds(room)
            ?: emptyList()
        return ids.asSequence().map { it.trim() }.filter { it.isNotEmpty() }.toCollection(LinkedHashSet())
    }

    // ---- nonadmin：自动移除非管理员拉的成员 ----

    private fun onJoin(host: Context, room: String, member: String) {
        GroupAdminMemberCache.invalidate(room) // 成员表变了，缓存立刻失效（第 14 条）
        // 5.5.19：进群路径落点日志（要求 3）——先记「进群事件已收到」，再记黑名单判定结果。
        HLog.i(TAG + " 进群事件：room=" + room + " member=" + member +
            " managed=" + GroupAdminStore.isManaged(host, room) +
            " cacheInvalidated=true")
        kickIfBlacklisted(host, room, member)
        // 规格 3.2：进群验证（与黑名单、nonadmin 各自独立；刚被黑名单踢掉的成员不再发欢迎与题目）。
        if (GroupAdminStore.isManaged(host, room)) {
            val blacklisted = runCatching {
                GroupAdminStore.blackHit(
                    GroupAdminStore.group(host, room), member, System.currentTimeMillis()
                )
            }.getOrNull()
            if (blacklisted == null) {
                val verifyName = GroupAdminMemberCache.nameNow(room, member).ifBlank { member }
                runCatching { GroupAdminVerify.onJoin(host, room, member, verifyName) }
            } else {
                HLog.i(TAG + " 进群验证：跳过（该成员在黑名单里，已按黑名单处理）· " + member + " · 群 " + room)
            }
        }
        if (!prerequisites(host, room, "nonadmin")) return
        val rule = GroupAdminRuleCatalog.ruleOf(GroupAdminStore.group(host, room), "nonadmin")
        if (!rule.on) return
        val cfg = GroupAdminStore.group(host, room)
        val self = selfWxId()
        val owner = ownerOf(room)
        if (GroupAdminStore.exemptBy(cfg, member, self, owner).isNotEmpty()) return

        val inviteeName = GroupAdminMemberCache.nameOf(room, member).ifBlank { member }
        val notice = GroupAdminMessageRuntime.takeInviter(room, inviteeName, member)
        if (notice == null) {
            GroupAdminMessageRuntime.note("入群 " + inviteeName + "：120 秒内没抓到邀请人通知，按「无法确认邀请人」跳过")
            HLog.i(TAG + " nonadmin 跳过：无法确认邀请人 · " + inviteeName + "（" + member + "）· 群 " + room)
            return
        }
        // 5.5.28 真机取证（2026-11-05 18:21:16，install=5.5.24）：入群通知里邀请人 wxid 已解析成功
        // （日志「邀请人配对：新成员=…（wxid_95wgia0a0lbj22）← 邀请人=༗࿐ི௸㎕࿐（wxid_5oj1u1p4gh2v22）· 间隔 2 秒」），
        // 但这里只拿昵称回查成员表；该昵称带装饰字符、与成员表里的昵称对不上 → 恒走「邀请人无法解析」
        // →「非管理员拉人」整条链路不动作。定点修复：优先采用通知里已解析的邀请人 wxid，昵称回查只作兜底。
        val inviterId = notice.inviterId.ifBlank { resolveInviter(room, notice.inviterName, self) }
        if (inviterId.isEmpty()) {
            GroupAdminMessageRuntime.note("入群 " + inviteeName + "：邀请人「" + notice.inviterName + "」在成员表里对不上，跳过")
            HLog.i(TAG + " nonadmin 跳过：邀请人无法解析 · " + notice.inviterName + " · 群 " + room)
            return
        }
        if (inviterId == self) return
        if (GroupAdminStore.exemptBy(cfg, inviterId, self, owner).isNotEmpty()) {
            GroupAdminMessageRuntime.note("入群 " + inviteeName + "：邀请人属豁免档，跳过")
            return
        }

        val spec = GroupAdminRuleCatalog.spec("nonadmin")
        val reason = rule.reason.ifBlank { spec?.defaultReason ?: GroupAdminRuleCatalog.defaultReason("nonadmin") }
        val both = GroupAdminRuleCatalog.paramBool(
            cfg, "nonadmin", GroupAdminRuleCatalog.PARAM_NONADMIN_BOTH, false
        )
        val inviterName = GroupAdminMemberCache.nameOf(room, inviterId).ifBlank { notice.inviterName }

        val inviteePlan = GroupAdminActions.plan(
            host, room, member, inviteeName,
            reason + "（邀请人：" + inviterName + "）", "nonadmin", true, rule.unbanHours
        )
        val invitee = GroupAdminActions.kick(host, inviteePlan)
        HLog.i(
            TAG + " nonadmin：移出被邀请人 " + inviteeName + "（" + member + "）· 邀请人 " + inviterName +
                "（" + inviterId + "）· 群 " + room + " · 提示=" + invitee.sent + " 移出=" + invitee.kicked +
                " 拉黑=" + invitee.blacked
        )
        GroupAdminMessageRuntime.note(
            "nonadmin 移出 " + inviteeName + "（邀请人 " + inviterName + "）· 移出=" + invitee.kicked
        )

        if (both) {
            val inviterPlan = GroupAdminActions.plan(
                host, room, inviterId, inviterName, reason + "（非管理员拉人）", "nonadmin", true, rule.unbanHours
            )
            val inviter = GroupAdminActions.kick(host, inviterPlan)
            HLog.i(
                TAG + " nonadmin（子开关）：一并移出邀请人 " + inviterName + "（" + inviterId + "）· 群 " + room +
                    " · 移出=" + inviter.kicked + " 拉黑=" + inviter.blacked
            )
            GroupAdminMessageRuntime.note("nonadmin 一并移出邀请人 " + inviterName + "· 移出=" + inviter.kicked)
        }
    }

    // ---- leavebl：退群自动加入黑名单 ----

    private fun onLeave(host: Context, room: String, member: String) {
        // 5.5.19（要求 4）：退群即清该成员在本群的警告计数，与「踢人后归零」策略一致。
        runCatching { GroupAdminStore.warnClearAll(context = host, room = room, wxId = member) }
        if (!prerequisites(host, room, "leavebl")) return
        val rule = GroupAdminRuleCatalog.ruleOf(GroupAdminStore.group(host, room), "leavebl")
        if (!rule.on) return
        val cfg = GroupAdminStore.group(host, room)
        val self = selfWxId()
        val owner = ownerOf(room)
        if (GroupAdminStore.exemptBy(cfg, member, self, owner).isNotEmpty()) return
        val now = System.currentTimeMillis()
        if (GroupAdminStore.blackHit(cfg, member, now) != null) {
            GroupAdminMessageRuntime.note("退群成员已在黑名单，保留原时限（不覆盖）")
            return
        }
        val name = GroupAdminMemberCache.nameOf(room, member).ifBlank { member }
        var added = false
        GroupAdminStore.update(host, room) { c ->
            added = GroupAdminStore.addBlack(c, member, name, rule.unbanHours, now) != null
        }
        if (added) {
            val hours = rule.unbanHours
            val text = if (hours <= 0) "永久" else hours.toString() + " 小时"
            HLog.i(TAG + " leavebl：退群成员 " + name + "（" + member + "）加入黑名单 · " + text + " · 群 " + room)
            GroupAdminMessageRuntime.note("leavebl 拉黑 " + name + "（" + text + "）")
        }
    }

    // ---- 共同前提与工具 ----

    private fun prerequisites(host: Context, room: String, rule: String): Boolean {
        if (!room.contains("@chatroom")) return false
        if (!GroupAdminStore.isManaged(host, room)) return false
        if (!GroupAdminSettings.isEnabled(host)) return false
        if (!GroupAdminStore.group(host, room).master) return false
        return GroupAdminRuleCatalog.ruleOf(GroupAdminStore.group(host, room), rule).on
    }

    /** 把系统消息里的邀请人名字换成 wxid：成员表按群昵称/昵称精确匹配，失败返回空。 */
    private fun resolveInviter(room: String, inviterName: String, self: String): String {
        val name = inviterName.trim()
        if (name.isEmpty()) return ""
        if (name == "你" || name == "我" || name == "本人") return self
        val members = GroupAdminMemberCache.members(room)
        members.firstOrNull { it.name.trim() == name }?.let { return it.id }
        members.firstOrNull { GroupAdminMemberCache.nameOf(room, it.id).trim() == name }?.let { return it.id }
        members.firstOrNull { GroupAdminMemberCache.nameOf(room, it.id).contains(name) && name.length >= 2 }
            ?.let { return it.id }
        return ""
    }

    private fun selfWxId(): String = try {
        WeChatApis.account()?.selfWxId().orEmpty()
    } catch (t: Throwable) {
        ""
    }

    private fun ownerOf(room: String): String {
        val cached = GroupAdminMemberCache.ownerOf(room)
        if (cached.isNotBlank()) return cached
        return try {
            WeChatApis.contact().chatrooms()?.getOwner(room).orEmpty()
        } catch (t: Throwable) {
            ""
        }
    }

    private fun duplicate(key: String): Boolean {
        val now = System.currentTimeMillis()
        val last = recentEvents[key]
        if (last != null && now - last < DEDUP_WINDOW_MS) return true
        recentEvents[key] = now
        return false
    }

    private fun pruneRecent() {
        val now = System.currentTimeMillis()
        recentEvents.entries.removeAll { now - it.value > DEDUP_WINDOW_MS * 4 }
        if (snapshots.size > 2_048) {
            val keep = snapshots.keys.toList().takeLast(1_024)
            snapshots.keys.retainAll(keep.toSet())
        }
    }
}
