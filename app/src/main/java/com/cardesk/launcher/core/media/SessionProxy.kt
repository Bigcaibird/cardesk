package com.cardesk.launcher.core.media

import android.content.Context
import android.media.Rating
import android.media.session.MediaController
import android.os.Bundle
import com.cardesk.launcher.core.log.LogTags
import com.cardesk.launcher.core.log.Logx
import java.lang.reflect.Method

/**
 * **标准 MediaSession 的隐藏 API 代理**（第一九六轮）。
 *
 * ## 为什么要有这一层
 *
 * 「收藏」和「播放模式」这两件事，**Android 官方定义的标准入口**是：
 *
 * | 动作 | 标准入口 | 谁来接 |
 * |---|---|---|
 * | 收藏 | `setRating(心形评分)` → `MediaSession.Callback.onSetRating` | 播放器的会话回调 |
 * | 播放模式 | `sendCustomAction(动作名, 参数)` → `onCustomAction` | 播放器的会话回调 |
 *
 * 任何播放器只要实现了这两个回调，**第三方就能驱动它** ——
 * 这就是「未适配 App 的通用兜底」的技术基础。
 *
 * ## 为什么必须反射
 *
 * `MediaController.setRating` / `MediaController.sendCustomAction` 在
 * Android 9 上**根本不存在**（公开的只有 `sendCommand`，而它和
 * `onCustomAction` 是两个独立回调，`sendCommand` 转不过去）。
 *
 * 设备实测（API 28）能用的唯一入口是**会话控制代理**：
 *
 * ```kotlin
 * val proxy = MediaSession.Token.mBinder   // 运行时类型 = ISessionController$Stub$Proxy
 * ```
 *
 * ## 签名差异（这是最容易踩坑的地方）
 *
 * `ISessionController` 的方法签名**随 Android 版本变化**，同一个方法在这台设备上长这样：
 *
 * ```java
 * void sendCustomAction(String callingPackage, ISessionControllerCallback callback,
 *                        String action, Bundle extras);
 * void rate(String callingPackage, ISessionControllerCallback callback, Rating rating);
 * ```
 *
 * 前面多了一个「**调用方包名**」——framework 拿它做**权限检查**，传错会被服务端拒。
 * 而 AOSP 9 的标准签名是 `sendCustomAction(String action, Bundle extras)`（没有前两个）。
 *
 * → 所以调用参数**按参数类型定位**业务参数的位置，
 * 其余：第一个 `String` 填真实的调用方包名，别的填 null。
 *
 * ## 评分入口的名字随版本变
 *
 * 有的版本叫 `rate(...)`，有的叫 `setRating(...)` → 两个都找（见 [findRatingMethod]）。
 */
internal object SessionProxy {

    /** framework 内部会话控制接口的全名。 */
    private const val ISESSION_CONTROLLER = "android.media.session.ISessionController"

    /**
     * 拿会话控制代理。
     *
     * 设备实测（API 28）里 `MediaController` 上**没有** `mImpl` 字段，
     * `MediaSession.Token` 上**没有** `getMediaController()` ——
     * 真正握着代理的是 **`MediaSession.Token.mBinder`**
     * （运行时类型就是 `ISessionController$Stub$Proxy`，已经是现成的代理对象）。
     *
     * @return 代理对象；拿不到返回 null
     */
    fun of(controller: MediaController): Any? {
        val tokenBinder = runCatching {
            val token = controller.sessionToken
            token.javaClass.getDeclaredField("mBinder")
                .apply { isAccessible = true }.get(token)
        }.getOrNull()
        if (tokenBinder != null) return tokenBinder
        // 另一个入口（部分 ROM 上 `mSessionBinder` 是活的）
        return runCatching {
            MediaController::class.java.getDeclaredField("mSessionBinder")
                .apply { isAccessible = true }.get(controller)
        }.getOrNull()
    }

    /**
     * 在代理上找同名方法，**优先参数少的那个**。
     *
     * 接口上声明的签名（AOSP 9 那种 2 参）比代理类上的（AIDL 加了隐藏参数）更稳。
     */
    fun find(proxy: Any, name: String): Method? {
        val fromInterface = runCatching {
            Class.forName(ISESSION_CONTROLLER).methods.filter { it.name == name }
        }.getOrDefault(emptyList())
        val fromProxy = runCatching {
            proxy.javaClass.methods.filter { it.name == name }
        }.getOrDefault(emptyList())
        return (fromInterface + fromProxy)
            .distinctBy { it.parameterTypes.size }
            .minByOrNull { it.parameterTypes.size }
    }

