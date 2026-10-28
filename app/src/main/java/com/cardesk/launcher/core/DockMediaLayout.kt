package com.cardesk.launcher.core

/**
 * Dock 媒体卡的**三键布局**（上一首 / 播放暂停 / 下一首）。
 *
 * ## 为什么抽成纯逻辑
 *
 * 第三十九轮（4385）把播放键挪到前后曲**中间**，同时把播放键从 60×60 缩到 44×44、
 * 前后曲按钮从 36 放大到 40、间隙从 16 收到 8 —— 这组数值必须互相咬合
 * （三个键 + 两个间隙正好占满媒体卡右侧），写成一段算式很容易算错，
 * 所以抽到这里做纯 Kotlin，便于单测锁住。
 *
 * ## 布局（卡片局部坐标，0 = 卡片左边）
 *
 * `
 * ┌────────────────────────────────────────────────────────┐
 * │ [封面60] 歌名/歌手………………… |◀40 | ▶44 | ▶|40 │
 * │ ↑14 ↑+60+16=90 ↑prev-16 │
 * └────────────────────────────────────────────────────────┘
 *                                          ↑=cardW-14-40=nextLeft
 * `
 *
 * 顺序是**上一首 → 播放暂停 → 下一首**（用户要求：播放键放在前后曲之间）。
 */
object DockMediaLayout {

    /** 媒体卡高度（设计 px）。 */
    const val CARD_H = 88f

    /** 媒体卡左右内边距（`paddingStart/End = 14px`）。 */
    const val PAD_L = 14f
    const val PAD_R = 14f

    /** 封面边长（= 60px）。 */
    const val COVER_SIZE = 60f

    /** 封面与文本区之间的间隙（`layout_marginStart = 16px`）。 */
    const val COVER_GAP = 16f

    /** 文本区与第一个按钮之间的间隙。 */
    const val TEXT_GAP = 16f

    /**
     * 播放 / 暂停键的边长。
     *
     * 原来 60×60 圆（容器）
     * 但在 88px 高的卡里视觉权重太重，和旁边的小三角**不协调**（用户反馈）。
     * 缩到 44 之后三个键的视觉重量才接近。
     */
    const val PLAY_BTN = 44f

    /** 上一首 / 下一首按钮的边长。 */
    const val SKIP_BTN = 40f

    /** 三个键之间的间隙。 */
    const val KEY_GAP = 8f

    /**
     * 媒体卡的**最大**宽度（设计 px）。
     *
     * 第三十七轮由 400 加宽到 496，用来放上一首 / 播放 / 下一首三个键。
     */
    const val MEDIA_CARD_MAX_W = 496f

    /**
     * 媒体卡与右侧空调簇之间的最小间隙（设计 px）。
     *
     * 第三十九轮发现的一个**老 bug**：媒体卡加宽到 496 之后，右侧空调簇
     * （从右往左排的一长串）实际占位超过了 Dock 的剩余空间
     * **电源键被挤进了媒体卡里**，与「下一首」按钮叠在一起
     * （4384 时因为白色播放圆有 60px 大，刚好把电源键整个盖住，没被发现；
     * 这轮把播放圆缩到 44px，它就露出来了。
     *
     * 现在媒体卡宽度按「空调簇实际左边界」**动态收敛**，两者永远不会压叠。
     *
     * 第五十三轮：真正参与三段布局的常量搬到了 [DockLayout.MIN_GROUP_GAP]，
     * 这个名字保留下来只为兼容旧断言，**新代码别再引用它**。
     */
    const val MEDIA_CLUSTER_GAP = 16f

    /** 媒体卡的**最小**宽度（设计 px）：再窄就装不下封面 + 三个键了。 */
    const val MIN_MEDIA_W = 360f

    /**
     * 三个键的位置（**卡片局部坐标**）。
     *
     * @param prevLeft 上一首键左边
     * @param playLeft 播放键左边
     * @param nextLeft 下一首键左边
     * @param textLeft 文本区左边
     * @param textWidth 文本区宽度
     */
    data class Keys(
        val prevLeft: Float,
        val playLeft: Float,
        val nextLeft: Float,
        val textLeft: Float,
        val textWidth: Float,
    )

    /**
     * 算三个键的位置与文本区宽度。
     *
     * @param cardWidth 媒体卡宽度（设计 px）
     */
    fun keys(cardWidth: Float): Keys {
        val nextLeft = cardWidth - PAD_R - SKIP_BTN
        val playLeft = nextLeft - KEY_GAP - PLAY_BTN
        val prevLeft = playLeft - KEY_GAP - SKIP_BTN
        val textLeft = PAD_L + COVER_SIZE + COVER_GAP
        val textRight = prevLeft - TEXT_GAP
        return Keys(
            prevLeft = prevLeft,
            playLeft = playLeft,
            nextLeft = nextLeft,
            textLeft = textLeft,
            textWidth = if (textRight - textLeft > 0f) textRight - textLeft else 0f,
        )
    }
}
