package com.cardesk.launcher.core.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 高德红绿灯协议解析单测。
 *
 * 覆盖两套协议：
 * - 官方标准协议（`KEY_TYPE = 60073`，extras）
 * - 修改版（YH）巡航协议（`NAVITOOL_CRUISE_TRAFFIC_LIGHTS`，JSON）
 *
 * 以及「只显示最近路口一个」这条硬口径：多方向时挑不出来就不给信号。
 */
class AmapTrafficLightTest {

    // ------------------------------------------------------------ 官方协议

    /** 官方协议的一帧（extras 全是数值型）。 */
    private fun standard(
        keyType: Int = AmapTrafficLight.KEY_TYPE_TRAFFIC_LIGHT,
        dir: Any? = 2,
        status: Any? = 0,
        redCountdown: Any? = null,
        waitRound: Any? = null,
        greenLastSecond: Any? = null,
    ): Map<String, Any?> {
        val map = HashMap<String, Any?>()
        map[AmapTrafficLight.EXTRA_KEY_TYPE] = keyType
        map[AmapTrafficLight.EXTRA_DIR] = dir
        map[AmapTrafficLight.EXTRA_STATUS] = status
        if (redCountdown != null) map[AmapTrafficLight.EXTRA_RED_COUNTDOWN] = redCountdown
        if (waitRound != null) map[AmapTrafficLight.EXTRA_WAIT_ROUND] = waitRound
        if (greenLastSecond != null) map[AmapTrafficLight.EXTRA_GREEN_LAST_SECOND] = greenLastSecond
        return map
    }

    @Test
    fun `官方协议 trafficLightStatus 为 1 时判红灯`() {
        val signal = AmapTrafficLight.standard(standard(status = 1, redCountdown = 23))
        assertNotNull(signal)
        assertEquals(AmapTrafficLight.Lamp.RED, signal!!.lamp)
        assertEquals(23, signal.countdownSeconds)
        assertEquals(1, signal.rawStatus)
        assertEquals(2, signal.rawDirection)
    }

    @Test
    fun `官方协议 trafficLightStatus 为 4 时判绿灯`() {
        val signal = AmapTrafficLight.standard(standard(status = 4, greenLastSecond = 12))
        assertNotNull(signal)
        assertEquals(AmapTrafficLight.Lamp.GREEN, signal!!.lamp)
        // 绿灯读秒取的是绿灯自己的秒数
        assertEquals(12, signal.countdownSeconds)
        assertEquals(4, signal.rawStatus)
    }

    @Test
    fun `官方协议 绿灯时不拿红灯倒计时当读秒`() {
        // 这一条就是真车报的那个毛病：
        // 以前只看「红灯倒计时 > 0」就判红，绿灯期间那个字段照样可能是正数 ⇒ 绿灯画成红灯。
        // 现在灯态看 `trafficLightStatus`，读秒也跟着灯态走。
        val signal = AmapTrafficLight.standard(
            standard(status = 4, redCountdown = 23, greenLastSecond = 9),
        )
        assertNotNull(signal)
        assertEquals(AmapTrafficLight.Lamp.GREEN, signal!!.lamp)
        assertEquals(9, signal.countdownSeconds)
    }

    @Test
    fun `官方协议 灯态枚举已核对过`() {
        val signal = AmapTrafficLight.standard(standard(status = 1, redCountdown = 5))
        assertNotNull(signal)
        // 官方那一套的 `trafficLightStatus` 枚举已经核对过
        // （依据：高德自己的红绿灯语音模块 + 语音设置项文案，见 AmapTrafficLight 的注释）。
        assertTrue(signal!!.enumVerified)
    }

    @Test
    fun `官方协议 认不出的灯态按读秒字段兜底`() {
        // 高德要是换了枚举取值，我们不猜它是什么意思，
        // 改用自带字段名的读秒兜底 —— 不能退回到「永远画红灯」。
        val green = AmapTrafficLight.standard(standard(status = 99, greenLastSecond = 9))
        assertEquals(AmapTrafficLight.Lamp.GREEN, green!!.lamp)
        assertEquals(9, green.countdownSeconds)

        val red = AmapTrafficLight.standard(standard(status = 99, redCountdown = 7))
        assertEquals(AmapTrafficLight.Lamp.RED, red!!.lamp)
        assertEquals(7, red.countdownSeconds)

        val none = AmapTrafficLight.standard(standard(status = 99))
        assertEquals(AmapTrafficLight.Lamp.UNKNOWN, none!!.lamp)
        assertNull(none.countdownSeconds)
    }

    @Test
    fun `官方协议 没有读秒时灯态是未知而不是猜绿灯`() {
        val signal = AmapTrafficLight.standard(standard(status = 99))
        assertNotNull(signal)
        assertEquals(AmapTrafficLight.Lamp.UNKNOWN, signal!!.lamp)
        assertNull(signal.countdownSeconds)
        // 原始值仍然留着，只做诊断。
        assertEquals(99, signal.rawStatus)
    }

