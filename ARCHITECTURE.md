# 红果短剧 · 原生 Android TV 播放器 — 系统架构

> 版本：v1.0（架构设计稿）
> 目标：从零重写一个**只保留「红果」单站源**、**走网页版接口（免签名）**、**覆盖 Android 4.0（API 14）及以上**、**UI 完整**的原生播放器。
> 依据：Go 参照实现 `D:/hongguo/km95VWsZkPYCQx3ag2In9Tz/guoapp/native/core/provider_hongguo*.go`（已逐字段核对）；Flutter「红果鉴」`guoapp/lib/`；现有原生壳 `D:/hongguo/HougTime`（已核对源码与 QA 报告）。

---

## 0. 一句话结论

现有原生壳 `D:/hongguo/HougTime` 的 **UI/Activity/Adapter/播放器/追剧/图片加载全部可复用**，**唯一必须重写的是 `NetClient.java` 的站源层**——把 `catalog()`/`detail()`/`videoModel()` 三个方法从「App 版签名接口（X-Gorgon，连不上）」改为「网页版 `_ROUTER_DATA` 接口（免签名，已用普通 curl 验证 200）」。搜索方法 `search()` 已经是网页版，可直接沿用。核心工作量集中在「站源层重写 + 首页补第 4 个分类（动漫）+ 播放器取流/Referer 修正」三点。

---

## 1. 系统架构

### 1.1 模块划分（分层）

```
┌─────────────────────────────────────────────────────────────┐
│  View 层（传统 View + XML，Kotlin，AppCompatActivity）        │
│  MainActivity(首页4分类)  SearchActivity(搜索)                │
│  DetailActivity(详情+选集)  HistoryActivity(追剧)             │
│  PlayerActivity(MediaPlayer+播放栏)                           │
│  Adapter: DramaAdapter / EpisodeAdapter / HistoryAdapter      │
└───────────────────────────┬─────────────────────────────────┘
                            │ 回调 / Intent(seriesId,vidList,...)
┌───────────────────────────▼─────────────────────────────────┐
│  应用层单例  App (DuanjuApplication)                           │
│  持有 netClient / watchHistory，全局注入                        │
└───────────────────────────┬─────────────────────────────────┘
                            │ 同步调用(已切 Dispatchers.IO)
┌───────────────────────────▼─────────────────────────────────┐
│  网络/站源层  NetClient.java（★ 唯一重写点）                   │
│  catalog()  detail()  videoModel()  search()  suggest()       │
│  - OkHttp 3.12.13 GET（免签名）                                │
│  - 正则定位 _ROUTER_DATA → JSON 解析                            │
│  - loaderData 前缀匹配(category_/detail_/player_/search_)     │
│  - 字段映射为 Drama/Episode/VideoModel                         │
└───────────────────────────┬─────────────────────────────────┘
                            │ HTTPS GET
                    ┌───────▼────────┐
                    │ hongguoduanju.com │ 网页版 SSR 页面（HTML 内嵌 _ROUTER_DATA JSON）
                    └────────────────┘
```

### 1.2 数据流

```
网页版接口(GET, 免签名)
   │  HTML 页面（<script> 内嵌 _ROUTER_DATA = {...} JSON）
   ▼
NetClient.extractRouterData(html)  →  正则 (?s)(?:window\.)?_ROUTER_DATA\s*=\s* 定位
   │  找到起始位置后，用「大括号配对 + 字符串/转义状态机」截取完整 JSON 子串
   ▼
org.json.JSONObject 解析 → loaderData
   │  按前缀匹配：category_ / detail_ / player_ / search_
   ▼
NetClient.Drama / Episode / VideoModel（字段映射，见 §2）
   ▼
Activity + Adapter 渲染（Glide 加载封面，RecyclerView 网格）
   ▼
点击剧集 → DetailActivity（取 vid_list）→ 选集 → PlayerActivity
   ▼
PlayerActivity：videoModel(vid) → MediaPlayer.setDataSource(url, {Referer})
```

### 1.3 关键设计约束（API 14 兼容）

