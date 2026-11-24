package com.cardesk.launcher.ui

import android.content.Context
import com.cardesk.launcher.core.car3d.Car3dAssets
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import com.cardesk.launcher.core.car3d.TestCarAssets
import org.junit.Rule

/**
 * 第179轮：「车况卡视角」设置行在纯俯视资产下整行隐藏。
 *
 * 背景：01/03/05 都改成「只有 cardtop 的 32 态俯视资产」后，卡固定俯视，
 * 三视角（3/4 侧视 / 俯视 / 正前 3/4）切换没有意义，用户拍板隐藏该行。
 * 判定必须**资产驱动**：将来换回多视角资产这一行要能自动回来。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1920dp-h1080dp-land")
class SettingsCardViewRowTest {
    @get:Rule val carAssets = TestCarAssets.Rule()

    private fun model(views: Map<String, Car3dAssets.ViewSpec>): Car3dAssets.Model =
        Car3dAssets.Model(
            dir = "test",
            model = "test",
            name = "测试车",
            views = views,
            states = setOf("closed"),
            anims = emptyMap(),
            attribution = "3D 模型：测试",
        )

    private fun spec() = Car3dAssets.ViewSpec(356, 264, 356, 264)

    @Test
    fun `纯俯视资产 视角行不出现`() {
        val topOnly = model(mapOf(Car3dAssets.VIEW_CARDTOP to spec()))
        assertTrue("只声明 cardtop 应判为纯俯视", Car3dAssets.isTopDownOnly(topOnly))
        assertFalse(
            "纯俯视资产下不应显示「车况卡视角」行",
            showCardViewRow(car3dEnabled = true, topDownOnly = Car3dAssets.isTopDownOnly(topOnly)),
        )
    }

    @Test
    fun `三视角资产 视角行出现`() {
        val threeView = model(
            mapOf(
                Car3dAssets.VIEW_CARDTOP to spec(),
                Car3dAssets.VIEW_HERO to spec(),
                Car3dAssets.VIEW_CARD to spec(),
            ),
        )
        assertFalse("有多视角就不算纯俯视", Car3dAssets.isTopDownOnly(threeView))
        assertTrue(
            "多视角资产下应显示「车况卡视角」行",
            showCardViewRow(car3dEnabled = true, topDownOnly = Car3dAssets.isTopDownOnly(threeView)),
        )
    }

    @Test
    fun `写实车模总开关关闭时 视角行也不出现`() {
        val threeView = model(
            mapOf(Car3dAssets.VIEW_CARDTOP to spec(), Car3dAssets.VIEW_HERO to spec()),
        )
        assertFalse(
            "写实车模关掉后 视角设置无意义",
            showCardViewRow(car3dEnabled = false, topDownOnly = Car3dAssets.isTopDownOnly(threeView)),
        )
    }

    @Test
    fun `当前三台车资产实测都是纯俯视`() {
        val context: Context = RuntimeEnvironment.getApplication()
        for (dir in listOf("lynk01", "lynk03", "lynk05")) {
            assertTrue("$dir 应是纯俯视资产", Car3dAssets.topDownOnly(context, dir))
            assertFalse(
                "$dir 下「车况卡视角」行应隐藏",
                showCardViewRow(
                    car3dEnabled = true,
                    topDownOnly = Car3dAssets.topDownOnly(context, dir),
                ),
            )
        }
    }
}
