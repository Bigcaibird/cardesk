package com.cardesk.launcher.core.media

import java.util.Locale

/**
 * 一行歌词的**锚点**（设计 px）。
 *
 * ## 为什么要有锚点
 *
 * 居中块布局（三行）里，每行的位置是「算出来」的：
 * 先按字号推基线，再把整块平移到中心。
 *
 * 但 KTV 式两行要的是**错落阶梯** —— 当前句贴左边距，
 * 下一句往右下错开。每行的位置是**定死的**，算不出来。
 *
 * 所以给布局加一份「每行的锚点」：
 * [leftX] = 文字**左边缘** x（这一版是左对齐，不是居中），
 * [baselineY] = 文字基线 y。
 *
 * @param leftX 文字左边缘 x（设计 px）
 * @param baselineY 文字基线 y（设计 px）
 */
data class LyricAnchor(val leftX: Float, val baselineY: Float)

// —— KTV 两行的锚点（设计坐标 1920×1080）——
//
// 用户画的示意图：当前句在**左上**、下一句在**右下**，两行一样大、都从左边缘起。
// 下面的具体数值是按实机效果定的（示意图只表示「错落成阶梯」这个关系）。
//
// 第120轮（用户：「整体左移一点，下面一句的区域尾部对齐胎压卡片的右缘」）：
//   - 两句**一起**左移 34px（当前句 44→10、下一句 776→742），行距不变；
//   - 区域尾部从 1488（行程卡左缘）收到 **1453**（胎压卡右缘）。
//
// 关于当前句的 x=10：这是**用户指定的值**（在几个候选里选的「两条都挪」）。
// 它比内容左边距（44）更靠左 —— 歌词是桌面里唯一贴着内容左缘往左走的元素，
// 跟引导卡（x=44）、底排卡片（x=44）故意错开一条竖线。
//
// 注意：所有坐标都是**设计坐标系**（1920×1080）里的值，
// 实际车机屏不是 1920×1080 时，整层内容会等比缩放（`Design.scaleFor`），
// 这里存的还是缩放前的设计值。
const val KTV_CURRENT_LEFT_X = 10f
const val KTV_CURRENT_BASELINE_Y = 552f
const val KTV_NEXT_LEFT_X = 742f
const val KTV_NEXT_BASELINE_Y = 656f

/**
 * 歌词的**字号档**（第117轮）。
 *
 * ## 为什么单独一个对象
 *
 * `LyricLayout` 的枚举项在构造时要用到这些档位，
 * 而 Kotlin 的**伴生对象在枚举项之后才初始化** ——
 * 所以档位不能放在 `LyricLayout` 的伴生对象里（编译期就报
 * "Companion object of enum class is uninitialized here"）。
 * 放在文件级的独立对象里就没这个问题（`const` 是编译期常量，直接内联）。
 */
object LyricSizeTier {
    /** 远档（最外围那行）。 */
    const val FAR = 0

    /** 近档（贴着当前句的那行）。 */
    const val NEAR = 1

    /** 当前句（视觉焦点）。 */
    const val MAIN = 2
}

/**
 * 首页中央大字歌词的**布局**（第117轮）。
 *
 * ## 为什么把它单独拎出来
 *
 * 用户要两种排法：
 *
 * 1. **三行**（默认）：上 1 句 / 当前句 / 下 1 句；
 * 2. **KTV 式两行**：当前句在上、下一句在下 —— 像卡拉OK那样，
 *    唱过的那句往上走掉，屏幕上永远是「正在唱的 + 马上要唱的」这一对。
 *    **两行一样大**（用户要求：不靠字号区分前后句）。
 *
 * 这两种的差别不只是「有几行」：
 * **行序、当前行在哪、每行用哪档字号**全都不一样。
 * 如果这些散落在 `ScenePage` 的绘制代码里，加第三种布局就得把
 * `drawCenterLyric` 再改一遍 —— 而且很容易漏掉某处。
 *
 * 所以这里把「一个布局长什么样」收成一份**纯 Kotlin**的清单：
 * 界面只管按清单画，判定逻辑可以 JVM 单测直接覆盖。
 *
 * ## 行序（slots）
 *
 * [slots] 是**行序**，从最上一行到最下一行，
 * 每项是「这一行相对当前句的偏移」：
 * `-1` = 上一句、`0` = 当前句、`1` = 下一句。
 *
 * 三行布局 = `[-1, 0, 1]`（当前句在中间），KTV 两行布局 = `[0, 1]`（当前句在最上）。
 * 当前行下标由布局自己给，绘制代码不写死。
 *
 * 字号档同理：**当前行不一定是最大档**
 * （三行是「近 / 当前 / 近」，KTV 两行是「近 / 近」—— 两句一样大）。
 */