| 约束 | 决策 | 说明 |
|---|---|---|
| minSdk | **14**（Android 4.0） | 团队目标为 4.4+，取 14 可覆盖到 4.0，现有壳已设 14 并实测构建通过 |
| **OkHttp 版本** | **3.12.13**（非 3.14.x） | ⚠️ OkHttp **3.13+ 起 minSdk 要求 API 21**，3.14.x 无法跑在 4.0~4.4。3.12.13 是「最后支持 API 9+」的版本，现有壳已正确使用 |
| 图片 | Glide **4.12.0**（非更高） | Glide 4.13+ 起 minSdk 要求 API 21，4.12.0 是「最后支持 API 14」的版本，现有壳已正确使用 |
| JSON | 系统 `org.json` | 避免 Gson/Moshi 等额外依赖；API 14 无 `StandardCharsets`/`java.time`，用 `Charset.forName("UTF-8")` 与 `System.currentTimeMillis()` |
| 字符集 | `Charset.forName("UTF-8")` | API 19+ 才有 `StandardCharsets`，禁止使用 |
| 网络执行 | OkHttp 同步 `execute()` + 协程 `Dispatchers.IO` | 现有壳已用 `withContext(Dispatchers.IO)` 切线程 |
| 播放 | 系统 `MediaPlayer` + `SurfaceView` | API 14 仅硬解 H264 mp4；红果网页取流为 mp4，符合 |
| 协程 | kotlinx-coroutines 1.7.3 | 现有壳已引入，API 14 兼容 |

---

## 2. 接口契约（★ 工程师实现的最重要依据）

### 2.0 通用约定

- **Base URL**：`https://hongguoduanju.com`
- **HTTP 方法**：全部 `GET`，**无签名、无 TLS 指纹伪装**。
- **通用请求头**（所有页面接口）：
  ```
  User-Agent: Mozilla/5.0 (iPhone; CPU iPhone OS 18_7 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.6 Mobile/15E148 Safari/604.1
  Referer: https://hongguoduanju.com/
  Accept-Language: zh-CN,zh;q=0.9
  ```
- **响应结构**：返回的是**完整 HTML 页面**，其中 `<script>` 内嵌 `window._ROUTER_DATA = {...}`（或直接 `_ROUTER_DATA = {...}`）的 JSON。
- **JSON 提取**（`NetClient.extractRouterData`，现有代码已实现，保留）：
  1. 正则 `(?s)(?:window\.)?_ROUTER_DATA\s*=\s*` 定位起始；
  2. 从该位置找到第一个 `{`，用「大括号深度计数 + 字符串/转义状态机」截取到配对的 `}`；
  3. `new JSONObject(子串)`。
- **loaderData 定位**：`_ROUTER_DATA.loaderData` 是一个 map，其 key 形如 `category_real-drama`、`detail_xxx`、`player_xxx`、`search_xxx`。**key 后缀不定，必须按前缀匹配**，取第一个 `key.startsWith(prefix)` 且值为非空 JSONObject 的项。

### 2.1 分类 `GET /category/{route}?page={N}`

- `route` ∈ `real-drama`(真人剧) / `comic-drama`(漫剧) / `ai-drama`(AI剧) / `comic`(动漫)
- `page` 从 1 开始，分页终止条件见 §2.1.1。

**loaderData 定位前缀**：`category_`（Go 侧名称为 `["category_page","category_$"]`，先精确匹配 `category_page`，再前缀匹配 `category_`）。

**字段映射表**（loaderData 对象 = `page`）：

| JSON 字段 | 类型 | 用途 | 说明 |
|---|---|---|---|
| `page.isSuccess` | bool | 校验 | 必须为 `true`，否则判定站源结构/权限变化 |
| `page.recommendList` | array | 剧集列表 | 每项含 `video_data` 子对象 |
| `page.pagination.totalPages` | string/int | 总页数 | 用于判断是否还有下一页 |

**`recommendList[]` 每项 → `video_data` 子对象字段**（若 `video_data` 为空则该项本身即字段容器）：

