package com.aeonlab.toolkit.hooks.items.selectedmessages

class SelectedMessageSendHandle internal constructor(
    private val cancelAction: () -> Unit
) {
    fun cancel() {
        cancelAction()
    }
}