    @Test
    fun `官方协议 KEY_TYPE 不是 60073 就不是红绿灯帧`() {
        assertNull(AmapTrafficLight.standard(standard(keyType = 10001, redCountdown = 23)))
    }

    @Test
    fun `官方协议 缺 dir 或缺灯态就不给信号`() {
        assertNull(AmapTrafficLight.standard(standard(dir = null, redCountdown = 23)))
        assertNull(AmapTrafficLight.standard(standard(status = null, redCountdown = 23)))
    }

    @Test
    fun `官方协议 字段是字符串时不当成数值用`() {
        // 协议里这些字段本来就是数值；混进字符串说明不是这一类广播。
        // `dir` / `trafficLightStatus` 是**必填字段**，是字符串 → 整帧作废。
        assertNull(AmapTrafficLight.standard(standard(dir = "2", redCountdown = 23)))
        assertNull(AmapTrafficLight.standard(standard(status = "0", redCountdown = 23)))
        // 读秒只是「可选」——是字符串就当这一帧没带读秒（而不是当成 23 秒）。
        val signal = AmapTrafficLight.standard(standard(status = 99, redCountdown = "23"))
        assertNotNull(signal)
        assertNull(signal!!.countdownSeconds)
        assertEquals(AmapTrafficLight.Lamp.UNKNOWN, signal.lamp)
    }

    @Test
    fun `官方协议 读秒越界就当没有读秒`() {
        // 超过 999 说明给的不是秒数（比如毫秒）。
        val signal = AmapTrafficLight.standard(standard(status = 99, redCountdown = 1000))
        assertNotNull(signal)
        assertNull(signal!!.countdownSeconds)
        assertEquals(AmapTrafficLight.Lamp.UNKNOWN, signal.lamp)
    }

    @Test
    fun `官方协议 绿灯剩余秒数只在绿灯时用`() {
        // `greenLightLastSecond` 是「绿灯剩余秒数」——
        // 灯态是绿的时候拿它当读秒，灯态不是绿的时候不碰它。
        val green = AmapTrafficLight.standard(standard(status = 4, greenLastSecond = 18))
        assertEquals(18, green!!.countdownSeconds)

        // 灯态是红的时候，绿灯的秒数不参与（读秒走红灯倒计时）
        val red = AmapTrafficLight.standard(standard(status = 1, redCountdown = 6, greenLastSecond = 18))
        assertEquals(AmapTrafficLight.Lamp.RED, red!!.lamp)
        assertEquals(6, red.countdownSeconds)
    }

    @Test
    fun `官方协议 整数值的浮点也认`() {
        val signal = AmapTrafficLight.standard(standard(status = 1, redCountdown = 23.0))
        assertNotNull(signal)
        assertEquals(23, signal!!.countdownSeconds)
    }

    @Test
    fun `官方协议 小数读秒不认`() {
        val signal = AmapTrafficLight.standard(standard(status = 1, redCountdown = 23.5))
        assertNotNull(signal)
        assertNull(signal!!.countdownSeconds)
    }

    @Test
    fun `官方协议 方向枚举与巡航协议不是同一张表`() {
        // 官方：1=左转 / 2=右转 / 4=直行；巡航：1=左 / 2=直 / 3=右。
        assertEquals("左转", AmapTrafficLight.standardDirectionLabel(1))
        assertEquals("右转", AmapTrafficLight.standardDirectionLabel(2))
        assertEquals("直行", AmapTrafficLight.standardDirectionLabel(4))
        assertEquals("掉头", AmapTrafficLight.standardDirectionLabel(8))
        assertEquals("未知", AmapTrafficLight.standardDirectionLabel(null))
    }

    // ------------------------------------------------------------ 巡航协议

    private fun cruiseJson(vararg lights: String): String {
        val body = lights.joinToString(",")
        return """{"ts":1712345678901,"lights":[$body]}"""
    }

    private fun light(direction: Int, status: Int, countdown: Int? = null): String {
        val cd = if (countdown == null) "null" else countdown.toString()
        return """{"direction":$direction,"status":$status,"countdown":$cd,"waitNum":1,"showType":0}"""
    }

    @Test
    fun `巡航协议 单条灯就用它`() {
        val signal = AmapTrafficLight.cruise(cruiseJson(light(2, 0, 23)))
        assertNotNull(signal)
        assertEquals(AmapTrafficLight.Lamp.RED, signal!!.lamp)
        assertEquals(23, signal.countdownSeconds)
        assertEquals(2, signal.rawDirection)
        // 这一套的枚举在 YH 包里验证过。
        assertTrue(signal.enumVerified)
    }

    @Test
    fun `巡航协议 灯态 0 是红 1 是绿`() {
        val red = AmapTrafficLight.cruise(cruiseJson(light(2, 0, 10)))
        val green = AmapTrafficLight.cruise(cruiseJson(light(2, 1, 10)))
        assertEquals(AmapTrafficLight.Lamp.RED, red!!.lamp)
        assertEquals(AmapTrafficLight.Lamp.GREEN, green!!.lamp)
    }

