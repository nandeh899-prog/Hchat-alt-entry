package com.aeonlab.toolkit.hooks.items.groupadmin

/**
 * 规则目录（规格 5.1 `GroupAdminRuleCatalog`）：24 条规则的元数据、判定顺序与默认值。
 *
 * 默认值的取证来源（规格第 5 步）：
 * - 规则名与开关形态：按规格第 5 步给出的规则表逐条落地（命名与开关形态沿用该表，不引用任何外部实现）。
 * - 默认文案 / 默认阈值 / 默认拉黑时长：本模块早期真机测试群的实际配置导出中那个
 *   **未被改过的群**（测试群）的取值，与规格 2.5、4.2 记录的三条已知默认（atmany「禁止@多人/@全体成员」、
 *   long「消息太长了，请分条发送~」、repeat「一直重复累不累？休息一下吧！」）逐字一致。
 * - 参数键名：本模块自己的参数键（`at_limit` / `long_limit` / `repeat_max` /
 *   `fakeowner_grace` / `pat_limit` / `pat_window` / `mute_cd` / `keyword_list` / `at_all`）。
 *
 * 判定顺序是规格 2.2 的原文顺序，**不要**按本文件的书写顺序重排。
 */
object GroupAdminRuleCatalog {

    const val DEFAULT_WARN_TEXT = "不允许此类消息"

    /** 逐条规则的自定义参数（文本/整数/布尔），面板据此渲染，引擎据此取值。 */
    data class ParamSpec(
        val key: String,
        val title: String,
        val unit: String,
        val default: Int = 0,
        val min: Int = 1,
        val max: Int = 100_000,
        val isText: Boolean = false,
        val defaultText: String = "",
        val isBool: Boolean = false,
        val defaultBool: Boolean = false,
        val summary: String = ""
    ) {
        val isNumber: Boolean get() = !isText && !isBool
    }

    /** 规则触发路径：消息判定链 / 拍一拍 / 系统消息 / 群成员事件。 */
    enum class Kind { CHAIN, PAT, SYSTEM, MEMBER }

    data class RuleSpec(
        val id: String,
        val title: String,
        val defaultReason: String,
        val defaultUnbanHours: Int = 0,
        val defaultWarnCount: Int = 1,
        val defaultWarnText: String = DEFAULT_WARN_TEXT,
        val kind: Kind = Kind.CHAIN,
        val params: List<ParamSpec> = emptyList(),
        /** 依赖的消息类型（面板与文档展示，说明「什么消息会命中这条」）。 */
        val typeDep: String = "—",
        val note: String = ""
    )

    // ---- 参数键 ----
    const val PARAM_LONG_LIMIT = "long_limit"
    const val PARAM_AT_LIMIT = "at_limit"
    const val PARAM_AT_ALL = "at_all"
    const val PARAM_REPEAT_MAX = "repeat_max"
    const val PARAM_FAKE_GRACE = "fakeowner_grace"
    const val PARAM_PAT_LIMIT = "pat_limit"
    const val PARAM_PAT_WINDOW = "pat_window"
    const val PARAM_MUTE_CD = "mute_cd"
    const val PARAM_KEYWORD_LIST = "keyword_list"
    const val PARAM_NONADMIN_BOTH = "nonadmin_both"
    const val PARAM_AT_ALL_TEXT = "at_all_text"

    const val DEFAULT_LONG_LIMIT = 500
    const val DEFAULT_AT_LIMIT = 3
    const val DEFAULT_REPEAT_MAX = 5
    const val DEFAULT_FAKE_GRACE = 60
    const val DEFAULT_PAT_LIMIT = 2
    const val DEFAULT_PAT_WINDOW = 10
    const val DEFAULT_MUTE_CD = 60
    const val DEFAULT_AT_ALL_TEXT = "禁止@全体成员"

    /** 判定链顺序（规格 2.2 原文）：禁言最前，视频先于图片，二维码先于图片。 */
    val CHAIN_ORDER: List<String> = listOf(
        "mute", "fakeowner", "atmany", "keyword", "repeat", "long",
        "video", "qrcode", "image", "card", "mini", "article",
        "voice", "location", "emoji", "file", "music", "redpacket", "transfer", "note"
    )

