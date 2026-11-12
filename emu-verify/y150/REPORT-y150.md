# 第150轮工作报告

> 项目：CarDesk（Android 车机桌面）· `/Users/roy/Documents/desktopapp/cardesk`
> 设备：emulator-5560（arm64，1920×1080，density 160）
> 构建：`:app:assembleSystem -PcardeskPackage=com.ecarx.cardesk` → `app/build/outputs/apk/system/app-system.apk`（2.58 MB，17:41）

---

## 一、本轮需求

> **「把设置面板里所有啰嗦的功能描述，需求解释的文字去除」**

一句话：**设置页里那些「把标题再说一遍」的说明文字，全部删掉。**

---

## 二、结果一览

| 指标 | 精简前 | 精简后 | 变化 |
|------|--------|--------|------|
| 开关副标题（功能描述） | 27 条 | **5 条** | 删 22 |
| `note()` 说明 / 需求解释 | 37 条 | **19 条** | 删 18 |
| **说明文字合计** | **64 条** | **24 条** | **删 40（-62.5%）** |
| 保留的说明性质 | — | 状态 / 条件 / 风险 / 互斥 / 语义 / 选项 / 法律 | 7 类 |

留下的 5 条开关副标题、19 条 note 全部有据可查，不是漏网之鱼。

---

## 三、删的判定标准

核心原则一句话：**标题能推出来的，删；标题推不出来的，留。**

| 删掉（复述型） | 留下（推不出来） |
|----------------|------------------|
| 「开机后进入本桌面」 | 「与隐藏 Dock / 下移 Dock 互斥」 |
| 「桌面启动时自动锁定这个来源」 | 「开启后保持 ISS 关闭；关闭则不改动车机逻辑」 |
| 「只隐藏桌面自己画的底部控制栏」 | 「将关闭所有应用并重新启动系统，约需 40 秒。行驾中请勿操作。」 |
| 「在字外面套一圈对比色」 | 「本应用源代码完整公开在上述仓库……」 |

### 为什么「互斥」「语义」「风险」「法律」必须留

- **互斥**：`增强导航栏` 和 `下移 Dock` 是两个独立开关，标题里没有任何信息说明它俩会打架。用户开一个发现另一个自动关掉，不写出来就是「桌面出 bug」。
- **语义差异**：`开启后保持 ISS 关闭；关闭则不改动车机逻辑` —— 标题只说「开机后自动关闭启停」，但「关掉这个开关」到底是「不生效」还是「反向操作」，语义完全不同。
- **风险**：`重启车机` 会关掉所有应用重启系统 —— 这是**行车安全**提示，不属于「啰嗦的功能描述」。
- **法律**：开源协议与授权声明，有法律意义。
- **状态**：诊断页的 `平台：…`、日志页的 `未授予…`、播放状态等是**运行时状态**，不是功能描述 —— 状态会变，说明文不会。
- **选项说明**：歌词布局的 `上一句 / 当前句 / 下一句`、氛围灯预设的 `颜色跟着频谱缓慢换` —— 删了用户就分不清这几个选项有什么区别，属于必要副标题。

---

## 四、怎么保证「删干净了，而且不会再加回来」

### 4.1 关键设计：给说明行打标记，从视图树里挑

只靠「源码里有没有这句话」来查，有两个洞：

1. 查不出「**一共还剩几条**」；
2. 查不出「**换个说法又写回去**」了。

所以给两类说明行都打了 tag：

```kotlin
// SettingsOverlay.kt
private fun note(text: String): TextView {
    val tv = Ui.text(context, text, 22f, pal.textDim).apply {
        setLineSpacing(10f, 1f)
        tag = NOTE_MARK          // ← 第150轮：说明行打标记
    }
    ...
}

// row() 里给副标题打标记
Ui.text(context, subtitle, 19f, pal.textDim).apply { tag = SUBTITLE_MARK }

companion object {
    val NOTE_MARK = Any()
    val SUBTITLE_MARK = Any()
}
```

测试顺着视图树把带标记的 TextView 全捞出来，逐条对白名单核。

### 4.2 新增测试 `SettingsCopyTest`（5 条）

| # | 用例 | 作用 |
|---|------|------|
| 1 | `说明文字只能剩白名单里那几类` | 白名单 31 条前缀，逐条核；白名单外的一律判红 |
| 2 | `啰嗦文案不许再出现` | 本轮删掉的 **40 段**文案进黑名单，再写回去就红 |
| 3 | `说明文字总数不许多于白名单条数` | 防止「换个说法批量加回来」 |
| 4 | `开关副标题只剩互斥和语义那几条` | 精确断言副标题**恰好 5 条** |
| 5 | `说明行的标记还在_不然检查就形同虚设` | **专门防假通过**（见 4.4） |

