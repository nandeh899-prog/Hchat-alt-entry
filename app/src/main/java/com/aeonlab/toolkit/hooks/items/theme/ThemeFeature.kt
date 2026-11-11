package com.aeonlab.toolkit.hooks.items.theme

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
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
                    runCatching { applyTheme(activity) }
                        .onFailure { HLog.e("$TAG 应用主题异常: ${it.message}", it) }
                }
            })
        }.onFailure {
            HLog.e("$TAG 挂载 LauncherUI.onResume 失败: ${it.message}", it)
            return false
        }
        installed = true
        HLog.i("$TAG 主题 Hook 已挂载")
        return true
    }

    private fun applyTheme(activity: Activity) {
        val snapshot = ThemeSettings.snapshot(context.hostContext())
        val decor = activity.window?.decorView as? ViewGroup ?: return
        if (!snapshot.enabled) {
            restoreTouchedViews()
            return
        }
        applyActionBar(activity, decor, snapshot)
        applyBottomTab(activity, decor, snapshot)
        applyPageBackgrounds(activity, decor, snapshot)
        maybeShowSplash(activity, decor, snapshot)
    }

    private fun applyActionBar(activity: Activity, decor: ViewGroup, snapshot: ThemeSnapshot) {
        val bar = findActionBar(activity, decor)
        if (bar == null) {
            HLog.e("$TAG 没找到 ActionBar 容器，标题栏相关主题项本轮未生效")
            return
        }
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
            HLog.e("$TAG 没找到底栏（$BOTTOM_TAB_CLASS / BottomTab 结构），底栏主题项本轮未生效")
            return
        }
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
            applyPageBitmap(page, bitmap, resources)
        }
    }

    private fun applyPageBitmap(page: View, bitmap: Bitmap, resources: android.content.res.Resources) {
        val drawable = BitmapDrawable(resources, bitmap)
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
        val id = runCatching {
            activity.resources.getIdentifier("action_bar", "id", HOST_PACKAGE)
        }.getOrDefault(0)
        if (id != 0) {
            val byId = runCatching { root.findViewById<View>(id) }.getOrNull()
            if (byId is ViewGroup) return byId
        }
        return findViewByClassName(root, "ActionBarContainer")
            ?: findViewByClassName(root, "ActionBarLayout")
            ?: findViewByClassName(root, "MMWindowActionBar")
    }

    private fun findBottomTab(root: ViewGroup): ViewGroup? {
        val byClass = findViewByClassName(root, BOTTOM_TAB_SIMPLE_NAME) as? ViewGroup
        if (byClass != null) return byClass
        return findViewByClassName(root, "BottomTab") as? ViewGroup
    }

    private fun findViewPager(root: ViewGroup): ViewGroup? {
        val candidates = ArrayList<ViewGroup>()
        collectViewGroups(root, candidates, 0)
        return candidates
            .filter { it.javaClass.name.contains("ViewPager") }
            .maxByOrNull { it.childCount }
            ?: candidates.firstOrNull { it.childCount >= 4 && it.javaClass.name.contains("Frame") }
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
        const val SPLASH_DURATION_MS = 1200L
        const val MAX_DEPTH = 32
    }
}
