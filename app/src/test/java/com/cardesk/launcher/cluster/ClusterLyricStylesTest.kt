package com.cardesk.launcher.cluster

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * 仪表歌词的**面板样式**标签 ↔ 键映射（第一百四十三轮）。
 *
 * ## 为什么这些值得用单测钉住
 *
 * 第一百三十七轮那个 bug 就是「中文标签存进了只认英文键的偏好里」：
 * 点「纯文字」→ 存进去 "纯文字" → 服务只认 "plain" → 一律落到 `else`（胶囊）。
 *
 * 现象是「点了没反应」，编译不报错、测试不报错，**只有真车上切样式才知道坏了**。
 * 而且它还会**留在用户的偏好里**——修好设置页只能保证以后存的是对的，
 * 已经存坏的机器还得靠读取方认中文标签才能恢复。
 *
 * 所以这里钉三件事：
 * 1. 标签和键一一对应（谁也不是谁）；
 * 2. **中文标签也认**（老机器能被救回来）；
 * 3. 认不出来时有个明确的兜底，不至于整行 chip 都不亮。
 */
class ClusterLyricStylesTest {

    @Test
    fun 三个样式都在() {
        assertEquals(3, ClusterLyricStyles.OPTIONS.size)
        assertEquals(listOf("胶囊", "纯文字", "发光"), ClusterLyricStyles.OPTIONS.map { it.first })
        assertEquals(
            listOf(ClusterLyricStyles.PILL, ClusterLyricStyles.PLAIN, ClusterLyricStyles.GLOW),
            ClusterLyricStyles.OPTIONS.map { it.second },
        )
    }

    @Test
    fun 标签和键一一对应() {
        // 最要命的就是这一条：标签和键**不能是同一个字符串**，
        // 否则 `chipRow` 的高亮逻辑（`options.indexOf(selected)`）
        // 和服务端的 `when` 会同时对同一个字符串生效 ——
        // 一旦哪边写错，症状就是「点了没反应」。
        for ((label, key) in ClusterLyricStyles.OPTIONS) {
            assertNotEquals("标签和键不能相同：$label", label, key)
        }
        assertEquals(3, ClusterLyricStyles.OPTIONS.map { it.first }.toSet().size)
        assertEquals(3, ClusterLyricStyles.OPTIONS.map { it.second }.toSet().size)
    }

    @Test
    fun 英文键原样返回() {
        assertEquals(ClusterLyricStyles.PILL, ClusterLyricStyles.normalize("pill"))
        assertEquals(ClusterLyricStyles.PLAIN, ClusterLyricStyles.normalize("plain"))
        assertEquals(ClusterLyricStyles.GLOW, ClusterLyricStyles.normalize("glow"))
    }

    @Test
    fun 中文标签也能归一化() {
        // 这是**救老机器**的那一步：第一百三十七轮那个 bug 往偏好里写的就是中文标签。
        assertEquals(ClusterLyricStyles.PILL, ClusterLyricStyles.normalize("胶囊"))
        assertEquals(ClusterLyricStyles.PLAIN, ClusterLyricStyles.normalize("纯文字"))
        assertEquals(ClusterLyricStyles.GLOW, ClusterLyricStyles.normalize("发光"))
    }

    @Test
    fun 认不出来的落到胶囊() {
        // 和服务里 `styleOf` 的 `else` 保持一致：不能因为一个脏值就整个面板画不出来。
        assertEquals(ClusterLyricStyles.PILL, ClusterLyricStyles.normalize(""))
        assertEquals(ClusterLyricStyles.PILL, ClusterLyricStyles.normalize("nonsense"))
        assertEquals(ClusterLyricStyles.PILL, ClusterLyricStyles.normalize("PLAIN2"))
    }

    @Test
    fun 高亮下标对得上() {
        // 设置页靠它挑 chip；之前拿英文键去中文列表里 `indexOf` → -1 → 一个都不亮。
        assertEquals(0, ClusterLyricStyles.indexOf("pill"))
        assertEquals(1, ClusterLyricStyles.indexOf("plain"))
        assertEquals(2, ClusterLyricStyles.indexOf("glow"))
        // 中文标签也给出同一个下标（老机器打开设置页要能看出当前选的是哪个）。
        assertEquals(0, ClusterLyricStyles.indexOf("胶囊"))
        assertEquals(1, ClusterLyricStyles.indexOf("纯文字"))
        assertEquals(2, ClusterLyricStyles.indexOf("发光"))
        // 认不出来时落到第 0 个，别让整行 chip 都不亮。
        assertEquals(0, ClusterLyricStyles.indexOf("???"))
    }

    @Test
    fun 标签反查() {
        assertEquals("胶囊", ClusterLyricStyles.labelOf("pill"))
        assertEquals("纯文字", ClusterLyricStyles.labelOf("plain"))
        assertEquals("发光", ClusterLyricStyles.labelOf("glow"))
        assertEquals("纯文字", ClusterLyricStyles.labelOf("纯文字"))
    }
}
