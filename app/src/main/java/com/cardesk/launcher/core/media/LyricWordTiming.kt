package com.cardesk.launcher.core.media

import kotlin.math.abs

/**
 * 歌词**逐字时间**（卡拉 OK 逐字擦除的数据基础）。
 *
 * ## 要解决的问题
 *
 * 第八十一轮用户反馈：**「歌词逐字擦除对于一些唱的快或慢的句子会不匹配，
 * 会出现一句歌词还没擦除完就到下一句了」**。
 *
 * 取证结论（真机日志 + 参照工程反编译）：
 *
 * 1. LRC 的**行时间戳本身是准的** —— 真机日志里「播放位置 − 当前行时间戳」
 *    全程稳定在 **18~293ms**（`analysis/findings` 与 `emu-verify` 里的诊断包可查），
 *    说明歌词轴与实际演唱**没有整体错位**。
 * 2. 擦除进度是**行级线性插值**：`（播放位置 − 行首）/（下一行首 − 行首）`。
 *    原厂 APK（`com.claunchercard` 的 `MusicManager.computeLineWipeFraction`）
 *    与 CarDesk **公式完全一致**，16ms 一帧、位置外推也一致。
 * 3. ⇒ **擦除在「下一行开始」的那一刻必然到 100%**，而歌手**往往早就唱完了这一句**
 *    （唱得快 → 字挤在前半段；拖腔 → 字唱完后还吊着）。
 *    于是「高亮还在半路，句子已经唱完」⇒ 用户看到的正是
 *    **「这一句还没擦完就跳到下一句」**。
 *
 * ⇒ **只靠行时间戳无法修**：行内「字怎么分」这个信息根本不在行时间戳里。
 * 真正的修法是**按字推进**，前提是拿到**逐字时间**。
 *
 * ## 逐字时间从哪来：`<起,止,时>` 标签
 *
 * QQ 音乐车机版的歌词广播正文里带 `<1234,1456,222>` 这样的**逐字时间标签**
 * （CarDesk 此前是**直接剥掉**的，见 [LrcParser]）。
 *
 * ## 但这三个数字的语义**至今没有权威来源**
 *
 * - 原厂 APK 只 `replaceAll("<\\d+,\\d+,\\d+>", "")` —— **剥掉即丢**，从不解析；
 * - uni-launcher（Flutter 参考工程）连剥都不剥，标签直接留在正文里；
 * - 三个数字分别是什么、是**相对行首**还是**相对歌曲开头**，**没有任何代码或注释能回答**。
 *
 * ⇒ 所以本文件**不写死语义**，而是**用数据自证**（见 [LyricWordTiming.attach]）：
 *
 * - **候选 A（绝对）**：标签值本身就是相对歌曲开头的毫秒；
 * - **候选 B（相对行首）**：标签值是相对**本行行首**的毫秒。
 *
 * 两种候选都要满足「行内自洽」才算数：
 * 第一个字必须紧挨着行首（|首字起点 − 行首| ≤ [TOLERANCE_MS]）、
 * 逐字时间单调不减、每个字有正的时长、整行落在本行的时间窗内。
 *
 * 再**按整份歌词投票**（逐字标签通常出现在全篇，用多数行的口径定全篇的约定，
 * 比逐行猜稳得多），少数不自洽的行**直接丢弃逐字数据、回退行级**。
 *
 * ## 本模块的**边界**
 *
 * 这里只管**语义无权威来源**的 `<n,n,n>`（QQ 广播正文里那种）。
 *
 * 酷狗 **KRC** 的 `<相对行首偏移,时长,0>` 是**另一种格式**，
 * 语义有两个互相独立的开源实现交叉确认（`krc-reader` / `smart-lyric`）
 * ⇒ 走**独立解析器** [KugouKrc]，不塞进这里的自校准里瞎猜。
 *
 * ## 回退口径不变
 *
 * 没有任何逐字数据时，擦除仍是**行级线性**（与原厂一致）—— 不猜、不倒退。
 */
data class LyricWord(
    /** 这个字（或这个词）的起始时刻（ms，相对歌曲开头）。 */
    val startMs: Long,
    /** 结束时刻（ms，相对歌曲开头）。 */
    val endMs: Long,
    /** 在本行正文里的**起始字符下标**（含）。 */
    val charStart: Int,
    /** 结束字符下标（不含）。 */
    val charEnd: Int,
)

/**
 * 逐字时间的**解析 + 语义自校准**（纯逻辑，不碰 Android）。
 */