    /** 消息判定链之外的 4 条：拍一拍 → 系统消息 → 群成员事件。 */
    val EVENT_ORDER: List<String> = listOf("pat", "rename", "nonadmin", "leavebl")

    /** 面板展示顺序 = 判定链 + 事件类。 */
    val DISPLAY_ORDER: List<String> = CHAIN_ORDER + EVENT_ORDER

    /** 24 条全部已接入（规格第 5 步）。保留该常量以兼容旧调用方。 */
    val IMPLEMENTED: List<String> = DISPLAY_ORDER

    const val LONG = "long"

    /** 5.5.16 及以前用过的默认文案：命中说明该规则从未被用户改过，升级时换成本轮的类型专属默认值。 */
    val LEGACY_REASONS: Map<String, List<String>> = mapOf(
        "mute" to listOf("本群已开启全员禁言，请勿发言"),
        "fakeowner" to listOf("请勿冒充群主"),
        "atmany" to listOf("禁止@多人/@全体成员"),
        "keyword" to listOf("触发违禁关键词"),
        "repeat" to listOf("一直重复累不累？休息一下吧！"),
        "long" to listOf("消息太长了，请分条发送~"),
        "video" to listOf("禁止发送视频"),
        "qrcode" to listOf("禁止发送二维码"),
        "image" to listOf("禁止发送图片"),
        "card" to listOf("禁止发送名片"),
        "mini" to listOf("禁止发送小程序"),
        "article" to listOf("禁止发送文章链接"),
        "voice" to listOf("禁止发送语音"),
        "location" to listOf("禁止发送位置"),
        "emoji" to listOf("禁止发送表情"),
        "file" to listOf("禁止发送文件"),
        "music" to listOf("禁止发送音乐"),
        "redpacket" to listOf("禁止发送红包"),
        "transfer" to listOf("禁止发送转账"),
        "note" to listOf("禁止发送接龙/笔记"),
        "pat" to listOf("禁止拍一拍"),
        "rename" to listOf("请勿擅自修改群聊名称"),
        "nonadmin" to listOf("禁止非管理员拉人"),
        "leavebl" to listOf("拜拜~")
    )

    /** 旧默认警告文案（全规则共用那条 + mute 专用那条）。 */
    val LEGACY_WARN_TEXTS: List<String> = listOf(DEFAULT_WARN_TEXT, "本群已开启全员禁言，请勿发言")

    /**
     * 升级兼容：只把「仍是旧默认值（或从未写过）」的文案换成本轮的类型专属默认值。
     * 用户手动改过的文案不等于任何旧默认值，因此原样保留。
     */
    fun migrateLegacyTexts(cfg: GroupConfig): Int {
        var changed = 0
        cfg.rules.forEach { (id, rc) ->
            val spec = BY_ID[id] ?: return@forEach
            if (rc.reason.isBlank() || LEGACY_REASONS[id]?.contains(rc.reason) == true) {
                if (rc.reason != spec.defaultReason) { rc.reason = spec.defaultReason; changed++ }
            }
            if (rc.warnText.isBlank() || LEGACY_WARN_TEXTS.contains(rc.warnText)) {
                if (rc.warnText != spec.defaultWarnText) { rc.warnText = spec.defaultWarnText; changed++ }
            }
        }
        return changed
    }

