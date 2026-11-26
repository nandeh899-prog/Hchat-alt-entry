package com.aeonlab.toolkit.hooks.items.groupadmin

import android.os.Handler
import android.os.Looper
import com.aeonlab.toolkit.hooks.api.core.WeChatApis
import java.util.concurrent.ConcurrentHashMap

/**
 * 群成员缓存。
 *
 * 读取口径对齐模块既有实现 `ScriptWaBridge.readGroupMemberIds`：**三源合并 + 读空重试**。
 * 单读 `chatrooms.getMemberIds`（= `SELECT memberlist FROM chatroom`）对大群经常只是子集甚至为空，
 * 完整名单在 `chatroom.roomdata`，由 `contacts.getGroupMemberRoomDisplayNames(room).keys` 暴露
 * （该 map 的 key 就是 roomdata 里的成员 username）。第三源 `contacts.getGroupMemberIds` 补齐
 * 只有 memberlist 的行。
 *
 * 展示名回退链（`getGroupMemberRoomDisplayNames` 只对**设过群昵称**的成员有值，其余为空串）：
 * 群昵称表 → 好友备注/昵称 → 微信内部群昵称方法 → wxid。
 */
object GroupAdminMemberCache {

    /** 一次读取的账目，面板直接显示，便于对照微信自身成员数判断哪一源偏少。 */
    data class MemberReadStat(
        val memberlist: Int,
        val roomNames: Int,
        val total: Int,
        val named: Int
    )

    private class RawRead(
        val ids: List<String>,
        val roomNames: Map<String, String>,
        val memberlist: Int
    )

    /** 缓存有效期：超时后再次读取会自动重读（5.5.18 修：第 14 条，避免必须重启微信）。 */
    private const val TTL_MS = 120_000L

    private const val MAX_ATTEMPTS = 4
    private const val RETRY_DELAY_MS = 150L

    /**
     * 5.5.31 分层读取：群昵称的实时读取兜底间隔（**每群**）。
     * 窗口内直接用缓存，超时后由该群下一条消息强制实时读一次并回填，使「每条消息一次宿主成员列表读取」
     * 降为「每群每 30 秒最多一次」。群资料变化时 invalidate() 会清缓存与时间戳，回调可达则仍即时生效。
     */
    private const val NICK_TTL_MS = 30_000L
    private val nickLiveAt = ConcurrentHashMap<String, Long>()

    /**
     * 5.5.32 修复（真机 18:15 回归取证）：保存「上次实时读取得到的成员昵称」，key = room|wxId。
     * 5.5.31 窗口内直接返回 nameOf()，而 nameOf() 优先取成员表快照（刷新时的旧昵称），于是同一处理流程里
     * 先由诊断读到的实时新名会被旧名覆盖 —— 真机表现为「冒充自检读到群主、判定链却用旧名」→ 冒充群主失效。
     * 该表只由实时读取写入，窗口内优先于 nameOf()，保证「窗口时间戳」与「窗口内返回的值」同源。
     */
    private val nickLive = ConcurrentHashMap<String, String>()

    /** 5.5.31 同一群同一发送者「强制实时校验」的最小间隔（防刷屏式误触发拖慢消息路径）。 */
    private const val NICK_FORCE_MIN_INTERVAL_MS = 5_000L
    private val nickForcedAt = ConcurrentHashMap<String, Long>()

    /** 强制实时校验的状态（用于说明与日志）。 */
    const val STATUS_LIVE = "实时命中"
    const val STATUS_THROTTLED = "节流跳过"
    const val STATUS_FAILED = "读取失败"
    const val STATUS_SKIPPED = "参数缺失"

    /** 单条强制实时校验的结果：name 仅在 STATUS_LIVE 时非空。 */
    data class ForceNick(val name: String, val status: String)