enum class LyricLayout(
    /** 行数。 */
    val rows: Int,
    /** 当前行在行序里的下标（行序 = 最上 → 最下）。 */
    val currentIndex: Int,
    /** 行序：每行相对当前句的偏移（负 = 上一句，正 = 下一句）。 */
    val slots: IntArray,
    /** 每行的**字号档**（[LyricSizeTier.FAR] / [LyricSizeTier.NEAR] / [LyricSizeTier.MAIN]）。 */
    val sizeTiers: IntArray,
    /**
     * 行距倍数（相对桌面那个基础行距）。
     *
     * 只对**居中块布局**有意义（三行：当前句上下都贴着衬句，用基础行距就够）。
     *
     * **锚定布局**（KTV 两行）不用它 —— 那里的行距由两个锚点之间的距离
     * 定死了，算出来的行距反而会跟锚点打架。
     */
    val gapScale: Float = 1f,
    /**
     * 当前行要不要**加粗**。
     *
     * ## 为什么这个也要由布局决定
     *
     * 粗体在 40px 的中文字上**看起来比常规字大一号**
     * （笔画更粗 → 视觉上更"大"）。
     * 用户要求 KTV 两行「前后句保持大小一样」——
     * 光把字号调成一样还不够，粗体带来的视觉差也要去掉。
     *
     * 所以 KTV 两行：**同字号 + 同字重**，
     * 当前句只靠**颜色**（青色逐字擦除 + 亮色）和**位置**（在上面）认出来。
     *
     * 三行布局的当前句本来就是唯一的大字号（56px），
     * 加粗跟它的大字号是一致的，就保持粗体。
     */
    val boldCurrent: Boolean = true,
    /**
     * 每一行的**锚点**（按行序，与 [slots] 顺序一致）。
     *
     * - `null` = 走「整块居中」算法（三行布局：基线算出来，再把整块平移到中心）；
     * - 非 `null` = 每行**独立定位**（KTV 两行的错落阶梯：位置定死，不算）。
     *
     * 锚定布局下 [gapScale] 不参与计算（行距已经由锚点之间的距离定死了）。
     */
    val anchors: Array<LyricAnchor>? = null,
) {

    /**
     * **三行**（默认）：上 1 句 / 当前句 / 下 1 句。
     *
     * 字号沿用原来五行里的「近 / 当前 / 近」三档（40 / 56 / 40）——
     * 用户说「大小保持不变」，所以这里只减行数、不动字号。
     */
    THREE(
        rows = 3,
        currentIndex = 1,
        slots = intArrayOf(-1, 0, 1),
        sizeTiers = intArrayOf(LyricSizeTier.NEAR, LyricSizeTier.MAIN, LyricSizeTier.NEAR),
        boldCurrent = true,
    ),

    /**
     * **KTV 式两行**：**当前句在上、下一句在下**。
     *
     * 不显示上一句 —— 唱过的那句直接往上走掉，
     * 屏幕上永远是「正在唱的 + 马上要唱的」这一对。
     *
     * **两行字号一样大**（都是「近档」40px）——
     * 这是用户明确要求的：「前后句保持大小一样，不需要区分前后句大小」。
     *
     * ## 为什么当前行不再用最大字号
     *
     * 原来这版是「当前句 56px + 下一句 40px」，看上去一粗一细。
     * 用户要的是**两句一样大**，所以两行都降到衬句那一档 ——
     * **靠颜色和逐字擦除区分当前句，不靠字号**。
     *
     * 当前句靠**颜色 + 位置**认出来：青色逐字擦除（已唱到的部分是青色）
     * + 它在**上面**那一行。字重也不加粗 ——
     * 40px 的粗体中文字视觉上比常规字大一号，
     * 那样就又变成「上下不一样大」了。
     *
     * ## 位置：错落阶梯（不是居中）
     *
     * 用户画的示意图是「当前句在左上、下一句在右下」，
     * 两行从各自的**左边缘**起排（不是居中）。
     * 所以这一版不用「整块居中」那套算法，
     * 改用 [anchors] 给每行一个固定锚点。
     *
     * 具体坐标见文件顶部的 [KTV_CURRENT_LEFT_X] 等常量。
     */
    KTV(
        rows = 2,
        currentIndex = 0,
        slots = intArrayOf(0, 1),
        sizeTiers = intArrayOf(LyricSizeTier.NEAR, LyricSizeTier.NEAR),
        boldCurrent = false,
        anchors = arrayOf(
            // 行 0 = 当前句（贴左边距，在上面）
            LyricAnchor(KTV_CURRENT_LEFT_X, KTV_CURRENT_BASELINE_Y),
            // 行 1 = 下一句（往右下错开）
            LyricAnchor(KTV_NEXT_LEFT_X, KTV_NEXT_BASELINE_Y),
        ),
    ),
    ;

    /** 持久化用的键（`scene_lyric_layout`）。 */
    val key: String get() = name.lowercase(Locale.US)

    /** 设置页里给用户看的名字。 */
    val label: String get() = when (this) {
        THREE -> "三行"
        KTV -> "KTV 两行"
    }

    /** 一句话说明这个布局长什么样（设置页的提示文案）。 */
    val hint: String get() = when (this) {
        THREE -> "上一句 / 当前句 / 下一句"
        KTV -> "当前句在左上、下一句在右下错开，两句一样大"
    }

    /**
     * 设置页里关于**位置**的那句说明。
     *
     * 三行是「整块居中」—— 调字号整块跟着缩放但中心不动；
     * KTV 两行是「错落阶梯」—— 位置本来就是定死的，调字号不动。
     * 两种布局的这句话不一样，所以由布局自己给。
     */
    val positionHint: String get() = when (this) {
        THREE -> "歌词块始终居中，调大调小都不会往一边偏。"
        KTV -> "两行位置是定死的：当前句贴左边在左上，下一句往右下错开。"
    }

    companion object {
        /**
         * 按持久化的键取布局。
         *
         * 认不出来（老设备、键被手改过、以后加了新布局但 App 是老的）
         * 一律回**三行** —— 那是默认形态，不该因为一个脏键就让歌词整块不显示。
         */
        fun fromKey(key: String?): LyricLayout {
            if (key.isNullOrBlank()) return THREE
            val k = key.trim().lowercase(Locale.US)
            return values().firstOrNull { it.key == k } ?: THREE
        }
    }
}
