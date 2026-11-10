package com.aeonlab.toolkit.hooks.items.groupadmin

/**
 * 群管理助手模板：**24 条规则的全量快照 + 该群的媒体设置快照**。
 *
 * 语义（与用户确认过的口径一致）：
 * - 群配置里只保存「模板 id + 套用那一刻的快照」，运行期照旧只读群配置 → 不改变既有读取路径；
 * - 套用后逐条规则编辑被锁（面板层拦截），要改就改模板或用「改为单独设置」；
 * - 模板改动**不会**自动影响已套用的群，需要在模板页点「同步到已套用的 N 个群」，避免「改了却不生效/突然生效」的困惑；
 * - 「改为单独设置」= 清空 templateId，**保留当前生效值**，从此该群独立配置。
 *
 * 本文件不依赖 Android，便于 JVM 单测。
 */
data class GroupAdminTemplate(
    val id: String = "",
    var name: String = "",
    /** 规则 id → 规则配置（24 条全量快照）。 */
    var rules: MutableMap<String, RuleConfig> = linkedMapOf(),
    /** 媒体设置基线键 → 值；套用时按 `<键>_<群 id>` 落到群级键。 */
    var media: MutableMap<String, String> = linkedMapOf(),
    var updatedAt: Long = 0L
) {
    fun ruleCount(): Int = rules.size

    fun mediaCount(): Int = media.size

    /** 开启中的规则条数，用于列表摘要。 */
    fun onCount(): Int = rules.values.count { it.on }

    fun copyAs(newId: String, newName: String): GroupAdminTemplate {
        val rulesCopy = linkedMapOf<String, RuleConfig>()
        rules.forEach { (id, rc) ->
            rulesCopy[id] = RuleConfig(
                on = rc.on,
                reason = rc.reason,
                unbanHours = rc.unbanHours,
                warnEnable = rc.warnEnable,
                warnCount = rc.warnCount,
                warnText = rc.warnText,
                params = LinkedHashMap(rc.params)
            )
        }
        return GroupAdminTemplate(
            id = newId,
            name = newName,
            rules = rulesCopy,
            media = LinkedHashMap(media),
            updatedAt = System.currentTimeMillis()
        )
    }
}

/** 模板的纯逻辑：快照、套用与摘要（不依赖 Android / JSON）。 */
object GroupAdminTemplates {

    /** 模板 id 命名空间前缀（存储键与群配置里的 templateId 都用它，避免与其它功能撞名）。 */
    const val ID_PREFIX = "gadmin-tpl-"

    fun newId(now: Long, salt: Int = 0): String =
        ID_PREFIX + now.toString(36) + "-" + (salt and 0xFFFF).toString(36)

    /**
     * 把群配置里的 24 条规则做快照（含未开启的，保证「套用后开关状态完全等于模板」）。
     * 缺失的规则用目录默认值补齐，模板始终是 24 条全量。
     */
    fun snapshotRules(cfg: GroupConfig): MutableMap<String, RuleConfig> {
        val out = linkedMapOf<String, RuleConfig>()
        GroupAdminRuleCatalog.SPECS.forEach { spec ->
            val src = GroupAdminRuleCatalog.ruleOf(cfg, spec.id)
            out[spec.id] = RuleConfig(
                on = src.on,
                reason = src.reason,
                unbanHours = src.unbanHours,
                warnEnable = src.warnEnable,
                warnCount = src.warnCount,
                warnText = src.warnText,
                params = LinkedHashMap(src.params)
            )
        }
        return out
    }

    /** 把模板规则写进群配置（覆盖式，24 条全量；既有名单/群设置不动）。 */
    fun applyRules(cfg: GroupConfig, tpl: GroupAdminTemplate) {
        cfg.rules.clear()
        tpl.rules.forEach { (id, rc) ->
            cfg.rules[id] = RuleConfig(
                on = rc.on,
                reason = rc.reason,
                unbanHours = rc.unbanHours,
                warnEnable = rc.warnEnable,
                warnCount = rc.warnCount,
                warnText = rc.warnText,
                params = LinkedHashMap(rc.params)
            )
        }
        cfg.templateId = tpl.id
    }

    /** 套用后的可见提示文案。 */
    fun appliedSummary(tpl: GroupAdminTemplate): String =
        "已套用：" + tpl.name + "（" + tpl.onCount() + "/" + tpl.ruleCount() + " 条规则开启，媒体 " + tpl.mediaCount() + " 项）"

    fun listSummary(tpl: GroupAdminTemplate, boundGroups: Int): String =
        "规则 " + tpl.onCount() + "/" + tpl.ruleCount() + " 开启 · 媒体 " + tpl.mediaCount() + " 项 · " +
            boundGroups + " 个群在用"
}
