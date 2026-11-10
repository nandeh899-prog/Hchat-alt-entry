package com.aeonlab.toolkit.hooks.items.groupadmin

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import com.aeonlab.toolkit.hooks.api.core.WeChatApis
import com.aeonlab.toolkit.hooks.api.message.WeChatMessageObserveApi
import com.aeonlab.toolkit.ui.SettingsUI
import com.aeonlab.toolkit.utils.HLog

/**
 * 群管理聊天指令（12 条）。
 *
 * 口径对照原实现的指令解析逐条移植：
 * - **全匹配**：整条消息只能由「指令标记 + 目标（@昵称 / wxid / 群内编号）+ 可选理由 `*~理由~*` + 分隔符」
 *   组成；扣除这些成分后若仍有剩余文字就不触发。中文写法（如「踢」「拉黑」）还必须明确指向成员
 *   （@ 提及 / wxid / 引用回复），避免误匹配日常聊天。
 * - **权限**：本人 = 全权限；群主 = 全权限；代理群主 = 受本群「允许代理指令」开关控制，且「代理可任免管理员」
 *   决定能否用管理员任免指令；管理员 = 受本群「允许管理员指令」开关控制。无权限时只写日志、不在群里回复。
 * - **生效范围**：只在已纳管的群里生效，指令本身不受模块总开关与本群规则开关限制（同原实现：全局开关关闭时
 *   仍保留手动指令）。系统消息不参与指令解析。
 * - **回复**：文案、字段顺序、换行、@ 形态照原实现——失败与无权限提示 @ 发送者，成功提示不 @；
 *   所有回复都带零宽标记，避免被本模块自己的回声过滤漏掉后再触发规则。
 * - **与原实现的唯一差异**：原实现靠吞掉发送按钮拦截自己发出的指令消息，本模块没有等价 hook，
 *   因此**自己发送的指令消息会留在群聊里**，模块只是观察并执行。
 *
 * 接入点见 [GroupAdminMessageRuntime.handleInternal] 最开头：只有整条消息构成合法指令时才返回 true 接管，
 * 其余消息（含全部 24 条规则路径）行为完全不变。
 */
object GroupAdminCommands {

    private const val TAG = "[AeonLab:GroupAdminCmd]"

    /** 打开模块设置页的指令（照原实现：自己发送「群管设置」或「/群管设置」）。 */
    private val PANEL_WORDS = arrayOf("群管设置", "/群管设置")

    // ---- 指令标记与中文同义短语（顺序与集合照原实现） ----
    private val T_UPB = arrayOf("#upb", "解除永久黑名单", "取消永久黑名单", "取消永久黑")
    private val T_PB = arrayOf("#pb", "加入永久黑名单", "永久黑名单", "永久拉黑")
    private val T_UW = arrayOf("#uw", "解除白名单", "移出白名单", "取消白名单", "去掉白名单")
    private val T_W = arrayOf("#w", "加入白名单", "加白名单", "添加白名单", "设为白名单", "白名单")
    private val T_UH = arrayOf("#uh", "解除黑名单", "移出黑名单", "取消黑名单", "去掉黑名单")
    private val T_H = arrayOf("#h", "加入黑名单", "加黑名单", "添加黑名单", "设为黑名单", "拉黑", "黑名单")
    private val T_UM = arrayOf("#um", "升级管理", "升级为管理", "设为管理员", "设置管理员", "添加管理员")
    private val T_DM = arrayOf("#dm", "取消管理", "解除管理", "移除管理员", "取消管理员", "解除管理员")
    private val T_DA = arrayOf("#da", "取消代理群主", "解除代理群主", "取消代理", "解除代理")
    private val T_UA = arrayOf(
        "#ua", "设为代理群主", "设置代理群主", "添加代理群主", "设为代理", "设置代理", "升级代理", "代理群主"
    )
    private val T_B = arrayOf("#b", "踢出群", "踢出", "踢人", "移出群聊", "踢")

    /** 全部指令标记与中文同义短语（只用于识别「目标只剩纯数字」的写法，不参与指令判定）。 */
    private val T_ALL = T_UPB + T_PB + T_UW + T_W + T_UH + T_H + T_UM + T_DM + T_DA + T_UA + T_B

    // 代理任免指令的目标解析标记（原实现里与指令全集不同：只认「设为代理/取消代理」几种写法）
    private val T_UA_TARGET = arrayOf("#ua", "设为代理", "设置代理", "升级代理", "代理群主")
    private val T_DA_TARGET = arrayOf("#da", "取消代理")

    private val REASON = Regex("\\*~([^~]+)~\\*")
    private val REASON_SP = Regex("\\*~[^~]*~\\*")
    private val PAT_WXID = Regex("wxid_[0-9A-Za-z_\\-]+")
    private val PAT_ROOM = Regex("(?i)[0-9A-Za-z_\\-]+@chatroom")
    private val PAT_DIGITS = Regex("[0-9]+")
    private val PAT_NOISE = Regex("[\\s\\+：:,，、。！!？?~\\-\\*#@＠]+")
    private const val ZAP_CHARS = "\u200b\u200c\u200d\u200e\u200f\u2005\u2007\u2060\ufeff\u202a\u202b\u202c\u202d\u202e"

    // ---------------- 重复投递处理（5.5.39 修「每条指令回两条」） ----------------

    /** 数据库层投递的标识（ObservedMessage.source）。 */
    private const val SOURCE_DB = "message_db"

    /** 事件层那份被推迟多久：真机实测数据库层那份在 0.3~2.5s 内到。 */
    private const val TAKEOVER_DELAY_MS = 3500L

    /** 同一份投递在多少毫秒内再次出现视为重复。 */
    private const val HANDLED_TTL_MS = 2000L

