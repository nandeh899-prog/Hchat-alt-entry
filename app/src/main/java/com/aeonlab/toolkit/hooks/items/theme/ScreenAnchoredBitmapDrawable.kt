package com.aeonlab.toolkit.hooks.items.theme

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.view.View
import java.lang.ref.WeakReference

/**
 * 全屏锚定背景图（5.5.104）。
 *
 * 5.5.103 及之前，同一张壁纸被同时塞给 decorView、ViewPager 的四个页面、首页整页容器
 * 和下拉小程序面板，每一层都是 BitmapDrawable / ImageView.FIT_XY——即「按自己那一层的宽高
 * 各缩各的」。列表容器高度和屏幕高度并不相等，于是状态栏/标题栏里的图和列表里的图比例不同，
 * 屏幕上出现一条看得见的接缝，同一张图还会在上下各出现一次（截图 10:05:26 实锤）。
 *
 * 这层的做法：缩放永远以**整个窗口**为基准（cover，等比放大到铺满，居中裁切），
 * 绘制时再按宿主 View 的「静置布局位置」把画布平移回去。任何一层铺这张图，
 * 落在屏幕上的像素都完全一致，接缝和重复自然消失；图也永远盖满状态栏到导航栏的整块屏幕。
 *
 * 598：锚定基准从 getLocationInWindow 改为父链 left/top 累加的静置布局位置。
 * getLocationInWindow 会把祖先链上的 RenderNode 属性变换（下拉小程序面板手势的
 * translationY 等）一起算进去；面板首绘恰好发生在手势中途时，T(-loc) 把这段瞬时偏移
 * 烙进 display list，而属性动画只改 RenderNode 矩阵、不触发子 view 重录——
 * 错位帧从此滞留，直到划后台重进才被重录修复（597 复现：壁纸整体上移约 220px，
 * 底部露出一条背景色横带）。布局位置在动画全程恒定 → 录制内容逐帧一致，
 * 手势位移由窗口合成层自然施加（壁纸随面板一起滑动），落定后即为正确帧。
 * 另加 drift>2px 的排定重绘兜底，保证动画收尾后必有一次落定帧重录。
 *
 * 599：静置基准补上祖先链 scrollX/Y 修正（见 staticLocInWindow 注释）——598 的
 * 纯布局基准把横向翻页 scroll 丢在合成层外，通讯录/发现/我三页壁纸移出屏幕全紫
 * （1329 截图实锤）；主页与面板链路 scroll=0 不受影响。修正后静止时基准与
 * getLocationInWindow 数值一致（动画偏移仍被排除），597/598 两个场景同时成立。
 *
 * 600：两个收尾——①重绘兜底从「每次漂移episode只排定一次」改为逐次排定直到落定帧：
 * 599 的单次重试常落在手势中途，错帧烙进 display list 后再无重录，落定后的正确帧
 * 永远等不到（599 实锤：小程序面板二次打开壁纸整体下沉 ~1000px、顶部露透明带，
 * 划后台才恢复；页面切换中途紫底同理）。②新增 requestSyncIfDrifted 供 enforce
 * 轮询：祖先 scroll 只改合成不触发宿主重录，漂移>2px 主动排定重录（40 次上限）。
 */
