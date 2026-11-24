# 第三百一十三轮：启动页底图 PNG → WebP q90（APK 再降 10.1%）

## 0. 背景

上一轮把内置车模图从安装包移出后，`assets/` 里剩下的最大一块是**启动页背景底图**：

| 文件 | 尺寸 | 体积 |
|---|---|---|
| `splash_day.png` | 960×540 | 225,937 B |
| `splash_night.png` | 960×540 | 100,373 B |

两张合计 326,310 B，占当时 APK 的 10.4%。它们是画板背景的「烘焙图」：画板底由外层三条渐变、
`::before/::after` 两个大圆盘、内层四处高光/模糊盘叠成 8 层，CSS 的 `radial-gradient` 与
Canvas `RadialGradient` 口径不同，逐层近似实测左上青绿区差 34/255（肉眼可见「左上发白」），
所以按画板原样渲染成位图。图里**没有文字/Logo**：品牌字、圆环、状态文案、进度条都是代码绘制。

关键观察：这张图是**平滑多色渐变**——lossy WebP 对这类内容极其友好，和照片完全不同。

## 1. 做了什么

- 用 WebP **q90**（Pillow `method=6`）重编码两张底图：

  | 文件 | PNG | WebP q90 | 压缩比 |
  |---|---|---|---|
  | `splash_day` | 225,937 B | **6,054 B** | 37× |
  | `splash_night` | 100,373 B | **3,524 B** | 28× |
  | 合计 | 326,310 B | **9,578 B** | **−316,732 B（−97.1%）** |

- `assets/splash/*.png` 删除，只留 `*.webp`。
- `LaunchSplashView`：资源名改成 `.webp`；类注释补上「为什么用 WebP q90」和体积数字。
  解码仍是 `BitmapFactory.decodeStream`（API 14 起原生支持 WebP，**零新增依赖**），
  失败路径不变——`backdrop == null` 就回落纯色底，不会黑屏、不会崩。
- 新增 `app/src/test/.../ui/SplashAssetTest.kt` 三条断言，锁住这次改动容易悄悄回退的三点：
  1. 两张底图都在，且真的是 WebP（`RIFF` + `WEBP` 魔数 —— 不看扩展名）；
  2. 单张 < 20KB（谁再导成 PNG 就是 100~220KB，直接失败）；
  3. 老的 `.png` 名字必须不存在（避免「两份都留着、改了一处不生效」）。

## 2. 视觉等价性（编码质量）

对「原 PNG ↔ WebP q90 解码结果」做逐像素比较（同尺寸 960×540，不缩放）：

| 图 | 平均绝对差（R/G/B） | 最大单通道差 | PSNR |
|---|---|---|---|
| `splash_day` | 0.70 / 0.48 / 0.71 | **4** / 255 | 49.2 dB |
| `splash_night` | 0.36 / 0.40 / 0.63 | **6** / 255 | 50.8 dB |

平滑渐变没有出现色带或块状（4~6 的差值低于 8 位屏在暗部的可分辨量级），PSNR ≈ 50dB。

## 3. 设备实测（`emulator-5572`，`com.ecarx.cardesk` system 变体）

启动页只存在 100~950ms（冷启动 `splashFirstFrame=130ms`、`splashGone=1567ms`），
截图用「进程起来后立刻 `kill -STOP` 冻住 → 截图 → `kill -CONT`」抓取：

- **日间**：`CarDesk/App: 启动页配色 night=false resolveMs=5`，
  背景柔和渐变完整、圆环/「CarDesk」/「正在启动桌面…」/扫光条正常
  → `emu-verify/shots-r313/r313-01-splash-day.png`。
- **夜间**：`启动页配色 night=true`，深色渐变过渡平滑、无 banding
  → `emu-verify/shots-r313/r313-02-splash-night.png`（抓的是淡出中的一帧，
  所以底下透出桌面，但底图着色/过渡正常）。
- 两次 `logcat -b crash` 均 0 条；启动总时长与上一轮同量级（1.3~1.9s），底图解码不在关键路径上
  （`splashFirstFrame` 仍在 100~130ms）。

## 4. 结果

| | 上一版 4325 | 本轮 4326 | 变化 |
|---|---|---|---|
| APK | 3,141,407 B | **2,824,725 B** | **−316,682 B（−10.1%）** |
| `assets/` 解压后总计 | 334,848 B | **18,116 B** | −316,732 B |
| dex | 14 个，解压 6,761,660 B | 14 个，解压 6,761,664 B | 不变 |

累计：本轮之前的 4,168,917 B → 现在 2,824,725 B，**两轮共 −32.3%**。

## 5. 判据（复现用）

```bash
# 体积与内容
unzip -l dist/CarDesk-platform-ecarx-cardesk-*.apk | grep -E "splash|assets/car3d"
# 期望：splash 只有两条 .webp（6KB / 3.5KB），无 car3d

# 单元测试（含新增的 SplashAssetTest）
gradle testSystemUnitTest --offline --tests "*SplashAssetTest*"

# 设备：抓启动页帧（启动页存在不到 1 秒，必须冻结进程再截）
adb -s emulator-5572 shell "am force-stop com.ecarx.cardesk"
adb -s emulator-5572 shell am start -n com.ecarx.cardesk/com.cardesk.launcher.MainActivity
pid=$(adb -s emulator-5572 shell pidof com.ecarx.cardesk | tr -d '\r')
adb -s emulator-5572 shell kill -STOP $pid
adb -s emulator-5572 emu screenrecord screenshot /tmp/splash
adb -s emulator-5572 shell kill -CONT $pid
adb -s emulator-5572 logcat -d | grep 启动页配色
```

> 注：车机镜像里 `screencap`/`uiautomator` 不可用，截图统一走
> `adb emu screenrecord screenshot`（宿主侧落 PNG）。

## 6. 诚实的缺口 / 下一步

- **dex 现在是大头（2.79MB，占 APK 98%+）**：`isMinifyEnabled=false`、keep 规则 0 条，
  没跑 R8。要进 2MB 以内必须动它（估计省 0.8~1.2MB），但本项目大量反射调车机私有 API，
  需要 keep 规则 + 全量回归，风险最高，本轮未做。
- WebP 选择 q90 而非 q82/q75（还能再省 4~6KB，但收益可忽略、暗部色带风险反而上升），
  没有再往下压的必要。
- 夜间启动页的截图是淡出中的一帧（不是纯净首帧）：镜像里的 `am force-stop` 会被
  辅助服务立刻拉起，改 prefs 强制夜间会被进程回写覆盖，所以没抓到纯净帧。
  日间帧是纯净首帧，两者用的是同一份代码路径与同格式资产。
