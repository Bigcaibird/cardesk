package com.cardesk.launcher.core

import android.content.Context
import android.os.SystemClock
import com.cardesk.launcher.core.log.LogTags
import com.cardesk.launcher.core.log.Logx

/**
 * Home 键运行时：把两条入口（Input 拦截 / HOME intent 兜底）合成一条手势流，
 * 并把「回桌面 / 打开最近任务」两个动作交给桌面。
 *
 * 两个开关都放在 Prefs 的通用键里（与 `camera360_radar_open` 同一惯例，不新增字段）：
 *
 * - [KEY_ENABLED]（默认开）：接管 Home 键双击；关掉后两条入口都只做系统原本的事。
 * - [KEY_DOUBLE_RECENTS]（默认开）：双击打开本项目的最近任务页；关掉则双击 = 两次回桌面。
 *
 * ## 为什么两条入口都要
 *
 * - 真车的 Home 键走车机 Input 通道（`com.ecarx.xui.adaptapi.input.Input`），可以
 *   **拦截并吃掉**按键 —— 这是唯一能「不让系统弹自己的最近任务」的路子；
 * - 但并不是每台车/每个固件都能拦到（`Input.requestKeysInterception` 会按厂商
 *   白名单过滤，卡片里没这个键就静默失败），而且模拟器上合成的按键不走厂商
 *   通道。这时候靠 HOME intent 兜底：系统每次 Home 都会把桌面拉起来
 *   （`onNewIntent` 带 `CATEGORY_HOME`），我们在那里数一次按压。
 *
 * 两条入口共用同一个 [HomeKeyPolicy.Detector]，所以不会把「拦截一次 + intent 一次」
 * 算成双击 —— 拦截成功时系统根本不会发 HOME intent；万一固件仍然发了，这里按
 * 拦截通道生效期间一律忽略 HOME intent（真车上它是 0.85~1.0s 后才到的回声，
 * 不能当成新按压 —— 详见 [onHomeIntent]）。
 */
object HomeKeyRuntime {

    /** 接管总开关（默认开）。 */
    const val KEY_ENABLED = "home_key_enabled"

    /** 双击打开本项目最近任务（默认开）。 */
    const val KEY_DOUBLE_RECENTS = "home_double_recents"

    /** 诊断：最近一次 Home 事件（设置页展示）。 */
    const val KEY_DIAG_LAST = "home_diag_last"

    private val detector = HomeKeyPolicy.Detector()

    private var lastInterceptAt = 0L

    /** 动作出口：由 [com.cardesk.launcher.ui.LauncherView] 在启动服务时挂上。 */
    @Volatile
    var onGoHome: (() -> Unit)? = null

    @Volatile
    var onShowRecents: (() -> Unit)? = null

    /** 车机 Input 通道是否真的接管到了 Home 键（设置页展示）。 */
    @Volatile
    var interceptorActive: Boolean = false

    /** 车机 Input 通道状态文案（设置页展示）。 */
    @Volatile
    var interceptorStatus: String = "未启动"

    /** 功能是否开启（两条入口共用）。 */
    fun enabled(prefs: Prefs): Boolean = prefs.flag(KEY_ENABLED, true)

    /** 双击是否映射到本项目最近任务。 */
    fun doubleRecents(prefs: Prefs): Boolean = prefs.flag(KEY_DOUBLE_RECENTS, true)

    /**
     * 拦截通道来的一次按压。
     *
     * @return 是否吃掉这次按键（吃掉后系统不再处理 Home）。
     */
    fun onInterceptPress(context: Context, prefs: Prefs): Boolean {
        lastInterceptAt = SystemClock.uptimeMillis()
        press(context, prefs, "车机 Input 拦截")
        // 接管时**必须吃掉**按键：不吃掉的话框架自己的「双击 Home 开系统最近任务」
        // 仍会执行，用户会看到系统最近任务闪一下再被我们的盖住。
        // 关掉开关就原样放行，交给系统。
        return enabled(prefs)
    }