| JSON 字段 | 类型 | 用途 | 兜底字段（依次尝试） |
|---|---|---|---|
| `video_data.series_id_str` | string | 剧集 ID | `series_id` → 外层 `series_id_str`/`series_id` → `keyword` |
| `video_data.series_title` | string | 剧名 | `series_name` → `title` → 外层 `series_name`/`name` |
| `video_data.series_cover` | string | 封面 URL | `cover` → 外层 `series_cover` |
| `video_data.series_intro` | string | 简介 | `video_desc` → 外层 `series_intro` |
| `video_data.episode_cnt` | string | 集数 | 外层 `episode_cnt` |
| `video_data.episode_right_text` | string | 状态文案（连载/完结/全N集） | 外层 `episode_right_text`；为空且集数非空时兜底为「共N集」 |
| `video_data.series_status` | string | 完结标记 | `"1"`=完结，`"0"`=连载（优先级高于文案推断） |
| `video_data.tags` | string[] | 标签 | — |
| `video_data.category_list` | object[] | 分类标签 | 取每项 `.name` 并入 tags |
| `video_data.category_schema` | string(JSON) | 分类标签(JSON串) | `[{"name":...}]`，可解析并入 tags |
| `video_data.category_name` | string | 单值分类 | 兜底 `categoryName` → `category` |
| `video_data.score` | string | 评分 | — |
| `video_data.series_play_cnt` | string | 播放量 | 兜底 `play_cnt` |

**2.1.1 分页策略**（参照 Go `fetchHongguoRoute`）：读 `totalPages` 决定是否翻页；单页新增为 0 或 `len(items) < 24` 时提前终止；页数上限 500 兜底。原生首页按需翻页（RecyclerView 滚动到底加载下一页），无需一次拉全量。

### 2.2 详情/选集 `GET /detail?series_id={id}`

- `series_id` 需 URL 编码（`URLEncoder.encode`，注意将 `*` 编码为 `%2A`）。

**loaderData 定位前缀**：`detail_`（Go 侧 `["detail_page","detail_"]`）。

| JSON 字段 | 类型 | 用途 |
|---|---|---|
| `page.seriesDetail` | object | 详情容器，为空则报错 |
| `page.seriesDetail.series_name` | string | 剧名（兜底 `series_title` → `name`） |
| `page.seriesDetail.vid_list` | string[] | **各集 vid**（元素为数字字符串，逐个为「第 N 集」） |

> ⚠️ 注意：网页版详情**只给 `vid_list` 字符串数组**（无 `video_list`/`vid_index` 结构），与 App 版不同。集号按数组下标从 1 递增（第 1 集 = vid_list[0]）。跳过空串或 `"<nil>"` 元素。

### 2.3 播放取流 `GET /player/{series_id}/{vid}`

**loaderData 定位前缀**：`player_`（Go 侧 `["player_","player_page"]`，前缀匹配 `player_`）。

| JSON 字段 | 类型 | 用途 |
|---|---|---|
| `page.vid` | string | **必须等于**请求的 vid，否则判定仅网页试看/无权限 |
| `page.series_id` | string | **必须等于**请求的 series_id，同上 |
| `page.video_player_info` | object | 播放地址容器 |
| `page.video_player_info.duration` | string/float | 时长（秒） |

**`video_player_info` 内取流地址字段**（`hongguoMediaAddresses` 等价实现，按顺序扫描以下 key）：

```
main_url, backup_url, backup_url_1, backup_url_2, backup_urls, url_list
```

**取流地址提取规则**（关键，务必逐条实现）：
1. 每个 key 的值可能是 **字符串** 或 **字符串数组**，数组需扁平化；
2. 每个字符串先 `trim`；长度 > 8192 丢弃；
3. 若字符串不是 `http://`/`https://` 开头，则**先 Base64 解码**（标准或 Raw 编码），解码后若变成合法 http(s) URL 则采用；
4. 最终只保留「http/https 且带 host 且无 userinfo」的地址，去重。

**播放媒体请求头**：`Referer: https://hongguoduanju.com/`（★ 现有 `PlayerActivity.kt:193` 写的是 `https://novel.snssdk.com/`，那是 App 版媒体的 Referer，**必须改为** `https://hongguoduanju.com/`）。

### 2.4 搜索 `GET /search/{keyword}`

- `keyword` 需 URL 编码（`URLEncoder.encode`，`*` → `%2A`，空格注意：网页版用 PathEscape，建议 `%20`）。

**loaderData 定位前缀**：`search_`（Go 侧 `["search_(keyword)/page","search_"]`，前缀匹配 `search_`）。

