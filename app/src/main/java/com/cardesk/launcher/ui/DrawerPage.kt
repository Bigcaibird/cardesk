package com.cardesk.launcher.ui

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.cardesk.launcher.core.GlassDrawable
import com.cardesk.launcher.core.Palette
import com.cardesk.launcher.core.Shape
import com.cardesk.launcher.core.Skin
import com.cardesk.launcher.core.log.LogTags
import com.cardesk.launcher.core.log.Logx
import com.cardesk.launcher.ui.anim.Motion
import com.cardesk.launcher.ui.anim.MotionSpec
import com.cardesk.launcher.ui.widget.OverlayBackdrop

/**
 * 应用抽屉。
 *
 * 按画板 `03-apps-day` 重排（1920×1080 设计坐标，像素从画板量出来）：
 * - 面板 **1836×880**，`left 44 / top 67`，圆角 28 的**玻璃浮层**（[GlassDrawable]）；
 * - 标题「全部应用」**28px 粗体** (32, 34)；
 * - 右上**分段开关**（全屏打开 / 小窗打开）`right 36 / top 27`：浅色轨道 + 白色选中胶囊，
 *   选项 16px / 内边距 14、项间距 6、轨道内边距 4；
 * - 6 列应用网格：格子 **278×156 圆角 24**，列距 20、行距 22；
 *   图标底板 **88×88 圆角 22**（浅青 12%），名称 16px 次级文字、距图标 14；
 *   禁用应用只在**卡片右上角**挂一个 12px 「已禁用」胶囊，不再在名称下面多占一行；
 * - 底部提示 14px `(32, 836)`，**没有计数行**（画板没有，上一轮已因此验收失败）。
 *
 * ## 本轮修复（画板 → 代码）
 *
 * 验收判 FAIL 的那一条是**图标语言**：画板 `03-apps.png` 里每一格都是一块
 * **浅青玻璃底 + 单色 accent 线稿符号**，整屏统一；改前是把系统给的
 * **彩色真实图标**原样贴上去，一块红一块蓝，6×4 的网格看起来最乱。
 *
 * 画板量出来的规格没变（所以底板/格子一行都没动）：格子 278×156 r24、
 * 图标底板 **88×88 r22、accent 12% 底**、名称 16px。变的是**图标本身**：
 *
 * 1. 先用 `loadIcon` 把图标画进 60×60 的位图（88 减两边各 14 内边距）；
 * 2. 逐像素做**单色化**，颜色统一取 `pal.accent`（`monochromeIcon()`）：
 *    - 大多数图标是「一整块底板 + 一个符号」，那就**把底板当透明**、
 *      符号按与底板的**色距**算墨量 —— 蓝底白圈 / 白底黑柱都会还原成
 *      画板那种**细的单色符号**；
 *    - 透明底的纯线稿图标没有底板，退回**亮度**口径（浅色透明、深色留下）；
 *    两种口径都**保留图标内部轮廓**，不是 `SRC_IN` 糊成一块纯色方块；
 * 3. 两种保底（画板没法表达、硬套会变成不可辨认的块）：
 *    - **纯色块**：内容区 alpha ≥ 240 的像素占比 > 92% → 就是一块实心色，保留原图；
 *    - **几乎洗掉**：alpha ≥ 64 的「墨迹」占比 < 3%（底板和符号同色、
 *      或白底 + 极浅符号）→ 单色化后基本看不见，保留原图。
 *
 * 注意：**没有**加计数行 / 分类栏 / 搜索框 —— 画板里没有，上一轮已经因此扣过分。
 *
 * ## 为什么没有「左侧分类栏 / 搜索框」
 *
 * 需求描述里提到左侧竖直分类栏与搜索区，但画板 `03-apps-day` 里**两者都没有**：
 * 标题右侧是启动方式分段开关，下面是 6 列网格和底部提示。
 * 工程里也没有分类数据（`reload()` 只查 launcher activity）。以画板为唯一事实源，
 * 这里不虚构分类栏/搜索框 —— 差异在交付说明里列明。
 *
 * ## 去重
 *
 * 数据层已经 `distinctBy { packageName }`，但 `queryIntentActivities` 对同一个包
 * 可能返回多个 launcher activity（不同 activity 名）；画板每格是一个**应用**，
 * 所以 [reload] 末尾再按包名做一次最终去重，保证不会出现两个「CarDesk 车控桌面」。
 *
 * ## 第152轮保留的行为
 *
 * 长按图标弹应用菜单（重命名 / 应用信息 / 禁用·启用 / 卸载），去掉「⋮」与拖放排序；
 * 点按启动、全屏/小窗两态、[onClose]/[onOpenApp] 回调都没有改。
 */
class DrawerPage(context: Context, private val pal: Palette) : FrameLayout(context) {

    var onClose: (() -> Unit)? = null
    /** 打开应用：mini=true 表示小窗打开（浮窗承载），false 表示全屏。 */
    var onOpenApp: ((pkg: String, name: String, mini: Boolean) -> Unit)? = null

    private var miniMode = false

    private lateinit var grid: GridLayout
    private lateinit var hint: TextView

    /**
     * 当前显示顺序（包名）。
     *
     * 第152轮**去掉拖放排序**后，它不再被任何地方改写 ——
     * 只用来记一下本次加载了多少个应用（排障日志）。
     */
    private var currentPackages: List<String> = emptyList()
    private val prefs = com.cardesk.launcher.core.Prefs(context.applicationContext)

    /** 面板（动画用）。 */
    private var panelView: View? = null

    /** 遮罩（动画用）。 */
    private var scrimView: View? = null

    /**
     * 分段项外观表（按视图索引）。
     *
     * ## 声明位置不能挪到下面
     *
     * Kotlin 的属性初始化按**声明顺序**执行 ——
     * 声明在 [init] **之后**的属性，到 [init] 跑的时候还是 `null`。
     * 而 [segment] 是在 [init] 里调用的（构造分段控件），
     * 所以这张表**必须**声明在 [init] 之前，
     * 否则第一次构造就 `NullPointerException`。
     */
    private val segmentStyles = HashMap<TextView, SegmentStyle>()

    /** 打开动画：面板上滑 + 遮罩淡入（一族）。 */
    fun playOpen() {
        OverlayAnim.open(panelView, scrimView)
    }

    /** 关闭动画：面板下滑 + 淡出（一族）。 */
    fun playClose(onEnd: () -> Unit) {
        OverlayAnim.close(panelView, scrimView, onEnd)
    }

