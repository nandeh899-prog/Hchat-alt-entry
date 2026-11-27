package com.aeonlab.toolkit.hooks.items.conversationgroup

import android.app.Activity
import android.content.Context
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.view.MenuItem
import com.aeonlab.toolkit.hooks.api.core.WeChatApis
import com.aeonlab.toolkit.hooks.api.ui.AeonLabMenuDotDrawable
import com.aeonlab.toolkit.ui.miuix.VoiceForwardMiuixDialog
import com.aeonlab.toolkit.utils.HLog

/**
 * 首页顶部的「全局批量操作」入口。
 *
 * 和分组页右上角那个菜单的区别：
 * - 分组页菜单里的「添加 / 移出 / 移至 / 搜索 / 设置 / 发送群聊邀请」都依赖某一个具体分组，
 *   全局化以后语义就不成立了，所以**一项都不搬**，原样留在分组页。
 * - 这里只放「跟分组无关、任何会话集合都能跑」的批量动作：
 *   标已读 / 删消息 / 屏蔽消息 / 免打扰 / 解除免打扰 / 发送 / 归组。
 * - 「AeonLab模块」入口也不搬：＋ 号菜单里已经有一份。
 *
 * 入口是幂等的：LauncherUI 是四个 Tab 共用的容器，ActionBar 可能被重建，
 * 所以每次 onResume / onNewIntent 都会先摘掉再挂一次（见 [ensureTopEntry]）。
 */
internal object ConversationGlobalMenu {

    private const val TAG = "[AeonLab:ConversationGroup]"

    /** 新入口的 itemId（避开 0x48434752 会话长按 / 0x4843474D 分组页菜单 / 0x48434654 朋友圈过滤）。 */
    const val MENU_ITEM_ID = 0x48434754

    private const val MENU_TEXT = "菜单"

    private val main = Handler(Looper.getMainLooper())

    private val loggedClasses = HashSet<String>()

    private val refreshHome: () -> Unit = {
        runCatching { ConversationGroupHomeProjection.refresh() }
        Unit
    }

    // ------------------------------------------------------------------ 菜单内容

    private const val KEY_MARK_READ = "mark_read"
    private const val KEY_CLEAR = "clear_messages"
    private const val KEY_BLOCK = "block_messages"
    private const val KEY_DND_ON = "dnd_on"
    private const val KEY_DND_OFF = "dnd_off"
    private const val KEY_SEND = "send"
    private const val KEY_REGROUP = "regroup"

    private val ENTRIES: List<Triple<String, String, String>> = listOf(
        Triple(KEY_MARK_READ, "所有消息标为已读", "标记全部会话（含已收进分组的会话）"),
        Triple(KEY_CLEAR, "批量删除消息", "选择会话并清空聊天记录"),
        Triple(KEY_BLOCK, "批量屏蔽消息", "选择会话并屏蔽后续收到的消息"),
        Triple(KEY_DND_ON, "消息免打扰", "开启全部会话的微信免打扰"),
        Triple(KEY_DND_OFF, "解除消息免打扰", "关闭全部会话的微信免打扰"),
        Triple(KEY_SEND, "发送", "批量发送文字、媒体、收藏、名片或 XML"),
        Triple(KEY_REGROUP, "批量归组", "选择会话并归入指定分组")
    )

