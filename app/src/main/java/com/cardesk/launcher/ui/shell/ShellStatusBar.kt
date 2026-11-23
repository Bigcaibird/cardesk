package com.cardesk.launcher.ui.shell

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import com.cardesk.launcher.core.Design
import com.cardesk.launcher.core.Glass
import com.cardesk.launcher.core.Skin
import com.cardesk.launcher.core.WallpaperContrast
import com.cardesk.launcher.core.log.LogTags
import com.cardesk.launcher.core.log.Logx
import com.cardesk.launcher.ui.WeatherGlyphs
import com.cardesk.launcher.ui.anim.Motion
import com.cardesk.launcher.ui.anim.MotionSpec
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 顶部状态栏（布局 view_design_status：1920×1080 覆盖层）。
 *
 * **第五十二轮：整行元素统一缩到 2/3**（用户反馈「状态栏占的空间还是太大了」）。
 * 缩放口径 = 乘 2/3 后取整，行内所有间距 / 字号 / 图标尺寸同步缩，
 * 因此左右两段仍然对齐、内容基线整体上移。
 *
 * 左：36px 头像 + 29px 高"玻璃胶囊"（8px 状态点 + 13px 文案）。
 * 右：蓝牙/热点/WiFi/信号 17×17 图标，燃油图标 16px + 13px 百分比，25px 时间，
 *     **以及位置 + 天气两个胶囊**（第八十二轮从中置挪过来）。
 * 全部自绘，图标用 Path/圆角矩形近似（内部按 17×17 画，用 [drawIcon] 统一缩放）。
 *
 * **第八十二轮：状态栏正中空出来** —— 原先摆在中间的「位置 + 天气」挪到了右侧图标行，
 * 正中改放**翻页指示圆点**（见 `PageDotsView`）。
 *
 * 第四十三轮曾整体缩小一档、第四十四轮按用户要求**恢复成原值**
 * 第五十二轮按用户要求**再缩到 2/3**（车机屏幕小，状态栏原来吃掉太多竖向空间）。
 * 顶部渐变遮罩在第四十九轮「浮屿」版式里已去掉。
 *
 * **第六十三轮：行内元素放大一档，上下空隙收窄**（用户反馈「字和图标放大一点，
 * 状态栏上下都有一段空间」）。口径 = **状态栏这一行的整体大小不动**
 * （[ROW_TOP] / [ROW_H] / [EDGE] 一律不改），只把行内的字与图标放大，
 * 让元素在原行高里占得更满 —— 行内元素是垂直居中的，字号图标一大，
 * 元素上下的空隙自然就小了。
 * 放大幅度取「第五十二轮 2/3」与「原值」之间（约为原值的 0.8~0.85）。
 *
 * **第223轮：胶囊跟着壁纸明暗走** ——
 * 用户反馈「状态栏白色的胶囊在黑色的壁纸上有点丑」：
 * 胶囊**有底板**，好不好看取决于底板跟壁纸上合不合，不取决于文字对比度。
 * 所以这一行的**三个胶囊**（驾驶模式 / 位置 / 天气）的底板 + 描边 + 内部文字
 * 一起按壁纸明暗成套切换（暗 ⇒ 夜间那套 `#171A22`/`#333947`，亮 ⇒ 日间那套）。
 * 详见 [Skin.overlayTop] 与 [com.cardesk.launcher.core.WallpaperContrast]。
 *
 * **第三百轮：按 `cardesk-ui/design/redesign/Main.png` 重排，去掉一切深色实心块**
 *
 * 设计稿这一行是**极简**的（实测，1:1）：
 * - 左：34×34 的**玻璃**圆角方块（x28..61、y5..38、圆角 12）+ 深色齿轮；
 *   紧随其后一个 34 高的**玻璃胶囊**（x74..220）—— 不再是强调色实心方块；
 * - 中：一组翻页点（accent 长条 22×6 + 弱色圆点 6，组中心 x≈888）；
 * - 右：**全部裸文字 / 裸图标**（位置 · 天气 · 蓝牙 · WiFi · 信号 · 油量% · 时间），
 *   没有任何胶囊底板。
 *
 * 所以：位置 / 天气两个胶囊被拆掉，改成裸元素；
 * 左边两件从「实心强调色 / 实心浅色」换成 `GlassDrawable` 那套玻璃
 * （`Glass.card`，带内高光与 1px rim），底板与文字仍成套跟着壁纸明暗走。
 *
 * 行高常量 [ROW_TOP] / [ROW_H] 也按实测改了（5 / 33 → 行中心 21.5）：
 * `PageDotsView` 直接引用这两个常量来纵向居中，行一改它自动跟着对齐，
 * 这正是第八十二轮把几何集中到这里的用意。
 *
 * **第三百零二轮：整行文字与图标放大 1.3~1.5 倍**（用户反馈「状态栏留白多，
 * 但图标和文字较小」）。
 * 口径 = **字号 / 图标 / 胶囊等内容元素统一放大 1.3~1.5 倍**（时钟这种最重的
 * 元素放最多 ×1.41，小图标 ×1.39，正文文字 ×1.36），左右边距 [EDGE] /
 * [EDGE_RIGHT] 不动（设计稿的左右对齐框架保持），行中心随 [ROW_TOP] /
 * [ROW_H] 从 21.5 下移到 27.5 —— 放大后的元素（最高 46）既不顶出屏幕上沿，
 * 也不压到下方内容区（内容从 y73 开始，行底 50.5，还留 22 的空隙）。
 * 间距只随字号微调（[ICON_GAP] 14→16 等），**不按倍数放大** ——
 * 留白要收窄正是本轮的目的。
 *
 * **第三百零三轮：实车「屏幕右侧闪」诊断日志**（用户反馈：装了 UI 重设计版后
 * 实车屏幕右侧有闪烁，用的是静态壁纸）。
 * 只加观测、不改行为：
 * - [invalidateCount] 观测口 —— `refresh()` 每秒会把 [assistText] /
 *   [fuelPercent] / [clockText] / [rangeText] 各赋值一次，而这几个 setter
 *   是**无条件 invalidate**，状态栏整行每秒至少重绘一次；
 *   心跳里打这个计数就能确认「每分钟重绘多少次」；
 * - [fuelPercent] / [clockText] / [rangeText] **值真的变化**时各打一条 INFO
 *   （[assistText] 本来就有）—— 车端数据在「有值↔null」间抖动会让右段
 *   `--%`↔`62%` 跳变，诊断包里直接能看到抖动节奏。
 */
class ShellStatusBar(context: Context) : View(context) {

    /**
     * 状态胶囊文案：
     * 形如「驾驶模式 · 待命」（缺省为「待命」）。
     */
    var assistText: String = "驾驶模式 · 待命"
        set(v) {
            val changed = field != v
            field = v
            // 排障场景：「驾驶模式胶囊不更新」——胶囊文案变化落盘。
            if (changed) {
                Logx.i(LogTags.DOCK, "状态栏驾驶模式胶囊变化", "assist" to v)
            }
            invalidate()
        }

    /**
     * 燃油百分比。**-1 = 车端没有数据**，显示布局的默认值 `--%`
     * （`android:text="--%"`）。
     */
    var fuelPercent: Int = FUEL_UNKNOWN_VALUE
        set(v) {
            val changed = field != v
            field = v
            // 排障场景（第三百零三轮）：实车「屏幕右侧闪」——
            // 车端油量数据在「有值↔null」间抖动会让这段文字每秒跳变。
            // 值变了才打，抖动节奏在诊断包里一目了然。
            if (changed) {
                Logx.i(LogTags.SYS, "状态栏油量变化", "fuel" to v)
            }
            invalidate()
        }

