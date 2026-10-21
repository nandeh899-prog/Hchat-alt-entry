package com.aeonlab.toolkit.xposed.compat

import java.lang.reflect.Member

/**
 * 兼容层：等价于 legacy 的 de.robv.android.xposed.XC_MethodHook。
 *
 * 语义对齐（关键，不能变形）：
 * - beforeHookedMethod 里 setResult(x) → 跳过原方法，结果为 x
 * - beforeHookedMethod 里 setThrowable(t) → 跳过原方法并抛出 t
 * - afterHookedMethod 里 setResult / setThrowable 可覆盖已有结果
 * - args / thisObject 可直接改写（改写后的 args 会传给原方法）
 */
open class XC_MethodHook @JvmOverloads constructor(
    @JvmField val priority: Int = XCallback.PRIORITY_DEFAULT
) {

    class MethodHookParam {
        @JvmField
        var method: Member? = null

        @JvmField
        var thisObject: Any? = null

        @JvmField
        var args: Array<Any?> = emptyArray()

        private var result: Any? = null
        private var throwable: Throwable? = null
        private var overridden = false
        private var extras: MutableMap<String, Any?>? = null

        fun getResult(): Any? = result

        fun setResult(value: Any?) {
            result = value
            throwable = null
            overridden = true
        }

        fun hasThrowable(): Boolean = throwable != null

        fun getThrowable(): Throwable? = throwable

        fun setThrowable(value: Throwable?) {
            throwable = value
            overridden = true
        }

        fun getObjectExtra(key: String): Any? = extras?.get(key)

        fun setObjectExtra(key: String, value: Any?) {
            val map = extras ?: HashMap<String, Any?>().also { extras = it }
            map[key] = value
        }

        fun removeObjectExtra(key: String) {
            extras?.remove(key)
        }

        /** 是否已被 setResult / setThrowable 覆盖（供桥接层判断是否跳过原方法） */
        internal fun isOverridden(): Boolean = overridden

        /** 进入 after 阶段前重置覆盖标记，但保留 result / throwable 现状 */
        internal fun enterAfterPhase() {
            overridden = false
        }

        internal fun seedAfterPhase(value: Any?, error: Throwable?) {
            result = value
            throwable = error
            overridden = false
        }

        internal fun putRawResult(value: Any?) {
            result = value
        }
    }

    class Unhook internal constructor(private val detach: () -> Unit) {
        fun unhook() {
            detach()
        }
    }

    protected open fun beforeHookedMethod(param: MethodHookParam) {}

    protected open fun afterHookedMethod(param: MethodHookParam) {}

    // internal：仅供兼容层桥接调用（Java 侧不可见，不会污染对外 API）
    internal fun callBeforeHook(param: MethodHookParam) {
        beforeHookedMethod(param)
    }

    internal fun callAfterHook(param: MethodHookParam) {
        afterHookedMethod(param)
    }
}