package com.cardesk.launcher.ui.pages

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.view.Gravity
import android.view.MotionEvent
import android.view.TouchDelegate
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.cardesk.launcher.core.Palettes
import com.cardesk.launcher.core.Palette
import com.cardesk.launcher.core.Prefs
import com.cardesk.launcher.core.Skin
import com.cardesk.launcher.core.log.LogTags
import com.cardesk.launcher.core.log.Logx
import com.cardesk.launcher.core.media.LyricWindow
import com.cardesk.launcher.ui.Ui
import com.cardesk.launcher.ui.pip.NavSplitPlacement
import com.cardesk.launcher.ui.pip.PipDividerView
import com.cardesk.launcher.ui.pip.PipRatio
import com.cardesk.launcher.ui.pip.PipRatioStore
import com.cardesk.launcher.ui.pip.PipSides
import com.cardesk.launcher.ui.pip.PipSurface

/**
 * 导航页。
 *
 * 结构（+）
 *
 * `
 * FrameLayout desktopNavPage 1920×1080
 *  ├── LinearLayout desktopNavRow（横向）
 *  │ ├── CardView mapCardContainer weight = primaryPipRatio（默认 0.71030045）
 *  │ │ └── card_map：pipContainer（画中画）+ pipDotGroup（3 个 6px 圆点）
 *  │ ├── View divider 1px，上下各留 10px
 *  │ └── CardView combinedCardContainer weight = 1 - primaryPipRatio
 *  │ └── rightSlot
 *  │ ├── card_combined 单画：tpmsCard「胎压 · 车况」+ musicCard
 *  │ │ （四轮胎压值 / 俯视车身图 / 来源行 / 封面+歌名 / 三行歌词 / 进度 / 控制键）
 *  │ └── card_map2 → 副画 双画：第二路画中画（MainActivity:1453 的第二个）
 *  ├── View navSplit 36×110 玻璃按钮，start|center，chevron 图标
 *  │        （第129轮：**挂在画中画承载面 mapCard 里**，叠在画面左边缘，
 *  │          不再独占导航页左侧那条 44px 留白）
 *  └── 画中画分隔条 pipDivider 12×144 玻璃 chip
 *        （单击 = 主画全屏；长按 = 左右拖动调整分屏比例）
 * `
 *
 * 画中画本体挂在**导航页**的地图卡里（找
 *  把 PIP 挂到 `mapCard`，场景页没有画中画。
 *
 * 分屏比例语义一致：**主画（左侧地图卡）在导航行里的权重**
 *
 * 默认值 / 持久化键名见 `PipRatio`。
 */
class NavPage(context: Context, private val pal: Palette) : FrameLayout(context) {

    /**
     * 偏好存储。
     *
     * 声明在**属性区最前面**：下面 [coverBackdropEnabled] 的初始值要读它，
     * 而 Kotlin 的属性初始化按声明顺序走 —— 声明晚了 setter 里就还拿不到。
     */
    private val prefs = Prefs(context)

    /**
     * 组合卡（音乐卡）的配色 —— **日夜各一套**，但两套各自的规矩不一样。
     *
     * ## 夜间（深底浅字，三档文字）
     *
     * 最弱那档是 `#757E8E`（L=0.2065）→ 背景亮度**上限** `L ≤ 0.0341`
     * → 封面被**压暗**，色彩和构图都清晰可见。
     *
     * ## 日间（白底深字，**只有两档**文字）
     *
     * 最弱那档是 `#565D6B`（L=0.1087）→ 背景亮度**下限** `L ≥ 0.4340`
     * → 封面被罩成一层**很淡的灰调影子**：认得出是那张封面，但卡片整体还是亮的，
     * 和日间其它卡**风格统一**。
     *
     * ## 为什么日间要砍到两档（第153轮）
     *
     * 原来的日间路线是三档（最弱 `#8B92A0`，L=0.2859）
     * → 下限 **0.9743** → 整张封面被冲成一片均匀的白
     * → 「开封面背景」和「关封面背景」画出来**像素级几乎一样**
     * → 用户点封面图只看到一句提示、看不到任何变化（真机实测，见 REPORT-y153）。
     *
     * 砍掉最弱那档、把它并进 `textDim` 之后，下限从 0.9743 降到 0.4340，
     * 白罩可以淡很多 —— 这就是「风格统一」和「封面看得见」能同时成立的分水岭。
     *
     * ## 夜间为什么一行不改
     *
     * 夜间本来就是深底浅字，上面那套（三档 + 上限 0.0341）**原样保留**
     * → 夜间零退化（真机逐像素比对，见 REPORT-y153）。
     */
    private val cardPal: Palette = if (pal.night) Palettes.night else Palettes.day

    /**
     * 卡面上**最弱那档文字**的颜色 —— 背景亮度区间就是由它反推的。
     *
     * - 夜间：三档，最弱是 `textFaint`（`#757E8E`）；
     * - 日间：两档，最弱是 `textDim`（`#565D6B`）——
     *   日间**不用** `textFaint`（`#8B92A0`），那档会把下限顶回 0.9743。
     */
    private val cardWeakText: Int
        get() = if (pal.night) cardPal.textFaint else cardPal.textDim

    /**
     * 卡面上的强调色（当前歌词高亮 / 进度条填充）。
     *
     * - 夜间：`#5AD8C8`（青，明亮底上很跳）；
     * - 日间：**加深的青** `#0A6459` ——
     *   白罩底最暗处 L=0.4340，普通强调色 `#0F9C8E`（L=0.2584）在上面只有 1.57:1，
     *   读不出来；`#0A6459`（L=0.0990）有 3.25:1，两种状态下都够。
     */
    private val cardAccent: Int
        get() = if (pal.night) CARD_ACCENT_NIGHT else CARD_ACCENT_DAY

    /**
     * 组合卡的卡面色。
     *
     * - **RGB**：用户自选的卡片色**够用就原样用**；
     *   夜间要求它够暗（浅色字压得住），日间要求它够亮（深色字读得清）；
     *   不满足就换成该模式的标准氛围底（夜间 `#1F232D` / 日间 `#FFFFFF`）。
     * - **alpha**：仍然跟用户的「卡片不透明度」设置走（[Skin.surface] 的 alpha 段）。
     */
    private fun cardSurface(): Int {
        val alpha = Skin.surface ushr 24
        val rgb = Skin.surface and 0x00FFFFFF
        val l = com.cardesk.launcher.core.media.CoverBackdrop.relativeLuminance(rgb)
        val ok = if (pal.night) {
            // 浅色字压在底上 → 底色亮度的**上限**，由最弱那档文字色反推。
            l <= com.cardesk.launcher.core.media.CoverBackdrop
                .maxBackgroundLuminance(cardWeakText)
        } else {
            // 深色字压在底上 → 底色亮度的**下限**，由最弱那档文字色反推。
            l >= com.cardesk.launcher.core.media.CoverBackdrop
                .minBackgroundLuminance(cardWeakText)
        }
        return (alpha shl 24) or (if (ok) rgb else cardPal.surface and 0x00FFFFFF)
    }

    // ------------------------------------------------------------------ 对外状态

    /** 画中画承载面。 */
    val pip = PipSurface(context, pal)

    /**
     * 组合卡的**背景层**（专辑封面做底）。
     *
     * 它替代了原来的 `Skin.flat(Shape.CARD)`：
     * - 有封面 → 模糊后的封面铺满整张卡 + 一层压暗遮罩（保证歌词 / 歌名读得清）；
     * - 没封面 → 纯色卡面 + 1px 描边，和原来**一模一样**。
     *
     * 遮罩强度不是拍脑袋定的，是按「最弱那档文字与背景的对比度 ≥ 3:1」反推的
     * （见 `CoverBackdrop`）。
     *
     * 声明在**属性区最前面**是为了让 [artwork] 的 setter 能直接喂它 ——
     * Kotlin 的属性初始化按声明顺序走，声明晚了 setter 里就还拿不到。
     */
    private val coverBackdrop = com.cardesk.launcher.ui.widget.CoverBackdropDrawable(
        surfaceColor = cardSurface(),
        strokeColor = cardPal.stroke,
        cornerRadius = com.cardesk.launcher.core.Shape.CARD,
        textColor = cardWeakText,
    )

    /**
     * 音乐来源名（`musicSource`）。
     */
    var sourceLabel: String = "通用音乐"
        set(v) {
            field = v
            if (::sourceText.isInitialized) sourceText.text = v
            // 第138轮：文案还决定自绘符号的**品牌色**，图标要跟着换色。
            if (::sourceIconView.isInitialized) sourceIconView.sourceLabel = v
        }