    fun show(activity: Activity) {
        val ids = allConversationIds(activity)
        if (ids.isEmpty()) {
            ConversationGroupMenu.toast(activity, "没有可处理的会话")
            return
        }
        VoiceForwardMiuixDialog.showListChoices(
            activity = activity,
            title = "全局批量操作",
            summary = "全部会话（含已收进分组的会话）共 ${ids.size} 个",
            choices = ENTRIES.map { it.second to it.third },
            onSelected = { index ->
                // 按稳定 key 分发：以后增删项不会像以前那样整列错位。
                when (ENTRIES.getOrNull(index)?.first) {
                    KEY_MARK_READ -> ConversationGroupMenu.markIdsRead(activity, ids, refreshHome)
                    KEY_CLEAR -> ConversationGroupMenu.clearMessagesOf(activity, ids, refreshHome)
                    KEY_BLOCK -> ConversationGroupMenu.blockActionFor(activity, ids)
                    KEY_DND_ON ->
                        ConversationGroupMenu.setDoNotDisturbFor(activity, ids, true, refreshHome)
                    KEY_DND_OFF ->
                        ConversationGroupMenu.setDoNotDisturbFor(activity, ids, false, refreshHome)
                    KEY_SEND -> ConversationGroupMenu.sendTo(activity, ids)
                    KEY_REGROUP -> regroup(activity, ids)
                    else -> Unit
                }
            },
            onDismiss = {}
        )
    }

    /**
     * 批量归组：全局唯一需要分组语义的动作，但它是「选会话 → 选目标分组」，
     * 不依赖当前在哪个分组里，所以可以放在全局入口。
     */
    private fun regroup(activity: Activity, ids: List<String>) {
        val groups = ConversationGroupStore.load(activity)
        if (groups.isEmpty()) {
            ConversationGroupMenu.toast(activity, "还没有创建分组")
            return
        }
        ConversationGroupMenu.showConversationPicker(
            activity = activity,
            ids = ids,
            title = "选择要归组的会话",
            confirmText = "下一步"
        ) { selected ->
            val chosen = selected.map { it.id }
            if (chosen.isEmpty()) return@showConversationPicker
            val destinations: List<Pair<String?, String>> =
                listOf(null to "微信首页") + groups.map { it.id to it.name }
            VoiceForwardMiuixDialog.showListChoices(
                activity = activity,
                title = "归组到",
                summary = "已选择 ${chosen.size} 个会话",
                choices = destinations.map { (_, name) -> name to "" },
                onSelected = { index ->
                    val targetId = destinations.getOrNull(index)?.first
                    val success = ConversationGroupStore.setConversationGroups(
                        activity,
                        chosen,
                        targetId
                    )
                    ConversationGroupMenu.toast(
                        activity,
                        if (success) "已归组 ${chosen.size} 个会话" else "归组失败"
                    )
                    if (success) refreshHome()
                },
                onDismiss = {}
            )
        }
    }

    /** 全局会话集合：直接取微信最近会话列表，不看分组快照（快照可能过期）。 */
    private fun allConversationIds(context: Context): List<String> {
        return WeChatApis.conversations()
            ?.getRecentConversationUsernames(10000)
            .orEmpty()
            .map(String::trim)
            .filter { it.isNotEmpty() && !ConversationGroupRuntime.isVirtualTalker(it) }
            .distinct()
    }

    // ------------------------------------------------------------------ 入口挂载

    /**
     * 幂等挂载：先摘掉可能残留的旧菜单项，再尝试添加。
     * 图标路（addIconOptionMenu + 自绘 Drawable）优先，宿主没这签名就退回文字「菜单」。
     */
    fun ensureTopEntry(activity: Activity) {
        if (activity.isFinishing || activity.isDestroyed) return
        install(activity, attempt = 1)
    }

    private fun install(activity: Activity, attempt: Int) {
        if (activity.isFinishing || activity.isDestroyed) return
        detach(activity)
        val listener = MenuItem.OnMenuItemClickListener {
            runCatching { show(activity) }
                .onFailure { HLog.e("$TAG 打开全局批量操作菜单失败: ${it.message}", it) }
            true
        }
        if (installIconEntry(activity, listener)) {
            HLog.i("$TAG 首页顶部图标入口已挂载: ${activity.javaClass.simpleName}")
            return
        }
        if (installTextEntry(activity, listener)) {
            HLog.i("$TAG 首页顶部图标入口不可用，已回退文字入口「$MENU_TEXT」")
            return
        }
        if (attempt < 3) {
            // onCreate 阶段 ActionBar 可能还没建好，隔一帧再试。
            main.postDelayed({ install(activity, attempt + 1) }, 400L * attempt)
        } else {
            HLog.e(
                "$TAG 首页顶部菜单入口挂载失败（图标与文字都失败）: ${activity.javaClass.name}",
                RuntimeException("installTopEntry")
            )
        }
    }

