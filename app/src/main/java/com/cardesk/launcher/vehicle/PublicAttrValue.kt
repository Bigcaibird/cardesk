package com.cardesk.launcher.vehicle

/**
 * 车机 PA 属性值（`GeelyProto.PublicAttrValue`）的解析结果。
 *
 * ## 为什么单独写一个解析器
 *
 * 车门那五个属性（`560993300`~`560993304`）的值是 **protobuf 字节**，
 * 消息体是 `PublicAttrValue`。它通常同时带 `availability`、`format`、`status`、
 * `data` **好几个 varint 字段**。
 *
 * 之前这里用的是 [com.cardesk.launcher.core.ProtoLite.singleVarint] ——
 * 那个函数的口径是「整包**恰好一个** varint」，多一个就返回 null。
 * 于是 `PublicAttrValue` 永远解不出来，车门状态**恒读不到**：
 * 这就是「车门状态读不到」的根因，而不是属性通道没起来。
 *
 * 现在按 protobuf 的公开线格式**逐字段扫完整条消息**，照抄 UNI Launcher
 * 已经跑通的 `OfficialCarPayload` 口径（字段号与 wire type 一一对应）：
 *
 * | field | 含义 | wire type |
 * | --- | --- | --- |
 * | 1 | `availability` | varint |
 * | 2 | `format` | varint |
 * | 3 | `status` | varint |
 * | 4 | `data`（**sint32，ZigZag 编码**） | varint |
 * | 5 | `data`（double） | 64-bit |
 * | 6 | `data`（string，本类不保存） | length-delimited |
 * | 7 | 未知/扩展字段，按 wire type 跳过 | 任意 |
 * | 8 | `level` | varint |
 *
 * ## 为什么 `data` 用 Number 而不是 Int
 *
 * 同一个字段号 4 是 sint32、5 是 double、6 是 string —— 车机按属性类型选一种。
 * 车门用整数那支，但解析器不能假定只有整数，否则遇到 double 消息整条解析失败，
 * 又会退化成「读不到」。所以 `data` 保存原样类型，由调用方按需取
 * [integerData] / [doubleData]。
 *
 * ## 为什么失败一律返回 null
 *
 * 这是只读诊断路径，宁可「这一条读不到」也不能抛异常打断每秒一次的车身轮询。
 * 需要区分失败原因时，调用方拿不到的原因看日志里的原始字节即可。
 */