    /**
     * 音乐来源 **id**（第138轮）。
     *
     * 来源行要显示**对应 App 的图标**（真图标优先，取不到用自绘符号），
     * 而图标种类由 **id** 决定（`MediaSources.iconKind`）——
     * 文案只用来取品牌色。所以 id 和文案要分开给。
     */
    var sourceId: String = com.cardesk.launcher.core.media.MediaSources.GENERIC_ID
        set(v) {
            if (field == v) return
            field = v
            if (::sourceIconView.isInitialized) sourceIconView.sourceId = v
        }

    /**
     * 音乐来源的**真实 App 图标**（第138轮）；null = 取不到 → 画自绘符号。
     *
     * 与首页音乐卡**共用同一份缓存**（`MediaSourceIcons.cached`），
     * 两页不会出现「一个是 App 图标、另一个是小圆点」的同屏不一致。
     */
    var sourceIcon: android.graphics.Bitmap? = null
        set(v) {
            if (field === v) return
            field = v
            if (::sourceIconView.isInitialized) sourceIconView.icon = v
        }

    /** 播放状态文案（缺省「已暂停」）。 */
    var musicState: String = "已暂停"
        set(v) { field = v; if (::musicStateText.isInitialized) musicStateText.text = v }

    /** 歌名。 */
    var trackTitle: String = ""
        set(v) { field = v; if (::titleText.isInitialized) titleText.text = v }

    /** 歌手。 */
    var trackArtist: String = ""
        set(v) { field = v; if (::artistText.isInitialized) artistText.text = v }

    /**
     * 专辑图（96×96 / 圆角 26 / centerCrop）。
     *
     * 同一张封面同时喂给**两处**：
     * - [albumArtView]：卡片左上角那块 96×96 的小封面；
     * - [coverBackdrop]：整张卡的**背景**（模糊 + 遮罩）。
     */
    var artwork: android.graphics.Bitmap? = null
        set(v) {
            field = v
            if (::albumArtView.isInitialized) albumArtView.bitmap = v
            // 背景层是封面做底的唯一入口 —— 换歌时它负责重算模糊图与遮罩强度。
            coverBackdrop.bitmap = v
        }

    /**
     * 「歌曲图片当卡片背景」开关（**第148轮**）。
     *
     * - true：整张卡的卡面是模糊后的封面（第146轮上线的效果）；
     * - false：卡面退回纯色卡面 + 描边（等于第146轮之前的 `Skin.flat`）。
     *
     * ## 切换入口
     *
     * 用户**直接点这张 96×96 的封面图**就能切（见 [toggleCoverBackdrop]），
     * 设置页「桌面卡片 → 音乐卡封面背景」那个开关操作的是**同一份状态**。
     *
     * 关掉时封面图**本身不动**（还是原来那张封面）——
     * 变的是**整张卡**：卡面从「模糊封面」退回「纯色卡面」。
     *
     * 状态落在 `Prefs.navCoverBackdropEnabled`，默认 **true**（保持第146轮现状）。
     */
    var coverBackdropEnabled: Boolean = prefs.navCoverBackdropEnabled
        set(v) {
            // 同一个值不重复刷 —— 桌面每轮刷新都会把偏好重新灌进来，
            // 不做这个判断的话每帧都会走一遍 sync（白重建背景层）。
            if (field == v) return
            field = v
            syncCoverBackdropState()
        }

    /**
     * 把 [coverBackdropEnabled] 同步到背景层 + 封面图上的标记。
     *
     * ## 为什么需要这个方法（Kotlin 的一个坑）
     *
     * 属性初始化**不走 setter** —— `var x = 初值` 是编译器直接把初值写进 field，
     * setter 一次都不调。所以：
     *
     * ```
     * var coverBackdropEnabled = prefs.navCoverBackdropEnabled   // field 正确
     * coverBackdrop.coverEnabled                                 // ← 还是构造时的 true！
     * ```
     *
     * 用户上次把背景关掉了，这次冷启动 `prefs` 里是 false，
     * 但背景层还停在 true —— **背景照样会显示**，开关等于没记住。
     *
     * 所以 build() 里必须显式同步一次（见 `build()` 末尾），
     * 之后每次改状态都走 setter → 也统一走这个方法。
     */
    private fun syncCoverBackdropState() {
        coverBackdrop.coverEnabled = coverBackdropEnabled
    }

    /**
     * 背景层当前**实际上**是不是在用封面（供单测 / 排障读取）。
     *
     * ## 为什么不能直接读 [coverBackdropEnabled]
     *
     * `coverBackdropEnabled` 是**属性**（field），它的初值读自 `prefs`，永远是对的；
     * 真正可能错的是**背景层**（`coverBackdrop.coverEnabled`）——
     * 属性初始化不走 setter，所以少了 `build()` 末尾那次同步，
     * 背景层会停在构造时的 `true`，而属性显示 `false`。
     *
     * **两边不一致 = bug**。这条只读属性把背景层的真实状态暴露出来，
     * 让「冷启动记住了吗」这条断言查得到真东西。
     */
    val coverBackdropOn: Boolean get() = coverBackdrop.coverEnabled

    /**
     * 组合卡当前**实际用的卡面色**（含 alpha 段）。
     *
     * 第153轮：夜间是深色氛围底（日间默认的纯白压不住浅色字 → 换成 `#1F232D`），
     * 所以这个值**不一定等于** `Skin.surface`（那是全局卡片色）。
     * 供单测 / 排障读取。
     */
    val cardSurfaceColor: Int get() = coverBackdrop.surfaceColorNow

    /**
     * 组合卡当前**实际用的文字色**（歌名那一档）。
     *
     * 第153轮：夜间是浅色字，日间是深色字（白底深字）—— 跟着主题走。
     * 供单测 / 排障读取。
     */
    val cardTextColor: Int
        get() = if (::titleText.isInitialized) titleText.currentTextColor else 0

    /**
     * 组合卡当前**实际用的次要文字色**（歌手那一档）。
     *
     * 第153轮：夜间浅色、日间深色 —— 跟着主题走。
     * 供单测 / 排障读取。
     */
    val cardArtistColor: Int
        get() = if (::artistText.isInitialized) artistText.currentTextColor else 0

    /**
     * 组合卡当前**实际用的强调色**（当前歌词高亮 / 进度条填充）。
     *
     * 取的是**歌词控件真实在用的那个色**，不是常量 ——
     * 常量本身对了、但接线接错的话，查常量是查不出来的
     * （反向验证第 3 项踩过这个坑：第一版查的是常量，改坏接线没红）。
     *
     * 供单测 / 排障读取。
     */
    val cardAccentColor: Int
        get() = currentWipeView()?.activeColor ?: 0

    /**
     * 背景层本体（供单测 / 排障读取：亮度允许区间、模糊图尺寸）。
     *
     * 第153轮日间模式的修复就是靠它验证的 ——
     * 夜间区间是「上限」（封面被压暗），日间是「下限」且下限只有 0.4340
     * （封面被罩成淡灰影子，而不是像旧路线那样冲成纯白）。
     */
    val coverBackdropLayer: com.cardesk.launcher.ui.widget.CoverBackdropDrawable
        get() = coverBackdrop

    /**
     * 车型编号（= `tpms_car_model`）。
     *
     * 第三十二轮起**第二页组合卡不再有胎压卡**（用户要求：只留播放器、占满那半屏），
     * 原来跟着胎压卡一起画的车模（`NavPage.CarTopView`）也一并删掉了。
     * 胎压与车况仍在**第一页底部的胎压卡**里（`ScenePage.drawTireCard`），
     * **第三十三轮起「车模编号」设置接到第一页胎压卡的自绘图模上**
     * （`ScenePage.drawCarTop` → `CarTopGeometryFactory`）。
     */
    var carModel: String = com.cardesk.launcher.core.CarModels.MODEL_03

    /**
     * 歌词窗口（上 [lyricPast] 句 + 当前句 + 下 [lyricFuture] 句）。
     *
     *  expandedLyricWindow
     * 第三十三轮按用户要求固定 5 句（{2, 2}）。
     *
     * [lyricPrev] / [lyricCurrent] / [lyricNext] 仍保留：**它们是窗口里离当前句最近的 3 句**，
     * 供旧调用方（预览渲染、场景页同步）继续使用。
     */
    var lyricPast: Int = 1
        private set

    var lyricFuture: Int = 1
        private set

    /** 上一句歌词（窗口内当前句的上一行）。 */
    var lyricPrev: String = ""
        set(v) { field = v; refreshLyricWindow() }