    private val handledDeliveries = HashMap<String, Long>()
    private val pendingTakeovers = HashMap<String, Runnable>()

    private fun selfKey(room: String, raw: String): String = room + "\u0000" + raw.trim()

    private fun markHandled(key: String) {
        val now = System.currentTimeMillis()
        synchronized(handledDeliveries) {
            if (handledDeliveries.size > 64) {
                val it = handledDeliveries.entries.iterator()
                while (it.hasNext()) if (now - it.next().value > HANDLED_TTL_MS * 8) it.remove()
            }
            handledDeliveries[key] = now
        }
    }

    private fun alreadyHandled(key: String): Boolean {
        val now = System.currentTimeMillis()
        synchronized(handledDeliveries) {
            val t = handledDeliveries[key] ?: return false
            return now - t <= HANDLED_TTL_MS
        }
    }

    private fun cancelTakeover(key: String) {
        val task = synchronized(pendingTakeovers) { pendingTakeovers.remove(key) }
        if (task != null) {
            runCatching { Handler(Looper.getMainLooper()).removeCallbacks(task) }
            HLog.i(TAG + " 数据库层投递已到达，作废事件层兜底：key=" + key.substringAfter('\u0000'))
        }
    }

    /** 事件层那份先不处理：等数据库层那份；窗口内没等到才按事件层数据继续。 */
    private fun scheduleTakeover(
        key: String,
        context: Context,
        message: WeChatMessageObserveApi.ObservedMessage
    ) {
        val handler = Handler(Looper.getMainLooper())
        val task = Runnable {
            synchronized(pendingTakeovers) { pendingTakeovers.remove(key) }
            if (alreadyHandled(key)) return@Runnable
            HLog.i(TAG + " 数据库层投递未在窗口内到达，按事件层数据继续处理：" + key.substringAfter('\u0000'))
            runCatching { handleInner(context, message, fromTakeover = true) }
                .onFailure { HLog.e(TAG + " 兜底处理异常", it) }
        }
        val dup = synchronized(pendingTakeovers) {
            if (pendingTakeovers.containsKey(key)) true else { pendingTakeovers[key] = task; false }
        }
        if (!dup) handler.postDelayed(task, TAKEOVER_DELAY_MS)
    }

    /** 一次消息处理内的权限上下文（照原实现懒计算）。 */
    private class Perm {
        var ready = false
        var canNormal = false
        var canManageAdmin = false
        var canManageAgent = false
    }

    // ---------------- 入口 ----------------

    /**
     * 观察到的消息 → 指令处理。返回 true 表示这条消息已被当指令处理（调用方应直接 return，
     * 不再走回声过滤与规则链）；返回 false 表示不是指令，调用方按原路径继续。
     */
    fun handle(context: Context, message: WeChatMessageObserveApi.ObservedMessage): Boolean {
        return try {
            handleInner(context, message)
        } catch (t: Throwable) {
            HLog.e(TAG + " 指令解析异常", t)
            false
        }
    }

