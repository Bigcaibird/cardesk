package com.cardesk.launcher.core.media

import com.cardesk.launcher.core.JsonLite
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 线上取词的**离线回归样本**。
 *
 * 第四十轮（4386）修「歌词和歌曲播放速度不匹配」，用陶喆《小镇姑娘》复现。
 * 这里的 JSON 是**线上真实返回**（2026-10-27 实测，按需裁剪过），
 * 用来锁死两个真 bug：
 *
 * 1. **网易的歌曲 id 是数字**（`150619`），不是字符串 ——
 *    之前用 `JsonLite.str` 取 → 空串 → `?id=` → 整条网易渠道永远取不到词
 *    （诊断包日志里那条「歌词渠道未命中 provider=netease」就是它）。
 * 2. **同一首歌会返回一堆版本**，其中「小镇姑娘 / 陶喆 / 213s」是**电台剪辑版** ——
 *    歌名歌手和原版一模一样，时间轴却短 82 秒；挂上去歌词就跑得比歌快。
 *    之前是「取第一条能解析出歌词的」，而搜索**顺序不稳定**，等于抽签。
 */
class OnlineLyricCandidateTest {

    private val title = "小镇姑娘"
    private val artist = "陶喆"
    /** 专辑原版时长 295666ms（网易 / QQ / 酷狗三家的原版都在 295s 附近）。 */
    private val songDuration = 295666L

    // ---------------------------------------------------------------- 样本

    /** 网易真实返回（裁剪版）：`id` / `duration` 都是**数字**。 */
    private val neteaseJson = """
        {"result":{"songs":[
          {"id":150619,"name":"小镇姑娘","duration":295666,"artists":[{"name":"陶喆"}]},
          {"id":150484,"name":"小镇姑娘 (Live)","duration":270813,"artists":[{"name":"陶喆"}]},
          {"id":2647579132,"name":"小镇姑娘 (Live)","duration":213028,"artists":[{"name":"陶喆"}]},
          {"id":150508,"name":"小镇姑娘","duration":295666,"artists":[{"name":"陶喆"}]},
          {"id":2150103431,"name":"小镇姑娘（翻唱完整版）","duration":295665,"artists":[{"name":"Emo避难所"}]},
          {"id":406730952,"name":"小镇姑娘","duration":241400,"artists":[{"name":"群星"}]}
        ]}}
    """.trimIndent()

    /** QQ 真实返回（裁剪版）：`interval` 单位是**秒**。 */
    private val qqJson = """
        {"data":{"song":{"list":[
          {"songmid":"002fWpie3IjneY","songname":"小镇姑娘","interval":295,"singer":[{"name":"陶喆"}]},
          {"songmid":"001JySdo1kn5gm","songname":"小镇姑娘 (Live)","interval":270,"singer":[{"name":"陶喆"}]},
          {"songmid":"002MVNqF44CJRV","songname":"小镇姑娘 (DJ Bo版)","interval":223,"singer":[{"name":"陶喆"}]},
          {"songmid":"003TH2SX4QcwhF","songname":"小镇姑娘 (2014大地飞歌南宁民歌艺术节)","interval":299,"singer":[{"name":"陶喆"}]},
          {"songmid":"001H6VZd0OTZPl","songname":"小镇姑娘","interval":176,"singer":[{"name":"李瑨瑶"}]}
        ]}}}
    """.trimIndent()

    /** 酷狗真实返回（裁剪版）：**同时含原版 295s 与电台剪辑版 213s**。 */
    private val kugouJson = """
        {"data":{"lists":[
          {"FileHash":"7A3F9ECC73C46EAC591BAC593941682A","SongName":"小镇姑娘","SingerName":"陶喆","Duration":295},
          {"FileHash":"C2FE2E85F7A7AEB0CD63851CCC8F7CBA","SongName":"小镇姑娘 (Live)","SingerName":"陶喆","Duration":270},
          {"FileHash":"9DAAD3E8F8395180AC16664E6E886DB1","SongName":"小镇姑娘 (2023最美的夜晚会现场)","SingerName":"陶喆","Duration":221},
          {"FileHash":"CFDA92A752393474819D22121ECC88C0","SongName":"找自己","SingerName":"陶喆","Duration":304},
          {"FileHash":"327ACAF532B286DF24D065C47B1FA969","SongName":"小镇姑娘","SingerName":"陶喆","Duration":213}
        ]}}
    """.trimIndent()

    // ---------------------------------------------------------------- 解析（与渠道代码同口径）

    private fun neteaseCandidates(): List<LyricCandidate> {
        val songs = (JsonLite.parseObject(neteaseJson)?.get("result") as? Map<*, *>)
            ?.get("songs") as? List<*> ?: return emptyList()
        return songs.mapNotNull { raw ->
            val s = raw as? Map<String, Any?> ?: return@mapNotNull null
            val ar = (s["artists"] as? List<*>)?.firstOrNull() as? Map<String, Any?>
            val id = JsonLite.long(s, "id")
            if (id <= 0) return@mapNotNull null
            LyricCandidate(
                title = JsonLite.str(s, "name"),
                artist = ar?.let { JsonLite.str(it, "name") }.orEmpty(),
                durationMs = JsonLite.long(s, "duration"),
                payload = id.toString(),
            )
        }
    }

