package com.aeonlab.toolkit.hooks.items.groupadmin

/**
 * 消息类型判定（5.5.19：**对齐模块内「屏蔽消息」的判型实现**）。
 *
 * 唯一权威参照（本模块自带、已在真机长期使用）：
 *  - `hooks/api/model/WeChatMessage.kt` 的宿主谓词：`isText/isQuote/isImage/isVideo/isVoice/
 *    isLink/isFile/isNote/isMusic/isMiniProgram/isShareCard/isEmoji/isRedPacket/isTransfer/
 *    isVoip/isLocation/isSystem/isPat/isVideoNumberVideo`，以及 `appMsgType()/bodyContent()`。
 *  - `hooks/api/model/WeChatMessageTypes.kt` 的 `normalize/normalize 规则`。
 *  - `hooks/items/messageblock/MessageBlockFeature.kt#typeTokenMatches` 的「类型 → 谓词」对应表。
 *
 * 之前群管自己维护了一份 appmsg 数字表（把名片当成 appmsg 42/66 等），与参照不一致，
 * 导致音乐/接龙/小程序/订阅号文章/二维码/名片/拍一拍判不出来。本轮改为：
 * **宿主谓词优先，其次才用与参照同款的 XML 兜底**，不再自造判定方式。
 *
 * 设计：模块 IPC 只给一个**特征袋** [GroupAdminMessageFeatures]（宿主谓词结果 + type + 正文），
 * 判定函数是纯函数，可在 JVM 单测；运行期由 `GroupAdminMessageRuntime` 负责填特征袋。
 */
data class GroupAdminMessageFeatures(
    val type: Int = 0,
    /** 正文（群聊里已剥掉 `wxid:
` 前缀，等价参照的 bodyContent()）。 */
    val content: String = "",
    /** 原始正文（未剥前缀），保留用于兜底与日志。 */
    val rawContent: String = "",
    /** 消息 XML（系统消息/入群通知解析要用），取不到就是空串。 */
    val xml: String = "",
    /** appmsg 子类型（`<type>…</type>`），等价参照的 `appMsgType()`；取不到是空串。 */
    val appType: String = "",
    /** 宿主谓词标记（`WeChatMessage` 的同名判定结果）。 */
    val flags: Set<String> = emptySet(),
    val atList: List<String> = emptyList(),
    val atAllFlag: Boolean = false,
    val atCountInText: Int = 0,
    val patFrom: String = "",
    val patPatted: List<String> = emptyList()
) {
    fun has(flag: String): Boolean = flags.contains(flag)
}

object GroupAdminMessageTypes {

    // ===== 宿主谓词标记（与 WeChatMessage 的 isXxx() 一一对应） =====
    const val F_TEXT = "text"
    const val F_QUOTE = "quote"
    const val F_IMAGE = "image"
    const val F_VIDEO = "video"
    const val F_VOICE = "voice"
    const val F_LINK = "link"
    const val F_FILE = "file"
    const val F_MUSIC = "music"
    const val F_MINI = "mini_program"
    const val F_CARD = "share_card"
    const val F_EMOJI = "emoji"
    const val F_REDPACKET = "red_packet"
    const val F_TRANSFER = "transfer"
    const val F_VOIP = "voip"
    const val F_LOCATION = "location"
    const val F_SYSTEM = "system"
    const val F_PAT = "pat"
    const val F_NOTE = "note"
    const val F_VIDEONUMBER = "video_number"

    // ===== 类型号（与 WeChatMessageTypes 一致，仅作谓词取不到时的兜底） =====
    internal const val TYPE_TEXT = 1
    private const val TYPE_IMAGE = 3
    private const val TYPE_VOICE = 34
    private const val TYPE_VIDEO = 43
    private const val TYPE_VIDEO_FORWARD = 62
    private const val TYPE_EMOJI = 47
    private const val TYPE_LOCATION = 48
    private const val TYPE_APP = 49
    private const val TYPE_SYSTEM = 10000
    private const val TYPE_RECALLED = 10002
    private const val TYPE_CARD_A = 42
    private const val TYPE_CARD_B = 66
    private const val TYPE_VOIP_A = 50
    private const val TYPE_VOIP_B = 1000052
    private const val TYPE_VOIP_C = 1000053
    private const val TYPE_VIDEO_ACCOUNT = 0x2D000031
    private const val TYPE_VIDEO_ACCOUNT_CARD = 0x2E000031
    private const val TYPE_VIDEO_ACCOUNT_LIVE = 0x3A000031

