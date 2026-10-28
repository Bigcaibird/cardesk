package com.cardesk.launcher.ui.embed

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.View
import com.cardesk.launcher.core.log.LogTags
import com.cardesk.launcher.core.log.Logx

/**
 * 用 `ActivityView` + `VirtualDisplay` 把别的应用真正嵌进自己的窗口。
 *
 * 用的是系统类
 * `android.app.ActivityView`：
 *
 * - 构造 `new ActivityView(context, null, 0)`；
 * - `setCallback(...)` → 回调里 `startActivity(intent)`；
 * - 尺寸变化时同步内部 `mVirtualDisplay`，否则画面会拉伸/黑边。
 *
 * 这一整套都是 @SystemApi / 隐藏 API：**平台签名或 sharedUserId=android.uid.system 时可用**，
 * 普通应用会抛异常 → 这里返回 null，由调用方降级成卡片。
 *
 * ## 两个必须踩准的点（都踩过）
 *
 * ### 1. 承载面没就绪就启动 → 应用全屏抢屏
 *
 * `ActivityView` 内部是 `SurfaceView` + `VirtualDisplay`，**VirtualDisplay 是在 surface
 * 创建（surfaceCreated）时才建出来的**。在它之前调启动 API：
 *
 * - API 29（Android 10）：`startActivity(Intent)` **不带 displayId**，
 *   应用被拉到**默认显示器** → 全屏盖住桌面，画中画位置是空的；
 * - API 30+：同一个方法会抛 `IllegalStateException("Trying to start activity before
 *   ActivityView is ready.")`。
 *
 * 在 `onActivityViewReady` 回调里启动
 * 正是为了避开这个时序。我们在 R+ 上 `StateCallback` 是**抽象类**代理不了，
 * 所以这里改成**轮询探测承载面是否就绪**，就绪后再启动。
 *
 * ### 2. 启动 API 的顺序不能反
 *
 * 各版本可用的重载不一样，**带 displayId 的才会落在 VirtualDisplay 上**：
 *
 * | API | 可用重载 | 落点 |
 * | --- | --- | --- |
 * | 30+ | `startActivity(PendingIntent[, ActivityOptions])`、`startActivity(Intent)` | VirtualDisplay ✓ |
 * | 29 | `startActivityForResult(Intent, int)` | VirtualDisplay ✓ |
 * | 29 | `startActivity(Intent)` | **默认显示器** ✗（全屏抢屏） |
 * | **28（领克车机）** | `startActivity(PendingIntent)` | VirtualDisplay ✓ |
 *
 * API 28 的 `ActivityView`（AOSP `android-9.0.0_r1`）里 `startActivity(PendingIntent)`
 * 会走 → 带 → 落在 VirtualDisplay 上，
 * 所以「PendingIntent 单参重载」这一档在 API 28 就能用。
 *
 * 所以顺序固定为：PendingIntent 重载 → `startActivityForResult` → `startActivity(Intent)`，
 * 与顺序一致（在 PendingIntent
 * 失败时兜底到 `startActivity(Intent)`，那是它的最后手段。
 */
object ActivityEmbed {

    /**
     * 画中画承载启动的 Intent flags。
     *
     * = `FLAG_ACTIVITY_NEW_TASK (0x10000000)` | `FLAG_ACTIVITY_RESET_TASK_IF_NEEDED (0x00200000)`
     */
    const val PIP_LAUNCH_FLAGS =
        Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED

