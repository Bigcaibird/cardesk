# 设置页「无效设置项」审计

> 需求：「过一遍所有的设置项，统计一下有多少设置项是实际没有功能的」
> 对象：`app/src/main/java/com/cardesk/launcher/ui/SettingsOverlay.kt`（4436 行，25 个页面）
> 方法：静态可达性分析（键 → 访问器 → 消费者）+ 逐项追进被调用的方法体

---

## 一、总账

| 项 | 数量 |
|---|---|
| 设置页页面 | 25 个（5 组） |
| 交互项合计 | **154 项** |
| ├ 偏好值类（开关 / 滑杆 / chip / 按键槽） | 89 项（switch 28 / slider 35 / chip 21 / keySlot 5） |
| └ 动作类（按钮 / 选择器 / 时间选择） | 65 项（actionButton 41 / pickerRow 7 / cardChoice 3 / timePickerRow 2 / dropdownRow 1 / pillButton 1 / 行内动作行 10） |
| **确定没有功能** | **12 项**（全部是偏好值类） |
| 存疑（当场不生效、重启后生效） | **1 项**（动作类） |
| 确认有效 | 141 项 |

无效率：**12 / 154 ≈ 7.8%**；把存疑项算进去是 13 / 154 ≈ 8.4%。

动作类 65 项里**没有一项是完全空转的**——全文检索 `敬请期待|即将支持|暂不支持|待支持|未实现|TODO` 零命中。

---

## 二、判定口径

「没有功能」= **设置项把值写进了 SharedPreferences（或调用了空实现），但没有任何代码读它 / 用它**。

判据不是看代码「像不像实现了」，而是逐项追到消费端：

1. 从设置页抽出全部偏好键字面量；
2. 在 `Prefs.kt` 里建立「键 → 访问器」映射（**属性和函数都要算** ——
   `ambientMaxDeltaETenths` 这个属性没人读，但 `ambientMaxDeltaE()` 这个派生函数被
   `AmbientLightController` 读了，算活的。第一版只查属性，误判了 9 个氛围灯参数）；
3. 计算每个访问器的可达键集合，再查它在 `Prefs.kt` / `SettingsOverlay.kt` 之外的使用点；
4. 零使用点 ⇒ 只写不读 ⇒ 无效；
5. 再人工复核每个「零使用点」，排除「只控界面显隐」这类**合法**的消费者
   （例：`ambient_custom_mode` 只用于决定滑杆区显不显示，它是有功能的开关，不算无效）。

盲区已排除：全仓另有一批按字符串键动态读偏好的写法（`PipRatioStore`、`ScheduledTasks`、
`BottomCardOrder`、`AdbBridge` 等），但它们用的都是**另外的键常量**；
本报告的 12 个键在全仓的字面量出现次数都是 2（都在 `Prefs.kt` 的 getter/setter 里），
**任何其它文件里都不存在这些键的字面量**，也没有反射读取 → 判定是确定的。

---

## 三、确定无效的 12 项

### 3.1 启动与开机页（2 项）

| 设置项 | 偏好键 | 现象 |
|---|---|---|
| 桌面开机启动 | `desktop_boot_autostart` | 开关能切、值能存、**开机流程没人读它** |
| 自动导航投屏 | `start_navi_cast_enabled` | 开关能切、值能存，**启动时没有任何投屏动作** |

- `desktop_boot_autostart`：`BootReceiver.onReceive()` 只读 `prefs.adbAuto` 和
  `prefs.adbWirelessAuto`（开机打开调试开关 / 无线端口），**不读 `bootAutostart`**。
  全仓该键字面量只有 2 处，都在 `Prefs.kt`。
  （背景：桌面是 HOME 车机本来就随系统起，这个开关大概率是早期「启动器选择」时代的遗留。）
- `start_navi_cast_enabled`：`BootReceiver` / `MainActivity` 都不引用它。

### 3.2 360 环影页（5 项）

| 设置项 | 偏好键 |
|---|---|
| 倒车视角 | `camera360_reverse_view_v2` |
| R→D 视角 | `camera360_rd_view_v2` |
| D 档视角 | `camera360_d_view_v2` |
| 转向灯视角 | `camera360_lamp_view_v2` |
| N/P 关闭环影 | `camera360_np_close_v2` |

