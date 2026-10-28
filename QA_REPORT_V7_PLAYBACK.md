# QA 报告 V7 —— 取流接口改版 + CENC 本地明文播放 + 画质调整

**日期**：2026-10-22
**范围**：`sign/PlaybackSigner.java`、`sign/MediaCrypto.java`、`sign/DeviceRegister.java`、`net/NetClient.java`（取流 / 弹幕 / 榜单 / 详情 / 搜索）、`playback/Cenc*`（CENC 解密重封装）、`PlayerActivity.kt`（选档 / 画质切换 / 错误自愈 / 预取 / 弹幕）、`LogActivity.kt` + `activity_log.xml`（协议自检页）、`app/build.gradle.kts`（兼容性配置）
**构建版本**：`versionCode 117 / versionName 1.0.17`，debug APK `12,721,715` 字节（2026-10-22 15:19:33，**已含 D-01 修复**）
**结论**：**不通过（No-Go），但 P0 已解除**。协议链路本体已验证打通（3/3 次在线全链路成功）；原 **P0 阻断级兼容缺陷 D-01 已修复并四重复核通过**（单测 1/1、lint NewApi=0、真实数据离线解密 PASS、APK 重建），Android 5–7 播放链路恢复可用（待真机最终确认）。剩余 **2 个 P1 产品级缺陷**（画质档位实际失效、非 HEVC 设备第 4 集起无源）待产品决策，仍不建议正式发布。详见「十二、D-01 修复复核」。

---

## 一、自动化执行（真实运行记录）

环境：Windows PowerShell 5，显式 JDK 21（`D:\dev\tools\jdk-21`），Android SDK `D:\dev\tools\android-sdk`。
统一前置：`$env:JAVA_HOME="D:\dev\tools\jdk-21"; $env:Path="D:\dev\tools\jdk-21\bin;"+$env:Path`

### 1.1 测试源码清点（Glob 实测）

| 项 | 结果 |
|---|---|
| `app/src/test/**` | **仅 1 个测试类**：`app/src/test/java/com/houg/tv/sign/PlaybackSignerTest.java` |
| 测试资源 | `app/src/test/resources/signing.json`（12 行固定向量） |
| V6 测试桩 `_jvmprobe/QaProbe.java` | **已不存在**（`LS d:\hongguo\_jvmprobe` → File does not exist） |

> 与任务描述"现在有多个测试类"不符：实测全量只有 1 个类、1 个用例。V6 中覆盖搜索三缺陷的 JVM 测试桩已被清理，这本身构成 **P2 回归覆盖缺口**（见缺陷 D-05）。

### 1.2 命令与结果

| 命令 | 结果 | 关键输出 |
|---|---|---|
| `.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug` | ✅ **BUILD SUCCESSFUL in 2s** | 43 tasks（2 executed，41 up-to-date） |
| 单测报告 `app/build/test-results/testDebugUnitTest/TEST-com.houg.tv.sign.PlaybackSignerTest.xml` | ✅ **通过 1 / 失败 0 / 错误 0 / 跳过 0**（0.021s） | 唯一用例 `matchesFixedVectors`（branch/hashF13/gorgon/helios/medusa 12 行向量全对拍） |
| `.\gradlew.bat :app:lintDebug` | ✅ **BUILD SUCCESSFUL in 1m 12s** | 报告 `app/build/reports/lint-results-debug.html` / `.xml`；**共 141 条**：`NewApi = 6`、`InlinedApi = 2` |
| APK `app/build/outputs/apk/debug/app-debug.apk` | ✅ 产出 | 12,721,715 字节，2026-10-22 14:31:05 |

### 1.3 lint NewApi / InlinedApi 逐条明细（Android 5 兼容性的直接证据）

| 类型 | 位置 | 命中内容 |
|---|---|---|
| **NewApi** | `sign/MediaCrypto.java:92` | `java.util.Base64#getDecoder`、`Base64.Decoder#decode`（需 **API 26** 或 core library desugaring，当前 min 21） |
| **NewApi** | `sign/PlaybackSigner.java:192` | 同上（`decode64`） |
| **NewApi** | `sign/PlaybackSigner.java:196` | `java.util.Base64#getEncoder`、`Encoder#encodeToString`（需 **API 26**） |
| InlinedApi | `update/UpdateDialog.kt:119` / `:121` | `Settings#ACTION_MANAGE_UNKNOWN_APP_SOURCES`（API 26 常量；编译期内联，**运行期无害**） |

### 1.4 兼容性配置核查

| 项 | 实测 | 判定 |
|---|---|---|
| `minSdk` | 21 | 名义支持 Android 5.0+ |
| `compileSdk / targetSdk` | 35 / 35 | — |
| `isCoreLibraryDesugaringEnabled` | **未声明** | 缺失 |
| `coreLibraryDesugaring(...)` 依赖 | **不存在** | 缺失 |
| `compileOptions` | Java 8 source/target；`jvmTarget = "1.8"` | 无补丁作用 |
| Media3 版本 | `media3-exoplayer / media3-ui 1.4.1` | 1.4.x 的 minSdk 为 21，**与当前 minSdk 匹配，不构成新限制** |
| `lint { abortOnError = false }` | 已开启容错，但**未显式 disable NewApi** | 故 NewApi 仍被如实报出 |

> **结论性判断（对应"是不是还支持安卓 5 以上"）**：**不再实质支持 Android 5.x–7.x（API 21–25）**。`minSdk=21` 只是编译期声明，`java.util.Base64` 在 API 26（Android 8.0）才加入，且项目未开启 core library desugaring、也未把这两处改写为 `android.util.Base64`，因此 API 21–25 设备一进入播放链路即崩（详见缺陷 D-01）。

---

## 二、在线真实接口验证（B：3 次运行，共 9 次请求）

探针：`d:\hongguo\_probe11\PlaybackProbe.java`（**只读使用，未修改**；输出类落 `d:\hongguo\_probe11\qa-out`，未污染 `HougTime\app\src` 与 gradle 产物）。
编译与运行模板（可照抄，JDK 21）：

```powershell
$env:JAVA_HOME="D:\dev\tools\jdk-21"; $env:Path="D:\dev\tools\jdk-21\bin;"+$env:Path
$ok="C:\Users\Administrator\.gradle\caches\modules-2\files-2.1\com.squareup.okhttp3\okhttp\3.12.13\528e024f340da35cca5d679b631da85d8db9ed\okhttp-3.12.13.jar"
$oi="C:\Users\Administrator\.gradle\caches\modules-2\files-2.1\com.squareup.okio\okio\1.15.0\bc28b5a964c8f5721eb58ee3f3c47a9bcbf4f4d8\okio-1.15.0.jar"
$aj="D:\dev\tools\android-sdk\platforms\android-35\android.jar"
# 编译（-encoding UTF-8 只给 javac）
javac -encoding UTF-8 -cp "$ok;$oi;$aj" -d "D:\hongguo\_probe11\qa-out" `
  "D:\hongguo\_probe11\PlaybackProbe.java" `
  "D:\hongguo\HougTime\app\src\main\java\com\houg\tv\sign\PlaybackSigner.java" `
  "D:\hongguo\HougTime\app\src\main\java\com\houg\tv\sign\MediaCrypto.java" `
  "D:\hongguo\HougTime\app\src\main\java\com\houg\tv\sign\HongguoSm3.java"
"EXIT=$LASTEXITCODE"   # 实测 EXIT=0
# 运行（-Dfile.encoding=UTF-8 只给 java）
java "-Dfile.encoding=UTF-8" -cp "D:\hongguo\_probe11\qa-out;$ok;$oi;$aj" PlaybackProbe
"EXIT=$LASTEXITCODE"   # 实测 EXIT=0
```

### 2.1 三次运行结果

| 轮次 | ① 注册 | ② `video_model/v1` | ③ `fallback_api` GET | ④ `video_list` | ⑤ `decryptUrl` | 退出码 |
|---|---|---|---|---|---|---|
| run1 | 200 / 312B，device_id=1765186919251163 | 200 / **19366B**，code=0 | 200 / **20101B** | 5 档 | 明文 `https://v95-bjb-mc-reading-video.qznovelvod.com/...` ✅ | 0 |
| run2 | 200 / 312B，device_id=2521650919379321 | 200 / **19343B**，code=0 | 200 / **20160B** | 5 档 | `https://v95-bjb-mc-reading-video.qznovelvod.com/...` ✅ | 0 |
| run3 | 200 / 308B，device_id=903169803558362 | 200 / **19162B**，code=0 | 200 / **20098B** | 5 档 | `https://v26-reading-videocdn302.qznovelvod.com/...` ✅ | 0 |

- `video_model` 响应含 `data.video_model`（JSON 字符串，含 `fallback_api`、`video_list`）与 `code=0`；旧 `multi_video_model` 未被使用，改版方向正确。
- `key_seed` 三次均不同（`Rs46cW…` / `SHMjsF…` / `IvysXQ…`），全部成功解出 16 字节内容密钥并解出 `https://` 明文地址 → **`decryptUrl` 链路 3/3 通过**。
- 三次均为真实 CDN 直链（`qznovelvod.com`），未出现 200+空 body；稳定性 3/3。

### 2.2 五档实际编码构成（关键）

