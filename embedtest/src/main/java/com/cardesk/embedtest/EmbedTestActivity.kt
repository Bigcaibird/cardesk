package com.cardesk.embedtest

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.graphics.Color
import android.view.Display
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 「可被嵌入」的测试承载画面（第140轮）。
 *
 * ## 它证明什么
 *
 * 画中画（`ActivityView` + `VirtualDisplay`）把**别的应用**嵌进我们的窗口。
 * 要在 **API 28** 上验证这条链路，需要一个「允许被嵌入」的目标应用 ——
 * 高德车机版不满足（AOSP 9 要求 activity 级 `resizeableActivity="true"`），见
 * `embedtest/build.gradle.kts` 里的说明。
 *
 * 这个 Activity 就是那个目标：**画一屏有编号的纯色**，截图一眼就能认出是它，
 * 不用去分辨是高德还是别的画面 —— 换肤重建前后如果画面连续，就说明承载面没被拆。
 *
 * ## 为什么自己画而不是放张图
 *
 * - 不依赖任何资源文件（模块最小化，构建快）；
 * - 纯色 + 大号数字在截图里**一眼可辨**（对比度高，不怕压缩）；
 * - 顺带把**承载面当前尺寸**写在画面上 —— 尺寸变了能立刻看出来
 *   （这正是「画中画全屏 ↔ 还原」那类问题的观察点）。
 */
class EmbedTestActivity : Activity() {

    /** 根容器（诊断时要读它的 display）。 */
    private lateinit var root: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val label = TextView(this).apply {
            text = "嵌入测试\nEMBED TEST"
            setTextColor(Color.WHITE)
            textSize = 28f
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#0F6C4A"))
        }
        val size = TextView(this).apply {
            text = "承载面尺寸：待测量"
            setTextColor(Color.parseColor("#BFF3E2"))
            textSize = 14f
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#0F6C4A"))
        }
        val where = TextView(this).apply {
            text = "所在 display：待测量"
            setTextColor(Color.parseColor("#BFF3E2"))
            textSize = 14f
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#0F6C4A"))
        }

        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#0F6C4A"))
            // 注意：weight 是 LinearLayout.LayoutParams 的属性，
            // 用 ViewGroup.LayoutParams 写不出来（编译报 Unresolved reference: weight）。
            addView(
                label,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    0,
                ).apply { weight = 1f },
            )
            addView(
                size,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
            addView(
                where,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
        setContentView(root)

        // 尺寸用 post 延后一帧再读 —— onCreate 里还没布局，读出来是 0。
        root.post {
            val dm = resources.displayMetrics
            size.text = "承载面尺寸：${dm.widthPixels}×${dm.heightPixels} @${dm.densityDpi}dpi"
            where.text = describeDisplay()
        }
    }

    /**
     * 报告「我到底在哪个 display 上」。
     *
     * ## 两条口径，各说各的
     *
     * | 口径 | 是什么 | API 28 上可用吗 |
     *---|---|---|
     * | **task 的 displayId** | AMS 记的 task 归属 | **不可用** —— AOSP 9 的 `RunningTaskInfo` **根本没有这个字段**（AOSP 10 才加） |
     * | **`View.getDisplay()`** | 这个 View 的窗口挂在哪块屏上 | **可用** —— 实测真嵌入时返回虚拟屏 id（16），落到主屏时返回 0 |
     *
     * 第140轮曾以为「`View.getDisplay()` 在 API 28 上恒返回默认屏、不可信」——
     * **那是没量准**：第141轮实测，它返回的是**窗口真实所在的那块屏**。
     * （`ViewRootImpl` 拿不到 display 时才退化成 `Display.DEFAULT_DISPLAY`，
     * 那是「窗口还没挂到虚拟屏」的情形。）
     *
     * 所以这里：先试 task 口径（API 29+ 能读到），读不到就用 View 口径，
     * 并**标明用的是哪一条** —— 别让排障的人把两条口径搞混。
     */
    private fun describeDisplay(): String {
        val taskDisplayId = runCatching {
            val am = getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val task = am?.getRunningTasks(10)
                ?.firstOrNull { it.baseActivity?.packageName == packageName }
            task?.javaClass?.getField("displayId")?.getInt(task)
        }.getOrNull()

        if (taskDisplayId != null) {
            val kind = if (taskDisplayId == Display.DEFAULT_DISPLAY) " ← 主屏，不是虚拟屏" else " ← 虚拟屏"
            return "所在 display：#$taskDisplayId（task 归属）$kind"
        }
        val d = root?.display
        return if (d == null) {
            "所在 display：读不到（task / View 两条路都失败）"
        } else {
            "所在 display：#${d.displayId}（View 口径：窗口挂在这块屏上）"
        }
    }
}