    private fun handleInner(
        context: Context,
        message: WeChatMessageObserveApi.ObservedMessage,
        fromTakeover: Boolean = false
    ): Boolean {
        val raw = message.getContent().orEmpty()
        if (raw.isBlank()) return false

        val room = message.getTalker().orEmpty()
        if (room.isBlank() || !room.contains("@chatroom")) return false
        // 原实现：未纳管的群直接返回，连指令都不处理。
        if (!GroupAdminStore.isManaged(context, room)) return false
        // 系统消息在指令之前就被消费（原实现先处理系统消息再进指令解析）。
        if (runCatching { message.isSystem() }.getOrDefault(false)) return false

        val isQuoteMsg = raw.contains("refermsg")
        var text = bodyOf(raw, runCatching { message.isGroupChat() }.getOrDefault(false))
        if (text.isEmpty()) text = raw
        if (text.isEmpty()) return false
        // 引用/AppMsg：正文以 content 里的 <title> 为准（引用回复取到的正文常不可靠）。
        if (isQuoteMsg || text.contains('<')) {
            val un = raw.replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&amp;", "&")
            val title = pickTag(un, "title")
            if (title.isNotEmpty()) text = title
        }
        // 自己发的提示（带零宽标记）不参与指令解析。
        if (text.contains(GroupAdminActions.NOTICE_MARK)) return false

        val self = runCatching { message.isSend() }.getOrDefault(false)

        // 修「每条指令回两条」：观察层对「自己发出的消息」会投递两份——事件层
        // （source=local_send，正文之外的成员信息不全，没有 atuserlist）与数据库层
        // （source=message_db，取自消息表，信息完整）。两份都进指令入口，于是动作与回复都做两遍；
        // 真机 18:31:46 那次两次还拿到了不同的目标解析结果（事件层那份解析不出目标、回了「未找到
        // 要移除的成员」，数据库层那份真的移除了 2 人）。
        // 处理：数据库层那份照常处理；事件层那份先挂 3.5s 兜底，窗口内数据库层那份到达就作废兜底
        // （一条指令只执行、只回复一次）；万一数据库层那份没到，兜底到期后仍按事件层数据处理，
        // 不让指令彻底失联。别人发的消息不受影响（实测只投递一份）。
        if (self && !fromTakeover) {
            val key = selfKey(room, raw)
            if (message.source == SOURCE_DB) {
                cancelTakeover(key)
                if (alreadyHandled(key)) {
                    HLog.i(TAG + " 重复投递已忽略：群=" + room + " 正文=" + text)
                    return true
                }
                markHandled(key)
            } else {
                if (alreadyHandled(key)) {
                    HLog.i(TAG + " 重复投递已忽略：群=" + room + " 正文=" + text)
                    return true
                }
                scheduleTakeover(key, context, message)
                return true
            }
        }

        // 打开设置面板：照原实现只认自己发出的「群管设置」/「/群管设置」。
        val panel = text.trim()
        if (self && (panel == PANEL_WORDS[0] || panel == PANEL_WORDS[1])) {
            openPanel(room)
            return true
        }

        val hasHash = text.contains('#')
        val hasWxid = text.contains("wxid_")
        val hasAt = text.contains('@') || text.contains('＠')
        // 既无指令标记、又不指向成员、也非引用回复 → 普通聊天，早退。
        if (!hasHash && !hasWxid && !isQuoteMsg && !hasAt) return false

        var atList = runCatching { message.getAtUserList() }.getOrNull().orEmpty()
            .map { it.trim() }
            .filter { it.isNotEmpty() && it != "notify@all" && it != "all" && it != "notifyall" }
        if (atList.isEmpty() && hasAt) atList = atFromContent(room, text)

        val names = memberNames(room)
        // 自己发出的消息：WeChatMessage.sendTalker() 对 outgoing 的实现就是 `return talker`，
        // 群聊里即群号（如 44023963393@chatroom）。旧实现把它当发送者、又被 contains("@chatroom")
        // 判掉后静默 return false，导致「自己发出的指令全部无反应、日志里也没有任何指令行」。
        // 本人发送时发送者固定取本机 wxid。
        val rawSender = message.getSendTalker().orEmpty().trim()
        val sender = if (self) {
            val mine = runCatching { GroupAdminRoleInspector.selfWxId() }.getOrNull().orEmpty().trim()
            if (mine.isNotEmpty()) mine else rawSender.takeIf { it.startsWith("wxid_") }.orEmpty()
        } else {
            if (rawSender.isBlank() || rawSender.contains("@chatroom")) "" else rawSender
        }
        if (sender.isEmpty()) {
            // 这里过去是静默早退：出问题时日志里连痕迹都没有，改为留痕（低频：只在指令候选上触发）。
            HLog.i(
                TAG + " 指令候选但取不到发送者：self=" + self + " raw=" + rawSender +
                    " 群=" + room + " 正文=" + text
            )
            return false
        }

        // #diag：只写日志，不往群里发（原实现同样只落日志）。
        if (cmdFull(text, arrayOf("#diag"), emptyArray(), false, names)) {
            logDiag(room, message, sender, self, atList)
            return true
        }

        val targeted = atList.isNotEmpty() || hasAt || hasWxid || isQuoteMsg
        if (!hasHash && !targeted) return false

        // 5.5.40：正文去掉指令标记后只剩纯数字（原实现里的「ID号码/群内编号」写法）→
        // 按普通聊天信息处理：不执行动作、也不回复（旧行为是回「未找到目标成员」）。
        // 有 @ 目标时不算，wxid 与 @昵称 两种写法的解析与回复保持不变。
        if (numericOnlyTarget(text, atList)) {
            HLog.i(TAG + " 纯数字目标按普通信息处理：正文=" + text + " 群=" + room)
            return false
        }

        val cfg = GroupAdminStore.group(context, room)
        val perm = Perm()

        if (cmdFull(text, T_UPB, T_UPB, targeted, names)) {
            ensurePerm(perm, room, sender, self, cfg)
            if (!perm.canNormal) { denied(room, sender, "取消永久黑名单"); return true }
            cmdBlack(context, room, text, T_UPB, atList, names, sender, add = false, perm = true)
            return true
        }
        if (cmdFull(text, T_PB, T_PB, targeted, names)) {
            ensurePerm(perm, room, sender, self, cfg)
            if (!perm.canNormal) { denied(room, sender, "加入永久黑名单"); return true }
            cmdBlack(context, room, text, T_PB, atList, names, sender, add = true, perm = true)
            return true
        }
        if (cmdFull(text, T_UW, T_UW, targeted, names)) {
            ensurePerm(perm, room, sender, self, cfg)
            if (!perm.canNormal) { denied(room, sender, "解除白名单"); return true }
            cmdWhite(context, room, text, T_UW, atList, names, sender, add = false)
            return true
        }
        if (cmdFull(text, T_W, T_W, targeted, names)) {
            ensurePerm(perm, room, sender, self, cfg)
            if (!perm.canNormal) { denied(room, sender, "加入白名单"); return true }
            cmdWhite(context, room, text, T_W, atList, names, sender, add = true)
            return true
        }
        if (cmdFull(text, T_UH, T_UH, targeted, names)) {
            ensurePerm(perm, room, sender, self, cfg)
            if (!perm.canNormal) { denied(room, sender, "解除黑名单"); return true }
            cmdBlack(context, room, text, T_UH, atList, names, sender, add = false, perm = false)
            return true
        }
        if (cmdFull(text, T_H, T_H, targeted, names)) {
            ensurePerm(perm, room, sender, self, cfg)
            if (!perm.canNormal) { denied(room, sender, "加入黑名单"); return true }
            cmdBlack(context, room, text, T_H, atList, names, sender, add = true, perm = false)
            return true
        }
        if (cmdFull(text, T_UM, T_UM, targeted, names)) {
            ensurePerm(perm, room, sender, self, cfg)
            if (!perm.canManageAdmin) { denied(room, sender, "升级管理"); return true }
            cmdAdmin(context, room, text, "#um", atList, names, sender)
            return true
        }
        if (cmdFull(text, T_DM, T_DM, targeted, names)) {
            ensurePerm(perm, room, sender, self, cfg)
            if (!perm.canManageAdmin) { denied(room, sender, "取消管理"); return true }
            cmdAdmin(context, room, text, "#dm", atList, names, sender)
            return true
        }
        if (cmdFull(text, T_DA, T_DA, targeted, names)) {
            ensurePerm(perm, room, sender, self, cfg)
            if (!perm.canManageAgent) { denied(room, sender, "取消代理群主"); return true }
            cmdAgent(context, room, text, "#da", atList, names, sender)
            return true
        }
        if (cmdFull(text, T_UA, T_UA, targeted, names)) {
            ensurePerm(perm, room, sender, self, cfg)
            if (!perm.canManageAgent) { denied(room, sender, "设置代理群主"); return true }
            cmdAgent(context, room, text, "#ua", atList, names, sender)
            return true
        }
        if (cmdFull(text, T_B, T_B, targeted, names)) {
            ensurePerm(perm, room, sender, self, cfg)
            if (!perm.canNormal) { denied(room, sender, "移除成员"); return true }
            cmdKick(room, text, atList, names, sender, message)
            return true
        }
        // 长得像指令但一条都没匹配上：留一行痕迹（旧实现这里也是静默的，出问题查不出来）。
        if (hasHash || hasWxid || hasAt || isQuoteMsg) {
            HLog.i(
                TAG + " 指令未匹配：正文=" + text + " 发送者=" + sender + " 群=" + room +
                    " @目标数=" + atList.size
            )
        }
        return false
    }

