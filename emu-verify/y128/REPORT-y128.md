# 第 128 轮验证记录：QQ 音乐车机版方控修好

## 起因

用户在真车（领克 dcy11 / Android 9 / `geely/dcy11_a1`）上装了 4226 后出一份诊断包
（`cardesk-logs-20261106-085332.zip`），反馈「**还是无法方控 QQ 音乐**」。

## 诊断包里挖到的三个根因

```
SharedPreferences(cardesk) 全量键值：
    design_music_source_v1 = qq          ← 有（用户在卡片来源行里选的）
    （**没有** default_music_source_v1）  ← 设置页的「默认媒体源」用户压根没动过

qqBroadcast: 收到=4 采纳=0 丢弃=4
忽略 QQ 广播（当前会话不是 QQ 音乐） boundPkg= defaultSrc=generic
qqControl(已发方控命令): 0
bridge.bound: true                      ← 原车服务连上了
bridge.registered: false                ← 但注册一次都没成功
bridge.callbackCount: 0                 ← 原车一次方控回调都没给过我们
bridge.reason: 正在连接原车媒体中心      ← registerMusic 根本没被调用过
```

### 根因一：采纳门读错了键

第126轮 `QqBroadcast.shouldAccept` 的 KDoc 写「真车上的实际情况（`default_music_source_v1=qq`）」——
**这个假设被诊断包证伪**。用户选的是 `design_music_source_v1`（卡片来源行），
而我们只读 `default_music_source_v1`（设置页「默认媒体源」），后者永远是默认值 `generic`
→ 4 条广播全被丢弃。

**修**：`shouldAccept(boundPkg, defaultSourceId, currentSourceId)`，两个源任一选中 QQ 音乐即采纳。

### 根因二：开门广播（`action=100`）一次都没发过

它用**同一个错键**判条件 → 直接 return → QQ 音乐不往外挂 `update_song` / `update_state` /
`update_lyric` → 我们永远拿不到歌名。

**修**：开门广播与采纳门共用 `QqBroadcast.isQqSelected`；卡片来源行切换时立刻开门。

### 根因三：原车媒体桥把「注册」和「有歌名」绑死了

```kotlin
if (!running || !sample.available || registered || api == null) return   // 老代码
```

QQ 音乐车机版**不发布可用会话** → 歌名只能靠它的三方广播 → 拿不到歌名 → 永远注册不上
→ 原车永远不把方向盘方控交给我们。`reason` 停在「正在连接原车媒体中心」就是铁证：
`registerMusic()` 根本没被调用过（调用过的话 reason 会变成「原车媒体注册未返回令牌」或异常名）。

**修**：解耦「方控目标」与「歌名」——
- 注册不再要求样本；没有样本时也**不注销**（注销了原车就把方控收回去）；
- 方控目标 = 歌名的来源包 **或** 用户选的媒体源（兜底目标）；
- 目标不在三方在线播放器白名单里就不发（收音机 / USB / 蓝牙一律不碰）。

## 模拟器实测（emulator-5556 / Android 10 / arm64-v8a）

用**真包** QQ 音乐车机版 1.9.8.22。prefs 特意做成**和真车一模一样**：
`design_music_source_v1=qq`，**没有** `default_music_source_v1`。

### ① 开门广播发得出去（关键：`defaultSrc=generic` 也照发）

```
I/CarDesk/Media: QQ 开门广播：已选 QQ 音乐，准备开门 defaultSrc=generic currentSrc=qq
I/CarDesk/Media: 请求 QQ 音乐打开「允许对外发送」 sent=true doorOpened=false defaultSrc=generic currentSrc=qq
```

### ② QQ 音乐侧真的开门了

```
I/BroadcastReceiverCenterForThird(22811): onReceive： uri:null
I/BroadcastSenderCenterForThird(22811): updatePlayState ret:0 curr_time:0 total_time:0
D/BroadcastSenderCenterForThird(22811): updatePlayState commandString :{"module":"play","command":{"method":"update_state","data":{"state":0,...
I/BroadcastSenderCenterForThird(22811): setAllowSendOutside, allow=true
```

### ③ 广播被采纳（真车上这里曾是 采纳=0 丢弃=4）

```
D/CarDesk/Media: 采纳 QQ 广播 method=update_state module=play boundPkg=
D/CarDesk/Media: 采纳 QQ 广播 method=update_song module=play boundPkg=
I/CarDesk/Media: QQ 广播合成曲目 title=测试歌名 artist=测试歌手 durationMs=200000 method=update_song positionMs=5000 playing=true
I/CarDesk/Media: 收到 QQ 歌词广播 boundPkg= lines=3 title=测试歌名 artist=测试歌手
D/CarDesk/Media: 歌词当前行 index=0 total=3 positionMs=5000 key= lineMs=0 offsetMs=5000 durationMs=200000 playing=true live= lineText=这是第一句歌词
```

