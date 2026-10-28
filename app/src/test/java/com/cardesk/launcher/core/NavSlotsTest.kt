package com.cardesk.launcher.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 槽位模型单测： 语义
 *
 */
class NavSlotsTest {

    @Test
    fun defaults_haveFifteenSlotsInReferenceOrder() {
        val slots = NavSlots.defaults()
        assertEquals(15, slots.size)
        assertEquals(15, NavSlots.countedCount(slots))
        assertEquals("back", slots[0].actionId)
        assertEquals("home", slots[1].actionId)
        assertEquals("tasks", slots[2].actionId)
        assertEquals(NavSlot.TYPE_MEDIA, slots[5].type)
        assertEquals("hvac_temp_driver", slots[7].actionId)
        assertEquals("hvac_fan", slots[8].actionId)
        assertEquals("hvac_defrost_rear", slots[14].actionId)
    }

    @Test
    fun defaults_useReferenceLabels() {
        val slots = NavSlots.defaults()
        assertEquals("返回", slots[0].label)
        assertEquals("主页", slots[1].label)
        assertEquals("应用列表", slots[2].label)
        assertEquals("360环影", slots[4].label)
        assertEquals("媒体", slots[5].label)
        assertEquals("主驾温度", slots[7].label)
        assertEquals("副驾温度", slots[9].label)
        assertEquals("前除霜", slots[13].label)
        assertEquals("后除霜", slots[14].label)
    }

    @Test
    fun emptySlots_doNotCountTowardLimit() {
        val slots = NavSlots.defaults()
        assertEquals(15, NavSlots.countedCount(slots))
        slots.add(NavSlot.createEmpty())
        slots.add(NavSlot.createEmpty())
        assertEquals("空槽不计入 24 个上限", 15, NavSlots.countedCount(slots))
        assertEquals("空槽位", slots[15].label)
        assertEquals("empty", slots[15].actionId)
    }

    @Test
    fun catalog_matchesReferenceSystemActionCatalog() {
        assertEquals(22, NavSlots.CATALOG.size)
        assertEquals("back" to "返回", NavSlots.CATALOG[0])
        assertEquals("settings" to "设置", NavSlots.CATALOG[2])
        assertEquals("tasks" to "应用列表", NavSlots.CATALOG[3])
        assertEquals("media" to "媒体", NavSlots.CATALOG[6])
        assertEquals("lyric" to "歌词", NavSlots.CATALOG[7])
        assertEquals("date" to "日期", NavSlots.CATALOG[8])
        assertEquals("hvac_defrost_rear" to "后除霜", NavSlots.CATALOG[21])
    }

    @Test
    fun encode_producesReferenceJsonArrayFieldNames() {
        val slots = listOf(
            NavSlot.createAction("home", "主页"),
            NavSlot.createApp("com.example.music", "音乐", NavSlot.LAUNCH_PIP1),
            NavSlot.createMedia(),
        )
        val array = JsonLite.parseArray(NavSlots.encode(slots))
        assertEquals(3, array.size)
        assertEquals("action", JsonLite.str(array[0], "type"))
        assertEquals("home", JsonLite.str(array[0], "actionId"))
        assertEquals("", JsonLite.str(array[0], "packageName"))
        assertEquals("主页", JsonLite.str(array[0], "label"))
        assertTrue("非 app 槽不写 launchMode", !array[0].containsKey("launchMode"))
        assertEquals("app", JsonLite.str(array[1], "type"))
        assertEquals("com.example.music", JsonLite.str(array[1], "packageName"))
        assertEquals("pip1", JsonLite.str(array[1], "launchMode"))
        assertEquals("media", JsonLite.str(array[2], "type"))
    }

    @Test
    fun encodeDecode_roundTripsJsonSlots() {
        val slots = listOf(
            NavSlot.createAction("hvac_ac", "AC"),
            NavSlot.createApp("com.example.music", "音乐", NavSlot.LAUNCH_PIP2),
            NavSlot.createLyric(),
            NavSlot.createEmpty(),
            NavSlot.createDatetime(NavSlot.ACTION_DATETIME_HMS_WEEK, NavSlot.datetimeLabel(NavSlot.ACTION_DATETIME_HMS_WEEK)),
        )
        val back = NavSlots.decode(NavSlots.encode(slots))
        assertEquals(slots.size, back.size)
        assertEquals(slots[0].actionId, back[0].actionId)
        assertEquals("com.example.music", back[1].pkg)
        assertEquals("pip2", back[1].launchMode)
        assertEquals(NavSlot.TYPE_LYRIC, back[2].type)
        assertEquals(NavSlot.TYPE_EMPTY, back[3].type)
        assertEquals(NavSlot.TYPE_DATETIME, back[4].type)
    }

