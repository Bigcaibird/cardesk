package com.cardesk.launcher.core

import android.content.Context
import android.os.SystemClock
import com.cardesk.launcher.core.log.LogTags
import com.cardesk.launcher.core.log.Logx
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 冷启动分段计时（第九十三轮，对照 UNI 的 `StartupTrace.java`）。
 *
 * ## 为什么要有
 *
 * 「车机开机后黑屏一段时间才出桌面」这件事，**以前根本没法量化**：
 * 诊断包里只有业务日志，没有一条「从进程启动到首帧画出来用了多少毫秒」。
 * 每次用户反馈「还是有点慢」，我们只能猜是 `LauncherView` 构造、天气网络、
 * 还是媒体会话绑定。
 *
 * 这个类只做一件事：**在关键节点打一个 uptimeMillis 时间戳**，
 * 一次性写成 `key=value` 日志。时间基准与 `am start -W` 的 TotalTime 一致
 * （都用 `SystemClock.uptimeMillis()`），可以直接对照系统的启动耗时。
 *
 * ## 车机没有 adb 也能看
 *
 * 真机可能连不上 adb，所以这里**不是只写 logcat**：
 *
 * - 每个分段都写 [Logx]（INFO，默认落盘）→ 进诊断包的 `logs/`；
 * - 桌面就绪时把汇总行**持久化**到 `cardesk_startup.xml`
 *   → 诊断包 `summary.txt` 的「冷启动」段直接给出本次 + 上次的数字，
 *   **即使日志已经被轮转掉也不会丢**。
 *
 * `bootMs` 是「开机以来毫秒」，用来区分两种情况：
 * 系统开机就慢（`bootMs` 很大） vs 我们的进程启动慢（`processMs` 大）。
 *
 * ## 怎么读
 *
 * - 车机无 adb：**设置 → 系统 → 诊断日志 → 导出诊断包**，
 *   看 `summary.txt` 的「---- 冷启动 ----」段（本次 + 上次）；
 *   也可以搜日志里的「启动分段」/「启动完成」。
 * - 有 adb：`adb logcat -s CarDesk-Startup`。
 *
 * ## 为什么起点要反射
 *
 * 真正有意义的起点是**进程 fork 出来的那一刻**，对应
 * `android.os.Process.getStartUptimeMillis()`；但它不在公开 SDK 里
 * （UNI 用 hidden API 编译所以能直接调，我们 targetSdk 29 走不到）。
 * 这里反射试一次，被隐藏 API 拦了就退回「本类第一次被引用」的时刻 ——
 * 那样 total 会少掉进程初始化的一小段，但**各分段之间的差值不受影响**，
 * 定位瓶颈足够用。摘要里会标明起点是 `process` 还是 `onCreate`。
 */
object StartupTrace {

    /** 只进 Logcat 的独立 tag：真机上用 `adb logcat -s CarDesk-Startup` 读。 */
    private const val LOGCAT_TAG = "CarDesk-Startup"

    /** 持久化最近一次启动摘要（诊断包 summary.txt 用）。 */
    private const val PREFS = "cardesk_startup"
    private const val KEY_LAST = "last"
    private const val KEY_LAST_AT = "last_at"
    private const val KEY_LAST_COLD = "last_cold"
    private const val KEY_LAST_COLD_AT = "last_cold_at"

    /**
     * 「开机以来多少毫秒内启动」算一次冷启动记录（5 分钟）。
     *
     * 车机重启后用户可能先开别的应用、过很久才回桌面，那时进程早被回收，
     * 再回桌面是**热启动**（数字很小）。如果只存 `last`，这条热启动会把
     * 开机时那次冷启动的数字覆盖掉，用户下次开机前就再也看不到「黑屏其实多长」。
     * 所以额外存一份 `last_cold`，只被「开机 5 分钟内启动」的记录更新。
     */
    private const val COLD_BOOT_WINDOW_MS = 5L * 60_000L

    /** 本类第一次被引用（= `MainActivity.onCreate` 开头）的时刻。 */
    private val firstTouchMs = SystemClock.uptimeMillis()

    /** 反射拿到的进程启动时刻；拿不到为 null。 */
    private val processStartReflected: Long? = runCatching {
        val method = Class.forName("android.os.Process")
            .getMethod("getStartUptimeMillis")
        (method.invoke(null) as Long)
            .takeIf { it > 0L && it <= SystemClock.uptimeMillis() }
    }.getOrNull()

    /**
     * 进程启动时刻（uptimeMillis）。
     *
     * 反射成功就用它；失败（隐藏 API 限制 / 旧系统没有该方法）退回 [firstTouchMs]。
     */
    private val processStartMs: Long = processStartReflected ?: firstTouchMs

    /** 起点来源，写进摘要：`process` = 进程 fork，`onCreate` = 本类首次被引用。 */
    private val startSource: String = if (processStartReflected != null) "process" else "onCreate"

    /** 已经打过的时间戳（按打点顺序）。 */
    private val marks = LinkedHashMap<String, Long>()

    /** 已写过汇总（进程内只持久化一次，避免热启动把冷启动记录覆盖掉）。 */
    private var summarized = false

    @Volatile
    private var app: Context? = null

    /**
     * 绑定 Context（`MainActivity.onCreate` 里 `Logx.init` 之后调一次）。
     *
     * 只用来把汇总行持久化到 prefs —— 不绑定也能跑，只是诊断包里看不到「上次启动」。
     */
    fun install(context: Context) {
        if (app == null) app = context.applicationContext
    }

