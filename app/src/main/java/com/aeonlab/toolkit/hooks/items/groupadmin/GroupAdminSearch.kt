package com.aeonlab.toolkit.hooks.items.groupadmin

/**
 * 「添加群聊」页的搜索匹配（纯函数、无 Android 依赖，便于 JVM 单测）。
 *
 * 规则：
 * 1. 查询串去掉首尾空白后为空 → 原样返回，不重排；
 * 2. 群名称模糊匹配：名称包含查询串（大小写不敏感，中文按子串比较）；
 * 3. 群 ID 匹配：同时接受带后缀（7423734976@chatroom）与不带后缀（7423734976）两种输入，
 *    完整 ID 精确命中或 ID 包含查询串（片段）都算命中；
 * 4. 排序：群 ID 精确命中的排最前，其次群名完全相同，再是群名模糊、完整 ID 片段、纯数字 ID 片段；
 *    同一档内保持列表原有顺序（稳定排序）；
 * 5. 查询串只有「@…」而没有实义字符时（例如只输了 "@chatroom"）不做过滤，
 *    否则会出现「搜什么都能搜到」。
 */
object GroupAdminSearch {

    fun filterGroups(list: List<Pair<String, String>>, query: String): List<Pair<String, String>> {
        val text = query.trim()
        if (text.isEmpty()) return list
        val lower = text.lowercase()
        val idPart = text.substringBefore('@').trim().lowercase()
        if (idPart.isEmpty()) return list
        val hits = list.mapIndexedNotNull { index, entry ->
            val id = entry.first.trim()
            val idLower = id.lowercase()
            val bare = idLower.substringBefore('@')
            val name = entry.second.trim().lowercase()
            val rank = when {
                (bare.isNotEmpty() && bare == idPart) || idLower == lower -> 0
                name.isNotEmpty() && name == lower -> 1
                name.isNotEmpty() && name.contains(lower) -> 2
                idLower.contains(lower) -> 3
                bare.isNotEmpty() && bare.contains(idPart) -> 4
                else -> -1
            }
            if (rank < 0) null else Triple(rank, index, entry)
        }
        return hits.sortedWith(compareBy({ it.first }, { it.second })).map { it.third }
    }
}
