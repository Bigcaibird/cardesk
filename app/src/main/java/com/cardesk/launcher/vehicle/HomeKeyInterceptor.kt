package com.cardesk.launcher.vehicle

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.cardesk.launcher.core.HomeKeyRuntime
import com.cardesk.launcher.core.OemRecentsSuppressor
import com.cardesk.launcher.core.Prefs
import com.cardesk.launcher.core.log.LogTags
import com.cardesk.launcher.core.log.Logx
import java.lang.reflect.Proxy

/**
 * 车机 Input 通道的 Home 键接管。
 *
 * ## 为什么不是 `dispatchKeyEvent`
 *
 * Home 键在 `PhoneWindowManager` 里就被吃掉了（回桌面 / 双击开系统最近任务），
 * 应用层的 `dispatchKeyEvent` 永远看不到。车机固件另外提供了一条给应用用的
 * 按键拦截通道（`ecarx.adapterapi` 里的 `com.ecarx.xui.adaptapi.input.Input`），
 * 可以让应用在**框架之前**拿到 Home 键并决定要不要吃掉：
 *
 * ```java
 * Input input = Input.create(context);
 * int[] accepted = input.requestKeysInterception(new int[]{ 3 }, callback);
 * // callback.onKeyPressed(3) 返回 true = 吃掉，框架不再处理
 * ```
 *
 * 白名单是固件里的 `CarInputHandlingService.InputFilter`，注册不在名单里的码会被
 * 静默过滤（返回值里没有它）。所以这里必须看 `accepted` 里到底有没有
 * `KEYCODE_HOME`，没有就明确报「不可用」，让设置页显示出来。
 *
 * ## 失败就是失败
 *
 * 反射/服务连接/固件差异任何一种失败都只记日志并把状态写进
 * [HomeKeyRuntime.interceptorStatus]，不抛异常 —— 这条通道是「锦上添花」，
 * 拿不到时 HOME intent 兜底路径仍然工作。
 */
