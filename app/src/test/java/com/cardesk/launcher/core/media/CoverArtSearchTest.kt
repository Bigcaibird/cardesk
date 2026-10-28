package com.cardesk.launcher.core.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 在线封面搜索。
 *
 * 判定链：QQ 音乐搜索 → 酷狗搜索 → [LyricMatch.score] 最高分 ≥ 80 才算命中。
 */
class CoverArtSearchTest {

    // ------------------------------------------------------------ QQ 音乐

    @Test
    fun pickQq_picksBestScoringCandidate() {
        val root = mapOf(
            "data" to mapOf(
                "song" to mapOf(
                    "list" to listOf(
                        mapOf(
                            "songname" to "海阔天空",
                            "singer" to listOf(mapOf("name" to "BEYOND")),
                            "interval" to 324,
                            "albummid" to "002qcJuX3lO3EZ",
                        ),
                        mapOf(
                            "songname" to "海阔天空",
                            "singer" to listOf(mapOf("name" to "BEYOND")),
                            "interval" to 239,
                            "albummid" to "00449cf44ccf8n",
                        ),
                    ),
                ),
            ),
        )
        // 期望时长 240s → 第二条（239s）时长更接近，得分更高
        val hit = CoverArtSearch.pickQq(root, "海阔天空", "BEYOND", 240_000L)
        assertNotNull("应该命中", hit)
        assertEquals("00449cf44ccf8n", hit!!.imageUrl.removePrefix(CoverArtSearch.QQ_COVER_PREFIX).removeSuffix(".jpg"))
        assertTrue("得分应达标：${hit.score}", hit.score >= CoverArtSearch.MIN_SCORE)
    }

    @Test
    fun pickQq_skipsCandidatesWithoutAlbumMid() {
        val root = mapOf(
            "data" to mapOf(
                "song" to mapOf(
                    "list" to listOf(
                        mapOf(
                            "songname" to "海阔天空 (现场)",
                            "singer" to listOf(mapOf("name" to "BEYOND")),
                            "interval" to 204,
                            "albummid" to "",
                        ),
                    ),
                ),
            ),
        )
        assertNull("没有 albummid 的候选要跳过", CoverArtSearch.pickQq(root, "海阔天空", "BEYOND", 240_000L))
    }

    @Test
    fun pickQq_returnsNullWhenScoreTooLow() {
        val root = mapOf(
            "data" to mapOf(
                "song" to mapOf(
                    "list" to listOf(
                        mapOf(
                            "songname" to "完全不同的歌",
                            "singer" to listOf(mapOf("name" to "别人")),
                            "interval" to 100,
                            "albummid" to "abc",
                        ),
                    ),
                ),
            ),
        )
        assertNull("同名不同歌不能命中", CoverArtSearch.pickQq(root, "海阔天空", "BEYOND", 240_000L))
    }

    @Test
    fun pickQq_handlesMissingStructure() {
        assertNull(CoverArtSearch.pickQq(null, "a", "b", 0L))
        assertNull(CoverArtSearch.pickQq(emptyMap<String, Any?>(), "a", "b", 0L))
        assertNull(CoverArtSearch.pickQq(mapOf("data" to "notamap"), "a", "b", 0L))
    }

    // ------------------------------------------------------------ 酷狗

    @Test
    fun pickKugou_picksBestScoringCandidate() {
        val root = mapOf(
            "data" to mapOf(
                "lists" to listOf(
                    mapOf(
                        "SongName" to "海阔天空",
                        "SingerName" to "BEYOND",
                        "Duration" to 324,
                        "Image" to "http://imge.kugou.com/stdmusic/{size}/a.jpg",
                    ),
                    mapOf(
                        "SongName" to "海阔天空",
                        "SingerName" to "BEYOND",
                        "Duration" to 239,
                        "Image" to "http://imge.kugou.com/stdmusic/{size}/b.jpg",
                    ),
                ),
            ),
        )
        val hit = CoverArtSearch.pickKugou(root, "海阔天空", "BEYOND", 240_000L)
        assertNotNull("应该命中", hit)
        assertTrue("命中的是时长更接近的那条", hit!!.imageUrl.endsWith("/b.jpg"))
    }

    @Test
    fun pickKugou_skipsCandidatesWithoutImage() {
        val root = mapOf(
            "data" to mapOf(
                "lists" to listOf(
                    mapOf(
                        "SongName" to "海阔天空",
                        "SingerName" to "BEYOND",
                        "Duration" to 239,
                        "Image" to "",
                    ),
                ),
            ),
        )
        assertNull(CoverArtSearch.pickKugou(root, "海阔天空", "BEYOND", 240_000L))
    }

