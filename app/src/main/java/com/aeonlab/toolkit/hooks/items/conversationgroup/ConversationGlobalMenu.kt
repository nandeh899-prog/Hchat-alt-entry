package com.aeonlab.toolkit.hooks.items.conversationgroup

import android.app.Activity
import android.content.Context
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import com.aeonlab.toolkit.hooks.api.core.WeChatApis
import com.aeonlab.toolkit.hooks.api.ui.AeonLabMenuDotDrawable
import com.aeonlab.toolkit.ui.miuix.VoiceForwardMiuixDialog
import com.aeonlab.toolkit.utils.HLog
import java.lang.reflect.Method

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
 * 挂载方式不写死签名（1049 实测 `LauncherUI` 上既没有 `addIconOptionMenu*`，
 * 也没有 `addTextOptionMenu(int,String,listener)`），改成：
 * 扫描宿主真实的 `*OptionMenu*` 方法 → Activity 与首页 Fragment 逐个试 → 签名全部写进日志。
 */
internal object ConversationGlobalMenu {

    private const val TAG = "[AeonLab:ConversationGroup]"

    /** 新入口的 itemId（避开 0x48434752 会话长按 / 0x4843474D 分组页菜单 / 0x48434654 朋友圈过滤）。 */
    const val MENU_ITEM_ID = 0x48434754

    private const val MENU_TEXT = "菜单"

    private val main = Handler(Looper.getMainLooper())

    private val inventoryCache = HashMap<Class<*>, List<Method>>()

    private val loggedKeys = HashSet<String>()

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
     * 幂等挂载：先摘掉可能残留的旧菜单项，再按「图标 → 文字」、按「Activity → 首页 Fragment」逐个试。
     * 每次 onCreate / onResume / onNewIntent 都会调（LauncherUI 是四 Tab 共用容器，ActionBar 会重建）。
     */
    fun ensureTopEntry(activity: Activity) {
        if (activity.isFinishing || activity.isDestroyed) return
        install(activity, attempt = 1)
    }

    private fun install(activity: Activity, attempt: Int) {
        if (activity.isFinishing || activity.isDestroyed) return
        val targets = menuTargets(activity)
        targets.forEach { detachMenu(it) }
        val listener = MenuItem.OnMenuItemClickListener {
            runCatching { show(activity) }
                .onFailure { HLog.e("$TAG 打开全局批量操作菜单失败: ${it.message}", it) }
            true
        }
        for (wantIcon in listOf(true, false)) {
            for (target in targets) {
                val method = pickMenuMethod(target, wantIcon) ?: continue
                if (invokeMenuAdd(target, method, listener)) {
                    HLog.i(
                        "$TAG 首页顶部入口已挂载(${if (wantIcon) "图标" else "文字"}) " +
                            "target=${target.javaClass.simpleName} method=${method.name}"
                    )
                    refreshMenu(target)
                    return
                }
            }
        }
        if (attempt < 4) {
            // onCreate 阶段 ActionBar / Fragment 可能还没建好，隔一帧再试。
            main.postDelayed({ install(activity, attempt + 1) }, 400L * attempt)
        } else {
            HLog.e(
                "$TAG 首页顶部菜单入口挂载失败: ${activity.javaClass.name} " +
                    "targets=${targets.joinToString("|") { it.javaClass.name }}",
                RuntimeException("installTopEntry")
            )
        }
    }

    /** 候选宿主：Activity 本身 + 当前首页 Fragment（ActionBar 实际常挂在 Fragment 上）。 */
    private fun menuTargets(activity: Activity): List<Any> {
        val targets = mutableListOf<Any>(activity)
        runCatching {
            val homeFragment = homeFragment(activity)
            if (homeFragment != null) {
                targets += homeFragment
            } else if (loggedKeys.add("nofrag:" + activity.javaClass.name)) {
                HLog.i("$TAG 没找到首页 Fragment，只在 ${activity.javaClass.simpleName} 上挂")
            }
        }.onFailure { HLog.e("$TAG 枚举首页 fragment 失败: ${it.message}", it) }
        return targets
    }

    private fun homeFragment(activity: Activity): Any? {
        val list = findConversationListView(activity.window?.decorView) ?: return null
        val fragments = fragmentsOf(activity)
        if (loggedKeys.add("frags")) {
            HLog.i(
                "$TAG 首页容器 ${activity.javaClass.simpleName} 的 fragment=" +
                    fragments.joinToString("|") { it.javaClass.name }
            )
        }
        fragments.forEach { fragment ->
            val root = runCatching {
                fragment.javaClass.getMethod("getView").invoke(fragment) as? View
            }.getOrNull() ?: return@forEach
            if (isDescendant(root, list)) return fragment
        }
        return null
    }

