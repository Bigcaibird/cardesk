package com.cardesk.launcher.ui

import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import com.cardesk.launcher.core.Palettes
import com.cardesk.launcher.core.Prefs
import com.cardesk.launcher.vehicle.HvacCodec
import com.cardesk.launcher.vehicle.VehicleController
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1920dp-h1080dp-land")
class ClimateCabinTest {

    @Before
    fun setUp() {
        val ctx = RuntimeEnvironment.getApplication()
        VehicleController.init(ctx, Prefs(ctx))
        VehicleController.setBlowingMask(1) // 仅吹脸
    }

    private fun tap(view: View, x: Float, y: Float) {
        val now = SystemClock.uptimeMillis()
        view.dispatchTouchEvent(MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0))
        view.dispatchTouchEvent(MotionEvent.obtain(now, now + 20, MotionEvent.ACTION_UP, x, y, 0))
    }

    @Test
    fun `tapping cabin airflow buttons toggles corresponding blowing mask bits`() {
        val page = ClimatePage(RuntimeEnvironment.getApplication(), Palettes.night).apply {
            measure(
                View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            )
            layout(0, 0, 1920, 1080)
        }
        val cabinField = ClimatePage::class.java.getDeclaredField("cabin").apply { isAccessible = true }
        val cabin = cabinField.get(page) as View

        // 初始仅吹脸 (1) → 点吹脚 (x=366, y=100) → 变成 吹脸+吹脚 (3)
        tap(cabin, 366f, 100f)
        assertEquals(3, HvacCodec.blowingMask(VehicleController.state.blowing))

        // 再点吹窗 (x=602, y=100) → 变成 吹脸+吹脚+吹窗 (7)
        tap(cabin, 602f, 100f)
        assertEquals(7, HvacCodec.blowingMask(VehicleController.state.blowing))

        // 再点吹脸 (x=129, y=100) → 关掉吹脸，剩 吹脚+吹窗 (6)
        tap(cabin, 129f, 100f)
        assertEquals(6, HvacCodec.blowingMask(VehicleController.state.blowing))
    }
}