    val SPECS: List<RuleSpec> = listOf(
        RuleSpec(
            id = "mute", title = "全员禁言（非豁免成员发言警告）",
            defaultReason = "群内禁言中，禁止发言",
            defaultWarnText = "群内禁言中，禁止发言",
            defaultWarnCount = 3,
            params = listOf(
                ParamSpec(
                    PARAM_MUTE_CD, "同成员冷却", "秒", DEFAULT_MUTE_CD, 0, 86_400,
                    summary = "同一成员在此秒数内只处理一次（默认 60）"
                )
            ),
            typeDep = "任意消息",
            note = "判定链最前，命中即返回；软禁言（警告满次数后踢并拉黑），不是微信原生禁言"
        ),
        RuleSpec(
            id = "fakeowner", title = "自动移除冒充群主者",
            defaultReason = "请勿冒充群主",
            defaultWarnText = "请勿冒充群主",
            params = listOf(
                ParamSpec(
                    PARAM_FAKE_GRACE, "限时改回", "分钟", DEFAULT_FAKE_GRACE, 0, 1_440,
                    summary = "先发限时警告，超时仍冒充才踢；0 = 命中即踢"
                )
            ),
            kind = Kind.SYSTEM,
            typeDep = "群昵称 / 系统消息「改名为」",
            note = "两条路径：群昵称含「群主」或与群主昵称相同；系统消息里把名字改成含「群主」"
        ),
        RuleSpec(
            id = "atmany", title = "自动移除@多人者",
            defaultReason = "请勿一次@多人",
            defaultWarnText = "请勿一次@多人",
            params = listOf(
                ParamSpec(
                    PARAM_AT_ALL_TEXT, "@全体成员文案", "",
                    isText = true, defaultText = "禁止@全体成员",
                    summary = "@全体成员 / 疑似@全体命中时使用的文案（默认「禁止@全体成员」）"
                ),
                ParamSpec(PARAM_AT_LIMIT, "@人数阈值", "人", DEFAULT_AT_LIMIT, 1, 500, summary = "不含 @全体"),
                ParamSpec(
                    PARAM_AT_ALL, "@全体也移除", "", defaultBool = true, isBool = true,
                    summary = "开启后 @全体成员 的发送者也会被处理"
                )
            ),
            typeDep = "@列表 / @全体标记",
            note = "疑似@全体（@人数 ≥ 群人数−1 且群人数 ≥3）直接踢且无视警告"
        ),
        RuleSpec(
            id = "keyword", title = "自动移除发送关键词者",
            defaultReason = "触发违禁关键词",
            defaultWarnText = "触发违禁关键词",
            params = listOf(
                ParamSpec(
                    PARAM_KEYWORD_LIST, "关键词表", "", isText = true,
                    summary = "英文逗号 / 换行 / 分号分隔；命中任意一个即触发"
                )
            ),
            typeDep = "文本消息",
            note = "关键词表为空时本规则不会命中"
        ),
        RuleSpec(
            id = "repeat", title = "自动移除发送重复消息者",
            defaultReason = "请勿重复发送相同内容",
            defaultWarnText = "请勿重复发送相同内容",
            params = listOf(
                ParamSpec(PARAM_REPEAT_MAX, "重复次数阈值", "次", DEFAULT_REPEAT_MAX, 2, 100, summary = "默认 5，最小 2")
            ),
            typeDep = "文本消息",
            note = "同一发送者 10 分钟窗口内发送完全相同内容，次数达阈值触发；超窗口重新计数"
        ),
        RuleSpec(
            id = LONG, title = "自动移除发送超长消息者",
            defaultReason = "消息太长了，请分两条发送",
            defaultWarnText = "消息太长了，请分两条发送",
            params = listOf(
                ParamSpec(PARAM_LONG_LIMIT, "长度阈值", "字", DEFAULT_LONG_LIMIT, 1, 999_999, summary = "默认 500 字")
            ),
            typeDep = "文本消息",
            note = "纯文本消息才参与判定"
        ),
        RuleSpec(
            id = "video", title = "自动移除视频消息者",
            defaultReason = "不允许发送视频信息",
            defaultWarnText = "不允许发送视频信息",
            typeDep = "type 43 / 62 / 宿主 isVideo",
            note = "先于图片判定，避免视频被图片规则误吃"
        ),
        RuleSpec(
            id = "qrcode", title = "自动移除二维码消息者",
            defaultReason = "不允许发送二维码",
            defaultWarnText = "不允许发送二维码",
            typeDep = "图片消息且正文含 qrcode / 二维码",
            note = "先于图片判定；关闭本规则时图片仍按 image 规则处理"
        ),
        RuleSpec(
            id = "image", title = "自动移除图片消息者",
            defaultReason = "不允许发送图片信息",
            defaultWarnText = "不允许发送图片信息",
            typeDep = "type 3 / 宿主 isImage"
        ),
        RuleSpec(
            id = "card", title = "自动移除发送名片消息者",
            defaultReason = "不允许发送名片信息",
            defaultWarnText = "不允许发送名片信息",
            typeDep = "appmsg type 42 / 66 / 宿主 isShareCard"
        ),
        RuleSpec(
            id = "mini", title = "自动移除发送小程序消息者",
            defaultReason = "不允许发送小程序信息",
            defaultWarnText = "不允许发送小程序信息",
            typeDep = "appmsg type 33 / 36 或正文含 miniprogram"
        ),
        RuleSpec(
            id = "article", title = "自动移除发送文章消息者",
            defaultReason = "不允许发送链接信息",
            defaultWarnText = "不允许发送链接信息",
            typeDep = "appmsg type 5 / 宿主 isLink"
        ),
        RuleSpec(
            id = "voice", title = "自动移除语音消息者",
            defaultReason = "不允许发送语音信息",
            defaultWarnText = "不允许发送语音信息",
            typeDep = "type 34 / 宿主 isVoice"
        ),
        RuleSpec(
            id = "location", title = "自动移除位置消息者",
            defaultReason = "不允许发送位置信息",
            defaultWarnText = "不允许发送位置信息",
            typeDep = "type 48 / 宿主 isLocation"
        ),
        RuleSpec(
            id = "emoji", title = "自动移除表情消息者",
            defaultReason = "不允许发送表情信息",
            defaultWarnText = "不允许发送表情信息",
            typeDep = "type 47 / appmsg type 8 / 正文含 emoji、imgmd5、emoticonmd5"
        ),
        RuleSpec(
            id = "file", title = "自动移除文件消息者",
            defaultReason = "不允许发送文件信息",
            defaultWarnText = "不允许发送文件信息",
            typeDep = "appmsg type 6 / 74 或正文含 appattach + fileext"
        ),
        RuleSpec(
            id = "music", title = "自动移除音乐消息者",
            defaultReason = "不允许发送音乐信息",
            defaultWarnText = "不允许发送音乐信息",
            typeDep = "appmsg type 3 / 76、musicurl、dataurl，或 QQ音乐/网易云/酷狗/酷我域名"
        ),
        RuleSpec(
            id = "redpacket", title = "自动移除红包消息者",
            defaultReason = "不允许发送红包信息",
            defaultWarnText = "不允许发送红包信息",
            typeDep = "appmsg type 2001 / 宿主 isRedPacket"
        ),
        RuleSpec(
            id = "transfer", title = "自动移除转账消息者",
            defaultReason = "不允许发送转账信息",
            defaultWarnText = "不允许发送转账信息",
            typeDep = "appmsg type 2000 / 宿主 isTransfer"
        ),
        RuleSpec(
            id = "note", title = "自动移除接龙/笔记消息者",
            defaultReason = "不允许发送接龙信息",
            defaultWarnText = "不允许发送接龙信息",
            typeDep = "appmsg type 53 或正文含 solitaire / #接龙"
        ),
        RuleSpec(
            id = "pat", title = "自动移除拍一拍者（拍多人）",
            defaultReason = "请勿拍一拍",
            defaultWarnText = "请勿拍一拍",
            kind = Kind.PAT,
            params = listOf(
                ParamSpec(PARAM_PAT_LIMIT, "拍不同成员数", "人", DEFAULT_PAT_LIMIT, 1, 200, summary = "1 = 拍即触发"),
                ParamSpec(PARAM_PAT_WINDOW, "统计窗口", "分钟", DEFAULT_PAT_WINDOW, 1, 1_440)
            ),
            typeDep = "拍一拍消息（patMsg）",
            note = "同窗口内被拍的人重复拍只算一次；被拍者是黑名单成员则当场踢"
        ),
        RuleSpec(
            id = "rename", title = "自动移除擅自修改群聊名称者",
            defaultReason = "请勿随意修改群名",
            defaultWarnText = "请勿随意修改群名",
            kind = Kind.SYSTEM,
            typeDep = "系统消息（修改群名为…）",
            note = "直接踢，不走警告"
        ),
        RuleSpec(
            id = "nonadmin", title = "自动移除非管理员拉的成员",
            defaultReason = "仅群主或管理员可邀请成员",
            defaultWarnText = "仅群主或管理员可邀请成员",
            kind = Kind.MEMBER,
            params = listOf(
                ParamSpec(
                    PARAM_NONADMIN_BOTH, "邀请人一并移出", "", isBool = true,
                    summary = "开启后被邀请人与邀请人都踢出"
                )
            ),
            typeDep = "群成员入群事件 + 系统消息缓存（120 秒）",
            note = "邀请人非豁免且被邀请人也非豁免才处理"
        ),
        RuleSpec(
            id = "leavebl", title = "退群自动加入黑名单",
            defaultReason = "拜拜~",
            defaultWarnText = "拜拜~",
            kind = Kind.MEMBER,
            typeDep = "群成员退群事件",
            note = "已在黑名单则不覆盖原时限；本群总开关打开才生效"
        )
    )

