修复全局 Dock 在几乎所有 App 里让位 + 让位后车机自带导航栏露出来

一、现象
- 全局 Dock 在几乎所有 App 里都让位（原先只有网易云、汽水音乐会让位），回到桌面也受影响。
- Dock 一让位，底下被它盖住的车机自带导航栏（CarNavigationBar）就露了出来。

二、根因（一处策略写错，连锁四环）
- 第 189 轮把「隐藏原车 Dock」改成全局生效时，把 hideNavigation = prefs.hideDock 误接到了**预留区路径**上。
- 结果 CarDesk 往 policy_control 写了 immersive.navigation=*，把系统导航栏连同 112px 预留带从**所有** App 的窗口布局里摘掉。
- 同一条策略让 WMS 给**每个** App 打上 HIDE_NAVIGATION/IMMERSIVE，而 dumpsys window policy 的 mLastSystemUiFlags 是**全局**值 —— 沉浸探测对任何前台 App 都返回 true。
- 于是 Dock 到处让位；而 CarDesk 的 Dock 是 TYPE_NAVIGATION_BAR_PANEL(2024)，本来就盖在原车导航栏**之上**，它一隐藏，底下的原车导航栏就露出来。

三、改了什么
- 预留区路径不再写 immersive.navigation=*：闸口 GlobalDockPolicyGate 改传 hideNavigation=false，SystemNavigationPolicy.merge() 里也加了结构性兜底（navInsets=true 时一律不写）。没有预留区的 2038 悬浮窗路径保持原样（那条路径必需）。
- 沉浸探测加一道闸：policy_control 里有通配符沉浸策略时，mLastSystemUiFlags 代表的是策略而不是某个 App 的行为 —— 判为「探测不可用」，宁可不让位，也不让所有 App 都让位。
- 让位判定抽成纯函数 DockYieldPolicy：名单命中 → 让位；桌面在前台 → 一定不让位；探测不可用 → 不让位。
- 让位日志区分「名单 / 桌面 / 不可用 / true / false」，排障时能一眼看出是哪条规则生效。

四、实测（API 28 模拟器，同一套设置下新旧包 A/B 对照）
- 旧包 4268：从 QQ 音乐起就一直让位，高德、设置页、甚至普通 App 全部让位（复现了用户报告的现象）。
- 新包：桌面左右滑动不触发让位；QQ 音乐、高德、普通 App 均不让位，Dock 完整显示、导航栏不再露出；网易云、汽水音乐、真正的沉浸型 App 仍按预期让位。
- 策略侧：policy_control 由 immersive.navigation=* 变为 null，预留带保持 112px。

五、校验
- 单测 2291 → 2308 项全绿（新增 DockYieldPolicyTest 9 项、ImmersiveDetectorTest 6 项，SystemNavigationPolicyTest 改写并补 2 项）。