同页的「启用 360 环影增强」「视角延迟」「按键视角」三项是**活的**：
实现侧 `LauncherView.kt:2125-2135` 只有一条通路 ——
`VehicleController.schedule360View(prefs.camera360KeyView, prefs.camera360DelayMs)`。
也就是说：**一次只切一个视角（按键视角），其余 5 个视角开关存了没人用**。

### 3.3 歌词页 · 导航栏歌词槽字号（4 项）

| 设置项 | 偏好键 |
|---|---|
| 收起·主歌词 | `desk_desk_compact_main_sp` |
| 收起·副歌词 | `desk_desk_compact_sub_sp` |
| 展开·主歌词 | `desk_desk_expanded_main_sp` |
| 展开·副歌词 | `desk_desk_expanded_sub_sp` |

歌词槽的实际绘制在 `ui/shell/NavSlotBarView.kt:459-483`，字号是**写死的**：
`text.textSize = 12f * u`（当前行）/ `10f * u`（下一行），跟偏好没有任何关系。

同页注释还留着一条同类记录（第 32 轮）：`desk_desk_page3_main_sp` /
`desk_desk_page3_sub_sp`（第三页全屏歌词字号）因为画面没了，设置项已移除、
键留在盘上——本报告把这两个键也一并核过，它们**已经不是设置项**，不计入。

### 3.4 车辆信息页（1 项）

| 设置项 | 偏好键 |
|---|---|
| 胎压数字字号 | `desk_tpms_value_sp` |

胎压数值的绘制里没有任何地方读这个键；数字字号是渲染时写死的。

---

## 四、存疑 1 项

### 「恢复默认 15 槽位」（`SettingsOverlay.kt:2986`，Dock 与导航栏页）

```kotlin
actionButton("恢复默认 15 槽位") {
    NavSlots.save(prefs, NavSlots.defaults())
    Logx.event(LogTags.SETTING, "恢复默认 15 槽位")
    onApply?.invoke()
}
```

- 持久化副作用是真的（`NavSlots.save()` 落盘，重启后生效）；
- 但**没有把改动推给正在显示的导航栏**：`onApply` → `LauncherView.onSettingsApplied()`
  只在 `builtEnhancedNav != prefs.enhancedNav || builtNavStyle != prefs.navStyle`
  时才 `build()` 重建，而本按钮不改这两个键 ⇒ **不重建**；
  也没有像同页「编辑槽位…」那样同步 `navSlots` / `slotsBar?.slots`；
  连本页都不重画（没有 `select(selectedKey)`），按钮上方「编辑槽位（N 项）」的 N 也不变。

对照同页兄弟按钮（`LauncherView.kt:1644-1649`）：

```kotlin
dialog.onChange = { list ->
    navSlots = list.toMutableList()
    NavSlots.save(prefs, list)
    slotsBar?.slots = list      // ← 一键重置缺的就是这句
    refresh()
}
```

**用户现象**：点了之后导航栏纹丝不动，得切一次「导航栏样式」或重启才生效。
归为「当场无效、重启后生效」。

---

## 五、两个看着像空转、实则正常的（避免误报）

1. `SettingsOverlay.kt:3401` `actionButton("启动局域网传输", primary = true) {}` —— lambda 确实是空的，
   但 3442 行用 `toggle.setOnClickListener { … }` **覆盖**了它，
   实际点下去会启停真实的 HTTP 服务（`LanTransferServer.kt:140/201`）。
2. `SettingsOverlay.kt:351` `setOnClickListener { }` —— 设置面板底板，
   用来**吞掉**点击防止误触发 `onClose`，不是设置项。

---

## 六、复核过、确认有效的敏感项（防误报记录）

| 设置项 | 消费端 | 结论 |
|---|---|---|
| 氛围灯自定义参数 | 只用于控制滑杆区显隐 | 有效（有可见效果） |
| 氛围灯 9 个参数（灵敏度/色差上限/空闲呼吸…） | `AmbientLightController` 经**派生函数**读取 | 有效 |
| 平台判定 | `Platforms.detect(context, prefs.platformOverride)` + `VehicleController.reconnect` | 有效 |
| 自动切换驾驶模式 | `BootReceiver` | 有效 |
| 启停控制 | `VehicleController.setIssEnabled` 真写车 | 有效（无车控时有明确 Toast 降级） |
| ADB 系列 10 个按钮 | `AdbDebug` 逐个落到 `Settings.Global` / USB 功能位 | 有效 |
| 局域网传输启停 | `LanTransferServer` 真实 HTTP 服务 | 有效 |

