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
        /**
         * 是否显式要求跳过原方法。
         *
         * 关键坑（真机 2026-11-27 定位）：legacy 语义里只有 setResult/setThrowable 会把
         * returnEarly 置位；直接写字段 `param.result = null` 在 null 情况下等于「什么都没做」，
         * 原方法照样执行。分组功能里大量拦截点写的是 `param.result = null`（吞掉点击 / 拦截
         * 虚拟会话），于是「吞掉」全部无效：微信自己又开了一个分组页（要返回两次）、
         * 又建了空白聊天层（退出闪屏）。所以这里补上 flag，并让 setResult(null) 真正生效。
         */
        private var returnEarly = false
        private var extras: MutableMap<String, Any?>? = null
        fun getResult(): Any? = result
        fun setResult(value: Any?) {
            result = value
            throwable = null
            returnEarly = true
        }

        fun hasThrowable(): Boolean = throwable != null
        fun getThrowable(): Throwable? = throwable
        fun setThrowable(value: Throwable?) {
            throwable = value
            returnEarly = true
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
         * legacy 判定：result / throwable 非空，或显式 setResult/setThrowable 过（含 null）。
         * 后半句是本次补的关键语义 —— 以前的实现只看非空，导致 `setResult(null)`
         * 依然会把原方法跑一遍。
         */
        internal fun isOverridden(): Boolean =
            returnEarly || result != null || throwable != null

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