| 档位 | height | codec | encrypted | spade_a | 是否可用 |
|---|---|---|---|---|---|
| video_1 | 640 | **bytevc2** | true | true | ❌ 被过滤（AV1） |
| video_2 | 854 | **bytevc2** | true | true | ❌ 被过滤 |
| video_3 | 1024 | **bytevc2** | true | true | ❌ 被过滤 |
| video_4 | 1280 | **bytevc2** | true | true | ❌ 被过滤 |
| video_5 | **1920** | **bytevc1**（HEVC） | true | true | ✅ 唯一可播 |

> 三次运行结论完全一致：**只有 1080p 是 bytevc1，其余 4 档全部是 bytevc2**。这与协议档案记载吻合，也是本轮两个 P1 缺陷的数据基础。

---

## 三、本轮改动逐项验证

| # | 改动 | 证据 | 结论 |
|---|---|---|---|
| 1 | 取流接口改版为唯一 `POST /novel/player/video_model/v1/` | `NetClient.java:813-888`（域名池 + 每域名空体重试）；在线 3/3 返回 code=0 | ✅ 通过 |
| 2 | 三道门槛：空 body 当失败重试 / 必带注册 Cookie / 不补 `x-tt-token` | `NetClient.java:893-937`（`text.isEmpty() return null`、仅 `Cookie`、无 token 头）；`EMPTY_BODY_RETRIES=1`（:94） | ✅ 静态 + 在线通过 |
| 3 | 27 字段播放器档案 `defaultDeviceProfile()` + UA `com.phoenix.read/71332` | `PlaybackSigner.java:29-66`（LinkedHashMap 27 项，顺序即签名顺序）；在线签名被服务端接受 | ✅ 通过 |
| 4 | `fallback_api` 三态兼容 + 必须 https | `NetClient.java:1201-1230`（对象/数组/字符串化 JSON；非 http(s) 抛"播放地址格式异常"）；在线实际为 https | ✅ 通过 |
| 5 | `video_info.data` → `key_seed` + `video_list`（对象，video_1..5） | `NetClient.java:1113-1120`；在线 5 档键序不定仍正确遍历 | ✅ 通过 |
| 6 | `MediaCrypto.decryptUrl(main_url, decode64(key_seed))` | `MediaCrypto.java:27-93`；在线 3/3 解出 https | ✅ 通过（但见 D-01 兼容缺陷） |
| 7 | 编码过滤：跳过 bytevc2/bvc2，保留 bytevc1/h264 | `NetClient.java:1137-1139`（严格相等判断，避免误伤） | ✅ 通过（副作用见 D-03） |
| 8 | 选档：quality_desc→height→vheight→quality→key 降序取 ≤上限最高档，全超限取最低档 | `NetClient.java:1233-1260`（`playbackQuality`）、`:1172-1186` | ✅ 规则实现正确（副作用见 D-02） |
| 9 | 不支持 HEVC 时完全排除 HEVC + 运行期 `disableHevc()` | `NetClient.java:231-314`；`PlayerActivity.kt:260-352`（解码失败→`disableHevc()`→重取流→软解） | ⚠️ 有缺口（见 D-03） |
| 10 | CENC 本地明文播放 | `CencMp4Downloader.java:24-38`（App UA + `Referer: https://novel.snssdk.com/`）；`CencMp4Remuxer.java:8-13,133-141`（encv/bvc1/hev1→hvc1、enca→mp4a、丢弃 senc/saio/saiz/sgpd/sbgp）；`CencSampleDecryptor.java:13`（`AES/CTR/NoPadding`）；`CencMp4Parser.java:67-69,203-220`（senc+saio+saiz 取 IV） | ✅ 代码与协议一致；**真机解密播放未执行**（需设备，见第九节） |
| 11 | 画质调整（AUTO→480P→720P→1080P→原画循环） | `PlayerActivity.kt:1951-1959`（5 档枚举）、`:902-920`（循环 + 清缓存 + 重取流）、`:657-669`（`maxVariantHeight`）、`:675-695`（`cacheTag`）、`:937-943`（`pendingQualityHint` 实际档位提示）、`:949-968`（软硬解切换） | ⚠️ 状态机完整，但**实际只有一档可用**（D-02） |
| 12 | 自检页四段 ①注册→②搜索→③取流→④解密 | `LogActivity.kt:100-190`（四段独立 try/catch，逐行 ✅/❌，写 `SelfCheck` tag）；`activity_log.xml`（`btn_selfcheck` 为 focusable/clickable TextView + `button_bg`） | ✅ 静态通过；按钮异常护栏有缺口（D-07） |
| 13 | 签名离线回归 `PlaybackSignerTest` + `signing.json` | 单测 `tests=1 failures=0`，12 行向量对拍通过 | ✅ 通过 |

---

## 四、其他 part 静态核查（C，均附 文件:行号）

### 4.1 榜单 `NetClient.ranking`

| 核查点 | 证据 | 结论 |
|---|---|---|
| URL 拼装 | `NetClient.java:519-527`：`WEB_SITE + "/rank/" + board.path + (page > 1 ? "?page=" + page : "")` | ✅ 第 1 页不加 `?page=`，符合网页行为 |
| 免签名 | 走 `getText`（`:2081`）网页 GET，无签名头 | ✅ 与协议档案一致，不受取流改版影响 |
| 分页 | `:527-553` 解析 `pageNum/totalPages` 与 `hasMore`；`RANK_PAGE_SIZE=20`、`RANK_MAX_PAGE=500`（`:66-70`） | ✅ 上限有护栏 |
| SSR 双引号/单引号 | `:561-600` 先单引号 `run-router-data-fn`，后双引号 `data-fn-args`（mergeLoaderData / routerDataFnArgs[0]） | ✅ 两种形态都覆盖 |
| 行解析 | `:600-672`：`id.matches("\\d{10,30}")`、`rank` 区间校验、`JSONTokener` 宽松解析 | ✅ 防脏数据 |
| 域名轮换 / 降级 | `HostPool.java:29-91`（6 域名，成功归位、失败无条件 CAS 推进） | ✅ 逻辑正确 |
| 在线验证 | 本轮未执行（请求预算全部用于播放链路） | ⚠️ 需真机/联网验证 |

### 4.2 弹幕 `NetClient.danmaku`

| 核查点 | 证据 | 结论 |
|---|---|---|
| 评论专用签名 | `:1374-1400` `appPost(..., true)`；`HongguoCommentSign.java`（普通 X-Gorgon 会 110001） | ✅ 与协议一致 |
| 新窗口推进 | `:1406-1424`：`data.common_list_info.cursor`（字符串化 JSON）→ `end_offset_time`；`danmaku_count` 取总数 | ✅ 已按改版实现 |
| 三层兜底 | `:1430-1436`：cursor → 旧字段 `extra.next_query_danmaku_list_time` → `startMs + WINDOW_MS`（30s，`:2354`） | ✅ 顺序正确 |
| 非法推进防护 | `nextMs <= startMs` 时强制 `startMs + WINDOW_MS`；`durationMs > 0` 时 `min(nextMs, durationMs)` 末窗截断 | ✅ 无死循环风险 |
| 解析与去重 | `:1440-1482`：`data.data_list[].comment.common.content.text`、`status=="1"`、`expand.offset_time`、按窗口过滤 + 去重 + 排序；`totalCount` 仅日志 | ✅ |
| 轨道几何 | `DanmakuView.kt:216-237` `pickLane`：只有同轨道上一条右边缘让开屏宽 + `gap` 才可用，全占满返回 null | ✅ 不摘在途弹幕 |
| 在途不强行摘除 | `:200-214` `spawnDue`：无轨道则 `break` 等下一帧（`DROP_AFTER_MS=3000`） | ✅ |
| seek 重基准 | `:87-104` `setPositionMs`：位置跳变 > `JUMP_TOLERANCE_MS=3000` → `flying.clear()` + 指针前推 | ✅ |
| 失败窗口重试 | `PlayerActivity.kt:1035-1046`、`:1150-1172`：窗口失败只记日志，但 `lastDanmakuWindow` 已推进 | ⚠️ 该 30s 窗口弹幕永久缺失（D-08） |
| 在线验证 | 未执行 | ⚠️ 需真机 |

### 4.3 详情 `NetClient.detail`

| 核查点 | 证据 | 结论 |
|---|---|---|
| 双路回退 | `:681-700`：App `video_detail/v1` 空/异常 → `webDetail`（`/detail?series_id=`）；两路都空抛"该内容可能已下架或暂时无法观看" | ✅ |
| 误伤风险 | `webDetail` 读 `seriesDetail.vid_list` 字符串数组、集号=下标+1（`:720-777`） | ⚠️ 若网页结构变更会整体判"下架"，文案会误导；需真机验证 |
| 在线验证 | 未执行（自检页②③路径会实际打到该接口，需真机跑一次） | ⚠️ 需真机 |

### 4.4 搜索 `NetClient.search`（V6 三缺陷回归）

