package com.aeonlab.toolkit.hooks.items.groupadmin

import android.content.Context
import com.aeonlab.toolkit.hooks.items.groupleave.GroupLeaveMonitorSettings
import com.aeonlab.toolkit.preferences.AeonLabStorage
import org.json.JSONObject

/**
 * 模板读写入口。
 *
 * - 存储：群管理助手自己的 PREFS 里的独立键 [KEY]（只放模板，命名空间 `gadmin-tpl-`），
 *   与群配置键 `group_admin_group_configs` 完全分离 → 既有群配置行为不变；
 * - 群与模板的绑定：群配置里的 `templateId`（缺省/空 = 单独设置，向后兼容）；
 * - 媒体设置：套用时把模板里的基线键写到群级键 `<基线键>_<群 id>`（与模块既有约定一致）。
 */
object GroupAdminTemplateStore {

    const val KEY = GroupAdminSettings.KEY_TEMPLATES

    private const val MEDIA_PREFS = "AeonLab_group_leave_monitor_config"

    /** 模板携带的媒体设置基线键（群级键 = 基线键 + "_" + 群 id）。 */
    fun mediaKeys(): List<String> = listOf(
        GroupLeaveMonitorSettings.KEY_MEDIA_ORDER,
        GroupLeaveMonitorSettings.KEY_MEDIA_SEQUENCE,
        GroupLeaveMonitorSettings.KEY_PROMPT_TYPE,
        GroupLeaveMonitorSettings.KEY_BOTH_ORDER,
        GroupLeaveMonitorSettings.KEY_JOIN_IMAGE_PATHS,
        GroupLeaveMonitorSettings.KEY_LEFT_IMAGE_PATHS,
        GroupLeaveMonitorSettings.KEY_JOIN_VOICE_PATHS,
        GroupLeaveMonitorSettings.KEY_LEFT_VOICE_PATHS,
        GroupLeaveMonitorSettings.KEY_JOIN_EMOJI_PATHS,
        GroupLeaveMonitorSettings.KEY_LEFT_EMOJI_PATHS,
        GroupLeaveMonitorSettings.KEY_JOIN_VIDEO_PATHS,
        GroupLeaveMonitorSettings.KEY_LEFT_VIDEO_PATHS,
        GroupLeaveMonitorSettings.KEY_JOIN_FILE_PATHS,
        GroupLeaveMonitorSettings.KEY_LEFT_FILE_PATHS,
        GroupLeaveMonitorSettings.KEY_JOIN_FAVORITE_PATHS,
        GroupLeaveMonitorSettings.KEY_LEFT_FAVORITE_PATHS
    )

    private fun mediaPrefs(context: Context) =
        AeonLabStorage.preferences(context, MEDIA_PREFS)

    /** 读取某个群现在的媒体设置（只取非空项）。 */
    fun mediaSnapshot(context: Context?, room: String): MutableMap<String, String> {
        val out = linkedMapOf<String, String>()
        if (context == null || room.isBlank()) return out
        val sp = runCatching { mediaPrefs(context) }.getOrNull() ?: return out
        mediaKeys().forEach { base ->
            val value = runCatching { sp.getString(base + "_" + room, "") }.getOrNull().orEmpty()
            if (value.isNotBlank()) out[base] = value
        }
        return out
    }

    /** 把媒体设置写到群级键；只写模板里存在的键，其它键保持原样。 */
    fun applyMedia(context: Context?, room: String, media: Map<String, String>) {
        if (context == null || room.isBlank() || media.isEmpty()) return
        val sp = runCatching { mediaPrefs(context) }.getOrNull() ?: return
        val allowed = mediaKeys().toSet()
        val editor = sp.edit()
        media.forEach { (base, value) -> if (allowed.contains(base)) editor.putString(base + "_" + room, value) }
        editor.apply()
    }

    // ---------------- JSON ----------------

