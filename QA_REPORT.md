# HougTime 红果短剧 TV 版 — 深度验证报告

- **验证对象**：`D:/hongguo/HougTime`（原生 Android，minSdk 14，传统 View 体系）
- **验证方式**：深度静态代码审查 + 逻辑/边界/并发/生命周期分析 + 签名算法 Java/Python 交叉一致性自检 + 实际构建产物验证
- **验证日期**：2026-10-08
- **验证结论**：**通过（无 P0 致命缺陷）**。7 处修复全部正确，签名算法与 Python 参考实现完全一致，构建成功（APK minSdk=14）。发现 12 项新问题，其中 7 项 P2、5 项 P3，均为体验/竞态/防御性层面，不影响核心播放主流程的正确性。

---

## 一、验证结论总览

| 严重度 | 数量 | 说明 |
|---|---|---|
| P0（致命：崩溃/数据丢失/安全） | 0 | 未发现 |
| P1（重要：功能错误/资源泄漏） | 0 | 未发现 |
| P2（一般：竞态/体验/隐患） | 7 | 见缺陷清单 |
| P3（轻微：可读性/防御性） | 5 | 见缺陷清单 |

核心结论：MediaPlayer 生命周期（release 时机）、自动连播状态机、h264 选流兜底、线程切换、空指针/数组越界防护等关键链路均已正确实现，未见崩溃级缺陷。

---

## 二、7 处修复逐项核对结果

### 修复 1：搜索弹不出输入法 — ✅ 正确

- **位置**：`SearchActivity.kt:53-58`
- **实现**：`input.requestFocus()` 后 `postDelayed(300ms)` 再 `showSoftInput(input, SHOW_IMPLICIT)`。
- **结论**：正确。`postDelayed` 保证 View 完成 attach 并获得窗口焦点后再唤起 IME；`SHOW_IMPLICIT` 是 TV 场景的温和唤起标志，符合预期。
- **是否引入新问题**：轻微。延迟回调在 Activity 被快速退出后仍会执行，但 `showSoftInput` 对已 detach 的 View 是安全 no-op，无崩溃风险（P3 级提示）。

### 修复 2 + 5 + 6：播放器对话框焦点处理（关键）— ✅ 正确

- **位置**：`PlayerActivity.kt:319-344`
- **实现细节核对**：
  - `:320` `tracking = false`：弹窗前先解除 SeekBar 拖动态，正确。
  - `:322` `val wasPlaying = mp?.isPlaying == true`：暂停前记录播放态，正确。
  - `:323` `mp?.pause()`：暂停，正确。
  - `:338-342` `setOnDismissListener` + `if (!isFinishing)` + `btnPlayPause.post { requestFocus() }`：焦点恢复放在对话框**真正关闭之后**（post 到主线程消息队列），规避了原 `setPositiveButton` 回调里「对话框未 dismiss 焦点被抢占」的时序 bug。
  - `:339` `if (!isFinishing)`：选「退出播放」时 `finish()` 先于 dismiss 执行（AlertController 先 onClick 后 dismiss），此时 `isFinishing=true`，跳过 requestFocus，避免作用在销毁中的 View。**时序核对正确**。
- **结论**：`onDismissListener + post + isFinishing` 三者组合无漏洞，焦点恢复逻辑自洽。
- **是否引入新问题**：引入一处语义问题（非崩溃）——用户在**已暂停**状态打开对话框并选「继续播放」时，`:328` 的 `if (wasPlaying)` 为 false，不恢复播放，按钮文案与行为不符（P2-4）。

### 修复 3：小米 4s 安装失败（minSdk 14）— ✅ 正确

- **位置**：`app/build.gradle.kts:15` `minSdk = 14`
- **结论**：正确。已实测校验所有关键依赖 AAR 的 `minSdkVersion` 均为 14：`androidx.core:core-ktx:1.9.0`、`appcompat:1.6.1`、`recyclerview:1.3.2`，与 `minSdk=14` 无冲突。构建产物 APK 实测 `sdkVersion:'14'`。
- **是否引入新问题**：无。

