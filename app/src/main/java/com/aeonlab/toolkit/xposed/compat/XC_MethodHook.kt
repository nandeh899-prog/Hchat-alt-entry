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

    /**
     * 与 legacy API 82 保持一致：result / throwable 是 public 字段，
     * 大量既有代码直接写 param.result = x，不能改成 private + setter。
     */
    class MethodHookParam {
        @JvmField
        var method: Member? = null

        @JvmField
        var thisObject: Any? = null

        @JvmField
        var args: Array<Any?> = emptyArray()

        @JvmField
        var result: Any? = null

        @JvmField
        var throwable: Throwable? = null

        private var extras: MutableMap<String, Any?>? = null

        fun getResult(): Any? = result

        fun setResult(value: Any?) {
            result = value
            throwable = null
        }

        fun hasThrowable(): Boolean = throwable != null

        fun getThrowable(): Throwable? = throwable

        fun setThrowable(value: Throwable?) {
            throwable = value
        }

        fun getObjectExtra(key: String): Any? = extras?.get(key)

        fun setObjectExtra(key: String, value: Any?) {
            val map = extras ?: HashMap<String, Any?>().also { extras = it }
            map[key] = value
        }

        fun removeObjectExtra(key: String) {
            extras?.remove(key)
        }

        /** 与 legacy 判定一致：result 或 throwable 非空即视为已覆盖原方法 */
        internal fun isOverridden(): Boolean = result != null || throwable != null

        internal fun seedAfterPhase(value: Any?, error: Throwable?) {
            result = value
            throwable = error
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

    @Throws(Throwable::class)
    protected open fun beforeHookedMethod(param: MethodHookParam) {}

    @Throws(Throwable::class)
    protected open fun afterHookedMethod(param: MethodHookParam) {}

    // internal：仅供兼容层桥接调用（Java 侧不可见，不会污染对外 API）
    internal fun callBeforeHook(param: MethodHookParam) {
        beforeHookedMethod(param)
    }

    internal fun callAfterHook(param: MethodHookParam) {
        afterHookedMethod(param)
    }
}