object LyricWordTiming {

    /**
     * 语义判定的容差（ms）。
     *
     * 依据：真机诊断包里「播放位置 − 当前行时间戳」实测在 **18~293ms**
     * （行时间戳本身就带这个量级的误差），所以逐字标签的首字起点
     * 允许与行首差这么多。取 **1500ms** 是留了 5 倍余量，
     * 又远小于相邻两行的最小间隔（真机实测最短行跨度 **3180ms**），
     * 因此不会把「相对行首」误判成「绝对」。
     */
    const val TOLERANCE_MS = 1500L

    /** 逐字标签：`<起,止,时>`。 */
    private val TAG = Regex("<(\\d+),(\\d+),(\\d+)>")

    /** 标签的语义约定（**由数据投票决定**，不是写死的）。 */
    enum class Mode {
        /** 标签值本身就是相对歌曲开头的毫秒。 */
        ABSOLUTE,

        /** 标签值是相对本行行首的毫秒。 */
        RELATIVE_TO_LINE,

        /** 定不下来 ⇒ 全篇当没有逐字数据。 */
        NONE,
    }

    /**
     * 一行里的一个逐字片段。
     *
     * @param a 标签第一个数
     * @param b 标签第二个数
     * @param c 标签第三个数
     * @param text 这个片段对应的正文
     * @param charStart 片段在**整行正文**里的起始字符下标（含）
     * @param charEnd 结束字符下标（不含）
     */
    data class Segment(
        val a: Long,
        val b: Long,
        val c: Long,
        val text: String,
        val charStart: Int,
        val charEnd: Int,
    )

    /**
     * 一行的解析结果。
     *
     * @param text 剥掉标签后的正文
     * @param segments 逐字片段（没有标签时为空）
     */
    data class Parsed(
        val text: String,
        val segments: List<Segment>,
    )

    /**
     * 把一行原文切成「纯文本 + 逐字片段」。
     *
     * ## 什么样的行能被切
     *
     * - **必须以标签开头**（时间戳之后紧跟 `<a,b,c>`）：
     *   标签前若已有正文，标签与字就**对不上号**，映射不可靠 ⇒ 返回 null。
     * - 标签后必须有正文（否则这一行没内容）。
     *
     * 返回 null 表示「这行没有可用的逐字数据」（调用方回退行级）。
     */
    fun split(raw: String): Parsed? {
        val matches = TAG.findAll(raw).toList()
        if (matches.isEmpty()) return null
        val first = matches.first()
        // 第一个标签前面不能有正文（可以有空白）。
        if (raw.substring(0, first.range.first).isNotBlank()) return null

        val rawSegs = ArrayList<Triple<Long, Long, Long>>()
        val texts = ArrayList<String>()
        for (i in matches.indices) {
            val m = matches[i]
            val a = m.groupValues[1].toLongOrNull() ?: return null
            val b = m.groupValues[2].toLongOrNull() ?: return null
            val c = m.groupValues[3].toLongOrNull() ?: return null
            val textStart = m.range.last + 1
            val textEnd = if (i + 1 < matches.size) matches[i + 1].range.first else raw.length
            rawSegs.add(Triple(a, b, c))
            texts.add(raw.substring(textStart, textEnd))
        }

        val plain = texts.joinToString("")
        val trimmed = plain.trim()
        if (trimmed.isEmpty()) return null
        // 前导空白要从字符下标里扣掉。
        val lead = plain.length - plain.trimStart().length

        val segs = ArrayList<Segment>(rawSegs.size)
        var pos = 0
        for (i in rawSegs.indices) {
            val len = texts[i].length
            val s = (pos - lead).coerceIn(0, trimmed.length)
            val e = (pos + len - lead).coerceIn(s, trimmed.length)
            segs.add(Segment(rawSegs[i].first, rawSegs[i].second, rawSegs[i].third, texts[i], s, e))
            pos += len
        }
        return Parsed(trimmed, segs)
    }