    // ---------------- 各指令动作 ----------------

    private fun cmdKick(
        room: String,
        text: String,
        atList: List<String>,
        names: List<String>,
        sender: String,
        message: WeChatMessageObserveApi.ObservedMessage
    ) {
        val targets = ArrayList(cmdTargets(room, text, T_B, atList, names))
        // 引用/回复消息后整条发指令：踢被引用者。
        if (targets.isEmpty()) {
            val quoted = quoteTarget(message)
            if (quoted.isNotEmpty() && quoted != sender) targets.add(quoted)
        }
        HLog.i(TAG + " 踢人指令：发送者=" + sender + " 目标=" + targets.joinToString(",") + " 群=" + room)
        if (targets.isEmpty()) {
            replyAt(room, sender, "未找到要移除的成员")
            return
        }
        var reason = cmdReason(text)
        if (reason.isEmpty()) reason = "管理员操作"
        var ok = 0
        var skipOwner = 0
        val removed = ArrayList<String>()
        for (id in targets) {
            if (id == sender) continue
            if (targetProtected(room, sender, id)) {
                skipOwner++
                continue
            }
            val done = try {
                WeChatApis.contact().chatrooms()?.delChatroomMember(room, id)
                true
            } catch (t: Throwable) {
                HLog.e(TAG + " 踢人失败：群=" + room + " 目标=" + id, t)
                false
            }
            if (done) {
                ok++
                removed.add(id)
            }
        }
        if (ok > 0) {
            reply(room, "移除" + joinedNames(room, removed) + "（" + reason + "）" +
                if (skipOwner > 0) "（群主不可被管理，已跳过）" else "")
        } else {
            replyAt(room, sender, "无可移除成员")
        }
    }

    private fun cmdBlack(
        context: Context,
        room: String,
        text: String,
        tokens: Array<String>,
        atList: List<String>,
        names: List<String>,
        sender: String,
        add: Boolean,
        perm: Boolean
    ) {
        HLog.i(TAG + " 黑名单指令：add=" + add + " 永久=" + perm + " 发送者=" + sender + " 群=" + room + " 正文=" + text)
        var targets = cmdTargets(room, text, tokens, atList, names)
        if (targets.isEmpty() && !add) {
            val fallback = listFallbackTargets(context, room, text, tokens)
            if (fallback.isNotEmpty()) {
                HLog.i(TAG + " 解除目标改由名单条目解析：" + fallback.joinToString(",") + " 正文=" + text)
                targets = fallback
            }
        }
        if (targets.isEmpty()) {
            replyAt(room, sender, "未找到目标成员")
            return
        }
        var ok = 0
        var skipOwner = 0
        val done = ArrayList<String>()
        val now = System.currentTimeMillis()
        GroupAdminStore.update(context, room) { cfg ->
            for (id in targets) {
                if (targetProtected(room, sender, id)) {
                    skipOwner++
                    continue
                }
                if (add) {
                    // 原实现：加黑名单固定写入永久（expire=0），#h 与 #pb 的差别只在回复文案。
                    val name = GroupAdminMemberCache.nameOf(room, id).ifBlank { id }
                    GroupAdminStore.addBlack(cfg, id, name, 0, now)
                } else {
                    cfg.black.removeAll { it.id == id }
                }
                ok++
                done.add(id)
            }
        }
        val action = when {
            add && perm -> "加入永久黑名单"
            add -> "加入黑名单"
            perm -> "解除永久黑名单"
            else -> "解除黑名单"
        }
        // 5.5.40：成功时回复点名式（多目标用「、」连接、全部列出）；
        // 一个都没成功（例如只有群主、被跳过）时沿用原有失败文案，不输出空列表。
        // 5.5.44：点名式措辞与 #h / #uh 统一（#pb / #upb 的落库行为与它们一致：加黑名单固定写永久）。
        val verb = when {
            add && perm -> "加入黑名单（永久）"
            add -> "加入黑名单（永久）"
            perm -> "从黑名单移除"
            else -> "从黑名单移除"
        }
        val skipNote = if (skipOwner > 0) "（群主不可被管理，已跳过）" else ""
        val named = joinedNames(room, done)
        reply(
            room,
            if (named.isEmpty()) "已" + action + " " + ok + " 人" + skipNote else named + verb + skipNote
        )
    }