    /**
     * 拼「动作 + 参数」的调用参数。
     *
     * - `action` 放在**最后一个 Bundle 之前最近的 String** 上；
     * - `extras` 放在最后一个 `Bundle` 上；
     * - 其余参数：第一个 `String` 填调用方包名，别的填 null。
     */
    fun actionArgs(
        method: Method,
        callingPackage: String,
        action: String,
        extras: Bundle?,
    ): Array<Any?> {
        val types = method.parameterTypes
        val args = arrayOfNulls<Any?>(types.size)
        val extrasIdx = types.indexOfLast { it == Bundle::class.java }
            .let { if (it < 0) types.size - 1 else it }
        var actionIdx = -1
        for (i in extrasIdx - 1 downTo 0) {
            if (types[i] == String::class.java) {
                actionIdx = i
                break
            }
        }
        if (actionIdx < 0) actionIdx = if (extrasIdx > 0) extrasIdx - 1 else 0
        args[actionIdx] = action
        args[extrasIdx] = extras
        for (i in types.indices) {
            if (i == actionIdx || i == extrasIdx) continue
            args[i] = if (types[i] == String::class.java) callingPackage else null
        }
        return args
    }

    /**
     * 在代理上找**评分**方法（`rate` / `setRating`）。
     *
     * ## 为什么有两个名字
     *
     * AOSP 各版本的会话控制接口上，评分入口叫法不一样：
     *
     * - 有的版本叫 `setRating(String callingPackage, ISessionControllerCallback, Rating)`；
     * - 有的版本叫 `rate(String callingPackage, ISessionControllerCallback, Rating)`。
     *
     * 设备实测（API 28）这台是 **`rate`** ——
     * 但不同 ROM / 不同版本可能不一样 → 两个都找，
     * 取「**参数里能塞进一个评分**」的那个里参数最少的。
     *
     * @param proxy 会话控制代理
     * @return 找到的方法；找不到返回 null
     */
    fun findRatingMethod(proxy: Any): Method? {
        val candidates = RATING_METHOD_NAMES.flatMap { name ->
            val fromInterface = runCatching {
                Class.forName(ISESSION_CONTROLLER).methods.filter { it.name == name }
            }.getOrDefault(emptyList())
            val fromProxy = runCatching {
                proxy.javaClass.methods.filter { it.name == name }
            }.getOrDefault(emptyList())
            fromInterface + fromProxy
        }
        return candidates
            .filter { ratingIndex(it) >= 0 }
            .minByOrNull { it.parameterTypes.size }
    }

    /**
     * 评分参数在方法签名里的**位置**（-1 = 找不到）。
     *
     * 评分参数的类型在不同版本上可能是 `Rating` 也可能是 `Object`，
     * 而 `callingPackage` 是 `String`、回调是**接口** →
     * 按「不是 String、不是接口」的位置定位。
     */
    private fun ratingIndex(method: Method): Int {
        val types = method.parameterTypes
        for (i in types.indices) {
            val t = types[i]
            if (t != String::class.java && !t.isInterface && t != Object::class.java) return i
        }
        return -1
    }

    /**
     * 拼「评分」的调用参数（`rate(callingPackage, callback, Rating)`）。
     *
     * 评分参数的类型在不同版本上可能是 `Rating` 也可能是 `Object`
     * → 按「不是 String、不是回调」的位置定位。
     */
    fun ratingArgs(method: Method, callingPackage: String, rating: Rating): Array<Any?> {
        val types = method.parameterTypes
        val args = arrayOfNulls<Any?>(types.size)
        var ratingIdx = ratingIndex(method)
        if (ratingIdx < 0) ratingIdx = types.size - 1
        args[ratingIdx] = rating
        for (i in types.indices) {
            if (i == ratingIdx) continue
            args[i] = if (types[i] == String::class.java) callingPackage else null
        }
        return args
    }

