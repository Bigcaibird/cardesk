package com.cardesk.launcher.core.ambient

/**
 * 氛围灯 V2 的**参数预设**。
 *
 * ## 为什么要有预设
 *
 * V2 的参数有 9 个（attack / decay / 色相 τ / 限速 / 限加速 / 色差上限 /
 * 底光 / 饱和度 / 呼吸幅度），每一项都有合理的取值范围 ——
 * 但让用户在车里逐个拖 9 个滑杆是不现实的。
 *
 * 预设给出 6 个「一键可用」的落点，覆盖几种典型场景：
 *
 * | 预设 | 场景 | 关键取向 |
 * | --- | --- | --- |
 * | [BALANCED] 均衡 | 日常 | 方案文档的默认值 |
 * | [NIGHT] 夜驾柔和 | 夜间、低速 | 变化更慢更弱，不刺眼 |
 * | [CYBER] 赛博高对比 | 演示、性能车 | 变化快、对比强 |
 * | [BREATHE] 呼吸感 | 静止、怠速 | 慢而柔，接近呼吸 |
 * | [RAINBOW] 彩虹 | 想「五彩」轮转 | 色相跨度 360° + 质心展开 + 彩度上限 0.32 + 18°/s 自转 |
 * | [JUMP] 跳跃 | 驻车/展示、要刺激 | 色相 120° 硬切 + 每拍换档 + 换色间隔 380ms + 亮度 10/60ms + 起拍闪光 1.2× / 120ms |
 *
 * ## 预设不覆盖的东西
 *
 * 「引擎选择」「颜色模式」「亮度」「分区编排」**不在预设里** ——
 * 那些是用户的选择，不是观感参数。换预设不该把用户的设置一起冲掉。
 */
object AmbientPresets {

    /**
     * 一组预设参数。
     *
     * @param name 显示名（设置页 chip 上显示）
     * @param attackMs 明度上升时间常数（毫秒）
     * @param decayMs 明度下降时间常数（毫秒）
     * @param hueTauMs 色相平滑时间常数（毫秒）
     * @param hueMaxDeg 色相角速度上限（度/秒）
     * @param hueMaxAccel 色相角加速度上限（度/秒²）
     * @param maxDeltaETenths 每帧色差上限（ΔE00 ×10）
     * @param floor 基础亮度（%）
     * @param breathHundredths 空闲呼吸幅度（×100）
     * @param saturationHundredths 饱和度上限（×100）
     * @param hueSpanDeg 色相跨度（度，150..360）：150 = 原窄带，360 = 整圈彩虹
     * @param centroidWindowLowHundredths 谱质心展开窗口下沿（×100），0 = 不展开
     * @param centroidWindowSpanHundredths 谱质心展开窗口跨度（×100），100 = 不展开
     * @param hueRotateDegPerSec 色相自转（度/秒），0 = 不转
     * @param maxChromaHundredths 彩度上限（×100），默认 22
     * @param hueJumpDeg 色相跳变步长（度，0..180）：0 = 连续，60 = 六档
     * @param pulseGainHundredths 起拍闪光强度（×100），默认 35
     * @param beatMinGapMs 起拍最短间隔（毫秒），默认 240
     * @param jumpHoldMs 跳变模式的强制换色间隔（毫秒，0 = 只跟起拍）
     */
    data class Preset(
        val name: String,
        val attackMs: Int,
        val decayMs: Int,
        val hueTauMs: Int,
        val hueMaxDeg: Int,
        val hueMaxAccel: Int,
        val maxDeltaETenths: Int,
        val floor: Int,
        val breathHundredths: Int,
        val saturationHundredths: Int,
        val hueSpanDeg: Int = 150,
        val centroidWindowLowHundredths: Int = 0,
        val centroidWindowSpanHundredths: Int = 100,
        val hueRotateDegPerSec: Int = 0,
        val maxChromaHundredths: Int = 22,
        val hueJumpDeg: Int = 0,
        val pulseGainHundredths: Int = 35,
        val beatMinGapMs: Int = 240,
        val jumpHoldMs: Int = 0,
    )

    /** 均衡（方案文档的默认值）。 */
    val BALANCED = Preset(
        name = "均衡",
        attackMs = 45,
        decayMs = 260,
        hueTauMs = 260,
        hueMaxDeg = 60,
        hueMaxAccel = 240,
        maxDeltaETenths = 18,
        floor = 8,
        breathHundredths = 10,
        saturationHundredths = 80,
    )