    private fun cmdWhite(
        context: Context,
        room: String,
        text: String,
        tokens: Array<String>,
        atList: List<String>,
        names: List<String>,
        sender: String,
        add: Boolean
    ) {
        HLog.i(TAG + " 白名单指令：add=" + add + " 发送者=" + sender + " 群=" + room + " 正文=" + text)
        var targets = cmdTargets(room, text, tokens, atList, names)
        if (targets.isEmpty() && !add) {
            val fallback = listFallbackTargets(context, room, text, tokens)
            if (fallback.isNotEmpty()) {
                HLog.i(TAG + " 解除目标改由名单条目解析：" + fallback.joinToString(",") + " 正文=" + text)
                targets = fallback
            }
        }
        if (targets.isEmpty()) {
            replyAt(room, sender, "未找到目标成员")
            return
        }
        var ok = 0
        var skipOwner = 0
        val done = ArrayList<String>()
        GroupAdminStore.update(context, room) { cfg ->
            for (id in targets) {
                if (targetProtected(room, sender, id)) {
                    skipOwner++
                    continue
                }
                if (add) {
                    val name = GroupAdminMemberCache.nameOf(room, id).ifBlank { id }
                    // 名单互斥由 Store 负责：加入白名单会同时移出黑名单（同原实现）。
                    GroupAdminStore.addMember(cfg, cfg.white, id, name)
                } else {
                    cfg.white.removeAll { it.id == id }
                }
                ok++
                done.add(id)
            }
        }
        val skipNote = if (skipOwner > 0) "（群主不可被管理，已跳过）" else ""
        val named = joinedNames(room, done)
        reply(
            room,
            if (named.isEmpty()) "已" + (if (add) "加入白名单" else "解除白名单") + " " + ok + " 人" + skipNote
            else named + (if (add) "加入白名单（永久）" else "从白名单移除") + skipNote
        )
    }

    private fun cmdAdmin(
        context: Context,
        room: String,
        text: String,
        token: String,
        atList: List<String>,
        names: List<String>,
        sender: String
    ) {
        HLog.i(TAG + " 管理指令：" + token + " 发送者=" + sender + " 群=" + room + " 正文=" + text)
        val tokens = if (token == "#um") arrayOf("#um", "升级管理") else arrayOf("#dm", "取消管理")
        val targets = ArrayList(cmdTargets(room, text, tokens, atList, names))
        // 原实现：没说目标时作用于发送者自己（#um/#dm/#ua/#da 都是这条）。
        if (targets.isEmpty()) targets.add(sender)
        var ok = 0
        var skipOwner = 0
        val done = ArrayList<String>()
        GroupAdminStore.update(context, room) { cfg ->
            for (id in targets) {
                if (targetProtected(room, sender, id)) {
                    skipOwner++
                    continue
                }
                if (token == "#um") {
                    GroupAdminStore.addMember(cfg, cfg.admins, id, GroupAdminMemberCache.nameOf(room, id))
                } else {
                    cfg.admins.removeAll { it.id == id }
                }
                ok++
                done.add(id)
            }
        }
        val skipNote = if (skipOwner > 0) "（群主不可被管理，已跳过）" else ""
        val named = joinedNames(room, done)
        reply(
            room,
            if (named.isEmpty()) "已" + (if (token == "#um") "升级管理" else "取消管理") + " " + ok + " 人" + skipNote
            else if (token == "#um") named + "设为管理员" + skipNote
            else "取消" + named + "的管理员" + skipNote
        )
    }

    private fun cmdAgent(
        context: Context,
        room: String,
        text: String,
        token: String,
        atList: List<String>,
        names: List<String>,
        sender: String
    ) {
        HLog.i(TAG + " 代理指令：" + token + " 发送者=" + sender + " 群=" + room + " 正文=" + text)
        val tokens = if (token == "#ua") T_UA_TARGET else T_DA_TARGET
        val targets = ArrayList(cmdTargets(room, text, tokens, atList, names))
        if (targets.isEmpty()) targets.add(sender)
        var ok = 0
        var skipOwner = 0
        val done = ArrayList<String>()
        GroupAdminStore.update(context, room) { cfg ->
            for (id in targets) {
                if (targetProtected(room, sender, id)) {
                    skipOwner++
                    continue
                }
                if (token == "#ua") {
                    GroupAdminStore.addMember(cfg, cfg.agents, id, GroupAdminMemberCache.nameOf(room, id))
                } else {
                    cfg.agents.removeAll { it.id == id }
                }
                ok++
                done.add(id)
            }
        }
        val skipNote = if (skipOwner > 0) "（群主不可被管理，已跳过）" else ""
        val named = joinedNames(room, done)
        reply(
            room,
            if (named.isEmpty()) "已" + (if (token == "#ua") "设置代理群主" else "取消代理群主") + " " + ok + " 人" + skipNote
            else if (token == "#ua") named + "设为代理群主" + skipNote
            else "取消" + named + "的代理群主" + skipNote
        )
    }

    // ---------------- 权限 ----------------

    private fun ensurePerm(perm: Perm, room: String, sender: String, self: Boolean, cfg: GroupConfig) {
        if (perm.ready) return
        perm.ready = true
        if (self) {
            perm.canNormal = true
            perm.canManageAdmin = true
            perm.canManageAgent = true
            return
        }
        val owner = ownerOf(room)
        val isOwner = owner.isNotEmpty() && owner == sender
        val isAgent = listHasMember(cfg.agents, room, sender)
        val isAdmin = listHasMember(cfg.admins, room, sender)
        val agentActive = isAgent && cfg.allowAgentCmd
        val adminActive = isAdmin && cfg.allowAdminCmd
        perm.canNormal = isOwner || agentActive || adminActive
        perm.canManageAdmin = isOwner || (agentActive && cfg.agentManageAdmin)
        perm.canManageAgent = isOwner
    }

    private fun denied(room: String, sender: String, action: String) {
        HLog.i(TAG + " 指令无权限：" + action + " 发送者=" + sender)
        replyAt(room, sender, "无权限执行：" + action)
    }