class PublicAttrValue private constructor(
    /** field 1：属性当前是否可用（1 = 可用）。 */
    val availability: Int?,
    /** field 2：值格式。 */
    val format: Int?,
    /** field 3：业务状态。 */
    val status: Int?,
    /** field 4/5/6：值本体；null = 消息没带数据字段。 */
    val data: Number?,
    /** field 4：`data` 在 wire 上的**无符号原值**（诊断 ZigZag 用）。 */
    val int32Wire: Long?,
    /** field 8：等级。 */
    val level: Int?,
) {

    /**
     * 属性当前是否有效。
     *
     * 口径与 UNI 的 `OfficialCarPayload.active()` 完全一致：
     * **`availability` 必须显式等于 1**。没带这个字段、或带了 0/其它值，
     * 一律视为「当前没有有效值」—— 不能把「没数据」当成「值 = 0」，
     * 否则车门会凭空变成「关」。
     */
    fun active(): Boolean = availability == 1

    /**
     * `data` 是整数时返回，否则 null。
     *
     * 与 UNI `integerData()` 一致：只认整数那支（field 4），
     * double / string 不算「门的开闭值」，交给 [doubleData] 或诊断。
     */
    fun integerData(): Int? = data as? Int

    /** `data` 是有限 double 时返回（field 5）；NaN / Infinity 视为没有。 */
    fun doubleData(): Double? = when (val d = data) {
        is Double -> if (d.isNaN() || d.isInfinite()) null else d
        is Number -> d.toDouble()
        else -> null
    }

    override fun toString(): String =
        "PublicAttrValue(availability=$availability, format=$format, status=$status, " +
            "data=$data, int32Wire=$int32Wire, level=$level)"

    companion object {

        /** 与 UNI 一致的上限：PA 消息不可能这么大，超过说明取到的不是这条消息。 */
        private const val MAX_BYTES = 8192

        /**
         * 解析一条 `PublicAttrValue` 消息。
         *
         * @return 线格式合法时返回解析结果；**任何**非法（截断、tag 为 0、
         *   wire type 不符、枚举/整数溢出、超长）都返回 null
         */
        fun parse(bytes: ByteArray?): PublicAttrValue? {
            if (bytes == null || bytes.size > MAX_BYTES) return null

            var availability: Int? = null
            var format: Int? = null
            var status: Int? = null
            var data: Number? = null
            var int32Wire: Long? = null
            var level: Int? = null

            val c = Cursor(bytes)
            while (c.position < bytes.size) {
                // tag 的低 3 位是 wire type，高位是 field number。
                val tag = c.varint() ?: return null
                if (tag <= 0L || tag > 0xFFFFFFFFL || (tag ushr 3) == 0L) return null
                val field = (tag ushr 3).toInt()
                val wire = (tag and 7L).toInt()

                when (field) {
                    // —— 枚举/整数标量：wire type 必须是 0（varint）——
                    1, 2, 3, 4, 8 -> {
                        if (wire != 0) return null
                        val encoded = c.varint() ?: return null
                        // 与 UNI 一致：枚举字段不得超过 Integer.MAX_VALUE，
                        // sint32 字段不得超过 u32 上限（负数用 ZigZag 表示）。
                        if (field != 4 && (encoded < 0L || encoded > Int.MAX_VALUE.toLong())) return null
                        if (field == 4 && (encoded < 0L || encoded > 0xFFFFFFFFL)) return null
                        val value = encoded.toInt()
                        when (field) {
                            1 -> availability = value
                            2 -> format = value
                            3 -> status = value
                            4 -> {
                                int32Wire = encoded
                                // sint32：protobuf 的 ZigZag 编码。
                                // wire 上的无符号值 n 对应 (n ushr 1) xor -(n and 1)，
                                // 所以 2 → 1、1 → -1、0xFFFFFFFF → Int.MIN_VALUE。
                                data = ((encoded ushr 1) xor -(encoded and 1L)).toInt()
                            }
                            8 -> level = value
                        }
                    }

                    // —— double 值：wire type 必须是 1（64-bit）——
                    5 -> {
                        if (wire != 1) return null
                        val bits = c.littleEndianLong() ?: return null
                        data = java.lang.Double.longBitsToDouble(bits)
                        int32Wire = null
                    }

                    // —— 其它字段（6 = string、7 = 扩展）：按 wire type 跳过 ——
                    else -> {
                        if (field == 6) {
                            // UNI 的 string 分支：只有 wire type 2 才合法；
                            // 本类不保存字符串，但「data=null」这个语义要跟 UNI 一致。
                            if (wire != 2) return null
                            data = null
                            int32Wire = null
                        }
                        if (!c.skip(wire)) return null
                    }
                }
            }
            return PublicAttrValue(availability, format, status, data, int32Wire, level)
        }
    }

    /**
     * 线格式游标。
     *
     * 为什么不用 protobuf 库：车机进程里没有可用的 protobuf runtime，
     * 而且整条消息只有几个标量字段，手写游标才能精确控制
     * 「第 10 个 varint 字节必须合法」这类边界（照抄 UNI 的实现）。
     */
    private class Cursor(private val bytes: ByteArray) {
        var position = 0
            private set

        /** 读一个 varint（最多 10 字节）；截断或越界返回 null。 */
        fun varint(): Long? {
            var result = 0L
            var shift = 0
            for (i in 0 until 10) {
                if (position >= bytes.size) return null
                val b = bytes[position++].toInt() and 0xFF
                // 第 10 字节只有最低 1 位有意义；(b & 0xFE) != 0 说明超过 64 位。
                if (i == 9 && (b and 0xFE) != 0) return null
                result = result or ((b and 0x7F).toLong() shl shift)
                if (b and 0x80 == 0) return result
                shift += 7
            }
            return null
        }

        /** 读 8 字节小端（protobuf 的 fixed64 / double 都是小端）。 */
        fun littleEndianLong(): Long? {
            if (position + 8 > bytes.size) return null
            var bits = 0L
            for (i in 0 until 8) {
                bits = bits or ((bytes[position + i].toLong() and 0xFF) shl (8 * i))
            }
            position += 8
            return bits
        }

        /**
         * 按 wire type 跳过一个字段。
         *
         * 支持的 wire type 与 UNI 一致：0 = varint、1 = 8 字节、2 = 长度前缀、
         * 5 = 4 字节；3/4（旧 group）与 6/7 直接判非法 —— PA 消息里不会出现。
         */
        fun skip(wire: Int): Boolean {
            return when (wire) {
                0 -> varint() != null
                1 -> {
                    if (position + 8 > bytes.size) return false
                    position += 8
                    true
                }
                2 -> {
                    val len = varint() ?: return false
                    if (len < 0L || len > Int.MAX_VALUE.toLong()) return false
                    if (len > (bytes.size - position).toLong()) return false
                    position += len.toInt()
                    true
                }
                5 -> {
                    if (position + 4 > bytes.size) return false
                    position += 4
                    true
                }
                else -> false
            }
        }
    }
}
