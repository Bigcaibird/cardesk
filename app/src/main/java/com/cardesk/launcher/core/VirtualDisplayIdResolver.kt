package com.cardesk.launcher.core

/**
 * 从「承载面（`ActivityView`）」里解析出**虚拟显示器**的 displayId（第140轮，纯逻辑）。
 *
 * ## 为什么单独抽一个文件
 *
 * 画中画黑屏的**权威判据**是「承载应用的 Activity 还挂在我们的 VirtualDisplay 上吗」，
 * 判法跟 AOSP 官方 `VirtualDisplay Launcher` 示例一样：
 * `task.displayId == virtualDisplay.getDisplayId()`。
 *
 * 这条判据要成立，先得**拿到我们自己的 displayId**。
 * 而「拿到 displayId」这件事在 **API 28 上以前是拿不到的** —— 判据恒为「不知道」，
 * 于是**黑屏自愈那一整级在 API 28 上等于没有**。
 *
 * ## 拿不到的原因（第140轮实测 + AOSP 9 源码）
 *
 * AOSP 9（`android-9.0.0_r1`）的 `android.app.ActivityView` 里：
 *
 * ```java
 * import android.hardware.display.VirtualDisplay;
 * ...
 * private VirtualDisplay mVirtualDisplay;
 * ...
 * options.setLaunchDisplayId(mVirtualDisplay.getDisplay().getDisplayId());
 * ```
 *
 * 注意 `android.hardware.display.VirtualDisplay` **不是** `android.view.Display`
 * （它是个 final 类，包着一个 `Display`）——
 * 所以只认 `android.view.Display` 的旧代码在 API 28 上**永远取不到 id**。
 *
 * API 29 的 `ActivityView`（AOSP 10）也是这个结构。
 *
 * ## 为什么不在这里直接写反射
 *
 * 这个文件里的反射**只碰 `java.lang.reflect`**（按方法名调用），
 * 不引用任何 `android.*` 类型 → **可以进 JVM 单测**。
 * 平台差异（哪个字段名、哪个类）交给 [ActivityEmbed] 那一侧，
 * 它把「探测到的东西」喂进来，这里只做**取舍**。
 *
 * ## 取舍规则为什么这么写
 *
 * | 规则 | 为什么 |
 * |---|---|
 * | 只认**已确认是虚拟显示器**的候选 | 第130轮的教训：`ActivityViewHost.mDisplay` 是**承载面自己所在的那块屏**（桌面那屏），拿它当虚拟屏 → 跟任何 task 都对不上 → **把正常画面判成黑屏并误重启了高德** |
 * | `displayId <= 0` 一律丢弃 | `displayId == 0` 是**主显示器**，虚拟屏不会是 0 |
 * | 等于**我们自己的 displayId** 的一律丢弃 | 车机是多屏的：我们的窗口所在的屏 id 可能不是 0，把它当成虚拟屏 → 又是误判 |
 * | 列表顺序 = 优先级，取第一个 | 公开 API 最可靠，其次是自己持有的 `mVirtualDisplay`，最后才是 `mHost` 那条老路 |
 * | 一个都拿不准 → **null** | 判据「不知道」时**宁可漏判也不能误判** —— 误判的代价是**把用户正在用的应用重启** |
 */
object VirtualDisplayIdResolver {

    /** 探测来源（列表顺序即优先级，从高到低）。 */
    enum class Source {
        /** 公开方法 `getVirtualDisplayId()`（API 30+）。 */
        PUBLIC_API,

        /** 承载面**自己**持有的 `mVirtualDisplay`（AOSP 9 / API 28 起就是它）。 */
        OWN_VIRTUAL_DISPLAY,

        /** `ActivityViewHost.mVirtualDisplay`（AOSP 8.1 / API 27 及更早）。 */
        HOST_VIRTUAL_DISPLAY,

        /**
         * `ActivityViewHost.mDisplay` —— 承载面**自己所在**的那块屏。
         *
         * 只有**确认它确实是虚拟显示器**时才作数（第130轮的坑就在这）。
         */
        HOST_DISPLAY,
    }