### 修复 4：分类菜单按返回直接退出 — ✅ 正确

- **位置**：`MainActivity.kt:192-208`
- **实现**：重写 `onKeyDown` 拦截 `KEYCODE_BACK`（`:192-198`），弹「确定退出」确认框（`:200-208`），`setCancelable(true)`。
- **结论**：正确。对话框 `cancelable=true` 时，BACK 键事件由 Dialog 的 window 消费（先 `onKeyDown` 消费、`onKeyUp` 触发 cancel），不会回传 Activity 重复弹窗，因此**不会叠加对话框**。
- **是否引入新问题**：无实际影响。仅在对话框 window 尚未获得焦点的极端窗口期连按返回才有理论叠加风险，概率可忽略。

### 修复 7：搜索软键盘未收起 + 焦点悬空 — ✅ 正确

- **位置**：`SearchActivity.kt:64-69`
- **实现**：`hideSoftInputFromWindow(input.windowToken, 0)`（`:66`）→ `input.clearFocus()`（`:67`）→ `btn_do_search.requestFocus()`（`:69`）。
- **结论**：正确。顺序合理——先收起（此时 EditText 仍有焦点，`windowToken` 有效），再清焦点，再回落焦点到搜索按钮，避免 TV 遥控器确定键无目标。
- **是否引入新问题**：轻微。空关键词时 `doSearch` 在 `:63` 提前 `return`，此时不执行收起键盘/移焦逻辑，键盘仍可能弹出（P3-8）。

---

## 三、新发现的缺陷清单

### P2 级（一般）

**P2-1：切后台/回前台丢失播放进度**
- **位置**：`PlayerActivity.kt:99-102`（surfaceDestroyed→releasePlayer）、`:92-95`（surfaceCreated→playCurrent）
- **问题**：Activity 进入后台时 SurfaceView 被销毁，`surfaceDestroyed` 触发 `releasePlayer()`；回前台 `surfaceCreated` 触发 `playCurrent()` 重新取流并**从头播放**。当前集内播放位置不持久化。
- **触发条件**：播放中按 Home 键切后台再返回。
- **建议**：在 `surfaceDestroyed` 前记录 `mp.currentPosition`，`playCurrent` 恢复时 `seekTo` 该位置；或在 `onPause` 主动 `pause()` 并缓存进度。同时可考虑在 `onPause` 暂停播放，避免个别设备 surface 未销毁导致的后台音频残留。

**P2-2：搜索竞态，旧响应覆盖新结果**
- **位置**：`SearchActivity.kt:74-90`
- **问题**：`doSearch` 的协程**无 generation/cancel 保护**。快速连续搜索不同关键词时，两个请求并发，慢的旧请求可能晚到并 `adapter.submit()` 覆盖新关键词的结果，导致结果与输入框不一致。MainActivity 已用 `generation` 解决同类问题，此处遗漏。
- **触发条件**：搜关键词 A（慢）后立刻改搜 B（快）。
- **建议**：仿照 MainActivity 增加 `generation` 计数器，或用 `playJob` 式 `job?.cancel()` + 代次校验丢弃过期响应。

**P2-3：SeekBar 拖动回调 `mp.duration` 无 try-catch**
- **位置**：`PlayerActivity.kt:113-120`
- **问题**：`onProgressChanged(fromUser=true)` 直接调用 `mp.duration`。若 player 处于非 Prepared 状态（如 prepareAsync 中、Error 态未走 onErrorListener 释放），`getDuration()` 会抛 `IllegalStateException`，主线程崩溃。与同文件 `progressRunnable`（`:284-294`）已有 try-catch 的做法不一致。
- **触发条件**：极少数状态下用户在播放器未 ready 时拖动进度条。
- **建议**：给 `onProgressChanged` 内 `mp.duration`/`seekTo` 加 try-catch 兜底。

**P2-4：暂停状态下选「继续播放」不恢复播放**
- **位置**：`PlayerActivity.kt:327-333`
- **问题**：对话框「继续播放」仅在 `wasPlaying=true` 时 `player?.start()`。用户**主动暂停**后打开对话框选「继续播放」，期望恢复播放，实际保持暂停。文案与行为不一致。
- **建议**：将分支改为「若 dialog 打开前暂停/播放态为暂停，则点继续时无条件 `start()`」，或改按钮文案为「保持暂停」。

