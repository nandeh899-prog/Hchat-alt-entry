package com.aeonlab.toolkit.hooks.items.groupadmin

/**
 * 系统消息 / 通知文本解析（规格 2.4 的 `rename`、`nonadmin`、`fakeowner`(b) 路径）。
 *
 * 全部是纯函数，便于 JVM 单测——微信系统消息在不同版本下形态不同（纯文本、带引号的中文名、
 * 或 XML 里的 `<link>`/`<members>`），因此解析写成「多形态兼容 + 解析不到就返回 null」，
 * 解析不到时规则**不触发**（宁可漏判也不误踢）。真机形态需按说明文档的验证步骤确认。
 */
object GroupAdminNoticeParser {

    /** 入群通知：邀请人名字（可能为空 = 邀请人未知）、被邀请人名字、被邀请人 wxid（能取到才有）。 */
    data class JoinNotice(
        val inviterName: String = "",
        val joinedName: String = "",
        val joinedId: String = "",
        /** 邀请人 wxid（5.5.20 按实测从 `_wc_custom_link_` 文本里取到）。 */
        val inviterId: String = ""
    )

    /** 改群名通知：操作者名字、新群名。 */
    data class RenameNotice(
        val actorName: String = "",
        val actorId: String = "",
        val newName: String = ""
    )

    private val QUOTED = Regex("[\"“”'‘’《》]([^\"“”'‘’《》]{1,64})[\"“”'‘’《》]")
    /** 实测：邀请人 wxid 出现在 `<_wc_custom_link_ …>wxid_xxx</_wc_custom_link_>` 的文本里。 */
    private val LINK_ID = Regex("(wxid_[A-Za-z0-9_\\-]{3,})")

    /** sysmsg 模板 `link_list` 条目（5.5.21）。 */
    private val LINK_ITEM = Regex(
        "<link\\s+name=\"([^\"]*)\"[^>]*>\\s*([^<]{1,120}?)\\s*</link>", RegexOption.IGNORE_CASE
    )
    private val XML_USERNAME = Regex("<username>\\s*(?:<!\\[CDATA\\[)?([^<\\]]+?)(?:\\]\\]>)?\\s*</username>", RegexOption.IGNORE_CASE)
    private val XML_MEMBER = Regex("<member>\\s*(?:<!\\[CDATA\\[)?([^<\\]]+?)(?:\\]\\]>)?\\s*</member>", RegexOption.IGNORE_CASE)

    // 5.5.24 按真机模板修：`link_list` 的条目**不是**纯文本，而是内嵌成员节点，例如
    // <link name="username" type="link_profile"><memberlist><member>
    //   <username><![CDATA[wxid_95wgia0a0lbj22]]></username>
    //   <nickname><![CDATA[.⃪ོه̪云裳ꦿ⃪ོ୊⃪໊]]></nickname></member></memberlist></link>
    // <link name="remark" type="link_plain"><plain><![CDATA[测试]]></plain></link>
    // 旧正则要求 `<link name="x">明文</link>`，实测模板一律匹配不到 → 5.5.21/5.5.22 的改名与邀请人解析因此失败。
    private val LINK_BLOCK = Regex(
        "<link\\s+name=\"([^\"]+)\"[^>]*>(.*?)</link>",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )
    private val LINK_NODE_USER = Regex("<username>\\s*(?:<!\\[CDATA\\[)?([^<\\]]+?)(?:\\]\\]>)?\\s*</username>", RegexOption.IGNORE_CASE)
    private val LINK_NODE_NICK = Regex("<nickname>\\s*(?:<!\\[CDATA\\[)?([^<\\]]+?)(?:\\]\\]>)?\\s*</nickname>", RegexOption.IGNORE_CASE)
    private val LINK_NODE_PLAIN = Regex("<plain>\\s*(?:<!\\[CDATA\\[)?([^<\\]]*?)(?:\\]\\]>)?\\s*</plain>", RegexOption.IGNORE_CASE)

    // ---- 入群 ----

