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
     * 与 legacy API 82 语义对齐（关键）：
     * legacy 的 param.result 是 getResult()/setResult() 的合成属性，代码里的
     * `param.result = x`（包括写 null）在 before 阶段等价于 setResult(x) →
     * returnEarly = true，即「覆盖原方法，原方法不再执行」。既有代码大量依赖
     * `param.result = null` 来吞掉宿主（微信）的原处理，因此这里必须保留同一语义。
     */
    class MethodHookParam {
        @JvmField
        var method: Member? = null

        @JvmField
        var thisObject: Any? = null

        @JvmField
        var args: Array<Any?> = emptyArray()

        /** 写过 result/throwable 即为「已覆盖原方法」，等价于 legacy 的 returnEarly。 */
        private var overridden = false

        private var rawResult: Any? = null

        var result: Any?
            get() = rawResult
            set(value) {
                rawResult = value
                throwable = null
                overridden = true
            }

        @JvmField
        var throwable: Throwable? = null

        private var extras: MutableMap<String, Any?>? = null

        /**
         * Kotlin 侧仍沿用 legacy 的 getResult()/setResult() 调用形式
         * （Kotlin 类的属性访问器不能当函数调用，这里用 @JvmName 避免与属性访问器签名冲突）。
         */
        @JvmName("getResultCompat")
        fun getResult(): Any? = rawResult

        @JvmName("setResultCompat")
        fun setResult(value: Any?) {
            result = value
        }

        fun hasThrowable(): Boolean = throwable != null

        fun getThrowable(): Throwable? = throwable

        fun setThrowable(value: Throwable?) {
            throwable = value
            rawResult = null
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

        /**
         * 与 legacy 一致：只要写过 result/throwable 就视为已覆盖原方法。
         * 注意不能用「result != null」判定——legacy 里写 null 同样会阻止原方法执行，
         * 之前这样判定会让所有 `param.result = null` 的拦截静默失效。
         */
        internal fun isOverridden(): Boolean = overridden || throwable != null

        /** after 阶段重新开始：先注入原方法结果，但不算「已覆盖」，由 after 回调决定是否改写。 */
        internal fun seedAfterPhase(value: Any?, error: Throwable?) {
            rawResult = value
            throwable = error
            overridden = false
        }

        internal fun putRawResult(value: Any?) {
            rawResult = value
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