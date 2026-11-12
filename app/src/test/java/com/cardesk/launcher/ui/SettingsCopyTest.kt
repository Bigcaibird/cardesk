package com.cardesk.launcher.ui

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.cardesk.launcher.core.Palettes
import com.cardesk.launcher.core.Prefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * 设置页文案精简（第150轮）。
 *
 * ## 这条测试锁住什么
 *
 * 第150轮把设置面板里**啰嗦的功能描述、需求解释**全删了，只留下三类：
 *
 * ① **状态**：当前是什么、装没装、连没连；
 * ② **风险警告**：会重启系统之类的；
 * ③ **法律声明**：开源与授权。
 *
 * 「删掉」容易，「别又加回来」难 —— 所以这里给说明行和开关副标题都打了标记
 * （`SettingsOverlay.NOTE_MARK` / `SUBTITLE_MARK`），
 * 测试顺着视图树把它们**挑出来**，逐条对着白名单核。
 *
 * ## 为什么不用源码断言
 *
 * 源码断言只能查「某句话在不在」，查不出「一共还剩几条」、
 * 也查不出「换个说法又写回去」了。视图树这层能真查。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1920dp-h1080dp-land")
class SettingsCopyTest {

    private fun overlay(): SettingsOverlay {
        val activity = Robolectric.buildActivity(com.cardesk.launcher.MainActivity::class.java).setup().get()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        return SettingsOverlay(activity, Prefs(activity), Palettes.night)
    }

    /** 反射拿设置页正文容器。 */
    private fun bodyOf(overlay: SettingsOverlay): ViewGroup {
        val f = SettingsOverlay::class.java.getDeclaredField("body").apply { isAccessible = true }
        return f.get(overlay) as ViewGroup
    }

    /** 顺着视图树把打了标记的说明文字全捞出来。 */
    private fun markedTexts(root: View, mark: Any): List<String> {
        val out = ArrayList<String>()
        fun walk(v: View) {
            if (v.tag === mark && v is TextView) out.add(v.text.toString())
            if (v is ViewGroup) {
                for (i in 0 until v.childCount) walk(v.getChildAt(i))
            }
        }
        walk(root)
        return out
    }

    /** 打开一页，把该页的说明文字收集起来。 */
    private fun pageCopy(key: String): Pair<List<String>, List<String>> {
        val overlay = overlay()
        overlay.showPageKey(key)
        shadowOf(android.os.Looper.getMainLooper()).idle()
        val body = bodyOf(overlay)
        return markedTexts(body, SettingsOverlay.NOTE_MARK) to
            markedTexts(body, SettingsOverlay.SUBTITLE_MARK)
    }

    /**
     * 设置页**所有**页面 key。
     *
     * 除了导航树里的条目，还要加上 `about`（开源与声明）——
     * 它的入口按钮（左栏底部的「开源与声明」）被底部 Dock 挡住了，
     * 真机上点不到，但**那一页的文案照样要有人盯**，
     * 不然法律声明那段就变成了没人管的死角。
     */
    private fun allPageKeys(): List<String> {
        val overlay = overlay()
        val f = SettingsOverlay::class.java.getDeclaredField("navRows").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val rows = f.get(overlay) as Map<String, TextView>
        return rows.keys.toList() + "about"
    }

    // -------------------------------------------------------------- 白名单

    /**
     * **允许留在设置页里的说明文字**。
     *
     * 每条后面写清楚它属于哪一类：
     * - 状态：告诉用户「现在是什么情况」；
     * - 条件：只在特定状态下出现（缺了它用户会一头雾水）；
     * - 风险：会重启系统之类的警告；
     * - 互斥/语义：两个开关之间的关系，标题推不出来；
     * - 法律：开源与授权。
     */
    private val ALLOWED = listOf(
        // —— 状态 ——
        "当前：" to "状态",
        "预留区未启用" to "状态",
        "这台车机没有解析到原厂设置入口" to "状态",
        "正在扫描原厂设置入口" to "状态",
        "导出目录：" to "状态",
        "保存路径：" to "状态",
        "… 还有" to "状态",
        // —— 状态（运行时拼出来的，内容随设备变）——
        "平台：" to "状态",
        "未授予" to "状态",
        "(暂无日志)" to "状态",
        // —— 条件提示 ——
        "要先在上面把默认来源选成具体的 App" to "条件",
        "投射到哪块屏：" to "小标题",
        "当前投射状态：" to "小标题",
        "未上桌的卡，点一下添加：" to "小标题",
        // —— 风险 ——
        "将关闭所有应用并重新启动系统" to "风险",
        // —— 互斥 / 语义 ——
        "与隐藏 Dock / 下移 Dock 互斥" to "互斥",
        "与增强导航栏互斥" to "互斥",
        "开启后保持 ISS 关闭" to "语义",
        "关：只看预设；开：展开全部滑杆" to "语义",
        "续航卡自适应时会吃掉行尾剩余宽度" to "语义",
        // —— 选项说明（不写就分不清选项）——
        "上一句 / 当前句 / 下一句" to "选项",
        "歌词块始终居中" to "选项",
        "两行位置是定死的" to "选项",
        "颜色跟着频谱缓慢换" to "选项",
        "所有参数都开放" to "选项",
        // —— 法律 ——
        "本应用源代码完整公开" to "法律",
    )

