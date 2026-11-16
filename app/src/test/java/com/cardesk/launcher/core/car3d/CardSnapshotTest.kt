package com.cardesk.launcher.core.car3d

import com.cardesk.launcher.core.car3d.CardSnapshot.Box
import com.cardesk.launcher.core.car3d.CardSnapshot.Item
import com.cardesk.launcher.core.car3d.CardSnapshot.NormPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 快照卡胶囊布局（第167轮）——纯几何单测，不需要 Robolectric。
 *
 * 锁住设计规范 §2.4/§2.5 的三条硬约束：
 * 1. 胶囊**不出边界**、不互相重叠（同侧间距 ≥ [CardSnapshot.STACK_STEP]）；
 * 2. 异常胶囊最多 4 个，其余并成 `+N`；**胎压四轮永远全显示**；
 * 3. 锚点来自资产（归一化 0..1）；没有锚点时必须兜底排布，不能空白。
 */
class CardSnapshotTest {

    private val image = Box(100f, 200f, 456f, 450f)     // 356×250
    private val bounds = Box(50f, 180f, 700f, 500f)
    private val measure: (String) -> Float = { it.length * 7f }

    private fun tires(count: Int = 4) = List(count) { idx ->
        Item(
            text = "2.4",
            warn = false,
            anchorKey = listOf("tires.fl", "tires.fr", "tires.rl", "tires.rr")[idx],
            unit = "bar",
            priority = CardSnapshot.ALWAYS_PRIORITY,
        )
    }

    private fun warnings(count: Int) = List(count) { idx ->
        Item(
            text = listOf("左前门", "右前门", "左后门", "右后门", "尾门", "左前窗")[idx],
            warn = true,
            anchorKey = listOf("lf", "rf", "lb", "rb", "trunk", "lf")[idx],
        )
    }

    @Test
    fun `全关时只显示四个胎压胶囊`() {
        val pills = CardSnapshot.layout(tires(), null, image, bounds, measure)
        assertEquals(4, pills.size)
        assertTrue(pills.all { !it.warn })
        assertTrue(pills.all { it.unit == "bar" })
    }

    @Test
    fun `胶囊不出边界且同侧不重叠`() {
        val pills = CardSnapshot.layout(warnings(4) + tires(), null, image, bounds, measure)
        assertEquals(8, pills.size)
        for (p in pills) {
            assertTrue("胶囊左侧越界：$p", p.left >= bounds.left - 0.01f)
            assertTrue("胶囊右侧越界：$p", p.right <= bounds.right + 0.01f)
            assertTrue("胶囊上侧越界：$p", p.top >= bounds.top - 0.01f)
            assertTrue("胶囊下侧越界：$p", p.bottom <= bounds.bottom + 0.01f)
        }
        // 左侧一组按 y 排序后间距 ≥ STACK_STEP
        for (leftSide in listOf(true, false)) {
            val group = pills.filter {
                if (leftSide) it.left < image.left + image.width * 0.5f else it.left > image.left + image.width * 0.5f
            }.sortedBy { it.centerY }
            for (i in 1 until group.size) {
                assertTrue(
                    "同侧胶囊重叠：${group[i - 1].text} / ${group[i].text}",
                    group[i].centerY - group[i - 1].centerY >= CardSnapshot.STACK_STEP - 0.01f,
                )
            }
        }
    }

    @Test
    fun `异常胶囊超过四个时其余并成加N`() {
        val pills = CardSnapshot.layout(warnings(5) + tires(), null, image, bounds, measure)
        assertEquals(8, pills.size) // 4 常驻胎压 + 4 个（3 异常 + 1 个 +N）
        assertTrue("应有 +N 汇总胶囊", pills.any { it.text == "+2" })
        assertTrue("胎压必须四个都在", pills.count { it.unit == "bar" } == 4)
    }

    @Test
    fun `胎压胶囊始终四个不受异常数量影响`() {
        val pills = CardSnapshot.layout(warnings(6) + tires(), null, image, bounds, measure)
        assertEquals(4, pills.count { it.unit == "bar" })
    }

    @Test
    fun `资产锚点决定胶囊在车的哪一侧`() {
        val anchors = mapOf(
            "lf" to NormPoint(0.15f, 0.30f),
            "rf" to NormPoint(0.90f, 0.70f),
            "tires.fl" to NormPoint(0.10f, 0.60f),
            "tires.fr" to NormPoint(0.95f, 0.60f),
            "tires.rl" to NormPoint(0.12f, 0.85f),
            "tires.rr" to NormPoint(0.93f, 0.85f),
        )
        val pills = CardSnapshot.layout(warnings(2) + tires(), anchors, image, bounds, measure)
        val lf = pills.first { it.text == "左前门" }
        val rf = pills.first { it.text == "右前门" }
        assertTrue("左前门应在车左侧", lf.right < image.left + image.width / 2f)
        assertTrue("右前门应在车右侧", rf.left > image.left + image.width / 2f)
        // 引线终点必须落在锚点上（归一化 → 像素）
        assertEquals(image.left + 0.15f * image.width, lf.leadX, 0.5f)
        assertEquals(image.top + 0.30f * image.height, lf.leadY, 0.5f)
    }

