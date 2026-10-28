package com.cardesk.launcher.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [CarModels] 单测（纯逻辑，不依赖 Android）。
 *
 *
 * - 车型编号集合与归一化规则；
 * - `01` / `02` / `02HB` / `05` 是 SUV 家族，`03` / `03+` 是轿车家族。
 *
 * 车模的**形状**单测在 [CarTopGeometryTest]（第四轮起坐标挪到 `CarTopGeometry`）。
 */
class CarModelsTest {

    @Test
    fun `车型集合符合预期`() {
        assertEquals(
            listOf("01", "02", "02HB", "03", "03+", "05"),
            CarModels.MODEL_IDS,
        )
    }

    @Test
    fun `归一化：空与未知一律回落到 03`() {
        assertEquals("03", CarModels.normalize(null))
        assertEquals("03", CarModels.normalize(""))
        assertEquals("03", CarModels.normalize("  "))
        assertEquals("03", CarModels.normalize("99"))
        assertEquals("03", CarModels.normalize("随便写"))
    }

    @Test
    fun `归一化：不区分大小写`() {
        assertEquals("02HB", CarModels.normalize("02hb"))
        assertEquals("03+", CarModels.normalize("03plus"))
        assertEquals("03+", CarModels.normalize("03PLUS"))
        assertEquals("01", CarModels.normalize(" 01 "))
    }

    @Test
    fun `01 和 05 是 SUV，03 是轿车`() {
        assertEquals(CarModels.Body.SUV, CarModels.bodyOf("01"))
        assertEquals(CarModels.Body.SUV, CarModels.bodyOf("05"))
        assertEquals(CarModels.Body.SEDAN, CarModels.bodyOf("03"))
    }

    @Test
    fun `同系列其余车型归属一致`() {
        // 02 轿跑 SUV / 02HB 猎装 → SUV 家族；03+ → 轿车家族
        assertEquals(CarModels.Body.SUV, CarModels.bodyOf("02"))
        assertEquals(CarModels.Body.SUV, CarModels.bodyOf("02HB"))
        assertEquals(CarModels.Body.SEDAN, CarModels.bodyOf("03+"))
    }

    @Test
    fun `远程文件名符合预期`() {
        assertEquals("vc_car_model_01.webp", CarModels.remoteFileName("01"))
        assertEquals("vc_car_model_02.webp", CarModels.remoteFileName("02"))
        assertEquals("vc_car_model_02hb.webp", CarModels.remoteFileName("02HB"))
        assertEquals("vc_car_model_03plus.webp", CarModels.remoteFileName("03+"))
        assertEquals("vc_car_model_05.webp", CarModels.remoteFileName("05"))
        // 03 是内置资源，没有远程文件
        assertNull(CarModels.remoteFileName("03"))
    }

    @Test
    fun `只有 03 是内置车型`() {
        assertTrue(CarModels.isBundled("03"))
        assertTrue(!CarModels.isBundled("01"))
        assertTrue(!CarModels.isBundled("05"))
    }
}
