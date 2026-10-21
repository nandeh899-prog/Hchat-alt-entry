package com.aeonlab.toolkit.hooks.items.textspeech

import android.app.Activity
import android.view.KeyEvent
import de.robv.android.xposed.XC_MethodHook
import com.aeonlab.toolkit.event.Events
import com.aeonlab.toolkit.hooks.api.core.WeChatApis
import com.aeonlab.toolkit.hooks.core.BaseFeature
import com.aeonlab.toolkit.hooks.core.FeatureContext
import com.aeonlab.toolkit.hooks.core.HookRegistry
import com.aeonlab.toolkit.utils.HLog
import com.aeonlab.toolkit.utils.KavaReflector

class TextSpeechFeature : BaseFeature() {
    private var runtime: TextSpeechRuntime? = null

    override fun featureId(): String = ID

    override fun name(): String = "文字转语音播报"

    override fun onFeatureInit(context: FeatureContext) {
        registerSettingsProvider(TextSpeechSettingsProvider())
    }

    override fun onFeatureInstall(context: FeatureContext) {
        runtime = TextSpeechRuntime(context.hostContext())
        installVolumeKeyHook()
        subscribe(Events.DexReady::class.java) {
            val observe = WeChatApis.message().observe()
            if (observe == null) {
                HLog.e("$TAG 消息观察 API 未就绪")
                return@subscribe
            }
            trackSubscription(observe.subscribe { message -> runtime?.handleMessage(message) })
        }
    }

    override fun onFeatureDestroy(context: FeatureContext) {
        runtime?.shutdown()
        runtime = null
    }

    private fun installVolumeKeyHook() {
        val method = KavaReflector.findDeclaredMethod(
            Activity::class.java,
            "dispatchKeyEvent",
            KeyEvent::class.java
        )
        if (method == null) {
            HLog.e("$TAG 未找到 Activity.dispatchKeyEvent")
            return
        }
        runCatching {
            HookRegistry.get().hook(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val event = param.args.getOrNull(0) as? KeyEvent ?: return
                    if (runtime?.handleVolumeKey(event) == true) param.result = true
                }
            })
        }.onFailure {
            HLog.e("$TAG 音量键 Hook 安装失败: ${it.message}", it)
        }
    }

    companion object {
        const val ID = "text_speech"
        private const val TAG = "[Hchat:TextSpeech]"
    }
}
