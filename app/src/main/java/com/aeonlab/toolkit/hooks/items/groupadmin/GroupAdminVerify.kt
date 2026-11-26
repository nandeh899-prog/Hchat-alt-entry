package com.aeonlab.toolkit.hooks.items.groupadmin

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.aeonlab.toolkit.hooks.api.core.WeChatApis
import com.aeonlab.toolkit.utils.HLog
import org.json.JSONObject
import kotlin.random.Random

/**
 * 进群问答验证（规格 3.2 的语义照搬）。
 *
 * 生效条件：全局开关开 且 本群 enable 为真；与「自动移除总开关」互相独立。
 *
 * 运行状态四张表，键都是「群ID_wxid」：验证中、已答错次数、当前题目答案、本次会话开始时间。
 * 超时走双路：入群按 timeLimit 起一个定时器，同时每次群消息与每次入群都做一遍兜底扫描；
 * 两条路径都校验会话开始时间，避免退群重进之后被旧定时器误杀。
 */
object GroupAdminVerify {

    private const val TAG = "[AeonLab:GroupAdminVerify]"

    /** 默认题库：与原实现 gvDefaultCfg 一致（四条），可在面板里改写。 */
    const val DEFAULT_QUESTION_LIST = "1+1=?,2;今天周几?,周四;2+3=?,5;5x4=?,20"
    /** 题库整段为空时的原始兜底串（原实现 gvRandomQa 的 raw 兜底）。 */
    const val RAW_FALLBACK_QUESTION_LIST = "1+1=?,2;今天周几?,周四"
    /** 没有任何可用条目时的兜底题目（原实现 qaList.isEmpty() 分支）。 */
    const val FALLBACK_QUESTION = "1+1=?"
    const val FALLBACK_ANSWER = "2"
    const val DEFAULT_MAX_ERR = 3
    const val DEFAULT_TIME_LIMIT = 0
    /** 移出提示前缀与失败提示（原实现 gvKick 的两条文案，措辞按本模块中性口径）。 */
    const val KICK_PREFIX = "【入群验证】"
    const val FAIL_NOTICE = "踢人失败，请把运行模块的账号设为群管理员！错误："

    const val DEFAULT_TEXT_WELCOME =
        "欢迎新人「%name%」\n请回答验证题：\n%ques%\n连续答错%maxerr%次将被移出\n%limit%"
    const val DEFAULT_TEXT_WRONG = "答案错误(%now%/%max%)\n新题目：%ques%"
    const val DEFAULT_TEXT_PASS = "答案正确！验证通过，欢迎交流~"
    const val DEFAULT_TEXT_KICK = "连续答错%max%次，已移出群聊"
    const val DEFAULT_TEXT_TIMEOUT = "超过%sec%秒未完成验证，已移出群聊"

    /** 广告词：命中直接移出，不进入答题。纯 ASCII 词按词边界匹配，避免误配。 */
    private val AD_WORDS = listOf("http", "https", "二维码", "vx", "加我", "兼职", "刷单")

    data class VerifyConfig(
        var enable: Boolean = false,
        var questionList: String = DEFAULT_QUESTION_LIST,
        var maxErr: Int = DEFAULT_MAX_ERR,
        var timeLimit: Int = DEFAULT_TIME_LIMIT,
        var textWelcome: String = DEFAULT_TEXT_WELCOME,
        var textWrong: String = DEFAULT_TEXT_WRONG,
        var textPass: String = DEFAULT_TEXT_PASS,
        var textKick: String = DEFAULT_TEXT_KICK,
        var textTimeout: String = DEFAULT_TEXT_TIMEOUT
    )

    private val verifying = HashMap<String, Boolean>()
    private val errCache = HashMap<String, Int>()
    private val ansCache = HashMap<String, String>()
    private val joinTs = HashMap<String, Long>()
    private val timers = HashMap<String, Runnable>()
    private val handler = Handler(Looper.getMainLooper())

    private fun key(room: String, wxId: String) = room + "_" + wxId

    // ---------- 配置 ----------