| 核查点 | 证据 | 结论 |
|---|---|---|
| 三路合一 | `:1500-1535`：建议接口 + 网页 `/search/<kw>` + `completeSeasons`，单路失败 try/catch 忽略 | ✅ |
| 建议接口过滤 | `:1566-1618`：`app_id=8662`、仅 `word_type=="short_play_name"`、`video_data`、`isNumericId` | ✅ |
| 网页只取 `searchList` | `:1538-1559`：校验返回 `query` 与输入一致，防串词 | ✅ |
| 去重 | `mergeDramas`（`:1621+`）：按 id 去重、保留字段更全者 | ✅ |
| V6 缺陷①复合中文数字 | `:1865-1892` 已按 `digit/number` 分离实现；注释明确"十四→104"是回归点 | ✅ 代码仍为修复态 |
| V6 缺陷②排序全序 | `:1942-1966` 词典序全序键（匹配度→基础名→单位→季数→id） | ✅ 代码仍为修复态 |
| V6 缺陷③季数正则锚点 | `SEASON_MARK` 含 `\s*$`（`:60-64` 区域），`parseSeason` 依赖它（`:1813-1834`） | ✅ 代码仍为修复态 |
| **回归覆盖** | `app/src/test` 仅 `PlaybackSignerTest`，V6 的 `QaProbe` 已删除 | ❌ **三缺陷当前无自动化回归**（D-05） |

### 4.5 UI / TV 交互（自检页）

| 核查点 | 证据 | 结论 |
|---|---|---|
| 控件类型 | `activity_log.xml`：`btn_selfcheck` 为 `TextView` + `focusable="true"` + `clickable="true"` | ✅ TV 可聚焦 |
| 焦点态 | `res/drawable/button_bg.xml:4-9` 含 `state_focused`（品牌红实心 + 10dp 圆角） | ✅ 焦点可见 |
| 遥控方向键可达 | 与 `btn_copy/btn_share/btn_clear` 同排；`btn_upload` 显式 `nextFocusUp/Down=@id/btn_back`，链路较短可能"跳过"同行按钮 | ⚠️ 逻辑上默认可达，**需真机按方向键确认**（D-10） |
| 重复点击防护 | `LogActivity.kt:108-111`：`if (!btn.isEnabled) return`；禁用 + 文案"自检中…" | ✅ |
| 线程模型 | `:113-114` `scope.launch { withContext(Dispatchers.IO) { selfCheck() } }`，结果回主线程更新 UI；`:onDestroy` `scope.cancel()` | ✅ 无 NetworkOnMainThread、无泄漏 |
| 异常护栏 | `:126-127` `HttpClientProvider.get()` / `NetClient(client)` 位于 try **之外**，抛异常则协程直接失败，`:117-118` 的按钮恢复不执行 | ⚠️ 按钮停留在"自检中…"直到页面销毁（D-07） |
| 四段独立捕获 | `:130-190` 每段 `try/catch Throwable`，单段失败不影响其余 | ✅ |

---

## 五、兼容性 / 健壮性专项（D）

| 专项 | 证据 | 结论 |
|---|---|---|
| 200+空 body 重试 | `NetClient.java:93-94`（`EMPTY_BODY_RETRIES=1`，每域名共 2 次）、`:843-866`、`:2003-2031`；注释说明"空 body 按 path 维度出现" | ✅ 实现正确；在线 3 次未触发 |
| 空变体错误文案 vs "只有 1080p 是 bytevc1" | 文案 `:1088`"红果未返回兼容的媒体（可能仅剩 bytevc2 编码）"属**旧 `appVideoModel` 路径**；fallback 路径的同类错误是 `:1168`"没有电视可播放的清晰度" | ⚠️ **不矛盾**：线上 fallback 保留唯一 bytevc1 1080p，故不抛 1088。真正后果是"只剩 1 档"（D-02）。但两条路径文案分叉，用户会看到两种无源提示（D-09，P3） |
| 画质上限与真实数据 | 实测 5 档仅 1920 为 bytevc1；`:1172-1186` 在 `maxHeight=480/720/1080` 时无 ≤上限候选 → `selected = variants.get(size-1)` 取最低档 = 唯一 1920 | ⚠️ 480P/720P/低端 AUTO(720) 全部回落 1080P（D-02，P1） |
| 非 HEVC 设备 | `:231-314`：`avcOnly` 非空才过滤 HEVC；**无 AVC 时保留 HEVC**。线上不存在 AVC 变体 → 过滤不生效 → 仍下发 HEVC | ⚠️ 解码失败 → `disableHevc()` 重取仍是同一 HEVC → 退 `webVideoModel` 360p（`:1318-1333`，仅前几集免费）→ 第 4 集起无源（D-03，P1） |
| 缓存键 | `PlayerActivity.kt:675-695`：`clear_<vid>_<tag>.mp4`，tag = `auto/auto720/480/720/1080/full`；`findCachedClearFile` 要求 >1024 字节 | ✅ 键含 vid 与档位，无冲突 |
| 切档清缓存范围 | `:902-913` `cycleQuality` → `cleanupClearFiles(keep = null)`；`:1361-1375` keep 为 null 时删除 cacheDir 下**所有** `clear_` 文件（含其他 vid、其他档位） | ⚠️ 与注释"清预加载窗口"不符，预取成果全废（D-04，P2） |
| 磁盘上限 | `cleanupClearFiles` 无大小/数量上限，仅在切档与 `onDestroy` 清理 | ⚠️ 退到后台未销毁时若持续预取，空间随集数增长；需真机观察（待验证项） |
| CENC 明文残留 | 明文写在 `cacheDir`（应用私有目录）；`onDestroy`（`:1861-1868`）调 `cleanupClearFiles()` 删除全部 `clear_` 文件 | ✅ 退出即清理；会话期私有目录可接受 |
| HostPool | `HostPool.java:29-91`：6 个已验证域名、失败无条件 CAS 推进、成功归位 | ✅ |
| 超时 | `HttpClientProvider.kt:45-47`：connect 8s / read 20s / write 20s；`followRedirects(true)` | ✅ 与本轮接口耗时匹配（探针 12s connect 下 3/3 成功） |
| IPv4 优先 DNS | `HttpClientProvider.kt:23-31,40`：`Ipv4FirstDns` 注入唯一 `OkHttpClient` 单例；`NetClient`/`DeviceRegister`/`CencMp4Downloader`/`UpdateChecker`/`LogUploader` 均取自该单例 | ✅ 全通道生效 |
| 并发与主线程 | 网络全部在 `Dispatchers.IO`（`PlayerActivity.kt:616/625/631/1083/1155`、`LogActivity.kt:114` 等）；`onDestroy` 取消 `scope` + `handler.removeCallbacksAndMessages(null)` | ✅ 未见 NetworkOnMainThread 与 Activity 泄漏 |
| 内存峰值（CENC） | `CencMp4Downloader.java:36` 返回**整段** `resp.body().bytes()`；`prepareClearFile`（`PlayerActivity.kt:698-760`）再 remux 出一份字节数组 | ⚠️ 单集峰值约 2× 文件大小；1080p 短剧在 1GB 内存盒子上有 OOM 风险（D-06，P2；需真机验证） |

---

## 六、缺陷清单（严格按可复现格式）

### D-01（P0，高）Android 5–7 上播放链路必崩：`java.util.Base64` 需 API 26

- **标题**：`PlaybackSigner` / `MediaCrypto` 使用 `java.util.Base64`，在 API 21–25 设备上抛 `NoSuchMethodError` / `ExceptionInInitializerError`，播放与自检全链路不可用
- **严重度**：P0（阻断）
- **优先级**：高（声明 minSdk=21，覆盖 Android 5.0/5.1/6.0/7.0/7.1 电视盒子，影响面 100%）
- **环境**：Android TV 盒子，Android 5.1 / 6.0 / 7.1（API 22/23/25）；构建 1.0.17 (117) debug；项目未开启 core library desugaring
- **复现步骤**：
  1. 在 Android 5.1（或 6.0/7.1）设备安装本 APK；
  2. 进入任意剧集详情，点击播放；
  3. 观察 `AppLog` / logcat。
  - 备选复现：进入日志页点击"协议自检"按钮。
- **预期**：正常取流并播放；自检四段返回 ✅/❌ 结果文本。
- **实际**：`PlaybackSigner` 类静态初始化（`PlaybackSigner.java:72` 的 `SBOX = decode64(...)`）即调用 `java.util.Base64.getDecoder()`；`MediaCrypto.decode64`（`MediaCrypto.java:92`）同样调用。API 26 以下该方法不存在 → 类初始化失败，取流/解密直接抛错，播放无法开始。
- **证据**：
  - lint `NewApi` 6 条：`MediaCrypto.java:92`、`PlaybackSigner.java:192`、`PlaybackSigner.java:196`（`app/build/reports/lint-results-debug.xml`）；
  - `app/build.gradle.kts` 无 `isCoreLibraryDesugaringEnabled` / `desugar_jdk_libs`；
  - 同项目已知同类事故：`HongguoCommentSign.java:138-140` 注释"java.util.Base64 是 API 26+ 才有，Android 7 以下会 NoClassDefFoundError 闪退（日志实锤：MagicBox_M16S API 22 拉弹幕即崩）。统一用 android.util.Base64"——本轮新增代码未沿用该结论。
- **建议修复（建议补丁，未实施）**：二选一。
  1. 开启核心库脱糖（推荐，一次覆盖全部 Java 8+ API）：
     ```kotlin
     compileOptions {
         isCoreLibraryDesugaringEnabled = true
         sourceCompatibility = JavaVersion.VERSION_1_8
         targetCompatibility = JavaVersion.VERSION_1_8
     }
     dependencies { coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.4") }
     ```
  2. 或改写为 `android.util.Base64`（保持 `-`/`_` 归一后 `decode(s, Base64.DEFAULT)`；编码用 `encodeToString(data, Base64.NO_WRAP)`），并为 JVM 单测保留可注入实现，避免 `PlaybackSignerTest` 在桌面 JVM 上缺少 `android.util.Base64`。