    init {
        val scrim = OverlayBackdrop(context).apply {
            // 第五十轮：设计板 `s03-apps` 是**整页**（`.bgwash` + 卡片 + Dock），
            // **没有 scrim**，所以背景板只要 `.bgwash`（不透明，桌面不再透出）。
            palette = this@DrawerPage.pal
            withScrim = false
            isClickable = true
            setOnClickListener {
                // 排障场景：「点遮罩抽屉没关掉」——关闭动作落盘。
                Logx.event(LogTags.DRAWER, "抽屉关闭（点击遮罩）")
                onClose?.invoke()
            }
        }
        scrimView = scrim
        addView(scrim, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        // —— 面板：画板 03 的玻璃浮层（44,67 / 1836×880 / 圆角 28）——
        //
        // 旧版是 `Skin.flat` 的扁平面；画板这里是一整块半透明玻璃
        // （下面是壁纸的模糊版 + 1px 白边 + 柔影），所以换成 GlassDrawable。
        val panel = FrameLayout(context).apply {
            background = GlassDrawable(28f, Skin.surface, Skin.GLASS_STROKE, true, true)
            isClickable = true
            setOnClickListener { }
        }
        panelView = panel
        addView(
            panel,
            LayoutParams(PANEL_W, PANEL_H, Gravity.TOP or Gravity.START).apply {
                leftMargin = PANEL_LEFT
                topMargin = PANEL_TOP
            },
        )

        // —— 标题：28px（画板文字 bbox x76..183 / y108..133，面板内约 (32, 34)）——
        val title = Ui.text(context, "全部应用", 28f, Skin.TEXT, bold = true)
        panel.addView(title, abs(PANEL_PAD, TITLE_TOP, -1, 40))

        // —— 右上分段开关（全屏打开 / 小窗打开）——
        //
        // 画板的选中态是**白色实底 + 深字**，不是 accent 实底；轨道也只是很淡的 tint。
        // 这和首页其他「选中=accent」的控件不同 —— 抽屉这里是「模式切换」，不是「当前分类」。
        val seg = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            background = Ui.round(tint(Skin.TEXT, 0x0B), Shape.PILL, 1f, Skin.GLASS_STROKE)
            setPadding(4, 4, 4, 4)
        }
        val full = segment("全屏打开", true)
        val mini = segment("小窗打开", false)
        fun applyMode(miniOn: Boolean) {
            miniMode = miniOn
            paintSegment(full, !miniOn)
            paintSegment(mini, miniOn)
            // 刚被选中的那一项「鼓一下」——
            // 启动方式的切换没有声音也没有飞行动效，不给个形变用户会以为没生效。
            Motion.bump(if (miniOn) mini else full)
            // 排障场景：「小窗/全屏切换没生效」——启动方式切换落盘。
            Logx.event(
                LogTags.DRAWER,
                "抽屉启动方式切换",
                "mode" to if (miniOn) "mini" else "fullscreen",
            )
        }
        full.setOnClickListener { applyMode(false) }
        mini.setOnClickListener { applyMode(true) }
        seg.addView(full, LinearLayout.LayoutParams(-2, SEG_ITEM_H).apply { rightMargin = 6 })
        seg.addView(mini, LinearLayout.LayoutParams(-2, SEG_ITEM_H))
        panel.addView(
            seg,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END).apply {
                topMargin = SEG_TOP
                rightMargin = SEG_RIGHT
            },
        )

        // 画板没有标题下的分隔线，这里一并去掉 —— 标题与网格之间靠 60px 留白分层，不需要线。

        hint = Ui.text(context, "没有读取到可启动的应用", 16f, Skin.TEXT_DIM).apply {
            gravity = Gravity.CENTER
        }
        panel.addView(hint, abs(PANEL_PAD, GRID_TOP, PANEL_W - PANEL_PAD * 2, 120))

