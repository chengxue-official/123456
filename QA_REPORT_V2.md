# QA_REPORT_V2 — 站源层重写独立验证

> 验证人：software-qa-engineer-3
> 日期：2026-10-09
> 方式：独立全量编译 + curl 实测 4 接口逐字段核对 + 代码审查（只读，未改任何源码）
> 结论一句话：**站源层重写真实可用，编译通过，4 接口字段映射全部命中；发现 1 个 P1（试看集数未限制）+ 若干 P2/P3。**

---

## 1. 独立编译验证

| 项 | 结果 |
|---|---|
| 命令 | `JAVA_HOME="C:\Program Files\Microsoft\jdk-21.0.9.10-hotspot" ./gradlew.bat clean assembleDebug --no-daemon` |
| 结果 | **BUILD SUCCESSFUL in 2m 27s**（35 tasks，34 executed，强制全量重编） |
| 新增错误 | **无** |
| 警告 | 仅 deprecation：`getColor`(EpisodeAdapter.kt:28/30)、`SYSTEM_UI_FLAG_*`(MainActivity.kt:182-190)、`setAudioStreamType`(PlayerActivity.kt:203)、JDK source/target 8 弃用提示（可忽略） |
| APK | `app/build/outputs/apk/debug/app-debug.apk`（3,992,278 字节） |
| minSdkVersion | **14**（merged manifest 实测 `minSdkVersion="14"`） |
| Java/Kotlin | `compileDebugJavaWithJavac`、`compileDebugKotlin` 均实际执行并通过 |

结论：编译干净通过，无编译期错误，minSdk=14 达标。

---

## 2. curl 实测：4 接口真实字段 vs 代码假设对照

通用请求头（与 NetClient 完全一致）均返回 200：

```
UA: Mozilla/5.0 (iPhone; CPU iPhone OS 18_7 like Mac OS X) AppleWebKit/605.1.15 ...
Referer: https://hongguoduanju.com/
Accept-Language: zh-CN,zh;q=0.9
```

页面 `<meta charset="utf-8">`，NetClient 用 `Charset.forName("UTF-8")` 解码正确。

### 2.1 分类 `GET /category/real-drama?page=1` → HTTP 200（249KB）

| 项目 | 真实返回 | NetClient 假设 | 核对 |
|---|---|---|---|
| `_ROUTER_DATA` 写法 | `_ROUTER_DATA =`（无 `window.` 前缀） | 正则 `(?s)(?:window\.)?_ROUTER_DATA\s*=\s*` | ✅ 命中 |
| loaderData key | **`category_$`（字面量）**，另有 `category_layout`=null | `findLoaderPage(root,"category_page","category_$")` 精确匹配 `category_$` | ✅ 精确命中 |
| `page.isSuccess` | `true` | 校验 `optBoolean("isSuccess")` | ✅ |
| `page.recommendList[]` | **扁平结构，无 `video_data` 子对象**（`video_data`=null），字段直接在每项上 | `parseDrama(row.optJSONObject("video_data"), row)`，`vd==null` 时用 item 自身 | ✅ 兜底正确 |
| 每项字段 | `series_id`(str)、`series_name`(str)、`series_cover`(str https)、`series_intro`(str)、`episode_cnt`(**int** 69)、`episode_right_text`("全69集")、`tags`(str[])、`vid_list`(str[]) | 兜底链 `series_id_str→series_id→keyword`，`episode_cnt` 经 `valueToString` 处理 Number | ✅ 命中 |
| `pagination` | `{total:800, pageNum:1, pageSize:24, totalPages:34}`（totalPages 为 int） | `optText("totalPages")` → `parseInt` | ✅ 命中 34 |

> ⚠️ 注意：架构文档 §2.1 写的字段是 `series_id_str`，真实是 `series_id`（string）；`episode_cnt` 是 int 而非 string。两者代码均已兜底处理，**无失效**。另发现每项含 `accessible_episode_cnt=3`（试看集数），见缺陷 1。

其余 3 路由 `comic-drama`/`ai-drama`/`comic` 均 HTTP 200，`category_$` key 与扁平结构完全一致。

### 2.2 详情 `GET /detail?series_id=7686713195246930968` → HTTP 200（539KB）

| 项目 | 真实返回 | 代码假设 | 核对 |
|---|---|---|---|
| loaderData key | **`detail_page`（精确）** | `findLoaderPage(root,"detail_page","detail_")` | ✅ |
| `seriesDetail` | 存在，含 `series_name`/`series_cover`/`series_intro`/`vid_list` | 解析正确 | ✅ |
| `vid_list` | string 数组，**69 项**（全部集） | 逐项取 `optString`，跳过空/`<nil>` | ✅ |
| `series_episode_info` | `{episode_cnt:69, episode_total_cnt:69, series_status:1}` | 未使用（无影响） | ✅ |
| `accessible_episode_cnt` | **3**（仅前 3 集免费） | **代码未读取** | ⚠️ 见缺陷 1 |

不存在的 series_id（`9999999999999999999`）→ HTTP 500，`detail()` 抛异常 → DetailActivity 捕获显示"加载失败"，**不崩溃**。

