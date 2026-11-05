package com.cardesk.launcher.vehicle

import android.os.Looper
import com.ecarx.xui.adaptapi.device.FakeDayNightMode
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/**
 * AdaptAPI 昼夜通道（`Device.getDayNightMode()`）的反射路径测试。
 *
 * 用同名同签名的**假实现**（`com/ecarx/xui/adaptapi/device/`）把反射跑通：
 * 类名、方法名、动态代理注册、回调派发、合并延时，全是真路径。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AdaptapiDayNightTest {

    @Before
    fun setUp() {
        AdaptapiDayNight.resetForTest()
        // 第116轮：假实现的回调列表是**进程内静态**的，别的测试类（比如起完整桌面的
        // 启动测试）也会真的 attach 一次昼夜通道，把回调注册进来。
        // 这个用例断言的是「**一次 attach 流程里**只注册一次」，
        // 所以先把上一位留下的清干净，别把别的测试的残留算到自己头上。
        FakeDayNightMode.callbacks.clear()
    }

    @After
    fun tearDown() {
        AdaptapiDayNight.resetForTest()
        FakeDayNightMode.callbacks.clear()
    }

    private fun idle(ms: Long = 600L) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    }

    @Test
    fun attach_readsActualDayNightFromChannel() {
        val context = RuntimeEnvironment.getApplication()
        assertTrue("应当能接上车机昼夜通道", AdaptapiDayNight.attach(context, force = true))

        FakeDayNightMode.display = 1
        assertEquals(false, AdaptapiDayNight.nightUi())

        FakeDayNightMode.display = 2
        assertEquals(true, AdaptapiDayNight.nightUi())
    }

    @Test
    fun attach_isIdempotent_andRegistersCallbackOnce() {
        val context = RuntimeEnvironment.getApplication()
        AdaptapiDayNight.attach(context, force = true)
        AdaptapiDayNight.attach(context, force = true)
        AdaptapiDayNight.attach(context, force = true)

        assertEquals("回调只应注册一次", 1, FakeDayNightMode.callbacks.size)
    }

    @Test
    fun displayCallback_reachesListener() {
        val context = RuntimeEnvironment.getApplication()
        AdaptapiDayNight.attach(context, force = true)

        val seen = mutableListOf<Boolean>()
        AdaptapiDayNight.listener = { seen += it }

        FakeDayNightMode.fireDisplay(2)
        idle()

        assertEquals(listOf(true), seen)
    }

    @Test
    fun modeCallback_autoFallsBackToActualDisplay() {
        val context = RuntimeEnvironment.getApplication()
        AdaptapiDayNight.attach(context, force = true)

        val seen = mutableListOf<Boolean>()
        AdaptapiDayNight.listener = { seen += it }

        // 模式变成「跟随」(3)：模式本身不定明暗 → 读实际明暗（此时是白天）
        FakeDayNightMode.display = 1
        FakeDayNightMode.fireMode(3)
        idle()
        assertEquals(listOf(false), seen)

        // 模式变成固定夜间 → 夜间
        FakeDayNightMode.fireMode(2)
        idle()
        assertEquals(listOf(false, true), seen)
    }

    @Test
    fun callback_isDebouncedToLastValue() {
        val context = RuntimeEnvironment.getApplication()
        AdaptapiDayNight.attach(context, force = true)

        val seen = mutableListOf<Boolean>()
        AdaptapiDayNight.listener = { seen += it }

        // 明暗边界上会连着推好几帧：不合并的话配色会来回闪
        FakeDayNightMode.fireDisplay(2)
        FakeDayNightMode.fireDisplay(1)
        FakeDayNightMode.fireDisplay(2)
        idle()

        assertEquals("合并窗口内只应下发最后一个", listOf(true), seen)
    }

    @Test
    fun detach_stopsDeliveringToListener() {
        val context = RuntimeEnvironment.getApplication()
        AdaptapiDayNight.attach(context, force = true)

        val seen = mutableListOf<Boolean>()
        AdaptapiDayNight.listener = { seen += it }
        AdaptapiDayNight.detach()

        FakeDayNightMode.fireDisplay(2)
        idle()

        assertTrue("断开后不该再派发", seen.isEmpty())
    }

    @Test
    fun nightUi_isNullWhenChannelUnavailable() {
        // 没挂上（类不存在 / 车机服务没连上）时读不到 → null，交给下一个来源
        assertNull(AdaptapiDayNight.nightUi())
    }
}