- **状态**：**已修复（2026-10-22 15:19），详见第十二章复核记录**。实际采用的方案是第三种（本报告未列）：新建项目内纯 Java `B64` 工具类，替换全部 `java.util.Base64` 调用，从而同时满足「API 21 可用」「JVM 单测可跑」「不引入 desugar 依赖」三个约束。

### D-02（P1，高）画质档位实际失效：线上仅 1080p(HEVC) 可播，480P/720P/AUTO 均回落 1080P

- **标题**：画质菜单可在 5 档间循环，但实际取流始终是 1920/bytevc1，用户无法降档，流量与解码压力不可控
- **严重度**：P1（严重）
- **优先级**：高（所有用户可见，每次播放都命中）
- **环境**：任意设备 + 1.0.17 (117)；真实接口（2026-10-22 三次采样一致）
- **复现步骤**：
  1. 播放任意剧集；
  2. 连按画质键，依次切到 480P、720P、1080P、原画；
  3. 观察 `AppLog` 的 `fallback 取得 N 档，选中 codec=… height=…` 与 toast 实际档位提示。
- **预期**：请求 480P 时选 640/854 档（h264 或 HEVC 均可）；请求 720P 时选 1024/1280 档；1080P 选 1920 档。
- **实际**：`fallbackStreamModel` 先过滤 bytevc2（`NetClient.java:1137-1139`），线上仅剩 1920/bytevc1 一档；`maxHeight=480/720/1080` 找不到 ≤上限候选，走 `selected = variants.get(variants.size()-1)`（`:1184-1186`）取唯一档 → 恒为 1080P。`cycleQuality`（`PlayerActivity.kt:902-920`）除换 `cacheTag` 与重下同一清晰度外无实际效果。
- **证据**：`_probe11/qa-out/run1.txt`、`run2.txt`、`run3.txt`（三次均为 `video_5 h=1920 bytevc1`，`video_1..4` 为 bytevc2）；`NetClient.java:1172-1186`。
- **建议修复**：
  - 短期：画质菜单对"仅单档可用"做诚实反馈（toast"当前资源仅提供 1080P"），避免用户误以为切换生效；
  - 中期：对 bytevc2 增加 AV1 软解/系统解码探测，或向上游请求 `need_all_video_definition=true` 时明确要 h264 档（需主策确认产品取舍）；
  - 兜底策略保留 `selected==null → 最低档`，但需同时记录诊断日志（已具备）。
- **状态**：**已修复（2026-10-22，第十三章）**。按 HGTV 源码结论，"放开 bytevc2 解码"在 Android 上属容器解析层不可行（MP4 extractor 只出音频轨），故走用户方案的后半句：**清晰度菜单只显示本集实际可用档位**，并对全超限回落标注"（兼容资源）"。

### D-03（P1，中）不支持 HEVC 的设备第 4 集起无源可播

- **标题**：设备无 HEVC 硬解时，`sortVariantsForDevice` 在无 AVC 变体情况下仍保留 HEVC，`disableHevc()` 重取无效，最终只能退网页 360p（仅前几集免费）
- **严重度**：P1（严重）
- **优先级**：中（影响不支持 HEVC 的老盒子；HEVC 在近 5 年盒子上较普及，但老设备 100% 复现）
- **环境**：不支持 HEVC 解码的 Android TV 盒子（如部分 Amlogic S905 早期固件、海思老方案）；1.0.17 (117)
- **复现步骤**：
  1. 在不支持 HEVC 的设备进入某剧第 1 集 → 可退网页 360p 播放；
  2. 切到第 4 集及以后 → 网页 360p 不存在；
  3. 观察错误提示与 `AppLog`。
- **预期**：无 HEVC 能力时应拿到 AVC 档；无 AVC 档时应提示"该设备暂不支持此片源"，而不是反复重试后仍失败。
- **实际**：`NetClient.java:231-314` 只有 `avcOnly` 非空才排掉 HEVC；线上 5 档无 AVC → HEVC 被保留 → 解码失败 → `PlayerActivity.kt:260-352` 调 `disableHevc()` 重取，仍返回同一 HEVC → 再失败 → `webVideoModel` 探 360p（`:1318-1333`），第 4 集起 404 → 抛"该集未开放免费观看"，文案与真实原因不符。
- **证据**：`NetClient.java:231-314`、`:1318-1333`、`PlayerActivity.kt:260-352`；在线 5 档均无 AVC。
- **建议修复**：`disabledHevc` 状态下若无 AVC 且 HEVC 已否决，直接抛明确错误（"当前片源仅 HEVC，本设备不支持"），并引导用户换设备/反馈；不要在每次播放时做 3 次无效重试。
- **状态**：**已修复（2026-10-22，第十三章）**。按 HGTV 源码思路补两层：硬解 `DefaultRenderersFactory.setEnableDecoderFallback(true)` 让解码器失败时自动换下一个（含软解）；`STATE_READY` 检查 `currentTracks.isTypeSelected(C.TRACK_TYPE_VIDEO)`，无视频轨（audio-only 黑屏假播）即停播给可读错误。原有两级自愈（排 HEVC 重取流 → 切软解）保留。

### D-04（P2，中）切档清缓存范围过大：删除所有 `clear_` 文件（含其他剧集）

- **标题**：`cycleQuality()` 以 `keep = null` 调用清理，实际清空 cacheDir 下全部 `clear_*.mp4`，预取成果全部作废
- **严重度**：P2（一般）
- **优先级**：中（每次切档触发，造成重复下载与流量浪费，但不影响正确性）
- **环境**：任意设备；1.0.17 (117)
- **复现步骤**：播放某剧 → 等预取完成（AppLog 出现预取成功）→ 按画质键切档 → 再切回 → 观察 cacheDir 与再次播放耗时。
- **预期**：只清理当前 vid 的旧档位文件（或当前预加载窗口）。
- **实际**：`PlayerActivity.kt:902-913` → `cleanupClearFiles(keep = null)`；`:1361-1375` 对 `keep == null` 删除所有带 `clear_` 前缀文件，包含其他 vid 与其他档位。
- **证据**：`PlayerActivity.kt:908`、`:1361-1375`。
- **建议修复**：改为 `cleanupClearFilesExceptVid(vid, keepTag = 新 tag)`，仅删当前 vid 的非目标档位。
- **状态**：**已修复（2026-10-22，第十三章）**。新增 `clearVidCacheFiles(vid)`（按文件名第一个 `_` 精确切出 vid，防 vid=123 误删 vid=1234），`cycleQuality()` 只清当前集的旧档缓存，预加载窗口内其他集的缓存全部保留。

### D-05（P2，中）自动化回归覆盖缺口：全量仅 1 个测试类，搜索三缺陷无回归

- **标题**：`app/src/test` 仅 `PlaybackSignerTest`，V6 已修复的搜索三缺陷（复合中文数字/排序全序/季数正则锚点）无任何自动化回归
- **严重度**：P2（一般，质量保障能力缺口）
- **优先级**：中（本轮改动虽不涉及搜索，但后续任何重构都可能静默回退）
- **环境**：仓库源码；1.0.17 (117)
- **复现步骤**：Glob `app/src/test/**`；执行 `.\gradlew.bat :app:testDebugUnitTest`。
- **预期**：存在覆盖 `parseSeason` / `chineseOrArabic` / `sortSearchResult` 的单测，且随全量单测执行。
- **实际**：仅 1 个类 1 个用例；V6 使用的 `_jvmprobe/QaProbe.java` 已不存在；三处修复仍在代码中（`NetClient.java:1865-1892`、`:1942-1966`、`SEASON_MARK`），但无测试守护。
- **证据**：Glob 结果；`TEST-com.houg.tv.sign.PlaybackSignerTest.xml`（tests=1）。
- **建议修复**：把 V6 的 17+21+10+8+16 例断言迁入 `app/src/test/java/com/houg/tv/net/NetClientSearchTest.java`（需将 `parseSeason/chineseOrArabic/sortSearchResult` 提升为包内可见或抽纯函数）。
- **状态**：新建

### D-06（P2，中）CENC 下载整段入内存，低内存盒子存在 OOM 风险

- **标题**：`CencMp4Downloader.download` 返回完整 `byte[]`，remux 再生成一份，单集峰值约 2× 文件大小
- **严重度**：P2（一般）
- **优先级**：中（需真机确认；1GB 内存盒子风险最高）
- **环境**：1GB/1.5GB RAM 电视盒子；1080p 单集（约 130s）
- **复现步骤**：在低内存盒子连续播放 1080p 剧集并触发预取（`PREFETCH_AHEAD=5`），观察 `AppLog` 是否出现 `OutOfMemoryError` / `prepareClearFile` 失败。
- **预期**：内存峰值与文件大小无关（流式写盘）。
- **实际**：`CencMp4Downloader.java:36` 一次性 `resp.body().bytes()`；`PlayerActivity.kt:698-760` 的 remux 再持有输入/输出两份。`playCurrent` 已 catch `Throwable`（含 OOM），不会崩溃，但表现为"下载或解密失败"。
- **证据**：`CencMp4Downloader.java:36`、`PlayerActivity.kt:698-760`。
- **建议修复**：改为流式下载到临时文件 + 分块解密；或对变体大小设阈值，超限时降级为直连播放（若上游支持）。
- **状态**：新建