    /**
     * 连接类图标（蜂窝 / WiFi / 热点 / 蓝牙）的**真实状态**（第138轮）。
     *
     * 之前这四个图标是「画上去就一直亮着」的 —— 不管连没连 WiFi、热点开没开，
     * 四个图标永远同时显示，这就是用户说的「图标显示不太准确」。
     *
     * 现在改成：采样器（`SystemStatusSampler`）读真机状态，
     * 按 [SystemStatusPolicy] 的规则算出**要画哪几个、几格、什么颜色**，
     * 这里只负责画。
     */
    var systemStatus: com.cardesk.launcher.core.SystemStatusPolicy.Snapshot =
        com.cardesk.launcher.core.SystemStatusPolicy.Snapshot()
        set(v) {
            if (field == v) return
            field = v
            statusSlots = com.cardesk.launcher.core.SystemStatusPolicy.slots(v)
            invalidate()
        }

    /**
     * 由 [systemStatus] 算出的图标槽（**从右往左**排）。
     *
     * 单独存一份是为了不在每帧 `onDraw` 里重算（这一行每秒要重绘很多次）。
     */
    private var statusSlots: List<com.cardesk.launcher.core.SystemStatusPolicy.Slot> =
        com.cardesk.launcher.core.SystemStatusPolicy.slots(systemStatus)

    /** 时钟文案（外部直接给字符串时用这个）。 */
    var clockText: String = "20:35"
        set(v) {
            val changed = field != v
            field = v
            // 排障场景（第三百零三轮）：时钟每秒被赋值一次，但文案一分钟才变一次；
            // 变了才落一条，用于确认「时钟刷新节奏正常」。
            if (changed) {
                Logx.d(LogTags.DOCK, "状态栏时钟变化", "clock" to v)
            }
            invalidate()
        }

    var rangeText: String = ""
        set(v) {
            val changed = field != v
            field = v
            // 排障场景（第三百零三轮）：续航文案抖动（值↔空）也是右段闪烁候选。
            if (changed) {
                Logx.d(LogTags.DOCK, "状态栏续航变化", "range" to v)
            }
            invalidate()
        }

    // -------------------------------------------------- 位置 + 天气（第四轮新增）

    /**
     * 状态栏右侧的**位置**文案（城市名，来自天气/定位链路）。
     *
     * 空串 = 还没拿到位置（画「位置获取中」）。
     */
    var locationText: String = ""

    /** 状态栏的**天气**描述（决定天气图标，取值同 `WeatherDesk.KEY_SKY`）。 */
    var weatherSky: String = ""

    /** 状态栏的**天气**温度（整数）；null = 没有天气数据（画 `--°`）。 */
    var weatherTemp: Int? = null

    /**
     * 一次性刷新「位置 + 天气」。
     *
     * 三个字段合成一个入口，是为了**只在数据真的变了时打一条日志**：
     * 天气链路一次刷新会带回城市 / 天气 / 温度三个字段，
     * 分开打会变成三条噪音日志，而且看不出是不是同一次刷新。
     *
     * @param location 城市名（空 = 还没定位到）
     * @param sky 天气描述
     * @param temp 温度（null = 无数据）
     */
    fun setLocationWeather(location: String, sky: String, temp: Int?) {
        val loc = location.trim()
        if (loc == locationText && sky == weatherSky && temp == weatherTemp) return
        locationText = loc
        weatherSky = sky
        weatherTemp = temp
        // 排障场景：「状态栏上的位置/天气不显示或一直不更新」——
        // 城市、天气、温度一起落盘，能看出到底是哪一环没数据。
        Logx.i(
            LogTags.SYS,
            "状态栏位置天气刷新",
            "location" to loc,
            "sky" to sky,
            "temp" to (temp ?: TEMP_UNKNOWN_VALUE),
        )
        invalidate()
    }

    /**
     * 用格式刷新时钟：`SimpleDateFormat("H:mm", Locale.CHINA)`
     * （**单小时不补零**）。
     */
    fun setClock(date: Date) {
        clockText = CLOCK.format(date)
        // 排障场景：「状态栏时钟不刷新」——时钟每秒刷新一次，必须用 DEBUG（不落盘）。
        Logx.d(LogTags.DOCK, "状态栏时钟刷新", "clock" to clockText)
    }

    /** 点头像 / 点状态胶囊。 */
    var onAvatar: (() -> Unit)? = null
    var onAssist: (() -> Unit)? = null

    /**
     * `invalidate()` 被调用的次数（**观测口，第三百零三轮实车闪屏诊断**）。
     *
     * `refresh()` 每秒把 [assistText] / [fuelPercent] / [clockText] /
     * [rangeText] 各赋值一次而这几个 setter 是无条件 invalidate ⇒
     * 正常节奏就是**每秒至少 1 次**整行重绘；
     * 若车端数据抖动，这个数字会涨得更快（比如每秒 2 次）。
     * 诊断心跳每 30 秒读一次这个计数，涨速异常就是右段闪烁的直接证据。
     *
     * 覆写 `invalidate()` 计数（而不是问 View 的 dirty 状态）——
     * dirty 标志会被 layout / draw / 换肤等各路径置位，数不清来源；
     * 这里数的是「这一行的重绘请求来了多少次」。
     * 同一个模式在 `BackgroundView.invalidateCountForTest` 已有先例。
     */
    var invalidateCount: Long = 0L
        private set

