package com.cardesk.launcher.core.log

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.widget.Toast

/**
 * 日志相关的 UI 辅助：导出诊断包、分享、预览、清空。
 *
 * 导出是**阻塞 IO**（读文件 + 写 zip + 拷公共目录），必须在后台线程跑，
 * 完成后回主线程提示用户。
 */
object LogTools {

    private val main = Handler(Looper.getMainLooper())

    /**
     * 导出诊断包。
     *
     * @param onDone 主线程回调，参数是给用户看的提示文案。
     */
    fun export(context: Context, onDone: (String) -> Unit) {
        val app = context.applicationContext
        Thread({
            val result = Diagnostics.export(app)
            main.post {
                when (result) {
                    is Diagnostics.Result.Ok -> onDone(
                        "诊断包已导出：${result.displayPath}\n" +
                            "共 ${result.fileCount} 个日志文件、${result.bytes / 1024} KB",
                    )
                    is Diagnostics.Result.Fail -> onDone("导出失败：${result.reason}")
                }
            }
        }, "cardesk-log-export").start()
    }

    /**
     * 导出并调起系统分享（把 zip 直接发给开发）。
     *
     * @param onDone 主线程回调，参数是给用户看的提示文案（成功时为空串）。
     */
    fun exportAndShare(context: Context, onDone: (String) -> Unit) {
        val app = context.applicationContext
        Thread({
            val result = Diagnostics.export(app)
            main.post {
                when (result) {
                    is Diagnostics.Result.Ok -> {
                        val uri = result.uri
                        if (uri == null) {
                            onDone("已导出到 ${result.displayPath}，该系统版本不支持直接分享，请手动取文件")
                            return@post
                        }
                        try {
                            val intent = Diagnostics.shareIntent(uri)
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            app.startActivity(Intent.createChooser(intent, "发送 CarDesk 诊断日志").apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            })
                            onDone("")
                        } catch (t: Throwable) {
                            onDone("已导出到 ${result.displayPath}，但拉起分享失败：${t.message}")
                        }
                    }
                    is Diagnostics.Result.Fail -> onDone("导出失败：${result.reason}")
                }
            }
        }, "cardesk-log-share").start()
    }

    /** 提示。 */
    fun toast(context: Context, message: String) {
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
    }

    /** 清空日志。 */
    fun clear(context: Context, onDone: (String) -> Unit) {
        Logx.clear()
        main.post { onDone("日志已清空") }
    }

    /** 末尾若干行预览。 */
    fun preview(lines: Int = 60): String = Logx.tail(lines).ifBlank { "(暂无日志)" }
}
