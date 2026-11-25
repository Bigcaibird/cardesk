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
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.cardesk.launcher.core.Palettes
import com.cardesk.launcher.core.Palette
import com.cardesk.launcher.core.Prefs
import com.cardesk.launcher.core.Skin
import com.cardesk.launcher.core.log.LogTags
import com.cardesk.launcher.core.log.Logx
import com.cardesk.launcher.core.media.LyricWindow
import com.cardesk.launcher.core.media.MediaActions
import com.cardesk.launcher.ui.Ui
import com.cardesk.launcher.ui.anim.Motion
import com.cardesk.launcher.ui.anim.MotionSpec
import com.cardesk.launcher.ui.pip.NavSplitPlacement
import com.cardesk.launcher.ui.pip.PipDividerView
import com.cardesk.launcher.ui.pip.PipRatio
import com.cardesk.launcher.ui.pip.PipRatioStore
import com.cardesk.launcher.ui.pip.PipSides
import com.cardesk.launcher.ui.pip.PipSurface
import com.cardesk.launcher.ui.widget.MediaActionIcons

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
     * 专辑图（140×140 / 圆角 28 / centerCrop；画板实测见 [COMBINED_ART_TOP]）。
     *
     * 同一张封面同时喂给**两处**：
     * - [albumArtView]：卡片左上角那块 140×140 的封面（画板实测 1323,137）；
     * - [coverBackdrop]：整张卡的**背景**（模糊 + 遮罩）。
     */
    var artwork: android.graphics.Bitmap? = null
        set(v) {
            field = v
            if (::albumArtView.isInitialized) albumArtView.bitmap = v
            // 画板只在**没有封面**时画占位块里的音符 —— 有图了就撤掉，
            // 否则音符会压在真封面上。
            if (::coverNote.isInitialized) {
                coverNote.visibility = if (v == null) View.VISIBLE else View.GONE
            }
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
        // 封面背景是**自绘背景层**，开关它没有渐变可言；
        // 至少给点它的那个封面图「鼓一下」——
        // 不然卡片只是悄悄换了个底色，用户会以为点空了。
        if (::albumArtView.isInitialized && albumArtView.isAttachedToWindow) {
            Motion.bump(albumArtView)
        }
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
            val changed = field != v
            field = v
            if (::playGlyph.isInitialized) {
                // ▶ / ❚❚ 换脸是「开始 / 暂停」**唯一**的信号 ——
                // 补一个鼓一下，确认这一下真的生效了。
                playGlyph.setPlaying(v)
                if (changed) Motion.bump(playGlyph)
            }
        }

    // ------------------------------------------- 收藏 / 播放模式（第一九五轮）

    /**
     * 当前播放器的**包名**。
     *
     * 决定收藏 / 播放模式这两个按钮**显不显示** ——
     * 不支持的播放器**直接隐藏**，而不是亮一个「点了没反应」的按钮
     * （汽水音乐车机版就没有切播放模式的对外通道）。
     */
    var musicPackage: String? = null
        set(value) {
            field = value
            applyActionVisibility()
        }

    /**
     * 当前会话的**运行时探测**结果（第一九六轮）。
     *
     * 能力表里没有的包（没逆向过的音乐 App）靠它决定亮不亮按钮 ——
     * 会话发了**心形评分** → 亮收藏；会话**暴露了**播放模式 → 亮播放模式。
     *
     * 判定在 [MediaActions.capsOf] 里做（纯逻辑，可单测）。
     */
    var actionProbe: MediaActions.Probe? = null
        set(value) {
            field = value
            // 这里**不做**「值没变就跳过」的短路 ——
            // 换了播放器但探测结果恰好相同时（两个 App 都发心形评分），
            // 短路会让按钮显隐跟着上一个播放器走。
            // `applyActionVisibility()` 只是设两个 visibility，代价可以忽略。
            applyActionVisibility()
        }

    /**
     * 收藏态（**三态**：已收藏 / 未收藏 / 不知道）。
     *
     * 「不知道」和「未收藏」在界面上要分得开：
     * 不知道 → 空心心形且**不点亮**；未收藏 → 空心心形但颜色正常。
     */
    var favoriteState: MediaActions.FavoriteState = MediaActions.FavoriteState.UNKNOWN
        set(value) {
            // 桌面每秒刷新一次 → 值没变就不重画（重建矢量 Drawable 纯属浪费）。
            if (field == value) return
            field = value
            applyFavoriteState()
            // 三态之间任意一跳（空心 ↔ 实心、不知道 → 已知）都要有信号：
            // 鼓一下，让用户确认「收藏状态真的变了」。
            favoriteBtn?.let { if (it.isAttachedToWindow) Motion.bump(it) }
        }

    /**
     * 播放模式。
     *
     * 不知道时显示「顺序」—— 下一次点击就是切到顺序播放
     * （见 [MediaActions.nextPlayMode]），按钮上显示的**就是点击后要切到的模式**。
     */
    var playModeState: MediaActions.PlayModeState = MediaActions.PlayModeState.UNKNOWN
        set(value) {
            if (field == value) return
            field = value
            applyPlayModeState()
            // 播放模式符号换了（顺序 → 单曲 → 随机）→ 鼓一下，
            // 否则只是图标悄悄变了一下，余光根本抓不到。
            playModeBtn?.let { if (it.isAttachedToWindow) Motion.bump(it) }
        }

    /** 收藏按钮被点了（宿主去下发「收藏 / 取消收藏」）。 */
    var onFavoriteToggle: (() -> Unit)? = null

    /** 播放模式按钮被点了（宿主去切「顺序 → 单曲 → 随机」）。 */
    var onPlayModeToggle: (() -> Unit)? = null

    // ------------------------------------------------------------------ 回调

    /**
     * 来源行的锚点视图（来源菜单据此定位）。
     *
     * 第三十二轮起第三页（播放器页）已去掉，来源菜单统一锚在**第二页组合卡**的来源行上。
     */
    val sourceAnchor: View? get() = sourceRowView

    /**
     * 音乐卡（右槽）矩形，页面坐标（第244轮）。
     *
     * 导航页的来源弹层要**盖在音乐卡上**：卡片本身 900×780 的量级，
     * 弹层 361×625 居中放进去正好把卡片当底板，不会伸到地图上。
     */
    val musicCardRect: RectF?
        get() {
            if (combinedCard.width <= 0 || combinedCard.height <= 0) return null
            val self = IntArray(2)
            val card = IntArray(2)
            getLocationOnScreen(self)
            combinedCard.getLocationOnScreen(card)
            val l = (card[0] - self[0]).toFloat()
            val t = (card[1] - self[1]).toFloat()
            return RectF(l, t, l + combinedCard.width, t + combinedCard.height)
        }

    /**
     * 来源菜单展开时把箭头转 180°（**动画**转，不是硬切）。
     *
     * 箭头是「菜单开没开」唯一的常驻信号 ——
     * 硬切的话余光只会觉得「有个东西闪了一下」，转过去才看得出是翻页。
     */
    fun setSourceMenuOpen(open: Boolean) {
        val chev = sourceChevron ?: return
        val target = if (open) 180f else 0f
        if (chev.rotation == target) return
        chev.animate().cancel()
        chev.animate()
            .rotation(target)
            .setDuration(MotionSpec.SHEET_ENTER_DURATION)
            .setInterpolator(Motion.EMPHASIS)
            .start()
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

    /**
     * 双画中画窗口 2 上方三个点：**长按** → 打开副画应用选择器。
     */
    var onPipSecondaryPickApp: (() -> Unit)? = null

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
            //
            // 第228轮：**节流**。拖动时每帧都会走这里 ⇒ 每帧一条 logcat
            // ⇒ 车机上（logcat 本身很吵）这条日志本身就是拖动卡顿的一部分。
            // 关键信息（起始 / 结束）在 [PipDividerView] 与
            // 「画中画分隔条比例落盘」里都有，这里只留个稀疏的采样。
            val now = android.os.SystemClock.uptimeMillis()
            if (now - lastRatioLogAt >= RATIO_LOG_THROTTLE_MS) {
                lastRatioLogAt = now
                Logx.d(LogTags.NAV_UI, "画中画分屏比例变化", "ratio" to next, "dual" to dualPip)
            }
            applyRatio()
        }

    /** 本次拖动收到了多少个比例步进（第229轮，量拖动是否被主线程堵住）。 */
    private var dragSteps = 0

    /** 上一次记「分屏比例变化」的时刻（节流用，第228轮）。 */
    private var lastRatioLogAt = 0L

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
        // 卡缝宽度跟画板走：单画 40（02-nav）、双画 24（09-pip）。
        applyNavGap()
    }

    /** 卡缝宽度：单画 40、双画 24（见 [syncPipState]）。 */
    private fun applyNavGap() {
        if (!::navGap.isInitialized) return
        val want = if (dualPip) NAV_COLUMN_GAP_DUAL else NAV_COLUMN_GAP
        val lp = navGap.layoutParams as? LinearLayout.LayoutParams ?: return
        if (lp.width == want) return
        lp.width = want
        navGap.layoutParams = lp
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

    private lateinit var navGap: View
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

    /** 播放键：自绘的「实心圆 + 描边三角 / 双竖条」（画板要求，见 [NavPlayButtonView]）。 */
    private lateinit var playGlyph: NavPlayButtonView

    /** 封面占位块里的双联音符（画板：没有封面时才出现）。 */
    private lateinit var coverNote: NavCoverNoteView

    /**
     * 空槽位的**设计版占位**（白圆 + 加号 + 「请选择应用」）。
     *
     * 与 PipSurface 自己的占位互斥：哪个可见由 [syncEmptySlot] 决定，
     * 见那边的说明（为什么要「接管」而不是新做一个空状态）。
     */
    private var emptySlot: LinearLayout? = null

    /** 空槽位文案（文案本身来自 PipSurface 的占位，见 [syncEmptySlot]）。 */
    private lateinit var emptyLabel: TextView

    /** 收藏按钮（第一九五轮；不支持的播放器会隐藏它）。 */
    /** 音乐卡控制键那一行（第229轮：窄卡时要收紧间距）。 */
    private var combinedControls: LinearLayout? = null
    private var prevButton: TextView? = null
    private var nextButton: TextView? = null

    private var favoriteBtn: ImageView? = null

    /** 播放模式按钮（第一九五轮；不支持的播放器会隐藏它）。 */
    private var playModeBtn: ImageView? = null

    // 第207轮：给单测开的观察口（只读，不改行为）。
    //
    // 为什么要开这个口：「两个按钮一样大」这件事**光看常量不够** ——
    // 常量对了但 padding 写错，图标照样不一样大。
    // 所以单测要能拿到**真实控件**去量。
    fun favoriteBtnForTest(): ImageView? = favoriteBtn

    /** 第207轮：给单测开的观察口（见 [favoriteBtnForTest]）。 */
    fun playModeBtnForTest(): ImageView? = playModeBtn

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

    /**
     * 地图卡。**空槽位时也必须是卡片**：
     *
     * 画板 `02-nav` 的左卡有卡面（玻璃 + 圆角 + 1px 描边），而 [PipSurface]
     * 在没有地图应用时画的是「透明底 + 占位圆」，卡面就整块消失 ——
     * 实机截图 `/tmp/v-nav-day.png` 里左半屏一片空白，就是这个原因。
     * 有应用时 PipSurface 自己会画画面把底盖住，所以这层底色只在空态可见。
     */
    private val mapCard = FrameLayout(context).apply {
        background = Skin.flat(com.cardesk.launcher.core.Shape.CARD)
    }

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
    private val secondaryDots by lazy { buildPipDots { this@NavPage.onPipSecondaryPickApp?.invoke() } }

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
     * ## 底部为什么是 0（第159轮实测订正）
     *
     * 底部 Dock 栏（铺满屏宽×112，贴底）确实浮在页面之上，
     * 但**导航页整块内容已经被压到 Dock 顶边之上了**：
     * 导航页高度 = `LauncherView.DOCK_TOP_Y - navTop`（底边 = 968），
     * 导航行再有 `bottomMargin = NAV_BOTTOM_GAP`（28），
     * 于是画中画卡底边 = **940**，在 Dock 顶边 968 上方 28px。
     *
     * 所以这里**不需要**再留白 —— 留了反而会让承载内容不铺满卡片、
     * 底部空出一条带子（第159轮那次误改就是这个效果，已撤）。
     *
     * 全屏态（[fullscreen] = true）置 0：画中画全屏就该铺满自己的帧。
     * 第201轮起全屏帧的高度只到 Dock 顶边（968），所以内容正好铺到 Dock 上方，
     * 不会再被底栏压住 112px。
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
        // 中：卡缝（单画 40 / 双画 24；画板 02 与 09 各一份，本身不画线，只当间隔）。
        navGap = View(context)
        row.addView(
            navGap,
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
            // 画板：卡底 y878、Dock 顶 968 → 行底距 90（见 [NAV_ROW_BOTTOM_GAP] 的说明）
            bottomMargin = NAV_ROW_BOTTOM_GAP
        })

        // 右槽内容（rightSlot：combinedCard + mapCard2）
        // mapCard2 = **副画承载面**（第二个）
        rightSlot.addView(combinedCard, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        rightSlot.addView(pipSecondary, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        rightSlot.addView(secondaryDots, dotsParams())

        // 地图卡内容：画中画 + 顶部 3 个圆点
        mapCard.addView(pip, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        // 空槽位的**设计版占位**（画板：白圆 132 + 加号 + 「请选择应用」22px）。
        //
        // PipSurface 自己的占位（70 描边环 + 十字 + 19px 文案）画在 pip 内部，
        // 从外面既改不了版式也不能删 —— 所以这里在它之上再叠一层设计版式，
        // 并在确认「pip 正处于默认空态」时把底下那层藏掉（见 [syncEmptySlot]）。
        // 叠放顺序：pip → 本层 → 三个点，保证点仍然在最上面可点。
        val slot = buildEmptySlot()
        emptySlot = slot
        mapCard.addView(
            slot,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER),
        )
        slot.visibility = View.GONE
        mapCard.addView(buildPipDots(), dotsParams())

        // 第148轮：把「封面作背景」的开关状态同步进背景层 + 封面图。
        //
        // 必须在 build() 里显式做一次 —— 属性初始化不走 setter（见 [syncCoverBackdropState]
        // 的说明），不补这一下，用户上次关掉的背景在冷启动时会自己回来。
        syncCoverBackdropState()
        // 建完就对一次账（首次布局前就把占位换成设计版式，避免先闪一下旧占位）。
        syncEmptySlot()
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
    private fun buildPipDots(
        onLongPress: () -> Unit = { this@NavPage.onPipPickApp?.invoke() },
    ): LinearLayout =
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
                onLongPress()
                true
            }
            // setupDotGestures 的触摸拦截：手势期间不让父层（翻页）抢走事件
            //
            // 用 [Motion.attachPress] 把按压反馈**并进**这个监听器 ——
            // `setOnTouchListener` 是覆盖式的，直接再装一个会把拦截逻辑顶掉。
            Motion.attachPress(this, { v, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                }
                false
            }, MotionSpec.PRESS_SCALE_ICON)
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

        // **绝对定位**版式（坐标相对卡片左上角），第240轮按画板 02-nav 全量重标：
        //
        // 来源行 left 27 top 26  16px 文本 + 24×24 来源图标 + 下拉箭头
        // 封面   left 27 top 70  140×140 圆角 28（无封面时叠一层描边音符）
        // 歌名   left 192 top 107 38px 粗体（画板墨迹 150×36）
        // 歌手   left 192 top 164 14px textFaint
        // 歌词块 left 27 top 318 居中三行（38 粗体 / 27 / 27）—— 画板是空状态，位置沿用旧版
        // 进度条 left 27 top 643 高 10 全圆角（宽 = 卡宽 − 27 − 28）
        // 时间行 left 27 top 663 14px textFaint
        // 控制键 left 0  top 701 高 76 居中：‹ 40 + 68 + 白圆 76 + 68 + 40
        //
        // 卡右内边 28 与左侧 27 差 1 —— 画板右卡 x1296..1879 只有 1px 高光边，
        // 内容区 1323..1852，所以左 27 / 右 28（[COMBINED_PAD] 统一取 27）。
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
            LinearLayout.LayoutParams(SOURCE_ICON_SIZE, SOURCE_ICON_SIZE).apply { rightMargin = 4 },
        )
        sourceText = Ui.text(context, sourceLabel, COMBINED_SOURCE_SIZE, cardPal.textDim)
        sourceRow.addView(sourceText, LinearLayout.LayoutParams(-2, -2))
        val chev = Ui.text(context, "⌄", 20f, cardPal.textDim)
        sourceChevron = chev
        // 画板：来源文案墨迹止于 x1413、箭头墨迹起于 x1428 —— 中间那 15px 里
        // 有 7px 是「⌄」自己的左边距，所以 View 间距取 8。
        sourceRow.addView(chev, LinearLayout.LayoutParams(20, 20).apply { leftMargin = 8 })
        musicStateText = Ui.text(context, musicState, COMBINED_SOURCE_SIZE, cardWeakText)
        sourceRow.addView(
            musicStateText,
            LinearLayout.LayoutParams(0, -2, 1f).apply { gravity = Gravity.END or Gravity.CENTER_VERTICAL },
        )
        sourceRow.isClickable = true
        // 来源行是一整行可点的区域 —— 按行级幅度轻轻一沉。
        Motion.press(sourceRow, MotionSpec.PRESS_SCALE_ROW)
        sourceRow.setOnClickListener {
            // 排障场景：「导航页音乐卡来源行点了没弹菜单」。
            Logx.event(LogTags.NAV_UI, "导航页音乐卡来源行点击", "source" to sourceLabel)
            onSource?.invoke()
        }
        combinedCard.addView(sourceRow, absIn(pad, COMBINED_SOURCE_TOP, -1, 24, pad))
        sourceRowView = sourceRow

        // —— 封面 + 歌名 / 歌手 ——
        // **画板实测封面 140×140、圆角 28 @ (1323,137)**（相对卡顶 67 → y70）。
        // 实机原来是 96×96 —— 画板上封面撑起了右卡上半整块重心，尺寸差一倍就完全不对了。
        val art = com.cardesk.launcher.ui.widget.AlbumArtView(context).apply {
            cornerRadius = COMBINED_ART_RADIUS
            placeholderColor = cardPal.withAlpha(cardPal.text, 0x14)
            // 第148轮：**点封面图 = 切换「歌曲图片当卡片背景」**。
            //
            // 这是用户拿到这个功能的主入口 —— 音乐卡上就这一张图，
            // 点它就能开关，比去设置页里找开关顺手得多。
            contentDescription = "歌曲图片，点击切换封面背景"
            // 封面图是**图片**类按钮 —— 用图标档的按压幅度，
            // 让「这是一张可以点的图」更明确。
            Motion.press(this, MotionSpec.PRESS_SCALE_ICON)
            setOnClickListener { toggleCoverBackdrop() }
        }
        albumArtView = art
        combinedCard.addView(
            art,
            abs(pad, COMBINED_ART_TOP, COMBINED_ART_SIZE, COMBINED_ART_SIZE),
        )

        // 封面里的**双联音符**：画板在没有封面时，占位块正中画一个描边音符
        // （墨迹 37×44，色 = textFaint）。AlbumArtView 的占位只铺一块底色，
        // 所以这一层由本页叠上去 —— 有封面图时整层隐藏（[artwork] 的 setter 负责）。
        coverNote = NavCoverNoteView(context, cardPal.textFaint)
        combinedCard.addView(
            coverNote,
            abs(pad, COMBINED_ART_TOP, COMBINED_ART_SIZE, COMBINED_ART_SIZE),
        )
        coverNote.visibility = if (artwork == null) View.VISIBLE else View.GONE

        //
        // `maxLines="1"` + `ellipsize="end"` —— 长歌名 / 长歌手名截断成省略号。
        titleText = Ui.text(context, trackTitle, COMBINED_TITLE_SIZE, cardPal.text, bold = true).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        combinedCard.addView(titleText, absIn(COMBINED_META_X, COMBINED_TITLE_TOP, -1, 48, pad))
        // 画板：副标题是 **textFaint**（比来源行那档 textDim 更弱），不是 textDim。
        artistText = Ui.text(context, trackArtist, COMBINED_ARTIST_SIZE, cardPal.textFaint).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        combinedCard.addView(
            artistText,
            absIn(COMBINED_META_X, COMBINED_ARTIST_TOP, -1, COMBINED_ARTIST_H, pad),
        )

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
        combinedCard.addView(lyricSpacer, absIn(pad, COMBINED_LYRIC_TOP, -1, LYRIC_BLOCK_H, pad))

        // —— 进度条：高 10 的胶囊轨道 + 强调色填充（画板：全圆角 r=5，宽 530 = 卡宽 − 27 − 28）——
        progressHost = FrameLayout(context)
        progressHost.addView(
            View(context).apply { background = Skin.solid(cardPal.surfaceStrong, COMBINED_PROGRESS_H / 2f) },
            LayoutParams(LayoutParams.MATCH_PARENT, COMBINED_PROGRESS_H, Gravity.CENTER_VERTICAL),
        )
        progressFill = View(context).apply {
            background = Skin.solid(cardAccent, COMBINED_PROGRESS_H / 2f)
        }
        progressHost.addView(
            progressFill,
            LayoutParams(0, COMBINED_PROGRESS_H, Gravity.CENTER_VERTICAL),
        )
        combinedCard.addView(
            progressHost,
            absIn(pad, COMBINED_PROGRESS_TOP, -1, COMBINED_PROGRESS_H, pad),
        )

        // —— 时间行 ——
        // 画板：14px、textFaint（日间是 (140,153,166)，比时间行旧版的 textDim 更浅）
        val timeRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        currentTimeText = Ui.text(context, positionText, COMBINED_TIME_SIZE, cardPal.textFaint)
        totalTimeText = Ui.text(context, durationText, COMBINED_TIME_SIZE, cardPal.textFaint)
        timeRow.addView(currentTimeText, LinearLayout.LayoutParams(0, -2, 1f))
        timeRow.addView(totalTimeText, LinearLayout.LayoutParams(-2, -2))
        combinedCard.addView(
            timeRow,
            absIn(pad, COMBINED_TIME_TOP, -1, COMBINED_TIME_H, pad),
        )

        // —— 控制键：上一曲 / **白色实心播放圆** / 下一曲 ——
        //
        // 画板实测：播放键是直径 76 的实心圆（日间纯白带柔阴影，夜间是玻璃圆 + 1px 描边），
        // 圆里是**描边三角形**（16×19，笔杆 3）而不是实心「▶」；两个 chevron 的墨迹是 9×16。
        val controls = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        combinedControls = controls
        val prevBtn = iconButton("‹", COMBINED_CHEVRON_SIZE)
        prevButton = prevBtn
        prevBtn.setOnClickListener {
            // 排障场景：「导航页上一曲/下一曲点了不动」。
            Logx.event(LogTags.NAV_UI, "导航页音乐卡上一曲", "title" to trackTitle)
            onPrev?.invoke()
        }
        controls.addView(
            prevBtn,
            LinearLayout.LayoutParams(COMBINED_CHEVRON_W, COMBINED_CHEVRON_H).apply {
                rightMargin = COMBINED_CHEVRON_GAP
            },
        )
        // 第240轮：播放键从「文字符号 ▶ / ❚❚」改成**自绘控件** ——
        // 画板要求的是「实心圆 + 描边三角形」，文字符号的三角形是实心的、也没有圆底。
        val playBtn = NavPlayButtonView(context, cardPal).apply {
            isClickable = true
            contentDescription = "播放 / 暂停"
            Motion.press(this, MotionSpec.PRESS_SCALE_ICON)
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
        controls.addView(
            playBtn,
            LinearLayout.LayoutParams(COMBINED_CONTROLS_H, COMBINED_CONTROLS_H),
        )
        val nextBtn = iconButton("›", COMBINED_CHEVRON_SIZE)
        nextButton = nextBtn
        nextBtn.setOnClickListener {
            Logx.event(LogTags.NAV_UI, "导航页音乐卡下一曲", "title" to trackTitle)
            onNext?.invoke()
        }
        controls.addView(
            nextBtn,
            LinearLayout.LayoutParams(COMBINED_CHEVRON_W, COMBINED_CHEVRON_H).apply {
                leftMargin = COMBINED_CHEVRON_GAP
            },
        )
        combinedCard.addView(
            controls,
            abs(0, COMBINED_CONTROLS_TOP, -1, COMBINED_CONTROLS_H),
        )
        // —— 播放态：播放键自己画（三角 / 双竖条）——
        playBtn.setPlaying(playing)

        // —— 收藏 / 播放模式（第一九五轮）——
        //
        // 放在控制键行的**左右两侧**：控制键是**居中**的（248px 宽），
        // 卡片宽约 520 → 左右各空出约 130px，正好放下这两个按钮。
        //
        // **不支持的播放器直接隐藏**（[applyActionVisibility]）——
        // 亮一个「点了没反应」的按钮比没有更糟。
        //
        // 第 195 轮把文字符号换成了**矢量图标**（[MediaActionIcons]）：
        // 收藏用心形，播放模式用 repeat / repeat_one / shuffle 标准符号。
        // 收藏 / 播放模式与 76 高的播放圆**垂直居中对齐**（外框 56 高 → 上下各让 10）。
        val actionTop = COMBINED_CONTROLS_TOP + (COMBINED_CONTROLS_H - ACTION_ICON_FRAME_H) / 2
        val favBtn = ImageView(context).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            // 内容区 40×40（外框 48×56 留出左右 4 / 上下 8）
            //
            // 第 196 轮**放大**：原来是 28×28（padding 10/14），
            // 实体心形在车机屏上偏小、看着不够醒目 → 内容区放到 40×40。
            // 外框尺寸不变（48×56）→ 点击热区不变，版式也不动。
            //
            // 第 207 轮：改成与播放模式按钮**共用** [ACTION_ICON_CONTENT] 等常量，
            // 两个按钮永远一样大。
            setPadding(4, 8, 4, 8)
            isClickable = true
            contentDescription = "收藏"
            Motion.press(this, MotionSpec.PRESS_SCALE_ICON)
            setOnClickListener {
                Logx.event(
                    LogTags.NAV_UI,
                    "导航页音乐卡收藏按钮",
                    "title" to trackTitle,
                    "favorited" to favoriteState.isFavorited,
                )
                onFavoriteToggle?.invoke()
            }
        }
        favoriteBtn = favBtn
        combinedCard.addView(
            favBtn,
            abs(24, actionTop, ACTION_ICON_FRAME_W, ACTION_ICON_FRAME_H),
        )

        val modeBtn = ImageView(context).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            // 第207轮**放大**：原来内容区只有 28×28（padding 12/14），
            // 和旁边已经放到 40×40 的收藏按钮**不一样大** —— 同一个控制键行里
            // 两个图标大小不齐，看着就像「收藏是重点、播放模式是凑数的」。
            // 现在与收藏按钮共用 [ACTION_ICON_CONTENT]（40×40，外框 48×56）。
            setPadding(4, 8, 4, 8)
            isClickable = true
            contentDescription = "播放模式"
            Motion.press(this, MotionSpec.PRESS_SCALE_ICON)
            setOnClickListener {
                Logx.event(
                    LogTags.NAV_UI,
                    "导航页音乐卡播放模式按钮",
                    "title" to trackTitle,
                    "mode" to (playModeState.current?.name ?: "unknown"),
                )
                onPlayModeToggle?.invoke()
            }
        }
        playModeBtn = modeBtn
        combinedCard.addView(
            modeBtn,
            LayoutParams(
                ACTION_ICON_FRAME_W, ACTION_ICON_FRAME_H,
                Gravity.TOP or Gravity.END,
            ).apply {
                topMargin = actionTop
                rightMargin = 24
            },
        )
        // 按钮建好了 → 按当前播放器的能力表决定显不显示
        applyActionVisibility()
        applyFavoriteState()
        applyPlayModeState()

        // 第229轮：这张卡在单画模式下宽度会跟着分隔条变 ——
        // 版式是按设计板 ~520 宽摆的绝对坐标，卡一窄就会「封面挤到歌名、进度条顶到边框」。
        // 所以宽度一变就按档位重新摆一次（见 [applyCombinedWidth]）。
        combinedCard.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            applyCombinedWidth(combinedCard.width)
        }
    }

    /**
     * 空槽位的**设计版占位**：白圆 + 加号 + 一行文案（见 [NavEmptyDiscView]）。
     *
     * 整块做成一个可点的竖直组，点击走 [onPipPickApp] —— 与 PipSurface 占位
     * 原来的 `onClick` 是**同一个出口**（LauncherView.showPipPicker），
     * 所以「点空槽位挑应用」这条交互没变，变的只是长什么样。
     */
    private fun buildEmptySlot(): LinearLayout {
        val group = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            isClickable = true
            // 无障碍：这一块是**一个**按钮，不让读屏把圆和文案拆成两件事。
            isFocusable = true
            contentDescription = "画中画空槽位，点击选择应用"
            setOnClickListener {
                Logx.event(LogTags.NAV_UI, "导航页空槽位点击（挑应用）")
                onPipPickApp?.invoke()
            }
            Motion.press(this, MotionSpec.PRESS_SCALE_ROW)
        }
        group.addView(
            NavEmptyDiscView(context, cardPal),
            LinearLayout.LayoutParams(EMPTY_DISC_SIZE, EMPTY_DISC_SIZE),
        )
        // 文案取自 PipSurface 的占位（见 [syncEmptySlot]），字号/颜色在这里定 ——
        // 画板实测 22px、日间 textDim、夜间 textFaint（[cardWeakText] 正好是这两档）。
        emptyLabel = Ui.text(context, PipSurface.TEXT_PICK_APP, EMPTY_LABEL_SIZE, cardWeakText).apply {
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            maxWidth = EMPTY_LABEL_MAX_W
        }
        group.addView(
            emptyLabel,
            LinearLayout.LayoutParams(-2, EMPTY_LABEL_H).apply { topMargin = EMPTY_LABEL_GAP },
        )
        return group
    }

    /**
     * 对账：**设计版占位**与 PipSurface 自己的占位哪个该出现。
     *
     * ## 为什么要「接管」而不是自己判断空状态
     *
     * 空状态不止一种文案 —— 除了「请选择应用」，还有「未安装: xxx」
     * （应用被卸载了）和「与主画相同，无法同时承载」（选错目标）。
     * 后面这两种是**错误提示**，用新版式盖掉就等于把提示吞了。
     * 所以文案一律取自 pip 的占位本身（[placeholderText]），只在「它确实是
     * 默认那一条、且 pip 没有承载内容」时才换成设计版式；其余情况原样保留。
     *
     * 读不到（反射失败 / 结构变了）→ 什么都不做，宁可少一层装饰，也不能让空槽位变空白。
     */
    private fun syncEmptySlot() {
        val group = emptySlot ?: return
        val placeholder = pipPlaceholder()
        if (placeholder == null) {
            group.visibility = View.GONE
            return
        }
        val text = placeholderText(placeholder)
        // 判据用「不是 GONE」而不是「== VISIBLE」：接管之后我们把它设成 INVISIBLE，
        // 若按 VISIBLE 判，下一次布局就会认为自己不该接管 → 新层收起、
        // 旧层还 INVISIBLE → **整个空槽位变空白**。
        // PipSurface 只在挂载内容时设 GONE，所以「不是 GONE」正好等于「占位态」。
        val placeholderMode = placeholder.visibility != View.GONE
        if (text != null && !pip.hasContent && placeholderMode) {
            if (::emptyLabel.isInitialized && emptyLabel.text != text) emptyLabel.text = text
            group.visibility = View.VISIBLE
            placeholder.visibility = View.INVISIBLE
        } else {
            if (group.visibility != View.GONE) group.visibility = View.GONE
            // 我们是**唯一**会把占位设成 INVISIBLE 的人，所以一旦不再接管就还回去：
            // 否则（例如某次反射失败读到 null）会出现「新层收起 + 旧层不可见」的空白卡。
            // 有内容时不还（否则旧占位会和承载的应用叠在一起）。
            if (!pip.hasContent && placeholder.visibility == View.INVISIBLE) {
                placeholder.visibility = View.VISIBLE
            }
        }
    }

    /**
     * PipSurface 里那个**私有**的占位 View（`PipSurface.PlaceholderView`）。
     *
     * 它是 `private class`：NavPage 既引用不到类型、也改不了它的 onDraw。
     * 这是本页唯一一处越过封装 —— 所以做成「找不到就什么都不做」的软探测：
     * 类名对不上（重命名 / 结构调整）就返回 null，界面退回原来的占位，绝不崩。
     * （release 构建 `isMinifyEnabled = false`，类名不会被混淆，见 app/build.gradle.kts。）
     */
    private fun pipPlaceholder(): View? {
        for (i in 0 until pip.childCount) {
            val child = pip.getChildAt(i) ?: continue
            if (child.javaClass.simpleName.endsWith("PlaceholderView")) return child
        }
        return null
    }

    /** 读占位 View 的文案（`var text: String` → JVM `getText()`）。读不到返回 null。 */
    private fun placeholderText(view: View): String? = try {
        val getter = view.javaClass.getMethod("getText")
        getter.isAccessible = true
        getter.invoke(view) as? String
    } catch (t: Throwable) {
        Logx.d(LogTags.NAV_UI, "画中画占位文案读不到（设计版空槽位不接管）", "err" to t.javaClass.simpleName)
        null
    }

    /** 播放键（`lateinit`，没建好时返回 null）。 */
    private fun playGlyphRef(): NavPlayButtonView? = if (::playGlyph.isInitialized) playGlyph else null

    /** 音乐卡上一次套用的宽度档位（-1 = 还没套过）。 */
    private var combinedTier = -1

    /**
     * 单画模式下，音乐卡按**卡片实际宽度**重排（第229轮）。
     *
     * 三档：
     *
     * | 档 | 宽度 | 版式 |
     * |---|---|---|
     * | 0 | ≥ [COMBINED_NARROW_W] | 画板版式（封面 140、歌名 left 192、收藏/播放模式都在） |
     * | 1 | ≥ [COMBINED_TIGHT_W] | 封面缩到 100、歌名 left 143、**收起收藏/播放模式**（会和居中控制键打架） |
     * | 2 | 更窄 | 隐去封面，歌名从左边距起、占满整行 |
     *
     * 进度条 / 时间行 / 歌词的右边距在创建时就统一成了 [COMBINED_PAD]，
     * 所以任何宽度下都不会贴边。
     */
    private fun applyCombinedWidth(width: Int) {
        if (width <= 0) return
        val tier = when {
            width >= COMBINED_NARROW_W -> 0
            width >= COMBINED_TIGHT_W -> 1
            else -> 2
        }
        if (tier == combinedTier) return
        combinedTier = tier
        val artSize = when (tier) {
            0 -> COMBINED_ART_SIZE
            1 -> COMBINED_ART_TIGHT_SIZE
            else -> 0
        }
        if (artSize > 0) {
            // 封面 + 它上面那层音符必须**同一个 rect**，否则窄档里音符会飘到封面外面。
            val artRect = abs(COMBINED_PAD, COMBINED_ART_TOP, artSize, artSize)
            if (::albumArtView.isInitialized) {
                albumArtView.visibility = View.VISIBLE
                albumArtView.layoutParams = artRect
            }
            if (::coverNote.isInitialized) {
                coverNote.layoutParams = abs(COMBINED_PAD, COMBINED_ART_TOP, artSize, artSize)
                coverNote.visibility = if (artwork == null) View.VISIBLE else View.GONE
            }
        } else {
            if (::albumArtView.isInitialized) albumArtView.visibility = View.GONE
            if (::coverNote.isInitialized) coverNote.visibility = View.GONE
        }
        val metaX = when (tier) {
            0 -> COMBINED_META_X
            1 -> COMBINED_PAD + COMBINED_ART_TIGHT_SIZE + 16
            else -> COMBINED_PAD
        }
        if (::titleText.isInitialized) {
            titleText.layoutParams = absIn(metaX, COMBINED_TITLE_TOP, -1, 48, COMBINED_PAD)
        }
        if (::artistText.isInitialized) {
            artistText.layoutParams =
                absIn(metaX, COMBINED_ARTIST_TOP, -1, COMBINED_ARTIST_H, COMBINED_PAD)
        }
        // 画板版式的控制行总宽 292（40+76+40 + 两个 68 的间距）——
        // 卡宽 320 时内容区只有 266，必然顶到两边被裁，所以紧档要同时收紧间距与键宽。
        val tight = tier >= 2
        val gap = when (tier) {
            0 -> COMBINED_CHEVRON_GAP
            1 -> 30
            else -> 12
        }
        val sideW = if (tight) 32 else COMBINED_CHEVRON_W
        val playW = when (tier) {
            0 -> COMBINED_CONTROLS_H
            1 -> 64
            else -> 64
        }
        prevButton?.let {
            it.layoutParams = LinearLayout.LayoutParams(sideW, COMBINED_CHEVRON_H).apply {
                rightMargin = gap
            }
        }
        playGlyphRef()?.let {
            // 播放键是**圆**，宽高必须相等。
            it.layoutParams = LinearLayout.LayoutParams(playW, playW)
        }
        nextButton?.let {
            it.layoutParams = LinearLayout.LayoutParams(sideW, COMBINED_CHEVRON_H).apply {
                leftMargin = gap
            }
        }
        // 「已暂停」这类状态文案在窄卡里只会被截成半个字，直接藏掉。
        if (::musicStateText.isInitialized) {
            musicStateText.visibility = if (tight) View.GONE else View.VISIBLE
        }
        // 收藏 / 播放模式是「贴左右两边、控制键居中」的版式，卡窄了必然打架。
        applyActionVisibility()
        Logx.d(LogTags.NAV_UI, "音乐卡宽度档位", "width" to width, "tier" to tier)
    }

    /**
     * 收藏 / 播放模式这两个按钮**显不显示**（第一九五轮 → 第一九六轮）。
     *
     * 判据是 [MediaActions.capsOf]：
     *
     * - **已知包**（QQ / 网易云 / 汽水）→ 查能力表；
     * - **未知包**（没逆向过的音乐 App）→ 看运行时探测（[actionProbe]）：
     *   会话发了**心形评分** → 亮收藏；会话**暴露了**播放模式 → 亮播放模式；
     * - 探不到 → **都不亮**（不猜：猜错了会亮一个点了没反应的按钮）。
     */
    private fun applyActionVisibility() {
        val pkg = musicPackage
        val caps = MediaActions.capsOf(pkg, actionProbe)
        // 第229轮：卡片窄的时候这两个按钮会和居中的控制键叠在一起，先让位。
        val roomy = combinedTier <= 0
        favoriteBtn?.visibility = if (roomy && caps.favorite) View.VISIBLE else View.GONE
        playModeBtn?.visibility = if (roomy && caps.playMode) View.VISIBLE else View.GONE
    }

    /**
     * 收藏态 → 收藏按钮长什么样（第一九五轮）。
     *
     * 三态分别长这样：
     *
     * | 状态 | 样子 |
     * |---|---|
     * | 已收藏 | **实心**心形，强调色 |
     * | 未收藏 | **空心**心形，常规弱色 |
     * | 不知道 | **空心**心形，**更弱**的色（别让用户以为「确定没收藏」） |
     *
     * 第 195 轮把 `♡` / `♥` 文字符号换成了矢量心形（[MediaActionIcons]）——
     * 车机字体里的 `♡` 上下不对称，看着像画歪的对勾。
     */
    private fun applyFavoriteState() {
        val btn = favoriteBtn ?: return
        val filled = favoriteState.known && favoriteState.isFavorited
        val color = when {
            filled -> cardAccent
            favoriteState.known -> cardPal.textDim
            else -> cardWeakText
        }
        btn.setImageDrawable(MediaActionIcons.heart(filled, color))
        btn.contentDescription = if (filled) "已收藏" else "收藏"
    }

    /**
     * 播放模式 → 按钮上画哪个符号（第一九五轮）。
     *
     * 显示的是**当前**模式；点一下会切到**下一个**
     * （顺序 → 单曲 → 随机 → 顺序，见 [MediaActions.nextPlayMode]）。
     *
     * | 模式 | 符号 |
     * |---|---|
     * | 顺序播放 | 两个箭头绕一圈（repeat） |
     * | 单曲循环 | 循环箭头 + 中间一个 1（repeat_one） |
     * | 随机播放 | 两条交叉箭头（shuffle） |
     *
     * 状态**不知道**时画「顺序」并用更弱的色 ——
     * 因为此时下一次点击就是切到顺序播放。
     */
    private fun applyPlayModeState() {
        val btn = playModeBtn ?: return
        val mode = playModeState.current
        val color = if (mode == null) cardWeakText else cardPal.textDim
        btn.setImageDrawable(MediaActionIcons.playMode(mode, color))
        btn.contentDescription = "播放模式：" + when (mode) {
            MediaActions.PlayMode.REPEAT_ALL -> "顺序"
            MediaActions.PlayMode.REPEAT_ONE -> "单曲"
            MediaActions.PlayMode.SHUFFLE -> "随机"
            null -> "顺序"
        }
    }

    /** FrameLayout 绝对定位（相对组合卡左上角）。 */
    private fun abs(x: Int, y: Int, w: Int, h: Int): LayoutParams =
        LayoutParams(w, h).apply {
            leftMargin = x
            topMargin = y
        }

    /**
     * 与 [abs] 相同，但补一个**右边距**（第229轮）。
     *
     * 音乐卡里的整行元素原来都是「左 24 + 宽度 MATCH_PARENT」——
     * 右边贴到卡片边缘，卡片一窄进度条就顶到边框上（用户报的「进度条贴边」）。
     */
    private fun absIn(x: Int, y: Int, w: Int, h: Int, right: Int): LayoutParams =
        abs(x, y, w, h).apply {
            rightMargin = right
        }
    /**
     * 字形按钮（上一曲 / 下一曲）。
     *
     * [size] 就是**字号**（设计 px）：画板 chevron 墨迹 9×16，按「墨迹高 ≈ 0.37 字号」
     * 反推字号 43 —— 旧实现把字号再乘 0.7，墨迹只有 11 高，明显比画板单薄。
     */
    private fun iconButton(glyph: String, size: Int): TextView = Ui.text(context, glyph, size.toFloat(), cardPal.text).apply {
        gravity = Gravity.CENTER
        isClickable = true
        // 图标类小按钮 —— 按 [MotionSpec.PRESS_SCALE_ICON] 缩，
        // 小按钮按 0.96 几乎看不出来。
        Motion.press(this, MotionSpec.PRESS_SCALE_ICON)
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
                // 第229轮：拖动期间**不 resize 虚拟屏** —— 卡宽每帧都在变，
                // 每帧 resize 一次会让承载应用每帧重排版重画（实测拖动 10fps）。
                // 这里只是把两路承载面切到「预览」态，松手时再一次性 resize。
                pip.beginDragPreview()
                pipSecondary.beginDragPreview()
                dragSteps++
                pipRatio = ratio
                // 第228轮：**不再逐帧记一条** —— 分隔条自己已经记了
                // 「画中画分隔条拖动比例」（含 from/to/dx），这里再记一条是重复；
                // 而拖动时每帧多一条 logcat，在车机上就是拖动卡顿的一部分。
                this@NavPage.onRatioChanged?.invoke(ratio)
            }
            // 松手落盘
            onRatioCommitted = { ratio ->
                ratioStore.write(dualPip, ratio)
                pipRatio = ratio
                // 等这一帧布局跑完（卡宽已定）再做真实 resize，承载应用只重排一次。
                pip.post { pip.endDragPreview() }
                pipSecondary.post { pipSecondary.endDragPreview() }
                Logx.event(
                    LogTags.NAV_UI,
                    "画中画拖动结束",
                    "steps" to dragSteps,
                    "ratio" to ratio,
                    "dual" to dualPip,
                )
                dragSteps = 0
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
            // 小玻璃按钮 —— 图标档按压幅度。
            Motion.press(this, MotionSpec.PRESS_SCALE_ICON)
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
        secondaryDots.visibility = if (dualPip) View.VISIBLE else View.GONE
    }

    /** 分隔条显隐与手势开关。 */
    private fun applyPipChrome() {
        if (!::pipDivider.isInitialized) return
        pipDivider.visibility = if (pipFullscreen) View.GONE else View.VISIBLE
        pipDivider.gestureEnabled = !pipFullscreen
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        // 占位的可见性由 pip 状态变化驱动（挂载/卸载应用都会 requestLayout），
        // 所以每次布局都对一次账：该显示设计版式就显示、该让位就立刻让位。
        syncEmptySlot()
        placePipDivider()
        // 布局刚跑完才知道进度条有多宽 —— 这里**直接定宽，不播动画**。
        //
        // ## 为什么布局里不能播动画
        //
        // 动画帧里改 `layoutParams.width` 需要重新布局才能生效，
        // 而「布局 → 播动画 → 动画帧 → 改宽度 → 重新布局」会绕成死循环：
        // Choreographer 每一帧都会排一次遍历，每次遍历都进一次布局，
        // 每次布局都重起一段动画 —— 永远停不下来。
        //
        // 所以：**布局里只定宽**，动画交给 [refreshProgress]（不在布局里调用）。
        applyProgressWidth()
    }

    /** 分隔条压在地图卡与右槽之间（:2277-2332）。 */
    private fun placePipDivider() {
        if (width <= 0) return
        val mapWidth = mapCard.width
        // 分隔条卡在「地图卡 ↔ 组合卡」那条缝上：
        // 不换位时地图卡在左 → 缝在地图卡右边；换位后地图卡在右 → 缝在左边。
        //
        // 第228轮（真车实测修的坐标错位）：
        // `mapCard.left/right` 是**相对 navRow** 的坐标，而 `pipDivider` 是
        // **NavPage 的直接子 View** —— 两套坐标系差了一个 `NAV_SIDE_MARGIN`（44px）。
        // 以前直接拿 `mapCard.right` 当屏幕坐标 ⇒ 分隔条被画在 x=1315，
        // 正好压在画中画画面自己的滚动条上 ⇒ **看起来像消失了**。
        // 真车证据：点 x=1359（卡缝真实位置）没反应；点 x=1321 反而命中
        // 日志「画中画分隔条按下 → 单击（切换全屏）」⇒ 控件一直在，只是画歪了 44px。
        val rowLeft = if (::navRow.isInitialized) navRow.left else 0
        val rowTop = if (::navRow.isInitialized) navRow.top else 0
        val edge = if (::navGap.isInitialized && navGap.width > 0) {
            rowLeft + (navGap.left + navGap.right) / 2
        } else {
            rowLeft + PipSides.dividerEdge(mapCard.left, mapCard.right, sidesSwapped)
        }
        pipDivider.x = (edge - pipDivider.width / 2).toFloat()
        pipDivider.y = (rowTop + mapCard.top + (mapCard.height - pipDivider.height) / 2).toFloat()

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

    /**
     * 进度条填充的**当前宽度**（px）。
     *
     * 进度是每秒刷新一次的，硬切宽度的话进度条是「一格一格跳」的；
     * 记一份当前显示的宽度，变化时从它开始**滑**过去，看起来才像在走。
     */
    private var progressWidth = 0f

    /** 刷新进度条填充宽度（0..1）。 */
    fun refreshProgress() {
        if (!::progressFill.isInitialized) return
        val host = progressHost
        val usable = host.width
        if (usable <= 0) return
        val target = (usable * progress).toInt().coerceAtLeast(0).toFloat()
        val from = progressWidth
        if (from == target) return
        // 用 `animateProgress`（按视图记）—— 同一个进度条上再起一段动画时，
        // 上一个会被取消，两个动画抢同一个宽度会抖。
        Motion.animateProgress(host, MotionSpec.VALUE_DURATION) { t ->
            progressWidth = from + (target - from) * t
            applyProgressWidth(progressWidth, request = true)
        }
    }

    /**
     * 把进度条填充宽度设成 [px]。
     *
     * @param request true = 主动请求一次布局，让新的宽度**立刻**生效。
     *
     * ## 为什么这里要显式 `requestLayout()`
     *
     * 宽度是写在 `layoutParams` 上的，**布局时**才被读走。
     * 而 `View.setLayoutParams` 传的是**同一个对象**时会直接返回
     * （`if (params == mLayoutParams) return;`），不会触发重新布局。
     *
     * 所以只改 `lp.width` 是不够的 —— 得再叫一次 `requestLayout()`，
     * 让下一轮布局把新宽度读走。
     *
     * 但**不能在布局过程中调**（见 [onLayout] 的说明），
     * 所以布局里那次是 `request = false`：那一轮布局本来就会读走宽度。
     */
    private fun applyProgressWidth(px: Float = progressWidth, request: Boolean = false) {
        val lp = progressFill.layoutParams ?: return
        val w = px.toInt()
        if (lp.width == w) return
        lp.width = w
        progressFill.layoutParams = lp
        if (request) progressFill.requestLayout()
    }


    companion object {
        /**
         * 收藏 / 播放模式两个按钮的**图标内容区边长**（设计 px）。
         *
         * 第207轮：播放模式按钮原来只有 28×28，收藏按钮已经是 40×40 ——
         * 同一个控制键行里两个图标大小不齐。现在**两者共用这一个常量**，
         * 保证「一样大」这件事不会再被单独改歪（有单测盯着）。
         */
        const val ACTION_ICON_CONTENT = 40

        /**
         * 收藏 / 播放模式两个按钮的**外框宽**（设计 px）。
         *
         * 外框比内容区大一圈（上下各留 8），点击热区在这个外框里。
         */
        const val ACTION_ICON_FRAME_W = 48

        /** 收藏 / 播放模式两个按钮的**外框高**（设计 px）。 */
        const val ACTION_ICON_FRAME_H = 56

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
         * **画板 02-nav 实测**（PIL 逐像素扫 `02-nav-day.png`，row 400）：
         * 左卡左缘 **x40**、右卡右缘 **x1880** —— 左右留白都是 40，不是第四十九轮记的 44。
         * 容器宽度 = 1920 − 40 × 2 = **1840**。
         *
         * 为什么不沿用 44：画板是唯一事实源，44 那版是照旧设计稿抄的，
         * 实机截图里卡片整体比画板**窄了 8px、右缘早了 4px**，卡缝也对不上。
         */
        const val NAV_SIDE_MARGIN = 32

        /**
         * 「画中画分屏比例变化」这条逐帧日志的最小间隔（毫秒，第228轮）。
         *
         * 拖分隔条时每帧都会写一条 logcat —— 车机上的 logcat 本身很吵，
         * 这条日志就是拖动卡顿的一部分。起始 / 结束的关键信息另有落盘日志，
         * 这里只留稀疏采样。
         */
        const val RATIO_LOG_THROTTLE_MS = 400L

        /**
         * 地图卡 ↔ 组合卡之间的缝（设计 px）。
         *
         * **画板实测**：左卡右缘 x1255、右卡左缘 x1296 → 缝 **40**。
         * 原值 20 是旧设计稿的缝，实机上两张卡贴得太近，右卡的玻璃底透出左卡的画面。
         */
        const val NAV_COLUMN_GAP = 38

        /** 双画卡缝（画板 09-pip：左 903 / 右 909 / 缝 24）。 */
        const val NAV_COLUMN_GAP_DUAL = 38

        /** 音乐卡宽度 ≥ 这个值用设计板版式（第229轮）。 */
        const val COMBINED_NARROW_W = 460

        /** 音乐卡宽度 ≥ 这个值收紧版式（封面缩到 100、收起左右两个图标按钮）；更窄则隐去封面。 */
        const val COMBINED_TIGHT_W = 320

        /**
         * 导航页内容顶部（状态栏显示时，设计 px）。
         *
         * 第四十九轮按设计板 `s02-nav`：两张卡顶边都在 **110**（状态栏那一行之下留 20）。
         *
         * 第五十二轮：状态栏整体缩到 2/3（行高 64 → 43），内容起点随之上移
         * **110 → 73**（= 110 × 2/3），底边仍贴 Dock 上沿 940，卡片相应长高。
         *
         * **本轮（对齐画板 02-nav）**：画板实测两张卡的顶边在 **y67**
         * （实机截图是 y72 —— NavPage 自身 topMargin 73 时卡顶落在 72），
         * 所以基线再上移 5 → **68**。卡顶、卡高、卡底三处只有同时对上，
         * 右卡内部那一列绝对坐标（封面 140 / 进度条 / 控制行）才落在画板的位置上。
         */
        const val NAV_CONTENT_TOP = 68

        /**
         * 导航行底边与 Dock 上沿之间的空隙（设计 px）。
         *
         * **画板实测**：卡底 y878、Dock 顶边 y968 → 空隙 **90**。
         *
         * 为什么这里不能直接把 [NAV_BOTTOM_GAP] 改成 90：
         * `app/src/test/java/com/cardesk/launcher/core/dock/PipDockInsetTest.kt`
         * 用 `DOCK_TOP_Y - NavPage.NAV_BOTTOM_GAP` 硬编码断言了 **940**，
         * 而本轮只允许改这一个文件。所以老常量保留（单测继续绿），
         * 实际行底距改用本常量 —— 等单测那边改成读本常量后再合并成一个。
         */
        const val NAV_ROW_BOTTOM_GAP = 17

        /** 导航页内容底部与 Dock 上沿之间的空隙（设计 px）。 */
        const val NAV_BOTTOM_GAP = 28

        /**
         * 组合卡内容左右留白（设计 px）。
         *
         * **画板 02-nav 实测**：右卡左缘 x1296、内容左缘 x1323 → 左内边 **27**；
         * 进度条右端 x1852、卡右缘 x1880 → 右内边 **28**。取 27（两侧共用，
         * 1px 的差在画板上是卡片高光描边蹭掉的，不值得为它拆两个常量）。
         */
        const val COMBINED_PAD = 27

        /**
         * 组合卡：来源行顶部偏移（行高 = [SOURCE_ICON_SIZE]）。
         *
         * 画板实测来源行墨迹 y97..112（相对卡顶 67 → 30..45）。16px 文本在 24 高的行里
         * 垂直居中时墨迹顶 = 行顶 + 2.6，反推行顶 = **26**。
         */
        const val COMBINED_SOURCE_TOP = 26

        /**
         * 来源行**图标**的边长（设计 px，第138轮）。
         *
         * 原来这里是一个 10px 的品牌色小圆点；换成真 App 图标后要画得清楚一点，
         * 取 **24**（与这一行 16px 的来源文案同一量级，不喧宾夺主）。
         */
        const val SOURCE_ICON_SIZE = 24

        /**
         * 来源行文案字号（设计 px）。
         *
         * 画板实测「通用音乐」墨迹 x1352..1413 = **62 宽 / 4 字 = 15.5px 一字**
         * → 字号 16。实机是 20（墨迹大了 25%），一行文案就顶到下拉箭头上了。
         */
        const val COMBINED_SOURCE_SIZE = 16f

        /**
         * 组合卡：封面左/上边缘与边长（设计 px）。
         *
         * 画板实测封面块 **140×140 @ (1323,137)**（相对卡顶 67 → y70），
         * 圆角按边角像素反解约 **28**。实机是 96×96 —— 画板上封面占掉了
         * 「来源行之下、歌名之前」的整块高度，这是右卡视觉重心的一半。
         */
        const val COMBINED_ART_TOP = 70
        const val COMBINED_ART_SIZE = 140
        const val COMBINED_ART_RADIUS = 28f

        /** 组合卡：窄档（封面缩一档）时的封面边长（设计 px）。 */
        const val COMBINED_ART_TIGHT_SIZE = 100

        /**
         * 组合卡：歌名 / 歌手的左边缘（设计 px，相对卡片）。
         *
         * 画板实测歌名墨迹 x1488 → 相对卡左 1296 = **192** = 留白 27 + 封面 140 + 25。
         */
        const val COMBINED_META_X = 192

        /**
         * 组合卡：歌名顶部 + 字号。
         *
         * 画板实测「暂无播放」墨迹 **x1488..1637（150 宽）× y176..211（36 高）**：
         * 4 个汉字 150 宽 → 一字 37.5 → **38px 粗体**（用户量的是 34，按像素不是）。
         * 38px 汉字的墨迹顶在 View 顶 +2，所以 View 顶 = 109 − 2 = **107**。
         */
        const val COMBINED_TITLE_TOP = 107
        const val COMBINED_TITLE_SIZE = 38f

        /**
         * 组合卡：歌手顶部 + 字号 + 行高。
         *
         * 画板实测副标题「通用音乐」墨迹 **x1488..1540（53 宽）× y232..244（12 高）**
         * → 字号 ≈ 13.4，取 **14**（墨迹 55 宽，差 2px）；墨迹顶 = View 顶 + 0.7 → **164**。
         */
        const val COMBINED_ARTIST_TOP = 164
        const val COMBINED_ARTIST_SIZE = 14f
        const val COMBINED_ARTIST_H = 22

        /** 组合卡：歌词块顶部。 */
        const val COMBINED_LYRIC_TOP = 318

        /**
         * 组合卡：进度条顶部 + 轨道高。
         *
         * 画板实测轨道 **x1323..1852（530 宽）× y710..719**（相对卡顶 → 643..652）：
         * **高 10、全圆角（r=5）**。实机是「高 8 + 贴到卡底」，画板上它与卡底留了 159。
         */
        const val COMBINED_PROGRESS_TOP = 643
        const val COMBINED_PROGRESS_H = 10

        /**
         * 组合卡：时间行顶部 + 字号 + 行高。
         *
         * 画板实测两组「0:00」墨迹 y733..742（高 10）→ 数字墨迹高 ≈ 0.71 字号 → 字号 **14**；
         * 数字墨迹顶 = View 顶 + 0.22 字号 → View 顶 = 666 − 3 = **663**。
         */
        const val COMBINED_TIME_TOP = 663
        const val COMBINED_TIME_SIZE = 14f
        const val COMBINED_TIME_H = 20

        /**
         * 组合卡：控制行顶部 + 行高。
         *
         * 画板实测**播放键是白色实心圆 直径 76**（x1550..1625 / y768..843，圆心 1587.5,805.5）
         * 相对卡顶 67 → 圆顶 **701**；控制行就按圆高 76 建，上一曲 / 下一曲在行内垂直居中。
         */
        const val COMBINED_CONTROLS_TOP = 701
        const val COMBINED_CONTROLS_H = 76

        /**
         * 上一曲 / 下一曲的「‹」「›」字号（设计 px）。
         *
         * 画板实测 chevron 墨迹 **9×16**；实机 35px 字号的墨迹是 7×13
         * → 要 16 高得到 **43px**（「‹」的墨迹高 ≈ 0.37 字号）。
         *
         * 注意这是**字号**本身：[iconButton] 现在直接拿它当 textSize，
         * 旧实现乘过 0.7（那是按「字形比框小一圈」估的，实测偏小）。
         */
        const val COMBINED_CHEVRON_SIZE = 43

        /** 上一曲 / 下一曲的点击框（设计 px，宽 × 高；高 < 控制行高 → 行内垂直居中）。 */
        const val COMBINED_CHEVRON_W = 40
        const val COMBINED_CHEVRON_H = 56

        /**
         * chevron 与播放圆之间的间距（设计 px）。
         *
         * 画板实测两个 chevron 的墨迹中心在 x1465 / x1710，离播放圆圆心（1587.5）
         * 各 **±124**；点击框 40 宽 → 间距 = 124 − 76/2 − 40/2 = **68**。
         */
        const val COMBINED_CHEVRON_GAP = 68

        // —— 空槽位（画中画没有应用时）的占位版式。画板实测见各自的注释 ——

        /**
         * 空槽位中央的**实心圆直径**（设计 px）。
         *
         * 画板实测白圆 bbox **x582..713 / y383..514 → 直径 132**，圆心落在卡的水平中心；
         * 「圆 + 加号 + 文案」这一组整体**垂直居中**于卡片（383..557 的中心 470 ≈ 卡中心 472）。
         */
        const val EMPTY_DISC_SIZE = 132

        /** 空槽位「+」的横 / 竖臂半长（设计 px）——画板墨迹 32×34，笔杆 5。 */
        const val EMPTY_PLUS_ARM_X = 16f
        const val EMPTY_PLUS_ARM_Y = 17f
        const val EMPTY_PLUS_STROKE = 5f

        /**
         * 空槽位文案「请选择应用」的字号 / 行高 / 与圆的间距。
         *
         * 画板实测墨迹 **x594..700（107 宽）× y538..557（20 高）**：5 个汉字 107 宽
         * → 一字 21.4 → **22px**（实机是 19px，明显偏小）。圆底 514 → 墨迹顶 538 = 23.5，
         * 22px 文本在 28 高的 View 里居中时墨迹顶 = View 顶 + 3.5 → 间距 **20**。
         */
        const val EMPTY_LABEL_SIZE = 22f
        const val EMPTY_LABEL_H = 28
        const val EMPTY_LABEL_GAP = 20

        /** 空槽位文案的最大宽度（设计 px）——「未安装: <包名>」这类长文案要能截断，不顶出卡片。 */
        const val EMPTY_LABEL_MAX_W = 460

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
         * 保持 **0**：状态栏是**浮层**（盖在页面之上），不是把内容往下推的那种，
         * 顶部留白只会让画面凭空矮一截、上面多一条空带。
         */
        const val PIP_CONTENT_INSET_TOP = 0

        /**
         * 画中画内容区底部留白（设计 px）。
         *
         * ## 保持 0 —— 底部**本来就不需要**留白（第159轮实测订正）
         *
         * 底部 Dock 栏（铺满屏宽×112、贴底）确实浮在页面之上，
         * 乍看会以为画中画内容会被压住。但**导航页整块内容已经被压到 Dock 顶边之上了**：
         *
         * - `LauncherView` 把导航页的高度设成 `DOCK_TOP_Y - navTop`
         *   （导航页底边 = Dock 顶边 968）；
         * - 导航行自己还有 `bottomMargin = NAV_BOTTOM_GAP`（28）。
         *
         * 于是画中画卡（地图卡）的底边 = 968 − 28 = **940**，
         * 正好停在 Dock 顶边 968 **上方 28px** —— 内容**从来没有被挡过**。
         *
         * ## 第159轮那次「修复」为什么被撤掉
         *
         * 当时误以为内容底边是 1052、会被压 84px，于是把这里改成 112。
         * 实际效果是**反向破坏**：承载内容不再铺满画中画卡，
         * 卡片底部反而空出一条 112px 的带子（实测：占位玻璃块底边从 939 缩到 828）。
         *
         * 教训：算几何要先确认**每一层**的实际高度，别只看当前这一层的 margin。
         */
        const val PIP_CONTENT_INSET_BOTTOM = 0
    }
}

