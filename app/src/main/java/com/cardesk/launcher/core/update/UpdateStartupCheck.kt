package com.cardesk.launcher.core.update

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.cardesk.launcher.core.Prefs
import com.cardesk.launcher.core.log.LogTags
import com.cardesk.launcher.core.log.Logx

/**
 * 桌面启动时的「检查一次在线更新」（第九十八轮）。
 *
 * 和 [AppUpdate] 的分工：
 * - [AppUpdate] 只负责「拉清单 / 下载 / 安装」，本身不关心什么时候查；
 * - 这里负责**启动时查一次**，查到新版就把 [R2Release] 交给界面弹窗，
 *   由用户决定「立即更新」还是「稍后」。
 *
 * 不打扰用户的约定：
 * - 整个进程只查一次（`checking` / `checked` 双保险）；
 * - 用户点过「稍后」的版本（[Prefs.updateSkippedVersionCode]）不再弹；
 * - 查询在后台线程跑，失败只写日志，绝不弹错误框（开机时弹网络失败最烦人）。
 */
object UpdateStartupCheck {

    /** 桌面起来后等多久再查（把启动带宽让给天气/车控）。 */
    const val CHECK_DELAY_MS = 3_000L

    @Volatile
    private var checked = false

    /** 是否应该弹窗：新版本号非 0，且不是用户刚点过「稍后」的那个版本。 */
    fun shouldPrompt(latestVersionCode: Long, skippedVersionCode: Long): Boolean =
        latestVersionCode > 0L && latestVersionCode != skippedVersionCode

    /**
     * 启动检测一次（阻塞部分在后台线程）。
     *
     * @param onAvailable 查到**需要提示**的新版本时回调（主线程）。
     */
    fun maybeCheck(context: Context, prefs: Prefs, onAvailable: (R2Release) -> Unit) {
        if (checked) return
        checked = true
        val app = context.applicationContext
        Thread({
            val result = runCatching { AppUpdate.check(app) }.getOrElse {
                Logx.w(LogTags.SETTING, "启动检查更新失败", it)
                return@Thread
            }
            when (result) {
                is AppUpdate.CheckResult.Available -> {
                    val code = result.release.versionCode
                    if (shouldPrompt(code, prefs.updateSkippedVersionCode)) {
                        Logx.event(
                            LogTags.SETTING, "启动检测到新版本",
                            "version" to result.release.version, "versionCode" to code,
                        )
                        Handler(Looper.getMainLooper()).post { onAvailable(result.release) }
                    } else {
                        Logx.i(LogTags.SETTING, "启动检查更新：该版本已被稍后掉", "versionCode" to code)
                    }
                }
                is AppUpdate.CheckResult.UpToDate -> Logx.i(
                    LogTags.SETTING, "启动检查更新：已是最新", "current" to result.current,
                )
                is AppUpdate.CheckResult.Failed -> Logx.w(
                    LogTags.SETTING, "启动检查更新失败（静默）", "reason" to result.reason,
                )
            }
        }, "update-startup-check").start()
    }

    /** 测试/诊断用：清掉「本进程已查过」的标记。 */
    fun resetForTest() {
        checked = false
    }
}
