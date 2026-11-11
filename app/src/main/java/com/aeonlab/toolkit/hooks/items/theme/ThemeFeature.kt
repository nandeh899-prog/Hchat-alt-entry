package com.aeonlab.toolkit.hooks.items.theme

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import com.aeonlab.toolkit.event.Events
import com.aeonlab.toolkit.hooks.core.BaseFeature
import com.aeonlab.toolkit.hooks.core.DexInstallScheduler
import com.aeonlab.toolkit.hooks.core.FeatureContext
import com.aeonlab.toolkit.hooks.core.HookRegistry
import com.aeonlab.toolkit.utils.HLog
import com.aeonlab.toolkit.utils.KavaReflector
import com.aeonlab.toolkit.xposed.compat.XC_MethodHook
import java.util.Collections
import java.util.WeakHashMap

class ThemeFeature : BaseFeature() {
    private var runtime: ThemeRuntime? = null

    override fun featureId(): String = ID

    override fun name(): String = "美化主题"

    override fun onFeatureInit(context: FeatureContext) {
        registerSettingsProvider(ThemeSettingsProvider())
    }

    override fun onFeatureInstall(context: FeatureContext) {
        runtime = ThemeRuntime(context)
        scheduleInstall()
        subscribe(Events.DexReady::class.java) { scheduleInstall() }
    }

    override fun onFeatureDestroy(context: FeatureContext) {
        runtime = null
    }

    private fun scheduleInstall() {
        DexInstallScheduler.schedule(ID, name()) {
            runtime?.installWeChatHooks() == true
        }
    }

    companion object {
        const val ID = "beautify_theme"
    }
}

/**
 * 主题生效逻辑。
 *
 * 全部走「拿到 LauncherUI 后遍历 decorView」这一条路：
 * 微信各版本文案/类名会变，所以每个落点都有多个兜底（类名 → id 名 → 结构特征），
 * 找不到就写一条带具体落点的日志，绝不静默跳过。
 */
