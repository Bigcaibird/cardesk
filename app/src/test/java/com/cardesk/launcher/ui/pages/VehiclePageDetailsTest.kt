package com.cardesk.launcher.ui.pages

import android.view.View
import android.widget.TextView
import com.cardesk.launcher.core.Palettes
import com.cardesk.launcher.vehicle.AdaptapiSensors
import com.cardesk.launcher.vehicle.BatteryVoltageProbe
import com.cardesk.launcher.vehicle.VehicleInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1920dp-h1080dp-land")
class VehiclePageDetailsTest {

    @Test
    fun `binds battery voltage, extended sensors, and enlarges range fuel fonts`() {
        val page = VehiclePage(RuntimeEnvironment.getApplication(), Palettes.night).apply {
            measure(
                View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            )
            layout(0, 0, 1920, 1080)
        }
        page.bind(
            VehicleInfo(
                rangeKm = 386,
                odometerKm = 24830,
                speedKmh = 68,
                fuelPercent = 62,
                avgFuelConsumption = 6.8,
                batteryVoltage = 12.6f,
                batteryEnergyLevel = BatteryVoltageProbe.ENERGY_LEVEL_HIGH,
                aqiIndoor = 24,
                pm25Indoor = 18,
                maintenanceKm = 6500,
                ignitionState = AdaptapiSensors.IGNITION_DRIVING,
                oilLevelEvent = AdaptapiSensors.OIL_LEVEL_OK,
                brakeFluidEvent = AdaptapiSensors.BRAKE_FLUID_OK,
                coolantWarnEvent = AdaptapiSensors.WARN_NORMAL,
                fuelLowWarnEvent = AdaptapiSensors.WARN_NORMAL,
                tires = floatArrayOf(240f, 241f, 238f, 235f),
            ),
        )

        fun fieldTv(name: String): TextView =
            VehiclePage::class.java.getDeclaredField(name).apply { isAccessible = true }.get(page) as TextView

        assertEquals("12.6 V", fieldTv("batteryVoltageValue").text.toString())
        assertEquals("24,830 km", fieldTv("odometerValue").text.toString())
        assertEquals("6,500 km", fieldTv("maintenanceValue").text.toString())
        assertEquals("68 km/h", fieldTv("speedValue").text.toString())
        assertEquals("18 µg/m³", fieldTv("pm25Value").text.toString())
        assertEquals("24", fieldTv("aqiValue").text.toString())

        assertEquals("行驶中", fieldTv("sensorIgnitionPill").text.toString())
        assertEquals("正常", fieldTv("sensorBatteryPill").text.toString())
        assertEquals("正常", fieldTv("sensorOilPill").text.toString())
        assertEquals("正常", fieldTv("sensorBrakeFluidPill").text.toString())
        assertEquals("正常", fieldTv("sensorCoolantPill").text.toString())
        assertEquals("正常", fieldTv("sensorFuelWarnPill").text.toString())

        assertTrue("续航字体应放大", fieldTv("rangeValue").textSize >= 64f)
        assertTrue("油量字体应放大", fieldTv("fuelValue").textSize >= 22f)

        // 校验左右胎压徽标相对车模中心严格对称
        val carView = VehiclePage::class.java.getDeclaredField("carView").apply { isAccessible = true }.get(page) as View
        @Suppress("UNCHECKED_CAST")
        val tireValues = VehiclePage::class.java.getDeclaredField("tireValues").apply { isAccessible = true }.get(page) as Array<TextView?>
        val flBadge = tireValues[0]!!.parent.parent as View
        val frBadge = tireValues[1]!!.parent.parent as View
        val carCenter = (carView.left + carView.right) / 2
        val leftDist = carCenter - flBadge.right
        val rightDist = frBadge.left - carCenter
        assertEquals("左右胎压徽标到车模中心距离应一致", leftDist, rightDist)
    }
}