    /**
     * 解析入群通知。支持：
     * `A邀请"B"加入了群聊`、`"A"邀请"B"加入了群聊`、`你邀请"B"加入了群聊`、
     * `"B"通过扫描"A"分享的二维码加入群聊`、`"B"加入了群聊`。
     */
    fun joinNotice(content: String, xml: String = ""): JoinNotice? {
        val text = stripMarkup(content)
        if (text.isEmpty()) return null

        // 形态零（5.5.20 按真机实测新增）：
        // 实测消息 type=10000、正文为「邀请者：<昵称> (<_wc_custom_link_ …>wxid_xxx</_wc_custom_link_>)」，
        // **不含**「加入了群聊」四字，邀请人昵称与 wxid 都在这一条里；被邀请人由成员表差分得到。
        if (text.contains("邀请者")) {
            val head = text.substringAfter("邀请者").trimStart('：', ':', ' ', '\u3000')
            val inviterName = cleanName(head.substringBefore("("))
            val inviterId = LINK_ID.find(content)?.groupValues?.getOrNull(1).orEmpty()
            if (inviterName.isNotEmpty() || inviterId.isNotEmpty()) {
                return JoinNotice(
                    inviterName = inviterName, joinedName = "", joinedId = "", inviterId = inviterId
                )
            }
        }

        val joined = text.contains("加入了群聊") || text.contains("加入群聊")
        if (!joined) return null
        // 5.5.24：模板占位符（$username$/$names$）在真机上不会被填充，必须优先读 `link_list`。
        // 实测：<link name="username">=邀请人（wxid+昵称）、<link name="names">=被邀请人。
        val links = templateLinks(xml)
        fun lk(n: String) = links.firstOrNull { it.first.equals(n, true) }?.second.orEmpty()
        val linkInviterId = lk("username")
        val linkInviterNick = lk("username.nick")
        val linkJoinedId = lk("names")
        val linkJoinedNick = lk("names.nick")
        if (linkInviterId.isNotEmpty() || linkJoinedId.isNotEmpty()) {
            return JoinNotice(
                inviterName = linkInviterNick,
                joinedName = linkJoinedNick,
                joinedId = if (linkJoinedId.contains("@chatroom")) "" else linkJoinedId,
                inviterId = linkInviterId
            )
        }
        val joinedId = joinedIdFrom(xml)

        // 形态一／二：X 邀请 Y 加入了群聊
        if (text.contains("邀请")) {
            val head = text.substringBefore("邀请").trim()
            val inviter = cleanName(head)
            val after = text.substringAfter("邀请", "")
            val joinedName = joinedNameFrom(after)
            if (inviter.isEmpty()) {
                // 「你邀请"B"加入了群聊」——本人在操作，邀请人就是自己，调用方按自己处理
                return JoinNotice(inviterName = "", joinedName = joinedName, joinedId = joinedId)
            }
            return JoinNotice(inviterName = inviter, joinedName = joinedName, joinedId = joinedId)
        }

        // 形态三：扫描二维码入群
        if (text.contains("扫描") && text.contains("二维码")) {
            val inviter = text.substringAfter("扫描", "").substringBefore("分享").trim().let { cleanName(it) }
            val joinedName = text.substringBefore("通过").trim().let { cleanName(it) }
            return JoinNotice(inviterName = inviter, joinedName = joinedName, joinedId = joinedId)
        }

        // 形态四：无邀请人
        return JoinNotice(inviterName = "", joinedName = text.substringBefore("加入").trim().let { cleanName(it) }, joinedId = joinedId)
    }

    private fun joinedNameFrom(after: String): String {
        val quoted = QUOTED.find(after)?.groupValues?.getOrNull(1)
        if (!quoted.isNullOrBlank()) return quoted.trim()
        return after.substringBefore("加入").trim().let { cleanName(it) }
    }