    fun list(context: Context?): List<GroupAdminTemplate> {
        val raw = readRaw(context)
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val root = JSONObject(raw)
            val out = ArrayList<GroupAdminTemplate>(root.length())
            for (key in root.keys()) {
                val o = root.optJSONObject(key) ?: continue
                out.add(decode(o))
            }
            out.sortedBy { it.name }
        }.getOrDefault(emptyList())
    }

    fun get(context: Context?, id: String): GroupAdminTemplate? {
        if (id.isBlank()) return null
        return list(context).firstOrNull { it.id == id }
    }

    fun save(context: Context?, tpl: GroupAdminTemplate) {
        if (context == null || tpl.id.isBlank()) return
        val root = runCatching { JSONObject(readRaw(context).ifBlank { "{}" }) }.getOrDefault(JSONObject())
        runCatching {
            root.put(tpl.id, encode(tpl))
            GroupAdminSettings.preferences(context).edit().putString(KEY, root.toString()).apply()
        }
    }

    private fun readRaw(context: Context?): String {
        if (context == null) return ""
        return runCatching {
            GroupAdminSettings.preferences(context).getString(KEY, "")
        }.getOrNull().orEmpty()
    }

    private fun encode(tpl: GroupAdminTemplate): JSONObject {
        val o = JSONObject()
        o.put("id", tpl.id)
        o.put("name", tpl.name)
        o.put("updatedAt", tpl.updatedAt)
        val rules = JSONObject()
        tpl.rules.forEach { (id, rc) ->
            val r = JSONObject()
            r.put("on", rc.on)
            r.put("reason", rc.reason)
            r.put("unbanHours", rc.unbanHours)
            r.put("warnEnable", rc.warnEnable)
            r.put("warnCount", rc.warnCount)
            r.put("warnText", rc.warnText)
            val params = JSONObject()
            rc.params.forEach { (k, v) -> params.put(k, v) }
            r.put("params", params)
            rules.put(id, r)
        }
        o.put("rules", rules)
        val media = JSONObject()
        tpl.media.forEach { (k, v) -> media.put(k, v) }
        o.put("media", media)
        return o
    }

    private fun decode(o: JSONObject): GroupAdminTemplate {
        val tpl = GroupAdminTemplate(
            id = o.optString("id", ""),
            name = o.optString("name", ""),
            updatedAt = o.optLong("updatedAt", 0L)
        )
        val rules = o.optJSONObject("rules")
        if (rules != null) {
            for (key in rules.keys()) {
                val r = rules.optJSONObject(key) ?: continue
                val params = linkedMapOf<String, String>()
                r.optJSONObject("params")?.let { p ->
                    for (k in p.keys()) {
                        val v = p.optString(k, "")
                        if (v.isNotBlank()) params[k] = v
                    }
                }
                tpl.rules[key] = RuleConfig(
                    on = r.optBoolean("on", false),
                    reason = r.optString("reason", ""),
                    unbanHours = r.optInt("unbanHours", 0),
                    warnEnable = r.optBoolean("warnEnable", false),
                    warnCount = r.optInt("warnCount", 1),
                    warnText = r.optString("warnText", ""),
                    params = params
                )
            }
        }
        val media = o.optJSONObject("media")
        if (media != null) {
            for (k in media.keys()) {
                val v = media.optString(k, "")
                if (v.isNotBlank()) tpl.media[k] = v
            }
        }
        return tpl
    }

    // ---------------- 模板操作 ----------------

    /** 新建模板：名字重复时自动加序号；规则默认全部关闭（等于「什么都没配」）。 */
    fun create(context: Context?, name: String, templateFrom: GroupConfig? = null): GroupAdminTemplate {
        val existing = list(context)
        val base = name.ifBlank { "新模板" }
        var finalName = base
        var index = 2
        while (existing.any { it.name == finalName }) {
            finalName = base + index
            index++
        }
        val tpl = GroupAdminTemplate(
            id = GroupAdminTemplates.newId(System.currentTimeMillis(), existing.size),
            name = finalName,
            updatedAt = System.currentTimeMillis()
        )
        if (templateFrom != null) {
            tpl.rules = GroupAdminTemplates.snapshotRules(templateFrom)
            tpl.media = mediaSnapshot(context, templateFrom.room)
        } else {
            tpl.rules = GroupAdminTemplates.snapshotRules(GroupConfig())
        }
        save(context, tpl)
        return tpl
    }

    fun duplicate(context: Context?, id: String): GroupAdminTemplate? {
        val src = get(context, id) ?: return null
        val existing = list(context)
        var name = src.name + " 副本"
        var index = 2
        while (existing.any { it.name == name }) {
            name = src.name + " 副本" + index
            index++
        }
        val copy = src.copyAs(GroupAdminTemplates.newId(System.currentTimeMillis(), existing.size), name)
        save(context, copy)
        return copy
    }

    fun rename(context: Context?, id: String, name: String): Boolean {
        val tpl = get(context, id) ?: return false
        tpl.name = name.ifBlank { tpl.name }
        tpl.updatedAt = System.currentTimeMillis()
        save(context, tpl)
        return true
    }

    /** 删除模板，并把所有已套用该模板的群解绑（保留它们当前的配置值）。 */
    fun remove(context: Context?, id: String): Int {
        if (context == null || id.isBlank()) return 0
        val bound = boundGroups(context, id)
        bound.forEach { room -> unbind(context, room) }
        val root = runCatching { JSONObject(readRaw(context).ifBlank { "{}" }) }.getOrDefault(JSONObject())
        root.remove(id)
        runCatching {
            GroupAdminSettings.preferences(context).edit().putString(KEY, root.toString()).apply()
        }
        return bound.size
    }

    fun boundGroups(context: Context?, id: String): List<String> {
        if (context == null || id.isBlank()) return emptyList()
        return GroupAdminStore.entries(context).map { it.room }.filter { room ->
            GroupAdminStore.group(context, room).templateId == id
        }
    }

    fun countBound(context: Context?, id: String): Int = boundGroups(context, id).size

    /** 套用模板到某个群：规则全量快照 + 媒体设置写入群级键。 */
    fun applyToGroup(context: Context?, room: String, id: String): Boolean {
        if (context == null || room.isBlank()) return false
        val tpl = get(context, id) ?: return false
        val cfg = GroupAdminStore.group(context, room)
        GroupAdminTemplates.applyRules(cfg, tpl)
        GroupAdminStore.save(context, cfg)
        applyMedia(context, room, tpl.media)
        return true
    }

    /** 改为单独设置：清空绑定，**保留当前生效值**。 */
    fun unbind(context: Context?, room: String): Boolean {
        if (context == null || room.isBlank()) return false
        val cfg = GroupAdminStore.group(context, room)
        if (cfg.templateId.isBlank()) return false
        cfg.templateId = ""
        GroupAdminStore.save(context, cfg)
        return true
    }

    /** 把模板当前内容同步到所有已套用的群（规则 + 媒体），返回同步的群数。 */
    fun syncToBoundGroups(context: Context?, id: String): Int {
        if (context == null || id.isBlank()) return 0
        val tpl = get(context, id) ?: return 0
        val rooms = boundGroups(context, id)
        rooms.forEach { room ->
            val cfg = GroupAdminStore.group(context, room)
            GroupAdminTemplates.applyRules(cfg, tpl)
            cfg.templateId = tpl.id
            GroupAdminStore.save(context, cfg)
            applyMedia(context, room, tpl.media)
        }
        return rooms.size
    }

    /**
     * 以「模板壳」形式取出模板里的规则，供规则配置页复用（room 用 `模板:<id>`，不会写进群配置）。
     */
    fun templateConfig(context: Context?, id: String): GroupConfig {
        val tpl = get(context, id) ?: return GroupConfig(room = templateRoom(id))
        val cfg = GroupConfig(room = templateRoom(id))
        tpl.rules.forEach { (ruleId, rc) ->
            cfg.rules[ruleId] = RuleConfig(
                on = rc.on,
                reason = rc.reason,
                unbanHours = rc.unbanHours,
                warnEnable = rc.warnEnable,
                warnCount = rc.warnCount,
                warnText = rc.warnText,
                params = LinkedHashMap(rc.params)
            )
        }
        return cfg
    }

    /** 模板壳的伪 room（只用于内存态与 remember key）。 */
    fun templateRoom(id: String): String = "模板:" + id

    fun isTemplateRoom(room: String): Boolean = room.startsWith("模板:")

    /** 把模板壳里的规则写回模板。 */
    fun updateTemplate(context: Context?, id: String, block: (GroupConfig) -> Unit) {
        if (context == null || id.isBlank()) return
        val tpl = get(context, id) ?: return
        val cfg = templateConfig(context, id)
        block(cfg)
        tpl.rules = linkedMapOf<String, RuleConfig>().also { out ->
            cfg.rules.forEach { (ruleId, rc) -> out[ruleId] = rc }
        }
        tpl.updatedAt = System.currentTimeMillis()
        save(context, tpl)
    }

    fun updateTemplateMedia(context: Context?, id: String, base: String, value: String) {
        if (context == null || id.isBlank()) return
        val tpl = get(context, id) ?: return
        if (value.isBlank()) tpl.media.remove(base) else tpl.media[base] = value
        tpl.updatedAt = System.currentTimeMillis()
        save(context, tpl)
    }
}