    /** 全局开关开 且 本群验证开关开。 */
    fun isOpen(context: Context?, room: String): Boolean =
        GroupAdminSettings.isEnabled(context) && GroupAdminStore.verifyEnabled(context, room)

    /**
     * 按群读配置。本群还没有键时把默认值写回一次（与原实现「读一次就落盘」一致）。
     * 字段名保持 enable/questionList/maxErr/timeLimit/text* 不变。
     */
    fun config(context: Context?, room: String): VerifyConfig {
        if (context == null || room.isBlank()) return VerifyConfig()
        val raw = runCatching {
            GroupAdminSettings.preferences(context).getString(GroupAdminSettings.KEY_VERIFY, "")
        }.getOrNull().orEmpty()
        val root = parse(raw)
        val obj = runCatching { root.optJSONObject(room) }.getOrNull()
        if (obj == null) {
            val fresh = VerifyConfig()
            write(context, room, fresh)
            return fresh
        }
        val fallback = VerifyConfig()
        return VerifyConfig(
            enable = obj.optBoolean("enable", false),
            questionList = obj.optString("questionList", DEFAULT_QUESTION_LIST)
                .ifBlank { DEFAULT_QUESTION_LIST },
            maxErr = obj.optInt("maxErr", DEFAULT_MAX_ERR),
            timeLimit = obj.optInt("timeLimit", DEFAULT_TIME_LIMIT),
            textWelcome = obj.optString("textWelcome", DEFAULT_TEXT_WELCOME)
                .ifBlank { fallback.textWelcome },
            textWrong = obj.optString("textWrong", DEFAULT_TEXT_WRONG).ifBlank { fallback.textWrong },
            textPass = obj.optString("textPass", DEFAULT_TEXT_PASS).ifBlank { fallback.textPass },
            textKick = obj.optString("textKick", DEFAULT_TEXT_KICK).ifBlank { fallback.textKick },
            textTimeout = obj.optString("textTimeout", DEFAULT_TEXT_TIMEOUT)
                .ifBlank { fallback.textTimeout }
        )
    }

    fun saveConfig(context: Context?, room: String, cfg: VerifyConfig) {
        if (context == null || room.isBlank()) return
        write(context, room, cfg)
    }

    private fun parse(raw: String): JSONObject =
        runCatching { if (raw.isBlank()) JSONObject() else JSONObject(raw) }.getOrDefault(JSONObject())

    private fun write(context: Context, room: String, cfg: VerifyConfig) {
        runCatching {
            val prefs = GroupAdminSettings.preferences(context)
            val root = parse(prefs.getString(GroupAdminSettings.KEY_VERIFY, "").orEmpty())
            val obj = JSONObject()
            obj.put("enable", cfg.enable)
            obj.put("questionList", cfg.questionList)
            obj.put("maxErr", cfg.maxErr)
            obj.put("timeLimit", cfg.timeLimit)
            obj.put("textWelcome", cfg.textWelcome)
            obj.put("textWrong", cfg.textWrong)
            obj.put("textPass", cfg.textPass)
            obj.put("textKick", cfg.textKick)
            obj.put("textTimeout", cfg.textTimeout)
            root.put(room, obj)
            prefs.edit().putString(GroupAdminSettings.KEY_VERIFY, root.toString()).apply()
        }
    }

    // ---------- 题库与判定（纯函数，便于核对） ----------

    /**
     * 题库解析：「问题,答案;问题,答案」。
     * 与原实现一致：按分号切分、逐段 trim、丢掉空段；每条取第一个逗号前为问题、
     * 第一个逗号到第二个逗号之间为答案；没有逗号的条目照样保留，答案为空串
     * （原实现同样保留，结果是这道题永远答不对，按答错累计到上限移出）。
     */
    fun questions(cfg: VerifyConfig): List<Pair<String, String>> {
        val raw = cfg.questionList.ifBlank { RAW_FALLBACK_QUESTION_LIST }
        val out = ArrayList<Pair<String, String>>()
        for (part in raw.split(";")) {
            val seg = foldWidth(stripInvisible(part)).trim()
            if (seg.isEmpty()) continue
            val idx = seg.indexOf(',')
            val q = if (idx < 0) seg else seg.substring(0, idx).trim()
            val a = if (idx < 0) "" else seg.substring(idx + 1).trim()
            out.add(q to a)
        }
        return out
    }