    @Test
    fun fromJson_normalizesTypeAndLabelLikeReference() {
        val empty = NavSlot.fromJson(mapOf("type" to "", "actionId" to "empty"))!!
        assertEquals(NavSlot.TYPE_EMPTY, empty.type)
        assertEquals("empty", empty.actionId)

        val media = NavSlot.fromJson(mapOf("type" to "media", "actionId" to ""))!!
        assertEquals(NavSlot.TYPE_MEDIA, media.type)
        assertEquals("media", media.actionId)
        assertEquals("媒体", media.label)

        val lyric = NavSlot.fromJson(mapOf("type" to "lyric", "actionId" to ""))!!
        assertEquals(NavSlot.TYPE_LYRIC, lyric.type)
        assertEquals("歌词", lyric.label)

        val datetime = NavSlot.fromJson(mapOf("type" to "datetime", "actionId" to ""))!!
        assertEquals(NavSlot.TYPE_DATETIME, datetime.type)
        assertEquals("date", datetime.actionId)
        assertEquals("日期", datetime.label)

        val week = NavSlot.fromJson(mapOf("type" to "action", "actionId" to "datetime_hm_week"))!!
        assertEquals(NavSlot.TYPE_DATETIME, week.type)
        assertEquals("日期时间（日期 时分 星期）", week.label)
    }

    @Test
    fun launchMode_normalizesAndLabelsLikeReference() {
        assertEquals("fullscreen", NavSlot.normalizeLaunchMode("weird"))
        assertEquals("pip1", NavSlot.normalizeLaunchMode("pip1"))
        assertEquals("pip2", NavSlot.normalizeLaunchMode("pip2"))
        assertEquals("主画", NavSlot.launchModeLabel("pip1"))
        assertEquals("副画", NavSlot.launchModeLabel("pip2"))
        assertEquals("全屏", NavSlot.launchModeLabel("fullscreen"))
        val app = NavSlot.createApp("pkg", "应用", "weird")
        assertEquals("fullscreen", app.launchMode)
    }

    @Test
    fun datetimeLabel_matchesReference() {
        assertEquals("日期", NavSlot.datetimeLabel("date"))
        assertEquals("日期时间（日期 时分秒）", NavSlot.datetimeLabel("datetime_hms"))
        assertEquals("日期时间（日期 时分）", NavSlot.datetimeLabel("datetime_hm"))
        assertEquals("日期时间（日期 时分 星期）", NavSlot.datetimeLabel("datetime_hm_week"))
        assertEquals("日期时间（日期 时分秒 星期）", NavSlot.datetimeLabel("datetime_hms_week"))
    }

    @Test
    fun createSlotForAction_usesTypedSlotsForMediaLyricAndDatetime() {
        assertEquals(NavSlot.TYPE_MEDIA, NavSlots.createSlotForAction("media").type)
        assertEquals(NavSlot.TYPE_LYRIC, NavSlots.createSlotForAction("lyric").type)
        val dt = NavSlots.createSlotForAction("datetime_hm_week")
        assertEquals(NavSlot.TYPE_DATETIME, dt.type)
        assertEquals("日期时间（日期 时分 星期）", dt.label)
        val action = NavSlots.createSlotForAction("back", "返回")
        assertEquals(NavSlot.TYPE_ACTION, action.type)
        assertEquals("返回", action.label)
    }

    @Test
    fun stripRemovedActions_removesOfflineActionsAndFixesLabels() {
        val slots = mutableListOf(
            NavSlot.createAction("home", "主页"),
            NavSlot.createAction("recents", "最近任务"),
            NavSlot.createAction("power", "电源"),
            NavSlot.createAction("tasks", "多任务"),
            NavSlot.createAction("hvac_defrost_rear", "后除雾"),
            NavSlot.createAction("hvac_defrost_rear", ""),
        )
        assertTrue(NavSlots.stripRemovedActions(slots))
        assertEquals(4, slots.size)
        assertEquals("应用列表", slots[1].label)
        assertEquals("后除霜", slots[2].label)
        assertEquals("后除霜", slots[3].label)
        assertFalse(slots.any { it.actionId == "recents" || it.actionId == "power" })
    }

    @Test
    fun stripRemovedActions_reportsNoChangeWhenNothingToFix() {
        val slots = mutableListOf(NavSlot.createAction("home", "主页"))
        assertFalse(NavSlots.stripRemovedActions(slots))
    }

    @Test
    fun decode_fallsBackToLegacyPipeFormat() {
        val legacy = "action|home||主页|fullscreen"
        val slots = NavSlots.decode(legacy)
        assertEquals(1, slots.size)
        assertEquals("home", slots[0].actionId)
        assertEquals("主页", slots[0].label)
    }

    @Test
    fun decode_ofBlank_returnsEmpty() {
        assertEquals(0, NavSlots.decode("").size)
        assertEquals(0, NavSlots.decode("   ").size)
    }

    @Test
    fun classifyHelpers_matchLayoutDispatchOrder() {
        assertTrue(NavSlots.isTempSlot(NavSlot.createAction("hvac_temp_passenger", "副驾温度")))
        assertTrue(NavSlots.isTextToggle(NavSlot.createAction("hvac_auto", "AUTO")))
        assertFalse(NavSlots.isTextToggle(NavSlot.createAction("hvac_fan", "风扇")))
        assertTrue(NavSlots.isHvacAction("hvac_defrost_front"))
        assertFalse(NavSlots.isHvacAction("camera_360"))
        assertTrue(NavSlots.isDatetime(NavSlot.createDatetime("datetime_hms_week", "日期时间")))
        assertNull(NavSlot.decode("not json"))
    }
}
