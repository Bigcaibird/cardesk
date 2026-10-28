package com.cardesk.launcher.core.media

import com.cardesk.launcher.core.log.LogTags
import com.cardesk.launcher.core.log.Logx

/**
 * 会话候选与选择规则。
 *
 * 全部
 * - 锁定包：优先「活跃」的锁定会话，其次「有元数据」的锁定会话，都没有则不选；
 * - 非锁定：活跃的非 mediacenter 会话直接胜出；
 * - 其次是活跃的 mediacenter 会话；
 * - 再看「最近被 touch 过且有元数据」的会话；
 * - 已经选中的会话要**稳定 80ms** 才允许被换掉；
 * - mediacenter 包降权。
 */
data class SessionCandidate(
    val pkg: String,
    val playing: Boolean,
    val hasMetadata: Boolean,
    val updatedAt: Long,
    val title: String = "",
    val artist: String = "",
    /** `isActive(PlaybackState)`：PLAYING(3) / BUFFERING(6) / STALLED(8)。 */
    val active: Boolean = playing,
    /** `isMediaCenter(pkg)`：mediacenter 包降权。 */
    val mediaCenter: Boolean = false,
    /**
     * 会话唯一标识。
     *
     * **必须是会话级、不是包级** —— 真机上同一个音乐 App 会同时发布两个
     * 其中只有一个带 metadata。按包名去重会把带歌的那个丢掉。
     *
     * 放在末位并给默认值，是为了不破坏既有的位置参数调用（`SessionCandidate("p", false, false, 0L)`）。
     */
    val tokenKey: String = "",
)

object SessionChooser {

    /** 换会话要领先 80ms 以上。 */
    const val STICKY_THRESHOLD_MS = 80L

    /**
     *
     * 自己、system、launcher、systemui 的会话不算数。
     */
    fun isUsablePackage(pkg: String?, selfPackage: String?): Boolean {
        if (pkg.isNullOrEmpty() || selfPackage == null) return false
        val lower = pkg.lowercase()
        return !(pkg == selfPackage || pkg == "android" ||
            lower.contains("launcher") || lower.contains("systemui"))
    }

    fun isMediaCenter(pkg: String?): Boolean = pkg?.lowercase()?.contains("mediacenter") == true

    /**
     * 打分（保留旧入口）：播放中 > 有元数据 > 锁定包 > 最近更新。
     * 真正的选择走 [choose]，这里的分数只用于日志/调试与旧调用方。
     */
    fun score(c: SessionCandidate, lockedPkg: String?, now: Long): Int {
        var s = 0
        if (c.active) s += 100
        if (c.hasMetadata) s += 50
        if (!lockedPkg.isNullOrEmpty() && c.pkg == lockedPkg) s += 80
        val age = (now - c.updatedAt).coerceAtLeast(0L)
        s += (20 - (age / 3000).toInt()).coerceIn(0, 20)
        return s
    }

    /**
     * 选出一个会话。
     *
     * @param lockedPkg 用户显式锁定的包（切来源时）
     * @param now 当前时刻（同一时钟域即可，仅用于日志）
     * @param selectedTokenKey 当前已绑定的**会话**（用于 80ms 稳定判定）
     * @param lastMusicPkg 上次记住的音乐包（MMKV `last_music_package`，touch 时 +1ms 加权）
     */
    fun choose(
        candidates: List<SessionCandidate>,
        lockedPkg: String?,
        now: Long,
        selectedTokenKey: String? = null,
        lastMusicPkg: String = "",
    ): SessionCandidate? {
        val picked = chooseInternal(candidates, lockedPkg, now, selectedTokenKey, lastMusicPkg)
        // 排障场景：为什么接管到了这个会话、为什么没接管到别的会话
        Logx.d(
            LogTags.MEDIA, "会话选择",
            "candidates" to candidates.joinToString(",") { "${it.pkg}#${it.tokenKey}" },
            "locked" to (lockedPkg ?: ""),
            "selected" to (selectedTokenKey ?: ""),
            "lastMusic" to lastMusicPkg,
            "picked" to (picked?.pkg ?: "无"),
        )
        return picked
    }

    /** [choose] 的实际判定逻辑。 */
    private fun chooseInternal(
        candidates: List<SessionCandidate>,
        lockedPkg: String?,
        now: Long,
        selectedTokenKey: String?,
        lastMusicPkg: String,
    ): SessionCandidate? {
        if (candidates.isEmpty()) return null

        // 锁定包：活跃优先，其次有元数据（:197-213）
        // 遍历的是**会话列表** —— 同一个包的多个会话都会被考虑
        // 谁活跃 / 谁有 metadata 就选谁。
        if (!lockedPkg.isNullOrEmpty()) {
            var fallback: SessionCandidate? = null
            for (c in candidates) {
                if (c.pkg != lockedPkg) continue
                if (c.active) return c
                if (fallback == null && c.hasMetadata) fallback = c
            }
            return fallback
        }

        var activeNormal: SessionCandidate? = null
        var activeMedia: SessionCandidate? = null
        var metaNormal: SessionCandidate? = null
        var metaNormalTouch = Long.MIN_VALUE
        var metaMedia: SessionCandidate? = null
        var metaMediaTouch = Long.MIN_VALUE
        var selected: SessionCandidate? = null
        var selectedTouch = Long.MIN_VALUE

        for (c in candidates) {
            val isLast = c.pkg == lastMusicPkg
            // 241-243 —— 上次记住的包正在活跃，直接选它
            if (c.active && isLast) return c
            if (c.active && !c.mediaCenter && activeNormal == null) activeNormal = c
            if (c.active && c.mediaCenter && activeMedia == null) activeMedia = c
            // 251-254 —— 已选中的会话按 **token** 认定（不是包名）
            if (selectedTokenKey != null && selectedTokenKey == c.tokenKey) {
                selected = c
                selectedTouch = c.updatedAt
            }
            if (c.hasMetadata) {
                val touch = touchOf(c, isLast)
                if (c.mediaCenter) {
                    if (touch > metaMediaTouch) {
                        metaMediaTouch = touch
                        metaMedia = c
                    }
                } else {
                    if (touch > metaNormalTouch) {
                        metaNormalTouch = touch
                        metaNormal = c
                    }
                }
            }
        }

        // 279-284 —— 活跃会话直接胜出
        if (activeNormal != null) return activeNormal
        if (activeMedia != null) return activeMedia

        // 285-291 —— 已选中的会话要稳定 80ms 才让位
        if (selected != null) {
            val best = metaNormal ?: metaMedia
            val bestTouch = if (metaNormal != null) metaNormalTouch else metaMediaTouch
            if (best == null || best.tokenKey == selected.tokenKey ||
                bestTouch <= selectedTouch + STICKY_THRESHOLD_MS
            ) {
                return selected
            }
            return best
        }

        // 292-297
        return metaNormal ?: metaMedia
    }

    /** touch 时间：上次记住的包 +1ms（:257）。 */
    private fun touchOf(c: SessionCandidate, isLast: Boolean): Long =
        c.updatedAt + if (isLast) 1L else 0L
}
