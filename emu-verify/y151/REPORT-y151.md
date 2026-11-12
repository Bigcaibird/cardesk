# 第151轮工作报告

> 项目：CarDesk（Android 车机桌面）· `/Users/roy/Documents/desktopapp/cardesk`
> 设备：emulator-5560（arm64，1920×1080，density 160）
> 构建：`tools/release.sh` → `dist/CarDesk-platform-ecarx-cardesk-4245.apk`（2.38 MB，平台签名）

---

## 一、本轮两条需求

> 1. **媒体来源改成下拉选择**
> 2. **设置里 dock 栏快捷应用添加的按钮压到了下一个选项，修复这个问题**

| # | 需求 | 状态 | 关键证据 |
|---|------|------|----------|
| ① | 媒体来源改成下拉选择 | ✅ | 真机：收起时当前值「通用音乐 ⌄」写在行上；展开后 9 个来源一次列全、选中项高亮、箭头转 180°；选中 QQ 音乐后「通用音乐」那条提示自动收起 |
| ② | Dock 快捷应用按钮压到下一项 | ✅ | 真机：「配置 Dock 快捷应用…」按钮底边与「沉浸系统导航栏」从 **6px 贴死** → **20px 空隙**；单测量过布局，断言按钮底边到下一项 ≥ 12px |

---

## 二、需求①：媒体来源改下拉

### 2.1 为什么原来是 chip 行不行

「默认来源」原来是一排**分段控件**（chip），三个问题：

1. 9 个选项在 1920 宽下**挤成两行还要横向滚动**；
2. 选中项（默认「通用音乐」）在**中间**，得靠 `post` 把它滚到可视区才看得见；
3. 打开设置页**一眼看不出当前选的是谁** —— 选中那个 chip 淹没在一排里。

### 2.2 实现：新增通用下拉组件 `dropdownRow`

放在 `SettingsOverlay` 里，是**可复用**的行控件（不是媒体源专用）：

```
行：  「默认来源」                    [ 通用音乐 ⌄ ]
                                    ↓ 点一下
      ┌──────────────────────────────────┐
      │ QQ音乐                           │
      │ 网易云音乐                        │
      │ 酷狗音乐                          │
      │ 酷我音乐                          │
      │ 汽水音乐                          │
      │ 多媒体                            │
      │ 通用音乐        ← 选中（强调色底+字）│
      │ 蓝牙音乐                          │
      │ USB 音乐                          │
      └──────────────────────────────────┘
```

- **收起时只占一行**，当前值就写在行上；
- **展开时 9 个选项一次列全**，当前选中项高亮（强调色底 + 强调色字 + 加粗）；
- 点行 / 点值 → 展开收起，箭头跟着转 180°；
- 点选项 → 选中、收起、落盘 + 回调 `onDefaultMediaSource`。

### 2.3 为什么是「原地展开」而不是 PopupWindow

设置页自己就是盖在桌面上的一层浮层，再往上盖一层浮层，层级和裁剪都要重新踩一遍坑
（这个项目至今**没用过** PopupWindow）。原地展开最稳 ——
展开时下面的内容被推下去，设置页本来就能滚。

实现上，面板是 `body` 里紧跟这一行的子 view，**收起时 `GONE`**（不占空间），
展开时 `VISIBLE` —— 这样不用在展开时挪 view 的位置，也就不会有「展开后插错行」的坑。

### 2.4 顺手修掉的一个小毛病

原来选中「通用音乐」以外的具体 App 后，那条条件提示
（「要先在上面把默认来源选成具体的 App …」）**不会消失** ——
因为 `note` 是建页时写一次的。现在下拉的回调里会顺手把它收起来。

---

## 三、需求②：按钮压住下一项

### 3.1 根因

`actionButton` 的布局参数只给了 `topMargin`：

```kotlin
body.addView(tv, LinearLayout.LayoutParams(-2, 64).also {
    it.topMargin = 18      // ← 只有上面有间距
})                          // ← 底边直接贴住下一行
```