### 2.3 播放 `GET /player/{series_id}/{vid}` → HTTP 200（109KB）

| 项目 | 真实返回 | 代码假设 | 核对 |
|---|---|---|---|
| loaderData key | **`player_(series_id)/(vid)/page`（字面占位符）**，另有 `player_layout`、`player_(series_id)/(vid)/layout` 均 null | `findLoaderPage(root,"player_","player_page")` 前缀匹配 `player_` | ✅ `startsWith("player_")` 命中 |
| `page.vid` | `"7686715217652223038"` = 请求 vid | 校验 `vid.equals(...)` | ✅ |
| `page.series_id` | `"7686713195246930968"` = 请求 id | 校验 `series_id.equals(...)` | ✅ |
| `video_player_info` | `{duration, height, width, poster_url, main_url}` | 扫描多地址 key + `duration` | ✅ |
| `duration` | **float `174.719`** | `parseDoubleInt` → `(int)Double.parseDouble("174.719")`=174 | ✅ |
| `main_url` | **https mp4 明文 URL**（489 字符，`mime_type=video_mp4`，**非 Base64**） | `collectAddresses` → `isHttpMediaUrl` 直接采用 | ✅ |

> 说明：真实返回只有 `main_url` 一个地址字段（无 backup_url 等），`extractMediaAddresses` 的 6 个 key 扫描仍能正确命中 `main_url`。`main_url` 为带 `l=`/`dy_q=` 过期时间戳的签名 URL（见缺陷 5）。

**试看边界实测**：第 1/2/3 集 HTTP 200；**第 4 集（`vid=7686715407662582846`）起 HTTP 404**（Not Found 页，`_ROUTER_DATA` 无 player page）。与 `accessible_episode_cnt=3` 吻合。

### 2.4 搜索 `GET /search/总裁` → HTTP 200（82KB）

| 项目 | 真实返回 | 代码假设 | 核对 |
|---|---|---|---|
| loaderData key | **`search_(keyword)/page`（字面占位符）**，另有 `search_layout`=null | `findLoaderPage(root,"search_")` 前缀匹配 | ✅ `startsWith("search_")` |
| `isSuccess` | `true` | 校验 | ✅ |
| `query` | `"总裁"` = 关键词 | `keyword.equals(query)` | ✅ |
| `searchList[]` | **有 `video_data` 子对象**（区别于分类扁平） | `parseDrama(row.optJSONObject("video_data"), row)` | ✅ |
| `video_data` 字段 | `series_id`(str)、`series_title`、`series_cover`、`series_intro`、`episode_cnt`(int 95)、`episode_right_text`("全95集")、`category_list` | 兜底链命中 | ✅ |
| `totalCount` | `"202"`（str） | 未使用（无影响） | ✅ |

**无结果关键词**（"哈哈哈xyz12345"、"不存在的剧名"）→ 站点返回 **HTTP 500**（非 200+空列表），`search()` 内部 catch 返回空列表 → SearchActivity 显示空态，**不崩溃**（见缺陷 6）。

---

## 3. 缺陷清单（P0/P1/P2/P3）

### P0（崩溃/阻断）— 无

未发现任何会导致崩溃或阻断编译/启动的缺陷。

### P1 — 试看集数未限制（功能缺陷）

- **位置**：`NetClient.java:177-188`（`detail()` 解析完整 `vid_list`，不读 `accessible_episode_cnt`）、`DetailActivity.kt:80-85`（渲染全部选集）
- **现象**：详情返回 69 集 `vid_list`，但站点仅 `accessible_episode_cnt=3` 免费。选集网格显示全部 69 集，用户点第 4 集起 → 播放器取流 HTTP 404 → 显示原始错误 `红果接口 HTTP 404`。
- **证据**：curl 实测第 1~3 集 200，第 4 集（`7686715407662582846`）404；`detail.html` 的 `accessible_episode_cnt=3`。
- **影响**：对绝大多数剧集，66/69 集不可播且无提示，核心观影链路断裂。
- **建议**：`detail()` 读取 `seriesDetail.accessible_episode_cnt`（或 `series_episode_info`），对超出试看范围的集在 DetailActivity 标记"试看/付费"或不显示；至少在不可播时给出可读提示而非 HTTP 错误码。

### P2 — 内部错误文案直接暴露给用户

- **位置**：`PlayerActivity.kt:175`（`showError(e.message)`）、`NetClient.java:398`（`throw new Exception("红果接口 HTTP " + resp.code())`）
- **现象**：404/500/超时等异常信息（含内部 HTTP 状态码）原样展示在播放器/页面错误区。
- **建议**：映射为友好文案（如"该集暂不可播放/网络异常，请重试"）。

### P3 — 死代码 sign/ 包未清理

- **位置**：`app/src/main/java/com/houg/tv/sign/SignedRequest.java`、`XGorgon.java`
- **现象**：重写后 NetClient 已不引用，但两文件仍参与编译（无引用、无报错），增加 dex 体积与误用风险。ARCHITECTURE.md §3.4/§6.8 已标注为"需确认后删除"。