    /** 当前句歌词。 */
    var lyricCurrent: String = ""
        set(v) { field = v; refreshLyricWindow() }

    /**
     * 当前句的**擦除比例** 0..1（已唱到的部分）。
     *
     * 当前行「已唱部分强调色 + 未唱部分暗色」
     * 比例随播放位置往前推。
     */
    var lyricWipe: Float = 0f
        set(v) {
            field = v.coerceIn(0f, 1f)
            currentWipeView()?.wipeTarget = field
        }

    /** 5 句窗口里「当前句」那一行的下标（[LyricWindow.slots] 里 slot == 0 的位置）。 */
    private fun currentWipeView(): com.cardesk.launcher.ui.widget.WipeLyricTextView? {
        if (!::lyricTexts.isInitialized) return null
        val idx = LYRIC_SLOTS.indexOf(0)
        if (idx < 0) return null
        return lyricTexts.getOrNull(idx)
    }

    /** 下一句歌词（窗口内当前句的下一行）。 */
    var lyricNext: String = ""

    /**
     * 下两句歌词（当前句往后数第二句）。
     *
     * 第五十轮：导航页组合卡的歌词区按设计板 s02 改成 **当句 / 下1 / 下2** 三行，
     * 所以需要单独一个「下2」的属性（原来只有上 / 当 / 下三句）。
     */
    var lyricNext2: String = ""
        set(v) { field = v; refreshLyricWindow() }

    /**
     * 用整份歌词 + 当前行下标刷新 5 句窗口。
     *
     * @param lines 全部歌词行
     * @param index 当前行下标（< 0 表示没有歌词）
     */
    fun setLyricWindow(lines: List<String>, index: Int) {
        if (index < 0) {
            lyricPrev = ""
            lyricCurrent = ""
            lyricNext = ""
            lyricNext2 = ""
            return
        }
        lyricPrev = LyricWindow.lineAt(lines, index, -1)
        lyricCurrent = LyricWindow.lineAt(lines, index, 0)
        lyricNext = LyricWindow.lineAt(lines, index, 1)
        lyricNext2 = LyricWindow.lineAt(lines, index, 2)
        // 上 2 / 下 2 句
        lyricWindowLines = lines
        lyricWindowIndex = index
        refreshLyricWindow()
    }

    /** 整份歌词（供窗口刷新用）。 */
    private var lyricWindowLines: List<String> = emptyList()

    /** 当前行下标（供窗口刷新用）。 */
    private var lyricWindowIndex: Int = -1

    /**
     * 把窗口里的每一句写进对应的歌词控件。
     *
     * 当前句居中、最亮；上方两句更淡，下方两句次淡
     *
     *
     * 第三十九轮（4385）：五句**统一**用自绘的 `WipeLyricTextView`，
     * 这样每一句都能走**跑马灯**（超宽时整句滚动，而不是被截断）。
     */
    private fun refreshLyricWindow() {
        if (!::lyricTexts.isInitialized) return
        val lines = lyricWindowLines
        val index = lyricWindowIndex
        for (i in LYRIC_SLOTS.indices) {
            val slot = LYRIC_SLOTS[i]
            val tv = lyricTexts.getOrNull(i) ?: continue
            // 最近的三句（上一句 / 当前句 / 下一句）走 [lyricPrev] / [lyricCurrent] /
            // [lyricNext] 三个属性 —— 它们**可以被外部直接赋值**
            // （`LauncherView` 就是这么塞「等待播放」占位的）；
            // 再往外的句子才从整份歌词里取。
            val line = when (slot) {
                0 -> lyricCurrent
                -1 -> lyricPrev
                1 -> lyricNext
                2 -> lyricNext2.ifBlank { if (index < 0) "" else LyricWindow.lineAt(lines, index, 2) }
                else -> if (index < 0) "" else LyricWindow.lineAt(lines, index, slot)
            }
            tv.text = line
            if (slot == 0) {
                // 当前句：擦除高亮按播放位置走。
                tv.wipeTarget = lyricWipe
            }
        }
    }

    /** 播放进度 0..1（max=1000）。 */
    var progress: Float = 0f
        set(v) {
            field = v.coerceIn(0f, 1f)
            post { refreshProgress() }
        }

    /** 已播时长文案。 */
    var positionText: String = "0:00"
        set(v) { field = v; if (::currentTimeText.isInitialized) currentTimeText.text = v }

    /** 总时长文案。 */
    var durationText: String = "0:00"
        set(v) { field = v; if (::totalTimeText.isInitialized) totalTimeText.text = v }

    /** 播放键图标。 */
    var playing: Boolean = false
        set(v) {
            field = v
            if (::playGlyph.isInitialized) playGlyph.text = if (v) "❚❚" else "▶"
        }

    // ------------------------------------------------------------------ 回调

    /**
     * 来源行的锚点视图（来源菜单据此定位）。
     *
     * 第三十二轮起第三页（播放器页）已去掉，来源菜单统一锚在**第二页组合卡**的来源行上。
     */
    val sourceAnchor: View? get() = sourceRowView

    /** 来源菜单展开时把箭头转 180°（rotation 动画）。 */
    fun setSourceMenuOpen(open: Boolean) {
        sourceChevron?.rotation = if (open) 180f else 0f
    }

    var onPlayToggle: (() -> Unit)? = null
    var onPrev: (() -> Unit)? = null
    var onNext: (() -> Unit)? = null
    var onSource: (() -> Unit)? = null

    /**
     * 封面背景开关被切换（**第148轮**）。
     *
     * 参数 = 切换**之后**的状态（true = 现在用封面当背景）。
     *
     * 宿主（`LauncherView`）拿它弹 Toast —— 桌面不是普通 Activity，
     * 用户点完得立刻知道当前是什么状态，不然会以为「点了没反应」。
     */
    var onCoverBackdropToggled: ((Boolean) -> Unit)? = null

    /** 画中画全屏 / 还原（`pipDivider` 单击 = 主画全屏）。 */
    var onPipFullscreen: (() -> Unit)? = null

    /** 左侧 chevron 按钮（`navSplit`）。 */
    var onSplitToggle: (() -> Unit)? = null

    /** 分隔条拖动中的实时比例（0..1）。 */
    var onRatioChanged: ((Float) -> Unit)? = null

    /** 分隔条拖动结束、比例已落盘（0..1）。 */
    var onRatioCommitted: ((Float) -> Unit)? = null

    /**
     * 画中画上方三个点：**单击** → 左右换位并保存。
     *
     *
     * `DesktopLayoutSettings.setSidesSwapped(!isSidesSwapped())` → MMKV sync →。
     */
    var onPipSwapSides: (() -> Unit)? = null

    /**
     * 画中画上方三个点：**长按** → 打开画中画应用选择器。
     *
     *
     */
    var onPipPickApp: (() -> Unit)? = null

    // ------------------------------------------------------------------ 画中画分屏

    /** 比例持久化（；我们用 SharedPreferences）。 */
    private val ratioStore = PipRatioStore(context)

    /**
     * 双画模式（键名 `desk_dual_pip`）。
     *
     * - 单画：左地图卡承载画中画，右边是组合卡（胎压 · 车况 + 音乐）；
     * - 双画：左半承载主画中画，右半承载**副画**（本应用只有一路画中画 → 降级占位）。
     *
     * 初始值直接读 `prefs.dualPip`；主线程改动设置后调 [syncPipState] 即可同步过来。
     */
    var dualPip: Boolean = prefs.dualPip
        set(value) {
            val changed = field != value
            field = value
            // 单画 / 双画各用一个持久化键
            pipRatio = ratioStore.read(value)
            applyDualLayout()
            // 排障场景：「双画切了没生效 / 切回单画比例乱」——模式变化必须落盘。
            if (changed) {
                Logx.event(
                    LogTags.NAV_UI,
                    "画中画模式切换",
                    "mode" to if (value) "dual" else "single",
                    "ratio" to pipRatio,
                )
            }
        }

    /**
     * 画中画分屏比例（0..1）：**主画（左侧地图卡）在导航行里的权重**。
     *
     *
     * 默认值单画 `0.71030045f`、双画 `0.5f`；
     * 持久化键名见 [PipRatio]。
     */
    var pipRatio: Float = ratioStore.read(dualPip)
        set(value) {
            val next = PipRatio.clamp(value, dualPip)
            field = next
            // 排障场景：「分隔条拖不动 / 比例跳变」——拖动过程用 DEBUG，不落盘。
            Logx.d(LogTags.NAV_UI, "画中画分屏比例变化", "ratio" to next, "dual" to dualPip)
            applyRatio()
        }