/**
 * 空槽位的**圆盘 + 加号**（[NavPage] 私有；绘制版式按画板 02-nav 实测还原）。
 *
 * 画板实测（1920×1080 设计坐标，圆的直径 132、圆心就是卡片中心 x647.5）：
 *
 * - 日间：圆是近白实心 (252,253,254)，加号墨迹 32×34、色 (140,153,166)=textFaint；
 * - 夜间：**没有圆底**（圆心区域就是卡面色 (23,29,35)），只有加号墨迹
 *   (107,120,133)=textFaint。
 *
 * 所以日间填 [Palette.surfaceStrong]（合成后≈252），夜间不填 —— 直接用调色板判断，
 * 不写死日间色（夜间卡是 0xA8161B22 玻璃，白圆在上面会「亮一块」）。
 */
private class NavEmptyDiscView(
    context: Context,
    private val pal: Palette,
) : View(context) {

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = NavPage.EMPTY_PLUS_STROKE
        strokeCap = Paint.Cap.ROUND
    }

    init {
        isClickable = false // 点击由父组（圆 + 文案）统一处理
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val r = minOf(width, height) / 2f
        if (!pal.night) {
            fill.color = pal.surfaceStrong
            canvas.drawCircle(cx, cy, r, fill)
        }
        // 圆边那一圈：画板夜间实测 (51,56,61) —— 正好是 [Palette.stroke]（白 12%）
        // 叠在卡面 (24,31,39) 上的合成值；日间是 (254,254,254)，同样对得上（白 35% 叠近白）。
        ink.color = pal.stroke
        ink.strokeWidth = 2f
        canvas.drawCircle(cx, cy, r - 1f, ink)
        ink.strokeWidth = NavPage.EMPTY_PLUS_STROKE
        ink.color = pal.textFaint
        canvas.drawLine(
            cx - NavPage.EMPTY_PLUS_ARM_X, cy,
            cx + NavPage.EMPTY_PLUS_ARM_X, cy,
            ink,
        )
        canvas.drawLine(
            cx, cy - NavPage.EMPTY_PLUS_ARM_Y,
            cx, cy + NavPage.EMPTY_PLUS_ARM_Y,
            ink,
        )
    }
}

