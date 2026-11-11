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
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
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
import java.lang.ref.WeakReference
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
 * 微信各版本文案/类名会变，所以每个落点都有多个兜底（类名 → 标签文字 → 结构特征），
 * 并且一律等视图量过尺寸（宽高 > 0）再判定——onResume 时视图还没布局，按 0 宽高做结构
 * 判断会全部落空，这也是 5.5.52/537 之前「命中了一个不可见的同名容器」的直接原因。
 * 找不到就写一条带具体落点的日志，绝不静默跳过。
 */
private class ThemeRuntime(private val context: FeatureContext) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val hiddenViews: MutableSet<View> = Collections.newSetFromMap(WeakHashMap<View, Boolean>())
    private val originalHeights: MutableMap<View, Int> =
        Collections.synchronizedMap(WeakHashMap<View, Int>())
    private val replacedBackgrounds: MutableMap<View, Drawable?> =
        Collections.synchronizedMap(WeakHashMap<View, Drawable?>())
    private val pageDrawables: MutableMap<View, Drawable> =
        Collections.synchronizedMap(WeakHashMap<View, Drawable>())
    private val pageScrim: MutableMap<View, ScrimSpec> =
        Collections.synchronizedMap(WeakHashMap<View, ScrimSpec>())
    private val enforcedDecors: MutableSet<ViewGroup> =
        Collections.newSetFromMap(WeakHashMap<ViewGroup, Boolean>())
    private val bitmapCache = object : LinkedHashMap<String, Bitmap>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>): Boolean =
            size > 6
    }

    @Volatile
    private var installed = false

    @Volatile
    private var splashShown = false

    @Volatile
    private var lastTab: WeakReference<ViewGroup>? = null

    /** 见过的 LauncherUI；用来在没有当前页面信息时兜底重刷。 */
    private val launchers: MutableSet<Activity> = Collections.newSetFromMap(WeakHashMap<Activity, Boolean>())

    @Volatile
    private var lastDumpAt = 0L

    @Volatile
    private var lastEnforceAt = 0L

    @Volatile
    private var lastScrimAt = 0L

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
        val decor = activity.window?.decorView as? ViewGroup
        if (decor == null) {
            HLog.e("$TAG ${activity.javaClass.simpleName} 拿不到 decorView，本轮跳过")
            return
        }
        if (decor.width <= 0 || decor.height <= 0) {
            // onResume 阶段视图还没量过，宽高为 0 时结构判定（贴底、扁条、子项数）全部落空，
            // 必须等这一帧布局完成再应用，否则会退化成「命中任意同名容器」。
            decor.post {
                runCatching { applyThemeNow(activity, decor) }
                    .onFailure { HLog.e("$TAG 布局后应用主题异常: ${it.message}", it) }
            }
            return
        }
        applyThemeNow(activity, decor)
    }

    private fun applyThemeNow(activity: Activity, decor: ViewGroup) {
        val snapshot = ThemeSettings.snapshot(context.hostContext())
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
        applyBottomTab(decor, snapshot)
        applyPageBackgrounds(activity, decor, snapshot, log = true)
        maybeShowSplash(activity, decor, snapshot)
        installEnforcement(activity, decor)
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

    private fun applyBottomTab(decor: ViewGroup, snapshot: ThemeSnapshot) {
        if (!snapshot.tabEnabled) return
        val tab = findBottomTab(decor, logCandidates = true)
        if (tab == null) {
            HLog.e("$TAG 没找到底栏容器（类名 $BOTTOM_TAB_CLASS_NAMES 与「微信/通讯录/发现/我」标签祖先全未命中），底栏主题项本轮未生效；视图树见上一条快照")
            return
        }
        lastTab = WeakReference(tab)
        HLog.i(
            "$TAG 底栏容器命中: ${tab.javaClass.name}#${idName(tab)} 子项=${tab.childCount}" +
                " 尺寸=${tab.width}x${tab.height} 可见=${tab.isShown} 隐藏=${snapshot.tabHideNative}"
        )
        if (snapshot.tabHideNative) {
            hideView(tab)
            return
        }
        showView(tab)
        applyTabColorsAndIcons(tab, snapshot)
    }

    private fun applyTabColorsAndIcons(tab: ViewGroup, snapshot: ThemeSnapshot) {
        ThemeSettings.parseColor(snapshot.tabBackgroundColor)?.let(tab::setBackgroundColor)
        val normalColor = ThemeSettings.parseColor(snapshot.tabTextColor)
        val selectedColor = ThemeSettings.parseColor(snapshot.tabSelectedTextColor) ?: normalColor
        val hasIcons = snapshot.tabIcons.any { it.isNotBlank() }
        if (normalColor == null && !hasIcons) return
        if (tab.childCount == 0) {
            HLog.e("$TAG 底栏没有子项，底栏文字与图标未生效")
            return
        }
        val selectedIndex = currentTabIndex(tab)
        for (index in 0 until tab.childCount) {
            val item = tab.getChildAt(index) ?: continue
            val label = findFirstView(item, TextView::class.java) as? TextView
            if (label != null && normalColor != null) {
                label.setTextColor(if (index == selectedIndex && selectedColor != null) selectedColor else normalColor)
            }
            val icon = findFirstView(item, ImageView::class.java) as? ImageView
            val path = snapshot.tabIcons.getOrElse(index) { "" }
            if (icon != null && path.isNotBlank()) {
                val image = bitmap(path)
                if (image != null) {
                    icon.setImageBitmap(image)
                } else {
                    HLog.e("$TAG 底栏第 ${index + 1} 个图标读取失败: $path")
                }
            }
        }
    }

    private fun applyPageBackgrounds(
        activity: Activity,
        decor: ViewGroup,
        snapshot: ThemeSnapshot,
        log: Boolean
    ) {
        if (!snapshot.backgroundEnabled) return
        val paths = listOf(
            snapshot.backgroundHome,
            snapshot.backgroundContacts,
            snapshot.backgroundDiscover,
            snapshot.backgroundMe
        )
        if (paths.all { it.isBlank() }) {
            if (log) HLog.i("$TAG 页面背景开关已开，但四个页面一张图都没设置，本轮跳过")
            return
        }
        val pager = findHomePager(decor, log)
        if (pager == null) {
            if (log) {
                HLog.e("$TAG 没找到首页 ViewPager（MMViewPager / ViewPager 均未命中），页面背景图本轮未生效；视图树见上一条快照")
            }
            return
        }
        if (log) {
            HLog.i(
                "$TAG 页面背景容器: ${pager.javaClass.name}#${idName(pager)}" +
                    " 子项=${pager.childCount} 尺寸=${pager.width}x${pager.height} 可见=${pager.isShown}"
            )
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
            val page = pager.getChildAt(index) ?: continue
            val image = bitmap(path)
            if (image == null) {
                HLog.e("$TAG 第 ${index + 1} 个页面背景读取失败: $path")
                continue
            }
            if (log) {
                HLog.i(
                    "$TAG 页面 ${index + 1} 目标: ${page.javaClass.name}#${idName(page)}" +
                        " 子项=${childCountOf(page)} 尺寸=${page.width}x${page.height}"
                )
            }
            val overlay = ThemeSettings.parseColor(snapshot.backgroundOverlay[slots[index]])
            applyPageBitmap(page, image, resources, overlay, log)
        }
    }

    private fun applyPageBitmap(
        page: View,
        bitmap: Bitmap,
        resources: android.content.res.Resources,
        overlayColor: Int?,
        log: Boolean
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
        pageDrawables[page] = drawable
        pageScrim[page] = ScrimSpec(bitmap, overlayColor)
        // 5.5.54 的实测：背景只从列表之外露出来——列表项、分组头、发现页卡片各自带着不透明白底，
        // 铺在页面上的图被逐层盖住。这里做「内容分层」：
        //   整页容器（≥92% 宽高）→ 完全透明，让背景沉到最底层；
        //   整行/整卡（≥60% 宽）→ 换成按背景明暗算出的半透明遮罩，保证文字可读。
        applyContentScrim(page, bitmap, overlayColor, log)
    }

    private data class ScrimSpec(val bitmap: Bitmap, val overlayColor: Int?)

    /** 整页容器置透明、整行/整卡换半透明遮罩；遮罩浓度按背景图平均亮度算，亮图淡、暗图浓。 */
    private fun applyContentScrim(page: View, bitmap: Bitmap, overlayColor: Int?, log: Boolean) {
        val pageWidth = page.width.coerceAtLeast(page.measuredWidth)
        val pageHeight = page.height.coerceAtLeast(page.measuredHeight)
        if (pageWidth <= 0 || pageHeight <= 0) return
        val luma = averageLuma(bitmap)
        val scrim = scrimColorFor(luma, overlayColor)
        val views = ArrayList<View>()
        collectAllViews(page, views, 0)
        var containers = 0
        var rows = 0
        views.forEach { view ->
            if (view === page || pageDrawables.containsKey(view)) return@forEach
            if (view is ImageView) return@forEach
            val background = view.background ?: return@forEach
            if (background is ColorDrawable && Color.alpha(background.color) == 0) return@forEach
            val width = view.width.coerceAtLeast(view.measuredWidth)
            val height = view.height.coerceAtLeast(view.measuredHeight)
            if (width <= 0 || height <= 0) return@forEach
            val fullPage = width >= pageWidth * 0.92f && height >= pageHeight * 0.92f
            val target = when {
                fullPage -> Color.TRANSPARENT
                width >= pageWidth * 0.6f -> scrim
                else -> return@forEach
            }
            if (!replacedBackgrounds.containsKey(view)) replacedBackgrounds[view] = background
            view.setBackgroundColor(target)
            if (fullPage) containers++ else rows++
        }
        if (log) {
            HLog.i(
                "$TAG 页面 ${page.javaClass.simpleName} 内容分层：整页容器置透明 ${containers} 个，" +
                    "整行/整卡换半透明遮罩 ${rows} 个，遮罩=#${Integer.toHexString(scrim)}" +
                    " 背景平均亮度=${String.format(java.util.Locale.US, "%.2f", luma)}"
            )
        }
    }

    /**
     * 遮罩浓度：主题包自带叠色（c 字段）时优先用它；否则按背景图平均亮度取白，
     * 亮图 20% 左右、暗图 60% 左右——暗图下深色文字会糊，只能靠白色遮罩把底提亮。
     */
    private fun scrimColorFor(luma: Float, overlayColor: Int?): Int {
        if (overlayColor != null && Color.alpha(overlayColor) > 0) return overlayColor
        val alpha = (0.62f - 0.45f * luma).coerceIn(0.20f, 0.62f)
        return Color.argb((alpha * 255).toInt(), 255, 255, 255)
    }

    private fun averageLuma(bitmap: Bitmap): Float = runCatching {
        val stepX = (bitmap.width / 24).coerceAtLeast(1)
        val stepY = (bitmap.height / 24).coerceAtLeast(1)
        var sum = 0.0
        var count = 0
        var y = 0
        while (y < bitmap.height) {
            var x = 0
            while (x < bitmap.width) {
                val color = bitmap.getPixel(x, y)
                sum += (0.299 * Color.red(color) + 0.587 * Color.green(color) + 0.114 * Color.blue(color)) / 255.0
                count++
                x += stepX
            }
            y += stepY
        }
        if (count == 0) 0.5f else (sum / count).toFloat()
    }.getOrDefault(0.5f)

    private fun maybeShowSplash(activity: Activity, decor: ViewGroup, snapshot: ThemeSnapshot) {
        if (splashShown) return
        val path = snapshot.backgroundSplash
        if (path.isBlank()) return
        val image = bitmap(path) ?: run {
            HLog.e("$TAG 启动图读取失败: $path")
            return
        }
        splashShown = true
        runCatching {
            val overlay = ImageView(activity)
            overlay.scaleType = ImageView.ScaleType.CENTER_CROP
            overlay.setImageBitmap(image)
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

    private fun hideView(view: View) {
        if (view.visibility != View.GONE) view.visibility = View.GONE
        if (!hiddenViews.add(view)) return
        val params = view.layoutParams
        if (params != null && params.height != 0) {
            originalHeights[view] = params.height
            params.height = 0
            view.layoutParams = params
            HLog.i("$TAG 已隐藏并收高: ${view.javaClass.name}#${idName(view)}")
        }
    }

    private fun showView(view: View) {
        if (!hiddenViews.remove(view)) return
        if (view.visibility != View.VISIBLE) view.visibility = View.VISIBLE
        val original = originalHeights.remove(view)
        val params = view.layoutParams
        if (original != null && params != null && params.height != original) {
            params.height = original
            view.layoutParams = params
        }
    }

    /**
     * 微信在切页/重建 Fragment 时会把底栏重新显示、把页面背景换回白底。
     * 这里挂一条 preDraw 兜底（限频 400ms，只在状态被改回来时才写日志），
     * 否则「设置里开着、界面上没变化」会一直复现。
     */
    private fun installEnforcement(activity: Activity, decor: ViewGroup) {
        if (!enforcedDecors.add(decor)) return
        decor.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                runCatching { enforce(activity, decor) }
                    .onFailure { HLog.e("$TAG 兜底重刷异常: ${it.message}", it) }
                return true
            }
        })
    }

    private fun enforce(activity: Activity, decor: ViewGroup) {
        if (!decor.isAttachedToWindow) return
        val now = SystemClock.uptimeMillis()
        if (now - lastEnforceAt < ENFORCE_INTERVAL_MS) return
        lastEnforceAt = now
        val snapshot = ThemeSettings.snapshot(context.hostContext())
        if (!snapshot.enabled) return
        if (snapshot.tabEnabled && snapshot.tabHideNative) {
            var tab = lastTab?.get()
            if (tab != null && !tab.isAttachedToWindow) tab = null
            if (tab == null) tab = findBottomTab(decor, logCandidates = false)
            if (tab != null) {
                lastTab = WeakReference(tab)
                if (tab.visibility != View.GONE) {
                    HLog.i("$TAG 底栏被微信恢复显示，重新隐藏: ${tab.javaClass.name}#${idName(tab)}")
                    hideView(tab)
                }
            }
        }
        if (snapshot.backgroundEnabled) {
            pageDrawables.entries.toList().forEach { (page, drawable) ->
                if (page.background !== drawable) {
                    runCatching { page.setBackgroundDrawable(drawable) }
                        .onFailure { HLog.e("$TAG 页面背景被覆盖后重铺失败: ${it.message}", it) }
                }
            }
            if (now - lastScrimAt >= SCRIM_INTERVAL_MS) {
                lastScrimAt = now
                pageScrim.entries.toList().forEach { (page, spec) ->
                    runCatching { applyContentScrim(page, spec.bitmap, spec.overlayColor, log = false) }
                        .onFailure { HLog.e("$TAG 内容分层重扫失败: ${it.message}", it) }
                }
            }
        }
    }

    private fun restoreTouchedViews() {
        hiddenViews.toList().forEach { view ->
            runCatching { view.visibility = View.VISIBLE }
            val original = originalHeights.remove(view)
            if (original != null) {
                runCatching {
                    val params = view.layoutParams
                    if (params != null) {
                        params.height = original
                        view.layoutParams = params
                    }
                }
            }
        }
        hiddenViews.clear()
        replacedBackgrounds.entries.toList().forEach { (view, background) ->
            runCatching { view.background = background }
        }
        replacedBackgrounds.clear()
        pageDrawables.keys.toList().forEach { page ->
            runCatching { page.background = null }
        }
        pageDrawables.clear()
        pageScrim.clear()
        lastTab = null
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

    /**
     * 底栏定位：同时收集「类名命中」与「四个页签标签的最近公共祖先」两类候选，
     * 每个候选都打一行尺寸/可见性，再挑真正可见的那一个。
     *
     * 5.5.53 的做法是取深度优先的第一个同名 View，而微信 8.0.49 里同时存在一个
     * 只有 1 个子项、不可见的 LauncherUIBottomTabView（`#huj`），于是每次都改在它身上：
     * 日志显示「命中、隐藏=true」，屏幕上四个 Tab 一动不动。
     */
    private fun findBottomTab(decor: ViewGroup, logCandidates: Boolean): ViewGroup? {
        val hits = LinkedHashMap<ViewGroup, String>()
        collectByClassNames(decor, BOTTOM_TAB_CLASS_NAMES, hits, 0)
        collectLabelBarCandidates(decor, hits)
        if (hits.isEmpty()) return null
        if (logCandidates) {
            hits.forEach { (view, source) ->
                HLog.i(
                    "$TAG 底栏候选[$source] ${view.javaClass.name}#${idName(view)}" +
                        " 子项=${view.childCount} 尺寸=${view.width}x${view.height}" +
                        " 可见=${view.isShown} attached=${view.isAttachedToWindow} top=${screenTop(view)}"
                )
            }
        }
        val visible = hits.keys.filter { it.isShown && it.width > 0 && it.height > 0 }
        val pool = if (visible.isNotEmpty()) visible else hits.keys.toList()
        return pool.maxByOrNull { it.width.toLong() * it.height.toLong() }
    }

    private fun collectByClassNames(
        root: View,
        names: List<String>,
        out: MutableMap<ViewGroup, String>,
        depth: Int
    ) {
        if (depth > MAX_DEPTH) return
        val className = root.javaClass.name
        for (name in names) {
            if (className == name || className.endsWith(".$name") || className.contains(name)) {
                val group = root as? ViewGroup
                if (group != null && !out.containsKey(group)) out[group] = "类名:$name"
                break
            }
        }
        if (root is ViewGroup) {
            for (index in 0 until root.childCount) {
                root.getChildAt(index)?.let { collectByClassNames(it, names, out, depth + 1) }
            }
        }
    }

    /** 按「微信/通讯录/发现/我」四个页签文字的最近公共祖先认底栏，类名换了也认得出。 */
    private fun collectLabelBarCandidates(root: ViewGroup, out: MutableMap<ViewGroup, String>) {
        val texts = ArrayList<TextView>()
        collectTextViews(root, texts)
        val matched = texts.filter { view ->
            view.isShown && view.text?.toString()?.trim()?.let { it.isNotEmpty() && it in BOTTOM_TAB_LABELS } == true
        }
        if (matched.size < 3) return
        var candidate = lowestCommonAncestor(matched) ?: return
        val limit = decorThird(root)
        while (candidate !== root && (candidate.height <= 0 || candidate.height > limit)) {
            candidate = candidate.parent as? ViewGroup ?: return
        }
        if (candidate !== root && !out.containsKey(candidate)) {
            out[candidate] = "标签:${matched.size}个页签文字"
        }
    }

    private fun decorThird(root: ViewGroup): Int {
        val height = root.height.coerceAtLeast(root.measuredHeight)
        return if (height <= 0) Int.MAX_VALUE else height / 3
    }

    private fun lowestCommonAncestor(views: List<View>): ViewGroup? {
        if (views.isEmpty()) return null
        val ancestors = HashSet<View>()
        var node: View? = views[0]
        while (node != null) {
            ancestors.add(node)
            node = node.parent as? View
        }
        var result: View = views[0]
        for (index in 1 until views.size) {
            var current: View? = views[index]
            while (current != null && !ancestors.contains(current)) {
                current = current.parent as? View
            }
            if (current == null) return null
            if (depthOf(current) > depthOf(result)) result = current
        }
        return result as? ViewGroup
    }

    private fun depthOf(view: View): Int {
        var depth = 0
        var current: View? = view.parent as? View
        while (current != null) {
            depth++
            current = current.parent as? View
        }
        return depth
    }

    private fun childCountOf(view: View): Int = (view as? ViewGroup)?.childCount ?: 0

    private fun screenTop(view: View): Int = runCatching {
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        location[1]
    }.getOrDefault(-1)

    /**
     * 首页 pager 定位：只认 ViewPager 家族（微信自己的 `com.tencent.mm.ui.base.MMViewPager`
     * 与 androidx ViewPager），不再用「任意 4 个子项的 FrameLayout」兜底——
     * 5.5.53 里这条兜底命中的是 ActionBar 下面那个包住整个内容区的 FrameLayout（6 个子项），
     * 背景铺在它上面等于铺在最底层，日志还不会报错。
     */
    private fun findHomePager(decor: ViewGroup, log: Boolean): ViewGroup? {
        val candidates = ArrayList<ViewGroup>()
        collectViewGroups(decor, candidates, 0)
        val pagers = candidates.filter { group ->
            val name = group.javaClass.name
            name.endsWith(".MMViewPager") || name.endsWith("MMViewPager") || name.contains("ViewPager")
        }
        if (pagers.isEmpty()) return null
        if (log) {
            pagers.forEach { pager ->
                HLog.i(
                    "$TAG ViewPager 候选: ${pager.javaClass.name}#${idName(pager)}" +
                        " 子项=${pager.childCount} 尺寸=${pager.width}x${pager.height} 可见=${pager.isShown}"
                )
            }
        }
        val fourPages = pagers.filter { it.childCount >= 4 && it.isShown }
        return fourPages.maxByOrNull { it.childCount }
            ?: pagers.filter { it.isShown }.maxByOrNull { it.childCount }
            ?: pagers.maxByOrNull { it.childCount }
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

    private fun bitmap(path: String): Bitmap? {
        bitmapCache[path]?.let { if (!it.isRecycled) return it }
        val loaded = ThemeImageStore.loadBitmap(path) ?: return null
        bitmapCache[path] = loaded
        return loaded
    }

    /** 命中不了目标时靠这条快照定位：类名 + id 名 + 宽高 + 可见性，限 70 行、10 秒一次。 */
    private fun dumpDecor(decor: ViewGroup) {
        val now = System.currentTimeMillis()
        if (now - lastDumpAt < 10_000L) return
        lastDumpAt = now
        val lines = ArrayList<String>()
        fun walk(view: View, depth: Int) {
            if (lines.size >= DUMP_MAX_LINES || depth > DUMP_MAX_DEPTH) return
            val hidden = if (view.visibility != View.VISIBLE) "(invisible)" else ""
            lines.add(
                "  ".repeat(depth) + view.javaClass.simpleName + "#" + idName(view) + hidden +
                    " " + view.width + "x" + view.height
            )
            if (view is ViewGroup) {
                for (index in 0 until minOf(view.childCount, 10)) {
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

    private fun collectAllViews(root: View, out: MutableList<View>, depth: Int) {
        if (depth > MAX_DEPTH) return
        out.add(root)
        if (root is ViewGroup) {
            for (index in 0 until root.childCount) {
                root.getChildAt(index)?.let { collectAllViews(it, out, depth + 1) }
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
        val ACTION_BAR_ID_NAMES = listOf("action_bar", "action_bar_container", "title_bar", "title_bar_container")
        val ACTION_BAR_CLASS_NAMES = listOf("ActionBarContainer", "ActionBarLayout", "MMWindowActionBar", "TitleBarView")
        val BOTTOM_TAB_CLASS_NAMES = listOf("LauncherUIBottomTabView", "MMTabView", "BottomTabView", "HomeBottomTab")
        val BOTTOM_TAB_LABELS = setOf("微信", "通讯录", "发现", "我")
        const val SPLASH_DURATION_MS = 1200L
        const val MAX_DEPTH = 32
        const val DUMP_MAX_DEPTH = 8
        const val DUMP_MAX_LINES = 70
        const val ENFORCE_INTERVAL_MS = 400L
        const val SCRIM_INTERVAL_MS = 1200L
    }
}