    private val members = ConcurrentHashMap<String, List<MemberRef>>()
    private val stats = ConcurrentHashMap<String, MemberReadStat>()
    private val names = ConcurrentHashMap<String, String>()
    private val groupNames = ConcurrentHashMap<String, String>()
    private val owners = ConcurrentHashMap<String, String>()
    private val loading = ConcurrentHashMap.newKeySet<String>()
    private val loadedAt = ConcurrentHashMap<String, Long>()
    private val handler = Handler(Looper.getMainLooper())

    fun members(room: String): List<MemberRef> = members[room].orEmpty()

    fun isLoaded(room: String): Boolean = members.containsKey(room)

    /** 缓存是否过期（没读过算过期）。 */
    fun isStale(room: String, ttlMs: Long = TTL_MS): Boolean {
        val at = loadedAt[room] ?: return true
        return System.currentTimeMillis() - at > ttlMs
    }

    /** 明确失效：进群/退群/踢人/拉人、进入群页、用户手动刷新时调用（第 14 条）。 */
    fun invalidate(room: String) {
        if (room.isBlank()) return
        loadedAt.remove(room)
        members.remove(room)
        // 5.5.31 分层读取配套：同时清掉该群的昵称回填与实时读取时间戳。
        // 否则 nameNow 会从 names 表读到旧昵称（5.5.29 靠「每条消息实时读」掩盖了这一点）。
        invalidateNick(room)
    }

    /**
     * 5.5.32 修复：只重置昵称分层窗口（不动成员表快照与加载时间）。
     * 群资料变化（改群昵称/群名等）时由 onChanged 调用 —— 真机 18:15 日志里「成员表变化=true 且
     * 群资料变化=true」的分支原先不会走到 invalidate()，于是改名后 30 秒窗口内仍返回窗口前的旧昵称。
     */
    fun invalidateNick(room: String) {
        if (room.isBlank()) return
        names.keys.removeIf { it.startsWith(room + "|") }
        nickLive.keys.removeIf { it.startsWith(room + "|") }
        nickLiveAt.remove(room)
    }

    fun invalidateAll() {
        loadedAt.clear()
        members.clear()
        stats.clear()
    }

    /**
     * 需要名字时按需实时读一次（不进缓存）：用于群昵称/好友备注都还没进缓存的场景
     * （5.5.18 修：第 9 条「冒充群主」判定依赖群昵称）。
     */
    fun nameNow(room: String, wxId: String): String {
        if (room.isBlank() || wxId.isBlank()) return ""
        // 5.5.29 真机取证（20:09–20:26 日志）：改名后这里**先返回缓存里的旧昵称**，导致
        //   · 20:10 改名回「云裳…」后仍连续三次按「群主」告警；
        //   · 20:25 改名成「云裳…群主」后反而一直读到旧名「云裳…」→ 冒充不再命中。
        // 5.5.31 分层读取（用户 2026-11-06 批准，接受最长 NICK_TTL_MS 的延迟）：
        // 默认取缓存；同一群距上次实时读取超过 NICK_TTL_MS 时，本次强制实时读一次并回填缓存。
        // 5.5.32 修复：窗口内先取「上次实时读取到的该成员昵称」（nickLive），再退回缓存 nameOf()。
        // 5.5.31 顺序相反，导致同一条消息里刚实时读到的「群主」被成员表快照里的旧昵称覆盖。
        val key = room + "|" + wxId
        val cached = nameOf(room, wxId)
        val at = nickLiveAt[room] ?: 0L
        val now = System.currentTimeMillis()
        if (at > 0L && now - at <= NICK_TTL_MS) {
            val liveCached = nickLive[key].orEmpty()
            if (liveCached.isNotBlank() && liveCached != wxId) return liveCached
            if (cached.isNotBlank() && cached != wxId) return cached
        }
        val live = runCatching {
            val roomNames = WeChatApis.contact().contacts()?.getGroupMemberRoomDisplayNames(room).orEmpty()
            displayNameOf(room, wxId, roomNames)
        }.getOrDefault("")
        if (live.isNotBlank() && live != wxId) {
            // 5.5.32 修复：只有真正读到名字才刷新窗口（5.5.31 读取失败也刷新时间戳，会白白挡住 30 秒内的重读）。
            nickLiveAt[room] = now
            nickLive[key] = live
            names[key] = live
            return live
        }
        return cached.ifBlank { live }
    }

