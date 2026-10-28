package com.cardesk.launcher.vehicle

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.cardesk.launcher.core.log.LogTags
import com.cardesk.launcher.core.log.Logx

/**
 * 原车车窗开闭。
 *
 * 通过 ECarX `VfmiscManager` 写四个车窗位置
 * `CB_Speech_TargetWinCtrlFrntLe` / / /，
 * 百分比 0 = 全关、50 = 打开；写入失败 800ms 后重试一次。
 *
 * 注意：车窗**没有车况来源**，桌面上的显示是本地乐观值
 * 这里只负责把「关/开」这个意图下发出去。
 */
object OemWindowController {

    const val CLOSE_PERCENT = 0
    const val OPEN_PERCENT = 50

    private val main = Handler(Looper.getMainLooper())

    private val TARGET_APIS = arrayOf(
        "CB_Speech_TargetWinCtrlReLe",
        "CB_Speech_TargetWinCtrlReRi",
        "CB_Speech_TargetWinCtrlFrntLe",
        "CB_Speech_TargetWinCtrlFrntRi",
    )

    /** 车机上是否解析得到车窗控制通道。 */
    fun isAvailable(): Boolean = EcarxSignals.vfmiscManager() != null

    /**
     * 设置四扇车窗的开度百分比。
     *
     * @return true 表示本次就写成功；false 表示通道不可用（已安排 800ms 后重试一次）。
     */
    fun setAllWindows(context: Context, percent: Int): Boolean {
        val p = percent.coerceIn(0, 100)
        // 排障场景：车窗没有车况回读，日志是唯一能证明「到底下发没下发」的证据。
        Logx.event(
            LogTags.WINDOW,
            if (p == CLOSE_PERCENT) "关窗" else if (p == OPEN_PERCENT) "开窗" else "设置车窗开度",
            "percent" to p,
        )
        if (writeAll(p)) {
            Logx.i(LogTags.WINDOW, "车窗下发成功", "percent" to p)
            return true
        }
        Logx.w(LogTags.WINDOW, "车窗下发失败，800ms 后重试一次", "percent" to p)
        main.postDelayed({
            val ok = writeAll(p)
            if (ok) {
                Logx.i(LogTags.WINDOW, "车窗重试下发成功", "percent" to p)
            } else {
                Logx.w(LogTags.WINDOW, "车窗重试仍然失败", "percent" to p)
            }
        }, 800L)
        return false
    }

    /** 开/关四扇车窗；[closed] 为 true 表示关窗。 */
    fun setWindowsClosed(context: Context, closed: Boolean): Boolean =
        setAllWindows(context, if (closed) CLOSE_PERCENT else OPEN_PERCENT)

    private fun writeAll(percent: Int): Boolean {
        val manager = EcarxSignals.vfmiscManager() ?: run {
            // 排障场景：通道拿不到（车机服务没连上 / 无权限）是车窗无效的头号原因。
            Logx.w(LogTags.WINDOW, "车窗通道不可用（拿不到 VfmiscManager）", "percent" to percent)
            return false
        }
        var ok = 0
        for (api in TARGET_APIS) {
            val result = runCatching {
                val method = manager.javaClass.getMethod(api, Int::class.javaPrimitiveType)
                method.invoke(manager, percent)
            }
            result.onSuccess { value ->
                if (isPaApiSucceed(value)) {
                    ok++
                    Logx.d(LogTags.WINDOW, "车窗指令成功", "api" to api, "percent" to percent, "result" to value)
                } else {
                    // 单窗失败要能看到是哪一扇、返回了什么——四扇可能只有部分成功。
                    Logx.w(LogTags.WINDOW, "车窗指令被拒绝", "api" to api, "percent" to percent, "result" to value)
                }
            }.onFailure { t ->
                Logx.w(
                    LogTags.WINDOW,
                    "车窗指令异常",
                    t,
                    "api" to api,
                    "percent" to percent,
                    "reason" to "${t.javaClass.simpleName}: ${t.message}",
                )
            }
        }
        if (ok == 0) {
            Logx.w(LogTags.WINDOW, "四扇车窗全部下发失败", "percent" to percent, "okCount" to ok)
        }
        return ok > 0
    }

    /** 枚举看名字，字符串看 SUCCEED / FAIL / ERROR。 */
    private fun isPaApiSucceed(result: Any?): Boolean {
        if (result == null) return false
        if (result is Enum<*>) {
            val name = result.name
            if (name == "SUCCEED") return true
            if (name.contains("FAIL") || name.contains("ERROR") ||
                name.contains("INVALID") || name.contains("NOT")
            ) return false
        }
        val text = result.toString()
        if (text.contains("SUCCEED") || text.contains("Succeed")) return true
        return !(text.contains("FAIL") || text.contains("Fail") || text.contains("ERROR"))
    }
}
