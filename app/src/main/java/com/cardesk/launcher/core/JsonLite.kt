package com.cardesk.launcher.core

/**
 * 极简 JSON 编解码（纯 Kotlin，无 Android 依赖）。
 *
 * ## 为什么不用 org.json
 *
 * 槽位与计划任务都用 `JSONArray`/`JSONObject` 持久化
 *
 * 键名相同但格式不同会**互相读崩**——所以本应用必须产出**同样的 JSON 结构**。
 *
 * 但 `org.json` 在 JVM 单元测试里是 android.jar 的 stub：本项目开了
 * `unitTests.isReturnDefaultValues = true`，调用它会静默返回 null/0，
 * 编解码根本无法验证。所以这里手写一份最小实现，既能在设备上跑，也能单测。
 *
 * 支持：对象 / 数组 / 字符串（含转义） / 数字 / 布尔 / null。
 */
object JsonLite {

    // ------------------------------------------------------------------ 序列化

    fun write(value: Any?): String = StringBuilder().also { writeValue(it, value) }.toString()

    private fun writeValue(out: StringBuilder, value: Any?) {
        when (value) {
            null -> out.append("null")
            is String -> writeString(out, value)
            is Boolean -> out.append(if (value) "true" else "false")
            is Int, is Long, is Short, is Byte -> out.append(value.toString())
            is Float, is Double -> {
                val d = (value as Number).toDouble()
                // 整数值的浮点不要写成 "1.0"，那边 MMKV 存的是字符串，比较时更稳
                if (d == Math.floor(d) && !d.isInfinite()) out.append(d.toLong().toString()) else out.append(d.toString())
            }
            is Map<*, *> -> {
                out.append('{')
                var first = true
                for ((k, v) in value) {
                    if (!first) out.append(',')
                    first = false
                    writeString(out, k.toString())
                    out.append(':')
                    writeValue(out, v)
                }
                out.append('}')
            }
            is Iterable<*> -> {
                out.append('[')
                var first = true
                for (v in value) {
                    if (!first) out.append(',')
                    first = false
                    writeValue(out, v)
                }
                out.append(']')
            }
            is Array<*> -> writeValue(out, value.asList())
            else -> writeString(out, value.toString())
        }
    }

