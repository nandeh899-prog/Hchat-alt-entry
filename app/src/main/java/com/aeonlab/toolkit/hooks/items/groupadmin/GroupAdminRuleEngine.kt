package com.aeonlab.toolkit.hooks.items.groupadmin

/**
 * 判定输入（规格 5.2 `GroupAdminMessageContext`）。纯数据：判定层不碰微信 API，也不碰存储。
 *
 * 需要「历史」的规则（重复、拍一拍、禁言冷却、冒充群主限时）由运行期先用
 * [GroupAdminRuleMemory] 算好，再以 `repeatCount` / `patDistinctCount` / `muteCooldownOk` /
 * `fakeGraceExpired` 的形式传进来——这样判定层保持纯函数，可逐条单测。
 */
data class GroupAdminMessageContext(
    val room: String = "",
    val sender: String = "",
    val senderName: String = "",
    val isSelf: Boolean = false,
    val isOwner: Boolean = false,
    val isAgent: Boolean = false,
    val isAdmin: Boolean = false,
    val isWhite: Boolean = false,
    val exempt: Boolean = false,
    val exemptBy: String = "",
    val msgType: Int = 0,
    val appType: String = "",
    val content: String = "",
    val atList: List<String> = emptyList(),
    val atCountInText: Int = 0,
    val memberCount: Int = 0,
    val isText: Boolean = false,
    val now: Long = 0L,
    /** 消息特征袋（类型判定用，见 [GroupAdminMessageTypes]）。 */
    val features: GroupAdminMessageFeatures = GroupAdminMessageFeatures(),
    /** @人数（@列表条数与正文 @ 计数取较大者）。 */
    val atDistinct: Int = 0,
    /** 是否 @全体（宿主 isNotifyAll/isAnnounceAll）。 */
    val atAll: Boolean = false,
    /** 本成员在 10 分钟窗口内发送完全相同内容的次数（含本次）。 */
    val repeatCount: Int = 1,
    /** 本成员在拍一拍窗口内拍过的不同成员数（含本次）。 */
    val patDistinctCount: Int = 1,
    /** 禁言冷却是否已过（可处理）。 */
    val muteCooldownOk: Boolean = true,
    /** 冒充群主的限时警告是否已超时（true = 该踢）。 */
    val fakeGraceExpired: Boolean = false,
    /** 群昵称含「群主」二字。 */
    val senderNameHasOwner: Boolean = false,
    /** 群昵称与群主昵称完全相同。 */
    val senderNameEqualsOwner: Boolean = false,
    /** 系统消息：本人「改名为 X」且 X 含「群主」或等于群主昵称。 */
    val renameToOwnerName: Boolean = false,
    /** 系统消息：本发送者修改了群名称。 */
    val renameRoomBySender: Boolean = false
)

/** 命中后要执行的动作类型。不同规则的动作语义不同，统一在这里表达。 */
enum class GroupAdminHitAction {
    /** 标准 `gmHit`：按警告模式计数，够了才踢。 */
    GM_HIT,

    /** 直接踢，不走警告（疑似@全体、擅自改群名、被拍者黑名单）。 */
    KICK_NOW,

    /** 冒充群主的限时警告：只发警告并记 pending，超时后由 due-check 再踢。 */
    GRACE_WARN,

    /** 只写黑名单不踢（退群自动拉黑）。 */
    BLACKLIST_ONLY
}

/** 判定结果：命中某条规则。 */
sealed interface GroupAdminDecision {
    data class Hit(
        val rule: String,
        val reason: String,
        val action: GroupAdminHitAction = GroupAdminHitAction.GM_HIT,
        val addBlack: Boolean = true,
        val unbanHours: Int = 0,
        val warnEnable: Boolean = false,
        val warnCount: Int = 1,
        val warnText: String = "",
        val skipWarn: Boolean = false,
        val detail: String = ""
    ) : GroupAdminDecision
}

/** `gmHit` 的两个分支：先警告，或直接踢。 */
sealed interface GroupAdminHitStep {
    data class Warn(val text: String, val now: Int, val need: Int) : GroupAdminHitStep

    data object Kick : GroupAdminHitStep
}

