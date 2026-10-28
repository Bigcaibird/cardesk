package com.cardesk.launcher.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 计划任务单测： `TaskScheduler` 语义
 *
 */
class ScheduledTasksTest {

    @Test
    fun emptyStringDecodesToEmptyList() {
        assertEquals(0, ScheduledTasks.decode("").size)
        assertEquals(0, ScheduledTasks.decode("  \n \n").size)
    }

    @Test
    fun encode_producesReferenceJsonArrayFieldNames() {
        val task = ScheduledTask(
            id = "t1",
            condition = ScheduledTasks.COND_LAUNCH,
            action = ScheduledTasks.ACTION_URL,
            target = "https://a.b/c?d=1",
            delaySeconds = 12,
            enabled = false,
        )
        val array = JsonLite.parseArray(ScheduledTasks.encode(listOf(task)))
        assertEquals(1, array.size)
        assertEquals("t1", JsonLite.str(array[0], "id"))
        assertEquals("LAUNCH_OPEN", JsonLite.str(array[0], "condition"))
        assertEquals(12, JsonLite.int(array[0], "delaySeconds"))
        assertEquals("OPEN_URL", JsonLite.str(array[0], "action"))
        assertEquals("https://a.b/c?d=1", JsonLite.str(array[0], "target"))
        assertEquals(false, JsonLite.bool(array[0], "enabled"))
    }

    @Test
    fun encodeDecode_roundTripsAllFields() {
        val task = ScheduledTask(
            id = "t1",
            condition = ScheduledTasks.COND_LAUNCH,
            action = ScheduledTasks.ACTION_URL,
            target = "https://a.b/c?d=1",
            delaySeconds = 12,
            enabled = false,
        )
        val back = ScheduledTasks.decode(ScheduledTasks.encode(listOf(task))).single()
        assertEquals(task, back)
    }

    @Test
    fun codec_escapesPipeAndNewlineInTarget() {
        val task = ScheduledTask(id = "t2", condition = ScheduledTasks.COND_BOOT, target = "a|b\nc%d")
        val back = ScheduledTasks.decode(ScheduledTasks.encode(listOf(task))).single()
        assertEquals("a|b\nc%d", back.target)
        assertEquals(ScheduledTasks.COND_BOOT, back.condition)
    }

    @Test
    fun decode_clampsDelayAndNormalizesUnknownCondition() {
        val back = ScheduledTask.decode("t3|WEIRD|||999|1")!!
        assertEquals(ScheduledTasks.COND_BOOT, back.condition)
        assertEquals(ScheduledTasks.DELAY_MAX, back.delaySeconds)
        assertTrue(back.enabled)
        val negative = ScheduledTask.decode("t4|BOOT_COMPLETE|||−5|0")!!
        assertEquals(ScheduledTasks.DELAY_DEFAULT, negative.delaySeconds)
        assertEquals(false, negative.enabled)
    }

    @Test
    fun decode_rejectsMalformedLines() {
        assertNull(ScheduledTask.decode("no-enough-fields"))
        assertNull(ScheduledTask.decode("||app|pkg|8|1"))
        assertEquals(1, ScheduledTasks.decode("bad\n\nt-good|BOOT_COMPLETE|OPEN_APP|pkg|8|1").size)
    }

    @Test
    fun delayUpperBound_isThirtySeconds() {
        assertEquals(30, ScheduledTasks.DELAY_MAX)
        val task = ScheduledTask(id = "t5", condition = ScheduledTasks.COND_BOOT, delaySeconds = 90)
        assertEquals(30, ScheduledTasks.decode(ScheduledTasks.encode(listOf(task))).single().delaySeconds)
    }

    @Test
    fun fromJson_normalizesConditionAndDefaults() {
        val task = ScheduledTasks.fromJson(mapOf("id" to "t6", "condition" to "DELAY"))!!
        assertEquals(ScheduledTasks.COND_LAUNCH, task.condition)
        assertEquals(ScheduledTasks.ACTION_APP, task.action)
        assertEquals(0, task.delaySeconds)
        assertTrue(task.enabled)
        assertNull(ScheduledTasks.fromJson(mapOf("id" to "")))
        assertNull(ScheduledTasks.fromJson(null))
    }

    @Test
    fun labelsFollowConditionAndDelay() {
        val immediate = ScheduledTask(id = "x", condition = ScheduledTasks.COND_BOOT, delaySeconds = 0)
        assertEquals("开机后立即", ScheduledTasks.conditionLabel(immediate))
        val delayed = immediate.copy(condition = ScheduledTasks.COND_LAUNCH, delaySeconds = 8)
        assertEquals("打开桌面后 8 秒", ScheduledTasks.conditionLabel(delayed))
        assertEquals("开启热点", ScheduledTasks.actionLabel(immediate.copy(action = ScheduledTasks.ACTION_HOTSPOT_ON)))
        assertEquals("关闭热点", ScheduledTasks.actionLabel(immediate.copy(action = ScheduledTasks.ACTION_HOTSPOT_OFF)))
        assertEquals("打开应用", ScheduledTasks.actionLabel(immediate))
        assertEquals("打开链接", ScheduledTasks.actionLabel(immediate.copy(action = ScheduledTasks.ACTION_URL)))
        assertEquals("打开 QQ 音乐", ScheduledTasks.actionLabel(immediate.copy(target = "pkg"), "QQ 音乐"))
    }

    @Test
    fun conditionOptions_useReferenceWording() {
        assertEquals(ScheduledTasks.COND_BOOT to "开机完成后", ScheduledTasks.conditions()[0])
        assertEquals(ScheduledTasks.COND_LAUNCH to "进入桌面后", ScheduledTasks.conditions()[1])
    }
}