    /** 抽一题：没有可用条目时用原实现的兜底题目「1+1=? / 2」。 */
    private fun pick(cfg: VerifyConfig): Pair<String, String> {
        val list = questions(cfg)
        if (list.isEmpty()) return FALLBACK_QUESTION to FALLBACK_ANSWER
        return list[Random.nextInt(list.size)]
    }

    /**
     * 答案比对，照原实现 gvAnswerOk：
     * 归一化（去全部空白、剥首尾标点）后先整条忽略大小写比较；
     * 标准答案整体是 ASCII 数字时，在答句里找一段与它逐字符相同的数字串（「12」不误配「2」）；
     * 其它情况允许包含，但标准答案至少两个字（单字答案只能整条相等）。
     */
    fun answerOk(input: String, expected: String): Boolean {
        val r = normAnswer(expected)
        if (r.isEmpty()) return false
        val c = normAnswer(input)
        if (c.isEmpty()) return false
        if (c.equals(r, ignoreCase = true)) return true
        if (isAsciiDigits(r)) {
            var i = 0
            while (i < c.length) {
                if (c[i] in '0'..'9') {
                    var j = i
                    while (j < c.length && c[j] in '0'..'9') j++
                    if (c.substring(i, j) == r) return true
                    i = j
                } else {
                    i++
                }
            }
            return false
        }
        return r.length >= 2 && c.lowercase().contains(r.lowercase())
    }

    /** 归一化：去全部空白，再剥掉首尾的标点（标点集照原实现 gvIsAnswerPunct）。 */
    private fun normAnswer(s: String): String {
        val src = foldWidth(stripInvisible(s))
        val sb = StringBuilder(src.length)
        for (ch in src) if (!ch.isWhitespace()) sb.append(ch)
        var t = sb.toString().trim()
        while (t.isNotEmpty() && isAnswerPunct(t.first())) t = t.substring(1)
        while (t.isNotEmpty() && isAnswerPunct(t.last())) t = t.substring(0, t.length - 1)
        return t
    }

    private fun isAnswerPunct(c: Char): Boolean =
        c == '。' || c == '，' || c == ',' || c == '.' || c == '!' || c == '！' ||
            c == '?' || c == '？' || c == '、' || c == '~' || c == '：' || c == ':'

    private fun isAsciiDigits(s: String): Boolean =
        s.isNotEmpty() && s.all { it in '0'..'9' }

    /** 不可见字符（零宽、BOM、方向标记、控制符）不可能是答案的一部分，比对前统一去掉。 */
    private fun stripInvisible(s: String): String {
        if (s.isEmpty()) return s
        val sb = StringBuilder(s.length)
        for (ch in s) {
            val t = Character.getType(ch)
            if (t == Character.FORMAT.toInt() || t == Character.CONTROL.toInt()) continue
            sb.append(ch)
        }
        return sb.toString()
    }

    /** 全角转半角（FF01–FF5E 与全角空格）：输入法全角输入不再导致答案比对不上。 */
    private fun foldWidth(s: String): String {
        if (s.isEmpty()) return s
        val sb = StringBuilder(s.length)
        for (ch in s) {
            sb.append(
                when {
                    ch.code == 0x3000 -> ' '
                    ch.code in 0xFF01..0xFF5E -> (ch.code - 0xFEE0).toChar()
                    else -> ch
                }
            )
        }
        return sb.toString()
    }