/**
 * 纯判定层，严格按规格 2.2 的顺序：
 * `mute → fakeowner → atmany → keyword → repeat → long → video → qrcode → image → card →
 *  mini → article → voice → location → emoji → file → music → redpacket → transfer → note`
 * （视频先于图片、二维码先于图片；纯文本消息跳过整段类型判定；命中即返回，不再往下判）
 */
object GroupAdminRuleEngine {

    /** 正文是否是标记/XML 载荷（表情、卡片、系统消息等）：这类长度不能当文本长度用。 */
    fun isMarkup(text: String): Boolean {
        if (text.isEmpty()) return false
        val t = text.trimStart()
        if (t.startsWith("<") || t.startsWith("&lt;")) return true
        val head = t.take(200).lowercase()
        return head.contains("<msg") || head.contains("<appmsg") || head.contains("<sysmsg") ||
            head.contains("<emoji") || head.contains("<patmsg") || head.contains("wxid_") && head.contains("<")
    }


    /** 命中即按顺序返回（至多一条）；空列表表示没有规则命中。 */
    fun evaluate(ctx: GroupAdminMessageContext, cfg: GroupConfig): List<GroupAdminDecision> {
        if (ctx.exempt) return emptyList()
        for (id in GroupAdminRuleCatalog.CHAIN_ORDER) {
            val hit = chainRule(id, ctx, cfg) ?: continue
            return listOf(hit)
        }
        return emptyList()
    }

    private fun chainRule(id: String, ctx: GroupAdminMessageContext, cfg: GroupConfig): GroupAdminDecision.Hit? =
        when (id) {
            "mute" -> mute(ctx, cfg)
            "fakeowner" -> fakeowner(ctx, cfg)
            "atmany" -> atmany(ctx, cfg)
            "keyword" -> keyword(ctx, cfg)
            "repeat" -> repeat(ctx, cfg)
            "long" -> long(ctx, cfg)
            else -> media(id, ctx, cfg)
        }

    /** 规则 `mute`（软禁言）：本群开启后任何非豁免成员发言即触发；同成员按冷却秒数只处理一次。 */
    fun mute(ctx: GroupAdminMessageContext, cfg: GroupConfig): GroupAdminDecision.Hit? {
        val rule = GroupAdminRuleCatalog.ruleOf(cfg, "mute")
        if (!rule.on) return null
        if (!ctx.muteCooldownOk) return null
        val count = GroupAdminRuleCatalog.paramInt(
            cfg, "mute", GroupAdminRuleCatalog.PARAM_MUTE_CD, GroupAdminRuleCatalog.DEFAULT_MUTE_CD
        )
        return hit(
            cfg, "mute",
            warnEnable = true,
            warnCount = if (rule.warnCount < 1) 1 else rule.warnCount,
            detail = "全员禁言生效（冷却 " + count + " 秒）"
        )
    }

    /** 规则 `fakeowner`：群昵称冒充（a）立即限时警告；系统消息改名冒充（b）同样走限时警告。 */
    fun fakeowner(ctx: GroupAdminMessageContext, cfg: GroupConfig): GroupAdminDecision.Hit? {
        val rule = GroupAdminRuleCatalog.ruleOf(cfg, "fakeowner")
        if (!rule.on) return null
        val impersonating = ctx.senderNameHasOwner || ctx.senderNameEqualsOwner || ctx.renameToOwnerName
        if (!impersonating) return null
        val grace = GroupAdminRuleCatalog.paramInt(
            cfg, "fakeowner", GroupAdminRuleCatalog.PARAM_FAKE_GRACE, GroupAdminRuleCatalog.DEFAULT_FAKE_GRACE
        )
        if (grace <= 0) {
            return hit(cfg, "fakeowner", action = GroupAdminHitAction.KICK_NOW, detail = "限时改回设为 0，命中即踢")
        }
        if (!ctx.fakeGraceExpired) {
            return hit(
                cfg, "fakeowner",
                action = GroupAdminHitAction.GRACE_WARN,
                detail = "先发限时警告，超过 " + grace + " 分钟仍冒充才踢"
            )
        }
        return hit(cfg, "fakeowner", action = GroupAdminHitAction.KICK_NOW, detail = "限时 " + grace + " 分钟已到，仍冒充群主")
    }

