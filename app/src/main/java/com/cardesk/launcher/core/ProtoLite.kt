package com.cardesk.launcher.core

/**
 * **极简 protobuf 扫描**（纯 Kotlin，不依赖 Android，可 JVM 单测）。
 *
 * ## 为什么要自己写
 *
 * 读 MC/Geely 事件属性时，属性值是一段
 * `byte[]`，内容是 protobuf 消息
 * （`vendor.cevt.hardware.automotive.vehicle.geely.V2_0`，
 * **CEVT 平台的类**，ECARX 平台没有。
 *
 * 那条通道我们不能直接抄：
 * 平台类不在我们进程里， 的**字段号**也无从得知。
 *
 * 但 protobuf 的**线格式**是公开的、稳定的，所以可以只做一件事：
 * **扫出消息里唯一一个 varint 字段的值**。
 *
 * ## 规则（刻意保守）
 *
 * - 顶层字段里**有且仅有一个** varint（wire type 0）字段 → 返回它的值；
 * - 一个都没有 → null（这条属性不是我们要的数值型）；
 * - **多于一个** → null（**歧义，不猜**）。
 *
 * 字段形状正好落在这条规则上：
 *  是 varint，而 （fixed64）、 /
 * （length-delimited）都不是 varint，
 * 所以正常情况下扫出来**只有一个** varint。
 *
 * ## 语义对齐
 *
 * 把这个值按「**等于 1 就是开**」解释
 * （`C1925v.m9801a(value, 1)`：`value != null && value.equals(1)`）。
 * 我们沿用同一套取值口径：`0` = 关、`1` = 开、其它 = **读不到**（不猜）。
 */
object ProtoLite {

    /** protobuf 的 wire type 0：varint。 */
    private const val WIRE_VARINT = 0

    /** wire type 1：64 位定长。 */
    private const val WIRE_FIXED64 = 1

    /** wire type 2：长度分隔。 */
    private const val WIRE_LENGTH_DELIMITED = 2

    /** wire type 3 / 4：group（protobuf 2 的废弃写法）。 */
    private const val WIRE_GROUP_START = 3
    private const val WIRE_GROUP_END = 4

    /** wire type 5：32 位定长。 */
    private const val WIRE_FIXED32 = 5

    /**
     * 扫出消息里**唯一一个** varint 字段的值。
     *
     * @param bytes protobuf 消息字节
     * @return 唯一 varint 的值；没有或不止一个时返回 null
     */
    fun singleVarint(bytes: ByteArray): Long? {
        var pos = 0
        var found: Long? = null
        var count = 0
        while (pos < bytes.size) {
            val key = readVarint(bytes, pos) ?: return null
            pos = key.second
            val wireType = (key.first.toInt() and 0x7)
            when (wireType) {
                WIRE_VARINT -> {
                    val v = readVarint(bytes, pos) ?: return null
                    pos = v.second
                    found = v.first
                    count++
                    if (count > 1) return null
                }
                WIRE_FIXED64 -> pos += 8
                WIRE_FIXED32 -> pos += 4
                WIRE_LENGTH_DELIMITED -> {
                    val len = readVarint(bytes, pos) ?: return null
                    pos = len.second + len.first.toInt()
                }
                WIRE_GROUP_START, WIRE_GROUP_END -> return null
                else -> return null
            }
            if (pos < 0 || pos > bytes.size) return null
        }
        return if (count == 1) found else null
    }

    /**
     * 读一个 varint（protobuf 的 base-128 变长编码）。
     *
     * @return 值与「读完之后的位置」；数据不完整时返回 null
     */
    private fun readVarint(bytes: ByteArray, start: Int): Pair<Long, Int>? {
        var result = 0L
        var shift = 0
        var pos = start
        while (pos < bytes.size) {
            val b = bytes[pos].toInt() and 0xFF
            result = result or ((b and 0x7F).toLong() shl shift)
            pos++
            if (b and 0x80 == 0) return Pair(result, pos)
            shift += 7
            if (shift > 63) return null
        }
        return null
    }
}
