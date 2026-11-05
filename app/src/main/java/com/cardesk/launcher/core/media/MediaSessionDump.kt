package com.cardesk.launcher.core.media

import com.cardesk.launcher.core.log.LogTags
import com.cardesk.launcher.core.log.Logx
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 把系统里的**全部媒体会话**打出来（第126轮新增，诊断用）。
 *
 * ## 为什么需要它
 *
 * `MediaSessionManager.getActiveSessions()` 只返回**活跃**会话
 * （发布过非 `STATE_NONE` 的 `PlaybackState`）。
 * 真车（领克 dcy11）上「QQ 音乐车机版拿不到歌」这个问题，靠日志只能看到
 * 「候选里没有它」，但**看不到它到底有没有会话**：
 *
 * - 它**压根没有**会话 → 我们只能靠它的广播（[QqBroadcast]）；
 * - 它**有**会话但没发布播放态 → 枚举看不到，得靠通知里的 token。
 *
 * 这两种情况的修法完全不同，而 `dumpsys media_session` 一把就能分清 ——
 * 它列出**全部**会话（含不活跃的）及其状态。
 *
 * ## 能不能跑
 *
 * `/system/bin/dumpsys` 是 `-rwxr-xr-x`（**任何应用都能执行**），
 * 而 dump 本身的权限校验在服务里：调用方 uid 必须是
 * root(0) / system(1000) / shell(2000)。
 * 本应用是 `sharedUserId=android.uid.system` 的系统应用 → uid 1000 → **可以**。
 *
 * 拿不到就返回一句说明，**绝不抛异常**（诊断包不能因为这一步失败就出不来）。
 */
object MediaSessionDump {

    /** 输出上限（字符）—— 防止某个系统的 dump 特别长把诊断包撑爆。 */
    private const val MAX_CHARS = 12_000

    /** 超时（秒）—— dump 卡住就放弃。 */
    private const val TIMEOUT_SECONDS = 8L

    /**
     * 执行 `dumpsys media_session` 并返回文本。
     *
     * 失败时返回一行说明（`取不到：…`），不抛异常。
     */
    fun dump(): String {
        val proc = try {
            val p = ProcessBuilder("dumpsys", "media_session")
                .redirectErrorStream(true)
                .start()
            // 先把输出读完再等退出 —— 管道写满时进程会阻塞。
            val text = try {
                p.inputStream.bufferedReader().use { it.readText() }
            } catch (t: Throwable) {
                ""
            }
            if (!p.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                p.destroy()
                return "取不到：dumpsys 超时（${TIMEOUT_SECONDS}s）"
            }
            if (text.isBlank()) {
                return "取不到：dumpsys 没有输出（exit=${p.exitValue()}）"
            }
            text
        } catch (t: Throwable) {
            Logx.w(LogTags.MEDIA, "dumpsys media_session 失败", t)
            return "取不到：${t.javaClass.simpleName} ${t.message.orEmpty()}"
        }
        return if (proc.length > MAX_CHARS) proc.take(MAX_CHARS) + "\n…（已截断，共 ${proc.length} 字符）" else proc
    }

    /**
     * 只看「有哪些包有会话」—— 一行摘要，比整份 dump 好读。
     *
     * 从 dump 文本里抓 `package=com.xxx` 这种行（不同 Android 版本格式略有差异，
     * 抓不到就返回空串，不影响整份 dump）。
     */
    fun summarizePackages(dumpText: String): String {
        val pkgs = LinkedHashSet<String>()
        val re = Regex("""package[=:]\s*([A-Za-z0-9_.]+)""")
        for (m in re.findAll(dumpText)) pkgs.add(m.groupValues[1])
        return pkgs.joinToString(",")
    }
}