    /** 名单成员判定：先按 wxid 精确匹配，再按昵称匹配（名单里可能存的是昵称）。 */
    private fun listHasMember(list: List<MemberRef>, room: String, wxId: String): Boolean {
        if (wxId.isEmpty()) return false
        if (list.any { it.id.isNotBlank() && it.id == wxId }) return true
        val name = GroupAdminMemberCache.nameOf(room, wxId)
        if (name.isEmpty()) return false
        return list.any { ref ->
            val raw = if (ref.id.isNotBlank()) ref.id else ref.name
            var v = raw.trim()
            if (v.startsWith("@")) v = v.substring(1).trim()
            v.isNotEmpty() && !v.startsWith("wxid_") && v == name
        }
    }

    private fun ownerOf(room: String): String = GroupAdminMemberCache.ownerOf(room).ifBlank {
        runCatching { WeChatApis.contact().chatrooms()?.getOwner(room) }.getOrNull().orEmpty()
    }.trim()

    /** 群主不可被他人管理（群主管理自己不受限）。 */
    private fun targetProtected(room: String, sender: String, id: String): Boolean {
        if (id.isEmpty()) return true
        val owner = ownerOf(room)
        return owner.isNotEmpty() && owner == id && owner != sender
    }

    // ---------------- 解析（全匹配） ----------------

    private fun cmdFull(
        text: String,
        hashTokens: Array<String>,
        zhPhrases: Array<String>,
        targeted: Boolean,
        names: List<String>
    ): Boolean {
        val s0 = text.trim()
        if (s0.isEmpty()) return false
        var s = s0
        var hit = false
        var pos = -1
        for (token in hashTokens) {
            val p = tokenPos(s, token)
            if (p >= 0 && (pos < 0 || p < pos)) {
                pos = p
                hit = true
            }
        }
        if (!hit && targeted) {
            for (phrase in zhPhrases) {
                val p = s.indexOf(phrase)
                if (p >= 0 && (pos < 0 || p < pos)) {
                    pos = p
                    hit = true
                }
            }
        }
        if (!hit) return false
        for (token in hashTokens) s = removeToken(s, token)
        for (phrase in zhPhrases.sortedByDescending { it.length }) s = s.replace(phrase, " ")
        s = REASON_SP.replace(s, " ")
        s = stripNoise(s, names)
        return s.isEmpty()
    }

    /** 指令标记定位：前边界为起始或分隔符（兼容「@昵称#b」这类紧贴写法），后边界为空白/结束/标点/数字/wxid。 */
    private fun tokenPos(text: String, token: String): Int {
        if (token.isEmpty()) return -1
        var p = text.indexOf(token)
        while (p >= 0) {
            var preOk = p == 0 || isCmdSep(text[p - 1])
            if (!preOk) {
                val at = text.lastIndexOf('@', p - 1)
                if (at >= 0) {
                    var noSep = true
                    var j = at + 1
                    while (j < p) {
                        if (isSpace(text[j]) || isCmdSep(text[j])) {
                            noSep = false
                            break
                        }
                        j++
                    }
                    if (noSep) preOk = true
                }
            }
            if (preOk) {
                val after = p + token.length
                if (after >= text.length) return p
                val c = text[after]
                if (isSpace(c) || c == '@' || c == '#' || c == '*' || c == '~' || c == '+' ||
                    c == ':' || c == '：' || c == '，' || c == ','
                ) return p
                if (c.isDigit()) return p
                val rest = text.substring(after)
                if (rest.startsWith("wxid_") || rest.startsWith("WXID_")) return p
            }
            p = text.indexOf(token, p + 1)
        }
        return -1
    }

    private fun removeToken(textIn: String, token: String): String {
        var text = textIn
        var start = 0
        while (true) {
            val p = tokenPos(text.substring(start), token)
            if (p < 0) break
            val abs = start + p
            text = text.substring(0, abs) + " " + text.substring(abs + token.length)
            start = abs + 1
            if (start >= text.length) break
        }
        return text
    }

    private fun isSpace(c: Char): Boolean = c.isWhitespace()

    private fun isCmdSep(c: Char): Boolean =
        isSpace(c) || c == '+' || c == ':' || c == '：' || c == '，' || c == ',' || c == '~' || c == '*' ||
            c == '#' || c == '@' || c == '＠' || c == '、' || c == '。' || c == '！' || c == '!' || c == '？' || c == '?'

    /** 去掉目标噪声：@提及（含带空格昵称）、wxid、群号、纯数字、分隔符；去完为空才算全匹配。 */
    private fun stripNoise(sIn: String, names: List<String>): String {
        val sb = StringBuilder()
        var i = 0
        while (i < sIn.length) {
            val c = sIn[i]
            if (c == '@' || c == '＠') {
                val end = matchMemberAt(sIn, i + 1, names)
                if (end > i + 1) {
                    i = end
                } else {
                    i++
                    while (i < sIn.length) {
                        val d = sIn[i]
                        if (isSpace(d) || isCmdSep(d)) break
                        i++
                    }
                }
                sb.append(' ')
            } else {
                sb.append(c)
                i++
            }
        }
        var s = sb.toString()
        s = PAT_WXID.replace(s, " ")
        s = PAT_ROOM.replace(s, " ")
        s = PAT_DIGITS.replace(s, " ")
        return PAT_NOISE.replace(s, "")
    }

    /** 从 from 起匹配当前群成员昵称（允许含空格），命中返回结束位置，否则 -1。 */
    private fun matchMemberAt(s: String, from: Int, names: List<String>): Int {
        var best = -1
        for (nm in names) {
            if (nm.isEmpty()) continue
            if (s.regionMatches(from, nm, 0, nm.length)) {
                val end = from + nm.length
                if (end > best) best = end
            }
        }
        return best
    }

