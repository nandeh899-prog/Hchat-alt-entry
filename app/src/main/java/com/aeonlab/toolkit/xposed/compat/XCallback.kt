package com.aeonlab.toolkit.xposed.compat

/**
 * 兼容层：对应 legacy 的 de.robv.android.xposed.callbacks.XCallback 优先级常量。
 * 数值与 legacy 保持一致，保证钩子相对顺序不变。
 */
object XCallback {
    const val PRIORITY_DEFAULT = 50
    const val PRIORITY_LOWEST = -10000
    const val PRIORITY_HIGHEST = 10000
}