    /** 规则 `atmany`：@全体（可关）→ 疑似@全体直接踢 → 普通@多人按阈值。 */
    fun atmany(ctx: GroupAdminMessageContext, cfg: GroupConfig): GroupAdminDecision.Hit? {
        val rule = GroupAdminRuleCatalog.ruleOf(cfg, "atmany")
        if (!rule.on) return null
        // 只有纯文本消息才可能 @人/@全体；名片、图片等即使 XML 里带 '@' 也不参与判定（5.5.18 修：第 6 条）
        if (!ctx.isText) return null
        val atDistinct = maxOf(ctx.atDistinct, ctx.atList.size, ctx.atCountInText)
        val atAllEnabled = GroupAdminRuleCatalog.paramBool(
            cfg, "atmany", GroupAdminRuleCatalog.PARAM_AT_ALL, true
        )
        // @全体成员 / 疑似@全体 用专属文案（默认「禁止@全体成员」），与普通@多人的「请勿一次@多人」区分开
        val atAllText = GroupAdminRuleCatalog.paramText(
            cfg, "atmany", GroupAdminRuleCatalog.PARAM_AT_ALL_TEXT, GroupAdminRuleCatalog.DEFAULT_AT_ALL_TEXT
        )
        // @全体只认宿主标志（isNotifyAll / isAnnounceAll），与普通@多人完全独立（5.5.18 修：第 5 条）
        if (ctx.atAll) {
            if (!atAllEnabled) return null
            return hit(cfg, "atmany", reason = atAllText, warnText = atAllText, detail = "@全体成员")
        }
        val limit = GroupAdminRuleCatalog.paramInt(
            cfg, "atmany", GroupAdminRuleCatalog.PARAM_AT_LIMIT, GroupAdminRuleCatalog.DEFAULT_AT_LIMIT
        )
        // 疑似@全体只作为说明信息，不再抢在普通@多人之前直接踢（小群里 @2 人会被误判）
        val suspect = ctx.memberCount >= 3 && atDistinct >= ctx.memberCount - 1 && atDistinct > 0
        if (atDistinct >= limit) {
            val detail = "@ " + atDistinct + " 人 ≥ 阈值 " + limit +
                if (suspect) "（疑似@全体：@人数 ≥ 群人数 " + ctx.memberCount + " − 1，仍按@多人处理）" else ""
            return hit(cfg, "atmany", detail = detail)
        }
        return null
    }

    /** 规则 `keyword`：文本命中关键词表。 */
    fun keyword(ctx: GroupAdminMessageContext, cfg: GroupConfig): GroupAdminDecision.Hit? {
        val rule = GroupAdminRuleCatalog.ruleOf(cfg, "keyword")
        if (!rule.on) return null
        if (!ctx.isText) return null
        val words = GroupAdminRuleCatalog.keywordList(cfg)
        if (words.isEmpty()) return null
        val matched = words.firstOrNull { ctx.content.contains(it) } ?: return null
        return hit(cfg, "keyword", detail = "命中关键词「" + matched + "」")
    }

    /** 规则 `repeat`：10 分钟窗口内同内容次数达阈值（次数由运行期算好传进来）。 */
    fun repeat(ctx: GroupAdminMessageContext, cfg: GroupConfig): GroupAdminDecision.Hit? {
        val rule = GroupAdminRuleCatalog.ruleOf(cfg, "repeat")
        if (!rule.on) return null
        if (!ctx.isText) return null
        val max = GroupAdminRuleCatalog.paramInt(
            cfg, "repeat", GroupAdminRuleCatalog.PARAM_REPEAT_MAX, GroupAdminRuleCatalog.DEFAULT_REPEAT_MAX
        )
        if (ctx.repeatCount < max) return null
        return hit(cfg, "repeat", detail = "同一内容第 " + ctx.repeatCount + " 次 ≥ 阈值 " + max + "（10 分钟窗口）")
    }

