package com.aeonlab.toolkit.hooks.items.groupadmin

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 四类名单统一结构。`id` 是唯一匹配依据；`name` 是展示名（面板显示「昵称（wxid）」，
 * 随成员缓存回填）；`id` 为空表示「未解析」（只存了昵称，待 resolvePending 重试）；
 * `expireAt` 仅黑名单用，0 = 永久。
 */
data class MemberRef(
    val id: String = "",
    var name: String = "",
    var expireAt: Long = 0L
)

/** 单条规则的通用参数：开关、踢人/拉黑理由、拉黑时长与警告设置。 */
data class RuleConfig(
    var on: Boolean = false,
    var reason: String = "",
    var unbanHours: Int = 0,
    var warnEnable: Boolean = false,
    var warnCount: Int = 1,
    var warnText: String = "不允许此类消息",
    /** 规则自己的额外参数（规格 2.5；例如超长规则的长度阈值参数 long_limit）。 */
    val params: MutableMap<String, String> = mutableMapOf()
)

/** 单个群的配置（对齐规格 4.3 的 GroupConfig）。 */
data class GroupConfig(
    val room: String = "",
    var master: Boolean = false,
    var notify: Boolean = true,
    var wlMaster: Boolean = true,
    var farewell: String = GroupAdminSettings.DEFAULT_FAREWELL,
    var allowAdminCmd: Boolean = true,
    var allowAgentCmd: Boolean = true,
    var agentManageAdmin: Boolean = true,
    var nonadminBoth: Boolean = false,
    /** 套用的模板 id（空 = 单独设置）；模板内容在套用时快照进 rules，运行期不依赖模板。 */
    var templateId: String = "",
    val rules: MutableMap<String, RuleConfig> = mutableMapOf(),
    val admins: MutableList<MemberRef> = mutableListOf(),
    val agents: MutableList<MemberRef> = mutableListOf(),
    val white: MutableList<MemberRef> = mutableListOf(),
    val black: MutableList<MemberRef> = mutableListOf()
)

enum class GroupAdminList(val title: String, val hint: String) {
    ADMIN("管理员名单", "名单内成员被视为管理员（豁免规则，且可执行管理动作）"),
    AGENT("代理群主名单", "本模块自定义名单；微信角色里没有这一档，排在管理员之后豁免"),
    WHITE("白名单", "白名单成员豁免规则，是否生效还受本群「白名单豁免」开关控制"),
    BLACK("黑名单", "命中即被拦黑；拉黑时长到期后自动失效，0 小时为永久")
}

data class GroupEntry(val room: String, val name: String)

/**
 * 按群配置与四类名单的唯一读写入口。
 *
 * 三条铁律（规格 3.1）：名单匹配**只按 id**；昵称只参与录入解析、搜索与展示；
 * 白名单与黑名单互斥（互相写入时移除对方）。
 */
object GroupAdminStore {

    // ---------- 读写 ----------

    fun group(context: Context?, room: String): GroupConfig {
        if (context == null || room.isBlank()) return GroupConfig(room)
        val raw = runCatching {
            GroupAdminSettings.preferences(context).getString(GroupAdminSettings.KEY_CONFIGS, "")
        }.getOrNull().orEmpty()
        if (raw.isBlank()) return GroupConfig(room)
        return runCatching {
            val root = JSONObject(raw)
            if (!root.has(room)) return@runCatching GroupConfig(room)
            val cfg = fromJson(room, root.getJSONObject(room))
            // 5.5.17：把仍是旧默认值的文案换成本轮的类型专属默认值（用户改过的原样保留），并立刻落盘
            if (GroupAdminRuleCatalog.migrateLegacyTexts(cfg) > 0) save(context, cfg)
            cfg
        }.getOrDefault(GroupConfig(room))
    }

    fun update(context: Context?, room: String, block: (GroupConfig) -> Unit) {
        val cfg = group(context, room)
        block(cfg)
        save(context, cfg)
    }