/**
 * 封面占位块里的**双联描边音符**（画板：没有封面图时占位块正中一个音符）。
 *
 * 画板实测（以封面块 140×140 为参照，墨迹 37 宽 × 44 高）：
 * 左符头中心 (56.5,84.5)、右符头 (77,80.5)，符头外径 16、描边 3.5；
 * 左符干 x62.5、y55..78，右符干 x82.5、y50..74，杆宽 4；
 * 符梁是连接两杆顶端的一条 ≈5px 粗斜带。
 *
 * 尺寸按宽度等比缩放（`w/140`），这样紧档封面缩到 100 时音符跟着缩，不会溢出。
 */
private class NavCoverNoteView(context: Context, private val tint: Int) : View(context) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val stem = Path()
    private val head = Path()

    init {
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        isClickable = false // 点击归封面（切换封面背景）
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return
        val k = minOf(width, height) / REFERENCE
        // 下画坐标就是**画板 140 盒坐标系**（墨迹 x57..94 / y50..94），
        // 所以这里只把 140 盒摆到 View 中心，不再额外平移 ——
        // 画板里那个「偏右下 5.5 / 2」本身就是双联音符的非对称造型，不是居中偏置。
        val dx = width / 2f - 70f * k
        val dy = height / 2f - 70f * k
        stem.reset()
        head.reset()

        // 符干 + 符梁：一条折线画完（两杆 + 顶端斜带），跟着封面一起缩放。
        stem.moveTo(dx + 62.5f * k, dy + 78f * k)
        stem.lineTo(dx + 62.5f * k, dy + 55.5f * k)
        stem.lineTo(dx + 82.5f * k, dy + 50.5f * k)
        stem.lineTo(dx + 82.5f * k, dy + 74f * k)
        paint.color = tint
        paint.strokeWidth = 4f * k
        canvas.drawPath(stem, paint)
        // 符梁更粗（≈5px），单独压一条，避免把杆也画粗。
        paint.strokeWidth = 5f * k
        canvas.drawLine(
            dx + 62.5f * k, dy + 55.5f * k,
            dx + 82.5f * k, dy + 50.5f * k,
            paint,
        )

        // 两个空心符头：外径 16 ⇒ 半径 6.25 + 描边 3.5。
        paint.strokeWidth = 3.5f * k
        head.addCircle(dx + 56.5f * k, dy + 84.5f * k, 6.25f * k, Path.Direction.CW)
        head.addCircle(dx + 77f * k, dy + 80.5f * k, 6.25f * k, Path.Direction.CW)
        canvas.drawPath(head, paint)
    }

    private companion object {
        /** 图标设计坐标系边长（与画板封面 140 对齐）。 */
        const val REFERENCE = 140f
    }
}

