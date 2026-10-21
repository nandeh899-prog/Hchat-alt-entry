package com.aeonlab.toolkit

import com.aeonlab.toolkit.utils.HLog
import com.aeonlab.toolkit.xposed.compat.CompatRuntime
import com.aeonlab.toolkit.xposed.compat.XC_LoadPackage
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.callbacks.XC_LoadPackage as LegacyLoadPackage

/**
 * legacy 框架入口（de.robv API）。
 *
 * 保留原因：旧框架（LSPosed < 102 或仍以 legacy 模式加载本模块时）通过 assets/xposed_init
 * 加载本类；现代框架走 [ModernEntry]，不会触碰本类，因此这里的 de.robv 引用不会在现代模式下被解析。
 */
class LegacyEntry : IXposedHookLoadPackage {

    override fun handleLoadPackage(lpparam: LegacyLoadPackage.LoadPackageParam) {
        CompatRuntime.installLegacy()
        val compat = XC_LoadPackage.LoadPackageParam().apply {
            packageName = lpparam.packageName
            processName = lpparam.processName
            classLoader = lpparam.classLoader
            appInfo = lpparam.appInfo
            isFirstApplication = lpparam.isFirstApplication
        }
        try {
            ModuleEntry().handleLoadPackage(compat)
        } catch (t: Throwable) {
            HLog.e("[Hchat:Entry] legacy 入口初始化失败", t)
        }
    }
}