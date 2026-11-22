package com.cardesk.launcher.core

/**
 * 画中画的**渲染路线**选择。
 *
 * ## 两条路线
 *
 * - 视频壁纸走 `TextureView`（[VideoWallpaper.BACKEND_TEXTURE_VIEW]）：
 *   视频画进窗口自己的缓冲 ⇒ 窗口**不透明** ⇒ 画中画可以照旧用
 *   `ActivityView`（承载层是独立图层，靠窗口「挖洞」透出来）。
 * - 视频壁纸走 `SurfaceView`（[VideoWallpaper.BACKEND_SURFACE_VIEW]）：
 *   视频是**硬件图层**、压在窗口**之下**，窗口必须 `TRANSLUCENT` 才能透出视频。
 *   窗口一透明，「挖洞」这条路就废了 —— 承载层的图层不再被窗口轮廓裁剪，
 *   于是圆角变直角、画面与窗口坐标错位、切页还会残留。
 *
 * 所以窗口透明时，画中画必须改走 [Mode.MIRROR]：自己建 `VirtualDisplay`，
 * 把承载画面**画进窗口自己的缓冲**（`TextureView`）—— 这样它又变回普通 View，
 * `clipToOutline` 圆角、切页隐藏全都成立，而且代价只有画中画那块（约 1287×867），
 * 远小于全屏视频。
 *
 * ## 第228轮：真车逐条复核（领克 820A，Android 9 / API 28）
 *
 * 路线本身**不用改**（半透明窗口下 `ActivityView` 的圆角确实会失效，
 * 真车实测：壁纸开 ⇒ 地图卡左上角是直角；壁纸关 ⇒ 圆角成立）。
 *
 * 但第226轮那版镜像路线**只做了一半**，于是用户在车机上看到三个现象 ——
 * **三个都不是路线选错，是这条路线缺东西**：
 *
 * | 现象 | 根因 | 第228轮修在哪 |
 * | --- | --- | --- |
 * | 画中画触控不了 | 镜像路线没有任何触摸转发（`ActivityView` 自带 input forwarding） | `PipMirrorHost.forwardTouch`（`createInputForwarder`） |
 * | 切全屏右侧缺 108px | 建虚拟屏后没调 `wm.dontOverrideDisplayInfo(displayId)`，WM 覆盖了显示信息 ⇒ 承载应用拿到的配置和实际尺寸对不上 | `PipMirrorHost.dontOverrideDisplayInfo` |
 * | 大小调整玻璃条看不见 | `NavPage.placePipDivider()` 把 **row 局部坐标**当成了 NavPage 坐标（差 44px），玻璃条画歪了压在画中画自己的滚动条上 | `NavPage.placePipDivider`（与路线无关） |
 *
 * ⇒ 结论：**保持「窗口透明 ⇒ 镜像」的选择，把镜像路线补完整。**
 */
object PipRenderPlan {

    /** 画中画的渲染路线。 */
    enum class Mode {
        /** 旧路线：`ActivityView` + 它自己的图层（窗口不透明时用）。 */
        LAYER,

        /** 第226轮：自建虚拟屏 + `TextureView` 镜像（窗口透明时用）。 */
        MIRROR,
    }

    /**
     * 按窗口是否透明选路线。
     *
     * @param translucentWindow 桌面窗口当前是不是半透明（视频壁纸走 SurfaceView 时为 true）
     * @return 窗口透明 ⇒ [Mode.MIRROR]；否则 [Mode.LAYER]
     */
    fun modeFor(translucentWindow: Boolean): Mode =
        if (translucentWindow) Mode.MIRROR else Mode.LAYER

    /**
     * 主路线建不起来时的**兜底**路线。
     *
     * @param translucentWindow 桌面窗口当前是不是半透明
     * @return 另一条路线（与 [modeFor] 相反）
     */
    fun fallbackModeFor(translucentWindow: Boolean): Mode =
        if (translucentWindow) Mode.LAYER else Mode.MIRROR

    /**
     * 承载面**要不要重建**（第228轮）。
     *
     * ## 为什么需要这个判断
     *
     * 窗口的透明性会**在运行中变**（用户开关视频壁纸 ⇒ 改窗口 format）。
     * 承载面是「同包名复用」的 ⇒ 判据变了，承载面还挂着**旧路线**的内容：
     *
     * - 窗口变不透明了，承载面却还是镜像路线 ⇒ 与「不用视频壁纸」时的老行为
     *   （`ActivityView`）不一致 ⇒ 全屏 / 拖动都别扭（真车实测：用户反馈
     *   「以前不用视频壁纸一切正常，现在有问题了」）；
     * - 窗口变半透明了，承载面却还是 `ActivityView` ⇒ 圆角失效、坐标错位、切页残留。
     *
     * ⇒ 路线变了就**重建**承载面。
     *
     * @param current 当前承载内容实际走的路线（null = 没在承载 / 降级成静态卡片）
     * @param want 现在应该走的路线
     * @return true = 必须重建
     */
    fun needsRebuild(current: Mode?, want: Mode): Boolean =
        current != null && current != want
}
