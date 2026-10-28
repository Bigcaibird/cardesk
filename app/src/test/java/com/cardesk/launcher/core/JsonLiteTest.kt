package com.cardesk.launcher.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 极简 JSON 编解码单测。
 *
 * 槽位与计划任务的持久化格式必须与 `JSONArray` **完全兼容**，
 * 所以编解码要能扛住「两边都写」和「两边都读」两个方向。
 */
class JsonLiteTest {

    @Test
    fun write_objectUsesStableFieldOrder() {
        val text = JsonLite.write(linkedMapOf("type" to "app", "actionId" to "", "label" to "高德地图"))
        assertEquals("""{"type":"app","actionId":"","label":"高德地图"}""", text)
    }

    @Test
    fun write_escapesQuotesAndControlChars() {
        val text = JsonLite.write(mapOf("k" to "a\"b\\c\nd\te"))
        assertEquals("""{"k":"a\"b\\c\nd\te"}""", text)
    }

    @Test
    fun write_arrayOfObjects() {
        val text = JsonLite.write(
            listOf(
                linkedMapOf<String, Any?>("type" to "app", "packageName" to "com.a"),
                linkedMapOf<String, Any?>("type" to "action", "actionId" to "home"),
            ),
        )
        assertEquals(
            """[{"type":"app","packageName":"com.a"},{"type":"action","actionId":"home"}]""",
            text,
        )
    }

    @Test
    fun write_integerFloatHasNoTrailingZero() {
        assertEquals("""{"a":1,"b":1.5,"c":true,"d":null}""", JsonLite.write(
            linkedMapOf<String, Any?>("a" to 1, "b" to 1.5, "c" to true, "d" to null),
        ))
    }

    @Test
    fun parse_readsNestedStructures() {
        val map = JsonLite.parseObject("""{"a":[1,2,{"b":"c"}],"d":{"e":true},"f":null}""")
        assertNull(map?.get("f"))
        val a = map?.get("a") as? List<*>
        assertEquals(3, a?.size)
        assertEquals(1L, (a?.get(0) as Long))
        assertEquals(2L, (a?.get(1) as Long))
        assertEquals("c", ((a?.get(2) as Map<*, *>)["b"]))
        assertEquals(true, (map?.get("d") as Map<*, *>)["e"])
    }

    @Test
    fun parse_handlesEscapes() {
        val map = JsonLite.parseObject("""{"k":"a\"b\nc\\d"}""")
        assertEquals("a\"b\nc\\d", map?.get("k"))
    }

    @Test
    fun parseArray_readsListOfObjects() {
        val list = JsonLite.parseArray("""[{"type":"app","packageName":"com.a"},{"type":"action"}]""")
        assertEquals(2, list.size)
        assertEquals("com.a", list[0]["packageName"])
        assertEquals("action", list[1]["type"])
    }

    @Test
    fun parseArray_brokenJsonFallsBackToEmpty() {
        assertTrue(JsonLite.parseArray("""[{"type":"app"}""").isEmpty())
        assertTrue(JsonLite.parseArray("not json").isEmpty())
        assertTrue(JsonLite.parseArray("""{"a":1}""").isEmpty())
    }

    @Test
    fun parseObject_brokenJsonReturnsNull() {
        assertNull(JsonLite.parseObject("""{"a":}"""))
        assertNull(JsonLite.parseObject("[1,2]"))
    }

    @Test
    fun typedAccessors_applyDefaults() {
        val map = JsonLite.parseObject("""{"s":"x","i":7,"b":true,"n":null}""")
        assertEquals("x", JsonLite.str(map, "s"))
        assertEquals("fallback", JsonLite.str(map, "missing", "fallback"))
        assertEquals(7, JsonLite.int(map, "i"))
        assertEquals(3, JsonLite.int(map, "missing", 3))
        assertTrue(JsonLite.bool(map, "b"))
        assertFalse(JsonLite.bool(map, "missing"))
        assertEquals("s", JsonLite.str(map, "n", "s"))
    }

    /**
     * 第四十轮新增：`long` 取值助手。
     *
     * 网易云的歌曲 id 是**数字**（`150619`），新歌 id 还超过了 Int 上限
     * （`2647579132`）—— 用 `str` 取会拿到空串，用 `int` 取会溢出。
     */
    @Test
    fun long_readsNumericAndStringValues() {
        val map = JsonLite.parseObject("""{"id":150619,"big":2647579132,"s":"42","d":295666.0}""")
        assertEquals(150619L, JsonLite.long(map, "id"))
        // 超过 Int 上限不溢出
        assertEquals(2647579132L, JsonLite.long(map, "big"))
        // 数字字符串也认
        assertEquals(42L, JsonLite.long(map, "s"))
        assertEquals(295666L, JsonLite.long(map, "d"))
        // 缺失 / 非法 → default
        assertEquals(0L, JsonLite.long(map, "missing"))
        assertEquals(9L, JsonLite.long(map, "missing", 9L))
        assertEquals(9L, JsonLite.long(JsonLite.parseObject("""{"x":"abc"}"""), "x", 9L))
    }

    @Test
    fun roundTrip_preservesSlotShape() {
        val original = listOf(
            linkedMapOf<String, Any?>(
                "type" to "app",
                "actionId" to "",
                "packageName" to "com.autonavi.amapauto",
                "label" to "高德地图",
                "launchMode" to 1,
            ),
            linkedMapOf<String, Any?>(
                "type" to "action",
                "actionId" to "home",
                "packageName" to "",
                "label" to "主页",
            ),
        )
        val text = JsonLite.write(original)
        val parsed = JsonLite.parseArray(text)
        assertEquals(2, parsed.size)
        assertEquals("app", parsed[0]["type"])
        assertEquals("com.autonavi.amapauto", parsed[0]["packageName"])
        assertEquals(1, JsonLite.int(parsed[0], "launchMode"))
        // action 槽不写 launchMode
        assertFalse(parsed[1].containsKey("launchMode"))
    }
}