    private val BY_ID: Map<String, RuleSpec> = SPECS.associateBy { it.id }

    fun spec(id: String): RuleSpec? = BY_ID[id]

    fun title(id: String): String = BY_ID[id]?.title ?: id

    fun defaultReason(id: String): String = BY_ID[id]?.defaultReason ?: "违反群规"

    fun paramSpec(id: String, key: String): ParamSpec? =
        BY_ID[id]?.params?.firstOrNull { it.key == key }

    /** 取本群该规则的配置；没写过就给一份带默认值的空配置（`on=false`）。 */
    fun ruleOf(cfg: GroupConfig, id: String): RuleConfig {
        val spec = BY_ID[id]
        return cfg.rules[id] ?: RuleConfig(
            on = false,
            reason = spec?.defaultReason.orEmpty(),
            unbanHours = spec?.defaultUnbanHours ?: 0,
            warnEnable = false,
            warnCount = spec?.defaultWarnCount ?: 1,
            warnText = spec?.defaultWarnText ?: DEFAULT_WARN_TEXT
        )
    }

    /** 面板编辑用：确保 `cfg.rules` 里有这一条，再返回可写实例。 */
    fun editable(cfg: GroupConfig, id: String): RuleConfig = cfg.rules.getOrPut(id) { ruleOf(cfg, id) }