    override fun invalidate() {
        super.invalidate()
        invalidateCount++
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Skin.TEXT
        textSize = 17f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
    }
    private val clockPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Skin.TEXT
        textSize = 32f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
    }
    private val fuelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Skin.TEXT
        textSize = 17f
    }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Skin.TEXT
        style = Paint.Style.STROKE
        strokeWidth = 2.6f
        strokeCap = Paint.Cap.ROUND
    }
    private val iconFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Skin.TEXT
        style = Paint.Style.FILL
    }
    private val locationPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Skin.TEXT_SOFT
        textSize = 17f
    }
    private val tempPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Skin.TEXT
        textSize = 22f
    }
    private val pinPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.3f
        strokeCap = Paint.Cap.ROUND
    }
    private val pinDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Skin.ACCENT_LIGHT }
    private val sepPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Skin.TEXT_DIM }
    private val avatarRect = RectF()

    /**
     * 驾驶模式胶囊的矩形。
     *
     * **第八十二轮：必须独立出来。**
     * 以前位置/天气胶囊画在屏幕正中，和左侧的驾驶模式胶囊隔得很远，
     * 所以「用同一个 RectF 复用」没暴露问题；后来天气胶囊挪到**右侧图标行**，
     * 两者虽然还是分开的，但复用的那个 RectF 会被**最后画的那个胶囊覆盖**
     * —— 点驾驶模式胶囊时 `contains` 判的是天气胶囊的位置，就再也点不开了。
     * 命中判定和绘制用的矩形必须一一对应，所以拆成独立的两个。
     *
     * 第三百轮：位置 / 天气改成裸元素，不再需要各自的 RectF，已删掉。
     */
    private val assistRect = RectF()

    private val tmp = RectF()

    // —— 按压反馈 ——
    //
    // 状态栏是**整块自绘**的，没有一个 View 能单独缩放，
    // 所以按压反馈只能自己画：记住按下了哪一块，动画一个缩放值，
    // 画那一块时围绕它自己的中心缩放（和 Dock 的做法一致）。
    //
    // 只给**能点的两块**加：设置齿轮（avatar）与驾驶模式胶囊（assist）。
    // 位置 / 天气胶囊目前不可点（没有命中区），不给它们加 —— 加了也没有入口。
    private var pressedRegion: String? = null
    private var pressScale = 1f
    private var pressAnimator: ValueAnimator? = null

    init {
        isClickable = true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // 第四十九轮「浮屿」：**去掉顶部渐变遮罩**。
        //
        // 第四十三轮那版是「沉浸式」：状态栏透明浮在任意背景上，所以要在顶部铺一条
        // 深色 → 透明的渐变把白字压住。现在背景已经换成**纯色底 + 极淡渐变**
        // （`BackgroundView` 不再有拟物山峦），内容也不会顶到屏幕最上沿，
        // 渐变遮罩反而会在顶部压出一条脏边 —— 直接去掉。
        //
        // 可读性改由**扁平分段胶囊**保证：每一段信息各自一个圆角底板，互不干扰。

        // 这一行元素的**垂直中心**（第三百轮按设计稿实测：元素 y5..38、高 34 ⇒ 中心 21.5）
        val cy = ROW_TOP + ROW_H / 2f

        // 第222轮：**裸元素**（时钟 / 油量% / 燃油图标 / 连接类图标）直接压在**壁纸**上，
        // 颜色按壁纸明暗走 —— 见 [com.cardesk.launcher.core.WallpaperContrast]。
        //
        // 这里必须**每帧重设**画笔颜色：
        // 换壁纸不会重建这个 View（只有换日夜才会），画笔颜色不刷就停在旧值上。
        val ink = Skin.overlayTop
        clockPaint.color = ink.text
        fuelPaint.color = ink.text
        iconPaint.color = ink.text
        // 定位针的中心点（设计稿是强调色小圆）也跟强调色成套走。
        pinDotPaint.color = ink.accentLight

        // 设置入口（第三百轮：**玻璃方块**，不再是强调色实心块）
        //
        // 设计稿这里是 34×34、圆角 12 的浅色玻璃方块 + 深色图标
        // （画板上的图标其实是「两条带滑块的横线」，语义就是设置，
        //  这里保留齿轮路径 —— 车的屏幕小，齿轮比 tune 更好认）。
        // 点击命中区（avatarRect）与回调 `onAvatar` 一点没变。
        drawSettingsTile(canvas, ink, cy)

        // 驾驶模式胶囊（玻璃底 + 强调色圆点 + 文案）
        drawAssistChip(canvas, ink, cy)

        // 正中翻页点。⚠️ 宿主（LauncherView）已经加了**可点击的** PageDotsView，
        // 两者画在同一行会叠在一起，所以这里只在「没有兄弟 PageDotsView」时
        // 才按设计稿补这一组（例如预览 / 单测 / 其它宿主）。
        if (!hasSiblingPageDots()) drawPageDots(canvas, ink, cy)

        // 右侧：全部裸元素（从右往左：时间 → 油量% → 油量图标 → 连接图标 → 天气 → 位置）
        drawRightRow(canvas, ink, cy)
    }

    /**
     * 左上角设置入口：**玻璃方块** + 齿轮。
     *
     * 第三百轮由「Skin.ACCENT 实心方块 + accentInk 齿轮」改成
     * 「玻璃底 + ink.text 齿轮」——设计稿这一行没有任何饱和色块，
     * 唯一带颜色的东西是驾驶胶囊里那颗状态点。
     * 第三百零二轮：方块 34 → 46、齿轮随行内元素一起放大（见 [AVATAR]）。
     * 点击命中区（avatarRect）与回调 `onAvatar` 语义不变。
     */
    private fun drawSettingsTile(canvas: Canvas, ink: WallpaperContrast.Ink, cy: Float) {
        avatarRect.set(EDGE, cy - AVATAR / 2f, EDGE + AVATAR, cy + AVATAR / 2f)
        // 按压反馈只作用在**这一小块**（围绕它自己的中心）——
        // 状态栏其它内容不跟着动，才看得出「按的是设置入口」。
        withPress(canvas, "avatar", avatarRect.centerX(), cy) {
            Glass.card(
                canvas, avatarRect, AVATAR_RADIUS, ink.surface,
                shadow = false, sheen = true, stroke = ink.stroke,
            )
            iconFill.color = ink.text
            canvas.drawPath(gearPath(avatarRect.centerX(), cy), iconFill)
        }
    }

    /**
     * 驾驶模式胶囊：玻璃底 + 强调色圆点 + 文案。
     *
     * 第三百轮：底板换成 `Glass.card`（内高光 + 1px rim），高度 34、圆角 17、
     * 文案 14px —— 与设计稿 x74..220 的实测一致；文字仍按壁纸明暗取墨色。
     */
    private fun drawAssistChip(canvas: Canvas, ink: WallpaperContrast.Ink, cy: Float) {
        textPaint.textSize = CHIP_TEXT_SIZE
        textPaint.color = ink.text
        val chipX = EDGE + AVATAR + CHIP_GAP
        val chipTextW = textPaint.measureText(assistText)
        assistRect.set(
            chipX, cy - CHIP_H / 2f,
            chipX + CHIP_PAD_L + CHIP_DOT + CHIP_PAD_M + chipTextW + CHIP_PAD_R,
            cy + CHIP_H / 2f,
        )
        val dotCx = assistRect.left + CHIP_PAD_L + CHIP_DOT / 2f
        // 胶囊（底板 + 指示点 + 文案）作为一个整体缩放，按压的落点才明确。
        withPress(canvas, "assist", assistRect.centerX(), cy) {
            Glass.card(
                canvas, assistRect, CHIP_RADIUS, ink.surface,
                shadow = false, sheen = true, stroke = ink.stroke,
            )
            iconFill.color = ink.accent
            canvas.drawCircle(dotCx, cy, CHIP_DOT / 2f, iconFill)
            canvas.drawText(
                assistText,
                dotCx + CHIP_DOT / 2f + CHIP_PAD_M,
                cy + CHIP_TEXT_SIZE / 3f,
                textPaint,
            )
        }
    }

    /**
     * 设计稿正中的翻页点：一段 accent 长条 + 一个弱色圆点。
     *
     * 实测：长条 x874..895、y19..24（≈22×6，全圆端），弱色圆点在 902..908（直径 6），
     * 整组中心 x≈888 —— 注意它**不在屏幕正中 960**，设计稿把它放在偏左的位置，
     * 这里照抄实测值（[PAGE_DOT_CENTER_X]）。
     *
     * ⚠️ 不可点击 —— 真正的翻页交互由 `PageDotsView` 负责（它有命中区与切页回调）。
     */
    private fun drawPageDots(canvas: Canvas, ink: WallpaperContrast.Ink, cy: Float) {
        val total = PAGE_DOT_BAR_W + PAGE_DOT_GAP + PAGE_DOT_SIZE
        val left = PAGE_DOT_CENTER_X - total / 2f
        val barTop = cy - PAGE_DOT_BAR_H / 2f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = ink.accent
        canvas.drawRoundRect(
            RectF(left, barTop, left + PAGE_DOT_BAR_W, barTop + PAGE_DOT_BAR_H),
            PAGE_DOT_BAR_H / 2f, PAGE_DOT_BAR_H / 2f, paint,
        )
        // 未选中的那一页：弱色小圆点（设计稿是灰调，不是白色）
        paint.color = ink.faint
        val dotCx = left + PAGE_DOT_BAR_W + PAGE_DOT_GAP + PAGE_DOT_SIZE / 2f
        canvas.drawCircle(dotCx, cy, PAGE_DOT_SIZE / 2f, paint)
    }

    /**
     * 宿主是否已经有 [PageDotsView]（它才是**可点击**的翻页指示器）。
     *
     * 第三百轮：设计稿要求正中有一组点，但第八十二轮起 `PageDotsView`
     * 就画在同一行同一位置。两者叠加会变成「一条玻璃条里 3 个点，
     * 右边又飘着 2 个点」—— 所以有它就不画，没它才补（预览 / 其它宿主）。
     */
    private fun hasSiblingPageDots(): Boolean {
        val group = parent as? android.view.ViewGroup ?: return false
        for (i in 0 until group.childCount) {
            if (group.getChildAt(i) is PageDotsView) return true
        }
        return false
    }

    /**
     * 右侧整行（**裸元素，全部无底板**，从右往左排）。
     *
     * 顺序与设计稿一致：时间 → 油量% → 油量图标 → 连接类图标（信号/WiFi/蓝牙/热点）
     * → 天气（图标 + 温度）→ 位置（定位针 + 城市）。
     *
     * ⚠️ 油量图标保留的是**油箱**而不是画板上的电池：
     * 这一项数据来源是 `VehicleStats.fuelPercent`（见 LauncherView 的赋值），
     * 是油量不是电量，画成电池会误导。差异已在交付说明里列出。
     */
    private fun drawRightRow(canvas: Canvas, ink: WallpaperContrast.Ink, cy: Float) {
        // 右侧边距与左侧**不一样**：画板左侧 28、右侧 ≈41（实测 17:10 的右边缘在 1877）。
        // 这里用一个独立常量，免得为了让右边对上而把左边推歪。
        val end = width - EDGE_RIGHT
        var x = end

        // 时间（设计稿里是这一行最重的一块：17px 粗体）
        clockPaint.textSize = CLOCK_TEXT_SIZE
        clockPaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        clockPaint.textAlign = Paint.Align.LEFT
        clockPaint.color = ink.text
        val clockW = clockPaint.measureText(clockText)
        // 第216轮曾在这里给时钟加「描边 + 底衬」（视频壁纸可读性），
        // **第217轮已按用户反馈全部回退**（「改得有点丑」）。
        // 第222轮起时钟是**裸文字**，颜色跟着壁纸明暗走，不加任何装饰。
        canvas.drawText(clockText, x - clockW, cy + CLOCK_TEXT_SIZE / 3f, clockPaint)
        x -= clockW + CLOCK_GAP

        // 油量百分比 + 图标（没有车端数据时显示默认的 `--%`）
        val pct = if (fuelPercent >= 0) "$fuelPercent%" else FUEL_UNKNOWN
        fuelPaint.textSize = FUEL_TEXT_SIZE
        fuelPaint.color = ink.text
        val pw = fuelPaint.measureText(pct)
        canvas.drawText(pct, x - pw, cy + FUEL_TEXT_SIZE / 3f, fuelPaint)
        x -= pw + FUEL_GAP
        drawIcon(canvas, x - FUEL_ICON, cy - FUEL_ICON / 2f, FUEL_ICON) { drawFuelIcon(it, 0f, 0f) }
        x -= FUEL_ICON + ICON_GAP

        // 连接类图标（第138轮）：按真实状态显示。
        //
        // 之前这里是**四个固定图标**（蜂窝 / WiFi / 热点 / 蓝牙）无条件画出来，
        // 看上去永远是「全都连着」—— 这就是「图标显示不太准确」的根源。
        // 现在由 [SystemStatusPolicy] 决定画哪几个、几格、什么颜色：
        // 连了 WiFi 就不画蜂窝、热点开着就不画 WiFi、蓝牙没开就不画蓝牙。
        for (slot in statusSlots) {
            drawIcon(canvas, x - ICON, cy - ICON / 2f, ICON) { drawStatusSlot(it, slot) }
            x -= ICON + ICON_GAP
        }

        // 天气 + 位置：第三百轮起是**裸元素**（原来各有一个圆角胶囊底板）
        x -= drawWeatherAndLocation(canvas, ink, cy, x)
    }

    /**
     * 按目标尺寸缩放绘制一个图标。
     *
     * 五个连接图标（蓝牙 / 热点 / WiFi / 信号 / 燃油）内部都是按 26×26 的坐标系
     * 硬编码的路径，这里统一用 `scale` 缩到目标尺寸，避免为了缩小而重画一遍路径。
     *
     */
    private inline fun drawIcon(
        canvas: Canvas,
        x: Float,
        y: Float,
        size: Float,
        draw: (Canvas) -> Unit,
    ) {
        val k = size / ICON_BASE
        canvas.save()
        canvas.translate(x, y)
        canvas.scale(k, k)
        draw(canvas)
        canvas.restore()
    }

    /**
     * 「位置 + 天气」段（第四轮新增；**第八十二轮**挪到右侧图标行；
     * **第三百轮**按设计稿改成**裸元素**，不再各带一个圆角胶囊底板）。
     *
     * 位置取天气链路带回来的**城市名**，天气取同一个链路的**天气文案 + 温度**，
     * 所以状态栏和第一页天气卡显示的一定是同一份数据。
     *
     * 段内顺序（从左往右）：位置（定位针 + 城市）→ 天气（图标 + 温度），
     * 与画板 `Main.png` 一致（`📍 尤奇亚 …… 4°`）。
     *
     * @param cy 状态栏这一行的中心 y
     * @param rightX 这一段的**右边界**（连接图标的左边）
     * @return 这一段占用的宽度（调用方据此继续往左排）
     */
    private fun drawWeatherAndLocation(
        canvas: Canvas,
        ink: WallpaperContrast.Ink,
        cy: Float,
        rightX: Float,
    ): Float {
        val hasLocation = locationText.isNotEmpty()
        val city = if (hasLocation) locationText else LOCATION_PENDING
        val temp = weatherTemp
        val tempText = if (temp != null) "$temp°" else TEMP_UNKNOWN

        locationPaint.textSize = LOCATION_TEXT_SIZE
        // 第223轮：文字跟着壁纸明暗走；第三百轮去掉底板后这一点更重要了 ——
        // 裸字压在壁纸上，颜色选错就是「看不见」，不是「丑」。
        locationPaint.color = if (hasLocation) ink.soft else ink.faint
        tempPaint.textSize = TEMP_TEXT_SIZE
        tempPaint.color = if (temp != null) ink.text else ink.faint
        locationPaint.textAlign = Paint.Align.LEFT
        tempPaint.textAlign = Paint.Align.LEFT

        val cityW = locationPaint.measureText(city)
        val tempW = tempPaint.measureText(tempText)

        // 城市名过长时截断（第八十二轮的老规矩：这一段是从右往左排的，
        // 地名一长就会顶到正中的翻页点上）。
        val maxCityW = LOC_MAX_W - (PIN_W + PIN_GAP)
        val cityText = if (cityW > maxCityW) clipEllipsis(city, maxCityW) else city
        val cityWW = if (cityW > maxCityW) locationPaint.measureText(cityText) else cityW

        val wxW = WX_ICON_W + WX_ICON_GAP + tempW
        val locW = PIN_W + PIN_GAP + cityWW
        val total = locW + WEATHER_LOCATION_GAP + wxW
        val x = rightX - total

        // ① 位置：定位针 + 城市名（裸）
        //
        // 第三百零一轮按画板 `Main.png` 调了**段内左右顺序**：画板里是
        // 「📍 尤奇亚 …… 4°」，即**位置在左、天气在右**；旧代码把天气画在前面
        // （从右往左排时天气恰好落在右边，看着像城市跟在温度后面），
        // 与画板相反。
        drawLocationPin(canvas, x + PIN_W / 2f, cy)
        canvas.drawText(cityText, x + PIN_W + PIN_GAP, cy + LOCATION_TEXT_SIZE / 3f, locationPaint)

        // ② 天气：图标 + 温度（裸）
        //
        // 天气图标的图形是按 24×24 视觉框硬编码的（[WeatherGlyphs]），
        // 这里按 [WX_ICON_SCALE] 等比缩放，描边也一起缩。
        val wxX = x + locW + WEATHER_LOCATION_GAP
        val wxCx = wxX + WX_ICON_W / 2f
        canvas.save()
        canvas.scale(WX_ICON_SCALE, WX_ICON_SCALE, wxCx, cy)
        WeatherGlyphs.draw(
            canvas,
            wxCx,
            cy,
            weatherSky,
            if (temp != null) ink.text else ink.faint,
            WX_ICON_STROKE / WX_ICON_SCALE,
        )
        canvas.restore()
        canvas.drawText(tempText, wxX + WX_ICON_W + WX_ICON_GAP, cy + TEMP_TEXT_SIZE / 3f, tempPaint)

        return total
    }

    /**
     * 把文本截到不超过 [maxW]（超出部分用「…」收尾）。
     *
     * 与 `ScenePage.clipLabel` 同一套口径：**先按整串量，超了就一个字一个字地截**，
     * 保证截完的宽度真的塞得进上限里。
     */
    private fun clipEllipsis(s: String, maxW: Float): String {
        if (s.isEmpty()) return s
        if (locationPaint.measureText(s) <= maxW) return s
        var out = s
        while (out.isNotEmpty() && locationPaint.measureText("$out…") > maxW) out = out.dropLast(1)
        return "$out…"
    }

    /** 定位针（一个水滴形轮廓 + 中心点）。 */
    private fun drawLocationPin(canvas: Canvas, cx: Float, cy: Float) {
        // 第六十三轮：定位针跟着字与图标一起放大（[PIN_SCALE]），
        // 描边宽度由 canvas 缩放一起放大，比例不变。
        canvas.save()
        canvas.scale(PIN_SCALE, PIN_SCALE, cx, cy)
        val p = Path().apply {
            moveTo(cx, cy + 6.7f)
            cubicTo(cx - 5.3f, cy + 0.7f, cx - 4.7f, cy - 6.7f, cx, cy - 6.7f)
            cubicTo(cx + 4.7f, cy - 6.7f, cx + 5.3f, cy + 0.7f, cx, cy + 6.7f)
            close()
        }
        pinPaint.color = Skin.overlayTop.soft
        canvas.drawPath(p, pinPaint)
        canvas.drawCircle(cx, cy - 2.7f, 2f, pinDotPaint)
        canvas.restore()
    }

    /**
     * 蓝牙（[SystemStatusPolicy.Kind.BLUETOOTH]）。
     *
     * @param emphasized 已连接 → 强调色；只是「已开启」→ 弱色。
     */
    private fun drawBluetooth(canvas: Canvas, x: Float, y: Float, emphasized: Boolean) {
        val p = Path().apply {
            moveTo(x + 8f, y + 1f)
            lineTo(x + 18f, y + 9f)
            lineTo(x + 8f, y + 17f)
            lineTo(x + 8f, y + 25f)
            lineTo(x + 18f, y + 17f)
        }
        val paint = Paint(iconPaint)
        // 第222轮：图标压在壁纸上 → 用浮层墨色（深底浅字 / 浅底深字）。
        paint.color = if (emphasized) Skin.overlayTop.accent else Skin.overlayTop.faint
        canvas.drawPath(p, paint)
        canvas.drawLine(x + 4f, y + 8f, x + 14f, y + 18f, paint)
        canvas.drawLine(x + 4f, y + 18f, x + 14f, y + 8f, paint)
        canvas.drawLine(x + 8f, y + 1f, x + 8f, y + 25f, paint)
    }

    /**
     * 热点（[SystemStatusPolicy.Kind.HOTSPOT]）。
     *
     * 开启的热点用**强调色**（原来就是 `ACCENT_LIGHT`，保持不变）。
     */
    private fun drawHotspot(canvas: Canvas, x: Float, y: Float, emphasized: Boolean) {
        val c = Paint(iconFill)
        // 第222轮：同 [drawBluetooth] —— 压在壁纸上的图标跟着壁纸明暗走。
        c.color = if (emphasized) Skin.overlayTop.accentLight else Skin.overlayTop.faint
        canvas.drawCircle(x + 13f, y + 13f, 4.5f, c)
        val arc = Paint(iconPaint)
        arc.color = c.color
        arc.strokeWidth = 2.4f
        tmp.set(x + 3f, y + 3f, x + 23f, y + 23f)
        canvas.drawArc(tmp, 200f, 140f, false, arc)
        tmp.set(x - 1f, y - 1f, x + 27f, y + 27f)
        canvas.drawArc(tmp, 205f, 130f, false, arc)
    }

    /**
     * WiFi（[SystemStatusPolicy.Kind.WIFI]）。
     *
     * ## 分档
     *
     * - `level >= 0`：一个点 + `min(level, 3)` 段弧（**level 0 = 只有点**，没弧）；
     * - `level == -1`（读不到）：只画点，**弱色** —— 不假装满格。
     *
     * 参考工程在读不到时是按满格画的，我们不这么做（见 [SystemStatusPolicy] 的说明）。
     */
    private fun drawWifi(canvas: Canvas, x: Float, y: Float, level: Int) {
        val known = level >= 0
        // 第222轮：同 [drawBluetooth]。
        val color = if (known) Skin.overlayTop.text else Skin.overlayTop.faint
        val c = Paint(iconFill).apply { this.color = color }
        canvas.drawCircle(x + 13f, y + 21f, 2.6f, c)
        val arcCount = if (known) level.coerceIn(0, 3) else 0
        val arc = Paint(iconPaint)
        arc.color = color
        for (i in 0 until arcCount) {
            val inset = 9f - i * 4.5f
            tmp.set(x + inset, y + (10f - i * 4.5f), x + 26f - inset, y + (26f - i * 4.5f))
            canvas.drawArc(tmp, 225f, 90f, false, arc)
        }
    }

    /**
     * 蜂窝信号（[SystemStatusPolicy.Kind.CELLULAR]）。
     *
     * - `level >= 0`：`level` 根亮条，其余是**弱色空条**；
     * - `level == -1`（读不到）：四根全是弱色（不假装有信号）。
     */
    private fun drawCellular(canvas: Canvas, x: Float, y: Float, level: Int) {
        val known = level >= 0
        // 第222轮：同 [drawBluetooth]。
        val on = Paint(iconFill).apply { color = Skin.overlayTop.text }
        val off = Paint(iconFill).apply { color = Skin.overlayTop.faint }
        for (i in 0..3) {
            val h = 6f + i * 5f
            canvas.drawRoundRect(
                RectF(x + i * 6.5f, y + 22f - h, x + i * 6.5f + 4f, y + 22f),
                2f, 2f,
                if (known && i < level) on else off,
            )
        }
    }

    /**
     * 画一个状态槽（第138轮）。
     *
     * 槽的种类 / 等级 / 是否强调都由 [SystemStatusPolicy] 决定，
     * 这里只做「怎么画」。
     */
    private fun drawStatusSlot(
        canvas: Canvas,
        slot: com.cardesk.launcher.core.SystemStatusPolicy.Slot,
    ) {
        when (slot.kind) {
            com.cardesk.launcher.core.SystemStatusPolicy.Kind.CELLULAR ->
                drawCellular(canvas, 0f, 0f, slot.level)
            com.cardesk.launcher.core.SystemStatusPolicy.Kind.WIFI ->
                drawWifi(canvas, 0f, 0f, slot.level)
            com.cardesk.launcher.core.SystemStatusPolicy.Kind.HOTSPOT ->
                drawHotspot(canvas, 0f, 0f, slot.emphasized)
            com.cardesk.launcher.core.SystemStatusPolicy.Kind.BLUETOOTH ->
                drawBluetooth(canvas, 0f, 0f, slot.emphasized)
        }
    }

    /**
     * 齿轮（设置图标）的路径。
     *
     * ## 为什么用 Canvas 现画，不用 drawable 资源
     *
     * 状态栏这一行的其它图标（蓝牙 / WiFi / 信号 / 燃油 / 时钟）**全是 Canvas 画的**，
     * 齿轮再单独引一套矢量资源，风格和缩放口径就会多一个例外。
     *
     * ## 画法
     *
     * 8 个梯形齿 + 一圈齿轮身 + 中心孔。
     * 中心孔用 [Path.FillType.EVEN_ODD] 挖空 —— 不依赖底色，
     * 换任何背景都还是透明的。
     *
     * @param cx 中心 x
     * @param cy 中心 y
     * @param rOuter 齿尖半径
     * @param rBody 齿根半径（齿轮身的外沿）
     * @param rHole 中心孔半径
     * @param teeth 齿数
     */
    private fun gearPath(
        cx: Float,
        cy: Float,
        rOuter: Float = AVATAR_GEAR_R,
        rBody: Float = AVATAR_GEAR_BODY_R,
        rHole: Float = AVATAR_GEAR_HOLE_R,
        teeth: Int = 8,
    ): Path {
        val p = Path()
        val step = 360f / teeth
        // 齿在外缘占一半角度（另一半是齿间的缺口）
        val half = step / 4f
        fun polar(r: Float, deg: Float): FloatArray {
            val rad = Math.toRadians(deg.toDouble())
            return floatArrayOf(
                cx + r * Math.cos(rad).toFloat(),
                cy + r * Math.sin(rad).toFloat(),
            )
        }
        for (i in 0 until teeth) {
            val base = i * step
            val a = polar(rOuter, base - half)
            val b = polar(rOuter, base + half)
            val c = polar(rBody, base + half)
            val d = polar(rBody, base + step - half)
            if (i == 0) p.moveTo(a[0], a[1]) else p.lineTo(a[0], a[1])
            p.lineTo(b[0], b[1])
            p.lineTo(c[0], c[1])
            p.lineTo(d[0], d[1])
        }
        p.close()
        // 中心孔：EVEN_ODD 把它挖成透明
        p.addCircle(cx, cy, rHole, Path.Direction.CW)
        p.fillType = Path.FillType.EVEN_ODD
        return p
    }

    private fun drawFuelIcon(canvas: Canvas, x: Float, y: Float) {
        val c = iconPaint
        canvas.drawRoundRect(RectF(x + 2f, y + 2f, x + 15f, y + 22f), 2f, 2f, c)
        canvas.drawLine(x + 15f, y + 7f, x + 21f, y + 10f, c)
        canvas.drawLine(x + 21f, y + 10f, x + 21f, y + 19f, c)
        canvas.drawLine(x + 5f, y + 7f, x + 12f, y + 7f, c)
    }

    private var downHit = false

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // 只有命中头像/胶囊才消费事件，其余位置放行给下层页面
                downHit = avatarRect.contains(event.x, event.y) || assistRect.contains(event.x, event.y)
                // 按下哪一块就缩哪一块（命中判定与点击完全一致，不改行为）。
                // 没命中就**不启动**动画 —— 这一层本来就不消费事件。
                if (downHit) {
                    startPress(
                        if (avatarRect.contains(event.x, event.y)) "avatar" else "assist",
                    )
                }
                return downHit
            }
            MotionEvent.ACTION_UP -> {
                if (downHit) {
                    if (avatarRect.contains(event.x, event.y)) {
                        // 排障场景：「点头像没反应」。
                        Logx.event(LogTags.DOCK, "状态栏头像点击")
                        onAvatar?.invoke()
                    }
                    if (assistRect.contains(event.x, event.y)) {
                        Logx.event(LogTags.DOCK, "状态栏驾驶模式胶囊点击", "assist" to assistText)
                        onAssist?.invoke()
                    }
                }
                downHit = false
                endPress()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                downHit = false
                endPress()
                return false
            }
        }
        return downHit
    }

    // --------------------------------------------------------------- 按压反馈

    /**
     * 按下时把该区域缩放到 [MotionSpec.PRESS_SCALE]，抬起回 1.0。
     *
     * 只缩**被按的那一块**（围绕它自己的中心）——
     * 状态栏其余内容不跟着动，才看得出按的是齿轮还是驾驶模式。
     */
    private fun withPress(canvas: Canvas, region: String, cx: Float, cy: Float, block: () -> Unit) {
        val s = if (pressedRegion == region) pressScale else 1f
        canvas.save()
        if (s != 1f) canvas.scale(s, s, cx, cy)
        block()
        canvas.restore()
    }

    private fun startPress(region: String?) {
        pressedRegion = region
        pressAnimator?.cancel()
        pressAnimator = ValueAnimator.ofFloat(pressScale, MotionSpec.PRESS_SCALE).apply {
            duration = MotionSpec.PRESS_DOWN_DURATION
            // 按下用标准减速：起步快、尾巴长 —— 手指还没抬起来就看到缩放了。
            interpolator = Motion.STANDARD
            addUpdateListener { pressScale = it.animatedValue as Float; invalidate() }
        }.also { it.start() }
    }

    private fun endPress() {
        pressedRegion = null
        pressAnimator?.cancel()
        pressAnimator = ValueAnimator.ofFloat(pressScale, 1f).apply {
            duration = MotionSpec.PRESS_UP_DURATION
            // 松手带回弹：略微冲过 1 再回来，按键才「活」。
            interpolator = Motion.REBOUND
            addUpdateListener { pressScale = it.animatedValue as Float; invalidate() }
        }.also { it.start() }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(Design.W.toInt(), Design.H.toInt())
    }

    companion object {
        // ================================================================
        // 第五十二轮：状态栏整行元素**统一缩到 2/3**
        //
        // 第四十三轮缩小过一档 → 第四十四轮恢复成原值
        // 第五十二轮用户再要求「缩小到目前的 2/3」。
        //
        // 缩放口径：**每一项 × 2/3 后取整**，行内相对关系保持不变。
        //   左右边距 44 → 29
        //   顶边距 26 → 17，行高 64 → 43（行底 60）
        //   头像 54 → 36，圆角 18 → 12，字号 26 → 17
        //   胶囊 高 44 → 29、间距 18 → 12、状态点 12 → 8、
        //              内边距 22/12/22 → 15/8/15、文案 20 → 13
        //   连接图标 26 → 17（相邻间距 22 → 15）
        //   燃油图标 24 → 16、燃油百分比 20 → 13、间距 6 → 4
        //   时钟 38 → 25、与燃油百分比间距 18 → 12
        //   位置/天气胶囊 高 44 → 29、内边距 16 → 11、字号 20/26 → 13/17
        //
        // 内容基线随之由 **110 → 73**（= 110 × 2/3），
        // 页面内容整体上移，不留空白带。
        //
        // ----------------------------------------------------------------
        // 第六十三轮：**行内元素放大一档**（字 + 图标），**行本身大小不动**。
        //
        // 用户反馈：「状态栏的字和图标放大一点，状态栏上下都有一段空间」，
        // 要求「不要调整状态栏的整体大小」。
        //
        // 口径 = **行（band）一个字都不动**：[ROW_TOP] / [ROW_H] / [EDGE]
        // 全部保持第五十二轮的值；只把行内的字与图标放大。
        // 行内元素都是**垂直居中**的，字号图标一大，
        // 元素上下留白自然就收窄了 —— 这正是用户要的效果。
        //
        // 放大幅度取「2/3」与「原值」之间（约为原值的 0.8~0.85）：
        //   头像 36 → 40，圆角 12 → 13，字号 17 → 21
        //   胶囊 高 29 → 34、圆角 15 → 17、状态点 8 → 10、
        //            内边距 15/8/15 → 16/9/16、文案 13 → 17
        //   连接图标 17 → 22（相邻间距 17 → 18）
        //   燃油图标 16 → 21、燃油百分比 13 → 17、间距 13 → 14
        //   时钟 25 → 32、与燃油百分比间距 17 → 18
        //   位置/天气胶囊 高 29 → 34、内边距 15 → 16、字号 13/17 → 17/22
        //   定位针 描边形状整体 ×1.3
        // ================================================================
        //
        // 第三百零二轮：**行内文字 / 图标 / 胶囊整体放大 1.3~1.5 倍**
        // （用户反馈「留白多，但图标和文字较小」）。
        //
        // 口径（每项 = 第三百轮值 × 放大倍数后取整）：
        //   行带 ROW_TOP 5 → 8、ROW_H 33 → 39（行中心 21.5 → 27.5，
        //     容纳放大后的 46 高元素；行底 47，内容区仍从 73 起）
        //   设置方块 34 → 46（×1.35），圆角 12 → 16，
        //     齿轮外径半径 8.6 → 11.6 / 齿根 6.2 → 8.4 / 中心孔 2.6 → 3.5
        //   驾驶胶囊 高 34 → 46、圆角 17 → 23、状态点 8 → 11、
        //     内边距 14/8/14 → 18/11/18、文案 14 → 19（×1.36）
        //   连接图标 18 → 25（×1.39），相邻间距 14 → 16
        //   燃油图标 18 → 25、燃油百分比 14 → 19、间距 12 → 13
        //   时钟 17 → 24（×1.41，这一行最重的元素放最多）、与油量间距 16 → 18
        //   城市名 / 温度 14 → 19，定位针占位 14 → 20、PIN_SCALE 1.0 → 1.45
        //   天气图标 16 → 22（×1.375）、描边 1.7 → 2.3、与温度间距 4 → 6
        //   城市名最大宽 260 → 320（字号大了，上限同步放宽）
        //   间距类（ICON_GAP / CLOCK_GAP 等）只微调不翻倍 —— 收窄留白是本轮目的
        // ================================================================

        /** 左边距（设置方块左边 = 29；画板实测 28，差 1px）。 */
        const val EDGE = 29f

        /**
         * 右边距（第三百轮）。
         *
         * 画板这一行是**左右不对称**的：左边距 28，右边距约 41
         * （时间「17:10」的右边缘实测在 1877，屏宽 1920）。
         * 共用 [EDGE] 会让整行右侧多出 12px 空隙。
         */
        const val EDGE_RIGHT = 41f

        /**
         * 这一行元素整体的顶边距。
         *
         * 第三百轮：17 → **5**（设计稿实测元素 y5..38）。
         * 第三百零二轮：5 → **8** —— 行内元素放大到 46 高之后，
         * 行中心随 [ROW_H] 一起下移，元素上沿才不顶出屏幕（最高元素 y4.5 起）。
         */
        const val ROW_TOP = 8f

        /**
         * 这一行的高度（元素的垂直中心线 = ROW_TOP + ROW_H / 2）。
         *
         * 第三百轮：43 → **33**（设计稿元素高 34、行中心 21.5 ⇒ 5 + 33/2 = 21.5）。
         * 第三百零二轮：33 → **39**（行中心 27.5 —— 放大后的 46 高元素
         * 落在 y4.5..50.5，下方内容区仍从 73 开始，互不挤压）。
         */
        const val ROW_H = 39f

        /**
         * 设置入口方块边长。
         *
         * 第三百轮：40 → **34**（设计稿 x28..61 / y5..38 = 34×34）。
         * 第三百零二轮：34 → **46**（×1.35，随行内元素一起放大）。
         * 名字仍叫 AVATAR，是为了不改 `avatarRect` / 命中区 / 回调那套既有命名。
         */
        const val AVATAR = 46f

        /** 设置入口方块圆角（设计稿 ≈12；第三百零二轮随方块 ×1.35 → 16）。 */
        const val AVATAR_RADIUS = 16f

        /**
         * 齿轮图标的**齿尖半径**（设计 px）。
         *
         * 第123轮：这里原来是一个字母「C」的字号（21px），
         * 现在改成设置图标 —— 齿轮外径 23px（= 2×11.5），
         * 跟原来字母的视觉大小（21px 粗体）差不多，不改变这一格的视觉分量。
         * 第三百轮方块缩到 34 ⇒ 齿轮外径同步收到 17（半径 8.6）。
         * 第三百零二轮：随方块 ×1.35 → 外径 23.2（半径 11.6）。
         */
        const val AVATAR_GEAR_R = 11.6f

        /** 齿轮的**齿根半径**（齿轮身的外沿；第三百零二轮 6.2 → 8.4）。 */
        const val AVATAR_GEAR_BODY_R = 8.4f

        /** 齿轮的**中心孔半径**（第三百零二轮 2.6 → 3.5）。 */
        const val AVATAR_GEAR_HOLE_R = 3.5f

        /** 设置方块与驾驶胶囊之间的距离（第五十一轮按设计板 18 改；布局是 22）。 */
        const val CHIP_GAP = 14f

        /** 驾驶胶囊高度（设计稿实测 34；第三百零二轮随行内元素 ×1.35 → 46）。 */
        const val CHIP_H = 46f

        /** 驾驶胶囊圆角（= 高的一半 → 胶囊形）。 */
        const val CHIP_RADIUS = 23f

        /** 胶囊里状态点直径（第三百轮按设计稿收到 8；第三百零二轮 ×1.35 → 11）。 */
        const val CHIP_DOT = 11f

        /** 胶囊左内边距（第三百零二轮 14 → 18）。 */
        const val CHIP_PAD_L = 18f

        /** 胶囊里状态点与文案之间的距离（第三百零二轮 8 → 11）。 */
        const val CHIP_PAD_M = 11f

        /** 胶囊右内边距（第三百零二轮 14 → 18）。 */
        const val CHIP_PAD_R = 18f

        /** 胶囊文案字号（第三百轮：17 → 14；第三百零二轮 ×1.36 → 19）。 */
        const val CHIP_TEXT_SIZE = 19f

        /** 连接图标的边长（蓝牙 / 热点 / WiFi / 信号）。第三百轮：22 → 18；第三百零二轮 ×1.39 → 25。 */
        const val ICON = 25f

        /**
         * 图标内部路径的基准尺寸（缩放用）。
         *
         * 五个图标的路径都按 **26×26** 的坐标系硬编码，这里只改显示尺寸（[ICON]），
         * 由 [drawIcon] 统一 `scale(size / ICON_BASE)` 缩放 ——
         * 基准值本身不能跟着缩，否则缩放会被抵消（图标反而变大）。
         */
        const val ICON_BASE = 26f

        /** 连接图标之间的距离。第三百轮：18 → 14；第三百零二轮微调 → 16。 */
        const val ICON_GAP = 16f

        /** 油量图标的边长（24×24 视觉框）。第三百轮：21 → 18；第三百零二轮 ×1.39 → 25。 */
        const val FUEL_ICON = 25f

        /** 油量图标与百分比之间的距离。第三百轮：14 → 12；第三百零二轮微调 → 13。 */
        const val FUEL_GAP = 13f

        /** 油量百分比与时钟之间的距离。第三百轮：18 → 16；第三百零二轮微调 → 18。 */
        const val CLOCK_GAP = 18f

        /**
         * 时钟字号（第三百轮新增：设计稿里 17:10 是这一行最重的一块，17px 粗体）。
         * 第三百零二轮 ×1.41 → 24 —— 放大倍数里给得最足的一项。
         */
        const val CLOCK_TEXT_SIZE = 24f

        /** 油量百分比字号（第三百轮新增，14 = 与其它文字同一档；第三百零二轮 → 19）。 */
        const val FUEL_TEXT_SIZE = 19f

        /**
         * 顶部渐变遮罩的高度。
         *
         * 第四十九轮「浮屿」起**不再画遮罩**（[onDraw] 里已删掉），
         * 常量保留是为了不动外部引用（若有）。
         */
        const val SCRIM_H = ROW_TOP + ROW_H

        /** 渐变遮罩顶部颜色（半透明深色）。 */
        const val SCRIM_TOP_COLOR = 0x7A000000.toInt()

        /** 渐变遮罩底部颜色（全透明）。 */
        const val SCRIM_BOTTOM_COLOR = 0x00000000.toInt()

        /** `H:mm`，单小时不补零。 */
        private val CLOCK = SimpleDateFormat("H:mm", Locale.CHINA)

        /** 没有油量数据时显示 `--%`。 */
        const val FUEL_UNKNOWN = "--%"

        /** [fuelPercent] 的「无数据」哨兵值。 */
        const val FUEL_UNKNOWN_VALUE = -1

        // -------------------------------------------------- 位置 + 天气（第四轮新增）

        /** 还没定位到时的占位文案（与场景页天气卡的「天气获取中」同一口径）。 */
        const val LOCATION_PENDING = "位置获取中"

        /** 没有天气数据时的温度占位（场景页用的是 `--`，这里带一度符号）。 */
        const val TEMP_UNKNOWN = "--°"

        /** [weatherTemp] 的「无数据」哨兵值（只用于日志）。 */
        const val TEMP_UNKNOWN_VALUE = -1

        /** 城市名字号（第三百轮：17 → 14；第三百零二轮 ×1.36 → 19）。 */
        const val LOCATION_TEXT_SIZE = 19f

        /** 温度字号（第三百轮：22 → 14；裸元素后和城市名同一档；第三百零二轮 → 19）。 */
        const val TEMP_TEXT_SIZE = 19f

        // -------------------------------------------------- 翻页点（第三百轮新增）

        /**
         * 翻页点：accent 长条宽 / 高、与弱色圆点之间的间隙、圆点直径。
         *
         * 全部来自设计稿实测：长条 x874..895 / y19..24（22×6），圆点直径 6，
         * 组中心 x≈888（**不在屏幕正中** —— 画板就是这样，照抄）。
         * 第三百零二轮随整行放大 ×1.35：长条 30×8、圆点 8、间隙 10
         * （仅「没有 PageDotsView 宿主」时的兜底绘制用）。
         */
        const val PAGE_DOT_BAR_W = 30f
        const val PAGE_DOT_BAR_H = 8f
        const val PAGE_DOT_GAP = 10f
        const val PAGE_DOT_SIZE = 8f
        const val PAGE_DOT_CENTER_X = 888f

        /** 定位针占位宽。第三百轮：18 → 14（裸元素里不需要胶囊内边距）；第三百零二轮 → 20。 */
        const val PIN_W = 20f

        /** 定位针与城市名之间的间距。第三百轮：8 → 6；第三百零二轮微调 → 7。 */
        const val PIN_GAP = 7f

        /**
         * 定位针的放大倍数（第六十三轮）。
         *
         * 定位针的路径是按固定坐标画的水滴（高 13.4），字与图标放大后
         * 它显得偏小 —— 这里整体乘一个倍数，描边宽度随之一起放大。
         * 第三百轮字缩到 14，倍数回到 1.0（不再放大）。
         * 第三百零二轮字放大到 19 ⇒ 倍数 1.45（水滴高 13.4 → 19.4，与文字同档）。
         */
        const val PIN_SCALE = 1.45f

        /** 城市名与分隔点之间的间距（当前未使用，保留）。 */
        const val SEP_W = 17f

        /**
         * 位置文案的**最大宽度**。
         *
         * 位置 + 天气是**从右往左**排的，城市名越长，这一段的左边界越靠左 ——
         * 而状态栏正中摆着翻页指示圆点，地名一长就会顶上去。
         * 所以给一个宽度上限，超了就把地名截断加省略号。
         *
         * 第三百轮：300 → 260。字号从 17 收到 14、又去掉了两侧胶囊内边距，
         * 260 已经够 18 个中文字（正常城市名远够不着）。
         * 第三百零二轮：字号放大到 19 ⇒ 上限同步放宽到 320（仍够 16 个中文字）。
         */
        const val LOC_MAX_W = 320f

        /** 分隔点半径。 */
        const val SEP_DOT_R = 1.1f

        /** 天气图标占位宽（第三百轮：21 → 16；第三百零二轮 ×1.375 → 22）。 */
        const val WX_ICON_W = 22f

        /** 天气图标与温度之间的间距（第三百轮：8 → 4；第三百零二轮微调 → 6）。 */
        const val WX_ICON_GAP = 6f

        /** 天气图标的显示边长（第三百轮：21 → 16；第三百零二轮 ×1.375 → 22）。 */
        const val WX_ICON_SIZE = 22f

        /** 天气图标缩放比（[WeatherGlyphs] 的图形按 24×24 视觉框硬编码）。 */
        const val WX_ICON_SCALE = WX_ICON_SIZE / 24f

        /** 天气图标描边（缩放后的实际宽度，设计 px；第三百零二轮 1.7 → 2.3，随图标等比）。 */
        const val WX_ICON_STROKE = 2.3f

        /**
         * 天气组与位置组之间的空档（第三百轮）。
         *
         * 设计稿实测：位置（定位针 + 「尤奇亚」）在 x≈1280..1400，
         * 天气（☀ 4°）从 x≈1420 起 —— 中间约 20。取 16，
         * 给「城市名英文变长」留一点缓冲。
         * 第三百零二轮随整行微调 → 18。
         */
        const val WEATHER_LOCATION_GAP = 18f
    }
}
