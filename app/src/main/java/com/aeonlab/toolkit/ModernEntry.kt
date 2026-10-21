package com.aeonlab.toolkit

import android.content.pm.ApplicationInfo
import android.util.Log
import com.aeonlab.toolkit.utils.HLog
import com.aeonlab.toolkit.xposed.compat.CompatRuntime
import com.aeonlab.toolkit.xposed.compat.XC_LoadPackage
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import java.util.concurrent.ConcurrentHashMap

/**
 * 现代框架入口（libxposed API 102）。
 *
 * 时机选择：
 * - onPackageLoaded 早于 Application 创建，与 legacy handleLoadPackage 时机最接近，
 *   能正常 hook 到 attachBaseContext / Tinker attach / Application.onCreate，因此优先使用。
 * - onPackageReady 作为兜底（Application 已创建、onCreate 之前），防止部分框架不回调 onPackageLoaded。
 * 同一进程只派发一次，避免重复安装 Hook。
 */
class ModernEntry : XposedModule() {

    private val dispatched = ConcurrentHashMap<String, Boolean>()

    /**
     * 真实进程名（如 com.tencent.mm:push）。
     * 不能用 ApplicationInfo.processName：那是 manifest 里静态声明的值，恒等于包名，
     * 会把 :push / :appbrand* 等子进程误判成主进程，导致它们跑完整初始化。
     */
    @Volatile
    private var currentProcessName: String? = null

    override fun onModuleLoaded(param: XposedModuleInterface.ModuleLoadedParam) {
        CompatRuntime.installLibXposed(this)
        currentProcessName = param.processName
        Log.i(
            TAG,
            "onModuleLoaded process=${param.processName} api=$apiVersion " +
                "framework=$frameworkName($frameworkVersionCode)"
        )
    }

    override fun onPackageLoaded(param: XposedModuleInterface.PackageLoadedParam) {
        dispatch(
            stage = "onPackageLoaded",
            packageName = param.packageName,
            info = param.applicationInfo,
            classLoader = param.defaultClassLoader,
            firstPackage = param.isFirstPackage
        )
    }

    override fun onPackageReady(param: XposedModuleInterface.PackageReadyParam) {
        dispatch(
            stage = "onPackageReady",
            packageName = param.packageName,
            info = param.applicationInfo,
            classLoader = param.classLoader,
            firstPackage = param.isFirstPackage
        )
    }

    private fun dispatch(
        stage: String,
        packageName: String?,
        info: ApplicationInfo?,
        classLoader: ClassLoader?,
        firstPackage: Boolean
    ) {
        val pkg = packageName ?: return
        val loader = classLoader ?: return
        val processName = currentProcessName ?: info?.processName ?: pkg
        if (dispatched.putIfAbsent("$pkg|$processName", true) != null) return
        Log.i(TAG, "$stage pkg=$pkg process=$processName first=$firstPackage")
        val compat = XC_LoadPackage.LoadPackageParam().apply {
            this.packageName = pkg
            this.processName = processName
            this.classLoader = loader
            this.appInfo = info
            this.isFirstApplication = firstPackage
        }
        try {
            ModuleEntry().handleLoadPackage(compat)
        } catch (t: Throwable) {
            HLog.e("[Hchat:Entry] 现代入口初始化失败($stage)", t)
        }
    }

    companion object {
        private const val TAG = "[Hchat:Modern]"
    }
}