    private fun detach(activity: Activity) {
        runCatching {
            val remove = activity.javaClass.methods.firstOrNull { method ->
                method.name == "removeOptionMenu" && method.parameterTypes.size == 1 &&
                    (method.parameterTypes[0] == Integer.TYPE ||
                        method.parameterTypes[0] == Integer::class.java)
            } ?: return
            remove.invoke(activity, MENU_ITEM_ID)
        }
    }

    private fun installIconEntry(
        activity: Activity,
        listener: MenuItem.OnMenuItemClickListener
    ): Boolean {
        val methods = activity.javaClass.methods.filter { it.name.startsWith("addIconOptionMenu") }
        logCandidatesOnce(activity, methods)
        val pick = methods.firstOrNull { method ->
            val types = method.parameterTypes
            types.isNotEmpty() &&
                types.last() == MenuItem.OnMenuItemClickListener::class.java &&
                types.any { Drawable::class.java.isAssignableFrom(it) }
        } ?: return false
        val args = buildArgs(pick.parameterTypes, AeonLabMenuDotDrawable(), listener)
        return runCatching {
            pick.invoke(activity, *args)
            true
        }.getOrElse {
            HLog.e("$TAG 调用 ${pick.name} 失败: ${it.message}", it)
            false
        }
    }

    private fun buildArgs(
        types: Array<Class<*>>,
        drawable: Drawable,
        listener: MenuItem.OnMenuItemClickListener
    ): Array<Any?> {
        var idAssigned = false
        return Array(types.size) { index ->
            val type = types[index]
            when {
                MenuItem.OnMenuItemClickListener::class.java.isAssignableFrom(type) -> listener
                Drawable::class.java.isAssignableFrom(type) -> drawable
                type == String::class.java || type == CharSequence::class.java -> MENU_TEXT
                type == Integer.TYPE || type == Integer::class.java -> {
                    if (idAssigned) 0 else {
                        idAssigned = true
                        MENU_ITEM_ID
                    }
                }
                type == Boolean.TYPE -> false
                else -> null
            }
        }
    }

    private fun installTextEntry(
        activity: Activity,
        listener: MenuItem.OnMenuItemClickListener
    ): Boolean {
        val add = activity.javaClass.methods.firstOrNull { method ->
            method.name == "addTextOptionMenu" &&
                method.parameterTypes.size == 3 &&
                method.parameterTypes[0] == Integer.TYPE &&
                method.parameterTypes[1] == String::class.java &&
                method.parameterTypes[2] == MenuItem.OnMenuItemClickListener::class.java
        } ?: return false
        return runCatching {
            add.invoke(activity, MENU_ITEM_ID, MENU_TEXT, listener)
            true
        }.getOrElse {
            HLog.e("$TAG 调用 addTextOptionMenu 失败: ${it.message}", it)
            false
        }
    }

    /**
     * 只打印一次宿主上所有 `addIconOptionMenu*` 签名。真机 8.0.49 的签名是不可预知的
     * （可能是 `(int,int,listener)`，也可能是 `(int,Drawable,listener)`），
     * 打出来以后就不用再猜，也方便下一版直接按最优签名精确调用。
     */
    private fun logCandidatesOnce(activity: Activity, methods: List<java.lang.reflect.Method>) {
        val className = activity.javaClass.name
        if (!loggedClasses.add(className)) return
        if (methods.isEmpty()) {
            HLog.i("$TAG $className 上没有 addIconOptionMenu*，走文字入口")
            return
        }
        methods.forEach { method ->
            HLog.i("$TAG addIconOptionMenu候选: ${method.name}(${method.parameterTypes.joinToString(",") { it.simpleName }})")
        }
    }
}