    /** 夜驾柔和：变化更慢更弱，色差上限更紧。 */
    val NIGHT = Preset(
        name = "夜驾柔和",
        attackMs = 70,
        decayMs = 520,
        hueTauMs = 420,
        hueMaxDeg = 32,
        hueMaxAccel = 120,
        maxDeltaETenths = 12,
        floor = 5,
        breathHundredths = 16,
        saturationHundredths = 55,
    )

    /** 赛博高对比：跟手、浓烈。 */
    val CYBER = Preset(
        name = "赛博高对比",
        attackMs = 28,
        decayMs = 180,
        hueTauMs = 160,
        hueMaxDeg = 120,
        hueMaxAccel = 520,
        maxDeltaETenths = 32,
        floor = 10,
        breathHundredths = 6,
        saturationHundredths = 100,
    )

    /** 呼吸感：慢而柔，接近静止时的呼吸。 */
    val BREATHE = Preset(
        name = "呼吸感",
        attackMs = 140,
        decayMs = 700,
        hueTauMs = 600,
        hueMaxDeg = 22,
        hueMaxAccel = 90,
        maxDeltaETenths = 10,
        floor = 6,
        breathHundredths = 22,
        saturationHundredths = 70,
    )

    /**
     * 彩虹（五彩）：色相走满一圈。
     *
     * ## 「五彩」必须三件事一起做
     *
     * 1. **跨度** 150° → 360°（整圈）；
     * 2. **质心窗口展开** [0.20, 0.65] → [0, 1] —— 感知层把每段归一化到
     *    自己的参考值附近，真实音乐的谱质心只在 0.3~0.6 之间移动，
     *    只放大跨度的话色相也只走 110°，看着还是「偏蓝」；
     * 3. **彩度上限** 0.22 → 0.32 —— 否则红/绿区因为彩度不足而发灰；
     *
     * 4. 另外叠一个 18°/s 的**色相自转**：歌与歌之间的段落质心几乎不动，
     *    自转保证「五彩」在任何时段都看得见，且是连续流过去而不是跳过去。
     */
    val RAINBOW = Preset(
        name = "彩虹",
        attackMs = 40,
        decayMs = 240,
        hueTauMs = 240,
        hueMaxDeg = 90,
        hueMaxAccel = 360,
        maxDeltaETenths = 22,
        floor = 12,
        breathHundredths = 8,
        saturationHundredths = 100,
        hueSpanDeg = 360,
        centroidWindowLowHundredths = 20,
        centroidWindowSpanHundredths = 45,
        hueRotateDegPerSec = 18,
        maxChromaHundredths = 32,
    )

    /**
     * 跳跃：颜色和亮度**跳**着变，鼓点一来就闪。
     *
     * ## 为什么必须「跳变」而不是「更快地滑」
     *
     * 平滑层（二阶色相 + attack/decay）存在的意义就是把跳变抹成滑动 ——
     * 光把 tau 调小、限速调高，结果只是「滑得更快」，仍然是连续过渡。
     * 所以跳跃换了一套机制：
     *
     * 1. **色相跳变** [hueJumpDeg] = 120°：目标色相直接吸到最近档位，
     *    每次换色跨 120°（三段一组，红/绿/蓝大跳），颜色是瞬间换的，不插值；
     * 2. **明度** attack 10ms / decay 60ms + 底光 0：鼓点起、鼓点落，
     *    中间允许真的暗下去（LED 闪烁感），不再「亮着慢慢呼吸」；
     * 3. **起拍闪光** 1.2 倍 + 最短间隔 120ms：最多约 8 次/秒，
     *    比默认的 4 次/秒密一倍，鼓点分得开；
     * 4. **色差上限** 6.0/写（顶格）：限速器不再把一次跳变拖成十几帧的滑坡；
     * 5. 跨度 360° + 质心展开 + 彩度 0.32：跳到的档位都是「有颜色的」，
     *    不会因为彩度不足而发灰；
     * 6. **换色间隔封顶** [jumpHoldMs] = 380ms：起拍检测是内容驱动的，
     *    没鼓点就不换色，灯又定住了 —— 隔这么久至少闪一次。
     *
     * ⚠️ 这一组是**观看/驻车向**的：夜间跟车时红/黄档容易晃眼，
     * 行车时建议切回 [BALANCED] 或 [NIGHT]。
     */
    val JUMP = Preset(
        name = "跳跃",
        attackMs = 10,
        decayMs = 60,
        hueTauMs = 60,
        hueMaxDeg = 180,
        hueMaxAccel = 800,
        maxDeltaETenths = 60,
        floor = 0,
        breathHundredths = 0,
        saturationHundredths = 100,
        hueSpanDeg = 360,
        centroidWindowLowHundredths = 20,
        centroidWindowSpanHundredths = 45,
        hueRotateDegPerSec = 0,
        maxChromaHundredths = 32,
        hueJumpDeg = 120,
        pulseGainHundredths = 120,
        beatMinGapMs = 120,
        jumpHoldMs = 380,
    )

