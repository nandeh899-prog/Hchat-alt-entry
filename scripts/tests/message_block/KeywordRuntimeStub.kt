package com.aeonlab.toolkit.hooks.items.keywordnotify
import android.content.Context
import com.aeonlab.toolkit.hooks.api.model.WeChatParsedMessage
object KeywordNotificationRuntime {
    fun shouldAllowBlockedMessageIntoDatabase(context: Context, message: WeChatParsedMessage): Boolean = false
}
