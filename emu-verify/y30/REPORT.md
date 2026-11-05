# 第 117 轮验证报告：首页中央歌词 三行 / KTV 两行

## 结论

**两种布局都在真机（模拟器）上验证通过**，设置页的切换入口、
偏好落盘、预览条联动、桌面联动、说明文案联动**五处全部对得上**。

- 包：`CarDesk-platform-ecarx-cardesk-4215.apk`（4215 / 1.0.1119-system）
- 设备：`emulator-5556`，Android 10 / SDK 29 / arm64-v8a，`wm size 1920x968`（对齐车机）
- 歌词来源：探针 `com.cardesk.mediaprobe` 注入《怎么我睡不着你也不来救我》，
  桌面在线抓歌词（`歌词竞速命中 provider=qq lines=54`）

## 两种布局的实机画面

### 三行（默认）

`y30-lyric-three.png`

```
怎么月转星移的规律把我推进银河      ← 上一句（40px，灰）
怎么我睡不着你也不知来救我          ← 当前句（56px 粗体 + 青色擦除）
辗转接着反侧                        ← 下一句（40px，灰）
```

### KTV 式两行（当前句在上）

`y30-lyric-ktv.png`

```
怎么我睡不着你也不知来救我          ← 当前句（56px 粗体 + 青色擦除）
辗转接着反侧                        ← 下一句（40px，灰）
```

两行之间有**明显分隔**（行距放大到 1.8 倍），当前句压在**上面**那一行 ——
这就是用户要的「上下各一行、当前句在上」。

## 设置页

| 截图 | 内容 |
|---|---|
| `y30-settings-layout-three.png` | 「歌词布局」chipRow：**三行**（选中）/ **KTV 两行**；下方说明「上一句 / 当前句 / 下一句」；预览条三行 |
| `y30-settings-layout-ktv.png` | 点「KTV 两行」后：chip 切到 KTV、预览条立刻变两行、说明文案跟着换 |
| `y30-settings-click-ktv-desktop.png` | 关掉设置页 → **桌面立刻变成 KTV 两行**（当前句在上），不用重启 |

## 验证中抓到并修掉的一个 bug

第一版切到 KTV 两行时，**预览条变了、桌面也变了，但 chip 下面那句说明还是三行的**
「上一句 / 当前句 / 下一句」。

原因：那句说明是在**建页时**写死一次的（`note(...)` 只是往 body 里塞一个 TextView），
之后 chip 的回调只更新了预览条和桌面，没回头改那行字。

修法：`note()` 改成**返回它建的 TextView**，歌词布局那一节把返回值留在手里，
chip 回调里就地改文案。

补了断言：`SettingsLyricLayoutTest` 现在会检查「切到 KTV 两行后，
页面上能找到 KTV 那句说明」。**做过反向验证** —— 临时去掉那行文案更新，
用例立刻变红（`点KTV两行会落盘并让预览与桌面都跟着变 FAILED`）。

## 复现步骤（`tools/emu_lyric_layout.sh`）

```bash
bash tools/emu_lyric_layout.sh three y30-lyric-three
bash tools/emu_lyric_layout.sh ktv   y30-lyric-ktv
```

脚本做三件事：

1. **强杀** CarDesk 进程；
2. 写偏好（`last_page=0` → 首页场景页、`scene_lyric_layout=three|ktv`）；
3. 起探针（带歌词）→ 起 CarDesk → 截图。

### 为什么必须强杀（`am force-stop` 不够）

CarDesk 是**系统 HOME 应用**。`am force-stop` 之后系统会立刻把它重新拉起来，
旧实例带着内存里的 `page=1` 状态；`am start` 会**复用**那个已有实例，
于是桌面一启动就停在导航页，而且它会把 `last_page` 又写回 1 ——
把刚写好的偏好覆盖掉。改成 `kill -9` 才干净。

（取证时顺手把模拟器的默认桌面改回了 `com.android.launcher3`，
免得系统一直把 CarDesk 拉起来。）

## 日志

`y30-lyric-three-logcat.txt` / `y30-lyric-ktv-logcat.txt`：

```
切页 from=0 to=0 animate=true
歌词竞速命中 provider=qq score=205 lines=54 key=t:怎么我睡不着你也不来救我
歌词已应用 key=t:怎么我睡不着你也不来救我 lines=54 sourceDurationMs=277000
```

两种布局都是**同一首歌、同一帧歌词**，唯一变量就是布局。

---

# 第 118 轮补充：KTV 两行改成「两句一样大」

用户看 4215 的实机图之后提的：
「对于两句歌词我的意思是这样布局，**前后句保持大小一样，不需要区分前后句大小**」。

4215 那版是「当前句 56px 粗体 + 下一句 40px 常规」—— 一粗一细。

## 改了三处（只改字号是不够的）

1. **字号**：`LyricLayout.KTV` 的 `sizeTiers` `[MAIN, NEAR]` → `[NEAR, NEAR]`；
2. **字重**：40px 的**粗体中文字视觉上比常规字大一号**（笔画更粗 → 看着更大），
   所以当前句**不加粗** —— 做成 `LyricLayout.boldCurrent` 字段，
   绘制代码不再写死「当前行 = 粗体」；
3. **颜色**：当前句靠**青色逐字擦除** + 在**上面**那一行认出来，下一行是暗灰。

**三行布局没动**（40 / 56 / 40，当前句加粗）。

## 截图（4216）

| 文件 | 内容 |
|---|---|
| `y31-lyric-ktv-samesize.png` | KTV 两行：两句**完全一样大、一样字重** |
| `y31-lyric-three-4216.png` | 三行：不受影响 |
| `y31-settings-layout-ktv-samesize.png` | 设置页：说明「当前句在上、下一句在下，两句一样大」+ 预览条两行一样大 |

## 像素级断言

桌面 KTV 两行行带 `[440..477, 519..556]` —— **两行高度都是 37，完全相等**
（4215 那版是 39 / 37，差的那 2px 就是粗体带来的）。

## 落盘键没变

`scene_lyric_layout` 还是 `three` / `ktv` —— 升级后**不用重设设置**。
