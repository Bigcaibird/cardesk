package com.cardesk.launcher.core.media

import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.Inflater

/**
 * 酷狗 **KRC**（逐字歌词）解密 + 解析。
 *
 * ## 为什么第八十一轮要它
 *
 * 用户反馈：**「歌词逐字擦除对于一些唱的快或慢的句子会不匹配，
 * 会出现一句歌词还没擦除完就到下一句了」**。
 *
 * 取证结论：行级线性插值把「这一句唱完」绑死在「下一句开始」上，
 * 而**行时间戳里根本没有「字怎么分」这个信息** ⇒ 真正的修法是**按字推进**，
 * 前提是能拿到**逐字时间**。
 *
 * 逐字时间从哪来（第八十一轮实测）：
 *
 * - 网易云 `api/song/lyric`：`klyric` **空**，`lyric/v1` 的 token 不带时间 ⇒ 拿不到；
 * - QQ `fcg_query_lyric_new.fcg`：只给**明文 LRC** ⇒ 拿不到；
 * - 酷狗 `lyrics.kugou.com/download?fmt=krc`：**给逐字数据，但是加密的** ⇒ 解密后就有。
 *
 * 真机日志也印证了酷狗这条通道：诊断包里「歌词已应用」的那 54 行
 * 与酷狗 `krc.php` 的行时间戳**逐行一致**（`[00:27.71]`），与网易云的
 * `[00:27.379]` 不同 ⇒ 用户车上的歌词**就是酷狗这一份**。
 *
 * ## KRC 的格式（有权威来源，不是猜的）
 *
 * 两个互相独立的开源实现给出口径一致（`krc-reader` 与 `smart-lyric`）：
 *
 * - **行标签**：`[行首毫秒,行时长毫秒]`
 * - **字标签**：`<相对行首的偏移毫秒,这个字的时长毫秒,0>` + 这个字的文字
 *
 * 也就是说，字标签里的第一个数是**相对行首的偏移**，第二个数是**时长**
 * （不是结束时刻）。这跟 LRC 里那个语义至今无权威来源的 `<n,n,n>` **不是一回事**，
 * 所以 KRC 走**独立解析器**，不跟 [LyricWordTiming] 的自校准混在一起。
 *
 * 实测样例（酷狗 KRC，`怎么我睡不着你也不来救我`）：
 *
 * ```
 * [0,2879]<0,180,0>怎<180,180,0>么<360,180,0>我<540,180,0>睡
 * ```
 *
 * ## 容器格式
 *
 * `lyrics.kugou.com/download?fmt=krc` 返回 JSON，其中 `content` 是 base64；
 * base64 解码后是
 *
 * ```
 * "krc1" + 异或密文
 * ```
 *
 * 密文按 16 字节密钥循环异或，再 zlib 解压即得 KRC 文本。
 *
 * ## 依赖
 *
 * 只用 framework / JDK 自带能力：`java.util.Base64`（API 26+）、`java.util.zip.Inflater`。
 * **不引任何第三方库**，纯逻辑部分可 JVM 单测。
 */
object KugouKrc {

    /** KRC 容器头（4 字节 ASCII `"krc1"`）。 */
    private val MAGIC = byteArrayOf(0x6b, 0x72, 0x63, 0x31)

    /**
     * 异或密钥（16 字节）。
     *
     * 来源：酷狗客户端自身的 KRC 编解码实现（公开可查的协议常量），
     * 不是从歌词内容反推的。
     */
    private val KEY = byteArrayOf(
        64, 71, 97, 119, 94, 50, 116, 71, 81, 54, 49, 45,
        // 206 / 210 在 byte 里是 -50 / -46
        -50, -46, 110, 105,
    )

    /** 行标签：`[行首毫秒,行时长毫秒]`。 */
    private val LINE_TAG = Regex("""\[(\d+)\s*,\s*(\d+)\s*\]""")

    /**
     * 解密 KRC 容器。
     *
     * @param base64Content `download?fmt=krc` 返回的 `content` 字段（base64 文本）
     * @return 解密解压后的 KRC 文本；任何一步不对都返回 null（调用方回退明文 LRC）
     */
    fun decrypt(base64Content: String?): String? {
        if (base64Content.isNullOrBlank()) return null
        val raw = runCatching { Base64.getDecoder().decode(base64Content.trim()) }.getOrNull() ?: return null
        if (raw.size <= MAGIC.size) return null
        for (i in MAGIC.indices) {
            if (raw[i] != MAGIC[i]) return null
        }
        val payload = ByteArray(raw.size - MAGIC.size)
        for (i in payload.indices) {
            val k = KEY[i % KEY.size].toInt() and 0xFF
            payload[i] = ((raw[MAGIC.size + i].toInt() and 0xFF) xor k).toByte()
        }
        return inflate(payload)
    }

    /** zlib 解压（KRC 正文是 zlib 压缩的）。 */
    private fun inflate(data: ByteArray): String? = runCatching {
        val inflater = Inflater()
        try {
            inflater.setInput(data)
            val out = ByteArrayOutputStream(data.size * 4)
            val buf = ByteArray(4096)
            while (!inflater.finished()) {
                val n = inflater.inflate(buf)
                if (n > 0) {
                    out.write(buf, 0, n)
                } else if (inflater.needsInput() || inflater.needsDictionary()) {
                    break
                }
            }
            String(out.toByteArray(), Charsets.UTF_8)
        } finally {
            inflater.end()
        }
    }.getOrNull()

