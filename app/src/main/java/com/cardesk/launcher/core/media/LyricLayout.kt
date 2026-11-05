package com.cardesk.launcher.core.media

import java.util.Locale

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
     * KTV 式两行是「分开的两块」，行距要更大；
     * 三行布局里当前句上下都贴着衬句，用基础行距就够。
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
     * [gapScale] 比三行大：这两行是**分开**的两块，不该挤在一起。
     */
    KTV(
        rows = 2,
        currentIndex = 0,
        slots = intArrayOf(0, 1),
        sizeTiers = intArrayOf(LyricSizeTier.NEAR, LyricSizeTier.NEAR),
        gapScale = 1.8f,
        boldCurrent = false,
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
        KTV -> "当前句在上、下一句在下，两句一样大"
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