### D-07（P3，低）自检页构造异常会导致按钮永久停留"自检中…"

- **标题**：`selfCheck()` 中 `HttpClientProvider.get()` / `NetClient(client)` 位于 try 之外，异常时按钮恢复逻辑不执行
- **严重度**：P3（轻微）
- **优先级**：低（发生概率低，且退出页面即恢复）
- **环境**：任意设备；1.0.17 (117)
- **复现步骤**：构造 `HttpClientProvider.get()` 抛异常的条件（如 OkHttp 初始化被破坏/极端内存不足），点击"协议自检"。
- **预期**：任何异常都被捕获并输出"❌ 初始化失败"，按钮恢复可点击。
- **实际**：`LogActivity.kt:126-127` 在 try 外；异常使 `scope.launch` 协程失败，`:117-118` 不执行，按钮保持禁用 + "自检中…"。
- **证据**：`LogActivity.kt:107-127`。
- **建议修复**：把 `:126-127` 移入 try，或在 `scope.launch` 外层加 `try/finally { btn.isEnabled = true; btn.text = ... }`。
- **状态**：新建

### D-08（P3，低）弹幕窗口请求失败后不重试，该 30s 窗口永久缺失

- **标题**：`fetchDanmaku` 失败仅记日志，已推进的 `lastDanmakuWindow` 导致同一窗口不再请求
- **严重度**：P3（轻微）
- **优先级**：低（仅弱网/单次抖动时出现）
- **环境**：弱网电视盒子；1.0.17 (117)
- **复现步骤**：播放中制造一次网络抖动（路由器瞬断 1s），观察该 30s 区间弹幕是否缺失且不补。
- **预期**：失败窗口在下次 tick 重试一次。
- **实际**：`PlayerActivity.kt:1035-1046` 先推进 `lastDanmakuWindow`，`:1150-1172` 失败只记日志；`danmakuFetchedWindows` 仅成功才加入，但窗口指针已前进，实际不再请求。
- **证据**：`PlayerActivity.kt:1035-1046`、`:1150-1172`。
- **建议修复**：失败时回退 `lastDanmakuWindow` 或加入一次性重试队列。
- **状态**：**已修复（2026-10-22，第十三章）**。新增失败计次与 5s 退避：`updateDanmaku` 未到重试时间不推进 `lastDanmakuWindow`；`fetchDanmaku` 失败计次并重置窗口指针让 500ms 轮询重入，最多重试 3 次，成功即清；切集/重开弹幕/跳转 seek 三处清点同步清两个 map。

### D-09（P3，低）两条无源路径文案分叉

- **标题**：旧 App 路径抛"红果未返回兼容的媒体（可能仅剩 bytevc2 编码）"，fallback 路径抛"没有电视可播放的清晰度"
- **严重度**：P3（轻微）
- **优先级**：低
- **环境**：全设备；1.0.17 (117)
- **复现步骤**：构造某集仅含 bytevc2 变体（或服务端下发异常），分别走 `appVideoModel` 与 `fallbackStreamModel`。
- **预期**：统一一句面向用户的提示。
- **实际**：`NetClient.java:1088` 与 `:1168` 文案不同，且前者含技术术语（bytevc2），不适合 TV 用户。
- **证据**：`NetClient.java:1088`、`:1168`。
- **建议修复**：抽 `noPlayableSourceMessage()` 统一文案，技术细节仅写 AppLog。
- **状态**：新建

### D-10（P3，低）自检页方向键链路需真机确认

- **标题**：`btn_upload` 的 `nextFocusUp/Down` 显式指向 `btn_back`，同排 `btn_selfcheck` 无显式 nextFocus
- **严重度**：P3（轻微）
- **优先级**：低
- **环境**：Android TV 遥控器；1.0.17 (117)
- **复现步骤**：日志页从标题区按方向键在 6 个按钮间移动，重点观察从 `btn_upload` 向下/向上是否跳过 `btn_copy/btn_share/btn_selfcheck/btn_clear`。
- **预期**：方向键可依次到达每个按钮，焦点态高亮清晰。
- **实际**：静态看默认焦点搜索可达（同为 focusable/clickable，`button_bg.xml:4-9` 有 `state_focused`），但显式 nextFocus 可能造成跳序；无法在 JVM 侧验证。
- **证据**：`activity_log.xml`（`btn_upload` 的 `nextFocusUp/Down`）、`drawable/button_bg.xml:4-9`。
- **建议修复**：为同行按钮补齐 `nextFocusLeft/Right`，或移除 `btn_upload` 的跨行 nextFocus。
- **状态**：新建（待真机验证）

---

## 七、静态审查发现（未改，记录在案）

| # | 位置 | 观察 | 评估 |
|---|---|---|---|
| A | `NetClient.java:868-888` | 取流主路径失败后仍会请求旧 `multi_video_model/preload/v1/` 兜底（注释称 2026-10-14 已下线） | 多一次无效往返（约 +8s 最坏），**建议下线**；不影响正确性 |
| B | `NetClient.java:894-937` | `playbackPost` 对 200+空 body 返回 null，交由上层按 path 维度重试 | 与协议档案一致，正确 |
| C | `NetClient.java:948-971` | `extractVideoModel` 按 vid 精确匹配，防串集 | 正确，避免多集串流 |
| D | `PlaybackSigner.java:36-66` | 27 字段使用 `LinkedHashMap`，注释明确"绝不能改 TreeMap" | 与签名顺序强耦合，后续改动需回归 `signing.json` |
| E | `MediaCrypto.java:33-35` | `deriveKey` 对 `raw.length` 与 `mixed` 做边界校验，"内容密钥过短"明确抛错 | 健壮 |
| F | `CencMp4Remuxer.java:133-141` | `encv/bvc1/hev1 → hvc1`、`enca → mp4a`，并在 stbl 丢弃 `senc/saio/saiz/sgpd/sbgp` | 与协议档案逐项一致 |
| G | `CencSampleDecryptor.java:13` | `AES/CTR/NoPadding`，符合 cenc-aes-ctr | 正确 |
| H | `PlayerActivity.kt:657-669` | `AUTO` 档低端设备压 720（`deviceCapHeight`），否则不设上限 | 设计合理，但因 D-02 实际无降档效果 |
| I | `PlayerActivity.kt:949-968` | 软硬解切换持久化 `PREF_DECODER_SOFT` 并重建播放器 | 正确；解码失败链的最终兜底 |
| J | `HttpClientProvider.kt:13-31` | IPv4 优先 DNS 的取舍有实测依据（8+8 次），并保底返回全部记录 | 合理 |
| K | `UpdateDialog.kt:119/121` | `ACTION_MANAGE_UNKNOWN_APP_SOURCES` 被 lint 报 InlinedApi | **无害**：编译期内联字符串常量，运行期不触发 API 检查 |

---

## 八、测试计划与用例（本轮实际执行口径）

分层：**L1 接口签名离线对拍 → L2 在线链路 → L3 播放/解码 → L4 UI/TV 交互 → L5 兼容性/健壮性**。
执行手段：JVM 单测（gradle）、在线探针（JVM + OKHttp）、Android Lint、静态代码审查、真机人工（未执行项明确标注）。

