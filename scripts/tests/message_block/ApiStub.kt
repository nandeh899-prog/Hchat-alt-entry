package com.aeonlab.toolkit.hooks.api.core
import com.aeonlab.toolkit.hooks.api.message.WeChatMessageParseApi
object WeChatApis {
    fun messageParser(): WeChatMessageParseApi? = null
    fun account(): Account? = null
}
class Account { fun selfWxId(): String = "self" }