    /** **绝不该再出现**的啰嗦文案（这轮删掉的那些）。 */
    private val FORBIDDEN = listOf(
        "长按底部卡片可拖动排序",
        "右侧固定卡片（车门 / 车窗 / 引擎盖 / 后备箱）",
        "第二页音乐卡的整张卡面可以是歌曲图片",
        "点「+」挑应用",
        "调试完请点「恢复全部设置」",
        "Dock 状态栏里那条歌词",
        "桌面正中那坨大字歌词",
        "放大后一句能显示的字会变少",
        "胶囊 = 圆角底板 + 普通字",
        "0% = 不画底板",
        "默认跟随日夜：夜间用薄荷白",
        "切换后立即生效",
        "列表由系统实时解析",
        "桌面启动时自动锁定这个来源",
        "排查问题时调到「调试」",
        "端口 18765..18775",
        "桌面启动 3 秒后自动检测一次",
        "24 小时制 · 点按箭头调整",
        // 开关副标题（这轮删掉的那些）
        "开机后进入本桌面",
        "开机后将导航投送到仪表屏",
        "连接车机仪表与方控通道",
        "为方向盘按键绑定额外动作",
        "系统、地图与桌面主题保持一致",
        "关闭后桌面右侧不再显示这张卡",
        "关闭后音乐卡退回纯色卡面",
        "第二页右侧胎压和音乐卡片替换为第二画中画",
        "按键与档位联动环影视角",
        "开机后自动打开车机热点",
        "开机后自动打开调试开关",
        "开机后自动开启 5555 端口",
        "把歌词贴到副屏",
        "在字外面套一圈对比色",
        "桌面自带 Dock 平时隐藏",
        "只隐藏桌面自己画的底部控制栏",
        "对指定应用隐藏原车 Dock",
        "仅隐藏原车顶栏",
        "在其它应用底部常驻 Dock",
        "桌面起来后拉起这个 App 并让它开始播放",
        "跟随正在播放的音乐改变灯光颜色与亮度",
        "三个分区轮流点亮",
    )

    // -------------------------------------------------------------- 用例

    @Test
    fun `说明文字只能剩白名单里那几类`() {
        val overlay = overlay()
        val bad = ArrayList<String>()
        for (key in allPageKeys()) {
            overlay.showPageKey(key)
            shadowOf(android.os.Looper.getMainLooper()).idle()
            val body = bodyOf(overlay)
            val texts = markedTexts(body, SettingsOverlay.NOTE_MARK) +
                markedTexts(body, SettingsOverlay.SUBTITLE_MARK)
            for (t in texts) {
                val ok = ALLOWED.any { (prefix, _) -> t.startsWith(prefix) }
                if (!ok) bad.add("[$key] $t")
            }
        }
        assertTrue(
            "设置页里出现了不在白名单的说明文字：\n" + bad.joinToString("\n"),
            bad.isEmpty(),
        )
    }

    @Test
    fun `啰嗦文案不许再出现`() {
        val overlay = overlay()
        val bad = ArrayList<String>()
        for (key in allPageKeys()) {
            overlay.showPageKey(key)
            shadowOf(android.os.Looper.getMainLooper()).idle()
            val body = bodyOf(overlay)
            val texts = markedTexts(body, SettingsOverlay.NOTE_MARK) +
                markedTexts(body, SettingsOverlay.SUBTITLE_MARK)
            for (t in texts) {
                FORBIDDEN.forEach { f ->
                    if (t.contains(f)) bad.add("[$key] $t")
                }
            }
        }
        assertTrue(
            "这些啰嗦文案又回来了：\n" + bad.joinToString("\n"),
            bad.isEmpty(),
        )
    }

    @Test
    fun `说明文字总数不许多于白名单条数`() {
        val overlay = overlay()
        var count = 0
        for (key in allPageKeys()) {
            overlay.showPageKey(key)
            shadowOf(android.os.Looper.getMainLooper()).idle()
            val body = bodyOf(overlay)
            count += markedTexts(body, SettingsOverlay.NOTE_MARK).size
            count += markedTexts(body, SettingsOverlay.SUBTITLE_MARK).size
        }
        assertTrue(
            "说明文字一共 $count 条，比预期的多 —— 又加回来了？",
            count <= ALLOWED.size,
        )
    }

    @Test
    fun `开关副标题只剩互斥和语义那几条`() {
        val overlay = overlay()
        val subtitles = ArrayList<String>()
        for (key in allPageKeys()) {
            overlay.showPageKey(key)
            shadowOf(android.os.Looper.getMainLooper()).idle()
            subtitles += markedTexts(bodyOf(overlay), SettingsOverlay.SUBTITLE_MARK)
        }
        // 互斥 2 + ISS 语义 1 + 自定义参数 1 + 续航卡 1 = 5
        assertEquals(
            "开关副标题应该只剩互斥 / 语义那几条，实际：$subtitles",
            5,
            subtitles.size,
        )
    }

    /**
     * **标记不能被摘掉**。
     *
     * 前面几条用例全靠标记从视图树里挑说明文字 ——
     * 哪天有人把 [SettingsOverlay.NOTE_MARK] 的赋值删了，
     * 检查就会「什么都没查到」然后**全绿通过**，那是典型的假通过。
     *
     * 所以这里单独盯住「确实还能挑出东西」。
     */
    @Test
    fun `说明行的标记还在_不然检查就形同虚设`() {
        val overlay = overlay()
        var notes = 0
        var subtitles = 0
        for (key in allPageKeys()) {
            overlay.showPageKey(key)
            shadowOf(android.os.Looper.getMainLooper()).idle()
            val body = bodyOf(overlay)
            notes += markedTexts(body, SettingsOverlay.NOTE_MARK).size
            subtitles += markedTexts(body, SettingsOverlay.SUBTITLE_MARK).size
        }
        assertTrue(
            "一条带标记的说明文字都没挑到（$notes 条）—— 标记被摘掉了？",
            notes >= 8,
        )
        assertTrue(
            "一条带标记的开关副标题都没挑到（$subtitles 条）—— 标记被摘掉了？",
            subtitles >= 3,
        )
    }
}