    /**
     * 画中画「待启动」意图的 flags。
     *
     * = 201326592 = 0x0C000000。
     *
     * 同一个数值在两套旗标里各有所指，两边都用得上：
     *
     * - 当作 **Intent 的 activity 旗标**：`FLAG_ACTIVITY_CLEAR_TOP (0x04000000)` |
     *   `FLAG_ACTIVITY_MULTIPLE_TASK (0x08000000)`；
     * - 当作 **PendingIntent 的旗标**：`FLAG_IMMUTABLE (0x04000000)` |
     *   `FLAG_UPDATE_CURRENT (0x08000000)` —— 这两个位刚好一一对应，
     *   所以把这个常量直接传给 `PendingIntent.getActivity` 也是合法的
     *   （targetSdk 31+ 要求必须带 IMMUTABLE/MUTABLE，正好满足）。
     */
    const val PIP_PENDING_INTENT_FLAGS =
        Intent.FLAG_ACTIVITY_MULTIPLE_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP

    /** 承载面就绪的探测间隔（毫秒）。 */
    private const val READY_POLL_MS = 120L

    /** 承载面就绪的「快速探测」上限（毫秒）。超过后转成慢速探测。 */
    private const val READY_FAST_MS = 8000L

    /** 慢速探测的间隔（毫秒）。 */
    private const val READY_SLOW_MS = 1000L

    /** 慢速探测的总上限（毫秒）。 */
    private const val READY_SLOW_TOTAL_MS = 300_000L

    /** 承载面 → 就绪等待器（拆承载面时要一并停掉）。 */
    private val starters = java.util.WeakHashMap<View, ReadyStarter>()

    /** 试着把 [pkg] 嵌进来；失败返回 null。 */
    fun embed(context: Context, pkg: String): View? {
        // 注意：**这里不能按 SDK 版本拦截**。
        //
        // `android.app.ActivityView` 在 Android 9（API 28）上就已经存在（@hide 的系统类），
        // AOSP `android-9.0.0_r1` 的 里：
        // - 构造器 `ActivityView(Context, AttributeSet, int)` ✓
        // - `StateCallback.onActivityViewReady / onActivityViewDestroyed` ✓
        // - `startActivity(Intent)` / `startActivity(PendingIntent)` ✓（**带 launchDisplayId**）
        //
        // - 私有字段 `mSurfaceView` / `mVirtualDisplay` ✓
        //
        // API 28 缺的只有 `startActivityViewPendingIntent(...)`（那是 API 29+ 的名字），
        // 而我们下面本来就是「先试带 ActivityOptions 的重载，再试单参重载」，
        // 单参 `startActivity(PendingIntent)` 在 API 28 上正好可用。
        //
        // 真机（领克 dcy11，Android 9 / SDK 28）之前就是被这里的版本门直接挡掉，
        // 日志里是「嵌入失败：系统版本过低 sdk=28」→ 画中画永远降级成卡片。
        Logx.i(LogTags.NAV_UI, "尝试 ActivityView 嵌入", "pkg" to pkg, "sdk" to Build.VERSION.SDK_INT)
        return runCatching {
            val cls = Class.forName("android.app.ActivityView")
            val ctor = runCatching {
                cls.getConstructor(Context::class.java, android.util.AttributeSet::class.java, Int::class.javaPrimitiveType)
            }.getOrNull() ?: runCatching {
                cls.getConstructor(Context::class.java, android.util.AttributeSet::class.java)
            }.getOrNull() ?: cls.getConstructor(Context::class.java)
            val args: Array<Any?> = when (ctor.parameterCount) {
                3 -> arrayOf(context, null, 0)
                2 -> arrayOf(context, null)
                else -> arrayOf(context)
            }
            val view = ctor.newInstance(*args) as View
            val launchIntent = context.packageManager.getLaunchIntentForPackage(pkg)
            if (launchIntent == null) {
                // 排障场景：「画中画不显示」——包名查不到启动 Intent 就是根因。
                Logx.w(LogTags.NAV_UI, "嵌入失败：包名没有启动 Intent", "pkg" to pkg)
                return null
            }
            val intent = launchIntent.addFlags(PIP_LAUNCH_FLAGS)

            // 承载应用**只在承载面就绪后启动**。
            //
            // 这里的坑有两个：
            // 1) 回调是**异步**触发的，不能拿「回调有没有来过」当「要不要兜底」的判据 ——
            //    那样回调和兜底都会启动一次，应用被拉起两个实例；
            // 2) 承载面没就绪就启动 → 应用被拉到默认显示器全屏抢屏（见类注释）。
            // 所以统一走 [ReadyStarter]：轮询到承载面就绪再启动，且**只启动一次**。
            val starter = ReadyStarter(view, intent, pkg)
            attachReadyCallback(view, starter)
            attachSurfaceCallback(view, starter)
            synchronized(starters) { starters[view] = starter }
            starter.begin()
            view
        }.onSuccess {
            // 排障场景：「画中画是黑的」——嵌入成功但没画面，多半卡在 startActivity。
            Logx.i(LogTags.NAV_UI, "ActivityView 嵌入成功", "pkg" to pkg)
        }.onFailure {
            // 排障场景：「画中画降级成卡片」——反射失败原因必须留痕。
            Logx.w(
                LogTags.NAV_UI,
                "ActivityView 嵌入失败（降级为卡片）",
                it,
                "pkg" to pkg,
                "reason" to it.message,
            )
        }.getOrNull()
    }

