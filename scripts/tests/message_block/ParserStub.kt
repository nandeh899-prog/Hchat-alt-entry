package com.aeonlab.toolkit.hooks.api.message
import com.aeonlab.toolkit.hooks.api.model.WeChatParsedMessage
class WeChatMessageParseApi {
    fun parseAddMsg(value: Any, selfWxId: String): WeChatParsedMessage? = null
    fun isLikelyAddMsgClass(type: Class<*>): Boolean = false
}