| JSON 字段 | 类型 | 用途 |
|---|---|---|
| `page.isSuccess` | bool | 必须为 `true` |
| `page.query` | string | 必须等于请求关键词，否则无效 |
| `page.searchList` | array | 结果列表，每项同分类结构（`video_data` 子对象，字段见 §2.1） |
| `page.totalCount` | string/int | 结果总数 |

### 2.5 搜索建议（可选，网页版可用）`GET /incent_resource/suggestion`

- URL：`https://hongguoduanju.com/incent_resource/suggestion?app_id=8662&query={q}&count={N}`（N 建议 10）
- **这是直接 JSON 接口（非 `_ROUTER_DATA`）**，返回 `application/json`，无需解析 HTML。

| JSON 字段 | 类型 | 用途 |
|---|---|---|
| `suggest_list`（或 `data.suggest_list`） | array | 联想词列表 |
| `suggest_list[].name` | string | 联想词 |
| `suggest_list[].word_type` | string | 类型：`short_play_name`/`short_play_category`/`common_query`/`actor_name`/`short_play_actor` |

> 结论：搜索建议**是网页版接口、免签名**，可作为可选项实现（300ms 防抖 + 内存缓存，参照 Flutter `search_input.dart`）。首版可只做「关键词直接搜索」+ 历史词。

---

## 3. 文件列表（计划创建/修改）

> 路径基准：`app/src/main/`

### 3.1 重写（核心）

| 文件 | 语言 | 职责 | 改动 |
|---|---|---|---|
| `java/com/houg/tv/net/NetClient.java` | Java | 站源层：网页版 `catalog/detail/videoModel/search/suggest` + `_ROUTER_DATA` 解析 + 字段映射 | **重写**：`catalog()`→网页分类分页；`detail()`→网页详情 `vid_list`；`videoModel()`→网页取流 `video_player_info`；保留现有 `search()`/`extractRouterData()`/`parseDrama()`；新增 `suggest()`（可选）；**删除** `SignedRequest`/`postJson` 依赖 |

### 3.2 修改（接入新站源层 + 补齐功能）

| 文件 | 语言 | 职责 | 改动 |
|---|---|---|---|
| `java/com/houg/tv/MainActivity.kt` | Kotlin | 首页分类 Tab | `genres` 增加第 4 项 `comic`(动漫)；分类请求改用网页版 `catalog()`（参数从 `(key,scene,offset,session)` 改为 `(route,page)`）；分页改用 `totalPages`；补 `activity_main.xml` 第 4 个 Tab |
| `java/com/houg/tv/DetailActivity.kt` | Kotlin | 详情+选集 | 改为消费网页版 `detail()`（`vid_list` 数组）；`episodes` 集号按下标 1 起 |
| `java/com/houg/tv/PlayerActivity.kt` | Kotlin | 播放器 | `videoModel()` 改为消费网页版 `VideoModel`（多地址 + 时长）；**修正媒体 Referer** 为 `https://hongguoduanju.com/`；可选：多地址失败切换（main→backup） |
| `java/com/houg/tv/SearchActivity.kt` | Kotlin | 搜索 | 沿用网页版 `search()`（已工作）；补 generation 防竞态（QA P2-2）；可选接入 `suggest()` |
| `res/layout/activity_main.xml` | XML | 首页布局 | 增加「动漫」Tab 项 |
| `res/values/strings.xml` | XML | 文案 | 增加「动漫」等新文案 |

### 3.3 复用（不改或微调）

| 文件 | 职责 | 说明 |
|---|---|---|
| `java/com/houg/tv/DuanjuApplication.kt` | `App` 单例容器 | 复用 |
| `java/com/houg/tv/net/HttpClientProvider.kt` | OkHttp 单例（3.12.13） | 复用，可加 `connectTimeout/readTimeout` 已合理 |
| `java/com/houg/tv/data/WatchHistory.kt` | 追剧历史（SharedPreferences） | 复用（已同步锁、进度持久化） |
| `java/com/houg/tv/HistoryActivity.kt` / `HistoryAdapter.kt` | 追剧列表 | 复用 |
| `java/com/houg/tv/DramaAdapter.kt` / `EpisodeAdapter.kt` | 卡片/选集网格 | 复用 |
| `java/com/houg/tv/sign/XGorgon.java` / `SignedRequest.java` | App 版签名 | **删除**（网页版免签名，不再需要） |
| 其余 `res/layout/*`、`res/drawable/*`、`res/color/*` | 样式 | 复用 |