    /**
     * 规则 `long`（规格 2.4 第 6 条）：文本消息且长度 ≥ 长度阈值参数 `long_limit`（默认 500）。
     * 非文本消息不参与本规则。
     */
    fun long(ctx: GroupAdminMessageContext, cfg: GroupConfig): GroupAdminDecision.Hit? {
        val rule = GroupAdminRuleCatalog.ruleOf(cfg, GroupAdminRuleCatalog.LONG)
        if (!rule.on) return null
        // 只对真文本消息判超长：表情包/自定义表情/小程序等即使被标成文本，
        // 其正文是 XML 或媒体载荷，长度毫无意义（5.5.18 修：第 2、10 条）
        if (!ctx.isText) return null
        if (ctx.appType.isNotBlank()) return null
        if (GroupAdminRuleEngine.isMarkup(ctx.content)) return null
        val limit = GroupAdminRuleCatalog.paramInt(
            cfg, GroupAdminRuleCatalog.LONG, GroupAdminRuleCatalog.PARAM_LONG_LIMIT,
            GroupAdminRuleCatalog.DEFAULT_LONG_LIMIT
        )
        val length = ctx.content.length
        if (length < limit) return null
        return hit(cfg, GroupAdminRuleCatalog.LONG, detail = "文本长度 " + length + " ≥ 阈值 " + limit)
    }

    /**
     * 媒体类型规则（video → qrcode → image → card → mini → article → voice → location →
     * emoji → file → music → redpacket → transfer → note）。
     * 纯文本消息（type=1 且正文不含 `<`）整段跳过——规格 2.2 末句。
     */
    fun media(id: String, ctx: GroupAdminMessageContext, cfg: GroupConfig): GroupAdminDecision.Hit? {
        val rule = GroupAdminRuleCatalog.ruleOf(cfg, id)
        if (!rule.on) return null
        val f = ctx.features
        // 5.5.25 真机取证定位：原实现把「文本/引用消息整段跳过媒体判定」放在谓词计算之前，
        // 于是 type=1 的「[微笑]」「[旺柴]」这类**系统表情文本**虽然判型行 emoji=true、sysEmojiText=true
        // （isEmoji 的最后一步本就回落到 isSystemEmojiText），却永远走不到谓词 —— 即「笑脸表情无识别」的落点。
        // 同批样本判型行 pat=false、生效规则 24 条全开，故与拍一拍分支无关，也不是开关/豁免问题。
        // 处理：先算谓词，再对文本/引用消息保留「整段跳过」的参照口径，但 emoji 规则放行纯系统表情文本。
        val matched = when (id) {
            "video" -> GroupAdminMessageTypes.isVideo(f)
            "qrcode" -> GroupAdminMessageTypes.isQrcode(f)
            "image" -> GroupAdminMessageTypes.isImage(f)
            "card" -> GroupAdminMessageTypes.isCard(f)
            "mini" -> GroupAdminMessageTypes.isMini(f)
            "article" -> GroupAdminMessageTypes.isArticle(f)
            "voice" -> GroupAdminMessageTypes.isVoice(f)
            "location" -> GroupAdminMessageTypes.isLocation(f)
            "emoji" -> GroupAdminMessageTypes.isEmoji(f)
            "file" -> GroupAdminMessageTypes.isFile(f)
            "music" -> GroupAdminMessageTypes.isMusic(f)
            "redpacket" -> GroupAdminMessageTypes.isRedPacket(f)
            "transfer" -> GroupAdminMessageTypes.isTransfer(f)
            "note" -> GroupAdminMessageTypes.isNote(f)
            else -> false
        }
        if (!matched) return null
        if (GroupAdminMessageTypes.isTextLike(f)) {
            val textEmoji = id == "emoji" && GroupAdminMessageTypes.isSystemEmojiText(f)
            if (!textEmoji) return null
        }
        val extra = if (id == "qrcode") {
            "（二维码形态：" + GroupAdminMessageTypes.qrcodeFormOf(f) + "）"
        } else ""
        return hit(cfg, id, detail = "消息类型判定命中" + extra)
    }

    /** 规则 `pat`（拍一拍）：窗口内拍过的不同成员数达阈值即触发。 */
    fun pat(ctx: GroupAdminMessageContext, cfg: GroupConfig): GroupAdminDecision.Hit? {
        val rule = GroupAdminRuleCatalog.ruleOf(cfg, "pat")
        if (!rule.on) return null
        val limit = GroupAdminRuleCatalog.paramInt(
            cfg, "pat", GroupAdminRuleCatalog.PARAM_PAT_LIMIT, GroupAdminRuleCatalog.DEFAULT_PAT_LIMIT
        )
        if (ctx.patDistinctCount < limit) return null
        val window = GroupAdminRuleCatalog.paramInt(
            cfg, "pat", GroupAdminRuleCatalog.PARAM_PAT_WINDOW, GroupAdminRuleCatalog.DEFAULT_PAT_WINDOW
        )
        return hit(cfg, "pat", detail = "窗口 " + window + " 分钟内拍过 " + ctx.patDistinctCount + " 人 ≥ 阈值 " + limit)
    }

