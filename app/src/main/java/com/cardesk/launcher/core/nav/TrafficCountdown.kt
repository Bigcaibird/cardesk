package com.cardesk.launcher.core.nav

/**
 * 红绿灯读秒的**显示**状态机（**纯逻辑**，不碰 Android，可直接 JVM 单测）。
 *
 * ## 为什么要有它
 *
 * 真车实测（第八十六轮）：**绿灯时读秒一直在 3 与 2 之间来回跳**，不往下走。
 *
 * 原因是上一版的显示算法有个致命细节：
 * **每收到一帧就把读秒重新锚回这一帧的原始值**。
 * 而高德**不是每秒都刷**这个字段（真车上帧间隔约 2 秒），
 * 原始值没变时，重新锚等于**把已经走掉的秒数又加回去** ——
 * 于是显示变成 `3 →（本机减 1）2 →（新帧重锚）3 → 2 …` 的锯齿。
 *
 * ## 这里的规则
 *
 * **同一个灯态里，读秒只减不增。**
 *
 * - **灯态变了**（红↔绿）→ 重新起一轮，直接拿这一帧的原始读秒当锚点；
 * - **同一灯态**下：
 *   - 原始读秒比当前显示**更小** → 供应商说「不多了」，以它为准，重新锚点；
 *   - 原始读秒**没变小**（多半是供应商没刷新）→ **沿用我们自己减出来的值**，
 *     只把时钟挪到这一帧，让它继续往下走；
 *   - 读秒**已经归零** → **不再显示数字**，直到灯态变化（不猜灯态）。
 *
 * ## 为什么不猜语义
 *
 * 官方协议那个绿灯字段 `greenLightLastSecond` 的**语义至今未验证**
 * （见 [AmapTrafficLight] 的 KDoc 与参照工程的取证结论）。
 * 这里**不判断它是不是倒计时**，只保证显示不出现锯齿 ——
 * 「数字只往下走、不回跳」是显示层的自洽要求，与字段语义无关。
 *
 * ## 和 TTL 的关系
 *
 * 有效期（[AmapDesktopGuide.TRAFFIC_LIGHT_TTL_MS]）由 [AmapDesktopGuide] 判定：
 * 断档超过 TTL 会被判成「没有信号」，重新来帧时由它先调 [reset] 再 [accept]，
 * 这样「断档很久」不会被当成「同一轮读秒的延续」。
 */
class TrafficCountdown {

    /** 当前这一轮属于哪个灯态（null = 还没收到过信号）。 */
    private var lamp: AmapTrafficLight.Lamp? = null

    /** 锚点上的读秒（null = 这一轮没有读秒可显示）。 */
    private var shown: Int? = null

    /** 锚点时刻（本机单调时钟，毫秒）。 */
    private var anchorAt = 0L

    /** 这一轮读秒是否已经归零（归零后不再显示，直到灯态变化）。 */
    private var expired = false

    /**
     * 收到一帧信号：更新锚点。
     *
     * @param lamp 本帧灯态。
     * @param raw 本帧的原始读秒（null = 这一帧没带读秒）。
     * @param atMs 本机单调时钟（毫秒）。
     * @return 锚定之后的读秒（null = 不显示数字）。
     */
    fun accept(lamp: AmapTrafficLight.Lamp, raw: Int?, atMs: Long): Int? {
        if (this.lamp != lamp) {
            // 灯态变了 → 重新起一轮
            this.lamp = lamp
            expired = false
            shown = raw
            anchorAt = atMs
            return shown
        }
        // 同一灯态：先把显示值随时间减到 atMs（这一段没人来刷新）
        var current = shown
        if (current != null) {
            val left = current - (atMs - anchorAt) / 1000L
            if (left <= 0L) {
                current = null
                expired = true
            } else {
                current = left.toInt()
            }
        }
        // 供应商的原始值只用来「往下压」，**不用来把秒数加回去**
        if (!expired && raw != null) {
            current = if (current == null) raw else minOf(raw, current)
        }
        shown = current
        anchorAt = atMs
        return current
    }

    /**
     * 按当前时刻算显示值（**不推进状态**）。
     *
     * @param atMs 本机单调时钟（毫秒）。
     * @return 显示用的读秒（null = 不显示数字）。
     */
    fun current(atMs: Long): Int? {
        val s = shown ?: return null
        val left = s - (atMs - anchorAt) / 1000L
        return if (left > 0L) left.toInt() else null
    }

    /** 清空（导航结束 / 信号断档）。 */
    fun reset() {
        lamp = null
        shown = null
        anchorAt = 0L
        expired = false
    }

    /**
     * 上一次 [accept] 的时刻（本机单调时钟，毫秒）；还没收到过信号时返回 0。
     *
     * 用来判断「这一轮是不是断档了很久」—— 断档超过 TTL 就该重新起一轮，
     * 而不是接着上一轮继续减。
     */
    fun lastAnchorAt(): Long = if (lamp == null) 0L else anchorAt
}