    /**
     * 挂上承载面的 surface 回调：**surface 重建后重新拉起承载应用**。
     *
     * ## 为什么需要
     *
     * AOSP 9 的 `ActivityView`里：
     *
     * -：
     *   `if (mVirtualDisplay == null) initVirtualDisplay(); else mVirtualDisplay.setSurface(...)`
     *   —— surface 重建时**不会**重建 VirtualDisplay，也**不会**再回调
     *   `onActivityViewReady`；
     * - 只 `mVirtualDisplay.setSurface(null)`，**不 release**。
     *
     * 于是：桌面切到别的页（页面置 GONE）或切到别的应用（Activity 退到后台）时，
     * SurfaceView 的 surface 被销毁 → 承载应用的画面没了；
     * 回到这一页时 surface 重建 → VirtualDisplay 复用 → 但承载应用**没有再被启动**，
     * 画面就停在黑屏，要点一下才有反应。
     *
     * `onActivityViewReady` 只在 VirtualDisplay 首次创建时触发
     * 覆盖不到「surface 重建」这一种；我们在 surface 重建时**重新下发一次启动**，
     * 用的还是那套 PendingIntent + NEW_TASK 旗标（既有 task 会被拉到前台
     * 不会重开一个实例。
     */
    private fun attachSurfaceCallback(view: View, starter: ReadyStarter) {
        val surfaceView = findField(view, "mSurfaceView")?.let { readField(it, view) } as? android.view.SurfaceView
        if (surfaceView == null) {
            Logx.d(LogTags.NAV_UI, "取不到 ActivityView 的 SurfaceView，surface 重建无法自愈")
            return
        }
        val holder = runCatching { surfaceView.holder }.getOrNull()
        if (holder == null) {
            Logx.d(LogTags.NAV_UI, "SurfaceView 没有 SurfaceHolder，surface 重建无法自愈")
            return
        }
        val cb = object : android.view.SurfaceHolder.Callback {
            override fun surfaceCreated(holder: android.view.SurfaceHolder) {
                starter.onSurfaceCreated()
            }

            override fun surfaceChanged(
                holder: android.view.SurfaceHolder,
                format: Int,
                width: Int,
                height: Int,
            ) {
            }

            override fun surfaceDestroyed(holder: android.view.SurfaceHolder) {
                starter.onSurfaceDestroyed()
            }
        }
        runCatching { holder.addCallback(cb) }
            .onSuccess {
                starter.surfaceCallback = cb
                // 挂上回调之前 surface 就已经建好的情况：
                // 下一次 surfaceCreated 就是「重建」，要按重建处理。
                val already = runCatching { holder.surface?.isValid == true }.getOrDefault(false)
                if (already) starter.markSurfaceAlreadyCreated()
            }
            .onFailure {
                Logx.w(LogTags.NAV_UI, "挂 SurfaceView 回调失败", it)
            }
    }