    /** 规则 `rename`（系统消息）：擅自修改群聊名称 → 直接踢，不走警告。 */
    fun rename(ctx: GroupAdminMessageContext, cfg: GroupConfig): GroupAdminDecision.Hit? {
        val rule = GroupAdminRuleCatalog.ruleOf(cfg, "rename")
        if (!rule.on) return null
        if (!ctx.renameRoomBySender) return null
        return hit(cfg, "rename", action = GroupAdminHitAction.KICK_NOW, detail = "系统消息：擅自修改群聊名称")
    }

    /** 规则 `leavebl`（退群事件）：写黑名单，不踢（人已经走了）。 */
    fun leavebl(cfg: GroupConfig): GroupAdminDecision.Hit? {
        val rule = GroupAdminRuleCatalog.ruleOf(cfg, "leavebl")
        if (!rule.on) return null
        return hit(cfg, "leavebl", action = GroupAdminHitAction.BLACKLIST_ONLY, detail = "退群自动加入黑名单")
    }

    /** 规则 `nonadmin`（入群事件）：非管理员拉人 → 踢被邀请人（可选连邀请人一起）。 */
    fun nonadmin(cfg: GroupConfig, inviterExempt: Boolean, inviteeExempt: Boolean): GroupAdminDecision.Hit? {
        val rule = GroupAdminRuleCatalog.ruleOf(cfg, "nonadmin")
        if (!rule.on) return null
        if (inviterExempt || inviteeExempt) return null
        val both = GroupAdminRuleCatalog.paramBool(
            cfg, "nonadmin", GroupAdminRuleCatalog.PARAM_NONADMIN_BOTH, false
        )
        return hit(
            cfg, "nonadmin",
            detail = if (both) "非管理员邀请入群（邀请人一并移出）" else "非管理员邀请入群"
        )
    }

    /**
     * `gmHit` 的分支：`skipWarn`/直接踢 → 踢；否则按警告模式计数，第 `need` 次才踢。
     * `currentWarnCount` 是**本次累加后**的次数。
     */
    fun hitStep(hit: GroupAdminDecision.Hit, currentWarnCount: Int): GroupAdminHitStep {
        if (hit.action == GroupAdminHitAction.KICK_NOW) return GroupAdminHitStep.Kick
        if (hit.action == GroupAdminHitAction.GRACE_WARN) return GroupAdminHitStep.Kick
        // 5.5.19 修（警告计数缺陷）：警告次数必须始终生效。
        // 旧实现 `if (!hit.warnEnable) return Kick` 让「警告开关关闭 + 次数=2」的规则一次命中就踢，
        // 用户看到的「有些类型没达到警告次数也被踢」正是这里。现在按次数判定，
        // 只是 warnEnable=false 时不发提示语（仍计数），踢人只发生在计满之后。
        val need = if (hit.warnCount < 1) 1 else hit.warnCount
        if (currentWarnCount < need) {
            return GroupAdminHitStep.Warn(
                text = hit.warnText + "（" + currentWarnCount + "/" + need + "）",
                now = currentWarnCount,
                need = need
            )
        }
        return GroupAdminHitStep.Kick
    }

    private fun hit(
        cfg: GroupConfig,
        id: String,
        action: GroupAdminHitAction = GroupAdminHitAction.GM_HIT,
        addBlack: Boolean = true,
        warnEnable: Boolean? = null,
        warnCount: Int? = null,
        reason: String? = null,
        warnText: String? = null,
        detail: String = ""
    ): GroupAdminDecision.Hit {
        val rule = GroupAdminRuleCatalog.ruleOf(cfg, id)
        val spec = GroupAdminRuleCatalog.spec(id)
        return GroupAdminDecision.Hit(
            rule = id,
            reason = reason ?: rule.reason.ifBlank { GroupAdminRuleCatalog.defaultReason(id) },
            action = action,
            addBlack = addBlack,
            unbanHours = rule.unbanHours,
            warnEnable = warnEnable ?: rule.warnEnable,
            warnCount = warnCount ?: if (rule.warnCount < 1) 1 else rule.warnCount,
            warnText = warnText
                ?: rule.warnText.ifBlank { spec?.defaultWarnText ?: GroupAdminRuleCatalog.DEFAULT_WARN_TEXT },
            skipWarn = action == GroupAdminHitAction.KICK_NOW,
            detail = detail
        )
    }
}