    /**
     * 给一批行挂上逐字时间。
     *
     * @param lines 已经解析好时间戳与正文的行（正文应与 [Parsed.text] 一致）
     * @param parsed 每行的 [Parsed]（没有逐字标签的行给 null）
     * @return 挂上逐字时间的新列表（挂不上的行原样返回，逐字数据为空）
     */
    fun attach(lines: List<LyricLine>, parsed: List<Parsed?>): List<LyricLine> {
        if (lines.isEmpty()) return lines

        // 本行的时间窗上界 = 全篇里**严格大于**本行时间戳的**最小**时间戳。
        //
        // 不能直接用 `lines[i + 1]`：
        // 同一句可以挂多个时间戳（`[00:10.00][00:20.00]同一句`），排序后
        // 相邻两行的时间戳会**相等** ⇒ 行跨度算成 0 ⇒ 自洽校验把整行误杀。
        // 「严格大于的最小值」才是这一行真正的时间窗右端（末行没有 ⇒ 无上界）。
        val distinct = lines.map { it.timeMs }.distinct().sorted()

        // ① 逐行判定两种语义候选是否自洽
        val absOk = BooleanArray(lines.size)
        val relOk = BooleanArray(lines.size)
        for (i in 0 until lines.size) {
            val p = parsed.getOrNull(i) ?: continue
            val segs = p.segments
            // 只有一个片段 = 这一行没有「行内进展」可用（整行一个标签），
            // 强行按字推进会把擦除变成阶跃，比行级线性更难看 ⇒ 不参与。
            if (segs.size < 2) continue
            val lineEnd = nextGreater(distinct, lines[i].timeMs)
            absOk[i] = selfConsistent(lines[i].timeMs, segs, lineEnd, absolute = true)
            relOk[i] = selfConsistent(lines[i].timeMs, segs, lineEnd, absolute = false)
        }

        // ② 按整份歌词投票定全篇约定
        // （末行没有「下一行行首」做上界，判定放宽一点，但同样参与投票 ——）
        //  否则只有一行的歌词文件会一票都投不出来，逐字数据白白丢掉。
        var absVotes = 0
        var relVotes = 0
        for (i in lines.indices) {
            if (absOk[i]) absVotes++
            if (relOk[i]) relVotes++
        }
        val mode = when {
            absVotes > relVotes -> Mode.ABSOLUTE
            relVotes > absVotes -> Mode.RELATIVE_TO_LINE
            else -> Mode.NONE
        }

        // ③ 按定下的约定挂词；**不自洽的行丢弃逐字数据**（回退行级）
        if (mode == Mode.NONE) return lines
        val out = ArrayList<LyricLine>(lines.size)
        for (i in lines.indices) {
            val p = parsed.getOrNull(i)
            // 只有「在定下的口径下自洽」的行才挂词 —— 否则会出现
            // 「这一行按 A 口径自洽、但全篇定的是 B 口径」的错挂。
            val ok = when (mode) {
                Mode.ABSOLUTE -> absOk[i]
                Mode.RELATIVE_TO_LINE -> relOk[i]
                else -> false
            }
            val words = if (ok && p != null) buildWords(lines[i].timeMs, p.segments, mode) else emptyList()
            out.add(if (words.isEmpty()) lines[i] else lines[i].copy(words = words))
        }
        return out
    }