而下一行是 `row()`，它的文字是**顶对齐**的（`textBox` 的 `Gravity.TOP`），
所以按钮底边 → 下一行标题，间距**正好是 0**。
「Dock 快捷应用」那个「配置 Dock 快捷应用…」按钮后面紧跟着
「沉浸系统导航栏」，看上去就**压在了上面**。

### 3.2 修法

```kotlin
it.bottomMargin = 24
```

取 24 而不是跟 `topMargin` 对称的 18：下一行的文字是顶对齐的，
间距小于行间距 [ROW_GAP]（37）仍然会显得挤。

### 3.3 同一个毛病的另一处

新加的下拉面板也是「贴住下一行」—— 展开后选项列表的底边直接压在
「启动后自动播放」上。面板也给了 `bottomMargin = 20`
（`GONE` 时不生效，收起状态不占任何空间）。

---

## 四、怎么保证不退回

### 4.1 新增 4 个标记，测试从**视图树**里挑东西

| 标记 | 标在谁身上 | 用来断言 |
|------|-----------|----------|
| `DROPDOWN_VALUE_MARK` | 下拉行的「当前值」 | 当前值显示的确实是当前选中的来源 |
| `DROPDOWN_OPTION_MARK` | 展开后的每个选项 | 9 个来源一个不少、顺序一致 |
| `CHIP_SEG_MARK` | chip 分段控件 | 媒体源页**已经没有** chip 行了 |
| `ACTION_BUTTON_MARK` | 操作按钮 | 按钮底边留了间距（并且**量了真实布局**） |

> 为什么不能只靠源码断言：源码能查「某句话在不在」，查不出「还剩几个」，
> 也查不出「换个写法又变回去了」。视图树这层能真查。

### 4.2 新增测试 `SettingsDropdownTest`（8 条）

| # | 用例 | 作用 |
|---|------|------|
| 1 | `媒体源页用下拉_不再有 chip 行` | 有下拉行 + **没有** chip 段控件 |
| 2 | `下拉显示的就是当前选中的来源` | 设 `qq` → 行上显示「QQ音乐」 |
| 3 | `下拉列出了全部 9 个来源` | 数量与顺序都跟 `MediaSources.LIST` 一致 |
| 4 | `点选项就换源_而且落盘` | `performClick` → prefs 变了 + 回调触发 |
| 5 | `选了具体App之后通用音乐那条提示要收起来` | 提示的可见性跟着选择走 |
| 6 | `按钮下面必须留间距_不然会压住下一项` | 所有按钮 `bottomMargin > 0` |
| 7 | `按钮底边到下一项要真的有空隙_不是零距离` | **真量一遍布局**：按钮底 → 下一项顶 ≥ 12px |
| 8 | `每一页的按钮底下都有间距` | 遍历导航树所有页，逐个查 |

第 7 条是关键：`bottomMargin > 0` 只说明「设了」，**够不够**要真摆一遍布局才知道。
测试里手动 `measure` + `layout` 正文容器，拿到子 view 的真实 `top` / `bottom`。

---

## 五、反向验证（5 项，全部必红）

脚本：`tools/verify_y151_reverse.sh`

| # | 改坏方式 | 预期 | 实测 |
|---|----------|------|------|
| 基线 | — | 0 红灯 | `RED=0 TOTAL=8` ✅ |
| 1 | 把「默认来源」整段换回 chip 行 | 必红 | `RED=5` ✅ |
| 2 | 下拉只列前 8 个来源 | 必红 | `RED=1` ✅ |
| 3 | 按钮底部间距改成 0 | 必红 | `RED=3` ✅ |
| 4 | 点选项不落盘 | 必红 | `RED=1` ✅ |
| 5 | 选了具体 App 之后提示不收起 | 必红 | `RED=1` ✅ |
| 还原 | — | 0 红灯 | `RED=0 TOTAL=8` ✅ |

### 踩过的坑：反向 1 不能只改函数名

`chipRow` 与 `dropdownRow` 的签名不同（一个是选中项**文案** `String`、一个是**下标** `Int`；
回调一个是 `(String) -> Unit`、一个是 `(Int) -> Unit`），
只把 `dropdownRow(` 改成 `chipRow(` 会**编译失败** → 单测一条都跑不了（`TOTAL=0`），
那条反向项就只剩「编译没过」在把关。