### ④ 方控 URI 通道能真正下发到 QQ 音乐

```
I/BroadcastReceiverCenterForThird(22811): onReceive： uri:qqmusiccar://?action=20&m0=3
D/BroadcastReceiverCenterForThird(22811): gotoNextByHtmlScheme：dataUrl is:action=20&m0=3
D/BroadcastReceiverCenterForThird(22811): ACTION : 20
I/BroadcastReceiverCenterForThird(22811): from thied-->the app has not started
```

（最后一行是模拟器上 QQ 音乐没启动/没登录，没有播放列表 —— 与第126轮实测一致。）

### ⑤ 画面

`y128-qq-broadcast-track-lyric.png` —— 场景页大歌词区显示「这是第一句歌词」+「这是第二句歌词」，
音乐卡片显示 QQ音乐来源（真实绿色音符图标）+ 测试歌名 + 测试歌手 + 暂停按钮，
底部 Dock 同步显示歌名/歌手与播放控制。

## 诊断包实测（从模拟器导出后核对）

```
bridge.controlTarget: com.tencent.qqmusiccar       ← 第128轮新增
bridge.sourceKind: broadcast
bridge.source: com.tencent.qqmusiccar
bridge.title: 测试歌名
qqBroadcast: 收到=2 采纳=2 丢弃=0                   ← 旧包（真车）是 收到=4 采纳=0 丢弃=4
qqDoor(允许对外发送): 已开 开门命令已发=1            ← 新增「开门命令已发」
qqBroadcastMethods: update_state×1, update_song×1    ← 第128轮新增（method 计数表）
com.tencent.qqmusiccar 版本: 1.9.8.22 (28)          ← 第128轮新增（版本号）
com.tencent.wecarflow 版本: 3.0.5.79174799
com.netease.cloudmusic.iot 版本: 6.2.81 (6002081)
```

## 反向验证（6 项，逐条改回旧实现确认断言变红）

| # | 改回什么 | 变红数 |
|---|---|---|
| 1 | 采纳门退回「只认默认媒体源」 | 3 |
| 2 | `isQqSelected` 退回「只认默认源」 | 4 |
| 3 | 注册退回「必须有样本」 | 3 |
| 4 | 方控目标退回「没歌名就不派发」 | 3 |
| 5 | 方控目标白名单放行一切 | 3 |
| 6 | `methodOf` 恒返回空 | 4 |

还原后自检：0 红灯。全量单测 **1708 条全绿**（基线 1697，新增 11）。

## 踩到的坑

### ① debug 变体的 applicationId 不是 `com.ecarx.cardesk`

`assembleDebug` 出的包是 **`com.cardesk.launcher`**；真车用的是 system 变体：

```bash
gradle :app:assembleSystem -PcardeskPackage=com.ecarx.cardesk \
        -PcardeskVersionCode=4227 -PcardeskVersionName=1.0.1131
```

一开始用 debug 包验证，`adb install` 报 Success、但设备上的 `com.ecarx.cardesk`
`lastUpdateTime` 纹丝不动 —— **装的是另一个包，验的是旧代码**。
判据：`pm path` + `dumpsys package | grep lastUpdateTime` + 设备上 APK 的 md5。

### ② `git checkout` 会连带清掉本轮新增的测试

做反向验证时为了还原测试文件用了 `git checkout <test file>`，
结果把本轮**新加**的测试用例一并还原成 HEAD 的样子（新增用例没了）。
教训：反向验证一律用**备份文件 cp 还原**，不要 `git checkout`；测试文件改动另存。

### ③ grep 的 `head -N` 会把关键日志挤掉

`logcat | grep ... | head -12` 时，前面刷屏的「没有选中任何会话」把
「请求 QQ 音乐打开「允许对外发送」」挤出了视野，一度以为修复没生效。
定位这类问题要**按关键词精确 grep**，不要用 head 截断关键结果。

## 没能在模拟器上验的部分（真车复验）

- **原车媒体桥在模拟器上没有 ecarx SDK** → `原车媒体接入失败：NameNotFoundException`，
  所以「注册 → 原车把方控交给我们 → 我们派发」这一段**只能在真车上验**。
  模拟器能验的是它前面的一切：开门、采纳、歌名/歌词、以及方控 URI 通道本身。
- **QQ 音乐没登录** → 「QQ 音乐自己播放时发什么」这一段是用协议探针
  （`am broadcast` 按真实协议发 `update_song`）模拟的。真车上登录后应当一致。
- 诊断包里 `bridge.bound` 在模拟器是 `false`，真车是 `true`，两者不要混着看。