    /**
     * 挂上承载面就绪回调（能挂就挂，挂不上就靠轮询）。
     *
     * - API 29：`ActivityView.Callback` 是**接口** → 动态代理可用；
     * - API 30+：`ActivityView.StateCallback` 是**抽象类** → 代理不了，只能靠轮询。
     */
    private fun attachReadyCallback(view: View, starter: ReadyStarter) {
        val cls = view.javaClass
        val setCb = cls.methods.firstOrNull { it.name == "setCallback" && it.parameterCount == 1 }
            ?: return
        val cbCls = setCb.parameterTypes[0]
        if (!cbCls.isInterface) {
            Logx.d(
                LogTags.NAV_UI,
                "ActivityView 回调是抽象类，改用轮询探测承载面",
                "cb" to cbCls.name,
            )
            return
        }
        runCatching {
            val proxy = java.lang.reflect.Proxy.newProxyInstance(cbCls.classLoader, arrayOf(cbCls)) { _, method, _ ->
                if (method.name == "onActivityViewReady") {
                    starter.onReady()
                }
                null
            }
            setCb.invoke(view, proxy)
            starter.callbackAttached = true
        }.onFailure {
            Logx.w(LogTags.NAV_UI, "挂 ActivityView 回调失败", it)
        }
    }

    // ------------------------------------------------------------------ 就绪探测

    /**
     * 承载面是否已经就绪（VirtualDisplay 建出来了）。
     *
     * 依次尝试：
     * 1. 公开方法 （API 30+），返回 -1 表示还没好；
     * 2. 私有字段 `mVirtualDisplay`（ActivityView 自己持有）；
     * 3. 私有字段 `mHost` 里挂着的 display 字段。
     *
     * 三种都探不到时返回 true（探不出来就别卡住流程，交给启动 API 抛异常来兜）。
     */
    private fun displayReady(view: View): Boolean {
        // 1) getVirtualDisplayId()（API 30+ 的公开方法，返回 -1 表示还没好）
        val id = runCatching {
            view.javaClass.getMethod("getVirtualDisplayId").invoke(view) as Int
        }.getOrNull()
        if (id != null) return id > 0

        // 2) mVirtualDisplay（ActivityView 自己持有）
        val own = findField(view, "mVirtualDisplay")
        if (own != null) return readField(own, view) != null

        // 3) mHost 里挂着的 display（API 29）
        val hostField = findField(view, "mHost") ?: return true
        val host = readField(hostField, view) ?: return true
        val m = findField(host, "mDisplay") ?: findField(host, "mVirtualDisplay")
        if (m != null) return readField(m, host) != null
        return true
    }

    /** 沿继承链找一个私有字段（拿不到返回 null）。 */
    private fun findField(target: Any, name: String): java.lang.reflect.Field? {
        var cls: Class<*>? = target.javaClass
        while (cls != null) {
            val field = runCatching { cls!!.getDeclaredField(name) }.getOrNull()
            if (field != null) {
                field.isAccessible = true
                return field
            }
            cls = cls.superclass
        }
        return null
    }

    /** 读字段值（读不到返回 null）。 */
    private fun readField(field: java.lang.reflect.Field, target: Any): Any? =
        runCatching { field.get(target) }.getOrNull()

    // ------------------------------------------------------------------ 启动

