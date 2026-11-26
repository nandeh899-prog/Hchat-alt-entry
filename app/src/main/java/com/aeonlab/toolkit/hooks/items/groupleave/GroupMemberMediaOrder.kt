package com.aeonlab.toolkit.hooks.items.groupleave

/**
 * 进退群媒体「类型顺序」的纯函数支持（无 Compose / Android 依赖，便于单测）。
 *
 * 语义澄清（三个控件互不相同，别混淆）：
 * - `KEY_MEDIA_ORDER`（面板「媒体顺序」）：**是否发媒体、媒体与提示的先后**（不发送/先提示后媒体/先媒体后提示）；
 * - `KEY_MEDIA_SEQUENCE`（面板「媒体类型顺序」）：**发送媒体时先发哪一类**，逗号分隔，本文件处理的就是它；
 * - 各「进群图片 / 退群图片 …」行：**该类媒体具体用哪些文件**。
 *
 * 落盘格式与键名保持原样（逗号分隔、同一 key），因此历史配置继续生效。
 */
object GroupMemberMediaOrder {
    /** 支持的媒体类型，顺序即默认顺序。 */
    val TYPES = listOf("image", "voice", "emoji", "video", "file", "favorite")

    fun label(type: String): String = when (type.trim().lowercase()) {
        "image" -> "图片"
        "voice" -> "语音"
        "emoji" -> "表情"
        "video" -> "视频"
        "file" -> "文件"
        "favorite" -> "收藏"
        else -> type
    }

    /**
     * 解析顺序串：只保留已知类型、去重、忽略空白；空串或全是未知类型时回退为默认六类。
     * 不会凭空追加用户没有配置的类型，避免改变既有运行期行为。
     */
    fun types(sequence: String): List<String> {
        val parsed = sequence.split(',').asSequence()
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() && TYPES.contains(it) }
            .distinct()
            .toList()
        return parsed.ifEmpty { TYPES }
    }

    fun sequence(types: List<String>): String = types.joinToString(",")

    /** 面板摘要：把逗号串转成可读顺序。 */
    fun summary(sequence: String): String = types(sequence).joinToString(" → ") { label(it) }

    /** 类型行标题：把「顺序 = 第几位」直接显示在该类型自己的行上（不在顺序里的类型不加序号）。 */
    fun rowTitle(type: String, title: String, sequence: String): String {
        val index = types(sequence).indexOf(type)
        return if (index < 0) title else "${index + 1}. $title"
    }

    /** 点按上移：返回新的类型列表；已在最前或下标越界时原样返回。 */
    fun moveUp(types: List<String>, index: Int): List<String> {
        if (index <= 0 || index >= types.size) return types
        val next = types.toMutableList()
        val moved = next.removeAt(index)
        next.add(index - 1, moved)
        return next
    }
}
