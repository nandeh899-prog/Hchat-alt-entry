package com.aeonlab.toolkit.hooks.api.payment

data class TransferQueryParams(
    val transactionId: String,
    val transId: String,
    val invalidTime: Int = 0,
    val groupUsername: String = "",
    val transferAttach: String = ""
)
