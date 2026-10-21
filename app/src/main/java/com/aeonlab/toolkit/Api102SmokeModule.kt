package com.aeonlab.toolkit

import android.util.Log
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam

/**
 * 冒烟测试用现代（API 102）模块入口：只打日志并 hook 一个 Activity.onResume，
 * 用于验证 LSPosed 的 libxposed 现代加载链路在本机可用。
 */
class Api102SmokeModule : XposedModule() {

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        log(
            Log.INFO, TAG,
            "onModuleLoaded process=${param.processName} api=$apiVersion " +
                "framework=$frameworkName($frameworkVersionCode)"
        )
    }

    override fun onPackageReady(param: PackageReadyParam) {
        log(Log.INFO, TAG, "onPackageReady pkg=${param.packageName} cl=${param.classLoader}")
        if (param.packageName != "com.tencent.mm") return
        probeLegacy("module_cl", javaClass.classLoader)
        probeLegacy("host_cl", param.classLoader)
        runCatching {
            val activity = Class.forName("android.app.Activity", false, param.classLoader)
            val onResume = activity.getDeclaredMethod("onResume")
            hook(onResume).intercept { chain ->
                log(Log.INFO, TAG, "smoke hook fired")
                chain.proceed()
            }
            log(Log.INFO, TAG, "smoke hook installed OK")
        }.onFailure {
            log(Log.ERROR, TAG, "smoke hook install failed: $it")
        }
        probeLegacyHook(param.classLoader)
    }

    /** 探测现代模块 classloader 是否还能看到 legacy de.robv 类 */
    private fun probeLegacy(tag: String, cl: ClassLoader?) {
        val names = listOf(
            "de.robv.android.xposed.XC_MethodHook",
            "de.robv.android.xposed.XposedBridge",
            "de.robv.android.xposed.XposedHelpers",
            "de.robv.android.xposed.callbacks.XCallback",
            "de.robv.android.xposed.XC_MethodReplacement"
        )
        names.forEach { n ->
            val r = runCatching { Class.forName(n, false, cl) }
            log(Log.INFO, TAG, "probe[$tag] $n = ${if (r.isSuccess) "FOUND" else "MISSING"}")
        }
    }

    /** 真正调用一次 legacy 桥，确认它在现代模块里能否工作 */
    private fun probeLegacyHook(hostCl: ClassLoader) {
        runCatching {
            val activity = Class.forName("android.app.Activity", false, hostCl)
            val onResume = activity.getDeclaredMethod("onResume")
            de.robv.android.xposed.XposedBridge.hookMethod(
                onResume,
                object : de.robv.android.xposed.XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        log(Log.INFO, TAG, "legacy bridge hook fired")
                    }
                }
            )
            log(Log.INFO, TAG, "legacy bridge hook installed OK")
        }.onFailure {
            log(Log.ERROR, TAG, "legacy bridge hook failed: $it")
        }
    }

    companion object {
        private const val TAG = "HchatApi102"
    }
}