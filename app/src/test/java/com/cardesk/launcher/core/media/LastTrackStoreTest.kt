package com.cardesk.launcher.core.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「上次歌曲状态」持久化与恢复的单测。
 *
 *
 * - 键名：
 * - 写入：
 * - 恢复：
 */
class LastTrackStoreTest {

    /** 内存版存储：让 [LastTrackStore] 能脱离 Android 在 JVM 里跑。 */
    private class MemoryStore : LastTrackStore.Storage {
        val values = LinkedHashMap<String, Any>()

        /** 批量写的次数（用来守住「一次 save 只提交一次」的写盘放大修复）。 */
        var putAllCalls = 0

        /** 批量删的次数。 */
        var removeAllCalls = 0

        override fun getString(key: String, default: String): String = values[key] as? String ?: default
        override fun getLong(key: String, default: Long): Long = values[key] as? Long ?: default
        override fun putString(key: String, value: String) {
            values[key] = value
        }

        override fun putLong(key: String, value: Long) {
            values[key] = value
        }

        override fun remove(key: String) {
            values.remove(key)
        }

        override fun putAll(values: Map<String, Any>) {
            putAllCalls++
            super.putAll(values)
        }

        override fun removeAll(keys: Collection<String>) {
            removeAllCalls++
            super.removeAll(keys)
        }
    }

    @Test
    fun load_returnsEmptyStateWhenNothingSaved() {
        val store = LastTrackStore(MemoryStore())
        val state = store.load()
        assertEquals("", state.packageName)
        assertEquals("", state.title)
        assertEquals("", state.artist)
        assertEquals(0L, state.durationMs)
        assertEquals(0L, state.positionMs)
        assertTrue(state.isEmpty)
    }

    /** —— 键名必须一字不差。 */
    @Test
    fun save_usesReferenceKeyNames() {
        val mem = MemoryStore()
        val store = LastTrackStore(mem)
        store.save(
            LastTrackStore.State(
                packageName = "com.tencent.qqmusiccar",
                title = "夜航星",
                artist = "不才",
                durationMs = 245_000L,
                positionMs = 12_000L,
            ),
        )
        assertEquals("com.tencent.qqmusiccar", mem.values["last_music_package"])
        assertEquals("夜航星", mem.values["last_song_title"])
        assertEquals("不才", mem.values["last_song_artist"])
        assertEquals(245_000L, mem.values["last_song_duration"])
        assertEquals(12_000L, mem.values["last_song_position"])
    }

    @Test
    fun saveAndLoad_roundTrips() {
        val store = LastTrackStore(MemoryStore())
        val saved = LastTrackStore.State("com.kugou.android.auto", "晴天", "周杰伦", 269_000L, 3_000L)
        store.save(saved)
        assertEquals(saved, store.load())
    }

    /**
     * —— 空串 / 非正时长**不覆盖**旧值。
     */
    @Test
    fun save_doesNotOverwriteKnownValuesWithEmptyOnes() {
        val store = LastTrackStore(MemoryStore())
        store.save(LastTrackStore.State("com.a", "夜航星", "不才", 245_000L, 1_000L))
        store.save(LastTrackStore.State("", "", "", 0L, 2_000L))
        val state = store.load()
        assertEquals("com.a", state.packageName)
        assertEquals("夜航星", state.title)
        assertEquals("不才", state.artist)
        assertEquals(245_000L, state.durationMs)
        // 进度每次都写
        assertEquals(2_000L, state.positionMs)
    }

    @Test
    fun clear_removesEverything() {
        val mem = MemoryStore()
        val store = LastTrackStore(mem)
        store.save(LastTrackStore.State("com.a", "夜航星", "不才", 245_000L, 1_000L))
        store.clear()
        assertTrue(mem.values.isEmpty())
        assertTrue(store.load().isEmpty)
    }

    /**
     * ——
     * 歌词源（本地/蓝牙/USB）的「标题」只是一句歌词，恢复时必须置空。
     */
    @Test
    fun restoredTitle_blanksTitleForLyricSource() {
        assertEquals(
            "",
            restoredTitle("com.tencent.wecarflow", "这是一句歌词", LyricMatch::isLyricTitleSource),
        )
        assertEquals(
            "夜航星",
            restoredTitle("com.tencent.qqmusiccar", "夜航星", LyricMatch::isLyricTitleSource),
        )
    }

    @Test
    fun loadRestored_appliesLyricSourceRule() {
        val store = LastTrackStore(MemoryStore())
        store.save(LastTrackStore.State("com.ecarx.multimedia", "这是一句歌词", "不才", 245_000L, 1_000L))
        val restored = store.loadRestored { LyricMatch.isLyricTitleSource(it) }
        assertEquals("", restored.title)
        // 其它字段照常恢复
        assertEquals("不才", restored.artist)
        assertEquals(245_000L, restored.durationMs)
        assertEquals(1_000L, restored.positionMs)
        assertEquals("com.ecarx.multimedia", restored.packageName)
    }

    @Test
    fun loadRestored_keepsTitleForNormalSource() {
        val store = LastTrackStore(MemoryStore())
        store.save(LastTrackStore.State("com.tencent.qqmusiccar", "夜航星", "不才", 245_000L, 1_000L))
        val restored = store.loadRestored { LyricMatch.isLyricTitleSource(it) }
        assertEquals("夜航星", restored.title)
        assertFalse(restored.isEmpty)
    }

    @Test
    fun state_isEmptyReflectsMissingContent() {
        assertTrue(LastTrackStore.State().isEmpty)
        assertFalse(LastTrackStore.State(packageName = "com.a").isEmpty)
        assertFalse(LastTrackStore.State(positionMs = 1L).isEmpty)
        assertNull(LastTrackStore.State().title.takeIf { it.isNotEmpty() })
    }

    /**
     * 一次 [LastTrackStore.save] 只提交**一次**批量写。
     *
     * SharedPreferences 的每次 都会重写整个 prefs XML；
     * 5 个键逐个写 = 一次 save 重写 5 遍 XML。播放时进度每秒落一次盘，
     * 在车机上就是每秒 5 次全量重写（掉帧 + 磨 flash）。
     */
    @Test
    fun save_commitsOnceInBatch() {
        val mem = MemoryStore()
        val store = LastTrackStore(mem)
        store.save(LastTrackStore.State("com.a", "夜航星", "不才", 245_000L, 1_000L))
        assertEquals(1, mem.putAllCalls)
        // 5 个键都要写进去（批量提交不能少键）
        assertEquals(5, mem.values.size)
    }

    /** 同样地，[LastTrackStore.clear] 也要一次提交删掉全部键。 */
    @Test
    fun clear_commitsOnceInBatch() {
        val mem = MemoryStore()
        val store = LastTrackStore(mem)
        store.save(LastTrackStore.State("com.a", "夜航星", "不才", 245_000L, 1_000L))
        store.clear()
        assertEquals(1, mem.removeAllCalls)
        assertTrue(mem.values.isEmpty())
    }
}