| 用例 | 层 | 前置 | 步骤 | 期望 | 实际 | 状态 |
|---|---|---|---|---|---|---|
| TC-01 | L1 | JDK21 | `:app:testDebugUnitTest` | 12 行签名向量全对拍 | 1 类 1 用例，failures=0 | ✅ 通过 |
| TC-02 | L1 | 同上 | 检查 `signing.json` 覆盖 branch/hashF13/gorgon/helios/medusa | 五类签名均有向量 | 12 行均覆盖 | ✅ 通过 |
| TC-03 | L2 | 联网 | 探针①设备注册 | 200 + device_id/install_id + Set-Cookie | 3/3 均 200（312/312/308B） | ✅ 通过 |
| TC-04 | L2 | 同上 | 探针②`POST /novel/player/video_model/v1/`（27 字段 + Cookie，无 x-tt-token） | 200、code=0、含 `video_model`/`fallback_api` | 3/3 code=0（19162–19366B） | ✅ 通过 |
| TC-05 | L2 | 同上 | 探针③`fallback_api` GET | 200、`video_info.data` 含 `key_seed`/`video_list` | 3/3 200（20098–20160B） | ✅ 通过 |
| TC-06 | L2 | 同上 | 解析 5 档并核对 codec | 5 档齐全，bytevc2 可识别 | 640/854/1024/1280 为 bytevc2，1920 为 bytevc1 | ✅ 通过 |
| TC-07 | L2 | 同上 | `decryptUrl(main_url, decode64(key_seed))` | 得到 `https://` 明文 CDN | 3/3 成功（qznovelvod.com） | ✅ 通过 |
| TC-08 | L2 | 同上 | 连续 3 轮稳定性 | 无 200+空 body、无超时 | 3/3 稳定（共 9 次请求） | ✅ 通过 |
| TC-09 | L3 | 真机 + 4K/1080p 盒 | 播放 1080p（唯一 bytevc1 档），确认 CENC 下载→remux→本地起播 | 本地 `clear_*` 文件生成，Media3 播放成功、无花屏 | 未执行 | ⏳ 待真机 |
| TC-10 | L3 | 真机 | 播放中按画质键 5 档循环 | toast 与实际档位一致，切档后重新起播 | 静态：恒 1080P（D-02） | ⚠️ 失败（静态可判定） |
| TC-11 | L3 | 真机（不支持 HEVC） | 连播第 1→4 集 | 可退网页 360p 或明确提示 | 静态：第 4 集起无源（D-03） | ⚠️ 失败（静态可判定） |
| TC-12 | L3 | 真机 | 拔网/限速触发解码失败 → 观察自愈 | 删缓存→重取流→软解→明确报错 | 代码链路完整（`PlayerActivity.kt:260-352`） | ⏳ 待真机 |
| TC-13 | L3 | 真机 | 暂停/恢复、seek、切集后弹幕 | 不发老弹幕、不重复、位置正确 | 静态：三层兜底 + 重基准已实现 | ⏳ 待真机 |
| TC-14 | L4 | 真机遥控器 | 日志页方向键遍历 6 按钮，确认自检按钮可聚焦/点击，焦点高亮 | 可达、有焦点态、可点击 | 静态可聚焦，链路待确认（D-10） | ⏳ 待真机 |
| TC-15 | L4 | 真机 | 连续快速点击自检按钮 | 仅一次执行，按钮禁用+"自检中…"，完成后恢复 | 有 `isEnabled` 护栏（`LogActivity.kt:108-111`） | ⏳ 待真机 |
| TC-16 | L4 | 真机 | 自检中旋转/退出页面 | 无崩溃、无泄漏 | `onDestroy` 取消 scope | ⏳ 待真机 |
| TC-17 | L5 | lint | `:app:lintDebug` | 无 NewApi 命中 | 修复前 NewApi=6（`java.util.Base64`）→ **修复后 NewApi=0** | ✅ 通过（D-01 已修） |
| TC-18 | L5 | 代码审查 | 检查 desugaring 配置 | 已开启或全量替换高 API | **已全量替换**为项目内 `B64`（未开 desugaring，亦无需） | ✅ 通过（D-01 已修） |
| TC-19 | L5 | 真机 API 21–25 | 安装并播放 | 可播放 | 静态证据链已闭合（lint 0 + 单测 + 真实数据回归），真机待验 | ⏳ 待真机复核 |
| TC-20 | L5 | 真机 API 26+ | 安装并播放 | 可播放 | 未执行 | ⏳ 待真机 |
| TC-21 | L5 | 真机低内存盒 | 1080p 连续预取 5 集 | 无 OOM、无失败 | 静态存在内存峰值风险（D-06） | ⏳ 待真机 |
| TC-22 | L5 | 真机弱网 | 弹幕窗口失败一次 | 下个窗口恢复、缺失可容忍 | 失败窗口不补（D-08） | ⚠️ 已知缺陷 |
| TC-23 | L2 | 联网 | 榜单 4 个 board × 5 页 | 20 条/页、totalPages 正确 | 本轮未执行（请求预算用于播放） | ⏳ 待联网 |
| TC-24 | L2 | 联网 | 详情 App→web 双路 | 正常剧集两路均能出集列表 | 本轮未执行 | ⏳ 待联网 |
| TC-25 | L2 | 联网 | 搜索「总裁/闪婚/离婚」 | 三路合一、多季聚簇、>10 条 | 本轮未执行；代码仍为 V6 修复态 | ⏳ 待联网 |

---

## 九、待真机验证项清单

1. **1080p CENC 全链路**：下载→`CencMp4Remuxer.remux`→本地 `clear_<vid>_*.mp4`→Media3 播放，确认 `parseTkhd` 时长正确、无花屏/音画不同步。
2. **画质切换实测**：5 档循环时记录 `AppLog` 的 `选中 codec/height`，确认与本次静态结论（恒 1080P）一致；确认切档后 `pendingQualityHint` toast 文案不误导。
3. **不支持 HEVC 设备连播第 4 集**：确认最终错误文案与真实原因（片源仅 HEVC）是否一致。
4. **Android 5.1 / 6.0 / 7.1 真机**：安装后播放 + 自检，采集 `NoSuchMethodError`/`ExceptionInInitializerError` 堆栈作为 D-01 的现场证据。
5. **低内存盒子（1GB）**：1080p 连续预取 5 集，观察 OOM 与 `prepareClearFile` 失败率。
6. **自检页遥控器焦点链路**：6 个按钮方向键遍历，确认 `btn_upload` 的跨行 nextFocus 不跳序。
7. **弱网弹幕**：单次抖动后 30s 窗口弹幕是否缺失、是否影响后续窗口。
8. **榜单/详情/搜索在线回归**：4 个榜单 × 前 2 页、`detail` 双路、搜索「总裁/闪婚/离婚」条数与季序。
9. **缓存磁盘占用**：连续播放 10+ 集后 cacheDir 大小，确认无上限增长是否可接受。

---

## 十、构建产物

- `app/build/outputs/apk/debug/app-debug.apk`（**12,721,715 字节**，2026-10-22 15:19:33 —— **D-01 修复后重建**；修复前为 14:31:05 版）
- 编译：`.\gradlew.bat :app:assembleDebug` ✅ 通过（与 `testDebugUnitTest` 合并执行，D-01 修复后 BUILD SUCCESSFUL）
- 单测：`app/build/test-results/testDebugUnitTest/TEST-com.houg.tv.sign.PlaybackSignerTest.xml`（tests=1, failures=0, errors=0 —— 修复前亦为 1/1，D-01 修复后复跑仍 1/1）
- Lint：`app/build/reports/lint-results-debug.html` / `lint-results-debug.xml`（修复前 141 条，**NewApi=6**；修复后 **NewApi=0**，仅剩 `InlinedApi=2` 无害）
- 在线探针产物：`d:\hongguo\_probe11\qa-out\{run1,run2,run3}.txt`（未写入 `HougTime\app\src`）

---

## 十一、发布就绪判定（Release Readiness）

**判定：不通过（No-Go）——但 P0 已解除**：D-01 已修复并四重复核通过（第十二章），Android 5–7 的播放阻断消失；剩余两个 P1 使"画质调整"与"老设备可用性"在产品层面仍不成立，需产品决策后方可发布。

> **判定变更记录**：修复前为「不通过（No-Go），1 个 P0 阻断」；D-01 关闭后降级为「P0 已解除，待 2 个 P1 决策 + 真机确认」。

### 直答五问

**(a) 播放链路成了吗？**
**成了（协议链路 + 设备兼容均已成立，剩真机观感确认）。** 真实接口 3/3 全链路成功：注册 200 → `video_model/v1` 200/code=0 → `fallback_api` 200 → 5 档解析 → `key_seed` 解密出 `https://…qznovelvod.com` 明文；三道门槛（空 body 当失败、必带 Cookie、不补 x-tt-token）在代码与在线行为上均成立。原 P0（`java.util.Base64` 需 API 26）已于 15:19 修复：新增纯 Java `B64` 替换全部调用，lint `NewApi` 6→0，真实数据离线解密 PASS（详见第十二章）。

**(b) 榜单 / 弹幕等其他 part 修好了吗？**（分别回答）
- **榜单**：本轮**无改动**；静态核查 URL 拼装、`page>1` 才加 `?page=`、SSR 双/单引号解析、分页上限、HostPool 轮换均正确，未受取流改版影响。**在线未验证**（本轮请求预算全给播放链路），不能声称"已验证修好"。
- **弹幕**：本轮**无改动**；`common_list_info.cursor → 旧字段 → startMs+30s` 三层兜底、末窗截断、`DanmakuView` 几何轨道/不摘在途/seek 重基准均正确。已知轻微缺陷：失败窗口不重试（D-08）。**在线未验证**。
- **详情/搜索**：无改动；V6 三个搜索缺陷的修复代码仍在（`:1865-1892`、`:1942-1966`、`SEASON_MARK` 末尾锚点），但**自动化回归已丢失**（D-05）。详情双路回退正确，文案误伤风险待真机。

**(c) 安卓兼容性测了吗？是否仍支持 Android 5+？**
**测了（lint + 配置 + 静态 + 单测），结论：仍支持 Android 5+，且阻断已解除。** 原判定为"不再实质支持 Android 5–7"，证据是 `minSdk=21` 却使用 `java.util.Base64`（API 26），lint 报 **NewApi 6 条**（`MediaCrypto.java:92`、`PlaybackSigner.java:192/196`），且 `PlaybackSigner.java:72` 的 SBOX 在类静态初始化就调用它——一碰即崩。**该问题已修复**：`B64.java` 全量替换后 lint `NewApi` **归零**，`PlaybackSigner` 类静态初始化在 JVM 上完整走通（单测 1/1），真实 `key_seed` 解密结果与 `java.util.Base64` 逐字节一致。剩余 `InlinedApi 2 条`（`UpdateDialog.kt:119/121`）为编译期内联常量，**运行期无害**。Media3 1.4.1 minSdk 为 21，不构成额外限制。`minSdk=21` 声明**重新成立**；最终仍需 Android 5.1/6.0/7.1 真机安装播放确认。