    /**
     * 一次探测的结果。
     *
     * @param source 来自哪一路
     * @param displayId 这一路读到的 displayId（null = 读不到）
     * @param confirmedVirtual 是否**已确认**是虚拟显示器
     */
    data class Candidate(
        val source: Source,
        val displayId: Int?,
        val confirmedVirtual: Boolean,
    )

    /**
     * 从候选里挑出**我们那个虚拟显示器**的 displayId。
     *
     * @param candidates 候选，按优先级从高到低排
     * @param ownDisplayId **我们自己的窗口**所在显示器的 id（null = 不知道）
     * @return 虚拟显示器的 displayId；null = **拿不准**（调用方按「不知道」处理）
     */
    fun pick(candidates: List<Candidate>, ownDisplayId: Int? = null): Int? {
        for (c in candidates) {
            // 只认「已确认是虚拟显示器」的候选 —— 见类注释里的第130轮教训
            if (!c.confirmedVirtual) continue
            val id = c.displayId ?: continue
            // displayId 0 是主显示器，虚拟屏不会是 0
            if (id <= 0) continue
            // 我们自己的窗口所在那块屏，一定不是我们要找的虚拟屏
            if (ownDisplayId != null && id == ownDisplayId) continue
            return id
        }
        return null
    }

    /**
     * 从「像虚拟显示器的东西」里取 displayId。
     *
     * 两种形态都要认：
     *
     * - 它**本身**就是个 `Display`（老 API 下的 `mVirtualDisplay`）→ 直接取；
     * - 它是个**包着 Display 的壳**（AOSP 9 的 `android.hardware.display.VirtualDisplay`）
     *   → 先 `getDisplay()` 再取。
     *
     * 全程只按**方法名**调用，不引用任何 `android.*` 类型 → 可 JVM 单测。
     *
     * @return null = 取不到（不是 Display，也没有 getDisplay()）
     */
    fun displayIdOfVirtualDisplayLike(obj: Any?, depth: Int = 0): Int? {
        if (obj == null) return null
        // 最多剥两层（壳 → Display），防止互相持有时死循环
        if (depth > 2) return null
        // 1) 本身就是 Display（或别的带 getDisplayId() 的东西）
        invokeInt(obj, "getDisplayId")?.let { return it }
        // 2) 壳：getDisplay() 里那个才是 Display
        val inner = invokeNoArg(obj, "getDisplay")
        if (inner != null && inner !== obj) {
            return displayIdOfVirtualDisplayLike(inner, depth + 1)
        }
        return null
    }

    /**
     * 这个对象**是不是**虚拟显示器？
     *
     * 判法（两条任一成立即可）：
     *
     * - **类名**里带 `VirtualDisplay`（`android.hardware.display.VirtualDisplay`）；
     * - 有公开的 `getVirtualDisplayId()` 且返回值 > 0（`android.view.Display` 的实现类）。
     *
     * 只按类名和方法名判断 → 不引用 `android.*` 类型 → 可 JVM 单测。
     *
     * ## 为什么看的是「简单类名」而不是全名
     *
     * 全名（`javaClass.name`）把**外层类**也带上了：
     * `com.xxx.VirtualDisplayIdResolverTest$FakeDisplay` 里也含 "VirtualDisplay"
     * → 会把一个普通 Display 误判成虚拟显示器。
     * 简单类名（`javaClass.simpleName`）只看它自己叫什么，判定才准。
     */
    fun looksLikeVirtualDisplay(obj: Any?): Boolean {
        if (obj == null) return false
        if (obj.javaClass.simpleName.contains("VirtualDisplay", ignoreCase = true)) return true
        val id = invokeInt(obj, "getVirtualDisplayId") ?: return false
        return id > 0
    }

    /** 按方法名调一个无参方法，取第一个返回值（`Int` / `Boolean` / 其它）。 */
    private fun invokeNoArg(obj: Any, name: String): Any? = runCatching {
        obj.javaClass.getMethod(name).invoke(obj)
    }.getOrNull()

    /** 按方法名调一个无参方法，只认 `Int` 返回值。 */
    private fun invokeInt(obj: Any, name: String): Int? = runCatching {
        obj.javaClass.getMethod(name).invoke(obj) as? Int
    }.getOrNull()
}