class HomeKeyInterceptor(
    private val context: Context,
    private val prefs: Prefs,
) {

    companion object {
        /** `KeyEvent.KEYCODE_HOME`。 */
        const val KEYCODE_HOME = 3

        private const val CLS_INPUT = "com.ecarx.xui.adaptapi.input.Input"
        private const val CLS_CALLBACK = "com.ecarx.xui.adaptapi.input.IKeyCallback"
        private const val M_CREATE = "create"
        private const val M_REQUEST = "requestKeysInterception"
        private const val M_ABANDON = "abandonKeysInterception"
        private const val M_PRESSED = "onKeyPressed"
        private const val M_RELEASED = "onKeyReleased"

        /**
         * 重试节奏（毫秒）。
         *
         * 车机刚开机时厂商输入服务往往还没注册 binder（失败信息是
         * `IllegalStateException: Failed to find IInputManager`），桌面比它先起来
         * 很正常 —— 一次失败就永久放弃的话，这一轮就再也接不上了。
         * 桌面每次 attach 会重新 start()，所以这串重试只是「等它起来」。
         */
        private val RETRY_DELAYS_MS = longArrayOf(1_000L, 2_000L, 3_000L, 5_000L, 8_000L, 12_000L, 20_000L)
    }

    private val main = Handler(Looper.getMainLooper())

    private var input: Any? = null
    private var callback: Any? = null

    @Volatile
    private var accepted: IntArray = IntArray(0)

    @Volatile
    private var failure: String? = null

    @Volatile
    private var started = false

    private var retryIndex = 0

    private val retry = Runnable {
        if (!started || active) return@Runnable
        attempt()
    }

    /** 是否真的接管到了 Home 键。 */
    val active: Boolean
        get() = started && input != null && accepted.contains(KEYCODE_HOME)

    /** 设置页展示的状态文案。 */
    val statusText: String
        get() = when {
            active -> "已接管（按键码 ${accepted.joinToString(",")}）"
            failure != null -> "不可用：$failure"
            started -> "已注册但固件未放行 Home 键（白名单：${accepted.joinToString(",").ifEmpty { "空" }}）"
            else -> "未启动"
        }

    /** 启动接管；幂等。 */
    fun start() {
        if (active) return
        started = true
        main.removeCallbacks(retry)
        retryIndex = 0
        attempt()
    }

    /** 尝试注册一次；失败就按 [RETRY_DELAYS_MS] 排下一次。 */
    private fun attempt() {
        failure = null
        runCatching { register() }
            .onFailure {
                failure = describe(it)
                // 每次失败都打全栈（真车诊断包只抓一次 logcat，摘要不够用），
                // 但重试过程本身降级成一行，避免刷屏。
                if (retryIndex == 0) {
                    Logx.w(LogTags.HOME, "Home 键接管失败（不影响 HOME intent 兜底）", it)
                } else {
                    Logx.i(LogTags.HOME, "Home 键接管重试失败", "attempt" to retryIndex, "reason" to failure)
                }
            }
        HomeKeyRuntime.interceptorActive = active
        HomeKeyRuntime.interceptorStatus = statusText
        Logx.i(
            LogTags.HOME,
            "Home 键接管状态",
            "active" to active,
            "accepted" to accepted.joinToString(","),
            "status" to statusText,
            "attempt" to retryIndex,
        )
        // 接管生效后压制原车最近任务（真车上框架的双击逻辑不受 consume 影响，
        // 会把车机自己的最近任务压上来）；接管没拿到就恢复。
        runCatching { OemRecentsSuppressor.sync(context, prefs) }
            .onFailure { Logx.w(LogTags.HOME, "原车最近任务同步失败", it) }
        if (!active && started) {
            val delay = RETRY_DELAYS_MS.getOrNull(retryIndex)
            if (delay != null) {
                retryIndex++
                main.removeCallbacks(retry)
                main.postDelayed(retry, delay)
            }
        }
    }

    /** 异常 → 设置页能看懂的一句话。 */
    private fun describe(error: Throwable): String {
        var t: Throwable? = error
        while (t is java.lang.reflect.InvocationTargetException && t.cause != null) t = t.cause
        val message = t?.message ?: error.javaClass.simpleName
        return when {
            message.contains("IInputManager") -> "厂商输入服务未就绪（IInputManager 未注册）"
            message.isBlank() -> (t?.javaClass ?: error.javaClass).simpleName
            else -> message
        }
    }

    /** 停止接管并把按键还给系统。 */
    fun stop() {
        if (!started) return
        started = false
        main.removeCallbacks(retry)
        retryIndex = 0
        runCatching {
            val cb = callback
            val inp = input
            if (cb != null && inp != null) {
                inp.javaClass.getMethod(M_ABANDON, cb.javaClass.interfaces.firstOrNull() ?: Any::class.java)
                    .invoke(inp, cb)
            }
        }.onFailure { Logx.w(LogTags.HOME, "放弃 Home 键接管失败（忽略）", it) }
        callback = null
        input = null
        accepted = IntArray(0)
        HomeKeyRuntime.interceptorActive = false
        HomeKeyRuntime.interceptorStatus = if (failure == null) "已停止" else "不可用：$failure"
        runCatching { OemRecentsSuppressor.release(context, prefs) }
            .onFailure { Logx.w(LogTags.HOME, "原车最近任务恢复失败", it) }
        Logx.i(LogTags.HOME, "Home 键接管已停止")
    }

    private fun register() {
        val inputCls = Class.forName(CLS_INPUT, false, context.classLoader)
        val callbackCls = Class.forName(CLS_CALLBACK, false, context.classLoader)

        val create = inputCls.getMethod(M_CREATE, Context::class.java)
        val inputObj = create.invoke(null, context) ?: error("Input.create 返回 null")

        val proxy = Proxy.newProxyInstance(callbackCls.classLoader, arrayOf(callbackCls)) { _, method, args ->
            when (method.name) {
                M_PRESSED -> {
                    val code = (args?.firstOrNull() as? Int) ?: KEYCODE_HOME
                    handlePress(code)
                }

                M_RELEASED -> {
                    // 抬起与按下同口径：接管时一起吃掉，避免框架在抬起时补动作。
                    HomeKeyRuntime.enabled(prefs)
                }

                "toString" -> "CardeskHomeKeyCallback"
                "hashCode" -> System.identityHashCode(this)
                "equals" -> false
                else -> null
            }
        }

        val request = inputCls.getMethod(M_REQUEST, IntArray::class.java, callbackCls)
        @Suppress("UNCHECKED_CAST")
        val result = request.invoke(inputObj, intArrayOf(KEYCODE_HOME), proxy) as? IntArray
        if (result == null) {
            error("requestKeysInterception 返回 null")
        }

        input = inputObj
        callback = proxy
        accepted = result
        if (!result.contains(KEYCODE_HOME)) {
            failure = "固件白名单未包含 Home 键"
        }
    }

    /**
     * 收到一次按下。
     *
     * @return true = 吃掉（框架不再处理）；false = 放行给系统。
     */
    private fun handlePress(code: Int): Boolean {
        // 回调线程不一定是主线程；决策/出口都切到主线程统一处理，避免和桌面的
        // 页面状态机抢线程。返回值要同步给出，所以这里先按开关决定「吃不吃」。
        val consume = HomeKeyRuntime.enabled(prefs)
        main.post {
            HomeKeyRuntime.onInterceptPress(context, prefs)
        }
        Logx.i(LogTags.HOME, "Home 键按下（Input 通道）", "code" to code, "consume" to consume)
        return consume
    }
}