    private const val APP_MUSIC_A = "3"
    private const val APP_MUSIC_B = "76"
    private const val APP_LINK_A = "4"
    private const val APP_LINK_B = "5"
    private const val APP_FILE = "6"
    private const val APP_EMOJI = "8"
    private const val APP_MINI_A = "33"
    private const val APP_MINI_B = "36"
    private const val APP_NOTE = "53"
    private const val APP_QUOTE = "57"
    private const val APP_TRANSFER_A = "2000"
    private const val APP_TRANSFER_B = "2011"
    private const val APP_REDPACKET = "2001"

    private val APP_TYPE_REGEX = Regex("<type>\\s*(\\d+)\\s*</type>")
    private val SYSTEM_EMOJI_TOKEN = Regex("^\\[[^\\[\\]]{1,12}\\]$")

    /** 归一化类型号：与 `WeChatMessageTypes.normalize` 同规则（含系统消息/撤回特判）。 */
    fun normalizeType(type: Int): Int {
        if (type <= 0) return type
        val low8 = type and 0xFF
        val low16 = type and 0xFFFF
        if ((type ushr 16) == 0) return type
        if (low16 == TYPE_SYSTEM || low16 == TYPE_RECALLED) return low16
        return if (low8 != 0 && low16 == low8) low8 else type
    }

    /** appmsg 子类型：依次在正文、原始正文、XML 里取 `<type>…</type>`（等价参照的 appMsgType()）。 */
    fun appType(primary: String, secondary: String = ""): String {
        for (text in listOf(primary, secondary)) {
            if (text.isNullOrEmpty()) continue
            val found = APP_TYPE_REGEX.find(text)?.groupValues?.getOrNull(1)
            if (!found.isNullOrEmpty()) return found.trim()
        }
        return ""
    }

    fun appTypeOf(f: GroupAdminMessageFeatures): String {
        if (f.appType.isNotBlank()) return f.appType
        return appType(f.content, f.rawContent.ifBlank { f.xml })
    }

    /** 正文（优先剥前缀后的 content）。 */
    private fun body(f: GroupAdminMessageFeatures): String =
        if (f.content.isNotBlank()) f.content else f.rawContent.ifBlank { f.xml }

    // ===== 基础谓词 =====

    fun isApp(f: GroupAdminMessageFeatures): Boolean = normalizeType(f.type) == TYPE_APP

    fun isText(f: GroupAdminMessageFeatures): Boolean =
        f.has(F_TEXT) || normalizeType(f.type) == TYPE_TEXT

    /** 参照 `isTextLikeMessage`：文本，或引用且引用标题非空（这里用「有引用标记」近似）。 */
    fun isTextLike(f: GroupAdminMessageFeatures): Boolean = isText(f) || f.has(F_QUOTE)

    fun isPlainText(f: GroupAdminMessageFeatures): Boolean = isTextLike(f) && !markup(f)

    /** 真·纯文本（type=1、无 appmsg 子型、正文非标记）——超长/重复/@ 等文本规则门槛。 */
    fun isPlainText(type: Int, content: String, appType: String): Boolean {
        if (normalizeType(type) != TYPE_TEXT) return false
        if (appType.isNotBlank()) return false
        return !GroupAdminRuleEngine.isMarkup(content)
    }

    private fun markup(f: GroupAdminMessageFeatures): Boolean =
        GroupAdminRuleEngine.isMarkup(body(f))

    fun isSystem(f: GroupAdminMessageFeatures): Boolean =
        f.has(F_SYSTEM) || normalizeType(f.type) == TYPE_SYSTEM

