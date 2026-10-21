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
    }

    companion object {
        private const val TAG = "HchatApi102"
    }
}