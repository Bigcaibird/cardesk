package com.cardesk.launcher.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 第228轮：画中画渲染路线的纯逻辑。
 *
 * ## 路线本身没改，改的是「这条路线要补齐什么」
 *
 * 第226轮写的是「窗口透明 ⇒ 必须走镜像路线」，理由是
 * 「透明窗口下承载层的图层不再被窗口轮廓裁剪 ⇒ 圆角失效 / 画面错位 / 切页残留」。
 *
 * 2026-11-20 在领克真车（Android 9 / API 28）上复核，**这个前提成立**：
 * 壁纸开（窗口半透明）⇒ 地图卡左上角是**直角**；壁纸关（窗口不透明）⇒ 圆角成立。
 *
 * 真车同时暴露了镜像路线**缺三样东西**（不是路线选错）：
 * 触控转发、`dontOverrideDisplayInfo`、以及一个与路线无关的玻璃条坐标错位。
 * 这三样在第228轮分别补在 `PipMirrorHost` 与 `NavPage` 里。
 *
 * 详见 `core/PipRenderPlan.kt` 的类注释（带逐条真车证据）。
 */
class PipRenderPlanTest {

    @Test
    fun `窗口不透明时走旧的图层路线`() {
        assertEquals(PipRenderPlan.Mode.LAYER, PipRenderPlan.modeFor(translucentWindow = false))
    }

    @Test
    fun `窗口透明时必须走窗口内渲染`() {
        // 半透明窗口下 `ActivityView` 的画面是独立图层、不受窗口轮廓裁剪 ⇒ 圆角失效。
        // 真车实测确认（壁纸开 ⇒ 直角，壁纸关 ⇒ 圆角）。
        assertEquals(PipRenderPlan.Mode.MIRROR, PipRenderPlan.modeFor(translucentWindow = true))
    }

    @Test
    fun `两条路线是互斥的枚举值`() {
        // 防止有人后续把 MIRROR 删了又让 LAYER 兜底：两种输入必须给出不同结果
        val opaque = PipRenderPlan.modeFor(false)
        val translucent = PipRenderPlan.modeFor(true)
        assertEquals(listOf(PipRenderPlan.Mode.LAYER, PipRenderPlan.Mode.MIRROR), listOf(opaque, translucent))
    }

    @Test
    fun `兜底路线与主路线相反`() {
        // 主路线建不起来时要能换另一条（ActivityView ↔ 镜像）。
        assertEquals(PipRenderPlan.Mode.MIRROR, PipRenderPlan.fallbackModeFor(translucentWindow = false))
        assertEquals(PipRenderPlan.Mode.LAYER, PipRenderPlan.fallbackModeFor(translucentWindow = true))
    }

    // —— 第228轮：路线变了要重建 ——
    //
    // 真车实测：用户在设置里**关掉视频壁纸** ⇒ 窗口变不透明，
    // 但承载面还挂着**镜像路线**的内容（`show()` 是「同包名复用」的）⇒
    // 全屏右侧缺一块、拖动很卡 ⇒ 用户反馈「以前不用视频壁纸一切正常」。
    //
    // ⇒ 判据一变就必须重建承载面。

    @Test
    fun `路线没变就不重建`() {
        assertFalse(PipRenderPlan.needsRebuild(PipRenderPlan.Mode.LAYER, PipRenderPlan.Mode.LAYER))
        assertFalse(PipRenderPlan.needsRebuild(PipRenderPlan.Mode.MIRROR, PipRenderPlan.Mode.MIRROR))
    }

    @Test
    fun `路线变了必须重建`() {
        // 窗口从半透明变不透明 ⇒ 镜像 → 图层
        assertTrue(PipRenderPlan.needsRebuild(PipRenderPlan.Mode.MIRROR, PipRenderPlan.Mode.LAYER))
        // 窗口从不透明变半透明 ⇒ 图层 → 镜像
        assertTrue(PipRenderPlan.needsRebuild(PipRenderPlan.Mode.LAYER, PipRenderPlan.Mode.MIRROR))
    }

    @Test
    fun `没在承载时不重建`() {
        // `current == null` = 没在承载 / 降级成静态卡片 ⇒ 没有「旧路线」可言，
        // 不能因为这个判定去反复重建一张降级卡片。
        assertFalse(PipRenderPlan.needsRebuild(null, PipRenderPlan.Mode.LAYER))
        assertFalse(PipRenderPlan.needsRebuild(null, PipRenderPlan.Mode.MIRROR))
    }
}
