package com.cardesk.launcher.ui.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import com.cardesk.launcher.core.QrCode
import com.cardesk.launcher.core.Skin
import com.cardesk.launcher.core.log.LogTags
import com.cardesk.launcher.core.log.Logx

/**
 * 二维码视图：把一段文本（一般是局域网访问地址）画成二维码。
 *
 * ## 为什么要有它
 *
 * 「设置 → 局域网传输」原来只显示一行 `http://192.168.x.x:18765/?t=<token>` 文本，
 * 用户得**手动把地址敲到电脑浏览器里** —— 车上用键盘敲 IP + 端口 + token 极易出错。
 *
 * 有了二维码，手机扫一下就打开了。
 *
 * ## 画法
 *
 * 二维码是**模块矩阵**（每个格子黑 / 白），所以：
 * - 外围留一圈**静区**（quiet zone，标准要求至少 4 个模块），不然有些扫码器认不出；
 * - 每个模块画成一个正方形，尺寸取整（避免抗锯齿把边糊掉）；
 * - 深色用近黑（不是纯黑，纯黑在深色背景上反而更难看），浅色用白。
 *
 * 编码器在 [QrCode]（纯 Kotlin，无第三方依赖）。
 */
class QrCodeView(context: Context) : View(context) {

    /** 要编码的文本（一般是访问地址）。空 → 不画。 */
    var text: String = ""
        set(value) {
            if (field == value) return
            field = value
            matrix = null
            failed = false
            invalidate()
        }

    /** 编码出来的矩阵（null = 还没编 / 编不出来）。 */
    private var matrix: QrCode.Matrix? = null

    /** 编码失败（文本太长之类）。 */
    private var failed = false

    /** 期望的边长（px）；实际会按测量结果等比缩放。 */
    var preferredSize: Int = 320

    private val paint = Paint().apply {
        style = Paint.Style.FILL
        isAntiAlias = false
    }

    private val quietZone = 4

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        val size = if (w > 0 && w <= h) w else if (h > 0) h else preferredSize
        setMeasuredDimension(size, size)
    }

    override fun onDraw(canvas: Canvas) {
        val m = matrix ?: encode()
        if (m == null) {
            if (failed) {
                // 编不出来（文本过长）→ 画个提示块，总比一片空白强
                paint.color = 0x22FFFFFF
                canvas.drawRoundRect(
                    RectF(0f, 0f, width.toFloat(), height.toFloat()),
                    16f, 16f, paint,
                )
            }
            return
        }
        val n = m.size
        // —— 静区：标准要求四周至少 4 个模块
        val total = n + quietZone * 2
        val cell = minOf(width, height).toFloat() / total
        if (cell <= 0f) return
        val originX = (width - total * cell) / 2f
        val originY = (height - total * cell) / 2f

        // 底：白底（扫码器需要浅底深码）
        paint.color = Color.WHITE
        canvas.drawRect(
            originX, originY,
            originX + total * cell, originY + total * cell,
            paint,
        )

        // 码：近黑（纯黑在深色主题上偏重）
        paint.color = if (Skin.isNight) 0xFF101418.toInt() else 0xFF14181D.toInt()
        for (y in 0 until n) {
            for (x in 0 until n) {
                if (!m[x, y]) continue
                val left = originX + (x + quietZone) * cell
                val top = originY + (y + quietZone) * cell
                // 画到格子右下角一点点，避免相邻模块之间出现白缝
                canvas.drawRect(left, top, left + cell + 0.5f, top + cell + 0.5f, paint)
            }
        }
    }

    /**
     * 编码一次（懒编，失败会记一条日志）。
     *
     * 编码不便宜（Reed-Solomon + 掩码评分），所以结果缓存住，
     * 只有文本变了才重编。
     */
    private fun encode(): QrCode.Matrix? {
        val t = text
        if (t.isEmpty()) return null
        return try {
            val m = QrCode.encode(t)
            matrix = m
            failed = false
            m
        } catch (t2: Throwable) {
            failed = true
            Logx.w(LogTags.LAN, "二维码编码失败", t2, "len" to t.length)
            null
        }
    }
}