    private fun fragmentsOf(activity: Activity): List<Any> {
        return runCatching {
            var current: Class<*>? = activity.javaClass
            var manager: Any? = null
            while (current != null && manager == null) {
                val getter = current.declaredMethods.firstOrNull {
                    it.name == "getSupportFragmentManager" && it.parameterTypes.isEmpty()
                }
                if (getter != null) {
                    manager = runCatching {
                        getter.isAccessible = true
                        getter.invoke(activity)
                    }.getOrNull()
                }
                current = current.superclass
            }
            val fragments = manager?.javaClass?.methods
                ?.firstOrNull { it.name == "getFragments" && it.parameterTypes.isEmpty() }
                ?.invoke(manager) as? List<*>
            fragments.orEmpty().filterNotNull()
        }.getOrDefault(emptyList())
    }

    private fun findConversationListView(root: View?): View? {
        val view = root ?: return null
        if (view is android.widget.ListView &&
            view.javaClass.simpleName.contains("Conversation", ignoreCase = true)
        ) {
            return view
        }
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                val found = findConversationListView(view.getChildAt(index))
                if (found != null) return found
            }
        }
        return null
    }

    private fun isDescendant(root: View, target: View): Boolean {
        var current: View? = target
        var depth = 0
        while (current != null && depth < 80) {
            if (current === root) return true
            current = current.parent as? View
            depth++
        }
        return false
    }

    // ------------------------------------------------------------------ 反射调用

    /** 宿主上所有 `*OptionMenu*` 方法，按类缓存，只打印一次签名。 */
    private fun menuMethods(target: Any): List<Method> {
        val clazz = target.javaClass
        inventoryCache[clazz]?.let { return it }
        val methods = clazz.methods.filter { it.name.contains("OptionMenu", ignoreCase = true) }
        inventoryCache[clazz] = methods
        if (loggedKeys.add("inv:" + clazz.name)) {
            if (methods.isEmpty()) {
                HLog.i("$TAG ${clazz.name} 没有任何 *OptionMenu* 方法")
            } else {
                methods.forEach { method ->
                    HLog.i(
                        "$TAG ${clazz.name} menu方法: ${method.name}(" +
                            method.parameterTypes.joinToString(",") { it.simpleName } + ")"
                    )
                }
            }
        }
        return methods
    }

    /**
     * 选方法：图标优先要 Drawable 形参；文字要 String 形参。
     * 两者都要求「参数里有 int（menuId）+ 最后一个参数是 OnMenuItemClickListener」，
     * 其余参数（颜色、boolean、描述等）由 [buildArgs] 按类型兜底填。
     */
    private fun pickMenuMethod(target: Any, wantIcon: Boolean): Method? {
        val methods = menuMethods(target)
        return methods.firstOrNull { method ->
            val types = method.parameterTypes
            types.isNotEmpty() &&
                types.any { it == Integer.TYPE } &&
                types.last() == MenuItem.OnMenuItemClickListener::class.java &&
                if (wantIcon) {
                    types.any { Drawable::class.java.isAssignableFrom(it) }
                } else {
                    types.any { it == String::class.java }
                }
        }
    }

    private fun invokeMenuAdd(
        target: Any,
        method: Method,
        listener: MenuItem.OnMenuItemClickListener
    ): Boolean {
        return runCatching {
            val args = buildArgs(method.parameterTypes, AeonLabMenuDotDrawable(), listener)
            method.isAccessible = true
            method.invoke(target, *args)
            true
        }.getOrElse {
            HLog.e(
                "$TAG 调用 ${target.javaClass.simpleName}.${method.name} 失败: ${it.message}",
                it
            )
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
                type == java.lang.Boolean.TYPE -> false
                else -> null
            }
        }
    }

    private fun detachMenu(target: Any) {
        runCatching {
            val remove = menuMethods(target).firstOrNull { method ->
                method.name.startsWith("remove") && method.parameterTypes.size == 1 &&
                    method.parameterTypes[0] == Integer.TYPE
            } ?: return
            remove.isAccessible = true
            remove.invoke(target, MENU_ITEM_ID)
        }
    }

    /** 加完菜单项通知 ActionBar 重排，否则有些页面要等下次 invalidate 才显示。 */
    private fun refreshMenu(target: Any) {
        listOf("supportInvalidateOptionsMenu", "invalidateOptionsMenu").forEach { name ->
            runCatching {
                val method = target.javaClass.methods.firstOrNull {
                    it.name == name && it.parameterTypes.isEmpty()
                } ?: return@runCatching
                method.invoke(target)
            }
        }
    }
}