### 3.4 建议新增（可选，轻量）

| 文件 | 语言 | 职责 |
|---|---|---|
| `java/com/houg/tv/net/RouterData.java` | Java | 把 `_ROUTER_DATA` 提取 + `loaderData` 前缀匹配 + 字段兜底工具从 NetClient 抽离为静态工具类，便于单测（非必须，避免过度设计时可留在 NetClient 内） |

---

## 4. 数据结构（核心 model）

> 现有 `NetClient` 内已用静态内部类承载；重写时建议保持同名/同字段以最小化 Activity 改动，字段对齐网页版接口。

```java
public static final class Drama {
    String id;            // series_id（series_id_str/series_id 兜底）
    String title;         // series_title/series_name/title
    String cover;         // series_cover/cover
    String intro;         // series_intro/video_desc
    String episodeCount;  // episode_cnt
    String remark;        // episode_right_text（连载/完结/共N集）
    List<String> tags;    // tags + category_list[].name + category_schema
    // 可选：releaseStatus(ongoing/finished), score, playCount
}

public static final class Episode {
    String vid;    // vid_list 元素
    int index;     // 集号，从 1 起（= 数组下标 + 1）
    String title;  // "第N集"
}

public static final class VideoModel {
    String videoId;        // 请求的 vid
    List<String> urls;     // 多清晰度/备份地址（main_url/backup_url...，已去重+解码）
    int duration;          // video_player_info.duration 秒
    String referer;        // 固定 "https://hongguoduanju.com/"
}

public static final class CatalogPage {
    List<Drama> dramas;
    int totalPages;        // pagination.totalPages（或 hasMore 布尔）
    int page;              // 当前页
}
```

> 说明：现有 `VideoModel` 用 `mainUrl/backupUrl` 两个字段，改为 `urls` 列表后，`PlayerActivity.startPlayback` 按序尝试（第一个失败切下一个）。详情接口只返回 `vid_list`，不再有 `vid_index`，集号由下标推导。

---

## 5. 任务列表（有序、含依赖、按实现顺序）

> 依赖：T2 依赖 T1；T3~T6 依赖 T1；T4 依赖 T3（可选）；T7 依赖 T3；T8/T9 依赖全部。

### T1 站源层重写（最高优先级，无前置）
- **输入**：本文件 §2 接口契约 + Go 参照 `provider_hongguo*.go`。
- **输出**：重写后的 `NetClient.java`（网页版 `catalog/detail/videoModel/search`，删除 `SignedRequest` 依赖）。
- **验收**：用 curl 或真机对同一 series_id/route/keyword 分别调用 4 个接口，能打印出正确的 Drama/Episode/VideoModel 字段；`isSuccess`/`vid`/`series_id`/`query` 校验生效。

### T2 OkHttp/依赖核对（无前置，与 T1 并行）
- **输入**：§1.3 依赖版本约束。
- **输出**：确认 `okhttp:3.12.13`、`glide:4.12.0` 保持不变（**不得升级**到 3.14.x/4.13+，否则 minSdk 14 崩溃/安装失败）。
- **验收**：`gradlew assembleDebug` 通过，APK `minSdkVersion=14`。

### T3 首页 4 分类（依赖 T1）
- **输入**：T1 的 `catalog(route, page)`。
- **输出**：`MainActivity.kt` 增加「动漫」Tab（共 4 个：真人剧/漫剧/AI剧/动漫），改用网页版分页。
- **验收**：4 个 Tab 均能加载出列表并可翻页；快速切 Tab 无数据错位（generation 校验保持）。

### T4 详情页接入网页版（依赖 T1）
- **输入**：T1 的 `detail(seriesId)` 返回 `vid_list`。
- **输出**：`DetailActivity.kt` 正确渲染封面/简介/选集网格，集号从 1 起。
- **验收**：点击某剧进入详情，选集数量与站点一致，点击第 N 集跳转播放器并携带正确 `vid`。

