package com.cardesk.launcher.core.media

/**
 * 「播放器自供歌词」的识别（纯逻辑，JVM 可单测）。
 *
 * ## 背景
 *
 * 拿到歌词一共有三条路：
 *
 * | 路子 | 覆盖范围 | 权威性 |
 * | --- | --- | --- |
 * | **QQ 音乐车机版广播**（`update_lyric`） | 只有 QQ 音乐车机版 | 最高（播放器自己推的整份 LRC） |
 * | **播放器自供**（里带的歌词） | 少数播放器 | 高（跟播放的是同一份） |
 * | **联网查**（网易 / QQ / 酷狗） | 全部 | 看匹配选得准不准 |
 *
 * 只有第一、三条
 * `OnlineLyricCatalog`，**没有**第二条 —— 它读 `MediaDescription` 时只取
 * `title` / `subtitle`，不看 `extras`。
 *
 * 这里把第二条补上（**增强，不是偏离**：不做，我们多认一条，不影响原有行为）。
 * 误判风险控制得很严：**只有能解析出带时间戳的 LRC 才认**，
 * 歌名里带方括号、描述里带个 `[1]` 之类的都不会被当成歌词。
 *
 * ## 已知限制
 *
 * 诊断包里的音源 `com.tencent.wecarflow`（微信车机 / 爱的听）**不推歌词**：
 * 明确把 `wecarflow` / `aiquting` / `qqmusictv` 排除在广播来源之外
 *
 * 我们照抄了。所以这类车机只能走联网，**匹配选得准不准才是关键**。
 */
object PlayerLyric {

    /** LRC 时间戳行：`[mm:ss]` / `[mm:ss.xx]`（与 `LrcParser.TIME_TAG` 同口径）。 */
    private val TIME_TAG = Regex("""\[\d{1,3}:\d{1,2}(?:[.:]\d{1,3})?]""")

    /** 至少要有这么多行带时间戳，才认定为歌词。 */
    const val MIN_TIME_LINES = 2

    /** 太长的一般不是歌词（防把整页 HTML / JSON 当歌词）。 */
    private const val MAX_LENGTH = 200_000

    /** 这段文字像一份歌词吗？ */
    fun looksLikeLrc(text: String?): Boolean {
        if (text.isNullOrBlank()) return false
        if (text.length > MAX_LENGTH) return false
        return TIME_TAG.findAll(text).count() >= MIN_TIME_LINES
    }

    /**
     * 从一组候选字符串里挑出**像歌词**的那一段（时间戳行最多的优先）。
     *
     * 候选来源（见 `MusicSessionTracker`）：
     * - `MediaMetadata` 的自定义 key（`lyric` / `lyrics` / `lrc` …）；
     * - `MediaDescription.extras` 里 key 名带 `lyric` / `lrc` / `caption` 的字符串值。
     */
    fun findLrc(candidates: List<String?>): String? {
        var best: String? = null
        var bestHits = 0
        for (raw in candidates) {
            if (raw.isNullOrBlank()) continue
            if (raw.length > MAX_LENGTH) continue
            val hits = TIME_TAG.findAll(raw).count()
            if (hits >= MIN_TIME_LINES && hits > bestHits) {
                best = raw
                bestHits = hits
            }
        }
        return best
    }

    /** 播放器可能用的歌词 key 名（小写比较）。 */
    val METADATA_LYRIC_KEYS: List<String> = listOf(
        "lyric", "lyrics", "lrc", "lyric_text", "current_lyric", "caption", "subtitles",
    )

    /** `MediaDescription.extras` 里带这些词的 key 都当歌词候选。 */
    val EXTRAS_KEY_HINTS: List<String> = listOf("lyric", "lrc", "caption", "subtitle")
}