    /** 日志可读化：控制/格式字符与全角字符写成转义，便于从日志直接看出答案里的隐形或全角字符。 */
    private fun visible(s: String): String {
        val sb = StringBuilder(s.length + 8)
        for (ch in s) {
            val t = Character.getType(ch)
            val esc = "\\u%04X".format(ch.code)
            when {
                ch == '\n' -> sb.append("\\n")
                ch == '\r' -> sb.append("\\r")
                ch == '\t' -> sb.append("\\t")
                t == Character.FORMAT.toInt() || t == Character.CONTROL.toInt() -> sb.append(esc)
                ch.code in 0xFF01..0xFF5E || ch.code == 0x3000 -> sb.append(esc)
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    /** 本群是否有人正在验证（供消息链路判断「这条消息没被验证步消费」）。 */
    fun hasActive(room: String): Boolean {
        val prefix = room + "_"
        for (k in verifying.keys) if (k.startsWith(prefix) && verifying[k] == true) return true
        return false
    }

    /** 广告词命中：纯 ASCII 关键词按词边界，含其它字符的关键词按包含（照原实现 gvAdHit）。 */
    fun adHit(text: String): Boolean {
        if (text.isEmpty()) return false
        val lower = text.lowercase()
        for (w in AD_WORDS) {
            val lw = w.lowercase()
            val ascii = lw.all { (it in 'a'..'z') || (it in '0'..'9') }
            if (!ascii) {
                if (lower.contains(lw)) return true
                continue
            }
            var from = 0
            while (true) {
                val p = lower.indexOf(lw, from)
                if (p < 0) break
                val end = p + lw.length
                val before = if (p > 0) lower[p - 1] else ' '
                val after = if (end < lower.length) lower[end] else ' '
                val lb = (before in 'a'..'z') || (before in '0'..'9')
                val la = (after in 'a'..'z') || (after in '0'..'9')
                if (!lb && !la) return true
                from = p + 1
            }
        }
        return false
    }

    /** 占位符替换：只替换，不清理没被替换掉的占位符（照原实现）。 */
    private fun fill(text: String, values: Map<String, String>): String {
        var out = text
        for ((k, v) in values) out = out.replace("%" + k + "%", v)
        return out
    }

    // ---------- 入群 ----------

    fun onJoin(context: Context?, room: String, wxId: String, name: String) {
        if (context == null || room.isBlank() || wxId.isBlank()) return
        sweepTimeouts(context, room)
        if (!isOpen(context, room)) return
        val cfg = config(context, room)
        val qa = pick(cfg)
        val k = key(room, wxId)
        // 原名缺失时用「新成员」，与原实现一致（不再退化成 wxid）。
        val label = GroupAdminMemberCache.nameNow(room, wxId).ifBlank { name }.ifBlank { "新成员" }
        clearTimer(k)
        verifying[k] = true
        errCache[k] = 0
        ansCache[k] = qa.second
        joinTs[k] = System.currentTimeMillis()
        val limit = if (cfg.timeLimit > 0) "请在 " + cfg.timeLimit + " 秒内完成验证，超时将移出" else ""
        sendAtText(
            room, wxId,
            fill(
                cfg.textWelcome,
                mapOf(
                    "name" to label,
                    "ques" to qa.first,
                    "maxerr" to cfg.maxErr.toString(),
                    "limit" to limit
                )
            )
        )
        HLog.i(
            TAG + " 进群验证：出题 · " + label + "（" + wxId + "）· 群 " + room +
                " · 限时=" + cfg.timeLimit + "s · 答错上限=" + cfg.maxErr + " · 题=" + qa.first +
                " · 标准=" + visible(qa.second) + " · 题库=" + visible(cfg.questionList) +
                "（" + questions(cfg).size + " 题）"
        )
        GroupAdminMessageRuntime.note("进群验证出题 " + label)
        scheduleTimeout(context, room, wxId, cfg)
    }

    /**
     * 答题入口。返回 true = 消息已被验证流程消费（不再进入自动规则）。
     * 非文本消息与命中广告词直接移出。
     */
    fun onMessage(
        context: Context?,
        room: String,
        sender: String,
        text: String,
        isTextMsg: Boolean
    ): Boolean {
        if (context == null || room.isBlank()) return false
        sweepTimeouts(context, room)
        if (!GroupAdminSettings.isEnabled(context)) return false
        if (sender.isBlank() || sender.contains("@chatroom")) return false
        val k = key(room, sender)
        if (!isOpen(context, room)) {
            // 会话中途被关掉时，原实现会丢弃该成员的状态；这里同样清掉，避免开关再打开后旧会话复活。
            if (verifying[k] == true) clear(room, sender)
            return false
        }
        if (verifying[k] != true) return false
        val cfg = config(context, room)
        val maxTotal = if (cfg.maxErr < 1) 1 else cfg.maxErr
        val label = GroupAdminMemberCache.nameNow(room, sender).ifBlank { sender }
        val body = text.trim()
        val kickText = fill(cfg.textKick, mapOf("max" to maxTotal.toString()))

        if (!isTextMsg) {
            HLog.i(TAG + " 进群验证：非文本消息，直接移出 · " + label + "（" + sender + "）· 群 " + room)
            kickOut(context, room, sender, label, kickText, "非文本消息")
            return true
        }
        if (adHit(body)) {
            HLog.i(
                TAG + " 进群验证：命中广告词，直接移出 · " + label + "（" + sender + "）· 群 " + room +
                    " · 正文=" + body
            )
            kickOut(context, room, sender, label, kickText, "广告词")
            return true
        }
        val expected = ansCache[k].orEmpty()
        val pass = body.isNotEmpty() && answerOk(body, expected)
        HLog.i(
            TAG + " 进群验证：判定 · " + label + "（" + sender + "）· 群 " + room +
                " · 收到=" + visible(body) + " · 标准=" + visible(expected) +
                " · 结果=" + if (pass) "通过" else "不通过"
        )
        if (pass) {
            clear(room, sender)
            sendAtText(room, sender, cfg.textPass)
            HLog.i(TAG + " 进群验证：答对通过 · " + label + "（" + sender + "）· 群 " + room)
            GroupAdminMessageRuntime.note("进群验证通过 " + label)
            return true
        }
        val err = (errCache[k] ?: 0) + 1
        errCache[k] = err
        if (err >= maxTotal) {
            HLog.i(
                TAG + " 进群验证：答错到上限，移出 · " + label + "（" + sender + "）· 群 " + room +
                    " · " + err + "/" + maxTotal
            )
            kickOut(context, room, sender, label, kickText, "答错 " + err + "/" + maxTotal)
            return true
        }
        val next = pick(cfg)
        ansCache[k] = next.second
        sendAtText(
            room, sender,
            fill(
                cfg.textWrong,
                mapOf(
                    "now" to err.toString(),
                    "max" to maxTotal.toString(),
                    "ques" to next.first
                )
            )
        )
        HLog.i(
            TAG + " 进群验证：答错换题 · " + label + "（" + sender + "）· 群 " + room +
                " · " + err + "/" + maxTotal + " · 新题=" + next.first +
                " · 标准=" + visible(next.second)
        )
        return true
    }

    // ---------- 超时（双路） ----------

    /** 兜底扫描：每次群消息与每次入群都跑一次。 */
    fun sweepTimeouts(context: Context?, room: String, now: Long = System.currentTimeMillis()) {
        if (context == null || room.isBlank()) return
        if (!isOpen(context, room)) return
        val cfg = config(context, room)
        val limit = cfg.timeLimit
        if (limit <= 0) return
        val prefix = room + "_"
        val keys = joinTs.keys.filter { it.startsWith(prefix) && (joinTs[it] ?: 0L) > 0L }
        for (k in keys) {
            val ts = joinTs[k] ?: continue
            if (now - ts < limit * 1000L) continue
            val wxId = k.substring(prefix.length)
            val label = GroupAdminMemberCache.nameNow(room, wxId).ifBlank { wxId }
            HLog.i(
                TAG + " 进群验证：超时移出 · " + label + "（" + wxId + "）· 群 " + room +
                    " · 已过 " + ((now - ts) / 1000) + "s / 限 " + limit + "s"
            )
            clear(room, wxId)
            kickOut(
                context, room, wxId, label,
                fill(
                    cfg.textTimeout,
                    mapOf("sec" to limit.toString(), "min" to (limit / 60).toString())
                ),
                "超时未完成"
            )
        }
    }

    private fun scheduleTimeout(context: Context?, room: String, wxId: String, cfg: VerifyConfig) {
        val limitSec = cfg.timeLimit
        if (limitSec <= 0) return
        val k = key(room, wxId)
        val ts = joinTs[k] ?: return
        val task = Runnable {
            runCatching {
                // 会话校验照原实现：不是同一次会话（退群重进或已完成）就忽略，避免旧定时器误踢。
                val sameSession = joinTs[k] == ts
                val stillVerifying = verifying[k] == true
                if (sameSession && stillVerifying) {
                    if (!isOpen(context, room)) {
                        clear(room, wxId)
                    } else {
                        val c = config(context, room)
                        val limit = c.timeLimit
                        clear(room, wxId)
                        val label = GroupAdminMemberCache.nameNow(room, wxId).ifBlank { wxId }
                        HLog.i(
                            TAG + " 进群验证：定时器到期 · " + label + "（" + wxId + "）· 群 " + room +
                                " · 限 " + limit + "s"
                        )
                        kickOut(
                            context, room, wxId, label,
                            fill(
                                c.textTimeout,
                                mapOf("sec" to limit.toString(), "min" to (limit / 60).toString())
                            ),
                            "超时未完成"
                        )
                    }
                }
            }
        }
        timers[k] = task
        runCatching { handler.postDelayed(task, limitSec * 1000L) }
    }

    // ---------- 内部 ----------

    private fun clear(room: String, wxId: String) {
        val k = key(room, wxId)
        verifying.remove(k)
        errCache.remove(k)
        ansCache.remove(k)
        joinTs.remove(k)
        clearTimer(k)
    }

    private fun clearTimer(k: String) {
        timers.remove(k)?.let { runCatching { handler.removeCallbacks(it) } }
    }

    /** 带 @ 的验证消息（欢迎、答错换题、验证通过），与原实现的三处一致。 */
    private fun sendAtText(room: String, wxId: String, text: String): Boolean {
        if (text.isEmpty()) return false
        return GroupAdminActions.sendAt(room, wxId, text)
    }

    /** 不带 @ 的群消息（移出提示与踢人失败提示），与原实现 gvKick 一致。 */
    private fun sendPlain(room: String, text: String): Boolean {
        if (text.isEmpty()) return false
        return GroupAdminActions.send(room, text)
    }

    /**
     * 移出：照原实现 gvKick —— 先移出，再在群里发「【入群验证】+ 文案」（不带 @）；
     * 移出抛错时改发失败提示。验证移出不写黑名单。
     */
    private fun kickOut(
        context: Context?,
        room: String,
        wxId: String,
        label: String,
        text: String,
        reason: String
    ) {
        var kicked = false
        var failure = ""
        try {
            kicked = WeChatApis.contact().chatrooms()?.delChatroomMember(room, wxId) == true
        } catch (e: Throwable) {
            failure = e.toString()
        }
        if (failure.isEmpty()) {
            sendPlain(room, KICK_PREFIX + text)
        } else {
            sendPlain(room, KICK_PREFIX + FAIL_NOTICE + failure)
        }
        clear(room, wxId)
        GroupAdminMemberCache.invalidate(room)
        HLog.i(
            TAG + " 进群验证移出：" + label + "（" + wxId + "）· 原因=" + reason +
                " · 移出=" + kicked + " · 群 " + room +
                (if (failure.isEmpty()) "" else " · 异常=" + failure)
        )
        GroupAdminMessageRuntime.note("进群验证移出 " + label + "（" + reason + "）")
    }

    /** 成员离开：清掉该成员的验证状态（照原实现 gvOnLeave）。 */
    fun onLeave(room: String, wxId: String) {
        if (room.isBlank() || wxId.isBlank()) return
        clear(room, wxId)
    }
}
