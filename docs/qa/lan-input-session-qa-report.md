# 跨屏输入会话保持与恢复 — QA 验收报告

| 项目 | 内容 |
| --- | --- |
| 被测应用 | 红果TV（`com.houg.tv`）Android TV |
| 被测功能 | 跨屏输入（手机扫码 → 输入 → 提交到电视搜索）的「会话保持与恢复」 |
| 构建版本 | `versionName 1.0.20` / `versionCode 120`（调试包 `app-debug.apk`，11,557,156 字节，2026-10-25 14:49:32 生成） |
| 构建标识 | 未提供 `state.json`，无 `build_version` 字段；以 APK 构建时间 + versionCode 作为本次测试标识 |
| 测试日期 | 2026-10-25 |
| 测试结论 | **有条件可发布**（P0 = 0，P1 = 0；4 项验收通过、1 项需主策裁决；P2 缺陷 1 条需评估排期） |

---

## 1. 测试范围与环境

### 1.1 被测代码（只读，未做任何修改）

| 文件 | 关注点 |
| --- | --- |
| [LanInputSession.kt](file:///d:/hongguo/HougTime/app/src/main/java/com/houg/tv/net/LanInputSession.kt) | 进程级单例会话、`TTL_MS`、`ensureStarted` 幂等、`WeakReference` 绑定、电视端快照 |
| [LanInputServer.kt](file:///d:/hongguo/HougTime/app/src/main/java/com/houg/tv/net/LanInputServer.kt) | 手写 HTTP 服务、`/state` `/draft` `/submit`、`touch()`/`isExpired`、`pageHtml` 内 JS |
| [SearchActivity.kt](file:///d:/hongguo/HougTime/app/src/main/java/com/houg/tv/SearchActivity.kt) | `bind/unbind`、`onPause` 快照、`onCreate` 恢复、二维码弹窗生命周期、`onLanKeyword` |
| [SoftKeyboard.kt](file:///d:/hongguo/HougTime/app/src/main/java/com/houg/tv/SoftKeyboard.kt#L48-L50) | `focusFirstKey()` 焦点落点 |
| [dialog_qr_input.xml](file:///d:/hongguo/HougTime/app/src/main/res/layout/dialog_qr_input.xml) / [strings.xml](file:///d:/hongguo/HougTime/app/src/main/res/values/strings.xml) | 二维码弹窗结构、503/不可用文案 |

### 1.2 测试环境

| 项 | 值 |
| --- | --- |
| 操作系统 | Windows 10/11（PowerShell 5.1，Git Bash 用于既有探针） |
| JDK | JDK 21（`D:\dev\tools\jdk-21`），Gradle Wrapper |
| Android 编译配置 | `compileSdk 35` / `minSdk 21` / `targetSdk 35` |
| 协议探针运行方式 | 用生产源码（`LanInputServer.kt` + `LanInputSession.kt`）原样编译到 JVM，配合 `Handler`/`Looper`/`AppLog` 桩；`Handler.post` 立即同步执行 |
| 网络环境 | 单机回环 + 本机局域网地址（探针取到 `26.2.157.191`），端口由系统随机分配 |
| JSON 校验器 | PowerShell 5.1 `ConvertFrom-Json`（真实解析器）+ `[String]::Equals(..., Ordinal)` 逐字符比较（项目内的 `org.json` 只是编译期桩，不能用于解析） |

### 1.3 测试范围外 / 限制（重要）

- **未执行真机或模拟器 UI 操作**：本次环境无 Android 设备/模拟器，验收项 1、3 中涉及「点开二维码、进详情、返回上一级、关弹窗、焦点落点」的 UI 链路，采用**代码路径静态审查 + 同源协议级实测**判定，未做端到端 UI 执行。发布前仍建议补一轮 TV 真机冒烟（见第 6 节）。
- 未做性能压测（并发上限、长稳）、未做多机型兼容矩阵、未做 iOS/微信内置浏览器实机验证（手机端仅做静态 JS 审查）。

---

## 2. 执行用例清单与结果

### 2.1 汇总

| 测试集 | 执行方式 | 通过 | 失败 |
| --- | --- | --- | --- |
| 构建验证 `:app:assembleDebug` | Gradle | 1 | 0 |
| 既有探针 `laninput` | `_jvmprobe/laninput/run.sh` | 60 | 0 |
| 既有探针 `pinyin` | `_jvmprobe/pinyin/run.sh` | 54 | 0 |
| 既有探针 `search` | `_jvmprobe/search/run.sh` | 11 | 0 |
| QA 补充协议级探针（A~G 七组） | 临时 Java 探针对真实 HTTP | 53 | 0 |
| `/state` JSON 转义真解析校验 | PowerShell `ConvertFrom-Json` | 3 | 0 |
| **合计** | | **182** | **0** |

补充探针原始日志：`d:\hongguo\HougTime\_jvmprobe\qa_tmp\qa_run.log`（末行 `==== 结果: 53 通过, 0 失败 ====`）、`ps_results.txt`、`out/state_*.json`。

### 2.2 构建与既有探针

- 构建：`.\gradlew.bat :app:assembleDebug --console=plain` → `BUILD SUCCESSFUL in 1s`（37 actionable tasks: 1 executed, 36 up-to-date），产物 `app/build/outputs/apk/debug/app-debug.apk`（11,557,156 字节）。
- 既有探针全部达到基线：`laninput 60 通过 / 0 失败`、`pinyin 54 通过 / 0 失败`、`search 11 通过 / 0 失败`（合计 125/125）。

### 2.3 补充协议级用例明细（53 项，全部 PASS）

| 组 | 场景 | 用例数 | 关键实际结果（日志证据） |
| --- | --- | --- | --- |
| A | 会话并发与一致性：20 并发写 `/draft` + 20 并发读 `/state`、最终一致性、`ttlMs` 语义 | 7 | 写 20/20 = 200；读 20/20 = 200 且无 403；`torn=0`；静止后 `/state` 精确等于最后写入 `{"draft":"最终值  带前后空格  ","cursor":4,"ttlMs":-1}` |
| B | sid 猜测防护与鉴权面 | 7 | 7 组错 sid（空 / 多 1 位 / 只差 1 位 / `zzzz` 等）`/state` **恒 403**，响应体恒为 `forbidden`，不含真实 sid；错 sid 的 `/draft`、`/submit` 也 403；**`GET /`（不带 sid）返回 200 且页面内嵌真实 sid**；**空 sid `/submit` 返回 200 `ok`** |
| C | 草稿原样性与 JSON 转义 | 6 | 特殊字符（前后空格、`\n` `\t`、`"`、`\`、`</script>`、`<b>`、`\r`、`\u0001`、`\u001f`，共 50 字符）保存 200 且 `/state` 200；5100 汉字长草稿保存 200、`/state` 200（`respLen=5155`）；未提交草稿**未 trim**（10 字符含前后各 2 空格，`cursor=4`）；>64KB 请求未拖垮服务（后续 `/state` 仍 200，见缺陷 BUG-004） |
| D | 提交语义 | 7 | 带 sid 提交 200 `ok`，TV 回调触发 1 次且关键词已 trim；提交后 `{"draft":"","cursor":0}`；重复提交仍 200 且草稿保持清空（回调再触发 1 次）；空关键词提交 400 `empty` 且不触发搜索 |
| E | 无 Content-Length / chunked 与连接健壮性 | 6 | chunked `POST /draft` 返回 200 未崩溃，但 body 被忽略 → **草稿被静默清空**；chunked 错 sid 仍先回 403（sid 校验先于 body）；3 条空闲预连接不阻塞后续真实请求 |
| F | 解绑后探活不得续命（TTL 真正生效） | 5 | `idleTimeoutMs=2000` + `receiverAvailable=false`，每 700ms 轮询 `/state`：**2850ms 自停**（未被拉到 6s 上限），`isExpired=true`，`port=0` |
| G | TTL 常量与过期自洽性、重新拉起 | 15 | `TTL_MS=600000`；空闲到期后 `isAlive=false`、`port()==0`、`url()==null`，旧端口连接被拒；`ensureStarted()` 可重新拉起：端口 `2642 → 2646`、sid `45f8cd58 → 7610db74`，新页面 200、**旧 sid 恒 403（旧二维码 100% 失效）**；绑定期间重复 `ensureStarted` 端口不变（`2642 → 2642`） |

### 2.4 `/state` JSON 转义真解析校验（3 项，全部 PASS）

用 PowerShell 5.1 `ConvertFrom-Json` 解析 `/state` 原始响应，并对 `draft` 做 Ordinal 逐字符比较：

| 用例 | draft 长度 | Ordinal 逐字符相等 | cursor | 结论 |
| --- | --- | --- | --- | --- |
| `special`（引号/反斜杠/换行/制表/控制符/HTML 标签/首尾空格） | 50 | 是 | 50 | PASS |
| `big`（5100 汉字） | 5100 | 是 | 5100 | PASS |
| `trim`（前后各 2 空格未 trim） | 10 | 是 | 4 | PASS |

原始响应样本：`out/state_special.json`、`out/state_big.json`、`out/state_trim.json`。`\u0001`、`\u001f` 等控制符转义符合 JSON 规范，`\n` `\r` `\t` `\"` `\\` 均正确。

---

## 3. 五项验收标准逐条判定

### 验收 1：中断不销毁会话（搜索 / 详情 / 返回 / 关二维码弹窗），手机端旧网址端口不变 —— **通过（协议级 + 代码）**

| 验证点 | 证据 | 结论 |
| --- | --- | --- |
| 关二维码弹窗不销毁服务 | [dismissQrDialog()](file:///d:/hongguo/HougTime/app/src/main/java/com/houg/tv/SearchActivity.kt#L283-L286) 只 `dismiss()`，无 `stop()` | 通过 |
| 进详情 / 返回上一级不销毁服务 | `onDestroy` 只 `dismissQrDialog()` + `unbind(this)` + 取消协程（[L455-L461](file:///d:/hongguo/HougTime/app/src/main/java/com/houg/tv/SearchActivity.kt#L455-L461)），不碰服务；全仓 `LanInputSession.` 仅 6 处引用，**没有任何生产路径调用 `stop()`** | 通过 |
| 端口不变（旧网址可用） | `ensureStarted()` 幂等：已有 `port != 0` 直接复用（[L55-L71](file:///d:/hongguo/HougTime/app/src/main/java/com/houg/tv/net/LanInputSession.kt#L55-L71)）；实测重复调用端口 `2642 → 2642`，URL 完全一致 | 通过 |
| UI 动作序列本身 | **未在真机/模拟器执行**（环境限制），仅代码路径审查 | 待真机复验 |

> 判定说明：服务生命周期与「弹窗 / 单次提交 / Activity 重建」解耦的核心诉求，已由代码 + 协议级实测闭环证明；UI 层动作序列属于回归冒烟项，建议真机补测，不影响本次判定。

### 验收 2：会话快照（TV 文本 + 光标；手机未提交草稿不 trim + 光标） —— **通过（协议级实测 + 代码）**

| 验证点 | 证据 | 结论 |
| --- | --- | --- |
| 手机侧草稿不 trim | `paramRaw()` 用 `URLDecoder.decode` 原样返回（[L352-L367](file:///d:/hongguo/HougTime/app/src/main/java/com/houg/tv/net/LanInputServer.kt#L352-L367)）；实测 `"  前后空格保留  "`（10 字符）经真实 JSON 解析后 Ordinal 完全相等，`cursor=4` 保留 | 通过 |
| 手机侧光标 | `/state` 返回 `cursor`，服务端 `coerceIn(0, len)` | 通过 |
| 特殊字符/超长草稿 | 50 字符特殊串 + 5100 汉字，JSON 解析成功且逐字符相等 | 通过 |
| TV 侧文本 + 光标快照 | `Snapshot(text, selection, at)`（[L39](file:///d:/hongguo/HougTime/app/src/main/java/com/houg/tv/net/LanInputSession.kt#L39)）；`onPause` 保存（[L299-L303](file:///d:/hongguo/HougTime/app/src/main/java/com/houg/tv/SearchActivity.kt#L299-L303)）；`onCreate` 恢复文本与 selection（[L145-L150](file:///d:/hongguo/HougTime/app/src/main/java/com/houg/tv/SearchActivity.kt#L145-L150)），超 10 分钟快照失效 | 通过（静态） |
| UI 层恢复效果 | 未真机执行 | 待真机复验 |

### 验收 3：返回搜索页后恢复输入框文本、光标位置、焦点 —— **不通过（焦点项）；文本与光标项通过**

| 验证点 | 证据 | 结论 |
| --- | --- | --- |
| 文本恢复 | `input.setText(snap.text)`（[L147](file:///d:/hongguo/HougTime/app/src/main/java/com/houg/tv/SearchActivity.kt#L147)） | 通过（静态） |
| 光标恢复 | `input.setSelection(snap.selection.coerceIn(0, snap.text.length))`（[L148](file:///d:/hongguo/HougTime/app/src/main/java/com/houg/tv/SearchActivity.kt#L148)） | 通过（静态） |
| 焦点恢复 | 紧接着执行 `softKeyboard.focusFirstKey()`（[L153](file:///d:/hongguo/HougTime/app/src/main/java/com/houg/tv/SearchActivity.kt#L153)），注释明确写「默认焦点给键盘首键…而不是输入框」→ 焦点落在**软键盘首键**，不是输入框 | **不通过（与验收标准字面不符）** |

> 这不是崩溃或功能失效，而是交互预期问题：遥控器场景下焦点在键盘首键确实可直接打字。**需主策书面确认**「恢复焦点」是指输入框还是可输入的键盘首键；若确认为键盘首键，则本项改判通过（仅需修订验收标准）。

### 验收 4：通道重连续传（刷新 / 关掉重开凭 sid 认回同一会话，服务复用同一端口） —— **通过（协议级实测）**

| 验证点 | 证据 | 结论 |
| --- | --- | --- |
| 凭 sid 取回草稿 + 光标 | `GET /state?sid=` 返回 `{"sid","draft","cursor","ttlMs"}`；页面 `restore()` 在 `el.value` 为空时回填并 `setSelectionRange` | 通过 |
| sid 不可猜 | 7 组错 sid 恒 403，响应体不含 sid | 通过 |
| 端口复用、二维码不失效 | `ensureStarted` 幂等实测 `2642 → 2642`；`url()` 由同一 `sessionId` 拼出 | 通过 |
| 提交后不串页 | 提交同时清空 `draft/draftCursor`，重复提交仍 200 且保持清空 | 通过 |

### 验收 5：一定时间内有效（TTL = 10 分钟；解绑后探活不得续命） —— **通过（协议级实测）**

| 验证点 | 证据 | 结论 |
| --- | --- | --- |
| `TTL_MS == 600000` | 反射/直接读取常量实测 `TTL_MS=600000` | 通过 |
| 空闲到期自动关闭 | 短 TTL 复现：`idleTimeoutMs=2000`，`receiverAvailable=false`，700ms 间隔轮询 `/state`，**2850ms 自停**，`isExpired=true`、`port=0` | 通过 |
| 解绑后探活不续命 | `touch()` 内 `if (receiverAvailable?.invoke() != false)`（[LanInputServer.kt](file:///d:/hongguo/HougTime/app/src/main/java/com/houg/tv/net/LanInputServer.kt#L233)），解绑后请求不再刷新 `lastActiveAt` | 通过 |
| 过期后状态自洽 | `isAlive=false` / `port=0` / `url=null` / 旧端口拒连；`ensureStarted()` 重新拉起新端口 + 新 sid，旧 sid 403（旧二维码彻底失效） | 通过 |

---

## 4. 缺陷列表

> 严重度按「阻断 / 严重 / 一般 / 轻微」= P0 / P1 / P2 / P3；优先级按影响用户范围 + 出现频率判定。

### 汇总

| 级别 | 数量 |
| --- | --- |
| P0 | 0 |
| P1 | 0 |
| P2 | 1 |
| P3 | 6 |

### BUG-001（P2 / 优先级：中）局域网内无鉴权的 sid 下发与草稿读写面

- **标题**：`GET /` 不做 sid 校验却无条件内嵌真实 sid，同网段任意设备可获取 sid 后读取/篡改草稿并向电视注入搜索
- **严重度**：P2　**优先级**：中
- **环境**：`com.houg.tv` 1.0.20（versionCode 120），调试包；JVM 协议探针 + 真实 HTTP；TV 与手机同一局域网
- **复现步骤**：
  1. 电视端进入搜索页，点开二维码（服务启动，得到端口 P）。
  2. 同网段另一台设备执行 `GET http://<TV-IP>:P/`（不带 sid）。
  3. 从返回 HTML 中取出 `var SID='xxxxxxxx'` 与表单 action 里的 sid。
  4. 执行 `GET http://<TV-IP>:P/state?sid=<SID>` → 200，得到当前草稿与光标。
  5. 执行 `POST /draft?sid=<SID>` 写入任意草稿；或直接 `POST /submit`（**不带 sid**）触发电视搜索。
- **预期**：未持有会话凭据的客户端不应拿到 sid；草稿读写与提交应受鉴权约束，或在电视端未授权时拒绝。
- **实际**：`GET /` 200 且 `泄露sid=true`；随后 `/state` 200 返回草稿、`/draft` 可改写；空 sid `POST /submit` 返回 200 `ok` 并触发电视搜索。
- **证据**：`qa_run.log` 第 13、14 行；`out/state_*.json`。
- **影响面**：同一局域网内任意设备（无需任何凭据）。泄露/可篡改的数据仅为「正在输入的搜索关键词」，无账号或凭据泄露；影响为隐私窥探 + 检索结果被他人操控。sid 为 8 位十六进制（32 bit），且无速率限制。

### BUG-002（P3 / 优先级：低）无 Content-Length（chunked）的 `/draft` 被静默当作空草稿

- **标题**：`Transfer-Encoding: chunked` 的 `POST /draft` 返回 200，但 body 被忽略，草稿被清空
- **环境**：同上；手工构造 chunked 请求
- **复现步骤**：先 `POST /draft?sid=<SID>` 写入 `kw=待提交草稿`；再以 `Transfer-Encoding: chunked` 的 `POST /draft?sid=<SID>` 发送 `kw=abc&cursor=3`；随后 `GET /state?sid=<SID>`。
- **预期**：应返回 411 Length Required（或 400），并保持原草稿不变。
- **实际**：返回 200，`/state` 变为 `{"draft":"","cursor":0}`，原草稿丢失。
- **证据**：`qa_run.log` 第 28-32 行。
- **影响面**：仅影响非浏览器客户端（浏览器与 `sendBeacon` 均带 Content-Length），出现频率极低；但属静默数据丢失，建议返回 411。

### BUG-003（P3 / 优先级：低）>64KB 请求体被丢弃，且客户端可能收到连接重置

- **标题**：`Content-Length > 65536` 的 `POST /draft` 未读取 body 即回复并关闭连接，客户端偶发 `SocketException`
- **环境**：同上；`HttpURLConnection` + `setFixedLengthStreamingMode(70000)`
- **复现步骤**：用 70000 字节 body 调 `POST /draft?sid=<SID>`；观察客户端异常与后续 `/state`。
- **预期**：明确返回 413（或 400）且保持原草稿，连接可被客户端正常读完响应。
- **实际**：两次执行结果不一致 —— 一次 200（服务存活），一次客户端 `SocketException`（连接被重置）；两种情况草稿均未更新。
- **证据**：`qa_run.log` 第 20 行（本次 `大包结果=EX:SocketException 后续state=200`；前一次同用例为 `200/200`）。
- **影响面**：正常手机页面不会产生 64KB 以上草稿；属健壮性问题，服务未崩溃、未影响后续请求。

### BUG-004（P3 / 优先级：低）`draft` 与 `draftCursor` 为两个独立 `@Volatile`，`/state` 存在理论撕裂

- **标题**：草稿正文与光标非原子更新，`/state` 可能读到「新正文 + 旧光标」
- **环境**：同上；20 并发写 + 20 并发读
- **复现步骤**：多线程并发 `POST /draft`（不同长度文本、不同 cursor）同时 `GET /state`，检查 `cursor <= draft.length` 是否恒成立。
- **预期**：任意一次 `/state` 返回的 `(draft, cursor)` 应是同一次写入的一致性快照。
- **实际**：20 轮采样 `torn=0` **未复现**，但代码层面两次独立 volatile 写之间存在窗口（`draft` 赋值与 `draftCursor` 赋值之间），理论可撕裂。
- **证据**：`qa_run.log` 第 4 行；[LanInputServer.kt](file:///d:/hongguo/HougTime/app/src/main/java/com/houg/tv/net/LanInputServer.kt#L259-L261)。
- **影响面**：极端并发下手机端恢复出的光标位置可能瞬时偏差，下一次输入即自愈；建议合并为一个 `@Volatile data class Draft(text, cursor)`。

### BUG-005（P3 / 优先级：低）返回搜索页后焦点落在软键盘首键而非输入框

- **标题**：`onCreate` 恢复文本/光标后立即 `focusFirstKey()`，焦点不在输入框
- **环境**：静态审查（[SearchActivity.kt:145-153](file:///d:/hongguo/HougTime/app/src/main/java/com/houg/tv/SearchActivity.kt#L145-L153)），未真机执行
- **复现步骤**：电视端搜索页输入「庆余年」→ 光标停在中间 → 进详情 → 返回搜索页。
- **预期**（按验收标准字面）：输入框获得焦点，可直接继续输入。
- **实际**：焦点在软键盘第一个键位上（代码注释为有意设计：遥控器进页面即可直接打字）。
- **影响面**：影响验收项 3 的判定口径；若产品确认该交互即预期，则仅需修订验收标准。
- **状态**：待主策裁决。

### BUG-006（P3 / 优先级：低）手机页面 15s 探活会在电视端绑定期间持续续命，空闲 TTL 实际不生效

- **标题**：`touch()` 对任意请求续命，手机页面常驻时 10 分钟空闲 TTL 永不触发；且 `touch()` 在 sid 校验之前调用
- **环境**：静态审查 + 协议级证据
- **复现步骤**：电视端停留在搜索页（接收方已绑定），手机页面保持打开（每 15s 轮询 `/state`）→ 观察 `lastActiveAt` 是否被刷新；另用错误 sid 持续请求 `/state`。
- **预期**：按「一定时间内有效」语义，空闲应有明确上限；至少错误 sid 的请求不应续命。
- **实际**：`touch()` 位于 [L233](file:///d:/hongguo/HougTime/app/src/main/java/com/houg/tv/net/LanInputServer.kt#L233)（sid 校验之前），只要 `receiverAvailable != false` 即刷新时间戳；15s 探活远小于 10 分钟 TTL，等于永不空闲过期。
- **影响面**：服务与端口在「电视在搜索页 + 手机页面开着」期间长期存活（有界于进程存活），不影响验收项 5 的「解绑后不续命」结论，但与「空闲 10 分钟自动关闭」的表述存在语义差异。**需主策确认**是否接受。

### BUG-007（P3 / 优先级：低）`onPause` 快照与手机提交改文本的极端时序可能保存陈旧文本

- **标题**：Activity 已 paused 时收到手机提交（`onLanKeyword` → `applyKeyword`），快照与文本变更无版本保护
- **环境**：静态审查（[L289-L303](file:///d:/hongguo/HougTime/app/src/main/java/com/houg/tv/SearchActivity.kt#L289-L303)）
- **复现步骤**：电视搜索页按 Home（走 `onPause` 保存快照）→ 手机端提交关键词（主线程仍执行 `setText` + `doSearch`）→ 回到应用。
- **预期**：返回时输入框内容与最后一次操作一致，不出现「提交前文本被恢复」。
- **实际**：`onPause` 已写入快照；若之后发生 Activity 重建，`onCreate` 会用旧快照回填。均为主线程串行，实际窗口很小。
- **影响面**：低概率显示陈旧文本，用户重新输入即恢复。

---

## 5. 静态审查结论（仅审查，未改代码）

| 审查项 | 结论 |
| --- | --- |
| 内存 / 生命周期（泄漏、残留线程） | `WeakReference<LanInputReceiver>` 正确，`unbind` 在 `onDestroy` 调用，`currentReceiver()` 会清理已回收引用，**无 Activity 泄漏**。`LanInputServer` 的 accept 线程与 `cachedThreadPool` 均为 daemon，`stop()` 会关闭 socket 并 `shutdownNow()`，**无残留线程**。注意：TTL 自停后 `LanInputSession.server` 字段不被置空（仅 `port==0`），`ensureStarted` 以 `port != 0` 判断重建，**逻辑自洽、无泄漏**（实测 2642→2646）。 |
| `SearchActivity.onPause` 快照 vs `onLanKeyword` 改文本 | 均为主线程，无真正竞态；但存在「提交在 paused 期间到达 → 快照为旧文本」的极端时序（见 BUG-007）。快照仅存内存，进程被杀后电视侧文本丢失（验收未要求）。 |
| 手机端 JS（sendBeacon / 防抖 / composition） | `pagehide` + `sendBeacon('/draft?sid='+SID, body)` 的 body 为 urlencoded 字符串，浏览器会置 `Content-Type: text/plain`，**服务端不校验 Content-Type，能正常解析**；`sync()` 用 `keepalive` fetch 且带正确 Content-Type。400ms 防抖 + `compositionstart/end` 抑制，符合中文输入法预期。风险点：**正在组合输入时触发 pagehide**，部分浏览器 `el.value` 不含未上屏组合串 → 最后一段可能丢失（P3 级，未实机验证）；`restore()` 仅在 `el.value` 为空时回填，bfcache 返回且本地有旧值时不会覆盖（多设备公用同一 sid 时可能显示旧值）。 |
| `/submit` 允许空 sid | 确认存在（[L266-L272](file:///d:/hongguo/HougTime/app/src/main/java/com/houg/tv/net/LanInputServer.kt#L266-L272)），实测空 sid 提交返回 200 `ok`。会话劫持/串页风险：只能注入搜索关键词，无法读取草稿、无法越权到其他会话；归入 BUG-001 的无鉴权面。 |
| 503 文案与实际 TV 状态一致性 | 一致：`receiverAvailable == false`（Activity `onDestroy` 已 unbind）才回 503，文案「电视已离开搜索页，请先在电视上回到搜索页」与手机端提示「电视已离开搜索页，请回到电视上的搜索页后重试」对应。注意 Activity 仅 `onPause`（未销毁）时接收方仍存在，提交会成功并在后台触发搜索，此时不会 503。 |
| `errorPage` 返回链接行为 | `<a href="/">返回</a>` 会回到同一会话页面（sid 随之重新下发），行为合理；但若 TV 仍在非搜索页，返回后依旧 503，无法自愈 —— 属预期提示闭环。 |
| LAN 内无鉴权草稿接口隐私风险 | **判定 P2（一般）**：同网段可读/写草稿、可注入搜索；数据敏感度为「搜索关键词」，无凭据泄露；sid 32 bit 且无速率限制，可被枚举（8 位十六进制空间有限）。建议 `GET /` 改为必须带 sid 或一次性 ticket，`/submit` 强制 sid，并对 `/state` `/draft` 增加简单频控。 |

---

## 6. 风险与建议

### 风险

1. **UI 链路未真机验证**（中）：验收项 1、3 的端到端表现（弹窗关闭、进详情返回、焦点落点、软键盘遮挡）只有代码与协议级证据，存在 UI 层集成风险。
2. **P2 隐私/完整性面**（中低）：局域网内任意设备可读改草稿、注入搜索（BUG-001）。
3. **交互口径未确认**（中）：焦点落点（BUG-005）、绑定期间探活续命（BUG-006）两项与验收标准字面存在差异，若主策认定为缺陷则需返工。
4. **快照不持久**（低）：进程被杀后电视侧输入内容与手机草稿全部丢失（会话数据仅在内存）。
5. **异常客户端健壮性**（低）：chunked、>64KB 请求存在静默丢数据/连接重置（BUG-002/003）。

### 建议

1. 发布前补 TV 真机冒烟 5 条：① 扫码→输入→提交成功；② 提交后不关弹窗继续输入并再次提交；③ 关弹窗后旧网址仍可提交；④ 进详情→返回，文本+光标恢复；⑤ 电视端退出搜索页后手机端提交得到 503 且提示正确，10 分钟内回搜索页可继续。
2. `GET /` 停止无条件下发 sid；`/submit` 强制 sid；为 `/state` `/draft` 增加简易频控（同 IP 限速）。
3. `/draft` 对无 Content-Length 返回 411，对 >64KB 返回 413 并 drain body，避免连接重置。
4. 将 `draft` + `draftCursor` 合并为单个 `@Volatile` 不可变快照，消除理论撕裂。
5. 手机端 `pagehide` 前若 `composing` 为真，先强制读取一次输入值再同步；`restore()` 在本地已有值时给出「以服务端为准」的冲突处理。
6. 明确产品口径：焦点落点、绑定期间 TTL 续命语义，并同步修订验收标准或代码。

---

## 7. 发布结论

**结论：有条件可发布（Conditional Go）。**

依据：

- 质量门禁：**P0 = 0，P1 = 0**；构建通过；既有 3 套协议探针 125/125；QA 补充用例 56/56（协议 53 + 真 JSON 解析 3），共 182 项断言全通过。
- 5 项验收：**4 项通过**（1、2、4、5），**1 项不通过/待裁决**（3 的「焦点」子项）。
- 核心风险（局域网无鉴权、聚焦口径、chunked/超大包健壮性）均为 P2/P3，不构成阻断。

放行条件（全部满足即可发布）：

1. 主策书面确认「返回搜索页后焦点落在软键盘首键」为预期交互（否则按 BUG-005 修复后复测）；
2. 主策确认「电视端绑定期间手机页面探活可无限续命」可接受（否则按 BUG-006 调整 `touch()` 语义）；
3. TV 真机完成上述 5 条 UI 冒烟且全部通过；
4. BUG-001 给出明确处置（本次修复，或登记下一迭代并知会 PM / 制作人接受风险）。

---

## 8. 主策决策记录（2026-10-25）

| 编号 | 议题 | 主策决策 | 处置 |
| --- | --- | --- | --- |
| D-001 | BUG-001 局域网内无鉴权的 sid 下发与草稿读写面（P2） | **本版不修，登记为已知风险** | 保留 BUG-001 全文与「风险 2」条目；不阻塞本次发布。触发条件为同网段设备主动探测，暴露面仅限搜索关键词，无凭据泄露。后续迭代再评估 `GET /` 强制 sid、`/submit` 强制 sid、`/state` `/draft` 频控三项加固。 |
| D-002 | BUG-006 绑定期间手机页面探活续命，空闲 TTL 实际不生效（P3） | **确认现有语义即预期** | TTL 语义定为：电视端接收方绑定且手机页面开着 → 持续续命；手机页面关闭（无探活）→ 最后一次活动起 10 分钟过期；电视端 `unbind` 后 → 探活不再续命，随即过期。验收标准按此修订，代码不改。 |
| D-003 | BUG-005 返回搜索页后焦点落在软键盘首键（P3） | **保持现状** | 维持 `focusFirstKey()`（遥控器进页面即可直接打字），不改代码；验收项 3 的「焦点」子项按此口径判为通过。 |

**放行条件更新**：条件 1（D-003）、条件 2（D-002）、条件 4（D-001）已由主策裁决关闭，剩余唯一放行条件为条件 3「TV 真机 5 条 UI 冒烟全部通过」。

由此本次结论修订为：**可发布（Go），待 TV 真机 UI 冒烟确认**（P0 = 0、P1 = 0、P2/P3 均已裁决）。

---

## 附：证据与临时文件处置

| 内容 | 路径 | 处置 |
| --- | --- | --- |
| 补充探针运行日志 | `d:\hongguo\HougTime\_jvmprobe\qa_tmp\qa_run.log` | 保留（证据） |
| 真 JSON 校验结果 | `d:\hongguo\HougTime\_jvmprobe\qa_tmp\ps_results.txt` | 保留（证据） |
| `/state` 原始响应样本 | `d:\hongguo\HougTime\_jvmprobe\qa_tmp\out\state_special.json` / `state_big.json` / `state_trim.json` + `expected_*` | 保留（证据） |
| 临时探针源码/脚本/编译产物 | `QaSupplementProbe.java`、`run_qa.ps1`、`out\probe\*.class` | 已清理 |
| 探针误写到工程根的 `out\` 目录 | `d:\hongguo\HougTime\out\` | 已清理 |
| 生产代码 | `app/src/main/**` | **未做任何修改**（只读） |