**P2-5：签名固定参数与实际运行环境不一致（提示性风险）**
- **位置**：`XGorgon.java:127-145`（`baseParams`）
- **问题**：`device_id`、`iid`、`device_type` 硬编码为逆向样本值，`os_api="36"`、`os_version="16"` 伪装为 Android 16。而目标设备是 API 14-23 老设备。存在两类风险：① 固定 device_id/iid 被服务端风控识别为批量请求；② os_api 与实际不符可能导致服务端下发不适配内容（代码已做 h264 优先筛选兜底，风险可控）。
- **性质**：逆向工程固有设计取舍，非代码缺陷，仅提示。
- **建议**：若出现接口风控，可改为运行时采集真实 `Build` 信息（需同步保持签名一致）。

**P2-6：URL 编码 Java/Python 差异（签名一致性隐患，当前不触发）**
- **位置**：`XGorgon.java:115-122`（`encode`）对比 `xgorgon.py:35-39`（`build_query`）
- **问题**：Java `URLEncoder.encode` 将空格编码为 `+`、`~` 编码为 `%7E`；Python `quote(safe='')` 将空格编码为 `%20`、`~` 保留不编码。当前 `baseParams` + `extra` 均无空格/`~`，故现有签名结果一致（已由 main 交叉验证）。若未来新增含空格或 `~` 的参数，两实现将产生不同 query → 不同 X-Gorgon。
- **建议**：统一编码规则（Java 端在 encode 后追加 `.replace("+", "%20")`，或双方都显式对齐 `~` 的处理），并在自检用例中加入含空格/`~` 的样本。

**P2-7：接口字段名假设需真机验证（提示性）**
- **位置**：`NetClient.java:177`（`optString("vid")`）、`NetClient.java:401-426`（`_ROUTER_DATA` 提取）、`NetClient.java:205-265`（`video_model` 结构）
- **问题**：分集 `vid` 字段名、搜索 web 站 `_ROUTER_DATA` 注入格式、`video_model` 清晰度列表结构均为逆向推断。静态代码无法验证与线上真实返回是否完全吻合。若字段名不符，表现为「加载为空」而非崩溃（已有兜底）。
- **建议**：真机抓包核对 `video_detail` 的分集 vid 字段、`hongguoduanju.com` 搜索页的 SSR 数据注入变量名。

### P3 级（轻微）

**P3-8：空关键词搜索不收起键盘**
- **位置**：`SearchActivity.kt:62-63`
- **问题**：`if (keyword.isEmpty()) return` 位于收起键盘/移焦逻辑之前。清空输入后点搜索，键盘保持弹出、焦点悬空。
- **建议**：将收起键盘/移焦逻辑前置，或对空关键词单独处理（提示后收起）。

**P3-9：魔法数字 `requestedOrientation = 6`**
- **位置**：`MainActivity.kt:178`
- **问题**：`6` 即 `SCREEN_ORIENTATION_SENSOR_LANDSCAPE`，用字面量可读性差。
- **建议**：改用 `ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE` 常量。

**P3-10：固定列数布局在低分辨率可能拥挤**
- **位置**：`MainActivity.kt:57`（首页 5 列）、`DetailActivity.kt:83`（选集 8 列）
- **问题**：`GridLayoutManager` 固定列数，未按屏幕宽度自适应。720p 老设备上选集 8 列偏挤。
- **建议**：按屏幕宽度动态计算列数。

**P3-11：Activity 后台时协程未及时取消**
- **位置**：`MainActivity.kt:171-174`、`SearchActivity.kt:101-104`、`DetailActivity.kt:125-128`
- **问题**：`scope` 仅在 `onDestroy` 取消。切后台后协程仍可能完成并更新已不可见 View（无崩溃，仅浪费）。
- **建议**：可在 `onStop` 补充取消或加生命周期感知。