    fun isImage(f: GroupAdminMessageFeatures): Boolean =
        f.has(F_IMAGE) || normalizeType(f.type) == TYPE_IMAGE

    fun isVideo(f: GroupAdminMessageFeatures): Boolean {
        val t = normalizeType(f.type)
        return f.has(F_VIDEO) || t == TYPE_VIDEO || t == TYPE_VIDEO_FORWARD
    }

    fun isVoice(f: GroupAdminMessageFeatures): Boolean =
        f.has(F_VOICE) || normalizeType(f.type) == TYPE_VOICE

    fun isLocation(f: GroupAdminMessageFeatures): Boolean =
        f.has(F_LOCATION) || normalizeType(f.type) == TYPE_LOCATION

    fun isVideoNumber(f: GroupAdminMessageFeatures): Boolean {
        if (f.has(F_VIDEONUMBER)) return true
        val t = normalizeType(f.type)
        return t == TYPE_VIDEO_ACCOUNT || t == TYPE_VIDEO_ACCOUNT_CARD || t == TYPE_VIDEO_ACCOUNT_LIVE
    }

    fun isVoip(f: GroupAdminMessageFeatures): Boolean {
        val t = normalizeType(f.type)
        return f.has(F_VOIP) || t == TYPE_VOIP_A || t == TYPE_VOIP_B || t == TYPE_VOIP_C
    }

    /** 引用（参照 `isQuote()`）：appmsg 57 或正文含 refermsg 系列标签。 */
    fun isQuote(f: GroupAdminMessageFeatures): Boolean {
        if (f.has(F_QUOTE)) return true
        if (appTypeOf(f) == APP_QUOTE) return true
        val b = body(f)
        return b.contains("<refermsg>", true) || b.contains("<referfromusr>", true) ||
            b.contains("<refermsgid>", true)
    }

    /**
     * 系统表情（笑脸）：微信「系统表情」是文本消息里的 `[微笑]` 这类编码，整条只由表情码组成。
     * 参照实现只认 type=47 的贴图，用户要求把两类都算「表情」，故这里显式补上系统表情分支。
     */
    fun isSystemEmojiText(f: GroupAdminMessageFeatures): Boolean {
        if (!isText(f)) return false
        val t = body(f).trim()
        if (t.isEmpty() || t.contains('<')) return false
        val tokens = t.split(Regex("\\s+")).filter { it.isNotEmpty() }
        return tokens.isNotEmpty() && tokens.all { SYSTEM_EMOJI_TOKEN.matches(it) }
    }

    /** 表情（参照 `isEmoji()` = type 47；另加 appmsg 8 与正文标记兜底，以及系统表情文本）。 */
    fun isEmoji(f: GroupAdminMessageFeatures): Boolean {
        if (f.has(F_EMOJI) || normalizeType(f.type) == TYPE_EMOJI) return true
        if (appTypeOf(f) == APP_EMOJI) return true
        val b = body(f)
        if (b.contains("<emoji") || b.contains("imgmd5")) return true
        return isSystemEmojiText(f)
    }

    /** 文件（参照 `isFile()`）：appmsg 6 或 attach 标记。 */
    fun isFile(f: GroupAdminMessageFeatures): Boolean {
        if (f.has(F_FILE)) return true
        if (appTypeOf(f).isEmpty() && !isApp(f)) return false
        val a = appTypeOf(f)
        if (a.isNotEmpty()) return a == APP_FILE
        val b = body(f)
        return b.contains("<fileext>", true) || b.contains("<filename>", true) ||
            b.contains("<cdnattachurl>", true)
    }

    /** 链接/文章（参照 `isLink()` = appmsg 4/5）；订阅号文章走这条。 */
    /**
     * 通知类消息（5.5.20 按实测新增）：系统消息（type=10000）或系统模板通知
     * （实测 type=570425393，低位归一 49，正文/xml 含 `<sysmsgtemplate>`）。
     */
    fun isNotice(f: GroupAdminMessageFeatures): Boolean {
        if (isSystem(f)) return true
        val c = f.content
        if (c.contains("sysmsgtemplate") || c.contains("<sysmsg")) return true
        val x = f.xml
        return x.contains("sysmsgtemplate") || x.contains("<sysmsg")
    }