/**
 * 规则需要的历史状态（纯 Kotlin，无 Android 依赖，便于 JVM 单测）：
 * 重复消息窗口、拍一拍窗口、禁言冷却、冒充群主限时警告。运行期持有一个实例，
 * 并交给 60 秒懒清理调用 [prune]。
 */
class GroupAdminRuleMemory {
    private val repeat = HashMap<String, LongArray>()   // room|sender|hash → [firstTs, count]
    private val pat = HashMap<String, MutableList<Pair<Long, String>>>()  // room|sender → [(ts, patted)]
    private val mute = HashMap<String, Long>()          // room|sender → lastTs
    private val fake = HashMap<String, Long>()          // room|sender → pendingUntilTs

    companion object {
        /** 规格 2.4：重复消息统计窗口固定 10 分钟（`GM_REPEAT_WINDOW_MS`）。 */
        const val REPEAT_WINDOW_MS = 600_000L
    }

    /** 记录一条文本，返回该内容在窗口内的出现次数（含本次）；超窗口重新计数。 */
    fun noteRepeat(room: String, sender: String, text: String, now: Long, windowMs: Long = REPEAT_WINDOW_MS): Int {
        val key = room + "|" + sender + "|" + text.hashCode()
        val cur = repeat[key]
        if (cur == null || now - cur[0] > windowMs || now < cur[0]) {
            repeat[key] = longArrayOf(now, 1L)
            return 1
        }
        cur[1] = cur[1] + 1L
        return cur[1].toInt()
    }

    /** 记录一次拍一拍，返回窗口内拍过的**不同成员数**（含本次）。 */
    fun notePat(room: String, sender: String, patted: String, now: Long, windowMs: Long): Int {
        val key = room + "|" + sender
        val list = pat.getOrPut(key) { ArrayList() }
        list.add(now to patted)
        val alive = list.filter { now - it.first <= windowMs && it.first <= now }
        list.clear()
        list.addAll(alive)
        return alive.map { it.second }.filter { it.isNotEmpty() }.distinct().size
    }

    /** 禁言冷却：窗口外返回 true 并记录本次（`cooldownSeconds <= 0` 视为不冷却）。 */
    fun muteAllowed(room: String, sender: String, now: Long, cooldownSeconds: Int): Boolean {
        if (cooldownSeconds <= 0) return true
        val key = room + "|" + sender
        val last = mute[key]
        if (last != null && now - last < cooldownSeconds * 1000L && now >= last) return false
        mute[key] = now
        return true
    }

    /** 记冒充群主的限时警告；返回到期时间。 */
    fun setFakePending(room: String, sender: String, now: Long, graceMs: Long): Long {
        val until = now + graceMs
        fake[room + "|" + sender] = until
        return until
    }

    /** 限时警告是否已到期（没有 pending 时为 false）。 */
    fun fakeDue(room: String, sender: String, now: Long): Boolean {
        val until = fake[room + "|" + sender] ?: return false
        return now >= until
    }

    fun clearFake(room: String, sender: String) {
        fake.remove(room + "|" + sender)
    }

    fun hasFakePending(room: String, sender: String): Boolean = fake.containsKey(room + "|" + sender)

    /** 60 秒懒清理（规格 2.1 第 15 步）：清过期黑名单之外的三张表。 */
    fun prune(now: Long, patWindowMs: Long = 600_000L, fakeGraceMs: Long = 3_600_000L) {
        repeat.entries.removeAll { now - it.value[0] > REPEAT_WINDOW_MS || it.value[0] > now }
        mute.entries.removeAll { now - it.value > 3_600_000L || it.value > now }
        fake.entries.removeAll { it.value + fakeGraceMs < now }
        pat.entries.forEach { (_, list) -> list.removeAll { now - it.first > patWindowMs || it.first > now } }
        pat.entries.removeAll { it.value.isEmpty() }
    }

    /** 仅供单测/诊断：当前记录条数。 */
    fun size(): Map<String, Int> = mapOf(
        "repeat" to repeat.size,
        "pat" to pat.size,
        "mute" to mute.size,
        "fake" to fake.size
    )
}