    /**
     * 5.5.31 收紧（用户 2026-11-06 批准）：高风险规则**疑似命中**时对单条消息强制实时读一次群昵称，
     * 由调用方用实时值做最终判定。仅在疑似命中时被调用，不会退化为每条消息实时读；
     * 同一群同一发送者在 NICK_FORCE_MIN_INTERVAL_MS 内只强读一次（返回 STATUS_THROTTLED）。
     */
    fun nameForceLive(room: String, wxId: String): ForceNick {
        if (room.isBlank() || wxId.isBlank()) return ForceNick("", STATUS_SKIPPED)
        val key = room + "|" + wxId
        val now = System.currentTimeMillis()
        val last = nickForcedAt[key] ?: 0L
        if (last > 0L && now - last < NICK_FORCE_MIN_INTERVAL_MS) return ForceNick("", STATUS_THROTTLED)
        nickForcedAt[key] = now
        if (nickForcedAt.size > 200) nickForcedAt.clear()
        val live = runCatching {
            val roomNames = WeChatApis.contact().contacts()?.getGroupMemberRoomDisplayNames(room).orEmpty()
            displayNameOf(room, wxId, roomNames)
        }.getOrDefault("")
        if (live.isBlank() || live == wxId) return ForceNick("", STATUS_FAILED)
        names[room + "|" + wxId] = live
        nickLive[room + "|" + wxId] = live
        nickLiveAt[room] = now
        return ForceNick(live, STATUS_LIVE)
    }

    fun stat(room: String): MemberReadStat? = stats[room]

    fun groupName(room: String): String = groupNames[room].orEmpty()

    fun ownerOf(room: String): String = owners[room].orEmpty()

    fun nameOf(room: String, wxId: String): String {
        if (wxId.isBlank()) return ""
        val hit = members[room].orEmpty().firstOrNull { it.id == wxId }
        if (hit != null && hit.name.isNotBlank()) return hit.name
        return names[room + "|" + wxId].orEmpty()
    }

    fun clear() {
        members.clear()
        stats.clear()
        names.clear()
        groupNames.clear()
        owners.clear()
        nickLiveAt.clear()
        nickLive.clear()
        nickForcedAt.clear()
    }

    /** 同步读取（后台线程调用；主线程调用只读缓存，不重试等待）。 */
    fun refresh(room: String): List<MemberRef> {
        if (room.isBlank()) return emptyList()
        val onMain = Looper.myLooper() == Looper.getMainLooper()
        var best: RawRead? = null
        var attempt = 0
        while (attempt < MAX_ATTEMPTS) {
            val raw = readOnce(room)
            if (best == null || raw.ids.size > best.ids.size) best = raw
            if (best.ids.isNotEmpty() && raw.ids.size == best.ids.size) break
            attempt++
            if (onMain || attempt >= MAX_ATTEMPTS) break
            runCatching { Thread.sleep(RETRY_DELAY_MS) }
        }
        val result = best ?: return members(room).orEmpty()
        val refs = ArrayList<MemberRef>(result.ids.size)
        var named = 0
        for (id in result.ids) {
            if (id.isBlank()) continue
            val display = displayNameOf(room, id, result.roomNames)
            if (display.isNotBlank() && display != id) named++
            refs.add(MemberRef(id = id, name = display))
            if (display.isNotBlank()) names[room + "|" + id] = display
        }
        val previous = members[room]
        if (refs.isEmpty() && previous != null) {
            // 本次读空，保留旧缓存，避免面板突然清空
            return previous
        }
        members[room] = refs
        loadedAt[room] = System.currentTimeMillis()
        stats[room] = MemberReadStat(
            memberlist = result.memberlist,
            roomNames = result.roomNames.size,
            total = refs.size,
            named = named
        )
        runCatching {
            val name = WeChatApis.contact().chatrooms()?.getChatroomName(room).orEmpty()
            if (name.isNotBlank()) groupNames[room] = name
        }
        runCatching {
            val owner = WeChatApis.contact().chatrooms()?.getOwner(room).orEmpty()
            if (owner.isNotBlank()) owners[room] = owner
        }
        return refs
    }