### P3 — detail() 未校验 isSuccess

- **位置**：`NetClient.java:160-170`
- **现象**：`catalog()`/`search()` 均校验 `isSuccess`，`detail()` 只判 `seriesDetail` 是否为空。站点改版若 detail 返回 `isSuccess=false` 但仍有部分字段，行为不一致。低风险。

### P3 — 取流地址为带过期时间的签名 URL

- **位置**：`NetClient.java:313-321`（`extractMediaAddresses` 直接采用）、`PlayerActivity.kt:193-233`（无失效重取）
- **现象**：`main_url` 含 `l=20261009115859...`、`dy_q=1791518339` 过期参数。若用户长时间暂停后继续播放/seek，地址可能已过期导致播放失败，当前无"重新取流"机制。低概率，建议 T9 真机长时暂停验证。

### P3 — 搜索无结果时站点返回 HTTP 500

- **位置**：`NetClient.java:221-246`（search catch）
- **现象**：无结果关键词站点返回 500（非 200+空列表），代码 catch 后返回空列表，功能安全，但"空态"与"无结果"语义合并。可接受，记录备查。

---

## 4. API 14 兼容性结论

| 检查项 | 结果 |
|---|---|
| OkHttp | **3.12.13**（`build.gradle.kts:46`），3.13+ 需 API 21 ✅ |
| Glide | **4.12.0**（`build.gradle.kts:49`），4.13+ 需 API 21 ✅ |
| `StandardCharsets` | 未使用，用 `Charset.forName("UTF-8")`（NetClient.java:31）✅ |
| `java.time` / `java.util.Optional` | 未使用 ✅ |
| Java/Kotlin 目标 | `source/target 1.8` + `jvmTarget 1.8` + AGP desugar（build.gradle.kts:24-28）✅ |
| 系统 `org.json` | 使用，无额外依赖 ✅ |
| 线程 | 全部网络调用经 `withContext(Dispatchers.IO)`：MainActivity.kt:124、DetailActivity.kt:63、PlayerActivity.kt:167、SearchActivity.kt:81 ✅ |
| 协程 | kotlinx-coroutines 1.7.3 ✅ |

结论：**API 14 兼容无风险**，未发现 `StandardCharsets`/`java.time`/需 API 24 的 lambda 用法。

---

## 5. 交叉检查：Activity 接入与 NetClient 新签名

| 调用点 | 新签名 | 核对 |
|---|---|---|
| MainActivity.kt:125 | `catalog(genre.route, page)` | ✅ 4 Tab（含 tab_anime=动漫，activity_main.xml:77 / strings.xml:7）|
| DetailActivity.kt:64 | `detail(seriesId)` → 消费 `vid_list` | ✅ 集号按下标+1（NetClient.java:185）|
| PlayerActivity.kt:168 | `videoModel(seriesId, vid)` → 消费多地址 `urls` | ✅ Referer 已改 `https://hongguoduanju.com/`（PlayerActivity.kt:205）|
| SearchActivity.kt:82 | `search(keyword)` + searchGen 防竞态 | ✅ |

- 分页：MainActivity 读 `totalPages`（MainActivity.kt:133-135），`hasMore = currentPage < totalPages` ✅
- 竞态防护：首页 generation（MainActivity.kt:46/127）、搜索 searchGen（SearchActivity.kt:31/76/84）、播放 playGen（PlayerActivity.kt:43/158/170）均存在 ✅
- 无任何旧签名残留调用（grep `netClient.catalog/detail/videoModel/search` 全为新签名）✅

---

## 6. 附加核对：Base64 解码与取流地址

- `decodeBase64` 对"非 base64 普通字符串"（如 `//cdn/...` 协议相对、`main` 等 token）：标准 + 补 padding 两次 `Base64.decode` 失败返回空串，或解码后非 http(s) 被 `isHttpMediaUrl` 丢弃，**不产生误判**。✅
- `collectAddresses` 对嵌套数组递归扁平化（NetClient.java:323-340）✅
- 真实 `main_url` 为明文 https mp4（非 Base64），当前无 Base64 场景，但兜底逻辑在位。✅

---

## 7. 附：curl 命令记录（可复现）

```bash
UA='Mozilla/5.0 (iPhone; CPU iPhone OS 18_7 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.6 Mobile/15E148 Safari/604.1'
REF='https://hongguoduanju.com/'
curl -sSL -A "$UA" -e "$REF" 'https://hongguoduanju.com/category/real-drama?page=1'
curl -sSL -A "$UA" -e "$REF" 'https://hongguoduanju.com/detail?series_id=7686713195246930968'
curl -sSL -A "$UA" -e "$REF" 'https://hongguoduanju.com/player/7686713195246930968/7686715217652223038'
curl -sSL -A "$UA" -e "$REF" 'https://hongguoduanju.com/search/%E6%80%BB%E8%A3%81'
```

`_ROUTER_DATA` 提取算法（大括号配对 + 字符串/转义状态机）已用 Python 逐字复刻实测，与 NetClient.java:406-431 逻辑一致，能正确取出 JSON 子串。