private class ThemeRuntime(private val context: FeatureContext) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val hiddenViews: MutableSet<View> = Collections.newSetFromMap(WeakHashMap<View, Boolean>())
    private val transparentViews: MutableSet<View> = Collections.newSetFromMap(WeakHashMap<View, Boolean>())

    @Volatile
    private var installed = false

    @Volatile
    private var splashShown = false

    /** 见过的 LauncherUI；用来在没有当前页面信息时兜底重刷。 */
    private val launchers: MutableSet<Activity> = Collections.newSetFromMap(WeakHashMap<Activity, Boolean>())

    @Volatile
    private var lastDumpAt = 0L

    @Synchronized
    fun installWeChatHooks(): Boolean {
        if (installed) return true
        val launcher = KavaReflector.loadClass(LAUNCHER_UI_CLASS, context.hostClassLoader())
        if (launcher == null) {
            HLog.e("$TAG 找不到 $LAUNCHER_UI_CLASS，主题无法挂载")
            return false
        }
        val resume = KavaReflector.findMethod(launcher, "onResume")
            ?: KavaReflector.findMethodRecursive(launcher, "onResume")
        if (resume == null) {
            HLog.e("$TAG $LAUNCHER_UI_CLASS 上没有 onResume，主题无法挂载")
            return false
        }
        runCatching {
            HookRegistry.get().hook(resume, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val activity = param.thisObject as? Activity ?: return
                    if (activity.javaClass.name.contains(LAUNCHER_UI_CLASS)) launchers.add(activity)
                    runCatching { applyTheme(activity) }
                        .onFailure { HLog.e("$TAG 应用主题异常: ${it.message}", it) }
                }
            })
        }.onFailure {
            HLog.e("$TAG 挂载 LauncherUI.onResume 失败: ${it.message}", it)
            return false
        }
        installed = true
        HLog.i("$TAG 主题 Hook 已挂载（页面=${LAUNCHER_UI_CLASS}，微信版本=${hostVersion()}）")
        // 设置页是微信内的叠加页面，保存不会产生 resume 事件，所以这里挂一条写设置回调。
        ThemeSettings.onSettingsWritten = { scheduleReapply("设置已保存") }
        scheduleReapply("Hook 安装完成")
        return true
    }

    private fun applyTheme(activity: Activity) {
        val snapshot = ThemeSettings.snapshot(context.hostContext())
        val decor = activity.window?.decorView as? ViewGroup
        if (decor == null) {
            HLog.e("$TAG ${activity.javaClass.simpleName} 拿不到 decorView，本轮跳过")
            return
        }
        if (!snapshot.enabled) {
            HLog.i("$TAG 主题未启用（读到的 enabled=false），恢复已改视图；配置=${prefsPath()}")
            restoreTouchedViews()
            return
        }
        HLog.i(
            "$TAG 开始应用主题：页面=${activity.javaClass.simpleName} 标题栏色=${snapshot.actionBarBackgroundColor.ifBlank { "-" }}" +
                " 隐藏标题栏=${snapshot.actionBarHide} 底栏开关=${snapshot.tabEnabled} 隐藏底栏=${snapshot.tabHideNative}" +
                " 圆形头像=${snapshot.roundAvatar} 页面背景=${snapshot.backgroundEnabled}"
        )
        dumpDecor(decor)
        applyActionBar(activity, decor, snapshot)
        applyBottomTab(activity, decor, snapshot)
        applyPageBackgrounds(activity, decor, snapshot)
        maybeShowSplash(activity, decor, snapshot)
    }

    private fun applyActionBar(activity: Activity, decor: ViewGroup, snapshot: ThemeSnapshot) {
        val bar = findActionBar(activity, decor)
        if (bar == null) {
            HLog.e("$TAG 没找到 ActionBar 容器（id=action_bar/action_bar_container/title_bar + 类名 ActionBar/TitleBar 全未命中），标题栏相关主题项本轮未生效")
            return
        }
        HLog.i("$TAG 标题栏容器命中: ${bar.javaClass.name}#${idName(bar)}")
        if (snapshot.actionBarHide) {
            if (bar.visibility != View.GONE) {
                bar.visibility = View.GONE
                hiddenViews.add(bar)
            }
        } else if (hiddenViews.remove(bar)) {
            bar.visibility = View.VISIBLE
        }
        ThemeSettings.parseColor(snapshot.actionBarBackgroundColor)?.let(bar::setBackgroundColor)
        val titleColor = ThemeSettings.parseColor(snapshot.actionBarTitleColor)
        val subtitleColor = ThemeSettings.parseColor(snapshot.actionBarSubtitleColor) ?: titleColor
        if (titleColor == null && subtitleColor == null) return
        val labels = ArrayList<TextView>()
        collectTextViews(bar, labels)
        if (labels.isEmpty()) {
            HLog.e("$TAG ActionBar 里没有 TextView，标题颜色未生效")
            return
        }
        labels.forEachIndexed { index, textView ->
            val color = if (index == 0) titleColor else subtitleColor
            if (color != null) textView.setTextColor(color)
        }
    }

    private fun applyBottomTab(activity: Activity, decor: ViewGroup, snapshot: ThemeSnapshot) {
        if (!snapshot.tabEnabled) return
        val tab = findBottomTab(decor)
        if (tab == null) {
            HLog.e("$TAG 没找到底栏（$BOTTOM_TAB_CLASS、BottomTab、TabView 类名全未命中），底栏主题项本轮未生效；视图树见上一条快照")
            return
        }
        HLog.i("$TAG 底栏容器命中: ${tab.javaClass.name}#${idName(tab)} 子项=${tab.childCount} 隐藏=${snapshot.tabHideNative}")
        if (snapshot.tabHideNative) {
            if (tab.visibility != View.GONE) {
                tab.visibility = View.GONE
                hiddenViews.add(tab)
            }
            return
        }
        if (hiddenViews.remove(tab)) tab.visibility = View.VISIBLE
        ThemeSettings.parseColor(snapshot.tabBackgroundColor)?.let(tab::setBackgroundColor)
        val normalColor = ThemeSettings.parseColor(snapshot.tabTextColor)
        val selectedColor = ThemeSettings.parseColor(snapshot.tabSelectedTextColor) ?: normalColor
        if (normalColor == null && snapshot.tabIcons.all { it.isBlank() }) return
        val selectedIndex = currentTabIndex(tab)
        val itemCount = tab.childCount
        if (itemCount == 0) {
            HLog.e("$TAG 底栏没有子项，底栏主题项本轮未生效")
            return
        }
        for (index in 0 until itemCount) {
            val item = tab.getChildAt(index)
            val label = findFirstView(item, TextView::class.java) as? TextView
            if (label != null && normalColor != null) {
                label.setTextColor(if (index == selectedIndex && selectedColor != null) selectedColor else normalColor)
            }
            val icon = findFirstView(item, ImageView::class.java) as? ImageView
            val path = snapshot.tabIcons.getOrElse(index) { "" }
            if (icon != null && path.isNotBlank()) {
                val bitmap = ThemeImageStore.loadBitmap(path)
                if (bitmap != null) {
                    icon.setImageBitmap(bitmap)
                } else {
                    HLog.e("$TAG 底栏第 ${index + 1} 个图标读取失败: $path")
                }
            }
        }
    }

    private fun applyPageBackgrounds(activity: Activity, decor: ViewGroup, snapshot: ThemeSnapshot) {
        if (!snapshot.backgroundEnabled) return
        val paths = listOf(
            snapshot.backgroundHome,
            snapshot.backgroundContacts,
            snapshot.backgroundDiscover,
            snapshot.backgroundMe
        )
        if (paths.all { it.isBlank() }) return
        val pager = findViewPager(decor)
        if (pager == null) {
            HLog.e("$TAG 没找到首页 ViewPager，页面背景图本轮未生效")
            return
        }
        val slots = listOf(
            ThemeImageStore.SLOT_HOME,
            ThemeImageStore.SLOT_CONTACTS,
            ThemeImageStore.SLOT_DISCOVER,
            ThemeImageStore.SLOT_ME
        )
        val resources = activity.resources
        for (index in 0 until minOf(pager.childCount, paths.size)) {
            val path = paths[index]
            if (path.isBlank()) continue
            val bitmap = ThemeImageStore.loadBitmap(path)
            if (bitmap == null) {
                HLog.e("$TAG 第 ${index + 1} 个页面背景读取失败: $path")
                continue
            }
            val page = pager.getChildAt(index) ?: continue
            val overlay = ThemeSettings.parseColor(snapshot.backgroundOverlay[slots[index]])
            applyPageBitmap(page, bitmap, resources, overlay)
        }
    }

    private fun applyPageBitmap(
        page: View,
        bitmap: Bitmap,
        resources: android.content.res.Resources,
        overlayColor: Int? = null
    ) {
        // 主题包里的 c 字段是背景上的叠色（#AARRGGBB），叠上去才和原主题观感一致
        val drawable: Drawable = if (overlayColor != null && Color.alpha(overlayColor) > 0) {
            LayerDrawable(
                arrayOf<Drawable>(
                    BitmapDrawable(resources, bitmap),
                    ColorDrawable(overlayColor)
                )
            )
        } else {
            BitmapDrawable(resources, bitmap)
        }
        page.setBackgroundDrawable(drawable)
        val lists = ArrayList<View>()
        collectListViews(page, lists, 0)
        if (lists.isEmpty()) {
            HLog.e("$TAG 页面 ${page.javaClass.simpleName} 里没找到列表，背景图可能被列表自身底色盖住")
            return
        }
        lists.forEach { list ->
            val current = runCatching { list.background }.getOrNull()
            if (current != null) {
                list.setBackgroundColor(Color.TRANSPARENT)
                transparentViews.add(list)
            }
        }
    }

    private fun maybeShowSplash(activity: Activity, decor: ViewGroup, snapshot: ThemeSnapshot) {
        if (splashShown) return
        val path = snapshot.backgroundSplash
        if (path.isBlank()) return
        val bitmap = ThemeImageStore.loadBitmap(path)
        if (bitmap == null) {
            HLog.e("$TAG 启动图读取失败: $path")
            return
        }
        splashShown = true
        runCatching {
            val overlay = ImageView(activity)
            overlay.scaleType = ImageView.ScaleType.CENTER_CROP
            overlay.setImageBitmap(bitmap)
            overlay.isClickable = false
            decor.addView(
                overlay,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
            mainHandler.postDelayed({
                runCatching { decor.removeView(overlay) }
            }, SPLASH_DURATION_MS)
        }.onFailure {
            HLog.e("$TAG 启动图浮层显示失败: ${it.message}", it)
        }
    }

    private fun restoreTouchedViews() {
        hiddenViews.toList().forEach { view ->
            runCatching { view.visibility = View.VISIBLE }
        }
        hiddenViews.clear()
        transparentViews.toList().forEach { view ->
            runCatching { view.setBackgroundColor(Color.TRANSPARENT) }
        }
        transparentViews.clear()
    }

    private fun findActionBar(activity: Activity, root: ViewGroup): View? {
        for (idName in ACTION_BAR_ID_NAMES) {
            val id = runCatching {
                activity.resources.getIdentifier(idName, "id", HOST_PACKAGE)
            }.getOrDefault(0)
            if (id == 0) continue
            val byId = runCatching { root.findViewById<View>(id) }.getOrNull()
            if (byId is ViewGroup && byId.visibility == View.VISIBLE) return byId
        }
        for (className in ACTION_BAR_CLASS_NAMES) {
            findViewByClassName(root, className)?.let { if (it is ViewGroup) return it }
        }
        return null
    }

    private fun findBottomTab(root: ViewGroup): ViewGroup? {
        for (className in BOTTOM_TAB_CLASS_NAMES) {
            val found = findViewByClassName(root, className) as? ViewGroup
            if (found != null) return found
        }
        // 类名兜不住时按结构认：贴底、扁条、2~6 个子项且每项都是图标+文字
        val groups = ArrayList<ViewGroup>()
        collectViewGroups(root, groups, 0)
        val rootHeight = root.height
        if (rootHeight <= 0) return null
        val structural = groups
            .filter { it.height > 0 && it.height <= rootHeight / 4 }
            .filter { it.top > rootHeight * 6 / 10 }
            .filter { group ->
                group.childCount in 2..6 &&
                    (0 until group.childCount).all { hasIconAndLabel(group.getChildAt(it)) }
            }
            .maxByOrNull { it.top }
        if (structural != null) {
            HLog.i("$TAG 底栏按结构命中: ${structural.javaClass.name}#${idName(structural)}")
        }
        return structural
    }

    private fun hasIconAndLabel(view: View?): Boolean {
        if (view == null) return false
        return findFirstView(view, ImageView::class.java) != null &&
            findFirstView(view, TextView::class.java) != null
    }

    private fun findViewPager(root: ViewGroup): ViewGroup? {
        val candidates = ArrayList<ViewGroup>()
        collectViewGroups(root, candidates, 0)
        return candidates
            .filter { it.javaClass.name.contains("ViewPager") }
            .maxByOrNull { it.childCount }
            ?: candidates.firstOrNull { it.childCount >= 4 && it.javaClass.name.contains("Frame") }
    }

    /** 设置写入后立刻重刷（设置页与 Hook 同在微信进程，写完就能拿到新值）。 */
    private fun scheduleReapply(reason: String) {
        val run = Runnable { applyToCurrent(reason) }
        mainHandler.post(run)
        mainHandler.postDelayed(run, 400L)
        mainHandler.postDelayed(run, 1400L)
    }

    private fun applyToCurrent(reason: String) {
        val activity = currentActivity() ?: launchers.firstOrNull { !it.isFinishing }
        if (activity == null) {
            HLog.e("$TAG 重刷($reason) 拿不到当前页面，稍后重试")
            return
        }
        if (!activity.javaClass.name.contains(LAUNCHER_UI_CLASS)) {
            HLog.i("$TAG 重刷($reason) 当前页面是 ${activity.javaClass.simpleName}，跳过")
            return
        }
        runCatching { applyTheme(activity) }
            .onFailure { HLog.e("$TAG 重刷($reason) 异常: ${it.message}", it) }
    }

    private fun currentActivity(): Activity? {
        return runCatching {
            val threadClass = Class.forName("android.app.ActivityThread")
            val thread = threadClass.getMethod("currentActivityThread").invoke(null)
                ?: return@runCatching null
            val field = threadClass.getDeclaredField("mActivities").apply { isAccessible = true }
            val records = field.get(thread) as? Map<*, *> ?: return@runCatching null
            var fallback: Activity? = null
            for (value in records.values) {
                val record = value ?: continue
                val paused = runCatching {
                    record.javaClass.getDeclaredField("paused").apply { isAccessible = true }.getBoolean(record)
                }.getOrDefault(true)
                if (paused) continue
                val activity = runCatching {
                    record.javaClass.getDeclaredField("activity").apply { isAccessible = true }.get(record) as? Activity
                }.getOrNull() ?: continue
                if (activity.isFinishing) continue
                if (activity.javaClass.name.contains(LAUNCHER_UI_CLASS)) return@runCatching activity
                if (fallback == null) fallback = activity
            }
            fallback
        }.getOrNull()
    }

    private fun prefsPath(): String = runCatching {
        val dir = com.aeonlab.toolkit.preferences.AeonLabStorage.storageDir(context.hostContext())
        "${dir.absolutePath}/${ThemeSettings.PREFS_NAME}"
    }.getOrDefault("?")

    private fun hostVersion(): String = runCatching {
        context.hostContext().packageManager
            .getPackageInfo(HOST_PACKAGE, 0).versionName ?: "?"
    }.getOrDefault("?")

    private fun idName(view: View): String = runCatching {
        if (view.id == 0) "-" else view.resources.getResourceEntryName(view.id)
    }.getOrDefault("-")

    /** 命中不了目标时靠这条快照定位：类名 + id 名 + 可见性，限 40 行、10 秒一次。 */
    private fun dumpDecor(decor: ViewGroup) {
        val now = System.currentTimeMillis()
        if (now - lastDumpAt < 10_000L) return
        lastDumpAt = now
        val lines = ArrayList<String>()
        fun walk(view: View, depth: Int) {
            if (lines.size >= 40 || depth > 6) return
            val hidden = if (view.visibility != View.VISIBLE) "(invisible)" else ""
            lines.add("  ".repeat(depth) + view.javaClass.simpleName + "#" + idName(view) + hidden)
            if (view is ViewGroup) {
                for (index in 0 until minOf(view.childCount, 8)) {
                    walk(view.getChildAt(index) ?: continue, depth + 1)
                }
            }
        }
        walk(decor, 0)
        HLog.i("$TAG 视图树快照:\n" + lines.joinToString("\n"))
    }

    private fun findViewByClassName(root: View, simpleName: String): View? {
        val name = root.javaClass.name
        if (name == simpleName || name.endsWith(".$simpleName") || name.contains(simpleName)) return root
        if (root is ViewGroup) {
            for (index in 0 until root.childCount) {
                val child = root.getChildAt(index) ?: continue
                findViewByClassName(child, simpleName)?.let { return it }
            }
        }
        return null
    }

    private fun findFirstView(root: View, type: Class<*>): View? {
        if (type.isInstance(root)) return root
        if (root is ViewGroup) {
            for (index in 0 until root.childCount) {
                val child = root.getChildAt(index) ?: continue
                findFirstView(child, type)?.let { return it }
            }
        }
        return null
    }

    private fun collectTextViews(root: View, out: MutableList<TextView>) {
        if (root is TextView && root.text?.isNotBlank() == true) {
            out.add(root)
        }
        if (root is ViewGroup) {
            for (index in 0 until root.childCount) {
                root.getChildAt(index)?.let { collectTextViews(it, out) }
            }
        }
    }

    private fun collectViewGroups(root: View, out: MutableList<ViewGroup>, depth: Int) {
        if (depth > MAX_DEPTH) return
        if (root is ViewGroup) {
            out.add(root)
            for (index in 0 until root.childCount) {
                root.getChildAt(index)?.let { collectViewGroups(it, out, depth + 1) }
            }
        }
    }

    private fun collectListViews(root: View, out: MutableList<View>, depth: Int) {
        if (depth > MAX_DEPTH) return
        val name = root.javaClass.name
        if (name.contains("RecyclerView") || name.contains("ListView")) out.add(root)
        if (root is ViewGroup) {
            for (index in 0 until root.childCount) {
                root.getChildAt(index)?.let { collectListViews(it, out, depth + 1) }
            }
        }
    }

    private fun currentTabIndex(tab: ViewGroup): Int {
        val method = KavaReflector.findMethodRecursive(tab.javaClass, "getCurIdx") ?: return -1
        return (KavaReflector.invoke(method, tab) as? Number)?.toInt() ?: -1
    }

    private companion object {
        const val TAG = "[AeonLab:Theme]"
        const val HOST_PACKAGE = "com.tencent.mm"
        const val LAUNCHER_UI_CLASS = "com.tencent.mm.ui.LauncherUI"
        const val BOTTOM_TAB_CLASS = "com.tencent.mm.ui.LauncherUIBottomTabView"
        const val BOTTOM_TAB_SIMPLE_NAME = "LauncherUIBottomTabView"
        val ACTION_BAR_ID_NAMES = listOf("action_bar", "action_bar_container", "title_bar", "title_bar_container")
        val ACTION_BAR_CLASS_NAMES = listOf("ActionBarContainer", "ActionBarLayout", "MMWindowActionBar", "TitleBarView")
        val BOTTOM_TAB_CLASS_NAMES = listOf("LauncherUIBottomTabView", "BottomTabView", "HomeBottomTab", "MMTabView")
        const val SPLASH_DURATION_MS = 1200L
        const val MAX_DEPTH = 32
    }
}
