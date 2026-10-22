package com.aeonlab.toolkit.hooks.items.hidemenu

import android.view.MenuItem
import com.aeonlab.toolkit.xposed.compat.XC_MethodHook
import com.aeonlab.toolkit.xposed.compat.XCallback
import com.aeonlab.toolkit.event.Events
import com.aeonlab.toolkit.hooks.api.message.SingleMessageMenuLocator
import com.aeonlab.toolkit.hooks.core.BaseFeature
import com.aeonlab.toolkit.hooks.core.DexInstallScheduler
import com.aeonlab.toolkit.hooks.core.FeatureContext
import com.aeonlab.toolkit.hooks.core.HookRegistry
import com.aeonlab.toolkit.preferences.AeonLabStorage
import com.aeonlab.toolkit.utils.HLog
import com.aeonlab.toolkit.utils.KavaReflector
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

class HideChatMenuFeature : BaseFeature() {
    private var runtime: HideChatMenuRuntime? = null

    override fun featureId(): String = ID

    override fun name(): String = "隐藏长按菜单"

    override fun onFeatureInit(context: FeatureContext) {
        registerSettingsProvider(HideChatMenuSettingsProvider())
    }

    override fun onFeatureInstall(context: FeatureContext) {
        runtime = HideChatMenuRuntime(context)
        scheduleInstall()
        subscribe(Events.DexReady::class.java) { scheduleInstall() }
    }

    override fun onFeatureDestroy(context: FeatureContext) {
        runtime = null
    }

    private fun scheduleInstall() {
        DexInstallScheduler.schedule(ID, name()) {
            runtime?.install() == true
        }
    }

    companion object {
        const val ID = "hide_chat_menu"
    }
}

private class HideChatMenuRuntime(
    private val context: FeatureContext
) {
    private val prefs = AeonLabStorage.preferences(context.hostContext(), HideChatMenuSettings.PREFS_NAME)
    private val hookedMethods = Collections.newSetFromMap(ConcurrentHashMap<Method, Boolean>())

    @Synchronized
    fun install(): Boolean {
        val methods = SingleMessageMenuLocator.menuCreateMethods(context) { message, throwable ->
            HLog.e("$TAG $message", throwable)
        }
        var installed = 0
        methods.forEach { method ->
            if (hookedMethods.contains(method)) {
                installed++
            } else if (hookedMethods.add(method) && hookMenuCreate(method)) {
                installed++
            }
        }
        if (installed == 0) {
            HLog.e("$TAG 定位或安装聊天长按菜单 Hook 失败")
        }
        return installed > 0
    }

    private fun hookMenuCreate(method: Method): Boolean {
        if (Modifier.isAbstract(method.modifiers) || method.declaringClass.isInterface) {
            hookedMethods.remove(method)
            return false
        }
        return runCatching {
            HookRegistry.get().hook(method, object : XC_MethodHook(XCallback.PRIORITY_HIGHEST) {
                override fun afterHookedMethod(param: MethodHookParam) {
                    hideConfiguredItems(param.args?.getOrNull(0))
                }
            })
            true
        }.getOrElse {
            hookedMethods.remove(method)
            HLog.e("$TAG 安装聊天长按菜单 Hook 失败: ${method.toGenericString()}", it)
            false
        }
    }

    private fun hideConfiguredItems(menu: Any?) {
        if (menu == null || !prefs.getBoolean(HideChatMenuSettings.KEY_ENABLE, HideChatMenuSettings.DEFAULT_ENABLE)) {
            return
        }
        val hiddenTitles = HideChatMenuSettings.parseTitles(
            prefs.getString(HideChatMenuSettings.KEY_TITLES, HideChatMenuSettings.DEFAULT_TITLES)
        )
        if (hiddenTitles.isEmpty()) return
        val size = (KavaReflector.invokeMethod(menu, "size") as? Number)?.toInt() ?: return
        val hiddenItemIds = LinkedHashSet<Int>()
        for (index in 0 until size) {
            val item = KavaReflector.invokeMethod(menu, "getItem", index) as? MenuItem ?: continue
            val title = item.title?.toString()?.trim().orEmpty()
            if (title in hiddenTitles) {
                hiddenItemIds += item.itemId
            }
        }
        hiddenItemIds.forEach { itemId ->
            KavaReflector.invokeMethod(menu, "removeItem", itemId)
        }
    }

    companion object {
        private const val TAG = "[AeonLab:HideChatMenu]"
    }
}
