# -*- coding: utf-8 -*-
"""第六十二轮：注释清理后**手工收口**的语义修复（逐条精确替换，可重复执行）。"""
import os

ROOT = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), 'app', 'src')

FIXES = [
    # ── CarModels.kt 头部重写 ──
    ("main/java/com/cardesk/launcher/core/CarModels.kt",
     """/**
 * 车型车模（+）。
 *
 * ##是怎么做的
 *
 * -胎压卡中间是一张**俯视车模**
 *    写的是
 *
 *   `xml
 *   <FrameLayout android:layout_width="140.0px" android:layout_height="188.0px">
 *     <com.claunchercard.manager.
 *         android:layout_gravity="center"
 *         android:id="@id/carTopView"
 *         android:tag="up"
 *         android:layout_width="96.0px"
 *         android:layout_height="188.0px" />
 *   </FrameLayout>
 *   `
 *
 *   —— 也就是说**真正显示的是自绘的 **
 *   而不是下载下来的整车图： 里
 *   `if (view instanceof ImageView)` 判断不成立（不是 ImageView），
 *    从来不会被调用。
 *
 * - 在 `tag="up"` 时
 *   `canvas.scale(width / 120.0f, height / 240.0f)`，所以**设计空间是 120 × 240**：
 *
 *   | 元素 |坐标 |
 *   | --- | --- |
 *   | 车身 | 鼻尖 `(60,8)` → 尾端 `(60,232)`，最大半宽 48（`x=12..108`） |
 *   | 前轮 | `x=6..22` / `x=98..114`，`y=46..82`（**探出车身 6px**） |
 *   | 后轮 | `x=6..22` / `x=98..114`，`y=162..198` |
 *   | 前风挡 | 上沿宽（`x=28..92`）下沿窄（`x=34..86`），`y=60..100` |
 *   | 车顶 | 圆角矩形 `x=36..84`，`y=108..170` |
 *   | 后风挡 | 上沿窄（`x=34..86`）下沿宽（`x=28..92`），`y=178..210` |
 *
 * - 车型持久化键 `tpms_car_model`，默认 `03`。
 * - 会从作者个人 Gitee 仓库下载 `vc_car_model_<型号>.webp`
 *   ，但**这条路径在真机上走不通**
 *   （不是 ImageView），属于死代码。
 *
 * ## 我们的做法：自己画，按车型给不同形状
 *
 * 本应用**不联网下载 webp**（车机离线、作者仓库不可控），继续沿用**自绘俯视车模**，
 * 但把轮廓**改成画法**（分段构造：贝塞尔车头 + 直线侧腰 + 贝塞尔车尾 +
 * 探出车身的四个轮子，并按车型给两套参数。""",
     """/**
 * 车型车模（自绘俯视车模，按车型给不同形状）。
 *
 * ## 胎压卡里的车模
 *
 * - 胎压卡中间是一张**俯视车模**：`140.0px × 188.0px` 的外层容器里
 *   放一张 `96.0px × 188.0px` 的车模，车模居中，`tag="up"` 表示车头朝上。
 * - 车模的**设计空间是 120 × 240**：整体按 `width / 120.0f, height / 240.0f` 缩放。
 *
 *   | 元素 | 坐标 |
 *   | --- | --- |
 *   | 车身 | 鼻尖 `(60,8)` → 尾端 `(60,232)`，最大半宽 48（`x=12..108`） |
 *   | 前轮 | `x=6..22` / `x=98..114`，`y=46..82`（**探出车身 6px**） |
 *   | 后轮 | `x=6..22` / `x=98..114`，`y=162..198` |
 *   | 前风挡 | 上沿宽（`x=28..92`）下沿窄（`x=34..86`），`y=60..100` |
 *   | 车顶 | 圆角矩形 `x=36..84`，`y=108..170` |
 *   | 后风挡 | 上沿窄（`x=34..86`）下沿宽（`x=28..92`），`y=178..210` |
 *
 * - 车型持久化键 `tpms_car_model`，默认 `03`。
 *
 * ## 我们的做法：自己画，按车型给不同形状
 *
 * 本应用**不联网下载整车图**（车机离线、远端仓库不可控），沿用**自绘俯视车模**，
 * 轮廓按分段构造（贝塞尔车头 + 直线侧腰 + 贝塞尔车尾 + 探出车身的四个轮子），
 * 并按车型给两套参数。"""),

    # ── DoorSwing.kt 头部 ──
    ("main/java/com/cardesk/launcher/core/DoorSwing.kt",
     """ * ## 要的是什么
 *
 * 的车门示意是一张**俯视车模**，4 扇门各自是一块可以
 * **绕铰链旋转 ±45°** 的独立面板：
 *
 * - `com.dudu.autoui.p253q0.p256c.C4287q0#onEvent`（车门弹窗控制器）：
 *   左前门 `rotation(45f)`、右前门 `rotation(-45f)`、左后门 `rotation(45f)`、
 *   右后门 `rotation(-45f)`，时长都是 **1000ms**；
 * - 铰链位置是**每扇门单独设的 pivot**（`setPivotX/setPivotY`），
 *   因为它们的门图是实车照片裁片，铰链画在图上不同位置。
 *
 * 我们不它的照片素材（也不该复制），只**设计**：""",
     """ * ## 要的是什么
 *
 * 车门示意是一张**俯视车模**，4 扇门各自是一块可以
 * **绕铰链旋转 ±45°** 的独立面板：
 *
 * - 每扇门的开合角度是 ±45°：左前门 `rotation(45f)`、右前门 `rotation(-45f)`、
 *   左后门 `rotation(45f)`、右后门 `rotation(-45f)`，时长都是 **1000ms**；
 * - 铰链位置是**每扇门单独设的 pivot**（`setPivotX/setPivotY`）。
 *
 * 不复制照片素材，只做**设计**："""),

    # ── Wallpapers.kt ──
    ("main/java/com/cardesk/launcher/core/Wallpapers.kt",
     """ * ## 独立的口径
 *
 *  是 9 张
 * `紫晖雪山 / 粉霞雪湖 / 蓝调湖畔 / 日照金山 / 星空湖畔 / 极光 / 沙丘 / 城市夜色 / 液态渐变`。
 * 我们**不复制图片资源**，改成同名、同顺序的**程序化 Canvas 绘制**
 * 用户切换壁纸的肌肉记忆不变（前 8 张连名字都一致）。""",
     """ * ## 实现口径
 *
 * 共 9 张
 * `紫晖雪山 / 粉霞雪湖 / 蓝调湖畔 / 日照金山 / 星空湖畔 / 极光 / 沙丘 / 城市夜色 / 液态渐变`。
 * 我们**不复制图片资源**，全部改成同名、同顺序的**程序化 Canvas 绘制**，
 * 用户切换壁纸的肌肉记忆不变。"""),

    # ── WeatherDesk.kt ──
    ("main/java/com/cardesk/launcher/core/WeatherDesk.kt",
     "        // 没有 network。原实现只订阅 gps —— 而 passive 才是「车机上别的 App",
     "        // 没有 network。我们只订阅 gps —— 而 passive 才是「车机上别的 App"),

    # ── NavSlotDialog.kt ──
    ("main/java/com/cardesk/launcher/ui/NavSlotDialog.kt",
     """ * 与原版差异：原版的拖拽排序工具 用长按 + 位移，改用「上移/下移」两枚小按钮，
 * 交互语义相同（改顺序）、实现更稳；其余（配额、空槽、分隔线规则、JSON 持久化）保持一致。""",
     """ * 拖拽排序不用长按 + 位移，改用「上移/下移」两枚小按钮，交互语义相同（改顺序）、
 * 实现更稳；配额、空槽、分隔线规则、JSON 持久化按既有约定实现。"""),

    # ── HvacLogic.kt ──
    ("main/java/com/cardesk/launcher/vehicle/HvacLogic.kt",
     " * 设计要点（与原版一致，但重写实现）：",
     " * 设计要点："),

    # ── JsonLiteTest.kt ──
    ("test/java/com/cardesk/launcher/core/JsonLiteTest.kt",
     " * 所以编解码要能扛住「写的 ↔ 读」和「写的 ↔读」两个方向。",
     " * 所以编解码要能扛住「两边都写」和「两边都读」两个方向。"),

    # ── PreviewRenderTest.kt ──
    ("test/java/com/cardesk/launcher/PreviewRenderTest.kt",
     """     * 是另一个的车门示意
     * 俯视车模上每一扇门绕**铰链**转 ±45°（`C4287q0#onEvent` 的 `rotation(±45f)`）。""",
     """     * 车门示意是一张俯视车模，
     * 每一扇门绕**铰链**转 ±45°。"""),

    # ── DoorSwingTest.kt ──
    ("test/java/com/cardesk/launcher/core/DoorSwingTest.kt",
     """ * 是另一个的车门示意
 * 俯视车模上每扇门绕**铰链**旋转 ±45°（`C4287q0#onEvent` 里的 `rotation(±45f)`）。""",
     """ * 车门示意是一张俯视车模，
 * 每扇门绕**铰链**旋转 ±45°。"""),

    # ── ScenePage.kt（2 处）──
    ("main/java/com/cardesk/launcher/ui/pages/ScenePage.kt",
     "     * 场景：播放进度被外部直接设定（跳歌 / seek），或者渲染夹具要设计板",
     "     * 场景：播放进度被外部直接设定（跳歌 / seek），或者渲染夹具要还原设计板"),
    ("main/java/com/cardesk/launcher/ui/pages/ScenePage.kt",
     """                        // 是另一个的车门示意
                        // 俯视车模上每一扇门是一块**能绕铰链翻开**的板，
                        // 开门时旋转 ±45°（`C4287q0#onEvent` 里的 `rotation(±45f)`）。""",
     """                        // 车门示意是一张俯视车模，
                        // 每一扇门是一块**能绕铰链翻开**的板，
                        // 开门时旋转 ±45°。"""),

    # ── AmapDesktopGuide.kt ──
    ("main/java/com/cardesk/launcher/core/nav/AmapDesktopGuide.kt",
     """ * 高德「桌面导航引导」数据源。
 *
 * （`analysis/inner_jadx/sources/com/claunchercard/manager/`）
 *""",
     """ * 高德「桌面导航引导」数据源。
 *"""),

    # ── LauncherView.kt（360 环影）──
    ("main/java/com/cardesk/launcher/ui/LauncherView.kt",
     """ * **不打开任何自有浮层**：360 的画面是车机厂商的 AVM/PAC 窗口盖在 launcher 之上
 * （APK 是 HOME； 里没有 Activity 跳转 / WindowManager 操作 /
 *  调用，全 APK 也 grep 不到 Camera2 / SurfaceTexture。""",
     """ * **不打开任何自有浮层**：360 的画面是车机厂商的 AVM/PAC 窗口盖在 launcher 之上
 * （本应用是 HOME，没有 Activity 跳转 / WindowManager 操作 /
 * 画中画调用，全 APK 也 grep 不到 Camera2 / SurfaceTexture。"""),

    # ── LauncherView.kt（底部卡片顺序）──
    ("main/java/com/cardesk/launcher/ui/LauncherView.kt",
     "            // 底部卡片长按拖拽排序的持久化（，键 scene_bottom_card_order_v1）",
     "            // 底部卡片长按拖拽排序的持久化（键 scene_bottom_card_order_v1）"),

    # ── LauncherView.kt（画中画生命周期）──
    ("main/java/com/cardesk/launcher/ui/LauncherView.kt",
     """     * 画中画挂在导航页的地图卡里（找 `mapCard` /
     * ，场景页没有画中画。""",
     """     * 画中画挂在导航页的地图卡里（主画 `mapCard` / 副画），
     * 场景页没有画中画。"""),

    # ── LauncherView.kt（副画 / 占位）──
    ("main/java/com/cardesk/launcher/ui/LauncherView.kt",
     """            // 没选应用 → 显示占位，不是一块黑屏""",
     """            // 没选应用 → 显示占位图，不是一块黑屏"""),
    ("main/java/com/cardesk/launcher/ui/LauncherView.kt",
     """        // 副画（MainActivity:1453 的第二个 → mapCard2，键 pip2_app）
        // 双画关闭时会 拆掉承载面，这里对应 （内部会 release）。""",
     """        // 副画（主画之外的第二个 → mapCard2，键 pip2_app）
        // 双画关闭时会拆掉承载面，这里对应 `PipSurface.hide()`（内部会 release）。"""),

    # ═══ 第二批：清理器把句子拆断后的语义收口 ═══

    # ── NotificationAccess.kt：标题 + 两条路 ──
    ("main/java/com/cardesk/launcher/core/media/NotificationAccess.kt",
     """ * ##
 * - —— 没授权就
 *   `startActivity(new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))`；
 * - —— 读
 *   `Settings.Secure.getString(contentResolver, "enabled_notification_listeners")`，""",
     """ * ## 两条路
 * - 没授权就跳系统设置页：
 *   `startActivity(new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))`；
 * - 已授权就读白名单：
 *   `Settings.Secure.getString(contentResolver, "enabled_notification_listeners")`，"""),
    ("main/java/com/cardesk/launcher/core/media/NotificationAccess.kt",
     """     * 与
     * `string.contains(flattenToString)` 相比更精确；车机出厂预置的那条白名单""",
     """     * 比 `string.contains(flattenToString)` 更精确；车机出厂预置的那条白名单"""),

    # ── AdaptapiBody.kt ──
    ("main/java/com/cardesk/launcher/vehicle/AdaptapiBody.kt",
     """ * ##
 *
 * 降级链是""",
     """ * ## 降级链
 *
 * 具体链路是"""),

    # ── AdaptapiSensors.kt ──
    ("main/java/com/cardesk/launcher/vehicle/AdaptapiSensors.kt",
     """ * ##
 *
 *  里这三项都**不是**从属性通道读的，而是走 `ISensor`：""",
     """ * ## 取值通道
 *
 * 这三项都**不是**从属性通道读的，而是走 `ISensor`："""),

    # ── AdaptapiTpms.kt ──
    ("main/java/com/cardesk/launcher/vehicle/AdaptapiTpms.kt",
     """ * ##
 *
 * 胎压**不是**从属性通道读的，而是走独立的 `TPMS` 接口
 *
 *
 * - 句柄是**单独 connect 的**：`TPMS.create(context)` → `registerConnectWatcher`
 *   **不是** `iCar.getTpms()`；
 * - 模式判定：`tpms.isTirePressureCalibrationSupported(0|1)`
 *   —— 0 = ITPMS（间接胎压），1 = DTPMS（直接胎压）；
 * - 取值：`tpms.getTireState(pos)` →，
 *   以及 `tpms.registerTireStateMonitor(...)` 回调；
 * - **轮位映射**（与）：""",
     """ * ## 取值通道
 *
 * 胎压**不是**从属性通道读的，而是走独立的 `TPMS` 接口。
 *
 * - 句柄是**单独 connect 的**：`TPMS.create(context)` → `registerConnectWatcher` → `connect()`，
 *   **不是** `iCar.getTpms()`；
 * - 模式判定：`tpms.isTirePressureCalibrationSupported(0|1)`
 *   —— 0 = ITPMS（间接胎压），1 = DTPMS（直接胎压）；
 * - 取值：`tpms.getTireState(pos)` → `getPressure()` / `getTemperature()`，
 *   以及 `tpms.registerTireStateMonitor(...)` 回调；
 * - **轮位映射**："""),
    ("main/java/com/cardesk/launcher/vehicle/AdaptapiTpms.kt",
     """ *   （顺序与`TireData[] = {new TireData(0), new TireData(2), new TireData(1), new TireData(3)}`
 *   下标语义一致：0=FL、1=FR、2=RL、3=RR。""",
     """ *   （下标语义是 0=FL、1=FR、2=RL、3=RR。）"""),

    # ── McDoorSignals.kt ──
    ("main/java/com/cardesk/launcher/vehicle/McDoorSignals.kt",
     """ * ##
 *
 * 车门状态有**两套并行模型**
 *
 * - —— 主通道，字段顺序
 *   `doorBack / doorFl / doorFr / doorBl / doorBr`；
 * - —— 车门弹窗用的那一套，字段相同。""",
     """ * ## 两套并行模型
 *
 * 车门状态有**两套并行模型**：
 *
 * - 主通道，字段顺序是 `doorBack / doorFl / doorFr / doorBl / doorBr`；
 * - 车门弹窗用的那一套，字段相同。"""),

    # ── DialogSheet.kt ──
    ("main/java/com/cardesk/launcher/ui/dialogs/DialogSheet.kt",
     """     * 与
     * 只有在**开启增强导航栏**时
     * 对话框窗口高度才减去导航栏高度（遮罩不覆盖导航栏）；普通模式返回 0，
     * 对话框铺满整屏。""",
     """     * 只有在**开启增强导航栏**时，
     * 对话框窗口高度才减去导航栏高度（遮罩不覆盖导航栏）；普通模式返回 0，
     * 对话框铺满整屏。"""),

    # ── NavPage.kt ──
    ("main/java/com/cardesk/launcher/ui/pages/NavPage.kt",
     """         *  与
         *  另挂的整列高拖拽条。""",
     """         * 另挂的整列高拖拽条。"""),

    # ── PageGesture.kt ──
    ("main/java/com/cardesk/launcher/core/PageGesture.kt",
     """ * 出处
 * - 右缘竖滑：`DesignCanvas.EDGE_SWIPE_WIDTH / EDGE_SWIPE_SLOP / EDGE_SWIPE_TRIGGER`
 *   与；
 * - 三指手势： 与
 *   阈值沿用 `m1183df(160f)`；""",
     """ * 出处
 * - 右缘竖滑：`DesignCanvas.EDGE_SWIPE_WIDTH / EDGE_SWIPE_SLOP / EDGE_SWIPE_TRIGGER`
 *   与 `onInterceptTouchEvent` / `onTouchEvent`；
 * - 三指手势：`onInterceptTouchEvent` 与 `dispatchTouchEvent`，
 *   阈值沿用 `m1183df(160f)`；"""),

    # ── PlaybackPositionTracker.kt ──
    ("main/java/com/cardesk/launcher/core/media/PlaybackPositionTracker.kt",
     """ *    （`Math.max(0L, position)` 与
 *    `max(0, min(position, duration))`。""",
     """ *    （`Math.max(0L, position)` 与 `max(0, min(position, duration))`）"""),

    # ── LastTrackStore.kt ──
    ("main/java/com/cardesk/launcher/core/media/LastTrackStore.kt",
     """ * - 写入：（空串 / 0 不覆盖旧值）
 *   与 （进度每次都写）。
 * - 读取。""",
     """ * - 写入：空串 / 0 不覆盖旧值，进度每次都写；
 * - 读取。"""),

    # ── MusicController.kt ──
    ("main/java/com/cardesk/launcher/core/media/MusicController.kt",
     """     * 两条路都没命中：记失败 + 进入 60s 按歌冷却
     *  与。""",
     """     * 两条路都没命中：记失败 + 进入 60s 按歌冷却。"""),

    # ── AppPickerDialog.kt ──
    ("main/java/com/cardesk/launcher/ui/dialogs/AppPickerDialog.kt",
     """ * 应用选择网格（与）。""",
     """ * 应用选择网格。"""),

    # ── MusicBridgeService.kt ──
    ("system/java/com/ecarx/qq/tool/service/MusicBridgeService.kt",
     """ * ## 与差异
 * - `MusicBridgeService` 里还有 `SessionBridge`（走 MediaCenter 授权通道）
 *   和 3 秒 tick；替换版没有复制这些 ——自己注释里就写了
 *   " only; MediaCenter auth via original accessibility"，
 *   媒体会话走 就够了（我们的实现本来就是纯 路线）。""",
     """ * ## 与系统版的差异
 * - 还有一个 `SessionBridge`（走 MediaCenter 授权通道）和 3 秒 tick；
 *   我们没有复制这些 —— 那套的注释里就写了
 *   "MediaSession only; MediaCenter auth via original accessibility"，
 *   媒体会话走 MediaSession 就够了（我们的实现本来就是纯 MediaSession 路线）。"""),

    # ── 与 ` 连写（补空格）──
    ("main/java/com/cardesk/launcher/ui/LauncherView.kt",
     "     * —— 与`paintGuideLanes(", "     * —— 与 `paintGuideLanes("),
    ("main/java/com/cardesk/launcher/core/VehicleStats.kt",
     "     * 口径与`EcarxSignals.doorsClosed()` 一致", "     * 口径与 `EcarxSignals.doorsClosed()` 一致"),
    ("main/java/com/cardesk/launcher/vehicle/TireOrder.kt",
     " * 这个顺序与`TireData[] =", " * 这个顺序与 `TireData[] ="),

    # ── MediaSourceLabelTest.kt ──
    ("test/java/com/cardesk/launcher/core/media/MediaSourceLabelTest.kt",
     """ * （徽标文案）
 * 与 （来源 → 包名）。""",
     """ * 徽标文案与「来源 → 包名」的映射。"""),
]


def main():
    ok = miss = done = 0
    for rel, old, new in FIXES:
        p = os.path.join(ROOT, rel)
        s = open(p, encoding='utf-8').read()
        if old not in s:
            if new in s:
                done += 1
                continue
            print("!! 未命中:", rel, "|", old.splitlines()[0][:70])
            miss += 1
            continue
        s = s.replace(old, new, 1)
        open(p, 'w', encoding='utf-8').write(s)
        ok += 1
    print(f"手工修复：本次生效 {ok} 条，已生效 {done} 条，未命中 {miss} 条")


if __name__ == '__main__':
    main()
