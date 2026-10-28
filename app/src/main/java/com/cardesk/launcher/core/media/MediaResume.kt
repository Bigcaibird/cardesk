package com.cardesk.launcher.core.media

/**
 * 「暂停后恢复播放」的**目标判定**（纯函数）。
 *
 * ## 要解决的问题（第六十八轮）
 *
 * 车机音乐 App 在暂停时常常**把自己的会话一起销毁**。
 * 于是「按了暂停 → 再按播放」时：
 *
 * - 我们拿不到任何活动会话（`controller == null`）；
 * - 以前只发了一个**全局**媒体键（`AudioManager.dispatchMediaKeyEvent`），
 *   全局键只会被「当前正在放声音的 App」接住 —— 没有活动会话时谁都不接 → **按播放没反应**。
 *
 * 用户要的口径是：**没找到活动会话时，就向上一次使用的播放器发一个播放事件**
 * ——定向发（`MEDIA_BUTTON` + `setPackage`），让那个 App 自己接着放。
 *
 * 抽成纯对象是为了能单测：这条规则决定「按播放到底有没有人接」。
 */
object MediaResume {

    /**
     * 恢复播放的**目标包**。
     *
     * 优先级：
     * 1. 当前**绑定会话**的包（有会话就直接让它播）；
     * 2. 本次恢复**指定**的包（暂停时那个 —— 也就是「上一次使用的播放器」）；
     * 3. 记住的**上次音乐包**（`LastTrackStore` / `lastMusicPkg`）。
     *
     * 全空 → 返回空串（调用方据此**不发**广播，避免给 `""` 发空包名广播）。
     */
    fun targetPackage(boundPkg: String?, fallbackPkg: String?, rememberedPkg: String?): String =
        firstNonBlank(boundPkg, fallbackPkg, rememberedPkg)

    /**
     * 要不要给目标包发**定向** `MEDIA_BUTTON(PLAY)`。
     *
     * - **没有活动会话**（[boundPkg] 空）→ **发**。
     *   这是第六十八轮补的那一路：哪怕目标不是「爱趣听类」，
     *   也要把播放事件送到「上一次使用的播放器」那里去。
     * - **有活动会话** → 只对「爱趣听类」发（保持原语义）。
     *   有会话时 `transportControls.play()` 本来就管用，
     *   再给别的 App 塞广播没有收益、反而有副作用
     *   （暂停路径上就出过「多发一路定向广播把会话搞没了」的事故）。
     *
     * @param boundPkg 当前绑定会话的包（空 = 没找到活动会话）
     * @param target 目标包
     * @param isAiquting 目标是不是「爱趣听类」（车机多媒体）
     */
    fun shouldSendTargetedPlay(boundPkg: String?, target: String, isAiquting: Boolean): Boolean {
        if (target.isBlank()) return false
        if (boundPkg.isNullOrBlank()) return true
        return isAiquting
    }

    private fun firstNonBlank(vararg values: String?): String {
        for (v in values) {
            if (!v.isNullOrBlank()) return v.trim()
        }
        return ""
    }
}