    /**
     * HOME intent 兜底路径的一次按压（`MainActivity.onNewIntent` 带 CATEGORY_HOME）。
     *
     * @return 需要桌面执行的动作。[HomeKeyPolicy.Action.SHOW_RECENTS] 表示这次是双击。
     */
    fun onHomeIntent(context: Context, prefs: Prefs): HomeKeyPolicy.Action {
        if (interceptorActive) {
            // 真车实测（2026-11-24，diagnostic 包 18:30:56–18:31:24）：
            // Input 通道接管之后，车机框架仍会在 **约 0.85~1.0 秒**后补发一条
            // CATEGORY_HOME（`consume=true` 只挡住了框架的一部分）。
            // 原来用 500ms 时间窗去重，回声正好落在窗外 ⇒ 被当成「第二次按压」，
            // 单击被判成双击、我们的最近任务莫名其妙弹出来，紧接着车机自己的
            // 最近任务也到前台（用户看到「两个最近任务先后出现」）。
            //
            // 接管期间这条通道上的 HOME intent 全是回声：按压次数只由 Input
            // 通道记账，这里一律忽略（不再用时间窗猜）。
            Logx.d(
                LogTags.HOME,
                "Home intent 已按回声忽略（Input 通道接管中）",
                "sinceInterceptMs" to (SystemClock.uptimeMillis() - lastInterceptAt),
            )
            return HomeKeyPolicy.Action.NONE
        }
        // 拦截通道不可用时才走兜底：这条路上两条动作都由调用方（MainActivity / 桌面）
        // 自己落地，这里只做判定。
        return press(context, prefs, "HOME intent", dispatch = false)
    }

    /**
     * 设置页自测：直接灌一次按压，走与真实按键完全相同的决策。
     *
     * @param dispatch 是否真的执行动作。「双击」自测会先灌一次 `dispatch=false`
     *   的第一下（只做判定、不真的回桌面），再灌第二下执行打开最近任务 ——
     *   否则第一下就把设置页关了，第二下没地方点。
     */
    fun simulate(context: Context, prefs: Prefs, dispatch: Boolean = true): HomeKeyPolicy.Action =
        press(context, prefs, "设置页自测", dispatch = dispatch)

    /** 重置手势状态（桌面销毁 / 切换开关时用）。 */
    fun reset() {
        detector.reset()
        lastInterceptAt = 0L
    }

    /** 诊断行文本。 */
    fun diag(context: Context, prefs: Prefs): String = prefs.stringValue(KEY_DIAG_LAST, "")

    private fun press(
        context: Context,
        prefs: Prefs,
        source: String,
        dispatch: Boolean = true,
    ): HomeKeyPolicy.Action {
        if (!enabled(prefs)) {
            detector.reset()
            Logx.i(LogTags.HOME, "Home 键未接管（开关关闭）", "source" to source)
            return HomeKeyPolicy.Action.NONE
        }
        val action = detector.onPress(
            now = SystemClock.uptimeMillis(),
            enabled = true,
            doubleRecents = doubleRecents(prefs),
        )
        val text = when (action) {
            HomeKeyPolicy.Action.GO_HOME -> "回桌面"
            HomeKeyPolicy.Action.SHOW_RECENTS -> "双击 · 最近任务"
            HomeKeyPolicy.Action.NONE -> "无动作"
        }
        runCatching { prefs.setStringValue(KEY_DIAG_LAST, text) }
        Logx.event(LogTags.HOME, "Home 键动作", "source" to source, "action" to action.name, "text" to text)
        when (action) {
            HomeKeyPolicy.Action.GO_HOME -> if (dispatch) onGoHome?.invoke()
            HomeKeyPolicy.Action.SHOW_RECENTS -> if (dispatch) onShowRecents?.invoke()
            HomeKeyPolicy.Action.NONE -> Unit
        }
        return action
    }
}