**(d) 画质调整有没有？**
**UI 与状态机有，实际效果没有。** `QUALITY_MODES` 5 档循环、`maxVariantHeight()`、`cacheTag()`、`cycleQuality()` 清缓存重取流、`pendingQualityHint` 实际档位提示、`PREF_DECODER_SOFT` 软硬解切换均已实现（`PlayerActivity.kt:902-968`、`:1951-1959`）。但线上 5 档中仅 1920/bytevc1 可播，其余 4 档 bytevc2 被过滤，`selected==null` 时兜底取最低档即唯一档（`NetClient.java:1184-1186`）→ **480P/720P/1080P/原画请求全部返回 1080P**，画质菜单"可点但无效"。

**(e) 解码规则变了吗？**
**变了，且实现与协议一致。** 新增：跳过 `bytevc2/bvc2`、保留 `bytevc1/h264`（`NetClient.java:1137-1139`）；按 `quality_desc→height→vheight→quality→key` 降序选 ≤上限最高档、全超限取最低档（`:1233-1260`、`:1172-1186`）；设备不支持 HEVC 时排除 HEVC + 运行期 `disableHevc()`（`:231-314`、`PlayerActivity.kt:260-352`）；CENC 本地明文（`encv/bvc1/hev1→hvc1`、`enca→mp4a`、丢弃 `senc/saio/saiz/sgpd/sbgp`、`AES/CTR/NoPadding`、`spade_a` 经 `deriveKey` 派生）。缺口：无 AVC 时"排除 HEVC"不生效（D-03）。

### 发布条件（建议）

1. ~~**必须**：修 D-01（P0）~~ → **已完成**（2026-10-22 15:19，见第十二章）。
2. **必须决策**：D-02（画质仅单档）——由主策决定是"诚实地只显示可用档"还是"补 AVC/AV1 解码方案"。
3. **建议**：D-03 在无 AVC 设备上给出准确错误文案，避免用户误以为"下架/未开放"。
4. **建议**：补回搜索三缺陷单测（D-05），并把 D-04/D-06 纳入下一轮迭代。

---

## 十二、D-01 修复复核（P0 → 已修复）

**修复时间**：2026-10-22 15:19
**修复方案**：项目内新增纯 Java Base64 实现 `app/src/main/java/com/houg/tv/sign/B64.java`（`encodeToString` / `decode`），替换全部 `java.util.Base64` 调用。
**为何不用报告原建议的两种方案**：
- 开 core library desugaring：需新增 AGP 依赖 + 改变构建矩阵，影响面大且需联网拉 `desugar_jdk_libs`；
- 换 `android.util.Base64`：`PlaybackSigner` / `MediaCrypto` 同时被 **JVM 单测**与**桌面在线探针**加载，桌面 classpath 里的 `android.jar` 全是 stub，一调用就 `RuntimeException: Stub!`，单测与探针会整体失效。
- 纯 Java 手写实现是唯一同时满足「API 21 可用」「JVM 可跑」「零新增依赖」的方案，且与 `java.util.Base64` 行为逐字节等价（已用真实数据对拍证明）。

### 12.1 改动清单

| # | 文件 | 改动 |
|---|---|---|
| 1 | `app/src/main/java/com/houg/tv/sign/B64.java` | **新建**。标准 Base64（`A-Za-z0-9+/`，尾部 `=`），`decode` 先剥 `=` 再按 4 字符一组解，非法字符/异常长度显式抛 `IllegalArgumentException` |
| 2 | `sign/MediaCrypto.java` | 删除 `import java.util.Base64;`；`decode64` 改为 `B64.decode(s.replace('-','+').replace('_','/'))` |
| 3 | `sign/PlaybackSigner.java` | 删除 `import java.util.Base64;`；`decode64` 与 `base64` 两处私有方法改为委托 `B64` |

未改动 `build.gradle.kts`（`minSdk` 仍为 21，`compileSdk/targetSdk` 35，无需 desugaring）。

### 12.2 修复过程中发现并修掉的自身 bug

首版 `B64.decode` 的输出长度公式写成 `(tail * 6 - 8) / 8`，对尾部 3 个字符（18 bit → 2 字节）算出 1 字节，导致 `out[o++]` 越界（`ArrayIndexOutOfBoundsException`），在 `PlaybackSigner` 类静态初始化（SBOX 解码）时即抛 `ExceptionInInitializerError`，单测 `matchesFixedVectors` 失败。
**修正**：`outLen = full * 3 + (tail == 0 ? 0 : tail == 2 ? 1 : 2)`，并对 `tail == 1` 显式抛「Base64 长度异常」（与 JDK 行为一致）。修正后全部通过。

### 12.3 四重验证证据

| 验证项 | 命令 / 手段 | 结果 |
|---|---|---|
| 编译 + 单测 | `.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug` | **BUILD SUCCESSFUL**；`tests=1 skipped=0 failures=0 errors=0`（12 行向量 × 5 项断言：branch/hashF13/gorgon/helios/medusa） |
| 兼容性静态检查 | `.\gradlew.bat :app:lintDebug` | **`NewApi` 归零**（原 6 条全部消失）；剩余 `InlinedApi=2` 为 `Settings` 常量内联，运行期无害 |
| 真实数据离线回归 | `_jvmprobe/DecryptRegression.java`（读 `_probe11/fb_last.json` 线上响应，走**新编译**的 `B64`/`MediaCrypto`，零网络） | `key_seed` 解出 32 字节且 `与 java.util 一致 = true`；`MediaCrypto.decryptUrl` 输出 `https://v26-reading-videocdn302.qznovelvod.com/...` → **PASS** |
| 产物重建 | `app/build/outputs/apk/debug/app-debug.apk` | `12,721,715` 字节，`2026-10-22 15:19:33`（含修复） |

### 12.4 结论

- D-01 的**代码级与构建级证据链已闭合**：API 21–25 上不再存在 `java.util.Base64` 引用（lint 证明），类静态初始化路径可由 JVM 完整走通（单测证明），真实线上数据可正确解密（回归证明）。
- **仍待真机确认**：Android 5.1 / 6.0 / 7.1 实机安装 → 播放 → 自检页四段全绿。此步只能由持有设备的测试者执行，本报告不假装完成。
- 该缺陷关闭后，`minSdk=21` 的声明**重新成立**（Media3 1.4.1 的 minSdk 亦为 21，不构成额外限制）。

## 十三、D-02 / D-03 / D-04 / D-08 修复（2026-10-22 第二轮）

**修复范围**：画质（D-02）、无 HEVC 设备兜底（D-03）、切档清缓存（D-04）、弹幕失败重试（D-08）。
**改动文件**：`PlayerActivity.kt`、`strings.xml`（新增 1 条文案）。**未改 `NetClient.java`**——bytevc2 过滤与设备能力判定保持原样（理由见 13.1）。

### 13.1 先回答用户的两个问题（源码为证）

**(a) "放开那个解码，或者进软件后自动探测适配就开放" —— 这条路在 Android 上不成立。**