    /**
     * 发一个**自定义动作**（标准 `onCustomAction` 入口）。
     *
     * @param context 上下文（取真实的调用方包名）
     * @param controller 当前会话的控制器
     * @param action 动作名
     * @param extras 参数（可空）
     * @return 是否真的发出去了
     */
    fun sendCustomAction(
        context: Context,
        controller: MediaController,
        action: String,
        extras: Bundle?,
    ): Boolean {
        val pkg = context.packageName
        // 路 1：公开的 `MediaController.sendCustomAction`（API 29+ 还在）
        runCatching {
            MediaController::class.java.getMethod(
                "sendCustomAction",
                String::class.java,
                Bundle::class.java,
            )
        }.getOrNull()?.let { m ->
            val err = runCatching { m.invoke(controller, action, extras) }.exceptionOrNull()
            if (err == null) return true
            Logx.w(LogTags.MEDIA, "自定义动作下发失败（直连）", err, "action" to action)
        }
        // 路 2：会话控制代理
        val proxy = of(controller)
        if (proxy == null) {
            Logx.i(LogTags.MEDIA, "自定义动作下发失败：拿不到会话控制代理", "action" to action)
            return false
        }
        val m = find(proxy, "sendCustomAction")
        if (m == null) {
            Logx.i(LogTags.MEDIA, "自定义动作下发失败：代理上没有 sendCustomAction", "action" to action)
            return false
        }
        val err = runCatching {
            m.invoke(proxy, *actionArgs(m, pkg, action, extras))
        }.exceptionOrNull()
        if (err != null) {
            Logx.i(
                LogTags.MEDIA, "自定义动作下发失败：调用出错",
                "action" to action, "参数个数" to m.parameterTypes.size,
                "异常" to err.javaClass.name, "消息" to (err.message ?: ""),
            )
        } else {
            Logx.i(
                LogTags.MEDIA, "自定义动作下发成功",
                "action" to action, "参数个数" to m.parameterTypes.size,
            )
        }
        return err == null
    }

    /**
     * 发一个**评分**（标准 `onSetRating` 入口）。
     *
     * 这是**收藏**的标准通道：播放器实现 `onSetRating` 就能收。
     *
     * @param context 上下文（取真实的调用方包名）
     * @param controller 当前会话的控制器
     * @param rating 评分（收藏用**心形评分**）
     * @return 是否真的发出去了
     */
    fun sendRating(
        context: Context,
        controller: MediaController,
        rating: Rating,
    ): Boolean {
        val pkg = context.packageName
        // 路 1：公开的 `MediaController.setRating`（API 29+ 还在）
        runCatching {
            MediaController::class.java.getMethod(
                "setRating",
                Rating::class.java,
                Bundle::class.java,
            )
        }.getOrNull()?.let { m ->
            val err = runCatching { m.invoke(controller, rating, null) }.exceptionOrNull()
            if (err == null) return true
            Logx.w(LogTags.MEDIA, "评分下发失败（直连）", err)
        }
        // 路 2：会话控制代理上的评分入口
        val proxy = of(controller)
        if (proxy == null) {
            Logx.i(LogTags.MEDIA, "评分下发失败：拿不到会话控制代理")
            return false
        }
        val m = findRatingMethod(proxy)
        if (m == null) {
            Logx.i(
                LogTags.MEDIA, "评分下发失败：代理上没有评分入口",
                "候选" to RATING_METHOD_NAMES.joinToString("/"),
            )
            return false
        }
        val err = runCatching {
            m.invoke(proxy, *ratingArgs(m, pkg, rating))
        }.exceptionOrNull()
        if (err != null) {
            Logx.i(
                LogTags.MEDIA, "评分下发失败：调用出错",
                "方法" to m.name,
                "参数个数" to m.parameterTypes.size,
                "异常" to err.javaClass.name, "消息" to (err.message ?: ""),
            )
        } else {
            Logx.i(
                LogTags.MEDIA, "评分下发成功",
                "方法" to m.name, "参数个数" to m.parameterTypes.size,
                "心形" to rating.hasHeart(),
            )
        }
        return err == null
    }

    /** 评分入口的候选方法名（不同版本叫法不一样）。 */
    private val RATING_METHOD_NAMES = listOf("rate", "setRating")
}
