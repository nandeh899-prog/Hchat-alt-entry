package com.aeonlab.toolkit.utils
object HLog { fun e(message: String, error: Throwable) { throw AssertionError(message, error) } }