HGTV 在 [ContentRepository.kt:1170-1173](file:///d:/hongguo/_probe11/HGTV/android/core/src/main/kotlin/com/hgtv/star/core/ContentRepository.kt#L1162-L1182) 的原文注释：

> "ByteVC2 resources can expose playable audio but no video track in Android's MP4 extractor."

即 bytevc2（字节私有 AV1 封装）**不是设备解码器能力问题，而是容器解析层问题**：Android 的 Mp4Extractor 解不出视频轨，任何机型探测都恒为"不适配"。HGTV 因此**无条件过滤 bytevc2/bvc2**，并用 4 个 JVM 回归用例锁死该行为（[ContentRepositoryTest.kt:158-178](file:///d:/hongguo/_probe11/HGTV/android/core/src/test/kotlin/com/hgtv/star/core/ContentRepositoryTest.kt#L158-L178)），其文档 [HONGGUO_API.md §7](file:///d:/hongguo/_probe11/HGTV/docs/HONGGUO_API.md#L570-L591) 也把它列为已知坑（"只有声音没画面"）。
所以走的是用户方案的**后半句**：不适配 → 只显示可用清晰度。

**(b) D-03 "他们的源码里还有别的解决办法吗" —— 有，两条，都已落进代码。**

HGTV 对无 HEVC 设备不做能力预过滤，靠"尽力播 + 快速失败"：
- [MainActivityPlayer.kt:231](file:///d:/hongguo/_probe11/HGTV/android/app/src/main/kotlin/com/hgtv/star/MainActivityPlayer.kt#L231-L251)：`DefaultRenderersFactory(this).setEnableDecoderFallback(true)`；
- 同文件 `STATE_READY`：`if (!p.currentTracks.isTypeSelected(C.TRACK_TYPE_VIDEO))` → 抛"资源没有可解码的视频轨道"，杜绝有声黑屏假播；
- 全档不可播时抛 `IOException("没有电视可播放的清晰度，请稍后再试或选择其他剧集")`。

我方原有两级自愈（排 HEVC 重取流 → 切软解）比 HGTV 更激进，缺的正是这两层保险，本轮补齐。

### 13.2 改动清单

| # | 缺陷 | 位置 | 改动 |
|---|---|---|---|
| 1 | D-02 | `PlayerActivity.kt` 新增 `availableHeights` 字段 + `availableQualityIndices()` + `isCompatFallback()` | `playCurrent` 取流后记录本集**实际可用档位集合**（NetClient 已按设备能力与 bytevc2 过滤）；`cycleQuality` 只在可用档位间循环，服务端没有的档不再出现在菜单里；档位未知（命中缓存）时不设限，防菜单突然缺档 |
| 2 | D-02 | `notifyQualityIfNeeded()` / `updateQualityLabel()` | 全超限回落时标注"（兼容资源）"，与 HGTV `quality = "${h}P（兼容资源）"` 同款文案 |
| 3 | D-03 | `buildPlayer()` | 硬解分支改用 `DefaultRenderersFactory(this).setEnableDecoderFallback(true)`（新增 import） |
| 4 | D-03 | `playerListener.STATE_READY` + 新增 `hasVideoTrack()` / `onNoVideoTrack()` | READY 时校验视频轨真被选中；否则置 `currentEpisodeFailed`、停播、`showError` 新文案"该集没有可解码的视频轨（只有声音），可在菜单里切软解或换一集试试"。判定异常时按正常处理，不误伤 |
| 5 | D-03 | `strings.xml` | 新增 `play_error_no_video_track` |
| 6 | D-04 | 新增 `clearVidCacheFiles(vid)`，`cycleQuality()` 调用它 | 只删 `clear_<当前vid>_*.mp4`（含旧版无档位形态）；按文件名第一个 `_` 精确切 vid，防 `vid=123` 误删 `vid=1234`。预加载窗口内其他集的缓存不再被牵连删除 |
| 7 | D-08 | 新增 `danmakuAttempts` / `danmakuRetryAt` 两个 map + `DANMAKU_MAX_ATTEMPTS=3` / `DANMAKU_RETRY_DELAY_MS=5000` | `updateDanmaku` 先查计次与退避，**未到重试时间不推进 `lastDanmakuWindow`**（旧代码先推进，失败窗口在跨出 30s 前永无第二次机会）；`fetchDanmaku` 成功清两 map、失败计次 + 写退避时间 + 复位窗口指针让 500ms 轮询重入 |
| 8 | D-08 | 三处 `danmakuFetchedWindows.clear()`（切集 / 重开弹幕 / `skipSeekGroup` 跳转） | 同步清 `danmakuAttempts`、`danmakuRetryAt`，避免已放弃的窗口被计次卡死 |

### 13.3 验证证据

| 验证项 | 命令 | 结果 |
|---|---|---|
| 编译 + 单测 + 打包 | `.\gradlew.bat :app:compileDebugKotlin :app:testDebugUnitTest :app:assembleDebug` | **exit 0**（单测仍 `failures=0 errors=0`） |
| 兼容性静态检查 | `.\gradlew.bat :app:lintDebug` | **exit 0**，报告中 `NewApi` 匹配数 **0**（本轮新增 API 均为 Media3 常规调用，未引入高于 API 21 的平台 API） |
| 代码级自检 | `hasVideoTrack` 走 `player?.currentTracks`（空安全，播放器已释放时不误报失败）；`clearVidCacheFiles` 对 vid 空串/null 直接 return | 已按现有代码风格（`catch (_: Exception)`、`AppLog.d`）落地 |

### 13.4 遗留与待真机项

- D-02 的"菜单只显示可用档位"依赖**本集取流结果**；同一剧各集档位通常一致，但若某集档位组合不同，菜单会随集变化——这是预期行为（诚实反馈），需真机确认观感可接受。
- D-03 的 `setEnableDecoderFallback(true)` 在部分 ROM 上会延长首次解码失败到回退成功之间的等待（多一次解码器初始化），真机需留意起播耗时。
- audio-only 快速失败路径（`onNoVideoTrack`）只有真机能构造（需一集仅有 bytevc2 的片源，线上实测第 4 集起 1920 为 bytevc1、640~1280 为 bytevc2，过滤后即走"没有电视可播放的清晰度"分支）。
- Android 5.1 / 6.0 / 7.1 真机安装 → 播放 → 自检页四段全绿，仍待设备持有者执行。

### 13.5 四环节检验日志（2026-10-22 第三轮补充）

应用户要求，把"播一集"拆成四个可独立判定的检验环节，日志统一带 `【检验环节N】` 前缀（`adb logcat -s HougTime` 或搜 `检验环节` 即可逐环节定位断点）：

| 环节 | 日志位置 | 判定内容 | 看什么 |
|---|---|---|---|
| 1. 取流 | `NetClient.fallbackStreamModel` / `videoModel` 直读路径 + `sortVariantsForDevice` | 服务端**原始下发**的全部档位（`key=codec@height`）、bytevc2 剔除数、无 HEVC 设备剔除的 bytevc1 数、最终选中档 | 原始下发是否为空；剔完是否剩 0 档（→"没有电视可播放的清晰度"）；选中 codec 是否 bytevc1/bytevc2 |
| 2. 下载/解密 + 可用档位 | `PlayerActivity.playCurrent`（取流成功后）+ `prepareClearFile`（既有）+ `cycleQuality` | 本集最终可用档集合（清晰度菜单依据）、下载/解密成功与否、切档后的新上限 | 可用档列表是否为空；`prepareClearFile` 的 codec/height/enc/clear 字节数；切档日志的新档上限 |
| 3. 建播放器 | `PlayerActivity.buildPlayer` | 软解 or 硬解（硬解=解码器回退已开） | 播放器创建时机与模式，配合 onPlayerError 的既有日志定位解码失败 |
| 4. 起播确认 | `STATE_READY` → `logReadyVideoTrack()` / `onNoVideoTrack()` | 视频轨是否真被选中：逐轨打印 `选中/备选 + sampleMimeType + WxH + codecs`；无视频轨则走 audio-only 快速失败 | 成功：确认实际解码格式（硬/软解、H.264/H.265）；失败：出现"无视频轨（audio-only）"即该集只剩不可解码资源 |

改动文件：`NetClient.java`（环节 1，fallback 与直读两条路径 + HEVC 剔除日志）、`PlayerActivity.kt`（环节 2/3/4，新增 `logReadyVideoTrack()`）。日志全部为 `AppLog.d`，无新平台 API。

**回归**：`compileDebugKotlin + testDebugUnitTest + assembleDebug + lintDebug` → **exit 0**，lint 报告 `NewApi` 匹配数仍为 **0**，APK 已重新产出（[app-debug.apk](file:///d:/hongguo/HougTime/app/build/outputs/apk/debug/app-debug.apk)）。

### 13.6 栏目加载失败修复：真人剧 / 漫剧 / AI剧（2026-10-22 第四轮）

**用户反馈**：真机播放已通（第 4 集起可看），但首页「真人剧 / 漫剧 / AI剧」三个栏目仍显示"加载失败"。

**根因**：HougTime 的 `catalog()` 只走 App 版签名 POST `/reading/distribution/category/landpage/v/`（`select_items.genre` 等），该接口在真机三栏目全部失败（HGTV 也完全不用它）。HGTV 的分类页改走**网页版公开 GET**，免签名免 Cookie：`/category/real-drama`、`/category/comic-drama`、`/category/ai-drama`，24 部/页，列表内嵌在页面 `_ROUTER_DATA` 的 `loaderData.category_$`（或 `category_page`）→ `recommendList` → `pagination.totalPages`。

**改动**（[NetClient.java](file:///d:/hongguo/HougTime/app/src/main/java/com/houg/tv/net/NetClient.java)）：

| # | 改动 |
|---|---|
| 1 | `catalog()` 改为「先 App 版 → 失败或空数据回退网页版」，与 `detail()` 的「App 失败回退网页版」同构；原 POST 主体抽为 `appCatalog()` |
| 2 | 新增 `webCatalog(genreKey, offset)`：`genreKey` 映射 `short_play→real-drama`、`comic_series→comic-drama`、`ai_series→ai-drama`；`page = offset/24 + 1`；复用既有 `fetchRouterData()`（`getText` + `extractRouterData` 引号/转义感知括号扫描）与 `findLoaderPage()` |
| 3 | 行映射复用 `parseDrama(row.video_data, row)`；按 HGTV `valid()` 规则过滤：id 必须 `[0-9]{1,30}`、标题非空、按 id 去重 |
| 4 | `hasMore = page < pagination.totalPages`；`nextOffset = page × 24`（网页版页长），与 `MainActivity.loadPage` 的分页字段完全兼容；日志统一 `【栏目】` 前缀（走的是 App 还是网页版、URL、条数、页码一目了然） |

**回归**：`compileDebugKotlin + testDebugUnitTest + assembleDebug + lintDebug` → **exit 0**，lint `NewApi` 匹配数 **0**；APK 重建为 12,721,715 字节、2026-10-22 16:52，且已确认 dex 内含新增的 `category_page` / `【栏目】` 常量（`classes6.dex`），产物非旧包。

**在线探针（2026-10-22 17:0x，PC 直连三路径 × 翻页）**：
- `/category/real-drama`、`/comic-drama`、`/ai-drama` 均 **HTTP 200**，`_ROUTER_DATA` / `category_$` / `recommendList` / `totalPages` 全部命中；
- 每页 **24 行**、真人剧/漫剧 **totalPages=34**；`?page=2/3` 返回不同片单（首条 id/名均不同），翻页成立；
- 条目**无 `video_data` 包装**，字段（`series_id`/`series_name`/`series_cover`/`series_intro`/`episode_cnt`/`episode_right_text`/`tags`）直接在顶层——`parseDrama(null, row)` 的回退路径正好覆盖此形态，`tags` 为纯字符串数组，`optString` 直接可读。

**待真机**：三个栏目能否出片单（预期 24 部/页 + 上滑加载下一页）。

