package com.cardesk.launcher.vehicle

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.cardesk.launcher.core.log.LogTags
import com.cardesk.launcher.core.log.Logx

/**
 * 原车车窗开闭。
 *
 * 写入优先级：
 * 1. **领克 820A AdaptAPI 浮点通道**（`ICarFunction.setCustomizeFunctionValue(BCM_FUNC_WINDOW_POS, zone, percent)`，
 *    四门 zone = 16 / 32 / 256 / 512，底层对应 `setCbSpeechTargetWinCtrlFrntLe/FrntRi/ReLe/ReRi`）；
 * 2. **ECarX `VfmiscManager` 兜底**（E03 等平台，`CB_Speech_TargetWinCtrl*`）。
 *
 * 百分比 0 = 全关、50 = 打开；写入失败 800ms 后在后台线程重试一次。
 */
object OemWindowController {

    const val CLOSE_PERCENT = 0
    const val OPEN_PERCENT = 50

    /** 领克 820A 逐扇车窗位置功能号（`IBcm.BCM_FUNC_WINDOW_POS`）。 */
    const val BCM_FUNC_WINDOW_POS = 553845504

    /** 四窗合一区域位（对齐小咖语音 `VehicleV2Manager.controlCarAllWindows`: `16|32|256|512 = 816`）。 */
    const val ZONE_ALL_WINDOWS = 816

    /** 四门车窗区域位（FL=16, FR=32, RL=256, RR=512）。 */
    val WINDOW_ZONES = intArrayOf(16, 32, 256, 512)

    private val main = Handler(Looper.getMainLooper())

    private val TARGET_APIS = arrayOf(
        "CB_Speech_TargetWinCtrlReLe",
        "CB_Speech_TargetWinCtrlReRi",
        "CB_Speech_TargetWinCtrlFrntLe",
        "CB_Speech_TargetWinCtrlFrntRi",
    )

    /** 车机上是否解析得到车窗控制通道。 */
    fun isAvailable(): Boolean =
        (VehicleController.initialized && VehicleController.gateway.isConnected()) ||
            EcarxSignals.vfmiscManager() != null

    /**
     * 设置四扇车窗的开度百分比。
     *
     * @return true 表示本次就写成功；false 表示通道不可用（已安排 800ms 后在后台重试一次）。
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
            Thread({
                val ok = writeAll(p)
                if (ok) {
                    Logx.i(LogTags.WINDOW, "车窗重试下发成功", "percent" to p)
                } else {
                    Logx.w(LogTags.WINDOW, "车窗重试仍然失败", "percent" to p)
                }
            }, "cardesk-window-retry").apply { isDaemon = true }.start()
        }, 800L)
        return false
    }

    /** 开/关四扇车窗；[closed] 为 true 表示关窗。 */
    fun setWindowsClosed(context: Context, closed: Boolean): Boolean =
        setAllWindows(context, if (closed) CLOSE_PERCENT else OPEN_PERCENT)

    /** 在后台线程执行开/关四扇车窗（供自动任务调用，避免阻塞主线程）。 */
    fun setWindowsClosedAsync(
        context: Context,
        closed: Boolean,
        onResult: ((Boolean) -> Unit)? = null,
    ) {
        val appCtx = context.applicationContext
        Thread({
            val ok = runCatching { setWindowsClosed(appCtx, closed) }.getOrDefault(false)
            if (onResult != null) {
                main.post { onResult(ok) }
            }
        }, "cardesk-window-ctrl").apply { isDaemon = true }.start()
    }

    private fun writeAll(percent: Int): Boolean {
        if (writeViaGateway(percent)) return true
        return writeViaVfmisc(percent)
    }

    /** 优先走车控通道：模拟通道直接改本地状态；真车走 AdaptAPI 浮点逐窗写入。 */
    private fun writeViaGateway(percent: Int): Boolean {
        if (!VehicleController.initialized) return false
        val gw = runCatching { VehicleController.gateway }.getOrNull() ?: return false
        if (gw is MockVehicleGateway) {
            gw.setWindowsOpen(percent > CLOSE_PERCENT)
            Logx.i(LogTags.WINDOW, "车窗指令已下发（模拟通道）", "percent" to percent)
            return true
        }
        // ① 优先走 820A 原厂小咖语音同款四窗合一 zone=816
        val allOk = runCatching {
            gw.writeCustomizeFunctionValue(BCM_FUNC_WINDOW_POS, ZONE_ALL_WINDOWS, percent.toFloat())
        }.getOrDefault(false)
        if (allOk) {
            Logx.i(
                LogTags.WINDOW,
                "车窗指令已通过 AdaptAPI 下发（四窗合一）",
                "channel" to gw.id,
                "zone" to ZONE_ALL_WINDOWS,
                "percent" to percent,
            )
            return true
        }
        // ② 兜底：逐扇下发 16 / 32 / 256 / 512
        var ok = 0
        for (zone in WINDOW_ZONES) {
            val wrote = runCatching {
                gw.writeCustomizeFunctionValue(BCM_FUNC_WINDOW_POS, zone, percent.toFloat())
            }.getOrDefault(false)
            if (wrote) ok++
        }
        if (ok > 0) {
            Logx.i(
                LogTags.WINDOW,
                "车窗指令已通过 AdaptAPI 下发（逐扇）",
                "channel" to gw.id,
                "percent" to percent,
                "okCount" to ok,
                "total" to WINDOW_ZONES.size,
            )
            return true
        }
        return false
    }

    private fun writeViaVfmisc(percent: Int): Boolean {
        val manager = EcarxSignals.vfmiscManager() ?: run {
            // 排障场景：通道拿不到（车机服务没连上 / 无权限）是车窗无效的头号原因。
            Logx.w(LogTags.WINDOW, "车窗通道不可用（AdaptAPI 与 VfmiscManager 均失败）", "percent" to percent)
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