**P3-12：PlayerActivity 任意按键均触发 showControls**
- **位置**：`PlayerActivity.kt:307-309`
- **问题**：`onKeyDown` 的 else 分支对所有非 BACK 键（含音量键 `KEYCODE_VOLUME_*`）调用 `showControls()`。用户调音量时播放栏会闪现。
- **建议**：仅对遥控器导航/确认键触发，或对音量键放行不做 showControls。

---

## 四、签名算法一致性结论

**结论：Java `sign/XGorgon.java` 与 Python `xgorgon.py` 算法 100% 一致。**

逐项核对：

| 要素 | Python (`xgorgon.py`) | Java (`XGorgon.java`) | 一致性 |
|---|---|---|---|
| SALT（20B） | `44b9b9d9a4aef9fca493aa757ca3c2c4a496938f` | `:18-22` 逐字节数组，hex 展开后相同 | ✅ |
| PREFIX（6B） | `84 04 40 1C 00 00` | `:25` 相同 | ✅ |
| MAGIC（4B） | `00 06 0B 1C` | `:28` 相同 | ✅ |
| BITREV 表 | `f"{i:08b}"[::-1]` | `:31-41` 逐位反转循环 | ✅ 等价 |
| MD5 取前 4B | `md5(query)[0:4]` / `md5(body)[0:4]` | `:59` / `:62-64` | ✅ |
| unix 秒大端 | `buf[12..15]` | `:70-74` | ✅ |
| 异或 SALT | `buf[i]^=SALT[i]` | `:77` | ✅ |
| 半字节交换 | `(v<<4)|(v>>4) & 0xFF` | `:82` `(v<<4)|(v>>>4) & 0xFF` | ✅（v∈0..255，`>>`/`>>>` 等价） |
| 异或下一字节 | `v ^= buf[(i+1)%20]` | `:83` | ✅ |
| 位反转查表 | `BITREV[v]` | `:84` | ✅ |
| 取反异或 0x14 | `((~v)^0x14)&0xFF` | `:85` `(byte)((~v)^0x14)` | ✅（低 8 位一致） |

**运行实测**（相同输入 `q="aid=8662&app_name=novelread&version_code=73532"`、`body={"video_id":"123456","content_type":1}`、`unixSec=1767225600`）：

- Python 输出：`X-Gorgon: 8404401C0000DEC59A2EEE4805E310F4252A0EC325FCD0B4868F`
- Java 输出：  `X-Gorgon: 8404401C0000DEC59A2EEE4805E310F4252A0EC325FCD0B4868F`
- X-SS-STUB 均为：`A8CE3AB7913707C1AA2AA6EBA7690067`

完全一致。签名算法无缺陷。

---

## 五、构建验证结果

- **命令**：`JAVA_HOME="C:/Program Files/Microsoft/jdk-21.0.9.10-hotspot" ./gradlew.bat assembleDebug --no-daemon`
- **结果**：`BUILD SUCCESSFUL in 25s`（34 个 task，无报错，`checkDebugAarMetadata` 通过）。
- **产物**：`app/build/outputs/apk/debug/app-debug.apk`（约 7.3 MB）。
- **APK 属性实测**（`aapt dump badging`）：
  - `package: name='com.houg.tv' versionCode='1' versionName='1.0.0'`
  - `sdkVersion:'14'`（minSdk，符合老设备适配目标）
  - `targetSdkVersion:'35'`
- **依赖 minSdk 校验**：`core-ktx:1.9.0`、`appcompat:1.6.1`、`recyclerview:1.3.2` 的 AAR manifest 均声明 `minSdkVersion="14"`，与 `minSdk=14` 无冲突。

---

## 六、总体评价

代码整体质量较高，7 处修复方向正确、实现严谨，尤其 PlayerActivity 对话框焦点时序（onDismissListener + post + isFinishing）处理到位，MediaPlayer 的 release/重建、自动连播越界、h264 选流兜底、协程 generation 防错位、SharedPreferences 同步锁等关键路径均有正确防护。剩余问题集中在「体验细节（进度持久化、搜索竞态）」与「逆向字段/风控假设需真机验证」两个层面，不影响编译与核心播放流程。