/**
 * 播放键：**实心圆 + 内嵌描边三角（暂停时两条竖条）**。
 *
 * 画板实测：圆直径 76（日间纯白带柔阴影；夜间是玻璃圆 + 1px stroke），
 * 圆内的播放符号是**描边**三角形（墨迹 16×19、笔杆 3、左边是一条竖边），
 * 不是字体里的实心「▶」—— 用字形怎么做都对不上，所以自绘。
 */
private class NavPlayButtonView(context: Context, private val pal: Palette) : View(context) {

    private var playing = false
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 3f
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val glyph = Path()

    init {
        // 圆底用 surfaceStrong：日间合成后≈白（画板 255,255,255），
        // 夜间合成后 (27,33,42) ≈ 画板实测 (23,30,38)。
        // 描边：画板夜间那颗圆有一圈 1px 亮边（实测 (45,51,59)）——
        // 用 [Palette.stroke] 合成后正好是这一档；日间这个 35% 白描边在白圆上不可见。
        background = Skin.oval(pal.surfaceStrong).apply { setStroke(2, pal.stroke) }
        // 画板的圆有柔阴影（日间白圆压出一点浮起感），靠 elevation 让系统去画。
        elevation = 8f
        isClickable = true
    }

    fun setPlaying(value: Boolean) {
        if (playing == value) return
        playing = value
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return
        val cx = width / 2f
        val cy = height / 2f
        // 必须用浮点除：紧档播放圆是 64，整数除会得到 0，整个字形缩没。
        val k = minOf(width, height).toFloat() / NavPage.COMBINED_CONTROLS_H
        glyph.reset()
        paint.color = pal.text
        if (playing) {
            // 暂停：两条 6×19 的实心竖条，间距 6。
            paint.style = Paint.Style.FILL
            val w = 6f * k
            val h = 19f * k
            val gap = 3f * k
            canvas.drawRoundRect(
                cx - gap - w, cy - h / 2f, cx - gap, cy + h / 2f, 2f * k, 2f * k, paint,
            )
            canvas.drawRoundRect(
                cx + gap, cy - h / 2f, cx + gap + w, cy + h / 2f, 2f * k, 2f * k, paint,
            )
        } else {
            // 播放：描边三角（左竖边 + 两个斜边），墨迹 16×19。
            //
            // 画板里三角的墨迹中心在 x1589.5、圆心 1587.5 —— **偏右 2px**。
            // 三角的重心天生偏左，右移这一点在视觉上才「居中」，照画板抄。
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 3f * k
            val ox = 2f * k
            glyph.moveTo(cx - 6.5f * k + ox, cy - 8f * k)
            glyph.lineTo(cx - 6.5f * k + ox, cy + 8f * k)
            glyph.lineTo(cx + 6.5f * k + ox, cy)
            glyph.close()
            canvas.drawPath(glyph, paint)
        }
    }
}