第 5 条是这轮最要紧的一条。下面单独说。

### 4.3 覆盖了真机上点不到的那一页

「开源与声明」页（法律声明所在）的入口按钮被底部 Dock 挡住了，真机点不到。
但**那一页的文案照样要有人盯**，所以测试的页面清单里额外加了 `about`：

```kotlin
private fun allPageKeys(): List<String> {
    ...
    return rows.keys.toList() + "about"   // ← 导航树里没有，但路由在
}
```

### 4.4 踩过的坑：检查会「假通过」

第 3 条反向验证（把 `tag = NOTE_MARK` 摘掉）实测出来的问题：

> 一旦标记被摘掉，用例 1/2/3 会「**什么都没查到**」，于是全部**照样绿** ——
> 检查形同虚设，比没检查更危险（它给人一种「已经验过了」的错觉）。

所以单独加了用例 5：断言 notes ≥ 8、subtitles ≥ 3，标记被摘就必红。
**没有这条，前三条用例随时可能变成空转。**

---

## 五、反向验证（5 项，全部必红）

脚本：`tools/verify_y150_reverse.sh`（改坏 → 断言必红 → 还原 → 恢复全绿）

| # | 改坏方式 | 预期 | 实测 |
|---|----------|------|------|
| 基线 | — | 0 红灯 | `RED=0 TOTAL=5` ✅ |
| 1 | 给「桌面开机启动」加回副标题 `"开机后进入本桌面"` | 必红 | `RED=3` ✅ |
| 2 | 加回一条被删的功能解释 `note(...)` | 必红 | `RED=2` ✅ |
| 3 | 把 `tag = NOTE_MARK` 摘成 `null`（假通过陷阱） | 必红 | `RED=1` ✅ |
| 4 | 把 `tag = SUBTITLE_MARK` 摘成 `null` | 必红 | `RED=2` ✅ |
| 5 | 给「沉浸系统导航栏」加回互斥副标题 | 必红 | `RED=2` ✅ |
| 还原 | — | 0 红灯 | `RED=0 TOTAL=5` ✅ |

### 踩过的坑：反向项写成「编译失败」等于没验

反向 5 最初写成「在 `switchRow(...)` 后面接一行」，
结果把原来的 `{ }` 块孤立掉 → **编译直接失败** → 单测一条都跑不了（`TOTAL=0`）。
表面上「红了」，实际上把关的只是「能编译」。

改成**改实参**（保持语法合法）后，`TOTAL=5` 且精确命中 2 条断言，反向项才真正成立。

---

## 六、回归

```
testSystemUnitTest   tests=2068  failures=0  errors=0
testDebugUnitTest    tests=2068  failures=0  errors=0
compileSystemSources  BUILD SUCCESSFUL
```

新增的 `SettingsCopyTest` 5 条在 system / debug 两个变体里都在，都绿。

---

## 七、真机验证（emulator-5560）

### 7.1 精简前后对比 —— 同一页

| 精简前（第149轮 `emu-verify/y149/20_settings_navtree_new.png`） | 精简后（本轮 `01_settings_entry.png`） |
|---|---|
| 标题下挂着一串解释 | 标题下干干净净 |
| 「开机后进入本桌面」 | （没了） |
| 「开机后将导航投送到仪表屏」 | （没了） |
| 「开机后自动检测一次」 | （没了） |
| 「端口 18765..18775」 | （没了） |

### 7.2 逐页确认（删干净的）

| 截图 | 页面 | 效果 |
|------|------|------|
| `01_settings_entry.png` | 启动与开机 | 开关只剩标题 |
| `02_settings_media.png` | 媒体源 | 「启动后自动播放」下的一句话没了，**条件提示还在** |
| `03_settings_lyric.png` | 歌词 | 各选项的说明保留（这是选项说明，不是功能描述） |
| `04_settings_dock.png` | Dock 与导航栏 | 「隐藏原车状态栏」「隐藏原车 Dock」下的一句话没了 |
| `05_settings_cards.png` | 桌面卡片 | 「显示这张卡」下的一句话没了 |
| `06_settings_ambient.png` | 氛围灯 | 预设行下面的解释没了 |
| `09_settings_p360.png` | 360 环影 | 「启用 360 环影增强」下的一句话没了 |

### 7.3 该留的一条没少（这是重点）