    /**
     * 5.5.40：正文去掉指令标记与噪声后只剩纯数字（原实现写作「ID号码/群内编号」）时返回 true。
     * 调用方按普通聊天信息处理：不执行动作也不回复。存在 @ 目标时一律返回 false。
     */
    private fun numericOnlyTarget(text: String, atList: List<String>): Boolean {
        if (atList.isNotEmpty()) return false
        var s = text
        for (t in T_ALL) if (t.isNotEmpty()) s = s.replace(t, " ")
        s = REASON_SP.replace(s, " ")
        s = PAT_NOISE.replace(s, "")
        return s.isNotEmpty() && PAT_DIGITS.matches(s)
    }

    private fun cmdReason(text: String): String =
        REASON.find(text)?.groupValues?.getOrNull(1)?.trim().orEmpty()

    /** 目标解析：@ 列表优先；否则剥离指令标记/中文短语后解析 @昵称、wxid、昵称。 */
    private fun cmdTargets(
        room: String,
        text: String,
        tokens: Array<String>,
        atList: List<String>,
        names: List<String>
    ): List<String> {
        val out = ArrayList<String>()
        if (atList.isNotEmpty()) {
            out.addAll(atList.filter { it.isNotBlank() })
            return out
        }
        var s = text
        for (token in tokens) if (token.isNotEmpty()) s = s.replace(token, " ")
        s = REASON_SP.replace(s, " ")
        s = s.replace('+', ' ').replace(':', ' ').replace('：', ' ')
            .replace('，', ' ').replace(',', ' ')
        s = s.trim()
        if (s.isEmpty()) return out
        val at = s.indexOf('@')
        if (at >= 0) s = s.substring(at + 1).trim()
        if (s.isEmpty()) return out
        val sp = s.indexOf(' ')
        if (sp > 0) s = s.substring(0, sp).trim()
        var wxid = findMemberByName(room, s)
        if (wxid == null && (s.startsWith("wxid_") || s.contains("@chatroom"))) wxid = s
        if (wxid == null) return out
        out.add(wxid)
        return out
    }

    private fun findMemberByName(room: String, name: String): String? {
        var want = name.trim()
        // 5.5.39：原来只剥 @，导致「#wxid_xxx 解除黑名单」这类中文混合写法把 # 留在目标里、
        // 解析不出 wxid（真机 18:33:04 现场）。@ / ＠ / # 一律先剥掉再判断。
        while (want.isNotEmpty() &&
            (want.startsWith("@") || want.startsWith("#") || want.startsWith("＠"))) {
            want = want.substring(1).trim()
        }
        if (want.isEmpty()) return null
        if (want.startsWith("wxid_") || want.contains("@chatroom")) return want
        val hit = GroupAdminMemberCache.resolve(room, want) ?: return null
        return hit.id.takeIf { it.isNotBlank() }
    }

    /**
     * 5.5.39：名单「解除」方向的目标兜底解析。
     * 已退群成员不在群成员缓存里，cmdTargets 会解析失败并回「未找到目标成员」（真机 18:33:04 现场）。
     * 名单条目本身存了 wxid 与当时记录的名字，这里直接拿来再解析一次：正文里出现的 wxid 优先
     * （含 #wxid_xxx 写法），其次按名单条目记录的名字匹配。只用于「解除」方向，不改「加入」的判定。
     */
    private fun listFallbackTargets(
        context: Context,
        room: String,
        text: String,
        tokens: Array<String>
    ): List<String> {
        val cfg = GroupAdminStore.group(context, room)
        val entries = GroupAdminStore.listOf(cfg, GroupAdminList.BLACK) +
            GroupAdminStore.listOf(cfg, GroupAdminList.WHITE)
        if (entries.isEmpty()) return emptyList()
        val out = ArrayList<String>()
        for (m in PAT_WXID.findAll(text)) {
            val id = m.value.trim()
            if (entries.any { it.id == id } && !out.contains(id)) out.add(id)
        }
        if (out.isNotEmpty()) return out
        var s = text
        for (token in tokens) if (token.isNotEmpty()) s = s.replace(token, " ")
        s = REASON_SP.replace(s, " ")
        s = s.replace('+', ' ').replace(':', ' ').replace('：', ' ')
            .replace('，', ' ').replace(',', ' ')
            .replace('@', ' ').replace('＠', ' ').replace('#', ' ')
        for (piece in s.split(' ')) {
            val want = piece.trim()
            if (want.isEmpty()) continue
            val hit = entries.firstOrNull { it.name.isNotBlank() && it.name == want } ?: continue
            if (!out.contains(hit.id)) out.add(hit.id)
        }
        return out
    }

    /** 群成员昵称集合（用于 @昵称 匹配与全匹配扣噪）。 */
    private fun memberNames(room: String): List<String> {
        var list = GroupAdminMemberCache.members(room)
        if (list.isEmpty()) {
            list = runCatching { GroupAdminMemberCache.refresh(room) }.getOrDefault(emptyList())
        }
        return list.map { it.name }.filter { it.isNotBlank() }
    }

    /** 从正文反查被 @ 的群成员（宿主 @ 列表为空时的兜底，同原实现）。 */
    private fun atFromContent(room: String, content: String): List<String> {
        val out = ArrayList<String>()
        if (content.isEmpty()) return out
        if (!content.contains('@') && !content.contains('＠')) return out
        if (hasAtAll(content)) {
            out.add("notify@all")
            return out
        }
        val cz = zap(content)
        for (ref in GroupAdminMemberCache.members(room)) {
            if (ref.id.isBlank()) continue
            val nm = zap(ref.name)
            if (nm.isEmpty()) continue
            if (cz.contains(nm) && !out.contains(ref.id)) out.add(ref.id)
        }
        return out
    }

