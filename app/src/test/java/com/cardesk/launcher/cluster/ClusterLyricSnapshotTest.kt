package com.cardesk.launcher.cluster

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 歌词**快照**的单测。
 *
 * ## 验的是什么
 *
 * 快照是「桌面卡片」和「仪表面板」之间唯一的**数据通路**：
 * 服务每隔 700ms 从 `MusicController` 取一次，三行文字 + 擦除比例一起冻结，
 * 面板每帧只读这份快照。
 *
 * 这样两边显示的必然是同一句、同一个擦除边界 ——
 * 这正是 UNI 那边踩过的坑（卡片读 LRC、面板读 KRC，同一首歌显示成两回事）。
 *
 * ## 重点验「空态文案」
 *
 * 三种空态要说三件不同的事：
 *
 * | 情况 | 文案 | 为什么不能混 |
 * | --- | --- | --- |
 * | 什么都没在播 | 等待播放 | 说「未提供歌词」是撒谎 |
 * | 在播但歌词还没到 | 正在获取歌词 | 说「未提供歌词」是撒谎 |
 * | 暂停且确实没词 | 当前音源未提供歌词 | —— |
 *
 * 前两种其实只持续一两帧，但用户会记住。
 */
class ClusterLyricSnapshotTest {

    @Test
    fun `默认快照没有歌词`() {
        val snapshot = ClusterLyricSnapshot()
        assertFalse(snapshot.hasLyrics)
        assertEquals(0f, snapshot.wipe, 0.0001f)
        assertEquals(0, snapshot.lyricLines)
    }

    @Test
    fun `有当前句就是有歌词`() {
        val snapshot = ClusterLyricSnapshot(currentText = "这是一句歌词")
        assertTrue(snapshot.hasLyrics)
    }

    // ------------------------------------------------------------ 空态文案

    @Test
    fun `空态一 什么都没播是等待播放`() {
        val snapshot = ClusterLyricSnapshot(title = "", playing = false)
        assertEquals("等待播放", snapshot.emptyLabel())
    }

    @Test
    fun `空态二 在播但没词是正在获取歌词`() {
        val snapshot = ClusterLyricSnapshot(title = "晴天", playing = true)
        assertEquals("正在获取歌词", snapshot.emptyLabel())
    }

    @Test
    fun `空态三 暂停且没词是当前音源未提供歌词`() {
        val snapshot = ClusterLyricSnapshot(title = "纯音乐", playing = false)
        assertEquals("当前音源未提供歌词", snapshot.emptyLabel())
    }

    @Test
    fun `有当前句时不走空态`() {
        val snapshot = ClusterLyricSnapshot(
            title = "晴天",
            playing = true,
            currentText = "故事的小黄花",
        )
        assertTrue(snapshot.hasLyrics)
        // 空态文案在有词时不参与显示，但函数本身不该崩。
        assertEquals("正在获取歌词", snapshot.emptyLabel())
    }

    // ------------------------------------------------------------ describe

    @Test
    fun `describe 在有歌词时给出行数`() {
        val snapshot = ClusterLyricSnapshot(
            title = "晴天",
            sourceLabel = "netease",
            lyricLines = 64,
        )
        assertEquals("netease 64 行", snapshot.describe())
    }

    @Test
    fun `describe 没有来源时用歌词二字`() {
        val snapshot = ClusterLyricSnapshot(title = "晴天", lyricLines = 12)
        assertEquals("歌词 12 行", snapshot.describe())
    }

    @Test
    fun `describe 没有曲目时是尚未拿到曲目`() {
        assertEquals("尚未拿到曲目", ClusterLyricSnapshot().describe())
    }

    @Test
    fun `describe 有歌没词时是正在匹配歌词`() {
        val snapshot = ClusterLyricSnapshot(title = "晴天")
        assertEquals("正在匹配歌词", snapshot.describe())
    }

    // ------------------------------------------------------------ 不可变性

    @Test
    fun `快照是不可变的 值相等即可复用`() {
        val a = ClusterLyricSnapshot(title = "晴天", currentText = "词", wipe = 0.4f)
        val b = ClusterLyricSnapshot(title = "晴天", currentText = "词", wipe = 0.4f)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }
}