    /** 升序数组里第一个**严格大于** [t] 的值；没有则返回 [Long.MAX_VALUE]（无上界）。 */
    private fun nextGreater(sortedDistinct: List<Long>, t: Long): Long {
        var lo = 0
        var hi = sortedDistinct.size
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (sortedDistinct[mid] <= t) lo = mid + 1 else hi = mid
        }
        return sortedDistinct.getOrNull(lo) ?: Long.MAX_VALUE
    }

    /**
     * 把 [source] 里的**逐字数据**贴到 [base] 的行上。
     *
     * ## 配对口径：**行首时间戳完全一致**
     *
     * 同一个标注服务给出的行时间戳是**逐毫秒相同**的
     * （实测 QQ 的明文 LRC 与酷狗的 KRC 行时间戳完全一致），
     * 所以「完全一致」是最保守、也最不会贴错的配对方式 ——
     * 差一毫秒都不配，宁可少贴几行，也不贴错。
     *
     * ## 为什么要「贴」而不是「换一份」
     *
     * 各家歌词的**分段**可能不同（实测同一首歌：网易云 62 行、QQ / 酷狗 54 行）。
     * 换一份 = 换了分段版式，那是另一个改动；贴逐字只影响**擦除**，
     * 显示的正文与行数一个字都不动 ⇒ 对显示零风险。
     *
     * @return 贴上逐字数据后的新列表（没有可贴的就原样返回）
     */
    fun mergeWords(base: List<LyricLine>, source: List<LyricLine>): List<LyricLine> {
        if (base.isEmpty() || source.isEmpty()) return base
        val byTime = HashMap<Long, List<LyricWord>>()
        for (s in source) {
            if (s.words.isNotEmpty()) byTime[s.timeMs] = s.words
        }
        if (byTime.isEmpty()) return base
        var changed = false
        val out = ArrayList<LyricLine>(base.size)
        for (b in base) {
            val w = byTime[b.timeMs]
            if (w != null && b.words.isEmpty()) {
                out.add(b.copy(words = w))
                changed = true
            } else {
                out.add(b)
            }
        }
        return if (changed) out else base
    }

    /**
     * 某一行的候选语义是否**自洽**。
     *
     * 判据（全部满足才算）：
     * 1. 第一个字紧挨着行首：`|首字起点 − 行首| ≤ TOLERANCE_MS`；
     * 2. 逐字时间**单调不减**；
     * 3. 没有「结束早于开始」的非法区间；
     * 4. 这一行**有正的时长**（末字结束 > 首字开始）——
     *    全零标签（`<0,0,0>`）在这里被否掉；
     * 5. 整行落在本行时间窗内：末字结束 ≤ 下一行行首 + 容差。
     */
    private fun selfConsistent(
        lineStartMs: Long,
        segs: List<Segment>,
        lineEndMs: Long,
        absolute: Boolean,
    ): Boolean {
        val w = windows(lineStartMs, segs, absolute)
        if (w.isEmpty()) return false
        if (abs(w[0].first - lineStartMs) > TOLERANCE_MS) return false
        for (i in 1 until w.size) {
            if (w[i].first < w[i - 1].first) return false
        }
        for ((s, e) in w) {
            if (e < s) return false
        }
        if (w.last().second <= w[0].first) return false
        if (lineEndMs != Long.MAX_VALUE && w.last().second > lineEndMs + TOLERANCE_MS) return false
        return true
    }

    /** 把片段按某种语义还原成 (起点, 终点) 列表。 */
    private fun windows(
        lineStartMs: Long,
        segs: List<Segment>,
        absolute: Boolean,
    ): List<Pair<Long, Long>> {
        val out = ArrayList<Pair<Long, Long>>(segs.size)
        for (s in segs) {
            val start = if (absolute) s.a else lineStartMs + s.a
            val end = if (absolute) s.b else lineStartMs + s.b
            out.add(start to end)
        }
        return out
    }

    /** 按定下的语义把片段变成 [LyricWord]。 */
    private fun buildWords(lineStartMs: Long, segs: List<Segment>, mode: Mode): List<LyricWord> {
        val absolute = mode == Mode.ABSOLUTE
        val out = ArrayList<LyricWord>(segs.size)
        for (s in segs) {
            val start = if (absolute) s.a else lineStartMs + s.a
            val end = if (absolute) s.b else lineStartMs + s.b
            out.add(LyricWord(start, end, s.charStart, s.charEnd))
        }
        return out
    }
}

/**
 * 一行歌词的**逐字擦除进度**（0..1）。
 *
 * ## 两种口径
 *
 * - **有逐字时间**（[LyricLine.words] 非空）：按**字**推进 ——
 *   已经唱完的字算满，正在唱的那个字按它自己的时间窗取部分进度。
 *   ⇒ 歌手提前唱完时，高亮**当场到 100%**（后面吊着的那段时间整句都是亮的），
 *   不会再出现「这句还没擦完就跳下一句」。
 * - **没有逐字时间**：行级线性（与原厂 APK 一致）。
 *
 * ## 为什么按**字数**而不是像素
 *
 * 绘制侧是「按宽度裁剪」，本函数给的是**比例**。
 * 中文歌词全是全角等宽字，**字数比例 ≈ 宽度比例**；
 * 中英混排会有一点偏差，但那属于「比例换算」的二阶误差，
 * 远小于行级线性带来的主误差（整段错位）。
 */
fun LyricLine.wipeFractionByWord(positionMs: Long): Float {
    val chars = text.length
    if (chars == 0 || words.isEmpty()) return 0f
    var done = 0f
    for (w in words) {
        if (positionMs >= w.endMs) {
            // 这个字唱完了 → 整段算满
            done = w.charEnd.toFloat()
            continue
        }
        if (positionMs >= w.startMs) {
            // 正在唱这个字 → 取它自己的时间窗内的部分进度
            val span = (w.endMs - w.startMs).toFloat()
            val p = if (span <= 0f) 1f else ((positionMs - w.startMs) / span).coerceIn(0f, 1f)
            done = w.charStart + p * (w.charEnd - w.charStart)
        }
        // 还没到这个字 → 停在这里
        break
    }
    return (done / chars).coerceIn(0f, 1f)
}