    /** 全部预设（设置页按这个顺序显示）。 */
    val ALL: List<Preset> = listOf(BALANCED, NIGHT, CYBER, BREATHE, RAINBOW, JUMP)

    /** 显示名列表（给 chipRow 用）。 */
    val NAMES: List<String> = ALL.map { it.name }

    /**
     * 一句话描述这个预设开起来是**什么效果**。
     *
     * 只描述灯的行为（多快、多亮、色相怎么走），不写「适合什么时候用」
     * —— 预设页要让人看懂「选了会看到什么」，不是给建议。
     *
     * @param name 预设名（[NAMES] 或 [CUSTOM_NAME]）
     */
    fun summaryOf(name: String): String = when (name) {
        BALANCED.name -> "颜色跟着频谱缓慢换，亮度随音乐起伏，变化幅度小。"
        NIGHT.name -> "颜色换得很慢，亮度起伏也小，相邻两帧的色差被压得更紧。"
        CYBER.name -> "颜色换得快、饱和度高，亮度起伏大，色相在 120° 的窄带里来回。"
        BREATHE.name -> "颜色基本停在原地，亮度像呼吸一样慢慢起伏。"
        RAINBOW.name -> "色相在整圈上慢慢转，颜色沿着光谱一路换过去。"
        JUMP.name -> "颜色一帧硬切到 120° 档位，每次起拍再前进一档，换色间隔 380 ms。"
        else -> "所有参数都开放：亮度包络、色相、彩度、跳变、自转各调各的。"
    }

    /** 预设行末尾「自定义」chip 的文案。 */
    const val CUSTOM_NAME = "自定义"

    /**
     * 当前参数最接近哪个预设（-1 = 不匹配任何预设）。
     *
     * 设置页用它来决定 chip 上高亮哪一个 ——
     * 用户手动调过滑杆之后，chip 就变成「自定义」状态。
     */
    fun match(
        attackMs: Int,
        decayMs: Int,
        hueTauMs: Int,
        hueMaxDeg: Int,
        hueMaxAccel: Int,
        maxDeltaETenths: Int,
        floor: Int,
        breathHundredths: Int,
        saturationHundredths: Int,
        hueSpanDeg: Int = 150,
        centroidWindowLowHundredths: Int = 0,
        centroidWindowSpanHundredths: Int = 100,
        hueRotateDegPerSec: Int = 0,
        maxChromaHundredths: Int = 22,
        hueJumpDeg: Int = 0,
        pulseGainHundredths: Int = 35,
        beatMinGapMs: Int = 240,
        jumpHoldMs: Int = 0,
    ): Int {
        ALL.forEachIndexed { index, p ->
            if (p.attackMs == attackMs &&
                p.decayMs == decayMs &&
                p.hueTauMs == hueTauMs &&
                p.hueMaxDeg == hueMaxDeg &&
                p.hueMaxAccel == hueMaxAccel &&
                p.maxDeltaETenths == maxDeltaETenths &&
                p.floor == floor &&
                p.breathHundredths == breathHundredths &&
                p.saturationHundredths == saturationHundredths &&
                p.hueSpanDeg == hueSpanDeg &&
                p.centroidWindowLowHundredths == centroidWindowLowHundredths &&
                p.centroidWindowSpanHundredths == centroidWindowSpanHundredths &&
                p.hueRotateDegPerSec == hueRotateDegPerSec &&
                p.maxChromaHundredths == maxChromaHundredths &&
                p.hueJumpDeg == hueJumpDeg &&
                p.pulseGainHundredths == pulseGainHundredths &&
                p.beatMinGapMs == beatMinGapMs &&
                p.jumpHoldMs == jumpHoldMs
            ) {
                return index
            }
        }
        return -1
    }
}