    fun isLink(f: GroupAdminMessageFeatures): Boolean {
        if (f.has(F_LINK)) return true
        return appTypeOf(f) == APP_LINK_A || appTypeOf(f) == APP_LINK_B
    }

    /** 订阅号文章：与参照的「文章/链接」同一判定（用户实测「订阅号文章无效」即本条）。 */
    fun isArticle(f: GroupAdminMessageFeatures): Boolean = isLink(f)

    /** 音乐卡片（参照 `isMusic()` = appmsg 3/76）。 */
    fun isMusic(f: GroupAdminMessageFeatures): Boolean {
        if (f.has(F_MUSIC)) return true
        return appTypeOf(f) == APP_MUSIC_A || appTypeOf(f) == APP_MUSIC_B
    }

    /** 小程序（参照 `isMiniProgram()` = appmsg 33/36）。 */
    fun isMini(f: GroupAdminMessageFeatures): Boolean {
        if (f.has(F_MINI)) return true
        return appTypeOf(f) == APP_MINI_A || appTypeOf(f) == APP_MINI_B
    }

    /** 接龙（参照 `isNote()` = appmsg 53 或正文含 solitaire/接龙）。 */
    fun isNote(f: GroupAdminMessageFeatures): Boolean {
        if (f.has(F_NOTE)) return true
        if (appTypeOf(f) == APP_NOTE) return true
        val b = body(f)
        return b.contains("solitaire", true) || b.contains("接龙")
    }

    /** 名片（参照 `isShareCard()` = **裸类型 42/66**，不是 appmsg 子型）。 */
    fun isCard(f: GroupAdminMessageFeatures): Boolean {
        if (f.has(F_CARD)) return true
        val t = normalizeType(f.type)
        return t == TYPE_CARD_A || t == TYPE_CARD_B
    }

    /** 红包（参照 `isRedPacket()`：nativeurl/正文关键字或 appmsg 2001）。 */
    fun isRedPacket(f: GroupAdminMessageFeatures): Boolean {
        if (f.has(F_REDPACKET)) return true
        val b = body(f)
        val nativeUrl = xmlTag(b, "nativeurl").lowercase()
        return nativeUrl.contains("receivehongbao") || nativeUrl.contains("wxhb") ||
            nativeUrl.contains("hongbao") ||
            b.contains("receivehongbao", true) || b.contains("wxhb_personalreceive", true) ||
            b.contains("/hongbao/", true) || appTypeOf(f) == APP_REDPACKET
    }

    /** 转账（参照 `isTransfer()`：appmsg 2000/2011 或 wcpayinfo 标记）。 */
    fun isTransfer(f: GroupAdminMessageFeatures): Boolean {
        if (f.has(F_TRANSFER)) return true
        val a = appTypeOf(f)
        if (isApp(f) && (a == APP_TRANSFER_A || a == APP_TRANSFER_B)) return true
        val b = body(f)
        return b.contains("<wcpayinfo>", true) &&
            (b.contains("<transferid>", true) || b.contains("<transcationid>", true) ||
                b.contains("<transactionid>", true) || b.contains("transfer_id=", true) ||
                b.contains("trans_id=", true) || b.contains("transferoperation", true))
    }