---

## 七、建议

按「改动成本 / 用户收益」排：

1. **删掉 360 环影页的 5 个视角开关**（或接上真实实现）——
   现在这一页给了用户 6 个选项，只有 1 个生效，最容易被当成「设置坏了」。
2. **删掉歌词页的 4 个字号滑杆**（或把 `NavSlotBarView` 的字号改成读偏好）——
   同页注释已经显示过一次同类历史（第三页字号）。
3. **「桌面开机启动」「自动导航投屏」二选一**：接上实现，或从界面上摘掉。
4. **「恢复默认 15 槽位」补生效通知**（宿主页加 `onNavSlotsChanged` 回调，
   或在 `onApply` 里同步 `navSlots` + `slotsBar?.slots`）。

---

## 八、整改（用户选定口径，已实施）

用户定的口径：**删掉 360 环影 5 项 + 歌词字号 4 项，其余无效项标注「暂不可用」，
补测试并提交。**「恢复默认 15 槽位」按第 4 条建议修好（它是真 bug，不是「无效项」）。

| 处置 | 项 | 做法 |
|---|---|---|
| **删除** | 360 环影 × 5（倒车视角 / R→D 视角 / D 档视角 / 转向灯视角 / N/P 关闭环影） | 整行删掉；落盘键在 `Prefs.kt` 保留，随时可接 |
| **删除** | 歌词槽字号 × 4（收起·主歌词 / 收起·副歌词 / 展开·主歌词 / 展开·副歌词） | 整行删掉；`首页中央歌词`（另一套画面，接 `scene_lyric_scale_pct`）不动 |
| **标注暂不可用** | 桌面开机启动 / 自动导航投屏 | 新增 `unavailableRow()`：右侧淡字写「暂不可用」+ 点整行弹 Toast + **不再写偏好** |
| **标注暂不可用** | 胎压数字字号 | 新增 `unavailableSliderRow()`：读数写「暂不可用」+ 滑杆禁用 + 点整行弹 Toast |
| **修好** | 恢复默认 15 槽位 | 新增 `onNavSlotsChanged` 回调，宿主侧 `navSlots = NavSlots.load(prefs)` + `slotsBar?.slots = navSlots` + `refresh()`；本页也重画（「编辑槽位（N 项）」的 N 要跟着变） |
| **顺带修好** | 360 环影总开关打开后子项不出现 | 总开关回调里补 `select(selectedKey)`，拨开后「视角延迟 / 按键视角」当场出现 |

### 为什么顺带修了「360 总开关打开后子项不出现」

装机取证时撞见的**既有毛病**（不是这轮改坏的，也不是那 12 个无效项之一）：
360 环影页的「视角延迟 / 按键视角」是**跟着总开关条件渲染**的（关着就不画），
可总开关原来只写偏好、**不重建本页** —— 用户拨开开关，眼前什么都没变，
得**切走再切回**才看得到子项，很容易被当成「这一页坏了」。

这一页恰好是本轮刚收敛过的（5 个假选项被删了），剩下的两项就是这一页**唯一能用的东西**。
要是还藏着，等于把仅存的功能也藏起来了 —— 所以一并修掉。

修法沿用文件里现成的惯例（`switchRow` 影响本页内容时都跟一句 `select(selectedKey)`，
见「显示这张卡」那一处），不引新机制。

### 为什么「暂不可用」写成**右侧状态文字**，不写副标题

第一版写成了副标题（「暂不可用：开机流程暂不读取该设置」），**被项目自己的
`SettingsCopyTest` 判红** —— 设置页的副标题是「功能描述」的主要载体，
第150轮专门做过一轮精简（白名单里只剩互斥 / 语义那几条，副标题总数硬性 5 条）。

「暂不可用」是**状态**不是功能描述，跟白名单里的「预留区未启用」一个性质，
不该挤进副标题那个池子。于是改成**右侧读数位**（跟胎压那行滑杆的读数同一个位置），
副标题一条不加，`SettingsCopyTest` 的规则**一个字没动**。