    fun save(context: Context?, cfg: GroupConfig) {
        if (context == null || cfg.room.isBlank()) return
        val prefs = GroupAdminSettings.preferences(context)
        val raw = runCatching { prefs.getString(GroupAdminSettings.KEY_CONFIGS, "") }.getOrNull().orEmpty()
        val root = runCatching { if (raw.isBlank()) JSONObject() else JSONObject(raw) }
            .getOrDefault(JSONObject())
        runCatching {
            root.put(cfg.room, toJson(cfg))
            prefs.edit().putString(GroupAdminSettings.KEY_CONFIGS, root.toString()).apply()
        }
    }

    // ---------- 已纳管群 ----------

    fun entries(context: Context?): List<GroupEntry> {
        if (context == null) return emptyList()
        val raw = runCatching {
            GroupAdminSettings.preferences(context).getString(GroupAdminSettings.KEY_GROUPS, "")
        }.getOrNull().orEmpty()
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val room = o.optString("room", "")
                if (room.isBlank()) null else GroupEntry(room, o.optString("name", ""))
            }
        }.getOrDefault(emptyList())
    }

    fun rememberGroup(context: Context?, room: String, name: String) {
        if (context == null || room.isBlank()) return
        val list = entries(context).toMutableList()
        val index = list.indexOfFirst { it.room == room }
        if (index >= 0) {
            if (name.isNotBlank()) list[index] = GroupEntry(room, name)
        } else {
            list.add(GroupEntry(room, name))
        }
        persistGroups(context, list)
    }

    fun forgetGroup(context: Context?, room: String) {
        if (context == null || room.isBlank()) return
        persistGroups(context, entries(context).filter { it.room != room })
    }

    private fun persistGroups(context: Context, list: List<GroupEntry>) {
        val arr = JSONArray()
        for (entry in list) {
            val o = JSONObject()
            o.put("room", entry.room)
            o.put("name", entry.name)
            arr.put(o)
        }
        runCatching {
            GroupAdminSettings.preferences(context).edit()
                .putString(GroupAdminSettings.KEY_GROUPS, arr.toString()).apply()
        }
    }

    // ---------- 纳管与开关 ----------

    fun isManaged(context: Context?, room: String): Boolean {
        if (room.isBlank()) return false
        if (group(context, room).master) return true
        return verifyEnabled(context, room)
    }

    /** 进群验证开关（第 8 步实现写入口，这里只读，取不到即 false）。 */
    fun verifyEnabled(context: Context?, room: String): Boolean {
        if (context == null || room.isBlank()) return false
        val raw = runCatching {
            GroupAdminSettings.preferences(context).getString(GroupAdminSettings.KEY_VERIFY, "")
        }.getOrNull().orEmpty()
        if (raw.isBlank()) return false
        return runCatching {
            val root = JSONObject(raw)
            root.has(room) && root.getJSONObject(room).optBoolean("enable", false)
        }.getOrDefault(false)
    }

    fun setMaster(context: Context?, room: String, value: Boolean) {
        update(context, room) { it.master = value }
    }

    // ---------- 名单 ----------

    fun listOf(cfg: GroupConfig, which: GroupAdminList): MutableList<MemberRef> = when (which) {
        GroupAdminList.ADMIN -> cfg.admins
        GroupAdminList.AGENT -> cfg.agents
        GroupAdminList.WHITE -> cfg.white
        GroupAdminList.BLACK -> cfg.black
    }

    private fun allLists(cfg: GroupConfig): List<MutableList<MemberRef>> =
        listOf(cfg.admins, cfg.agents, cfg.white, cfg.black)

    fun pendingCount(cfg: GroupConfig): Int = allLists(cfg).sumOf { list -> list.count { it.id.isBlank() } }

    fun search(list: List<MemberRef>, query: String): List<MemberRef> {
        val text = query.trim()
        if (text.isEmpty()) return list
        val lower = text.lowercase()
        return list.filter { ref ->
            ref.name.lowercase().contains(lower) || ref.id.lowercase().contains(lower)
        }
    }

    fun looksLikeId(text: String): Boolean {
        val t = text.trim()
        return t.startsWith("wxid_") || t.contains("@chatroom") || t.startsWith("gh_")
    }

    /**
     * 写入一条名单条目。输入可以是 wxid 或昵称：
     * - wxid 直接用；昵称在成员缓存里查**唯一**匹配转成 wxid；
     * - 查不到或重名多个 → id 置空、只留昵称（面板标「待确认」，结算时再试）。
     * 返回 null 表示已在名单里（幂等）。
     */
    fun addMember(cfg: GroupConfig, list: MutableList<MemberRef>, input: String, name: String = ""): MemberRef? {
        val text = input.trim()
        if (text.isBlank()) return null
        val dup = list.firstOrNull { it.id.isNotBlank() && it.id == text }
            ?: list.firstOrNull { it.id.isBlank() && it.name == text }
        if (dup != null) return null

        val resolved = if (looksLikeId(text)) null else GroupAdminMemberCache.resolve(cfg.room, text)
        val id = when {
            looksLikeId(text) -> text
            resolved != null -> resolved.id
            else -> ""
        }
        val display = when {
            name.isNotBlank() -> name
            resolved != null && resolved.name.isNotBlank() -> resolved.name
            else -> text
        }
        val ref = MemberRef(id = id, name = display)
        list.add(ref)
        if (list === cfg.white) cfg.black.removeAll { it.id.isNotBlank() && it.id == id && id.isNotBlank() }
        if (list === cfg.black) cfg.white.removeAll { it.id.isNotBlank() && it.id == id && id.isNotBlank() }
        return ref
    }

    /** 拉黑（黑名单专用）：hours<=0 为永久，重复写入只刷新时长。 */
    fun addBlack(cfg: GroupConfig, id: String, name: String, hours: Int, now: Long): MemberRef? {
        val target = id.trim()
        if (target.isBlank()) return null
        val expire = if (hours <= 0) 0L else now + hours * 3_600_000L
        val existing = cfg.black.firstOrNull { it.id == target }
        if (existing != null) {
            existing.expireAt = expire
            if (name.isNotBlank()) existing.name = name
            return existing
        }
        val ref = MemberRef(id = target, name = name.ifBlank { target }, expireAt = expire)
        cfg.black.add(ref)
        cfg.white.removeAll { it.id == target }
        return ref
    }

    fun removeMember(cfg: GroupConfig, list: MutableList<MemberRef>, ref: MemberRef): Boolean {
        val removed = list.removeAll { it.id == ref.id && it.name == ref.name } ||
            list.removeAll { ref.id.isNotBlank() && it.id == ref.id } ||
            list.removeAll { ref.id.isBlank() && it.name == ref.name }
        return removed
    }

    fun clearList(cfg: GroupConfig, list: MutableList<MemberRef>) {
        list.clear()
    }

    /**
     * 把未解析条目（id 为空）按昵称再试一次；只接受唯一匹配。
     * `MemberRef.id` 是匹配键且不可变，所以整条替换而不是改字段。
     */
    fun resolvePending(cfg: GroupConfig, members: List<MemberRef>): Int {
        var count = 0
        for (list in allLists(cfg)) {
            for (index in list.indices) {
                val ref = list[index]
                if (ref.id.isNotBlank()) continue
                if (ref.name.isBlank()) continue
                val hits = members.filter { it.name.isNotBlank() && it.name == ref.name }
                if (hits.size != 1) continue
                list[index] = MemberRef(
                    id = hits[0].id,
                    name = ref.name.ifBlank { hits[0].name },
                    expireAt = ref.expireAt
                )
                count++
            }
        }
        return count
    }

    /** 按 id 把 name 回填为当前群昵称；成员改名后名单显示自动更新，命中判定不受影响。 */
    fun refreshNames(cfg: GroupConfig, members: List<MemberRef>): Int {
        if (members.isEmpty()) return 0
        val byId = HashMap<String, String>()
        for (m in members) if (m.id.isNotBlank()) byId[m.id] = m.name
        var count = 0
        for (list in allLists(cfg)) {
            for (ref in list) {
                if (ref.id.isBlank()) continue
                val current = byId[ref.id]?.takeIf { it.isNotBlank() } ?: continue
                if (current != ref.name) {
                    ref.name = current
                    count++
                }
            }
        }
        return count
    }

    /** 清理到期的黑名单条目，并丢弃含 @chatroom 的脏条目（对齐 gmPruneBlackFor）。 */
    fun purgeExpired(cfg: GroupConfig, now: Long): Int {
        val before = cfg.black.size
        cfg.black.removeAll { ref ->
            (ref.expireAt in 1..now) || ref.id.contains("@chatroom")
        }
        return before - cfg.black.size
    }

    /** 结算：解析待确认 → 回填昵称 → 清理到期。返回 Triple(新解析, 回填, 清理)。 */
    fun settle(cfg: GroupConfig, members: List<MemberRef>, now: Long): Triple<Int, Int, Int> {
        val resolved = resolvePending(cfg, members)
        val renamed = refreshNames(cfg, members)
        val purged = purgeExpired(cfg, now)
        return Triple(resolved, renamed, purged)
    }

    // ---------- 判定 ----------

    /**
     * 豁免判定，顺序与 gmIsExempt 完全一致：
     * 本人 → 群主 → 管理员名单 → 代理名单 → 白名单（仅当 wlMaster 为真）。
     * 只按 id 匹配，昵称不参与。
     */
    fun isExempt(cfg: GroupConfig, wxId: String, self: String, owner: String = ""): Boolean =
        exemptBy(cfg, wxId, self, owner).isNotEmpty()

    /** 同上，返回命中的那一档名称（供只读报告显示依据）。 */
    fun exemptBy(cfg: GroupConfig, wxId: String, self: String, owner: String = ""): String {
        val id = wxId.trim()
        if (id.isBlank()) return ""
        if (self.isNotBlank() && id == self) return "本人"
        if (owner.isNotBlank() && id == owner) return "群主"
        if (cfg.admins.any { it.id.isNotBlank() && it.id == id }) return "管理员名单"
        if (cfg.agents.any { it.id.isNotBlank() && it.id == id }) return "代理群主名单"
        if (cfg.wlMaster && cfg.white.any { it.id.isNotBlank() && it.id == id }) return "白名单"
        return ""
    }

    /** 黑名单命中：只比 id；expireAt=0 永久，>now 在期内。 */
    fun blackHit(cfg: GroupConfig, wxId: String, now: Long): MemberRef? {
        val id = wxId.trim()
        if (id.isBlank()) return null
        return cfg.black.firstOrNull { it.id == id && (it.expireAt == 0L || it.expireAt > now) }
    }

    fun expiredBlackCount(cfg: GroupConfig, now: Long): Int =
        cfg.black.count { it.expireAt in 1..now }

    // ---------- 展示 ----------

    fun label(ref: MemberRef): String = when {
        ref.id.isBlank() -> ref.name.ifBlank { "未命名" } + "（待确认）"
        ref.name.isBlank() || ref.name == ref.id -> ref.id
        else -> ref.name + "（" + ref.id + "）"
    }

    fun blackTimeText(expireAt: Long, now: Long): String {
        if (expireAt <= 0L) return "永久"
        val left = expireAt - now
        if (left <= 0L) return "已到期"
        val minutes = left / 60_000L
        return when {
            minutes < 60L -> "剩余 " + maxOf(1L, minutes) + " 分钟"
            minutes < 60L * 24L -> "剩余 " + (minutes / 60L) + " 小时"
            else -> "剩余 " + (minutes / (60L * 24L)) + " 天"
        }
    }

    fun summaryOf(cfg: GroupConfig, which: GroupAdminList, now: Long): String {
        val list = listOf(cfg, which)
        val builder = StringBuilder()
        builder.append(list.size).append(" 人")
        val pending = list.count { it.id.isBlank() }
        if (pending > 0) builder.append("（").append(pending).append(" 条待确认）")
        if (which == GroupAdminList.BLACK) {
            val expired = list.count { it.expireAt in 1..now }
            if (expired > 0) builder.append(" · ").append(expired).append(" 条已到期")
        }
        return builder.toString()
    }

    // ---------- JSON ----------

    private fun toJsonArray(list: List<MemberRef>): JSONArray {
        val arr = JSONArray()
        for (ref in list) {
            val o = JSONObject()
            o.put("id", ref.id)
            o.put("name", ref.name)
            o.put("expireAt", ref.expireAt)
            arr.put(o)
        }
        return arr
    }

    // ================= 警告计数（规格 2.3 的 gmWarnInc / gmWarnReset） =================

    private fun warnKey(room: String, wxId: String, rule: String): String =
        if (room.isBlank() || wxId.isBlank() || rule.isBlank()) "" else room + "|" + wxId + "|" + rule

    private fun readWarn(context: Context): JSONObject {
        val raw = runCatching {
            GroupAdminSettings.preferences(context).getString(GroupAdminSettings.KEY_WARN, "")
        }.getOrNull().orEmpty()
        if (raw.isBlank()) return JSONObject()
        return runCatching { JSONObject(raw) }.getOrDefault(JSONObject())
    }

    /** 当前警告次数（房间 + 成员 + 规则）。 */
    fun warnCount(context: Context?, room: String, wxId: String, rule: String): Int {
        val key = warnKey(room, wxId, rule)
        if (context == null || key.isBlank()) return 0
        return runCatching { readWarn(context).optInt(key, 0) }.getOrDefault(0)
    }

    /** 计数 +1 并落盘，返回累加后的次数（移植 gmWarnInc）。 */
    fun warnInc(context: Context?, room: String, wxId: String, rule: String): Int {
        val key = warnKey(room, wxId, rule)
        if (context == null || key.isBlank()) return 1
        return runCatching {
            val root = readWarn(context)
            val next = root.optInt(key, 0) + 1
            root.put(key, next)
            GroupAdminSettings.preferences(context).edit()
                .putString(GroupAdminSettings.KEY_WARN, root.toString()).apply()
            next
        }.getOrDefault(1)
    }

    /** 归零（踢人后调用，移植 gmWarnReset）。 */
    fun warnReset(context: Context?, room: String, wxId: String, rule: String) {
        val key = warnKey(room, wxId, rule)
        if (context == null || key.isBlank()) return
        runCatching {
            val root = readWarn(context)
            root.remove(key)
            GroupAdminSettings.preferences(context).edit()
                .putString(GroupAdminSettings.KEY_WARN, root.toString()).apply()
        }
    }

    /** 一条警告计数记录（面板「警告次数管理」用）。 */
    data class WarnEntry(val room: String, val wxId: String, val rule: String, val count: Int)

    /** 列出警告计数：room 为空则列全部群。key 形如 `room|wxId|rule`。 */
    fun warnEntries(context: Context?, room: String = ""): List<WarnEntry> {
        if (context == null) return emptyList()
        val root = readWarn(context)
        val out = ArrayList<WarnEntry>()
        val it = root.keys()
        while (it.hasNext()) {
            val key = it.next()
            val parts = key.split("|")
            if (parts.size != 3) continue
            if (room.isNotBlank() && parts[0] != room) continue
            val count = runCatching { root.optInt(key, 0) }.getOrDefault(0)
            if (count <= 0) continue
            out.add(WarnEntry(parts[0], parts[1], parts[2], count))
        }
        return out.sortedWith(compareByDescending<WarnEntry> { it.count }.thenBy { it.room })
    }

    /** 清除单条警告计数（只动警告表，不碰黑名单/白名单/配置）。 */
    fun warnClear(context: Context?, room: String, wxId: String, rule: String): Boolean {
        val key = warnKey(room, wxId, rule)
        if (context == null || key.isBlank()) return false
        return runCatching {
            val root = readWarn(context)
            if (!root.has(key)) return@runCatching false
            root.remove(key)
            GroupAdminSettings.preferences(context).edit()
                .putString(GroupAdminSettings.KEY_WARN, root.toString()).apply()
            true
        }.getOrDefault(false)
    }

    /**
     * 清空警告计数：room 为空则不限群；wxId 非空则只清该成员。返回清掉的条数。
     * 只写 KEY_WARN 这一张表，不动黑名单/白名单/规则配置。
     */
    fun warnClearAll(context: Context?, room: String = "", wxId: String = ""): Int {
        if (context == null) return 0
        return runCatching {
            val root = readWarn(context)
            val drop = ArrayList<String>()
            val it = root.keys()
            while (it.hasNext()) {
                val k = it.next()
                val parts = k.split("|")
                if (parts.size != 3) continue
                if (room.isNotBlank() && parts[0] != room) continue
                if (wxId.isNotBlank() && parts[1] != wxId) continue
                drop.add(k)
            }
            drop.forEach { k -> root.remove(k) }
            GroupAdminSettings.preferences(context).edit()
                .putString(GroupAdminSettings.KEY_WARN, root.toString()).apply()
            drop.size
        }.getOrDefault(0)
    }

    /** key 格式化（供面板显示 room|wxId|rule）。 */
    fun warnKeyText(room: String, wxId: String, rule: String): String = room + "|" + wxId + "|" + rule

    private fun toJson(cfg: GroupConfig): JSONObject {
        val o = JSONObject()
        o.put("room", cfg.room)
        o.put("master", cfg.master)
        o.put("notify", cfg.notify)
        o.put("wlMaster", cfg.wlMaster)
        o.put("farewell", cfg.farewell)
        o.put("allowAdminCmd", cfg.allowAdminCmd)
        o.put("allowAgentCmd", cfg.allowAgentCmd)
        o.put("agentManageAdmin", cfg.agentManageAdmin)
        o.put("nonadminBoth", cfg.nonadminBoth)
        o.put("templateId", cfg.templateId)
        val rules = JSONObject()
        for ((key, value) in cfg.rules) {
            val r = JSONObject()
            r.put("on", value.on)
            r.put("reason", value.reason)
            r.put("unbanHours", value.unbanHours)
            r.put("warnEnable", value.warnEnable)
            r.put("warnCount", value.warnCount)
            r.put("warnText", value.warnText)
            val params = JSONObject()
            for ((pKey, pValue) in value.params) params.put(pKey, pValue)
            r.put("params", params)
            rules.put(key, r)
        }
        o.put("rules", rules)
        o.put("admins", toJsonArray(cfg.admins))
        o.put("agents", toJsonArray(cfg.agents))
        o.put("white", toJsonArray(cfg.white))
        o.put("black", toJsonArray(cfg.black))
        return o
    }

    private fun fromJson(room: String, o: JSONObject): GroupConfig {
        val cfg = GroupConfig(room)
        cfg.master = o.optBoolean("master", false)
        cfg.notify = o.optBoolean("notify", true)
        cfg.wlMaster = o.optBoolean("wlMaster", true)
        cfg.farewell = o.optString("farewell", GroupAdminSettings.DEFAULT_FAREWELL)
        cfg.allowAdminCmd = o.optBoolean("allowAdminCmd", true)
        cfg.allowAgentCmd = o.optBoolean("allowAgentCmd", true)
        cfg.agentManageAdmin = o.optBoolean("agentManageAdmin", true)
        cfg.nonadminBoth = o.optBoolean("nonadminBoth", false)
        cfg.templateId = o.optString("templateId", "")
        val rules = o.optJSONObject("rules")
        if (rules != null) {
            for (key in rules.keys()) {
                val r = rules.optJSONObject(key) ?: continue
                cfg.rules[key] = RuleConfig(
                    on = r.optBoolean("on", false),
                    reason = r.optString("reason", ""),
                    unbanHours = if (r.has("unbanHours")) r.optInt("unbanHours", 0) else r.optInt("unban", 0),
                    warnEnable = if (r.has("warnEnable")) r.optBoolean("warnEnable", false)
                    else r.optBoolean("warn", false),
                    warnCount = r.optInt("warnCount", 1),
                    warnText = r.optString("warnText", "不允许此类消息"),
                    params = readParams(r.optJSONObject("params"))
                )
            }
        }
        cfg.admins.addAll(readMembers(o.optJSONArray("admins")))
        cfg.agents.addAll(readMembers(o.optJSONArray("agents")))
        cfg.white.addAll(readMembers(o.optJSONArray("white")))
        cfg.black.addAll(readMembers(o.optJSONArray("black")))
        return cfg
    }

    private fun readParams(o: JSONObject?): MutableMap<String, String> {
        val out = LinkedHashMap<String, String>()
        if (o == null) return out
        for (key in o.keys()) {
            val value = o.optString(key, "")
            if (value.isNotBlank()) out[key] = value
        }
        return out
    }

    private fun readMembers(arr: JSONArray?): List<MemberRef> {
        if (arr == null) return emptyList()
        val out = ArrayList<MemberRef>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id", "")
            val name = o.optString("name", "")
            val expire = if (o.has("expireAt")) o.optLong("expireAt", 0L) else o.optLong("expire", 0L)
            if (id.isBlank() && name.isBlank()) continue
            out.add(MemberRef(id = id, name = name, expireAt = expire))
        }
        return out
    }
}
