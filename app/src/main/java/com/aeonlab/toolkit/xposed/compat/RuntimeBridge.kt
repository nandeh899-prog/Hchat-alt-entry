package com.aeonlab.toolkit.xposed.compat

import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Executable

/**
 * 兼容层运行时桥：把 [XC_MethodHook] 语义挂到具体的框架实现上。
 * 现代（libxposed API 102）与 legacy（de.robv API）各一个实现。
 */
internal interface RuntimeBridge {
    fun hook(executable: Executable, callback: XC_MethodHook): XC_MethodHook.Unhook

    fun log(message: String)

    fun log(throwable: Throwable)
}

internal object CompatRuntime {
    private const val TAG = "Hchat"

    @Volatile
    private var bridge: RuntimeBridge? = null

    fun installLibXposed(module: XposedModule) {
        bridge = LibXposedBridge(module)
    }

    fun installLegacy() {
        bridge = LegacyBridge()
    }

    fun hook(executable: Executable, callback: XC_MethodHook): XC_MethodHook.Unhook {
        val current = bridge ?: throw IllegalStateException("Hchat 兼容层运行时未初始化")
        return current.hook(executable, callback)
    }

    fun log(message: String) {
        val current = bridge
        if (current != null) current.log(message) else Log.i(TAG, message)
    }

    fun log(throwable: Throwable) {
        val current = bridge
        if (current != null) current.log(throwable) else Log.e(TAG, "Hchat", throwable)
    }
}

/** libxposed API 102 实现 */
private class LibXposedBridge(private val module: XposedModule) : RuntimeBridge {

    override fun hook(executable: Executable, callback: XC_MethodHook): XC_MethodHook.Unhook {
        val handle = module.hook(executable)
            .setPriority(callback.priority)
            .intercept { chain -> dispatch(callback, chain) }
        return XC_MethodHook.Unhook { runCatching { handle.unhook() } }
    }

    private fun dispatch(callback: XC_MethodHook, chain: XposedInterface.Chain): Any? {
        val param = XC_MethodHook.MethodHookParam()
        param.method = chain.executable
        param.thisObject = chain.thisObject
        param.args = chain.args.toTypedArray()

        callback.callBeforeHook(param)
        param.getThrowable()?.let { throw it }
        if (param.isOverridden()) return param.getResult()

        var result: Any? = null
        var error: Throwable? = null
        try {
            result = chain.proceed(param.args)
        } catch (t: Throwable) {
            error = t
        }

        param.seedAfterPhase(result, error)
        callback.callAfterHook(param)
        param.getThrowable()?.let { throw it }
        if (param.isOverridden()) return param.getResult()
        error?.let { throw it }
        return result
    }

    override fun log(message: String) {
        runCatching { module.log(Log.INFO, CompatRuntimeTag, message) }
            .onFailure { Log.i(CompatRuntimeTag, message) }
    }

    override fun log(throwable: Throwable) {
        val text = throwable.stackTraceToString()
        runCatching { module.log(Log.ERROR, CompatRuntimeTag, text) }
            .onFailure { Log.e(CompatRuntimeTag, text) }
    }
}

/** legacy de.robv API 实现（旧框架下继续可用） */
private class LegacyBridge : RuntimeBridge {

    override fun hook(executable: Executable, callback: XC_MethodHook): XC_MethodHook.Unhook {
        val legacy = object : de.robv.android.xposed.XC_MethodHook(callback.priority) {
            protected override fun beforeHookedMethod(
                param: de.robv.android.xposed.XC_MethodHook.MethodHookParam
            ) {
                val compat = XC_MethodHook.MethodHookParam()
                compat.method = param.method
                compat.thisObject = param.thisObject
                compat.args = param.args
                callback.callBeforeHook(compat)
                param.args = compat.args
                if (compat.hasThrowable()) {
                    param.setThrowable(compat.getThrowable())
                } else if (compat.isOverridden()) {
                    param.setResult(compat.getResult())
                }
            }

            protected override fun afterHookedMethod(
                param: de.robv.android.xposed.XC_MethodHook.MethodHookParam
            ) {
                val compat = XC_MethodHook.MethodHookParam()
                compat.method = param.method
                compat.thisObject = param.thisObject
                compat.args = param.args
                compat.seedAfterPhase(
                    param.getResult(),
                    if (param.hasThrowable()) param.getThrowable() else null
                )
                callback.callAfterHook(compat)
                if (compat.hasThrowable()) {
                    param.setThrowable(compat.getThrowable())
                } else if (compat.isOverridden()) {
                    param.setResult(compat.getResult())
                }
            }
        }
        val unhook = de.robv.android.xposed.XposedBridge.hookMethod(executable, legacy)
        return XC_MethodHook.Unhook { runCatching { unhook.unhook() } }
    }

    override fun log(message: String) {
        de.robv.android.xposed.XposedBridge.log(message)
    }

    override fun log(throwable: Throwable) {
        de.robv.android.xposed.XposedBridge.log(throwable)
    }
}

private const val CompatRuntimeTag = "Hchat"