    /** 画中画全屏态：隐藏分隔条并停用拖动。 */
    var pipFullscreen: Boolean = false
        set(value) {
            val changed = field != value
            field = value
            applyPipChrome()
            // 排障场景：「画中画全屏进不去 / 还原后画面还在最上层」——全屏与还原都要落盘。
            if (changed) {
                Logx.event(
                    LogTags.NAV_UI,
                    if (value) "画中画进入全屏" else "画中画退出全屏",
                    "dual" to dualPip,
                    "ratio" to pipRatio,
                    "pkg" to pip.currentPackage,
                )
            }
        }

    /**
     * 副画承载的应用包名
     * = `pip2_app`。
     *
     * 读不到 `pip2_app` 时退回主画同一个包名（与占位口径一致）。
     */
    var secondaryPackage: String = ratioStore.readSecondaryPackage(prefs.pipApp)
        set(value) {
            field = value
            Logx.d(LogTags.NAV_UI, "副画包名变更", "pkg" to value)
        }

    /**
     * **左右是否互换**（键 `desk_sides_swapped`）。
     *
     * 初始值读持久化；改动走 [toggleSidesSwapped]（换位 → 落盘 → 刷新布局）。
     */
    var sidesSwapped: Boolean = ratioStore.readSidesSwapped()
        private set

    /**
     * 同步画中画相关状态（双画模式 / 全屏态）。
     *
     * 主线程在设置变更、画中画开关变化时调用一次即可；
     * 比例本身由分隔条拖动落盘，不需要外部推。
     */
    fun syncPipState(dual: Boolean, fullscreen: Boolean) {
        dualPip = dual
        pipFullscreen = fullscreen
    }