    /**
     * 承载面就绪后启动承载应用（**只启动一次**）。
     *
     * 启动 API 的顺序见类注释：带 displayId 的优先。
     */
    fun startActivity(view: View, intent: Intent) {
        val pkg = intent.`package`
        val ctx = view.context

        // —— 1) 隐藏 API：startActivity(PendingIntent, ActivityOptions) ——
        if (startViaPendingIntent(view, intent)) {
            Logx.i(LogTags.NAV_UI, "ActivityView 启动承载应用", "pkg" to pkg, "api" to "pendingIntent")
            return
        }
        // —— 2) startActivityForResult(Intent, int)（API 29 上带 displayId）——
        if (startViaIntentForResult(view, intent)) {
            Logx.i(LogTags.NAV_UI, "ActivityView 启动承载应用", "pkg" to pkg, "api" to "startActivityForResult")
            return
        }
        // —— 3) 兜底：startActivity(Intent) ——
        // API 28 / 30+ 都带 displayId（会落在 VirtualDisplay 上）；
        // 只有 API 29 的 `startActivity(Intent)` 不带 displayId → 应用全屏抢屏。
        runCatching {
            view.javaClass.getMethod("startActivity", Intent::class.java).invoke(view, intent)
        }.onSuccess {
            // 排障场景：「画中画变成全屏 / 桌面被应用盖住」——API 29 上这条路径就是全屏抢屏。
            if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q) {
                Logx.w(
                    LogTags.NAV_UI,
                    "ActivityView 走 startActivity(Intent) 兜底：应用会全屏抢屏",
                    "pkg" to pkg,
                )
            } else {
                Logx.i(LogTags.NAV_UI, "ActivityView 启动承载应用", "pkg" to pkg, "api" to "startActivity")
            }
        }.onFailure {
            Logx.w(LogTags.NAV_UI, "ActivityView 启动承载应用失败", it, "pkg" to pkg)
        }
    }

    /**
     * 隐藏 API 路径：`ActivityView.startActivity(PendingIntent[, ActivityOptions])`。
     *
     *
     *
     * API 28 上没有 `startActivityViewPendingIntent(...)`（API 29+ 才有这个名字），
     * 但有 `startActivity(PendingIntent)`（AOSP android-9.0.0_r1 的 ActivityView 里有，
     * 内部 会带上 → 正好落在 VirtualDisplay 上。
     */
    private fun startViaPendingIntent(view: View, intent: Intent): Boolean {
        val ctx = view.context
        val pending = runCatching {
            PendingIntent.getActivity(ctx, intent.`package`?.hashCode() ?: 0, intent, PIP_PENDING_INTENT_FLAGS)
        }.getOrNull() ?: return false

        // 带 ActivityOptions 的重载优先（同款）
        val options = runCatching {
            val m = Class.forName("android.app.ActivityOptions")
                .getMethod("makeCustomAnimation", android.content.Context::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            m.invoke(null, ctx, 0, 0)
        }.getOrNull()
        if (options != null) {
            val ok = runCatching {
                view.javaClass
                    .getMethod("startActivity", PendingIntent::class.java, Class.forName("android.app.ActivityOptions"))
                    .invoke(view, pending, options)
            }.isSuccess
            if (ok) return true
        }
        // 单参重载
        return runCatching {
            view.javaClass.getMethod("startActivity", PendingIntent::class.java).invoke(view, pending)
        }.isSuccess
    }

    /** `startActivityForResult(Intent, int)`（API 29 上带 displayId）。 */
    private fun startViaIntentForResult(view: View, intent: Intent): Boolean =
        runCatching {
            view.javaClass.getMethod("startActivityForResult", Intent::class.java, Int::class.javaPrimitiveType)
                .invoke(view, intent, 0)
        }.isSuccess

    // ------------------------------------------------------------------ 承载面就绪后启动

    /**
     * 「等承载面就绪再启动」的执行器。
     *
     * 两种就绪信号：
     * - 回调（API 29 的 `Callback` 接口能被代理）；
     * - 轮询探测（API 30+ 的 `StateCallback` 是抽象类，只能轮询）。
     *
     * 两条路都走同一个 `started` 标志 —— **只启动一次**，
     * 避免「回调 + 轮询」把应用拉起两个实例。
     */
    private class ReadyStarter(
        private val view: View,
        private val intent: Intent,
        private val pkg: String,
    ) {
        /** 回调是否挂上了（挂上了就优先等回调，轮询只做兜底）。 */
        var callbackAttached: Boolean = false

        /** surface 回调（用于 surface 重建时自愈）。 */
        var surfaceCallback: android.view.SurfaceHolder.Callback? = null

        private val handler = Handler(Looper.getMainLooper())
        private var started = false
        private var cancelled = false

        /**
         * 「等太久了，停止轮询」（第六十七轮）。
         *
         * ## 为什么不能直接用 [cancelled]
         *
         * 原来超时的处理是 `cancelled = true` —— 而 `cancelled` 的语义是
         * 「**承载面被拆了**」。于是：
         *
         * 1. 用户选好画中画应用后一直**没去导航页**（那时 surface 不会创建）；
         * 2. 超过 5 分钟（`READY_SLOW_TOTAL_MS`）→ `cancelled = true`，放弃启动；
         * 3. 用户进导航页 → surface 建好了 → 但 `cancelled` 已经为真，
         *    [onSurfaceCreated] 直接 return、轮询也不再跑 → **承载应用从来没被启动**；
         * 4. 画面就是一块黑。
         *
         * 而用户的绕法是「**全屏一次再还原**」—— 还原时我们会给承载应用
         * 重新下发一次启动（`PipSurface.relaunchAfterResize`），所以就又能看了。
         *
         * 现在超时时只停**轮询**（[stopped]），不废掉承载面：
         * surface 一建好就重新开始等。
         */
        private var stopped = false
        private var slow = false
        private var warned = false
        private var beginAt = 0L

        /** surface 创建过几次（第一次是正常启动，之后都是「重建」）。 */
        private var surfaceCreatedCount = 0

        fun begin() {
            beginAt = System.currentTimeMillis()
            handler.postDelayed(poll, READY_POLL_MS)
        }

        /** 承载面就绪回调。 */
        fun onReady() {
            handler.post { attempt("callback") }
        }

        /**
         * surface 重建（切页 / 切到别的应用再回来）。
         *
         * AOSP 的 `ActivityView` 在 surface 重建时**不会**重新创建 VirtualDisplay，
         * 也就不会再次触发 `onActivityViewReady` → 承载应用不会被拉起来 → 黑屏。
         * 这里在重建时把承载应用**重新下发一次启动**
         * （用的是那套 NEW_TASK 旗标，已有 task 会被拉到前台而不是重开）。
         *
         * ## 第六十七轮补的一条
         *
         * **还没启动过**的时候也要启动 ——
         * 以前这里只处理「已经启动过 → 重建」，
         * 于是「首次就绪但等待已超时」这一整条路是死的（画中画一块黑，
         * 只有全屏一次再还原才看得到）。
         */
        fun onSurfaceCreated() {
            surfaceCreatedCount++
            if (cancelled) return
            if (!started) {
                // 还没启动过 → surface 建好了 = VirtualDisplay 建出来了 → 立刻试一次
                if (stopped) {
                    stopped = false
                    slow = false
                    warned = false
                    beginAt = System.currentTimeMillis()
                    Logx.i(
                        LogTags.NAV_UI,
                        "承载面就绪，重新开始等待（之前超时放弃过）",
                        "pkg" to pkg,
                    )
                }
                Logx.d(LogTags.NAV_UI, "承载面首次就绪", "pkg" to pkg, "times" to surfaceCreatedCount)
                handler.post { attempt("surface") }
                return
            }
            Logx.i(
                LogTags.NAV_UI,
                "承载面重建，重新拉起承载应用",
                "pkg" to pkg,
                "times" to surfaceCreatedCount,
            )
            handler.post { startActivity(view, intent) }
        }

        /** surface 被销毁（切页 / 桌面退到后台）。 */
        fun onSurfaceDestroyed() {
            Logx.d(LogTags.NAV_UI, "承载面销毁", "pkg" to pkg, "times" to surfaceCreatedCount)
        }

        /** 挂回调之前 surface 就已经建好了 → 记成「已经创建过一次」。 */
        fun markSurfaceAlreadyCreated() {
            if (surfaceCreatedCount == 0) surfaceCreatedCount = 1
        }

        /** 承载面被拆掉时停止等待。 */
        fun cancel() {
            cancelled = true
            handler.removeCallbacks(poll)
        }

        private val poll = object : Runnable {
            override fun run() {
                attempt("poll")
            }
        }

        private fun attempt(reason: String) {
            if (started || cancelled || stopped) return
            if (view.isAttachedToWindow && displayReady(view)) {
                started = true
                handler.removeCallbacks(poll)
                Logx.i(
                    LogTags.NAV_UI,
                    "承载面就绪，启动承载应用",
                    "pkg" to pkg,
                    "by" to reason,
                    "w" to view.width,
                    "h" to view.height,
                )
                startActivity(view, intent)
                return
            }
            reschedule(reason)
        }

        private fun reschedule(reason: String) {
            if (started || cancelled || stopped) return
            val elapsed = System.currentTimeMillis() - beginAt
            if (elapsed > READY_SLOW_TOTAL_MS) {
                // 只停**轮询**，不废掉承载面 —— surface 建好时会自动重新开始等。
                // （以前这里置 `cancelled = true`，于是「进导航页 → surface 建好 → 启动」
                //  这一整条路是死的 → 画中画一块黑，只能全屏一次再还原才看得到。）
                Logx.w(
                    LogTags.NAV_UI,
                    "承载面等待超时，停止轮询（surface 建好后会重新尝试）",
                    "pkg" to pkg,
                    "by" to reason,
                    "waited" to elapsed,
                )
                stopped = true
                handler.removeCallbacks(poll)
                return
            }
            if (!slow && elapsed > READY_FAST_MS) {
                // 转慢速探测：导航页不可见时页面是 GONE，SurfaceView 的 surface 不会创建，
                // VirtualDisplay 也就没有 —— 这时不该判死刑。
                // 用户翻到导航页、surface 重建后承载应用会自动起来。
                slow = true
                if (!warned) {
                    warned = true
                    Logx.w(
                        LogTags.NAV_UI,
                        "承载面暂不就绪，转慢速等待（导航页未显示时不创建 surface）",
                        "pkg" to pkg,
                        "by" to reason,
                    )
                }
            }
            handler.removeCallbacks(poll)
            handler.postDelayed(poll, if (slow) READY_SLOW_MS else READY_POLL_MS)
        }
    }

    /**
     * 取承载面的 **Surface**（画面探针采样用）。
     *
     * AOSP 的 `ActivityView` 内部是 `mSurfaceView`（`SurfaceView`），
     * 画面就画在它的 Surface 上 —— `PixelCopy` 采的就是它。
     *
     * @return null = 取不到（不是 ActivityView / Surface 还没建好 / 已被拆）
     */
    fun surfaceOf(view: View): android.view.Surface? {
        val surfaceView = findField(view, "mSurfaceView")
            ?.let { readField(it, view) } as? android.view.SurfaceView ?: return null
        return runCatching { surfaceView.holder.surface }.getOrNull()
    }

    /**
     * 释放承载面。
     *
     * 拆解顺序
     * `setCallback(null)` → → 从容器里移除。
     *
     * 不释放的话 VirtualDisplay 和被承载应用的 task 会一直留着 ——
     * 副画/主画来回切几次就能在车机上看到一堆残留任务和黑屏。
     */
    fun release(view: View?) {
        val v = view ?: return
        // 先停掉「等承载面就绪」的等待器 —— 承载面都拆了，就不该再把应用拉起来。
        val starter = synchronized(starters) { starters.remove(v) }
        starter?.cancel()
        // 摘掉 surface 回调，避免承载面拆了之后还来回调自愈逻辑。
        val cb = starter?.surfaceCallback
        if (cb != null) {
            runCatching {
                val surfaceView = findField(v, "mSurfaceView")?.let { readField(it, v) } as? android.view.SurfaceView
                surfaceView?.holder?.removeCallback(cb)
            }.onFailure {
                Logx.d(LogTags.NAV_UI, "摘 SurfaceView 回调失败", "reason" to it.message)
            }
        }
        runCatching {
            v.javaClass.getMethod("setCallback", Class.forName("android.app.ActivityView\$Callback"))
                .invoke(v, null)
        }
        runCatching {
            v.javaClass.getMethod("setCallback", Class.forName("android.app.ActivityView\$StateCallback"))
                .invoke(v, null)
        }
        runCatching {
            v.javaClass.getMethod("release").invoke(v)
        }.onFailure {
            Logx.d(LogTags.NAV_UI, "释放 ActivityView 失败", "reason" to it.message)
        }
    }

    /**
     * 尺寸变化时同步 VirtualDisplay，否则画面会拉伸/黑边（resizeVirtualDisplay）。
     *
     * ## 为什么要多调一次
     *
     * resize 之后紧接着
     * `activityView.onLocationChanged()` —— 它会把承载任务的**显示配置**刷新一遍，
     * 应用才会按新的显示尺寸重新排版。
     *
     * 只 resize 不通知的话，应用会拿旧尺寸的布局硬塞进新画面里：
     * **画中画全屏 → 还原**之后地图会被放大裁掉一块（模拟器实测：
     * 高德按 1920 宽排的版，还原到 1363 宽后仍按旧尺寸绘制，看着像被放大 1.4 倍。
     */
    fun resizeVirtualDisplay(view: View, w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        // 排障场景：「画中画面拉伸 / 黑边」——尺寸同步用 DEBUG，不落盘。
        Logx.d(LogTags.NAV_UI, "ActivityView 同步 VirtualDisplay 尺寸", "w" to w, "h" to h)
        runCatching {
            // API 30+：VirtualDisplay 由 ActivityView 自己持有（mVirtualDisplay）
            val own = findField(view, "mVirtualDisplay")
            if (own != null) {
                readField(own, view)?.let { resizeDisplay(it, w, h, view) }
                notifyLocationChanged(view)
                return@runCatching
            }
            // API 29：display 挂在 mHost 上
            val hostField = findField(view, "mHost")
            if (hostField != null) {
                val host = readField(hostField, view)
                if (host != null) {
                    val m = findField(host, "mDisplay") ?: findField(host, "mVirtualDisplay")
                    if (m != null) {
                        readField(m, host)?.let { resizeDisplay(it, w, h, view) }
                    }
                }
            }
            notifyLocationChanged(view)
        }
    }

    /**
     * 通知承载面「位置/尺寸变了」（里的
     * `activityView.onLocationChanged()`。
     *
     * AOSP 9 的 `ActivityView.onLocationChanged()` 会让承载任务刷新显示配置，
     * 应用据此重新排版 —— 少了这一步，全屏还原后画面比例会错。
     */
    private fun notifyLocationChanged(view: View) {
        runCatching {
            view.javaClass.getMethod("onLocationChanged").invoke(view)
        }.onFailure {
            // 老平台没有这个方法 → 忽略（画面拉伸由 resize 兜底）
            Logx.d(LogTags.NAV_UI, "ActivityView onLocationChanged 调用失败", "reason" to it.message)
        }
    }

    /** 调整 VirtualDisplay 尺寸（不同固件签名不一样，三参优先）。 */
    private fun resizeDisplay(display: Any, w: Int, h: Int, view: View) {
        val dpi = view.context.resources.displayMetrics.densityDpi
        val three = display.javaClass.methods.firstOrNull {
            it.name == "resize" && it.parameterTypes.size == 3
        }
        if (three != null) {
            three.invoke(display, w, h, dpi)
        } else {
            display.javaClass.methods.firstOrNull {
                it.name == "resize" && it.parameterTypes.size == 2
            }?.invoke(display, w, h)
        }
    }
}