    /** 数值参数：非法/越界回退默认值，并夹到 [min,max]。 */
    fun paramInt(cfg: GroupConfig, id: String, key: String, fallback: Int): Int {
        val spec = paramSpec(id, key)
        val raw = cfg.rules[id]?.params?.get(key)?.trim().orEmpty()
        val value = raw.toIntOrNull() ?: return clamp(fallback, spec)
        return clamp(value, spec)
    }

    /** 布尔参数：`1/true/on` 为真，`0/false/off` 为假，空则用默认。 */
    fun paramBool(cfg: GroupConfig, id: String, key: String, fallback: Boolean): Boolean {
        val raw = cfg.rules[id]?.params?.get(key)?.trim()?.lowercase().orEmpty()
        return when (raw) {
            "" -> fallback
            "1", "true", "on", "yes", "y" -> true
            "0", "false", "off", "no", "n" -> false
            else -> fallback
        }
    }

    /** 文本参数：空则不覆盖默认（多数默认就是空串，即「未配置」）。 */
    fun paramText(cfg: GroupConfig, id: String, key: String, fallback: String): String =
        cfg.rules[id]?.params?.get(key)?.takeIf { it.isNotBlank() } ?: fallback

    /** 关键词表：支持中英文逗号 / 换行 / 分号 / 顿号分隔。 */
    fun keywordList(cfg: GroupConfig): List<String> =
        paramText(cfg, "keyword", PARAM_KEYWORD_LIST, "")
            .split(',', '，', '\n', ';', '；', '、')
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    private fun clamp(value: Int, spec: ParamSpec?): Int {
        if (spec == null || spec.isText || spec.isBool) return value
        return if (value < spec.min) spec.min else if (value > spec.max) spec.max else value
    }
}