    /**
     * 把画中画挂回地图卡（全屏还原时用）。
     *
     * 画中画全屏时会被摘到桌面的最上层（全屏铺满），还原时必须**挂回地图卡**，
     * 否则会留在根容器里飘着（主画 / 副画归位）。
     */
    fun restorePipToMapCard() {
        if (pip.parent !== mapCard) {
            (pip.parent as? android.view.ViewGroup)?.removeView(pip)
            mapCard.addView(pip, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            // 排障场景：「还原后画中画不见了 / 飘在根容器上」——归位要留痕。
            Logx.i(LogTags.NAV_UI, "画中画归位到地图卡", "pkg" to pip.currentPackage)
        }
        pip.visibility = View.VISIBLE
    }

    // ------------------------------------------------------------------ 内部

    private lateinit var sourceText: TextView
    /** 来源行的**图标**（第138轮）：真 App 图标优先，取不到用自绘符号。 */
    private lateinit var sourceIconView: com.cardesk.launcher.ui.widget.MediaSourceIconView
    private lateinit var musicStateText: TextView
    private lateinit var titleText: TextView
    private lateinit var artistText: TextView
    private lateinit var albumArtView: com.cardesk.launcher.ui.widget.AlbumArtView
    private lateinit var lyricTexts: ArrayList<com.cardesk.launcher.ui.widget.WipeLyricTextView>
    private lateinit var currentTimeText: TextView
    private lateinit var totalTimeText: TextView
    private lateinit var playGlyph: TextView
    private lateinit var progressFill: View
    private lateinit var progressHost: FrameLayout
    private lateinit var pipDivider: PipDividerView
    private lateinit var navSplit: View

    /**
     * 导航行（地图卡 ↔ 组合卡）。
     *
     * 左右换位就是把这行的布局方向切成 RTL。
     */
    private lateinit var navRow: LinearLayout

    private val mapCard = FrameLayout(context)

    /**
     * **画中画承载面**（地图卡）—— 只读，供排障 / 单测读取。
     *
     * 第129轮起「画中画全屏按钮」（`navSplit`）挂在这个容器里 ——
     * 悬浮叠在画中画画面左边缘（规则见 [NavSplitPlacement]），
     * 不再独占导航页左侧那条 44px 留白。
     */
    val pipCard: FrameLayout get() = mapCard

    /** 来源行（来源菜单据此定位）。 */
    private var sourceRowView: View? = null

    /** 来源行右侧的下拉箭头（来源菜单展开时转 180°）。 */
    private var sourceChevron: TextView? = null

    /** 右槽（`rightSlot`：单画放组合卡，双画放副画）。 */
    private val rightSlot = FrameLayout(context)

    /**
     * 右槽**组合卡**（第四十九轮「浮屿」重排）。
     *
     * 第四十三～四十八轮它是 `LinearLayout(VERTICAL)` + 内嵌一张「音乐卡」；
     * 第四十九轮按设计板 `s02-nav` 改成**整卡扁平容器 + 绝对定位子元素**，
     * 版式与设计板一一对应（见 [buildCombinedCard] 的坐标表）。
     */
    private val combinedCard = FrameLayout(context)

    /**
     * 右槽**组合卡自己**的顶部内边距（设计 px）。
     *
     * 第四十三轮（沉浸式）曾让卡片自己让开状态栏（40px）；
     * 第四十四轮导航页整体改回「紧凑」下移（见 [NAV_CONTENT_TOP_TIGHT]），
     * 卡片回到 `paddingTop = 8`。
     */
    private var cardTopInset: Int = CARD_TOP_INSET

    /**
     * 组合卡当前的顶部内边距（设计 px）—— 只读，供排障/单测读取。
     */
    val currentCardTopInset: Int get() = cardTopInset

    /**
     * **副画**承载面（双画中画的第二路）。
     *
     * `new PipManager(this, binding.mapCard2, KEY_PIP2_APP, false,...)`
     * —— 副画是**第二个独立的 **，与主画各跑一个应用；
     * 设置页的说明是「第二页右侧胎压和音乐卡片替换为第二画中画」
     *
     */
    val pipSecondary = PipSurface(context, pal)

    init {
        setBackgroundColor(0x00000000)
        pipSecondary.role = "副画"
        buildCombinedCard()
        buildRow()
        buildPipDivider()
        buildNavSplit()
        applyRatio()
        applyDualLayout()
        applyPipChrome()
        applyPipContentInset(false)
        // 初始的左右互换状态（`MainActivity.applyDesktopSideOrder` 在布局前先摆一次）
        applySidesSwapped(sidesSwapped)
    }

    /**
     * 画中画内容区的上下留白（设计坐标 px）。
     *
     * **与组合卡完全对齐**（根布局
     * `paddingTop="8.0px" paddingBottom="126.0px"`——
     * 组合卡底部那 126px 就是给浮在导航页之上的 Dock 栏（铺满屏宽×104 + 距底 8）让位的。
     *
     * 画中画那张卡**没有**对应的留白
     * 于是承载应用的画面底部被 Dock 压住 —— 我们补上，
     * 两栏顶部/底部对齐，画面既不被遮挡也不铺满。
     */
    fun applyPipContentInset(fullscreen: Boolean) {
        val top = if (fullscreen) 0 else PIP_CONTENT_INSET_TOP
        val bottom = if (fullscreen) 0 else PIP_CONTENT_INSET_BOTTOM
        pip.contentInsetTop = top
        pip.contentInsetBottom = bottom
        pipSecondary.contentInsetTop = top
        pipSecondary.contentInsetBottom = bottom
        Logx.i(
            LogTags.NAV_UI,
            "画中画内容留白",
            "top" to top,
            "bottom" to bottom,
            "fullscreen" to fullscreen,
        )
    }

    /**
     * 画中画上方三个点：**单击** → 左右换位并保存。
     *
     *
     * `
     * (!);
     * MMKV.defaultMMKV().sync();
     * ;
     * `
     * 我们的顺序完全一致：先换值 → 落盘 → 刷新布局。
     *
     * @return 换位后的新状态（true = 地图卡在右边）
     */
    fun toggleSidesSwapped(): Boolean {
        val next = PipSides.toggle(ratioStore.readSidesSwapped())
        ratioStore.writeSidesSwapped(next)
        applySidesSwapped(next)
        // 排障场景：「点三个点没反应 / 换位重启就没了」——用户操作必须落盘。
        Logx.event(
            LogTags.NAV_UI,
            "画中画左右换位",
            "swapped" to next,
            "dual" to dualPip,
            "pkg" to pip.currentPackage,
        )
        return next
    }

    /**
     * 应用左右换位。
     *
     * 换位是**布局层面**的：整行导航切成 RTL → 地图卡与组合卡视觉上左右对调；
     * 两张卡自己保持 LTR（`view.setLayoutDirection(0)` / `view3.setLayoutDirection(0)`），
     * **只换位置，不镜像卡片里的内容**。
     *
     * 注意：换位**不是**交换两个画中画的包名 —— 主画始终跑 `pip_app`，
     * 只是它显示在右边还是左边变了。
     */
    fun applySidesSwapped(swapped: Boolean) {
        sidesSwapped = swapped
        if (::navRow.isInitialized) {
            navRow.layoutDirection = if (PipSides.layoutDirection(swapped) == PipSides.DIR_RTL) {
                View.LAYOUT_DIRECTION_RTL
            } else {
                View.LAYOUT_DIRECTION_LTR
            }
            // 子项各自 LTR：卡片内部内容不跟着镜像（MainActivity:1081-1083）
            // 第129轮：方向来自 NavSplitPlacement —— 承载面必须 LTR，
            // 否则按钮（Gravity.START）会跑到画中画的右边，跟画面错位。
            mapCard.layoutDirection = NavSplitPlacement.PIP_CARD_LAYOUT_DIRECTION
            rightSlot.layoutDirection = View.LAYOUT_DIRECTION_LTR
            navRow.requestLayout()
        }
    }

    // ------------------------------------------------------------- 页面骨架
    private fun buildRow() {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            clipChildren = false
            clipToPadding = false
        }
        navRow = row

        // 左：地图卡（承载画中画）—— 权重 = primaryPipRatio
        row.addView(
            mapCard,
            LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, pipRatio),
        )
        // 中：分隔条（第四十九轮：宽度改成设计板的 20px 卡缝，本身不画线）
        row.addView(
            View(context),
            LinearLayout.LayoutParams(NAV_COLUMN_GAP, LayoutParams.MATCH_PARENT).apply {
                topMargin = 10
                bottomMargin = 10
            },
        )
        // 右：右槽 —— 权重 = 1 - primaryPipRatio
        row.addView(
            rightSlot,
            LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f - pipRatio),
        )
        addView(row, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT).apply {
            leftMargin = NAV_SIDE_MARGIN
            rightMargin = NAV_SIDE_MARGIN
            bottomMargin = NAV_BOTTOM_GAP
        })

        // 右槽内容（rightSlot：combinedCard + mapCard2）
        // mapCard2 = **副画承载面**（第二个）
        rightSlot.addView(combinedCard, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        rightSlot.addView(pipSecondary, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        // 地图卡内容：画中画 + 顶部 3 个圆点
        mapCard.addView(pip, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        mapCard.addView(buildPipDots(), dotsParams())

        // 第148轮：把「封面作背景」的开关状态同步进背景层 + 封面图。
        //
        // 必须在 build() 里显式做一次 —— 属性初始化不走 setter（见 [syncCoverBackdropState]
        // 的说明），不补这一下，用户上次关掉的背景在冷启动时会自己回来。
        syncCoverBackdropState()
    }

    /**
     * 画中画上方那三个点。
     *
     * ##交互
     *
     *
     * - `contentDescription` = 「单击左右换位并保存，长按切换应用」；
     * - **单击** →：
     *   `setSidesSwapped(!isSidesSwapped())` → MMKV sync →；
     * - **长按** →；
     * - 双击被显式关掉（`gestureDetector.setOnDoubleTapListener(null)`）。
     *
     * 三个点本身是**纯静态灰点**（= oval #666666）
     * 代码里没有任何状态更新 —— 我们保持一致，不做选中态。
     *
     * 版式：横向居中、padding 8dp、宽 60dp、marginTop 8dp、elevation 2dp。
     */
    private fun buildPipDots(): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(8, 8, 8, 8)
            minimumWidth = 60
            isFocusable = true
            isClickable = true
            isLongClickable = true
            contentDescription = "单击左右换位并保存，长按切换应用"
            elevation = 2f
            setOnClickListener {
                // lambda$setupDotGestures$3 —— 换位 + 落盘 + 刷新布局
                this@NavPage.onPipSwapSides?.invoke()
            }
            setOnLongClickListener {
                // lambda$setupDotGestures$4 —— 打开画中画应用选择器
                this@NavPage.onPipPickApp?.invoke()
                true
            }
            // setupDotGestures 的触摸拦截：手势期间不让父层（翻页）抢走事件
            setOnTouchListener { v, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                }
                false
            }
        }.also { group ->
            for (i in 0 until 3) {
                group.addView(
                    View(context).apply { background = Skin.oval(cardPal.withAlpha(cardPal.text, 0x4D)) },
                    LinearLayout.LayoutParams(6, 6).apply { if (i < 2) rightMargin = 8 },
                )
            }
        }

    /** 三个点的位置：地图卡顶部居中（`layout_gravity=center|top` + `marginTop=8dp`）。 */
    private fun dotsParams(): LayoutParams =
        LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
            topMargin = 8
        }

    // ------------------------------------------------------------- 组合卡

    /**
     * 卡片风格改了（第五十八轮）：刷新组合卡的背景。
     *
     * 组合卡的背景是**构建时**设上去的（不是每帧画），
     * 所以透明度 / 颜色 / 圆角改了之后要单独刷一次。
     *
     * 现在背景层是 `CoverBackdropDrawable` —— 它同时管「卡面色 / 描边 / 圆角」
     * 和「封面模糊图 + 遮罩强度」，所以换风格时这几样要一起刷。
     *
     * 注意**遮罩强度会跟着重算**：卡面色变了（用户把卡片调亮 / 调暗），
     * 「文字压在多少亮度的底上才读得清」的答案也变了。
     */
    fun applyCardStyle() {
        coverBackdrop.setStyle(
            surface = cardSurface(),
            stroke = cardPal.stroke,
            radius = com.cardesk.launcher.core.Shape.CARD,
            text = cardWeakText,
        )
    }

    /**
     * 切换「歌曲图片当卡片背景」（**第148轮**）。
     *
     * ## 落盘 + 刷新的顺序
     *
     * 先写 `prefs` 再改内存状态 —— 万一写到一半崩了，磁盘上还是上一个**完整**的状态，
     * 下次启动读出来不会出现「内存说开、磁盘说关」的错位。
     *
     * 改内存状态走 [coverBackdropEnabled] 的 setter，它一次干三件事：
     * 背景层开 / 关、封面图上画 / 擦掉「背景已关」标记。
     *
     * ## 为什么回调给宿主
     *
     * 光切状态用户是「看不到」的 —— 关掉背景后卡片变纯色了，但没有一句话告诉他
     * 「你刚把背景关了」。所以把新状态抛给宿主弹个 Toast。
     */
    fun toggleCoverBackdrop() {
        val next = !coverBackdropEnabled
        prefs.navCoverBackdropEnabled = next
        coverBackdropEnabled = next
        // 排障场景：「点了封面图背景没变 / 状态没记住」——切换必须落盘。
        Logx.event(
            LogTags.NAV_UI,
            "导航页音乐卡封面背景切换",
            "enabled" to next,
            "title" to trackTitle,
        )
        onCoverBackdropToggled?.invoke(next)
    }

    private fun buildCombinedCard() {
        // —— 卡片本体：专辑封面做底（模糊 + 压暗遮罩），没封面时回退成纯扁平面 ——
        //
        // 第四十九轮「浮屿」这层是「纯扁平面 + 1px 描边」（零投影零玻璃）；
        // 现在底层换成封面，所以这一行交给 `CoverBackdropDrawable`：
        // 有封面 → 模糊封面 + 遮罩；没封面 → 卡面色 + 1px 描边（和原来完全一样）。
        combinedCard.background = coverBackdrop
        combinedCard.setPadding(0, 0, 0, 0)

        // 第四十九轮按设计板 `s02-nav` 重排为**绝对定位**版式（坐标相对卡片左上角）：
        //
        // 来源行 left 24 top 22 20px 文本 + 10px 强调色圆点
        // 封面 left 24 top 66 96×96 圆角 26
        // 歌名 left 140 top 78 38px 粗体
        // 歌手 left 140 top 126 22px
        // 歌词块 left 24 top 318 居中三行（27 / 38 粗体 / 27）
        // 进度条 left 24 top 640 高 8
        // 时间行 left 24 top 662 18px
        // 控制键 left 0 top 700 居中，间距 56
        //
        val pad = COMBINED_PAD

        // —— 来源行（来源菜单据此定位）——
        val sourceRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
        }
        // 第138轮：原来这里是一个 10px 的品牌色小圆点 ——
        // 「看得出是哪家，但认不出是哪个 App」。
        // 现在换成与首页音乐卡同一套的来源图标（真 App 图标优先，取不到用自绘符号）。
        //
        // 注意：这里必须**赋给字段**，不能写 `val sourceIconView = …` ——
        // 局部变量会**遮蔽**字段，字段就永远没被初始化，
        // 外面 `::sourceIconView.isInitialized` 一直是 false，
        // 于是 sourceId / sourceIcon / sourceLabel 三处赋值全被跳过
        // （图标永远是默认的「通用音乐」音符）。
        sourceIconView = com.cardesk.launcher.ui.widget.MediaSourceIconView(context).apply {
            sourceId = this@NavPage.sourceId
            sourceLabel = this@NavPage.sourceLabel
            icon = this@NavPage.sourceIcon
        }
        sourceRow.addView(
            sourceIconView,
            LinearLayout.LayoutParams(SOURCE_ICON_SIZE, SOURCE_ICON_SIZE).apply { rightMargin = 8 },
        )
        sourceText = Ui.text(context, sourceLabel, 20f, cardPal.textDim)
        sourceRow.addView(sourceText, LinearLayout.LayoutParams(-2, -2))
        val chev = Ui.text(context, "⌄", 20f, cardPal.textDim)
        sourceChevron = chev
        sourceRow.addView(chev, LinearLayout.LayoutParams(20, 20).apply { leftMargin = 4 })
        musicStateText = Ui.text(context, musicState, 20f, cardWeakText)
        sourceRow.addView(
            musicStateText,
            LinearLayout.LayoutParams(0, -2, 1f).apply { gravity = Gravity.END or Gravity.CENTER_VERTICAL },
        )
        sourceRow.isClickable = true
        sourceRow.setOnClickListener {
            // 排障场景：「导航页音乐卡来源行点了没弹菜单」。
            Logx.event(LogTags.NAV_UI, "导航页音乐卡来源行点击", "source" to sourceLabel)
            onSource?.invoke()
        }
        combinedCard.addView(sourceRow, abs(pad, COMBINED_SOURCE_TOP, -1, 24))
        sourceRowView = sourceRow

        // —— 封面 + 歌名 / 歌手 ——
        // 设计板封面 96×96 / 圆角 26；原本是 80×80
        // 第四十九轮按设计板放大到 96。
        val art = com.cardesk.launcher.ui.widget.AlbumArtView(context).apply {
            cornerRadius = 26f
            placeholderColor = cardPal.withAlpha(cardPal.text, 0x14)
            // 第148轮：**点封面图 = 切换「歌曲图片当卡片背景」**。
            //
            // 这是用户拿到这个功能的主入口 —— 音乐卡上就这一张图，
            // 点它就能开关，比去设置页里找开关顺手得多。
            contentDescription = "歌曲图片，点击切换封面背景"
            setOnClickListener { toggleCoverBackdrop() }
        }
        albumArtView = art
        combinedCard.addView(art, abs(pad, COMBINED_ART_TOP, 96, 96))

        //
        // `maxLines="1"` + `ellipsize="end"` —— 长歌名 / 长歌手名截断成省略号。
        titleText = Ui.text(context, trackTitle, 38f, cardPal.text, bold = true).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        combinedCard.addView(titleText, abs(COMBINED_META_X, COMBINED_TITLE_TOP, -1, 48))
        artistText = Ui.text(context, trackArtist, 22f, cardPal.textDim).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        combinedCard.addView(artistText, abs(COMBINED_META_X, COMBINED_ARTIST_TOP, -1, 30))

        // —— 歌词：居中三行（上一句 / 当前句带擦除高亮 / 下一句）——
        //
        // 是 5 句窗口（的 {2,2}）
        // 第四十九轮按设计板 `s02-nav` 收敛成**三行**（与首页 `drawCenterLyric` 同款版式）：
        // 上一句 27px 弱色 / 当前句 38px 粗体 + 擦除高亮 / 下一句 27px 弱色。
        // 数据源仍来自 5 句窗口（[refreshLyricWindow] 里按 slot 取），
        // 只显示离当前句最近的 3 句 —— 功能不丢，版面与设计板一致。
        val lyricSpacer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        lyricTexts = ArrayList(LYRIC_SLOTS.size)
        for (slot in LYRIC_SLOTS) {
            val isCurrent = slot == 0
            // 设计板 s02：当前句主文字 / 下1 次文字 / 下2 弱文字
            val color = when {
                isCurrent -> cardPal.text
                slot == 1 -> cardPal.textDim
                else -> cardWeakText
            }
            // 自绘控件：当前句开擦除高亮，其余句纯色
            // 三种模式都支持**跑马灯**（超宽时整句水平滚动，不再被 `ellipsize` 截断）。
            val tv = com.cardesk.launcher.ui.widget.WipeLyricTextView(context).apply {
                textSizePx = if (isCurrent) LYRIC_SIZE_CURRENT else LYRIC_SIZE_OTHER
                bold = isCurrent
                wipeEnabled = isCurrent
                plainColor = color
                activeColor = cardAccent
                inactiveColor = cardPal.text
                wipeTarget = lyricWipe
                scrollable = true
            }
            lyricTexts.add(tv)
            lyricSpacer.addView(
                tv,
                // 设计板 s02：当前句 38px（行高 50）/ 下1 27px（行高 33，距上 26）/ 下2 27px（行高 33，距上 18）
                LinearLayout.LayoutParams(-1, if (isCurrent) LYRIC_LINE_H_CURRENT else LYRIC_LINE_H_OTHER).apply {
                    topMargin = when (slot) {
                        1 -> LYRIC_GAP_1
                        2 -> LYRIC_GAP_2
                        else -> 0
                    }
                },
            )
        }
        combinedCard.addView(lyricSpacer, abs(pad, COMBINED_LYRIC_TOP, -1, LYRIC_BLOCK_H))

        // —— 进度条：高 8 的胶囊轨道 + 强调色填充（第四十九轮去掉圆点，改成设计板的纯条）——
        progressHost = FrameLayout(context)
        progressHost.addView(
            View(context).apply { background = Skin.solid(cardPal.surfaceStrong, 4f) },
            LayoutParams(LayoutParams.MATCH_PARENT, 8, Gravity.CENTER_VERTICAL),
        )
        progressFill = View(context).apply { background = Skin.solid(cardAccent, 4f) }
        progressHost.addView(progressFill, LayoutParams(0, 8, Gravity.CENTER_VERTICAL))
        combinedCard.addView(progressHost, abs(pad, COMBINED_PROGRESS_TOP, -1, 24))

        // —— 时间行 ——
        val timeRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        currentTimeText = Ui.text(context, positionText, 18f, cardWeakText)
        totalTimeText = Ui.text(context, durationText, 18f, cardWeakText)
        timeRow.addView(currentTimeText, LinearLayout.LayoutParams(0, -2, 1f))
        timeRow.addView(totalTimeText, LinearLayout.LayoutParams(-2, -2))
        combinedCard.addView(timeRow, abs(pad, COMBINED_TIME_TOP, -1, 26))

        // —— 控制键：上一曲 / 播放 / 下一曲（设计板是纯字形，没有圆形底）——
        val controls = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val prevBtn = iconButton("‹", 50)
        prevBtn.setOnClickListener {
            // 排障场景：「导航页上一曲/下一曲点了不动」。
            Logx.event(LogTags.NAV_UI, "导航页音乐卡上一曲", "title" to trackTitle)
            onPrev?.invoke()
        }
        controls.addView(prevBtn, LinearLayout.LayoutParams(40, 56).apply { rightMargin = 56 })
        val playBtn = Ui.text(context, "▶", 48f, cardPal.text, bold = true).apply {
            gravity = Gravity.CENTER
            isClickable = true
            setOnClickListener {
                Logx.event(
                    LogTags.NAV_UI,
                    "导航页音乐卡播放暂停",
                    "playing" to playing,
                    "title" to trackTitle,
                )
                onPlayToggle?.invoke()
            }
        }
        playGlyph = playBtn
        controls.addView(playBtn, LinearLayout.LayoutParams(56, 56))
        val nextBtn = iconButton("›", 50)
        nextBtn.setOnClickListener {
            Logx.event(LogTags.NAV_UI, "导航页音乐卡下一曲", "title" to trackTitle)
            onNext?.invoke()
        }
        controls.addView(nextBtn, LinearLayout.LayoutParams(40, 56).apply { leftMargin = 56 })
        combinedCard.addView(controls, abs(0, COMBINED_CONTROLS_TOP, -1, 56))
    }

    /** FrameLayout 绝对定位（相对组合卡左上角）。 */
    private fun abs(x: Int, y: Int, w: Int, h: Int): LayoutParams =
        LayoutParams(w, h).apply {
            leftMargin = x
            topMargin = y
        }
    private fun iconButton(glyph: String, size: Int): TextView = Ui.text(context, glyph, size * 0.7f, cardPal.text).apply {
        gravity = Gravity.CENTER
        isClickable = true
    }

    // ------------------------------------------------------------- 分隔条 / 切页按钮

    private fun buildPipDivider() {
        pipDivider = PipDividerView(context).apply {
            background = Skin.flat(6f, true)
            // 单击 = 主画全屏
            onSingleTap = {
                // 排障场景：「点分隔条画中画没全屏」——单击事件必须落盘。
                Logx.event(
                    LogTags.NAV_UI,
                    "画中画分隔条单击（切换全屏）",
                    "dual" to dualPip,
                    "ratio" to pipRatio,
                    "pkg" to pip.currentPackage,
                )
                this@NavPage.onPipFullscreen?.invoke()
            }
            // 长按拖比例：实时刷新
            onRatioChanged = { ratio ->
                pipRatio = ratio
                // 排障场景：「分隔条拖不动」——每帧比例用 DEBUG，不落盘。
                Logx.d(LogTags.NAV_UI, "画中画分隔条拖动中", "ratio" to ratio)
                this@NavPage.onRatioChanged?.invoke(ratio)
            }
            // 松手落盘
            onRatioCommitted = { ratio ->
                ratioStore.write(dualPip, ratio)
                pipRatio = ratio
                // 排障场景：「比例拖完重启就没了」——落盘值必须落盘可查。
                Logx.event(
                    LogTags.NAV_UI,
                    "画中画分隔条比例落盘",
                    "dual" to dualPip,
                    "ratio" to ratio,
                    "key" to PipRatio.key(dualPip),
                )
                this@NavPage.onRatioCommitted?.invoke(ratio)
            }
        }
        addView(
            pipDivider,
            LayoutParams(12, 144, Gravity.CENTER_VERTICAL),
        )
    }

    private fun buildNavSplit() {
        val split = FrameLayout(context).apply {
            background = Skin.glass(18f)
            isClickable = true
            setOnClickListener {
                // 排障场景：「左侧 chevron 按钮点了没反应」。
                Logx.event(LogTags.NAV_UI, "导航页分屏按钮点击", "dual" to dualPip, "ratio" to pipRatio)
                onSplitToggle?.invoke()
            }
        }
        navSplit = split
        split.addView(
            Ui.text(context, "›", 26f, Skin.TEXT).apply { gravity = Gravity.CENTER },
            LayoutParams(24, 24, Gravity.CENTER),
        )
        // 第129轮：**挂进画中画承载面**，叠在画面左边缘。
        //
        // 以前它 addView 到导航页本体（NavigationPage 根 FrameLayout）的
        // START 位置 —— 也就是**独占了 navRow 左边那 44px 留白**
        // （NAV_SIDE_MARGIN），画中画左侧长期空着一条竖玻璃条。
        //
        // 现在它跟着画中画走：
        // - 画中画左右换位时（`applySidesSwapped` 靠 layoutDirection 实现），
        //   按钮跟着画中画一起换边，不会跑到画中画对面去；
        // - 位置与**画中画全屏态**完全一致（都是左侧垂直居中），
        //   用户不用记两个位置。
        mapCard.addView(split, navSplitParams())
        mapCard.bringChildToFront(split)
    }

    /**
     * 玻璃条在画中画承载面里的定位参数（第129轮）。
     *
     * 宽 / 高 / gravity 全部取自 [NavSplitPlacement] —— 全屏态
     * （`LauncherView.enterPipFullscreen`）也从同一个对象取，
     * 两态位置天然一致，用户不用记两个位置。
     */
    private fun navSplitParams(): LayoutParams =
        LayoutParams(
            NavSplitPlacement.WIDTH,
            NavSplitPlacement.HEIGHT,
            NavSplitPlacement.gravity(fullscreen = false),
        )

    /**
     * 把左侧玻璃条（`navSplit`）**摘出去** —— 全屏时它要浮在画中画上方当还原按钮。
     *
     * 全屏时
     * `navSplit.setVisibility(VISIBLE)` 且点击执行 （还原）；
     * 非全屏时它是 GONE。我们的画中画全屏时是提到根容器上的，
     * 导航页里的 `navSplit` 会被盖住 → 必须一起提上来。
     *
     * @return 被摘出来的 View（已在原父容器里移除）
     */
    fun detachNavSplit(): View? {
        val v = if (::navSplit.isInitialized) navSplit else return null
        (v.parent as? android.view.ViewGroup)?.removeView(v)
        return v
    }

    /**
     * 把玻璃条放回导航页（还原时调用）。
     */
    fun attachNavSplit() {
        if (!::navSplit.isInitialized) return
        if (navSplit.parent === mapCard) return
        (navSplit.parent as? android.view.ViewGroup)?.removeView(navSplit)
        // 第129轮：放回**画中画承载面**（不是导航页本体）。
        mapCard.addView(navSplit, navSplitParams())
        mapCard.bringChildToFront(navSplit)
    }

    // ------------------------------------------------------------- 布局刷新

    /**
     * 把比例写回导航行权重
     *
     */
    private fun applyRatio() {
        val ratio = pipRatio
        (mapCard.layoutParams as? LinearLayout.LayoutParams)?.let {
            it.weight = ratio
            mapCard.layoutParams = it
        }
        (rightSlot.layoutParams as? LinearLayout.LayoutParams)?.let {
            it.weight = 1f - ratio
            rightSlot.layoutParams = it
        }
    }

    /**
     * 单画 / 双画切换
     * 双画时组合卡隐藏、右侧换成第二路画中画。
     *
     * 本应用只有一路画中画能力（`ui/embed/ActivityEmbed.kt`），
     * 所以右半是**副画降级占位**（同包名 + 「副画暂不支持：已在主画承载」）。
     */
    private fun applyDualLayout() {
        // 双画：组合卡隐藏、右侧换成**第二路画中画**（mapCard2）
        combinedCard.visibility = if (dualPip) View.GONE else View.VISIBLE
        pipSecondary.visibility = if (dualPip) View.VISIBLE else View.GONE
    }

    /** 分隔条显隐与手势开关。 */
    private fun applyPipChrome() {
        if (!::pipDivider.isInitialized) return
        pipDivider.visibility = if (pipFullscreen) View.GONE else View.VISIBLE
        pipDivider.gestureEnabled = !pipFullscreen
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        placePipDivider()
        refreshProgress()
    }

    /** 分隔条压在地图卡与右槽之间（:2277-2332）。 */
    private fun placePipDivider() {
        if (width <= 0) return
        val mapWidth = mapCard.width
        // 分隔条卡在「地图卡 ↔ 组合卡」那条缝上：
        // 不换位时地图卡在左 → 缝在地图卡右边；换位后地图卡在右 → 缝在左边。
        val edge = PipSides.dividerEdge(mapCard.left, mapCard.right, sidesSwapped)
        pipDivider.x = (edge - pipDivider.width / 2).toFloat()
        pipDivider.y = ((mapCard.height - pipDivider.height) / 2).toFloat()

        // —— 拖比例手势需要的输入——
        pipDivider.ratioProvider = { pipRatio }
        // 可拖动总宽度 = 地图卡宽度 + 右槽宽度
        pipDivider.totalDragWidth = (mapWidth + rightSlot.width).toFloat()
        pipDivider.dualMode = dualPip
        // 左右互换：拖动方向要跟着反过来（`PipRatio.dragRatio` 的 swapped 分支）
        pipDivider.sidesSwapped = sidesSwapped
        pipDivider.gestureEnabled = !pipFullscreen

        // 触摸热区：另有一条整列高的拖拽条
        // 本应用用 TouchDelegate 把 12px 宽的 chip 扩成整列高、36px 宽。
        if (mapCard.height > 0) {
            val extra = DIVIDER_TOUCH_EXTRA_WIDTH / 2
            touchDelegate = TouchDelegate(
                Rect(
                    (pipDivider.x - extra).toInt(),
                    0,
                    (pipDivider.x + pipDivider.width + extra).toInt(),
                    mapCard.height,
                ),
                pipDivider,
            )
        }
    }

    /** 刷新进度条填充宽度（0..1）。 */
    fun refreshProgress() {
        if (!::progressFill.isInitialized) return
        val host = progressHost
        val usable = host.width
        if (usable <= 0) return
        val lp = progressFill.layoutParams
        if (lp != null) {
            lp.width = (usable * progress).toInt().coerceAtLeast(0)
            progressFill.layoutParams = lp
        }
    }


    companion object {
        /**
         * 组合卡里歌词高亮用的**夜间那档强调色**（`#5AD8C8`）。
         *
         * 夜间是深底浅字，这档青在压暗后的封面上很跳。
         */
        const val CARD_ACCENT_NIGHT = 0xFF5AD8C8.toInt()

        /**
         * 日间组合卡的强调色（当前歌词高亮 / 进度条填充）。
         *
         * 日间是**白底深字**，封面被罩成一层很淡的灰调影子（最暗处 L=0.4340）——
         * 日间主题那个强调色 `#0F9C8E`（L=0.2584）压在上面只有 1.57:1，读不出来。
         * 这档加深到 `#0A6459`（L=0.0990）→ 3.25:1。
         */
        const val CARD_ACCENT_DAY = 0xFF0A6459.toInt()

        // —— 第四十九轮「浮屿」：导航页版面对齐设计板 s02-nav ——

        /**
         * 导航行左右留白（设计 px）。
         *
         * 第四十九轮按设计板 `s02-nav`：左槽地图卡 `left 44`、右槽组合卡 `right 1876`，
         * 两张卡之间留 20 的缝。容器宽度 = 1920 − 44 × 2 = **1832**。
         */
        const val NAV_SIDE_MARGIN = 44

        /** 地图卡 ↔ 组合卡之间的缝（设计 px）。 */
        const val NAV_COLUMN_GAP = 20

        /**
         * 导航页内容顶部（状态栏显示时，设计 px）。
         *
         * 第四十九轮按设计板 `s02-nav`：两张卡顶边都在 **110**（状态栏那一行之下留 20）。
         *
         * 第五十二轮：状态栏整体缩到 2/3（行高 64 → 43），内容起点随之上移
         * **110 → 73**（= 110 × 2/3），底边仍贴 Dock 上沿 940，卡片相应长高。
         */
        const val NAV_CONTENT_TOP = 73

        /** 导航页内容底部与 Dock 上沿之间的空隙（设计 px）。 */
        const val NAV_BOTTOM_GAP = 28

        /** 组合卡内容左右留白（设计 px）。 */
        const val COMBINED_PAD = 24

        /** 组合卡：来源行顶部偏移。 */
        const val COMBINED_SOURCE_TOP = 22

        /**
         * 来源行**图标**的边长（设计 px，第138轮）。
         *
         * 原来这里是一个 10px 的品牌色小圆点；换成真 App 图标后要画得清楚一点，
         * 取 **24**（与这一行 20px 的来源文案同一量级，不喧宾夺主）。
         */
        const val SOURCE_ICON_SIZE = 24

        /** 组合卡：专辑封面上边缘。 */
        const val COMBINED_ART_TOP = 66

        /** 组合卡：歌名 / 歌手的左边缘（封面右侧）。 */
        const val COMBINED_META_X = 140

        /** 组合卡：歌名顶部。 */
        const val COMBINED_TITLE_TOP = 78

        /** 组合卡：歌手顶部。 */
        const val COMBINED_ARTIST_TOP = 126

        /** 组合卡：歌词块顶部。 */
        const val COMBINED_LYRIC_TOP = 318

        /** 组合卡：进度条顶部。 */
        const val COMBINED_PROGRESS_TOP = 640

        /** 组合卡：时间行顶部。 */
        const val COMBINED_TIME_TOP = 662

        /** 组合卡：控制键顶部。 */
        const val COMBINED_CONTROLS_TOP = 700

        /**
         * 组合卡歌词显示的三个 slot（**当前句 / 下 1 / 下 2**）。
         *
         * 第五十轮按设计板 `s02-nav` 改：当句在最上面（38px 粗体 + 擦除高亮），
         * 下面依次是下 1（27px 次文字）与下 2（27px 弱文字）。
         */
        val LYRIC_SLOTS = intArrayOf(0, 1, 2)

        /** 组合卡：当前句歌词字号（设计 px）。 */
        const val LYRIC_SIZE_CURRENT = 38f

        /** 组合卡：非当前句歌词字号（设计 px）。 */
        const val LYRIC_SIZE_OTHER = 27f

        /** 组合卡：当前句行高（设计 px，38 × 1.3）。 */
        const val LYRIC_LINE_H_CURRENT = 50

        /** 组合卡：非当前句行高（设计 px，27 × 1.2）。 */
        const val LYRIC_LINE_H_OTHER = 33

        /** 组合卡：下 1 句与当前句之间的间距（设计 px）。 */
        const val LYRIC_GAP_1 = 26

        /** 组合卡：下 2 句与下 1 句之间的间距（设计 px）。 */
        const val LYRIC_GAP_2 = 18

        /** 组合卡：歌词块总高（设计 px）。 */
        const val LYRIC_BLOCK_H = LYRIC_LINE_H_CURRENT + LYRIC_GAP_1 + LYRIC_LINE_H_OTHER +
            LYRIC_GAP_2 + LYRIC_LINE_H_OTHER

        /**
         * 导航页内容顶部留白 —— **状态栏显示**时（设计 px）。
         *
         *
         *
         * `java
         * public static int navContentTop() {
         *     return ? 8: 110;
         * }
         * `
         *
         * 在 用它给导航页主容器设 `topMargin`
         * 状态栏是**全屏浮层**（是 1920×1080 的 FrameLayout，
         * 左段头像+驾驶模式、右段图标+时钟，它浮在导航页**之上**。
         * 状态栏显示时导航页内容整体下移 110px 让位，否则右槽的播放器头
         * （封面 / 歌名）会被状态栏的时钟和信号图标**压住**。
         *
         *  **默认 true**（导航页默认隐藏状态栏）
         * 我们第三十七轮按用户要求把默认值改成 false（两页都显示状态栏），
         * 所以默认走的是 **110** 这条分支。
         *
         * ---
         *
         * **第四十三轮整条「整页下移」被推翻**（用户要求「做成沉浸式的不要割裂」）：
         * 那轮把导航页顶到屏幕顶、状态栏当透明浮层压在内容上。
         *
         * **第四十四轮按用户要求「内容不要顶到屏幕顶」改回「紧凑」形态**：
         * 导航页整体下移到**状态栏那一行的底部**（见 [NAV_CONTENT_TOP_TIGHT]），
         * 状态栏正好贴着内容的上沿 —— 既不顶到屏幕顶，也没有一条空白带。
         */
        const val NAV_CONTENT_TOP_WITH_STATUS = 73

        /**
         * 导航页内容顶部留白 —— **状态栏隐藏**时（设计 px）。
         *
         *  8（与组合卡 `paddingTop=8` 一起
         * 让导航页贴着顶部。
         */
        const val NAV_CONTENT_TOP_NO_STATUS = 8

        /**
         * 导航页内容顶部留白 —— **状态栏显示**时（设计 px）。
         *
         * 第四十四轮（用户选定的「紧凑」形态）：内容从**状态栏那一行的底部**开始，
         * 状态栏紧贴内容上沿，中间没有空白带。
         *
         * 数值 = `ShellStatusBar.ROW_TOP + ShellStatusBar.ROW_H`
         *      = 26 + 64 = **90**（状态栏这一行整行的高度，含顶边距）。
         *
         * 与 110 的差别：那 110 是给**更大的**状态栏留的
         * 我们这版状态栏元素已恢复原值（行底 90）
         * 按 110 下移就会在状态栏与内容之间多出一条 20px 的空带 —— 那正是
         * 用户说的「割裂」。所以这里取**行底 90**，贴着走。
         *
         * 第五十二轮状态栏缩到 2/3，行底跟着变成 **60**（17 + 43）。
         */
        const val NAV_CONTENT_TOP_TIGHT = 60

        /**
         * 右槽**组合卡自己**的顶部内边距（设计 px）。
         *
         * 第四十四轮起回到 `paddingTop = 8`
         * 导航页整体已经让开状态栏那一行了，卡片自己不用再额外避让。
         */
        const val CARD_TOP_INSET = 8

        /**
         * 分隔条触摸热区在 chip 两侧各扩出的宽度（设计 px，两侧合计 +24 → 热区 36px 宽）。
         *
         * 另挂的整列高拖拽条。
         */
        const val DIVIDER_TOUCH_EXTRA_WIDTH = 24f

        /**
         * 画中画内容区顶部留白（设计 px）。
         *
         * 与组合卡 `paddingTop=8` 对齐。
         */
        const val PIP_CONTENT_INSET_TOP = 0

        /**
         * 画中画内容区底部留白（设计 px）。
         *
         * 与组合卡 `paddingBottom=126` 对齐——
         * 这 126px 是给浮在导航页之上的 Dock 栏（铺满屏宽×104 + 距底 8）让位的。
         */
        const val PIP_CONTENT_INSET_BOTTOM = 0
    }
}