    /** 开机以来毫秒（含系统启动过程）。 */
    private fun sinceBoot(): Long = SystemClock.elapsedRealtime()

    /**
     * 打一个启动分段。
     *
     * 三个数字一起打：
     * - `processMs`：距进程启动（含 fork / Application 初始化）；
     * - `onCreateMs`：距 `onCreate` 入口 —— 这一列才是**我们能优化**的部分；
     * - `bootMs`：开机以来 —— 用来区分「系统开机慢」和「桌面启动慢」。
     *
     * 这里用 INFO 级别（默认落盘）：启动一次只打几条，不会撑爆日志。
     */
    @Synchronized
    fun mark(label: String) {
        val at = SystemClock.uptimeMillis()
        marks[label] = at
        val sinceProcess = at - processStartMs
        val sinceOnCreate = at - (marks["onCreate"] ?: firstTouchMs)
        val boot = sinceBoot()
        Logx.i(
            LogTags.APP,
            "启动分段：$label",
            "processMs" to sinceProcess,
            "onCreateMs" to sinceOnCreate,
            "bootMs" to boot,
        )
        android.util.Log.i(
            LOGCAT_TAG,
            "$label process=${sinceProcess}ms onCreate=${sinceOnCreate}ms boot=${boot}ms",
        )
        // 诊断包即使在中途导出，也能拿到目前走过的分段（不必等到 summary）。
        if (label == "onCreate") persist(currentLine())
    }

    /**
     * 桌面**完全可见**时打一条汇总（进程内只打一次）。
     *
     * 汇总把全部分段压成一行 `名称=距onCreate毫秒`，方便一眼看出时间花在哪：
     * 通常关心 `splashFirstFrame`（第一张图）、`launcherFirstFrame`（桌面画出来）、
     * `servicesStarted`（车控/天气/媒体这些重活干完）。
     */
    @Synchronized
    fun summary() {
        if (summarized) return
        summarized = true
        if (marks.isEmpty()) return
        val total = SystemClock.uptimeMillis() - processStartMs
        val boot = sinceBoot()
        val segments = segmentsSinceCreate()
        Logx.i(
            LogTags.APP,
            "启动完成",
            "totalMs" to total,
            "bootMs" to boot,
            "startSource" to startSource,
            "fromCreateMs" to segments,
        )
        android.util.Log.i(
            LOGCAT_TAG,
            "done total=${total}ms boot=${boot}ms start=$startSource fromCreate $segments",
        )
        persist(currentLine())
    }

    /**
     * 诊断包 `summary.txt` 用：本次启动 + 上次持久化的启动摘要。
     *
     * 这样即使日志文件已经被轮转掉、或者用户是热启动后导出，
     * 也能看到**最近一次冷启动**的数字。
     */
    fun describe(): String {
        val now = currentLine()
        val sp = app?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val last = sp?.getString(KEY_LAST, null)
        val lastAt = sp?.getLong(KEY_LAST_AT, 0L) ?: 0L
        val lastCold = sp?.getString(KEY_LAST_COLD, null)
        val lastColdAt = sp?.getLong(KEY_LAST_COLD_AT, 0L) ?: 0L
        val sb = StringBuilder()
        sb.append("本次启动: ").append(now).append('\n')
        if (!last.isNullOrBlank()) {
            sb.append("上次启动: ")
            if (lastAt > 0) {
                sb.append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date(lastAt)))
                    .append("  ")
            }
            sb.append(last).append('\n')
        } else {
            sb.append("上次启动: (还没有持久化记录)\n")
        }
        if (!lastCold.isNullOrBlank()) {
            sb.append("上次冷启动(开机5分钟内): ")
            if (lastColdAt > 0) {
                sb.append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date(lastColdAt)))
                    .append("  ")
            }
            sb.append(lastCold).append('\n')
        }
        sb.append("说明: total/processMs 与 `am start -W` 的 TotalTime 同钟（uptimeMillis）；")
        sb.append("bootMs 是开机以来的毫秒数，用来区分系统开机慢还是桌面启动慢。\n")
        return sb.toString()
    }

    /** 当前已打点的分段（未汇总时导出诊断包也能看）。 */
    @Synchronized
    private fun currentLine(): String {
        if (marks.isEmpty()) return "（本进程还没有启动分段）"
        val total = SystemClock.uptimeMillis() - processStartMs
        return "total=${total}ms boot=${sinceBoot()}ms 起点=$startSource fromCreate ${segmentsSinceCreate()}"
    }

    private fun segmentsSinceCreate(): String {
        val onCreateAt = marks["onCreate"] ?: firstTouchMs
        return marks.entries.joinToString(" ") { (label, at) -> "$label=${at - onCreateAt}" }
    }

    /**
     * 把当前摘要落进 prefs（诊断包 summary.txt 读它）。
     *
     * 同时写两份：
     * - `last`：最近一次（含热启动）；
     * - `last_cold`：只在「开机 5 分钟内启动」时更新 ——
     *   防止后来的热启动记录把开机那次冷启动的数字覆盖掉。
     *
     * 失败不影响启动 —— 这只是诊断，不能因为写盘失败把桌面卡住。
     */
    private fun persist(line: String) {
        val ctx = app ?: return
        runCatching {
            val now = System.currentTimeMillis()
            val cold = sinceBoot() <= COLD_BOOT_WINDOW_MS
            val editor = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_LAST, line)
                .putLong(KEY_LAST_AT, now)
            if (cold) {
                editor.putString(KEY_LAST_COLD, line)
                editor.putLong(KEY_LAST_COLD_AT, now)
            }
            editor.apply()
        }
    }
}
