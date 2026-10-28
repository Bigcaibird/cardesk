package com.cardesk.launcher.core

/**
 * Dock 的**四段式布局**（第四十九轮「浮屿」→ 第五十三轮等距 → **第五十四轮四段**）。
 *
 * `
 * ┌────────────────────────────────────────────────────────────────────────┐
 * │ ⦿ ▦ ◫ ⟳ ♪ 媒体卡 ⏻ −23.5°+ 🌬 ▮▮▮▮▮▮▮ −23.5°+ A/C AUTO ↻ ❄ ❄ ◉ ◉ ◉ │
 * └────────────────────────────────────────────────────────────────────────┘
 *   ↑ 导航键 ↑ 音乐卡 ↑ 空调簇 ↑ 快捷应用
 * `
 *
 * ## 第五十四轮：音乐卡与空调簇对调 + 段间不再刻意留白
 *
 * 第五十三轮把三组**等距**摆开（间隙各 104），但用户反馈的根因其实还在：
 * **「挤在一块，中间还有很长的空隙」** —— 104 的间隙在 1872 宽的 Dock 上
 * 仍然是两块视觉上的「空地」，而真正的内容（空调簇）宽得离谱。
 *
 * 这一轮改成：
 * 1. **音乐卡和空调簇交换位置** —— 音乐卡贴导航键，空调簇居中偏右；
 * 2. **段与段之间不再刻意留空隙** —— 只保留 [SECTION_GAP] 这一口气；
 * 3. 省下来的宽度全部给**快捷应用**（贴最右，默认 [DockQuickApps.SLOTS] 个槽位）。
 *
 * ## 为什么抽成纯逻辑
 *
 * 簇宽里的 A/C · AUTO 两段是**按字体实测**的 —— 算式一旦写错，
 * 就会出现第三十九轮那个老 bug：媒体卡把电源键挤进去（被 60px 的白色播放圆盖住）。
 *
 * 所以这里把「给定 Dock 宽 + 簇宽 → 四段的边界」做成纯函数，单测直接锁住：
 * - 四段**按顺序**排开、互不压叠；
 * - 快捷应用段**贴着右边缘**；
 * - 媒体卡宽度落在 [DockMediaLayout.MIN_MEDIA_W, DockMediaLayout.MEDIA_CARD_MAX_W] 内。
 */
object DockLayout {

    /**
     * 左段第一个导航键的左边（第五十轮对齐设计板 s01：Dock 底板左边 24 + 内边距 22）。
     */
    const val KEY_LEFT = 22f

    /**
     * 导航键的槽宽（设计板 `.tile` 是 60×60 圆角 20）。
     */
    const val KEY_SLOT = 60f

    /** 导航键之间的间隙（设计板 22 + 60 + 14 的步进）。 */
    const val KEY_GAP = 14f

    /** 导航键的个数：主页 / 应用抽屉 / 分屏 / 360。 */
    const val KEY_COUNT = 4

    /** 最右一段（快捷应用）右边到 Dock 右边缘的距离。 */
    const val MEDIA_RIGHT_INSET = 22f

    /**
     * 段与段之间的**呼吸位**（第五十四轮）。
     *
     * 用户要的是「不特意留空隙」—— 所以这里是 **10px** 的最小呼吸位，
     * 不是第五十三轮那种 104px 的刻意留白。
     * 之所以还留 10px：媒体卡是 28 圆角的圆角矩形，导航键是 60×60 的圆角方块，
     * 贴死的话两个圆角会互相咬出一块难看的缺口。
     */
    const val SECTION_GAP = 10f

    /** 快捷应用槽位的边长（设计 px）。 */
    const val QUICK_TILE = 56f

    /** 快捷应用槽位之间的间隙（设计 px）。 */
    const val QUICK_GAP = 8f

    /**
     * 四段布局的结果。
     *
     * @param keysLeft 左段导航键的左边界
     * @param keysRight 左段导航键的右边界
     * @param mediaLeft 音乐卡的左边界
     * @param mediaRight 音乐卡的右边界
     * @param mediaWidth 音乐卡宽度
     * @param clusterLeft 空调簇左边界
     * @param clusterRight 空调簇右边界
     * @param quickLeft 快捷应用段左边界
     * @param quickRight 快捷应用段右边界
     */
    data class Sections(
        val keysLeft: Float,
        val keysRight: Float,
        val mediaLeft: Float,
        val mediaRight: Float,
        val mediaWidth: Float,
        val clusterLeft: Float,
        val clusterRight: Float,
        val quickLeft: Float,
        val quickRight: Float,
    )

    /**
     * 算四段布局（**从右往左定**：快捷应用贴右 → 空调簇 → 音乐卡 → 导航键贴左）。
     *
     * @param dockWidth Dock 总宽（设计 px）
     * @param clusterWidth 空调簇的**实测**占位宽（含 A/C · AUTO 两段文字）
     */
    fun sections(dockWidth: Float, clusterWidth: Float): Sections {
        // 左段：4 个导航键贴左
        val keysLeft = KEY_LEFT
        val keysRight = keysLeft + KEY_COUNT * KEY_SLOT + (KEY_COUNT - 1) * KEY_GAP

        // 最右段：快捷应用贴右
        val quickWidth = DockQuickApps.SLOTS * QUICK_TILE + (DockQuickApps.SLOTS - 1) * QUICK_GAP
        val quickRight = dockWidth - MEDIA_RIGHT_INSET
        val quickLeft = quickRight - quickWidth

        // 空调簇：紧挨快捷应用左边
        val clusterRight = quickLeft - SECTION_GAP
        val clusterLeft = clusterRight - clusterWidth

        // 音乐卡：紧挨空调簇左边，宽度取剩余空间（夹在合法区间）
        val mediaRight = clusterLeft - SECTION_GAP
        var mediaWidth = (mediaRight - keysRight - SECTION_GAP)
            .coerceIn(DockMediaLayout.MIN_MEDIA_W, DockMediaLayout.MEDIA_CARD_MAX_W)
        var mediaLeft = mediaRight - mediaWidth
        // 兜底：簇异常宽时媒体卡可以窄，但**不能压到导航键**
        if (mediaLeft < keysRight + 4f) {
            mediaLeft = keysRight + 4f
            mediaWidth = mediaRight - mediaLeft
        }

        return Sections(
            keysLeft = keysLeft,
            keysRight = keysRight,
            mediaLeft = mediaLeft,
            mediaRight = mediaRight,
            mediaWidth = mediaWidth,
            clusterLeft = clusterLeft,
            clusterRight = clusterRight,
            quickLeft = quickLeft,
            quickRight = quickRight,
        )
    }

    /**
     * 第 [index] 个快捷应用槽位的**左边界**（Dock 局部坐标）。
     *
     * @param sections [sections] 的结果
     * @param index 槽位下标（0..[DockQuickApps.SLOTS)-1）
     */
    fun quickSlotLeft(sections: Sections, index: Int): Float =
        sections.quickLeft + index * (QUICK_TILE + QUICK_GAP)
}