    @Test
    fun `巡航协议 灯态 2 语义未定 按未知处理`() {
        val signal = AmapTrafficLight.cruise(cruiseJson(light(2, 2, 10)))
        assertNotNull(signal)
        // 浮窗里索引 2 也用绿色，但语义没确定 —— 不能当成绿灯。
        assertEquals(AmapTrafficLight.Lamp.UNKNOWN, signal!!.lamp)
        assertEquals(2, signal.rawStatus)
    }

    @Test
    fun `巡航协议 多方向时优先匹配当前转向`() {
        val json = cruiseJson(light(1, 0, 5), light(2, 1, 30), light(3, 0, 7))
        val signal = AmapTrafficLight.cruise(json, AmapTrafficLight.DIR_STRAIGHT)
        assertNotNull(signal)
        assertEquals(AmapTrafficLight.Lamp.GREEN, signal!!.lamp)
        assertEquals(2, signal.rawDirection)
    }

    @Test
    fun `巡航协议 多方向且挑不出方向时不给信号`() {
        val json = cruiseJson(light(1, 0, 5), light(2, 1, 30))
        // 有转向但对不上（掉头没有对应方向）
        assertNull(AmapTrafficLight.cruise(json, AmapTrafficLight.DIR_RIGHT))
        // 没有转向也不知道
        assertNull(AmapTrafficLight.cruise(json, null))
        // 不给偏好方向
        assertNull(AmapTrafficLight.cruise(json))
    }

    @Test
    fun `巡航协议 空数组或坏 JSON 不给信号`() {
        assertNull(AmapTrafficLight.cruise("""{"ts":1,"lights":[]}"""))
        assertNull(AmapTrafficLight.cruise("not json"))
        assertNull(AmapTrafficLight.cruise(""))
        assertNull(AmapTrafficLight.cruise("""{"ts":1}"""))
    }

    @Test
    fun `巡航协议 超长报文直接拒掉`() {
        val huge = "x".repeat(AmapTrafficLight.MAX_CRUISE_JSON_LENGTH + 1)
        assertNull(AmapTrafficLight.cruise(huge))
    }

    @Test
    fun `巡航协议 数组太长直接拒掉`() {
        val lights = (1..(AmapTrafficLight.MAX_CRUISE_LIGHTS + 1)).joinToString(",") { light(2, 0, 1) }
        assertNull(AmapTrafficLight.cruise("""{"ts":1,"lights":[$lights]}"""))
    }

    @Test
    fun `巡航协议 方向或灯态缺一个就整帧作废`() {
        assertNull(AmapTrafficLight.cruise(cruiseJson("""{"status":0,"countdown":10}""")))
        assertNull(AmapTrafficLight.cruise(cruiseJson("""{"direction":2,"countdown":10}""")))
    }

    @Test
    fun `巡航协议 没有读秒时灯态照常有`() {
        val signal = AmapTrafficLight.cruise(cruiseJson(light(2, 0, null)))
        assertNotNull(signal)
        assertEquals(AmapTrafficLight.Lamp.RED, signal!!.lamp)
        assertNull(signal.countdownSeconds)
    }

    // ------------------------------------------------------------ 文案

    @Test
    fun `灯态文案`() {
        assertEquals("红灯", NavGuideText.trafficLampText(AmapTrafficLight.Lamp.RED))
        assertEquals("绿灯", NavGuideText.trafficLampText(AmapTrafficLight.Lamp.GREEN))
        assertEquals("黄灯", NavGuideText.trafficLampText(AmapTrafficLight.Lamp.AMBER))
        assertEquals("灯态未知", NavGuideText.trafficLampText(AmapTrafficLight.Lamp.UNKNOWN))
    }

    @Test
    fun `读秒文案 没有读秒显示破折号`() {
        assertEquals("—", NavGuideText.trafficCountdownText(null))
        assertEquals("—", NavGuideText.trafficCountdownText(0))
        assertEquals("12", NavGuideText.trafficCountdownText(12))
    }

    // ------------------------------------------------------------ 模型

    @Test
    fun `引导卡模型 默认没有红绿灯`() {
        val guide = NavGuideModel.build(
            icon = 2,
            segmentMeters = 300,
            currentRoad = "科技路",
            nextRoad = null,
            remainSeconds = 60,
            remainMeters = 1000,
            lanesRaw = null,
        )
        assertNull(guide.trafficLight)
    }

    @Test
    fun `引导卡模型 能带上红绿灯`() {
        val signal = AmapTrafficLight.Signal(
            lamp = AmapTrafficLight.Lamp.RED,
            countdownSeconds = 8,
            rawStatus = 0,
            rawDirection = 2,
            enumVerified = false,
        )
        val guide = NavGuideModel.build(
            icon = 2,
            segmentMeters = 300,
            currentRoad = "科技路",
            nextRoad = null,
            remainSeconds = 60,
            remainMeters = 1000,
            lanesRaw = null,
            trafficLight = signal,
        )
        assertEquals(signal, guide.trafficLight)
    }
}