    @Test
    fun pickKugou_returnsNullWhenScoreTooLow() {
        val root = mapOf(
            "data" to mapOf(
                "lists" to listOf(
                    mapOf(
                        "SongName" to "别人的歌",
                        "SingerName" to "别人",
                        "Duration" to 100,
                        "Image" to "http://imge.kugou.com/stdmusic/{size}/c.jpg",
                    ),
                ),
            ),
        )
        assertNull(CoverArtSearch.pickKugou(root, "海阔天空", "BEYOND", 240_000L))
    }

    // ------------------------------------------------------------ URL 拼装

    @Test
    fun qqCoverUrl_matchesReferenceFormat() {
        assertEquals(
            "https://y.gtimg.cn/music/photo_new/T002R500x500M00000449cf44ccf8n.jpg",
            CoverArtSearch.qqCoverUrl("00449cf44ccf8n"),
        )
    }

    @Test
    fun kugouCoverUrl_replacesSizeToken() {
        assertEquals(
            "https://imge.kugou.com/stdmusic/400/a.jpg",
            CoverArtSearch.kugouCoverUrl("http://imge.kugou.com/stdmusic/{size}/a.jpg"),
        )
    }

    @Test
    fun kugouCoverUrl_keepsHttpsUntouched() {
        assertEquals(
            "https://imge.kugou.com/stdmusic/400/a.jpg",
            CoverArtSearch.kugouCoverUrl("https://imge.kugou.com/stdmusic/400/a.jpg"),
        )
    }

    @Test
    fun keyword_appendsArtistOnlyWhenPresent() {
        assertEquals("海阔天空 Beyond", CoverArtSearch.keyword("海阔天空", "Beyond"))
        assertEquals("海阔天空", CoverArtSearch.keyword("海阔天空", ""))
        assertEquals("海阔天空", CoverArtSearch.keyword("海阔天空", "   "))
    }

    @Test
    fun endpointsMatchReference() {
        assertTrue(CoverArtSearch.QQ_SEARCH.startsWith("https://c.y.qq.com/soso/fcgi-bin/client_search_cp"))
        assertTrue(CoverArtSearch.QQ_SEARCH.contains("format=json"))
        assertTrue(CoverArtSearch.QQ_REFERER.contains("y.qq.com"))
        assertTrue(CoverArtSearch.KUGOU_SEARCH.startsWith("https://songsearch.kugou.com/song_search_v2"))
        assertTrue(CoverArtSearch.KUGOU_SEARCH.contains("platform=WebFilter"))
        assertEquals(80, CoverArtSearch.MIN_SCORE)
    }

    // ------------------------------------------------------------ 记忆键

    @Test
    fun songKey_prefersMediaId() {
        assertEquals("id:abc", CoverLoader.songKey("abc", "海阔天空", "BEYOND"))
    }

    @Test
    fun songKey_fallsBackToCleanedTitle() {
        val key = CoverLoader.songKey(null, "海阔天空", "BEYOND")
        assertTrue("应以 t: 开头：$key", key.startsWith("t:"))
    }

    @Test
    fun songKey_isStableForSameSong() {
        assertEquals(
            CoverLoader.songKey(null, "海阔天空", "BEYOND"),
            CoverLoader.songKey(null, "海阔天空", "BEYOND"),
        )
    }

    // ------------------------------------------------------------ 缓存上限

    @Test
    fun cacheLimitsAreCapped() {
        // 车机存储紧张：磁盘缓存必须封顶
        assertTrue("磁盘缓存上限应 ≤ 32MB", CoverLoader.DISK_CACHE_MAX_BYTES <= 32L * 1024 * 1024)
        assertTrue("磁盘缓存文件数应 ≤ 64", CoverLoader.DISK_CACHE_MAX_FILES <= 64)
        assertTrue("内存缓存应 ≤ 16MB", CoverLoader.MEM_CACHE_KB <= 16 * 1024)
        assertTrue("单张下载上限应 ≤ 8MB", CoverLoader.MAX_DOWNLOAD_BYTES <= 8L * 1024 * 1024)
    }

    // ------------------------------------------------------------ 进度夹取

    @Test
    fun clampPosition_capsAtDuration() {
        // 外推的进度不能走出总时长（实测显示 6:12 / 总长 3:59）
        assertEquals(239_000L, MusicSessionTracker.clampPosition(372_000L, 239_000L))
    }

    @Test
    fun clampPosition_keepsValidPosition() {
        assertEquals(30_000L, MusicSessionTracker.clampPosition(30_000L, 239_000L))
    }

    @Test
    fun clampPosition_floorsAtZero() {
        assertEquals(0L, MusicSessionTracker.clampPosition(-1_000L, 239_000L))
    }

    @Test
    fun clampPosition_unknownDurationOnlyFloors() {
        assertEquals(372_000L, MusicSessionTracker.clampPosition(372_000L, 0L))
        assertEquals(0L, MusicSessionTracker.clampPosition(-5L, 0L))
    }
}