    /**
     * 解析 sysmsg 模板 `<link_list>` 里的 `name → 文本` 对（5.5.21 新增，用于改名/邀请类模板）。
     * 实测模板形如 `<link name="username" type="link_profile">显示名</link>`。
     */
    fun templateLinks(xml: String): List<Pair<String, String>> {
        if (xml.isBlank()) return emptyList()
        val out = ArrayList<Pair<String, String>>()
        for (m in LINK_BLOCK.findAll(xml)) {
            val name = m.groupValues[1].trim()
            val body = m.groupValues[2]
            val user = LINK_NODE_USER.find(body)?.groupValues?.getOrNull(1)?.trim().orEmpty()
            val nick = LINK_NODE_NICK.find(body)?.groupValues?.getOrNull(1)?.trim().orEmpty()
            val plain = LINK_NODE_PLAIN.find(body)?.groupValues?.getOrNull(1)?.trim().orEmpty()
            val value = when {
                user.isNotEmpty() -> user
                plain.isNotEmpty() -> plain
                else -> ""
            }
            if (name.isNotEmpty() && value.isNotEmpty()) out.add(name to value)
            if (name.isNotEmpty() && nick.isNotEmpty()) out.add(name + ".nick" to nick)
        }
        // 兜底：老式纯文本 link 条目（`<link name="x">明文</link>`）
        if (out.isEmpty()) {
            for (m in LINK_ITEM.findAll(xml)) {
                val name = m.groupValues[1].trim()
                val value = m.groupValues[2].trim()
                if (value.isNotEmpty()) out.add(name to value)
            }
        }
        return out
    }

    /** XML 里能拿到被邀请人 wxid（`<username>`/`<member>`）；拿不到返回空串。 */
    fun joinedIdFrom(xml: String): String {
        if (xml.isBlank()) return ""
        val candidate = XML_MEMBER.find(xml)?.groupValues?.getOrNull(1)
            ?: XML_USERNAME.find(xml)?.groupValues?.getOrNull(1)
            ?: return ""
        val id = candidate.trim()
        return if (id.contains("@chatroom")) "" else id
    }

    // ---- 改群名 ----

    /**
     * 解析改群名通知：「X修改群名为"新名字"」「"X"修改群名为"新名字"」。
     * 只有确实包含「修改群名」时才返回。
     */
    fun renameNotice(content: String, xml: String = ""): RenameNotice? {
        val text = stripMarkup(content)
        // 5.5.18 修：真机形态可能不止「修改群名为」（第 13 条），加宽语义再兜底关键词
        val keys = listOf("修改群名为", "改了群名为", "改群名为", "修改群名称", "群名称已改为", "群名修改为", "修改群名")
        val keyword = keys.firstOrNull { text.contains(it) } ?: return null
        val actorPart = text.substringBefore(keyword)
        val tail = text.substringAfter(keyword)
        val newName = QUOTED.find(tail)?.groupValues?.getOrNull(1)?.trim()
            ?: tail.trim().let { cleanName(it) }
        val actorId = XML_USERNAME.find(xml)?.groupValues?.getOrNull(1)?.trim().orEmpty()
        // 5.5.21 按真机日志修：改名通知实为 sysmsg 模板（正文形如 `<sysmsgtemplate>…
        // 改群名为“$remark$”]]></template><link_list><link name="…">值</link>…`），操作者名字在
        // `<link_list>` 里而不是纯文本前缀；这里按名称优先取 `username`，取不到再取第一个链接文本。
        val links = templateLinks(xml)
        val linkActor = links.firstOrNull { it.first.equals("username", true) }?.second
            ?: links.firstOrNull()?.second.orEmpty()
        val resolvedActor = cleanName(actorPart).ifBlank { cleanName(linkActor) }
        val linkNewName = links.firstOrNull { !it.first.equals("username", true) }?.second.orEmpty()
        return RenameNotice(
            actorName = resolvedActor,
            actorId = if (actorId.contains("@chatroom")) "" else actorId,
            newName = newName.ifBlank { linkNewName }
        )
    }

    // ---- 冒充群主 ----

    /**
     * 群昵称是否冒充群主（规格 2.4 fakeowner a；5.5.22 按用户更正收窄）：
     * ① 昵称**整体**就是「群主」称呼——归一化并去掉装饰符/括号后核心等于「群主」
     *    （覆盖「群主」「（群主）」「【群主】」以及带表情/零宽装饰的「群主」）；
     * ② 昵称与真实群主昵称完全相同——比较前统一归一化。
     *
     * 真机证据（2026-11-05 12:49:14）：昵称 `.⃪ོه⃪云裳ꦿ⃪⃪⃪໊ོ群主` 的成员发 @ 多人文本消息时被旧口径
     * （任意位置 `contains("群主")`）判为冒充群主，属误判；长昵称里顺带出现「群主」二字不再算。
     * 另：本人昵称取自群成员资料，@ 名单与正文永不参与本判定（见 nickKnown 前置）。
     */
    fun impersonates(name: String, ownerName: String = ""): Boolean {
        val n = normalizeName(name)
        if (n.isEmpty()) return false
        // 5.5.28 按用户 2026-11-05 明确口径放宽（推翻 5.5.22 的收窄）：昵称含「群主」文本即算冒充，
        // 覆盖三种形态——① 群主；②（群主）等带括号/装饰；③ 长昵称顺带含「群主」（如「…云裳…群主」）。
        // 与群主同名仍由下方 n == o 判定。原 coreName(n) == OWNER_WORD 只覆盖 ①②，长昵称漏判。
        if (n.contains(OWNER_WORD) || name.contains(OWNER_WORD)) return true
        val o = normalizeName(ownerName)
        return o.isNotEmpty() && n == o
    }