| 截图 | 保留内容 | 性质 |
|------|----------|------|
| `08_settings_dock_mutex.png` | 「与隐藏 Dock / 下移 Dock 互斥」「与增强导航栏互斥」 | 互斥 |
| `07_settings_ambient_semantic.png` | 「关：只看预设；开：展开全部滑杆（当前参数不变）」 | 语义 |
| `16_settings_cards_range.png` | 「续航卡自适应时会吃掉行尾剩余宽度；小/中/大是固定宽度」 | 语义 |
| `17_settings_iss_semantic.png` | 「开启后保持 ISS 关闭；关闭则不改动车机逻辑」+ 状态「启停开着」 | 语义 + 状态 |
| `12_settings_restart_risk.png` | 「将关闭所有应用并重新启动系统，约需 40 秒。行驾中请勿操作。」 | 风险 |
| `11_settings_logs_state.png` | 通知权限状态、导出目录、平台信息 | 状态 |

**5 条开关副标题在真机上全部逐条核对到位**。

---

## 八、顺带修掉的一个小毛病

设置页左栏底部的「开源与声明」入口 + VIN 行被底部 Dock 挡住了（真机点不到）。
这是**既有布局问题**，跟本轮文案无关，所以没动它的布局 ——
但改用测试覆盖 `about` 页，保证那一页的法律声明没人在管（见 4.3）。

---

## 九、改动清单

| 文件 | 改动 |
|------|------|
| `app/src/main/java/com/cardesk/launcher/ui/SettingsOverlay.kt` | 删 40 段说明文字；`note()` / 副标题打 `NOTE_MARK` / `SUBTITLE_MARK`；新增两个标记常量；页面默认标题「启动切换」→「桌面设置」 |
| `app/src/test/java/com/cardesk/launcher/ui/SettingsCopyTest.kt` | 新增，5 条用例（白名单 + 黑名单 + 计数 + 精确断言 + 防假通过） |
| `tools/verify_y150_reverse.sh` | 新增，5 项反向验证 |

### 删「24 小时制 · 点按箭头调整」时踩的坑

这一行是 `Ui.text(...)` 直接文本，删掉之后**把 `sheet.addView(...)` 的实参数量撑少了一个**，
编译报 `Type mismatch: inferred type is LinearLayout.LayoutParams but View! was expected`。

修法：整段删掉 `addView`，并把 `wheels` 的 `topMargin` 从 8 调到 20 补回间距。

> **教训**：删一行**参数列表里的**调用，和删一整行 `addView` 指令，后果完全不同。
> 批量删文案必须**每条都过一遍编译**。

---

## 十、设备状态（已还原）

| 项 | 值 | 状态 |
|----|----|------|
| `pip_app` | `com.autonavi.amapautp.yh` | ✅ 保持 |
| `nav_cover_backdrop_enabled` | `true` | ✅ 保持 |
| `auto_launch_media_app` | 未写入 | ✅ 回默认（关） |
| `default_music_source_v1` | 未写入 | ✅ 回默认 |
| `desk_overlay_w/h/alpha` | 未写入 | ✅ 回默认（100/50/88） |
| 高德地图 | 未被禁用 | ✅ |
| QQ 音乐车机版 | 3.14.0.4 保留安装 | ✅ |

本轮只改文案，不改任何 pref；装机后仅浏览各页，未切换任何开关。

---

## 十一、截图索引

```
emu-verify/y150/
├── 01_settings_entry.png            启动与开机（精简后，与 y149 同页对比）
├── 02_settings_media.png            媒体源
├── 03_settings_lyric.png            歌词（选项说明保留）
├── 04_settings_dock.png             Dock 与导航栏
├── 05_settings_cards.png            桌面卡片
├── 06_settings_ambient.png          氛围灯
├── 07_settings_ambient_semantic.png 氛围灯（自定义参数语义副标题）
├── 08_settings_dock_mutex.png       Dock（两条互斥副标题）
├── 09_settings_p360.png             360 环影
├── 10_settings_nav_scrolled.png     左栏导航（滚到底）
├── 11_settings_logs_state.png       诊断日志（状态类说明保留）
├── 12_settings_restart_risk.png     重启车机（风险警告保留）
├── 13_settings_nav_bottom.png       左栏导航底部
├── 14_settings_cards_slots.png      桌面卡片（行程卡 / 车门车窗）
├── 15_settings_cards_slots2.png     桌面卡片（空调 / 胎压）
├── 16_settings_cards_range.png      桌面卡片（续航卡语义副标题）
├── 17_settings_iss_semantic.png     启停控制（ISS 语义 + 状态）
├── 90_restored_desktop.png          还原后的桌面
└── REPORT-y150.md
```
