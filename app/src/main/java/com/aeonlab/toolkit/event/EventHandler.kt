package com.aeonlab.toolkit.event

fun interface EventHandler<E> {
    fun handleEvent(event: E)
}