    /** 后台线程读取，回调在主线程。同一群并发调用只跑一次。 */
    fun refreshAsync(room: String, onDone: (List<MemberRef>) -> Unit = {}) {
        if (room.isBlank()) return
        if (!loading.add(room)) {
            handler.post { onDone(members(room)) }
            return
        }
        val thread = Thread({
            val list = runCatching { refresh(room) }.getOrDefault(members(room))
            loading.remove(room)
            handler.post { onDone(list) }
        }, "groupadmin-members")
        thread.isDaemon = true
        thread.start()
    }

    /** 昵称 / wxid → 成员；昵称必须唯一匹配，重名返回 null。 */
    fun resolve(room: String, input: String): MemberRef? {
        val text = input.trim()
        if (text.isBlank()) return null
        val list = members(room)
        list.firstOrNull { it.id == text }?.let { return it }
        val hits = list.filter { it.name.isNotBlank() && it.name == text }
        return if (hits.size == 1) hits[0] else null
    }

    /** 模糊候选（搜索与重名提示用）。 */
    fun candidates(room: String, input: String): List<MemberRef> {
        val text = input.trim()
        if (text.isBlank()) return emptyList()
        val lower = text.lowercase()
        return members(room).filter {
            it.name.lowercase().contains(lower) || it.id.lowercase().contains(lower)
        }
    }

    /** 微信「选择群聊」里可见的群：wxId = 群 id，displayName() = 群名。 */
    fun groups(): List<Pair<String, String>> = runCatching {
        WeChatApis.contact().contacts()?.getPickerGroups()
            ?.map { it.wxId to it.displayName() }
            ?: emptyList()
    }.getOrDefault(emptyList())

    // ---------- 内部 ----------

    private fun readOnce(room: String): RawRead {
        val chatrooms = WeChatApis.contact().chatrooms()
        val contacts = WeChatApis.contact().contacts()
        val fromChatroom = runCatching { chatrooms?.getMemberIds(room).orEmpty() }
            .getOrDefault(emptyList())
        val fromContact = runCatching { contacts?.getGroupMemberIds(room).orEmpty() }
            .getOrDefault(emptyList())
        val roomNames = runCatching { contacts?.getGroupMemberRoomDisplayNames(room) }
            .getOrNull().orEmpty()
        val union = LinkedHashSet<String>()
        union.addAll(fromChatroom)
        union.addAll(fromContact)
        union.addAll(roomNames.keys)
        return RawRead(union.toList(), roomNames, fromChatroom.size)
    }

    private fun displayNameOf(room: String, wxId: String, roomNames: Map<String, String>): String {
        val roomName = roomNames[wxId].orEmpty().trim()
        if (roomName.isNotEmpty() && roomName != wxId) return roomName
        val friend = runCatching { WeChatApis.contact().contacts()?.getContact(wxId)?.displayName().orEmpty() }
            .getOrDefault("")
        if (friend.isNotBlank() && friend != wxId) return friend
        val inner = runCatching {
            WeChatApis.contact().contacts()?.getGroupMemberDisplayName(room, wxId).orEmpty()
        }.getOrDefault("")
        if (inner.isNotBlank() && inner != wxId) return inner
        return wxId
    }
}