    /** 拍一拍（参照 `isPat()`：系统消息正文含 pattedUser/pattedusername/拍了拍）。 */
    fun isPat(f: GroupAdminMessageFeatures): Boolean {
        // 5.5.23 按用户指令改为「白名单排除法」（5.5.21 的「必须有正文标记」把真拍一拍也挡掉了：
        // 2026-11-05 15:28:35 连续 8 条拍一拍无任何响应，属回归）。
        // 判定顺序与依据：
        //   ① 名片（42/66）与已知 appmsg 卡片（appType 非空：8/76/5/53/33…）直接排除 —— 真机证据
        //      2026-11-05 12:42–12:59 这些卡片的判型行全部 pat=true，必须继续挡在拍一拍分支之外；
        //   ② 宿主拍一拍字段（patFrom/patPatted）非空 → 判为拍一拍；
        //   ③ 否则看宿主标志 F_PAT 或正文/XML 标记（拍了拍/patMsg/pattedUser），并要求类型属通知类
        //      （49/10000/10002 或 isSystem）——真拍一拍是通知类消息，而纯文本/图片/视频等不会走这里。
        if (isCard(f)) return false
        if (appTypeOf(f).isNotEmpty()) return false
        if (f.patFrom.isNotEmpty() || f.patPatted.isNotEmpty()) return true
        if (hasPatText(f)) return true
        // 5.5.24 真机取证（2026-11-05 15:28:35 拍一拍全部无响应）：真拍一拍成对出现两条——
        // ① type=10000 flags=[pat, system] **xml长度=0**、正文=`"${wxid_95wgia0a0lbj22}"`（占位符，无任何标记）；
        // ② type=10002 flags=[pat, system] xml长度=376，正文=<sysmsg type="pat"> <pat> …
        // 5.5.21/5.5.22 要求「正文/XML 必须有拍了拍字样」→ 两条都不满足 → 拍一拍整条链路失效。
        // 5.5.27 **按用户「定点改动 + 已测项目不可回归」要求收窄**：5.5.24 曾用「F_PAT + XML 非空」这条宽口径，
        // 它会让任何带宿主拍一拍标志且 XML 非空的消息（含通知类之外的形态）进入拍一拍分支；
        // 现改为「F_PAT + 类型属通知类（10000/10002/49 或 isSystem）+ 正文/XML 里带拍一拍**专有载荷**」。
        // 该口径对真机样本仍成立：样本 ② 的正文含 <sysmsg type="pat"> 与 <pat>，命中 hasPatPayload；
        // 样本 ① xml长度=0、正文无标记，本来就两条口径都不满足（它只是 ② 的伴生条目，不承担判定与计数）。
        if (!f.has(F_PAT)) return false
        val pt = normalizeType(f.type)
        val noticeLike = pt == 49 || pt == 10000 || pt == 10002 || isSystem(f)
        return noticeLike && hasPatPayload(f)
    }

    /** 拍一拍**专有载荷**（5.5.27 收窄用）：只认拍一拍自身的节点/字段名，避免其它通知类 XML 被误判。 */
    fun hasPatPayload(f: GroupAdminMessageFeatures): Boolean {
        val b = body(f)
        return b.contains("type=\"pat\"", true) || b.contains("<pat ", true) || b.contains("<pat>", true) ||
            b.contains("patMsg", true) || b.contains("pattedUser", true) ||
            b.contains("pattedusername", true) || b.contains("拍了拍")
    }

    /** 正文/XML 是否带拍一拍标记（5.5.23）。 */
    fun hasPatText(f: GroupAdminMessageFeatures): Boolean {
        val b = body(f)
        return b.contains("拍了拍") || b.contains("<patMsg", true) ||
            b.contains("pattedUser", true) || b.contains("pattedusername", true)
    }

    /** 二维码形态：`CARD` 个人名片型、`IMAGE` 截图型、`NONE`。 */
    const val QR_FORM_CARD = "名片型"
    const val QR_FORM_IMAGE = "截图型"
    const val QR_FORM_NONE = ""