    private fun writeString(out: StringBuilder, s: String) {
        out.append('"')
        for (c in s) {
            when (c) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                '\b' -> out.append("\\b")
                '' -> out.append("\\f")
                else ->
                    if (c < ' ') {
                        out.append("\\u").append(String.format("%04x", c.code))
                    } else {
                        out.append(c)
                    }
            }
        }
        out.append('"')
    }

    // ------------------------------------------------------------------ 反序列化

    /** 解析失败返回 null（调用方按「数据损坏 → 回退默认值」处理）。 */
    fun parse(text: String): Any? = try {
        val p = Parser(text)
        p.skipWs()
        val v = p.readValue()
        p.skipWs()
        v
    } catch (t: Throwable) {
        null
    }

    /** 解析成对象数组；失败返回空列表。 */
    fun parseArray(text: String): List<Map<String, Any?>> {
        val v = parse(text) as? List<*> ?: return emptyList()
        return v.mapNotNull { it as? Map<String, Any?> }
    }

    /** 解析成单个对象；失败返回 null。 */
    fun parseObject(text: String): Map<String, Any?>? = parse(text) as? Map<String, Any?>

    private class Parser(private val s: String) {
        private var i = 0

        fun skipWs() {
            while (i < s.length && s[i].isWhitespace()) i++
        }

        fun readValue(): Any? {
            skipWs()
            if (i >= s.length) throw IllegalArgumentException("unexpected end")
            return when (s[i]) {
                '{' -> readObject()
                '[' -> readArray()
                '"' -> readString()
                't' -> { expect("true"); true }
                'f' -> { expect("false"); false }
                'n' -> { expect("null"); null }
                else -> readNumber()
            }
        }

        private fun expect(lit: String) {
            if (!s.startsWith(lit, i)) throw IllegalArgumentException("expected $lit at $i")
            i += lit.length
        }

        private fun readObject(): Map<String, Any?> {
            i++ // {
            val out = LinkedHashMap<String, Any?>()
            skipWs()
            if (i < s.length && s[i] == '}') { i++; return out }
            while (true) {
                skipWs()
                val key = readString()
                skipWs()
                if (i >= s.length || s[i] != ':') throw IllegalArgumentException("expected : at $i")
                i++
                out[key] = readValue()
                skipWs()
                if (i >= s.length) throw IllegalArgumentException("unexpected end of object")
                when (s[i]) {
                    ',' -> i++
                    '}' -> { i++; return out }
                    else -> throw IllegalArgumentException("expected , or } at $i")
                }
            }
        }

        private fun readArray(): List<Any?> {
            i++ // [
            val out = ArrayList<Any?>()
            skipWs()
            if (i < s.length && s[i] == ']') { i++; return out }
            while (true) {
                out.add(readValue())
                skipWs()
                if (i >= s.length) throw IllegalArgumentException("unexpected end of array")
                when (s[i]) {
                    ',' -> i++
                    ']' -> { i++; return out }
                    else -> throw IllegalArgumentException("expected , or ] at $i")
                }
            }
        }

        private fun readString(): String {
            if (i >= s.length || s[i] != '"') throw IllegalArgumentException("expected string at $i")
            i++
            val sb = StringBuilder()
            while (true) {
                if (i >= s.length) throw IllegalArgumentException("unterminated string")
                when (val c = s[i]) {
                    '"' -> { i++; return sb.toString() }
                    '\\' -> {
                        i++
                        if (i >= s.length) throw IllegalArgumentException("bad escape")
                        when (val e = s[i]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('')
                            'u' -> {
                                val hex = s.substring(i + 1, minOf(i + 5, s.length))
                                sb.append(hex.toInt(16).toChar())
                                i += 4
                            }
                            else -> sb.append(e)
                        }
                        i++
                    }
                    else -> { sb.append(c); i++ }
                }
            }
        }

        private fun readNumber(): Any {
            val start = i
            if (i < s.length && (s[i] == '-' || s[i] == '+')) i++
            while (i < s.length && (s[i].isDigit() || s[i] == '.' || s[i] == 'e' || s[i] == 'E' || s[i] == '-' || s[i] == '+')) i++
            val raw = s.substring(start, i)
            return raw.toLongOrNull() ?: raw.toDoubleOrNull() ?: throw IllegalArgumentException("bad number $raw")
        }
    }

    // ------------------------------------------------------------------ 取值助手

    /** 字符串字段（缺失或非字符串时返回 default）。 */
    fun str(map: Map<String, Any?>?, key: String, default: String = ""): String {
        val v = map?.get(key) ?: return default
        return v as? String ?: default
    }

    /** 整数字段。 */
    fun int(map: Map<String, Any?>?, key: String, default: Int = 0): Int {
        val v = map?.get(key) ?: return default
        return when (v) {
            is Int -> v
            is Long -> v.toInt()
            is Double -> v.toInt()
            is String -> v.toIntOrNull() ?: default
            else -> default
        }
    }

    /**
     * 长整数字段。
     *
     * 第四十轮新增：网易云的歌曲 `id` 是**数字**（`150619`），用 [str] 取会拿到空串，
     * 结果整条网易渠道永远取不到词（诊断包日志里的「歌词渠道未命中 provider=netease」）。
     * 另外网易的新歌 id 已经超过 Int 上限（`2647579132`），[int] 会溢出，
     * 所以这里必须走 Long。
     */
    fun long(map: Map<String, Any?>?, key: String, default: Long = 0L): Long {
        val v = map?.get(key) ?: return default
        return when (v) {
            is Long -> v
            is Int -> v.toLong()
            is Double -> v.toLong()
            is String -> v.trim().toLongOrNull() ?: default
            else -> default
        }
    }

    /** 布尔字段（接受 "true"/"1"/1）。 */
    fun bool(map: Map<String, Any?>?, key: String, default: Boolean = false): Boolean {
        val v = map?.get(key) ?: return default
        return when (v) {
            is Boolean -> v
            is Int -> v != 0
            is Long -> v != 0L
            is Double -> v != 0.0
            is String -> v == "true" || v == "1"
            else -> default
        }
    }

    /** 双精度字段（天气接口的 `temperature_2m` 是小数，用它取）。 */
    fun double(map: Map<String, Any?>?, key: String, default: Double = 0.0): Double {
        val v = map?.get(key) ?: return default
        return when (v) {
            is Double -> v
            is Float -> v.toDouble()
            is Int -> v.toDouble()
            is Long -> v.toDouble()
            is String -> v.trim().toDoubleOrNull() ?: default
            else -> default
        }
    }
}