class ScreenAnchoredBitmapDrawable(
    private val bitmap: Bitmap,
    host: View,
    private val paint: Paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
) : Drawable() {

    private val hostRef: WeakReference<View> = WeakReference(host)
    private val matrix = Matrix()
    private val location = IntArray(2)
    /** 598：静置布局位置（父链 left/top 累加，不含 RenderNode 属性变换）。 */
    private val staticLocation = IntArray(2)
    /** 600：漂移重绘链计数，落定帧（drift≤2）清零；上限防止静止漂移场景空转。 */
    private var settleRetries = 0
    private var animDriftLogged = false
    private var anchorWidth = 0
    private var anchorHeight = 0

    /** 598：静置布局位置 = 沿父链累加 left/top。
     *  599 修正：还必须减去祖先链各 View 的 scrollX/scrollY。实证（597↔598 两场日志 +
     *  截图 1329）：横向翻页 scroll（CustomViewPager，通讯录/发现/我三页的宿主链）与
     *  RenderNode left/top 一样走窗口合成、getLocationInWindow 把它算在内；598 只累加
     *  left/top，三页首绘时 scrollX=一整页宽，壁纸被 T(-static) 整体平移一个页宽移出
     *  屏幕，露出下层微信暗色底（三页全紫）。scroll 基准在静止时逐帧不变，scrollTo
     *  会触发重录，不破坏 598「动画期间录制内容不变」的面板修复；RenderNode 属性动画
     *  （translationX/Y）仍不在此列。宿主自身 scroll 不影响自身背景位置，不参与累减。 */
    private fun staticLocInWindow(host: View, out: IntArray) {
        var x = 0
        var y = 0
        var cur: View = host
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

    fun holds(target: Bitmap): Boolean = target === bitmap

    /** 639：把「锚定尺寸 / 静置位置 / 当前实际位置」一次性打出来，供探针判定错位量。 */
    fun snapshot(): String {
        val host = hostRef.get() ?: return "SCREEN(no-host)"
        runCatching { staticLocInWindow(host, staticLocation) }
        runCatching { host.getLocationInWindow(location) }
        return "SCREEN(anchor=${anchorWidth}x${anchorHeight}" +
            " static=${staticLocation[0]},${staticLocation[1]}" +
            " loc=${location[0]},${location[1]}" +
            " drift=${location[0] - staticLocation[0]},${location[1] - staticLocation[1]})"
    }

    /** 639：一次性强制重录宿主 display list（切后台重开之所以能修好，走的正是这条路）。 */
    fun forceReRecord(): Boolean {
        val host = hostRef.get() ?: return false
        if (!host.isAttachedToWindow) return false
        host.invalidate()
        return true
    }

    /** 600：供 enforce 轮询的漂移探测——祖先 scroll/RenderNode 变化只改窗口合成、
     *  不触发宿主重录，中途录制的帧会滞留到下次主动失效为止（页面切换紫底、
     *  面板手势错帧）。漂移>2px 即排定一次延后重录，落定帧自然把链停下。 */
    fun requestSyncIfDrifted() {
        val host = hostRef.get() ?: return
        if (!host.isAttachedToWindow || anchorWidth <= 0) return
        runCatching {
            staticLocInWindow(host, staticLocation)
            host.getLocationInWindow(location)
            val drift = maxOf(
                Math.abs(location[0] - staticLocation[0]),
                Math.abs(location[1] - staticLocation[1])
            )
            if (drift > 2 && settleRetries < 40) {
                settleRetries++
                host.postInvalidateDelayed(16)
            }
        }
    }

    /** anchorWidth/anchorHeight 必须是窗口（decorView）的尺寸，所有层共用同一组值。 */
    fun bindAnchor(width: Int, height: Int) {
        if (width == anchorWidth && height == anchorHeight) return
        anchorWidth = width
        anchorHeight = height
        rebuild()
        invalidateSelf()
    }

    private fun rebuild() {
        matrix.reset()
        if (anchorWidth <= 0 || anchorHeight <= 0) return
        if (bitmap.width <= 0 || bitmap.height <= 0) return
        val scale = maxOf(
            anchorWidth.toFloat() / bitmap.width.toFloat(),
            anchorHeight.toFloat() / bitmap.height.toFloat()
        )
        val dx = (anchorWidth - bitmap.width * scale) / 2f
        val dy = (anchorHeight - bitmap.height * scale) / 2f
        matrix.setScale(scale, scale)
        matrix.postTranslate(dx, dy)
    }

    /** 594：紫条回铺/取证需要拿到锚定位图与窗口基准。 */
    fun sourceBitmap(): Bitmap = bitmap

    fun anchorDims(): IntArray = intArrayOf(anchorWidth, anchorHeight)

    private var drewOnce = false
    private var skippedByHeight = false
    private var askedReRecord649 = false
    private var unreadableSkips = 0
    private var heightSkips = 0
    private var drawSeq = 0L

    override fun draw(canvas: Canvas) {
        // 596：绘制路径兜底——锚定壁纸绘制抛任何异常都不允许击穿宿主主线程（否则又是启动闪退循环）。
        runCatching { drawAnchored(canvas) }.onFailure {
            android.util.Log.e("AeonLab:Theme", "锚定绘制异常已忽略: ${it.javaClass.name}: ${it.message}")
        }
    }

    /** 648：只给「窗口高度的宿主」画壁纸。
     *  实证（647 的 7 次进入，每次 摘=2）：TaskBarContainer 与它内层 RelativeLayout 高度是面板高 2722，
     *  比窗口 2772 少 50；这两层每次进入都会被重新装上锚定壁纸，而且被录制时把面板的 translationY 一起烙进
     *  display list（摘前 y2548 行差 94.70/102.62/103.05，摘掉后 3.69/8.95/6.42）。
     *  所以「摘一次」不持久。这里在绘制入口按宿主高度一次性谢绝：高度比锚定高度少 1..120px 的宿主不画壁纸，
     *  让窗口尺寸那几层（decor/MainUIView/LayoutListenerView…）透出来。只读宿主高度、不写宿主属性、不重录。 */
    private fun skipForPanelHeight(): Boolean {
        val h = hostRef.get() ?: return false
        if (anchorHeight <= 0) return false
        val hh = if (h.height > 0) h.height else h.measuredHeight
        // 650：高度还读不到 ⇒ 不画（等可读后再画一次），并把跳过次数记下来
        if (hh <= 0) {
            unreadableSkips++
            if (!askedReRecord649) {
                askedReRecord649 = true
                runCatching { h.post { runCatching { h.invalidate() } } }
            }
            if (unreadableSkips == 1) {
                android.util.Log.i("AeonLab:Theme", "高度未可读不画壁纸(650) host=" + h.javaClass.simpleName + " 跳过=$unreadableSkips")
            }
            return true
        }
        // 650：区间条件改成「宿主高 != 窗口高」（648 的 1..120 区间在真机上一次都没命中）
        val skip = hh != anchorHeight
        if (skip) {
            heightSkips++
            if (heightSkips == 1 || heightSkips % 50 == 0) {
                android.util.Log.i(
                    "AeonLab:Theme",
                    "宿主高判定(650) host=" + h.javaClass.simpleName + " 宿主高=$hh 窗口高=$anchorHeight" +
                        " 差=" + (anchorHeight - hh) + " 跳过=$heightSkips"
                )
            }
        }
        return skip
    }

    /** 649：648 的静态计数是 0 —— 那两层在我们摘掉之前根本没被重录，所以「绘制入口判定」没机会跑。
     *  649 让判定在首帧也生效：命中但不画时，对宿主做**一次** invalidate（唯一一次重录），
     *  让首帧也走同一条「面板高宿主不画壁纸」，之后不再干预。 */
    private fun drawAnchored(canvas: Canvas) {
        if (skipForPanelHeight()) {
            if (!askedReRecord649) {
                askedReRecord649 = true
                runCatching { val h = hostRef.get(); h?.invalidate() }
                val h0 = hostRef.get()
                android.util.Log.i(
                    "AeonLab:Theme",
                    "首次绘制等到高度可读(649) host=" + (h0?.javaClass?.simpleName ?: "?") +
                        " 宿主高=" + (h0?.height ?: -1) + " 锚定高=" + anchorHeight
                )
            }
            if (!skippedByHeight) {
                skippedByHeight = true
                val h = hostRef.get()
                android.util.Log.i(
                    "AeonLab:Theme",
                    "面板高宿主不画壁纸(648) host=" + (h?.javaClass?.simpleName ?: "?") +
                        " 宿主高=" + (h?.height ?: -1) + " 锚定高=" + anchorHeight +
                        " 差=" + (anchorHeight - (h?.height ?: 0))
                )
            }
            return
        }
        if (anchorWidth <= 0 || anchorHeight <= 0) {
            // 594：fallback 不能用宿主自身尺寸——面板内层按自己的高度 cover，
            // 会和别的层差出缩放基准（09:35 截图面板接缝的机制）。优先用 rootView=DecorView。
            val fallback = hostRef.get()
            if (fallback != null) {
                // 604：cover 基准必须是窗口尺寸。602 会话日志实证 anchor 被自绑成宿主尺寸
                // （锚定绘制实例: anchor=1240x2772，2772 是面板列表高度而非窗口高度）——
                // 该副本的 cover 缩放与窗口基准副本不一致，直接产生错位/重复。
                val root = fallback.rootView
                if (root.width > 0 && root.height > 0) {
                    bindAnchor(root.width, root.height)
                } else {
                    bindAnchor(fallback.width, fallback.height)
                }
            }
            if (anchorWidth <= 0 || anchorHeight <= 0) return
        }
        val host = hostRef.get() ?: return
        if (!host.isAttachedToWindow) return
        // 598：锚定基准改用「静置布局位置」——沿父链累加 left/top（不读 translationX/Y、
        // 不读 getLocationInWindow）。597 用 getLocationInWindow，它把下拉手势给面板容器
        // 挂的 translationY 一起算进去：首绘发生在手势中途时，T(-loc) 把 (s-1)·Δ 的错位
        // 烙进 display list（Δ≈300px → 壁纸上移 ≈220px，底部露蓝条，597 截图 1303/1306）；
        // 而 RenderNode 属性动画不会触发子 view 重录，错帧从此滞留到划后台重进。
        // 布局位置在动画全程不变 → 录制内容逐帧一致；手势位移由窗口合成层施加，
        // 壁纸随面板平滑跟随，落定帧天然正确。
        staticLocInWindow(host, staticLocation)
        host.getLocationInWindow(location)
        val drift = maxOf(
            Math.abs(location[0] - staticLocation[0]),
            Math.abs(location[1] - staticLocation[1])
        )
        if (drift > 2) {
            // 600：漂移帧逐次排定延后重绘，直到出现落定帧（drift≤2）才停。
            // 599 的单次重试落在手势中途时，落定帧永远等不到（见类注释 600 节）。
            if (settleRetries < 40) {
                settleRetries++
                host.postInvalidateDelayed(64)
            }
            if (!animDriftLogged) {
                animDriftLogged = true
                val driftMsg = "锚定绘制遇动画偏移: loc=(${location[0]},${location[1]}) static=(${staticLocation[0]},${staticLocation[1]}) 已按静置位绘制并排定重绘"
                android.util.Log.i("AeonLab:Theme", driftMsg)
                runCatching { com.aeonlab.toolkit.xposed.compat.XposedBridge.log("[AeonLab:Theme] $driftMsg") }
            }
        } else {
            settleRetries = 0
            animDriftLogged = false
        }
        if (!drewOnce) {
            drewOnce = true
            val firstDrawMsg = "锚定绘制实例: anchor=${anchorWidth}x$anchorHeight host=${host.javaClass.simpleName} ${host.width}x${host.height} loc=(${location[0]},${location[1]}) static=(${staticLocation[0]},${staticLocation[1]}) bmp=${bitmap.width}x${bitmap.height}"
            android.util.Log.i("AeonLab:Theme", firstDrawMsg)
            // 596：drawable 绝不能直引 de.robv.android.xposed.XposedBridge——本模块按
            // libxposed API 102 挂载时宿主进程类路径没有 legacy XposedBridge，首绘直引
            // 直接 NoClassDefFoundError → 微信启动即崩溃循环（16:28:36~16:29:14 四次同栈）。
            // 改走项目兼容层（与 HLog 同通道，modern/legacy 自适应），并兜底吞掉日志异常。
            runCatching { com.aeonlab.toolkit.xposed.compat.XposedBridge.log("[AeonLab:Theme] $firstDrawMsg") }
        }
        drawSeq++
        if (drawSeq % 40L == 1L) {
            val probeMsg = "锚定绘制604: host=${host.javaClass.simpleName} anchor=${anchorWidth}x$anchorHeight static=(${staticLocation[0]},${staticLocation[1]}) loc=(${location[0]},${location[1]}) hw=${canvas.isHardwareAccelerated} bmp=${bitmap.width}x${bitmap.height} seq=$drawSeq"
            runCatching { com.aeonlab.toolkit.xposed.compat.XposedBridge.log("[AeonLab:Theme] $probeMsg") }
        }
        val save = canvas.save()
        // 595：画布可能带祖先变换（面板开合动画缩放、下拉回弹）。getLocationInWindow 已含
        // 祖先变换，再叠加 translate 会双重计入 → 动画帧错位且不再重绘，留在屏上直到触摸
        // （594 截图接缝 + 触碰复原的机制）。改为：位图→窗口 cover 矩阵先串上画布矩阵的逆，
        // 任何祖先变换都被抵消，壁纸始终按窗口坐标锚定。
        val cm = android.graphics.Matrix()
        canvas.getMatrix(cm)
        val inv = android.graphics.Matrix()
        if (!cm.invert(inv)) { canvas.restoreToCount(save); return }
        val total = android.graphics.Matrix()
        if (canvas.isHardwareAccelerated) {
            // 597：硬件加速下画布矩阵不含宿主 View 的窗口偏移——RenderNode 用 left/top 定位，
            // 该偏移不进 RecordingCanvas 矩阵。597 用 getLocationInWindow 补这个偏移，但
            // 手势/动画中途它含动画平移，会把错位烙进 display list；598 改为补「静置布局位置」，
            // 与窗口合成层语义严格对应（录制内容放在布局位，合成层再施加 RenderNode 变换），
            // 动画全程不变量，落定帧即为正确帧。
            // 604：矩阵次序修正——T(-static) 必须作用在窗口坐标上：落位 = CM⁻¹·T(-static)·M·p。
            // 603 及之前是 CM⁻¹·M·T(-static)（先在位图空间减 static），实际落位 = M·p - M·static，
            // 与正确值 M·p - static 相差 (M-I)·static：静置位非 0 的内层宿主（面板 TaskBarView
            // static≈289 → 偏 ~180px；下拉手势祖先 scroll 使 static=-1979 → 偏 ~1.2k px）
            // 与外层宿主各画一份错位副本——14:57:32 截图壁纸整幅重复绘制的机制。
            // static=0 的宿主两式等值，主页等既有正确路径不受影响。
            val winOffset = android.graphics.Matrix()
            winOffset.setTranslate(-staticLocation[0].toFloat(), -staticLocation[1].toFloat())
            total.setConcat(winOffset, matrix)
            total.setConcat(inv, total)
        } else {
            total.setConcat(inv, matrix)
        }
        canvas.drawBitmap(bitmap, total, paint)
        canvas.restoreToCount(save)
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    override fun getIntrinsicWidth(): Int = bitmap.width

    override fun getIntrinsicHeight(): Int = bitmap.height
}