    @Test
    fun `没有锚点时沿车模框两侧兜底排布`() {
        val pills = CardSnapshot.layout(warnings(4) + tires(), null, image, bounds, measure)
        // 左右两侧都要用到，且纵向散开（不是全叠一处）
        val left = pills.filter { it.left < image.left + image.width / 2f }
        val right = pills.filter { it.left > image.left + image.width / 2f }
        assertTrue("兜底排布两侧都应有胶囊", left.isNotEmpty() && right.isNotEmpty())
        val ys = pills.map { it.centerY }.distinct()
        assertTrue("兜底排布纵向应分散，实际只有 ${ys.size} 个 y", ys.size >= 4)
    }

    @Test
    fun `有锚点时胶囊贴近车体且引线不超过40px`() {
        val anchors = mapOf(
            "lf" to NormPoint(0.20f, 0.30f),
            "rf" to NormPoint(0.80f, 0.30f),
            "trunk" to NormPoint(0.50f, 0.20f),
            "tires.fl" to NormPoint(0.12f, 0.70f),
            "tires.fr" to NormPoint(0.88f, 0.70f),
            "tires.rl" to NormPoint(0.14f, 0.90f),
            "tires.rr" to NormPoint(0.86f, 0.90f),
        )
        val pills = CardSnapshot.layout(warnings(3) + tires(), anchors, image, bounds, measure)
        for (p in pills) {
            if (!p.leader) continue
            // 锚点在胶囊右侧 → 靠车一侧是 p.right，反之 p.left
            val edge = if (p.leadX >= p.left + p.width / 2f) p.right else p.left
            val gap = kotlin.math.abs(p.leadX - edge)
            assertTrue("胶囊到锚点间距应 26..36：${p.text} gap=$gap", gap >= CardSnapshot.GAP_MIN - 0.5f && gap <= CardSnapshot.GAP_MAX + 4f)
            val leader = kotlin.math.hypot(p.leadX - p.leadFromX, p.leadY - p.leadFromY)
            assertTrue("引线应 ≤40px：${p.text} len=$leader", leader <= CardSnapshot.LEADER_MAX + 1f)
        }
    }

    @Test
    fun `加N贴在最后一个异常胶囊下方`() {
        val pills = CardSnapshot.layout(warnings(5) + tires(), null, image, bounds, measure)
        val plus = pills.first { it.text == "+2" }
        val lastWarn = pills.filter { it.warn }.maxBy { it.bottom }
        assertTrue("+N 应在最后一个异常胶囊下方", plus.top >= lastWarn.bottom - 0.5f)
        assertTrue("+N 不应离开卡片", plus.bottom <= bounds.bottom + 0.5f && plus.left >= bounds.left - 0.5f)
    }

    // —— 第174轮：状态文字必须包含车窗（0/1/2/≥3 处） ——

    private fun parts(doors: List<String> = emptyList(), windows: List<String> = emptyList(), trunk: Boolean = false) =
        CardSnapshot.statusParts(doors, windows, trunk)

    @Test
    fun `状态文字_零处未关_全车已关`() {
        val p = parts()
        assertEquals(1, p.size)
        assertEquals("全车已关", p[0].text)
        assertEquals(CardSnapshot.StatusKind.NONE, p[0].kind)
    }

    @Test
    fun `状态文字_一处未关_列出具体位置并区分门与窗`() {
        val door = parts(doors = listOf("左前门"))
        assertEquals(listOf("左前门"), door.map { it.text })
        assertEquals(CardSnapshot.StatusKind.DOOR, door[0].kind)

        val win = parts(windows = listOf("左前窗"))
        assertEquals(listOf("左前窗"), win.map { it.text })
        assertEquals(CardSnapshot.StatusKind.WINDOW, win[0].kind)
    }

    @Test
    fun `状态文字_两处未关_逐个列出含尾门`() {
        val p = parts(doors = listOf("左前门"), windows = listOf("左前窗"))
        assertEquals(listOf("左前门", "左前窗"), p.map { it.text })
        assertEquals(listOf(CardSnapshot.StatusKind.DOOR, CardSnapshot.StatusKind.WINDOW), p.map { it.kind })

        val withTrunk = parts(doors = listOf("后备箱和门"), trunk = true)
        assertEquals(listOf("后备箱和门", "后备箱"), withTrunk.map { it.text })
    }

    @Test
    fun `状态文字_三处及以上_合并成一句`() {
        val mixed = parts(doors = listOf("左前门"), windows = listOf("左前窗", "右前窗"))
        assertEquals(1, mixed.size)
        assertEquals("车门、窗未关", mixed[0].text)
        assertEquals(CardSnapshot.StatusKind.GENERIC, mixed[0].kind)

        assertEquals("车门未关", parts(doors = listOf("左前门", "右前门", "左后门"))[0].text)
        assertEquals("车窗未关", parts(windows = listOf("左前窗", "右前窗", "左后窗"))[0].text)
        // 尾门也算一处：门 + 箱 + 窗 = 3 处 → 合并
        assertEquals("车门、窗未关", parts(doors = listOf("左前门"), windows = listOf("左前窗"), trunk = true)[0].text)
    }

    @Test
    fun `空列表返回空结果`() {
        assertTrue(CardSnapshot.layout(emptyList(), null, image, bounds, measure).isEmpty())
        assertNotNull(image)
    }
}