    private fun qqCandidates(): List<LyricCandidate> {
        val root = JsonLite.parseObject(qqJson) ?: return emptyList()
        val list = ((root["data"] as? Map<*, *>)?.get("song") as? Map<*, *>)
            ?.get("list") as? List<*> ?: return emptyList()
        return list.mapNotNull { raw ->
            val s = raw as? Map<String, Any?> ?: return@mapNotNull null
            val sg = (s["singer"] as? List<*>)?.firstOrNull() as? Map<String, Any?>
            LyricCandidate(
                title = JsonLite.str(s, "songname"),
                artist = sg?.let { JsonLite.str(it, "name") }.orEmpty(),
                durationMs = JsonLite.long(s, "interval") * 1000,
                payload = JsonLite.str(s, "songmid"),
            )
        }
    }

    private fun kugouCandidates(): List<LyricCandidate> {
        val list = (JsonLite.parseObject(kugouJson)?.get("data") as? Map<*, *>)
            ?.get("lists") as? List<*> ?: return emptyList()
        return list.mapNotNull { raw ->
            val s = raw as? Map<String, Any?> ?: return@mapNotNull null
            LyricCandidate(
                title = JsonLite.str(s, "SongName"),
                artist = JsonLite.str(s, "SingerName"),
                durationMs = JsonLite.long(s, "Duration") * 1000,
                payload = JsonLite.str(s, "FileHash"),
            )
        }
    }

    // ---------------------------------------------------------------- 回归断言

    /** 网易：数字 id 必须能取出来（之前 `JsonLite.str` → 空串 → 整条渠道废掉）。 */
    @Test
    fun netease_numericIdIsReadable() {
        val candidates = neteaseCandidates()
        assertEquals(6, candidates.size)
        assertEquals("150619", candidates.first().payload)
        assertEquals(295666L, candidates.first().durationMs)
    }

    /** 网易：选中原版（不是 Live 版、不是翻唱版、不是群星版）。 */
    @Test
    fun netease_picksAlbumOriginal() {
        val best = LyricCandidatePicker.pickBest(title, artist, songDuration, neteaseCandidates())
        assertNotNull(best)
        assertEquals("小镇姑娘", best!!.title)
        assertEquals("陶喆", best.artist)
        assertEquals("150619", best.payload)
    }

    /** QQ：`interval` 是秒 → 毫秒；选中原版。 */
    @Test
    fun qq_picksAlbumOriginal() {
        val candidates = qqCandidates()
        assertEquals(295000L, candidates.first().durationMs)
        val best = LyricCandidatePicker.pickBest(title, artist, songDuration, candidates)
        assertNotNull(best)
        assertEquals("002fWpie3IjneY", best!!.payload)
    }

    /**
     * 酷狗：**最关键的一条** —— 候选里同时有原版（295s）和电台剪辑版（213s），
     * 歌名歌手完全一样。必须选原版，否则歌词时间轴短 82 秒 → 歌词跑得比歌快。
     */
    @Test
    fun kugou_picksAlbumOriginalNotRadioEdit() {
        val candidates = kugouCandidates()
        assertTrue(candidates.any { it.durationMs == 213000L })
        val best = LyricCandidatePicker.pickBest(title, artist, songDuration, candidates)
        assertNotNull(best)
        assertEquals("7A3F9ECC73C46EAC591BAC593941682A", best!!.payload)
        assertEquals(295000L, best.durationMs)
    }

    /**
     * 车机会话**还没给出总时长**时（本地音乐来源很常见，
     * 诊断包实测第一推的 metadata 里没有 DURATION，同分取**更长**的那条。
     */
    @Test
    fun allProviders_pickOriginalEvenWhenDurationUnknown() {
        assertEquals("150619", LyricCandidatePicker.pickBest(title, artist, 0, neteaseCandidates())?.payload)
        assertEquals("002fWpie3IjneY", LyricCandidatePicker.pickBest(title, artist, 0, qqCandidates())?.payload)
        assertEquals(
            "7A3F9ECC73C46EAC591BAC593941682A",
            LyricCandidatePicker.pickBest(title, artist, 0, kugouCandidates())?.payload,
        )
    }

    /** 三家选出来的都必须是「295s 左右的原版」，长度彼此一致。 */
    @Test
    fun allProviders_agreeOnSameVersion() {
        val picked = listOf(
            LyricCandidatePicker.pickBest(title, artist, songDuration, neteaseCandidates()),
            LyricCandidatePicker.pickBest(title, artist, songDuration, qqCandidates()),
            LyricCandidatePicker.pickBest(title, artist, songDuration, kugouCandidates()),
        )
        assertTrue(picked.all { it != null })
        val durations = picked.map { it!!.durationMs }
        assertTrue(durations.all { kotlin.math.abs(it - songDuration) <= 2000L })
    }
}