所以反向 1 用 Python **整段替换**（old/new 全文），保证语法合法、断言精确命中 5 条。

---

## 六、回归

```
testSystemUnitTest   tests=2076  failures=0  errors=0
testDebugUnitTest    tests=2076  failures=0  errors=0
```

（比上轮 2068 多了 8 条 = 本轮新增的 `SettingsDropdownTest`。）

---

## 七、真机验证（emulator-5560，装的是 4245 正式包）

| 截图 | 内容 |
|------|------|
| `01_media_dropdown_closed.png` | 媒体源页：默认来源变成「通用音乐 ⌄」下拉行；那条条件提示在 |
| `02_media_dropdown_open.png` | 展开：9 个来源一次列全，「通用音乐」高亮，箭头转 180° |
| `03_media_picked_qq.png` | 选中 QQ 音乐：当前值变了，**通用音乐那条提示自动收起** |
| `04_dock_button_gap.png` | 需求②：按钮底边不再压着「沉浸系统导航栏」 |
| `05_boot_button_gap.png` | 启动与开机页不受影响 |
| `06_daynight_buttons.png` | 日夜模式页两个连续按钮的间距（改前是 0，现在是 24） |

---

## 八、出包

走项目自带的一键出包流程 `tools/release.sh`（不是手动 gradle）：

- 版本号自动 +1：**4244 → 4245**（`1.0.1149-system`）
- rsync 到**全新临时目录干净全量构建**（避免增量构建在 APK 里留下对齐填充空隙）
- 全量单测 + 出包 + **平台证书指纹校验**（不对就拒发）+ SHA-256
- 自动重写 `dist/README.md` 与 R2 清单 `dist/cardesk-latest.json`

| | |
|---|---|
| 文件 | `dist/CarDesk-platform-ecarx-cardesk-4245.apk` |
| 包名 | `com.ecarx.cardesk` |
| versionCode / versionName | **4245** / `1.0.1149-system` |
| 签名 | AOSP platform（`c8a2e9bc…ab8`） |
| SHA-256 | `2ac58846210064272cee33363496bece3b66e86877868d964d72987661f3fd53` |

开发调试包另出：`dist-dev/CarDesk-debug-1.0.1149-com.cardesk.launcher.apk`
（debug 签名，包名 `com.cardesk.launcher`，**不能替换车机桌面**）。

---

## 九、设备状态（已还原）

| 项 | 值 | 状态 |
|----|----|------|
| `default_music_source_v1` | 验证时选成 `qq`，验证后**已删除该键** | ✅ 回默认（通用音乐） |
| `pip_app` | `com.autonavi.amapautp.yh` | ✅ 保持 |
| `nav_cover_backdrop_enabled` | `true` | ✅ 保持 |
| `auto_launch_media_app` | 未写入 | ✅ 回默认（关） |
| `desk_overlay_w/h/alpha` | 未写入 | ✅ 回默认 |
| 高德地图 | 未被禁用 | ✅ |
| QQ 音乐车机版 | 3.14.0.4 保留安装 | ✅ |

改 prefs 用 `awk` 生成到 `/data/local/tmp` 再 `cat >` 写回 —— `mv` 会把属主改成 root。

---

## 十、改动清单

| 文件 | 改动 |
|------|------|
| `app/src/main/java/com/cardesk/launcher/ui/SettingsOverlay.kt` | 新增 `dropdownRow` 组件；「默认来源」改用下拉；`actionButton` 加 `bottomMargin = 24`；下拉面板加 `bottomMargin = 20`；新增 4 个标记常量 |
| `app/src/test/java/com/cardesk/launcher/ui/SettingsDropdownTest.kt` | 新增，8 条用例 |
| `tools/verify_y151_reverse.sh` | 新增，5 项反向验证 |
| `tools/release.sh` | rsync 多排除一个 `dist-dev`（开发包目录不进构建） |
| `version.properties` | 4244 → 4245 |
