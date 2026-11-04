package com.cardesk.launcher.core.nav

import android.content.Context
import android.content.Intent
import android.os.Looper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * 「高德广播日夜 → 桌面收到」的回归测试。
 *
 * ## 为什么要有这个测试
 *
 * 高德的 `10019`（自动状态）是**一条共用通道**：
 * 导航中(8)、巡航(9)、模拟(10)、在不在地图页(3/4) 与
 * **白天(37)/黑夜(38)** 挤在同一个 `EXTRA_STATE` 上。
 *
 * 桌面以前把 `10019` 的状态值一律当「导航状态」处理，`37`/`38` 被静默丢掉，
 * 于是「跟随高德」永远读不到高德的日夜 —— 桌面和高德怎么切都对不上。
 * 这里锁住「37/38 必须走日夜通道，其它状态不能走」。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AmapDesktopGuideDayNightTest {

    /** 记录收到的日夜回调。 */
    private class Recorder : AmapDesktopGuide.Listener {
        var dayNight: Boolean? = null
        var calls = 0

        override fun onGuide(guide: AmapDesktopGuide.Guide) {}
        override fun onLanes(raw: String) {}
        override fun onTrafficLight(signal: AmapTrafficLight.Signal?) {}
        override fun onStopped() {}
        override fun onCityChanged(city: String) {}

        override fun onDayNight(nightUi: Boolean) {
            dayNight = nightUi
            calls++
        }
    }

    private fun send(context: Context, state: Int) {
        val intent = Intent(AmapDayNight.ACTION_SEND)
        intent.putExtra(AmapDayNight.KEY_TYPE, AmapDayNight.KEY_TYPE_AUTO_STATUS)
        intent.putExtra(AmapDayNight.EXTRA_STATE, state)
        context.sendBroadcast(intent)
        // Robolectric 4.x 是 PAUSED looper：广播在主线程 idle 时才派发。
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun autoStatusNight_isDeliveredAsNight() {
        val context = RuntimeEnvironment.getApplication()
        val recorder = Recorder()
        val guide = AmapDesktopGuide(context, recorder)
        guide.start()

        send(context, AmapDayNight.STATE_NIGHT)

        assertEquals("EXTRA_STATE=38 应当回「夜间」", true, recorder.dayNight)
        assertEquals(1, recorder.calls)
    }

    @Test
    fun autoStatusDay_isDeliveredAsDay() {
        val context = RuntimeEnvironment.getApplication()
        val recorder = Recorder()
        val guide = AmapDesktopGuide(context, recorder)
        guide.start()

        send(context, AmapDayNight.STATE_DAY)

        assertEquals("EXTRA_STATE=37 应当回「白天」", false, recorder.dayNight)
        assertEquals(1, recorder.calls)
    }

    @Test
    fun navigationStates_doNotTouchDayNight() {
        val context = RuntimeEnvironment.getApplication()
        val recorder = Recorder()
        val guide = AmapDesktopGuide(context, recorder)
        guide.start()

        // 导航中(8) / 巡航(9) / 模拟(10) / 在地图页(3、4) 都不是日夜
        for (state in listOf(8, 9, 10, 3, 4, 40, 50, 51)) {
            send(context, state)
        }

        assertNull("导航状态不能被当成日夜", recorder.dayNight)
        assertEquals(0, recorder.calls)
    }
}