    private fun hasAtAll(content: String): Boolean {
        val c = content.replace('＠', '@')
        val low = c.lowercase()
        return c.contains("@所有人") || c.contains("@全体成员") ||
            low.contains("notify@all") || low.contains("@all")
    }

    private fun zap(s: String): String {
        if (s.isEmpty()) return ""
        var out = s
        for (ch in ZAP_CHARS) out = out.replace(ch, ' ')
        return out
    }

    /** 取被引用/回复消息的发送者 wxid；非引用消息返回空。 */
    private fun quoteTarget(message: WeChatMessageObserveApi.ObservedMessage): String {
        try {
            if (runCatching { message.isQuote() }.getOrDefault(false)) {
                val q = runCatching { message.getQuoteMsg() }.getOrNull()
                if (q != null) {
                    val id = runCatching { q.getSendTalker() }.getOrNull().orEmpty().trim()
                    if (id.isNotEmpty() && !id.contains("@chatroom")) return id
                }
            }
            var raw = message.getContent().orEmpty()
            if (!raw.contains("refermsg")) raw = runCatching { message.xml }.getOrNull().orEmpty()
            val t = raw.replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&amp;", "&")
            val ra = t.indexOf("<refermsg")
            if (ra >= 0) {
                var rb = t.indexOf("</refermsg>", ra)
                if (rb < 0) rb = t.length
                val id = pickTag(t.substring(ra, rb), "chatusr").trim()
                if (id.isNotEmpty() && !id.contains("@chatroom")) return id
            }
            return ""
        } catch (t: Throwable) {
            return ""
        }
    }

    private fun pickTag(s: String, tag: String): String {
        if (s.isEmpty() || tag.isEmpty()) return ""
        return try {
            val a = s.indexOf("<" + tag + ">")
            if (a < 0) return ""
            val b = s.indexOf("</" + tag + ">", a)
            if (b < 0) return ""
            s.substring(a + tag.length + 2, b)
        } catch (t: Throwable) {
            ""
        }
    }

    private fun bodyOf(raw: String, group: Boolean): String {
        if (!group) return raw
        val i = raw.indexOf(":\n")
        return if (i > 0) raw.substring(i + 2) else raw
    }

    // ---------------- 回复 ----------------

    /**
     * 打开群管设置面板。
     *
     * `MiuixSettingsPage.show()` 第一行是 `context as? Activity ?: return`，而 `showFeature()`
     * 不管有没有真的挂上页面都返回 true —— 上一版把它的返回值当成功依据，日志写 `opened=true`
     * 而面板根本没出现。这里改为：取宿主 Activity + 主线程挂载，并把「是否真的拿到 Activity / 是否挂上」写进日志。
     */
    private fun openPanel(room: String) {
        val activity = runCatching { WeChatApis.currentActivity()?.currentActivity() as? Activity }.getOrNull()
        if (activity == null) {
            HLog.i(TAG + " 群管设置面板未打开：找不到宿主 Activity（发出时微信需停在聊天页前台）群=" + room)
            return
        }
        val posted = runCatching {
            Handler(Looper.getMainLooper()).post {
                val ok = runCatching { SettingsUI.showFeature(activity, GroupAdminFeature.ID) }
                    .getOrDefault(false)
                HLog.i(TAG + " 群管设置面板已挂载（主线程）ok=" + ok + " 群=" + room)
            }
            true
        }.getOrDefault(false)
        HLog.i(TAG + " 群管设置面板请求：activity=1 posted=" + posted + " 群=" + room)
    }

    /** 点名式回复用的显示名：优先该成员在群里的显示名，取不到退化为 wxid（不留空）。 */
    private fun displayName(room: String, id: String): String {
        if (id.isBlank()) return ""
        val n = runCatching { GroupAdminMemberCache.nameOf(room, id) }.getOrNull().orEmpty().trim()
        return n.ifBlank { id }
    }

    /** 点名式目标串：多个目标用「、」连接（调用方保证列表非空，不输出空列表）。 */
    private fun joinedNames(room: String, ids: List<String>): String =
        ids.joinToString("、") { displayName(room, it) }

    private fun reply(room: String, text: String): Boolean =
        GroupAdminActions.send(room, text + GroupAdminActions.NOTICE_MARK)

    private fun replyAt(room: String, wxId: String, text: String): Boolean =
        GroupAdminActions.sendAt(room, wxId, text + GroupAdminActions.NOTICE_MARK)

    // ---------------- #diag ----------------

    private fun logDiag(
        room: String,
        message: WeChatMessageObserveApi.ObservedMessage,
        sender: String,
        self: Boolean,
        atList: List<String>
    ) {
        val sb = StringBuilder()
        sb.append("【群管诊断】")
        sb.append("\n群=").append(room)
        sb.append("\n发送者=").append(sender)
        sb.append("\n本人=").append(runCatching { GroupAdminRoleInspector.selfWxId() }.getOrDefault(""))
        sb.append("\nisSelf=").append(self)
        sb.append(" isGroup=").append(runCatching { message.isGroupChat() }.getOrDefault(false))
        sb.append(" isText=").append(runCatching { message.isText() }.getOrDefault(false))
        sb.append("\natList(").append(atList.size).append(")=").append(atList.joinToString(","))
        sb.append("\n内容=").append(message.getContent().orEmpty().trim())
        sb.append("\n发送者是否豁免=").append(
            runCatching {
                val cfg = GroupAdminStore.group(null, room)
                GroupAdminStore.exemptBy(cfg, sender, GroupAdminRoleInspector.selfWxId(), ownerOf(room))
            }.getOrDefault("")
        )
        HLog.i(TAG + " " + sb.toString())
    }
}