    /** 一个字片段（解析中间态，带文字）。 */
    private class RawWord(val startMs: Long, val endMs: Long, val text: String)

    /**
     * 解析 KRC 文本 → 带逐字时间的行。
     *
     * ## 逐字数据要不要留，靠**数据自洽**判
     *
     * 逐字时间必须**落在本行的时间窗内**（末字结束 ≤ 下一行行首 + 容差），
     * 否则说明这段逐字数据跟行时间戳不是一回事 ⇒ **丢掉逐字数据、回退行级**。
     * 容差用 [LyricWordTiming.TOLERANCE_MS]（1500ms，依据真机实测的行时间戳误差
     * 18~293ms 留了 5 倍余量，又远小于最短行跨度 3180ms）。
     *
     * @return 带逐字时间的行（没逐字数据的行 [LyricLine.words] 为空）
     */
    fun parse(krcText: String?): List<LyricLine> {
        if (krcText.isNullOrBlank()) return emptyList()
        val parsed = ArrayList<LyricLine>()
        for (raw in krcText.split('\n')) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            // 元信息行（`[id:...]` / `[ar:]` / `[ti:]` / `[by:]`）没有「数字,数字」，自然不匹配。
            val m = LINE_TAG.find(line) ?: continue
            val startMs = m.groupValues[1].toLongOrNull() ?: continue
            val body = line.substring(m.range.last + 1)
            val raws = parseWords(body, startMs)
            if (raws.isEmpty()) {
                // 这一行没有逐字标签（纯文字行）⇒ **整行保留**、只是没有逐字时间。
                // 不能整行丢掉：那会平白少掉一句歌词。
                val plain = body.trim()
                if (plain.isEmpty()) continue
                parsed.add(LyricLine(startMs, plain))
                continue
            }
            parsed.add(buildLine(raws, startMs))
        }
        if (parsed.isEmpty()) return emptyList()
        // KRC 的行本身就是按时间递增的，但**不假设** —— 排序后再做时间窗自检。
        val sorted = parsed.sortedBy { it.timeMs }
        return applyWindowCheck(sorted)
    }

    /**
     * 把一行组装成 [LyricLine]：正文 = 各个字的文字拼接；
     * 逐字时间 = 行首 + 偏移，时长换算成结束时刻。
     *
     * @param lineStartMs **行标签给的时间戳**（不是首字时间）——
     *   行时间戳必须与明文 LRC 的行时间戳**完全一致**，
     *   否则 [LyricWordTiming.mergeWords] 按时间戳配对时会对不上。
     */
    private fun buildLine(raws: List<RawWord>, lineStartMs: Long): LyricLine {
        val builder = StringBuilder()
        for (w in raws) builder.append(w.text)
        val plain = builder.toString()
        val text = plain.trim()
        val lead = plain.length - plain.trimStart().length
        val words = ArrayList<LyricWord>(raws.size)
        var cursor = 0
        for (w in raws) {
            val cs = (cursor - lead).coerceIn(0, text.length)
            cursor += w.text.length
            val ce = (cursor - lead).coerceIn(cs, text.length)
            words.add(LyricWord(w.startMs, w.endMs, cs, ce))
        }
        return LyricLine(lineStartMs, text, words)
    }

    /**
     * 逐字时间窗自检：末字结束必须落在「下一行行首 + 容差」之内，
     * 且整行有正时长。不合格的行**丢掉逐字数据**（回退行级线性）。
     */
    private fun applyWindowCheck(lines: List<LyricLine>): List<LyricLine> {
        val out = ArrayList<LyricLine>(lines.size)
        for (i in lines.indices) {
            val line = lines[i]
            val words = line.words
            if (words.isEmpty()) {
                out.add(line)
                continue
            }
            val last = words.last()
            val first = words.first()
            val nextStart = lines.getOrNull(i + 1)?.timeMs ?: Long.MAX_VALUE
            val ok = last.endMs > first.startMs &&
                (nextStart == Long.MAX_VALUE || last.endMs <= nextStart + LyricWordTiming.TOLERANCE_MS)
            out.add(if (ok) line else line.copy(words = emptyList()))
        }
        return out
    }

    /** 切出一行的字片段：`<偏移,时长,0>文字`。 */
    private fun parseWords(body: String, lineStartMs: Long): List<RawWord> {
        val out = ArrayList<RawWord>()
        var i = 0
        while (i < body.length) {
            val open = body.indexOf('<', i)
            if (open < 0) break
            val close = body.indexOf('>', open + 1)
            if (close < 0) break
            val parts = body.substring(open + 1, close).split(',')
            if (parts.size < 2) {
                i = close + 1
                continue
            }
            val offset = parts[0].trim().toLongOrNull()
            val duration = parts[1].trim().toLongOrNull()
            if (offset == null || duration == null) {
                i = close + 1
                continue
            }
            val next = body.indexOf('<', close + 1)
            val wordText = if (next < 0) body.substring(close + 1) else body.substring(close + 1, next)
            val start = lineStartMs + offset
            out.add(RawWord(start, start + duration, wordText))
            i = if (next < 0) body.length else next
        }
        return out
    }
}
