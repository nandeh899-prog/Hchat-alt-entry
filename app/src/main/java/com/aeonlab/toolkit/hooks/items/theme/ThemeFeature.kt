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
import android.view.Window
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
import android.graphics.Canvas
import android.graphics.Paint
import com.aeonlab.toolkit.xposed.compat.XC_MethodHook
import com.aeonlab.toolkit.xposed.compat.XposedBridge
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
    /** 710：633 缝隙清线已处理过的列表实例（每实例只清一次，防止 706 那种反复清同一个列表导致四页乱屏）。 */
    private val seamClearedLists710: MutableSet<View> = Collections.newSetFromMap(WeakHashMap<View, Boolean>())
    /** 710：已挂过布局监听的 decor（每个 decor 只挂一个监听）。 */
    private val seamClearerDecors710: MutableSet<ViewGroup> = Collections.newSetFromMap(WeakHashMap<ViewGroup, Boolean>())
    /** 710：被 applyEarlyWindowBackground 改写成壁纸的 window 底原值；还原 decor 时必须连这一层一起还原，否则设置页/非主题页会继续透出壁纸。 */
    private val replacedWindowBackgrounds: MutableMap<Window, Drawable?> =
        Collections.synchronizedMap(WeakHashMap<Window, Drawable?>())
    /** 710：633 缝隙清线的走树限频时间戳。 */
    @Volatile private var lastSeamWalk710 = 0L
    /** 710：已打过「视图树里没有列表」日志的 decor（每个 decor 只打一次，避免刷屏）。 */
    private val seamEmptyLogged710: MutableSet<ViewGroup> = Collections.newSetFromMap(WeakHashMap<ViewGroup, Boolean>())
    private val homeImageHosts: MutableMap<View, android.graphics.drawable.Drawable?> =
        Collections.synchronizedMap(WeakHashMap<View, android.graphics.drawable.Drawable?>())
    private val homeImageDrawables: MutableMap<View, ScreenAnchoredBitmapDrawable> =
        Collections.synchronizedMap(WeakHashMap<View, ScreenAnchoredBitmapDrawable>())
    /** 5.5.104：全屏锚定基准尺寸（decorView 宽高）。所有背景层共用同一组缩放值，接缝才会消失。 */
    @Volatile private var anchorWidth = 0
    @Volatile private var anchorHeight = 0
    /** 710j：布局落定后强制重录锚定宿主的排期句柄与统计。 */
    private var settleReRecordRunnable: Runnable? = null
    private var settleReRecordCount710 = 0
    private var settleReRecordLogAt710 = 0L
    private var lastHomeRepaintLogAt = 0L
    private val originalCacheColorHints: MutableMap<android.widget.AbsListView, Int> =
        Collections.synchronizedMap(WeakHashMap<android.widget.AbsListView, Int>())
    private val originalWillNotDraw: MutableMap<View, Boolean> =
        Collections.synchronizedMap(WeakHashMap<View, Boolean>())
    /** 被我们清成透明的视图；宿主把底色写回来后要能重新清（5.5.72）。 */
    private val rowScrimDrawables: MutableMap<View, Drawable> =
        Collections.synchronizedMap(WeakHashMap<View, Drawable>())
    private val clearedViews: MutableSet<View> =
        Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap<View, Boolean>()))
    /** 已经真正清到底色的页面；没进这个集合的页面会在 enforce 里重试（5.5.72）。 */
    private val scrimmedPages: MutableSet<View> =
        Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap<View, Boolean>()))
    private val scrimRetryCount: MutableMap<View, Int> =
        Collections.synchronizedMap(WeakHashMap<View, Int>())
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

    // ===== 613：冷启动/切页铺底时序 =====
    /** page → 0..3 槽位，供切页预热与背景写入拦截使用。 */
    private val pageSlots: MutableMap<View, Int> =
        Collections.synchronizedMap(WeakHashMap<View, Int>())
    /** 已挂 layout 监听的页面：页面内容第一次 inflate 时靠它在 draw 之前补铺。 */
    private val pageLayoutWatched: MutableSet<View> =
        Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap<View, Boolean>()))
    private val pagePrepAt: MutableMap<View, Long> =
        Collections.synchronizedMap(WeakHashMap<View, Long>())
    /** 页面结构的廉价指纹：只有内容真的建起来/重建了才重跑铺底，避免 layout→setBackground→layout 打转。 */
    private val pageSignatureAt: MutableMap<View, Int> =
        Collections.synchronizedMap(WeakHashMap<View, Int>())
    private val earlyWindowApplied: MutableSet<Activity> =
        Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap<Activity, Boolean>()))
    private var lastHomePager: WeakReference<ViewGroup>? = null
    @Volatile private var guardHits = 0L
    @Volatile private var lastGuardLogAt = 0L
    private var pagePrepCount = 0
    private var lastPageSkipProbeAt = 0L
    private var fastAssertLogged = false
    private val guardTransparent = ColorDrawable(Color.TRANSPARENT)

    // ===== 614：进页前铺底 + 滚动露白 =====
    private var frameSweepInstalled = false
    private var scrollProbe614 = false
    private var lastPagerIndex = -1
    private var lastSweepAt = 0L
    private var lastSweepLogAt = 0L
    private var sweepRuns = 0
    private var huntHits = 0L
    private var huntLogs = 0
    @Volatile private var lastScrollStateLogAt = 0L

    // ===== 615 =====
    private val pageLists615 = WeakHashMap<View, List<android.widget.AbsListView>>()
    private var listBgRestores = 0L
    private var lastListRestoreLogAt = 0L
    private var stripHits = 0L
    // ===== 617 =====
    private var lastPagerScrollAt617 = 0L
    private var followLogCount = 0
    private val pageBitmapByPage = WeakHashMap<View, Bitmap>()
    // ===== 619：只读定位探针（不写任何背景，避免 618 那种每帧 setBackground 引发的重绘抖动）=====
    private var lastLineLocateAt = 0L
    private var lineLocateRuns = 0
    private var lastPixelAt = 0L
    private var pixelRuns = 0
    // ===== 621：过渡帧栏位 / 标题栏-列表缝隙 只读实证 =====
    private var lastBarProbeAt621 = 0L
    private var barProbeCount621 = 0
    private var lastGapProbeAt621 = 0L
    private var gapProbeRuns621 = 0
    // ===== 622：ActionBarContainer 内部 Drawable 字段 / 缝隙像素（只画容器子树）=====
    private var lastSeamAt622 = 0L
    private var seamRuns622 = 0
    private var barFieldDumps622 = 0
    // ===== 623：标题带像素 + DynamicBgContainer（微信动态背景容器）=====
    private var lastPixel623 = 0L
    private var pixelRuns623 = 0
    private var dynFieldDumps623 = 0
    // ===== 624：整窗 decor 直画两条带（缝隙带只在主页静置时采、标题带只在过渡帧采）=====
    private var lastSeamDraw624 = 0L
    private var seamDraws624 = 0
    private var lastFlashDraw624 = 0L
    private var flashDraws624 = 0
    // ===== 625：逐帧状态追踪（抓 1 帧闪白）+ 全高度行剖面（找任意 y 的黑线）=====
    private var traceFrames625 = 0
    // ===== 626：PixelCopy 真帧缓冲取证（软件画布看不到硬件层）+ 缝隙 View 精确定位 =====
    private var lastPcSeam626 = 0L
    private var pcSeam626 = 0
    private var lastPcTitle626 = 0L
    private var pcTitle626 = 0
    private var seamDump626 = 0
    // ===== 628：缝隙淡线一次性清除（找到 287/288 的 #1a000000 1px 分隔）+ 窄条逐帧闪白取证 =====
    private var seamFix628Done = 0
    private var seamFix628At = 0L
    private var stripRuns628 = 0
    private var lastStrip628 = 0L
    private var painterRuns628 = 0
    // 628b（用户 09:50 新位置假设）：小程序底栏上沿带的取样 + ActionBar 附着/可见性差分
    private var miniBarRuns628 = 0
    private var lastMiniBar628 = 0L
    private var prevPageIdx628 = -1
    // ===== 629：用户截图实证（1240x2772 原分辨率，无需缩放）—— 黑线 = y=287..288 整宽 2px =====
    // 逐行实测：y=287/288 亮度 221.6、上下邻行 240~241，色 #eae5bb；x=0..1239 全宽（1188/1240 列都被压暗 8~20 级）。
    // 全宽 + 10% 黑 ⇒ 不是 x=236 起的行内底衬，而是**列表自己的分隔线**（ListView.divider / RecyclerView 分隔 Decoration）。
    private var divFix629 = 0
    private var lastDiv629 = 0L
    // 630：628 日志证明「窄条 x=100..140、y=139..289」40 帧白占比恒 0、abAtt 恒 true，
    // 闪白既不在那条窄条里、也不是 ActionBar 脱离所致 → 改为**整窗逐帧**找「整宽近白带 + 相邻帧突变」。
    private var flashRuns630 = 0
    // 631：629 的 divider 已被日志证否（`ListView divider=null h=0`、`装饰=null`，线仍在）→ 改逐层隔离
    private var layerRuns631 = 0
    // 632：631 日志已锁定「线由 ConversationListView 自己画出」（decor/页0/列表三层都有 y=287 凹陷，行0/标题栏没有）
    private var layerRuns632 = 0
    // 634：闪白改为「切页事件驱动的定点抓帧」（定时采样已确认漏帧：12:29 日志 10 帧全无标题带白帧）
    private var burstArm634 = 0
    // 635：634 的 setCurrentItem 钩子真机零触发（日志里没有「切页事件(634)」），改成由 onPageScrolled 的
    // 「起划突变」与「静置到位」两种事件触发定点抓帧；同时把标题栏白底写入就地拦成透明。
    private var burstArm635 = 0
    private var barWrites635 = 0
    private var titleWhiteBlocked635 = 0
    // 636：635 已把标题栏那条 #ffededed 白写入拦掉（用户确认标题栏不闪了），但状态栏那条
    // （View#statusBarBackground y=0 1240x139）是同一机制、不同 View，635 的类名过滤没覆盖它。
    private var barWrites636 = 0
    private var statusWhiteBlocked636 = 0
    private var lastSettle635 = -1L
    private var lastScrollX635 = -1
    private var barsDump634 = 0
    // 637：小拉小程序面板首次打开时，底栏上方多一层半透明白；切后台再回来就没了。
    // 本轮先逐层 dump 该带的所有 View，并把「半透明白底」一次性清成透明（≤3 次、不写每帧）。
    private var panelProbe637 = 0
    // 638：637 的 dump 命中=0 —— 面板子树里根本没有落在 2150..2660 的 View，
    // 说明那层半透明白是「面板子树之外的兄弟层」。638 改成全 decor 扫描 y=2500..2700，
    // 并排除 LauncherUIBottomTabView 子树（保护底栏锚定与 微信(N)/🔍/＋）。
    private var bandProbe638 = 0
    // 639：透出的底图错位 = ScreenAnchoredBitmapDrawable 在面板手势中途录制了 display list，
    // 把 T(-static) 烙进录制内容；RenderNode 位移不触发重录，只有重录（切后台重开）才修好。
    // 本轮在「面板已落定（drift≤2）」时一次性强制重录面板子树里的锚定底图，并把参数打全。
    private var panelSync639 = 0
    // 640：639 被证伪（真机无变化）。而且 639 的日志显示它压根没找到真正的面板：
    // `面板壁纸重录(639) 第1/2次 面板=AppBrandDesktopContainerView loc=0,-2434` —— 抓到的是
    // 屏幕上方 -2434 那个「未展开的面板实例」，所以 重录=0，什么都没做。
    // 640 改为：①在屏面板判定（矩形必须与屏幕相交）；②把 y=2440..屏底 这条带对每个候选 View
    // 单独渲染成 bitmap，与 PixelCopy 真帧逐像素比对，用「差值最小的那个」定归属；
    // ③同时把带内「高度 ≤600、背景是锚定壁纸」的宿主背景一次性清空（露出 decor 上唯一正确的壁纸）。
    private var band640 = 0
    private var last640 = 0L
    private var drop640 = 0
    // 641：640 的「带内归属」差值全部饱和在 159.4（单层渲染时未绘制区域是全透明，
    // 与真帧比较时差值被真帧亮度主导，指标无效）；同时 640 的摘层按「高度 ≤600」筛选，
    // 而真正的宿主是 2722 高的面板容器，被筛掉了 —— 移除=0 与「带没变化」一致。
    // 本轮直接按机制处理：面板那份锚定壁纸就在面板子树里，面板展开靠 translationY，
    // 而锚定只补偿静置布局位置，于是这份壁纸跟着面板一起位移 → 露出错位的一片。
    private var panelDrop641 = 0
    // 642：641 又被同一个未展开实例骗了（面板y=-2434..288 摘=0）。
    // 本轮换成「改一处、看一处帧」的因果测法：先抓带内真帧基线，再逐个把候选层的背景临时置空、
    // 隔一帧重抓真帧，比较同一带有没有变化（真帧↔真帧，不用 640 那种会饱和的单层渲染差值）。
    // 有变化＝这层就是带内的绘制者，直接永久摘掉；没变化＝还原，绝不靠猜。
    private var chain642 = false
    private var chainIdx642 = 0
    private var chainBase642: IntArray? = null
    private var chainW642 = 0
    private var chainH642 = 0
    private var chainTop642 = 2440
    private var kept642 = 0
    // 643：642 的真帧逐层测法在「面板已落定」的两次运行里，所有候选差都是 0.00 ——
    // 即没有任何「带背景的 View」在这条带里画东西；而第一次运行（面板在 y=-1077 还在动画中）
    // 8 个候选全被判命中（差 41~42 全是面板动画本身），这说明两件事：
    //   ①量法对基线稳定性极度敏感，必须先证明「同一状态连抓两帧差≈0」；
    //   ②括号里那些候选没有一个是这条带的绘制者。
    // 643 因此：先量基线稳定性 → 再对「挂壁纸 Drawable 的宿主」和「类名像背景视图的 View」做
    // 位移匹配（渲染单层 + 只在它自己画过的像素上比较，找最佳纵向位移），并 dump 窗口/独立 surface。
    private var p643Runs = 0
    // 644：643 取证结果——基线稳定时连抓两帧差 0.00（量法可信）；所有带背景的 View 置空都不改变带内（642）；
    // 位移匹配里全部候选都是整屏锚定壁纸层，最佳位移一致落在 +48，与「decor 2772 / 面板 2722 = 差 50」同量级；
    // 窗口 dump 只有两层锚定壁纸是「底 = 2722（面板高）」而不是 2772：TaskBarContainer 与它那个 RelativeLayout。
    // 644 因此不再换测法：先在帧内量出这条带与上方内容的实际纵向位移，再按「锚定层逐层带内匹配」定位，
    // 最后只对「尺寸按面板高（≠窗口高）的那几层」做一次性摘除，并用「带内变、上半区不变」的帧证据决定保留还是还原。
    private var p644Runs = 0
    // 645：644 已经把这条带钉死了 —— `带内摘层(644) 摘=2 带内差=49.76 上半区差=0.00 保留`，
    // 被摘的两层是 `TaskBarContainer` 与它那个 `RelativeLayout`，几何都是 `y=0..2722`（底边=面板高，
    // 而不是窗口高 2772），背景都是锚定壁纸。也就是说：这条带是「按面板高 2722 录制的那两层锚定壁纸」
    // 画的，窗高 2772 与它差 50，而实测错位 120 来自录制那一刻面板的 translationY（锚定只补偿 left/top
    // 与祖先 scroll，不认 translationY），所以每次进入错位量都不同、完全展开时又自己对齐。
    // 645 把它固化成一次性、无实验的摘除，并在「接缝两侧行差」上做前后对照：修前接缝有明显阶跃、
    // 修后接缝应回到与上方连续。
    private var p645Runs = 0
    // 646：645 的日志是 `摘=0`，而且 `面板y=-1073..1649` —— 摘层发生在面板还没展开的时候，
    // 那一刻 TaskBarContainer 还是整窗高（≠2722），所以按「底边 == 面板高」一个都匹配不上；
    // 同时 645 把 644 的「两帧稳定性闸门」丢了，于是 `带内差=49.49` 其实是面板动画本身，
    // `y2548 行差 3.58→0.34` 也只是动画中途的偶然值，不是修好的证据。
    // 646 把闸门补回来并且更严：必须「面板顶边 == 0（完全展开）」+「同状态连抓两帧差 ≤1」才动手，
    // 不满足就下一轮再试（最多 8 次），每次把判定值打进日志。
    private var p646Tries = 0
    // 649：区分「冷启动首次进入」与「后续进入」，并把当次宿主几何一并打出来
    private var p649Entries = 0

    /** 650：冷启动首次进入时，屏外还挂着一个 AppBrandDesktopContainerView 实例，
     *  它按自己的位置把整块内容画在图标记处（截图里「H 海绵宝宝」上移压住图标区）。
     *  只做一次：顶边在屏幕外(top <= -100) 但底边压在接缝带(>2548) 的实例 alpha 置 0。 */
    private fun hideOffscreenPanel650(activity: android.app.Activity): Int {
        val root = activity.window?.decorView as? android.view.ViewGroup ?: return 0
        val hits = ArrayList<String>(4)
        var hidden = 0
        fun walk(v: android.view.View, depth: Int) {
            if (depth > 64) return
            if (v.javaClass.simpleName.contains("AppBrandDesktopContainer")) {
                val loc = IntArray(2)
                runCatching { v.getLocationInWindow(loc) }
                val top = loc[1]
                val bottom = top + v.height
                if (top <= -100 && bottom > 2548) {
                    runCatching { v.alpha = 0f }
                    hidden++
                    hits.add("${v.javaClass.simpleName}#${idName(v)} y=$top..$bottom h=${v.height} 隐藏")
                } else {
                    hits.add("${v.javaClass.simpleName}#${idName(v)} y=$top..$bottom h=${v.height} 保留")
                }
            }
            if (v is android.view.ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i), depth + 1)
        }
        walk(root, 0)
        HLog.i("$TAG 面板实例(650) 隐藏=$hidden " + hits.joinToString(" | "))
        return hidden
    }
    // 647：646 的双闸门放行了 —— `面板高锚定层已摘(646) 摘=2`（TaskBarContainer + RelativeLayout，均 y=0..2722），
    // 且 `基线稳定差=0.00`；摘后 `带内差=58.65 / 上半区差=0.00`（确实只动了这条带），
    // 但 `y2548 行差 0.21 → 97.51` —— 摘掉之后这条带与上方反而断开，说明「摘掉是正确的」这个前提不成立：
    // 去掉的两层原本是**对齐**的（0.21），露出来的下层才是错位/不同源的那一份。
    // 647 因此：①摘除动作保持不变（每轮进入都重放，幂等，最多 8 次），②在摘除后直接输出这条带的
    // 逐行剖面（突变行数、最大行差、突变 y 区间）与上半区参考值，把「残留像素数/y 范围」变成可读的数，
    // ③把「带内是否归零」写成一行结论，供下一轮只动那一份下层。
    private var p647Runs = 0
    private var p646Done = false
    private var last646 = 0L
    private var last645 = 0L
    private var last644 = 0L
    private val bandTop644 = 2545
    private var last643 = 0L
    private var frameA643: IntArray? = null
    private var frameW643 = 0
    private var frameH643 = 0
    private val top643 = 2440
    private var lastChain642 = 0L
    private val chainCands642 = ArrayList<View>()
    private val chainSaved642 = HashMap<View, android.graphics.drawable.Drawable?>()
    private var lastPanelDrop641 = 0L
    private var lastDrop640 = 0L
    private var lastPanelSync639 = 0L
    private var lastBand638 = 0L
    private var lastPanelProbeAt637 = 0L
    private var lastBarsIdx634 = -1
    // 633：632 字段实证已抓到嫌疑人 —— ConversationListView 自带 Paint `q` = #1a000000 / alpha 26 / strokeWidth 1.55
    // （≈2px，与截图那条「整宽 2px、约 10% 黑」同族）；行0 与前 4 个子 View 单独画都没有凹陷 ⇒ 线由列表自绘。
    // 本轮只做一次性清除 + 清前/清后 PixelCopy 自证，仍不进每帧。
    private var seamStage633 = 0
    private var seamAt633 = 0L
    private var seamClears633 = 0
    private var lastLayer632 = 0L
    private var lastLayer631 = 0L
    private var lastFlash630 = 0L
    private var prevRowMean630: IntArray? = null
    // ===== 627：全屏真帧缓冲基线 + 过渡差分 + 列表首行底衬反射 =====
    private var basePx627: IntArray? = null
    private var baseW627 = 0
    private var baseH627 = 0
    private var lastBase627 = 0L
    private var baseRuns627 = 0
    private var lastDiff627 = 0L
    private var diffRuns627 = 0
    private var insetDump627 = 0
    private var lastProfile625 = 0L
    private var profileRuns625 = 0
    private var colorGuardHits = 0L

    @Volatile
    private var lastTab: WeakReference<ViewGroup>? = null

    /** 见过的 LauncherUI；用来在没有当前页面信息时兜底重刷。 */
    private val launchers: MutableSet<Activity> = Collections.newSetFromMap(WeakHashMap<Activity, Boolean>())

    // 676：叠加页（微信原生设置页这类独立 Activity）在前台时，把壁纸层临时撤掉。
    // 依据：这些页面是从 LauncherUI 之上叠加的窗口，透区里能看见我们铺在 LauncherUI 上的壁纸
    // （用户 11-23 08:34 截图：设置/设置>聊天 右半侧透出壁纸、行列跟着错位）。
    // 只在窗口失焦/宿主 onPause 这些一次性时机改，不做每帧写入。
    private var wallpaperHidden = false
    // 677：状态栏那条底色由宿主反复写回 #ffededed（10:03 日志 windowStatusBar=#ffededed 9 次），
    // 我们只在 applyWindowBackground 里设过一次透明，之后就被盖掉。这里按 1 秒节流重设窗口栏位颜色
    // （窗口属性，不是逐帧写宿主 View 属性），并把标题栏内部那块灰白底清掉。
    private val rowsProbedAt: MutableMap<View, Long> = WeakHashMap()
    private var lastWindowColorAssert = 0L
    private var lastScrimRefreshAt = 0L
    private var lastScrimRefreshLogAt = 0L
    private var titleBarClearedLogged = 0
    private val focusWatched: MutableSet<ViewGroup> = Collections.newSetFromMap(WeakHashMap<ViewGroup, Boolean>())

    @Volatile
    private var lastDumpAt = 0L

    @Volatile
    private var lastEnforceAt = 0L
    private var lastDarkIcons = true
    private var lastActionBar: WeakReference<View>? = null
    private var actionBarDrawable: Drawable? = null
    private val lifecycleBumped: MutableSet<Activity> =
        Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap<Activity, Boolean>()))
    private val topDumpedAt: MutableMap<View, Long> =
        java.util.Collections.synchronizedMap(java.util.WeakHashMap())
    private val titleDumpedAt: MutableMap<View, Long> =
        java.util.Collections.synchronizedMap(java.util.WeakHashMap())
    private val topDumpIntervalMs = 60_000L
    private var framePumpUntil = 0L
    private var lastClearReassertLogAt = 0L
    private var lastLayoutBumpAt = 0L
    @Volatile private var rowMaskAlpha = -1
    @Volatile private var lastAppliedRowStrength = -1
    private var lastTopDumpAt = 0L
    private var lastFirstScreenRepaintLogAt = 0L
    private val periodicTicked: MutableSet<Activity> =
        Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap<Activity, Boolean>()))

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
        // 613：铺底时机提前到 onCreate。onResume 才铺的话，从窗口创建到 resume 之间
        // 首帧画的是微信原生浅底——11-19 07:14 会话里 Hook 挂载 40.877、首次 applyTheme 41.108，
        // 这 200~400ms 就是用户看到的「冷启动白底闪一下」。
        val onCreate = KavaReflector.findMethodRecursive(launcher, "onCreate", android.os.Bundle::class.java)
            ?: KavaReflector.findMethodRecursive(launcher, "onCreate")
        if (onCreate != null) {
            runCatching {
                HookRegistry.get().hook(onCreate, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val activity = param.thisObject as? Activity ?: return
                        if (!activity.javaClass.name.contains(LAUNCHER_UI_CLASS)) return
                        launchers.add(activity)
                        runCatching { applyEarlyWindowBackground(activity) }
                            .onFailure { HLog.e("$TAG 冷启动铺底异常(613): ${it.message}", it) }
                    }
                })
            }.onFailure { HLog.e("$TAG 挂载 LauncherUI.onCreate 失败(613): ${it.message}", it) }
        } else {
            HLog.e("$TAG 没找到 LauncherUI.onCreate，冷启动首帧只能等 onResume 补(613)")
        }
        val pause = KavaReflector.findMethod(launcher, "onPause")
            ?: KavaReflector.findMethodRecursive(launcher, "onPause")
        if (pause != null) {
            runCatching {
                HookRegistry.get().hook(pause, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        // 676：宿主被叠加页盖住时先撤壁纸，回来时 applyTheme 会重铺。
                        runCatching { if (overlayActivity() != null) hideWallpaperForOverlay("宿主 onPause") }
                            .onFailure { HLog.e("$TAG 壁纸暂隐失败(676): ${it.message}") }
                    }
                })
            }.onFailure { HLog.e("$TAG 挂载 LauncherUI.onPause 失败(676): ${it.message}") }
        }
        installed = true
        HLog.i("$TAG 主题 Hook 已挂载（页面=${LAUNCHER_UI_CLASS}，微信版本=${hostVersion()}）")
        installGreyPainterProbe()
        // 710：叠加页（微信设置页等）的壁纸撤销改由 Activity#onResume 驱动，
        // 且挂在 mount() 里——它原来藏在 installLifecycleBumps() 里，
        // 真机 17:48/18:09 两次会话都拿不到 `叠加页钩子已就位`，说明那条链路根本没跑到。
        runCatching { installSettingsResumeHook684() }
            .onFailure { HLog.e("$TAG 叠加页钩子安装异常(710): ${it.message}") }
        installPurpleBackgroundSwap()
        installGradientDrawKiller()
        installPagerScrollProbe()
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
        // 710：底栏开关不再挂在任何门控之后。708 的失效链是「关页态 → 本函数早退 → installEnforcement
        // 从未执行 → enforce 没有任何调用者」，所以底栏的隐藏/恢复必须先于 mountOff703() 与 enabled 判定跑一次。
        val snapshot = ThemeSettings.snapshot(context.hostContext())
        runCatching { syncNativeTab710(decor, snapshot) }
            .onFailure { HLog.e("$TAG 底栏独立同步异常(710): ${it.message}", it) }
        // 710：633 缝隙清线同理独立挂载（每个列表实例首次布局清一次），不依赖巡检与页面背景开关。
        runCatching { installSeamClearer710(decor) }
            .onFailure { HLog.e("$TAG 633 缝隙清线挂载异常(710): ${it.message}", it) }
        if (mountOff703()) { dumpCounts703("mount-skip applyPageBackgrounds("); return }
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
        dumpDecorTree(decor)
        if (focusWatched.add(decor)) {
            runCatching {
                decor.viewTreeObserver.addOnWindowFocusChangeListener { hasFocus ->
                    if (hasFocus) {
                        runCatching { restoreWallpaperAfterOverlay("窗口获焦") }
                    } else {
                        runCatching { if (overlayActivity() != null) hideWallpaperForOverlay("窗口失焦") }
                    }
                }
            }.onFailure { HLog.e("$TAG 焦点监听挂载失败(676): ${it.message}") }
        }
    }

    /** 676：当前是否有一个非 LauncherUI 的可见 Activity 压在上面（微信原生设置页等）。 */
    private fun overlayActivity(): Activity? {
        return runCatching {
            val threadClass = Class.forName("android.app.ActivityThread")
            val thread = threadClass.getMethod("currentActivityThread").invoke(null) ?: return@runCatching null
            val field = threadClass.getDeclaredField("mActivities").apply { isAccessible = true }
            val records = field.get(thread) as? Map<*, *> ?: return@runCatching null
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
                if (activity.javaClass.name.contains(LAUNCHER_UI_CLASS)) continue
                return@runCatching activity
            }
            null
        }.getOrNull()
    }

    /** 当前 ViewPager 里正在显示的那一页（按 viewport 覆盖面积最大取，不依赖 pager 类名）。 */
    private fun visiblePageOfPager(pager: ViewGroup): View? {
        var best: View? = null
        var bestOverlap = 0
        val left = pager.scrollX
        val right = left + pager.width
        for (i in 0 until pager.childCount) {
            val c = pager.getChildAt(i) ?: continue
            val overlap = minOf(c.right, right) - maxOf(c.left, left)
            if (overlap > bestOverlap) {
                bestOverlap = overlap
                best = c
            }
        }
        return best
    }

    /**
     * 678：11:08 日志里通讯录页的 `内容分层` 计数全是 0（`容器置透明 0 个，通栏行压半透明 0 个`，背景图 741x1600），
     * 说明那一趟是在页面还没 inflate 内容时跑的；`retryUnscrimmedPages` 的 20 次重试也被空页面消耗完，
     * 之后内容就绪（列表 15 行、行高 174、白 .9）再没人跑过一次分层 —— 行白底就是这么留下来的。
     * 这里在常驻补刷里对**可见页**按 1.2 秒节流补跑一次分层（幂等：已换过的行背景 alpha=0 会被跳过），
     * 每 6 秒打一次计数，页面就绪后再也不会漏。
     */
    private fun refreshVisiblePageScrim() {
        val pager = lastHomePager?.get() ?: return
        if (!pager.isAttachedToWindow || pager.width <= 0) return
        val page = visiblePageOfPager(pager) ?: return
        val spec = pageScrim[page] ?: return
        val group = page as? ViewGroup ?: return
        if (group.childCount <= 2) return
        val now = SystemClock.uptimeMillis()
        if (now - lastScrimRefreshAt < 1200L) return
        lastScrimRefreshAt = now
        scrimRetryCount[page] = 0
        val logNow = now - lastScrimRefreshLogAt >= 6000L
        if (logNow) lastScrimRefreshLogAt = now
        applyContentScrim(page, spec.bitmap, spec.overlayColor, spec.isHome, log = logNow)
        if (logNow) {
            HLog.i(
                "$TAG 可见页分层补跑(678): 页=${page.javaClass.simpleName}#${idName(page)}" +
                    " 子项=${group.childCount} 尺寸=${page.width}x${page.height} 已分层页=${scrimmedPages.size}"
            )
        }
        runCatching { dumpRowsProbe(page, true) }
            .onFailure { HLog.e("$TAG 行底色探针异常(678): ${it.message}") }
    }

    /**
     * 677：宿主会把 window.statusBarColor 写回 #ffededed（10:03 日志 9 次），我们设的透明只活到下一次宿主写入。
     * 这里按 1 秒节流把状态栏/导航栏重新设回透明——改的是窗口属性，不是逐帧写宿主 View 属性。
     */
    private fun assertWindowColors(activity: Activity) {
        if (gateWrite702()) return
        if (wallpaperHidden) return
        val now = SystemClock.uptimeMillis()
        if (now - lastWindowColorAssert < 1000L) return
        lastWindowColorAssert = now
        runCatching {
            val window = activity.window ?: return
            val sb = window.statusBarColor
            if (sb != Color.TRANSPARENT) {
                window.statusBarColor = Color.TRANSPARENT
                HLog.i("$TAG 状态栏底色写回透明(677): 宿主写的 #" + Integer.toHexString(sb))
            }
            if (window.navigationBarColor != Color.TRANSPARENT) window.navigationBarColor = Color.TRANSPARENT
        }.onFailure { HLog.e("$TAG 状态栏底色重设异常(677): ${it.message}") }
    }

    /**
     * 677 取证件：把当前页列表的行视图连同行内子视图的底色打出来（每页 60 秒最多一次）。
     * 用户 11-23 10:03/10:07 截图里通讯录的行是白底、只有 6dp 行缝露出壁纸，
     * 但同一时刻的 `内容分层` 计数显示行已被换成透明遮罩——到底是哪一层在画白，靠这份转储定位。
     */
    private fun findListView(page: View): ViewGroup? {
        var best: ViewGroup? = null
        var bestArea = 0
        fun walk(v: View, d: Int) {
            if (d > 14) return
            if (v is ViewGroup && v !== page) {
                val n = v.javaClass.simpleName
                if ((v is android.widget.AbsListView || n.contains("RecyclerView") || n.contains("ListView")) && v.childCount > 0) {
                    val area = v.width * v.height
                    if (area > bestArea) {
                        bestArea = area
                        best = v
                    }
                }
            }
            if (v is ViewGroup) for (i in 0 until v.childCount) v.getChildAt(i)?.let { walk(it, d + 1) }
        }
        walk(page, 0)
        return best
    }

    private fun dumpRowsProbe(page: View, log: Boolean) {
        if (!log) return
        val now = SystemClock.uptimeMillis()
        val last = rowsProbedAt[page]
        if (last != null && now - last < 20000L) return
        rowsProbedAt[page] = now
        val group = page as? ViewGroup
        if (group == null || group.childCount <= 2) {
            HLog.i("$TAG 行底色转储(678): 页面内容未就绪 子项=${group?.childCount ?: -1}")
            return
        }
        runCatching {
            val list = findListView(page)
            if (list == null) {
                HLog.i("$TAG 行底色转储(678): 未命中列表容器 页面=${page.javaClass.simpleName} 子项=${group.childCount} 尺寸=${page.width}x${page.height}")
                return
            }
            val lw = list.width.coerceAtLeast(list.measuredWidth)
            val lh = list.height.coerceAtLeast(list.measuredHeight)
            HLog.i(
                "$TAG 行底色转储(678): 列表=${list.javaClass.simpleName}#${idName(list)} ${lw}x$lh" +
                    " bg=${descDrawable(list.background)} cacheHint=" +
                    runCatching { "#" + Integer.toHexString((list as android.widget.AbsListView).cacheColorHint) }.getOrDefault("-")
            )
            if (list is ViewGroup) {
                val n = minOf(list.childCount, 6)
                for (i in 0 until n) {
                    val row = list.getChildAt(i) ?: continue
                    val rw = row.width.coerceAtLeast(row.measuredWidth)
                    val rh = row.height.coerceAtLeast(row.measuredHeight)
                    HLog.i(
                        "$TAG   行$i: ${row.javaClass.simpleName}#${idName(row)} ${rw}x$rh" +
                            " bg=${descDrawable(row.background)} 我们改过=${clearedViews.contains(row) || rowScrimDrawables.containsKey(row)}"
                    )
                    if (row is ViewGroup) {
                        for (j in 0 until minOf(row.childCount, 4)) {
                            val c = row.getChildAt(j) ?: continue
                            val cw = c.width.coerceAtLeast(c.measuredWidth)
                            val ch = c.height.coerceAtLeast(c.measuredHeight)
                            HLog.i(
                                "$TAG     行$i 子$j: ${c.javaClass.simpleName}#${idName(c)} ${cw}x$ch" +
                                    " bg=${descDrawable(c.background)}"
                            )
                        }
                    }
                }
            }
        }.onFailure { HLog.e("$TAG 行底色转储异常(677): ${it.message}") }
    }

    private fun hideWallpaperForOverlay(reason: String) {
        if (gateWrite702()) return
        if (wallpaperHidden) return
        wallpaperHidden = true
        runCatching {
            for ((pager, drawable) in pagerUnderlays.entries.toList()) {
                if (drawable is PagerStripWallpaperDrawable) drawable.clearSlots()
                if (pager.background === drawable) {
                    runCatching { pager.setBackgroundDrawable(ColorDrawable(0xffededed.toInt())) }
                }
            }
            restoreTouchedViews()
        }.onFailure { HLog.e("$TAG 壁纸暂隐异常(676): ${it.message}") }
        HLog.i("$TAG 壁纸层暂隐(676): 原因=$reason（叠加页在前台，壁纸层全部撤下）")
    }

    private fun restoreWallpaperAfterOverlay(reason: String) {
        if (gateWrite702()) return
        if (!wallpaperHidden) return
        wallpaperHidden = false
        HLog.i("$TAG 壁纸层恢复(676): 原因=$reason")
        scheduleReapply("叠加页退出")
    }

    private fun applyActionBar(activity: Activity, decor: ViewGroup, snapshot: ThemeSnapshot) {
        val bar = findActionBar(activity, decor)
        if (bar == null) {
            HLog.e("$TAG 没找到 ActionBar 容器（id=action_bar/action_bar_container/title_bar + 类名 ActionBar/TitleBar 全未命中），标题栏相关主题项本轮未生效")
            return
        }
        HLog.i("$TAG 标题栏容器命中: ${bar.javaClass.name}#${idName(bar)}")
        lastActionBar = WeakReference(bar)
        // 5.5.71：隐藏标题栏不再用 GONE（会连带子 View 不参与命中测试、搜索与＋号点不动），
        // 改成 alpha=0：视觉上消失、布局不变、点击照常命中。
        if (snapshot.actionBarHide) {
            if (bar.alpha != 0f) {
                if (!originalViewAlphas.containsKey(bar)) originalViewAlphas[bar] = bar.alpha
                runCatching { bar.alpha = 0f }
                HLog.i("$TAG 标题栏 alpha=0（不 GONE、不重排，保留搜索/＋号点击）")
            }
        } else if (originalViewAlphas.containsKey(bar)) {
            runCatching { bar.alpha = originalViewAlphas.remove(bar) ?: 1f }
        }
        val barColor = ThemeSettings.parseColor(snapshot.actionBarBackgroundColor)
        applyInternalActionBarColor(bar, barColor ?: Color.TRANSPARENT)
        // 5.5.88：500ms 重压挡不住微信逐帧重设内部字段（截图仍能捕到灰/紫）——挂 PreDraw 每帧重压。
        if (bar.tag != BAR_PREDRAW_TAG) {
            bar.tag = BAR_PREDRAW_TAG
            bar.viewTreeObserver.addOnPreDrawListener(object : android.view.ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    runCatching {
                        if (bar.alpha != 0f && bar.visibility == View.VISIBLE) {
                            val c = ThemeSettings.parseColor(ThemeSettings.snapshot(context.hostContext()).actionBarBackgroundColor)
                            applyInternalActionBarColor(bar, c ?: Color.TRANSPARENT)
                        }
                    }
                    return true
                }
            })
            HLog.i("$TAG 标题栏内部底色已挂 PreDraw 每帧重压")
        }
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

    /**
     * 710：底栏隐藏/恢复的独立同步入口。
     *
     * 与 applyBottomTab 的分工：这里只做「隐藏 / 恢复」一件事，不受 mountOff703()、backgroundEnabled
     * 与 enforce 巡检频率的任何约束，因此设置页、关页态下底栏开关依然生效（708「隐藏底栏失效」的根因
     * 就是这条链断在 applyThemeNow 的早退上：installEnforcement 从未执行、enforce 没有调用者）。
     *
     * 恢复方向必须自己复位 tabHiddenByUs：restoreTouchedViews() 只还原 hiddenViews 的可见性与高度，
     * 不清这个标记，漏复位会让下一次「隐藏底栏=开」误判成「已经隐藏过」。
     */
    private fun syncNativeTab710(decor: ViewGroup, snapshot: ThemeSnapshot) {
        if (!snapshot.enabled) {
            // 主题总开关关掉：restoreTouchedViews() 会把 hiddenViews 全部置回 VISIBLE，这里只对齐标记。
            tabHiddenByUs = false
            return
        }
        val wantHide = snapshot.tabHideNative
        var cached = lastTab?.get()
        if (cached != null && !cached.isAttachedToWindow) cached = null
        if (cached == null && !wantHide && !tabHiddenByUs) {
            // 不需要隐藏、也没有隐藏过 → 无事可做，避免每次进页面都扫一遍视图树。
            return
        }
        val tab = cached ?: findBottomTab(decor, logCandidates = wantHide)
        if (tab == null) {
            if (wantHide) {
                HLog.e("$TAG 底栏独立同步(710)：隐藏底栏=开，但没找到底栏容器，本轮未隐藏")
            }
            return
        }
        lastTab = WeakReference(tab)
        if (wantHide) {
            if (tab.visibility != View.GONE) {
                HLog.i("$TAG 隐藏原生底栏=开，隐藏底栏容器(710): ${tab.javaClass.name}#${idName(tab)}")
            }
            hideView(tab)
            tabHiddenByUs = true
            return
        }
        if (tabHiddenByUs) {
            runCatching { showView(tab) }
            tabHiddenByUs = false
            HLog.i("$TAG 隐藏原生底栏=关，恢复原生底栏显示(710): ${tab.javaClass.name}#${idName(tab)}")
        }
    }
    private fun applyBottomTab(decor: ViewGroup, snapshot: ThemeSnapshot) {
        if (!snapshot.tabEnabled && !snapshot.tabHideNative) return
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
            tabHiddenByUs = true
            return
        }
        showView(tab)
        tabHiddenByUs = false
        if (!snapshot.tabEnabled) return
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
        if (mountOff703()) { dumpCounts703("mount-skip applyPageBackgrounds("); return }
        if (!snapshot.backgroundEnabled) return
        val paths = snapshot.pagePaths()
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
                "$TAG 页面开关状态(671): 主=${snapshot.pageHome} 通=${snapshot.pageContacts} 发=${snapshot.pageDiscover} 我=${snapshot.pageMe} 启=${snapshot.pageSplash}" +
                    " 底图主=${snapshot.backgroundHome.isNotBlank()} 通=${snapshot.backgroundContacts.isNotBlank()}" +
                    " 发=${snapshot.backgroundDiscover.isNotBlank()} 我=${snapshot.backgroundMe.isNotBlank()}"
            )
            HLog.i(
                "$TAG 页面背景容器: ${pager.javaClass.name}#${idName(pager)}" +
                    " 子项=${pager.childCount} 尺寸=${pager.width}x${pager.height} 可见=${pager.isShown}"
            )
        }
        // 610：切页过渡底衬——给 ViewPager 自己铺一张窗口锚定壁纸（宿主静态位=(0,0)，逐帧恒定）。
        // 页面在滑动过程中被宿主重录/重绘的空洞里，透出来的是壁纸而不是微信 fragment 渐变底。
        applyPagerUnderlay(pager, paths.firstOrNull { it.isNotBlank() }, paths.firstOrNull()?.takeIf { it.isNotBlank() }, log)
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
            runCatching { dumpRowsProbe(page, log) }
                .onFailure { HLog.e("$TAG 行底色探针异常(677): ${it.message}") }
            // 613：登记槽位并监听这页的首次布局（内容 inflate 的那一帧，draw 之前补铺）
            pageSlots[page] = index
            installPageLayoutWatch(page, index)
        }
        lastHomePager = WeakReference(pager)
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
        // 710c：设置页可见期间，任何重铺都直接放弃（否则 layout 回调会把刚按下去的壁纸又铺回来）。
        if (settingsLikeHidden710.get()) return
        // 主题包里的 c 字段是背景上的叠色（#AARRGGBB），叠上去才和原主题观感一致
        // 5.5.104：页面层也换全屏锚定 Drawable，与 decor/容器层像素对齐，接缝才会消失。
        val image: Drawable = anchored(page, bitmap)
        // 617：底衬按页分格要用原始位图（同一张 Bitmap、同一套 cover 矩阵，切页不换图）
        runCatching {
            pageBitmapByPage[page] = bitmap
            val pager = lastHomePager?.get()
            val idx = pager?.indexOfChild(page) ?: -1
            if (pager != null && idx >= 0) {
                (pagerUnderlays[pager] as? PagerStripWallpaperDrawable)?.setSlot(idx, bitmap, overlayColor ?: 0)
            }
        }
        val drawable: Drawable = if (overlayColor != null && Color.alpha(overlayColor) > 0) {
            LayerDrawable(arrayOf<Drawable>(image, ColorDrawable(overlayColor)))
        } else {
            image
        }
        // 613：先登记再落背景——写入拦截钩子按 pageDrawables 判断该视图应保持哪张图，
        // 顺序反了钩子会把刚铺上的新图当成「宿主改写」又换回旧的。
        // 616：壁纸只由「窗口锚定、static 恒为 (0,0)」的层来画——decor 与 ViewPager 底衬。
        // 页面层在 ViewPager 里，static 随 scrollX 变化；翻页过程中它录进 display list 的
        // T(-static) 与相邻层不一致，屏幕上就并排出现两份不同偏移的同一张图（11:00 截图
        // 1000141886：发现页左侧约 40% 是错位副本），落定后另一层重录 → 图又「变一张」跳一下。
        // 所以非首页不再自己画壁纸，一律透明，由统一底层透出；首页 static 恒为 0，保留原状。
        if (isHome) {
            pageDrawables[page] = drawable
            page.setBackgroundDrawable(drawable)
        } else {
            pageDrawables[page] = guardTransparent
            page.setBackgroundDrawable(guardTransparent)
        }
        if (SystemClock.uptimeMillis() - lastWallpaperOwnerLogAt >= 3000L) {
            lastWallpaperOwnerLogAt = SystemClock.uptimeMillis()
            HLog.i(
                "$TAG 壁纸层归属(616): 第 ${if (isHome) 1 else 0}层 page=${page.javaClass.simpleName}#${idName(page)}" +
                    " 画壁纸=${isHome} static=(0,0) 期望 — 非首页统一由 decor/底衬透出"
            )
        }
        // 614：宿主给页面壳设过 setWillNotDraw(true)（真机探针里四页 wnd=true），
        // 那会让 ViewGroup 直接跳过背景绘制——页面层铺的图其实没画出来。
        runCatching { if (page is ViewGroup && page.willNotDraw()) page.setWillNotDraw(false) }
        pageScrim[page] = ScrimSpec(bitmap, overlayColor, path, isHome)
        // 5.5.54 的实测：背景只从列表之外露出来——列表项、分组头、发现页卡片各自带着不透明白底，
        // 铺在页面上的图被逐层盖住。这里做「内容分层」：
        //   整页容器（≥92% 宽高）→ 完全透明，让背景沉到最底层；
        //   整行/整卡（≥60% 宽）→ 换成按背景明暗算出的半透明遮罩，保证文字可读。
        applyContentScrim(page, bitmap, overlayColor, isHome, log)
        // 5.5.69 回退：5.5.67 起四页都铺「整页容器图」，真机日志出现「页面容器铺图: 19 个」，
        // 通讯录把列表容器/行容器全涂成壁纸，整页看不见内容并卡死。铺图只保留在首页（去紫需要），
        // 其余页面只走「清底」路径。
        // 615：撤掉 614 给非首页插的实体壁纸层——真机截图里通讯录右侧索引栏那条竖带
        // 出现了「自己按自己宽高缩放的壁纸」，与窗口锚定层错位。铺图只保留首页（去紫需要）。
        if (isHome) ensureHomeImageLayer(page, bitmap, log)
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
        val skipped710q = skippedRowRewrite710q
        skippedRowRewrite710q = 0
        // 711 取证：只读打印行缩进链路，不写任何布局。
        runCatching { probeRowInsets711(page, pageWidth) }
            .onFailure { HLog.e("$TAG 行缩进取证调用异常(711): ${it.message}") }
        // 712：行内缩回钉——只把「这一行自己曾经亲眼出现过的正内缩」钉回去，
        // 绝不凭空给值，因此不会把正常的行改坏。详见函数注释。
        runCatching { pinRowInsets712(page, pageWidth) }
            .onFailure { HLog.e("$TAG 行内缩回钉异常(712): ${it.message}", it) }
        // 712c：记住这次处理过的页面，供 OnPreDraw 里「画之前回钉」用。
        lastScrimPage712 = java.lang.ref.WeakReference(page)
        runCatching { headerHousekeeping(page, pageWidth, pageHeight, detail) }
            .onFailure { HLog.e("$TAG 头部清理异常: ${it.message}", it) }
        val scrimmedTotal = counters[0] + counters[1] + counters[2] + counters[3]
        if (scrimmedTotal > 0) scrimmedPages.add(page)
        if (log) {
            HLog.i(
                "$TAG 页面 ${page.javaClass.simpleName} 内容分层：容器置透明 ${counters[0]} 个，" +
                    "通栏行压半透明 ${counters[1]} 个，幂等跳过=$skipped710q 个，索引条去底 ${counters[2]} 个，" +
                    "整页图片层透明 ${counters[3]} 个，" +
                    "行缝=${ROW_GAP_DP}dp 遮罩=#${Integer.toHexString(scrim)}" +
                    " 背景平均色=#${String.format(java.util.Locale.US, "%02X%02X%02X", (stats[0] * 255).toInt(), (stats[1] * 255).toInt(), (stats[2] * 255).toInt())}" +
                    " 亮度=${String.format(java.util.Locale.US, "%.2f", luma)} 背景图=${bitmap.width}x${bitmap.height}"
            )
            sample.forEach { HLog.i("$TAG   分层: $it") }
        }
    }

    /**
     * 状态栏层白名单（5.5.71）：只清「状态栏那一条」的底色，不再按「顶部 45% 区域 + 宽度」清块。
     * 5.5.70 的宽规则把下拉小程序面板那张 1042x537、#26223a 的卡片一起清了，面板因此看不见。
     * 命中条件：系统 id statusBarBackground / 资源名 statusBarBackground / 高度等于 status_bar_height。
     */
    private fun isStatusBarLayer(view: View, height: Int): Boolean {
        if (view.id == android.R.id.statusBarBackground) return true
        val name = runCatching { view.resources.getResourceEntryName(view.id) }.getOrNull()
        if (name == "statusBarBackground") return true
        val barHeight = statusBarHeightOf(view)
        return barHeight > 0 && height == barHeight
    }

    private fun statusBarHeightOf(view: View): Int = runCatching {
        val id = view.resources.getIdentifier("status_bar_height", "dimen", "android")
        if (id > 0) view.resources.getDimensionPixelSize(id) else 0
    }.getOrDefault(0)

    /** 是否位于滚动容器内部（列表行/条目的底色由行遮罩统一处理，不能在这里整块清或铺图）。 */
    /** 5.5.88：小程序任务栏（下拉面板）深色卡 #26223a 被行遮罩/清底规则破坏——其子树一律跳过。 */
    private fun insideTaskBar(view: View, page: View): Boolean {
        var cur: View? = view.parent as? View
        var depth = 0
        while (cur != null && cur !== page && depth < 24) {
            if (cur.javaClass.simpleName == "TaskBarContainer") return true
            cur = cur.parent as? View
            depth++
        }
        return false
    }

    private fun insideScrollingContainer(view: View, page: View): Boolean {
        var cur: View? = view.parent as? View
        var depth = 0
        while (cur != null && cur !== page && depth < 24) {
            val name = cur.javaClass.simpleName
            if (cur is android.widget.AdapterView<*> ||
                name.contains("RecyclerView") || name.contains("ListView") || name.contains("ScrollView")
            ) return true
            cur = cur.parent as? View
            depth++
        }
        return false
    }

    /** 顶部 600px 稠密转储：白块到底挂在哪个 View 上，只能靠这份明细。 */
    private fun dumpTopRegion(page: View, log: Boolean) {
        if (!log) return
        val nowMs = android.os.SystemClock.elapsedRealtime()
        val lastDumpMs = topDumpedAt[page]
        if (lastDumpMs != null && nowMs - lastDumpMs < topDumpIntervalMs) return
        topDumpedAt[page] = nowMs
        val lines = ArrayList<String>()
        fun walk(view: View, depth: Int) {
            if (lines.size >= 120 || depth > 20) return
            val w = view.width.coerceAtLeast(view.measuredWidth)
            val h = view.height.coerceAtLeast(view.measuredHeight)
            var top = 0
            var cur: View? = view
            while (cur != null && cur !== page) {
                top += cur.top
                cur = cur.parent as? View
            }
            if (top < 1200 && w > 100) {
                val bg = view.background
                val desc = when {
                    bg == null -> "null"
                    bg is ColorDrawable -> "${bg.javaClass.simpleName}#${Integer.toHexString(bg.color)}"
                    bg is android.graphics.drawable.GradientDrawable -> "${bg.javaClass.simpleName}#${
                        (0 until 1).map { 0 }.let { try { Integer.toHexString(bg.color?.defaultColor ?: 0) } catch (e: Throwable) { "?" } }
                    }"
                    else -> "${bg.javaClass.simpleName} a=${bg.alpha}"
                }
                val img = (view as? android.widget.ImageView)?.let { iv -> if (iv.drawable == null) " img=无" else " img=有" } ?: ""
                lines.add(
                    "${"·".repeat(depth)}${view.javaClass.simpleName}#${idName(view)} ${w}x${h}" +
                        " top=$top bg=$desc$img vis=${view.visibility}"
                )
            }
            if (view is ViewGroup) for (i in 0 until view.childCount) walk(view.getChildAt(i), depth + 1)
        }
        walk(page, 0)
        HLog.i("$TAG 顶部区域转储(页面 ${page.javaClass.simpleName}): ${lines.size} 条")
        lines.forEach { HLog.i("$TAG   顶部: $it") }
    }

    /**
     * 5.5.82：两处日志点名的候选。
     * 1) 我页白块：09:12 转储点名 ImageView#gxn 1240x549 top=0 bg=ColorDrawable#ffffffff（两次转储均在）。
     *    规则：顶部 20px 内、宽≥80% 页宽、高≥300px、底色为不透明白 ColorDrawable → 清透明并登记 clearedViews。
     * 2) 首页灰条：5.5.82 的 GradientColorBackgroundView 禁自绘在真机 0 命中且灰条仍在，假设已证伪；
     *    本版只加「灰条探针」定期如实记录 ActionBar 内部字段与 jlt 父链，不改行为。
     */
    private var lastBarProbeAt = 0L
    private var lastBarFieldsProbeAt = 0L
    private var lastPanelProbeAt = 0L
    private var lastPanelSignature: String? = null

    /** 5.5.83 灰条探针：三版假设（标题卡容器、GradientColorBackgroundView）均被日志证伪后，
     *  只定期如实记录 ActionBar 内部字段与 jlt 父链，等待截图对时裁决谁在画 0..149 的灰。 */
    private fun titleBarProbe(decor: ViewGroup, now: Long) {
        if (now - lastBarProbeAt < topDumpIntervalMs) return
        // 710：这里原来判 lastBarProbeAt、写 lastBarProbeAt621，两个变量不是同一个，
        // 于是节流永远不生效，每轮 enforce 都打一屏「灰条探针/ActionBar 子树转储」，
        // 日志文件 20 分钟就滚一次（真机 82 条/分钟）。
        lastBarProbeAt = now
        runCatching {
            fun descD(d: android.graphics.drawable.Drawable?): String = when (d) {
                null -> "null"
                is ColorDrawable -> "ColorDrawable#${Integer.toHexString(d.color)}"
                is android.graphics.drawable.GradientDrawable ->
                    "GradientDrawable#" + runCatching { Integer.toHexString(d.color?.defaultColor ?: -1) }.getOrDefault("?")
                else -> d.javaClass.simpleName
            }
            fun descV(v: View): String = "bg=${descD(v.background)} alpha=${v.alpha}"
            val parts = ArrayList<String>()
            findViewByClassName(decor, "LayoutListenerView")?.let { page ->
                var cur: View = page
                val chain = ArrayList<String>()
                var guard = 0
                while (guard++ < 8) {
                    val p = cur.parent as? View ?: break
                    chain.add("${p.javaClass.simpleName}#${idName(p)} ${p.width}x${p.height} ${descV(p)}")
                    if (p === decor) break
                    cur = p
                }
                parts.add("jlt父链=[${chain.joinToString(" > ")}]")
            }
            listOf(
                "ActionBarContainer", "ActionBarOverlayLayout", "ContentFrameLayout",
                "FitSystemWindowLayoutView", "FirstScreenFrameLayout", "ConversationListView"
            ).forEach { name ->
                findViewByClassName(decor, name)?.let { v ->
                    var extra = ""
                    if (name == "ActionBarContainer") {
                        extra = ""
                        listOf("mBackground", "mStackedBackground", "mSplitBackground").forEach { f ->
                            runCatching {
                                val fl = v.javaClass.getDeclaredField(f)
                                fl.isAccessible = true
                                extra += " $f=${descD(fl.get(v) as? android.graphics.drawable.Drawable)}"
                            }.onFailure { extra += " $f=取不到" }
                        }
                    }
                    parts.add("$name#${idName(v)} ${v.width}x${v.height} ${descV(v)}$extra")
                }
            }
            HLog.i("$TAG 灰条探针: ${parts.joinToString(" | ")}")
        }.onFailure { HLog.e("$TAG 灰条探针异常: ${it.message}", it) }
    }

    @Volatile private var tabHiddenByUs = false
    private var lastInternalBarSig: String? = null

    /** 5.5.85：ActionBarContainer 的灰/紫底不在 view.background，而在它 onDraw 画的内部字段
     *  （探针实测：首页 h=#ffededed、面板页 h=#ff7a7396、另有 i=#ff111111，字段名被混淆）。
     *  把所有不透明 ColorDrawable 字段统一替换成目标色（未指定=透明），enforce 每轮重压。 */
    private fun applyInternalActionBarColor(bar: View, color: Int) {
        runCatching {
            val replaced = ArrayList<String>()
            var cls: Class<*>? = bar.javaClass
            var guard = 0
            while (cls != null && guard++ < 6) {
                for (f in cls.declaredFields) {
                    runCatching {
                        f.isAccessible = true
                        val cur = f.get(bar)
                        if (cur is ColorDrawable && Color.alpha(cur.color) != 0) {
                            f.set(bar, ColorDrawable(color))
                            replaced.add("${f.name}=#${Integer.toHexString(cur.color)}")
                        }
                    }
                }
                cls = cls.superclass
            }
            if (replaced.isNotEmpty()) {
                val sig = "${Integer.toHexString(color)}|${replaced.joinToString("/")}"
                if (sig != lastInternalBarSig) {
                    lastInternalBarSig = sig
                    HLog.i("$TAG 标题栏内部底色已替换 ${replaced.size} 处 → #${Integer.toHexString(color)} [${replaced.joinToString("/")}]")
                }
            }
        }.onFailure { HLog.e("$TAG 标题栏内部底色替换失败: ${it.message}", it) }
    }

    private fun descDrawable(d: android.graphics.drawable.Drawable?): String = when (d) {
        null -> "null"
        is ColorDrawable -> "ColorDrawable#${Integer.toHexString(d.color)}"
        is android.graphics.drawable.GradientDrawable ->
            "GradientDrawable#" + runCatching { Integer.toHexString(d.color?.defaultColor ?: -1) }.getOrDefault("?")
        is android.graphics.drawable.BitmapDrawable -> {
            val bmp = d.bitmap
            if (bmp == null) "BitmapDrawable#null"
            else "BitmapDrawable#" + runCatching {
                val cols = listOf(
                    bmp.getPixel(0, 0),
                    bmp.getPixel(bmp.width - 1, 0),
                    bmp.getPixel(0, bmp.height - 1),
                    bmp.getPixel(bmp.width / 2, bmp.height / 2)
                )
                cols.joinToString("/") { Integer.toHexString(it) }
            }.getOrDefault("?") + " ${bmp.width}x${bmp.height}"
        }
        else -> d.javaClass.simpleName
    }

    private var lastDecorStripAt = 0L

    /** 5.5.86：ActionBarContainer alpha=0 且内部字段已清透明后灰条仍可见 —— 灰条绘制者另有其人。
     *  decor 顶部 220px 内全量转储（不筛 bg、不筛类名），把它点名。 */
    private fun decorStripProbe(decor: ViewGroup, now: Long) {
        if (now - lastDecorStripAt < topDumpIntervalMs) return
        lastDecorStripAt = now
        runCatching {
            fun topAbs(v: View): Int {
                var t = 0
                var cur: View = v
                var guard = 0
                while (guard++ < 20) {
                    t += cur.top
                    cur = cur.parent as? View ?: break
                }
                return t
            }
            val rows = ArrayList<Pair<Int, String>>()
            fun walk(v: View, d: Int) {
                if (rows.size >= 400 || d > 18) return
                val w = v.width.coerceAtLeast(v.measuredWidth)
                val h = v.height.coerceAtLeast(v.measuredHeight)
                val t = topAbs(v)
                if (t < 220 && w > 300 && h > 10) {
                    val img = (v as? android.widget.ImageView)?.let { iv -> if (iv.drawable == null) " img=无" else " img=有" } ?: ""
                    rows.add(Pair(t, "top=$t ${"·".repeat(d)}${v.javaClass.simpleName}#${idName(v)} ${w}x${h} bg=${descDrawable(v.background)} fg=${descDrawable(v.foreground)}$img alpha=${v.alpha} vis=${v.visibility}"))
                }
                if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i), d + 1)
            }
            walk(decor, 0)
            rows.sortBy { it.first }
            HLog.i("$TAG decor顶部全量转储: ${rows.size} 条")
            rows.take(120).forEach { HLog.i("$TAG   decor顶部: ${it.second}") }
        }.onFailure { HLog.e("$TAG decor顶部转储异常: ${it.message}", it) }
    }

    /** 5.5.84： ActionBarContainer 内部字段按「所有 Drawable 类型字段」反射（mBackground 等名字取不到，
     *  微信重打包后字段名不同），并完整转储其子树——灰色圆角标题卡大概率是它的子视图。 */
    private fun actionBarProbe(decor: ViewGroup, now: Long) {
        if (now - lastBarFieldsProbeAt < 10_000L) return
        lastBarFieldsProbeAt = now
        runCatching {
            val bar = findViewByClassName(decor, "ActionBarContainer") ?: return
            val sb = StringBuilder()
            var cls: Class<*>? = bar.javaClass
            var guard = 0
            while (cls != null && guard++ < 6) {
                for (f in cls.declaredFields) {
                    runCatching {
                        f.isAccessible = true
                        val value = f.get(bar)
                        if (value is android.graphics.drawable.Drawable) {
                            sb.append(' ').append(f.name).append('=').append(descDrawable(value))
                        }
                    }
                }
                cls = cls.superclass
            }
            HLog.i("$TAG ActionBar字段探针: ActionBarContainer#${idName(bar)} alpha=${bar.alpha}$sb")
            val rows = ArrayList<String>()
            fun walk(v: View, d: Int) {
                if (rows.size >= 40 || d > 10) return
                val w = v.width.coerceAtLeast(v.measuredWidth)
                val h = v.height.coerceAtLeast(v.measuredHeight)
                if (w > 60 && h > 30) {
                    val img = (v as? android.widget.ImageView)?.let { iv -> if (iv.drawable == null) " img=无" else " img=有" } ?: ""
                    rows.add("${"·".repeat(d)}${v.javaClass.simpleName}#${idName(v)} ${w}x${h} bg=${descDrawable(v.background)} fg=${descDrawable(v.foreground)}$img alpha=${v.alpha} vis=${v.visibility}")
                }
                if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i), d + 1)
            }
            walk(bar, 0)
            HLog.i("$TAG ActionBar子树转储: ${rows.size} 条")
            rows.forEach { HLog.i("$TAG   ActionBar: $it") }
        }.onFailure { HLog.e("$TAG ActionBar探针异常: ${it.message}", it) }
    }

    /** 5.5.84： 面板探针——TaskBarContainer 顶部接近可视（用户下拉中/已下拉）时，转储其子树的
     *  底色/内容图/可见性，按签名去重：状态不变不重复打。裁决深色卡与图标是谁清的、还是没画。 */
    private fun panelProbe(decor: ViewGroup, now: Long) {
        if (now - lastPanelProbeAt < 2_000L) return
        lastPanelProbeAt = now
        runCatching {
            val panel = findViewByClassName(decor, "TaskBarContainer") ?: return
            val effTop = panel.top + panel.translationY.toInt()
            if (effTop < -400 || panel.visibility != View.VISIBLE) return
            val rows = ArrayList<String>()
            fun walk(v: View, d: Int, base: Int) {
                if (rows.size >= 120 || d > 14) return
                val w = v.width.coerceAtLeast(v.measuredWidth)
                val h = v.height.coerceAtLeast(v.measuredHeight)
                val at = base + v.top
                if (w > 60 && h > 30) {
                    val img = (v as? android.widget.ImageView)?.let { iv -> if (iv.drawable == null) " img=无" else " img=有" } ?: ""
                    rows.add("${"·".repeat(d)}${v.javaClass.simpleName}#${idName(v)} ${w}x${h} top=$at bg=${descDrawable(v.background)} fg=${descDrawable(v.foreground)}$img alpha=${v.alpha} vis=${v.visibility}")
                }
                if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i), d + 1, at)
            }
            val root = panel.parent as? View ?: panel
            walk(root, 0, effTop - panel.top - (root.takeIf { it !== panel }?.top ?: 0))
            val sig = rows.joinToString("|")
            if (sig == lastPanelSignature) return
            lastPanelSignature = sig
            HLog.i("$TAG 面板探针(打开态 effTop=$effTop): ${rows.size} 条")
            rows.forEach { HLog.i("$TAG   面板: $it") }
        }.onFailure { HLog.e("$TAG 面板探针异常: ${it.message}", it) }
    }

    private val gradientViewsHandled: MutableSet<View> =
        Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap<View, Boolean>()))

    private fun headerHousekeeping(page: View, pageWidth: Int, pageHeight: Int, log: Boolean) {
        if (pageWidth <= 0 || pageHeight <= 0) return
        fun topOf(v: View): Int {
            var t = 0
            var cur: View? = v
            while (cur != null && cur !== page) {
                t += cur.top
                cur = cur.parent as? View
            }
            return t
        }
        fun walk(v: View) {
            val name = v.javaClass.simpleName
            // 5.5.88：AppBrandDesktopContainerView 面板打开时 top=0 全屏盖在图标区上层（14:35 日志实锤），
            // 它的壁纸底色让图标「看不见但可点」——清透明并禁止再铺图。
            if (name == "AppBrandDesktopContainerView") {
                if (clearedViews.add(v)) {
                    runCatching {
                        v.setBackgroundColor(Color.TRANSPARENT)
                        HLog.i("$TAG 小程序桌面容器底色已清透明: ${name}#${idName(v)} ${v.width}x${v.height}")
                    }
                }
                return
            }
            if (v !== page && insideTaskBar(v, page)) return
            if (name == "GradientColorBackgroundView" && v.width > 0 && v.height > 0 && gradientViewsHandled.add(v)) {
                runCatching {
                    v.setWillNotDraw(true)
                    HLog.i("$TAG 渐变背景视图已禁绘制: ${name}#${idName(v)} ${v.width}x${v.height}")
                }
            }
            val bg = v.background
            val bgWhite = when (bg) {
                is ColorDrawable -> bg.color == -0x1
                is android.graphics.drawable.GradientDrawable ->
                    runCatching { bg.color?.defaultColor == -0x1 }.getOrDefault(false)
                else -> false
            }
            if (bgWhite &&
                v.width >= pageWidth * 0.8f && v.height >= 200 && topOf(v) <= 740
            ) {
                if (!clearedViews.add(v)) return
                runCatching {
                    v.setBackgroundColor(Color.TRANSPARENT)
                    HLog.i("$TAG 大块白色底已清透明: ${v.javaClass.simpleName}#${idName(v)} ${v.width}x${v.height}")
                }
            }
            val db = v.background
            if (v.height in 1..8 && v.width >= pageWidth * 0.5f &&
                db is ColorDrawable && Color.alpha(db.color) > 0
            ) {
                if (clearedViews.add(v)) {
                    runCatching {
                        v.setBackgroundColor(Color.TRANSPARENT)
                        HLog.i("$TAG 细分隔线已清透明: ${v.javaClass.simpleName}#${idName(v)} ${v.width}x${v.height} #${Integer.toHexString(db.color)}")
                    }
                }
            }
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(page)
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
        if (view !== page && insideTaskBar(view, page)) return
        if (view !== page && !pageDrawables.containsKey(view)) {
            val width = view.width.coerceAtLeast(view.measuredWidth)
            val height = view.height.coerceAtLeast(view.measuredHeight)
            if (width > 4 && height > 4) {
                val background = view.background
                val narrow = width <= pageWidth * 0.18f
                val tall = height >= pageHeight * 0.30f
                when {
                    // 5.5.77：我页顶部头像/微信号白块——背景清透明后仍纯白（19:23 日志 398 次重压无效），
                    // 判定为该容器自绘（RoundedCornerFrameLayout 的 onDraw 画白底）。
                    // 对这类大号自绘圆角容器直接关绘制，只保留子 View。
                    "RoundedCornerFrameLayout" in view.javaClass.simpleName &&
                        width >= pageWidth * 0.50f && height >= pageHeight * 0.10f &&
                        view.top < pageHeight * 0.50f -> {
                        if (!replacedBackgrounds.containsKey(view)) replacedBackgrounds[view] = background
                        view.background = ColorDrawable(Color.TRANSPARENT)
                        runCatching { view.setWillNotDraw(true) }
                        counters[0]++
                        clearedViews.add(view)
                        if (log && sample.size < 120) {
                            sample.add("${view.javaClass.simpleName}#${idName(view)} ${width}x${height} top=${view.top}→自绘圆角容器已禁绘制")
                        }
                    }
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
                    isStatusBarLayer(view, height) &&
                        background != null && background !is BitmapDrawable &&
                        !(background is ColorDrawable && Color.alpha(background.color) == 0) -> {
                        if (!replacedBackgrounds.containsKey(view)) replacedBackgrounds[view] = background
                        val was = background.javaClass.simpleName +
                            (if (background is ColorDrawable) "#${Integer.toHexString(background.color)}" else "")
                        view.background = ColorDrawable(Color.TRANSPARENT)
                        clearedViews.add(view)
                        counters[0]++
                        if (log && sample.size < 120) {
                            sample.add("${view.javaClass.simpleName}#${idName(view)} ${width}x${height} top=${view.top} bg=$was→状态栏层清底")
                        }
                    }
                    width < pageWidth * 0.30f -> {
                        // 677：20%~30% 宽但底色为不透明 .9/纯色的「行内白块」不再跳过——
                        // 通讯录行里 1017x174 的白 .9 就是这个量级（10:03 灰条绘制者取证：
                        // color=#ffffffff 来源=NinePatchDrawable.draw 1017x174）。只清底色，不动窄图/图标。
                        val rowBand = height <= pageHeight * 0.25f
                        if (rowBand && width >= pageWidth * 0.18f && background != null &&
                            background !is BitmapDrawable && background.alpha == 255 &&
                            !(background is ColorDrawable && Color.alpha(background.color) == 0)
                        ) {
                            if (!replacedBackgrounds.containsKey(view)) replacedBackgrounds[view] = background
                            view.background = ColorDrawable(Color.TRANSPARENT)
                            clearedViews.add(view)
                            counters[0]++
                            if (log && sample.size < 120) {
                                sample.add("${view.javaClass.simpleName}#${idName(view)} ${width}x${height} bg=${background.javaClass.simpleName}→行内白块清底(677)")
                            }
                        } else if (log && sample.size < 120) {
                            sample.add("${view.javaClass.simpleName}#${idName(view)} ${width}x${height} 跳过(窄)")
                        }
                    }
                    background != null && background.alpha != 0 && !homeImageHosts.containsKey(view) -> {
                        if (!replacedBackgrounds.containsKey(view)) replacedBackgrounds[view] = background
                        val container = height >= pageHeight * 0.55f
                        if (container || scrimmed) {
                            view.background = ColorDrawable(Color.TRANSPARENT)
                            clearedViews.add(view)
                            // 710：同一视图不能再留行遮罩登记，否则两表互相覆盖（详见 desiredBackgroundFor）。
                            rowScrimDrawables.remove(view)
                            counters[0]++
                            if (log && sample.size < 120) {
                                sample.add("${view.javaClass.simpleName}#${idName(view)} ${width}x${height} bg=${background.javaClass.simpleName}→置透明")
                            }
                        } else {
                            // 通栏行：纯色（无圆角）＋ 行底留缝，让背景从行间透出来
                            // 608：换底前记下行的有效缩进（视图 padding 与 .9 背景 padding 取大值），换底后钉回；
                            // 否则 .9 图自带的左/上内缩随背景一起消失，发现页/通讯录图标就贴左错位。
                            //
                            // 710q：真机日志证明这里以前**每轮都重建一个新 InsetDrawable 再赋值**，
                            // 而 `setBackgroundDrawable` 必定 requestLayout()+invalidate()，
                            // 710 的「吞掉等价写入」按引用比较 → 新实例永远不等于旧实例 → 等于被绕过。
                            // 证据：12:18:06.143~.156 同一个 ConversationFolderItemView#cj1 在 13ms 内被
                            // 写成「通栏行6.0dp缝」十几次，行内布局反复重排 → 标题文本脱离原生约束、偶发靠左错位。
                            // 这里只加幂等跳过：已经是我们那条缝、且引用没被宿主换掉，就不重建、不重写；
                            // 判据与作用对象完全不变（壁纸换了会清表，引用不再相同，照旧重建）。
                            val keptPadding = rowPaddingOf(view)
                            val prev = rowScrimDrawables[view]
                            if (prev != null && view.background === prev) {
                                rowPaddingKept[view]?.let { applyKeptPadding(view, it) }
                                counters[1]++
                                scrimmed = true
                                skippedRowRewrite710q++
                            } else {
                                val insetRow = InsetDrawable(ColorDrawable(scrim), 0, 0, 0, gapPx)
                                view.background = insetRow
                                rowScrimDrawables[view] = insetRow
                                clearedViews.remove(view)
                                rowPaddingKept[view] = keptPadding
                                applyKeptPadding(view, keptPadding)
                                counters[1]++
                                scrimmed = true
                                if (log && sample.size < 120) {
                                    sample.add("${view.javaClass.simpleName}#${idName(view)} ${width}x${height} bg=${background.javaClass.simpleName}→通栏行${ROW_GAP_DP}dp缝 (钉缩进=${keptPadding.toList()})")
                                }
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
        val alpha = if (rowMaskAlpha >= 0) rowMaskAlpha else if (isHome) HOME_ROW_ALPHA else CONTENT_ROW_ALPHA
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
            runCatching { existing.setImageDrawable(anchored(existing, bitmap)) }
            repaintHomeHosts(log = false)
            if (log) HLog.i("$TAG 首页图片层已存在，更新图片: ${bitmap.width}x${bitmap.height}")
            return
        }
        val image = ImageView(page.context)
        image.tag = HOME_IMAGE_TAG
        // 5.5.71：覆盖层不能吃触摸，否则会挡住标题栏搜索/＋号（参考 Wuyu134 的做法）
        image.isClickable = false
        image.isFocusable = false
        image.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        image.scaleType = ImageView.ScaleType.FIT_XY
        image.setImageDrawable(anchored(image, bitmap))
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
    /** 为某个 View 取得「全屏锚定」背景图；同一 View 复用同一份，锚定尺寸随窗口变化重设。 */
    private fun anchored(view: View, image: Bitmap): ScreenAnchoredBitmapDrawable {
        val cached = homeImageDrawables[view]
        val drawable = if (cached != null && cached.holds(image)) cached
        else ScreenAnchoredBitmapDrawable(image, view).also { homeImageDrawables[view] = it }
        drawable.bindAnchor(anchorWidth, anchorHeight)
        return drawable
    }

    /** 5.5.104：对全部已登记锚定壁纸宿主做一次漂移探测并排定重录。
     *
     *  710p：710o 曾在这里加过「漂移 > 2 就排定一次 710j 强制重录」，想修面板半透明层，
     *  真机 1022 日志证伪：静止页面上驻留的漂移本来就常 > 2，结果每轮巡检都排定补录，
     *  1 分钟内触发 19 次（`动画结束补录(710o)` 19 条、`布局落定后强制重录(710j)` 累计 12），
     *  变成自己制造抖动 —— 已按「已修好的路径不许再动」原则**整体撤回**，恢复 710j 的原始形态。
     *  面板半透明层后续只做取证，不再靠猜改这条链路。 */
    private fun syncAnchoredHosts() {
        if (homeImageDrawables.isEmpty()) return
        for (d in homeImageDrawables.values.toList()) runCatching<Unit> { d.requestSyncIfDrifted() }
    }

    /**
     * 710j：布局落定后对锚定壁纸宿主做一次**强制重录**。
     *
     * 真机 1014 反馈两条：①从主页下拉进「最近」小程序面板时，中间压着一层半透明图，点一下屏幕
     * 才变透明；②发现页/我的页图标偶发错位。两者都是 599 记录过的同一类问题：面板/翻页的
     * property 动画期间录进 display list 的帧会滞留，直到下一次宿主重录（触摸恰好触发）才纠正。
     *
     * 做法与 `requestSyncIfDrifted()` 互补而不是替代：
     * - 只有「布局变更」才排定（翻页、面板弹出/收起、页面 inflate 都会触发，静止时不打扰）；
     * - 延迟 700ms 再动手，给入场动画留出时间；
     * - 动手前先看漂移量（`driftNow()`），动画还没停就 200ms 后再试，最多 5 次 ——
     *   避免把动画中途的平移量烙进 display list（648 踩过的坑）；
     * - 设置页按住期间、壁纸撤销期间直接跳过。
     */
    private fun scheduleSettleReRecord710() {
        settleReRecordRunnable?.let { mainHandler.removeCallbacks(it) }
        val r = Runnable { settleReRecordNow710(0) }
        settleReRecordRunnable = r
        mainHandler.postDelayed(r, SETTLE_RERECORD_DELAY_MS)
    }

    private fun settleReRecordNow710(attempt: Int) {
        if (homeImageDrawables.isEmpty()) {
            settleReRecordRunnable = null
            return
        }
        if (wallpaperHidden || settingsLikeHidden710.get()) {
            settleReRecordRunnable = null
            return
        }
        val list = homeImageDrawables.values.toList()
        val busy = list.any { runCatching { it.driftNow() }.getOrDefault(-1) > 2 }
        if (busy) {
            if (attempt >= SETTLE_RERECORD_MAX_RETRY) {
                settleReRecordRunnable = null
                return
            }
            val r = Runnable { settleReRecordNow710(attempt + 1) }
            settleReRecordRunnable = r
            mainHandler.postDelayed(r, SETTLE_RERECORD_RETRY_MS)
            return
        }
        var n = 0
        list.forEach { if (runCatching { it.forceReRecord() }.getOrDefault(false)) n++ }
        settleReRecordCount710++
        val now = SystemClock.uptimeMillis()
        if (now - settleReRecordLogAt710 >= 3000L) {
            settleReRecordLogAt710 = now
            HLog.i("$TAG 布局落定后强制重录(710j): 宿主=$n 个 累计=$settleReRecordCount710 尝试=$attempt")
        }
        settleReRecordRunnable = null
    }

    private fun ensurePageImageHosts(page: View, bitmap: Bitmap, log: Boolean) {
        if (page !is ViewGroup) return
        val hosts = ArrayList<View>()
        val pageW = pageWidthOf(page)
        val pageH = pageHeightOf(page)
        for (index in 0 until page.childCount) {
            collectBigContainers(page.getChildAt(index), pageW, pageH, hosts)
        }
        anchored(page, bitmap)
        var painted = 0
        hosts.forEach { host ->
            if (!homeImageHosts.containsKey(host)) homeImageHosts[host] = host.background
            val drawable = anchored(host, bitmap)
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
            if (view.javaClass.simpleName == "AppBrandDesktopContainerView") return
            if (width >= pageWidth * 0.85f && height >= pageHeight * 0.45f) out.add(view)
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
        if (mountOff703()) { dumpCounts703("mount-skip applyWindowBackground("); return }
        // 710：叠加页（微信设置页 / 小程序）在前台时壁纸层已撤下，enforce 每 400ms 跑一次会把壁纸
        // 重新铺回 decor+窗口底，结果叠加页又一帧一帧透出壁纸。这里直接不铺。
        if (wallpaperHidden) return
        // 710h：设置页（LauncherUI 内的叠加页）在前台时一律不铺 decor 壁纸。
        // 这条路径不只在 enforce 里走：installLifecycleBumps 注册的 addOnGlobalLayoutListener
        // 每次布局变更都会调一次 applyWindowBackground，而设置页里滚动/焦点变化会不断触发它，
        // 于是 710c 刚按住的那层整屏壁纸又被铺回 decor —— 真机 1012 表现为「设置页右上角
        // 一直留一块壁纸，而代理自截帧里又看不到」（重铺发生在截图之间的空档）。
        if (settingsLikeHidden710.get()) {
            blockedDecorWrites710++
            if (blockedDecorWrites710 == 1 || blockedDecorWrites710 % 50 == 0) {
                HLog.i("$TAG 设置页按住中，跳过铺 decor 壁纸(710h): 累计=${blockedDecorWrites710}")
            }
            return
        }
        // 619：黑线定位探针挂在这条每次 enforce 都会走的路径上（3 秒一次、只读）
        runCatching { locateLines(activity, decor, SystemClock.uptimeMillis()) }
        runCatching { sampleDarkRows(decor, SystemClock.uptimeMillis()) }
        rowMaskAlpha = (snapshot.rowMaskStrength.coerceIn(0, 100) * 255) / 100
        if (snapshot.rowMaskStrength != lastAppliedRowStrength) {
            if (lastAppliedRowStrength >= 0) {
                runCatching {
                    scrimmedPages.clear()
                    rowScrimDrawables.clear()
                    HLog.i("$TAG 行遮罩强度变更: $lastAppliedRowStrength -> ${snapshot.rowMaskStrength}，已重置分层缓存并重扫")
                }
            }
            lastAppliedRowStrength = snapshot.rowMaskStrength
        }
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
        if (isSettingsPage703(activity)) { restoreNativeDecorIfOurs703(activity, decor); return }
        anchorWidth = decor.width.coerceAtLeast(decor.measuredWidth)
        anchorHeight = decor.height.coerceAtLeast(decor.measuredHeight)
        val drawable: Drawable = anchored(decor, image)
        if (decor.background !== drawable) {
            if (!replacedBackgrounds.containsKey(decor)) replacedBackgrounds[decor] = decor.background
            decor.setBackgroundDrawable(drawable)
        }
        HLog.i("$TAG 状态栏区域已铺背景图: 解码=${image.width}x${image.height} 标题栏透明=${snapshot.actionBarBackgroundColor.isBlank()}")
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
        lastHostActivity = java.lang.ref.WeakReference(activity)
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
        runCatching { decor.findViewById<View>(android.R.id.statusBarBackground) }.getOrNull()?.let {
            targets.add(it)
            HLog.i("$TAG 状态栏层灰底待清: ${it.javaClass.simpleName}#${idName(it)} ${it.width}x${it.height} bg=${descDrawable(it.background)}")
        }
        // 5.5.88：14:35 日志里全 decor 仅剩的两处 #ededed 灰底——statusBarBackground（此前按框架 id 清，但
        // getIdentifier 查的是宿主包返回 0，从未清过）与 FirstScreenFrameLayout#g6w（唯一幸存灰底，判别实验）。
        listOf("ActionBarOverlayLayout", "ContentFrameLayout", "ActionBarContainer", "FitSystemWindowLayoutView", "FirstScreenFrameLayout").forEach { className ->
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
        // 677：灰白那条不在上面这些容器上，而在 ActionBar 内部的子视图里。
        // 判据（10:07:59 主页单开时 decor 顶部转储）：
        //   `LinearLayout#- 1240x174 bg=fff7f7f7`（整宽标题条）、`RelativeLayout#nvt 310x174 bg=NinePatchDrawable`（标题卡片），
        //   两者都在 ActionBar 子树内、高≈标题栏高、底色不透明。只清背景，不动 padding/尺寸，不碰 ImageView 与状态栏条。
        var barCleared = 0
        runCatching {
            val bar = findViewByClassName(decor, "ActionBarContainer")
                ?: findViewByClassName(decor, "ActionBarOverlayLayout")
            val barW = bar?.width?.coerceAtLeast(bar.measuredWidth) ?: 0
            val barH = bar?.height?.coerceAtLeast(bar.measuredHeight) ?: 0
            if (bar is ViewGroup && barW > 0 && barH > 0) {
                fun walkBar(v: View, d: Int) {
                    if (d > 8) return
                    val w = v.width.coerceAtLeast(v.measuredWidth)
                    val h = v.height.coerceAtLeast(v.measuredHeight)
                    val bg = v.background
                    if (v !== bar && v.id != android.R.id.statusBarBackground &&
                        v !is android.widget.ImageView && bg != null && bg.alpha == 255 &&
                        w >= barW * 0.15f && h in 1..(barH * 2) &&
                        !(bg is ColorDrawable && Color.alpha(bg.color) == 0) &&
                        !homeImageHosts.containsKey(v)
                    ) {
                        if (!replacedBackgrounds.containsKey(v)) replacedBackgrounds[v] = bg
                        v.setBackgroundColor(Color.TRANSPARENT)
                        clearedViews.add(v)
                        barCleared++
                        if (names.size < 12) names.add("${v.javaClass.simpleName}#${idName(v)}")
                    }
                    if (v is ViewGroup) for (i in 0 until v.childCount) walkBar(v.getChildAt(i), d + 1)
                }
                walkBar(bar, 0)
            }
        }.onFailure { HLog.e("$TAG 标题栏内部清底异常(677): ${it.message}") }
        if (barCleared > 0 && titleBarClearedLogged < 5) {
            titleBarClearedLogged++
            HLog.i("$TAG 标题栏内部灰白底已清(677): $barCleared 处（ActionBar 子树里高≈标题栏高的不透明底色）")
        }
        // 678：11:08 日志证明灰带不在 ActionBar 子树里（ActionBar 自身 bg=ColorDrawable#7a7396＝全透明），
        // 而在 **decor 顶部的其他兄弟层**上：绘制栈 `ColorDrawable.draw ← View.drawBackground`，
        // 颜色 `#ffededed`，且宿主对 View#nhm 写 #ffededed 累计 370 次（我们只在 636 钩子上拦它的写入）。
        // 判据（截图定量 1240x2772）：主页/通讯录 的 y≈139..270 一条纯 #EDEDED。这里按「整宽 × 顶带内 × 不透明浅灰/白 .9」
        // 一次性清成透明并登记 clearedViews，让 613 的引用级守卫在宿主再写回时也换回我们的透明。
        var topBandCleared = 0
        runCatching {
            val decorW = decor.width.coerceAtLeast(decor.measuredWidth)
            val decorH = decor.height.coerceAtLeast(decor.measuredHeight)
            if (decorW > 0 && decorH > 0) {
                val bandLimit = (decorH * 0.20f).toInt().coerceIn(200, 620)
                fun absTopOf(v: View): Int {
                    var top = 0
                    var cur: View? = v
                    var guard = 0
                    while (cur != null && guard++ < 24) {
                        top += cur.top
                        cur = cur.parent as? View
                    }
                    return top
                }
                fun walkTop(v: View, d: Int) {
                    if (d > 18) return
                    val w = v.width.coerceAtLeast(v.measuredWidth)
                    val h = v.height.coerceAtLeast(v.measuredHeight)
                    val bg = v.background
                    if (v !== decor && w > 0 && h in 1..520 && w >= decorW * 0.5f && bg != null &&
                        bg !is ScreenAnchoredBitmapDrawable && bg !is PagerStripWallpaperDrawable &&
                        !homeImageHosts.containsKey(v) && !pageDrawables.containsKey(v)
                    ) {
                        val grey = when (bg) {
                            is ColorDrawable ->
                                Color.alpha(bg.color) == 255 && Color.red(bg.color) >= 230 &&
                                    Color.green(bg.color) >= 230 && Color.blue(bg.color) >= 230
                            is android.graphics.drawable.NinePatchDrawable -> true
                            else -> false
                        }
                        if (grey && absTopOf(v) < bandLimit) {
                            if (!replacedBackgrounds.containsKey(v)) replacedBackgrounds[v] = bg
                            v.setBackgroundColor(Color.TRANSPARENT)
                            clearedViews.add(v)
                            topBandCleared++
                            HLog.i(
                                "$TAG 顶带灰底已清(678): ${v.javaClass.simpleName}#${idName(v)} ${w}x$h" +
                                    " top=${absTopOf(v)} bg=" + descDrawable(bg)
                            )
                        }
                    }
                    if (v is ViewGroup) for (i in 0 until v.childCount) walkTop(v.getChildAt(i) ?: continue, d + 1)
                }
                walkTop(decor, 0)
            }
        }.onFailure { HLog.e("$TAG 顶带清底异常(678): ${it.message}") }
        if (topBandCleared > 0) HLog.i("$TAG 顶带灰底合计已清(678): $topBandCleared 处")
        if (cleared == 0 && runCatching {
            val t = android.os.SystemClock.elapsedRealtime()
            val last = titleDumpedAt[decor]
            if (last != null && t - last < topDumpIntervalMs) return@runCatching false
            titleDumpedAt[decor] = t
            true
        }.getOrDefault(false)) {
            val rows = ArrayList<String>()
            fun walk(v: View, d: Int) {
                if (rows.size >= 30 || d > 14) return
                val w = v.width.coerceAtLeast(v.measuredWidth)
                val h = v.height.coerceAtLeast(v.measuredHeight)
                if (h > 40 && w > 200 && v.background != null) {
                    val b = v.background
                    val col = if (b is ColorDrawable) Integer.toHexString(b.color)
                    else if (b is android.graphics.drawable.GradientDrawable) try { Integer.toHexString(b.color?.defaultColor ?: 0) } catch (e: Throwable) { "?" }
                    else b.javaClass.simpleName
                    val img = (v as? android.widget.ImageView)?.let { iv -> if (iv.drawable == null) "img=无" else "img=有" } ?: ""
                    rows.add("${"·".repeat(d)}${v.javaClass.simpleName}#${idName(v)} ${w}x${h} bg=$col$img")
                }
                if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i), d + 1)
            }
            walk(decor, 0)
            HLog.i("$TAG 标题卡清空0处，decor 顶部带底色视图转储 ${rows.size} 条")
            rows.forEach { HLog.i("$TAG   标题区: $it") }
        }
        // 710：状态栏那条「黄色渐变」——678 的顶带清底只认 #E0..#FF 的浅灰/白 .9，黄色渐变
        // （GradientDrawable / 非纯色）全部落空，所以日志一直是 `标题卡片区域已全透明: 清空 0 处`。
        // 这里补一趟：贴着屏幕最顶端、整宽、高度≈状态栏、底色不是我们自己的壁纸 → 一律清透明，
        // 并把类名与底色打出来（这次就能看清那条渐变到底挂在谁身上）。
        var statusBandCleared = 0
        runCatching {
            val decorW = decor.width.coerceAtLeast(decor.measuredWidth)
            if (decorW > 0) {
                val statusH = runCatching {
                    val resId = decor.resources.getIdentifier("status_bar_height", "dimen", "android")
                    if (resId != 0) decor.resources.getDimensionPixelSize(resId) else 0
                }.getOrDefault(0).let { if (it > 0) it else (decor.resources.displayMetrics.density * 32f).toInt() }
                fun walkBand(v: View, d: Int) {
                    if (d > 16) return
                    val w = v.width.coerceAtLeast(v.measuredWidth)
                    val h = v.height.coerceAtLeast(v.measuredHeight)
                    val bg = v.background
                    if (v !== decor && v.id != android.R.id.statusBarBackground &&
                        v !is android.widget.ImageView && bg != null &&
                        !(bg is ColorDrawable && Color.alpha(bg.color) == 0) &&
                        w >= decorW * 0.9f && h in 1..(statusH * 2) &&
                        !isOurWallpaperDrawable(bg) &&
                        bg !is ScreenAnchoredBitmapDrawable && bg !is PagerStripWallpaperDrawable &&
                        !homeImageHosts.containsKey(v) && absTopOfView(v) <= statusH / 2
                    ) {
                        if (!replacedBackgrounds.containsKey(v)) replacedBackgrounds[v] = bg
                        v.setBackgroundColor(Color.TRANSPARENT)
                        clearedViews.add(v)
                        rowScrimDrawables.remove(v)
                        statusBandCleared++
                        HLog.i(
                            "$TAG 状态栏条底色已清(710): ${v.javaClass.simpleName}#${idName(v)} ${w}x$h" +
                                " top=${absTopOfView(v)} bg=${descDrawable(bg)}"
                        )
                    }
                    if (v is ViewGroup) for (i in 0 until v.childCount) walkBand(v.getChildAt(i) ?: continue, d + 1)
                }
                walkBand(decor, 0)
            }
        }.onFailure { HLog.e("$TAG 状态栏条清底异常(710): ${it.message}") }
        if (statusBandCleared > 0) HLog.i("$TAG 状态栏条合计已清(710): $statusBandCleared 处（状态栏区域应完全透出壁纸）")
        HLog.i(
            "$TAG 标题卡片区域已全透明: 清空 $cleared 处底色 + 标题栏内 $barCleared 处 [${names.joinToString("/")}]" +
                " 状态栏全沉浸: 图标=${if (darkIcons) "深色" else "浅色"}" +
                " 背景亮度=${String.format(java.util.Locale.US, "%.2f", luma)}"
        )
        // 612：聊天页（群聊/私聊）状态栏并入页面底色。必须放在这轮清底之后——611 把它放在
        // enforce 的 runStep 里，同一轮稍后这里又把 statusBarBackground 清回透明，实测无效（22:0x 日志）。
        runCatching { applyChatStatusBarBand(activity, decor) }
            .onFailure { HLog.e("$TAG 聊天页状态栏处理异常(612): ${it.message}", it) }
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
        if (!snapshot.pageSplash) return
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
    /**
     * 613：冷启动首帧铺底。
     *
     * 证据（11-19 07:14 会话）：Hook 07:14:40.877 挂上，第一次 applyTheme 在 07:14:41.108。
     * 这 200~400ms 里窗口背景还是微信原生主题的浅色，首帧画出来就是「冷启动白底闪一下」。
     * 这里在 LauncherUI.onCreate 之后、首帧之前直接把 window 与 decor 的背景都换成窗口锚定壁纸：
     * 首帧就是壁纸，onResume 那次 applyTheme 只做同图同锚定的校正。
     */
    private fun applyEarlyWindowBackground(activity: Activity) {
        if (!earlyWindowApplied.add(activity)) return
        runCatching {
            val snapshot = ThemeSettings.snapshot(context.hostContext())
            if (!snapshot.enabled || !snapshot.backgroundEnabled) return@runCatching
            if (isSettingsPage703(activity)) {
                HLog.i("$TAG 设置页不铺窗口底(703): ${activity.javaClass.simpleName} 窗口底/装饰底保持宿主原样，列表容器所在页面底不透明")
                return@runCatching
            }
            val path = snapshot.backgroundHome
            if (path.isBlank()) return@runCatching
            val image = bitmap(path) ?: return@runCatching
            val window = activity.window ?: return@runCatching
            val decor = window.decorView as? ViewGroup ?: return@runCatching
            val dm = activity.resources.displayMetrics
            if (anchorWidth <= 0) anchorWidth = decor.width.takeIf { it > 0 } ?: dm.widthPixels
            if (anchorHeight <= 0) anchorHeight = decor.height.takeIf { it > 0 } ?: dm.heightPixels
            val drawable = anchored(decor, image)
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
            // 710：写 window 底之前先登记原值，否则设置页 / 非主题页只还原 decor、window 那层壁纸仍会透出来。
            if (!replacedWindowBackgrounds.containsKey(window)) {
                // compileSdk 37 的 android.view.Window 已无 getBackground()（CI 实测 Unresolved reference），
                // 只能从实现类（PhoneWindow）里读 mBackgroundDrawable；读不到就按 null 记，还原时等同透明底。
                val prevWindowBackground = runCatching {
                    KavaReflector.findFieldRecursive(window.javaClass, "mBackgroundDrawable")?.get(window) as? Drawable
                }.getOrNull()
                replacedWindowBackgrounds[window] = prevWindowBackground
            }
            window.setBackgroundDrawable(drawable)
            window.statusBarColor = Color.TRANSPARENT
            window.navigationBarColor = Color.TRANSPARENT
            if (decor.background !== drawable) {
                if (!replacedBackgrounds.containsKey(decor)) replacedBackgrounds[decor] = decor.background
                decor.setBackgroundDrawable(drawable)
            }
            HLog.i(
                "$TAG 冷启动首帧铺底(613): window+decor 背景=锚定壁纸 ${image.width}x${image.height}" +
                    " 锚定=${anchorWidth}x$anchorHeight 页面=${activity.javaClass.simpleName}"
            )
        }.onFailure { HLog.e("$TAG 冷启动首帧铺底失败(613): ${it.message}", it) }
    }

    /**
     * 613：宿主重写已登记视图底色时，返回我们应该保持的背景；null 表示这次写入不拦。
     *
     * 证据（11-19 07:14 会话）：`已清底视图被宿主重新上色，重新清透明: 1 个`、
     * `通栏行遮罩被宿主覆盖，已重压: 12 个`、`首页容器底色被宿主刷回…: ConversationListView#j8g`
     * 每 1~3 秒复现一次——宿主回收/重绑列表与容器时先写自己的浅底，我们的重压要等下一轮
     * enforce（≤400ms）才补上，中间那几帧就是「停留本页也偶发闪一下」。
     */
    /**
     * 710：某个视图「应该」是什么背景——唯一真相源。
     *
     * 优先级：页面底图 > 首页实心图片层 > 分格底衬 > 行遮罩 > 清透明。
     * 写入拦截（[guardedBackgroundFor]）、逐帧断言（[fastAssertBackgroundsBeforeDraw]）、
     * enforce 里的重压（[reassertRowScrims] / [reassertClearedBackgrounds]）全部只读这里，
     * 不再各自维护一套判断，杜绝「两个登记表给出相反答案 → 每帧互写」的死循环。
     */
    private fun desiredBackgroundFor(view: View): Drawable? {
        pageDrawables[view]?.let { return it }
        homeImageDrawables[view]?.let { return it }
        if (view is ViewGroup) pagerUnderlays[view]?.let { return it }
        rowScrimDrawables[view]?.let { return it }
        if (clearedViews.contains(view)) return guardTransparent
        return null
    }
    /**
     * 710：两张背景是否「视觉上等价到不需要再写一次」。
     *
     * 同一个引用当然算；两个全透明的 ColorDrawable 也算（透明换透明没有任何视觉变化，
     * 但 setBackgroundDrawable 仍会 requestLayout，正是闪烁死循环的燃料）。
     */
    private fun sameBackground(current: Drawable?, want: Drawable): Boolean {
        if (current === want) return true
        if (current is ColorDrawable && want is ColorDrawable) {
            val a = Color.alpha(current.color)
            val b = Color.alpha(want.color)
            if (a == 0 && b == 0) return true
            if (a == 255 && b == 255 && current.color == want.color) return true
        }
        return false
    }
    /** 710：这个视图是否属于「已被用户关掉背景」的页面（宿主再写深色底时要还原成原生灰）。 */
    private fun inOffPage710(view: View): Boolean {
        if (offPageNative710.isEmpty()) return false
        var cur: View? = view
        var guard = 0
        while (cur != null && guard++ < 24) {
            if (offPageNative710.contains(cur)) return true
            cur = cur.parent as? View
        }
        return false
    }
    private fun guardedBackgroundFor(view: View, incoming: Drawable): Drawable? {
        if (gateWrite702()) return null
        // 676：叠加页在前台时壁纸层已撤下，这段时间放行宿主自己写背景，别把壁纸又拉回来。
        if (wallpaperHidden) return null
        // 710c：设置页可见期间同样放行，避免把刚按下去、换成的原生灰又刷回壁纸。
        if (settingsLikeHidden710.get()) return null
        // 状态栏那条 139px 由 612 的 window.statusBarColor 通道管，绝不能被这里的「清透明」碰到。
        if (view.id == android.R.id.statusBarBackground) return null
        try {
            // 710：通讯录这类「用户关掉背景图」的页面，宿主会把整页容器写成 #ff232323 深色底。
            // 在写入处直接换成原生灰，比事后巡检早一帧，也不会和别的登记表打架。
            if (incoming is ColorDrawable && isNativeDark(incoming.color) && inOffPage710(view)) {
                return ColorDrawable(NATIVE_GREY)
            }
            // 710：改成单一真相源。原来这里是「逐条 if 不匹配就 return」，一旦同一个 View 同时被登记到
            // rowScrimDrawables（要行遮罩）和 clearedViews（要透明），写行遮罩时会被 clearedViews 判成
            // 「不透明 → 换 guardTransparent」，于是引用永远对不上、每帧重写一次：
            // 真机日志 `背景写入已拦回(613): 累计 2238966 次`（30 万涨到 223 万）、
            // `通栏行遮罩被宿主覆盖，已重压: 12 个` 与 `已清底视图被宿主重新上色，重新清透明: 12 个`
            // 互相覆盖，setBackgroundDrawable 每次 requestLayout+invalidate → 页面持续闪烁、图标错位。
            val want = desiredBackgroundFor(view) ?: return null
            if (want === incoming) return null
            return want
        } catch (t: Throwable) {
            // 拦截本身绝不能影响宿主设置背景
        }
        return null
    }

    /**
     * 613：绘制前的逐帧断言。只比对引用、不扫树，把「漏过写入拦截」的宿主回刷
     * （例如直接改 Drawable 颜色、或走我们没钩到的通道）在同一帧内拉回。
     */
    private fun fastAssertBackgroundsBeforeDraw() {
        if (gateWrite702()) return
        if (wallpaperHidden) return
        // 710c：设置页可见期间不要逐帧把壁纸断言回来。
        if (settingsLikeHidden710.get()) return
        if (!fastAssertLogged) {
            fastAssertLogged = true
            HLog.i("$TAG 逐帧背景断言已生效(613): 每帧绘制前把被宿主改写的页面/容器/行底色拉回")
        }
        var fixed = 0
        // 710：合并成一次遍历 + 单一真相源。原来分五趟各自判断，行视图同时命中
        // rowScrimDrawables 与 clearedViews 时，两趟会互相把对方刚写的背景改回去，
        // 于是每一帧都要写、每一帧都 invalidate（真机 `背景写入已拦回` 30 万→223 万）。
        val candidates = LinkedHashSet<View>()
        runCatching {
            candidates.addAll(pageDrawables.keys.toList())
            candidates.addAll(homeImageDrawables.keys.toList())
            candidates.addAll(pagerUnderlays.keys.toList())
            candidates.addAll(rowScrimDrawables.keys.toList())
            candidates.addAll(clearedViews.toList())
        }
        for (view in candidates) {
            if (!view.isAttachedToWindow) continue
            val want = desiredBackgroundFor(view) ?: continue
            if (view.background === want) continue
            runCatching { view.setBackgroundDrawable(want) }.onSuccess { fixed++ }
        }
        if (fixed > 0 && SystemClock.uptimeMillis() - lastGuardLogAt >= 5000L) {
            lastGuardLogAt = SystemClock.uptimeMillis()
            HLog.i("$TAG 绘制前拉回底色(613): $fixed 处走的是断言兜底而非写入拦截")
        }
    }

    /**
     * 613：页面内容第一次 inflate（或重建）的那次 layout 里，在 draw 之前把这页铺底+分层做到位。
     *
     * 通讯录/发现/我 的页面壳一直在 ViewPager 里（1240x2772），真正的内容是第一次显示时才建的；
     * 内容是宿主自己的浅色底，原来只在下一次 enforce 全树扫描时才被清，那一下白就是用户说的
     * 「每个页面冷启动先闪一下白底」。layout 监听比定时补刷早一帧，且不改宿主布局。
     */
    private fun installPageLayoutWatch(page: View, index: Int) {
        if (!pageLayoutWatched.add(page)) return
        runCatching {
            page.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
                override fun onLayoutChange(
                    v: View,
                    left: Int,
                    top: Int,
                    right: Int,
                    bottom: Int,
                    oldLeft: Int,
                    oldTop: Int,
                    oldRight: Int,
                    oldBottom: Int
                ) {
                    val activity = lastHostActivity?.get()
                    if (activity == null) {
                        HLog.e("$TAG 页面布局回调(614): 第 ${index + 1} 页 拿不到宿主 Activity，跳过")
                        return
                    }
                    runCatching { prepPageNow(activity, page, index, "首次布局") }
                        .onFailure { HLog.e("$TAG 页面布局预热异常(614): ${it.message}", it) }
                }
            })
        }.onFailure { HLog.e("$TAG 页面布局监听挂载失败(613): ${it.message}", it) }
    }

    /** 页面结构的廉价指纹（直接子项类名+子项数+孙项数），用于判断「内容是否刚建/重建」。 */
    private fun pageSignatureOf(page: View): Int {
        val group = page as? ViewGroup ?: return page.width * 17 + page.height
        var sig = group.childCount * 17 + group.width + group.height * 3
        for (i in 0 until minOf(group.childCount, 8)) {
            val c = group.getChildAt(i) ?: continue
            sig = sig * 31 + c.javaClass.name.hashCode()
            if (c is ViewGroup) sig = sig * 31 + c.childCount
        }
        return sig
    }

    // 681：关页原生灰还原 / 微信原生设置页灰底还原
    private val NATIVE_GREY = 0xffededed.toInt()
    private val offPageFillAt: MutableMap<View, Long> = java.util.HashMap()
    /** 710：已经按「关页」还原成原生灰的页面（宿主再写深色底时要在写入处换成原生灰）。 */
    private val offPageNative710: MutableSet<View> = java.util.Collections.synchronizedSet(
        java.util.Collections.newSetFromMap(java.util.WeakHashMap<View, Boolean>())
    )
    private var offPageFillCount = 0
    private var lastOffPageFillLogAt = 0L
    private val settingsGreyAt: MutableMap<Activity, Long> = WeakHashMap()
    // 682：主开关/四页全关时切「原生模式」用的状态与探针
    private var lastNativeModeCheckAt = 0L
    /** 710：壁纸层归属日志节流（applyPageBitmap 每次重铺都会调，不节流会刷满日志）。 */
    private var lastWallpaperOwnerLogAt = 0L
    private var nativeModeOn = false
    private var lastNativeFillAt = 0L
    private var nativeFillCount = 0
    private var lastOverlayProbeAt = 0L
    private var nativeGuardsInstalled = false
    private var settingsHook684 = false

    /**
     * 684：原生模式下把「页面」做成不透明原生灰，并转储页面大小的非纯色底（定位状态 B 透出的那层）。
     * 依据：683 日志 `壁纸层=0`、`划动跟随(617)…底衬=null`、`decorBg=ColorDrawable` ——
     * 透出的不是自家壁纸层，而是「我们让页面变透明 + 主题不在」时露出的微信自己的底。
     */
    private fun applyNativePages684(decor: ViewGroup, reason: String) {
        val dm = decor.resources.displayMetrics
        val queue = ArrayList<View>(); queue.add(decor); var i = 0
        var pages = 0; var bars = 0; var odd = 0
        while (i < queue.size && i < 1500) {
            val v = queue[i]; i++
            val w = v.width.coerceAtLeast(v.measuredWidth); val h = v.height.coerceAtLeast(v.measuredHeight)
            val cn = v.javaClass.name
            val isPager = cn.contains("ViewPager")
            if (isPager && v is ViewGroup) {
                for (k in 0 until v.childCount) {
                    val c = v.getChildAt(k) ?: continue
                    if (c.width >= dm.widthPixels * 9 / 10 && c.height >= dm.heightPixels / 3) {
                        runCatching { c.setBackgroundDrawable(ColorDrawable(NATIVE_GREY)) }
                        pages++
                        if (pages <= 4) HLog.i("$TAG 原生模式页面已置不透明(684): ${c.javaClass.simpleName}#${idName(c)} 视=${c.width}x${c.height} 原=${c.background?.javaClass?.simpleName}")
                    }
                }
            }
            if (cn.contains("ActionBarContainer") && w >= dm.widthPixels * 9 / 10 && h >= 40) {
                runCatching { v.setBackgroundDrawable(ColorDrawable(NATIVE_GREY)) }
                bars++
                if (bars <= 3) HLog.i("$TAG 原生模式标题栏已置不透明(684): ${v.javaClass.simpleName}#${idName(v)} 视=${w}x$h")
            }
            val bg = v.background
            if (bg != null && bg !is ColorDrawable && w >= dm.widthPixels / 2 && h >= dm.heightPixels / 3) {
                odd++
                if (odd <= 8) HLog.i("$TAG 页面非纯色底探针(684): ${v.javaClass.simpleName}#${idName(v)} 视=${w}x$h 原=${bg.javaClass.name}")
            }
            if (v is ViewGroup) for (k in 0 until v.childCount) v.getChildAt(k)?.let { queue.add(it) }
        }
        HLog.i("$TAG 原生模式页面/标题栏(684): 页面置灰=$pages 标题栏置灰=$bars 页面级非纯色底=$odd（$reason）")
    }

    /** 683：宿主深色底家族（#181818..#303030 的不透明灰黑）。 */
    private fun isNativeDark(c: Int): Boolean {
        if (Color.alpha(c) != 255) return false
        val r = c shr 16 and 0xFF; val g = c shr 8 and 0xFF; val b = c and 0xFF
        return r == g && g == b && r >= 0x18 && r <= 0x30
    }

    /** 683：自家壁纸层（锚定层 / 底衬）——只认自家类名，绝不碰宿主 drawable。 */
    private fun isOurWallpaperDrawable(d: Any?): Boolean {
        if (d == null) return false
        val n = d.javaClass.name
        return n.contains("ScreenAnchoredBitmapDrawable") || n.contains("PagerStripWallpaperDrawable") || n.contains("Wallpaper")
    }

    /** 683：原生模式下把自家壁纸层摘成原生灰（一次性，状态翻转时跑）。 */

    private var blockedWrites702 = 0
    private fun masterOff702(): Boolean = runCatching { nativeOnlyMode() }.getOrDefault(false)
    private fun gateWrite702(): Boolean {
        if (!masterOff702()) return false
        blockedWrites702++
        return true
    }


    private var installCount703 = 0
    private var writeCount703 = 0
    private fun snap703(): Any? = runCatching { ThemeSettings.snapshot(context.hostContext()) }.getOrNull()
    /** 703: 挂载级门控。总开关关闭或四页全关时，壁纸层/分格底衬/行遮罩/decor 一律不安装。 */
    private fun mountOff703(): Boolean = runCatching {
        val s = ThemeSettings.snapshot(context.hostContext())
        s == null || !s.enabled || (!s.pageHome && !s.pageContacts && !s.pageDiscover && !s.pageMe)
    }.getOrDefault(true)
    private fun mountOn703(where: String) {
        installCount703++
        if (installCount703 <= 8) HLog.i("$TAG 模块侧安装(703): $where 累计=$installCount703")
    }
    private fun writeOn703(where: String) {
        writeCount703++
        if (writeCount703 <= 8) HLog.i("$TAG 模块侧 View 写入(703): $where 累计=$writeCount703")
    }
    private fun dumpCounts703(reason: String) {
        HLog.i("$TAG 模块侧计数(703) $reason 安装=$installCount703 View写入=$writeCount703")
    }


    /** 703：微信自己的设置界面不铺我们的窗口底/装饰底。这类页面的页面底（#EDEDED）本来就不透明、列表项只盖住一部分，一换成壁纸那一条就露出来。 */
    private fun isSettingsPage703(a: Activity): Boolean = runCatching {
        val n = a.javaClass.name
        n.startsWith("com.tencent.mm.plugin.setting") || n.contains("Setting") || n.contains("etting")
    }.getOrDefault(false)

    /** 703：非主题页不铺壁纸；若此前铺过，用原底精确还原（只还原我们写过的那一层）。 */
    private fun restoreNativeDecorIfOurs703(activity: Activity, decor: ViewGroup) {
        runCatching {
            // 710：先还原 window 底（冷启动铺底写的就是这一层），再还原 decor，顺序反了会露出中间态。
            val window = activity.window
            if (window != null && replacedWindowBackgrounds.containsKey(window)) {
                val prevWindow = replacedWindowBackgrounds.remove(window)
                runCatching { window.setBackgroundDrawable(prevWindow) }
                HLog.i(
                    "$TAG 非主题页还原窗口底(710): ${activity.javaClass.simpleName}" +
                        " 原=${prevWindow?.javaClass?.simpleName ?: "null"}"
                )
            }
            val prev = replacedBackgrounds.remove(decor)
            if (prev != null && decor.background !== prev) {
                decor.setBackgroundDrawable(prev)
                HLog.i("$TAG 非主题页还原原生页面底(703): ${activity.javaClass.simpleName} 视=${decor.width}x${decor.height} 原=${prev.javaClass.simpleName}")
            } else {
                HLog.i("$TAG 非主题页不铺壁纸(703): ${activity.javaClass.simpleName} decorBg=${decor.background?.javaClass?.simpleName ?: "null"}")
            }
            dumpNonThemeContainers703(decor, activity)
        }
    }

    /** 703 取证：列出比屏宽窄、高度>=40px 的容器（设置页那条右侧缺口应当被点名）。只读。 */
    private fun dumpNonThemeContainers703(root: ViewGroup, activity: Activity) {
        runCatching {
            val screen = root.width
            val hits = ArrayList<String>()
            val q = ArrayDeque<android.view.View>()
            q.add(root)
            var seen = 0
            while (q.isNotEmpty() && seen < 600) {
                val v = q.removeFirst(); seen++
                if (v.width in 1 until (screen - 4) && v.height >= 40) {
                    hits.add("[${v.javaClass.simpleName}#${idName(v)} ${v.width}x${v.height} bg=${v.background?.javaClass?.simpleName ?: "null"}]")
                }
                if (v is ViewGroup) for (i in 0 until v.childCount) q.add(v.getChildAt(i))
            }
            HLog.i("$TAG 非主题页容器转储(703): ${activity.javaClass.simpleName} 屏宽=$screen 扫描=$seen 窄容器=${hits.size} ${hits.take(8).joinToString(" ")}")
        }
    }

    private fun stripOurWallpaper(decor: ViewGroup, reason: String) {
        val queue = ArrayList<View>(); queue.add(decor)
        var i = 0; var n = 0
        while (i < queue.size && i < 1500) {
            val v = queue[i]; i++
            val bg = v.background
            if (isOurWallpaperDrawable(bg)) {
                val old = bg?.javaClass?.simpleName
                runCatching { v.setBackgroundDrawable(ColorDrawable(NATIVE_GREY)) }
                n++
                if (n <= 8) HLog.i("$TAG 原生模式已摘壁纸层(683): ${v.javaClass.simpleName}#${idName(v)} 原=$old（$reason）")
            }
            if (v is ViewGroup) for (k in 0 until v.childCount) v.getChildAt(k)?.let { queue.add(it) }
        }
        HLog.i("$TAG 原生模式壁纸层(683): 已摘=$n 处（$reason）")
    }

    /** 683：状态探针（关主开关 / 四页全关各打一次，报计数而不是「已处理」）。 */
    private fun dumpNativeState(reason: String) {
        val snap = runCatching { ThemeSettings.snapshot(context.hostContext()) }.getOrNull()
        val dec = runCatching { currentActivity()?.window?.decorView as? ViewGroup }.getOrNull()
        var dark = 0; var wp = 0
        if (dec != null) {
            val queue = ArrayList<View>(); queue.add(dec); var i = 0
            while (i < queue.size && i < 1500) {
                val v = queue[i]; i++
                val c = runCatching { (v.background as? ColorDrawable)?.color }.getOrNull()
                if (c != null && isNativeDark(c)) dark++
                if (isOurWallpaperDrawable(v.background)) wp++
                if (v is ViewGroup) for (k in 0 until v.childCount) v.getChildAt(k)?.let { queue.add(it) }
            }
        }
        HLog.i("$TAG 状态探针(683): 主开关=${snap?.enabled} 页=${snap?.pageHome}/${snap?.pageContacts}/${snap?.pageDiscover}/${snap?.pageMe} hidden=$wallpaperHidden 深色底=$dark 壁纸层=$wp decorBg=${dec?.background?.javaClass?.simpleName} 窗口=${currentActivity()?.javaClass?.simpleName}（$reason）")
    }

    /**
     * 683：在写入处拦宿主深色底——原生模式下把 #232323 族改成原生灰。
     * 618 的教训是「不要靠重复写宿主属性」，所以这里做的是「不让那一次写发生」，而不是事后反复巡检。
     */

    /** 684：不依赖 LauncherUI 生命周期的设置页钩子（设置页可能在别的进程，683 日志 `叠加页探测`=0）。 */
    private fun installSettingsResumeHook684() {
        if (settingsHook684) return
        settingsHook684 = true
        runCatching {
            // 710：设置页透壁纸的真正成因（真机日志推出来）：
            //   `开始应用主题：页面=` 25 次全是 LauncherUI、`窗口获焦/失焦` 一条没有、
            //   `还原窗口底(710)=0` —— 说明 676 那套 `addOnWindowFocusChangeListener` 在 Android 15 上
            //   从未回调，设置页（独立 Activity）也就从来没被识别成「叠加页」。
            // 这里改为钩 Activity#onResume（进程内所有 Activity 都会走），进入叠加页就撤壁纸、
            // 回到 LauncherUI 再补铺，不依赖窗口焦点事件。
            // 710 二轮：`Activity#onResume` 这条路在微信里没触发（钩子装上、日志为 0），
            // 微信的 Activity 基类覆盖了 onResume，未必调 super。改成钩
            // Instrumentation#callActivityOnResume —— ActivityThread 对**每一个** Activity 恢复都会走它，
            // 与宿主继承结构无关。
            XposedBridge.hookAllMethods(
                android.app.Instrumentation::class.java,
                "callActivityOnResume",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val a = param.args.getOrNull(0) as? Activity ?: return
                        runCatching { onOverlayActivityResumed710(a) }
                            .onFailure { HLog.e("$TAG 叠加页处理异常(710): ${it.message}") }
                    }
                }
            )
            // 保留 Activity#onResume 作为双保险（不重复处理：内部有 wallpaperHidden 状态机）。
            XposedBridge.hookAllMethods(Activity::class.java, "onResume", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val a = param.thisObject as? Activity ?: return
                    runCatching { onOverlayActivityResumed710(a) }
                        .onFailure { HLog.e("$TAG 叠加页处理异常(710b): ${it.message}") }
                }
            })
            HLog.i("$TAG 叠加页钩子已就位(710)：Instrumentation#callActivityOnResume + Activity#onResume")
        }.onFailure { HLog.e("$TAG 设置页钩子异常(684): ${it.message}") }
    }
    /**
     * 710b：设置页这类「半透明窗口 + 内容层留白」的叠加页，只撤 decor 壁纸还不够。
     * 真机截图（1000143806 / 1000143807）显示设置页顶部是直接透出「我的」页的头像与
     * 「海绵宝宝」卡片 —— 说明看到的并不是设置页自己画的壁纸，而是底下 LauncherUI 的壁纸层。
     * 半透明窗口里 `window.setBackgroundDrawable()` 是无效的（系统会把 decor 底置空），
     * 所以在叠加页 decor 的最底层插一块整窗原生灰底板，退出时整块摘掉。
     */
    private val overlaySettingsActivities710: MutableSet<Activity> = java.util.Collections.synchronizedSet(
        java.util.Collections.newSetFromMap(java.util.WeakHashMap<Activity, Boolean>())
    )
    private var lastOverlayBackdropLogAt = 0L
    private var overlayBackdropCount710 = 0

    /** 710b：微信「设置」家族 Activity（设置主页 / 通用 / 青少年模式 / 关怀模式 …）。 */
    private fun isSettingsFamily710(a: Activity): Boolean {
        val n = a.javaClass.name
        return !n.startsWith("com.aeonlab") && !n.contains(LAUNCHER_UI_CLASS) &&
            (n.startsWith("com.tencent.mm.plugin.setting") || n.contains("etting") || n.contains("Setting"))
    }

    private fun fullWindowLayoutParams(): ViewGroup.LayoutParams =
        android.widget.FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )

    /**
     * 710b：保证叠加页 decor 最底层有一块整窗原生灰底板。
     * 宿主/系统可能把它挪位或改回透明，所以每轮 enforce 都会再校一次（幂等，代价是一次 childCount 遍历）。
     */
    private fun ensureOverlayBackdrop710(a: Activity, reason: String) {
        val decor = runCatching { a.window?.decorView as? ViewGroup }.getOrNull() ?: return
        if (!decor.isAttachedToWindow) return
        if (decor !is android.widget.FrameLayout) return
        val existing = decor.findViewWithTag<View>(OVERLAY_BACKDROP_TAG)
        if (existing != null) {
            if ((existing.background as? ColorDrawable)?.color != NATIVE_GREY) {
                runCatching { existing.setBackgroundColor(NATIVE_GREY) }
            }
            if (existing.visibility != View.VISIBLE) runCatching { existing.visibility = View.VISIBLE }
            if (decor.indexOfChild(existing) != 0) {
                runCatching {
                    decor.removeView(existing)
                    decor.addView(existing, 0, fullWindowLayoutParams())
                }
            }
            return
        }
        runCatching {
            val backdrop = View(a)
            backdrop.tag = OVERLAY_BACKDROP_TAG
            backdrop.setBackgroundColor(NATIVE_GREY)
            backdrop.isClickable = false
            backdrop.isFocusable = false
            backdrop.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            decor.addView(backdrop, 0, fullWindowLayoutParams())
            overlayBackdropCount710++
            val now = SystemClock.uptimeMillis()
            if (now - lastOverlayBackdropLogAt >= 2000L) {
                lastOverlayBackdropLogAt = now
                HLog.i(
                    "$TAG 叠加页灰底板已插入(710b): ${a.javaClass.simpleName} decor=${decor.javaClass.simpleName}" +
                        " ${decor.width}x${decor.height} 累计=$overlayBackdropCount710（$reason）"
                )
            }
        }.onFailure { HLog.e("$TAG 叠加页灰底板插入失败(710b): ${it.message}") }
    }

    /**
     * 710c：微信「设置」不是独立 Activity —— 真机 dumpsys 里 user999 的任务栈只有 LauncherUI、
     * 日志全程只有一个 (com.tencent.mm) 进程，说明设置页是**在 LauncherUI 窗口内渲染的页面**。
     * 所以之前「给设置 Activity 插灰底板」打错了靶：那片壁纸其实是底下页面自己的壁纸层
     * （用户描述「背景图应该是我的页面的那张」完全对上）。
     *
     * 做法：扫顶层 300px 有没有设置类页面的标题文本，命中就把页面壁纸层撤掉、页面容器转原生灰，
     * 让设置页那些留白处透出灰底而不是壁纸；离开设置页再整体铺回。
     */
    private val settingsLikeHidden710 = java.util.concurrent.atomic.AtomicBoolean(false)
    /** 710f：被我们临时摘掉的自家壁纸 Drawable（设置页按住期间），键=视图，值=原背景。 */
    private val settingsSavedBgs710 = ArrayList<Pair<View, Drawable?>>()

    private val settingsTitles710 = arrayOf(
        "设置", "通用", "聊天", "隐私", "账号与安全", "朋友权限", "关于微信", "青少年模式",
        "关怀模式", "个人信息与权限", "新消息通知", "个人信息收集清单", "第三方信息共享清单",
        "订单与卡包", "界面和显示", "存储空间", "辅助功能", "发现页管理"
    )
    private var lastSettingsLikeLogAt = 0L
    /** 710c：连续判定「不在设置页」的轮数，用于抗抖（见 enforce 里的恢复门）。 */
    private var settingsMissCycles710 = 0
    private var blockedDecorWrites710 = 0

    /** 710c：只在设置页里出现、且不会出现在「我」页上的文案（次要证据）。 */
    private val settingsOnlyTitles710 = arrayOf(
        "账号与安全", "青少年模式", "关怀模式", "朋友权限",
        "个人信息与权限", "个人信息收集清单", "第三方信息共享清单", "关于微信"
    )

    /**
     * 710c：decor 里是否正在显示「设置类页面」。
     *
     * 主证据：decor 顶部 300px 内的标题文本命中 [settingsTitles710]（排除列表行里的同名文本）。
     * 次证据：树里同时出现 ≥2 个 [settingsOnlyTitles710] 里的设置页独有文案。
     * 次证据是为了抗抖：设置页滚动/切换子页时标题可能被回收或短暂置空，只靠主证据会
     * 每隔一两秒误判一次「已离开设置页」，壁纸于是反复隐显（真机 1009 日志里实测
     * 5 次按住 / 4 次放开即由此而来）。
     */
    private fun settingsLikeVisible710(decor: ViewGroup): Boolean {
        var hit = false
        var secondary = 0
        val queue = ArrayList<View>()
        queue.add(decor)
        var i = 0
        while (i < queue.size && i < 1200 && !hit) {
            val v = queue[i]; i++
            if (v is TextView && v.visibility == View.VISIBLE) {
                val t = v.text?.toString()?.trim()
                if (t != null && t.isNotEmpty() && t.length <= 10) {
                    if (!hit && absTopOfView(v) < 300 && settingsTitles710.contains(t) &&
                        !insideScrollable710(v)
                    ) hit = true
                    if (secondary < 2 && settingsOnlyTitles710.contains(t)) secondary++
                    if (hit || secondary >= 2) hit = true
                }
            }
            if (v is ViewGroup) for (k in 0 until v.childCount) v.getChildAt(k)?.let { queue.add(it) }
        }
        return hit
    }

    /** 710c：撤掉页面壁纸层（页面底图 ImageView + 页面背景），让设置页留白处透出原生灰。 */
    private fun hidePageWallpaperForSettings710(decor: ViewGroup, reason: String) {
        // 710h：改成每轮巡检幂等复清。原先用 compareAndSet 只在进入设置页的那一帧跑一次，
        // 之后只要任何路径再写回一层壁纸（布局监听里的 applyWindowBackground、宿主自己重铺、
        // syncAnchoredHosts 的漂移重录）就再也不会被清 —— 真机 1012 就是「右上角那块一直在」。
        // 记录表只在首次进入时清空，否则会把宿主原本的底丢掉。
        if (settingsLikeHidden710.compareAndSet(false, true)) settingsSavedBgs710.clear()
        var imgs = 0
        var pages = 0
        var bgs = 0
        val queue = ArrayList<View>()
        queue.add(decor)
        var i = 0
        while (i < queue.size && i < 1500) {
            val v = queue[i]; i++
            if (v.tag == HOME_IMAGE_TAG) {
                runCatching { v.visibility = View.GONE }
                imgs++
            } else if (isOurWallpaperDrawable(v.background)) {
                // 710f：状态栏那条壁纸（612 通道）与 pager 底衬不在 HOME_IMAGE_TAG 上，
                // 只认 tag 会漏掉它们 —— 真机 1011 上设置页右上角残留的那块黄就是状态栏图。
                // 710h：原值只在第一次记（那才是宿主自己的底），之后每轮只要又看到自家壁纸
                // 就重新压灰，避免「清一次 → 被重铺 → 永远露着」。
                if (settingsSavedBgs710.none { it.first === v }) settingsSavedBgs710.add(v to v.background)
                if ((v.background as? ColorDrawable)?.color != NATIVE_GREY) {
                    runCatching { v.setBackgroundDrawable(ColorDrawable(NATIVE_GREY)) }
                    bgs++
                }
            }
            if (v is ViewGroup) for (k in 0 until v.childCount) v.getChildAt(k)?.let { queue.add(it) }
        }
        pageDrawables.keys.toList().forEach { page ->
            runCatching { page.setBackgroundDrawable(ColorDrawable(NATIVE_GREY)) }
            pages++
        }
        val now = SystemClock.uptimeMillis()
        if (now - lastSettingsLikeLogAt >= 2000L) {
            lastSettingsLikeLogAt = now
            HLog.i("$TAG 设置页已按住页面壁纸(710c): 壁纸层=$imgs 页底=$pages 壁纸Drawable=$bgs（$reason）")
        }
    }

    /** 710c：离开设置页，把壁纸整体铺回来。 */
    private fun restorePageWallpaperAfterSettings710(decor: ViewGroup?, reason: String) {
        if (!settingsLikeHidden710.compareAndSet(true, false)) return
        var shown = 0
        if (decor != null) {
            val queue = ArrayList<View>()
            queue.add(decor)
            var i = 0
            while (i < queue.size && i < 1500) {
                val v = queue[i]; i++
                if (v.tag == HOME_IMAGE_TAG && v.visibility != View.VISIBLE) {
                    runCatching { v.visibility = View.VISIBLE }
                    shown++
                }
                if (v is ViewGroup) for (k in 0 until v.childCount) v.getChildAt(k)?.let { queue.add(it) }
            }
        }
        var restored = 0
        settingsSavedBgs710.forEach { pair ->
            runCatching {
                val v = pair.first
                val d = pair.second
                if (d == null) v.background = null else v.setBackgroundDrawable(d)
                restored++
            }
        }
        settingsSavedBgs710.clear()
        HLog.i("$TAG 设置页已退出，重铺页面壁纸(710c): 壁纸层恢复=$shown 壁纸Drawable恢复=$restored（$reason）")
        scheduleReapply("设置页退出710c")
    }

    /** 710k：被「底栏空档放开」改过的内容容器 —— 视图 + 原高度 / 原底边距 / 原底部内边距。 */
    private data class StripStretch710(val v: View, val h: Int, val bm: Int, val pb: Int)
    private val stripFixes710 = ArrayList<StripStretch710>()
    /** 710m：上次放开扫描的时间 —— 只做节流，不再用「已放开」状态位（切页后新 inflate 的页面容器也要补扫）。 */
    private var stripScanAt710 = 0L
    /** 710n：「列表底内边距再压」的日志节流（宿主可能每帧重写，避免刷屏）。 */
    private var stripRepressLogAt710 = 0L

    /**
     * 710k：把各页的内容链放到底栏空档里去。
     *
     * 1015 真机视图树（user999，通讯录页）：
     *   `LayoutListenerView 0..2772` → `MMWeUIBounceView 288..2598`
     *   → `NestedScrollView 288..2598` → `FrameLayout 288..2598` → `WxRecyclerView 288..2598`
     * 也就是说「隐藏原生底栏」之后，宿主仍然只给了 2598 的高度，2598..2772 这 174px 没有任何内容，
     * 露出来的是 ViewPager 底衬（`PagerStripWallpaperDrawable`）画的壁纸 —— 用户看到的
     * 「底栏位置盖了一层背景图、本该透出好友列表」就是这一块。
     *
     * 做法：只沿「高度 ≥ 屏高 55%」的容器（即内容链本身，不会碰到 174px 的列表行）逐层放开：
     * 写死的高度改 `MATCH_PARENT`、底边距与底部内边距归零；顶部让位（topMargin）一律不动，
     * 避免 710g 那样把内容顶到标题栏下面。原值全部记进 `stripFixes710`，恢复底栏时写回。
     */
    private fun applyBottomGapStretch710(activity: Activity, decor: ViewGroup, snapshot: ThemeSnapshot? = null) {
        val s = snapshot ?: runCatching { ThemeSettings.snapshot(context.hostContext()) }.getOrNull() ?: return
        runCatching {
            // 710l：判定条件只看「隐藏原生底栏」。710k 写成 `tabHideNative && !tabEnabled`，
            // 但 tabEnabled 默认就是 true（与「自定义底栏」互不冲突，applyBottomTab 里也是
            // tabHideNative 优先直接 return），条件恒假 → 710k 一次都没执行（1027 真机日志：
            // 底栏开关=false 隐藏底栏=true，`底栏空档放开` 计数 0）。
            //
            // 710m：不再用「已放开」状态位——ViewPager 的页面是懒 inflate 的，启动时只有首页
            // 那一条链在，切到通讯录/发现/我的之后新容器才出现（1027 真机只命中 2 处）。
            // 改成按 1.5s 节流幂等扫描：已处理过的视图在 stripFixes710 里直接跳过。
            val want = s.tabHideNative &&
                activity.javaClass.name.contains(LAUNCHER_UI_CLASS)
            if (want) {
                val nowMs = SystemClock.uptimeMillis()
                if (nowMs - stripScanAt710 >= STRIP_SCAN_INTERVAL_710) {
                    stripScanAt710 = nowMs
                    val before = stripFixes710.size
                    val n = stretchPageContent710(decor)
                    if (n > 0) {
                        HLog.i("$TAG 底栏空档放开(710k): 新增=$n 处 累计=${stripFixes710.size} 处（前=$before）")
                    }
                }
            } else if (stripFixes710.isNotEmpty()) {
                val back = restoreStripStretch710()
                HLog.i("$TAG 底栏空档放开已还原(710k): 还原=$back 处")
            }
        }.onFailure { HLog.e("$TAG 底栏空档放开异常(710k): ${it.message}") }
    }

    /**
     * 1029：让「空页格填充色」跟随主背景开关。
     *
     * - 主背景开关关（`!backgroundEnabled`）→ 白色（回到 710d「关背景时窗口底改白」的原意）；
     * - 主开关开、只是某几页关 → 保持 #EDEDED 原生灰（676 起验证过的观感，不动）。
     *
     * 只改我们自己那一层 Drawable 的填充色，不写宿主任何属性。
     */
    private fun syncPagerOffFill1029(s: ThemeSnapshot) {
        if (pagerUnderlays.isEmpty()) return
        val want = if (s.backgroundEnabled) 0xffededed.toInt() else 0xffffffff.toInt()
        for ((_, d) in pagerUnderlays.entries.toList()) {
            (d as? PagerStripWallpaperDrawable)?.setOffFillColor(want)
        }
    }

    /** 1029：mount-off 分支的工作节流（该分支在 OnPreDraw 上，不能每帧读配置/扫树）。 */
    @Volatile private var lastMountOffWork1029 = 0L
    private fun stretchPageContent710(decor: ViewGroup): Int {
        val screenH = decor.height.coerceAtLeast(decor.measuredHeight)
        if (screenH <= 0) return 0
        val minH = screenH * 55 / 100
        var n = 0
        // 710m：改成幂等补扫后，每次只打印「本次新增/本次再压」的详情（1.5s 节流，量很小）。
        var firstLog = true
        val queue = ArrayList<View>()
        queue.add(decor)
        var i = 0
        while (i < queue.size && i < 1800) {
            val v = queue[i]; i++
            if (v is ViewGroup && v.javaClass.name.endsWith("LayoutListenerView")) {
                for (k in 0 until v.childCount) {
                    val c = v.getChildAt(k) ?: continue
                    if (c.visibility != View.VISIBLE) continue
                    n += stretchChain710(c, minH, screenH, firstLog)
                }
            }
            if (v is ViewGroup) for (k in 0 until v.childCount) v.getChildAt(k)?.let { queue.add(it) }
        }
        return n
    }

    private fun stretchChain710(v: View, minH: Int, screenH: Int, log: Boolean): Int {
        val h = if (v.height > 0) v.height else v.measuredHeight
        if (h <= 0 || h < minH) return 0
        var n = 0
        val lp = v.layoutParams
        if (lp != null) {
            val mlp = lp as? ViewGroup.MarginLayoutParams
            val bm = mlp?.bottomMargin ?: 0
            val pb = v.paddingBottom
            val known = stripFixes710.firstOrNull { it.v === v }
            if (known == null) {
                val origH = lp.height
                val needH = origH > 0
                val needBm = bm > 0
                val needPb = pb > 0
                if (needH || needBm || needPb) {
                    stripFixes710.add(StripStretch710(v, origH, bm, pb))
                    if (needH) lp.height = ViewGroup.LayoutParams.MATCH_PARENT
                    if (needBm) mlp?.bottomMargin = 0
                    if (needPb) v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, 0)
                    runCatching { v.layoutParams = lp }
                    runCatching { v.requestLayout() }
                    n++
                    if (log) {
                        HLog.i(
                            "$TAG 内容链放开(710k): ${v.javaClass.simpleName}#${idName(v)}" +
                                " 高=$h 原高=$origH 原底边距=$bm 原底内边距=$pb 屏高=$screenH"
                        )
                    }
                }
            } else {
                // 710o：宿主会在 onMeasure/onLayout 之后把「底栏占位」重新写回来 ——
                // 真机反馈「透明能看到两次，重启后又不行」，就是这个回写把 710k 的放开抵消了。
                // 所以对已登记过的视图每轮再压一次（原值仍用首次记录的那份），只读 lp.height 判断，代价极小。
                if (lp.height > 0) {
                    val oldH = lp.height
                    lp.height = ViewGroup.LayoutParams.MATCH_PARENT
                    runCatching { v.layoutParams = lp }
                    runCatching { v.requestLayout() }
                    n++
                    val nowRepress = SystemClock.uptimeMillis()
                    if (log && nowRepress - stripRepressLogAt710 >= 60000L) {
                        stripRepressLogAt710 = nowRepress
                        HLog.i("$TAG 内容链高度再压(710o): ${v.javaClass.simpleName}#${idName(v)} 被写回固定高=$oldH → MATCH_PARENT")
                    }
                }
                if (pb > 0) {
                    v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, 0)
                    runCatching { v.requestLayout() }
                    n++
                    val nowRepress = SystemClock.uptimeMillis()
                    if (log && nowRepress - stripRepressLogAt710 >= 60000L) {
                        stripRepressLogAt710 = nowRepress
                        HLog.i("$TAG 列表底内边距再压(710n): ${v.javaClass.simpleName}#${idName(v)} 原底内边距=${known.pb} 当前=$pb")
                    }
                }
                if (bm > 0) {
                    mlp?.bottomMargin = 0
                    runCatching { v.layoutParams = lp }
                    runCatching { v.requestLayout() }
                    n++
                }
            }
        }
        if (v is ViewGroup) {
            for (k in 0 until v.childCount) {
                val c = v.getChildAt(k) ?: continue
                n += stretchChain710(c, minH, screenH, log)
            }
        }
        return n
    }

    private fun restoreStripStretch710(): Int {
        var back = 0
        stripFixes710.forEach { fix ->
            runCatching {
                val v = fix.v
                val lp = v.layoutParams
                if (lp != null) {
                    lp.height = fix.h
                    (lp as? ViewGroup.MarginLayoutParams)?.bottomMargin = fix.bm
                    runCatching { v.layoutParams = lp }
                }
                if (fix.pb > 0) v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, fix.pb)
                runCatching { v.requestLayout() }
                back++
            }
        }
        stripFixes710.clear()
        return back
    }

    /** 710b：退出叠加页时把灰底板整块摘掉，绝不留给宿主。 */
    private fun clearOverlayBackdrops710(reason: String) {
        if (overlaySettingsActivities710.isEmpty()) return
        val list = overlaySettingsActivities710.toList()
        overlaySettingsActivities710.clear()
        var removed = 0
        list.forEach { a ->
            runCatching {
                val decor = a.window?.decorView as? ViewGroup ?: return@runCatching
                val bd = decor.findViewWithTag<View>(OVERLAY_BACKDROP_TAG) ?: return@runCatching
                decor.removeView(bd)
                removed++
            }
        }
        if (removed > 0) HLog.i("$TAG 叠加页灰底板已摘除(710b): $removed 处（$reason）")
    }

    /** 710：某个 Activity 恢复到前台时的统一处理（叠加页撤壁纸 / 宿主回来补铺 / 设置页灰底）。 */
    private fun onOverlayActivityResumed710(a: Activity) {
        val n = a.javaClass.name
        if (n.startsWith("com.aeonlab")) return
        if (n.contains(LAUNCHER_UI_CLASS)) {
            revealDecorWallpaper710("宿主 resumed710")
            clearOverlayBackdrops710("宿主 resumed710")
            return
        }
        val settingsFamily = isSettingsFamily710(a)
        if (settingsFamily) {
            HLog.i("$TAG 叠加页探测(710): 类=$n → 撤下 decor/窗口壁纸")
            overlaySettingsActivities710.add(a)
        }
        hideDecorWallpaper710("叠加页 ${a.javaClass.simpleName}")
        if (settingsFamily) {
            // 710b：先插整窗灰底板（半透明窗口的关键），再走原来的窗口/页面灰底。
            ensureOverlayBackdrop710(a, "resumed710")
            applyNativeGreyToSettingsPage(a, "resumed710")
        }
    }
    /**
     * 710：叠加页在前台时，把 decor 与窗口底那层壁纸还原成原值（不是全局原生灰）。
     *
     * 679/703 的教训：`restoreTouchedViews()` 会顺手把底栏、标题栏、页面底图全部还原，
     * 叠加页退出后又要整树重铺一遍，用户能看到明显闪一下。这里只撤「最外面那两层」，
     * 页面内容不动（反正被叠加页盖住），返回时 applyThemeNow 重铺一次即可。
     */
    private fun hideDecorWallpaper710(reason: String) {
        if (gateWrite702()) return
        if (wallpaperHidden) return
        wallpaperHidden = true
        val act = lastHostActivity?.get()
        runCatching {
            val dec = act?.window?.decorView as? ViewGroup
            if (dec != null) {
                val prev = replacedBackgrounds.remove(dec)
                runCatching { dec.setBackgroundDrawable(prev ?: ColorDrawable(NATIVE_GREY)) }
            }
            act?.window?.let { w ->
                if (replacedWindowBackgrounds.containsKey(w)) {
                    val prev = replacedWindowBackgrounds.remove(w)
                    runCatching { w.setBackgroundDrawable(prev) }
                }
            }
        }.onFailure { HLog.e("$TAG 叠加页撤壁纸失败(710): ${it.message}") }
        HLog.i("$TAG 叠加页已撤壁纸(710): decor/窗口底还原原生，页面内容保持不动（$reason）")
    }
    /** 710：叠加页退出、宿主回到前台时，把壁纸重新铺回去（走一次完整 applyThemeNow）。 */
    private fun revealDecorWallpaper710(reason: String) {
        if (!wallpaperHidden) return
        wallpaperHidden = false
        HLog.i("$TAG 叠加页已退出(710): 重新铺窗口底与装饰底（$reason）")
        scheduleReapply("叠加页退出710")
    }

    private fun installNativeModeGuards() {
        if (nativeGuardsInstalled) return
        nativeGuardsInstalled = true
        runCatching {
            XposedBridge.hookAllMethods(View::class.java, "setBackgroundColor", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!nativeOnlyMode()) return
                    val c = param.args.getOrNull(0) as? Int ?: return
                    if (!isNativeDark(c)) return
                    param.args[0] = NATIVE_GREY
                    val v = param.thisObject as? View
                    HLog.i("$TAG 原生模式拦宿主深色底(683): ${v?.javaClass?.simpleName}#${v?.let { idName(it) }} 原=#" + Integer.toHexString(c) + " → #ffededed")
                }
            })
            XposedBridge.hookAllMethods(View::class.java, "setBackgroundDrawable", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!nativeOnlyMode()) return
                    val d = param.args.getOrNull(0) as? ColorDrawable ?: return
                    if (!isNativeDark(d.color)) return
                    param.args[0] = ColorDrawable(NATIVE_GREY)
                    HLog.i("$TAG 原生模式拦宿主深色底(683): drawable 原=#" + Integer.toHexString(d.color) + " → #ffededed")
                }
            })
            HLog.i("$TAG 原生模式写入拦截已就位(683)")
        }.onFailure { HLog.e("$TAG 原生模式写入拦截异常(683): ${it.message}") }
    }

    /** 683：设置页里任何「页面大小的非纯色底」都换原生灰，并点出原类名（探针用）。 */
    private fun greyOutSettingsAnyDrawable(a: Activity, reason: String) {
        val dm = a.resources.displayMetrics
        val root = runCatching { a.window?.decorView as? ViewGroup }.getOrNull() ?: return
        val queue = ArrayList<View>(); queue.add(root)
        var i = 0; var n = 0
        while (i < queue.size && i < 1500) {
            val v = queue[i]; i++
            val w = v.width.coerceAtLeast(v.measuredWidth); val h = v.height.coerceAtLeast(v.measuredHeight)
            val bg = v.background
            if (w >= dm.widthPixels / 2 && h >= dm.heightPixels * 3 / 10 && bg != null && bg !is ColorDrawable) {
                val old = bg.javaClass.simpleName
                runCatching { v.setBackgroundDrawable(ColorDrawable(NATIVE_GREY)) }
                n++
                if (n <= 8) HLog.i("$TAG 设置页背景已换原生灰(683): ${v.javaClass.simpleName}#${idName(v)} 视=${w}x$h 原=$old（$reason）")
            }
            if (v is ViewGroup) for (k in 0 until v.childCount) v.getChildAt(k)?.let { queue.add(it) }
        }
        if (n > 0) HLog.i("$TAG 设置页背景(683): 已换=$n 处（$reason）")
    }

    /**
     * 682：主开关（启用页面背景）关掉、或四个页面开关全关时，整页就该是微信原生观感。
     * 14:47 截图实测：此时窗口底仍是主题壁纸（主页标题栏/状态栏压成壁纸暗部 #19182A），
     * 宿主页面容器还留 #232323（通讯录两条分组头 y=1158..1256 / 1431..1530 满宽纯 #232323）。
     */
    private fun nativeOnlyMode(): Boolean {
        val now = SystemClock.uptimeMillis()
        if (now - lastNativeModeCheckAt < 1500L) return nativeModeOn
        lastNativeModeCheckAt = now
        val snap = runCatching { ThemeSettings.snapshot(context.hostContext()) }.getOrNull()
        nativeModeOn = snap == null || !snap.backgroundEnabled ||
            (!snap.pageHome && !snap.pageContacts && !snap.pageDiscover && !snap.pageMe)
        return nativeModeOn
    }

    /** 682：原生模式下的状态机（只在状态变化时写一次，不做逐帧写）。 */
    private fun syncNativeOnlyMode(activity: Activity, decor: ViewGroup, reason: String) {
        val want = nativeOnlyMode()
        if (want == wallpaperHidden) return
        if (want) {
            wallpaperHidden = true
            runCatching { decor.setBackgroundDrawable(ColorDrawable(NATIVE_GREY)) }
            runCatching { activity.window?.setBackgroundDrawable(ColorDrawable(NATIVE_GREY)) }
            HLog.i("$TAG 原生模式已开启(682): 主开关/页面开关全关 → 窗口底与装饰视图置原生灰（$reason）")
            runCatching { stripOurWallpaper(decor, reason) }
            // 702 restore-chain shorted: runCatching { applyNativePages684(decor, reason) }
            runCatching { dumpNativeState("进入原生模式") }
            // 702 restore-chain shorted: restoreNativeFillTree(decor, reason)
            // 702 restore-chain shorted: restoreNativeMiniLayer701(decor, reason)
        } else {
            wallpaperHidden = false
            HLog.i("$TAG 原生模式已退出(682): 恢复页面背景（$reason）")
            scheduleReapply("原生模式退出(682)")
        }
    }

    /**
     * 682：把宿主留下的深色底还原成原生灰。只认「不透明、R=G=B 且接近 0x23、宽≥90%屏宽、高≥40px」的纯色底，
     * 覆盖 681 漏掉的行高深色条（分组头 1240x98）与页面容器；文字/图标/白卡/图片背景一律不碰。
     */

    /** 701：原生模式（四页开关全关或关掉「启用页面背景」）下，把「最近/小程序」那层的自绘背景交还原生。
     *  依据 145905 日志：状态 A 已走还原分支（原生模式页面已置不透明(684)=572、标题栏=143），但
     *  页面非纯色底探针(684) 仍报 PullDownListView#list 1240x2772 非纯色底 ⇒ 还原没覆盖「最近/小程序」层。
     *  只在状态翻转时走一次（上限 600 个视图），不逐帧、不挂钩子、不直接写宿主属性。 */
    private var miniRestore701Count = 0
    private fun restoreNativeMiniLayer701(any: Any?, extra: Any? = null) {
        runCatching {
            val r = (any as? android.view.View) ?: (any as? android.view.ViewGroup) ?: return
            var n = 0
            val stack = ArrayDeque<android.view.View>()
            stack.addLast(r)
            while (stack.isNotEmpty() && n < 600) {
                val v = stack.removeFirst(); n++
                if (v is android.view.ViewGroup) { var i = 0; while (i < v.childCount) { stack.addLast(v.getChildAt(i)); i++ } }
                val bg = v.background ?: continue
                val cn = bg.javaClass.name
                val ours = cn.startsWith("com.aeonlab.toolkit")
                val mini = cn.contains("DynamicBg") || cn.contains("GradientColorBackgroundView") || cn.contains("ScreenAnchoredBitmapDrawable") || (cn.contains("NinePatchDrawable") && v.javaClass.name.contains("PullDown"))
                if (ours || mini) { runCatching { v.background = null }; miniRestore701Count++ }
            }
            if (miniRestore701Count <= 6) HLog.i("$TAG \u5c0f\u7a0b\u5e8f\u5c42\u80cc\u666f\u5df2\u4ea4\u8fd8\u539f\u751f(701): \u6e05\u5e95=" + miniRestore701Count + " \u626b\u63cf=" + n)
        }
    }

    private fun restoreNativeFillTree(root: View, reason: String) {
        val dm = root.resources.displayMetrics
        val now = SystemClock.uptimeMillis()
        if (now - lastNativeFillAt < 1000L) return
        lastNativeFillAt = now
        val queue = ArrayList<View>(); queue.add(root)
        var i = 0; var fixed = 0
        while (i < queue.size && i < 1500) {
            val v = queue[i]; i++
            val w = v.width.coerceAtLeast(v.measuredWidth)
            val h = v.height.coerceAtLeast(v.measuredHeight)
            if (h >= dm.heightPixels * 95 / 100) {
                HLog.i("$TAG 全屏容器跳过不置灰(684): ${v.javaClass.simpleName}#${idName(v)} 视=${w}x$h 原=#" + Integer.toHexString((runCatching { (v.background as? ColorDrawable)?.color }.getOrNull() ?: 0)) + "（留宿主原样，避免底栏区露灰块）")
            }
            if (w >= dm.widthPixels * 9 / 10 && h >= 40 && h < dm.heightPixels * 95 / 100) {
                val c = runCatching { (v.background as? ColorDrawable)?.color }.getOrNull()
                if (c != null && Color.alpha(c) == 255) {
                    val r = c shr 16 and 0xFF; val g = c shr 8 and 0xFF; val b = c and 0xFF
                    if (r == g && g == b && r >= 0x18 && r <= 0x30) {
                        runCatching { v.setBackgroundDrawable(ColorDrawable(NATIVE_GREY)) }
                        fixed++; nativeFillCount++
                        if (now - lastOffPageFillLogAt >= 2000L) {
                            lastOffPageFillLogAt = now
                            HLog.i(
                                "$TAG 宿主深色底已还原(682): ${v.javaClass.simpleName}#${idName(v)} 视=${w}x$h" +
                                    " 原=#" + Integer.toHexString(c) + " → #ffededed 累计=$nativeFillCount（$reason）"
                            )
                        }
                    }
                }
            }
            if (v is ViewGroup) for (k in 0 until v.childCount) v.getChildAt(k)?.let { queue.add(it) }
        }
        if (fixed > 0) HLog.i("$TAG 原生模式深色底(682): 本轮还原=$fixed 处（$reason）")
    }


    /**
     * 681：关页（该页开关关闭 = 当作没设图）时，宿主给页面容器留的深色底会把列表分组头显成黑条。
     * 只处理「页面大小（宽≥70%屏宽 且 高≥30%屏高）+ 不透明深色（通道均值<0x60）」的容器底色，
     * 换成原生灰 #ffededed；其余视图（行、分组头、分隔线、白卡）不动。
     */
    private fun restoreOffPageNativeFill(page: View, index: Int, reason: String) {
        val dm = page.resources.displayMetrics
        val now = SystemClock.uptimeMillis()
        if (now - (offPageFillAt[page] ?: 0L) < 1000L) return
        offPageFillAt[page] = now
        var fixed = 0
        val queue = ArrayList<View>()
        queue.add(page)
        var i = 0
        while (i < queue.size && i < 400) {
            val v = queue[i]; i++
            val w = v.width.coerceAtLeast(v.measuredWidth)
            val h = v.height.coerceAtLeast(v.measuredHeight)
            // 710：页面容器自己也算——关页时整片黑底正是长在它身上的；此前 `v !== page` 把它排除掉了。
            if (w >= dm.widthPixels * 7 / 10 && h >= dm.heightPixels * 3 / 10) {
                val c = runCatching { (v.background as? ColorDrawable)?.color }.getOrNull()
                if (c != null && Color.alpha(c) == 255 &&
                    ((c shr 16 and 0xFF) + (c shr 8 and 0xFF) + (c and 0xFF)) / 3 < 0x60
                ) {
                    runCatching { v.setBackgroundDrawable(ColorDrawable(NATIVE_GREY)) }
                    offPageFillCount++
                    fixed++
                    if (now - lastOffPageFillLogAt >= 2000L) {
                        lastOffPageFillLogAt = now
                        HLog.i(
                            "$TAG 关页容器底色已还原(681): 第 ${index + 1} 页 ${v.javaClass.simpleName}#${idName(v)}" +
                                " 视=${w}x$h 原=#" + Integer.toHexString(c) + " → #ffededed 累计=$offPageFillCount"
                        )
                    }
                }
            }
            if (v is ViewGroup) for (k in 0 until v.childCount) v.getChildAt(k)?.let { queue.add(it) }
        }
        if (fixed > 0) {
            // 710：关页这一页不能再留在「铺图 / 清透明 / 行遮罩」任何登记表里，
            // 否则 desiredBackgroundFor 会先把页面底图又塞回去，刚还原的原生灰下一帧就被覆盖。
            pageDrawables.remove(page)
            pageScrim.remove(page)
            rowScrimDrawables.remove(page)
            clearedViews.remove(page)
            offPageNative710.add(page)
            HLog.i("$TAG 关页原生灰已就位(710): 第 ${index + 1} 页 还原=$fixed 处（$reason）")
        }
    }

    /**
     * 681：微信原生设置页（com.tencent.mm.plugin.setting.*）保持原生灰底。
     * 13:06 截图实测（设备原生 1240x2772）：设置主页 y=1560..1684、通用页 y=1460..1584 处
     * x≥720 整片透出壁纸、x<720 是内容白卡 ⇒ 宿主把主题壁纸铺在了设置窗口的「窗口底/页面底」上。
     * 只做两件事：①窗口底色换成原生灰；②把页面大小的位图背景（=被铺上去的壁纸）换成原生灰。
     * 只对微信原生设置页生效，聊天/朋友圈/小程序与主题自己的设置页都不动。
     */
    private fun applyNativeGreyToSettingsPage(a: Activity, reason: String) {
        val an = a.javaClass.name
        if (an.contains(LAUNCHER_UI_CLASS) || an.startsWith("com.aeonlab")) return
        val hit = an.startsWith("com.tencent.mm.plugin.setting") || an.contains("etting")
        if (SystemClock.uptimeMillis() - lastOverlayProbeAt >= 5000L) {
            lastOverlayProbeAt = SystemClock.uptimeMillis()
            HLog.i("$TAG 叠加页探测(682): 类=${a.javaClass.simpleName} 灰底命中=$hit")
        }
        if (!hit) return
        val now = SystemClock.uptimeMillis()
        val first = !settingsGreyAt.containsKey(a)
        if (first) settingsGreyAt[a] = now
        if (first) {
            mainHandler.postDelayed({ runCatching { dumpSettingsPageTree(a) } }, 600L)
        }
        if (now - (settingsGreyAt[a] ?: 0L) >= 2000L || first) {
            settingsGreyAt[a] = now
            runCatching { a.window?.setBackgroundDrawable(ColorDrawable(NATIVE_GREY)) }
                .onFailure { HLog.e("$TAG 设置页窗口底色异常(681): ${it.message}") }
            HLog.i("$TAG 设置页窗口底色已置原生灰(681): ${a.javaClass.simpleName}（$reason）")
            runCatching { (a.window?.decorView as? ViewGroup)?.setBackgroundDrawable(ColorDrawable(NATIVE_GREY)) }
            runCatching { (a.findViewById<View>(android.R.id.content) as? ViewGroup)?.setBackgroundDrawable(ColorDrawable(NATIVE_GREY)) }
            HLog.i("$TAG 设置页装饰/内容底已置原生灰(682): ${a.javaClass.simpleName}")
            // 702 restore-chain shorted: runCatching { greyOutSettingsAnyDrawable(a, reason) }
        }
        mainHandler.postDelayed({
            runCatching { greyOutSettingsBitmaps(a, reason) }
                .onFailure { HLog.e("$TAG 设置页位图层异常(681): ${it.message}") }
        }, 400L)
    }

    /** 把设置页里「页面大小的位图背景」（宿主铺的壁纸）换成原生灰；.9 与纯色底不碰。 */
    private fun greyOutSettingsBitmaps(a: Activity, reason: String) {
        val decor = a.window?.decorView as? ViewGroup ?: return
        val dm = a.resources.displayMetrics
        var n = 0
        val queue = ArrayList<View>()
        queue.add(decor)
        var i = 0
        while (i < queue.size && i < 1500) {
            val v = queue[i]; i++
            val w = v.width.coerceAtLeast(v.measuredWidth)
            val h = v.height.coerceAtLeast(v.measuredHeight)
            val bg = v.background
            if (bg != null && w >= dm.widthPixels * 5 / 10 && h >= dm.heightPixels * 3 / 10) {
                val bn = bg.javaClass.name
                if (bg is BitmapDrawable || bn.contains("Bitmap") || bn.contains("ImageDrawable")) {
                    runCatching { v.setBackgroundDrawable(ColorDrawable(NATIVE_GREY)) }
                    n++
                    HLog.i(
                        "$TAG 设置页壁纸底已还原(681): ${v.javaClass.simpleName}#${idName(v)} 视=${w}x$h" +
                            " 原=${bg.javaClass.simpleName} 累计=$n（$reason）"
                    )
                }
            }
            if (v is ViewGroup) for (k in 0 until v.childCount) v.getChildAt(k)?.let { queue.add(it) }
        }
        HLog.i("$TAG 设置页壁纸底已还原(681): 本轮命中=$n 处（窗口底色已置原生灰，$reason）")
    }

    /** 设置页背景转储：本次修复没盖住时，下一份日志能直接点名哪一层在画壁纸。 */
    private fun dumpSettingsPageTree(a: Activity) {
        val decor = a.window?.decorView as? ViewGroup ?: return
        val dm = a.resources.displayMetrics
        val sb = StringBuilder()
        val queue = ArrayList<View>()
        queue.add(decor)
        var i = 0
        while (i < queue.size && i < 1500) {
            val v = queue[i]; i++
            val w = v.width.coerceAtLeast(v.measuredWidth)
            val h = v.height.coerceAtLeast(v.measuredHeight)
            if (w >= dm.widthPixels * 3 / 10 && h >= dm.heightPixels / 20) {
                sb.append('[').append(v.javaClass.simpleName).append('#').append(idName(v))
                    .append(' ').append(w).append('x').append(h)
                    .append(" top=").append(absTopOfView(v))
                    .append(" bg=").append(v.background?.javaClass?.simpleName ?: "null")
                    .append(" vis=").append(v.visibility).append(']')
            }
            if (v is ViewGroup) for (k in 0 until v.childCount) v.getChildAt(k)?.let { queue.add(it) }
        }
        HLog.i("$TAG 设置页背景转储(681): 类=${a.javaClass.simpleName} 窗口底=${a.window?.decorView?.background?.javaClass?.simpleName ?: "null"} " + sb.toString())
    }

    /** 613：把某一页的背景与内容分层在它被看见之前做到位（切页回调 / 页面 layout 回调共用）。 */
    private fun prepPageNow(activity: Activity, page: View, index: Int, reason: String) {
        // 710c：设置页可见期间不铺图（否则切页回调 = 把壁纸又铺回灰底上）。
        if (settingsLikeHidden710.get()) return
        val now = SystemClock.uptimeMillis()
        if (now - (pagePrepAt[page] ?: 0L) < 150L) return
        // 结构没变、页面背景也还是我们的那张 → 什么都不用做。
        // 这一步是必需的：applyPageBitmap 里 setBackgroundDrawable 会 requestLayout，
        // 在 layout 回调里无条件重铺会让页面每 150ms 重新布局一次。
        val sig = pageSignatureOf(page)
        val current = pageDrawables[page]
        if (sig == pageSignatureAt[page] && current != null && page.background === current) return
        pagePrepAt[page] = now
        pageSignatureAt[page] = sig
        val snapshot = ThemeSettings.snapshot(context.hostContext())
        if (!snapshot.enabled || !snapshot.backgroundEnabled) return
        val paths = snapshot.pagePaths()
        val flags = "主=${snapshot.pageHome} 通=${snapshot.pageContacts} 发=${snapshot.pageDiscover} 我=${snapshot.pageMe} 启=${snapshot.pageSplash}"
        val raw = paths.getOrNull(index)
        if (raw.isNullOrBlank()) {
            // 681：关页不再「什么都不做」——宿主给每页容器留了深色底（13:06 日志
            // LinearLayout 1240x2310 bg=ColorDrawable#ff232323），列表分组头是原生透明行，
            // 关页时整条露成黑底（1000142860.jpg：y=1175..1250 / 1450..1540 实测 #232323）。
            // 只把「页面大小 + 不透明深色」的容器底色还原成原生灰，行/分组头/分隔线一律不碰。
            // 710：用户把这一页的背景开关关掉时，宿主给整页容器留的是不透明深色底（通讯录 #ff232323），
            // 分组头是原生透明行，于是整条露成黑底。这里只修「这一页内部、页面大小、不透明深色」的容器。
            runCatching { restoreOffPageNativeFill(page, index, reason) }
                .onFailure { HLog.e("$TAG 关页底色还原异常(710): ${it.message}") }
            // syncNativeOnlyMode 会把 decor / 窗口底全局刷成 #EDEDED，等于把其他页面的壁纸一起废掉，
            // 所以关单页时绝不走它（682 的全局原生模式另有入口）。
            // 671 探针：这一页被判为「关」时，把读到的开关原值打出来（证明读的是哪个值）
            if (SystemClock.uptimeMillis() - lastPageSkipProbeAt >= 5000L) {
                lastPageSkipProbeAt = SystemClock.uptimeMillis()
                HLog.i("$TAG 页面开关读取(671): 第 ${index + 1} 页 $flags → 路径=空，跳过铺图（$reason）")
            }
            return
        }
        val path = raw
        val image = bitmap(path) ?: return
        val themeSlots = listOf(
            ThemeImageStore.SLOT_HOME,
            ThemeImageStore.SLOT_CONTACTS,
            ThemeImageStore.SLOT_DISCOVER,
            ThemeImageStore.SLOT_ME
        )
        val overlay = ThemeSettings.parseColor(snapshot.backgroundOverlay[themeSlots[index]])
        pageSlots[page] = index
        applyPageBitmap(page, image, activity.resources, overlay, false, path, index == 0)
        pagePrepCount++
        if (pagePrepCount <= 12 || pagePrepCount % 8 == 0) {
            HLog.i(
                "$TAG 换页铺底(613): 第 ${index + 1} 页 背景+内容分层已在绘制前就位" +
                    "（$reason，累计 $pagePrepCount 次）"
            )
        }
    }

    /**
     * 614：给非首页也插一层「页面最底下的壁纸 ImageView」（首页早就有这层）。
     *
     * 真机 08:01 日志：四个页面壳 `wnd=true`（willNotDraw），页面自己铺的 Drawable 不参与绘制；
     * 进 发现/我的 时页面内容是新 inflate 的，宿主在白底容器上先画一帧，我们的清理要等下一轮扫描，
     * 于是「先白底再透明」。把壁纸做成页面内的实体 ImageView 后，它永远在 z 轴最底、一定会被画。
     */
    private fun ensurePageWallpaperLayer(page: View, bitmap: Bitmap, log: Boolean) {
        if (page !is ViewGroup) return
        val existing = page.findViewWithTag<View>(HOME_IMAGE_TAG)
        if (existing is ImageView) {
            runCatching { existing.setImageDrawable(anchored(existing, bitmap)) }
            return
        }
        val image = ImageView(page.context)
        image.tag = HOME_IMAGE_TAG
        image.isClickable = false
        image.isFocusable = false
        image.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        image.scaleType = ImageView.ScaleType.FIT_XY
        image.setImageDrawable(anchored(image, bitmap))
        runCatching {
            page.addView(
                image,
                0,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
            // 滚动露白的另一条通道：AbsListView 的 cacheColorHint/solidColor 在快速滚动时
            // 直接用纯色块顶替列表项绘制，宿主的设置是浅色 → 滚动时满屏白。这里统一改透明。
            var lists = 0
            collectCacheColorHints(page) { list ->
                if (list.cacheColorHint != Color.TRANSPARENT) {
                    list.cacheColorHint = Color.TRANSPARENT
                    lists++
                }
            }
            if (log) HLog.i("$TAG 页面壁纸底层已插入: ${page.javaClass.simpleName} 子节点=${page.childCount} 列表cacheColorHint置透明=$lists 个")
        }.onFailure { HLog.e("$TAG 页面壁纸底层插入失败(614): ${it.message}", it) }
    }

    /**
     * 614：把可见页面里「不透明的浅色大块」清掉并登记（不扫全树、只处理面积 ≥5% 页面的块）。
     * 这是进页白底的兜底：宿主在 fragment 首次 inflate 时把内容容器的底色写成 #ffffff/#ededed，
     * 我们的分层规则只覆盖 ≥92% 宽高的整页容器，中间层就漏了。
     */
    /**
     * 619：黑线定位（**只读**）。扫一遍 decor 子树，把两类候选打出来：
     * ① 高度 1~10px 且宽度 ≥ 屏宽 60% 的横条（divider/阴影/分隔线）；
     * ② 背景不透明且亮度 <0.55 的深色 View。
     * 每条带类名、idName、窗口坐标 (x,y)、宽高、背景描述，用来判定黑线到底是哪一层画的。
     * 全程不做任何 setBackground / requestLayout，因此不会像 618 那样造成持续重绘。
     */
    private fun locateLines(activity: Activity, decor: ViewGroup, now: Long) {
        if (now - lastLineLocateAt < 3000L) return
        lastLineLocateAt = now
        if (lineLocateRuns >= 20) return
        lineLocateRuns++
        val decorWidth = decor.width.coerceAtLeast(1)
        val loc = IntArray(2)
        var found = 0
        val sb = StringBuilder()
        val stack = ArrayDeque<Pair<View, Int>>()
        stack.addLast(decor to 0)
        var guard = 0
        while (stack.isNotEmpty() && guard++ < 800 && found < 12) {
            val (v, depth) = stack.removeLast()
            if (depth <= 30 && v is ViewGroup) {
                for (i in 0 until v.childCount) stack.addLast(v.getChildAt(i) to depth + 1)
            }
            if (v.visibility != View.VISIBLE || v.width <= 0 || v.height <= 0) continue
            val bg = v.background
            val color = runCatching { (bg as? ColorDrawable)?.color }.getOrNull()
            val dark = color != null && Color.alpha(color) > 24 &&
                (0.299 * Color.red(color) + 0.587 * Color.green(color) + 0.114 * Color.blue(color)) / 255.0 < 0.55
            val thin = v.height in 1..10 && v.width >= decorWidth * 6 / 10
            if (!dark && !thin) continue
            found++
            runCatching { v.getLocationInWindow(loc) }
            sb.append(" [").append(v.javaClass.simpleName).append("#").append(idName(v))
                .append(" y=").append(loc[1]).append(" x=").append(loc[0])
                .append(" ").append(v.width).append("x").append(v.height)
                .append(" bg=").append(descDrawable(bg))
                .append(" fg=").append(descDrawable(v.foreground))
                .append(" el=").append(v.elevation).append("]")
        }
        if (found > 0) {
            HLog.i("$TAG 黑线候选定位(619) 第${lineLocateRuns}轮: 屏宽=${decorWidth} 命中=$found 处$sb")
        }
    }

    /**
     * 620：像素级黑线实证。把 decor 以 1/2 缩放画进独立 Bitmap，逐行统计「暗像素占比」，
     * 占比 ≥80% 的行即横贯全屏的黑线，直接给出真实像素颜色与 y 坐标。
     * 只读：只 draw 到私有 Bitmap，不触碰任何 View 属性、不 requestLayout。
     */
    private fun sampleDarkRows(decor: ViewGroup, now: Long) {
        if (now - lastPixelAt < 4000L) return
        val w = decor.width
        val h = decor.height
        if (w <= 0 || h <= 0 || h > 4200 || pixelRuns >= 8) return
        lastPixelAt = now
        pixelRuns++
        // 全分辨率、只取顶部一条带（黑线在标题栏下方约 1/6 处），避免 1px 线在缩放采样里被抹掉
        val scale = 1
        val bw = w.coerceAtLeast(1)
        val bh = minOf(h, 1400)
        runCatching {
            val bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            canvas.scale(1f / scale, 1f / scale)
            decor.draw(canvas)
            val pixels = IntArray(bw * bh)
            bmp.getPixels(pixels, 0, bw, 0, 0, bw, bh)
            bmp.recycle()
            val need = bw * 8 / 10
            val sb = StringBuilder()
            var n = 0
            for (y in 0 until bh) {
                var cnt = 0
                var sample = 0
                val base = y * bw
                for (x in 0 until bw) {
                    val p = pixels[base + x]
                    val a = (p ushr 24) and 0xFF
                    if (a < 32) continue
                    val lum = 0.299 * ((p shr 16) and 0xFF) + 0.587 * ((p shr 8) and 0xFF) + 0.114 * (p and 0xFF)
                    if (lum < 80.0) { cnt++; sample = p }
                }
                if (cnt >= need) {
                    sb.append(" y=").append(y * scale)
                        .append(" 暗占比=").append(cnt * 100 / bw).append("%")
                        .append(" 色=#").append(Integer.toHexString(sample))
                    n++
                    if (n >= 6) break
                }
            }
            if (n > 0) {
                HLog.i("$TAG 黑线像素实证(620) 第${pixelRuns}轮: 屏=${w}x${h} 命中=$n 行$sb")
            } else if (pixelRuns <= 4) {
                HLog.i("$TAG 黑线像素实证(620) 第${pixelRuns}轮: 屏=${w}x${h} 未发现横贯暗行(≥80%宽)")
            }
        }.onFailure { HLog.e("$TAG 黑线像素实证失败(620): ${it.message}") }
    }

    private fun viewDesc(v: View?): String {
        if (v == null) return "无"
        val loc = IntArray(2)
        runCatching { v.getLocationInWindow(loc) }
        return "${v.javaClass.simpleName}#${idName(v)} y=${loc[1]} ${v.width}x${v.height}" +
            " bg=${descDrawable(v.background)} fg=${descDrawable(v.foreground)}" +
            " al=${v.alpha} el=${v.elevation} vis=${v.visibility}"
    }

    /**
     * 621：切页过渡帧的栏位实证（**只读**）。每 60ms 一条、整场 ≤40 条。
     */
    private fun probeTransition621(activity: Activity?, pager: ViewGroup, now: Long) {
        val act = activity ?: return
        if (now - lastBarProbeAt621 < 60L || barProbeCount621 >= 40) return
        lastBarProbeAt621 = now
        barProbeCount621++
        runCatching {
            val win = act.window ?: return
            val decor = win.decorView as? ViewGroup ?: return
            val sbBand = decor.findViewById<View>(android.R.id.statusBarBackground)
            val abContainer = findViewByClassName(decor, "ActionBarContainer")
            val ab = findViewByClassName(decor, "ActionBar")
            val idx = ((pager.scrollX + pager.width / 2) / pager.width.coerceAtLeast(1))
            if (barFieldDumps622 < 10 && abContainer != null) {
                barFieldDumps622++
                HLog.i(
                    "$TAG 过渡帧容器内部字段(622): 帧${barFieldDumps622} 页=${idx} scrollX=${pager.scrollX}" +
                        dumpDrawableFields622(abContainer, "容器")
                )
            }
            HLog.i(
                "$TAG 过渡帧栏位实证(621): 页=${idx} scrollX=${pager.scrollX}" +
                    " windowStatusBar=#" + Integer.toHexString(win.statusBarColor) +
                    " 状态栏条=[" + viewDesc(sbBand) + "]" +
                    " ActionBar容器=[" + viewDesc(abContainer) + "]" +
                    " ActionBar=[" + viewDesc(ab) + "]"
            )
        }
    }

    /**
     * 621：标题栏与列表之间那条缝的实证（**只读**）。整场 ≤3 轮、间隔 1.5s。
     */
    private fun probeGap621(activity: Activity, decor: ViewGroup, now: Long) {
        if (gapProbeRuns621 >= 3 || now - lastGapProbeAt621 < 1500L) return
        lastGapProbeAt621 = now
        gapProbeRuns621++
        runCatching {
            val loc = IntArray(2)
            val abc = findViewByClassName(decor, "ActionBarContainer")
            var barBottom = 0
            if (abc != null && abc.height > 0) {
                abc.getLocationInWindow(loc)
                barBottom = loc[1] + abc.height
            }
            if (barBottom <= 0) barBottom = decor.height / 8
            val lo = (barBottom - 8).coerceAtLeast(0)
            val hi = (barBottom + 80).coerceAtMost(decor.height)
            val stack = ArrayDeque<Pair<View, Int>>()
            stack.addLast(decor to 0)
            val sb = StringBuilder()
            var n = 0
            var guard = 0
            while (stack.isNotEmpty() && guard++ < 1500 && n < 25) {
                val pair = stack.removeLast()
                val v = pair.first
                val dep = pair.second
                if (dep <= 32 && v is ViewGroup) {
                    for (i in 0 until v.childCount) stack.addLast(v.getChildAt(i) to dep + 1)
                }
                if (v.visibility != View.VISIBLE || v.width <= 0 || v.height <= 0) continue
                runCatching { v.getLocationInWindow(loc) }
                val top = loc[1]
                val bot = top + v.height
                if (bot < lo || top > hi) continue
                val crossesSeam = top <= barBottom && bot >= barBottom
                val thinStrip = v.height <= 20 && v.width >= 300
                if (!crossesSeam && !thinStrip) continue
                n++
                sb.append(" [").append(v.javaClass.simpleName).append("#").append(idName(v))
                    .append(" y=").append(top).append("..").append(bot)
                    .append(" x=").append(loc[0]).append(" ").append(v.width).append("x").append(v.height)
                    .append(" bg=").append(descDrawable(v.background))
                    .append(" fg=").append(descDrawable(v.foreground))
                    .append(" el=").append(v.elevation).append("]")
            }
            HLog.i("$TAG 标题栏与列表缝隙实证(621) 第${gapProbeRuns621}轮: 标题栏底=${barBottom} 屏宽=${decor.width} 命中=$n$sb")
            if (abc != null) {
                HLog.i("$TAG ActionBarContainer内部字段(622): 轮${gapProbeRuns621}" + dumpDrawableFields622(abc, "容器"))
            }
            probeSeamPixels622(activity, decor, now)
            probeTitleBand623(activity, decor, now)

            val bandTop = (barBottom - 4).coerceAtLeast(0)
            val bandH = 44
            if (decor.width > 0 && bandTop + bandH <= decor.height) {
                val bmp = Bitmap.createBitmap(decor.width, bandH, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bmp)
                canvas.translate(0f, -bandTop.toFloat())
                decor.draw(canvas)
                val px = IntArray(decor.width * bandH)
                bmp.getPixels(px, 0, decor.width, 0, 0, decor.width, bandH)
                bmp.recycle()
                var bestRow = -1
                var bestCnt = 0
                var bestColor = 0
                var bestStart = -1
                var bestEnd = -1
                for (y in 0 until bandH) {
                    var cnt = 0
                    var first = -1
                    var last = -1
                    var color = 0
                    val base = y * decor.width
                    for (x in 0 until decor.width) {
                        val p = px[base + x]
                        val a = (p ushr 24) and 0xFF
                        if (a < 32) continue
                        val lum = 0.299 * ((p shr 16) and 0xFF) + 0.587 * ((p shr 8) and 0xFF) + 0.114 * (p and 0xFF)
                        if (lum < 100.0) {
                            cnt++
                            if (first < 0) first = x
                            last = x
                            color = p
                        }
                    }
                    if (cnt > bestCnt) {
                        bestCnt = cnt
                        bestRow = y
                        bestColor = color
                        bestStart = first
                        bestEnd = last
                    }
                }
                HLog.i(
                    "$TAG 缝隙像素实证(621) 第${gapProbeRuns621}轮: 最暗行 y=${bandTop + bestRow}" +
                        " 暗像素=$bestCnt/${decor.width} 占比=${bestCnt * 100 / decor.width.coerceAtLeast(1)}%" +
                        " 色=#" + Integer.toHexString(bestColor) +
                        " x起止=${bestStart}..${bestEnd}"
                )
            }
        }.onFailure { HLog.e("$TAG 缝隙实证失败(621): ${it.message}") }
    }

    /** 622：把 ActionBarContainer（及其子 View）内部所有 Drawable 类型字段打出来 —— 灰色底/细线不在 view.background 里，而在这些字段。 */
    private fun dumpDrawableFields622(v: View, tag: String): String {
        val sb = StringBuilder()
        var cls: Class<*>? = v.javaClass
        var guardCls = 0
        while (cls != null && cls != android.view.View::class.java && guardCls++ < 6) {
            for (f in cls.declaredFields) {
                if (!android.graphics.drawable.Drawable::class.java.isAssignableFrom(f.type)) continue
                runCatching {
                    f.isAccessible = true
                    val d = f.get(v) as? android.graphics.drawable.Drawable ?: return@runCatching
                    sb.append(" ").append(tag).append(".").append(f.name).append("=")
                        .append(descDrawable(d)).append(" bounds=").append(d.bounds.flattenToString())
                        .append(" alpha=").append(d.alpha)
                }
                if (sb.length > 800) return sb.toString()
            }
            cls = cls.superclass
        }
        return sb.toString()
    }

    /**
     * 622：缝隙像素实证 v2 —— 只把 **ActionBarContainer 子树**画进私有 Bitmap，
     * 避开 GL/Hardware layer（621 的 decor.draw 整窗截图在这台机上是全透明，等于没采到）。
     * 取容器底边上方 8px 到下方 6px 共 14 行，逐行统计暗像素，输出有暗像素的行：y、颜色、x 起止。
     */
    private fun probeSeamPixels622(activity: Activity, decor: ViewGroup, now: Long): Boolean {
        val abc = findViewByClassName(decor, "ActionBarContainer") ?: return false
        if (abc.width <= 0 || abc.height <= 0) return false
        val loc = IntArray(2)
        abc.getLocationInWindow(loc)
        val bandTopWin = loc[1] + abc.height - 8
        val h = 14
        val sb = StringBuilder()
        var hits = 0
        runCatching {
            val bmp = Bitmap.createBitmap(decor.width, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            canvas.translate(0f, -(bandTopWin - loc[1]).toFloat())
            abc.draw(canvas)
            val px = IntArray(decor.width * h)
            bmp.getPixels(px, 0, decor.width, 0, 0, decor.width, h)
            bmp.recycle()
            for (y in 0 until h) {
                var cnt = 0
                var first = -1
                var last = -1
                var color = 0
                val base = y * decor.width
                for (x in 0 until decor.width) {
                    val p = px[base + x]
                    val a = (p ushr 24) and 0xFF
                    if (a < 32) continue
                    val lum = 0.299 * ((p shr 16) and 0xFF) + 0.587 * ((p shr 8) and 0xFF) + 0.114 * (p and 0xFF)
                    if (lum < 120.0) {
                        cnt++
                        if (first < 0) first = x
                        last = x
                        color = p
                    }
                }
                if (cnt > 0) {
                    sb.append(" [y=").append(bandTopWin + y)
                        .append(" 暗=").append(cnt).append("/").append(decor.width)
                        .append(" 色=#").append(Integer.toHexString(color))
                        .append(" x=").append(first).append("..").append(last).append("]")
                    hits++
                }
            }
        }.onFailure { HLog.e("$TAG 缝隙像素v2失败(622): ${it.message}") }
        HLog.i("$TAG 缝隙像素实证v2(622): 容器底=${loc[1] + abc.height} 带=${bandTopWin}..${bandTopWin + h - 1} 有暗行=$hits$sb")
        return hits > 0
    }

    /**
     * 623：只读实证——把「标题带 139..288」+「缝隙带 280..296」一起画进私有 Bitmap。
     * 关键点：从 622 的结论出发，ActionBarContainer 自身字段全透明/alpha=0、缝隙带 280..293 无暗行，
     * 所以怀疑对象换成它下面的 `DynamicBgContainer`（微信动态背景容器，1240x2772，覆盖整窗）。
     * 每次采样输出：①标题带白像素占比与均值（判断「白条」是真画了白底还是背后有洞）②缝隙带最暗 2 行的色值与 x 起止
     * ③DynamicBgContainer 的 bg/alpha/vis 与其所有 Drawable 字段（前 8 次）。
     */
    private fun probeTitleBand623(activity: Activity, decor: ViewGroup, now: Long) {
        if (pixelRuns623 >= 60 || now - lastPixel623 < 40L) return
        lastPixel623 = now
        pixelRuns623++
        runCatching {
            val dyn = findViewByClassName(decor, "DynamicBgContainer")
            val dynLoc = IntArray(2)
            if (dyn != null) runCatching { dyn.getLocationInWindow(dynLoc) }
            val bandTop = 139
            val bandH = 158
            if (decor.width <= 0 || bandTop + bandH > decor.height) return
            val bmp = Bitmap.createBitmap(decor.width, bandH, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            canvas.translate(0f, -bandTop.toFloat())
            // 只画动态背景容器子树：它就是标题带背后那层，画整窗会太贵
            if (dyn != null) dyn.draw(canvas) else decor.draw(canvas)
            val w = decor.width
            val px = IntArray(w * bandH)
            bmp.getPixels(px, 0, w, 0, 0, w, bandH)
            bmp.recycle()
            // ① 标题带 139..288（对应 bitmap 行 0..148）白像素占比 + 均值
            var white = 0
            var total = 0
            var sumLum = 0.0
            for (y in 0 until 149) {
                val base = y * w
                for (x in 0 until w step 8) {
                    val p = px[base + x]
                    val a = (p ushr 24) and 0xFF
                    if (a < 32) continue
                    val lum = 0.299 * ((p shr 16) and 0xFF) + 0.587 * ((p shr 8) and 0xFF) + 0.114 * (p and 0xFF)
                    total++
                    sumLum += lum
                    if (lum > 245.0) white++
                }
            }
            // ② 缝隙带 280..296（bitmap 行 141..157）最暗 2 行
            val sb = StringBuilder()
            var bestY = -1
            var bestCnt = 0
            var bestColor = 0
            var bestFirst = -1
            var bestLast = -1
            for (y in 141 until bandH) {
                var cnt = 0
                var first = -1
                var last = -1
                var color = 0
                val base = y * w
                for (x in 0 until w) {
                    val p = px[base + x]
                    val a = (p ushr 24) and 0xFF
                    if (a < 32) continue
                    val lum = 0.299 * ((p shr 16) and 0xFF) + 0.587 * ((p shr 8) and 0xFF) + 0.114 * (p and 0xFF)
                    if (lum < 160.0) {
                        cnt++
                        if (first < 0) first = x
                        last = x
                        color = p
                    }
                }
                if (cnt > bestCnt) {
                    bestCnt = cnt
                    bestY = y
                    bestColor = color
                    bestFirst = first
                    bestLast = last
                }
                if (cnt > w / 2) {
                    sb.append(" [y=").append(bandTop + y).append(" 暗=").append(cnt)
                        .append(" 色=#").append(Integer.toHexString(color))
                        .append(" x=").append(first).append("..").append(last).append("]")
                }
            }
            val whitePct = if (total > 0) white * 100 / total else -1
            val mean = if (total > 0) (sumLum / total).toInt() else -1
            val dynDesc = if (dyn == null) "无" else
                "${dyn.javaClass.simpleName}#${idName(dyn)} y=${dynLoc[1]} ${dyn.width}x${dyn.height}" +
                    " bg=${descDrawable(dyn.background)} alpha=${dyn.alpha} vis=${dyn.visibility}"
            HLog.i(
                "$TAG 标题带像素实证(623) 第${pixelRuns623}次: 标题带白占比=${whitePct}% 均值亮度=${mean}" +
                    " 动态背景容器=[${dynDesc}]" +
                    " 缝隙最暗行 y=${if (bestY >= 0) (bandTop + bestY) else -1} 暗=${bestCnt}" +
                    " 色=#" + Integer.toHexString(bestColor) + " x=${bestFirst}..${bestLast}" +
                    " 超半屏暗行:$sb"
            )
            if (dyn != null && dynFieldDumps623 < 8) {
                dynFieldDumps623++
                HLog.i("$TAG 动态背景容器字段(623): 次${dynFieldDumps623}" + dumpDrawableFields622(dyn, "动态"))
            }
        }.onFailure { HLog.e("$TAG 标题带像素实证失败(623): ${it.message}") }
    }

    /** 把 decor 的任意一条带按 1:1 画进私有 Bitmap，返回像素数组（宽度 = decor 宽）。 */
    private fun drawBandPixels624(decor: ViewGroup, top: Int, h: Int): IntArray? = runCatching {
        if (decor.width <= 0 || h <= 0 || top < 0 || top + h > decor.height) return null
        val bmp = Bitmap.createBitmap(decor.width, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.translate(0f, -top.toFloat())
        decor.draw(c)
        val px = IntArray(decor.width * h)
        bmp.getPixels(px, 0, decor.width, 0, 0, decor.width, h)
        bmp.recycle()
        px
    }.getOrNull()

    /**
     * 624：两条带各自正确打卡位的整窗像素实证（**只读**）。
     * 623 的两处证伪：DynamicBgContainer 无任何 Drawable 字段、只画它得到全透明（alpha<32），
     * ActionBarContainer 只画它也是全透明 —— 两个嫌疑人都画不出东西。
     * 所以本轮直接把 decor 整窗画进私有 Bitmap，一次覆盖所有层：
     * ①缝隙带（仅主页静置 800ms 以上，且当前页=0）逐行统计 lum<150 的暗像素，回答「黑线在哪一行、什么色、从 x 几开始」；
     * ②标题带（仅过渡帧）统计白占比/均值亮度 + 6 个采样点色值，回答「闪白那 1~2 帧标题带到底是什么色」。
     */
    private fun probeBands624(activity: Activity, decor: ViewGroup, now: Long) {
        val pager = lastHomePager?.get()
        val pw = pager?.width ?: 0
        val idx = if (pager != null && pw > 0) ((pager.scrollX + pw / 2) / pw).coerceIn(0, 3) else -1
        val resting = now - lastPagerScrollAt617 > 600L
        // ① 缝隙带：主页静置时采，最多 12 次
        if (resting && idx == 0 && seamDraws624 < 12 && now - lastSeamDraw624 > 800L) {
            lastSeamDraw624 = now
            seamDraws624++
            val top = 272
            val h = 44
            val px = drawBandPixels624(decor, top, h)
            if (px != null) {
                val w = decor.width
                val sb = StringBuilder()
                var hits = 0
                var bestY = -1
                var bestCnt = 0
                var bestColor = 0
                var bestFirst = -1
                var bestLast = -1
                for (y in 0 until h) {
                    var cnt = 0
                    var first = -1
                    var last = -1
                    var color = 0
                    val base = y * w
                    for (x in 0 until w) {
                        val p = px[base + x]
                        val a = (p ushr 24) and 0xFF
                        if (a < 32) continue
                        val lum = 0.299 * ((p shr 16) and 0xFF) + 0.587 * ((p shr 8) and 0xFF) + 0.114 * (p and 0xFF)
                        if (lum < 150.0) {
                            cnt++
                            if (first < 0) first = x
                            last = x
                            color = p
                        }
                    }
                    if (cnt > bestCnt) {
                        bestCnt = cnt
                        bestY = top + y
                        bestColor = color
                        bestFirst = first
                        bestLast = last
                    }
                    if (cnt > 0 && hits < 6) {
                        hits++
                        sb.append(" [y=").append(top + y).append(" 暗=").append(cnt)
                            .append(" 色=#").append(Integer.toHexString(color))
                            .append(" x=").append(first).append("..").append(last).append("]")
                    }
                }
                HLog.i(
                    "$TAG 缝隙带逐行(624) 第${seamDraws624}次: 页=$idx 中性行=${hits}$sb" +
                        " | 最暗行 y=$bestY 暗=$bestCnt 色=#" + Integer.toHexString(bestColor) +
                        " x=$bestFirst..$bestLast"
                )
            }
        }
        // ② 标题带：过渡帧采，最多 30 次
        if (!resting && flashDraws624 < 30 && now - lastFlashDraw624 > 60L) {
            lastFlashDraw624 = now
            flashDraws624++
            val top = 139
            val h = 150
            val px = drawBandPixels624(decor, top, h)
            if (px != null) {
                val w = decor.width
                var white = 0
                var total = 0
                var sum = 0.0
                for (y in 0 until h step 2) {
                    val base = y * w
                    for (x in 0 until w step 8) {
                        val p = px[base + x]
                        val a = (p ushr 24) and 0xFF
                        if (a < 32) continue
                        val lum = 0.299 * ((p shr 16) and 0xFF) + 0.587 * ((p shr 8) and 0xFF) + 0.114 * (p and 0xFF)
                        total++
                        sum += lum
                        if (lum > 245.0) white++
                    }
                }
                fun at(x: Int, y: Int): String =
                    "#" + Integer.toHexString(px[y.coerceIn(0, h - 1) * w + x.coerceIn(0, w - 1)])
                HLog.i(
                    "$TAG 标题带像素(624) 第${flashDraws624}次: 页=$idx scrollX=${pager?.scrollX} " +
                        "白占比=${if (total > 0) white * 100 / total else -1}% " +
                        "均值=${if (total > 0) (sum / total).toInt() else -1} " +
                        "采样=[x10y10=${at(10, 10)} x620y10=${at(620, 10)} x1229y10=${at(1229, 10)}" +
                        " x10y75=${at(10, 75)} x620y75=${at(620, 75)} x620y148=${at(620, 148)}]"
                )
            }
        }
    }

    /**
     * 625：逐帧状态追踪（**只读、不绘制**）。
     * 624 的结论是：标题带 139..288 在 81 次采样里白占比恒 0%、底色恒为壁纸黄（均值≈220）——
     * 说明 60ms 定时采样**漏掉了那 1~2 帧**。这里改成挂在每帧的 sweepTick 上，
     * 只在「刚划过页 900ms 内」逐帧打一行各层状态，用来抓「哪一帧、哪一层没了/变了」。
     */
    private fun traceFrame625(activity: Activity, decor: ViewGroup, now: Long) {
        if (now - lastPagerScrollAt617 > 900L) {
            traceFrames625 = 0
            return
        }
        if (traceFrames625 >= 60) return
        traceFrames625++
        runCatching {
            val pager = lastHomePager?.get()
            val pw = pager?.width ?: 0
            val idx = if (pager != null && pw > 0) ((pager.scrollX + pw / 2) / pw).coerceIn(0, 3) else -1
            val ab = findViewByClassName(decor, "ActionBarContainer")
            val fromPage628 = prevPageIdx628
            prevPageIdx628 = idx
            val sb = decor.findViewById<View>(android.R.id.statusBarBackground)
            val pagerBg = pager?.background
            val filled = (pagerBg as? PagerStripWallpaperDrawable)?.filledCount()
            HLog.i(
                "$TAG 帧序状态(625) #${traceFrames625} 页=$idx scrollX=${pager?.scrollX}" +
                    " decorBg=${descDrawable(decor.background)}" +
                    " pagerBg=${descDrawable(pagerBg)} 格数=$filled" +
                    " abBg=${descDrawable(ab?.background)} abAlpha=${ab?.alpha}" +
                    " abAtt=${ab?.isAttachedToWindow} abVis=${ab?.visibility} abShown=${ab?.isShown}" +
                    " from=$fromPage628" +
                    " sbVis=${sb?.visibility}" +
                    " gc=" + runCatching {
                        val gc = findViewByClassName(decor, "GradientColorBackgroundView")
                        if (gc == null) "无" else "al=${gc.alpha} vis=${gc.visibility} bg=${descDrawable(gc.background)}"
                    }.getOrDefault("?") +
                    " fs=" + runCatching {
                        val fs = findViewByClassName(decor, "FirstScreenFrameLayout")
                        if (fs == null) "无" else "bg=${descDrawable(fs.background)} al=${fs.alpha}"
                    }.getOrDefault("?") +
                    " wndSB=#" + Integer.toHexString(activity.window?.statusBarColor ?: 0)
            )
        }
    }

    /**
     * 625：全高度行剖面（**只读**）。一次把 decor 的 0..900 行画进私有 Bitmap，
     * 逐行算「均值亮度 / lum<120 的像素数」，挑出比上下各 2 行都暗 ≥6 的行（局部凹陷＝横线特征），
     * 按均值从暗到亮列最多 6 条，并给该行 x=620 处的中色值 —— 不管黑线在 y 多少都能抓到。
     */
    private fun profileRows625(activity: Activity, decor: ViewGroup, now: Long) {
        if (profileRuns625 >= 3 || now - lastProfile625 < 2000L) return
        val pager = lastHomePager?.get()
        val pw = pager?.width ?: 0
        val idx = if (pager != null && pw > 0) ((pager.scrollX + pw / 2) / pw).coerceIn(0, 3) else -1
        if (idx != 0 || now - lastPagerScrollAt617 < 800L) return
        lastProfile625 = now
        profileRuns625++
        runCatching {
            val h = minOf(901, decor.height)
            val w = decor.width
            if (w <= 0 || h < 100) return
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            decor.draw(c)
            val px = IntArray(w * h)
            bmp.getPixels(px, 0, w, 0, 0, w, h)
            bmp.recycle()
            val mean = DoubleArray(h)
            val dark = IntArray(h)
            for (y in 0 until h) {
                var sum = 0.0
                var d = 0
                val base = y * w
                for (x in 0 until w step 4) {
                    val p = px[base + x]
                    val lum = 0.299 * ((p shr 16) and 0xFF) + 0.587 * ((p shr 8) and 0xFF) + 0.114 * (p and 0xFF)
                    sum += lum
                    if (lum < 120.0) d++
                }
                mean[y] = sum / (w / 4 + 1)
                dark[y] = d
            }
            val cand = ArrayList<Triple<Int, Double, Int>>()
            for (y in 2 until h - 2) {
                if (mean[y] < mean[y - 2] - 6.0 && mean[y] < mean[y + 2] - 6.0) {
                    cand.add(Triple(y, mean[y], dark[y]))
                }
            }
            cand.sortBy { it.second }
            val sb = StringBuilder()
            var n = 0
            for ((y, m, d) in cand) {
                if (n >= 6) break
                n++
                val p = px[y * w + w / 2]
                sb.append(" [y=").append(y).append(" 均值=").append(m.toInt())
                    .append(" 暗=").append(d).append(" 中色=#").append(Integer.toHexString(p)).append("]")
            }
            HLog.i("$TAG 行剖面(625) 第${profileRuns625}次: 页=$idx 屏=${w}x${decor.height} 候选=${cand.size}$sb")
        }.onFailure { HLog.e("$TAG 行剖面失败(625): ${it.message}") }
    }

    /**
     * 626：用 PixelCopy 读**真实帧缓冲**（合成后的屏幕），彻底绕开「软件画布看不到硬件层/GL」这个盲区。
     * 前面 621~625 的 decor.draw 全部走软件画布：625 的 0..900 行剖面在主页静置时也没有任何深色行，
     * 而用户肉眼能看到那条线 —— 唯一合理解释就是那条线在硬件层里，软件画布画不出来。
     */
    private fun pixelCopyBand626(activity: Activity, top: Int, h: Int, what: String, onPixels: (IntArray, Int) -> Unit) {
        if (android.os.Build.VERSION.SDK_INT < 26) return
        val win = activity.window ?: return
        val decor = win.decorView as? ViewGroup ?: return
        if (decor.width <= 0 || top < 0 || top + h > decor.height) return
        runCatching {
            val bmp = Bitmap.createBitmap(decor.width, h, Bitmap.Config.ARGB_8888)
            val rect = android.graphics.Rect(0, top, decor.width, top + h)
            android.view.PixelCopy.request(win, rect, bmp, { result ->
                if (result == android.view.PixelCopy.SUCCESS) {
                    runCatching {
                        val px = IntArray(decor.width * h)
                        bmp.getPixels(px, 0, decor.width, 0, 0, decor.width, h)
                        bmp.recycle()
                        onPixels(px, top)
                    }
                } else {
                    HLog.e("$TAG PixelCopy失败(626) $what result=$result")
                }
            }, android.os.Handler(android.os.Looper.getMainLooper()))
        }.onFailure { HLog.e("$TAG PixelCopy异常(626) $what ${it.message}") }
    }

    private fun probePixelCopy626(activity: Activity, decor: ViewGroup, now: Long) {
        val pager = lastHomePager?.get()
        val pw = pager?.width ?: 0
        val idx = if (pager != null && pw > 0) ((pager.scrollX + pw / 2) / pw).coerceIn(0, 3) else -1
        val resting = now - lastPagerScrollAt617 > 700L
        // ① 缝隙带真帧缓冲：主页静置，≤8 次、间隔 2s
        if (resting && idx == 0 && pcSeam626 < 8 && now - lastPcSeam626 > 2000L) {
            lastPcSeam626 = now
            pcSeam626++
            val run = pcSeam626
            pixelCopyBand626(activity, 272, 44, "缝隙带") { px, top ->
                val w = decor.width
                val sb = StringBuilder()
                var hits = 0
                var bestY = -1
                var bestCnt = 0
                var bestColor = 0
                var bestFirst = -1
                var bestLast = -1
                for (y in 0 until 44) {
                    var cnt = 0
                    var first = -1
                    var last = -1
                    var color = 0
                    val base = y * w
                    for (x in 0 until w) {
                        val p = px[base + x]
                        val a = (p ushr 24) and 0xFF
                        if (a < 32) continue
                        val lum = 0.299 * ((p shr 16) and 0xFF) + 0.587 * ((p shr 8) and 0xFF) + 0.114 * (p and 0xFF)
                        if (lum < 150.0) {
                            cnt++
                            if (first < 0) first = x
                            last = x
                            color = p
                        }
                    }
                    if (cnt > bestCnt) {
                        bestCnt = cnt
                        bestY = top + y
                        bestColor = color
                        bestFirst = first
                        bestLast = last
                    }
                    if (cnt > 0 && hits < 6) {
                        hits++
                        sb.append(" [y=").append(top + y).append(" 暗=").append(cnt)
                            .append(" 色=#").append(Integer.toHexString(color))
                            .append(" x=").append(first).append("..").append(last).append("]")
                    }
                }
                HLog.i(
                    "$TAG 真帧缓冲缝隙(626) 第${run}次: 页=$idx 有暗行=$hits$sb" +
                        " | 最暗 y=$bestY 暗=$bestCnt 色=#" + Integer.toHexString(bestColor) + " x=$bestFirst..$bestLast"
                )
            }
        }
        // ② 标题带真帧缓冲：过渡帧，≤24 次、间隔 50ms
        if (!resting && pcTitle626 < 24 && now - lastPcTitle626 > 50L) {
            lastPcTitle626 = now
            pcTitle626++
            val run = pcTitle626
            pixelCopyBand626(activity, 139, 150, "标题带") { px, top ->
                val w = decor.width
                var white = 0
                var total = 0
                var sum = 0.0
                for (y in 0 until 150 step 2) {
                    val base = y * w
                    for (x in 0 until w step 8) {
                        val p = px[base + x]
                        val a = (p ushr 24) and 0xFF
                        if (a < 32) continue
                        val lum = 0.299 * ((p shr 16) and 0xFF) + 0.587 * ((p shr 8) and 0xFF) + 0.114 * (p and 0xFF)
                        total++
                        sum += lum
                        if (lum > 245.0) white++
                    }
                }
                fun at(x: Int, y: Int): String = "#" + Integer.toHexString(px[y.coerceIn(0, 149) * w + x.coerceIn(0, w - 1)])
                HLog.i(
                    "$TAG 真帧缓冲标题带(626) 第${run}次: 白占比=${if (total > 0) white * 100 / total else -1}%" +
                        " 均值=${if (total > 0) (sum / total).toInt() else -1}" +
                        " 采样=[x10y10=${at(10, 10)} x620y10=${at(620, 10)} x1229y10=${at(1229, 10)}" +
                        " x10y75=${at(10, 75)} x620y75=${at(620, 75)} x620y148=${at(620, 148)}]"
                )
            }
        }
        // ③ 缝隙处 View 精确定位：top/bottom 落在 288±4 的所有 View（不再按尺寸过滤）
        if (resting && idx == 0 && seamDump626 < 3 && now - lastPcSeam626 < 20L) {
            seamDump626++
            val sb = StringBuilder()
            var n = 0
            val stack = ArrayDeque<Pair<View, Int>>()
            stack.addLast(decor to 0)
            var guard = 0
            while (stack.isNotEmpty() && guard++ < 2000 && n < 15) {
                val (v, dep) = stack.removeLast()
                if (dep <= 32 && v is ViewGroup) for (i in 0 until v.childCount) stack.addLast(v.getChildAt(i) to dep + 1)
                if (v.visibility != View.VISIBLE || v.width <= 0 || v.height <= 0) continue
                val loc = IntArray(2)
                runCatching { v.getLocationInWindow(loc) }
                val t = loc[1]
                val b = t + v.height
                if (Math.abs(t - 288) > 4 && Math.abs(b - 288) > 4) continue
                n++
                val parent = (v.parent as? View)
                sb.append(" [").append(v.javaClass.simpleName).append("#").append(idName(v))
                    .append(" y=").append(t).append("..").append(b)
                    .append(" x=").append(loc[0]).append(" ").append(v.width).append("x").append(v.height)
                    .append(" bg=").append(descDrawable(v.background))
                    .append(" hw=").append(v.isHardwareAccelerated)
                    .append(" p=").append(parent?.javaClass?.simpleName).append("#").append(parent?.let { idName(it) })
                    .append("]")
            }
            HLog.i("$TAG 缝隙视图精确定位(626) 第${seamDump626}次: 命中=$n$sb")
        }
    }

    /** 627：抓整窗真实帧缓冲（合成后的屏幕，含硬件层与 GL）。 */
    private fun captureFull627(activity: Activity, onPixels: (IntArray, Int, Int) -> Unit) {
        if (android.os.Build.VERSION.SDK_INT < 26) return
        val win = activity.window ?: return
        val decor = win.decorView
        val w = decor.width
        val h = decor.height
        if (w <= 0 || h <= 0) return
        runCatching {
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            android.view.PixelCopy.request(win, android.graphics.Rect(0, 0, w, h), bmp, { r ->
                if (r == android.view.PixelCopy.SUCCESS) {
                    runCatching {
                        val px = IntArray(w * h)
                        bmp.getPixels(px, 0, w, 0, 0, w, h)
                        bmp.recycle()
                        onPixels(px, w, h)
                    }
                } else {
                    HLog.e("$TAG 全屏帧缓冲失败(627) result=$r")
                }
            }, android.os.Handler(android.os.Looper.getMainLooper()))
        }.onFailure { HLog.e("$TAG 全屏帧缓冲异常(627) ${it.message}") }
    }

    private fun rowLum627(px: IntArray, w: Int, y: Int, step: Int = 4): Int {
        var sum = 0.0
        var n = 0
        val base = y * w
        for (x in 0 until w step step) {
            val p = px[base + x]
            val a = (p ushr 24) and 0xFF
            if (a < 32) continue
            sum += 0.299 * ((p shr 16) and 0xFF) + 0.587 * ((p shr 8) and 0xFF) + 0.114 * (p and 0xFF)
            n++
        }
        return if (n > 0) (sum / n).toInt() else -1
    }

    /**
     * 627：主探针。
     * ①主页静置时抓一张**整屏**真帧缓冲做基线，并做全屏行剖面（0..h），找「比上下各 2 行都暗 ≥5」的凹陷行
     *   与「lum<200 覆盖 ≥60% 宽」的长暗行 —— 黑线无论在哪一行都会被列出来；
     * ②过渡帧每 100ms 抓一张，与基线逐行求差，报「差值最大的行/行带」与白占比 —— 闪白无论在哪一条都会被列出来；
     * ③反射列表首行 `ConversationFolderItemView` 的背景（本轮实测是 `InsetDrawable`），打出内层 Drawable 与 insets。
     */
    private fun probeFullFrame627(activity: Activity, decor: ViewGroup, now: Long) {
        val pager = lastHomePager?.get()
        val pw = pager?.width ?: 0
        val idx = if (pager != null && pw > 0) ((pager.scrollX + pw / 2) / pw).coerceIn(0, 3) else -1
        val resting = now - lastPagerScrollAt617 > 700L
        if (resting && idx == 0 && baseRuns627 < 2 && now - lastBase627 > 3000L) {
            lastBase627 = now
            baseRuns627++
            val run = baseRuns627
            captureFull627(activity) { px, w, h ->
                val lum = IntArray(h) { rowLum627(px, w, it) }
                val dips = StringBuilder()
                var dipN = 0
                for (y in 2 until h - 2) {
                    if (lum[y] < 0) continue
                    if (lum[y] < lum[y - 2] - 5 && lum[y] < lum[y + 2] - 5 && dipN < 10) {
                        dipN++
                        dips.append(" [y=").append(y).append(" 均=").append(lum[y])
                            .append(" 邻=").append((lum[y - 2] + lum[y + 2]) / 2)
                            .append(" 色=#").append(Integer.toHexString(px[y * w + w / 2]))
                            .append(" 右色=#").append(Integer.toHexString(px[y * w + (w - 40)]))
                            .append("]")
                    }
                }
                val longs = StringBuilder()
                var longN = 0
                for (y in 0 until h) {
                    var cnt = 0
                    var color = 0
                    var first = -1
                    var last = -1
                    val base = y * w
                    for (x in 0 until w step 2) {
                        val p = px[base + x]
                        val a = (p ushr 24) and 0xFF
                        if (a < 32) continue
                        val l = 0.299 * ((p shr 16) and 0xFF) + 0.587 * ((p shr 8) and 0xFF) + 0.114 * (p and 0xFF)
                        if (l < 200.0) {
                            cnt++
                            if (first < 0) first = x
                            last = x
                            color = p
                        }
                    }
                    if (cnt >= (w / 2) * 6 / 10 && longN < 10) {
                        longN++
                        longs.append(" [y=").append(y).append(" 暗=").append(cnt).append("(x").append(first)
                            .append("..").append(last).append(") 色=#").append(Integer.toHexString(color)).append("]")
                    }
                }
                HLog.i("$TAG 全屏剖面(627) 第${run}次: 屏=${w}x${h} 凹陷=$dipN$dips | 长暗行=$longN$longs")
                if (basePx627 == null || baseW627 != w || baseH627 != h) {
                    basePx627 = px
                    baseW627 = w
                    baseH627 = h
                }
            }
        }
        if (!resting && diffRuns627 < 8 && now - lastDiff627 > 100L && basePx627 != null) {
            lastDiff627 = now
            diffRuns627++
            val run = diffRuns627
            captureFull627(activity) { px, w, h ->
                val bs = basePx627
                if (bs == null || w != baseW627 || h != baseH627) return@captureFull627
                var white = 0
                var total = 0
                var bestY = -1
                var bestDiff = 0
                val band = StringBuilder()
                var bandN = 0
                var prevBig = false
                for (y in 0 until h step 4) {
                    val cur = rowLum627(px, w, y)
                    val old = rowLum627(bs, w, y)
                    if (cur < 0 || old < 0) continue
                    val d = Math.abs(cur - old)
                    if (d > bestDiff) {
                        bestDiff = d
                        bestY = y
                    }
                    if (d > 10) {
                        if (!prevBig && bandN < 6) {
                            bandN++
                            band.append(" [y=").append(y).append(" 差=").append(d)
                                .append(" 当前=#").append(Integer.toHexString(px[y * w + w / 2])).append("]")
                        }
                        prevBig = true
                    } else {
                        prevBig = false
                    }
                }
                for (y in 0 until h step 4) {
                    val b = y * w
                    for (x in 0 until w step 8) {
                        val p = px[b + x]
                        val a = (p ushr 24) and 0xFF
                        if (a < 32) continue
                        total++
                        val l = 0.299 * ((p shr 16) and 0xFF) + 0.587 * ((p shr 8) and 0xFF) + 0.114 * (p and 0xFF)
                        if (l > 248.0) white++
                    }
                }
                HLog.i(
                    "$TAG 过渡差分(627) 第${run}次: 页=$idx 白占比=${if (total > 0) white * 100 / total else -1}%" +
                        " 最大行差 y=$bestY 差=$bestDiff 色=#" + Integer.toHexString(if (bestY >= 0) px[bestY * w + w / 2] else 0) +
                        " 变化行带=$bandN$band"
                )
            }
        }
        // ③ 列表首行底衬反射（只做 1 次）
        if (resting && idx == 0 && insetDump627 < 1) {
            insetDump627++
            runCatching {
                val list = findViewByClassName(decor, "ConversationListView")
                var row: View? = null
                if (list is ViewGroup) {
                    for (i in 0 until list.childCount) {
                        val c = list.getChildAt(i)
                        if (c != null && c.height > 0) {
                            row = c
                            break
                        }
                    }
                }
                val bg = row?.background
                val sb = StringBuilder()
                if (bg is android.graphics.drawable.InsetDrawable) {
                    for (f in bg.javaClass.declaredFields) {
                        runCatching {
                            f.isAccessible = true
                            val v = f.get(bg)
                            sb.append(" ").append(f.name).append("=")
                            when (v) {
                                is android.graphics.drawable.Drawable -> sb.append(descDrawable(v))
                                    .append(" bounds=").append(v.bounds.flattenToString())
                                is android.graphics.Rect -> sb.append("(").append(v.left).append(",").append(v.top)
                                    .append(",").append(v.right).append(",").append(v.bottom).append(")")
                                else -> sb.append(v)
                            }
                        }
                    }
                }
                HLog.i(
                    "$TAG 列表首行底衬(627): 行=" + (row?.javaClass?.simpleName ?: "无") +
                        "#" + (row?.let { idName(it) } ?: "-") +
                        " y=" + (row?.let { v -> val l = IntArray(2); v.getLocationInWindow(l); l[1] } ?: -1) +
                        " 背景=" + descDrawable(bg) + " 内层:$sb"
                )
            }
        }
    }

    /**
     * 628：`全屏剖面(627)` 已经把那条线量出来了 —— 主页静置时 **y=287 与 y=288 两行**
     * 均值 226、邻行 245、中色 `#ffeae5be`（壁纸 #fef5ba 被 10% 黑压暗），
     * 形状与历史候选 `View x=236 1004x1 bg=ColorDrawable#1a000000` 完全一致：**1~2px、半透明黑、从 x=236 起**。
     * 这类 1px 分隔是列表行的分隔线 View（`height in 1..3`，宽≥600，background 是低 alpha 近黑 ColorDrawable）。
     * 本版做**一次性**清除：只在「页=0 且静置 >700ms」时最多做 5 轮、每轮间隔 1.2s，**不进每帧循环**，
     * 避免 618 那种「每帧写宿主 View 属性」导致的持续闪烁回归。
     */
    private fun clearSeamHairlines628(decor: ViewGroup, now: Long) {
        if (seamFix628Done >= 5) return
        if (now - lastPagerScrollAt617 < 700L) return
        if (now - seamFix628At < 1200L) return
        val list = findViewByClassName(decor, "ConversationListView") ?: return
        seamFix628At = now
        seamFix628Done++
        val sb = StringBuilder()
        var n = 0
        val stack = ArrayDeque<View>()
        stack.addLast(list)
        var guard = 0
        while (stack.isNotEmpty() && guard++ < 600) {
            val v = stack.removeLast()
            if (v is ViewGroup) for (i in 0 until v.childCount) stack.addLast(v.getChildAt(i))
            if (v.height !in 1..3 || v.width < 600) continue
            val bg = v.background
            if (bg !is ColorDrawable) continue
            val c = bg.color
            val a = (c ushr 24) and 0xFF
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            if (a > 64 || r > 48 || g > 48 || b > 48) continue
            val loc = IntArray(2)
            runCatching { v.getLocationInWindow(loc) }
            runCatching { v.background = null }
            n++
            if (n <= 8) {
                sb.append(" [").append(v.javaClass.simpleName).append("#").append(idName(v))
                    .append(" y=").append(loc[1]).append("..").append(loc[1] + v.height)
                    .append(" x=").append(loc[0]).append(" ").append(v.width).append("x").append(v.height)
                    .append(" 色=#").append(Integer.toHexString(c)).append("]")
            }
        }
        HLog.i("$TAG 缝隙淡线一次性清除(628) 第${seamFix628Done}次: 清除=$n$sb")
    }

    /**
     * 628：窄条逐帧闪白取证。626 的整带 PixelCopy 每 50ms 一次仍可能漏 1 帧；
     * 这里只抓 **x=100..140 的 40×150 窄条**（成本极低），**每帧**一次、过渡窗口内最多 40 帧，
     * 报该条的白占比(lum>250)/均值/最亮值 —— 只要标题带真的白过一帧，这里必然命中。
     */
    private fun probeStrip628(activity: Activity, now: Long) {
        if (now - lastPagerScrollAt617 > 700L) return
        if (stripRuns628 >= 40) return
        if (now - lastStrip628 < 12L) return
        lastStrip628 = now
        stripRuns628++
        val run = stripRuns628
        val win = activity.window ?: return
        val decor = win.decorView as? ViewGroup ?: return
        if (android.os.Build.VERSION.SDK_INT < 26 || decor.width < 200) return
        runCatching {
            val bmp = Bitmap.createBitmap(40, 150, Bitmap.Config.ARGB_8888)
            val rect = android.graphics.Rect(100, 139, 140, 289)
            android.view.PixelCopy.request(win, rect, bmp, { r ->
                if (r == android.view.PixelCopy.SUCCESS) runCatching {
                    val px = IntArray(40 * 150)
                    bmp.getPixels(px, 0, 40, 0, 0, 40, 150)
                    bmp.recycle()
                    var white = 0
                    var total = 0
                    var sum = 0.0
                    var maxLum = 0.0
                    for (p in px) {
                        val a = (p ushr 24) and 0xFF
                        if (a < 32) continue
                        val lum = 0.299 * ((p shr 16) and 0xFF) + 0.587 * ((p shr 8) and 0xFF) + 0.114 * (p and 0xFF)
                        total++
                        sum += lum
                        if (lum > maxLum) maxLum = lum
                        if (lum > 250.0) white++
                    }
                    HLog.i(
                        "$TAG 窄条闪白(628) 第${run}帧: 白占比=${if (total > 0) white * 100 / total else -1}%" +
                            " 均值=${if (total > 0) (sum / total).toInt() else -1} 最亮=${maxLum.toInt()}" +
                            " 中色=#" + Integer.toHexString(px[75 * 40 + 20])
                    )
                }
            }, android.os.Handler(android.os.Looper.getMainLooper()))
        }
    }

    /**
     * 628b：用户 09:50 更正「黑线在主页列表与小程序界面的交界处」。
     * 627 的全屏剖面是 0..2772 全高，但候选只保留最暗 10 条（`凹陷=10` 已截断），
     * 小程序底栏上沿的淡线会被 1579..1588 那些图片行挤掉，所以这里**定点**取上沿带：
     * 先找贴底（bottom 在 H-80 内）、宽≥90% 屏宽、高 40..400 的候选条，逐条打出类名/位置；
     * 再取其中最高的那条的**上沿**，用 PixelCopy 抓 [top-24, top+24] 的行均值/暗像素，找局部凹陷行。
     */
    private fun probeMiniBar628(activity: Activity, decor: ViewGroup, now: Long) {
        if (miniBarRuns628 >= 3) return
        if (now - lastPagerScrollAt617 < 700L) return
        if (now - lastMiniBar628 < 1500L) return
        val pager = lastHomePager?.get() ?: return
        val pw = pager.width
        if (pw <= 0) return
        if (((pager.scrollX + pw / 2) / pw).coerceIn(0, 3) != 0) return
        lastMiniBar628 = now
        miniBarRuns628++
        val run = miniBarRuns628
        val H = decor.height
        val W = decor.width
        if (H < 400 || W <= 0) return
        val cands = ArrayList<View>()
        val stack = ArrayDeque<View>()
        stack.addLast(decor)
        var guard = 0
        while (stack.isNotEmpty() && guard++ < 1500) {
            val v = stack.removeLast()
            if (v is ViewGroup) for (i in 0 until v.childCount) stack.addLast(v.getChildAt(i))
            if (v.visibility != View.VISIBLE || v.width < W * 9 / 10 || v.height !in 40..400) continue
            val loc = IntArray(2)
            runCatching { v.getLocationInWindow(loc) }
            val top = loc[1]
            val bottom = top + v.height
            if (bottom < H - 80 || bottom > H + 4) continue
            cands.add(v)
        }
        val sb = StringBuilder()
        var topmost = -1
        var maxH = 0
        for ((i, v) in cands.withIndex()) {
            if (i >= 6) break
            val loc = IntArray(2)
            runCatching { v.getLocationInWindow(loc) }
            if (v.height > maxH) {
                maxH = v.height
                topmost = loc[1]
            }
            sb.append(" [").append(v.javaClass.simpleName).append("#").append(idName(v))
                .append(" y=").append(loc[1]).append("..").append(loc[1] + v.height)
                .append(" ").append(v.width).append("x").append(v.height)
                .append(" bg=").append(descDrawable(v.background))
                .append(" hw=").append(v.isHardwareAccelerated).append("]")
        }
        HLog.i("$TAG 小程序底栏候选(628) 第${run}次: 命中=${cands.size} 最高=${maxH}px 上沿=$topmost$sb")
        if (topmost <= 24 || topmost + 24 >= H) return
        val bTop = topmost - 24
        val runFix = run
        pixelCopyBand626(activity, bTop, 48, "底栏上沿") { px, top ->
            val w = W
            val means = IntArray(48)
            val darks = IntArray(48)
            val v620 = IntArray(48)
            for (y in 0 until 48) {
                var sum = 0.0
                var cnt = 0
                var dark = 0
                val base = y * w
                for (x in 0 until w) {
                    val p = px[base + x]
                    val a = (p ushr 24) and 0xFF
                    if (a < 32) continue
                    val lum = 0.299 * ((p shr 16) and 0xFF) + 0.587 * ((p shr 8) and 0xFF) + 0.114 * (p and 0xFF)
                    sum += lum
                    cnt++
                    if (lum < 200.0) dark++
                }
                means[y] = if (cnt > 0) (sum / cnt).toInt() else -1
                darks[y] = dark
                v620[y] = px[y * w + (w / 2).coerceIn(0, w - 1)]
            }
            val sb2 = StringBuilder()
            var dips = 0
            for (y in 2 until 46) {
                if (means[y] < 0) continue
                if (means[y] <= means[y - 2] - 5 && means[y] <= means[y + 2] - 5) {
                    dips++
                    if (dips <= 6) {
                        sb2.append(" [y=").append(top + y).append(" 均=").append(means[y])
                            .append(" 邻=").append(Math.max(means[y - 2], means[y + 2]))
                            .append(" 暗=").append(darks[y])
                            .append(" 色=#").append(Integer.toHexString(v620[y]))
                            .append(" 右=#").append(Integer.toHexString(px[y * w + (w - 12)]))
                            .append(" 左=#").append(Integer.toHexString(px[y * w + 12])).append("]")
                    }
                }
            }
            HLog.i("$TAG 底栏上沿剖面(628) 第${runFix}次: 上沿=$topmost 凹陷=$dips$sb2")
        }
    }

    /**
     * 629：按截图实证处理那条线。特征是「整宽 2px、约 10% 黑、位于 y=287..288（列表内容区顶边）」，
     * 排除法：不是行内 InsetDrawable（那只从 x=236 起），而是列表的分隔线对象。
     * 一次性处理：ListView → divider=null + dividerHeight=0；RecyclerView → 移除名字含 divider/separator/line 的 ItemDecoration。
     * 同时把「高≤4 且宽≥1000」的整宽细 View 一并列出（若有 287..288 的全宽 2px View 就是它）。
     * 全部只在「页=0 且静置>700ms」时跑，最多 5 轮、间隔 1.5s，不进每帧。
     */
    private fun stripDividers629(decor: ViewGroup, now: Long) {
        if (divFix629 >= 5) return
        if (now - lastPagerScrollAt617 < 700L) return
        if (now - lastDiv629 < 1500L) return
        val list = findViewByClassName(decor, "ConversationListView") ?: return
        lastDiv629 = now
        divFix629++
        val info = StringBuilder()
        if (list is android.widget.ListView) {
            info.append(" ListView divider=").append(descDrawable(list.divider))
                .append(" h=").append(list.dividerHeight)
            runCatching { list.divider = null }
            runCatching { list.dividerHeight = 0 }
        }
        val cnt = runCatching {
            list.javaClass.getMethod("getItemDecorationCount").invoke(list) as Int
        }.getOrNull()
        if (cnt != null && cnt > 0) {
            for (i in 0 until cnt) {
                runCatching {
                    val at = list.javaClass.methods.firstOrNull { it.name == "getItemDecorationAt" && it.parameterTypes.size == 1 }
                    val dec = at?.invoke(list, i) ?: return@runCatching
                    val nm = dec.javaClass.name
                    info.append(" 装饰$i=").append(nm)
                    val lower = nm.lowercase()
                    if (lower.contains("divider") || lower.contains("separator") || lower.contains("line")) {
                        val rm = list.javaClass.methods.firstOrNull { it.name == "removeItemDecoration" && it.parameterTypes.size == 1 }
                        rm?.invoke(list, dec)
                        info.append("(已移除)")
                    }
                }
            }
        }
        // 整宽细 View 排查
        val stack = ArrayDeque<View>()
        stack.addLast(decor)
        var guard = 0
        var thin = 0
        while (stack.isNotEmpty() && guard++ < 1500) {
            val v = stack.removeLast()
            if (v is ViewGroup) for (i in 0 until v.childCount) stack.addLast(v.getChildAt(i))
            if (v.visibility != View.VISIBLE || v.height !in 1..4 || v.width < 1000) continue
            val loc = IntArray(2)
            runCatching { v.getLocationInWindow(loc) }
            if (thin < 6) {
                info.append(" 宽细View[").append(v.javaClass.simpleName).append("#").append(idName(v))
                    .append(" y=").append(loc[1]).append("..").append(loc[1] + v.height)
                    .append(" ").append(v.width).append("x").append(v.height)
                    .append(" bg=").append(descDrawable(v.background)).append("]")
            }
            thin++
        }
        val after = if (list is android.widget.ListView) " 清除后: divider=" + descDrawable(list.divider) + " h=" + list.dividerHeight else ""
        HLog.i("$TAG 列表分隔线取证(629) 第${divFix629}次: 装饰=$cnt 宽细View=$thin$after$info")
    }

    /**
     * 630：整窗逐帧找闪白。过渡窗口内每 ≥80ms 抓整窗（≤6 帧），只保留每行均值：
     * ①「整宽近白带」= 行均值>250 且该行 ≥95% 列亮度>245 的连续行；
     * ②「相邻帧突变」= 与上一帧同一行均值差 >25 的连续行（闪 1~2 帧的东西必然在这里冒头）。
     * 输出 `整窗闪白(630)`，一次帧一行，带 y 区间与峰值差。
     */
    private fun probeFlashFrame630(activity: Activity, now: Long) {
        if (now - lastPagerScrollAt617 > 1400L) return
        if (flashRuns630 >= 10) return
        if (now - lastFlash630 < 90L) return
        lastFlash630 = now
        flashRuns630++
        val run = flashRuns630
        captureFull627(activity) { px, w, h ->
            runCatching {
                val means = IntArray(h)
                val brightRows = IntArray(h)
                for (y in 0 until h) {
                    val base = y * w
                    var sum = 0L
                    var bright = 0
                    var cnt = 0
                    for (x in 0 until w) {
                        val p = px[base + x]
                        val a = (p ushr 24) and 0xFF
                        if (a < 32) continue
                        val lum = (299 * ((p shr 16) and 0xFF) + 587 * ((p shr 8) and 0xFF) + 114 * (p and 0xFF)) / 1000
                        sum += lum
                        cnt++
                        if (lum > 245) bright++
                    }
                    means[y] = if (cnt > 0) (sum / cnt).toInt() else -1
                    brightRows[y] = if (cnt > 0 && bright * 100 / cnt >= 95 && means[y] > 250) 1 else 0
                }
                // 近白带
                val sb = StringBuilder()
                var bands = 0
                var y = 0
                while (y < h && bands < 4) {
                    if (brightRows[y] == 1) {
                        val st = y
                        while (y < h && brightRows[y] == 1) y++
                        bands++
                        sb.append(" 白带=[y ").append(st).append("..").append(y - 1).append("]")
                    } else y++
                }
                // 相邻帧突变
                val prev = prevRowMean630
                var d = 0
                if (prev != null && prev.size == h) {
                    var i = 0
                    while (i < h && d < 4) {
                        if (means[i] >= 0 && prev[i] >= 0 && Math.abs(means[i] - prev[i]) > 25) {
                            val st = i
                            var peak = 0
                            while (i < h && means[i] >= 0 && prev[i] >= 0 && Math.abs(means[i] - prev[i]) > 25) {
                                peak = Math.max(peak, Math.abs(means[i] - prev[i]))
                                i++
                            }
                            d++
                            sb.append(" 突变=[y ").append(st).append("..").append(i - 1).append(" Δ").append(peak).append("]")
                        } else i++
                    }
                }
                prevRowMean630 = means
                HLog.i("$TAG 整窗闪白(630) 第${run}帧: 屏=${w}x${h} 白带数=$bands 突变数=$d$sb")
            }
        }
    }

    private fun lumOf631(p: Int): Double =
        0.299 * ((p shr 16) and 0xFF) + 0.587 * ((p shr 8) and 0xFF) + 0.114 * (p and 0xFF)

    /** 只把某一层单独画进 y=bandTop..bandTop+hBand 的窄带，找比上下各 2 行暗 ≥5 的行 */
    private fun bandDips631(v: View?, bandTop: Int, w: Int, hBand: Int): String {
        if (v == null) return "空"
        val bmp = runCatching { Bitmap.createBitmap(w, hBand, Bitmap.Config.ARGB_8888) }.getOrNull() ?: return "分配失败"
        bmp.eraseColor(0)
        val cv = Canvas(bmp)
        val loc = IntArray(2)
        runCatching { v.getLocationInWindow(loc) }
        cv.save()
        cv.translate(-loc[0].toFloat(), -bandTop.toFloat())
        runCatching { v.draw(cv) }
        cv.restore()
        val px = IntArray(w * hBand)
        bmp.getPixels(px, 0, w, 0, 0, w, hBand)
        bmp.recycle()
        val means = IntArray(hBand)
        for (y in 0 until hBand) {
            var sum = 0.0
            var n = 0
            for (x in 0 until w) {
                val p = px[y * w + x]
                if (((p ushr 24) and 0xFF) < 32) continue
                sum += lumOf631(p)
                n++
            }
            means[y] = if (n > 0) (sum / n).toInt() else -1
        }
        val sb = StringBuilder()
        var k = 0
        for (y in 2 until hBand - 2) {
            if (means[y] < 0) continue
            if (means[y] <= means[y - 2] - 5 && means[y] <= means[y + 2] - 5) {
                k++
                if (k <= 3) sb.append(" [y=").append(bandTop + y).append(" 均=").append(means[y])
                    .append(" 邻=").append(Math.max(means[y - 2], means[y + 2])).append("]")
            }
        }
        return "凹陷=$k$sb"
    }

    /**
     * 631：黑线的绘制者定位。629 证明「不是 ListView.divider」（日志回读 divider=null h=0、装饰=null，线仍在）。
     * 这里换成逐层隔离：decor / 页0 / 列表 / 行0 / 标题栏 各单独画到 y=280..296 的窄带，
     * 谁画出 287/288 的暗行，谁就是绘制者；同时回读所有跨越 287..289 的 View 的 bg/fg/elevation/translationZ，
     * InsetDrawable 再拆出内层 Drawable 与内嵌 top/bottom。
     */
    private fun probeLayers631(activity: Activity, decor: ViewGroup, now: Long) {
        if (layerRuns631 >= 2) return
        if (now - lastPagerScrollAt617 < 800L) return
        if (now - lastLayer631 < 2000L) return
        val pager = lastHomePager?.get() ?: return
        val pw = pager.width
        if (pw <= 0) return
        if (((pager.scrollX + pw / 2) / pw).coerceIn(0, 3) != 0) return
        lastLayer631 = now
        layerRuns631++
        val run = layerRuns631
        val bandTop = 280
        val hBand = 16
        val w = decor.width
        if (w <= 0) return
        val list = findViewByClassName(decor, "ConversationListView")
        val page0 = if (pager is ViewGroup && pager.childCount > 0) pager.getChildAt(0) else null
        val row0 = if (list is ViewGroup && list.childCount > 0) list.getChildAt(0) else null
        val ab = findViewByClassName(decor, "ActionBarContainer")
        val sb = StringBuilder()
        sb.append(" decor(").append(bandDips631(decor, bandTop, w, hBand)).append(")")
        sb.append(" 页0(").append(bandDips631(page0, bandTop, w, hBand)).append(")")
        sb.append(" 列表(").append(bandDips631(list, bandTop, w, hBand)).append(")")
        sb.append(" 行0(").append(bandDips631(row0, bandTop, w, hBand)).append(")")
        sb.append(" 标题栏(").append(bandDips631(ab, bandTop, w, hBand)).append(")")
        HLog.i("$TAG 分层隔离(631) 第${run}次: 带=${bandTop}..${bandTop + hBand}$sb")

        val info = StringBuilder()
        var hit = 0
        val stack = ArrayDeque<View>()
        stack.addLast(decor)
        var guard = 0
        while (stack.isNotEmpty() && guard++ < 1200) {
            val v = stack.removeLast()
            if (v is ViewGroup) for (i in 0 until v.childCount) stack.addLast(v.getChildAt(i))
            if (v.visibility != View.VISIBLE) continue
            val loc = IntArray(2)
            runCatching { v.getLocationInWindow(loc) }
            val top = loc[1]
            val bottom = top + v.height
            if (bottom < 287 || top > 289) continue
            hit++
            if (hit > 10) continue
            info.append(" [").append(v.javaClass.simpleName).append("#").append(idName(v))
                .append(" y=").append(top).append("..").append(bottom)
                .append(" x=").append(loc[0]).append(" ").append(v.width).append("x").append(v.height)
                .append(" bg=").append(descDrawable(v.background))
                .append(" fg=").append(descDrawable(v.foreground))
                .append(" el=").append(v.elevation).append(" tz=").append(v.translationZ)
                .append(" hw=").append(v.isHardwareAccelerated)
            val bgd = v.background
            if (bgd is InsetDrawable) {
                var inner: Drawable? = null
                var insTop = 0
                var insBottom = 0
                runCatching {
                    val f1 = InsetDrawable::class.java.getDeclaredField("mDrawable")
                    f1.isAccessible = true
                    inner = f1.get(bgd) as? Drawable
                    val f2 = InsetDrawable::class.java.getDeclaredField("mInsetTop")
                    f2.isAccessible = true
                    insTop = (f2.get(bgd) as? Int) ?: 0
                    val f3 = InsetDrawable::class.java.getDeclaredField("mInsetBottom")
                    f3.isAccessible = true
                    insBottom = (f3.get(bgd) as? Int) ?: 0
                }
                info.append(" 内层=").append(descDrawable(inner)).append(" 内嵌top=$insTop bottom=$insBottom")
            }
            info.append("]")
        }
        HLog.i("$TAG 边界视图回读(631) 第${run}次: 命中=$hit$info")
    }

    /** 632：把「贴着 287..289」的 View 才列出来（631 版本按跨越算，前 10 条全被全屏容器占满，等于没看到） */
    private fun boundaryEdges632(decor: ViewGroup): String {
        val sb = StringBuilder()
        var hit = 0
        val stack = ArrayDeque<View>()
        stack.addLast(decor)
        var guard = 0
        while (stack.isNotEmpty() && guard++ < 1200) {
            val v = stack.removeLast()
            if (v is ViewGroup) for (i in 0 until v.childCount) stack.addLast(v.getChildAt(i))
            if (v.visibility != View.VISIBLE) continue
            val loc = IntArray(2)
            runCatching { v.getLocationInWindow(loc) }
            val top = loc[1]
            val bottom = top + v.height
            val edge = top in 284..292 || bottom in 284..292
            if (!edge) continue
            hit++
            if (hit > 12) continue
            sb.append(" [").append(v.javaClass.simpleName).append("#").append(idName(v))
                .append(" y=").append(top).append("..").append(bottom)
                .append(" x=").append(loc[0]).append(" ").append(v.width).append("x").append(v.height)
                .append(" bg=").append(descDrawable(v.background))
                .append(" fg=").append(descDrawable(v.foreground))
                .append(" el=").append(v.elevation)
                .append(" padT=").append(v.paddingTop).append(" ctp=").append((v as? ViewGroup)?.clipToPadding)
            val bgd = v.background
            if (bgd is InsetDrawable) {
                var inner: Drawable? = null
                var insTop = 0
                runCatching {
                    val f1 = InsetDrawable::class.java.getDeclaredField("mDrawable")
                    f1.isAccessible = true
                    inner = f1.get(bgd) as? Drawable
                    val f2 = InsetDrawable::class.java.getDeclaredField("mInsetTop")
                    f2.isAccessible = true
                    insTop = (f2.get(bgd) as? Int) ?: 0
                }
                sb.append(" 内层=").append(descDrawable(inner)).append(" 内嵌top=$insTop")
            }
            sb.append("]")
        }
        return "命中=$hit$sb"
    }

    /** 632：把 ConversationListView 及其父类里所有 Paint / Drawable / int 字段都倒出来，找「谁带着那条线的颜色」 */
    private fun listFields632(list: View): String {
        val sb = StringBuilder()
        var n = 0
        var cls: Class<*>? = list.javaClass
        var depth = 0
        while (cls != null && depth < 3 && n < 28) {
            for (f in cls.declaredFields) {
                if (n >= 28) break
                val t = f.type
                val isPaint = android.graphics.Paint::class.java.isAssignableFrom(t)
                val isDrawable = Drawable::class.java.isAssignableFrom(t)
                val isInt = t == Int::class.javaPrimitiveType
                if (!isPaint && !isDrawable && !isInt) continue
                runCatching {
                    f.isAccessible = true
                    val v = f.get(list)
                    if (isPaint) {
                        val pt = v as? android.graphics.Paint ?: return@runCatching
                        val c = pt.color
                        sb.append(" ").append(f.name).append("=Paint(#").append(Integer.toHexString(c))
                            .append(" a=").append(pt.alpha).append(" w=").append(pt.strokeWidth).append(")")
                        n++
                    } else if (isDrawable) {
                        sb.append(" ").append(f.name).append("=").append(descDrawable(v as? Drawable))
                        n++
                    } else if (isInt) {
                        val iv = v as? Int ?: return@runCatching
                        if (iv != 0 && (f.name.contains("ivider") || f.name.contains("hadow") || f.name.contains("ine") || f.name.contains("olor"))) {
                            sb.append(" ").append(f.name).append("=").append(iv)
                            n++
                        }
                    }
                }
            }
            cls = cls.superclass
            depth++
        }
        return "字段=$n$sb"
    }

    /** 632：把列表的每个子 View 单独画进 y=280..296 窄带，确认线是不是某个子 View 画的 */
    private fun childrenIsolation632(list: ViewGroup, w: Int, bandTop: Int, hBand: Int): String {
        val sb = StringBuilder()
        val c = Math.min(list.childCount, 4)
        for (i in 0 until c) {
            val ch = list.getChildAt(i)
            sb.append(" 子$i(").append(bandDips631(ch, bandTop, w, hBand)).append(")")
        }
        return if (c == 0) "无子" else sb.toString()
    }

    /** 632：把列表层单独画出的窄带逐行均值 + 三个采样点的颜色都打出来，拿到「列表自绘线」的真实色值 */
    private fun listBandProfile632(list: View, w: Int, bandTop: Int, hBand: Int): String {
        val bmp = runCatching { Bitmap.createBitmap(w, hBand, Bitmap.Config.ARGB_8888) }.getOrNull() ?: return "分配失败"
        bmp.eraseColor(0)
        val cv = Canvas(bmp)
        val loc = IntArray(2)
        runCatching { list.getLocationInWindow(loc) }
        cv.save()
        cv.translate(-loc[0].toFloat(), -bandTop.toFloat())
        runCatching { list.draw(cv) }
        cv.restore()
        val px = IntArray(w * hBand)
        bmp.getPixels(px, 0, w, 0, 0, w, hBand)
        bmp.recycle()
        val sb = StringBuilder()
        for (y in 0 until hBand) {
            var sum = 0.0
            var n = 0
            for (x in 0 until w) {
                val p = px[y * w + x]
                if (((p ushr 24) and 0xFF) < 32) continue
                sum += lumOf631(p)
                n++
            }
            val mean = if (n > 0) (sum / n).toInt() else -1
            sb.append(" [").append(bandTop + y).append(":").append(mean)
                .append("/#").append(Integer.toHexString(px[y * w + 30]))
                .append("/#").append(Integer.toHexString(px[y * w + (w / 2)]))
                .append("/#").append(Integer.toHexString(px[y * w + (w - 30)]))
                .append("]")
        }
        return sb.toString()
    }

    private fun probeLayers632(decor: ViewGroup, now: Long) {
        if (layerRuns632 >= 2) return
        if (now - lastPagerScrollAt617 < 800L) return
        if (now - lastLayer632 < 2500L) return
        val pager = lastHomePager?.get() ?: return
        val pw = pager.width
        if (pw <= 0) return
        if (((pager.scrollX + pw / 2) / pw).coerceIn(0, 3) != 0) return
        val list = findViewByClassName(decor, "ConversationListView") as? ViewGroup ?: return
        lastLayer632 = now
        layerRuns632++
        val run = layerRuns632
        val w = decor.width
        val bandTop = 280
        val hBand = 16
        if (w <= 0) return
        HLog.i("$TAG 列表边缘视图(632) 第${run}次: " + boundaryEdges632(decor))
        HLog.i("$TAG 列表字段(632) 第${run}次: " + listFields632(list) +
            " | divider=${descDrawable((list as? android.widget.ListView)?.divider)}" +
            " h=${(list as? android.widget.ListView)?.dividerHeight} listTop=${list.top} padT=${list.paddingTop}")
        HLog.i("$TAG 子隔离(632) 第${run}次:" + childrenIsolation632(list, w, bandTop, hBand))
        HLog.i("$TAG 列表带剖面(632) 第${run}次:" + listBandProfile632(list, w, bandTop, hBand))
    }

    /** 633：只抓 y=top..top+height 这条真实帧缓冲窄带，用于清前/清后自证 */
    private fun captureBand633(activity: Activity, top: Int, height: Int, onPixels: (IntArray, Int, Int) -> Unit) {
        if (android.os.Build.VERSION.SDK_INT < 26) return
        val win = activity.window ?: return
        val decor = win.decorView
        val w = decor.width
        val h = decor.height
        if (w <= 0 || top < 0 || top + height > h) return
        val bmp = runCatching { Bitmap.createBitmap(w, height, Bitmap.Config.ARGB_8888) }.getOrNull() ?: return
        val loc = IntArray(2)
        runCatching { decor.getLocationInWindow(loc) }
        val rect = android.graphics.Rect(loc[0], loc[1] + top, loc[0] + w, loc[1] + top + height)
        runCatching {
            android.view.PixelCopy.request(win, rect, bmp, { r ->
                if (r == android.view.PixelCopy.SUCCESS) {
                    val px = IntArray(w * height)
                    bmp.getPixels(px, 0, w, 0, 0, w, height)
                    onPixels(px, w, height)
                } else {
                    HLog.i("$TAG 窄带实测(633): PixelCopy 失败 code=$r")
                }
                bmp.recycle()
            }, android.os.Handler(android.os.Looper.getMainLooper()))
        }
    }

    private fun seamRows633(px: IntArray, w: Int, h: Int, top: Int): String {
        val means = IntArray(h)
        for (y in 0 until h) {
            var sum = 0.0
            for (x in 0 until w) sum += lumOf631(px[y * w + x])
            means[y] = (sum / w).toInt()
        }
        val dips = StringBuilder()
        var k = 0
        for (y in 2 until h - 2) {
            if (means[y] <= means[y - 2] - 5 && means[y] <= means[y + 2] - 5) {
                k++
                if (k <= 4) dips.append(" [y=").append(top + y).append(" 均=").append(means[y])
                    .append(" 邻=").append(Math.max(means[y - 2], means[y + 2]))
                    .append(" 色=#").append(Integer.toHexString(px[y * w + w / 2])).append("]")
            }
        }
        val rows = StringBuilder()
        for (y in 0 until h) {
            if (top + y < 282 || top + y > 292) continue
            rows.append(" ").append(top + y).append(":").append(means[y])
        }
        return "凹陷=$k$dips 行=$rows"
    }

    /**
     * 710：633 缝隙清线改为「每个 ConversationListView 实例首次布局后清一次 Paint」。
     *
     * 706 把 probeSeam633 挂到 500ms 巡检里反复清同一个列表，四页全乱，707 因此整段回退。
     * 这里用 WeakHashMap 记住已处理的实例：宿主重建列表（新实例）才再清一次，同一实例永不重复清；
     * 触发用全局布局监听（列表还没布局时不会空跑），并限频 500ms，避免切页动画期间每帧走树。
     */
    private fun installSeamClearer710(decor: ViewGroup) {
        if (!seamClearerDecors710.add(decor)) return
        runCatching {
            decor.viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
                override fun onGlobalLayout() {
                    runCatching { clearSeamOnce710(decor) }
                        .onFailure { HLog.e("$TAG 633 缝隙清线异常(710): ${it.message}", it) }
                }
            })
            HLog.i("$TAG 633 缝隙清线已挂载(710)：每个列表实例首次布局后清一次 Paint")
            // 710 实测：布局监听在部分会话里只对第一个列表实例回调（17:00 清过、17:06/17:07/17:08 三次都没清），
            // 所以挂载后自己也主动试几轮，覆盖「列表晚于首次布局才挂上」的情况；每实例仍只清一次。
            runCatching { clearSeamOnce710(decor) }
            decor.post { runCatching { clearSeamOnce710(decor) } }
            decor.postDelayed({ runCatching { clearSeamOnce710(decor) } }, 400L)
            decor.postDelayed({ runCatching { clearSeamOnce710(decor) } }, 1500L)
        }.onFailure { HLog.e("$TAG 633 缝隙清线挂载失败(710): ${it.message}", it) }
    }
    private fun clearSeamOnce710(root: ViewGroup) {
        if (root.width <= 0 || root.height <= 0) return
        val now = SystemClock.uptimeMillis()
        if (now - lastSeamWalk710 < 1500L) return
        lastSeamWalk710 = now
        val lists = ArrayList<View>()
        collectSeamLists710(root, lists, 0)
        if (lists.isEmpty()) {
            if (seamEmptyLogged710.add(root)) {
                HLog.i("$TAG 633 缝隙清线(710): 当前视图树里还没有 $SEAM_LIST_CLASS，等下一次布局重试")
            }
            return
        }
        val fresh = lists.filter { !seamClearedLists710.contains(it) }
        if (fresh.isEmpty()) return
        HLog.i("$TAG 633 缝隙清线(710): 待处理列表 ${fresh.size}/${lists.size} 个")
        for (list in fresh) {
            // 关键：每个列表实例只清一次，已处理过就整段跳过（706 的乱屏就是重复清同一个列表）。
            if (!seamClearedLists710.add(list)) continue
            val sb = StringBuilder()
            var count = 0
            var cls: Class<*>? = list.javaClass
            var depth = 0
            while (cls != null && depth < 3) {
                for (f in KavaReflector.declaredFields(cls)) {
                    if (!Paint::class.java.isAssignableFrom(f.type)) continue
                    runCatching {
                        val paint = f.get(list) as? Paint ?: return@runCatching
                        val color = paint.color
                        if (((color ushr 24) and 0xFF) !in 1..80 || (color and 0xFFFFFF) != 0) return@runCatching
                        sb.append(" ").append(f.name).append("=#").append(Integer.toHexString(color))
                        paint.color = 0
                        paint.alpha = 0
                        count++
                    }
                }
                cls = cls.superclass
                depth++
            }
            runCatching { list.invalidate() }
            if (count > 0) {
                HLog.i("$TAG 633 缝隙清线(710): ${list.javaClass.name}#${idName(list)} 清除 $count 个 Paint:$sb")
            } else {
                HLog.i("$TAG 633 缝隙清线(710): ${list.javaClass.name}#${idName(list)} 无低 alpha 黑色 Paint，未改动")
            }
        }
    }
    private fun collectSeamLists710(node: View, out: MutableList<View>, depth: Int) {
        if (depth > MAX_DEPTH) return
        val name = node.javaClass.name
        if (name == SEAM_LIST_CLASS || name.endsWith(".$SEAM_LIST_CLASS") || name.contains(SEAM_LIST_CLASS)) {
            out.add(node)
        }
        if (node is ViewGroup) {
            for (index in 0 until node.childCount) {
                node.getChildAt(index)?.let { collectSeamLists710(it, out, depth + 1) }
            }
        }
    }
    /**
     * 633：把「#000000 族、低 alpha」的列表自带 Paint 一次性清成透明，并用 PixelCopy 清前/清后自证。
     * 状态机：0→测清前→(300ms)→清 Paint→(500ms)→测清后→完成。全程最多 3 次清除。
     */
    private fun probeSeam633(activity: Activity, decor: ViewGroup, now: Long) {
        if (seamStage633 == 3) return
        if (seamStage633 > 0 && now < seamAt633) return
        if (seamStage633 == 0 && now - lastPagerScrollAt617 < 800L) return
        val pager = lastHomePager?.get() ?: return
        val pw = pager.width
        if (pw <= 0) return
        if (((pager.scrollX + pw / 2) / pw).coerceIn(0, 3) != 0) return
        val list = findViewByClassName(decor, "ConversationListView") ?: return
        when (seamStage633) {
            0 -> {
                seamStage633 = 1
                seamAt633 = now + 300L
                captureBand633(activity, 280, 16) { px, w, h ->
                    HLog.i("$TAG 清前缝隙实测(633): " + seamRows633(px, w, h, 280))
                }
            }
            1 -> {
                val targets = ArrayList<Pair<java.lang.reflect.Field, android.graphics.Paint>>()
                var cls: Class<*>? = list.javaClass
                var depth = 0
                while (cls != null && depth < 3) {
                    for (f in cls.declaredFields) {
                        if (!android.graphics.Paint::class.java.isAssignableFrom(f.type)) continue
                        runCatching {
                            f.isAccessible = true
                            val pt = f.get(list) as? android.graphics.Paint ?: return@runCatching
                            val c = pt.color
                            if (((c ushr 24) and 0xFF) in 1..80 && (c and 0xFFFFFF) == 0) targets.add(f to pt)
                        }
                    }
                    cls = cls.superclass
                    depth++
                }
                if (targets.isEmpty()) {
                    seamStage633 = 3
                    HLog.i("$TAG 列表自绘线颜色清除(633): 未找到 #000000 族低 alpha Paint，未改动")
                    return
                }
                seamClears633++
                val sb = StringBuilder()
                for ((f, pt) in targets) {
                    sb.append(" ").append(f.name).append("=#").append(Integer.toHexString(pt.color))
                        .append("/a").append(pt.alpha).append("/w").append(pt.strokeWidth)
                    runCatching { pt.color = 0 }
                    runCatching { pt.alpha = 0 }
                    sb.append("→#").append(Integer.toHexString(pt.color))
                }
                runCatching { list.invalidate() }
                HLog.i("$TAG 列表自绘线颜色清除(633) 第${seamClears633}次:$sb")
                if (seamClears633 >= 3) {
                    seamStage633 = 3
                } else {
                    seamStage633 = 2
                    seamAt633 = now + 500L
                }
            }
            else -> {
                // 2：复测 + 回读 Paint 是否被宿主写回
                captureBand633(activity, 280, 16) { px, w, h ->
                    HLog.i("$TAG 清后缝隙实测(633): " + seamRows633(px, w, h, 280))
                }
                val rb = StringBuilder()
                var cls: Class<*>? = list.javaClass
                var depth = 0
                while (cls != null && depth < 3) {
                    for (f in cls.declaredFields) {
                        if (!android.graphics.Paint::class.java.isAssignableFrom(f.type)) continue
                        runCatching {
                            f.isAccessible = true
                            val pt = f.get(list) as? android.graphics.Paint ?: return@runCatching
                            val c = pt.color
                            if (((c ushr 24) and 0xFF) in 1..200 && (c and 0xFFFFFF) == 0) {
                                rb.append(" ").append(f.name).append("=#").append(Integer.toHexString(c)).append("/a").append(pt.alpha)
                            }
                        }
                    }
                    cls = cls.superclass
                    depth++
                }
                HLog.i("$TAG 缝隙清除后 Paint 复读(633):$rb")
                seamStage633 = 3
            }
        }
    }

    private fun burstState634(decor: ViewGroup): String {
        val ab = findViewByClassName(decor, "ActionBarContainer") ?: return "标题栏=无"
        val loc = IntArray(2)
        runCatching { ab.getLocationInWindow(loc) }
        return "标题栏=bg=" + descDrawable(ab.background) + " vis=" + ab.visibility + " alpha=" + ab.alpha +
            " x=" + loc[0] + " y=" + loc[1] + " " + ab.width + "x" + ab.height +
            " 子=" + (ab as? ViewGroup)?.childCount +
            " 首子=" + (ab as? ViewGroup)?.getChildAt(0)?.javaClass?.simpleName
    }

    /** 634：定点抓 y=0..340（含状态栏+标题带），逐行算「近似纯白占比」，与当时的标题栏层状态一起落日志 */
    private fun burstCapture634(activity: Activity, from: Int, to: Int, seq: Int, delay: Long) {
        if (android.os.Build.VERSION.SDK_INT < 26) return
        val win = activity.window ?: return
        val decor = win.decorView as? ViewGroup ?: return
        val w = decor.width
        if (w <= 0) return
        val bandH = 340
        val bmp = runCatching { Bitmap.createBitmap(w, bandH, Bitmap.Config.ARGB_8888) }.getOrNull() ?: return
        val loc = IntArray(2)
        runCatching { decor.getLocationInWindow(loc) }
        val rect = android.graphics.Rect(loc[0], loc[1], loc[0] + w, loc[1] + bandH)
        runCatching {
            android.view.PixelCopy.request(win, rect, bmp, { r ->
                runCatching {
                    if (r == android.view.PixelCopy.SUCCESS) {
                        val px = IntArray(w * bandH)
                        bmp.getPixels(px, 0, w, 0, 0, w, bandH)
                        val means = IntArray(bandH)
                        val whites = IntArray(bandH)
                        for (y in 0 until bandH) {
                            var sum = 0.0
                            var wh = 0
                            for (x in 0 until w) {
                                val p = px[y * w + x]
                                val l = lumOf631(p)
                                sum += l
                                if (l >= 250.0 && ((p ushr 24) and 0xFF) >= 200) wh++
                            }
                            means[y] = (sum / w).toInt()
                            whites[y] = wh * 100 / w
                        }
                        var maxW = 0
                        var maxAt = -1
                        val lim = Math.min(300, bandH)
                        for (y in 139 until lim) if (whites[y] > maxW) { maxW = whites[y]; maxAt = y }
                        val bands = StringBuilder()
                        var i = 0
                        while (i < lim) {
                            if (whites[i] >= 60) {
                                val st = i
                                var peak = 0
                                while (i < lim && whites[i] >= 60) {
                                    peak = Math.max(peak, whites[i])
                                    i++
                                }
                                bands.append(" [y ").append(st).append("..").append(i - 1).append(" 白占比").append(peak).append("%]")
                            } else i++
                        }
                        HLog.i(
                            "$TAG 定点抓白(634) from=${from} to=${to} 第${seq}帧 延时=${delay}ms" +
                                " 标题带最大白占比=${maxW}%@y${maxAt} 白带=$bands" +
                                " y139=${means[139]} y200=${means.getOrElse(200) { -1 }} y287=${means.getOrElse(287) { -1 }}" +
                                " " + burstState634(decor)
                        )
                    } else {
                        HLog.i("$TAG 定点抓白(634) from=${from} to=${to} 第${seq}帧 延时=${delay}ms PixelCopy失败=$r")
                    }
                }
                bmp.recycle()
            }, android.os.Handler(android.os.Looper.getMainLooper()))
        }
    }

    private fun armBurst634(pager: ViewGroup, from: Int, to: Int) {
        val activity = lastHostActivity?.get() ?: return
        val decor = activity.window?.decorView as? ViewGroup ?: return
        if (burstArm634 >= 8) return
        burstArm634++
        HLog.i("$TAG 切页事件(634) 第${burstArm634}次 from=$from to=$to scrollX=${pager.scrollX} 页宽=${pager.width} " + burstState634(decor))
        val delays = longArrayOf(0L, 40L, 80L, 140L, 450L)
        for (k in delays.indices) {
            val d = delays[k]
            runCatching {
                decor.postDelayed({ runCatching { burstCapture634(activity, from, to, k + 1, d) } }, d)
            }
        }
    }

    private fun dumpActionBars634(decor: ViewGroup, pageIdx: Int) {
        val sb = StringBuilder()
        var n = 0
        val stack = ArrayDeque<View>()
        stack.addLast(decor)
        var guard = 0
        while (stack.isNotEmpty() && guard++ < 1500 && n < 12) {
            val v = stack.removeLast()
            if (v is ViewGroup) for (i in 0 until v.childCount) stack.addLast(v.getChildAt(i))
            val name = v.javaClass.simpleName
            if (!name.contains("ActionBar") && !name.contains("Toolbar")) continue
            n++
            val loc = IntArray(2)
            runCatching { v.getLocationInWindow(loc) }
            sb.append(" [").append(name).append("#").append(idName(v))
                .append(" x=").append(loc[0]).append(" y=").append(loc[1])
                .append(" ").append(v.width).append("x").append(v.height)
                .append(" vis=").append(v.visibility).append(" alpha=").append(v.alpha)
                .append(" bg=").append(descDrawable(v.background))
                .append(" 子=").append((v as? ViewGroup)?.childCount).append("]")
        }
        HLog.i("$TAG 各页标题栏(634) 页=$pageIdx 共=$n$sb")
    }

    private fun probeBars634(decor: ViewGroup, now: Long) {
        if (barsDump634 >= 6) return
        if (now - lastPagerScrollAt617 < 900L) return
        val pager = lastHomePager?.get() ?: return
        val pw = pager.width
        if (pw <= 0) return
        val idx = ((pager.scrollX + pw / 2) / pw).coerceIn(0, 3)
        if (idx == lastBarsIdx634) return
        lastBarsIdx634 = idx
        barsDump634++
        dumpActionBars634(decor, idx)
    }

    private fun isTitleBarView635(v: View): Boolean {
        val n = v.javaClass.simpleName
        return n.contains("ActionBar") || n.contains("Toolbar") || n.contains("TitleBar")
    }

    private fun isWhite635(color: Int): Boolean {
        val a = (color ushr 24) and 0xFF
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF
        return a >= 200 && r >= 235 && g >= 235 && b >= 235
    }

    private fun burstState635(decor: ViewGroup): String {
        val ab = findViewByClassName(decor, "ActionBarContainer") ?: return "标题栏=无"
        val loc = IntArray(2)
        runCatching { ab.getLocationInWindow(loc) }
        val sb = StringBuilder()
        sb.append("标题栏=bg=").append(descDrawable(ab.background))
            .append(" vis=").append(ab.visibility).append(" alpha=").append(ab.alpha)
            .append(" x=").append(loc[0]).append(" y=").append(loc[1])
            .append(" ").append(ab.width).append("x").append(ab.height)
        val g = ab as? ViewGroup
        if (g != null) {
            for (i in 0 until Math.min(g.childCount, 5)) {
                val c = g.getChildAt(i)
                sb.append(" [子").append(i).append(" ").append(c.javaClass.simpleName)
                    .append(" ").append(c.width).append("x").append(c.height)
                    .append(" bg=").append(descDrawable(c.background))
                    .append(" vis=").append(c.visibility).append(" a=").append(c.alpha)
                if (c is android.widget.TextView) {
                    sb.append(" text=").append(c.text).append(" tc=#").append(Integer.toHexString(c.currentTextColor))
                }
                sb.append("]")
            }
        }
        return sb.toString()
    }

    /** 635：y=0..340 定点抓帧，逐行算纯白占比，并同帧记录标题栏子树状态 */
    private fun burstCapture635(activity: Activity, from: Int, to: Int, seq: Int, delay: Long, why: String) {
        if (android.os.Build.VERSION.SDK_INT < 26) return
        val win = activity.window ?: return
        val decor = win.decorView as? ViewGroup ?: return
        val w = decor.width
        if (w <= 0) return
        val bandH = 340
        val bmp = runCatching { Bitmap.createBitmap(w, bandH, Bitmap.Config.ARGB_8888) }.getOrNull() ?: return
        val loc = IntArray(2)
        runCatching { decor.getLocationInWindow(loc) }
        val rect = android.graphics.Rect(loc[0], loc[1], loc[0] + w, loc[1] + bandH)
        runCatching {
            android.view.PixelCopy.request(win, rect, bmp, { r ->
                runCatching {
                    if (r == android.view.PixelCopy.SUCCESS) {
                        val px = IntArray(w * bandH)
                        bmp.getPixels(px, 0, w, 0, 0, w, bandH)
                        val means = IntArray(bandH)
                        val whites = IntArray(bandH)
                        for (y in 0 until bandH) {
                            var sum = 0.0
                            var wh = 0
                            for (x in 0 until w) {
                                val p = px[y * w + x]
                                val l = lumOf631(p)
                                sum += l
                                if (l >= 250.0 && ((p ushr 24) and 0xFF) >= 200) wh++
                            }
                            means[y] = (sum / w).toInt()
                            whites[y] = wh * 100 / w
                        }
                        var maxW = 0
                        var maxAt = -1
                        val lim = Math.min(300, bandH)
                        for (y in 139 until lim) if (whites[y] > maxW) { maxW = whites[y]; maxAt = y }
                        val bands = StringBuilder()
                        var i = 0
                        while (i < lim) {
                            if (whites[i] >= 60) {
                                val st = i
                                var peak = 0
                                while (i < lim && whites[i] >= 60) {
                                    peak = Math.max(peak, whites[i])
                                    i++
                                }
                                bands.append(" [y ").append(st).append("..").append(i - 1).append(" 白占比").append(peak).append("%]")
                            } else i++
                        }
                        var sMaxW = 0
                        var sMaxAt = -1
                        for (y in 0 until Math.min(139, bandH)) if (whites[y] > sMaxW) { sMaxW = whites[y]; sMaxAt = y }
                        HLog.i(
                            "$TAG 定点抓白(636) [$why] from=${from} to=${to} 第${seq}帧 延时=${delay}ms" +
                                " 状态带最大白占比=${sMaxW}%@y${sMaxAt}" +
                                " 标题带最大白占比=${maxW}%@y${maxAt} 白带=$bands" +
                                " y139=${means[139]} y287=${means.getOrElse(287) { -1 }}" +
                                " 白写入已拦=${titleWhiteBlocked635} " + burstState635(decor) + " | " + statusState636(decor)
                        )
                    } else {
                        HLog.i("$TAG 定点抓白(635) [$why] 第${seq}帧 延时=${delay}ms PixelCopy失败=$r")
                    }
                }
                bmp.recycle()
            }, android.os.Handler(android.os.Looper.getMainLooper()))
        }
    }

    private fun armBurst635(pager: ViewGroup, from: Int, to: Int, why: String) {
        val activity = lastHostActivity?.get() ?: return
        val decor = activity.window?.decorView as? ViewGroup ?: return
        if (burstArm635 >= 6) return
        burstArm635++
        HLog.i("$TAG 定点抓白(635) 第${burstArm635}次触发[$why] from=$from to=$to scrollX=${pager.scrollX} " + burstState635(decor))
        val delays = longArrayOf(0L, 16L, 32L, 48L, 64L, 96L, 140L, 220L)
        for (k in delays.indices) {
            val d = delays[k]
            runCatching {
                decor.postDelayed({ runCatching { burstCapture635(activity, from, to, k + 1, d, why) } }, d)
            }
        }
    }

    private fun isStatusBarView636(v: View): Boolean {
        val idn = runCatching { idName(v) }.getOrDefault("").lowercase()
        if (idn.contains("statusbar") || idn.contains("status_bar") || idn.contains("statusbarbackground")) return true
        val cls = v.javaClass.simpleName
        if (cls.contains("StatusBar")) return true
        if (v.height <= 0 || v.width < 600) return false
        val loc = IntArray(2)
        runCatching { v.getLocationInWindow(loc) }
        val top = loc[1]
        val bottom = top + v.height
        return top <= 8 && bottom in 60..200
    }

    private fun statusState636(decor: ViewGroup): String {
        var best: View? = null
        val stack = ArrayDeque<View>()
        stack.addLast(decor)
        var guard = 0
        while (stack.isNotEmpty() && guard++ < 1500) {
            val v = stack.removeLast()
            if (v is ViewGroup) for (i in 0 until v.childCount) stack.addLast(v.getChildAt(i))
            val idn = runCatching { idName(v) }.getOrDefault("").lowercase()
            if (idn.contains("statusbar") || idn.contains("status_bar")) { best = v; break }
        }
        val v = best ?: return "状态条=未找到"
        val loc = IntArray(2)
        runCatching { v.getLocationInWindow(loc) }
        return "状态条=" + v.javaClass.simpleName + "#" + idName(v) +
            " y=" + loc[1] + ".." + (loc[1] + v.height) + " " + v.width + "x" + v.height +
            " bg=" + descDrawable(v.background) + " vis=" + v.visibility + " alpha=" + v.alpha +
            " 已拦=" + statusWhiteBlocked636
    }

    private fun findPanelRoot637(decor: ViewGroup): ViewGroup? {
        var best: ViewGroup? = null
        var bestArea = 0L
        val stack = ArrayDeque<View>()
        stack.addLast(decor)
        var guard = 0
        while (stack.isNotEmpty() && guard++ < 1500) {
            val v = stack.removeLast()
            if (v is ViewGroup) for (i in 0 until v.childCount) stack.addLast(v.getChildAt(i))
            if (v !is ViewGroup) continue
            val name = v.javaClass.simpleName
            if (!name.contains("AppBrand")) continue
            if (!v.isShown || v.width < 600 || v.height < 600) continue
            val area = v.width.toLong() * v.height
            if (area > bestArea) { bestArea = area; best = v }
        }
        return best
    }

    private fun probePanelBottom637(decor: ViewGroup, now: Long) {
        if (panelProbe637 >= 3) return
        if (now - lastPanelProbeAt637 < 3000L) return
        val panel = findPanelRoot637(decor) ?: return
        panelProbe637++
        lastPanelProbeAt637 = now
        val sb = StringBuilder()
        var cleared = 0
        var hits = 0
        val stack = ArrayDeque<View>()
        stack.addLast(panel)
        var guard = 0
        while (stack.isNotEmpty() && guard++ < 2000) {
            val v = stack.removeLast()
            if (v is ViewGroup) for (i in 0 until v.childCount) stack.addLast(v.getChildAt(i))
            val loc = IntArray(2)
            runCatching { v.getLocationInWindow(loc) }
            val top = loc[1]
            val h = v.height
            if (h <= 0) continue
            val bottom = top + h
            if (bottom < 2150 || top > 2660) continue
            hits++
            val bg = v.background
            val isTab = v.javaClass.simpleName.contains("BottomTab")
            if (hits <= 10) {
                sb.append(" [").append(v.javaClass.simpleName).append("#").append(idName(v))
                    .append(" y=").append(top).append("..").append(bottom)
                    .append(" ").append(v.width).append("x").append(h)
                    .append(" bg=").append(descDrawable(bg))
                    .append(" a=").append(v.alpha).append(" vis=").append(v.visibility)
                    .append(" hw=").append(v.isHardwareAccelerated).append("]")
            }
            if (!isTab && v.width >= 600 && bg is ColorDrawable) {
                val c = bg.color
                val a = (c ushr 24) and 0xFF
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                if (a in 1..250 && r >= 240 && g >= 240 && b >= 240) {
                    runCatching { v.setBackgroundColor(Color.TRANSPARENT) }
                    cleared++
                }
            }
        }
        HLog.i("$TAG 面板底部透视(637) 第${panelProbe637}次 面板=${panel.javaClass.simpleName} 命中=$hits 清=$cleared$sb")
    }

    private fun walkBand638(
        v: View, inTab: Boolean, sb: StringBuilder, counter: IntArray, cleared: IntArray
    ) {
        val name = v.javaClass.simpleName
        val tabNow = inTab || name.contains("BottomTab")
        val loc = IntArray(2)
        runCatching { v.getLocationInWindow(loc) }
        val top = loc[1]
        val h = v.height
        if (h > 0) {
            val bottom = top + h
            if (bottom >= 2500 && top <= 2700) {
                counter[0]++
                val bg = v.background
                if (counter[0] <= 12) {
                    sb.append(" [").append(name).append("#").append(idName(v))
                        .append(" y=").append(top).append("..").append(bottom)
                        .append(" ").append(v.width).append("x").append(h)
                        .append(" bg=").append(descDrawable(bg))
                        .append(" a=").append(v.alpha).append(" vis=").append(v.visibility)
                        .append(if (tabNow) " TAB" else "").append("]")
                }
                if (!tabNow && v.width >= 600 && v.visibility == View.VISIBLE) {
                    var white = false
                    if (bg is ColorDrawable) {
                        val c = bg.color
                        val al = (c ushr 24) and 0xFF
                        val r = (c shr 16) and 0xFF
                        val g = (c shr 8) and 0xFF
                        val b = c and 0xFF
                        white = r >= 240 && g >= 240 && b >= 240
                        if (white && al in 1..250) {
                            runCatching { v.setBackgroundColor(Color.TRANSPARENT) }
                            cleared[0]++
                        } else if (white && al >= 251 && v.alpha < 0.99f) {
                            // 白底 + View 级半透明 = 实际的半透明白层
                            runCatching { v.setBackgroundColor(Color.TRANSPARENT) }
                            cleared[0]++
                        }
                    }
                    if (!white && v.alpha < 0.99f && v.foreground is ColorDrawable) {
                        val fc = (v.foreground as ColorDrawable).color
                        val fa = (fc ushr 24) and 0xFF
                        var fn = true
                        if (fa in 1..250) {
                            runCatching { v.foreground = null }
                            cleared[0]++
                        }
                    }
                }
            }
        }
        if (v is ViewGroup) for (i in 0 until v.childCount) walkBand638(v.getChildAt(i), tabNow, sb, counter, cleared)
    }

    private fun probeBottomBand638(decor: ViewGroup, now: Long) {
        if (bandProbe638 >= 3) return
        if (now - lastBand638 < 3000L) return
        if (findPanelRoot637(decor) == null) return
        bandProbe638++
        lastBand638 = now
        val sb = StringBuilder()
        val counter = IntArray(1)
        val cleared = IntArray(1)
        runCatching { walkBand638(decor, false, sb, counter, cleared) }
        HLog.i("$TAG 底部带透视(638) 第${bandProbe638}次 命中=${counter[0]} 清=${cleared[0]}$sb")
    }

    private fun staticLoc639(v: View, out: IntArray) {
        var x = 0
        var y = 0
        var cur: View = v
        var guard = 0
        while (guard++ < 32) {
            x += cur.left
            y += cur.top
            val p = cur.parent as? View ?: break
            x -= p.scrollX
            y -= p.scrollY
            cur = p
        }
        out[0] = x
        out[1] = y
    }

    private fun syncPanelWallpaper639(decor: ViewGroup, now: Long) {
        if (panelSync639 >= 3) return
        if (now - lastPanelSync639 < 2500L) return
        val panel = findPanelRoot637(decor) ?: return
        val loc = IntArray(2)
        val st = IntArray(2)
        runCatching { panel.getLocationInWindow(loc) }
        staticLoc639(panel, st)
        val drift = maxOf(Math.abs(loc[0] - st[0]), Math.abs(loc[1] - st[1]))
        if (drift > 2) return
        panelSync639++
        lastPanelSync639 = now
        var records = 0
        var bandInfo = 0
        val sb = StringBuilder()
        val stack = ArrayDeque<View>()
        stack.addLast(panel)
        var guard = 0
        while (stack.isNotEmpty() && guard++ < 2000) {
            val v = stack.removeLast()
            if (v is ViewGroup) for (i in 0 until v.childCount) stack.addLast(v.getChildAt(i))
            val bg = v.background
            if (bg is ScreenAnchoredBitmapDrawable) {
                if (bg.forceReRecord()) records++
                if (bandInfo < 8) {
                    bandInfo++
                    sb.append(" [锚定底图 ").append(v.javaClass.simpleName).append("#").append(idName(v))
                        .append(" ").append(v.width).append("x").append(v.height)
                        .append(" ").append(bg.snapshot()).append("]")
                }
            }
            val l2 = IntArray(2)
            runCatching { v.getLocationInWindow(l2) }
            val h = v.height
            // 639：不管这一层挂的是什么 Drawable，只要它落在底部这条带里，落定后都重录一次。
            // 错位帧是「手掌手势中途录制」的产物，重录（= 切后台重开走的同一条路）才会把
            // 录进 display list 的 T(-static) 换成落定位置。
            if (h in 1..400 && (l2[1] + h) >= 2400 && l2[1] <= 2722) {
                runCatching { v.invalidate() }
                records++
            }
            if (h in 1..400 && (l2[1] + h) >= 2500 && l2[1] <= 2700 && bandInfo < 8) {
                bandInfo++
                sb.append(" [带 ").append(v.javaClass.simpleName).append("#").append(idName(v))
                    .append(" y=").append(l2[1]).append("..").append(l2[1] + h)
                    .append(" ").append(v.width).append("x").append(h)
                    .append(" bg=").append(descDrawable(bg)).append("]")
            }
        }
        HLog.i(
            "$TAG 面板壁纸重录(639) 第${panelSync639}次 面板=${panel.javaClass.simpleName}" +
                " loc=${loc[0]},${loc[1]} static=${st[0]},${st[1]} drift=$drift 重录=$records$sb"
        )
    }

    /** 640：只认「真的在屏幕上」的面板实例（639 抓到的是 y=-2434 的未展开实例）。 */
    private fun findPanelOnScreen640(decor: ViewGroup): ViewGroup? {
        var best: ViewGroup? = null
        var bestArea = 0L
        val stack = ArrayDeque<View>()
        stack.addLast(decor)
        var guard = 0
        val screenH = decor.height.coerceAtLeast(1)
        while (stack.isNotEmpty() && guard++ < 1500) {
            val v = stack.removeLast()
            if (v is ViewGroup) for (i in 0 until v.childCount) stack.addLast(v.getChildAt(i))
            if (v !is ViewGroup) continue
            if (!v.javaClass.simpleName.contains("AppBrand")) continue
            if (!v.isShown || v.width < 600 || v.height < 600) continue
            val loc = IntArray(2)
            runCatching { v.getLocationInWindow(loc) }
            // 642：-2434..288 这种「只露出屏幕顶部 288px」的未展开实例必须排除——
            // 641 就是被它骗了（日志：面板y=-2434..288 摘=0）。
            val visTop = maxOf(loc[1], 0)
            val visBottom = minOf(loc[1] + v.height, screenH)
            if (visBottom - visTop < v.height * 6 / 10) continue
            val area = v.width.toLong() * v.height
            if (area > bestArea) { bestArea = area; best = v }
        }
        return best
    }

    private fun renderBand640(v: View, bandTop: Int, w: Int, hBand: Int): IntArray? {
        val bmp = runCatching { Bitmap.createBitmap(w, hBand, Bitmap.Config.ARGB_8888) }.getOrNull() ?: return null
        bmp.eraseColor(0)
        val cv = Canvas(bmp)
        val loc = IntArray(2)
        runCatching { v.getLocationInWindow(loc) }
        cv.save()
        cv.translate(-loc[0].toFloat(), -bandTop.toFloat())
        runCatching { v.draw(cv) }
        cv.restore()
        val px = IntArray(w * hBand)
        bmp.getPixels(px, 0, w, 0, 0, w, hBand)
        bmp.recycle()
        return px
    }

    private fun probeBandSource640(activity: Activity, decor: ViewGroup, now: Long) {
        if (band640 >= 3) return
        if (now - last640 < 3000L) return
        if (findPanelOnScreen640(decor) == null) return
        val w = decor.width
        val bandTop = 2440
        val hBand = decor.height - bandTop
        if (w <= 0 || hBand <= 40) return
        band640++
        last640 = now
        // 候选：宽 ≥600、高度 40..900、窗口矩形与这条带相交的 View（底栏/浮层这类小容器优先）
        val cands = ArrayList<View>()
        val stack = ArrayDeque<View>()
        stack.addLast(decor)
        var guard = 0
        while (stack.isNotEmpty() && guard++ < 2000 && cands.size < 10) {
            val v = stack.removeLast()
            if (v is ViewGroup) for (i in 0 until v.childCount) stack.addLast(v.getChildAt(i))
            if (v === decor) continue
            val loc = IntArray(2)
            runCatching { v.getLocationInWindow(loc) }
            val h = v.height
            if (w <= 0 || v.width < 600) continue
            if (h < 40 || h > 900) continue
            if (loc[1] + h < bandTop || loc[1] > decor.height) continue
            cands.add(v)
        }
        val candInfo = StringBuilder()
        captureBand633(activity, bandTop, hBand) { real, rw, rh ->
            val n = minOf(rw, w)
            var bestDesc = "无"
            var bestDiff = Double.MAX_VALUE
            for (v in cands) {
                val px = renderBand640(v, bandTop, n, rh) ?: continue
                var sum = 0.0
                var cnt = 0
                for (y in 0 until rh step 4) {
                    for (x in 0 until n step 8) {
                        val i = y * n + x
                        sum += Math.abs(lumOf631(px[i]) - lumOf631(real[i]))
                        cnt++
                    }
                }
                if (cnt == 0) continue
                val diff = sum / cnt
                val loc = IntArray(2)
                runCatching { v.getLocationInWindow(loc) }
                if (diff < bestDiff) { bestDiff = diff; bestDesc = "${v.javaClass.simpleName}#${idName(v)} y=${loc[1]}..${loc[1] + v.height}" }
                candInfo.append(" [").append(v.javaClass.simpleName).append("#").append(idName(v))
                    .append(" y=").append(loc[1]).append("..").append(loc[1] + v.height)
                    .append(" ").append(v.width).append("x").append(v.height)
                    .append(" bg=").append(descDrawable(v.background))
                    .append(" 差=").append(String.format("%.1f", diff)).append("]")
            }
            HLog.i(
                "$TAG 带内归属(640) 第${band640}次 带=y${bandTop}..${bandTop + rh} 候选=${cands.size}" +
                    " 最匹配=$bestDesc 差=${String.format("%.1f", bestDiff)}$candInfo"
            )
        }
    }

    /** 640：一次性摘掉带内那段「重复的锚定壁纸」，让 decor 上唯一正确的那层透出来。 */
    private fun dropDuplicateWallpaper640(decor: ViewGroup, now: Long) {
        if (drop640 >= 3) return
        if (now - lastDrop640 < 3000L) return
        if (findPanelOnScreen640(decor) == null) return
        val screenH = decor.height
        drop640++
        lastDrop640 = now
        var removed = 0
        val sb = StringBuilder()
        val stack = ArrayDeque<View>()
        stack.addLast(decor)
        var guard = 0
        while (stack.isNotEmpty() && guard++ < 2000) {
            val v = stack.removeLast()
            if (v is ViewGroup) for (i in 0 until v.childCount) stack.addLast(v.getChildAt(i))
            if (v === decor) continue
            if (v.width < 600) continue
            val h = v.height
            if (h < 1 || h > 600) continue
            val loc = IntArray(2)
            runCatching { v.getLocationInWindow(loc) }
            if (loc[1] + h < 2400 || loc[1] > screenH) continue
            if (v.background !is ScreenAnchoredBitmapDrawable) continue
            runCatching { v.background = null }
            removed++
            if (removed <= 4) sb.append(" [").append(v.javaClass.simpleName).append("#").append(idName(v))
                .append(" y=").append(loc[1]).append("..").append(loc[1] + h).append("]")
        }
        HLog.i("$TAG 面板底部重复壁纸(640) 第${drop640}次 移除=$removed$sb")
    }

    /** 641：把面板子树里那份「跟着面板一起位移」的锚定壁纸摘掉，露出 decor 上锚在窗口的那张。 */
    private fun dropPanelWallpaper641(decor: ViewGroup, now: Long) {
        if (panelDrop641 >= 3) return
        if (now - lastPanelDrop641 < 3000L) return
        val panel = findPanelOnScreen640(decor) ?: return
        panelDrop641++
        lastPanelDrop641 = now
        val ploc = IntArray(2)
        runCatching { panel.getLocationInWindow(ploc) }
        var removed = 0
        val sb = StringBuilder()
        val stack = ArrayDeque<View>()
        stack.addLast(panel)
        var guard = 0
        while (stack.isNotEmpty() && guard++ < 1500) {
            val v = stack.removeLast()
            if (v is ViewGroup) for (i in 0 until v.childCount) stack.addLast(v.getChildAt(i))
            val bg = v.background
            if (bg !is ScreenAnchoredBitmapDrawable) continue
            val loc = IntArray(2)
            runCatching { v.getLocationInWindow(loc) }
            val info = bg.snapshot()
            runCatching { v.background = null }
            removed++
            if (removed <= 5) sb.append(" [").append(v.javaClass.simpleName).append("#").append(idName(v))
                .append(" y=").append(loc[1]).append("..").append(loc[1] + v.height)
                .append(" ").append(v.width).append("x").append(v.height)
                .append(" ").append(info)
                .append(" 相对面板=").append(loc[1] - ploc[1]).append("]")
        }
        HLog.i(
            "$TAG 面板锚定壁纸已摘(641) 第${panelDrop641}次 面板=${panel.javaClass.simpleName}" +
                " 面板y=${ploc[1]}..${ploc[1] + panel.height} 面板bg=${descDrawable(panel.background)} 摘=$removed$sb"
        )
    }

    private fun bandRect642(v: View, screenH: Int): Boolean {
        val loc = IntArray(2)
        runCatching { v.getLocationInWindow(loc) }
        return loc[1] + v.height >= 2400 && loc[1] <= screenH
    }

    private fun collectCands642(panel: ViewGroup, decor: ViewGroup): Int {
        chainCands642.clear()
        chainSaved642.clear()
        val stack = ArrayDeque<View>()
        stack.addLast(panel)
        var guard = 0
        while (stack.isNotEmpty() && guard++ < 1500 && chainCands642.size < 6) {
            val v = stack.removeLast()
            if (v is ViewGroup) for (i in 0 until v.childCount) stack.addLast(v.getChildAt(i))
            if (v === decor || v.background == null) continue
            if (v.width < 300 || !bandRect642(v, decor.height)) continue
            chainCands642.add(v)
        }
        val stack2 = ArrayDeque<View>()
        stack2.addLast(decor)
        guard = 0
        while (stack2.isNotEmpty() && guard++ < 2000 && chainCands642.size < 8) {
            val v = stack2.removeLast()
            if (v is ViewGroup) for (i in 0 until v.childCount) stack2.addLast(v.getChildAt(i))
            if (v === decor || v === panel) continue
            if (chainCands642.contains(v)) continue
            if (v.width < 300 || v.height < 1 || v.height > 400) continue
            if (v.background == null || !bandRect642(v, decor.height)) continue
            chainCands642.add(v)
        }
        return chainCands642.size
    }

    private fun meanDiff642(a: IntArray, b: IntArray, w: Int, h: Int, rowsFrom: Int, rowsTo: Int = -1): Double {
        var sum = 0.0
        var n = 0
        val end = if (rowsTo in 0 until h) rowsTo else h
        for (y in rowsFrom until end step 3) {
            for (x in 0 until w step 6) {
                val i = y * w + x
                if (i >= a.size || i >= b.size) continue
                sum += Math.abs(lumOf631(a[i]) - lumOf631(b[i]))
                n++
            }
        }
        return if (n == 0) 0.0 else sum / n
    }

    private fun startChain642(activity: Activity, decor: ViewGroup, now: Long) {
        if (chain642) return
        if (now - lastChain642 < 3000L) return
        val panel = findPanelOnScreen640(decor) ?: return
        lastChain642 = now
        val n = collectCands642(panel, decor)
        val ploc = IntArray(2)
        runCatching { panel.getLocationInWindow(ploc) }
        val sb = StringBuilder()
        for (v in chainCands642) {
            val loc = IntArray(2)
            runCatching { v.getLocationInWindow(loc) }
            sb.append(" [").append(v.javaClass.simpleName).append("#").append(idName(v))
                .append(" y=").append(loc[1]).append("..").append(loc[1] + v.height)
                .append(" bg=").append(descDrawable(v.background)).append("]")
        }
        HLog.i(
            "$TAG 带内探层(642) 开始 面板=${panel.javaClass.simpleName} y=${ploc[1]}..${ploc[1] + panel.height}" +
                " 候选=$n$sb"
        )
        if (n == 0 || decor.height - chainTop642 < 40) return
        chain642 = true
        chainIdx642 = 0
        kept642 = 0
        captureBand633(activity, chainTop642, decor.height - chainTop642) { px, w, h ->
            chainBase642 = px
            chainW642 = w
            chainH642 = h
            stepChain642(activity, decor)
        }
    }

    private fun stepChain642(activity: Activity, decor: ViewGroup) {
        if (chainIdx642 >= chainCands642.size) {
            chain642 = false
            HLog.i("$TAG 带内探层(642) 结束 已测=${chainCands642.size} 永久摘除=$kept642")
            return
        }
        val v = chainCands642[chainIdx642]
        val bg = v.background
        if (bg == null) {
            chainIdx642++
            stepChain642(activity, decor)
            return
        }
        chainSaved642[v] = bg
        runCatching { v.background = null }
        val loc = IntArray(2)
        runCatching { v.getLocationInWindow(loc) }
        val label = "${v.javaClass.simpleName}#${idName(v)} y=${loc[1]}..${loc[1] + v.height} ${v.width}x${v.height}"
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            captureBand633(activity, chainTop642, chainH642) { px, w, h ->
                val base = chainBase642
                val rowsFrom = maxOf(0, 2500 - chainTop642)
                val diff = if (base != null && base.size >= px.size) meanDiff642(base, px, w, h, rowsFrom) else -1.0
                val keep = diff > 3.0
                if (keep) {
                    kept642++
                } else {
                    runCatching { v.background = chainSaved642[v] }
                }
                HLog.i(
                    "$TAG 带内探层(642) [${chainIdx642 + 1}/${chainCands642.size}] $label" +
                        " bg=${descDrawable(chainSaved642[v])} 带内差=${String.format("%.2f", diff)}" +
                        if (keep) " 命中→永久摘除" else " 无贡献→已还原"
                )
                chainIdx642++
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
                    { stepChain642(activity, decor) }, 60
                )
            }
        }, 140)
    }

    private class Match643(val coverage: Double, val shift: Int, val err: Double)

    private fun renderBand643(v: View, bandTop: Int, w: Int, hBand: Int): IntArray? {
        val bmp = runCatching { Bitmap.createBitmap(w, hBand, Bitmap.Config.ARGB_8888) }.getOrNull() ?: return null
        bmp.eraseColor(0)
        val cv = Canvas(bmp)
        val loc = IntArray(2)
        runCatching { v.getLocationInWindow(loc) }
        cv.save()
        cv.translate(-loc[0].toFloat(), -bandTop.toFloat())
        runCatching { v.draw(cv) }
        cv.restore()
        val px = IntArray(w * hBand)
        bmp.getPixels(px, 0, w, 0, 0, w, hBand)
        bmp.recycle()
        return px
    }

    /** 只在候选自己画过的像素上比，并搜索最佳纵向位移（正 = 候选内容来自更下方的真帧行）。 */
    private fun shiftMatch643(cand: IntArray, real: IntArray, w: Int, h: Int, rowsFrom: Int): Match643 {
        var cover = 0
        var total = 0
        for (y in rowsFrom until h step 4) {
            for (x in 0 until w step 8) {
                total++
                if ((cand.getOrElse(y * w + x) { 0 } ushr 24) >= 128) cover++
            }
        }
        val cov = if (total == 0) 0.0 else cover.toDouble() / total
        if (cov < 0.25) return Match643(cov, 0, -1.0)
        var bestS = 0
        var bestE = Double.MAX_VALUE
        for (sh in -260..260 step 2) {
            var sum = 0.0
            var n = 0
            for (y in rowsFrom until h step 4) {
                val yy = y + sh
                if (yy < 0 || yy >= h) continue
                for (x in 0 until w step 8) {
                    val i = y * w + x
                    if ((cand.getOrElse(i) { 0 } ushr 24) < 128) continue
                    sum += Math.abs(lumOf631(cand[i]) - lumOf631(real[yy * w + x]))
                    n++
                }
            }
            if (n < 200) continue
            val e = sum / n
            if (e < bestE) { bestE = e; bestS = sh }
        }
        return Match643(cov, bestS, if (bestE == Double.MAX_VALUE) -1.0 else bestE)
    }

    private fun looksLikeBgView643(name: String): Boolean =
        name.contains("Dynamic") || name.contains("Gradient") || name.contains("Background") ||
            name.contains("Wallpaper") || name.contains("Bg")

    private fun dumpWindow643(decor: ViewGroup, panel: ViewGroup) {
        val sb = StringBuilder()
        sb.append(" decor=").append(decor.width).append("x").append(decor.height)
        sb.append(" 面板=").append(panel.width).append("x").append(panel.height)
        sb.append(" 差高=").append(decor.height - panel.height)
        runCatching {
            val vr = Class.forName("android.view.ViewRootImpl")
            val f = vr.getDeclaredField("mWinFrame").apply { isAccessible = true }
            val r = f.get(decor.parent) as android.graphics.Rect
            sb.append(" winFrame=").append(r.left).append(",").append(r.top).append(",")
                .append(r.right).append(",").append(r.bottom).append(" 高=").append(r.height())
        }
        val stack = ArrayDeque<View>()
        stack.addLast(decor)
        var guard = 0
        while (stack.isNotEmpty() && guard++ < 2000) {
            val v = stack.removeLast()
            if (v is ViewGroup) for (i in 0 until v.childCount) stack.addLast(v.getChildAt(i))
            val loc = IntArray(2)
            runCatching { v.getLocationInWindow(loc) }
            if (v is android.view.SurfaceView) {
                sb.append(" [SurfaceView ").append(v.javaClass.simpleName).append(" y=").append(loc[1])
                    .append("..").append(loc[1] + v.height).append(" valid=")
                    .append(runCatching { v.holder.surface.isValid }.getOrDefault(false)).append("]")
            }
            if (v is android.view.TextureView) {
                sb.append(" [TextureView ").append(v.javaClass.simpleName).append(" y=").append(loc[1])
                    .append("..").append(loc[1] + v.height).append(" avail=")
                    .append(runCatching { v.isAvailable }.getOrDefault(false)).append("]")
            }
            val bg = v.background
            if (bg is ScreenAnchoredBitmapDrawable) {
                sb.append(" [锚定 ").append(v.javaClass.simpleName).append("#").append(idName(v))
                    .append(" y=").append(loc[1]).append("..").append(loc[1] + v.height)
                    .append(" ").append(bg.snapshot()).append("]")
            }
        }
        HLog.i("$TAG 窗口图层(643)$sb")
    }

    private fun startProbe643(activity: Activity, decor: ViewGroup, now: Long) {
        if (p643Runs >= 1) return
        if (now - last643 < 3000L) return
        val panel = findPanelOnScreen640(decor) ?: return
        val loc = IntArray(2)
        runCatching { panel.getLocationInWindow(loc) }
        val st = IntArray(2)
        staticLoc639(panel, st)
        val drift = maxOf(Math.abs(loc[0] - st[0]), Math.abs(loc[1] - st[1]))
        if (drift > 2) return
        if (decor.height - top643 < 40) return
        last643 = now
        captureBand633(activity, top643, decor.height - top643) { px, w, h ->
            frameA643 = px
            frameW643 = w
            frameH643 = h
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                captureBand633(activity, top643, decor.height - top643) { px2, w2, h2 ->
                    val rowsFrom = maxOf(0, 2500 - top643)
                    val stable = meanDiff642(px, px2, w2, h2, rowsFrom)
                    HLog.i(
                        "$TAG 基线稳定(643) 连抓两帧带内差=${String.format("%.2f", stable)}" +
                            " 面板drift=$drift 面板y=${loc[1]}..${loc[1] + panel.height}"
                    )
                    if (stable <= 1.0) {
                        p643Runs++
                        attribute643(decor, panel, px2, w2, h2, rowsFrom)
                    }
                }
            }, 120)
        }
    }

    private fun attribute643(decor: ViewGroup, panel: ViewGroup, real: IntArray, w: Int, h: Int, rowsFrom: Int) {
        val cands = ArrayList<View>()
        val stack = ArrayDeque<View>()
        stack.addLast(decor)
        var guard = 0
        while (stack.isNotEmpty() && guard++ < 2500 && cands.size < 8) {
            val v = stack.removeLast()
            if (v is ViewGroup) for (i in 0 until v.childCount) stack.addLast(v.getChildAt(i))
            if (v === decor || v.width < 300) continue
            val loc = IntArray(2)
            runCatching { v.getLocationInWindow(loc) }
            if (loc[1] + v.height < 2400 || loc[1] > decor.height) continue
            val bg = v.background
            val interesting = bg is ScreenAnchoredBitmapDrawable ||
                (bg != null && bg.javaClass.simpleName.contains("Wallpaper")) ||
                looksLikeBgView643(v.javaClass.simpleName)
            if (!interesting) continue
            cands.add(v)
        }
        for (v in cands) {
            val px = renderBand643(v, top643, w, h) ?: continue
            val m = shiftMatch643(px, real, w, h, rowsFrom)
            val loc = IntArray(2)
            runCatching { v.getLocationInWindow(loc) }
            HLog.i(
                "$TAG 位移匹配(643) ${v.javaClass.simpleName}#${idName(v)} y=${loc[1]}..${loc[1] + v.height}" +
                    " ${v.width}x${v.height} bg=" + descDrawable(v.background) +
                    " 覆盖=" + String.format("%.2f", m.coverage) +
                    " 最佳位移=" + m.shift + " 误差=" + String.format("%.2f", m.err)
            )
            if (m.coverage >= 0.5 && m.err in 0.0..6.0 && Math.abs(m.shift) in 20..400) {
                HLog.i(
                    "$TAG 位移匹配(643) ↑ 这层在带内画了东西且错位 ${m.shift}px（应有位移=0）" +
                        " ${v.javaClass.simpleName}#${idName(v)}"
                )
            }
        }
        dumpWindow643(decor, panel)
    }

    private fun selfShift644(px: IntArray, w: Int, h: Int, top: Int): DoubleArray {
        val b0 = maxOf(0, bandTop644 - top)
        var best = 0.0
        var bestE = Double.MAX_VALUE
        var secondE = Double.MAX_VALUE
        var sh = -300
        while (sh <= 300) {
            var sum = 0.0
            var n = 0
            var y = b0
            while (y < h) {
                val yy = y - sh
                if (yy >= 0 && yy < h) {
                    var x = 0
                    while (x < w) {
                        sum += Math.abs(lumOf631(px[y * w + x]) - lumOf631(px[yy * w + x]))
                        n++
                        x += 8
                    }
                }
                y += 4
            }
            if (n >= 500) {
                val e = sum / n
                if (e < bestE) {
                    secondE = bestE
                    bestE = e
                    best = sh.toDouble()
                } else if (e < secondE) {
                    secondE = e
                }
            }
            sh += 2
        }
        return doubleArrayOf(best, if (bestE == Double.MAX_VALUE) -1.0 else bestE, if (secondE == Double.MAX_VALUE) -1.0 else secondE)
    }

    private fun startProbe644(activity: Activity, decor: ViewGroup, now: Long) {
        if (p644Runs >= 1) return
        if (now - last644 < 3000L) return
        val panel = findPanelOnScreen640(decor) ?: return
        val loc = IntArray(2)
        runCatching { panel.getLocationInWindow(loc) }
        val st = IntArray(2)
        staticLoc639(panel, st)
        val drift = maxOf(Math.abs(loc[0] - st[0]), Math.abs(loc[1] - st[1]))
        if (drift > 2) return
        last644 = now
        captureBand633(activity, bandTop644, decor.height - bandTop644) { px, w, h ->
            frameA643 = px
            frameW643 = w
            frameH643 = h
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                captureBand633(activity, bandTop644, decor.height - bandTop644) { px2, w2, h2 ->
                    val rowsFrom = maxOf(0, 2500 - bandTop644)
                    val stable = meanDiff642(px, px2, w2, h2, rowsFrom)
                    if (stable > 1.0) {
                        HLog.i("$TAG 基线稳定(644) 连抓两帧带内差=${String.format("%.2f", stable)} 不稳→本轮不测")
                        return@captureBand633
                    }
                    p644Runs++
                    val sf = selfShift644(px2, w2, h2, bandTop644)
                    HLog.i(
                        "$TAG 帧内自位移(644) 基线差=${String.format("%.2f", stable)}" +
                            " 带内相对上方最佳位移=${sf[0].toInt()}px 误差=" + String.format("%.2f", sf[1]) +
                            " 次佳误差=" + String.format("%.2f", sf[2]) +
                            " 窗口高=${decor.height} 面板高=${panel.height} 差=" + (decor.height - panel.height) +
                            " 带高=" + decor.height
                    )
                    matchHosts644(decor, panel, px2, w2, h2, rowsFrom)
                    experiment644(activity, decor, panel, px2, w2, h2)
                }
            }, 150)
        }
    }

    private fun anchoredHosts644(decor: ViewGroup): ArrayList<View> {
        val out = ArrayList<View>()
        val stack = ArrayDeque<View>()
        stack.addLast(decor)
        var guard = 0
        while (stack.isNotEmpty() && guard++ < 2500) {
            val v = stack.removeLast()
            if (v is ViewGroup) for (i in 0 until v.childCount) stack.addLast(v.getChildAt(i))
            if (v !== decor && v.background is ScreenAnchoredBitmapDrawable) out.add(v)
        }
        return out
    }

    private fun matchHosts644(decor: ViewGroup, panel: ViewGroup, real: IntArray, w: Int, h: Int, rowsFrom: Int) {
        var i = 0
        for (v in anchoredHosts644(decor)) {
            if (i++ >= 12) break
            val px = renderBand643(v, bandTop644, w, h) ?: continue
            val m = shiftMatch643(px, real, w, h, rowsFrom)
            val loc = IntArray(2)
            runCatching { v.getLocationInWindow(loc) }
            val bottom = loc[1] + v.height
            HLog.i(
                "$TAG 带内匹配(644) ${v.javaClass.simpleName}#${idName(v)} y=${loc[1]}..$bottom" +
                    " 底贴合=" + (if (bottom == panel.height) "面板高" else "窗口高") +
                    " 覆盖=" + String.format("%.2f", m.coverage) +
                    " 位移=" + m.shift + " 误差=" + String.format("%.2f", m.err)
            )
        }
    }

    private fun experiment644(activity: Activity, decor: ViewGroup, panel: ViewGroup, base: IntArray, w: Int, h: Int) {
        val saved = ArrayList<Pair<View, android.graphics.drawable.Drawable>>()
        var n = 0
        for (v in anchoredHosts644(decor)) {
            if (v === decor) continue
            val loc = IntArray(2)
            runCatching { v.getLocationInWindow(loc) }
            val bottom = loc[1] + v.height
            val inPanel = isDescendant644(v, panel)
            // 只动两类：①面板子树里的锚定层 ②尺寸按面板高、不是窗口高的锚定层
            if (!inPanel && bottom != panel.height) continue
            val bg = v.background as? ScreenAnchoredBitmapDrawable ?: continue
            saved.add(Pair(v, bg))
            runCatching { v.background = null }
            n++
            HLog.i(
                "$TAG 带内摘层(644) 目标 ${v.javaClass.simpleName}#${idName(v)} y=${loc[1]}..$bottom" +
                    " 面板内=" + inPanel + " bg=" + bg.snapshot()
            )
        }
        if (n == 0) {
            HLog.i("$TAG 带内摘层(644) 目标=0（没有面板内/面板高的锚定层）")
            return
        }
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            captureBand633(activity, bandTop644, decor.height - bandTop644) { px, ww, hh ->
                val rowsFrom = maxOf(0, 2500 - bandTop644)
                val bandDiff = meanDiff642(base, px, ww, hh, rowsFrom)
                val upperDiff = meanDiff642(base, px, ww, hh, 0, maxOf(0, rowsFrom - 1))
                val keep = bandDiff > 3.0 && bandDiff > upperDiff * 2.0
                if (!keep) for (p in saved) runCatching { p.first.background = p.second }
                HLog.i(
                    "$TAG 带内摘层(644) 摘=$n 带内差=" + String.format("%.2f", bandDiff) +
                        " 上半区差=" + String.format("%.2f", upperDiff) +
                        (if (keep) " 保留→这就是这条带的绘制者" else " 还原→不是它")
                )
            }
        }, 160)
    }

    private fun isDescendant644(v: View, root: View): Boolean {
        var cur: View? = v
        var guard = 0
        while (cur != null && guard++ < 64) {
            if (cur === root) return true
            cur = cur.parent as? View
        }
        return false
    }

    private fun seamDiff645(px: IntArray, w: Int, h: Int, top: Int, rowY: Int): Double {
        val y = rowY - top
        if (y <= 0 || y >= h) return -1.0
        var sum = 0.0
        var n = 0
        var x = 0
        while (x < w) {
            sum += Math.abs(lumOf631(px[y * w + x]) - lumOf631(px[(y - 1) * w + x]))
            n++
            x += 4
        }
        return if (n == 0) -1.0 else sum / n
    }

    private fun dropPanelHeightWallpapers645(activity: Activity, decor: ViewGroup, panel: ViewGroup, tag: String = "645"): Int {
        var n = 0
        val sb = StringBuilder()
        for (v in anchoredHosts644(decor)) {
            if (v === decor) continue
            val loc = IntArray(2)
            runCatching { v.getLocationInWindow(loc) }
            val bottom = loc[1] + v.height
            if (bottom != panel.height) continue
            val bg = v.background as? ScreenAnchoredBitmapDrawable ?: continue
            val info = bg.snapshot()
            runCatching { v.background = null }
            n++
            if (n <= 4) sb.append(" [").append(v.javaClass.simpleName).append("#").append(idName(v))
                .append(" y=").append(loc[1]).append("..").append(bottom).append(" ").append(info).append("]")
        }
        HLog.i(
            "$TAG 面板高锚定层已摘($tag) 面板高=${panel.height} 窗口高=${decor.height} 差=${decor.height - panel.height}" +
                " 摘=$n$sb"
        )
        return n
    }

    private fun startProbe645(activity: Activity, decor: ViewGroup, now: Long) {
        if (p645Runs >= 1) return
        if (now - last645 < 2000L) return
        val panel = findPanelOnScreen640(decor) ?: return
        val loc = IntArray(2)
        runCatching { panel.getLocationInWindow(loc) }
        val st = IntArray(2)
        staticLoc639(panel, st)
        val drift = maxOf(Math.abs(loc[0] - st[0]), Math.abs(loc[1] - st[1]))
        if (drift > 2) return
        last645 = now
        val top = bandTop644
        val bh = decor.height - top
        captureBand633(activity, top, bh) { before, w, h ->
            dropPanelHeightWallpapers645(activity, decor, panel)
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                captureBand633(activity, top, bh) { after, w2, h2 ->
                    val rowsFrom = maxOf(0, 2500 - top)
                    val bandDiff = meanDiff642(before, after, w2, h2, rowsFrom)
                    val upperDiff = meanDiff642(before, after, w2, h2, 0, maxOf(0, rowsFrom - 1))
                    val sB = seamDiff645(before, w, h, top, 2548)
                    val sA = seamDiff645(after, w2, h2, top, 2548)
                    val sB2 = seamDiff645(before, w, h, top, 2721)
                    val sA2 = seamDiff645(after, w2, h2, top, 2721)
                    p645Runs++
                    HLog.i(
                        "$TAG 接缝(645) 带内差=" + String.format("%.2f", bandDiff) +
                            " 上半区差=" + String.format("%.2f", upperDiff) +
                            " y2548行差: 修前=" + String.format("%.2f", sB) + " 修后=" + String.format("%.2f", sA) +
                            " y2721行差: 修前=" + String.format("%.2f", sB2) + " 修后=" + String.format("%.2f", sA2) +
                            " 面板drift=$drift 面板y=${loc[1]}..${loc[1] + panel.height}"
                    )
                }
            }, 200)
        }
    }

    private fun startProbe646(activity: Activity, decor: ViewGroup, now: Long) {
        if (p646Tries >= 2) return
        if (now - last646 < 2500L) return
        val panel = findPanelOnScreen640(decor) ?: return
        val loc = IntArray(2)
        runCatching { panel.getLocationInWindow(loc) }
        // 只认完全展开：面板顶边必须为 0（645 就是在这里放过了 y=-1073 的半展开实例）
        if (loc[1] != 0) return
        last646 = now
        p646Tries++
        val top = bandTop644
        val bh = decor.height - top
        captureBand633(activity, top, bh) { a, w, h ->
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                captureBand633(activity, top, bh) { b, w2, h2 ->
                    val rowsFrom = maxOf(0, 2500 - top)
                    val stable = meanDiff642(a, b, w2, h2, rowsFrom)
                    if (stable > 1.0) {
                        HLog.i(
                            "$TAG 展开判定(646) 面板顶边=0 但两帧差=" + String.format("%.2f", stable) +
                                " 未稳定→第${p646Tries}次放弃，等下一轮"
                        )
                        return@captureBand633
                    }
                    if (p649Entries == 0) hideOffscreenPanel650(activity)
                    val n = dropPanelHeightWallpapers645(activity, decor, panel, "646")
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        captureBand633(activity, top, bh) { c, w3, h3 ->
                            val bandDiff = meanDiff642(b, c, w3, h3, rowsFrom)
                            val upperDiff = meanDiff642(b, c, w3, h3, 0, maxOf(0, rowsFrom - 1))
                            val sB = seamDiff645(b, w2, h2, top, 2548)
                            val sA = seamDiff645(c, w3, h3, top, 2548)
                            HLog.i(
                                "$TAG 残留剖面(649) 第${p646Tries}次 本进程第${++p649Entries}次进入" +
                                    (if (p649Entries == 1) "(冷启动)" else "(二次进入)") +
                                    " 面板y=${loc[1]}..${loc[1] + panel.height} 面板高=${panel.height} 窗口高=${decor.height}" +
                                    " 带内[" + residual647(c, w3, h3, top, 2548, 2722, true) + "] 纹理参考[" +
                                    residual647(c, w3, h3, top, 2560, 2600) + "]"
                            )
                            HLog.i(
                                "$TAG 接缝(646) 第${p646Tries}次 摘=$n 基线稳定差=" + String.format("%.2f", stable) +
                                    " 摘后带内差=" + String.format("%.2f", bandDiff) +
                                    " 上半区差=" + String.format("%.2f", upperDiff) +
                                    " y2548行差: 摘前=" + String.format("%.2f", sB) +
                                    " 摘后=" + String.format("%.2f", sA) +
                                    " 面板y=${loc[1]}..${loc[1] + panel.height} 窗口高=${decor.height}"
                            )
                        }
                    }, 200)
                }
            }, 150)
        }
    }

    private fun residual647(px: IntArray, w: Int, h: Int, top: Int, y0: Int, y1: Int, withVerdict: Boolean = false): String {
        var over = 0
        var rows = 0
        var maxD = 0.0
        var sum = 0.0
        val ranges = StringBuilder()
        var runStart = -1
        var y = y0
        while (y <= y1) {
            val a = y - top
            val b = a - 1
            if (a in 0 until h && b in 0 until h) {
                var sd = 0.0
                var m = 0
                var x = 0
                while (x < w) {
                    sd += Math.abs(lumOf631(px[a * w + x]) - lumOf631(px[b * w + x]))
                    m++
                    x += 4
                }
                val d = if (m == 0) 0.0 else sd / m
                sum += d
                rows++
                if (d > maxD) maxD = d
                if (d > 8.0) {
                    over++
                    if (runStart < 0) runStart = y
                } else if (runStart >= 0) {
                    ranges.append(" ").append(runStart).append("..").append(y - 1)
                    runStart = -1
                }
            }
            y++
        }
        if (runStart >= 0) ranges.append(" ").append(runStart).append("..").append(y1)
        val mean = if (rows == 0) 0.0 else sum / rows
        val verdict = if (!withVerdict) "" else
            if (over <= 6 && mean <= 6.0) " 判定=带内已连续" else " 判定=仍有错位"
        return "行数=$rows 突变行=$over 最大行差=" + String.format("%.2f", maxD) +
            " 平均行差=" + String.format("%.2f", mean) +
            " 突变区间=" + (if (ranges.isEmpty()) "无" else ranges.toString()) + verdict
    }

    private fun huntWhitesInPage(page: View, logHits: Boolean): Int {
        val pageLists = ArrayList<android.widget.AbsListView>(4)
        val pageArea = (page.width.coerceAtLeast(1)).toLong() * page.height.coerceAtLeast(1)
        if (pageLists615[page] == null) pageLists615[page] = pageLists
        val registered = pageLists615[page]!!
        val pageW = page.width.coerceAtLeast(1)
        val pageH = page.height.coerceAtLeast(1)
        val minArea = (pageArea / 20L).coerceAtLeast(4000L)
        var cleared = 0
        var visited = 0
        val queue = ArrayList<View>()
        queue.add(page)
        var head = 0
        while (head < queue.size && visited < 500 && cleared < 40) {
            val v = queue[head++]
            visited++
            if (v is android.widget.AbsListView && registered.none { it === v }) {
                (registered as? ArrayList<android.widget.AbsListView>)?.add(v)
            }
            val bg = v.background
            // 615：右侧索引栏那类「窄高竖带」——它自己铺一份按自身宽高缩放的壁纸就会和窗口锚定层错位。
            // 统一改成透明，让页面/窗口那张统一壁纸透出来，天然无缝。
            if (v !== page && v.width in 1..(pageW * 18 / 100) && v.height >= pageH / 2 && bg != null) {
                val notOurs = bg !== guardTransparent
                if (notOurs) {
                    clearedViews.add(v)
                    runCatching { v.background = guardTransparent }
                    stripHits++
                    if (logHits && stripHits <= 20) {
                        HLog.i(
                            "$TAG 索引条竖带已改透明(615): ${v.javaClass.simpleName}#${idName(v)}" +
                                " ${v.width}x${v.height} 原bg=${descDrawable(bg)}（改为透出统一底层）"
                        )
                    }
                }
            }
            if (v !== page && bg is ColorDrawable) {
                val c = bg.color
                val a = Color.alpha(c)
                if (a == 255 && v.width > 0 && v.height > 0) {
                    val r = Color.red(c); val g = Color.green(c); val b = Color.blue(c)
                    val lum = (0.299 * r + 0.587 * g + 0.114 * b) / 255.0
                    val area = v.width.toLong() * v.height
                    val ours = pageDrawables.containsKey(v) || homeImageDrawables.containsKey(v) ||
                        rowScrimDrawables.containsKey(v)
                    if (!ours && lum >= 0.86 && area >= minArea) {
                        clearedViews.add(v)
                        runCatching { v.background = guardTransparent }
                        cleared++
                        huntHits++
                        if (logHits && huntLogs < 40) {
                            huntLogs++
                            HLog.i(
                                "$TAG 进页白块已清(614): ${v.javaClass.simpleName}#${idName(v)}" +
                                    " ${v.width}x${v.height} bg=#${Integer.toHexString(c)} 亮度=${String.format(java.util.Locale.US, "%.2f", lum)}"
                            )
                        }
                    }
                }
            }
            if (v is ViewGroup) {
                for (i in 0 until v.childCount) {
                    val c = v.getChildAt(i)
                    if (c.visibility == View.VISIBLE) queue.add(c)
                }
            }
        }
        return cleared
    }

    /**
     * 614：逐帧巡检（Choreographer 常驻，只处理当前可见页）。
     * - 页面结构指纹变化（内容刚 inflate）→ 立刻重跑分层 + 清白块，赶在这一帧绘制前；
     * - 每 250ms 采样一次，把宿主新写回的浅色块清掉；
     * 首页/通讯录滚动露白主要由 cacheColorHint 与上面的白块负责。
     */
    private fun installFrameSweep(activity: Activity, decor: ViewGroup) {
        if (frameSweepInstalled) return
        frameSweepInstalled = true
        HLog.i("$TAG 逐帧巡检已启动(614): 可见页结构变化即重铺，250ms 采样清白块")
        val choreographer = runCatching { android.view.Choreographer.getInstance() }.getOrNull() ?: return
        val callback = object : android.view.Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                runCatching { sweepTick(activity, decor) }
                    .onFailure { HLog.e("$TAG 逐帧巡检异常(614): ${it.message}", it) }
                choreographer.postFrameCallback(this)
            }
        }
        choreographer.postFrameCallback(callback)
    }

    private fun sweepTick(activity: Activity, decor: ViewGroup) {
        runCatching { locateLines(activity, decor, SystemClock.uptimeMillis()) }
        runCatching { sampleDarkRows(decor, SystemClock.uptimeMillis()) }
        if (!decor.isAttachedToWindow) return
        val now = SystemClock.uptimeMillis()
        // 615：每帧先做最廉价的一件事——把可见页列表自己的底色拉回壁纸。
        // 09:37 滚动探针实锤：ConversationListView#j8g 自身bg 在
        // ScreenAnchoredBitmapDrawable 与 ColorDrawable#ffededed 之间来回跳，
        // 而 setBackgroundColor 没被 613 的钩子覆盖，宿主那一下白底就漏到了屏幕上。
        runCatching { restoreVisibleListBackgrounds(now) }
        val pager = lastHomePager?.get() ?: run {
            val found = findHomePager(decor, false) ?: return
            lastHomePager = WeakReference(found)
            found
        }
        if (pager.childCount == 0) return
        val idx = (pager.scrollX + pager.width / 2) / pager.width.coerceAtLeast(1)
        val index = idx.coerceIn(0, pager.childCount - 1)
        val page = pager.getChildAt(index) ?: return
        val sig = pageSignatureOf(page)
        val changed = sig != pageSignatureAt[page]
        // 617：切页过渡期间把巡检间隔压到 50ms——「我的」→「发现」时标题栏那条宿主的
        // #ffededed 只露 1~2 帧，250ms 的节流根本轮不到它（13:06 反馈）。落定后回到 250ms 省电。
        val sweeping = now - lastPagerScrollAt617 < 320L
        runCatching { probeBands624(activity, decor, now) }
        runCatching { probePixelCopy626(activity, decor, now) }
        runCatching { clearSeamHairlines628(decor, now) }
        runCatching { probeStrip628(activity, now) }
        runCatching { probeMiniBar628(activity, decor, now) }
        runCatching { stripDividers629(decor, now) }
        runCatching { probeLayers631(activity, decor, now) }
        runCatching { probeLayers632(decor, now) }
        runCatching { probeBars634(decor, now) }
        runCatching { probePanelBottom637(decor, now) }
        runCatching { probeBottomBand638(decor, now) }
        runCatching { dropDuplicateWallpaper640(decor, now) }
        runCatching { startProbe646(activity, decor, now) }
        runCatching { probeSeam633(activity, decor, now) }
        runCatching { probeFlashFrame630(activity, now) }
        runCatching { probeFullFrame627(activity, decor, now) }
        runCatching { traceFrame625(activity, decor, now) }
        runCatching { profileRows625(activity, decor, now) }
        val interval = if (sweeping) 50L else 250L
        if (!changed && now - lastSweepAt < interval) return
        lastSweepAt = now
        if (changed) {
            pageSignatureAt[page] = sig
            val spec = pageScrim[page]
            if (spec != null) {
                runCatching {
                    applyContentScrim(page, spec.bitmap, spec.overlayColor, spec.isHome, log = false)
                }
            }
        }
        val cleared = huntWhitesInPage(page, logHits = true)
        sweepRuns++
        if ((changed || cleared > 0) && now - lastSweepLogAt >= 2000L) {
            lastSweepLogAt = now
            HLog.i(
                "$TAG 逐帧巡检(614): 可见页=${index + 1} 结构变化=$changed 清白块=$cleared 处" +
                    " 累计巡检=$sweepRuns 次 累计清白=$huntHits 处 scrollX=${pager.scrollX}"
            )
        }
        // 滚动期间另一条通道：列表把浅色纯色块当滚动缓存画出来
        assertListScrollingColor(page)
    }

    /** 614：列表滚动缓存色（cacheColorHint / 视图 solidColor）—滚动时宿主用它们顶替 item 绘制。 */
    /** 615：可见页的列表底色每帧比对（宿主在滚动/复用时会用 setBackgroundColor 写回 #ffededed）。 */
    private fun restoreVisibleListBackgrounds(now: Long) {
        val pager = lastHomePager?.get() ?: return
        if (pager.childCount == 0) return
        val index = ((pager.scrollX + pager.width / 2) / pager.width.coerceAtLeast(1))
            .coerceIn(0, pager.childCount - 1)
        val page = pager.getChildAt(index) ?: return
        val lists = pageLists615[page] ?: return
        if (lists.isEmpty()) return
        val target = pageDrawables[page] ?: return
        var fixed = 0
        for (list in lists) {
            if (!list.isAttachedToWindow) continue
            val bg = list.background
            if (bg is ScreenAnchoredBitmapDrawable || bg === target) continue
            runCatching { list.background = target }
            fixed++
        }
        if (fixed > 0) {
            listBgRestores += fixed
            if (now - lastListRestoreLogAt >= 2000L) {
                lastListRestoreLogAt = now
                HLog.i(
                    "$TAG 列表底色被宿主刷回，逐帧拉回(615): 本帧 $fixed 个 列表=${lists.size}" +
                        " 累计 $listBgRestores 次（滚动/fling 期间保底，不再等 400ms 周期）"
                )
            }
        }
    }

    private fun assertListScrollingColor(page: View) {
        collectCacheColorHints(page) { list ->
            if (list.cacheColorHint != Color.TRANSPARENT) {
                runCatching { list.cacheColorHint = Color.TRANSPARENT }
            }
        }
    }

    /** 614：滚动探针——把列表在滚动瞬间实际使用的底色通道打出来（下一轮取证用）。 */
    private fun installScrollProbe() {
        if (scrollProbe614) return
        scrollProbe614 = true
        runCatching {
            XposedBridge.hookAllMethods(
                android.widget.AbsListView::class.java,
                "onScrollChanged",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val v = param.thisObject as? android.widget.AbsListView ?: return
                        val now = SystemClock.uptimeMillis()
                        if (now - lastScrollStateLogAt < 600L) return
                        lastScrollStateLogAt = now
                        val first = v.getChildAt(0)
                        HLog.i(
                            "$TAG 滚动探针(614): ${v.javaClass.simpleName}#${idName(v)}" +
                                " 项数=${v.childCount} 首子=${first?.javaClass?.simpleName}" +
                                " 首子bg=${descDrawable(first?.background)}" +
                                " cacheColorHint=#${Integer.toHexString(v.cacheColorHint)}" +
                                " 自身bg=${descDrawable(v.background)} overScroll=${v.overScrollMode}"
                        )
                    }
                })
            val rv = KavaReflector.loadClass("androidx.recyclerview.widget.RecyclerView", context.hostClassLoader())
            if (rv != null) {
                XposedBridge.hookAllMethods(rv, "onScrolled", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val v = param.thisObject as? ViewGroup ?: return
                        val now = SystemClock.uptimeMillis()
                        if (now - lastScrollStateLogAt < 600L) return
                        lastScrollStateLogAt = now
                        val first = v.getChildAt(0)
                        HLog.i(
                            "$TAG 滚动探针(614): ${v.javaClass.simpleName}#${idName(v)} 子项=${v.childCount}" +
                                " 首子bg=${descDrawable(first?.background)} 自身bg=${descDrawable(v.background)}" +
                                " overScroll=${v.overScrollMode}"
                        )
                    }
                })
            }
            HLog.i("$TAG 滚动探针已挂(614): AbsListView.onScrollChanged + RecyclerView.onScrolled")
        }.onFailure { HLog.e("$TAG 滚动探针挂载失败(614): ${it.message}", it) }
    }

    private fun installEnforcement(activity: Activity, decor: ViewGroup) {
        HLog.i("$TAG 模块版本: ${com.aeonlab.toolkit.BuildConfig.VERSION_NAME}/${com.aeonlab.toolkit.BuildConfig.VERSION_CODE}")
        if (!enforcedDecors.add(decor)) return
        // 5.5.63 真机：通讯录/发现/我刚进入时标题栏白、没有背景图，手指点一下才刷出来。
        // 根因是兜底逻辑挂在 OnPreDrawListener 上——没有 invalidate 就不会触发绘制，
        // 也就永远不会跑 enforce；首帧之后只能等用户触摸。这里改成定时补刷 + 每轮结束强制重绘。
        installLifecycleBumps(activity, decor)
        // 702 restore-chain shorted: installFrameSweep(activity, decor)
        installScrollProbe()
        installPeriodicTick(activity, decor)
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
                // 613：先做逐帧断言（纯引用比对，不受 400ms 节流限制），再跑常规 enforce。
                runCatching { fastAssertBackgroundsBeforeDraw() }
                    .onFailure { HLog.e("$TAG 逐帧断言异常(613): ${it.message}", it) }
                // 712c：把「行内缩回钉」也提到画之前，让错位在画出来之前就被钉回，
                // 而不是等 1.2 秒的巡检（用户 1027 实测：错位约 1~2 秒后被压回去）。
                runCatching { pinRowInsetsBeforeDraw712() }
                    .onFailure { HLog.e("$TAG 绘制前回钉异常(712): ${it.message}", it) }
                runCatching { assertWindowColors(activity) }
                    .onFailure { HLog.e("$TAG 状态栏底色重设异常(677): ${it.message}", it) }
                runCatching { enforce(activity, decor) }
                    .onFailure { HLog.e("$TAG 兜底重刷异常: ${it.message}", it) }
                return true
            }
        })
    }

    /**
     * 5.5.76：真机报「通讯录/发现/我的刚打开都是白底，触屏一下才透出壁纸」。
     * 说明清理仍是边沿触发：只有绘制/触摸才会跑 enforce，静止页面没人纠正。
     * 这里改成常驻低频轮询（500ms），只做已登记视图的状态断言与未清底页面的重试，不扫全树。
     */
    private fun installPeriodicTick(activity: Activity, decor: ViewGroup) {
        if (!periodicTicked.add(activity)) return
        val tick = object : Runnable {
            override fun run() {
                if (!decor.isAttachedToWindow) return
                runCatching { enforce(activity, decor, force = true) }
                    .onFailure { HLog.e("$TAG 常驻补刷异常: ${it.message}", it) }
                runCatching { refreshVisiblePageScrim() }
                    .onFailure { HLog.e("$TAG 可见页分层补跑异常(678): ${it.message}", it) }
                // 710：列表实例重建后（切页/回首页）再补一次清线；同一实例由 seamClearedLists710 挡住，不会重复清。
                runCatching { clearSeamOnce710(decor) }
                    .onFailure { HLog.e("$TAG 633 缝隙清线异常(710): ${it.message}", it) }
                mainHandler.postDelayed(this, PERIODIC_TICK_MS)
            }
        }
        mainHandler.postDelayed(tick, PERIODIC_TICK_MS)
        HLog.i("$TAG PeriodicTick armed ${PERIODIC_TICK_MS}ms 常驻补刷已启动（只做状态断言）")
    }

    /**
     * 5.5.71：补刷从「OnPreDraw 一次性」改为挂 Activity 生命周期。
     * 真机日志（18:54:12.282–.340）证明清底只发生在一个 58ms 窗口里，之后 4 分 56 秒再没有第二次；
     * 静态页面不重绘 → OnPreDraw 不回调 → 宿主把底色写回来后没人纠正，于是「静止白、碰一下才透明」。
     * 现在 onCreate/onStart/onResume/onWindowFocusChanged 四个点各触发一次（每次内部再延迟 80ms 重清一次），
     * 外加 decor 的 AttachStateChange 与主题设置变更兜底，保证无人触摸时也会被清到。
     */
    /** 5.5.75：从容器往下打三层，带 top/bottom/y/translationY，用于定位标题栏为何在页面底部。 */
    private fun dumpDeep(view: View, prefix: String, depth: Int) {
        if (depth >= 3) return
        val group = view as? ViewGroup ?: return
        for (i in 0 until minOf(group.childCount, 8)) {
            val child = group.getChildAt(i) ?: continue
            HLog.i("$TAG $prefix[$i]: ${child.javaClass.name}#${idName(child)} ${child.width}x${child.height} top=${child.top} bottom=${child.bottom} y=${child.y} tY=${child.translationY} vis=${child.visibility} alpha=${child.alpha}")
            dumpDeep(child, "$prefix[$i]", depth + 1)
        }
    }

    /** 5.5.72 诊断专用：decor 直接子层的位置/大小/透明度，用来定位「标题栏出现在页面底部」。 */
    private fun dumpDecorTree(decor: ViewGroup) {
        runCatching {
            HLog.i("$TAG decor 层转储: 子项=${decor.childCount} 尺寸=${decor.width}x${decor.height}")
            for (i in 0 until minOf(decor.childCount, 12)) {
                val child = decor.getChildAt(i) ?: continue
                HLog.i(
                    "$TAG decor[$i]: ${child.javaClass.name}#${idName(child)} " +
                        "${child.width}x${child.height} top=${child.top} y=${child.y} " +
                        "vis=${child.visibility} alpha=${child.alpha} " +
                        "bg=${child.background?.javaClass?.simpleName ?: "null"}"
                )
                dumpDeep(child, "decor[$i]", 0)
            }
        }.onFailure { HLog.e("$TAG decor 转储失败: ${it.message}", it) }
    }

    private fun installLifecycleBumps(activity: Activity, decor: ViewGroup) {
        if (mountOff703()) { dumpCounts703("mount-skip applyWindowBackground("); return }
        if (!lifecycleBumped.add(activity)) return
        val bump: (String) -> Unit = { tag ->
            mainHandler.post {
                runCatching {
                    HLog.i("$TAG 生命周期补刷触发: $tag")
                    enforce(activity, decor, force = true)
                    decor.postInvalidateOnAnimation()
                }.onFailure { HLog.e("$TAG 生命周期补刷异常($tag): ${it.message}", it) }
                mainHandler.postDelayed({
                    runCatching {
                        enforce(activity, decor, force = true)
                        decor.postInvalidateOnAnimation()
                    }.onFailure { HLog.e("$TAG 生命周期补刷(80ms)异常($tag): ${it.message}", it) }
                }, LIFECYCLE_BUMP_DELAY_MS)
            }
        }
        if (false) runCatching { installNativeModeGuards() }
        // 710：设置页/小程序等叠加页的壁纸撤销链路改由 Activity#onResume 驱动（焦点监听在 Android 15 不回调）。
        runCatching { installSettingsResumeHook684() }
        if (false) runCatching { syncNativeOnlyMode(activity, decor, "生命周期补刷(onCreate)") }
            .onFailure { HLog.e("$TAG 原生模式同步异常(682): ${it.message}") }
        bump("onCreate")
        runCatching {
            activity.application.registerActivityLifecycleCallbacks(
                object : android.app.Application.ActivityLifecycleCallbacks {
                    override fun onActivityCreated(a: Activity, b: android.os.Bundle?) = bump("onCreate")
                    override fun onActivityStarted(a: Activity) = bump("onStart")
                    override fun onActivityResumed(a: Activity) {
                        // 681：微信原生设置页是叠加页，宿主把主题壁纸铺在它的窗口底上，
                        // 原生透明的分组头/卡片右侧就整片透出壁纸（1000142866/67.jpg）。
                        runCatching { applyNativeGreyToSettingsPage(a, "onResume") }
                            .onFailure { HLog.e("$TAG 设置页灰底异常(681): ${it.message}") }
                        bump("onResume")
                    }
                    override fun onActivityPaused(a: Activity) {}
                    override fun onActivityStopped(a: Activity) {}
                    override fun onActivitySaveInstanceState(a: Activity, b: android.os.Bundle) {}
                    override fun onActivityDestroyed(a: Activity) {}
                }
            )
        }.onFailure { HLog.e("$TAG 生命周期回调注册失败: ${it.message}", it) }
        // 5.5.74：切页后 ViewPager 会新建一套页面视图，pageScrim 里存的是启动时的占位引用
        // （5.5.73 日志：页面 2/3/4 子项=2、分层计数恒 0），必须重新解析页面再跑一次完整背景/清底。
        runCatching {
            decor.viewTreeObserver.addOnGlobalLayoutListener {
                val now = SystemClock.uptimeMillis()
                if (now - lastLayoutBumpAt < 200L) return@addOnGlobalLayoutListener
                lastLayoutBumpAt = now
                runCatching {
                    val snap = ThemeSettings.snapshot(context.hostContext())
                    if (snap.enabled) applyWindowBackground(activity, decor, snap)
                }.onFailure { HLog.e("$TAG 布局变更重扫异常: ${it.message}", it) }
                // 710j：页面切换 / 小程序面板弹出这类「整体布局变化」落定后再重录一次锚定宿主。
                // 成因见 ScreenAnchoredBitmapDrawable 的 599/600 注释：动画期间录进 display list 的帧
                // 会滞留（面板弹出后壁纸整体下沉、发现/我的页白色底衬错位），而触摸屏幕带来的重录
                // 恰好把它修好 —— 用户这次报的「点一下又透明了」「图标偶发错位」就是这个滞留帧。
                scheduleSettleReRecord710()
            }
        }.onFailure { HLog.e("$TAG 布局变更监听注册失败: ${it.message}", it) }
        runCatching {
            decor.viewTreeObserver.addOnWindowFocusChangeListener { focused ->
                if (focused) bump("onWindowFocusChanged")
            }
        }.onFailure { HLog.e("$TAG 窗口焦点监听注册失败: ${it.message}", it) }
        runCatching {
            decor.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) = bump("覆盖层Attached")
                override fun onViewDetachedFromWindow(v: View) {}
            })
        }.onFailure { HLog.e("$TAG AttachStateChange 监听注册失败: ${it.message}", it) }
        runCatching {
            ThemeSettings.preferences(context.hostContext())
                .registerOnSharedPreferenceChangeListener { _, _ -> bump("设置变更") }
        }.onFailure { HLog.e("$TAG 设置变更监听注册失败: ${it.message}", it) }
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

    /** 5.5.73：宿主把通栏行的白底写回来后重压遮罩（发现页分组行、我页顶部块都是这条路）。 */
    private fun reassertRowScrims() {
        var fixed = 0
        for ((view, _) in rowScrimDrawables.entries.toList()) {
            if (!view.isAttachedToWindow) continue
            val want = desiredBackgroundFor(view) ?: continue
            if (view.background === want) continue
            runCatching { view.setBackgroundDrawable(want) }
            rowPaddingKept[view]?.let { applyKeptPadding(view, it) }
            fixed++
        }
        if (fixed > 0) HLog.i("$TAG 通栏行遮罩被宿主覆盖，已重压: $fixed 个")
    }

    /**
     * 5.5.72：真机日志（11:05:20）里「→置透明」整场只出现 1 次（首页 ConversationListView#j8g），
     * 之后 4 分钟没有第二次；宿主把 #ffededed 写回来后没人纠正，静态页面就一直是浅灰/白。
     * 这里对已经清过的视图做廉价的状态断言：底色不再是全透明就再清一次，不做全树扫描。
     */
    private fun reassertClearedBackgrounds() {
        var fixed = 0
        val now = SystemClock.uptimeMillis()
        for (view in clearedViews.toList()) {
            if (!view.isAttachedToWindow) continue
            val want = desiredBackgroundFor(view)
            if (want == null || view.background === want) continue
            if (want is ColorDrawable && Color.alpha(want.color) == 0) {
                val bg = view.background
                if (bg is ColorDrawable && Color.alpha(bg.color) == 0) continue
            }
            runCatching { view.setBackgroundDrawable(want) }
            fixed++
        }
        if (fixed > 0 && now - lastClearReassertLogAt >= 3000L) {
            lastClearReassertLogAt = now
            HLog.i("$TAG 已清底视图被宿主重新上色，重新清透明: $fixed 个")
        }
    }

    /**
     * 5.5.72：真机日志（11:05:20）里页面 2/3/4 的分层结果是「容器置透明 0 个、通栏行 0 个」——
     * 启动那一刻 ViewPager 只对首页量好了子 View，另外三页的子 View 还是 0x0，被 scrimTree 的
     * 「width>4 && height>4」条件整片跳过；之后 enforce 只重铺页面背景、不再跑分层，
     * 于是那三页的卡片白底一直留着，表现为「发现页要点一下才出图」「我页顶部白块」。
     * 这里对「还没清到底」的页面按需重试，最多 20 次，成功一次就进 scrimmedPages 不再跑。
     */
    private fun retryUnscrimmedPages() {
        val pages = pageScrim.entries.toList()
        for ((page, spec) in pages) {
            if (scrimmedPages.contains(page)) continue
            if (!page.isAttachedToWindow) continue
            val width = page.width.coerceAtLeast(page.measuredWidth)
            val height = page.height.coerceAtLeast(page.measuredHeight)
            if (width <= 0 || height <= 0) continue
            val tried = scrimRetryCount[page] ?: 0
            if (tried >= 20) continue
            scrimRetryCount[page] = tried + 1
            runCatching {
                val parent = page.parent as? ViewGroup
                val pageGroup = page as? ViewGroup
                if (pageGroup != null && pageGroup.childCount <= 2 && parent != null) {
                    var replaced = false
                    for (i in 0 until parent.childCount) {
                        val sibling = parent.getChildAt(i)
                        if (sibling === page || sibling.javaClass != page.javaClass) continue
                        if (!sibling.isAttachedToWindow || sibling.width <= 0) continue
                        val siblingGroup = sibling as? ViewGroup
                        val siblingChildren = siblingGroup?.childCount ?: 0
                        if (siblingChildren > 2 && sibling.background !is android.graphics.drawable.BitmapDrawable) {
                            HLog.i("$TAG 占位页面替换为真实页面: ${sibling.javaClass.name}#${idName(sibling)} 子项=$siblingChildren 尺寸=${sibling.width}x${sibling.height}")
                            pageScrim.remove(page)
                            applyPageBitmap(sibling, spec.bitmap, sibling.resources, spec.overlayColor, true, spec.path, spec.isHome)
                            replaced = true
                            break
                        }
                    }
                    if (replaced) return@runCatching
                }
                applyContentScrim(page, spec.bitmap, spec.overlayColor, spec.isHome, log = tried == 0)
            }.onFailure { HLog.e("$TAG 页面分层重试异常: ${it.message}", it) }
        }
    }

    private fun enforce(activity: Activity, decor: ViewGroup, force: Boolean = false) {
        if (mountOff703()) {
            // 1029：mount-off703 的含义是「壁纸层/底衬/行遮罩不安装」（总开关关或四页全关），
            // 但 710k 是**布局**修复（内容链放开到底），与壁纸无关。
            // 早退把它一起关掉了 → 1028 真机（启用页面背景关 + 通讯录页面背景关）复现：
            // 视图树 MMWeUIBounceView 0,288-1240,2598 又回到 2598，
            // 屏幕 2598..2772 那 174px 没有内容链覆盖，露出页面灰（截图实测 y=2576 起 #EDEDED）。
            // 所以这里只补跑「底栏空档放开」+「空页格填充色」，其它门控行为一律不变。
            // 注意：总开关真的关掉时（!enabled）仍然什么都不做——只有「主题开着、但背景/页面开关关了」
            // 这种情况才需要补跑，避免模块整体停用时还去改宿主布局。
            // 节流 300ms：这段在 OnPreDraw 上，不能每帧读配置。
            val t1029 = SystemClock.uptimeMillis()
            if (t1029 - lastMountOffWork1029 >= 300L) {
                lastMountOffWork1029 = t1029
                val s1029 = ThemeSettings.snapshot(context.hostContext())
                if (s1029.enabled) {
                    applyBottomGapStretch710(activity, decor, s1029)
                    syncPagerOffFill1029(s1029)
                }
            }
            dumpCounts703("mount-skip if (snapshot.backgroundEnabled) reassertImmersive")
            return
        }
        if (!decor.isAttachedToWindow) return
        val now = SystemClock.uptimeMillis()
        if (!force && now - lastEnforceAt < ENFORCE_INTERVAL_MS) return
        lastEnforceAt = now
        val snapshot = ThemeSettings.snapshot(context.hostContext())
        if (!snapshot.enabled) return
        // 710c：微信「设置」是 LauncherUI 窗口内的页面（不是独立 Activity，dumpsys 任务栈与日志进程都印证了），
        // 所以只能靠顶层标题文本识别：命中就按住页面壁纸，离开再铺回。
        runCatching {
            if (settingsLikeVisible710(decor)) {
                settingsMissCycles710 = 0
                hidePageWallpaperForSettings710(decor, "巡检")
            } else if (settingsLikeHidden710.get()) {
                // 抗抖：设置页里标题被回收/滚动时会短暂识别不到，连续 6 轮（≈2.4s）都没命中
                // 才认为真的离开了设置页，否则壁纸会反复隐显。
                settingsMissCycles710++
                if (settingsMissCycles710 >= SETTINGS_RESTORE_MISS_CYCLES_710) {
                    settingsMissCycles710 = 0
                    restorePageWallpaperAfterSettings710(decor, "巡检")
                }
            }
        }.onFailure { HLog.e("$TAG 设置页壁纸按住异常(710c): ${it.message}") }
        // 710i：710g 的「把内容容器 LayoutParams 拉成 MATCH_PARENT」已被真机 1014 否掉 ——
        // 用户反馈「通讯录底栏位置没有透明，而是盖了一层背景图」：容器被拉满后，原本被页面白色
        // 底衬压住的整张壁纸在底栏那一带原样露了出来，和上面的列表形成一条明显色带。
        // 1012（710f，实际不生效的 padding 版本）反而是用户两次确认过的状态，因此整段回退：
        // 不再动任何 LayoutParams，也不涂色，底栏空档交还给宿主原本的绘制。
        // 详见 docs/FEATURE_FRAMEWORK.md §10。
        //
        // 710k：1014/1015 真机视图树确认「底栏空档露壁纸」的真正成因 —— 内容链止于 y=2598：
        //   LayoutListenerView 0..2772 / MMWeUIBounceView 288..2598 / NestedScrollView 288..2598
        //   / FrameLayout 288..2598 / WxRecyclerView 288..2598。
        // 2598 以下没有任何内容，露出的就是 pager 底衬（PagerStripWallpaperDrawable）画的壁纸，
        // 用户看到的就是「一条被壁纸盖住、本该透出好友列表的区域」。
        // 所以只在「隐藏原生底栏 && 未开自定义底栏」时，把这半条链放到底（保留顶部让位与标题栏），
        // 恢复底栏时原值写回。
        //
        // 1029：整段原样抽成 applyBottomGapStretch710()，唯一目的是让 mount-off703 早退分支也能跑到它
        // （关背景后底部 174px 灰回归，见 enforce 开头的注释）。判定与写回逻辑一个字节都没改。
        applyBottomGapStretch710(activity, decor, snapshot)
        // 1029：主开关开着时把空页格填充色拉回原生灰（关闭时是白），两个方向都由这一处收敛。
        runCatching { syncPagerOffFill1029(snapshot) }
            .onFailure { HLog.e("$TAG 空页格填充色同步异常(1029): ${it.message}") }
        // 710：叠加页兜底巡检——钩子（Instrumentation/Activity#onResume）万一在某些 ROM 上不生效，
        // 这里每轮也看一眼「当前有没有非 Launcher 的可见 Activity」，有就撤壁纸，回来就补铺。
        runCatching {
            val overlay = overlayActivity()
            if (overlay != null && !wallpaperHidden) {
                HLog.i("$TAG 叠加页巡检(710): 前台叠加页=${overlay.javaClass.simpleName} → 撤壁纸")
                hideDecorWallpaper710("巡检 ${overlay.javaClass.simpleName}")
            } else if (overlay == null && wallpaperHidden) {
                revealDecorWallpaper710("巡检：回到宿主")
                clearOverlayBackdrops710("巡检：回到宿主")
            }
            // 710b：叠加页一直开着时，每轮都把灰底板校一遍（宿主会把它挪位/改回透明）。
            if (overlaySettingsActivities710.isNotEmpty()) {
                overlaySettingsActivities710.toList().forEach { a ->
                    runCatching { ensureOverlayBackdrop710(a, "巡检") }
                }
            }
        }.onFailure { HLog.e("$TAG 叠加页巡检异常(710): ${it.message}") }
        if (snapshot.backgroundEnabled && !settingsLikeHidden710.get()) reassertImmersive(activity, decor, force)
        if (snapshot.backgroundEnabled && !settingsLikeHidden710.get()) {
            reassertClearedBackgrounds()
            reassertRowScrims()
            retryUnscrimmedPages()
        }
        var tab = lastTab?.get()
        if (tab != null && !tab.isAttachedToWindow) tab = null
        if (tab == null) tab = findBottomTab(decor, logCandidates = false)
        if (tab != null) {
            lastTab = WeakReference(tab)
            if (snapshot.tabHideNative) {
                if (tab.visibility != View.GONE) {
                    HLog.i("$TAG 隐藏原生底栏=开，隐藏底栏容器: ${tab.javaClass.name}#${idName(tab)}")
                    hideView(tab)
                    tabHiddenByUs = true
                }
            } else if (tabHiddenByUs) {
                runCatching { showView(tab) }
                tabHiddenByUs = false
                HLog.i("$TAG 隐藏原生底栏=关，恢复原生底栏显示: ${tab.javaClass.name}#${idName(tab)}")
            }
        }
        // 710b：底栏隐藏后原位置那条灰。与壁纸开关无关（用户实测开/关都一样），
        // 只在「底栏确实被我们隐藏了」时才清，否则会破坏微信自己画的底栏。
        if (snapshot.tabHideNative || tabHiddenByUs) {
            runCatching { clearBottomBarStrip710(decor, true) }
                .onFailure { HLog.e("$TAG 底部残条清理异常(710b): ${it.message}") }
        }
        if (snapshot.backgroundEnabled && !settingsLikeHidden710.get()) {
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
            syncAnchoredHosts()
            if (now - lastTopDumpAt >= topDumpIntervalMs) {
                lastTopDumpAt = now
                pageScrim.keys.toList().forEach { page ->
                    runCatching { dumpTopRegion(page, true) }
                        .onFailure { HLog.e("$TAG 顶部转储异常: ${it.message}", it) }
                }
            }
            findViewByClassName(decor, "ActionBarContainer")?.let { bar ->
                val parsed = ThemeSettings.parseColor(snapshot.actionBarBackgroundColor)
                applyInternalActionBarColor(bar, parsed ?: Color.TRANSPARENT)
            }
            // 19:37 日志判据：此处各步骤独立包 runCatching，异常与计数全部落日志，
            // 用于定住「探针在打、其后步骤零日志」究竟是哪一行在吞执行流。
            runStep(now, "neutralizeWhiteNinePatch") { neutralizeWhiteNinePatch(decor) }
            runStep(now, "glLayerProbe") {
                val gl = ArrayList<View>()
                collectBySimpleName(decor, "DynamicBackgroundGLSurfaceView", gl)
                if (gl.isNotEmpty()) {
                    for (g in gl) {
                        // 06:39 实锤：GL surface 由 SurfaceFlinger 合成在窗口后面，窗口透区（顶部0~288）
                        // 透出微信默认白色聊天背景，壁纸位图盖住处不可见——画布探针/背景采样全 0 的原因。
                        // 判别实验：直接隐藏，白块应随之消失；若聊天页等背景异常再回退加开关。
                        if (g.tag != GL_HIDDEN_TAG) {
                            runCatching {
                                g.tag = GL_HIDDEN_TAG
                                g.visibility = View.GONE
                                HLog.i("$TAG GL动态背景层已隐藏(判别实验): ${g.javaClass.simpleName}#${idName(g)} ${g.width}x${g.height}")
                            }.onFailure { HLog.e("$TAG GL动态背景层隐藏失败: ${it.message}", it) }
                        }
                    }
                    if (now - lastGlLayerLogAt >= topDumpIntervalMs) {
                        lastGlLayerLogAt = now
                        for (g in gl) {
                            HLog.i("$TAG GL动态背景层: ${g.javaClass.simpleName}#${idName(g)} ${g.width}x${g.height} vis=${g.visibility} alpha=${g.alpha} willNotDraw=${g.willNotDraw()}")
                        }
                    }
                }
            }
            runStep(now, "enforceFirstScreen") { enforceFirstScreen(decor) }
            runStep(now, "neutralizeTaskBarBottomView") { neutralizeTaskBarBottomView(decor) }
            runStep(now, "anchorPanelBottomBar") { anchorPanelBottomBar(decor) }
            runStep(now, "ensureConversationOverdrawHook") { ensureConversationOverdrawHook(decor) }
            runStep(now, "pageTransitionGuard610") {
                neutralizeGradientViews(decor)
                reassertPagerUnderlays()
            }
            runStep(now, "overlayWindowForensics") { overlayWindowForensics(decor) }
            runStep(now, "titleBarProbe") { titleBarProbe(decor, now) }
            runStep(now, "decorStripProbe") { decorStripProbe(decor, now) }
            runStep(now, "actionBarProbe") { actionBarProbe(decor, now) }
            runStep(now, "panelProbe") { panelProbe(decor, now) }
            runStep(now, "chatStatusBarBand611") { enforceChatStatusBarBand(decor) }
            if (now - lastScrimAt >= SCRIM_INTERVAL_MS) {
                lastScrimAt = now
                pageScrim.entries.toList().forEach { (page, spec) ->
                    runCatching { applyContentScrim(page, spec.bitmap, spec.overlayColor, spec.isHome, log = false) }
                        .onFailure { HLog.e("$TAG 内容分层重扫失败: ${it.message}", it) }
                }
            }
        }
    }

    /** 5.5.90：灰条绘制者取证——16:03 日志 g6w 全程透明且灰条仍在（第 5 次证伪），绘制不走 view.background，
     *  改为 hook Canvas 画矩形/圆角矩形/纯色的调用，命中 #ededed / #7a7396 时抓调用栈，让绘制者自己现身。 */
    private val painterProbeSigs: MutableMap<String, Long> = Collections.synchronizedMap(HashMap())


    // ===== 679：按 11:42 日志的绘制者实锤改「按绘制点拦」，不再猜 view.background =====
    // 日志判据（verbose 11:08:21 起，覆盖到 11:42）：
    //   `灰条绘制者取证: color=#ffededed 来源=Paint.setColor/RecordingCanvas.drawPath <- TaskBarBottomView→onDraw` ×13
    //   `color=#ff7a7396 ... <- TaskBarBottomView→onDraw` ×4
    //   `color=#ffffffff 来源=NinePatchDrawable.draw 1017x174/1240x99 <- View.drawBackground` ×54
    // 截图原生像素（1240x2772）：主页 1000142846 y=115..295 纯 #EDEDED（正是 TaskBarBottomView 自绘的那条），
    //   最近 1000142847 y=2560 起 #7A7395（=#7a7396，同一个 TaskBarBottomView 在底部），通讯录行 #FFFFFF。
    // 结论：这两条带**不是** view.background 画的，前几轮清背景/清 ActionBar 子树都碰不到它；行白底是九宫格 .9。
    private var taskBarBlocked679 = 0
    private var lastTaskBarBlockLogAt = 0L
    private var topBandSkip679 = 0
    private var lastTopBandSkipLogAt = 0L
    private val rowWhiteByPainter = Collections.synchronizedMap(HashMap<View, Long>())
    private var rowWhiteByPainterCount = 0
    private var lastRowWhiteByPainterLogAt = 0L

    /** 这条带用的两种填充色：浅灰（#ededed/#f1f1f1）与紫灰（#7a7396，最近页底栏）。 */
    private fun isTaskBarBandColor(color: Int): Boolean {
        if (Color.alpha(color) != 255) return false
        if (color == 0xff7a7396.toInt()) return true
        return Color.red(color) >= 226 && Color.green(color) >= 226 && Color.blue(color) >= 226
    }

    /** 只在颜色命中灰/紫灰时才走栈扫描（否则 Paint.setColor 每帧上千次会拖慢）。 */
    private fun callerIsTaskBarOnDraw(): Boolean {
        val st = Throwable().stackTrace
        var i = 0
        while (i < st.size) {
            val f = st[i]
            val cn = f.className
            if (cn.endsWith("TaskBarBottomView") && f.methodName == "onDraw") return true
            if (cn == "android.view.View" || cn == "android.view.ViewGroup") return false
            i++
        }
        return false
    }

    /** 底色填充色（排除纯白：最近页的图标/文字是白的，不能一起弄没）。 */
    private fun isTaskBarBandFill(color: Int): Boolean =
        isTaskBarBandColor(color) && color != 0xffffffff.toInt()

    /** 被几何门槛拒掉的形状也留痕，避免下一轮又「静默不命中」。 */
    private fun noteRejectedBandShape(method: String, param: XC_MethodHook.MethodHookParam) {
        val sig = runCatching {
            when (val a0 = param.args.getOrNull(0)) {
                is android.graphics.RectF -> "$method ${a0.width().toInt()}x${a0.height().toInt()}"
                is android.graphics.Rect -> "$method ${a0.width()}x${a0.height()}"
                is android.graphics.Path -> {
                    val r = android.graphics.RectF()
                    a0.computeBounds(r, true)
                    "$method ${r.width().toInt()}x${r.height().toInt()}"
                }
                else -> method
            }
        }.getOrDefault(method)
        val now = SystemClock.elapsedRealtime()
        synchronized(taskBarRejectedShapes) {
            val last = taskBarRejectedShapes[sig]
            if (last != null && now - last < 60_000L) return
            taskBarRejectedShapes[sig] = now
            if (taskBarRejectedShapes.size > 32) taskBarRejectedShapes.clear()
        }
        HLog.i("$TAG 任务栏自绘形状未达门槛(680): $sig（要求宽≥500 高≥28）")
    }

    private val taskBarRejectedShapes: MutableMap<String, Long> = Collections.synchronizedMap(HashMap())

    /** 只挡「整条带」的填充，别挡图标/文字：图标也走 drawPath，只是尺寸小。 */
    private fun taskBarFillIsBand(method: String, param: XC_MethodHook.MethodHookParam): Boolean {
        when (method) {
            "drawRect", "drawRoundRect" -> {
                val a0 = param.args.getOrNull(0)
                if (a0 is android.graphics.RectF) return a0.width() >= 500f && a0.height() >= 28f
                if (a0 is android.graphics.Rect) return a0.width() >= 500 && a0.height() >= 28
                if (param.args.size >= 4) {
                    val l = param.args[0] as? Float ?: return false
                    val top = param.args[1] as? Float ?: return false
                    val r = param.args[2] as? Float ?: return false
                    val b = param.args[3] as? Float ?: return false
                    return (r - l) >= 500f && (b - top) >= 28f
                }
                return false
            }
            "drawPath" -> {
                val path = param.args.getOrNull(0) as? android.graphics.Path ?: return false
                val rect = android.graphics.RectF()
                return runCatching {
                    path.computeBounds(rect, true)
                    rect.width() >= 500f && rect.height() >= 28f
                }.getOrDefault(false)
            }
            else -> return true // drawPaint / drawColor / drawARGB / drawRGB：整块填充
        }
    }

    private fun blockTaskBarSelfDraw(param: XC_MethodHook.MethodHookParam, color: Int, what: String, method: String): Boolean {
        if (!isTaskBarBandFill(color)) return false
        if (!callerIsTaskBarOnDraw()) return false
        if (!taskBarFillIsBand(method, param)) {
            noteRejectedBandShape(method, param)
            return false
        }
        param.setResult(null)
        taskBarBlocked679++
        val now = SystemClock.elapsedRealtime()
        if (now - lastTaskBarBlockLogAt >= 3000L) {
            lastTaskBarBlockLogAt = now
            HLog.i(
                "$TAG 任务栏自绘底色已拦(679): $what #" + Integer.toHexString(color) +
                    " 调用者=TaskBarBottomView.onDraw 累计=$taskBarBlocked679"
            )
        }
        return true
    }

    /** 710b：底部残条日志节流 + 探针节流。 */
    private var lastBottomStripLogAt = 0L

    private fun isNeutralGrey710(c: Int): Boolean {
        if (Color.alpha(c) != 0xFF) return false
        val r = c shr 16 and 0xFF
        val g = c shr 8 and 0xFF
        val b = c and 0xFF
        return r == g && g == b && r in 0x30..0xF8
    }

    /** 710b：浅色底（灰/白都算）——底栏残条既可能是 #EDEDED 也可能是 #F7F7F7。 */
    private fun isLightFill710(c: Int): Boolean {
        if (Color.alpha(c) != 0xFF) return false
        val r = c shr 16 and 0xFF
        val g = c shr 8 and 0xFF
        val b = c and 0xFF
        return (r + g + b) / 3 >= 0x60
    }

    /** 710b：是否位于滚动容器内部（列表行一律受这条保护，绝不清）。 */
    private fun insideScrollable710(v: View): Boolean {
        var cur: View? = v.parent as? View
        var guard = 0
        while (cur != null && guard++ < 16) {
            if (cur is android.widget.AbsListView) return true
            val n = cur.javaClass.name
            if (n.contains("RecyclerView") || n.contains("ScrollView") || n.contains("PullDownListView")) return true
            cur = cur.parent as? View
        }
        return false
    }

    /**
     * 710b：底栏隐藏后，原底栏位置还留着一条原生灰（用户真机截图：通讯录最底部一整条灰）。
     *
     * 判据收紧成「整宽 + 完全贴在屏幕最底 + 高度不超过 1.6 个底栏高 + 中性灰」，
     * 只清这种条状视图：纯白列表行（r≠0xED 那种）、图标、自家壁纸层一律不碰。
     * 没命中时打一条探针，把最底部那几层的类名/尺寸/底色点出来 —— 万一绘制者不是独立视图，
     * 下一份日志就能直接看出是谁在托底。
     */
    private fun clearBottomBarStrip710(decor: ViewGroup, dumpCandidates: Boolean) {
        val decorW = decor.width.coerceAtLeast(decor.measuredWidth)
        val decorH = decor.height.coerceAtLeast(decor.measuredHeight)
        if (decorW <= 0 || decorH <= 0) return
        val density = decor.resources.displayMetrics.density
        val barH = (density * 48f).toInt().coerceAtLeast(120)
        var cleared = 0
        val hits = ArrayList<String>()
        val candidates = ArrayList<String>()
        // 710c：无条件记录底部所有层（含列表内部、含 bg=null），灰条找不到绘制者时靠它点名。
        val all = ArrayList<String>()
        fun walk(v: View, d: Int) {
            if (d > 18) return
            val w = v.width.coerceAtLeast(v.measuredWidth)
            val h = v.height.coerceAtLeast(v.measuredHeight)
            if (v !== decor && w > 0 && h > 0) {
                val top = absTopOfView(v)
                val bottom = top + h
                val bg = v.background
                if (all.size < 12 && top >= decorH - barH * 2 && h <= barH * 2) {
                    val parent = (v.parent as? View)?.javaClass?.simpleName ?: "-"
                    all.add(
                        "${v.javaClass.simpleName}#${idName(v)} ${w}x$h top=$top bg=${descDrawable(bg)}" +
                            " shown=${v.isShown} inList=${insideScrollable710(v)} parent=$parent"
                    )
                }
                val inBand = bottom >= decorH - 6 && top >= decorH - barH * 2 &&
                    h <= barH * 16 / 10 && w >= decorW * 9 / 10
                if (inBand && v.isShown && v !is ImageView && bg != null &&
                    !(bg is ColorDrawable && Color.alpha(bg.color) == 0) &&
                    !isOurWallpaperDrawable(bg) && bg !is ScreenAnchoredBitmapDrawable &&
                    bg !is PagerStripWallpaperDrawable && !homeImageHosts.containsKey(v) &&
                    !insideScrollable710(v)
                ) {
                    val desc = descDrawable(bg)
                    if (candidates.size < 10) {
                        candidates.add("${v.javaClass.simpleName}#${idName(v)} ${w}x$h top=$top bg=$desc")
                    }
                    // 710b 二轮：残条不一定是纯灰 ColorDrawable（微信底栏容器普遍是
                    // StateListDrawable/InsetDrawable 的 selector）。几何判据已经很紧（整宽 +
                    // 完全贴底 + 高度≈一个底栏 + 不在任何滚动容器里），这里放宽到「浅色纯色 /
                    // 选择器 / 内边距底」，其余（图标、文字行、自家壁纸层）全部排除在外。
                    val clearable = when {
                        bg is ColorDrawable -> isLightFill710(bg.color)
                        else -> bg.javaClass.simpleName.let {
                            it.contains("StateList") || it.contains("Inset") || it.contains("Layer")
                        }
                    }
                    if (clearable) {
                        if (!replacedBackgrounds.containsKey(v)) replacedBackgrounds[v] = bg
                        runCatching { v.setBackgroundColor(Color.TRANSPARENT) }
                        clearedViews.add(v)
                        rowScrimDrawables.remove(v)
                        cleared++
                        if (hits.size < 8) {
                            hits.add("${v.javaClass.simpleName}#${idName(v)} ${w}x$h top=$top bg=$desc")
                        }
                    }
                }
            }
            if (v is ViewGroup) for (i in 0 until v.childCount) v.getChildAt(i)?.let { walk(it, d + 1) }
        }
        walk(decor, 0)
        val now = SystemClock.uptimeMillis()
        if (cleared > 0 && now - lastBottomStripLogAt >= 3000L) {
            lastBottomStripLogAt = now
            HLog.i("$TAG 底部底栏残条已清透明(710b): $cleared 处 [${hits.joinToString(" / ")}]")
        } else if (cleared == 0 && dumpCandidates && now - lastBottomStripLogAt >= 30000L) {
            lastBottomStripLogAt = now
            HLog.i(
                "$TAG 底部残条探针(710b): 底栏高≈$barH 屏高=$decorH 未命中，候选=" +
                    (if (candidates.isEmpty()) "无" else candidates.joinToString(" / "))
            )
            HLog.i(
                "$TAG 底部全层转储(710c): " + (if (all.isEmpty()) "空" else all.joinToString(" / "))
            )
        }
    }

    private fun absTopOfView(v: View): Int {
        var top = 0
        var cur: View? = v
        var guard = 0
        while (cur != null && guard++ < 24) {
            top += cur.top
            cur = cur.parent as? View
        }
        return top
    }

    private fun topViewOf(v: View?): View? {
        var cur: View? = v
        var guard = 0
        while (cur != null && guard++ < 30) {
            val parent = cur.parent as? View ?: return cur
            cur = parent
        }
        return cur
    }

    /** owner 是否在「主窗口（含 pager 的那棵树）」里——用来挡掉主题设置页/聊天页/小程序等其它窗口。 */
    private fun insidePagerWindow(owner: View): Boolean {
        val pager = lastHomePager?.get() ?: return false
        val a = topViewOf(owner) ?: return false
        val b = topViewOf(pager) ?: return false
        return a === b
    }

    /** owner 是否在我们铺壁纸的那几页里（页面本体 / 页面内视图）。 */
    private fun insideMyPages(owner: View): Boolean {
        val pager = lastHomePager?.get()
        var cur: View? = owner
        var guard = 0
        while (cur != null && guard++ < 30) {
            if (cur === pager) return true
            if (pageDrawables.containsKey(cur) || pageScrim.containsKey(cur) || pageSlots.containsKey(cur)) return true
            if (cur.javaClass.simpleName == "LayoutListenerView") return true
            cur = cur.parent as? View
        }
        return false
    }

    /**
     * 顶带里「整宽 + 不透明浅灰/白」的 ColorDrawable 背景：直接不让它画。
     * 只认同一窗口（含 pager）里的、位置在顶部 20% 屏高内、宽≥600、高≤520 的 owner，避免误伤其它页面。
     */
    private fun topBandGreyGuard(param: XC_MethodHook.MethodHookParam, d: ColorDrawable) {
        val c = runCatching { d.color }.getOrNull() ?: return
        if (Color.alpha(c) != 255) return
        if (Color.red(c) < 226 || Color.green(c) < 226 || Color.blue(c) < 226) return
        val owner = runCatching { d.callback as? View }.getOrNull() ?: return
        if (!owner.isAttachedToWindow) return
        if (!insidePagerWindow(owner)) return
        val w = owner.width.coerceAtLeast(owner.measuredWidth)
        val h = owner.height.coerceAtLeast(owner.measuredHeight)
        if (w < 600 || h <= 0 || h > 520) return
        if (absTopOfView(owner) >= 430) return
        param.setResult(null)
        topBandSkip679++
        val now = SystemClock.elapsedRealtime()
        if (now - lastTopBandSkipLogAt >= 3000L) {
            lastTopBandSkipLogAt = now
            HLog.i(
                "$TAG 顶带背景已跳过(679): ${owner.javaClass.simpleName}#${idName(owner)} ${w}x$h" +
                    " top=${absTopOfView(owner)} color=#" + Integer.toHexString(c) + " 累计=$topBandSkip679"
            )
        }
    }

    /**
     * 行白底：日志里 54 次 `color=#ffffffff 来源=NinePatchDrawable.draw 1017x174 / 1240x99`，
     * 说明行白是九宫格 .9 背景在 draw 时画出来的。这里直接按「绘制者 owner」找到那个 View，
     * 把它的背景一次性换成我们那条 6dp 缝的透明行遮罩（保留 .9 自带的左/上缩进），
     * 并登记 clearedViews / rowScrimDrawables —— 宿主再写回时由 613 引用级守卫与 reassertRowScrims 压回。
     * 作用域：owner 必须在我们铺壁纸的那几页里、位于内容区（absTop≥200）、绘制尺寸宽≥600、高 40..340。
     */
    private fun clearWhiteRowByPainter(d: android.graphics.drawable.NinePatchDrawable, bw: Int, bh: Int) {
        if (bh < 40 || bh > 340 || bw < 600) return
        val owner = runCatching { d.callback as? View }.getOrNull() ?: return
        if (!owner.isAttachedToWindow) return
        if (absTopOfView(owner) < 200) return
        if (!insideMyPages(owner)) return
        if (rowWhiteByPainter.containsKey(owner)) return
        if (rowWhiteByPainter.size > 600) rowWhiteByPainter.clear()
        rowWhiteByPainter[owner] = SystemClock.elapsedRealtime()
        mainHandler.post {
            runCatching {
                val bg = owner.background
                if (!replacedBackgrounds.containsKey(owner)) replacedBackgrounds[owner] = bg
                val kept = rowPaddingOf(owner)
                val gap = (ROW_GAP_DP * owner.resources.displayMetrics.density).toInt()
                val inset = InsetDrawable(ColorDrawable(Color.TRANSPARENT), 0, 0, 0, gap)
                owner.background = inset
                rowScrimDrawables[owner] = inset
                rowPaddingKept[owner] = kept
                applyKeptPadding(owner, kept)
                // 710：行遮罩与「清透明」只能登记一个，否则两个表互相覆盖（详见 desiredBackgroundFor）。
                clearedViews.remove(owner)
                rowWhiteByPainterCount++
                val now = SystemClock.elapsedRealtime()
                if (now - lastRowWhiteByPainterLogAt >= 2000L) {
                    lastRowWhiteByPainterLogAt = now
                    HLog.i(
                        "$TAG 行白底按绘制者清底(679): ${owner.javaClass.simpleName}#${idName(owner)}" +
                            " 视=${owner.width}x${owner.height} 绘制=${bw}x$bh 累计=$rowWhiteByPainterCount"
                    )
                }
            }.onFailure { HLog.e("$TAG 行白底清底异常(679): ${it.message}") }
        }
    }

    private fun installGreyPainterProbe() {
        val grey = 0xffededed.toInt()
        val purple = 0xff7a7396.toInt()
        val white = 0xffffffff.toInt()
        val canvasClasses = ArrayList<Class<*>>()
        canvasClasses.add(Canvas::class.java)
        runCatching { canvasClasses.add(Class.forName("android.graphics.BaseCanvas")) }
        runCatching { canvasClasses.add(Class.forName("android.graphics.RecordingCanvas")) }
        // 16:46 日志实证：只挂 Canvas 时取证 0 命中——硬件渲染走 RecordingCanvas/BaseCanvas 的 override，
        // Canvas 本类声明根本不被调用。三个类都挂，方法扩到 drawRect/drawRoundRect/drawPath/drawPaint/drawColor。
        val drawMethods = listOf("drawRect", "drawRoundRect", "drawPath", "drawPaint", "drawColor")
        // 07:22 复测：GL 已隐藏(vis=8)白块原样——GL 嫌疑证伪。排查到 drawRGB/drawARGB 是独立方法名
        // 未挂（drawColor 不覆盖它们），微信 GradientColorBackgroundView（bg=null 自绘渐变头）嫌疑最大。
        val rgbMethods = listOf("drawRGB", "drawARGB")
        for (clz in canvasClasses) {
            for (method in rgbMethods) {
                runCatching {
                    XposedBridge.hookAllMethods(clz, method, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val color = when (method) {
                                "drawRGB" -> {
                                    val r = param.args.getOrNull(0) as? Int ?: return
                                    val g = param.args.getOrNull(1) as? Int ?: return
                                    val b = param.args.getOrNull(2) as? Int ?: return
                                    (0xff shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)
                                }
                                else -> param.args.getOrNull(0) as? Int ?: return
                            }
                            if (color != grey && color != purple) {
                                if (color != white) return
                                val cvs = param.thisObject as? Canvas ?: return
                                if (cvs.width < 800 || cvs.height < 100) return
                            }
                            if (blockTaskBarSelfDraw(param, color, "${clz.simpleName}.$method", method)) return
                            reportPainter(color, "${clz.simpleName}.$method")
                        }
                    })
                    HLog.i("$TAG 灰条绘制者取证已挂: ${clz.name}.$method")
                }.onFailure { HLog.e("$TAG 灰条绘制者取证挂载失败 ${clz.name}.$method: ${it.message}", it) }
            }
        }
        for (clz in canvasClasses) {
            for (method in drawMethods) {
                runCatching {
                    XposedBridge.hookAllMethods(clz, method, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val color = when {
                                param.args.lastOrNull() is Paint -> (param.args.last() as Paint).color
                                method == "drawColor" && param.args.isNotEmpty() -> param.args[0] as? Int ?: return
                                else -> return
                            }
                            if (color != grey && color != purple) {
                                // 白色通道：只收大画布（宽≥800 且高≥200），避免行/小控件白底刷屏；
                                // 17:33 截图主页顶部 0~285 纯白块，疑为列表/标题窗口自绘头部。
                                if (color != white) return
                                val cvs = param.thisObject as? Canvas ?: return
                                if (cvs.width < 800 || cvs.height < 100) return
                            }
                            if (blockTaskBarSelfDraw(param, color, "${clz.simpleName}.$method", method)) return
                            reportPainter(color, "${clz.simpleName}.$method")
                        }
                    })
                    HLog.i("$TAG 灰条绘制者取证已挂: ${clz.name}.$method")
                }.onFailure { HLog.e("$TAG 灰条绘制者取证挂载失败 ${clz.name}.$method: ${it.message}", it) }
            }
        }
        // 5.5.97：20:20 dump 无白色背景、白栈仍 0——白色不走画笔，剩余盲区=drawBitmap 位图与
        // NinePatchDrawable（native 绘制，Java Canvas 通道抓不到）。位图通道按采样像素色值点名。
        runCatching {
            XposedBridge.hookAllMethods(Canvas::class.java, "drawBitmap", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val cvs = param.thisObject as? Canvas ?: return
                    if (cvs.width < 800) return
                    val bmp = param.args.firstOrNull() as? android.graphics.Bitmap ?: return
                    if (bmp.width <= 0 || bmp.height <= 0) return
                    val hit = runCatching {
                        listOf(
                            bmp.getPixel(0, 0),
                            bmp.getPixel(bmp.width - 1, 0),
                            bmp.getPixel(0, bmp.height - 1),
                            bmp.getPixel(bmp.width / 2, bmp.height / 2)
                        ).firstOrNull { c ->
                            Color.alpha(c) == 255 && Color.red(c) >= 224 && Color.green(c) >= 224 && Color.blue(c) >= 224
                        }
                    }.getOrNull() ?: return
                    reportPainter(hit, "Canvas.drawBitmap ${bmp.width}x${bmp.height}")
                }
            })
            HLog.i("$TAG 灰条绘制者取证已挂: Canvas.drawBitmap")
        }.onFailure { HLog.e("$TAG 灰条绘制者取证挂载失败 Canvas.drawBitmap: ${it.message}", it) }
        runCatching {
            XposedBridge.hookAllMethods(android.graphics.drawable.NinePatchDrawable::class.java, "draw", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val d = param.thisObject as? android.graphics.drawable.NinePatchDrawable ?: return
                    val r = d.bounds
                    if (r.width() < 800) return
                    val bmp = runCatching {
                        val m = d.javaClass.getMethod("getBitmap")
                        m.isAccessible = true
                        m.invoke(d) as? android.graphics.Bitmap
                    }.getOrNull()
                    val hit = if (bmp != null && bmp.width > 0) runCatching {
                        listOf(
                            bmp.getPixel(0, 0),
                            bmp.getPixel(bmp.width - 1, 0),
                            bmp.getPixel(bmp.width / 2, bmp.height / 2)
                        ).firstOrNull { c ->
                            Color.alpha(c) == 255 && Color.red(c) >= 224 && Color.green(c) >= 224 && Color.blue(c) >= 224
                        }
                    }.getOrNull() else null
                    if (hit != null) clearWhiteRowByPainter(d, r.width(), r.height())
                    reportPainter(hit ?: 0xffffffff.toInt(), "NinePatchDrawable.draw ${r.width()}x${r.height()}")
                }
            })
            HLog.i("$TAG 灰条绘制者取证已挂: NinePatchDrawable.draw")
        }.onFailure { HLog.e("$TAG 灰条绘制者取证挂载失败 NinePatchDrawable.draw: ${it.message}", it) }
        // 5.5.98：20:5x 主页白块纯白矩形、画笔/位图/九宫格三通道全 0——剩余盲区=Paint 带 Shader
        //（渐变白走 setShader，setColor 不写白色）。谁把白/灰渐变装上画笔就点名。
        runCatching {
            XposedBridge.hookAllMethods(Paint::class.java, "setShader", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val shader = param.args.firstOrNull() ?: return
                    var hit: Int? = null
                    var kind = ""
                    if (shader is android.graphics.LinearGradient) {
                        kind = "LinearGradient"
                        runCatching {
                            val f = android.graphics.LinearGradient::class.java.getDeclaredField("mColors")
                            f.isAccessible = true
                            val cols = f.get(shader) as? IntArray
                            hit = cols?.firstOrNull { c ->
                                Color.alpha(c) == 255 && Color.red(c) >= 224 && Color.green(c) >= 224 && Color.blue(c) >= 224
                            }
                        }
                    } else if (shader is android.graphics.BitmapShader) {
                        kind = "BitmapShader"
                        runCatching {
                            val f = android.graphics.BitmapShader::class.java.getDeclaredField("mBitmap")
                            f.isAccessible = true
                            val bmp = f.get(shader) as? android.graphics.Bitmap
                            hit = bmp?.getPixel(0, 0)?.takeIf { c ->
                                Color.alpha(c) == 255 && Color.red(c) >= 224 && Color.green(c) >= 224 && Color.blue(c) >= 224
                            }
                        }
                    }
                    if (hit != null) reportPainter(hit!!, "Paint.setShader $kind")
                }
            })
            HLog.i("$TAG 灰条绘制者取证已挂: Paint.setShader")
        }.onFailure { HLog.e("$TAG 灰条绘制者取证挂载失败 Paint.setShader: ${it.message}", it) }
        // 上色取证：谁把画笔设成灰条同色，直接抓（覆盖 drawBitmap/九宫格等不走 drawRect 的通道）。
        runCatching {
            XposedBridge.hookAllMethods(Paint::class.java, "setColor", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val color = param.args.firstOrNull() as? Int ?: return
                    if (color != grey && color != purple) return
                    if (isTaskBarBandColor(color) && callerIsTaskBarOnDraw()) {
                        // 680：12:25 日志 25 次命中这一条（条件成立），但只记不改 → 灰带还在。
                        // 现在把这一支画笔置透明：TaskBarBottomView.onDraw 里 setColor 是「先设色再画」，
                        // 文字/图标是另设色（纯白留给它，见 isTaskBarBandFill）→ 只弄掉底色。
                        if (isTaskBarBandFill(color)) {
                            param.args[0] = Color.TRANSPARENT
                            taskBarBlocked679++
                            val now = SystemClock.elapsedRealtime()
                            if (now - lastTaskBarBlockLogAt >= 3000L) {
                                lastTaskBarBlockLogAt = now
                                HLog.i(
                                    "$TAG 任务栏自绘底色已拦(680): Paint.setColor #" + Integer.toHexString(color) +
                                        "→透明 调用者=TaskBarBottomView.onDraw 累计=$taskBarBlocked679"
                                )
                            }
                        } else {
                            val now = SystemClock.elapsedRealtime()
                            if (now - lastTaskBarBlockLogAt >= 3000L) {
                                lastTaskBarBlockLogAt = now
                                HLog.i("$TAG 任务栏自绘画笔取证(680): Paint.setColor #" + Integer.toHexString(color) + "（纯白保留）调用者=TaskBarBottomView.onDraw")
                            }
                        }
                        return
                    }
                    reportPainter(color, "Paint.setColor")
                }
            })
            HLog.i("$TAG 灰条绘制者取证已挂: Paint.setColor")
        installWindowDecorCollector()
        }.onFailure { HLog.e("$TAG 灰条绘制者取证挂载失败 Paint.setColor: ${it.message}", it) }
        // 679：顶带里那 23 次 `#ffededed 来源=View.drawBackground/ColorDrawable.draw` 也用同一条守卫挡掉。
        runCatching {
            XposedBridge.hookAllMethods(ColorDrawable::class.java, "draw", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val d = param.thisObject as? ColorDrawable ?: return
                    runCatching { topBandGreyGuard(param, d) }
                        .onFailure { HLog.e("$TAG 顶带背景拦截异常(679): ${it.message}") }
                }
            })
            HLog.i("$TAG 顶带背景守卫已挂(679): ColorDrawable.draw")
        }.onFailure { HLog.e("$TAG 顶带背景守卫挂载失败(679): ${it.message}", it) }
    }

    private fun reportPainter(color: Int, source: String) {
        val st = Throwable().stackTrace
        var top: String? = null
        val chain = ArrayList<String>()
        for (f in st) {
            val cn = f.className
            if (cn.startsWith("com.aeonlab") || cn.startsWith("de.robv") || cn.startsWith("LSP")) continue
            val item = "${cn.substringAfterLast('.')}→${f.methodName}(${f.fileName ?: "?"}:${f.lineNumber})"
            if (chain.size < 8) chain.add(item)
            if (top == null && cn.startsWith("com.tencent.")) top = item
        }
        val sig = "$source|$color|" + (top ?: chain.firstOrNull() ?: "?")
        val now = SystemClock.elapsedRealtime()
        synchronized(painterProbeSigs) {
            val last = painterProbeSigs[sig]
            if (last != null && now - last < 60_000L) return
            painterProbeSigs[sig] = now
            if (painterProbeSigs.size > 64) painterProbeSigs.clear()
        }
        HLog.i("$TAG 灰条绘制者取证: color=#${Integer.toHexString(color)} 来源=$source 调用栈=${chain.joinToString(" <- ")}")
    }

    /**
     * 5.5.89：15:14 日志行为证据——statusBarBackground 清后 280/285 次采样透明（排除），而
     * FirstScreenFrameLayout#g6w 清一次后 32/33 次转储都是 #ededed：微信在持续把它刷回，
     * 它是全 decor 唯一反复变灰的视图且位置正对标题灰条。挂 PreDraw 每帧重清 + 禁自绘。
     * 可证伪：若灰条仍在且转储里 g6w 始终透明，则灰底走 dispatchDraw 自绘（下一轮换 alpha 方案）。
     */
    private fun enforceFirstScreen(decor: ViewGroup) {
        val fs = findViewByClassName(decor, "FirstScreenFrameLayout") ?: return
        if (fs.tag == FIRST_SCREEN_TAG) return
        fs.tag = FIRST_SCREEN_TAG
        runCatching { fs.setWillNotDraw(true) }
        runCatching {
            clearedViews.add(fs)
            fs.setBackgroundColor(Color.TRANSPARENT)
            fs.viewTreeObserver.addOnPreDrawListener(object : android.view.ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    val bg = fs.background
                    if (bg is ColorDrawable && Color.alpha(bg.color) == 0) return true
                    runCatching { fs.setBackgroundColor(Color.TRANSPARENT) }
                    val now = SystemClock.uptimeMillis()
                    if (now - lastFirstScreenRepaintLogAt >= 3000L) {
                        lastFirstScreenRepaintLogAt = now
                        HLog.i("$TAG 宿主刷回首屏灰底（当前=${descDrawable(bg)}），PreDraw 已重清: ${fs.javaClass.simpleName}#${idName(fs)}")
                    }
                    return true
                }
            })
            HLog.i("$TAG 首屏容器灰底已挂 PreDraw 每帧重压+禁自绘: ${fs.javaClass.simpleName}#${idName(fs)}")
        }
    }

    private val WHITE_NINEPATCH_TAG = Any()
    private val GL_HIDDEN_TAG = Any()

    /** 21:31 日志实锤：主页顶部白块= NinePatchDrawable 背景（View.drawBackground native 绘制，
     *  画布钩子看不到）。尺寸 1240x100（状态区）/1240x174（标题卡区）/1240x2772（列表项 .9 白色边条）。
     *  顶部 300px 内、宽≥800、背景为白/灰采样命中的 NinePatchDrawable 一律清透明。 */
    private var lastWhiteNinePatchLogAt = 0L

    private fun neutralizeWhiteNinePatch(decor: ViewGroup) {
        val stack = ArrayList<View>()
        stack.add(decor)
        val inventory = ArrayList<String>()
        var inventoryLogged = false
        val nowAll = SystemClock.uptimeMillis()
        if (nowAll - lastWhiteNinePatchLogAt >= topDumpIntervalMs) inventoryLogged = true
        while (stack.isNotEmpty()) {
            val v = stack.removeAt(stack.size - 1)
            if (v.tag != WHITE_NINEPATCH_TAG) {
                var absTop = 0
                runCatching {
                    var cur: View = v
                    var guard = 0
                    while (guard++ < 20) {
                        val p = cur.parent as? View ?: break
                        absTop += cur.top
                        cur = p
                    }
                }
                val d = runCatching { v.background }.getOrNull()
                if (absTop <= 300 && d != null) {
                    var nine: android.graphics.drawable.NinePatchDrawable? = d as? android.graphics.drawable.NinePatchDrawable
                    var wrap = ""
                    if (nine == null && d is android.graphics.drawable.InsetDrawable) {
                        nine = runCatching { d.drawable as? android.graphics.drawable.NinePatchDrawable }.getOrNull()
                        if (nine != null) wrap = "Inset包"
                    }
                    var whiteHit = false
                    var samples = ""
                    if (v.width >= 300) {
                        runCatching {
                            when (d) {
                                is ColorDrawable -> {
                                    samples = "Color#" + Integer.toHexString(d.color)
                                    whiteHit = Color.alpha(d.color) == 255 && Color.red(d.color) >= 224 && Color.green(d.color) >= 224 && Color.blue(d.color) >= 224
                                }
                                is android.graphics.drawable.BitmapDrawable -> {
                                    val bmp = d.bitmap
                                    if (bmp != null && bmp.width > 0 && bmp.height > 0) {
                                        val c0 = bmp.getPixel(0, 0)
                                        val c1 = bmp.getPixel(bmp.width - 1, 0)
                                        val c2 = bmp.getPixel(0, bmp.height / 2)
                                        samples = "Bmp#" + Integer.toHexString(c0) + "/" + Integer.toHexString(c1) + "/" + Integer.toHexString(c2)
                                        whiteHit = listOf(c0, c1, c2).any { c ->
                                            Color.alpha(c) == 255 && Color.red(c) >= 224 && Color.green(c) >= 224 && Color.blue(c) >= 224
                                        }
                                    }
                                }
                                is android.graphics.drawable.GradientDrawable -> {
                                    runCatching {
                                        val c = d.color?.defaultColor
                                        if (c != null) {
                                            samples = "Grad#" + Integer.toHexString(c)
                                            whiteHit = Color.alpha(c) == 255 && Color.red(c) >= 224 && Color.green(c) >= 224 && Color.blue(c) >= 224
                                        }
                                    }
                                }
                            }
                        }
                        if (nine != null) {
                            // 21:31 实锤 .9 白底是主嫌疑，不看采样直接清
                            whiteHit = true
                            samples = "NinePatch"
                        }
                    }
                    if (absTop <= 300 && v.width >= 800 && nine != null) {
                        runCatching {
                            v.background = ColorDrawable(Color.TRANSPARENT)
                            v.tag = WHITE_NINEPATCH_TAG
                            if (inventoryLogged) {
                                HLog.i("$TAG 顶部白底九宫格已清$wrap: ${v.javaClass.simpleName}#${idName(v)} ${v.width}x${v.height} top=$absTop samples=$samples")
                            }
                        }
                    } else if (whiteHit && v.width >= 800) {
                        runCatching {
                            v.background = ColorDrawable(Color.TRANSPARENT)
                            v.tag = WHITE_NINEPATCH_TAG
                            if (inventoryLogged) {
                                HLog.i("$TAG 顶部白底已清透明: ${v.javaClass.simpleName}#${idName(v)} ${v.width}x${v.height} top=$absTop samples=$samples")
                            }
                        }
                    }
                    if (inventoryLogged && inventory.size < 40) {
                        inventory.add("top=$absTop ${v.javaClass.simpleName}#${idName(v)} ${v.width}x${v.height} bg=$d 样本=$samples")
                    }
                }
            }
            if (v is ViewGroup) for (i in 0 until v.childCount) stack.add(v.getChildAt(i))
        }
        if (inventoryLogged) {
            lastWhiteNinePatchLogAt = nowAll
            if (inventory.isNotEmpty()) {
                inventory.forEach { HLog.i("$TAG 顶部背景全量: $it") }
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
        // 710：applyEarlyWindowBackground 写的是 window 底，只清 decor 会让设置页 / 关页态继续透出壁纸。
        replacedWindowBackgrounds.entries.toList().forEach { (window, background) ->
            runCatching { window.setBackgroundDrawable(background) }
        }
        replacedWindowBackgrounds.clear()
        lastTab = null
        // 710：hiddenViews 已在上面全部还原成 VISIBLE，标记必须同步复位。
        tabHiddenByUs = false
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

    /**
     * 5.5.92：17:11 取证栈点名——灰条绘制者是 TaskBarBottomView.onDraw：
     * Paint.setColor(#ffededed) + drawPath（圆角矩形），面板页同一视图画 #7a7396。
     * 它不走 view.background，此前五轮清底/替换全部无效。禁自绘（标题文字/图标是子 View，不受影响；
     * 若文字也画在 onDraw 里会一起消失——可证伪）。主页状态栏区的纯白与灰条同源，禁绘后一并由真机判别。
     */
    private val taskBarViewsHandled: MutableSet<View> =
        Collections.newSetFromMap(WeakHashMap<View, Boolean>())

    // ===== 608 新增状态（原版基线上重做，改动只服务三处已知问题）=====
    private val rowPaddingKept: MutableMap<View, IntArray> =
        Collections.synchronizedMap(WeakHashMap<View, IntArray>())
    /** 710q：本轮被幂等跳过的通栏行重写次数（只用于日志，不参与任何判定）。 */
    @Volatile private var skippedRowRewrite710q = 0
    /** 711 取证：行缩进探针的时间节流与上一次快照（只读，不改任何布局）。 */
    @Volatile private var rowProbeAt711 = 0L
    private val rowProbeSeen711: MutableMap<View, IntArray> =
        Collections.synchronizedMap(WeakHashMap<View, IntArray>())
    /** 712：每行 `cgi` 曾经亲眼出现过的正内缩值（只来自该行自己的历史观测）。 */
    private val rowInsetSeen712: MutableMap<View, Int> =
        Collections.synchronizedMap(WeakHashMap<View, Int>())
    @Volatile private var lastInsetPinLog712 = 0L
    /** 712c：绘制前回钉用的「最后处理过的页面」与节流时间（只用于把回钉提前到画之前）。 */
    private var lastScrimPage712: java.lang.ref.WeakReference<View>? = null
    @Volatile private var pinBeforeDrawAt712 = 0L
    /** 712c：每行累计回钉次数（防死循环：超 20 次就放弃，避免和宿主抢绘制）。 */
    private val rowInsetPinTries712: MutableMap<View, Int> =
        Collections.synchronizedMap(WeakHashMap<View, Int>())

    /**
     * 711 取证：把当前页里每个「行」的缩进链路打出来。
     *
     * 背景：1024 真机日志证明发现页/我的页的行**从未被我们的通栏行逻辑碰过**
     * （`通栏行压半透明 0 个`、`钉缩进` 零样本），但截图像素测量显示 6 行里有 2 行
     * 图标+标题整体右移 48px（≈16dp），即那 4 行的左缩进是 0。
     * 所以要找出：行自身 padding、背景 .9 的 padding、第一个孩子的 left 三者里，
     * 究竟哪一个是 0、以及它在什么时刻被改（本函数只读 + 打印差异，绝不写回）。
     */
    private fun probeRowInsets711(page: View, pageWidth: Int) {
        val now = SystemClock.uptimeMillis()
        if (now - rowProbeAt711 < 4000L) return
        rowProbeAt711 = now
        val rows = ArrayList<View>()
        runCatching { collectRows711(page, pageWidth, rows, 0) }
            .onFailure { HLog.e("$TAG 行缩进取证异常(711): ${it.message}") }
        if (rows.isEmpty()) return
        rows.sortBy { it.top }
        val sb = StringBuilder()
        sb.append("$TAG 行缩进取证(711) 页=${page.javaClass.simpleName} 行=${rows.size}")
        for (v in rows.take(10)) {
            val lp = v.layoutParams
            val pad = intArrayOf(v.paddingLeft, v.paddingTop, v.paddingRight, v.paddingBottom)
            val bg = v.background
            val bgPad = IntArray(4)
            if (bg != null) {
                val r = android.graphics.Rect()
                if (runCatching { bg.getPadding(r) }.getOrDefault(false)) {
                    bgPad[0] = r.left; bgPad[1] = r.top; bgPad[2] = r.right; bgPad[3] = r.bottom
                }
            }
            val c0 = (v as? ViewGroup)?.let { g -> if (g.childCount > 0) g.getChildAt(0) else null }
            val cL = c0?.left ?: -1
            sb.append(
                "\n$TAG   行 ${v.javaClass.simpleName}#${idName(v)} ${v.width}x${v.height} top=${v.top}" +
                    " pad=${pad.toList()} bg=${bg?.javaClass?.simpleName} bgPad=${bgPad.toList()}" +
                    " 子数=${(v as? ViewGroup)?.childCount ?: -1} 子0=${c0?.javaClass?.simpleName}@left=$cL 宽=${c0?.width ?: -1}" +
                    " lp=${lp?.javaClass?.simpleName}:${lp?.width}x${lp?.height}" +
                    " 我们换底=${rowScrimDrawables.containsKey(v)}"
            )
            val key = intArrayOf(pad[0], pad[2], cL, v.width)
            val old = rowProbeSeen711.put(v, key)
            if (old != null && !old.contentEquals(key)) {
                sb.append(" [缩进被改 前=${old.toList()}]")
            }
            // 712：链式取证——顺着「第一个有孩子的 ViewGroup」往下走 4 层，
            // 把每层的 left/尺寸/padding/背景/背景 padding 一起打出来。
            // 目的：50px 左内缩究竟落在 (a) 行 padding、(b) cgi 的 padding、
            // (c) 内容层 View 的 left，还是 (d) 被我们清掉的 .9 背景。只读。
            var cur: View? = v
            var lvl = 0
            while (cur != null && lvl < 4) {
                val g = cur as? ViewGroup
                if (g == null) break
                var next: View? = null
                for (i in 0 until g.childCount) {
                    val c = g.getChildAt(i)
                    if (c is ViewGroup && c.childCount > 0 && c.width > 0) {
                        next = c
                        break
                    }
                }
                if (next == null) break
                val cp = intArrayOf(next.paddingLeft, next.paddingTop, next.paddingRight, next.paddingBottom)
                val cbg = next.background
                val cbp = IntArray(4)
                if (cbg != null) {
                    val r2 = android.graphics.Rect()
                    if (runCatching { cbg.getPadding(r2) }.getOrDefault(false)) {
                        cbp[0] = r2.left; cbp[1] = r2.top; cbp[2] = r2.right; cbp[3] = r2.bottom
                    }
                }
                sb.append(
                    "\n$TAG     链${lvl + 1} ${next.javaClass.simpleName}#${idName(next)}" +
                        " left=${next.left} ${next.width}x${next.height} pad=${cp.toList()}" +
                        " bg=${cbg?.javaClass?.simpleName} bgPad=${cbp.toList()}" +
                        " 子数=${(next as? ViewGroup)?.childCount ?: -1}"
                )
                cur = next
                lvl++
            }
        }
        HLog.i(sb.toString())
    }

    /**
     * 712c：把 [pinRowInsets712] 放到 OnPreDraw 里跑，让「回钉」发生在这一帧画出来之前。
     *
     * 用户 1027 实测：错位出现后约 1~2 秒才被压回对齐（那是 1.2 秒巡检的延迟）。
     * 这里用 100ms 节流在绘制前补一枪——只为把延迟从「秒级」压到「肉眼看不见」，
     * 判定逻辑完全复用 pinRowInsets712，不新增任何判定条件。
     */
    private fun pinRowInsetsBeforeDraw712() {
        val now = SystemClock.uptimeMillis()
        if (now - pinBeforeDrawAt712 < 100L) return
        pinBeforeDrawAt712 = now
        val pg = lastScrimPage712?.get() ?: return
        if (!pg.isAttachedToWindow) return
        val w = pg.width
        if (w <= 0) return
        pinRowInsets712(pg, w)
    }

    /**
     * 712：行内缩回钉（只回钉「这一行自己曾经出现过」的正内缩）。
     *
     * 真机事实（1025 的 dumpsys 视图树）：
     *  - 发现页 6 行里 4 行 `cgi`(7f091250) 的首子 `LinearLayout` 宽 1128 / left=0，
     *    另 2 行同层宽 1078 / left=50 —— 即健康态是「cgi 左内缩 50，子宽 = cgi 宽 - 50」；
     *  - 用户反馈 1026 上两页又不出现错位（偶发）。
     *
     * 所以这是一个排版竞态：内缩有时在、有时被抹成 0。本函数不做任何推理，
     * 只做两件事：
     *  ① 每次巡检时，若某行的 `cgi` 有正内缩、且子节点确实按这个内缩摆放 → 记下来；
     *  ② 若之后同一行变成「内缩 0 且子节点贴左 0」→ 用记下来的那个值 setPadding 回去并 requestLayout。
     *
     * 关键约束：值只来自该行自己的历史观测，绝不硬编码 16dp、也不取同页多数，
     * 所以不存在「把本来正确的行改歪」的可能；从未健康过的行也不会被我们编造缩进。
     */
    private fun pinRowInsets712(page: View, pageWidth: Int) {
        val rows = ArrayList<View>()
        runCatching { collectRows711(page, pageWidth, rows, 0) }
        if (rows.isEmpty()) return
        var fixed = 0
        for (row in rows) {
            val cgi = findDescendantById712(row, "cgi", 0, 6) ?: continue
            val g = cgi as? ViewGroup ?: continue
            if (g.childCount < 1) continue
            val child = g.getChildAt(0) ?: continue
            if (child.width <= 0) continue
            val padL = cgi.paddingLeft
            val seen = rowInsetSeen712[cgi] ?: 0
            if (padL > 0 && child.left >= padL - 2) {
                // 健康态：子节点确实被内缩推开，记住这个值
                rowInsetSeen712[cgi] = padL
                continue
            }
            if (padL == 0 && seen > 0 && child.left <= 2 && child.width >= cgi.width - 2) {
                // 异常态：内缩被抹成 0（子节点铺满整个 cgi）→ 用这一行自己的历史值钉回去
                // 防死循环：同一行累计回钉超过 20 次说明宿主每帧都在把它抹回去，
                // 这时再钉就是和宿主抢绘制，直接放弃（避免掉帧），只在日志里留痕。
                val tries = (rowInsetPinTries712[cgi] ?: 0) + 1
                rowInsetPinTries712[cgi] = tries
                if (tries > 20) {
                    if (tries == 21) HLog.i("$TAG 行内缩回钉(712)：同一行回钉已超 20 次，放弃以免抢绘制")
                    continue
                }
                val ok = runCatching {
                    cgi.setPadding(seen, cgi.paddingTop, cgi.paddingRight, cgi.paddingBottom)
                    cgi.requestLayout()
                }.isSuccess
                if (ok) fixed++
            }
        }
        if (fixed > 0) {
            val now = SystemClock.elapsedRealtime()
            if (now - lastInsetPinLog712 >= 2000L) {
                lastInsetPinLog712 = now
                HLog.i("$TAG 行内缩回钉(712)：$fixed 行，页=${page.javaClass.simpleName}")
            }
        }
    }

    private fun findDescendantById712(v: View, name: String, depth: Int, maxDepth: Int): View? {
        if (depth > maxDepth) return null
        val g = v as? ViewGroup ?: return null
        val n = minOf(g.childCount, 40)
        for (i in 0 until n) {
            val c = g.getChildAt(i) ?: continue
            if (idName(c) == name) return c
            findDescendantById712(c, name, depth + 1, maxDepth)?.let { return it }
        }
        return null
    }

    private fun collectRows711(v: View, pageWidth: Int, out: MutableList<View>, depth: Int) {
        if (depth > 26) return
        val w = v.width
        val h = v.height
        if (w >= (pageWidth * 0.55f).toInt() && h in 60..300 && v is ViewGroup &&
            v.childCount in 1..8
        ) {
            out.add(v)
            if (out.size >= 40) return
            return
        }
        if (v is ViewGroup) {
            val n = minOf(v.childCount, 40)
            for (i in 0 until n) {
                val c = v.getChildAt(i) ?: continue
                collectRows711(c, pageWidth, out, depth + 1)
                if (out.size >= 40) return
            }
        }
    }
    private val panelBarAnchored: MutableSet<View> =
        Collections.newSetFromMap(WeakHashMap<View, Boolean>())
    @Volatile private var lastPanelBarLogAt = 0L
    private var purpleSwapCount = 0
    @Volatile private var lastPurpleSwapLogAt = 0L
    @Volatile private var purpleSwapInstalled = false

    private fun neutralizeTaskBarBottomView(decor: ViewGroup) {
        val found = ArrayList<View>()
        collectBySimpleName(decor, "TaskBarBottomView", found)
        val now = SystemClock.elapsedRealtime()
        if (now - lastTaskBarCountLogAt >= topDumpIntervalMs) {
            lastTaskBarCountLogAt = now
            HLog.i("$TAG 任务栏底视图收集: ${found.size} 个（decor=${decor.javaClass.simpleName} ${decor.width}x${decor.height}）")
        }
        for (v in found) {
            if (!taskBarViewsHandled.add(v)) continue
            v.setWillNotDraw(true)
            HLog.i("$TAG 任务栏底视图已禁绘制: ${v.javaClass.name}#${idName(v)} ${v.width}x${v.height} top=${v.top} alpha=${v.alpha} vis=${v.visibility}")
        }
    }

    /** 5.5.94：18:16 日志三大证据——①白栈 0 条：主页顶部白块不走 Canvas 画笔（drawRect/drawPath/Paint.setColor
     *  全挂全取证仍 0），结合滑动截图（白块随滑动整体顶出屏幕）判定白块属于一个独立覆盖层窗口的 view 背景/位图；
     *  ②灰栈 0 条：TaskBarBottomView 禁绘持续有效；③「列表自绘覆盖钩子已挂」0 条且无失败日志：静默 return，本轮补诊断。
     *  做法：反射枚举 WindowManagerGlobal.mViews 全部窗口根，打根类名/尺寸/窗口参数/背景，标注含 TaskBarBottomView 的根，
     *  并对该根子树 w≥800 且 top≤300 的纯白 Color/GradientDrawable 清透明。 */
    @Volatile private var lastGlLayerLogAt = 0L
    @Volatile private var lastTaskBarCountLogAt = 0L
    @Volatile private var lastWindowDumpAt = 0L
    private val overlayWhiteCleared = Collections.newSetFromMap(WeakHashMap<View, Boolean>())
    private val hookedDecorViews: MutableSet<View> = Collections.newSetFromMap(WeakHashMap())

    /** 公开 API 收集器：每次 Window.getDecorView 都把返回的 DecorView 弱引用收集起来，
     *  作为 WindowManagerGlobal.mViews 反射被拦截时的窗口根来源。 */
    private fun installWindowDecorCollector() {
        runCatching {
            XposedBridge.hookAllMethods(Window::class.java, "getDecorView", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val d = param.result as? View ?: return
                    hookedDecorViews.add(d)
                }
            })
            HLog.i("$TAG 窗口收集器已挂: Window.getDecorView")
        }.onFailure { HLog.e("$TAG 窗口收集器挂载失败: ${it.message}", it) }
    }

    private val stepErrorAt: MutableMap<String, Long> = Collections.synchronizedMap(HashMap())

    private fun runStep(now: Long, name: String, block: () -> Unit) {
        runCatching { block() }.onFailure {
            val last = stepErrorAt[name]
            if (last == null || now - last >= topDumpIntervalMs) {
                stepErrorAt[name] = now
                HLog.e("$TAG enforce步骤异常: $name: ${it.javaClass.name}: ${it.message}")
            }
        }
    }

    private fun overlayWindowForensics(decor: ViewGroup) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastWindowDumpAt < topDumpIntervalMs) return
        lastWindowDumpAt = now
        // 18:49-18:51 全窗口实证：5.5.94 四条新日志零出现且 enforce 后段探针正常打日志——
        // WindowManagerGlobal.mViews 反射疑似被隐藏 API 拦截，getOrNull ?: return 全程静默。
        // 改双通道：反射失败显式打日志；另挂公开 API Window.getDecorView 收集所有窗口根。
        val roots = LinkedHashSet<View>()
        val reflected = runCatching {
            val cls = Class.forName("android.view.WindowManagerGlobal")
            val inst = cls.getMethod("getInstance").invoke(null)
            val f = cls.getDeclaredField("mViews").apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST")
            (f.get(inst) as? List<View>)?.toList() ?: emptyList<View>()
        }
        reflected.onFailure {
            HLog.e("$TAG 窗口根转储反射失败: ${it.javaClass.simpleName}: ${it.message}")
        }
        reflected.getOrNull()?.let { roots.addAll(it) }
        val reflectedCount = reflected.getOrNull()?.size ?: -1
        roots.addAll(hookedDecorViews.filter { it.isAttachedToWindow })
        HLog.i("$TAG 窗口根转储: roots=${roots.size} 反射=$reflectedCount 收集器=${hookedDecorViews.size}")
        for (root in roots) {
            if (root === decor || root === decor.rootView) continue
            val barList = ArrayList<View>()
            collectBySimpleName(root, "TaskBarBottomView", barList)
            val convList = ArrayList<View>()
            collectBySimpleName(root, "ConversationListView", convList)
            val lp = root.layoutParams
            val params = lp as? android.view.WindowManager.LayoutParams
            val bg = runCatching { root.background }.getOrNull()
            HLog.i("$TAG 窗口根转储: ${root.javaClass.name}#${idName(root)} ${root.width}x${root.height} top=${root.top} alpha=${root.alpha} vis=${root.visibility} " +
                "bg=${bg?.javaClass?.simpleName ?: "null"} 窗口type=${params?.type?.toString() ?: "?"} flags=${params?.flags?.toString() ?: "?"} format=${params?.format?.toString() ?: "?"} " +
                "含TaskBarBottomView=${barList.size} 含ConversationListView=${convList.size}")
            // 覆盖层白底清理：含 TaskBarBottomView 的窗口里，宽≥800、顶部 300px 内的纯白 Color/GradientDrawable
            if (barList.isNotEmpty()) {
                val whites = ArrayList<View>()
                collectWhiteTopViews(root, whites)
                for (v in whites) {
                    if (!overlayWhiteCleared.add(v)) continue
                    runCatching {
                        v.setBackgroundColor(Color.TRANSPARENT)
                        HLog.i("$TAG 覆盖层白底已清透明: ${v.javaClass.name}#${idName(v)} ${v.width}x${v.height} top=${v.top}")
                    }
                }
            }
        }
    }

    private fun collectWhiteTopViews(root: View, out: MutableList<View>) {
        val w = root.width
        val top = root.top
        if (w >= 800 && top <= 300) {
            val bg = runCatching { root.background }.getOrNull()
            val color = when (bg) {
                is ColorDrawable -> bg.color
                is android.graphics.drawable.GradientDrawable -> runCatching { bg.color?.defaultColor ?: 0 }.getOrNull()
                else -> null
            }
            if (color != null && Color.alpha(color) == 255 && color != Color.TRANSPARENT &&
                Color.red(color) >= 224 && Color.green(color) >= 224 && Color.blue(color) >= 224
            ) out.add(root)
        }
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                collectWhiteTopViews(root.getChildAt(i) ?: continue, out)
            }
        }
    }

    /** 5.5.93：17:32 取证栈实证面板底部紫条由 ConversationListView.onDraw 自绘（Treerliet 包装器画 #7a7396）；
     *  主页顶部白块疑为同一机制（列表自绘头部，灰条 TaskBarBottomView 禁绘后露出）。对策不是猜：
     *  hook onDraw，在其自绘之上、子 View 之下，按壁纸位图对应切片原位回铺顶部 300px / 底部 149px，
     *  行滚动内容在子层不受影响。若白块绘制者另有其人，白色取证通道（同轮加）会点名。 */
    @Volatile private var conversationOverdrawHooked = false
    @Volatile private var lastConvCountLogAt = 0L
    @Volatile private var conversationOverdrawLoggedEmpty = false
    private val overdrawHookedClasses: MutableSet<String> = Collections.synchronizedSet(HashSet())
    @Volatile private var lastConvScanAt = 0L
    private val coverPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private var coverDrawCount = 0
    @Volatile private var lastCoverLogAt = 0L

    /** 608 重建（回到 5.5.103 原版基线后的最小改动）：604/606 的取证栈（11-18 07:38:28）已点名——
     *  #ff7a7396 紫底的绘制者就是 ConversationListView.onDraw（Paint.setColor(#ff7a7396) 后自绘），
     *  它不走 view.background，所以清底 / 换背景永远无效；旧实现只回铺顶部 300px / 底部 149px 两条带，
     *  中段紫色照旧漏出来。这里改成「整块覆盖」：onDraw 之后、子 View 之前，按该页面自身背景位图的
     *  同一比例映射，把列表整块区域铺成壁纸切片。行是子 View，仍画在壁纸上；不动任何布局 / padding。 */
    private fun ensureConversationOverdrawHook(decor: ViewGroup) {
        val nowScan = SystemClock.elapsedRealtime()
        if (nowScan - lastConvScanAt < 1500L) return
        lastConvScanAt = nowScan
        val found = ArrayList<View>()
        collectListLikeViews(decor, found)
        val nowLog = SystemClock.elapsedRealtime()
        if (nowLog - lastConvCountLogAt >= topDumpIntervalMs) {
            lastConvCountLogAt = nowLog
            HLog.i("$TAG 列表自绘钩子收集(608): ${found.size} 个（decor=${decor.javaClass.simpleName} ${decor.width}x${decor.height}）")
        }
        if (found.isEmpty()) {
            if (!conversationOverdrawLoggedEmpty) {
                conversationOverdrawLoggedEmpty = true
                HLog.i("$TAG 列表自绘覆盖钩子(608): 本轮 decor 未收集到列表视图（decor=${decor.javaClass.name}）")
            }
            return
        }
        var added = 0
        for (inst in found) {
            val clz = inst.javaClass
            if (overdrawHookedClasses.contains(clz.name)) continue
            val cb = object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val v = param.thisObject as? View ?: return
                    val canvas = param.args.firstOrNull() as? Canvas ?: return
                    runCatching { coverListWallpaper(v, canvas) }
                }
            }
            val hooked = runCatching {
                var count = 0
                for (m in clz.declaredMethods) {
                    if (m.name != "onDraw") continue
                    XposedBridge.hookMethod(m, cb)
                    count++
                }
                count
            }.getOrDefault(0)
            if (hooked > 0) {
                overdrawHookedClasses.add(clz.name)
                added++
                HLog.i("$TAG 列表自绘整块覆盖钩子已挂(608): ${clz.name}.onDraw ×$hooked")
            }
        }
        if (added > 0) conversationOverdrawHooked = true
    }

    /** 列表整块铺壁纸切片：坐标用静置布局位置沿父链累加（滚动走画布矩阵、动画走 RenderNode，都不进 left/top），
     *  比例用页面自身背景位图的映射（页面背景是 BitmapDrawable 拉伸铺满页面，按页面坐标线性切图即像素连续）。 */
    private fun coverListWallpaper(v: View, canvas: Canvas) {
        val w = v.width
        val h = v.height
        if (w <= 0 || h <= 0) return
        val page = findPageAncestor(v) ?: return
        val spec = pageScrim[page] ?: return
        val bmp = spec.bitmap
        if (bmp.width <= 0 || bmp.height <= 0 || bmp.isRecycled) return
        val pw = page.width.coerceAtLeast(page.measuredWidth)
        val ph = page.height.coerceAtLeast(page.measuredHeight)
        if (pw <= 0 || ph <= 0) return
        var dx = 0
        var dy = 0
        var cur: View = v
        var guard = 0
        while (cur !== page && guard++ < 24) {
            dx += cur.left
            dy += cur.top
            cur = cur.parent as? View ?: break
        }
        val l = dx.coerceIn(0, pw)
        val t = dy.coerceIn(0, ph)
        val r = (dx + w).coerceIn(0, pw)
        val b = (dy + h).coerceIn(0, ph)
        if (r <= l || b <= t) return
        // 5.5.104：锚定层按窗口 cover 映射切片，回铺也必须用同一套像素，否则又出现接缝/重影。
        val anchoredBg = (v.background as? ScreenAnchoredBitmapDrawable)
            ?: runCatching { v.rootView.background as? ScreenAnchoredBitmapDrawable }.getOrNull()
        if (anchoredBg != null && anchoredBg.sourceBitmap() === bmp) {
            val aW = anchoredBg.anchorDims()[0].coerceAtLeast(1)
            val aH = anchoredBg.anchorDims()[1].coerceAtLeast(1)
            val scale = maxOf(aW.toFloat() / bmp.width, aH.toFloat() / bmp.height)
            val adx = (aW - bmp.width * scale) / 2f
            val ady = (aH - bmp.height * scale) / 2f
            fun srcRow(windowY: Float): Int =
                (((windowY - ady) / scale).toInt()).coerceIn(0, bmp.height)
            var absTop = 0
            runCatching {
                var cur: View = v
                var g = 0
                while (g++ < 24) {
                    val par = cur.parent as? View ?: break
                    absTop += cur.top
                    cur = par
                }
            }
            val sx0 = ((-adx / scale).toInt()).coerceIn(0, bmp.width)
            val sx1 = (((aW - adx) / scale).toInt()).coerceIn(sx0, bmp.width)
            val wTop = absTop.coerceAtLeast(0).toFloat()
            val wBottom = (absTop + h).coerceAtLeast(0).toFloat()
            canvas.drawBitmap(
                bmp,
                android.graphics.Rect(sx0, srcRow(wTop), sx1, srcRow(wBottom)),
                android.graphics.Rect(0, 0, w, h),
                coverPaint
            )
        } else {
            val src = android.graphics.Rect(
                l * bmp.width / pw,
                t * bmp.height / ph,
                r * bmp.width / pw,
                b * bmp.height / ph
            )
            canvas.drawBitmap(bmp, src, android.graphics.Rect(0, 0, w, h), coverPaint)
        }
        coverDrawCount++
        val now = SystemClock.elapsedRealtime()
        if (now - lastCoverLogAt >= 10_000L) {
            lastCoverLogAt = now
            HLog.i(
                "$TAG 列表整块铺壁纸(608): ${v.javaClass.simpleName}#${idName(v)} ${w}x$h" +
                    " 页面内偏移=${dx},${dy} 累计=$coverDrawCount"
            )
        }
    }

    private fun findPageAncestor(v: View): View? {
        var cur: View? = v
        var guard = 0
        while (cur != null && guard++ < 24) {
            if (pageScrim.containsKey(cur)) return cur
            cur = cur.parent as? View
        }
        return null
    }

    private fun collectListLikeViews(root: View, out: MutableList<View>) {
        val name = root.javaClass.simpleName
        if (name.contains("ListView") || name.contains("RecyclerView") || name.contains("Treerliet")) {
            out.add(root)
        }
        if (root is ViewGroup) {
            for (index in 0 until root.childCount) {
                collectListLikeViews(root.getChildAt(index) ?: continue, out)
            }
        }
    }

    /** 608：紫底 #ff7a7396 的「view 背景」通道。07:38:28 的 ActionBar 字段探针显示 ActionBarContainer
     *  的私有字段里挂的就是 ColorDrawable#ff7a7396，宿主切页时会把它写回背景，模块下一次 enforce（400ms 节流）
     *  才纠正，中间那段就是用户看到的「页面切换紫色底」。这里在 setter 处直接换掉：任何 view 被设成
     *  #ff7a7396 纯色底，立即改为透明（壁纸在下面），不碰布局、不碰子 View。 */
    private fun installPurpleBackgroundSwap() {
        if (mountOff703()) { dumpCounts703("mount-skip 标题栏白底已拦(635)"); return }
        if (purpleSwapInstalled) return
        val purple = 0xff7a7396.toInt()
        val cb = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
        if (nativeOnlyMode()) return  // 684ï¼åçæ¨¡å¼ä¸ä¿çå®¿ä¸»åçä¸éæ
        if (nativeOnlyMode()) return  // 684ï¼åçæ¨¡å¼ä¸ä¿çå®¿ä¸»åçä¸éæ
                val view = param.thisObject as? View ?: return
                val incoming = param.args.firstOrNull() as? Drawable ?: return
                // 613 主路径：已登记视图被宿主改写的那一次调用里就换回我们的背景。
                // beforeHookedMethod 跑在真正落库之前，宿主那层浅底根本没机会进入这一帧的
                // display list ——「停留本页偶发闪一下」就是原来「先白一帧、下一轮 enforce 再补」的差。
                val forced = guardedBackgroundFor(view, incoming)
                if (forced != null) {
                    // 710：宿主那次写入要落库的，正好就是我们要保持的那张 → 直接吞掉整次调用。
                    // 不这么做的话，View.setBackgroundDrawable() 无论如何都会 requestLayout()+invalidate()，
                    // 宿主下一帧重绑列表又写一次，于是「宿主写白 → 我们换回 → 重排 → 宿主再写白」
                    // 变成帧率级死循环（真机实测 ~1500 次/秒 ≈ 120fps × 12 行，累计 352 万次），
                    // 表现就是发现页/我的页持续闪烁、条目图标错位。
                    if (sameBackground(view.background, forced)) {
                        param.setResult(null)
                        return
                    }
                    param.args[0] = forced
                    guardHits++
                    val nowMs = SystemClock.elapsedRealtime()
                    if (nowMs - lastGuardLogAt >= 3000L) {
                        lastGuardLogAt = nowMs
                        HLog.i(
                            "$TAG 背景写入已拦回(613): 累计 $guardHits 次" +
                                "（页面/容器/行遮罩在同一次调用内改回，不再等下一轮 enforce）"
                        )
                    }
                    return
                }
                val color = runCatching { (incoming as? ColorDrawable)?.color }.getOrNull() ?: return
                if (isTitleBarView635(view)) {
                    if (barWrites635 < 40) {
                        barWrites635++
                        HLog.i("$TAG 标题栏底色写入(635) " + view.javaClass.simpleName + "#" + idName(view) +
                            " -> #" + Integer.toHexString(color) + " 白=" + isWhite635(color))
                    }
                    if (isWhite635(color)) {
                        param.args[0] = ColorDrawable(Color.TRANSPARENT)
                        titleWhiteBlocked635++
                        writeOn703("635"); HLog.i("$TAG 标题栏白底已拦(635) " + view.javaClass.simpleName + " 累计=$titleWhiteBlocked635")
                        return
                    }
                }
                if (isStatusBarView636(view)) {
                    if (barWrites636 < 40) {
                        barWrites636++
                        writeOn703("636"); HLog.i("$TAG 状态栏底色写入(636) " + view.javaClass.simpleName + "#" + idName(view) +
                            " -> #" + Integer.toHexString(color) + " 白=" + isWhite635(color))
                    }
                    if (isWhite635(color)) {
                        param.args[0] = ColorDrawable(Color.TRANSPARENT)
                        statusWhiteBlocked636++
                        HLog.i("$TAG 状态栏白底已拦(636) " + view.javaClass.simpleName + "#" + idName(view) +
                            " -> #" + Integer.toHexString(color) + " 累计=" + statusWhiteBlocked636)
                        return
                    }
                }
                if (color != purple) return
                param.args[0] = ColorDrawable(Color.TRANSPARENT)
                purpleSwapCount++
                val now = SystemClock.elapsedRealtime()
                if (now - lastPurpleSwapLogAt >= 3000L) {
                    lastPurpleSwapLogAt = now
                    HLog.i("$TAG 紫底背景已换透明(608): 累计 $purpleSwapCount 次")
                }
            }
        }
        val colorCb = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val view = param.thisObject as? View ?: return
                val color = param.args.firstOrNull() as? Int ?: return
                if (isTitleBarView635(view)) {
                    if (barWrites635 < 40) {
                        barWrites635++
                        HLog.i("$TAG 标题栏底色写入(635) " + view.javaClass.simpleName + "#" + idName(view) +
                            " -> #" + Integer.toHexString(color) + " 白=" + isWhite635(color))
                    }
                    if (color != Color.TRANSPARENT && isWhite635(color)) {
                        param.setResult(null)
                        titleWhiteBlocked635++
                        writeOn703("635"); HLog.i("$TAG 标题栏白底已拦(635) " + view.javaClass.simpleName + " 累计=$titleWhiteBlocked635")
                        return
                    }
                }
                if (isStatusBarView636(view)) {
                    if (barWrites636 < 40) {
                        barWrites636++
                        writeOn703("636"); HLog.i("$TAG 状态栏底色写入(636) " + view.javaClass.simpleName + "#" + idName(view) +
                            " -> #" + Integer.toHexString(color) + " 白=" + isWhite635(color))
                    }
                    if (color != Color.TRANSPARENT && isWhite635(color)) {
                        param.setResult(null)
                        statusWhiteBlocked636++
                        HLog.i("$TAG 状态栏白底已拦(636) " + view.javaClass.simpleName + "#" + idName(view) +
                            " -> #" + Integer.toHexString(color) + " 累计=" + statusWhiteBlocked636)
                        return
                    }
                }
                if (color == Color.TRANSPARENT) return
                val forced = guardedBackgroundFor(view, ColorDrawable(color)) ?: return
                // 710：已经是我们想要的那张就只吞掉这次写入，不要再 setBackgroundDrawable 一次
                // （每次 setBackgroundDrawable 都会 requestLayout，宿主重绑后又写 → 帧率级死循环）。
                if (sameBackground(view.background, forced)) {
                    param.setResult(null)
                    return
                }
                // setBackgroundColor 只吃 int，这里阻断它并把已登记的背景重新压回
                param.setResult(null)
                colorGuardHits++
                runCatching { view.setBackgroundDrawable(forced) }
            }
        }
        runCatching {
            XposedBridge.hookAllMethods(View::class.java, "setBackgroundDrawable", cb)
            XposedBridge.hookAllMethods(View::class.java, "setBackground", cb)
            XposedBridge.hookAllMethods(View::class.java, "setBackgroundColor", colorCb)
            HLog.i("$TAG 底色写入拦截已扩展到 setBackgroundColor(615)")
            purpleSwapInstalled = true
            HLog.i("$TAG 紫底背景换透明已挂(608): View.setBackground/setBackgroundDrawable #ff7a7396→透明，613 起同时拦截宿主回刷已登记底色")
        }.onFailure { HLog.e("$TAG 紫底背景换透明挂载失败(608): ${it.message}", it) }
    }

    /** 行换底前后要保住的有效缩进：视图自身 padding 与 .9 背景自带 padding 取大值。 */
    // ===================== 610：页面切换紫色底 =====================

    private val pagerUnderlays: MutableMap<ViewGroup, Drawable> =
        Collections.synchronizedMap(WeakHashMap<ViewGroup, Drawable>())
    private val gradientViewsBlocked: MutableSet<View> =
        Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap<View, Boolean>()))
    @Volatile private var gradientKillerInstalled = false
    @Volatile private var gradientSkips = 0L
    @Volatile private var lastGradientSkipLogAt = 0L
    @Volatile private var lastGradientBlockLogAt = 0L
    @Volatile private var scrollProbeInstalled = false
    @Volatile private var lastScrollProbeAt = 0L
    @Volatile private var scrollProbeCount = 0

    /**
     * 610 切页紫底根因（17:52 会话日志实证，非沿用旧结论）：
     *
     * 微信首页每一页的 MainUIView 下都挂着 DynamicBgContainer → GradientColorBackgroundView
     * （日志 `顶部: ·····GradientColorBackgroundView#- 1240x2772 top=0 vis=0`；decor 级另有一个
     * `decor[0][1][0][1]: com.tencent.mm.dynamicbackground.view.GradientColorBackgroundView`）。
     * 这一层是「按当前 fragment 背景色画的垂直渐变」，位置在页面壁纸之上、列表内容之下：
     * 静止时它不画（所以静止页面看得到壁纸），切页瞬间微信把新页 fragment 的颜色灌进去，
     * 过渡期整屏就是渐变底。截图 1000141710.jpg 右半屏像素 #232136(顶)→#3A3850(底) 连续渐变，
     * 不是 608 修的 #7a7396 纯色 —— 所以 608 的「紫底背景换透明」（只拦 ColorDrawable#ff7a7396）
     * 管不到它。609 的 headerHousekeeping 里虽有一条 GradientColorBackgroundView → setWillNotDraw
     * 分支，但本会话 4317 条 Theme 日志里「渐变背景视图已禁绘制」0 条，该分支从未执行过。
     *
     * 修法两条腿：
     * ① 直接拦掉 GradientColorBackgroundView 的自绘（只 hook 该类**自己声明**的 onDraw/draw，
     *    不向父类漫游，避免误伤 View.draw 把整棵树画没）；
     * ② enforce 每轮把 decor 树里所有 GradientColorBackgroundView 置 willNotDraw + alpha=0，
     *    作为类改名/换实现时 hook 没命中的兜底。
     * 这层停画后，它下面是页面/DecorView 的锚定壁纸，过渡期露出的是壁纸。
     */
    private fun installGradientDrawKiller() {
        if (gradientKillerInstalled) return
        runCatching {
            val cls = KavaReflector.loadClass(GRADIENT_VIEW_CLASS, context.hostClassLoader())
            if (cls == null) {
                HLog.e("$TAG 渐变背景层类未命中(610): $GRADIENT_VIEW_CLASS（改走 willNotDraw 兜底）")
                return
            }
            val callback = object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    // 早退：GradientColorBackgroundView 是叶子自绘 View，跳过它自己的绘制不影响子节点
                    param.result = null
                    gradientSkips++
                    val now = SystemClock.uptimeMillis()
                    if (now - lastGradientSkipLogAt >= 2000L) {
                        lastGradientSkipLogAt = now
                        val v = param.thisObject as? View
                        HLog.i(
                            "$TAG 渐变背景层自绘已拦截(610): 累计 $gradientSkips 次" +
                                " 最近=${v?.javaClass?.simpleName} ${v?.width}x${v?.height}" +
                                " vis=${v?.visibility} alpha=${v?.alpha}"
                        )
                    }
                }
            }
            var hooked = 0
            for (m in cls.declaredMethods) {
                if (m.isSynthetic || m.isBridge) continue
                if (m.name != "onDraw" && m.name != "draw") continue
                runCatching {
                    XposedBridge.hookMethod(m, callback)
                    hooked++
                }.onFailure { HLog.e("$TAG 渐变背景层 hook 失败(610) ${m.name}: ${it.message}") }
            }
            gradientKillerInstalled = true
            HLog.i("$TAG 渐变背景层禁绘制已挂(610): GradientColorBackgroundView 自绘方法 $hooked 个")
        }.onFailure { HLog.e("$TAG 渐变背景层禁绘制挂载失败(610): ${it.message}", it) }
    }

    private fun neutralizeGradientViews(decor: ViewGroup) {
        val found = ArrayList<View>()
        runCatching { collectBySimpleName(decor, "GradientColorBackgroundView", found) }
        for (v in found) {
            if (!gradientViewsBlocked.add(v)) continue
            runCatching {
                v.setWillNotDraw(true)
                v.alpha = 0f
                val now = SystemClock.uptimeMillis()
                if (now - lastGradientBlockLogAt >= 1500L) {
                    lastGradientBlockLogAt = now
                    HLog.i("$TAG 渐变背景层已静态屏蔽(610): ${v.javaClass.simpleName}#${idName(v)} ${v.width}x${v.height}")
                }
            }.onFailure { HLog.e("$TAG 渐变背景层屏蔽失败(610): ${it.message}", it) }
        }
    }

    /** 610：ViewPager 自身也铺一张窗口锚定壁纸，切页过渡期的空洞露壁纸而不是宿主的渐变底。 */
    private fun applyPagerUnderlay(pager: ViewGroup, path: String?, homePath: String?, log: Boolean) {
        if (mountOff703()) { dumpCounts703("mount-skip PagerStripWallpaperDrawable(pager)"); return }
        if (path.isNullOrBlank()) {
            // 667：四页都关掉时把底衬摘掉，避免整页壁纸从 ViewPager 底下透出来
            val current = pagerUnderlays.remove(pager)
            if (current != null && pager.background === current) {
                runCatching { pager.setBackgroundDrawable(null) }
            }
            return
        }
        val image = bitmap(path) ?: return
        // 617：底衬换成「按页分格 + 随 scrollX 平移」的一层，切页过程中连续跟随手指，
        // 落定前后是同一份 Bitmap 与同一套 cover 矩阵，不会再「落定才换成下页的图」。
        val underlay: Drawable = (pagerUnderlays[pager] as? PagerStripWallpaperDrawable)
            ?: PagerStripWallpaperDrawable(pager).also { pagerUnderlays[pager] = it }
        if (underlay is PagerStripWallpaperDrawable) {
            for (i in 0 until pager.childCount) {
                val child = pager.getChildAt(i)
                val bmp = pageBitmapByPage[child]
                if (bmp != null) underlay.setSlot(i, bmp)
            }
            // 667：首页关掉时不能把别的页的图回填到第 0 格
            if (homePath != null && underlay.slotBitmap(0) == null) {
                (bitmap(homePath) ?: image).let { underlay.setSlot(0, it) }
            }
        }
        if (pager.background !== underlay) {
            runCatching { pager.setBackgroundDrawable(underlay) }
                .onFailure { HLog.e("$TAG 切页底衬铺设失败(610): ${it.message}", it) }
            if (log) {
                HLog.i(
                    "$TAG 切页底衬已铺(610): ${pager.javaClass.simpleName}#${idName(pager)}" +
                        " 图=${image.width}x${image.height} 尺寸=${pager.width}x${pager.height}"
                )
            }
        }
    }

    private fun reassertPagerUnderlays() {
        if (pagerUnderlays.isEmpty()) return
        for ((pager, drawable) in pagerUnderlays.entries.toList()) {
            if (!pager.isAttachedToWindow) continue
            if (pager.background !== drawable) {
                runCatching { pager.setBackgroundDrawable(drawable) }
            }
        }
    }

    /**
     * 610 探针：切页过程中的取证数据——scrollX、四个页面的 left 与背景 drawable 类型、
     * 渐变层拦截计数。下一轮若紫底仍在，这份数据能直接指出是「页面背景没铺到」还是
     * 「又有别的层在画」，不再靠猜。
     */
    private fun installPagerScrollProbe() {
        if (scrollProbeInstalled) return
        runCatching {
            val cls = KavaReflector.loadClass(PAGER_CLASS, context.hostClassLoader())
            if (cls == null) {
                HLog.e("$TAG 切页探针类未命中(610): $PAGER_CLASS")
                return
            }
            XposedBridge.hookAllMethods(cls, "onPageScrolled", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val pager = param.thisObject as? ViewGroup ?: return
                    // 614：进页预热改挂在这个实测有输出的回调上（onPageSelected 在真机零触发）。
                    runCatching {
                        if (lastHomePager?.get() === pager && pager.width > 0) {
                            val idx = ((pager.scrollX + pager.width / 2) / pager.width)
                                .coerceIn(0, maxOf(0, pager.childCount - 1))
                            if (idx != lastPagerIndex) {
                                lastPagerIndex = idx
                                val act = lastHostActivity?.get()
                                val page = pager.getChildAt(idx)
                                if (act != null && page != null) {
                                    prepPageNow(act, page, idx, "切页滚动命中")
                                    HLog.i("$TAG 进页预热(614): 第 ${idx + 1} 页 已在新页可见前铺底分层 scrollX=${pager.scrollX}")
                                }
                            }
                        }
                    }
                    val now = SystemClock.uptimeMillis()
                    lastPagerScrollAt617 = now
                    runCatching {
                        if (lastHomePager?.get() === pager && pager.width > 0) {
                            val pos = param.args.getOrNull(0) as? Int ?: -1
                            val off = param.args.getOrNull(1) as? Float ?: -1f
                            val sx = pager.scrollX
                            val prev = lastScrollX635
                            lastScrollX635 = sx
                            val settled = pos >= 0 && off == 0f
                            if (settled) {
                                if (lastSettle635 != pos.toLong()) {
                                    lastSettle635 = pos.toLong()
                                    armBurst635(pager, pos, pos, "静置到位 pos=$pos")
                                }
                            } else if (prev >= 0 && Math.abs(sx - prev) > 240) {
                                val fromIdx = ((prev + pager.width / 2) / pager.width).coerceIn(0, 3)
                                val toIdx = ((sx + pager.width / 2) / pager.width).coerceIn(0, 3)
                                armBurst635(pager, fromIdx, toIdx, "起划突变 Δ${sx - prev}")
                            }
                        }
                    }
                    runCatching { probeTransition621(lastHostActivity?.get(), pager, now) }
                    runCatching {
                        val act623 = lastHostActivity?.get()
                        val d623 = act623?.window?.decorView as? ViewGroup
                        if (act623 != null && d623 != null) probeTitleBand623(act623, d623, now)
                    }
                    runCatching {
                        val act621 = lastHostActivity?.get()
                        val d621 = act621?.window?.decorView as? ViewGroup
                        if (act621 != null && d621 != null) probeGap621(act621, d621, now)
                    }
                    runCatching {
                        if (followLogCount < 80 && now - lastScrollProbeAt >= 120L) {
                            followLogCount++
                            val pos = param.args.getOrNull(0) as? Int
                            val off = param.args.getOrNull(1) as? Float
                            HLog.i(
                                "$TAG 划动跟随(617): position=$pos offset=$off scrollX=${pager.scrollX}" +
                                    " 平移=${-pager.scrollX}px 底衬=${pager.background?.javaClass?.simpleName}" +
                                    " 格数=${(pager.background as? PagerStripWallpaperDrawable)?.filledCount()}" +
                                    " 页0图=${(pager.background as? PagerStripWallpaperDrawable)?.slotBitmap(0)?.let { "${it.width}x${it.height}" }}"
                            )
                        }
                    }
                    if (now - lastScrollProbeAt < 150L) return
                    if (scrollProbeCount >= 80) return
                    lastScrollProbeAt = now
                    scrollProbeCount++
                    val sb = StringBuilder()
                    for (i in 0 until pager.childCount) {
                        val c = pager.getChildAt(i)
                        sb.append(" [").append(i)
                            .append(" left=").append(c.left)
                            .append(" bg=").append(c.background?.javaClass?.simpleName ?: "null")
                            .append(" wnd=").append(c.willNotDraw())
                            .append("]")
                    }
                    runCatching {
                        HLog.i(
                            "$TAG 切页探针(610): scrollX=${pager.scrollX} 子项=${pager.childCount}$sb" +
                                " 渐变拦截=$gradientSkips 底衬=${pagerUnderlays.size}"
                        )
                    }
                }
            })
            // 613：切页时在目标页被看见之前把它铺好（页面内容首次 inflate 也走这里）。
            XposedBridge.hookAllMethods(cls, "onPageSelected", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val pager = param.thisObject as? ViewGroup ?: return
                    if (lastHomePager?.get() !== pager) return
                    val index = (param.args.firstOrNull() as? Int) ?: return
                    val activity = lastHostActivity?.get() ?: return
                    val page = pager.getChildAt(index) ?: return
                    runCatching { prepPageNow(activity, page, index, "切页选中") }
                        .onFailure { HLog.e("$TAG 切页预热异常(613): ${it.message}", it) }
                }
            })
            // 634：切页事件（tab 点击 -> setCurrentItem）直接触发定点抓帧，不再靠定时器
            XposedBridge.hookAllMethods(cls, "setCurrentItem", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    runCatching {
                        val pager = param.thisObject as? ViewGroup ?: return
                        if (lastHomePager?.get() !== pager) return
                        val target = param.args.firstOrNull() as? Int ?: return
                        val fromIdx = if (pager.width > 0)
                            ((pager.scrollX + pager.width / 2) / pager.width).coerceIn(0, 3) else -1
                        armBurst634(pager, fromIdx, target)
                    }
                }
            })
            scrollProbeInstalled = true
            HLog.i("$TAG 切页探针已挂(610): $PAGER_CLASS#onPageScrolled；613 起挂 onPageSelected 做页面预热")
        }.onFailure { HLog.e("$TAG 切页探针挂载失败(610): ${it.message}", it) }
    }

    private fun rowPaddingOf(view: View): IntArray {
        var l = view.paddingLeft
        var t = view.paddingTop
        var r = view.paddingRight
        var b = view.paddingBottom
        val bg = view.background
        if (bg != null) {
            val rc = android.graphics.Rect()
            val ok = runCatching { bg.getPadding(rc) }.getOrDefault(false)
            if (ok) {
                l = maxOf(l, rc.left)
                t = maxOf(t, rc.top)
                r = maxOf(r, rc.right)
                b = maxOf(b, rc.bottom)
            }
        }
        return intArrayOf(l, t, r, b)
    }

    private fun applyKeptPadding(view: View, p: IntArray) {
        if (view.paddingLeft == p[0] && view.paddingTop == p[1] &&
            view.paddingRight == p[2] && view.paddingBottom == p[3]
        ) return
        runCatching { view.setPadding(p[0], p[1], p[2], p[3]) }
    }

    /** 608 小程序底栏（按用户定的方向做）：不隐藏，锚定到底部。
     *  宿主 TaskBarBottomView 自绘 #ededed/#7a7396 填充，所以保留「禁自绘」避免底栏把壁纸盖住，
     *  但子控件（微信(N)/🔍/＋）照常绘制；再把整块按静置布局位置压到 decor 底边，|gap|<=8px 认为已贴底不动。 */
    private fun anchorPanelBottomBar(decor: ViewGroup) {
        val found = ArrayList<View>()
        collectBySimpleName(decor, "TaskBarBottomView", found)
        if (found.isEmpty()) return
        val decorH = decor.height
        if (decorH <= 0) return
        val now = SystemClock.elapsedRealtime()
        for (bar in found) {
            if (!bar.isAttachedToWindow) continue
            if (!inPanelTree(bar)) continue
            if (bar.height <= 0) continue
            if (bar.visibility != View.VISIBLE) bar.visibility = View.VISIBLE
            if (bar.alpha < 1f) bar.alpha = 1f
            runCatching { bar.setWillNotDraw(true) }
            var top = 0
            var cur: View = bar
            var guard = 0
            while (guard++ < 24) {
                top += cur.top
                cur = cur.parent as? View ?: break
            }
            val staticBottom = top + bar.height
            val gap = decorH - staticBottom
            if (bar.translationY != gap.toFloat()) bar.translationY = gap.toFloat()
            if (now - lastPanelBarLogAt >= 3000L) {
                lastPanelBarLogAt = now
                val texts = ArrayList<TextView>()
                collectTextViews(bar, texts)
                HLog.i(
                    "$TAG 小程序底栏锚定(608): ${bar.javaClass.simpleName}#${idName(bar)} ${bar.width}x${bar.height}" +
                        " 静置底=$staticBottom decorH=$decorH 位移=${bar.translationY.toInt()} 子项=${(bar as? ViewGroup)?.childCount ?: 0}" +
                        " 文本=" + texts.take(3).joinToString("|") { it.text?.toString() ?: "" }
                )
            }
        }
    }

    private fun inPanelTree(view: View): Boolean {
        var cur: View? = view.parent as? View
        var guard = 0
        while (cur != null && guard++ < 20) {
            val n = cur.javaClass.simpleName
            if (n.contains("AppBrand") || n.contains("Panel") || n.contains("MiniProgram")) return true
            cur = cur.parent as? View
        }
        return false
    }

    private fun collectBySimpleName(root: View, simpleName: String, out: MutableList<View>) {
        if (root.javaClass.simpleName == simpleName) out.add(root)
        if (root is ViewGroup) {
            for (index in 0 until root.childCount) {
                val child = root.getChildAt(index) ?: continue
                collectBySimpleName(child, simpleName, out)
            }
        }
    }

    // ===== 611/612：群聊/私聊页状态栏并入页面底色 =====
    @Volatile private var lastChatBarLogAt = 0L
    @Volatile private var lastChatBarState = -1
    @Volatile private var lastBandLogAt = 0L
    @Volatile private var lastHostActivity: java.lang.ref.WeakReference<Activity>? = null

    /**
     * 611 取证（19:15:55 / 19:16:55 日志 + 两张截图）：聊天页状态栏那条 139px 是宿主 DecorView 直接子 View
     * `View#statusBarBackground 1240x139`，原生底色 `ColorDrawable#ffededed`（= ChattingUILayout 的 #EDEDED）；
     * 主题从 5.5.71 起把它清成透明好让壁纸铺到状态栏，聊天页 `ChattingUILayout 1240x2633 (top=139)` 不覆盖该条 →
     * 露出 decor 上的壁纸（截图取样 y=5..130 全 #6FB5E8，y>=160 #EDEDED，分界 139）。
     *
     * 611 的改法被真机证伪（22:03 日志）：探针打在 `条=1240x139 色=#ffededed 来源=宿主原色 可见=true`，
     * 但屏幕仍是壁纸——因为只改了 View 的 background，而该条的显隐/颜色由框架按 `Window.statusBarColor`
     * 管理，主题每轮把 statusBarColor 置 TRANSPARENT 时框架会把这层按透明处理，View 上刷什么色都不显示。
     * 612 因此改走框架通道：聊天页可见时 `window.statusBarColor = 页面底色`（宿主原色优先），
     * 同时把该条 background 设成同色、并按亮度钉住状态栏图标深浅；离开聊天页恢复 TRANSPARENT。
     * 只动状态栏这一条的颜色/图标，不等同于改 padding/insets，288px 内缩与 610 既有修复不动。
     */
    private fun applyChatStatusBarBand(activity: Activity, decor: ViewGroup) {
        val window = activity.window ?: return
        val strip = runCatching { decor.findViewById<View>(android.R.id.statusBarBackground) }.getOrNull()
        val chat = findViewByClassName(decor, "ChattingUILayout")
        val chatShown = chat != null && chat.isShown && chat.height > 0 && chat.width > 0
        val now = SystemClock.uptimeMillis()
        if (!chatShown) {
            if (lastChatBarState != 0) {
                lastChatBarState = 0
                lastChatBarLogAt = now
                runCatching { window.statusBarColor = Color.TRANSPARENT }
                HLog.i("$TAG 已离开聊天页，状态栏恢复壁纸(612)")
            }
            return
        }
        val original = strip?.let { (replacedBackgrounds[it] as? ColorDrawable)?.color }
            ?.takeIf { Color.alpha(it) != 0 && it != 0 }
        val pageColor = (chat!!.background as? ColorDrawable)?.color?.takeIf { Color.alpha(it) != 0 }
        val target = original ?: pageColor ?: 0xFFEDEDED.toInt()
        val source = when {
            original != null -> "宿主原色"
            pageColor != null -> "聊天页底色"
            else -> "兜底#EDEDED"
        }
        runCatching {
            if (window.statusBarColor != target) window.statusBarColor = target
            strip?.let { view ->
                val cur = (view.background as? ColorDrawable)?.color
                if (cur != target) {
                    if (!replacedBackgrounds.containsKey(view)) replacedBackgrounds[view] = view.background
                    view.setBackgroundColor(target)
                }
            }
            // 亮底必须配深色图标，否则 #EDEDED 上的白色状态栏文字看不见。
            val light = ((Color.red(target) * 299 + Color.green(target) * 587 + Color.blue(target) * 114) / 1000) >= 140
            val flags = decor.systemUiVisibility
            val want = if (light) flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            else flags and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
            if (want != flags) decor.systemUiVisibility = want
        }.onFailure { HLog.e("$TAG 聊天页状态栏落色失败(612): ${it.message}", it) }
        if (lastChatBarState != 1 || now - lastChatBarLogAt >= 5000L) {
            lastChatBarState = 1
            lastChatBarLogAt = now
            HLog.i(
                "$TAG 聊天页状态栏已并入页面底色(612): window.statusBarColor=#${Integer.toHexString(window.statusBarColor)} " +
                    "目标=#${Integer.toHexString(target)} 来源=$source 条=${strip?.width ?: -1}x${strip?.height ?: -1} " +
                    "条可见=${strip?.visibility} 条alpha=${strip?.alpha} 条底=${(strip?.background as? ColorDrawable)?.let { "#${Integer.toHexString(it.color)}" } ?: strip?.background?.javaClass?.simpleName} " +
                    "聊天布局=${chat.width}x${chat.height}"
            )
        }
    }

    /** enforce 循环里的廉价兜底：拿得到宿主 Activity 就按 612 通道再压一次。 */
    private fun enforceChatStatusBarBand(decor: ViewGroup) {
        val activity = lastHostActivity?.get() ?: return
        if (!activity.isFinishing) applyChatStatusBarBand(activity, decor)
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
        const val BAR_PREDRAW_TAG = "hchat_bar_predraw"
        const val FIRST_SCREEN_TAG = "hchat_first_screen_predraw"
        const val HOST_PACKAGE = "com.tencent.mm"
        const val LAUNCHER_UI_CLASS = "com.tencent.mm.ui.LauncherUI"
        const val GRADIENT_VIEW_CLASS = "com.tencent.mm.dynamicbackground.view.GradientColorBackgroundView"
        const val PAGER_CLASS = "com.tencent.mm.ui.base.CustomViewPager"
        const val BOTTOM_TAB_CLASS = "com.tencent.mm.ui.LauncherUIBottomTabView"
        const val SEAM_LIST_CLASS = "ConversationListView"
        val ACTION_BAR_ID_NAMES = listOf("action_bar", "action_bar_container", "title_bar", "title_bar_container")
        val ACTION_BAR_CLASS_NAMES = listOf("ActionBarContainer", "ActionBarLayout", "MMWindowActionBar", "TitleBarView")
        val BOTTOM_TAB_CLASS_NAMES = listOf("LauncherUIBottomTabView", "MMTabView", "BottomTabView", "HomeBottomTab")
        val BOTTOM_TAB_LABELS = setOf("微信", "通讯录", "发现", "我")
        const val SPLASH_DURATION_MS = 1200L
        const val MAX_DEPTH = 32
        const val DUMP_MAX_DEPTH = 8
        const val DUMP_MAX_LINES = 70
        val FIRST_FRAME_DELAYS_MS = longArrayOf(0L, 60L, 160L, 320L, 700L, 1500L, 3000L)
        const val LIFECYCLE_BUMP_DELAY_MS = 80L
        const val PERIODIC_TICK_MS = 500L
        const val ENFORCE_INTERVAL_MS = 400L
        /** 710c：设置页识别连续失手多少轮才恢复页面壁纸（≈2.4s），用于抗抖。 */
        const val SETTINGS_RESTORE_MISS_CYCLES_710 = 6
        /** 710m：内容链「底栏空档放开」的幂等扫描节流间隔（切页后新容器靠它补扫）。 */
        const val STRIP_SCAN_INTERVAL_710 = 1500L
        /** 710j：布局落定后强制重录锚定宿主的延迟 / 漂移重试间隔 / 最大重试次数。 */
        const val SETTLE_RERECORD_DELAY_MS = 700L
        const val SETTLE_RERECORD_RETRY_MS = 200L
        const val SETTLE_RERECORD_MAX_RETRY = 5
        const val SCRIM_INTERVAL_MS = 1200L
        const val FRAME_PUMP_MS = 2400L
        const val ROW_GAP_DP = 6f
        const val HOME_IMAGE_TAG = "hchat_home_image_layer"
        /** 710b：叠加页（微信设置页等半透明窗口）整窗灰底板的 tag。 */
        const val OVERLAY_BACKDROP_TAG = "aeonlab_overlay_backdrop_710"
        const val HOME_ROW_ALPHA = 31
        const val CONTENT_ROW_ALPHA = 13
    }
}
