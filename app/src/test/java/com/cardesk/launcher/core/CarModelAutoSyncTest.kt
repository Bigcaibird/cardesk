package com.cardesk.launcher.core

import com.cardesk.launcher.core.car3d.CarModelAutoSync
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [CarModelAutoSync] 的纯 JVM 单测。
 *
 * 只覆盖「不碰 Android、不碰网络」的那半边：
 * - [CarModelAutoSync.expectedDirs] 由 [CarModels.MODEL_IDS] / [CarModels.assetDir] 推导，
 *   和车型表是同一份数据源；
 * - [CarModelAutoSync.missingDirs] 是纯函数，补齐逻辑的**终止条件**就是它返回空列表，
 *   所以这里把「空 / 全 / 缺一个 / 多出无关目录」四种输入都钉住。
 *
 * 不起 Robolectric：这两件事都不需要 Context，对象本身也没有静态 Android 依赖
 * （Handler 只在真正回调时才创建）。和 `CarModelRemoteTest` 一样走默认 JVM 即可。
 */
class CarModelAutoSyncTest {

    @Test
    fun `expectedDirs 顺序跟随车型表`() {
        // 期望顺序来自 MODEL_IDS 的遍历顺序：01/02/02HB → lynk01，03/03+ → lynk03，05 → lynk05，
        // 去重后是 lynk01 → lynk03 → lynk05。日志与断言都依赖这个稳定性。
        assertEquals(
            listOf("lynk01", "lynk03", "lynk05"),
            CarModelAutoSync.expectedDirs().toList(),
        )
    }

    @Test
    fun `expectedDirs 就是三个车型目录`() {
        assertEquals(
            setOf("lynk01", "lynk03", "lynk05"),
            CarModelAutoSync.expectedDirs(),
        )
        // 去重后不能有重复项，也不能漏成两个（02/02HB 与 01 共用 lynk01）
        assertEquals(3, CarModelAutoSync.expectedDirs().size)
    }

    @Test
    fun `每个车型都能映射到 expectedDirs 里的目录`() {
        // 防止以后加了车型却忘了烘焙资产：要么 assetDir 有值且已在 expectedDirs 里，
        // 要么明确返回 null（表示这台车没有资产、运行时回退 2D）
        for (modelId in CarModels.MODEL_IDS) {
            val dir = CarModels.assetDir(modelId)
            if (dir != null) {
                assertTrue("车型 $modelId 的目录 $dir 不在 expectedDirs 里", dir in CarModelAutoSync.expectedDirs())
            }
        }
    }

    @Test
    fun `重试间隔是五分钟`() {
        // 5 分钟 = 300000ms；改这个值等于改用户可见的重试节奏，单测钉住
        assertEquals(300_000L, CarModelAutoSync.RETRY_INTERVAL_MS)
        assertEquals(5 * 60 * 1000L, CarModelAutoSync.RETRY_INTERVAL_MS)
    }

    @Test
    fun `本地一个都没有时三个目录全缺`() {
        assertEquals(
            listOf("lynk01", "lynk03", "lynk05"),
            CarModelAutoSync.missingDirs(emptySet()),
        )
    }

    @Test
    fun `本地齐全时不缺任何目录`() {
        val installed = setOf("lynk01", "lynk03", "lynk05")
        assertTrue(CarModelAutoSync.missingDirs(installed).isEmpty())
    }

    @Test
    fun `缺一个时只报那一个`() {
        assertEquals(
            listOf("lynk03"),
            CarModelAutoSync.missingDirs(setOf("lynk01", "lynk05")),
        )
        assertEquals(
            listOf("lynk01"),
            CarModelAutoSync.missingDirs(setOf("lynk03", "lynk05")),
        )
        assertEquals(
            listOf("lynk05"),
            CarModelAutoSync.missingDirs(setOf("lynk01", "lynk03")),
        )
    }

    @Test
    fun `多出的无关目录不算缺`() {
        // 版本表里可能有别的车型 / 预览目录，它们不该让「已齐全」判断失效
        val installed = setOf("lynk01", "lynk03", "lynk05", "lynk99", "lynk01-preview")
        assertTrue(CarModelAutoSync.missingDirs(installed).isEmpty())
    }

    @Test
    fun `无关目录不能顶替真正缺的目录`() {
        // 只装了 lynk01 + 一个无关目录：仍然缺 lynk03 / lynk05，且顺序不变
        assertEquals(
            listOf("lynk03", "lynk05"),
            CarModelAutoSync.missingDirs(setOf("lynk01", "lynk99")),
        )
    }

    @Test
    fun `只有无关目录时等于一个都没有`() {
        assertEquals(
            listOf("lynk01", "lynk03", "lynk05"),
            CarModelAutoSync.missingDirs(setOf("foo", "lynk99")),
        )
    }
}