    /** 独立群主称呼的核心词（5.5.22）。 */
    const val OWNER_WORD = "群主"

    /**
     * 昵称「核心名」：只保留字母/数字/CJK，丢掉表情、零宽字符、括号、空格等修饰（5.5.22）。
     * 用途：判断昵称是否**整体**构成「群主」称呼，避免长昵称中顺带出现「群主」即命中。
     */
    fun coreName(raw: String): String {
        val sb = StringBuilder(raw.length)
        for (ch in normalizeName(raw)) {
            if (ch in '0'..'9' || ch in 'a'..'z' || ch in 'A'..'Z' || ch.code in 0x4E00..0x9FFF) sb.append(ch)
        }
        return sb.toString()
    }

    /** 昵称归一化（5.5.21）：用于匹配比较，不改写名字本身。 */
    fun normalizeName(raw: String): String {
        val sb = StringBuilder(raw.length)
        for (ch in raw) {
            when (ch) {
                '(', '（' -> sb.append('(')
                ')', '）' -> sb.append(')')
                else -> if (!ch.isWhitespace() && ch != '\u200B' && ch != '\u200C' && ch != '\u200D' &&
                    ch != '\uFEFF' && ch != '\u2060'
                ) {
                    sb.append(ch)
                }
            }
        }
        return sb.toString().trim()
    }

    /**
     * 命中依据（5.5.21，要求写进判型日志）：返回可直接读的中文依据，含归一化结果。
     * 两类口径必须能区分：「含群主」（含括号形态）与「与群主完全同名」。
     */
    fun impersonateBasis(name: String, ownerName: String = ""): String {
        val n = normalizeName(name)
        if (n.isEmpty()) return "昵称为空"
        val c = coreName(n)
        if (c == OWNER_WORD) return "昵称整体即「群主」称呼（核心=「" + c + "」归一化=「" + n + "」）"
        val o = normalizeName(ownerName)
        if (o.isEmpty()) return "未命中：昵称核心=「" + c + "」非独立「群主」称呼，且群主昵称未取到"
        return if (n == o) "与群主昵称完全同名（「" + n + "」）"
        else "未命中：昵称核心=「" + c + "」非独立「群主」称呼，与群主昵称「" + o + "」也不同名（@名单与正文不参与判定）"
    }

    /** 系统消息里把名字改成含「群主」或等于群主昵称（规格 2.4 fakeowner b）。 */
    fun renamedToImpersonate(newName: String, ownerName: String = ""): Boolean = impersonates(newName, ownerName)

    // ---- 工具 ----

    /** 正文里的 @ 计数；`@所有人` 不计入（规格 2.4 第 5 条）。 */
    fun atCountInText(content: String): Int {
        if (content.isBlank()) return 0
        var count = 0
        var index = content.indexOf("@")
        while (index >= 0) {
            val head = content.substring(index, minOf(content.length, index + 4))
            if (!head.startsWith("@所有人")) count++
            index = content.indexOf("@", index + 1)
        }
        return count
    }

    /** 去掉 ASCII 空白与零宽字符，保留中文引号（引号是解析标记）。 */
    private fun stripMarkup(text: String): String =
        text.replace("\u200b", "").replace("\uFEFF", "").replace("\n", " ").trim()

    private fun cleanName(raw: String): String =
        raw.trim().trim('"', '“', '”', '\'', '‘', '’', ':', '：', '，', ',', '。', '!', '！').trim()
}