    /**
     * 二维码：模块参照里没有这一类，故按「两种形态分开判 + XML 特征兜底 + 日志取证」实现
     * （用户实测两种都落到 image）：
     *  - 名片型：名片（裸类型 42/66）且正文含二维码特征；
     *  - 截图型：图片且正文/XML 含二维码特征（`qrcode`/`<qrcode`/`二维码`/`qr_code`）。
     * 特征取不到时记为未命中并在判型日志里留下 type/appType/xml 摘要，下一轮据此再定。
     */
    fun qrcodeFormOf(f: GroupAdminMessageFeatures): String {
        // 5.5.21 按真机日志修（2026-11-05 12:43:58）：裸 type=42 名片曾被无条件判成「名片型二维码」，
        // 同一会话里 @ 提及类消息也被这条截走。互斥前置：文本/@ 提及、系统与模板通知、拍一拍一律不判二维码。
        if (f.has(F_TEXT) || isPlainText(f.type, f.content, f.appType)) return QR_FORM_NONE
        if (isSystem(f) || isNotice(f) || isPat(f)) return QR_FORM_NONE
        val b = body(f)
        if (b.contains('@')) return QR_FORM_NONE
        // 5.5.22 按用户更正修回归：5.5.18 的 isQrcode 只认「图片 且 含 qrcode/二维码」，
        // 5.5.19 重写时给名片加了无条件命中，导致「发名片被判二维码」（以前正常）。
        // 现在名片型必须识别出二维码**载荷**特征（qrcode/qr_code），仅文字里出现「二维码」不算。
        val payload = b.contains("qrcode", true) || b.contains("qr_code", true) ||
            f.xml.contains("qrcode", true) || f.xml.contains("qr_code", true)
        if (isCard(f)) return if (payload) QR_FORM_CARD else QR_FORM_NONE
        // 5.5.30 按用户 2026-11-06 明确授权的口径：**图片消息且正文不含 hevc_mid_size 即判为二维码**。
        // 真机取证（11:05:17 个人二维码 → 属性 16 个、xml 长度 1128、无 hevc_mid_size；
        //          11:05:30 二维码截图 → 属性 17 个、xml 长度 1150、含 hevc_mid_size）。
        // 原「正文含 qrcode/二维码 才算」的图片判据按该口径替换（该文本判据不再单独成立）。
        if (isImage(f)) {
            val hasHevcMid = f.xml.contains("hevc_mid_size", true) || b.contains("hevc_mid_size", true)
            return if (hasHevcMid) QR_FORM_NONE else QR_FORM_IMAGE
        }
        return QR_FORM_NONE
    }

    fun isQrcode(f: GroupAdminMessageFeatures): Boolean = qrcodeFormOf(f).isNotEmpty()

    /** 拍一拍被拍者兜底解析（宿主不投 patPattedUser 时用）。 */
    fun patPattedOf(xml: String, content: String): List<String> {
        val out = LinkedHashSet<String>()
        for (src in listOf(xml, content)) {
            if (src.isBlank()) continue
            for (key in listOf("pattedusername", "patteduser", "pattedusername_str", "patteddisplayname")) {
                val re = Regex("<" + key + "\\s*>\\s*([^<]+)", RegexOption.IGNORE_CASE)
                re.findAll(src).forEach { m ->
                    val v = m.groupValues[1].trim()
                    if (v.isNotEmpty() && v != "notify@all") out.add(v)
                }
            }
        }
        return out.toList()
    }

    private fun xmlTag(raw: String, tag: String): String {
        if (raw.isEmpty()) return ""
        return Regex("<" + tag + "\\s*>\\s*([^<]*)", RegexOption.IGNORE_CASE)
            .find(raw)?.groupValues?.getOrNull(1)?.trim().orEmpty()
    }

    /** 与参照 TYPE_UNKNOWN 对应：以上类型全不命中。 */
    fun isUnknown(f: GroupAdminMessageFeatures): Boolean = !(isTextLike(f) || isQuote(f) || isImage(f) ||
        isVideo(f) || isVoice(f) || isLink(f) || isFile(f) || isNote(f) || isMusic(f) || isMini(f) ||
        isCard(f) || isEmoji(f) || isRedPacket(f) || isTransfer(f) || isVoip(f) || isLocation(f) ||
        isSystem(f) || isPat(f) || isVideoNumber(f))

    /** 参照 TYPE_APP（appmsg）判定，供「未知类型」与调试用。 */
    fun isAppMsg(f: GroupAdminMessageFeatures): Boolean = isApp(f)
}
