package com.cardesk.launcher.vehicle

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.cardesk.launcher.core.log.LogTags
import com.cardesk.launcher.core.log.Logx
import java.lang.reflect.Proxy
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 进程内共享的 AdaptAPI `Input` 按键拦截总控。
 *
 * ## 为什么必须共享单实例
 *
 * 反编译领克 820A 的 `android.cevt.dcy11.inputmanager.InputManager` 与 `InputImpl` 确认：
 * - `Input.create(context)` 每次都会 `new InputManager().setCallback(this.mCallback)`；
 * - 系统服务 `dcy11_input_service` 对同一客户端只保存**一个** `IInputCallback`（覆盖式 `setCallback`）。
 *
 * 此前 `VoiceKeyInterceptor`（2861ms）和 `HomeKeyInterceptor`（2917ms）各自调了一次 `Input.create()`，
 * 后启动的 `HomeKeyInterceptor` 直接覆盖掉了 `VoiceKeyInterceptor` 的回调，导致 Home 键正常但语音键
 * 永远收不到 Input 实时事件、双击被 `XCInputService` 固件防连点吞掉。
 *
 * 统一由本对象持有**唯一**的 `Input` 实例并带启动重试，所有按键监听挂在同一个实例上互不覆盖。
 */
object AdaptapiInputHub {

    interface KeyListener {
        fun onKeyPressed(keyCode: Int): Boolean
        fun onKeyReleased(keyCode: Int): Boolean
    }

    private const val CLS_INPUT = "com.ecarx.xui.adaptapi.input.Input"
    private const val CLS_CALLBACK = "com.ecarx.xui.adaptapi.input.IKeyCallback"
    private const val M_CREATE = "create"
    private const val M_REQUEST = "requestKeysInterception"
    private const val M_PRESSED = "onKeyPressed"
    private const val M_RELEASED = "onKeyReleased"

    private val RETRY_DELAYS_MS = longArrayOf(1_000L, 2_000L, 3_000L, 5_000L, 8_000L, 12_000L, 20_000L)

    private val main = Handler(Looper.getMainLooper())
    private val listenersByKey = ConcurrentHashMap<Int, CopyOnWriteArrayList<KeyListener>>()
    private val statusListeners = CopyOnWriteArrayList<() -> Unit>()

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var input: Any? = null

    @Volatile
    private var callback: Any? = null

    @Volatile
    private var acceptedKeys: Set<Int> = emptySet()

    @Volatile
    private var lastFailure: String? = null

    private var retryIndex = 0
    private val retryRunnable = Runnable {
        if (listenersByKey.isEmpty()) return@Runnable
        syncLocked()
    }

    fun isKeyAccepted(keyCode: Int): Boolean = input != null && acceptedKeys.contains(keyCode)

    fun acceptedKeys(): IntArray = acceptedKeys.toIntArray()

    fun lastFailure(): String? = lastFailure

    fun addStatusListener(listener: () -> Unit) {
        statusListeners.addIfAbsent(listener)
    }

    fun removeStatusListener(listener: () -> Unit) {
        statusListeners.remove(listener)
    }

    @Synchronized
    fun register(context: Context, keys: IntArray, listener: KeyListener): IntArray {
        appContext = context.applicationContext ?: context
        for (k in keys) {
            val list = listenersByKey.getOrPut(k) { CopyOnWriteArrayList() }
            list.addIfAbsent(listener)
        }
        retryIndex = 0
        main.removeCallbacks(retryRunnable)
        syncLocked()
        return keys.filter { acceptedKeys.contains(it) }.toIntArray()
    }

    @Synchronized
    fun unregister(listener: KeyListener) {
        val it = listenersByKey.entries.iterator()
        while (it.hasNext()) {
            val entry = it.next()
            entry.value.remove(listener)
            if (entry.value.isEmpty()) it.remove()
        }
        if (listenersByKey.isEmpty()) {
            main.removeCallbacks(retryRunnable)
            retryIndex = 0
        }
    }

    @Synchronized
    private fun syncLocked() {
        val app = appContext ?: return
        val wanted = listenersByKey.keys.toIntArray()
        if (wanted.isEmpty()) return
        lastFailure = null
        val ok = runCatching {
            val inputCls = Class.forName(CLS_INPUT, false, app.classLoader)
            val callbackCls = Class.forName(CLS_CALLBACK, false, app.classLoader)
            val inp = input ?: run {
                val create = inputCls.getMethod(M_CREATE, Context::class.java)
                val created = create.invoke(null, app) ?: error("Input.create 返回 null")
                input = created
                created
            }
            val cb = callback ?: run {
                val proxy = Proxy.newProxyInstance(callbackCls.classLoader, arrayOf(callbackCls)) { _, method, args ->
                    when (method.name) {
                        M_PRESSED -> {
                            val code = (args?.firstOrNull() as? Int) ?: return@newProxyInstance false
                            dispatchKey(code, pressed = true)
                        }
                        M_RELEASED -> {
                            val code = (args?.firstOrNull() as? Int) ?: return@newProxyInstance false
                            dispatchKey(code, pressed = false)
                        }
                        "toString" -> "CardeskSharedInputCallback"
                        "hashCode" -> System.identityHashCode(this)
                        "equals" -> false
                        else -> null
                    }
                }
                callback = proxy
                proxy
            }
            val toRequest = wanted.filter { !acceptedKeys.contains(it) }.toIntArray()
            if (toRequest.isNotEmpty()) {
                val request = inputCls.getMethod(M_REQUEST, IntArray::class.java, callbackCls)
                val res = request.invoke(inp, toRequest, cb) as? IntArray
                    ?: error("requestKeysInterception 返回 null")
                acceptedKeys = acceptedKeys + res.toSet()
                Logx.i(
                    LogTags.KEYS,
                    "共享 Input 通道注册成功",
                    "requested" to toRequest.joinToString(","),
                    "accepted" to res.joinToString(","),
                    "allAccepted" to acceptedKeys.joinToString(","),
                )
            }
            true
        }.getOrElse { err ->
            lastFailure = describe(err)
            if (retryIndex == 0) {
                Logx.w(LogTags.KEYS, "共享 Input 通道注册失败（稍后重试）", err, "reason" to lastFailure)
            } else {
                Logx.i(LogTags.KEYS, "共享 Input 通道重试失败", "attempt" to retryIndex, "reason" to lastFailure)
            }
            false
        }
        statusListeners.forEach { runCatching { it() } }
        if (!ok && listenersByKey.isNotEmpty()) {
            val delay = RETRY_DELAYS_MS.getOrNull(retryIndex)
            if (delay != null) {
                retryIndex++
                main.removeCallbacks(retryRunnable)
                main.postDelayed(retryRunnable, delay)
            }
        }
    }

    private fun dispatchKey(code: Int, pressed: Boolean): Boolean {
        val list = listenersByKey[code] ?: return false
        var consumed = false
        for (l in list) {
            val handled = runCatching {
                if (pressed) l.onKeyPressed(code) else l.onKeyReleased(code)
            }.getOrDefault(false)
            if (handled) consumed = true
        }
        return consumed
    }

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
}