### T5 播放器接入网页取流（依赖 T1、T4）
- **输入**：T1 的 `videoModel(seriesId, vid)` 返回多地址 + Referer。
- **输出**：`PlayerActivity.kt` 用网页取流地址播放，**Referer 改为 `https://hongguoduanju.com/`**，多地址失败切换。
- **验收**：真机/模拟器能稳定播放完整一集，上一集/下一集/进度条/自动连播正常。

### T6 搜索接入（依赖 T1，可先于 T4/T5）
- **输入**：T1 的 `search(keyword)`（已网页版）。
- **输出**：`SearchActivity.kt` 搜索可用；补 generation 防竞态（QA P2-2）；可选接入 `suggest()` 联想（§2.5）。
- **验收**：输入关键词返回正确结果，快速连续搜索不串结果。

### T7 追剧历史验证（依赖 T3）
- **输入**：现有 `WatchHistory.kt`（已复用）。
- **输出**：确认首页/详情/播放器对 `record/lastEpisode` 调用正确，HistoryActivity 正常展示。
- **验收**：看完一集后，追剧列表能显示该剧及集数，重进详情定位到上次集数。

### T8 UI 打磨（依赖 T3~T6）
- **输入**：现有 `res/layout` 与 Adapter。
- **输出**：网格列数按屏幕宽度自适应（QA P3-10）、加载/空/错误三态提示统一、遥控器焦点链路顺畅。
- **验收**：720p 老设备选集不拥挤；断网/接口异常有可读提示。

### T9 老设备真机兼容验证（依赖 T5）
- **输入**：目标机（Android 4.4 / 4.0）真机。
- **输出**：验证 HTTPS/TLS 握手、MediaPlayer 硬解 H264、Glide 图片加载、安装包签名。
- **验收**：真机可安装、可播放、无崩溃。

---

## 6. 风险与待确认

1. **OkHttp 版本冲突（高，需团队对齐）**：团队约束写了「OkHttp 3.14.x」，但 3.13+ 要求 API 21，与 minSdk 14 直接冲突。**必须用 3.12.13**（现有壳已是此版本）。此点需向 team-lead 明确。
2. **Glide 版本同理**：必须 4.12.0（4.13+ 要求 API 21）。
3. **老设备 TLS 握手（中，需真机验证）**：`hongguoduanju.com` 的 HTTPS 在 Android 4.0~4.4（API < 20）默认不启用 TLS 1.2/SNI，虽然 OkHttp 3.12 通过 `ConnectionSpec` 尽力启用 TLS 1.2，但个别旧设备/旧 ROM 可能握手失败。**「普通 curl 验证 200」不等于 Android 4.x 真机能通**，需 T9 真机确认；必要时需在 `HttpClientProvider` 显式配置 `ConnectionSpec`（含 TLSv1.2）或降级处理。
4. **网页版字段漂移（中）**：接口为逆向自 SSR 页面，`_ROUTER_DATA` 的 loaderData key 后缀、`video_data` 字段名可能随站点改版变化。已按「前缀匹配 + 多字段兜底」设计，单字段缺失表现为「列表为空/加载失败」而非崩溃；需真机抓包复核 `search` 页 SSR 变量名（QA P2-7）。
5. **取流地址可能为 Base64 编码（中）**：`hongguoMediaAddresses` 对非 http 开头的值做了 Base64 解码兜底，实现时不能漏。
6. **取流可能返回 m3u8（低）**：红果网页取流以 mp4 为主；若出现 `.m3u8`，系统 `MediaPlayer` 不支持 HLS，需在架构上预留「m3u8 判定 + 明确报错/换源」的分支（Go 参照有 `prepareWebProviderMedia` 的 HLS 处理，首版可仅 mp4）。
7. **搜索建议接口归属（低）**：`/incent_resource/suggestion` 是网页版 JSON 接口（免签名），与团队预设「App 版需签名」的判断不同，实测可作可选功能。
8. **App 版签名代码清理**：`sign/XGorgon.java`、`SignedRequest.java`、`xgorgon.py` 在重写后不再需要，建议删除以减小体积、降低误用风险（但删除属破坏性操作，执行前与 team-lead 确认）。