给这个状态文字打了专属标记 `UNAVAILABLE_MARK`，测试靠标记精确定位，
不靠扫文字（扫文字会跟别的页撞车）。

### 为什么「暂不可用」的行**不再写偏好**

盘上留一个**没有任何代码读**的值，只会在以后排障时误导人 ——
真机上查偏好发现「用户开着自动导航投屏」，但功能压根没接，容易查错方向。
所以点了只提示、不落盘。

### 测试

`app/src/test/java/com/cardesk/launcher/ui/SettingsDeadItemsTest.kt`（9 条）：

- 360 页：五个假选项**不能再出现**（先打开总开关再断言，否则页面只画一个开关、断言是空的）；
  真正能用的三项（启用 / 视角延迟 / 按键视角）**不许被误删**
- 歌词页：四个假滑杆不能再出现；`首页中央歌词` 还在
- 三个「暂不可用」：必须有 `UNAVAILABLE_MARK` 状态标记、文字是「暂不可用」、
  **字色要是淡的**（`pal.textFaint`，不能跟正常文字一样显眼）、
  **点了不写偏好**、有 Toast 提示；胎压那行滑杆必须 `isEnabled = false`
- 360 总开关：**不切走再切回**，子项就得当场出现
- 「恢复默认 15 槽位」：必须**通知宿主**（回调被调用）+ 槽位真的恢复成默认

### 反向验证

`tools/verify_y154_reverse.sh`（9 项）：把实现逐条改坏，对应断言**必红**：

| 反向 | 改坏什么 | 对应红掉的断言 |
|---|---|---|
| 1 | 「桌面开机启动」改回普通开关 | 点击不写偏好 |
| 2 | 「自动导航投屏」改回普通开关 | 点击不写偏好 |
| 3 | 「胎压数字字号」改回可拖动滑杆 | 滑杆必须禁用 |
| 4 | 「暂不可用」行不弹提示 | 点了要有提示 |
| 5 | 「暂不可用」的字不淡 | 字色要是淡的 |
| 6 | 把「倒车视角」加回 360 页 | 假选项不能再出现 |
| 7 | 把「收起·主歌词」加回歌词页 | 假滑杆不能再出现 |
| 8 | 「恢复默认 15 槽位」不通知宿主 | 必须通知宿主刷新槽位 |
| 9 | 360 总开关不重建本页 | 子项要当场出现 |

> 本轮**只做审计 + 整改**，没有动那 9 个被删项对应的偏好键 ——
> 键留在 `Prefs.kt` 里（将来实现侧支持了可以直接接回去），
> 但它们已经**不是设置项**了，所以再跑本文档的审计脚本，
> 这 9 个键仍会被列进「只写不读」—— 那是**预期的**，不是漏网。

---

## 九、装机取证（emulator-5560，1920×1080）

装 `com.ecarx.cardesk` 的 system 包逐页核对，截图存本目录：

| 截图 | 核对点 |
|---|---|
| `01-boot-unavailable.png` | 启动与开机页：「桌面开机启动」「自动导航投屏」右侧显示**暂不可用** |
| `02-lyric-only-center.png` | 歌词页：四个歌词槽字号滑杆没了，只剩「首页中央歌词」（布局 / 预览 / 字号缩放都还在） |
| `03-360-fake-options-removed.png` | 360 环影页：五个假视角选项没了 |
| `04-carinfo-tpms-disabled.png` | 车辆信息页：「胎压数字字号」是禁用滑杆 + 读数「暂不可用」（车模编号 / 车辆数据源正常） |
| `05-360-master-off.png` | 总开关**关着**时，这一页只画得出那一个开关 |
| `06-360-toggle-subitems-inline.png` | **修复后**：拨开总开关、**不切页**，「视角延迟 / 按键视角」当场出现 |
| `07-360-toggle-before-fix.png` | **修复前**：拨开总开关，眼前什么都没变 |
| `08-360-subitems-after-return-before-fix.png` | **修复前**：切走再切回，子项才出现（说明子项是活的，只是没即时重画） |

`07` / `08` 是修复**之前**的包留的对照；`05` / `06` 是修复**之后**重新出的包。

取证期间把 `pip_app` 临时改成了不存在的包名（高德会拉全屏抢焦点），
测完已把 `shared_prefs/cardesk.xml` **整份还原**（4372 字节、属主 `1000:1000`、键集合与备份一致）。
