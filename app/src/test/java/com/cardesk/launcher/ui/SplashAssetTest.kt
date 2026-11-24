package com.cardesk.launcher.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.FileNotFoundException

/**
 * 启动页背景底图必须存在、且是 WebP —— 锁住两件容易悄悄坏掉的事。
 *
 * ## 为什么要单独锁
 *
 * 1. **文件名只在 `LaunchSplashView` 里拼**（`splash/splash_day.webp` / `splash_night.webp`，
 *    不带扩展名常量、没有编译期检查）。改名/删图后编译照样通过，只有装到车上才会
 *    回落纯色底 —— 那是最不容易被发现的「降级」，所以在这里用测试顶住。
 * 2. **编码格式**：这两张是平滑渐变底图，转 WebP q90 后单张 3~6KB；如果谁把它们
 *    重新导成 PNG（几百 KB），包体白涨；如果导成 JPEG（无透明但对渐变会有块状），
 *    视觉也会退化。测试按魔数（`RIFF....WEBP`）断言真实格式，不看扩展名。
 *
 * ## 为什么不断言解码成功
 *
 * Robolectric 的 `BitmapFactory` 影子实现不保证能解 WebP，断言它等于测框架而不是测
 * 项目。设备端的真实解码由「启动页截图」验证；这里只保证「资产在、格式对、体积没失控」。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class SplashAssetTest {

    private val assets get() = RuntimeEnvironment.getApplication().assets

    /** 两张底图都要在，且真的是 WebP（`RIFF` + `WEBP` 魔数）。 */
    @Test
    fun `启动页日夜底图都在而且是 WebP`() {
        for (name in listOf("splash/splash_day.webp", "splash/splash_night.webp")) {
            val bytes = assets.open(name).use { it.readBytes() }
            assertTrue("$name 太小（${bytes.size} B），不像一张 960×540 底图", bytes.size > 512)
            assertEquals("$name 不是 RIFF 容器", "RIFF", String(bytes, 0, 4, Charsets.US_ASCII))
            assertEquals("$name 不是 WebP", "WEBP", String(bytes, 8, 4, Charsets.US_ASCII))
        }
    }

    /** 单张体积上限：转成 PNG 的话一张就 100~220KB，这里卡住不让它回到那个量级。 */
    @Test
    fun `启动页底图体积没有回退成 PNG 量级`() {
        for (name in listOf("splash/splash_day.webp", "splash/splash_night.webp")) {
            val size = assets.open(name).use { it.available().toLong() }
            assertTrue("$name 有 ${size}B，超过 20KB 说明可能又被导出成大图了", size < 20_000)
        }
    }

    /** 老的 PNG 名字必须彻底消失，避免有人「两份都留着」导致改了一处不生效。 */
    @Test
    fun `老的 PNG 底图已经不存在`() {
        for (name in listOf("splash/splash_day.png", "splash/splash_night.png")) {
            var opened = false
            try {
                assets.open(name).close()
                opened = true
            } catch (_: FileNotFoundException) {
            }
            assertTrue("$name 仍然存在，应该只保留 WebP 一份", !opened)
        }
    }
}
