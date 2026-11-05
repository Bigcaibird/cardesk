# 4214 导航页画中画不显示应用 —— 模拟器验证

环境：AVD（Android 10 / SDK 29，arm64-v8a，**分辨率改成 1920×968 对齐真车**），
被承载应用用的是**真车上同一个高德** `com.autonavi.amapautp.yh` 9.5.0.600013。

安装包：
- 修复版 `dist/CarDesk-platform-ecarx-cardesk-4214.apk`
  （平台证书 SHA-256 `c8a2e9bc…92ab8`，APK SHA-256 `1085fb4a…16e1`，2213278 字节）
- 对照版 `CarDesk-platform-ecarx-cardesk-4213.apk`（有 bug 的那一版，同一张平台证书）

判定标准：logcat 里 `desktopReady` 分段在不在 + `画中画显示` 的 mode 是不是 `embedded`
+ 截图里画中画区域是不是真的在渲染应用。

---

## 1. 现象复现：4213（用户反馈的那版）

冷启动装 4213（`pip_app` 已经存成高德），**画中画停在占位图**。

截图 `y29-04-4213-buggy-placeholder.png`
- 导航页左侧画中画区域显示「⊕ 请选择应用」占位，**不是**高德。

日志 `y29-logcat-4213-buggy.txt` 里的启动分段：

```
onCreate window splashFirstFrame uiBuilt launcherCreated launcherFirstFrame
launcherReady servicesBegin servicesDone servicesStarted launchServicesStarted
```

→ **没有 `desktopReady`**。

```
$ grep -c "启动分段：desktopReady" y29-logcat-4213-buggy.txt
0
$ grep -cE "刷新画中画|画中画显示|ActivityView 嵌入" y29-logcat-4213-buggy.txt
0
```

`刷新画中画` / `画中画显示` / `ActivityView 嵌入` **一条都没有** ——
承载根本没被触发过。

这与用户车上的诊断包**完全一致**：
`summary.txt` 的「本次启动」分段列表里查不到 `desktopReady`，
日志里只有「画中画占位点击（选应用）」，没有「画中画显示」。

## 2. 修复后：4214

### 2.1 冷启动自动承载（关键，不需要用户任何操作）

`pip_app` 已经是高德，冷启动桌面：

`y29-logcat-4214-fixed.txt`

```
启动分段：desktopReady processMs=3854 onCreateMs=1907 bootMs=47201908
启动完成 totalMs=3854 ... launchServicesStarted=1608 desktopReady=1907
桌面就绪后开始唤起高德相关动作
开始承载画中画 reason=桌面就绪
刷新画中画 pkg=com.autonavi.amapautp.yh pkg2= dual=false
ActivityView 嵌入成功 pkg=com.autonavi.amapautp.yh
[EVT] 画中画显示 pkg=com.autonavi.amapautp.yh app=Amap Auto mode=embedded role=主画
ActivityView 启动承载应用 pkg=com.autonavi.amapautp.yh api=pendingIntent
```

VirtualDisplay 也确实起来了：

```
Display device added: ActivityViewVirtualDisplay@… 1287 x 867 … state ON, owner com.ecarx.cardesk (uid 1000)
ActivityView 同步 VirtualDisplay 尺寸 w=1287 h=867
```

截图 `y29-05-4214-pip-hosting-amap.png`（最终 4214 包）
- 导航页左侧画中画里**高德在真实渲染**：地图底图、搜索框、「设置家 / 设置公司 / 周边服务」卡片、
  罗盘、「50 米」比例尺、右下角高德地图水印。

截图 `y29-03-4214-pip-hosting-amap.png`（第一次装的 4214，画面一致）

### 2.2 手动选应用（用户路径）

- `y29-01-empty-pip-placeholder.png`：没选应用时画中画是占位图（正常）。
- `y29-02-app-picker.png`：点占位图 → 「选择画中画应用」对话框。
- `y29-02b-after-pick.png`：选「Amap Auto」→ 立刻 `mode=embedded`。

## 3. A/B 对照结论

| 版本 | 启动分段里有 `desktopReady` | `画中画显示` | 画中画区域 |
|---|---|---|---|
| 4213（有 bug） | **没有**（0 次） | 无 | 「请选择应用」占位图 |
| 4214（修复后） | 有（正常路径，1907ms） | `mode=embedded` | 高德真实渲染 |

同一台模拟器、同一份 `pip_app`（高德）、同样 1920×968 分辨率、
同样的冷启动流程（force-stop → `am start -W`）—— **只有代码不同**。

## 4. 回归判定

- 进程 pid 全程不变（没有崩过）；
- logcat 无 `FATAL EXCEPTION` / `Fatal signal` / ANR；
- 画中画区域有真实应用画面，不是黑屏也不是空白。

## 5. 证据清单

| 文件 | 内容 |
|---|---|
| `y29-01-empty-pip-placeholder.png` | 没选应用时的占位图（正常形态） |
| `y29-02-app-picker.png` | 「选择画中画应用」对话框 |
| `y29-02b-after-pick.png` | 手动选高德后立刻嵌入 |
| `y29-03-4214-pip-hosting-amap.png` | 4214 冷启动自动承载（高德在渲染） |
| `y29-04-4213-buggy-placeholder.png` | **4213 复现：只有占位图** |
| `y29-05-4214-pip-hosting-amap.png` | 最终 4214 包冷启动自动承载（高德在渲染） |
| `y29-logcat-4213-buggy.txt` | 4213 启动分段（**无 desktopReady**） |
| `y29-logcat-4214-fixed.txt` | 4214 启动分段 + 画中画承载链 |
