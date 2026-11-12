package com.aeonlab.toolkit.hooks.items.theme

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Build
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
    private val originalAlphas: MutableMap<View, Int> =
        Collections.synchronizedMap(WeakHashMap<View, Int>())
    private val originalViewAlphas: MutableMap<View, Float> =
        Collections.synchronizedMap(WeakHashMap<View, Float>())
    private val originalVisibilities: MutableMap<View, Int> =
        Collections.synchronizedMap(WeakHashMap<View, Int>())
    private val homeImageHosts: MutableMap<View, android.graphics.drawable.Drawable?> =
        Collections.synchronizedMap(WeakHashMap<View, android.graphics.drawable.Drawable?>())
    private val homeImageDrawables: MutableMap<View, android.graphics.drawable.BitmapDrawable> =
        Collections.synchronizedMap(WeakHashMap<View, android.graphics.drawable.BitmapDrawable>())
    private var lastHomeRepaintLogAt = 0L
    private val originalCacheColorHints: MutableMap<android.widget.AbsListView, Int> =
        Collections.synchronizedMap(WeakHashMap<android.widget.AbsListView, Int>())
    private val originalWillNotDraw: MutableMap<View, Boolean> =
        Collections.synchronizedMap(WeakHashMap<View, Boolean>())
    private val dumpedPages: MutableSet<View> =
        Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap<View, Boolean>()))
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
    private var lastDarkIcons = true
    private var lastActionBar: WeakReference<View>? = null
    private var actionBarDrawable: Drawable? = null
    private val topDumpedPages: MutableSet<View> =
        Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap<View, Boolean>()))
    private var framePumpUntil = 0L

    @Volatile
    private var lastScrimAt = 0L

    @Volatile
    private var lastRepaintLogAt = 0L

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
        applyWindowBackground(activity, decor, snapshot)
        applyActionBar(activity, decor, snapshot)
        applyBottomTab(decor, snapshot)
        applyPageBackgrounds(activity, decor, snapshot, log = true)
        applyTypeface(activity, decor)
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
        lastActionBar = WeakReference(bar)
        if (snapshot.actionBarHide) {
            if (bar.visibility != View.GONE) {
                bar.visibility = View.GONE
                hiddenViews.add(bar)
            }
        } else if (hiddenViews.remove(bar)) {
            bar.visibility = View.VISIBLE
        }
        val barColor = ThemeSettings.parseColor(snapshot.actionBarBackgroundColor)
        if (barColor == null) {
            // 5.5.60 起标题栏按真机截图置透明，让背景图从状态栏一直连到列表
            if (!replacedBackgrounds.containsKey(bar)) replacedBackgrounds[bar] = bar.background
            bar.setBackgroundColor(Color.TRANSPARENT)
            HLog.i("$TAG 标题栏已置透明（主题未指定标题栏色）")
        } else {
            bar.setBackgroundColor(barColor)
        }
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
        // 5.5.62 起跟随开关：隐藏原生底栏=开 → 真的隐藏（5.5.60 为了对齐微X 截图改成常显，
        // 与设置页开关语义冲突，用户实测报「隐藏底栏失效」，这里改回跟随开关）。
        if (snapshot.tabHideNative) {
            HLog.i("$TAG 隐藏原生底栏=开，隐藏底栏容器: ${tab.javaClass.name}#${idName(tab)}")
            hideView(tab)
            return
        }
        showView(tab)
        if (snapshot.tabBackgroundColor.isBlank()) {
            if (!replacedBackgrounds.containsKey(tab)) replacedBackgrounds[tab] = tab.background
            tab.setBackgroundColor(Color.TRANSPARENT)
        }
        applyTabColorsAndIcons(tab, snapshot)
        val missing = snapshot.tabIcons.count { it.isBlank() }
        if (missing > 0) {
            HLog.i("$TAG 底栏图标缺 ${missing}/4 个（需要 icons/tab_icon1~4.png），缺的保持微信默认")
        }
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
            if (log) {
                HLog.i(
                    "$TAG 页面 ${index + 1} 背景来源: 图片 $path 解码=${image.width}x${image.height}" +
                        " 叠色=${ThemeSettings.parseColor(snapshot.backgroundOverlay[slots[index]])?.let { "#" + Integer.toHexString(it) } ?: "-"}"
                )
            }
            applyPageBitmap(page, image, resources, overlay, log, path, index == 0)
        }
    }

    private fun applyPageBitmap(
        page: View,
        bitmap: Bitmap,
        resources: android.content.res.Resources,
        overlayColor: Int?,
        log: Boolean,
        path: String,
        isHome: Boolean
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
        pageScrim[page] = ScrimSpec(bitmap, overlayColor, path, isHome)
        // 5.5.54 的实测：背景只从列表之外露出来——列表项、分组头、发现页卡片各自带着不透明白底，
        // 铺在页面上的图被逐层盖住。这里做「内容分层」：
        //   整页容器（≥92% 宽高）→ 完全透明，让背景沉到最底层；
        //   整行/整卡（≥60% 宽）→ 换成按背景明暗算出的半透明遮罩，保证文字可读。
        applyContentScrim(page, bitmap, overlayColor, isHome, log)
        if (isHome) ensureHomeImageLayer(page, bitmap, log) else ensurePageImageHosts(page, bitmap, log)
    }

    private data class ScrimSpec(val bitmap: Bitmap, val overlayColor: Int?, val path: String, val isHome: Boolean)

    /**
     * 内容分层 v4（5.5.58 起，对齐微X 主题观感）：
     *   列表容器 / 页面根（高 ≥ 55% 页高）→ 完全透明，背景图从标题栏下沿一直铺到屏幕底部；
     *   列表行（宽 ≥ 30% 页宽、高 ≤ 55% 页高）→ 换成**通栏纯色**半透明白（不带圆角、不带卡片），
     *     并用 InsetDrawable 在行底留 6dp 空隙，让行与行之间露出背景；
     *   行内嵌套白块（已经被上层行罩住）→ 归零，绝不叠加；
     *   ≤ 4 像素的分割线 / 描边 → 原样保留；
     *   右侧 A–Z 索引条（窄、高、贴右）→ 背景去掉，字母直接压在背景图上。
     * 5.5.56 的四层 27% 白叠加（约 71%）已废弃。
     */
    private fun applyContentScrim(page: View, bitmap: Bitmap, overlayColor: Int?, isHome: Boolean, log: Boolean) {
        val pageWidth = page.width.coerceAtLeast(page.measuredWidth)
        val pageHeight = page.height.coerceAtLeast(page.measuredHeight)
        if (pageWidth <= 0 || pageHeight <= 0) return
        val stats = sampleImage(bitmap)
        val luma = stats[3]
        val scrim = scrimColorFor(luma, overlayColor, isHome)
        val density = page.resources.displayMetrics.density
        val gapPx = (ROW_GAP_DP * density).toInt()
        val counters = intArrayOf(0, 0, 0, 0, 0)
        val sample = ArrayList<String>()
        // 明细每页只打一次，避免 1.2 秒重扫刷屏
        val detail = log && dumpedPages.add(page)
        dumpTopRegion(page, detail)
        scrimTree(page, page, pageWidth, pageHeight, scrim, gapPx, false, counters, sample, detail, isHome)
        if (log) {
            HLog.i(
                "$TAG 页面 ${page.javaClass.simpleName} 内容分层：容器置透明 ${counters[0]} 个，" +
                    "通栏行压半透明 ${counters[1]} 个，索引条去底 ${counters[2]} 个，" +
                    "整页图片层透明 ${counters[3]} 个，" +
                    "行缝=${ROW_GAP_DP}dp 遮罩=#${Integer.toHexString(scrim)}" +
                    " 背景平均色=#${String.format(java.util.Locale.US, "%02X%02X%02X", (stats[0] * 255).toInt(), (stats[1] * 255).toInt(), (stats[2] * 255).toInt())}" +
                    " 亮度=${String.format(java.util.Locale.US, "%.2f", luma)} 背景图=${bitmap.width}x${bitmap.height}"
            )
            sample.forEach { HLog.i("$TAG   分层: $it") }
        }
    }

    /** 顶部 600px 稠密转储：白块到底挂在哪个 View 上，只能靠这份明细。 */
    private fun dumpTopRegion(page: View, log: Boolean) {
        if (!log) return
        if (!topDumpedPages.add(page)) return
        val lines = ArrayList<String>()
        fun walk(view: View, depth: Int) {
            if (lines.size >= 60 || depth > 12) return
            val w = view.width.coerceAtLeast(view.measuredWidth)
            val h = view.height.coerceAtLeast(view.measuredHeight)
            var top = 0
            var cur: View? = view
            while (cur != null && cur !== page) {
                top += cur.top
                cur = cur.parent as? View
            }
            if (top < 600 && w > 100) {
                val bg = view.background
                val desc = when {
                    bg == null -> "null"
                    bg is ColorDrawable -> "${bg.javaClass.simpleName}#${Integer.toHexString(bg.color)}"
                    else -> "${bg.javaClass.simpleName} a=${bg.alpha}"
                }
                lines.add(
                    "${"·".repeat(depth)}${view.javaClass.simpleName}#${idName(view)} ${w}x${h}" +
                        " top=$top bg=$desc vis=${view.visibility}"
                )
            }
            if (view is ViewGroup) for (i in 0 until view.childCount) walk(view.getChildAt(i), depth + 1)
        }
        walk(page, 0)
        HLog.i("$TAG 顶部区域转储(页面 ${page.javaClass.simpleName}): ${lines.size} 条")
        lines.forEach { HLog.i("$TAG   顶部: $it") }
    }

    private fun scrimTree(
        view: View,
        page: View,
        pageWidth: Int,
        pageHeight: Int,
        scrim: Int,
        gapPx: Int,
        underScrim: Boolean,
        counters: IntArray,
        sample: MutableList<String>,
        log: Boolean,
        isHome: Boolean
    ) {
        var scrimmed = underScrim
        if (view !== page && !pageDrawables.containsKey(view)) {
            val width = view.width.coerceAtLeast(view.measuredWidth)
            val height = view.height.coerceAtLeast(view.measuredHeight)
            if (width > 4 && height > 4) {
                val background = view.background
                val narrow = width <= pageWidth * 0.18f
                val tall = height >= pageHeight * 0.30f
                when {
                    // 右侧 A–Z 索引条：窄且高，去掉它自带的半透明衬底，字母直接压在背景上
                    narrow && tall -> {
                        if (background != null && background.alpha != 0) {
                            if (!replacedBackgrounds.containsKey(view)) replacedBackgrounds[view] = background
                            view.background = ColorDrawable(Color.TRANSPARENT)
                            counters[2]++
                            if (log && sample.size < 120) {
                                sample.add("${view.javaClass.simpleName}#${idName(view)} ${width}x${height} bg=${background.javaClass.simpleName}→索引条去底")
                            }
                        }
                    }
                    // 首页整片灰蓝的嫌疑：微信会话列表里盖着一张整页 ImageView（模糊/深色底），
                    // 5.5.58 之前 ImageView 一律跳过，于是它的底永远盖在背景图上。大图直接让位。
                    view is ImageView -> {
                        if (view.tag == HOME_IMAGE_TAG) {
                            if (log && sample.size < 120) {
                                sample.add("${view.javaClass.simpleName}#${idName(view)} ${width}x${height} 跳过(首页图片层本体)")
                            }
                        } else if (width >= pageWidth * 0.50f && height >= pageHeight * 0.25f) {
                            if (!originalViewAlphas.containsKey(view)) originalViewAlphas[view] = view.alpha
                            view.alpha = 0f
                            counters[3]++
                            if (log && sample.size < 120) {
                                sample.add(
                                    "${view.javaClass.simpleName}#${idName(view)} ${width}x${height}" +
                                        " drawable=${runCatching { view.drawable?.javaClass?.simpleName }.getOrNull() ?: "null"}→整页图片层透明"
                                )
                            }
                        } else if (log && sample.size < 120) {
                            sample.add("${view.javaClass.simpleName}#${idName(view)} ${width}x${height} 跳过(小图)")
                        }
                    }
                    // 顶部大色块（我页头像/微信号那一整块白）不在「≥55% 页高」里，之前一直没被清掉。
                    // 这里对页面顶部 45% 区域、宽度 ≥60% 页宽的不透明色块单独清一次。
                    view.top < pageHeight * 0.45f && width >= pageWidth * 0.50f &&
                        background != null && background !is BitmapDrawable &&
                        !(background is ColorDrawable && Color.alpha(background.color) == 0) -> {
                        if (!replacedBackgrounds.containsKey(view)) replacedBackgrounds[view] = background
                        val was = background.javaClass.simpleName +
                            (if (background is ColorDrawable) "#${Integer.toHexString(background.color)}" else "")
                        view.background = ColorDrawable(Color.TRANSPARENT)
                        counters[0]++
                        if (log && sample.size < 120) {
                            sample.add("${view.javaClass.simpleName}#${idName(view)} ${width}x${height} top=${view.top} bg=$was→顶部清底")
                        }
                    }
                    width < pageWidth * 0.30f -> {
                        if (log && sample.size < 120) {
                            sample.add("${view.javaClass.simpleName}#${idName(view)} ${width}x${height} 跳过(窄)")
                        }
                    }
                    background != null && background.alpha != 0 && !homeImageHosts.containsKey(view) -> {
                        if (!replacedBackgrounds.containsKey(view)) replacedBackgrounds[view] = background
                        val container = height >= pageHeight * 0.55f
                        if (container || scrimmed) {
                            view.background = ColorDrawable(Color.TRANSPARENT)
                            counters[0]++
                            if (log && sample.size < 120) {
                                sample.add("${view.javaClass.simpleName}#${idName(view)} ${width}x${height} bg=${background.javaClass.simpleName}→置透明")
                            }
                        } else {
                            // 通栏行：纯色（无圆角）＋ 行底留缝，让背景从行间透出来
                            view.background = InsetDrawable(ColorDrawable(scrim), 0, 0, 0, gapPx)
                            counters[1]++
                            scrimmed = true
                            if (log && sample.size < 120) {
                                sample.add("${view.javaClass.simpleName}#${idName(view)} ${width}x${height} bg=${background.javaClass.simpleName}→通栏行${ROW_GAP_DP}dp缝")
                            }
                        }
                    }
                }
            }
        }
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                view.getChildAt(index)?.let {
                    scrimTree(it, page, pageWidth, pageHeight, scrim, gapPx, scrimmed, counters, sample, log, isHome)
                }
            }
        }
    }

    /**
     * 行遮罩浓度：主题包自带叠色（c 字段）且不透明时直接用它；否则取白，
     * 按背景亮度在 55%~72% 之间取——行要能看清文字，同时背景仍要透上来。
     */
    private fun scrimColorFor(luma: Float, overlayColor: Int?, isHome: Boolean): Int {
        if (overlayColor != null && Color.alpha(overlayColor) > 0) return overlayColor
        // 5.5.60 起按微X 真机截图取值：行几乎全透（12/255），只留细分隔线；
        // 首页文字密，保留极淡一层（31/255），其它三页更透（13/255）。
        val alpha = if (isHome) HOME_ROW_ALPHA else CONTENT_ROW_ALPHA
        return Color.argb(alpha, 255, 255, 255)
    }

    /**
     * 首页图片层（5.5.63 起）：5.5.62 的日志证明 decor 级 DynamicBgContainer /
     * GradientColorBackgroundView 就是四页壁纸的挂载点（隐藏后四页全白），所以那一层必须留着；
     * 首页的深紫是同一层按「当前 fragment 的背景色」画的，改不掉它，只能在首页页面里**盖一层自己的图**。
     * 做法：把 ImageView 插到 pager 首页（LayoutListenerView）的第 0 个子节点（最底层），
     * 再把列表的 cacheColorHint 置透明，避免 AbsListView 用缓存色把图片刷白。
     */
    private fun ensureHomeImageLayer(page: View, bitmap: Bitmap, log: Boolean) {
        if (page !is ViewGroup) return
        val existing = page.findViewWithTag<View>(HOME_IMAGE_TAG)
        if (existing is ImageView) {
            runCatching { existing.setImageBitmap(bitmap) }
            repaintHomeHosts(log = false)
            if (log) HLog.i("$TAG 首页图片层已存在，更新图片: ${bitmap.width}x${bitmap.height}")
            return
        }
        val image = ImageView(page.context)
        image.tag = HOME_IMAGE_TAG
        image.scaleType = ImageView.ScaleType.FIT_XY
        image.setImageBitmap(bitmap)
        runCatching {
            page.addView(
                image,
                0,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
        }.onFailure { HLog.e("$TAG 首页图片层插入失败: ${it.message}", it) }
        // 5.5.63 的日志：首页页面本身已经铺了背景图（背景平均色=#9FD8DB 是青绿壁纸），
        // 但屏幕上仍是深紫 ⇒ 紫是页面之上、列表容器自己重新上的底色（ConversationListView / WxRecyclerView /
        // LinearLayout 都是 1240x2772 的整页容器）。这些容器我们只在扫一遍时置透明，
        // 宿主一重新 setBackgroundColor 就又紫了。这里把首页的整页容器直接铺成背景图，
        // 并在 enforce 里持续压回（见 repaintHomeHosts）。
        ensurePageImageHosts(page, bitmap, log)
        var hints = 0
        collectCacheColorHints(page) { list ->
            if (!originalCacheColorHints.containsKey(list)) originalCacheColorHints[list] = list.cacheColorHint
            list.cacheColorHint = Color.TRANSPARENT
            hints++
        }
        if (log) {
            HLog.i(
                "$TAG 首页图片层已插入: 解码=${bitmap.width}x${bitmap.height} 子节点=${page.childCount}" +
                    " 列表cacheColorHint置透明=$hints 个"
            )
        }
    }

    private fun pageWidthOf(page: View): Int = page.width.coerceAtLeast(page.measuredWidth)

    private fun pageHeightOf(page: View): Int = page.height.coerceAtLeast(page.measuredHeight)

    /**
     * 首页整页级容器（≥85% 页宽、≥45% 页高）。5.5.63 日志里命中的是
     * ConversationListView#j8g / WxRecyclerView#mg / LinearLayout#imk 三个 1240x2772 的容器。
     */
    /** 页面内整页级容器（≥85% 页宽、≥15% 页高）统一铺背景图：我页头像/微信号那块白、首页列表容器都靠它透出壁纸。 */
    private fun ensurePageImageHosts(page: View, bitmap: Bitmap, log: Boolean) {
        if (page !is ViewGroup) return
        val hosts = ArrayList<View>()
        val pageW = pageWidthOf(page)
        val pageH = pageHeightOf(page)
        for (index in 0 until page.childCount) {
            collectBigContainers(page.getChildAt(index), pageW, pageH, hosts)
        }
        val drawable = homeImageDrawables[page]
            ?: android.graphics.drawable.BitmapDrawable(page.resources, bitmap)
        homeImageDrawables[page] = drawable
        var painted = 0
        hosts.forEach { host ->
            if (!homeImageHosts.containsKey(host)) homeImageHosts[host] = host.background
            homeImageDrawables[host] = drawable
            if (host.background !== drawable) {
                runCatching { host.background = drawable }.onSuccess { painted++ }
            }
        }
        if (log && painted > 0) {
            HLog.i("$TAG 页面容器铺图: ${painted} 个整页容器已铺背景图（${bitmap.width}x${bitmap.height}）")
        }
    }

    private fun collectBigContainers(view: View, pageWidth: Int, pageHeight: Int, out: MutableList<View>) {
        // 页面本身由 pageDrawables 负责，这里只管页面内的容器，避免两套 BitmapDrawable 互相覆盖（5.5.64 的 31/25 次来回刷）
        if (view is ViewGroup) {
            val width = view.width.coerceAtLeast(view.measuredWidth)
            val height = view.height.coerceAtLeast(view.measuredHeight)
            if (width >= pageWidth * 0.85f && height >= pageHeight * 0.15f) out.add(view)
            for (index in 0 until view.childCount) {
                val child = view.getChildAt(index)
                if (child.tag == HOME_IMAGE_TAG) continue
                collectBigContainers(child, pageWidth, pageHeight, out)
            }
        }
    }

    /** 宿主把首页容器的底色刷回去时，把背景图重新压上（enforce 每次都会调）。 */
    private fun repaintHomeHosts(log: Boolean) {
        if (homeImageDrawables.isEmpty()) return
        homeImageHosts.keys.toList().forEach { host ->
            val drawable = homeImageDrawables[host] ?: return@forEach
            if (host.background !== drawable) {
                runCatching { host.background = drawable }
                if (log) {
                    val now = SystemClock.uptimeMillis()
                    if (now - lastHomeRepaintLogAt >= 3000L) {
                        lastHomeRepaintLogAt = now
                        HLog.i(
                            "$TAG 首页容器底色被宿主刷回（当前=${host.background?.javaClass?.simpleName ?: "null"}），已重新压回背景图: " +
                                "${host.javaClass.simpleName}#${idName(host)}"
                        )
                    }
                }
            }
        }
    }

    private fun collectCacheColorHints(view: View, action: (android.widget.AbsListView) -> Unit) {
        if (view is android.widget.AbsListView) action(view)
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                collectCacheColorHints(view.getChildAt(index), action)
            }
        }
    }

    /**
     * 状态栏区域与标题栏：真机截图里背景图一直铺到状态栏，标题栏是透明的。
     * 做法是把首页那张图挂到 decorView 上，再把状态栏/导航栏置透明；
     * 页面自己的背景仍然盖在上面（同图同尺寸，看不出接缝）。
     */
    @Suppress("DEPRECATION")
    private fun applyWindowBackground(activity: Activity, decor: ViewGroup, snapshot: ThemeSnapshot) {
        if (!snapshot.backgroundEnabled) return
        runCatching {
            val window = activity.window ?: return@runCatching
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
            window.statusBarColor = Color.TRANSPARENT
            window.navigationBarColor = Color.TRANSPARENT
        }.onFailure { HLog.e("$TAG 状态栏透明化失败: ${it.message}", it) }
        val path = snapshot.backgroundHome
        if (path.isBlank()) {
            HLog.i("$TAG 状态栏区域：首页背景图未设置，状态栏区域保持系统默认")
            return
        }
        val image = bitmap(path) ?: return
        val drawable: Drawable = BitmapDrawable(activity.resources, image)
        if (decor.background !== drawable) {
            if (!replacedBackgrounds.containsKey(decor)) replacedBackgrounds[decor] = decor.background
            decor.setBackgroundDrawable(drawable)
        }
        HLog.i("$TAG 状态栏区域已铺背景图: 解码=${image.width}x${image.height} 标题栏透明=${snapshot.actionBarBackgroundColor.isBlank()}")
        runCatching { findActionBar(activity, decor)?.let { paintActionBar(decor, it, image, log = true) } }
            .onFailure { HLog.e("$TAG 标题栏铺图失败: ${it.message}", it) }
        applyImmersiveStatusBar(activity, decor, image)
    }

    /**
     * 全沉浸状态栏 + 标题卡片全透明（5.5.61 起）：
     * 1) 窗口加 LAYOUT_FULLSCREEN/LAYOUT_STABLE，状态栏与导航栏都置透明，背景图从屏幕最顶端开始；
     * 2) 按背景图平均亮度决定状态栏图标深浅：亮图用深色图标（LIGHT_STATUS_BAR），暗图用浅色图标；
     * 3) 把标题区那一串 View 的底色全部清空——View#statusBarBackground（状态栏那条底色）、
     *    FitSystemWindowLayoutView / ActionBarOverlayLayout#crd / ContentFrameLayout#content /
     *    ActionBarContainer#ei，任何一处留色都会在标题位置露出一块灰白。
     */
    @Suppress("DEPRECATION")
    private fun applyImmersiveStatusBar(activity: Activity, decor: ViewGroup, image: Bitmap) {
        val stats = sampleImage(image)
        val luma = stats[3]
        val darkIcons = luma >= 0.55f
        lastDarkIcons = darkIcons
        runCatching {
            var flags = View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            if (darkIcons) flags = flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            decor.systemUiVisibility = flags
            activity.window?.let {
                it.statusBarColor = Color.TRANSPARENT
                it.navigationBarColor = Color.TRANSPARENT
            }
        }.onFailure { HLog.e("$TAG 全沉浸状态栏设置失败: ${it.message}", it) }
        // API 30+ 只设 systemUiVisibility 不管用：必须同时关掉「decor 让出系统栏」，
        // 否则内容仍被顶到状态栏下面，顶部留一条白。这里连 FitSystemWindowLayoutView 的顶部 padding 一起清掉。
        var paddingCleared = 0
        runCatching {
            if (Build.VERSION.SDK_INT >= 30) activity.window?.setDecorFitsSystemWindows(false)
            if (decor.fitsSystemWindows) decor.fitsSystemWindows = false
            for (index in 0 until decor.childCount) {
                val child = decor.getChildAt(index)
                if (child.fitsSystemWindows) child.fitsSystemWindows = false
                if (child.paddingTop != 0) {
                    child.setPadding(child.paddingLeft, 0, child.paddingRight, child.paddingBottom)
                    paddingCleared++
                }
            }
            findViewByClassName(decor, "FitSystemWindowLayoutView")?.let { fit ->
                fit.fitsSystemWindows = false
                if (fit.paddingTop != 0) {
                    fit.setPadding(fit.paddingLeft, 0, fit.paddingRight, fit.paddingBottom)
                    paddingCleared++
                }
            }
        }.onFailure { HLog.e("$TAG 关闭系统栏让位失败: ${it.message}", it) }
        if (paddingCleared > 0) {
            HLog.i("$TAG 状态栏让位已关闭: decorFitsSystemWindows=false 清顶部padding=$paddingCleared 处")
        }

        val targets = ArrayList<View>()
        listOf("statusBarBackground", "action_bar", "action_bar_container", "content").forEach { name ->
            val id = runCatching { activity.resources.getIdentifier(name, "id", HOST_PACKAGE) }.getOrDefault(0)
            if (id != 0) runCatching { decor.findViewById<View>(id) }.getOrNull()?.let { targets.add(it) }
        }
        listOf("ActionBarOverlayLayout", "ContentFrameLayout", "ActionBarContainer", "FitSystemWindowLayoutView").forEach { className ->
            findViewByClassName(decor, className)?.let { targets.add(it) }
        }
        var cleared = 0
        val names = ArrayList<String>()
        targets.distinct().forEach { view ->
            val background = view.background ?: return@forEach
            if (background is ColorDrawable && Color.alpha(background.color) == 0) return@forEach
            if (!replacedBackgrounds.containsKey(view)) replacedBackgrounds[view] = background
            view.setBackgroundColor(Color.TRANSPARENT)
            cleared++
            names.add("${view.javaClass.simpleName}#${idName(view)}")
        }
        HLog.i(
            "$TAG 标题卡片区域已全透明: 清空 $cleared 处底色 [${names.joinToString("/")}]" +
                " 状态栏全沉浸: 图标=${if (darkIcons) "深色" else "浅色"}" +
                " 背景亮度=${String.format(java.util.Locale.US, "%.2f", luma)}"
        )
    }

    /** 书法字体：主题包目录里放 font.ttf / font.otf 才会生效，没放就明确报缺。 */
    private fun applyTypeface(activity: Activity, decor: ViewGroup) {
        val dir = ThemeImageStore.directory(context.hostContext())
        val candidates = listOf("font.ttf", "font.otf", "typeface.ttf", "typeface.otf")
        val file = candidates.map { java.io.File(dir, it) }.firstOrNull { it.isFile && it.length() > 0 }
        if (file == null) {
            HLog.i("$TAG 书法字体：主题包目录 ${dir.absolutePath} 下没有 font.ttf/font.otf，本轮未应用字体（需要字体文件）")
            return
        }
        val typeface = runCatching { android.graphics.Typeface.createFromFile(file) }.getOrNull()
        if (typeface == null) {
            HLog.e("$TAG 书法字体读取失败: ${file.absolutePath}")
            return
        }
        val labels = ArrayList<TextView>()
        collectTextViews(decor, labels)
        labels.forEach { runCatching { it.typeface = typeface } }
        HLog.i("$TAG 书法字体已应用: ${file.name} 覆盖 ${labels.size} 个文本")
    }

    /** 采样背景图：返回 [r, g, b, 亮度]，0~1。 */
    private fun sampleImage(bitmap: Bitmap): FloatArray = runCatching {
        val stepX = (bitmap.width / 24).coerceAtLeast(1)
        val stepY = (bitmap.height / 24).coerceAtLeast(1)
        var sumR = 0.0
        var sumG = 0.0
        var sumB = 0.0
        var count = 0
        var y = 0
        while (y < bitmap.height) {
            var x = 0
            while (x < bitmap.width) {
                val color = bitmap.getPixel(x, y)
                sumR += Color.red(color) / 255.0
                sumG += Color.green(color) / 255.0
                sumB += Color.blue(color) / 255.0
                count++
                x += stepX
            }
            y += stepY
        }
        if (count == 0) {
            floatArrayOf(0.5f, 0.5f, 0.5f, 0.5f)
        } else {
            val r = (sumR / count).toFloat()
            val g = (sumG / count).toFloat()
            val b = (sumB / count).toFloat()
            floatArrayOf(r, g, b, 0.299f * r + 0.587f * g + 0.114f * b)
        }
    }.getOrDefault(floatArrayOf(0.5f, 0.5f, 0.5f, 0.5f))

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
        // 5.5.63 真机：通讯录/发现/我刚进入时标题栏白、没有背景图，手指点一下才刷出来。
        // 根因是兜底逻辑挂在 OnPreDrawListener 上——没有 invalidate 就不会触发绘制，
        // 也就永远不会跑 enforce；首帧之后只能等用户触摸。这里改成定时补刷 + 每轮结束强制重绘。
        FIRST_FRAME_DELAYS_MS.forEach { delay ->
            mainHandler.postDelayed({
                runCatching {
                    enforce(activity, decor, force = true)
                    decor.postInvalidateOnAnimation()
                }.onFailure { HLog.e("$TAG 首帧补刷异常: ${it.message}", it) }
            }, delay)
        }
        HLog.i("$TAG 首帧补刷已排程: ${FIRST_FRAME_DELAYS_MS.joinToString("/")}ms，每轮结束后强制 invalidate")
        decor.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                runCatching { enforce(activity, decor) }
                    .onFailure { HLog.e("$TAG 兜底重刷异常: ${it.message}", it) }
                return true
            }
        })
    }

    /**
     * 状态栏沉浸的「补注」：微信在 resume / 首帧会用 setSystemUiVisibility(0) 和
     * decorFitsSystemWindows(true) 把 5.5.61 设的全沉浸重置掉，只在 applyWindowBackground 里设一次不够。
     * 这里每轮 enforce 比对一次，被改回就重新注入并打日志。
     */
    private fun reassertImmersive(activity: Activity, decor: ViewGroup, force: Boolean) {
        if (!decor.isAttachedToWindow) return
        var flags = View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        if (lastDarkIcons) flags = flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        val current = decor.systemUiVisibility
        if (current != flags) {
            runCatching { decor.systemUiVisibility = flags }
            HLog.i(
                "$TAG 状态栏沉浸被宿主重置，已重新注入: 0x${Integer.toHexString(current)} → 0x${Integer.toHexString(flags)}"
            )
        }
        runCatching {
            activity.window?.let { window ->
                if (Build.VERSION.SDK_INT >= 30) window.setDecorFitsSystemWindows(false)
                if (window.statusBarColor != Color.TRANSPARENT) window.statusBarColor = Color.TRANSPARENT
                if (window.navigationBarColor != Color.TRANSPARENT) window.navigationBarColor = Color.TRANSPARENT
            }
        }
        if (force) {
            // 兜底补刷期间顺带把标题区底色再清一遍（宿主会重新上白）
            var cleared = 0
            listOf("ActionBarOverlayLayout", "ContentFrameLayout", "ActionBarContainer", "FitSystemWindowLayoutView").forEach { className ->
                findViewByClassName(decor, className)?.let { view ->
                    val background = view.background
                    if (background is ColorDrawable && Color.alpha(background.color) != 0) {
                        if (!replacedBackgrounds.containsKey(view)) replacedBackgrounds[view] = background
                        view.setBackgroundColor(Color.TRANSPARENT)
                        cleared++
                    }
                }
            }
            if (cleared > 0) HLog.i("$TAG 标题区底色被宿主重上，已再清 $cleared 处")
        }
    }

    /**
     * 标题栏真正消失（5.5.67 起）：
     * 5.5.66 日志里「标题栏被微信恢复显示，重新隐藏」37 次，说明 hideView 的 GONE 每次都被微信自己改回来，
     * 微信赢在这条路上；而「标题栏已置透明」26 次也挡不住白底，因为白底在 ActionBarContainer 的**子 View**
     * （ActionBarView / 标题 TextView / 搜索与加号按钮）上，不在容器本身。
     * 所以改成：容器背景铺成背景图（不再是白/浅灰），子 View 全部 GONE（标题文字与图标彻底不显示），
     * enforce 每轮再压一次。这样即使微信把容器显示回来，顶部也是背景图、没有白条和标题。
     */
    private fun paintActionBar(decor: ViewGroup, bar: View, bitmap: Bitmap, log: Boolean) {
        val drawable = actionBarDrawable ?: android.graphics.drawable.BitmapDrawable(bar.resources, bitmap)
        actionBarDrawable = drawable
        var changed = false
        if (!replacedBackgrounds.containsKey(bar)) replacedBackgrounds[bar] = bar.background
        if (bar.background !== drawable) {
            runCatching { bar.background = drawable }
            changed = true
        }
        var hidden = 0
        if (bar is ViewGroup) {
            for (index in 0 until bar.childCount) {
                val child = bar.getChildAt(index)
                if (child.visibility != View.GONE) {
                    if (!originalVisibilities.containsKey(child)) originalVisibilities[child] = child.visibility
                    runCatching { child.visibility = View.GONE }
                    hidden++
                }
            }
        }
        if (log && (changed || hidden > 0)) {
            HLog.i(
                "$TAG 标题栏已铺背景图并隐藏子树: 容器=${bar.javaClass.simpleName}#${idName(bar)}" +
                    " 底色改写=${changed} 子View隐藏=$hidden 个（标题文字/搜索/加号）"
            )
        }
    }

    /**
     * 首帧补刷：只靠 OnPreDraw 会「不触摸就不重绘 → 不跑 enforce」，
     * 只靠定时器又会漏掉页面重建的那一帧。这里用 Choreographer 逐帧推 2.4 秒，
     * 保证进入页面后每一帧都跑一次 enforce(force=true) 并强制 invalidate。
     */
    private fun installFramePump(activity: Activity, decor: ViewGroup) {
        val now = SystemClock.uptimeMillis()
        if (now < framePumpUntil) return
        framePumpUntil = now + FRAME_PUMP_MS
        HLog.i("$TAG 首帧逐帧补刷已启动: ${FRAME_PUMP_MS}ms 内每帧 enforce")
        val choreographer = runCatching { android.view.Choreographer.getInstance() }.getOrNull() ?: return
        val callback = object : android.view.Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                if (SystemClock.uptimeMillis() > framePumpUntil) return
                if (!decor.isAttachedToWindow) return
                runCatching { enforce(activity, decor, force = true) }
                    .onFailure { HLog.e("$TAG 逐帧补刷异常: ${it.message}", it) }
                runCatching { decor.postInvalidateOnAnimation() }
                choreographer.postFrameCallback(this)
            }
        }
        choreographer.postFrameCallback(callback)
    }

    private fun enforce(activity: Activity, decor: ViewGroup, force: Boolean = false) {
        if (!decor.isAttachedToWindow) return
        val now = SystemClock.uptimeMillis()
        if (!force && now - lastEnforceAt < ENFORCE_INTERVAL_MS) return
        lastEnforceAt = now
        val snapshot = ThemeSettings.snapshot(context.hostContext())
        if (!snapshot.enabled) return
        if (snapshot.actionBarHide) {
            var bar = lastActionBar?.get()
            if (bar != null && !bar.isAttachedToWindow) bar = null
            if (bar == null) bar = findActionBar(activity, decor)
            if (bar != null) {
                lastActionBar = WeakReference(bar)
                val barBitmap = pageScrim.values.firstOrNull()?.bitmap
                if (barBitmap != null) {
                    paintActionBar(decor, bar, barBitmap, log = false)
                } else if (bar.visibility != View.GONE) {
                    HLog.i("$TAG 标题栏被微信恢复显示，重新隐藏: ${bar.javaClass.name}#${idName(bar)}")
                    hideView(bar)
                }
            }
        }
        if (snapshot.backgroundEnabled) reassertImmersive(activity, decor, force)
        if (snapshot.tabEnabled) {
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
                    if (now - lastRepaintLogAt >= 3000L) {
                        lastRepaintLogAt = now
                        HLog.i(
                            "$TAG 页面背景被宿主换掉（当前=${page.background?.javaClass?.simpleName ?: "null"}），已重铺成背景图"
                        )
                    }
                }
            }
            repaintHomeHosts(log = true)
            if (now - lastScrimAt >= SCRIM_INTERVAL_MS) {
                lastScrimAt = now
                pageScrim.entries.toList().forEach { (page, spec) ->
                    runCatching { applyContentScrim(page, spec.bitmap, spec.overlayColor, spec.isHome, log = false) }
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
        originalAlphas.forEach { (view, alpha) ->
            runCatching { view.background?.alpha = alpha }
        }
        originalAlphas.clear()
        originalViewAlphas.forEach { (view, alpha) ->
            runCatching { view.alpha = alpha }
        }
        originalViewAlphas.clear()
        originalCacheColorHints.forEach { (view, hint) ->
            runCatching { view.cacheColorHint = hint }
        }
        originalCacheColorHints.clear()
        originalWillNotDraw.forEach { (view, flag) ->
            runCatching { view.setWillNotDraw(flag) }
        }
        originalWillNotDraw.clear()
        homeImageHosts.entries.toList().forEach { (view, background) ->
            runCatching { view.background = background }
        }
        homeImageHosts.clear()
        homeImageDrawables.clear()
        originalVisibilities.forEach { (view, visibility) ->
            runCatching { view.visibility = visibility }
        }
        originalVisibilities.clear()
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
        val FIRST_FRAME_DELAYS_MS = longArrayOf(0L, 60L, 160L, 320L, 700L, 1500L, 3000L)
        const val ENFORCE_INTERVAL_MS = 400L
        const val SCRIM_INTERVAL_MS = 250L
        const val FRAME_PUMP_MS = 2400L
        const val ROW_GAP_DP = 6f
        const val HOME_IMAGE_TAG = "hchat_home_image_layer"
        const val HOME_ROW_ALPHA = 31
        const val CONTENT_ROW_ALPHA = 13
    }
}
