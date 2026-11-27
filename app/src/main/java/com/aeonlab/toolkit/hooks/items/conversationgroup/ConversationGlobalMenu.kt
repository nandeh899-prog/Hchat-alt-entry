package com.aeonlab.toolkit.hooks.items.conversationgroup

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.view.Gravity
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.LinearLayout
import android.widget.TextView
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

    private const val MENU_TEXT = "A"

    /**
     * 是否用自绘图标挂入口。
     * 1050 / 1051 实测：在首页 `MainUI` 上用 `addIconOptionMenu(...)` 加项，
     * 标题栏会在原有「搜索 🔍 + ＋」之间多渲染一个搜索图标（两种带 Drawable 的重载都试过），
     * 属于宿主 ActionBar 内部行为，不值得我们为了图标去和它较劲。
     * 先固定用文字「菜单」——位置稳定、语义明确，也不额外占用标题栏绘图资源。
     */
    private const val USE_ICON_ENTRY = false

    /**
     * 是否还允许退回「宿主菜单项」路径。
     *
     * 1052~1055 实测：走 `addTextOptionMenu` 时微信会额外渲染一个搜索图标和一个 ＋，
     * 标题栏会变成两个 🔍 两个 ＋。现在已改成自己往标题栏容器插 View，
     * 所以这条兜底默认**关闭**：宁可不出现，也不要污染标题栏。
     */
    private const val ALLOW_LEGACY_FALLBACK = false

    private val main = Handler(Looper.getMainLooper())

    private val inventoryCache = HashMap<Class<*>, List<Method>>()
    private var lastEnsureAt = 0L

    /** 只跑一条挂载重试链，避免切页面时叠出多条 500ms 轮询。 */
    private var installInFlight = false

    /** 我们插进去那个 A 的弱引用，守护靠它做 O(1) 判断。 */
    private var ownEntryRef: java.lang.ref.WeakReference<View>? = null

    /** 覆盖层入口（挂在 decorView 上，不随 ActionBar 重建）。 */
    private var overlayEntry: java.lang.ref.WeakReference<TextView>? = null

    /** 覆盖层入口的定位锚点缓存（标题栏右侧 ＋ 号 / 搜索）。 */
    private var overlayAnchor: java.lang.ref.WeakReference<View>? = null

    /** 幽灵图标扫描的节流标志。 */
    private var ghostScanPosted = false

    /** 已经挂了入口守护的 Activity / decorView（重建后自动换新的）。 */
    private var entryGuardActivity: java.lang.ref.WeakReference<Activity>? = null
    private var entryGuardRoot: java.lang.ref.WeakReference<View>? = null
    private var lastGuardMountAt = 0L
    private var guardRemountPosted = false

    private val loggedKeys = HashSet<String>()

    private val refreshHome: () -> Unit = {
        runCatching { ConversationGroupHomeProjection.refresh() }
        Unit
    }

    /**
     * 批量动作之后的统一刷新入口。
     *
     * 分组内的行由我们自己投影绘制（[refreshHome] 就够），但「没被收进分组」的行
     * 是微信原生 adapter 画的，必须让它自己 `notifyDataSetChanged()` 一次，
     * 否则会出现「分组内的免打扰图标会变、分组外的不变」。
     */
    private fun refreshAllFor(activity: Activity): () -> Unit = {
        scheduleHomeRefresh(activity)
    }

    /**
     * 多轮延迟刷新。
     *
     * 非要延迟好几轮的原因：群聊免打扰走的是 RoomSDK 异步提交，私聊走联系人更新，
     * 两条路都是「先落库、再回调」，我们调用返回时微信自己的会话数据往往还没变。
     * 只刷一次会得到一个中间态——分组内的行（我们自己读状态）已经变了，
     * 未分组的原生行（微信 adapter 读自己的数据）还是旧图标。
     */
    private fun scheduleHomeRefresh(activity: Activity) {
        refreshHome()
        notifyHomeList(activity)
        listOf(300L, 900L, 2000L, 3500L).forEach { delay ->
            main.postDelayed({
                if (activity.isFinishing || activity.isDestroyed) return@postDelayed
                refreshHome()
                notifyHomeList(activity)
            }, delay)
        }
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
        Triple(KEY_MARK_READ, "所有消息标为已读", "先选择会话，再批量标记已读"),
        Triple(KEY_CLEAR, "批量删除消息", "选择会话并清空聊天记录"),
        Triple(KEY_BLOCK, "批量屏蔽消息", "选择会话并屏蔽后续收到的消息"),
        Triple(KEY_DND_ON, "消息免打扰", "先选择会话，再批量开启微信免打扰"),
        Triple(KEY_DND_OFF, "解除消息免打扰", "先选择会话，再批量关闭微信免打扰"),
        Triple(KEY_SEND, "发送", "先选择会话，再批量发送文字/媒体/收藏/名片/XML"),
        Triple(KEY_REGROUP, "批量归组", "选择会话并归入指定分组")
    )

    fun show(activity: Activity) {
        val ids = allConversationIds(activity)
        if (ids.isEmpty()) {
            ConversationGroupMenu.toast(activity, "没有可处理的会话")
            return
        }
        // 批量动作改的是微信自己的免打扰 / 已读状态，分组里的行是我们在刷新，
        // 但「没被收进分组」的会话行属于微信原生 adapter，必须让它自己 notify 一次，
        // 否则就是「分组内图标会变、分组外不变」。
        val refreshAll: () -> Unit = refreshAllFor(activity)
        VoiceForwardMiuixDialog.showListChoices(
            activity = activity,
            title = "全局批量操作",
            summary = "全部会话（含已收进分组的会话）共 ${ids.size} 个",
            choices = ENTRIES.map { it.second to it.third },
            onSelected = { index ->
                // 按稳定 key 分发：以后增删项不会像以前那样整列错位。
                when (ENTRIES.getOrNull(index)?.first) {
                    // 标已读 / 免打扰 / 发送 都先选目标会话（和批量删除的交互保持一致，
                    // 避免“一按就作用于全部会话”，尤其发送是不可撤销的）。
                    KEY_MARK_READ -> pickTargets(activity, ids, "所有消息标为已读") { chosen ->
                        ConversationGroupMenu.markIdsRead(activity, chosen, refreshAll)
                    }
                    KEY_CLEAR -> ConversationGroupMenu.clearMessagesOf(activity, ids, refreshAll)
                    KEY_BLOCK -> ConversationGroupMenu.blockActionFor(activity, ids)
                    KEY_DND_ON -> pickDoNotDisturbTargets(activity, ids, enabled = true)
                    KEY_DND_OFF -> pickDoNotDisturbTargets(activity, ids, enabled = false)
                    KEY_SEND -> pickTargets(activity, ids, "发送") { chosen ->
                        ConversationGroupMenu.sendTo(activity, chosen)
                    }
                    KEY_REGROUP -> regroup(activity, ids)
                    else -> Unit
                }
            },
            onDismiss = {}
        )
    }

    /**
     * 免打扰 / 解除免打扰：先把「当前已经是该状态」的会话勾上，
     * 这样用户一眼能看见现在哪些是免打扰（解除时不用一个个试），
     * 也能直接沿用勾选做增量操作。状态读取放在后台线程，避免几百个会话卡主线程。
     */
    private fun pickDoNotDisturbTargets(activity: Activity, ids: List<String>, enabled: Boolean) {
        val title = if (enabled) "消息免打扰" else "解除消息免打扰"
        ConversationGroupTask.launch(
            activity = activity,
            title = title,
            message = "正在读取免打扰状态...",
            task = { _ ->
                val api = WeChatApis.conversations()
                ids.filter { talker ->
                    runCatching { api?.isWechatDoNotDisturb(talker) == true }.getOrDefault(false)
                }.toSet()
            },
            onComplete = { result ->
                val selected = result.getOrElse {
                    HLog.e("$TAG 读取免打扰状态失败: ${it.message}", it)
                    emptySet()
                }
                if (!enabled && selected.isEmpty()) {
                    ConversationGroupMenu.toast(activity, "没有已开启免打扰的会话")
                    return@launch
                }
                ConversationGroupMenu.showConversationPicker(
                    activity = activity,
                    ids = ids,
                    title = title,
                    confirmText = "下一步",
                    initialSelectedIds = selected
                ) { picked ->
                    val chosen = picked.map { it.id }
                    if (chosen.isEmpty()) return@showConversationPicker
                    ConversationGroupMenu.setDoNotDisturbFor(activity, chosen, enabled, refreshAllFor(activity))
                }
            }
        )
    }

    /** 统一的多选会话入口：确认后把选中的 talker 集合交给 [action]。 */
    private fun pickTargets(
        activity: Activity,
        ids: List<String>,
        title: String,
        action: (List<String>) -> Unit
    ) {
        ConversationGroupMenu.showConversationPicker(
            activity = activity,
            ids = ids,
            title = title,
            confirmText = "下一步"
        ) { selected ->
            val chosen = selected.map { it.id }
            if (chosen.isEmpty()) return@showConversationPicker
            action(chosen)
        }
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

    /** 是不是微信首页那个 Activity（`LauncherUI`，四 Tab 共用容器）。 */
    private fun isLauncherActivity(activity: Activity): Boolean =
        activity.javaClass.name.endsWith(".ui.LauncherUI")

    /**
     * 幂等挂载：先摘掉可能残留的旧菜单项，再按「图标 → 文字」、按「Activity → 首页 Fragment」逐个试。
     * 每次 onCreate / onResume / onNewIntent 都会调（LauncherUI 是四 Tab 共用容器，ActionBar 会重建）。
     */
    fun ensureTopEntry(activity: Activity) {
        if (activity.isFinishing || activity.isDestroyed) return
        // 只认微信首页那个 Activity。ConversationGroupRuntime 会在各种 Activity 上回调这里，
        // 之前把入口在多个 decorView 之间搬来搬去，既闪又有可能落到聊天页/群资料页的标题栏上。
        if (!isLauncherActivity(activity)) return
        // 先挂「绘制前守护」：它不依赖任何生命周期回调，切 Tab / 进聊天再返回时也能把 A 补回来。
        installEntryGuard(activity)
        val now = System.currentTimeMillis()
        if (now - lastEnsureAt < 300L) return
        lastEnsureAt = now
        if (loggedKeys.add("ensure:" + Process.myPid())) {
            HLog.i("$TAG 开始挂载首页顶部入口 pid=${Process.myPid()}")
        }
        // 列表头部那条假标题栏会在重建头部行时把两个图标重新显示出来，
        // 所以每次回到首页（onResume / onWindowFocusChanged / 点击）都顺手压一次。
        hideGhostTitleIcons(activity)
        install(activity, attempt = 1)
    }

    /**
     * 绘制前守护：保证「A」在任何页面切换后都能自己回来。
     *
     * 实测（1062）：`LauncherUI` 是四个 Tab 共用的容器，切 Tab 时 ActionBar 会重建，
     * 我们插进去的 View 随旧容器一起被丢弃；而这种切换**不会**触发 onCreate / onResume /
     * onPostResume，所以此前只能靠「切后台再回来」才有生命周期事件把入口补上。
     *
     * 挂在 `decorView` 的 `OnPreDrawListener` 上就不依赖任何具体回调：每帧只做一次
     * O(1) 判断（弱引用+l已挂载状态），发现入口不在就节流重挂。
     */
    private fun installEntryGuard(activity: Activity) {
        if (activity.isFinishing || activity.isDestroyed) return
        val root: View = activity.window?.decorView ?: return
        if (entryGuardActivity?.get() === activity && entryGuardRoot?.get() === root) return
        entryGuardActivity = java.lang.ref.WeakReference(activity)
        entryGuardRoot = java.lang.ref.WeakReference(root)
        val observer = root.viewTreeObserver ?: return
        observer.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                val current = entryGuardActivity?.get()
                val host = entryGuardRoot?.get() as? ViewGroup
                if (current != null && host != null) {
                    val entry = overlayEntry?.get()
                    if (entry != null && entry.parent === host) {
                        // 入口就在覆盖层上：只更新位置，不重建、不移除，切页面时完全无闪动。
                        positionOverlayEntry(current, host, entry)
                    } else if (!guardRemountPosted && homeListVisible()) {
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastGuardMountAt >= 150L) {
                            lastGuardMountAt = now
                            guardRemountPosted = true
                            main.post {
                                guardRemountPosted = false
                                val act = entryGuardActivity?.get() ?: return@post
                                if (act.isFinishing || act.isDestroyed) return@post
                                runCatching { ensureTopEntry(act) }
                                    .onFailure { HLog.e("$TAG 守护补挂入口失败: ${it.message}", it) }
                            }
                        }
                    }
                    // 幽灵图标：宿主一旦把 meb / plus_icon 重新设成可见，就在**同一帧绘制前**压回去。
                    // 以前要等下一轮 hideGhostTitleIcons（150ms~500ms 后），中间那几帧就是用户
                    // 看到的「搜索 ＋」露出来又被压掉——也就是切换页面时的闪动。
                    suppressGhostIcons()
                }
                return true
            }
        })
        HLog.i("$TAG 入口绘制前守护已挂上 root=${root.javaClass.simpleName}")
    }

    /** 绘制前把已被我们隐藏的幽灵图标再压一次（只遍历缓存，常量级开销）。 */
    private fun suppressGhostIcons() {
        if (ghostHiddenViews.isEmpty()) {
            if (!ghostScanPosted) {
                ghostScanPosted = true
                main.post {
                    ghostScanPosted = false
                    val act = entryGuardActivity?.get() ?: return@post
                    runCatching { hideGhostTitleIcons(act) }
                }
            }
            return
        }
        for (view in ghostHiddenViews.keys) {
            if (view.isAttachedToWindow && view.visibility != View.INVISIBLE) {
                view.visibility = View.INVISIBLE
            }
        }
    }

    private fun install(activity: Activity, attempt: Int) {
        if (activity.isFinishing || activity.isDestroyed) return
        // 已经挂好就什么都不做：多条轮询链（onCreate/onResume 各起一条）不会互相打架、也不会闪烁。
        if (hasOwnEntry(activity)) return
        // 同一时刻只允许一条重试链在跑，否则切页面时会叠出好几条 500ms 轮询、
        // 日志里全是「没找到可插入的标题栏容器」。
        if (!installInFlight) {
            installInFlight = true
        } else if (attempt > 1) {
            return
        }
        val targets = menuTargets(activity)
        targets.forEach { detachMenu(it) }
        // 首选：挂到 decorView 覆盖层上（生命周期与 LauncherUI 一致，切页面不重建）。
        if (mountOverlayEntry(activity)) {
            installInFlight = false
            dumpTopBarOnce(activity)
            return
        }
        // 退路：塞进标题栏容器（覆盖层挂不上时才会走到）。
        // 不走宿主的 addXxxOptionMenu，就不会出现「iconRes=0 被宿主渲染成默认图标」这类内部行为。
        if (mountOwnEntry(activity)) {
            installInFlight = false
            dumpTopBarOnce(activity)
            return
        }
        if (attempt < 40) {
            // 标题栏可能还没 inflate（实测开微信后要等一会儿、甚至滑动一下才建好）。
            // 慢轮询最多 20 秒，期间只走自绘路径，绝不碰宿主菜单项系统。
            // 头部行通常比标题栏更晚建好，所以每轮都重新扫一次幽灵图标。
            main.postDelayed({
                hideGhostTitleIcons(activity)
                install(activity, attempt + 1)
            }, 500L)
            return
        }
        if (!ALLOW_LEGACY_FALLBACK) {
            installInFlight = false
            dumpTopBarOnce(activity)
            HLog.e(
                "$TAG 首页顶部入口始终没找到标题栏容器: ${activity.javaClass.name}",
                RuntimeException("mountTopEntry")
            )
            return
        }
        val listener = MenuItem.OnMenuItemClickListener {
            runCatching { show(activity) }
                .onFailure { HLog.e("$TAG 打开全局批量操作菜单失败: ${it.message}", it) }
            true
        }
        for (wantIcon in if (USE_ICON_ENTRY) listOf(true, false) else listOf(false)) {
            for (target in targets) {
                val method = pickMenuMethod(target, wantIcon) ?: continue
                if (invokeMenuAdd(target, method, listener)) {
                    HLog.i(
                        "$TAG 首页顶部入口已挂载(${if (wantIcon) "图标" else "文字"}) " +
                            "target=${target.javaClass.simpleName} method=${method.name}(" +
                            method.parameterTypes.joinToString(",") { it.simpleName } + ")"
                    )
                    polishMenuAppearance(target, activity)
                    refreshMenu(target)
                    logMenuBar(activity, "after")
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

    // ------------------------------------------------------------ 自绘入口（不经宿主菜单系统）
    /** 我们插进标题栏那个 View 的 tag，用来在重建时精确定位并移除。 */
    private const val ENTRY_TAG = "aeonlab_global_menu_entry"

    /**
     * 会话 `ListView` 头部那条「假标题栏」里两个图标的资源 id 名。
     *
     * 实测（1059 + 无过滤 UI 树）：真 ActionBar 里的图标是 `ImageView id=h5n`（外层
     * `RelativeLayout id=jha/jga`），`meb` / `plus_icon` **只**出现在列表头部行，
     * 因此可以放心按名字找，再用「祖先含 ListView」把范围锁死在假标题栏上。
     */
    private val GHOST_ICON_IDS = arrayOf("meb", "plus_icon")

    /** 已经隐藏过的 View，避免每轮轮询都重复置位与刷日志。 */
    private val ghostHiddenViews = java.util.WeakHashMap<View, Boolean>()

    /** 已经挂了绘制前守护的列表（弱引用，列表随 Activity 重建时自动换新的）。 */
    private var ghostGuardList: java.lang.ref.WeakReference<View>? = null

    /** 防止「缓存全部失效」时刷屏式重扫。 */
    private var ghostRescanPosted = false

    /**
     * 自己往标题栏容器里塞一个纯文字 View。
     *
     * 好处：数量、外观、间距完全由我们决定，宿主不会在它的菜单项系统里额外渲染任何图标。
     * 只在宿主容器是 [LinearLayout] 时执行（微信 8.0.49 首页标题栏右侧就是横排容器），
     * 其他类型直接返回 false 交给反射路径，并把容器类名写进日志供下一次精确适配。
     */
    private fun mountOwnEntry(activity: Activity): Boolean {
        val host = findTitleContainer(activity)
        if (host == null) {
            HLog.i("$TAG 没找到可插入的标题栏容器，退回宿主菜单项路径")
            return false
        }
        if (host !is LinearLayout) {
            HLog.i("$TAG 标题栏容器是 ${host.javaClass.name}，暂不直接插入，退回宿主菜单项路径")
            return false
        }
        return runCatching {
            // 复用上一次插入的那个 View：ActionBar 重建后只是把它换到新容器里，
            // 不新建、不销毁，视觉上不会出现「消失→出现」的闪动。
            val root = activity.window?.decorView
            val reused = root?.let { findViewByTag(it, ENTRY_TAG) }
            val entry = reused ?: buildEntryView(activity)
            if (entry.parent === host) {
                ownEntryRef = java.lang.ref.WeakReference(entry)
                return true
            }
            (entry.parent as? ViewGroup)?.removeView(entry)
            val params = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            params.gravity = Gravity.CENTER_VERTICAL
            val before = host.childCount
            host.addView(entry, params)
            ownEntryRef = java.lang.ref.WeakReference(entry)
            HLog.i(
                "$TAG 标题栏自绘入口已挂载 host=${host.javaClass.simpleName} " +
                    "children=$before→${host.childCount} pid=${Process.myPid()}"
            )
            true
        }.getOrElse {
            HLog.e("$TAG 标题栏自绘入口挂载失败: ${it.message}", it)
            false
        }
    }

    /**
     * 把入口挂到 `decorView` 的**覆盖层**上（不放进 ActionBar 容器里）。
     *
     * 1066 之前的做法是塞进标题栏的横排容器：位置天然正确，但 `LauncherUI` 切页面时
     * ActionBar 会整体重建，我们那个 View 跟着旧容器一起被丢弃 —— 用户看到的就是
     * 「切页面闪一下」。挂到覆盖层以后，这个 View 的生命周期和 LauncherUI 一致，
     * 重建 ActionBar 与它无关，只需要每帧把位置对齐到 ＋ 号右侧即可。
     */
    private fun mountOverlayEntry(activity: Activity): Boolean {
        val host = activity.window?.decorView as? ViewGroup ?: return false
        return runCatching {
            val entry: TextView = overlayEntry?.get()
                ?: buildEntryView(activity).also { overlayEntry = java.lang.ref.WeakReference(it) }
            if (entry.parent !== host) {
                (entry.parent as? ViewGroup)?.removeView(entry)
                // 清掉可能残留的「容器插入版」旧入口，避免同时出现两个 A。
                findViewByTag(host, ENTRY_TAG)?.let { stale ->
                    if (stale !== entry) (stale.parent as? ViewGroup)?.removeView(stale)
                }
                host.addView(
                    entry,
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                )
                entry.bringToFront()
                HLog.i("$TAG 覆盖层入口已挂上 decorView pid=${Process.myPid()}")
            }
            ownEntryRef = java.lang.ref.WeakReference(entry)
            positionOverlayEntry(activity, host, entry)
            true
        }.getOrElse {
            HLog.e("$TAG 覆盖层入口挂载失败: ${it.message}", it)
            false
        }
    }

    /**
     * 每帧把覆盖层入口对齐到标题栏 ＋ 号的右侧空白处。
     *
     * 空间不够（例如通讯录页 ＋ 号就贴着屏幕右边缘）时把它设成 INVISIBLE ——
     * 既不会遮挡宿主的按钮，也不会触发任何重建，所以不会闪。
     */
    private fun positionOverlayEntry(activity: Activity, host: ViewGroup, entry: View) {
        val anchor = resolveOverlayAnchor(host)
        if (anchor == null) {
            if (entry.visibility != View.INVISIBLE) entry.visibility = View.INVISIBLE
            return
        }
        val height = anchor.height
        if (height <= 0) return
        entry.measure(
            View.MeasureSpec.makeMeasureSpec(host.width.coerceAtLeast(1), View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
        )
        val width = entry.measuredWidth
        val anchorLocation = IntArray(2)
        anchor.getLocationOnScreen(anchorLocation)
        val hostLocation = IntArray(2)
        host.getLocationOnScreen(hostLocation)
        // 注意：入口不再占用标题栏布局，微信会把 ＋ 号一路推到屏幕右边缘（实测 jga=[1091,1240]），
        // 右侧已经没有空位。所以贴「操作容器左侧」——也就是它以前插在容器里时占据的那一格。
        val container = (anchor.parent as? View) ?: anchor
        val containerLocation = IntArray(2)
        container.getLocationOnScreen(containerLocation)
        val containerLeft = containerLocation[0] - hostLocation[0]
        val containerTop = containerLocation[1] - hostLocation[1]
        val containerHeight = if (container.height > 0) container.height else height
        var x = containerLeft - width
        var y = containerTop
        var heightToUse = containerHeight
        val screenWidth = activity.resources.displayMetrics.widthPixels
        if (width <= 0 || x < 0) {
            // 容器贴着屏幕左边（理论上不会出现在首页），退回「贴 ＋ 号右侧」。
            x = anchorLocation[0] - hostLocation[0] + anchor.width
            y = anchorLocation[1] - hostLocation[1]
            heightToUse = height
        }
        if (width <= 0 || x < 0 || x + width > screenWidth) {
            if (entry.visibility != View.INVISIBLE) entry.visibility = View.INVISIBLE
            return
        }
        val params = entry.layoutParams
        if (params != null && params.height != heightToUse) {
            params.height = heightToUse
            entry.layoutParams = params
        }
        // 微信重建 ActionBar 时可能把自己重新加到 decorView 上层，把我们的入口盖住
        // （被盖住等于点不到、也可能看不见），发现不在最上层就提一次。
        if (host.childCount > 0 && host.getChildAt(host.childCount - 1) !== entry) {
            entry.bringToFront()
        }
        if (entry.visibility != View.VISIBLE) entry.visibility = View.VISIBLE
        if (entry.x != x.toFloat()) entry.x = x.toFloat()
        if (entry.y != y.toFloat()) entry.y = y.toFloat()
    }

    /** 覆盖层入口的定位锚点：标题栏右侧「更多功能按钮」，退化时用「搜索」。 */
    private fun resolveOverlayAnchor(host: ViewGroup): View? {
        val cached = overlayAnchor?.get()
        if (cached != null && cached.isAttachedToWindow && cached.isShown) return cached
        val found = findViewByDesc(host, "更多功能按钮") ?: findViewByDesc(host, "搜索")
        if (found != null) overlayAnchor = java.lang.ref.WeakReference(found)
        return found
    }

    private fun buildEntryView(activity: Activity): TextView {
        val tv = TextView(activity)
        tv.text = MENU_TEXT
        tv.textSize = 15f
        tv.gravity = Gravity.CENTER
        tv.setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        tv.setTextColor(titleTextColor(activity))
        val density = activity.resources.displayMetrics.density
        val horizontal = (density * 8).toInt()
        tv.setPadding(horizontal, 0, horizontal, 0)
        tv.isClickable = true
        tv.setOnClickListener {
            runCatching { show(activity) }
                .onFailure { HLog.e("$TAG 打开全局批量操作菜单失败: ${it.message}", it) }
        }
        tv.tag = ENTRY_TAG
        return tv
    }

    /** 标题栏文字颜色跟宿主主题走，深浅色主题下都不至于看不见。 */
    private fun titleTextColor(activity: Activity): Int {
        return runCatching {
            val attrs = activity.obtainStyledAttributes(intArrayOf(android.R.attr.textColorPrimary))
            val color = attrs.getColor(0, Color.WHITE)
            attrs.recycle()
            color
        }.getOrDefault(Color.WHITE)
    }

    /** 移除上一次插进去的入口（ActionBar 重建后 View 会残留在旧树上）。 */
    private fun removeOwnEntry(activity: Activity) {
        runCatching {
            val root = activity.window?.decorView ?: return
            val found = findViewByTag(root, ENTRY_TAG) ?: return
            (found.parent as? ViewGroup)?.removeView(found)
            if (ownEntryRef?.get() === found) ownEntryRef = null
        }.onFailure { HLog.e("$TAG 移除旧入口失败: ${it.message}", it) }
    }
private fun hasOwnEntry(activity: Activity): Boolean {
        val root = activity.window?.decorView ?: return false
        val view = findViewByTag(root, ENTRY_TAG) ?: return false
        return view.isAttachedToWindow
    }

    private fun findViewByTag(view: View, tag: String): View? {
        if (tag == view.tag) return view
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                val found = findViewByTag(view.getChildAt(index), tag)
                if (found != null) return found
            }
        }
        return null
    }


    /**
     * 隐藏「列表头部那条假标题栏」里的两个图标。
     *
     * 微信首页的会话 `ListView` 里自带一条复刻标题栏的行（`FrameLayout#wk` → `RelativeLayout#cg7`，
     * 里面有 `ImageView#meb`（搜索）和 `ImageView#plus_icon`（＋），还有一个 `TextView#text1`）。
     * 正常情况下它被真 ActionBar 完整盖住，看不见；但主题把标题栏做成透明后，
     * 这一层会透出来，变成「两个 🔍 两个 ＋」——尤其我们往真标题栏里加上 `A` 之后，
     * 真标题栏整体左移约 78px，两层的错位直接暴露。
     *
     * 这里只把**祖先链里含 ListView** 的那两个图标置为 INVISIBLE：
     * 不动假标题栏的标题文字（它参与微信的滚动/下拉效果），也不动真 ActionBar 的图标。
     */
    private fun hideGhostTitleIcons(activity: Activity) {
        runCatching {
            // 快速路径：上一轮隐藏过的 View 都还挂在树上且仍是 INVISIBLE，
            // 说明宿主没有再显示它们，不必每帧都全树扫描（这个函数每次 resume/轮询都会调）。
            if (ghostHiddenViews.isNotEmpty()) {
                var allHidden = true
                for (view in ghostHiddenViews.keys) {
                    if (!view.isAttachedToWindow || view.visibility != View.INVISIBLE) {
                        allHidden = false
                        break
                    }
                }
                if (allHidden) return
            }
            val root: View = activity.window?.decorView ?: return
            val ids = GHOST_ICON_IDS.mapNotNull { name ->
                val id = runCatching {
                    activity.resources.getIdentifier(name, "id", activity.packageName)
                }.getOrDefault(0)
                if (id == 0) null else name to id
            }
            if (ids.isEmpty()) return
            ids.forEach { (name, id) ->
                val hits = ArrayList<View>(2)
                collectViewsById(root, id, hits)
                var hitInList = 0
                hits.forEach { view ->
                    // 只处理「祖先含 ListView/RecyclerView」的那一份，真 ActionBar 的图标一概不动。
                    if (!hasListViewAncestor(view)) return@forEach
                    hitInList++
                    if (view.visibility == View.INVISIBLE) return@forEach
                    view.visibility = View.INVISIBLE
                    ghostHiddenViews[view] = true
                }
                if (hitInList > 0 && loggedKeys.add("ghost:" + name)) {
                    HLog.i("$TAG 已隐藏列表头部的幽灵标题栏图标 id=$name 数量=$hitInList")
                }
            }
            installGhostGuard(activity, root)
        }.onFailure { HLog.e("$TAG 隐藏幽灵标题栏图标失败: ${it.message}", it) }
    }

    /**
     * 绘制前守护：微信重建列表头部行时会把两个图标重新 setVisible（例如切 Tab、刷新标题计数）。
     *
     * 直接挂 `OnPreDrawListener` 每帧重扫整棵树太贵，这里只遍历我们缓存过的几个 View，
     * 常量级开销；缓存全部失效（头部行被整体重建）时才安排一次重新扫描。
     */
    private fun installGhostGuard(activity: Activity, root: View) {
        if (ghostHiddenViews.isEmpty()) return
        val list = findConversationList(root) ?: return
        if (ghostGuardList?.get() === list) return
        ghostGuardList = java.lang.ref.WeakReference<View>(list)
        val observer = list.viewTreeObserver ?: return
        observer.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                var alive = 0
                ghostHiddenViews.keys.toList().forEach { view ->
                    if (!view.isAttachedToWindow) {
                        ghostHiddenViews.remove(view)
                        return@forEach
                    }
                    alive++
                    if (view.visibility != View.INVISIBLE) view.visibility = View.INVISIBLE
                }
                if (alive == 0 && !ghostRescanPosted) {
                    ghostRescanPosted = true
                    main.postDelayed({
                        ghostRescanPosted = false
                        runCatching { hideGhostTitleIcons(activity) }
                    }, 200L)
                }
                return true
            }
        })
        HLog.i("$TAG 幽灵图标绘制前守护已挂上 list=${list.javaClass.simpleName}")
    }

    /**
     * 收集整棵树里所有 `id` 匹配的 View。
     *
     * 不用 `root.findViewById` 是因为它只返回**第一个**命中项：一旦微信把某个图标
     * 提前插进别处，我们就可能改错对象（1056 就是这么踩到列表头部行的）。
     */
    private fun collectViewsById(view: View, id: Int, out: MutableList<View>) {
        if (view.id == id) out.add(view)
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                collectViewsById(view.getChildAt(index), id, out)
            }
        }
    }
    private fun hasListViewAncestor(view: View): Boolean {
        var current = view.parent
        var depth = 0
        while (current != null && depth < 16) {
            val name = current.javaClass.simpleName
            if (name.contains("ListView") || name.contains("RecyclerView")) return true
            current = current.parent
            depth++
        }
        return false
    }

    /** 让微信原生会话列表自己刷新一次（免打扰 / 未读等图标由它的 adapter 绘制）。 */
    private fun notifyHomeList(activity: Activity) {
        main.post {
            runCatching {
                val root: View = activity.window?.decorView ?: return@post
                val list = findConversationList(root) ?: run {
                    if (loggedKeys.add("noList")) {
                        HLog.i("$TAG 找不到首页会话列表，跳过原生列表刷新")
                    }
                    return@post
                }
                if (loggedKeys.add("notifyHit")) {
                    HLog.i(
                        "$TAG 通知首页列表刷新 list=${list.javaClass.simpleName} " +
                            "pid=${Process.myPid()}"
                    )
                }
                // 用反射调 adapter.notifyDataSetChanged()，这样 ListView / RecyclerView 都能覆盖，
                // 也不用为了一个刷新方法去引入 recyclerview 依赖。
                val adapter = runCatching {
                    list.javaClass.getMethod("getAdapter").invoke(list)
                }.getOrNull()
                if (adapter != null) {
                    // 关键：ListView 加过 header 时拿到的是 HeaderViewListAdapter，
                    // 它的 notifyDataSetChanged() 是**空实现**（observer 注册在内层 adapter 上），
                    // 所以要一路 getWrappedAdapter 解包，通知真正干活的 adapter。
                    var target: Any = adapter
                    var depth = 0
                    while (depth < 6) {
                        val wrapped = runCatching {
                            target.javaClass.getMethod("getWrappedAdapter").invoke(target)
                        }.getOrNull()
                        if (wrapped == null || wrapped === target) break
                        target = wrapped
                        depth++
                    }
                    if (target !== adapter && loggedKeys.add("adapterUnwrap")) {
                        HLog.i(
                            "$TAG 列表 adapter 解包: ${adapter.javaClass.simpleName}" +
                                " → ${target.javaClass.simpleName}"
                        )
                    }
                    runCatching { target.javaClass.getMethod("notifyDataSetChanged").invoke(target) }
                        .onFailure { HLog.e("$TAG notifyDataSetChanged 失败: ${it.message}", it) }
                } else if (loggedKeys.add("noadapter")) {
                    HLog.i("$TAG 列表 ${list.javaClass.simpleName} 没有 adapter，跳过刷新")
                }
                // 再让 ListView 自己把可见行重新 bind 一次（invalidateViews 会清掉视觉缓存、
                // 重新调 adapter.getView），这是把行内「免打扰图标」这种状态刷出来的最直接手段。
                runCatching { list.javaClass.getMethod("invalidateViews").invoke(list) }
                    .onFailure { HLog.e("$TAG invalidateViews 失败: ${it.message}", it) }
                // 重绑过程中微信可能把头部行的两个图标重新显示出来，紧跟一次压制。
                main.postDelayed({ runCatching { hideGhostTitleIcons(activity) } }, 150L)
            }.onFailure { HLog.e("$TAG 刷新首页会话列表失败: ${it.message}", it) }
        }
    }

    private fun findConversationList(view: View?): android.widget.ListView? =
        findListView(view, requireConversationName = true)
            ?: findListView(view, requireConversationName = false)

    /** 首页会话列表此刻是否真的在屏幕上（用于判断「现在是不是首页」）。 */
    private fun homeListVisible(): Boolean {
        val root = entryGuardRoot?.get() ?: return false
        if (!root.isShown) return false
        val list = findListView(root, requireConversationName = true)
            ?: findConversationList(root)
            ?: return false
        return list.isAttachedToWindow && list.isShown && list.width > 0
    }

    private fun findListView(view: View?, requireConversationName: Boolean): android.widget.ListView? {
        val current = view ?: return null
        if (current is android.widget.ListView &&
            (!requireConversationName ||
                current.javaClass.simpleName.contains("Conversation", ignoreCase = true))
        ) {
            if (!requireConversationName && loggedKeys.add("list:" + current.javaClass.name)) {
                HLog.i("$TAG 首页列表类名不含 Conversation，按兜底规则命中 ${current.javaClass.name}")
            }
            return current
        }
        if (current is ViewGroup) {
            for (index in 0 until current.childCount) {
                val found = findListView(current.getChildAt(index), requireConversationName)
                if (found != null) return found
            }
        }
        return null
    }

    /**
     * 定位**真标题栏**右侧那个横排容器。
     *
     * 踩过的坑（1056）：按资源 id `plus_icon` / `meb` 上溯，命中的其实是
     * 会话 `ListView` 的**列表头部行**（里面也有两个 74px 小图标），
     * 主题把 ActionBar 背景做透明后那一条会透出来，于是看起来像「多了个 🔍 和 ＋」。
     *
     * 现在改为语义锚点：
     * 1. 找 contentDescription 为「更多功能按钮」（＋）或「搜索」的控件；
     * 2. 向上找第一个横向 LinearLayout，且**祖先里必须有 ActionBarContainer / Toolbar**；
     * 3. 把入口加进这个容器，紧跟在 ＋ 之后。
     */
    private fun findTitleContainer(activity: Activity): ViewGroup? {
        val root = activity.window?.decorView ?: return null
        val anchor = findViewByDesc(root, "更多功能按钮")
            ?: findViewByDesc(root, "搜索")
        if (anchor == null) {
            if (loggedKeys.add("anchorMiss")) {
                HLog.i("$TAG 找不到「更多功能按钮 / 搜索」锚点，无法定位标题栏容器")
            }
            return null
        }
        var parent = anchor.parent as? ViewGroup
        var depth = 0
        while (parent != null && depth < 6) {
            val group = parent
            if (group is LinearLayout && group.orientation == LinearLayout.HORIZONTAL &&
                isInsideActionBar(group)
            ) {
                if (loggedKeys.add("anchorHit")) {
                    HLog.i(
                        "$TAG 标题栏容器定位 anchor=${anchor.contentDescription} " +
                            "容器=${group.javaClass.simpleName} children=${group.childCount}"
                    )
                }
                return group
            }
            parent = group.parent as? ViewGroup
            depth++
        }
        if (loggedKeys.add("anchorNotInBar")) {
            HLog.i("$TAG 锚点上溯没有找到 ActionBarContainer 内的横向容器")
        }
        return null
    }

    /** 祖先里是否有 ActionBarContainer / Toolbar —— 用来区分「真标题栏」和「列表头部行」。 */
    private fun isInsideActionBar(view: View): Boolean {
        var current = view.parent
        var depth = 0
        while (current != null && depth < 12) {
            val name = current.javaClass.simpleName
            if (name.contains("ActionBarContainer") || name.contains("Toolbar") ||
                name.contains("ActionBar")
            ) {
                return true
            }
            current = current.parent
            depth++
        }
        return false
    }

    private fun findViewByDesc(view: View, desc: String): View? {
        if (desc.contentEquals(view.contentDescription ?: "")) return view
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                val found = findViewByDesc(view.getChildAt(index), desc)
                if (found != null) return found
            }
        }
        return null
    }

    private fun idName(view: View): String {
        val id = view.id
        if (id == View.NO_ID) return "-"
        return runCatching { view.resources.getResourceEntryName(id) }
            .getOrDefault("0x" + Integer.toHexString(id))
    }

    /** 诊断：把标题栏那一带的视图树打一次（每个 Activity 实例只打一次）。 */
    private fun dumpTopBarOnce(activity: Activity) {
        if (!loggedKeys.add("topbar:" + System.identityHashCode(activity))) return
        runCatching {
            val root = activity.window?.decorView ?: return
            val location = IntArray(2)
            root.getLocationOnScreen(location)
            val rootTop = location[1]
            val limitY = rootTop + (root.height * 16) / 100
            val builder = StringBuilder()
            dumpTree(root, 0, rootTop, limitY, builder, intArrayOf(0))
            HLog.i("$TAG 标题栏视图树 begin\n$builder$TAG 标题栏视图树 end")
        }.onFailure { HLog.e("$TAG dump 标题栏失败: ${it.message}", it) }
    }

    private fun dumpTree(
        view: View,
        depth: Int,
        rootTop: Int,
        limitY: Int,
        builder: StringBuilder,
        budget: IntArray
    ) {
        if (budget[0] > 220) return
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        val top = location[1] - rootTop
        val bottom = top + view.height
        if (bottom < 0 || top > limitY - rootTop) return
        budget[0]++
        repeat(depth) { builder.append(' ') }
        builder.append(view.javaClass.simpleName)
            .append(" id=").append(idName(view))
            .append(" [").append(top).append("..").append(bottom).append(']')
            .append(" w=").append(view.width)
            .append(" n=").append((view as? ViewGroup)?.childCount ?: -1)
            .append(" vis=").append(view.visibility)
        if (view is TextView) builder.append(" text=").append(view.text)
        builder.append('\n')
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                dumpTree(view.getChildAt(index), depth + 1, rootTop, limitY, builder, budget)
            }
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
     * 选方法。实测 `MainUI` 上同名重载很多，其中带 Drawable 的有三种：
     * `(int,int,Drawable,listener)`、`(int,String,Drawable,listener)`、`(int,Drawable,listener)`。
     * **优先 `(int,String,Drawable,listener)`**：三个参数语义都完整（id / 描述 / 图标），
     * 不会出现 `(int,int,Drawable,...)` 里第二个 int 只能填 0、导致标题栏多渲染一个占位图标的问题。
     */
    private fun pickMenuMethod(target: Any, wantIcon: Boolean): Method? {
        val methods = menuMethods(target)
        if (wantIcon) {
            val iconMethods = methods.filter { method ->
                val types = method.parameterTypes
                types.isNotEmpty() &&
                    types.last() == MenuItem.OnMenuItemClickListener::class.java &&
                    types.any { Drawable::class.java.isAssignableFrom(it) }
            }
            return iconMethods.minByOrNull { method ->
                val types = method.parameterTypes
                when {
                    // id + 描述 + 图标 + 监听：最完整，优先
                    types.size == 4 && types[0] == Integer.TYPE &&
                        types[1] == String::class.java -> 0
                    // id + 图标 + 监听：其次
                    types.size == 3 && types[0] == Integer.TYPE -> 1
                    // id + 未知 int + 图标 + 监听：最后（那个 int 只能填 0）
                    else -> 2
                }
            }
        }
        return methods.firstOrNull { method ->
            val types = method.parameterTypes
            method.name.startsWith("addTextOptionMenu") &&
                types.isNotEmpty() &&
                types.any { it == Integer.TYPE } &&
                types.last() == MenuItem.OnMenuItemClickListener::class.java &&
                types.any { it == String::class.java }
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

    /**
     * 收尾：微信用 `addTextOptionMenu` 加项时内部仍是「图标项 + 文字」两层，
     * `iconRes` 传 0 会渲染出一个默认图标（实测是放大镜），于是标题栏会多一个 🔍。
     * 这里把该项的图标换成宿主自带的透明资源，只留文字；并显式再写一次文字防止被覆盖。
     */
    private fun polishMenuAppearance(target: Any, activity: Activity) {
        val transparent = transparentDrawableId(activity)
        if (transparent != 0) {
            val ok = invokeWithInt(target, "updateOptionMenuIcon", MENU_ITEM_ID, transparent)
            HLog.i(
                "$TAG 菜单图标换成透明资源 res=0x${Integer.toHexString(transparent)} ok=$ok"
            )
        } else {
            HLog.i("$TAG 宿主没有可用的透明 drawable，图标槽保持原样")
        }
        val textOk = invokeWithString(target, "updateOptionMenuText", MENU_ITEM_ID, MENU_TEXT)
        HLog.i("$TAG 设置菜单文字「$MENU_TEXT」 ok=$textOk")
        invokeWithIntBool(target, "showOptionMenu", MENU_ITEM_ID, true)
    }

    /** 在宿主资源里找一个「透明/空」drawable，用来清掉菜单项自带的图标槽。 */
    private fun transparentDrawableId(activity: Activity): Int {
        val resources = activity.resources
        val packageName = activity.packageName
        listOf(
            "transparent", "mm_transparent", "icons_filled_transparent",
            "empty_drawable", "mm_title_btn_transparent"
        ).forEach { name ->
            val id = runCatching { resources.getIdentifier(name, "drawable", packageName) }
                .getOrDefault(0)
            if (id != 0) return id
        }
        return 0
    }

    private fun invokeWithInt(target: Any, methodName: String, id: Int, value: Int): Boolean {
        val method = menuMethods(target).firstOrNull {
            it.name == methodName && it.parameterTypes.size == 2 &&
                it.parameterTypes[0] == Integer.TYPE && it.parameterTypes[1] == Integer.TYPE
        } ?: return false
        return runCatching { method.invoke(target, id, value); true }.getOrDefault(false)
    }

    private fun invokeWithString(target: Any, methodName: String, id: Int, value: String): Boolean {
        val method = menuMethods(target).firstOrNull {
            it.name == methodName && it.parameterTypes.size == 2 &&
                it.parameterTypes[0] == Integer.TYPE &&
                it.parameterTypes[1] == String::class.java
        } ?: return false
        return runCatching { method.invoke(target, id, value); true }.getOrDefault(false)
    }

    private fun invokeWithIntBool(
        target: Any,
        methodName: String,
        id: Int,
        value: Boolean
    ): Boolean {
        val method = menuMethods(target).firstOrNull {
            it.name == methodName && it.parameterTypes.size == 2 &&
                it.parameterTypes[0] == Integer.TYPE &&
                it.parameterTypes[1] == java.lang.Boolean.TYPE
        } ?: return false
        return runCatching { method.invoke(target, id, value); true }.getOrDefault(false)
    }

    /** 诊断：ActionBar 的菜单容器里有几项（应只比微信自身多 1 项）。 */
    private fun logMenuBar(activity: Activity, stage: String) {
        runCatching {
            val root = activity.window?.decorView ?: return
            val container = findActionMenuView(root) ?: return
            HLog.i("$TAG ActionMenuView($stage) children=${container.childCount}")
        }
    }

    private fun findActionMenuView(view: View?): ViewGroup? {
        val current = view ?: return null
        if (current is ViewGroup &&
            current.javaClass.simpleName.contains("ActionMenuView", ignoreCase = true)
        ) {
            return current
        }
        if (current is ViewGroup) {
            for (index in 0 until current.childCount) {
                val found = findActionMenuView(current.getChildAt(index))
                if (found != null) return found
            }
        }
        return null
    }
}