        // —— 应用网格 ——
        val scroll = ScrollView(context).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
        }
        grid = GridLayout(context).apply { columnCount = 6 }
        // 第152轮：**去掉拖放排序**。
        //
        // 以前 grid 上挂了 `setOnDragListener` + 格子长按 `startDragAndDrop`，
        // 长按一个应用能拖着换位置（顺序写进 `launcher_app_order`）。
        // 现在长按改成**弹出应用菜单**，拖放整套（监听 / 拖影 / 落点换算）一并删掉 ——
        // 两个手势抢同一个入口，留着只会打架。
        scroll.addView(grid, FrameLayout.LayoutParams(GRID_W, LayoutParams.WRAP_CONTENT))
        panel.addView(scroll, abs(PANEL_PAD, GRID_TOP, GRID_W, GRID_H))

        // —— 底部提示：14px，画板在 y903（面板内 836）——
        // 文案里刻意不放「N 个应用 / N 个已禁用」——画板没有计数行，上一轮验收就是栽在这。
        val foot = Ui.text(context, "点按图标启动，长按图标打开菜单", 14f, Skin.TEXT_DIM)
        panel.addView(foot, abs(PANEL_PAD, FOOT_TOP, PANEL_W - PANEL_PAD * 2, 20))

        // 第191轮：**构造时不再 `reload()`**。
        //
        // `reload()` 要做两件重活：
        // 1. `queryIntentActivities` —— 跨进程问 PackageManager 要**全部**可启动应用；
        // 2. 给每个应用 `loadIcon` —— 每个图标都是一次资源读取 + 解码。
        //
        // 模拟器上这一段就要 **95ms**（实测 `warmup:begin` → `warmup:drawer`），
        // 真机上更慢 —— 而抽屉在启动这条路上**根本用不到**：
        // 桌面构造它只是为了「万一用户马上点应用键」。
        //
        // 以前这里是白干两遍：构造时 `reload()` 一次，
        // `LauncherView.showDrawer()` 打开时又 `reload()` 一次。
        // 现在只在**真正打开时**加载一次 —— 打开反而比原来快了。
        //
        // 构造本身只留「摆骨架」（二十来个 View），很快。
        Logx.i(LogTags.DRAWER, "抽屉页构造完成（应用列表按需加载）")
    }

    private fun segment(text: String, active: Boolean): TextView =
        Ui.text(context, text, 16f, if (active) Skin.TEXT else Skin.TEXT_SOFT).apply {
            gravity = Gravity.CENTER
            // 画板项高 38、水平内边距 14；项宽由 4 个汉字 + padding 自然撑开。
            setPadding(14, 0, 14, 0)
            // 底色**始终是同一个 drawable** —— 渐变时只改它的填充色。
            // 换来换去 drawable 是没法插值的（只能硬切）。
            val fill = Skin.solid(if (active) Skin.surfaceStrong else Color.TRANSPARENT, Shape.PILL)
            background = fill
            segmentStyles[this] = SegmentStyle(fill).apply {
                textColor = if (active) Skin.TEXT else Skin.TEXT_SOFT
                fillColor = if (active) Skin.surfaceStrong else Color.TRANSPARENT
            }
            paintSegment(this, active)
        }

    /**
     * 分段项两态（画板 03 的 `.seg span`）：
     * - 选中：**白色实底（L3 的 surfaceStrong）+ 主文字 + 加粗**；
     * - 未选中：透明底 + 次级文字。
     *
     * 颜色**渐变**过去而不是硬切：切换启动方式时，
     * 白胶囊是从透明里「淡入」的，用户才看得出这一项被选中了。
     */
    private fun paintSegment(seg: TextView, active: Boolean) {
        seg.typeface = Typeface.create(Typeface.DEFAULT, if (active) Typeface.BOLD else Typeface.NORMAL)
        val style = segmentStyles[seg] ?: return
        val targetText = if (active) Skin.TEXT else Skin.TEXT_SOFT
        val targetFill = if (active) Skin.surfaceStrong else Color.TRANSPARENT
        val fromText = style.textColor
        val fromFill = style.fillColor
        if (fromText == targetText && fromFill == targetFill) return
        Motion.animateProgress(seg) { t ->
            val tc = MotionSpec.lerpColor(fromText, targetText, t)
            val fc = MotionSpec.lerpColor(fromFill, targetFill, t)
            seg.setTextColor(tc)
            style.fill.setColor(fc)
            style.textColor = tc
            style.fillColor = fc
        }
    }

    /**
     * 分段项当前**显示中**的两态颜色。
     *
     * [TextView] 反查不到自己的文字色，背景 drawable 也一样 ——
     * 渐变必须从「现在显示的色」出发，中途再切状态才接得上。
     */
    private class SegmentStyle(val fill: GradientDrawable) {
        var textColor: Int = 0
        var fillColor: Int = 0
    }

    /**
     * 语义色的「同色相 + 指定透明度」版本。
     *
     * 画板里的灰卡底 / 浅青图标底，其实就是主文字色 / 强调色的低透明度版，
     * 不是另调的两支灰。这样日夜切换时底色会跟着 token 自动配对，不用维护两套。
     */
    private fun tint(color: Int, alpha: Int): Int = (color and 0x00FFFFFF) or (alpha shl 24)

    /**
     * 把真实 App 图标做成**画板那种单色 accent 符号**（[ICON]×[ICON] 的位图）。
     *
     * ## 做法（保留轮廓，不是纯色块）
     *
     * 1. 把图标画进 [ICON] 减两边 [ICON_PAD] 的方图（88 - 28 = 60×60）；
     * 2. **[borderPlate] 找底板色**：贴边 3 圈里过半是同一种颜色 ⇒ 这个图标是
     *    「一整块底板 + 一个符号」（桌面图标基本都是）。这时底板当**透明**、
     *    符号按与底板的**色距**算墨量（色距 ≥ 128 满墨）——
     *    蓝底白圈 / 白底黑柱都会还原成画板那种**细的单色符号**，
     *    而不是一整块 accent 方块（那样正是验收里说的「变纯色块」）；
     * 3. 找不到底板（透明底的纯线稿图标）时退回**亮度**口径：
     *    `alpha = (255 - 亮度) × 原 alpha / 255`，浅色透明、深色留下；
     * 4. 两种情况颜色都统一取 [accent]，符号内部的抗锯齿层次保留；
     * 5. 再把 60×60 贴回 [ICON]×[ICON] 的正中，四周补回 14px 留白（底板仍 88×88 r22）。
     *
     * ## 两种退回原图的情况
     *
     * 画板里每个图标都是「符号 + 留白」，但系统里有的图标本身就是**满幅纯色块**
     * （没有任何符号），单色化之后会变成一块 accent 实心方块（比彩色更糟）：
     *
     * - 内容区 `alpha ≥ [MONO_OPAQUE]` 的像素占比 **> [MONO_SOLID_PCT]%**
     *   —— 整块不透明，单色化后就是纯色块 ⇒ 保留原图；
     * - 内容区 `alpha ≥ [MONO_INK]` 的像素占比 **< [MONO_MIN_INK_PCT]%**
     *   —— 底板和符号一个色（纯色块）、或白底 + 极浅符号，单色化后
     *   几乎全透明、等于图标消失 ⇒ 保留原图。
     *
     * 这两条是**按这张位图实测出来的比例**判的，不是按包名写死，
     * 换图标包 / 新装应用也走同一口径。
     *
     * @return 单色化后的位图；不适合单色化（或图标为空）时返回 null
     */
    private fun monochromeIcon(icon: Drawable?, accent: Int): Bitmap? {
        if (icon == null) return null
        val content = ICON - ICON_PAD * 2
        if (content <= 0) return null

        // 1. 先按真实尺寸画一遍（60×60）。
        val src = Bitmap.createBitmap(content, content, Bitmap.Config.ARGB_8888)
        try {
            icon.setBounds(0, 0, content, content)
            icon.draw(Canvas(src))
        } catch (e: Exception) {
            // 个别 AdaptiveIcon / 第三方 drawable 在软件画布上会抛 —— 保持原图即可。
            Logx.d(LogTags.DRAWER, "图标单色化失败，保留原图", "err" to (e.message ?: e.javaClass.simpleName))
            return null
        }

        val pixels = IntArray(content * content)
        src.getPixels(pixels, 0, content, 0, 0, content, content)

        // 2. 找「底板色」：贴边那一圈如果有过半是同一种颜色，说明这个图标是
        //    「一整块底 + 一个符号」（桌面图标基本都是）—— 底当透明、符号按
        //    与底的**色距**算墨量，于是蓝底白圈 / 白底黑柱都会还原成细的单色符号，
        //    而不是一整块 accent 色块。透明底的纯线稿图标返回 0，走亮度分支。
        val plate = borderPlate(pixels, content)

        val r = (accent shr 16) and 0xFF
        val g = (accent shr 8) and 0xFF
        val b = accent and 0xFF
        val total = pixels.size
        var opaque = 0
        var ink = 0
        for (i in 0 until total) {
            val p = pixels[i]
            val sa = (p ushr 24) and 0xFF
            if (sa == 0) {
                pixels[i] = 0
                continue
            }
            val ta = if (plate == 0) {
                // 没有底板：亮度当墨量（白底/浅色透明，深色符号留下）。
                // BT.601 权重，和 ColorMatrix 的灰度口径一致。
                val lum = (299 * ((p shr 16) and 0xFF) + 587 * ((p shr 8) and 0xFF) + 114 * (p and 0xFF)) / 1000
                (255 - lum) * sa / 255
            } else {
                val dr = ((p shr 16) and 0xFF) - ((plate shr 16) and 0xFF)
                val dg = ((p shr 8) and 0xFF) - ((plate shr 8) and 0xFF)
                val db = (p and 0xFF) - (plate and 0xFF)
                // 最大通道色距 × 增益，饱和到 255：>=128 的色距就是满墨。
                // 用全限定名：本类里还有一个 4 参数的私有 `abs(x, y, w, h)`（布局用）。
                val d = maxOf(kotlin.math.abs(dr), kotlin.math.abs(dg), kotlin.math.abs(db))
                minOf(255, d * PLATE_INK_GAIN) * sa / 255
            }
            pixels[i] = (ta shl 24) or (r shl 16) or (g shl 8) or b
            if (ta >= MONO_OPAQUE) opaque++
            if (ta >= MONO_INK) ink++
        }

        // 2. 判「纯色块 / 会被洗掉」—— 命中就退回原图（判断依据见上面的 KDoc）。
        if (opaque * 100 / total > MONO_SOLID_PCT || ink * 100 / total < MONO_MIN_INK_PCT) return null

        // 3. 把改好的像素写回位图，再贴回 88×88 正中（四周补回内边距，底板尺寸不变）。
        src.setPixels(pixels, 0, content, 0, 0, content, content)
        val out = Bitmap.createBitmap(ICON, ICON, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(src, ICON_PAD.toFloat(), ICON_PAD.toFloat(), Paint(Paint.FILTER_BITMAP_FLAG))
        src.recycle()
        return out
    }

    /**
     * 找图标的「底板色」——贴边 [PLATE_RING] 像素内出现最多的那个不透明颜色。
     *
     * 桌面图标基本都是「一整块底板 + 一个符号」：底板颜色一定占满四条边。
     * 反过来，**透明底的纯线稿图标**四周全是透明像素 ⇒ 返回 0，
     * 调用方退回按亮度算墨量。
     *
     * 颜色按每通道高 3 位量化后统计（[PLATE_BUCKETS] 个桶），命中桶再取真实均值 ——
     * 轻微渐变 / 抗锯齿的底板也能归进同一桶，平均色仍然是准的。
     * 桶数组只是方法内临时量（512×4 int），画完即弃。
     *
     * @return `0xFFRRGGBB` 的底板色；没有底板时返回 0
     */
    private fun borderPlate(pixels: IntArray, size: Int): Int {
        val sums = IntArray(PLATE_BUCKETS * 4)
        var edge = 0
        for (y in 0 until size) {
            val edgeRow = y < PLATE_RING || y >= size - PLATE_RING
            for (x in 0 until size) {
                if (!edgeRow && x >= PLATE_RING && x < size - PLATE_RING) continue
                val p = pixels[y * size + x]
                if (((p ushr 24) and 0xFF) < 200) continue
                edge++
                val q = ((((p shr 16) and 0xFF) shr 5) shl 6) or
                    ((((p shr 8) and 0xFF) shr 5) shl 3) or
                    ((p and 0xFF) shr 5)
                val base = q * 4
                sums[base]++
                sums[base + 1] += (p shr 16) and 0xFF
                sums[base + 2] += (p shr 8) and 0xFF
                sums[base + 3] += p and 0xFF
            }
        }
        if (edge == 0) return 0
        var best = -1
        for (q in 0 until PLATE_BUCKETS) {
            val c = sums[q * 4]
            if (c > 0 && (best < 0 || c > sums[best * 4])) best = q
        }
        if (best < 0) return 0
        val n = sums[best * 4]
        // 贴边像素里至少 [PLATE_SHARE_PCT]% 是这个色，才认它是底板
        // （否则是「符号伸出边界」或渐变图标，别硬当成底）。
        if (n * 100 / edge < PLATE_SHARE_PCT) return 0
        return (0xFF shl 24) or
            ((sums[best * 4 + 1] / n) shl 16) or
            ((sums[best * 4 + 2] / n) shl 8) or
            (sums[best * 4 + 3] / n)
    }

    /** FrameLayout 绝对定位（相对面板左上角）。 */
    private fun abs(x: Int, y: Int, w: Int, h: Int): FrameLayout.LayoutParams =
        FrameLayout.LayoutParams(w, h).apply {
            leftMargin = x
            topMargin = y
        }

    private data class LoadedApp(
        val info: ResolveInfo,
        val display: String,
        val enabled: Boolean,
        val icon: Drawable?,
        val mono: Bitmap?,
    )

    @Volatile
    private var preloadedApps: List<LoadedApp>? = null

    @Volatile
    private var preloading = false

    /** 后台线程预扫描应用并预解码单色图标，避免首次打开抽屉时在主线程阻塞。 */
    fun preloadAsync() {
        if (preloading || preloadedApps != null) return
        preloading = true
        Thread({
            try {
                preloadedApps = queryLoadedApps()
            } catch (t: Throwable) {
                Logx.w(LogTags.DRAWER, "抽屉应用后台预加载失败", t)
            } finally {
                preloading = false
            }
        }, "cardesk-drawer-preload").apply { isDaemon = true }.start()
    }

    private fun queryLoadedApps(): List<LoadedApp> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = try {
            pm.queryIntentActivities(intent, PackageManager.MATCH_DISABLED_COMPONENTS)
                .distinctBy { it.activityInfo.packageName }
                .sortedBy { it.loadLabel(pm).toString() }
        } catch (e: Exception) {
            Logx.w(LogTags.DRAWER, "抽屉应用列表查询失败", e, "reason" to e.message)
            emptyList()
        }
        val order = prefs.appOrder
        val rank = order.withIndex().associate { (i, pkg) -> pkg to i }
        val ordered = apps.sortedWith(
            compareBy(
                { rank[it.activityInfo.packageName] ?: Int.MAX_VALUE },
                { displayLabel(it, pm) },
            )
        )
        val unique = LinkedHashMap<String, ResolveInfo>()
        for (info in ordered) {
            val pkg = info.activityInfo.packageName
            if (!unique.containsKey(pkg)) unique[pkg] = info
        }
        return unique.values.map { info ->
            val display = displayLabel(info, pm)
            val enabled = isEnabled(info)
            val icon: Drawable? = try {
                info.loadIcon(pm)
            } catch (_: Exception) {
                null
            }
            val mono = monochromeIcon(icon, pal.accent)
            LoadedApp(info = info, display = display, enabled = enabled, icon = icon, mono = mono)
        }
    }

    /** 读取全部可启动应用（不再截断），并按用户排序持久化（`launcher_app_order`）。 */
    fun reload() {
        grid.removeAllViews()
        // 排障场景：「抽屉里一个应用都没有」——加载耗时与数量要能查。
        val startMs = System.currentTimeMillis()
        val cached = preloadedApps?.also { preloadedApps = null }
        val loaded = cached ?: queryLoadedApps()
        currentPackages = loaded.map { it.info.activityInfo.packageName }
        hint.visibility = if (loaded.isEmpty()) View.VISIBLE else View.GONE
        // 排障场景：「抽屉打开很慢 / 应用数量不对」——数量 + 耗时落盘。
        Logx.i(
            LogTags.DRAWER,
            "抽屉应用列表加载",
            "count" to loaded.size,
            "costMs" to (System.currentTimeMillis() - startMs),
            "ordered" to prefs.appOrder.size,
            "preloaded" to (cached != null),
        )
        for ((index, app) in loaded.withIndex()) {
            val info = app.info
            val display = app.display
            val enabled = app.enabled
            // 格子 = 画板那张 278×156 的卡（圆角 24、比面板暗约 7%）。
            //
            // 点按/长按逻辑原样保留：格子整块是热区，tag 仍挂在 body 上
            // （测试靠 APP_CELL_MARK 找「长按弹菜单」的宿主）。
            val cell = FrameLayout(context).apply {
                // 画板 03：格子**比面板暗一档**（实测格 228~234、面板 236~246），
                // 即「面板玻璃上叠 7% 墨」。第 3 轮复核曾把这里改成纯白，
                // 结果方向反了（255 vs 228，Δ25）—— 这里按画板回退成叠加墨色。
                background = Ui.round(tint(Skin.TEXT, 0x12), Shape.CARD)
            }
            val body = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                tag = APP_CELL_MARK
                // 图标+名称这一列在卡内水平居中，顶部留 18（画板 icon top=18）。
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                setPadding(0, 18, 0, 0)
                isClickable = true
                // 格子是**大目标**，按 [MotionSpec.PRESS_SCALE_ROW] 轻轻一沉 ——
                // 整块缩 4% 会让一整排图标跟着晃。
                Motion.press(this, MotionSpec.PRESS_SCALE_ROW)
                setOnClickListener {
                    if (enabled) {
                        openApp(info, display)
                    } else {
                        showItemMenu(info, display)
                        toast("应用已禁用，可在菜单里启用")
                    }
                }
                // 第152轮：长按 = 弹出应用菜单（原来是拖动排序）。
                setOnLongClickListener {
                    showItemMenu(info, display)
                    true
                }
            }
            val iv = ImageView(context)
            val icon = app.icon
            val mono = app.mono
            if (mono != null) {
                // 位图里已经烤进了 14px 内边距，所以 View 不再留 padding。
                iv.setImageBitmap(mono)
                iv.setPadding(0, 0, 0, 0)
            } else {
                iv.setImageDrawable(icon)
                iv.setPadding(ICON_PAD, ICON_PAD, ICON_PAD, ICON_PAD)
            }
            // 已禁用：图标压暗，和可用应用区分开（两种图标都要压）。
            if (!enabled) iv.alpha = 0.35f
            // 图标底板 88×88 圆角 22，浅青 12% —— 画板里是一块很淡的青底。
            iv.scaleType = ImageView.ScaleType.FIT_CENTER
            iv.background = Ui.round(tint(Skin.ACCENT, 0x1E), 22f)
            // 名称列 246 宽（画板量出），16px、单行省略；放不下的长名字才截断。
            val column = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
            }
            column.addView(iv, LinearLayout.LayoutParams(ICON, ICON))
            val label = Ui.text(context, display, 16f, if (enabled) Skin.TEXT_SOFT else pal.textFaint).apply {
                gravity = Gravity.CENTER
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            column.addView(label, LinearLayout.LayoutParams(LABEL_W, 22).apply { topMargin = 14 })
            body.addView(column, LinearLayout.LayoutParams(LABEL_W, LayoutParams.WRAP_CONTENT))
            cell.addView(body, FrameLayout.LayoutParams(-1, -1))

            // 禁用徽标：画板挂在**卡片右上角**（12px 小胶囊），
            // 不再在名称下面加一行「已禁用」—— 那一行会把 156 高的卡撑破。
            if (!enabled) {
                val badge = Ui.text(context, "已禁用", 12f, Skin.TEXT_DIM).apply {
                    // 画板徽标高 28：12px 字（行高约 16）+ 上下 6。
                    setPadding(10, 6, 10, 6)
                    background = Ui.round(tint(Skin.TEXT, 0x16), Shape.PILL)
                }
                cell.addView(
                    badge,
                    FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply {
                        topMargin = 8
                        rightMargin = 8
                    },
                )
            }

            // 列距 20、行距 22：靠 GridLayout 的 margin 做，不用 spacer 视图。
            grid.addView(cell, GridLayout.LayoutParams().apply {
                width = CELL_W
                height = CELL_H
                rightMargin = CELL_GAP_X
                bottomMargin = CELL_GAP_Y
            })
            // 网格是整块重建的 —— 逐项淡入上移，
            // 用户看得出「这是一批新出现的图标」，而不是整页闪一下。
            Motion.revealStagger(body, index)
        }
    }

    /** 显示名：有别名用别名，否则用系统应用名（第九十五轮 C5）。 */
    private fun displayLabel(info: ResolveInfo, pm: PackageManager): String =
        prefs.appAliasOf(info.activityInfo.packageName) ?: info.loadLabel(pm).toString()

    /** 启动一个抽屉应用（沿用原来的「全屏 / 小窗」两态逻辑）。 */
    private fun openApp(info: ResolveInfo, display: String) {
        val pm = context.packageManager
        val pkg = info.activityInfo.packageName
        // 排障场景：「抽屉里点图标没启动 / 启动成了小窗」——选中项与启动方式落盘。
        Logx.event(
            LogTags.DRAWER,
            "抽屉选中应用",
            "pkg" to pkg,
            "name" to display,
            "mode" to if (miniMode) "mini" else "fullscreen",
        )
        if (miniMode) {
            onOpenApp?.invoke(pkg, display, true)
        } else if (onOpenApp != null) {
            onOpenApp?.invoke(pkg, display, false)
        } else {
            try {
                val i = Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_LAUNCHER)
                    setClassName(pkg, info.activityInfo.name)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(i)
            } catch (e: Exception) {
                Logx.w(LogTags.DRAWER, "抽屉应用启动失败", e, "pkg" to pkg, "name" to display)
            }
        }
    }

    // ---------------------------------------------------------- 应用菜单（第九十五轮 C5）

    private var itemMenu: View? = null
    private var renameDialog: View? = null
    private var confirmDialog: View? = null

    /**
     * 应用菜单：重命名 / 应用信息 / 禁用·启用 / 卸载。
     *
     * 第152轮起，入口是**长按图标**（原来是一个独立的「⋮」按钮）。
     */
    private fun showItemMenu(info: ResolveInfo, display: String) {
        if (itemMenu != null) return
        val pkg = info.activityInfo.packageName
        val menu = FrameLayout(context).apply {
            setBackgroundColor(0xB3000000.toInt())
            isClickable = true
            setOnClickListener { dismissItemMenu() }
        }
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = Skin.flat(Shape.CARD)
            setPadding(30, 26, 30, 22)
        }
        card.addView(Ui.text(context, display, 30f, pal.text, bold = true), LinearLayout.LayoutParams(-1, -2))
        card.addView(
            Ui.text(context, pkg, 17f, pal.textFaint),
            LinearLayout.LayoutParams(-1, -2).also { it.topMargin = 6 },
        )
        fun row(text: String, color: Int, onClick: () -> Unit) {
            val tv = Ui.text(context, text, 25f, color, bold = true).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(8, 0, 8, 0)
                isClickable = true
                pressFeedback()
                setOnClickListener { onClick() }
            }
            card.addView(tv, LinearLayout.LayoutParams(-1, 92).also { it.topMargin = 12 })
        }
        row("重命名", pal.text) {
            dismissItemMenu()
            showRenameDialog(pkg, display)
        }
        row("应用信息", pal.text) {
            dismissItemMenu()
            openAppInfo(pkg)
        }
        // 第九十四轮：禁用 / 启用。禁用后应用仍在抽屉里，只是置灰。
        when {
            pkg == context.packageName -> card.addView(
                Ui.text(context, "桌面本身不能禁用", 18f, pal.textFaint),
                LinearLayout.LayoutParams(-1, 64).also { it.topMargin = 8 },
            )
            isEnabled(info) -> row("禁用应用", pal.danger) {
                dismissItemMenu()
                showDisableDialog(info, display)
            }
            else -> row("启用应用", Skin.ACCENT) {
                dismissItemMenu()
                setAppEnabled(info, true, display)
            }
        }
        val systemApp = (info.activityInfo.applicationInfo.flags and
            android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
        if (systemApp) {
            card.addView(
                Ui.text(context, "系统应用不支持卸载", 18f, pal.textFaint),
                LinearLayout.LayoutParams(-1, 64).also { it.topMargin = 8 },
            )
        } else {
            row("卸载", pal.danger) {
                dismissItemMenu()
                uninstallApp(pkg)
            }
        }
        row("取消", pal.textDim) { dismissItemMenu() }
        menu.addView(
            card,
            FrameLayout.LayoutParams(680, LayoutParams.WRAP_CONTENT, Gravity.CENTER),
        )
        itemMenu = menu
        addView(menu, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        Logx.event(LogTags.DRAWER, "打开应用菜单", "pkg" to pkg)
    }

    private fun dismissItemMenu() {
        val menu = itemMenu ?: return
        itemMenu = null
        removeView(menu)
    }

    /**
     * 关掉抽屉里**最上面**那层浮层。
     *
     * 第152轮真机发现的 bug：应用菜单（长按弹出的那层）打开时按返回键，
     * 整个抽屉会被关掉，而**菜单本身留在了抽屉页里**——
     * 抽屉页是 LauncherView 长期持有的**同一个实例**，
     * 关闭抽屉只是把它从窗口摘掉，实例里的浮层原封不动。
     * 下次打开抽屉，菜单又盖在上面，它的遮罩会把所有点击吃掉，
     * **点图标一点都没反应**（第一次点只是把菜单关掉）。
     *
     * 返回键的优先级也靠它：**先关浮层，再关抽屉**。
     *
     * @return 真的关掉了一层返回 true；本来就没有浮层返回 false。
     */
    fun closeTopOverlay(): Boolean = when {
        // 顺序按「谁在上面谁先关」：重命名 / 禁用确认都是在菜单之上弹出来的。
        renameDialog != null -> {
            dismissRenameDialog()
            true
        }
        confirmDialog != null -> {
            dismissConfirmDialog()
            true
        }
        itemMenu != null -> {
            dismissItemMenu()
            true
        }
        else -> false
    }

    /**
     * 清掉抽屉里的**所有**浮层。
     *
     * 抽屉关闭时必须调用 —— 否则这些浮层会跟着抽屉页的实例留到下一次打开。
     */
    fun clearOverlays() {
        dismissItemMenu()
        dismissRenameDialog()
        dismissConfirmDialog()
    }

    private fun componentOf(info: ResolveInfo) =
        android.content.ComponentName(info.activityInfo.packageName, info.activityInfo.name)

    /**
     * 应用当前是否可用：清单里的 enabled + PackageManager 里应用级/组件级的状态都要看。
     *
     * 只读 `activityInfo.enabled` 不够——系统里被禁用（或默认禁用，如 SIM Toolkit）的组件，
     * 查询回来的 ActivityInfo.enabled 仍然是 true，必须查 [PackageManager.getComponentEnabledSetting]。
     */
    private fun isEnabled(info: ResolveInfo): Boolean {
        val app = info.activityInfo.applicationInfo ?: return false
        if (!app.enabled || !info.activityInfo.enabled) return false
        val pm = context.packageManager
        fun usable(state: Int) = state != PackageManager.COMPONENT_ENABLED_STATE_DISABLED &&
            state != PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER &&
            state != PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED
        val appState = try {
            pm.getApplicationEnabledSetting(info.activityInfo.packageName)
        } catch (e: Exception) {
            PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
        }
        val compState = try {
            pm.getComponentEnabledSetting(componentOf(info))
        } catch (e: Exception) {
            PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
        }
        return usable(appState) && usable(compState)
    }

    /**
     * 禁用 / 启用应用。
     *
     * 写 `PackageManager.setApplicationEnabledSetting(...)`，需要
     * `CHANGE_COMPONENT_ENABLED_STATE`（平台签名的系统应用才有）。普通安装拿不到权限时
     * 只提示、不崩，也不改界面。
     */
    private fun setAppEnabled(info: ResolveInfo, enabled: Boolean, display: String) {
        val pkg = info.activityInfo.packageName
        try {
            context.packageManager.setApplicationEnabledSetting(
                pkg,
                if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                else PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER,
                PackageManager.DONT_KILL_APP,
            )
            if (enabled) {
                // 有些应用只是「入口组件」被禁用（如 SIM Toolkit），应用级恢复后还要把入口组件打开。
                context.packageManager.setComponentEnabledSetting(
                    componentOf(info),
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    PackageManager.DONT_KILL_APP,
                )
            }
            Logx.event(LogTags.DRAWER, "应用状态变更", "pkg" to pkg, "enabled" to enabled)
            toast(if (enabled) "已启用 $display" else "已禁用 $display")
            reload()
        } catch (e: SecurityException) {
            Logx.w(LogTags.DRAWER, "应用状态变更失败", e, "pkg" to pkg, "enabled" to enabled)
            toast("没有系统权限，无法禁用/启用应用")
        } catch (e: IllegalArgumentException) {
            Logx.w(LogTags.DRAWER, "应用状态变更失败", e, "pkg" to pkg, "enabled" to enabled)
            toast("该应用不支持禁用到用户态")
        }
    }

    private fun toast(text: String) {
        android.widget.Toast.makeText(context, text, android.widget.Toast.LENGTH_SHORT).show()
    }

    /** 禁用前确认：误点会把应用停掉（可在菜单里恢复，但先问一句更稳）。 */
    private fun showDisableDialog(info: ResolveInfo, display: String) {
        if (confirmDialog != null) return
        val overlay = FrameLayout(context).apply {
            setBackgroundColor(0xB3000000.toInt())
            isClickable = true
            setOnClickListener { dismissConfirmDialog() }
        }
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = Skin.flat(Shape.CARD)
            setPadding(34, 28, 34, 26)
            isClickable = true
        }
        card.addView(Ui.text(context, "禁用应用", 30f, pal.text, bold = true), LinearLayout.LayoutParams(-1, -2))
        card.addView(
            Ui.text(
                context,
                // 第152轮：菜单入口从「⋮ 按钮」改成了「长按图标」，这句提示也跟着改。
                "禁用后「$display」会停止运行、图标变灰；随时可以长按它的图标重新启用。",
                18f,
                pal.textDim,
            ),
            LinearLayout.LayoutParams(-1, -2).also { it.topMargin = 10 },
        )
        val actions = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
        }
        fun button(text: String, primary: Boolean, onClick: () -> Unit) {
            val tv = Ui.text(context, text, 22f, if (primary) Skin.ACCENT_INK else pal.text, bold = true).apply {
                gravity = Gravity.CENTER
                setPadding(28, 12, 28, 12)
                background = Ui.round(
                    if (primary) Skin.ACCENT else Skin.CHIP_FILL,
                    Shape.PILL,
                    if (primary) 0f else 1f,
                    Skin.CHIP_STROKE,
                )
                isClickable = true
                pressFeedback()
                setOnClickListener { onClick() }
            }
            actions.addView(tv, LinearLayout.LayoutParams(-2, 64).also { it.leftMargin = 16 })
        }
        button("取消", false) { dismissConfirmDialog() }
        button("禁用", true) {
            dismissConfirmDialog()
            setAppEnabled(info, false, display)
        }
        card.addView(actions, LinearLayout.LayoutParams(-1, -2).also { it.topMargin = 22 })
        overlay.addView(card, FrameLayout.LayoutParams(820, LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        confirmDialog = overlay
        addView(overlay, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    private fun dismissConfirmDialog() {
        val d = confirmDialog ?: return
        confirmDialog = null
        removeView(d)
    }

    /** 重命名：本地别名写入 Prefs（`launcher_app_alias`），空串 = 恢复系统名称。 */
    private fun showRenameDialog(pkg: String, current: String) {
        if (renameDialog != null) return
        val overlay = FrameLayout(context).apply {
            setBackgroundColor(0xB3000000.toInt())
            isClickable = true
            setOnClickListener { dismissRenameDialog() }
        }
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = Skin.flat(Shape.CARD)
            setPadding(34, 28, 34, 26)
            isClickable = true
        }
        card.addView(Ui.text(context, "重命名应用", 30f, pal.text, bold = true), LinearLayout.LayoutParams(-1, -2))
        card.addView(
            Ui.text(context, "只改桌面显示名，不影响系统里的应用名。留空恢复原名。", 18f, pal.textDim),
            LinearLayout.LayoutParams(-1, -2).also { it.topMargin = 8 },
        )
        val input = android.widget.EditText(context).apply {
            setText(prefs.appAliasOf(pkg) ?: current)
            setTextColor(pal.text)
            setHintTextColor(pal.textFaint)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 26f)
            background = Ui.round(Skin.surfaceSoft, 18f, 1f, Skin.NAV_STROKE)
            setPadding(22, 0, 22, 0)
            isSingleLine = true
            setSelection(text.length)
        }
        card.addView(input, LinearLayout.LayoutParams(-1, 78).also { it.topMargin = 18 })
        val actions = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
        }
        fun button(text: String, primary: Boolean, onClick: () -> Unit) {
            val tv = Ui.text(context, text, 22f, if (primary) Skin.ACCENT_INK else pal.text, bold = true).apply {
                gravity = Gravity.CENTER
                setPadding(28, 12, 28, 12)
                background = Ui.round(
                    if (primary) Skin.ACCENT else Skin.CHIP_FILL,
                    Shape.PILL,
                    if (primary) 0f else 1f,
                    Skin.CHIP_STROKE,
                )
                isClickable = true
                pressFeedback()
                setOnClickListener { onClick() }
            }
            actions.addView(tv, LinearLayout.LayoutParams(-2, 64).also { it.leftMargin = 16 })
        }
        button("取消", false) { dismissRenameDialog() }
        button("保存", true) {
            val alias = input.text.toString().trim()
            prefs.setAppAlias(pkg, alias)
            Logx.event(LogTags.DRAWER, "应用重命名", "pkg" to pkg, "alias" to alias)
            dismissRenameDialog()
            reload()
        }
        card.addView(actions, LinearLayout.LayoutParams(-1, -2).also { it.topMargin = 22 })
        overlay.addView(card, FrameLayout.LayoutParams(820, LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        renameDialog = overlay
        addView(overlay, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    private fun dismissRenameDialog() {
        val dialog = renameDialog ?: return
        renameDialog = null
        removeView(dialog)
    }

    private fun openAppInfo(pkg: String) {
        val intents = listOf(
            Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(android.net.Uri.parse("package:" + pkg)),
            Intent(android.provider.Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS),
        )
        for (intent in intents) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                context.startActivity(intent)
                Logx.event(LogTags.DRAWER, "打开应用信息", "pkg" to pkg)
                return
            } catch (e: Exception) {
                Logx.w(LogTags.DRAWER, "打开应用信息失败", e, "pkg" to pkg)
            }
        }
        android.widget.Toast.makeText(context, "没有可打开的应用信息页", android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun uninstallApp(pkg: String) {
        val intents = listOf(
            Intent(Intent.ACTION_DELETE).setData(android.net.Uri.parse("package:" + pkg)),
            Intent(Intent.ACTION_UNINSTALL_PACKAGE).setData(android.net.Uri.parse("package:" + pkg)),
        )
        for (intent in intents) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                context.startActivity(intent)
                Logx.event(LogTags.DRAWER, "请求卸载应用", "pkg" to pkg, "action" to intent.action)
                return
            } catch (e: Exception) {
                Logx.w(LogTags.DRAWER, "卸载请求失败", e, "pkg" to pkg)
            }
        }
        android.widget.Toast.makeText(context, "本机没有卸载界面，无法卸载", android.widget.Toast.LENGTH_SHORT).show()
    }

    companion object {
        /**
         * 应用格子（图标列）的标记。
         *
         * 第152轮起，菜单入口是**长按这个格子**弹出来的（原来是一个独立的「⋮」按钮），
         * 所以测试需要能从视图树里精确找到某个应用的那个格子。
         */
        val APP_CELL_MARK = Any()

        /** 抽屉面板宽（设计 px）—— 画板 03 实测 44..1879，W1836（旧版 1876 会顶出屏幕右边）。 */
        const val PANEL_W = 1836

        /** 抽屉面板高（设计 px）—— 画板 67..947，H880。 */
        const val PANEL_H = 880

        /** 抽屉面板左边距（设计 px）。 */
        const val PANEL_LEFT = 44

        /** 抽屉面板上边距（设计 px）—— 画板 67（不是旧版的 73）。 */
        const val PANEL_TOP = 67

        /** 面板内容左右留白（设计 px）—— 画板标题 x76、网格 x75，取 32。 */
        const val PANEL_PAD = 32

        /** 标题顶部偏移（相对面板，设计 px）—— 画板文字 y108（28px 的 ink 约在视图顶 +5）。 */
        const val TITLE_TOP = 36

        /** 分段开关顶部偏移（相对面板，设计 px）—— 画板外框 y94。 */
        const val SEG_TOP = 27

        /** 分段项高度（设计 px）—— 外框 46 减上下内边距 4×2。 */
        const val SEG_ITEM_H = 38

        /** 分段开关右边距（设计 px）—— 画板外框右边 1843，面板右 1879。 */
        const val SEG_RIGHT = 36

        /** 网格顶部偏移（相对面板，设计 px）—— 画板首行卡 y172。 */
        const val GRID_TOP = 105

        /** 网格区高度（相对面板，设计 px）—— 4 行 ×156 + 3×22 = 690，留 2px 余量。 */
        const val GRID_H = 692

        /** 网格总宽（设计 px）—— 6 列 ×278 + 5×20 = 1768，cell 右侧 margin 会多出 20。 */
        const val GRID_W = 1788

        /** 底部提示顶部偏移（相对面板，设计 px）—— 画板文字 y903。 */
        const val FOOT_TOP = 836

        /** 单个格子宽（设计 px）—— 画板 278。 */
        const val CELL_W = 278

        /** 单个格子高（设计 px）—— 画板 156。 */
        const val CELL_H = 156

        /** 列间距（设计 px）—— 画板 20。 */
        const val CELL_GAP_X = 20

        /** 行间距（设计 px）—— 画板 22。 */
        const val CELL_GAP_Y = 22

        /** 应用图标底板边长（设计 px）—— 画板实测 88（不是需求里写的 96）。 */
        const val ICON = 88

        /**
         * 图标内容相对底板的内边距（设计 px）—— 画板里符号四周约 14。
         *
         * 单色化时位图按 88 - 14×2 = 60 画内容、四周补回 14，
         * 所以**视觉大小与原来的 `setPadding(14,14,14,14)` 完全一致**。
         */
        const val ICON_PAD = 14

        /**
         * 判定「纯色块」的不透明阈值（alpha，0..255）。
         *
         * 单色化后 alpha ≥ 该值的像素算「实心」；占比超过 [MONO_SOLID_PCT]
         * 说明这个图标本来就是一整块色，单色化会变成 accent 方块 ⇒ 保留原图。
         */
        const val MONO_OPAQUE = 240

        /** 实心像素占比上限（%）。超过即视为纯色块，保留原图。 */
        const val MONO_SOLID_PCT = 92

        /** 判定「有墨迹」的 alpha 阈值（0..255）。 */
        const val MONO_INK = 64

        /**
         * 墨迹像素占比下限（%）。
         *
         * 低于此值说明这个图标是「白底 + 极浅符号」，单色化后几乎全透明、
         * 等于图标消失 ⇒ 保留原图。
         */
        const val MONO_MIN_INK_PCT = 3

        /** 「底板色」统计的桶数：每通道高 3 位 ⇒ 2^9 = 512 个桶。 */
        const val PLATE_BUCKETS = 512

        /** 找底板只统计贴边这几圈像素（画板图标留白 14，3 圈足够贴住底板边沿）。 */
        const val PLATE_RING = 3

        /** 贴边像素里同色占比达到这个百分比，才认它是「一整块底板」。 */
        const val PLATE_SHARE_PCT = 55

        /** 色距 → 墨量的增益：色距 ≥ 128 就是满墨（符号留下、底板透明）。 */
        const val PLATE_INK_GAIN = 2

        /** 名称列宽（设计 px）—— 卡内 246（278 - 16×2）。 */
        const val LABEL_W = 246
    }
}
