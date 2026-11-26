package com.aeonlab.toolkit.hooks.items.groupadmin

/**
 * 「自动规则」三个数字输入框的纯归一函数。
 *
 * 单独抽出来的原因：`InputRow` 是完全受控组件（内部没有草稿状态，直接把 `value` 交给
 * `BasicTextField`），输入内容必须先落到本地草稿再写存储，而归一规则必须能在 JVM 上单测，
 * 不能只活在 Compose 回调里（真机出现过「改不动」的教训）。
 */
object GroupAdminRuleInputs {

    /** 长度阈值输入框回显文本：只留数字，最多 6 位。 */
    fun limitDraft(raw: String): String = raw.filter { it.isDigit() }.take(6)

    /** 长度阈值落盘值：空串或 <1 视为「用默认」，返回 null 表示从 params 里删掉该键。 */
    fun limitParam(draft: String): String? {
        val value = draft.toIntOrNull() ?: return null
        return if (value < 1) null else value.toString()
    }

    /** 警告次数输入框回显文本：只留数字，最多 3 位。 */
    fun warnCountDraft(raw: String): String = raw.filter { it.isDigit() }.take(3)

    /** 警告次数落盘值：空或非法按 1 计，范围 1~999。 */
    fun warnCount(draft: String): Int = draft.toIntOrNull()?.coerceIn(1, 999) ?: 1

    /** 拉黑时长输入框回显文本：只留数字，最多 5 位。 */
    fun unbanHoursDraft(raw: String): String = raw.filter { it.isDigit() }.take(5)

    /** 拉黑时长落盘值：空或非法按 0（永久）计，范围 0~87600 小时。 */
    fun unbanHours(draft: String): Int = draft.toIntOrNull()?.coerceIn(0, 87600) ?: 0
}
