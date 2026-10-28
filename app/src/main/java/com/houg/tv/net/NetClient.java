package com.houg.tv.net;

import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.os.Build;
import android.util.Base64;

import com.houg.tv.sign.DeviceRegister;
import com.houg.tv.sign.HongguoCrypto;
import com.houg.tv.sign.HostPool;
import com.houg.tv.sign.MediaCrypto;
import com.houg.tv.sign.PlaybackSigner;
import com.houg.tv.sign.SignedRequest;
import com.houg.tv.util.AppLog;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.net.URL;
import java.nio.charset.Charset;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * 红果短剧网络层（分类 / 详情 / 播放 / 搜索）。
 * 分类/详情走 App 版接口（X-Gorgon 签名），失败回退网页版（免签名）；
 * 搜索/榜单走网页版；播放走 App 版 multi_video_model，失败回退网页版公开 360p 直链。
 * 兼容 Android 4.0（API 14）：OkHttp 3.12.13 + 系统 org.json，避开 StandardCharsets（API 19）。
 */
public final class NetClient {

    /** 网页版（搜索用） */
    private static final String WEB_SITE = "https://hongguoduanju.com";

    /**
     * 「本机设备身份不被红果接口放行」的播放失败提示（App 通道全部 200 空 body，
     * 自动换新身份重试后仍如此，且网页直链兜底也失败时给出，替代误导性的
     * 「该集未开放免费观看」）。文案按「能自救的动作」分层，不再让用户以为
     * 是内容没上线。
     *
     * BUG-114：同时作为 PlayerActivity 显示「重试」按钮的判定依据（按字符串相等）。
     * 该场景下手动重试 = 换新设备身份 + 重播；因此文案里不再指引「调试日志 → 重置身份」，
     * 用户不需要理解「身份」概念，只看到「重试」。
     */
    public static final String DEVICE_IDENTITY_FAILED_MSG =
            "红果接口未放行本机（已自动换新设备身份重试，仍失败，不是该集没上线）。"
                    + "请检查电视网络后点下方「重试」（遥控器按确定键），会换一个新身份再播一次；"
                    + "仍看不了可卸载重装本应用。";

    private static final Charset UTF8 = Charset.forName("UTF-8");

    private static final MediaType JSON_TYPE = MediaType.parse("application/json; charset=utf-8");

    private static final String WEB_UA =
            "Mozilla/5.0 (iPhone; CPU iPhone OS 18_7 like Mac OS X) AppleWebKit/605.1.15 "
                    + "(KHTML, like Gecko) Version/26.6 Mobile/15E148 Safari/604.1";

    private static final Pattern ROUTER_DATA = Pattern.compile(
            "(?s)(?:window\\.)?_ROUTER_DATA\\s*=\\s*");

    /** 无效集数文案：「全0集 / 共0集 / 更新至0集」（数据源偶发把集数置 0）。 */
    private static final Pattern ZERO_COUNT_REMARK = Pattern.compile("(?:^|[^0-9])0+集");

    /* ---- 榜单（网页版 /rank/&lt;board&gt;，与搜索同站，免签名） ---- */

    /** 榜单形态一：mergeLoaderData 脚本，data-fn-args 用双引号包裹（多数榜单）。 */
    private static final Pattern RANK_FN_ARGS_DQ = Pattern.compile(
            "(?s)data-fn-args=\"(\\[.*?\\])\"");

    /** 榜单形态二：run-router-data-fn 脚本，data-fn-args 用单引号包裹（部分榜单）。 */
    private static final Pattern RANK_FN_ARGS_SQ = Pattern.compile(
            "(?s)data-fn-args='(\\[.*?\\])'");

    /** 榜单每页条数（服务端固定 20，用于校验 rank 区间）。 */
    private static final int RANK_PAGE_SIZE = 20;

    /** 榜单最大页码（服务端分页上限）。 */
    private static final int RANK_MAX_PAGE = 500;

    private static final String TAG = "NetClient";

    /**
     * 单次 App 请求最多尝试的域名数。
     *
     * <p><b>必须是整个域名池</b>（原先写死 3，而池里有 6 个）。线上日志显示：某个域名
     * 的「空 body」是<b>按 path 维度</b>出现的（同一 host 上 catalog/detail 正常但
     * multi_video_model 返回空），因此一次只轮询一半域名时，恰好全踩在坏域名上就会
     * 报「红果 App 接口未返回有效数据」——而下一轮换起点后同一集又能播（用户反馈
     * 「有的能看有的不能看」「看几集后突然不行」的真因）。
     * 连接超时已压到 8s，全池 6 个最坏约 48s，且失败域名会被游标跳过，实际远小于此。
     */
    private static final int APP_HOST_ATTEMPTS = 6;

    /** 单个域名返回「200 但空 body」时，在本域名上再试的次数（共 [EMPTY_BODY_RETRIES]+1 次）。 */
    private static final int EMPTY_BODY_RETRIES = 1;

    /**
     * App 播放通道的**总耗时预算**（BUG-107）。
     *
     * <p>最坏路径是 6 域名 × 2 轮 × 2 次 + legacy = 25 次请求，单次 connect 8s /
     * read 20s，理论上能把起播拖到分钟级（电视端用户只会看到一直转圈）。
     * 超过预算直接放弃 App 通道、回退网页直链——把时间留给上层给用户一个明确结果。
     */
    private static final long APP_CHANNEL_BUDGET_MS = 25_000L;

    /** 网页版请求（榜单/搜索/详情）的尝试次数。域名单次成功率约六成，重试可推到 95%+。 */
    private static final int WEB_ATTEMPTS = 3;

    /** 网页版重试的基础退避（ms），第 n 次重试等待 BASE_RETRY_BACKOFF_MS×n。 */
    private static final long WEB_RETRY_BACKOFF_MS = 300L;

    /**
     * 榜单「解析级」重试次数。
     *
     * <p>{@link #getText(String)} 内部已有 3 次传输层重试（非 200 / 空 body / 异常），
     * 但实测榜单页仍有约 8% 的请求返回 <b>HTTP 200 + 0 字节</b>且偶发解析失败；
     * 这些情况原先直接抛错 → 榜单页白屏，用户只能反复退出重进（「刷新好几次才进去」）。
     * 这里把「抓取 + 解析 + 校验」整体再重试一轮，配合缓存兜底即可消除该现象。
     */
    private static final int RANK_FETCH_ATTEMPTS = 2;

    /** 榜单解析级重试的退避基数（ms）。 */
    private static final long RANK_RETRY_BACKOFF_MS = 400L;

    /** 榜单缓存条数上限（boardId#page 维度）。 */
    private static final int RANK_CACHE_MAX = 24;

    /** 榜单缓存最长保留时长：一天内的旧榜单也比白屏有用。 */
    private static final long RANK_CACHE_TTL_MS = 24L * 60 * 60 * 1000;

    /* ---- 搜索增强（突破网页搜索页固定 10 条的限制） ---- */

    /** 搜索建议接口的 app_id（与网页版一致）。 */
    private static final String SUGGEST_APP_ID = "8662";

    /** 建议接口请求条数（服务端实际上限约 20）。 */
    private static final int SEARCH_SUGGEST_COUNT = 50;

    /** 官方全库搜索接口（/reading/bookapi/search/tab/v/）请求条数。 */
    private static final int SEARCH_OFFICIAL_COUNT = 50;

    /**
     * 官方搜索最多翻页数（上限，实际页数由「空页即停」与 {@link #SEARCH_LEGS_DEADLINE_MS}
     * 的剩余预算决定）。实测每页条数并不固定：offset=0 只回约 6 条，offset=6 却一次回约 40 条，
     * 之后迅速衰减到 0；服务端 {@code has_more} 会一直为 true，所以只靠页数上限翻到底会白烧时间。
     */
    private static final int SEARCH_OFFICIAL_MAX_PAGES = 8;

    /**
     * 发起下一页官方搜索前，至少还要剩余的时间预算（ms）。
     *
     * <p>实测官方搜索单页耗时 1.3~3.1s；剩余不足时不再发下一页，避免白烧时间。取值 2500
     * 是「页面偏快时多翻一页」与「快到预算仍会超时」之间的折中——即使真的翻超，
     * {@link #awaitOfficial} 也会保住已取到的部分，不会整条腿归零。
     */
    private static final long SEARCH_OFFICIAL_PAGE_MIN_MS = 2500L;

    /**
     * 官方腿在<b>流式</b>搜索（{@link #search(String, SearchSink)}）下的独立预算（ms）。
     *
     * <p>调用方挂了 {@code sink} 就能边翻边看到结果，首屏不再等这条腿，因此这里可以把预算
     * 放宽到超过 {@link #SEARCH_LEGS_DEADLINE_MS}。
     *
     * <p>探针实测（4 个关键词）：第 2 页约 4.7~5.5s 到齐（大头 40+ 条），第 3 页约
     * 8.1~8.7s 再补 1~15 条，第 4 页起恒定 0 条却仍要烧 3s。故取 9500ms：刚好够第 3 页，
     * 又不会发出注定为空的那一页。不挂 {@code sink} 的同步调用仍按 7s 老预算，行为不变。
     */
    private static final long SEARCH_OFFICIAL_STREAM_BUDGET_MS = 9500L;

    /**
     * 搜索三路（官方全库搜索 / 建议接口 / 网页搜索页）并行时的总预算（ms）。
     *
     * <p>实测 hongguoduanju.com 的路由页（/search、/rank、/）约 50% 的请求会挂起
     * 20~30s，只有 {@code /incent_resource/suggestion} 稳定（&lt;300ms）。原先两路串行，
     * 网页页又叠加 callTimeout 12s × 3 次传输重试 × 2 轮解析重试，一次搜索最坏要
     * ~50s 才出结果（用户看到的「一直转圈」）。并行 + 总预算后最坏 7s 出结果。
     */
    private static final long SEARCH_LEGS_DEADLINE_MS = 7000L;

    /** 搜索专用客户端的单次调用超时（s）：挂起的路由页 5s 内放弃，不再等满 12s。 */
    private static final int SEARCH_CALL_TIMEOUT_S = 5;

    /** 详情页「相关推荐」最多展示几条。 */
    private static final int DETAIL_RELATED_MAX = 12;

    /** 详情页「同系列剧集」逐季探测的并发度（一个季号一个请求，并行发出）。 */
    private static final int DETAIL_SEASON_PARALLEL = 6;

    /** 详情页逐季探测每批探几个季号（整批并行）。 */
    private static final int DETAIL_SEASON_BATCH = 6;

    /** 向「已见最大季」之上继续探时最多探几批；整批 0 命中即提前收工。 */
    private static final int DETAIL_SEASON_MAX_BATCH = 2;

    /** 向下补齐时最多回看多少个季号（防脏数据里季号过大导致请求爆炸）。 */
    private static final int DETAIL_SEASON_LOOKBACK = 12;

    /** 单个季号探测的等待上限（s），仅兜底防线程卡死。 */
    private static final long DETAIL_SEASON_PROBE_TIMEOUT_S = 20L;

    /** 合理的最大季/部数（超出视为脏数据）。 */
    private static final int SEARCH_SEASON_MAX = 200;

    /**
     * 季数后缀识别：第N季 / 第N部（支持阿拉伯数字与中文数字），且必须位于片名末尾。
     * 末尾锚点是必要的——否则「离婚第二季特辑」这类特别篇会被误并入「离婚」系列，
     * 补季时污染结果。参考真果鉴用 {@code [\p{P}\s]*$}，这里用 {@code \s*$}
     * （parseSeason 已先 trim，尾部标点由 base 清理逻辑处理）。
     */
    private static final Pattern SEASON_MARK = Pattern.compile(
            "第\\s*([0-9０-９零〇一二两兩三四五六七八九十百]+)\\s*([季部])\\s*$");

    private final OkHttpClient client;

    /**
     * 网页版专用客户端：在 {@link #client} 基础上加整体调用超时。
     *
     * <p>榜单/搜索页只有 connect 8s + read 20s 的分段超时，`HTTP 200 + 0 字节`
     * 往往要等 read 超时才暴露，三次重试最坏可拖到 80s 以上——TV 用户看到的就是
     * 「一直在加载」。加 callTimeout 后单次封顶 12s，重试链整体可控。
     */
    private final OkHttpClient webClient;

    /**
     * 搜索专用客户端：在 {@link #webClient} 基础上把单次调用超时压到 5s。
     *
     * <p>网页路由页 CDN 挂起时 12s 也会被耗满，叠加多重试会把搜索拖到 ~50s；
     * 搜索对「网页页」的依赖只是补字段，短超时 + 放弃该路更划算。
     */
    private final OkHttpClient searchClient;

    /**
     * 播放器签名栈（x-gorgon/x-helios/x-medusa + branch-0 抖动）。
     * 与搜索/详情用的旧 X-Gorgon 是两套：播放接口 2026-10-14 起只认完整签名链。
     */
    private final PlaybackSigner playbackSigner = new PlaybackSigner();

    /**
     * BUG-104：原字段 {@code appChannelBlockedByGateway} 已移除，改为每次取流的
     * 局部标记（见 {@link #videoModel}）。NetClient 是全局单例，实例字段会被并发
     * 取流（起播 + 预取下一集）互相覆盖，导致「身份问题 / 该集未开放」文案张冠李戴。
     */

    public NetClient(OkHttpClient client) {
        this.client = client;
        this.webClient = client.newBuilder()
                .callTimeout(12, java.util.concurrent.TimeUnit.SECONDS)
                .build();
        this.searchClient = client.newBuilder()
                .callTimeout(SEARCH_CALL_TIMEOUT_S, TimeUnit.SECONDS)
                .build();
        AppLog.INSTANCE.setInterfaceInfo(SignedRequest.BASE_URL,
                SignedRequest.deviceId(), SignedRequest.iid());
    }

    /** 榜单缓存条目：最后一次成功的分页结果。 */
    private static final class CachedRanking {
        final RankingPage page;
        final long at;

        CachedRanking(RankingPage page, long at) {
            this.page = page;
            this.at = at;
        }
    }

    /**
     * 榜单内存缓存（boardId#page → 最近一次成功结果，LRU + 24h）。
     *
     * <p>用途：所有重试都失败时返回「上一次成功抓到的同一页」，用户看到的是稍旧的
     * 榜单而不是白屏 + 错误文案。进程重启即失效，不落盘（榜单时效性强，不值得）。
     */
    private static final Map<String, CachedRanking> RANK_CACHE =
            Collections.synchronizedMap(new LinkedHashMap<String, CachedRanking>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, CachedRanking> eldest) {
                    return size() > RANK_CACHE_MAX;
                }
            });

    /* ==================== 数据模型 ==================== */

    public static final class Drama {
        public final String id;          // series_id
        public final String title;
        public final String cover;
        public final String intro;
        public final String episodeCount;
        public final String remark;      // 更新状态/共N集
        public final List<String> tags;

        Drama(String id, String title, String cover, String intro,
              String episodeCount, String remark, List<String> tags) {
            this.id = id;
            this.title = title;
            this.cover = cover;
            this.intro = intro;
            this.episodeCount = episodeCount;
            this.remark = remark;
            this.tags = tags;
        }

        /** 公开工厂方法：本地片名索引命中时构造精简 Drama（无简介/标签）。 */
        public static Drama minimal(String id, String title, String cover) {
            return new Drama(id, title, cover, "", "", "", Collections.<String>emptyList());
        }
    }

    public static final class Episode {
        public final String vid;         // 每集视频 ID
        public final int index;          // 集号，从 1 开始
        public final String title;

        Episode(String vid, int index, String title) {
            this.vid = vid;
            this.index = index;
            this.title = title;
        }
    }

    public static final class SeriesDetail {
        public final String seriesId;
        public final String title;
        public final String cover;
        public final String intro;
        public final List<Episode> episodes;

        SeriesDetail(String seriesId, String title, String cover,
                     String intro, List<Episode> episodes) {
            this.seriesId = seriesId;
            this.title = title;
            this.cover = cover;
            this.intro = intro;
            this.episodes = episodes;
        }
    }

    public static final class Variant {
        public final String codec;        // codec_type（bytevc1=HEVC/H.265，h264=AVC/H.264）
        public final int height;          // 清晰度高度
        public final List<String> urls;   // 该变体的多地址（已去重+解码）
        public final byte[] cencKey;      // CENC-AES-128 密钥（未加密时为 null）
        public final boolean encrypted;   // 是否 CENC 加密

        Variant(String codec, int height, List<String> urls, byte[] cencKey, boolean encrypted) {
            this.codec = codec;
            this.height = height;
            this.urls = urls;
            this.cencKey = cencKey;
            this.encrypted = encrypted;
        }
    }

    /**
     * 变体选择：优先 H.264，同编码内按高度降序。
     *
     * 背景（线上日志 DWIN-380P / 华为 Android 12）：红果同时下发 h264 与 bytevc1(HEVC)
     * 变体，旧逻辑一律按高度选，1080p 那档往往是 HEVC——而部分电视 SoC（尤其
     * 全志/晨星老方案与部分华为定制机）**根本没有 HEVC 硬解**，MediaCodec 初始化
     * 失败即黑屏无提示。故：
     *  - 设备不支持 HEVC 时（[deviceSupportsHevc] = false）：完全排除 HEVC 变体，
     *    只在 H.264 里选，宁可降清晰度也要能播；
     *  - 设备支持 HEVC 时：保持原策略（HEVC 效率更高、同清晰度码率更低），
     *    但 H.264 与 HEVC 同高度时优先 H.264——兼容性优先，避免软解兜底翻车。
     * H.264 缺失时仍返回 HEVC，不做强制丢弃（否则老片源可能无源可播）。
     */
    private static void sortVariantsForDevice(List<Variant> variants) {
        final boolean hevcOk = deviceSupportsHevc();
        boolean ascending = false;
        if (!hevcOk) {
            List<Variant> avcOnly = new ArrayList<>();
            for (Variant v : variants) {
                if (!isHevc(v.codec)) avcOnly.add(v);
            }
            if (!avcOnly.isEmpty()) {
                AppLog.INSTANCE.d(TAG, "【检验环节1】设备无 HEVC 硬解，剔除 "
                        + (variants.size() - avcOnly.size()) + " 个 HEVC/bytevc1 变体，"
                        + "只保留 H.264（剩余档位见下条选中日志）");
                variants.clear();
                variants.addAll(avcOnly);
            } else if (!variants.isEmpty()) {
                // 全部档位都是 HEVC、设备又没有硬解：丢掉就无源可播，只能留。
                // 此时必须改选**最低档**（升序）——按原策略选最高档等于让软解
                // 硬扛 1080p HEVC，线上实测直接 OutOfMemoryError。
                ascending = true;
                AppLog.INSTANCE.d(TAG, "【检验环节1】设备无 HEVC 硬解且全部档位都是 HEVC，"
                        + "改为按清晰度升序选最低档（避免软解高码率 HEVC 打爆内存）");
            }
        }
        final boolean asc = ascending;
        Collections.sort(variants, new Comparator<Variant>() {
            @Override
            public int compare(Variant a, Variant b) {
                if (a.height != b.height) {
                    if (asc) {
                        // 高度未知（0）视为最差，排在最后，避免被当成「最低档」选中
                        int ha = a.height > 0 ? a.height : Integer.MAX_VALUE;
                        int hb = b.height > 0 ? b.height : Integer.MAX_VALUE;
                        return ha < hb ? -1 : (ha > hb ? 1 : 0);
                    }
                    return b.height - a.height;
                }
                int ra = isHevc(a.codec) ? 1 : 0;
                int rb = isHevc(b.codec) ? 1 : 0;
                return ra - rb;
            }
        });
    }

    /** codec_type 是否为 HEVC（红果下发的 bytevc1 即 H.265）。 */
    private static boolean isHevc(String codec) {
        return codec != null && codec.toLowerCase().contains("bytevc1");
    }

    /**
     * 设备是否具备 HEVC(H.265) 硬解能力（结果缓存，探测只在首次调用做一次）。
     *
     * 用 MediaCodecList 查 `video/hevc` 的**硬件**解码器：有的 ROM 会挂软解
     * （名含 google/software），软解 1080p HEVC 在电视 SoC 上必然卡死，
     * 不能算「支持」。什么都不信时保守返回 false（走 H.264 更安全）。
     */
    private static Boolean hevcSupportedCache = null;

    /**
     * 运行期否决 HEVC：播放器实测「解码器初始化/解码失败」后调用。
     *
     * <p>为什么需要它：ROM 常谎报能力——{@link #deviceSupportsHevc} 是按
     * MediaCodecList 里有 `video/hevc` 硬件解码器来判定的，但「解码器存在」不等于
     * 「能解这个码流」。线上 DWIN-380P（华为 Android 12）：探测为 true，取流选中
     * bytevc1 1080p，然后硬解 INIT_FAILED → 切软解 → 软解同样失败 → 无限重建。
     *
     * <p>否决后本进程内所有后续取流都只挑 H.264，从源头避免再踩同一个坑；
     * 进程重启后重新探测（不落盘——用户换网络/系统升级后可能就好了，
     * 且播放页里仍可手动切软解自救）。
     */
    public static synchronized void disableHevc() {
        if (hevcSupportedCache != null && !hevcSupportedCache) return;
        hevcSupportedCache = false;
        AppLog.INSTANCE.d(TAG, "运行期否决 HEVC：后续取流只选 H.264");
    }

    private static synchronized boolean deviceSupportsHevc() {
        if (hevcSupportedCache != null) return hevcSupportedCache;
        boolean ok = false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
            try {
                for (MediaCodecInfo info : new MediaCodecList(MediaCodecList.REGULAR_CODECS)
                        .getCodecInfos()) {
                    if (info.isEncoder()) continue;
                    String name = info.getName().toLowerCase();
                    if (name.contains("google") || name.contains("software")
                            || name.contains("sw.") || name.contains("ffmpeg")) {
                        continue; // 软解，不计入
                    }
                    for (String type : info.getSupportedTypes()) {
                        if ("video/hevc".equalsIgnoreCase(type)) {
                            ok = true;
                            break;
                        }
                    }
                    if (ok) break;
                }
            } catch (Throwable t) {
                ok = false;
            }
        }
        AppLog.INSTANCE.d(TAG, "设备 HEVC 硬解能力=" + ok);
        hevcSupportedCache = ok;
        return ok;
    }

    public static final class VideoModel {
        public final String videoId;        // 请求的 vid
        public final List<String> urls;     // 最优变体的多地址（向后兼容）
        public final int duration;          // 秒
        public final String referer;        // App 版媒体固定 https://novel.snssdk.com/
        public final byte[] cencKey;        // 最优变体的 CENC-AES-128 密钥（未加密时为 null）
        public final boolean encrypted;     // 最优变体是否 CENC 加密
        public final List<Variant> variants; // 全部可选变体（按高度降序）
        public final String codec;          // 最优变体 codec
        public final int height;            // 最优变体高度

        VideoModel(String videoId, List<String> urls, int duration, String referer,
                   byte[] cencKey, boolean encrypted,
                   List<Variant> variants, String codec, int height) {
            this.videoId = videoId;
            this.urls = urls;
            this.duration = duration;
            this.referer = referer;
            this.cencKey = cencKey;
            this.encrypted = encrypted;
            this.variants = variants;
            this.codec = codec;
            this.height = height;
        }
    }

    public static final class CatalogPage {
        public final List<Drama> dramas;
        public final boolean hasMore;
        public final int nextOffset;
        public final String sessionId;
        /** 筛选行（仅首次请求 need_selector_panel=true 时有值，翻页时为空） */
        public final List<FilterRow> filterRows;

        CatalogPage(List<Drama> dramas, boolean hasMore, int nextOffset, String sessionId) {
            this(dramas, hasMore, nextOffset, sessionId, new ArrayList<FilterRow>());
        }

        CatalogPage(List<Drama> dramas, boolean hasMore, int nextOffset, String sessionId,
                    List<FilterRow> filterRows) {
            this.dramas = dramas;
            this.hasMore = hasMore;
            this.nextOffset = nextOffset;
            this.sessionId = sessionId;
            this.filterRows = filterRows;
        }
    }

    /** 一个筛选胶囊（id 为空串表示「全部」，由 UI 自行插入）。 */
    public static final class FilterOption {
        public final String id;
        public final String label;

        FilterOption(String id, String label) {
            this.id = id;
            this.label = label;
        }
    }

    /** 一行筛选：类型（对应 select_items 的 key）+ 展示名 + 可选项。 */
    public static final class FilterRow {
        public final String type;
        public final String label;
        public final List<FilterOption> options;

        FilterRow(String type, String label, List<FilterOption> options) {
            this.type = type;
            this.label = label;
            this.options = options;
        }
    }

    public static final class DanmakuItem {
        public final String id;
        public final String text;
        public final long timeMs;

        DanmakuItem(String id, String text, long timeMs) {
            this.id = id;
            this.text = text;
            this.timeMs = timeMs;
        }
    }

    /** 榜单一条：名次 + 剧集 + 热度/评分等展示文案。 */
    public static final class RankItem {
        public final int rank;
        public final Drama drama;
        /** 热度文案，如「7850万热度」 */
        public final String heat;
        /** 评分文案，如「9.0」 */
        public final String score;
        /** 收藏文案，如「268.7万收藏」 */
        public final String favorite;
        /** 点赞文案，如「470.6万点赞」 */
        public final String like;
        /** 状态标签，如「新剧」「热播中」 */
        public final List<String> statusTags;

        RankItem(int rank, Drama drama, String heat, String score, String favorite, String like,
                 List<String> statusTags) {
            this.rank = rank;
            this.drama = drama;
            this.heat = heat;
            this.score = score;
            this.favorite = favorite;
            this.like = like;
            this.statusTags = statusTags;
        }
    }

    /** 榜单分页结果。 */
    public static final class RankingPage {
        public final String boardId;
        public final int page;
        public final int totalPages;
        public final boolean hasMore;
        /** 榜单更新时间文案，如「10月14日已更新 · 基于红果内观看/互动等综合热度排序」 */
        public final String updatedText;
        public final List<RankItem> items;

        RankingPage(String boardId, int page, int totalPages, boolean hasMore,
                    String updatedText, List<RankItem> items) {
            this.boardId = boardId;
            this.page = page;
            this.totalPages = totalPages;
            this.hasMore = hasMore;
            this.updatedText = updatedText;
            this.items = items;
        }
    }

    /**
     * 榜单定义。{@code boardId} 是我们自己的稳定标识（也用于 UI 记忆选中项）；
     * {@code path} 是红果网页 {@code /rank/&lt;path&gt;} 的路径段。
     */
    public static final class RankBoard {
        public final String boardId;
        public final String path;
        public final String label;

        public RankBoard(String boardId, String path, String label) {
            this.boardId = boardId;
            this.path = path;
            this.label = label;
        }
    }

    /** 可用榜单列表（顺序即 UI 展示顺序）。 */
    public static final List<RankBoard> RANK_BOARDS = Collections.unmodifiableList(
            new ArrayList<RankBoard>() {{
                add(new RankBoard("hot-drama", "hot-drama", "热播榜"));
                add(new RankBoard("hot-real-drama", "hot-real-drama", "真人剧榜"));
                add(new RankBoard("hot-comic-drama", "hot-comic-drama", "漫剧榜"));
                add(new RankBoard("hot-ai-drama", "hot-ai-drama", "AI剧榜"));
            }});

    /* ==================== 分类（App 版） ==================== */

    public CatalogPage catalog(String genreKey, String scene, int offset, String sessionId) throws Exception {
        AppLog.INSTANCE.d(TAG, "catalog genre=" + genreKey + " scene=" + scene + " offset=" + offset);
        // 红果优先：App 版分类（X-Argus 签名族）→ 网页版公开分类页（免签名免 Cookie，
        // 与 HGTV 同款路径 /category/<real|comic|ai>-drama）。
        try {
            CatalogPage p = appCatalog(genreKey, scene, offset, sessionId);
            if (!p.dramas.isEmpty()) {
                return p;
            }
            if (!p.hasMore) {
                // 尾页：App 接口明确 has_more=false 且本页为空 → 正常结束。
                // 不能再回退网页版：网页版分页总数少于 App（ai-drama 只有 40 多页），
                // 同一 offset 换算出的 page 常常 404，会把整个分类判为失败。
                AppLog.INSTANCE.d(TAG, "【栏目】App 分类到尾页（has_more=false），直接结束");
                return p;
            }
            AppLog.INSTANCE.d(TAG, "【栏目】App 分类为空，回退网页版 category");
        } catch (Exception e) {
            AppLog.INSTANCE.e(TAG, "【栏目】App 分类失败，回退网页版 category", e);
        }
        try {
            CatalogPage w = webCatalog(genreKey, offset);
            if (!w.dramas.isEmpty()) {
                return w;
            }
            AppLog.INSTANCE.d(TAG, "【栏目】网页版也为空");
        } catch (Exception e) {
            AppLog.INSTANCE.e(TAG, "【栏目】网页版也失败", e);
        }
        throw new Exception("栏目暂无数据");
    }

    /** App 版分类（catalog 的签名 POST 主体）。 */
    private CatalogPage appCatalog(String genreKey, String scene, int offset, String sessionId) throws Exception {
        JSONObject selectItems = new JSONObject();
        selectItems.put("genre", new JSONArray().put(genreKey));
        selectItems.put("sort", new JSONArray().put("online_time"));
        selectItems.put("gender", new JSONArray());
        selectItems.put("category_dim_theme", new JSONArray());
        selectItems.put("category_dim_role", new JSONArray());
        selectItems.put("category_dim_epoch", new JSONArray());
        selectItems.put("online_time", new JSONArray());
        selectItems.put("creation_status", new JSONArray());

        JSONObject body = new JSONObject();
        body.put("req_scene", scene == null ? "" : scene);
        body.put("offset", offset);
        body.put("limit", 18);
        body.put("req_type", "only_content");
        body.put("need_selector_panel", false);
        body.put("client_req_type", offset > 0 ? 2 : 3);
        body.put("session_id", sessionId == null ? "" : sessionId);
        body.put("filter_ids", "");
        body.put("select_items", selectItems);

        JSONObject root = appPost("/reading/distribution/category/landpage/v/", body.toString());
        JSONObject data = root.optJSONObject("data");
        if (data == null) {
            throw new Exception("红果分类接口未返回数据");
        }
        JSONArray rows = data.optJSONArray("video_data");
        List<Drama> dramas = new ArrayList<>();
        if (rows != null) {
            for (int i = 0; i < rows.length(); i++) {
                JSONObject row = rows.optJSONObject(i);
                if (row == null) continue;
                Drama d = parseDrama(row.optJSONObject("video_data"), row);
                if (d != null && d.id != null && !d.id.isEmpty()) {
                    dramas.add(d);
                }
            }
        }
        boolean hasMore = data.optBoolean("has_more", false);
        int nextOffset = data.optInt("next_offset", offset + (rows == null ? 0 : rows.length()));
        String nextSession = data.optString("session_id", sessionId == null ? "" : sessionId);
        AppLog.INSTANCE.d(TAG, "catalog 返回 " + dramas.size() + " 条 hasMore=" + hasMore);
        return new CatalogPage(dramas, hasMore, nextOffset, nextSession);
    }

    /* ==================== 分类页（带 6 维筛选） ==================== */

    /** 筛选行展示顺序：类型 + 中文名（与参考实现一致）。 */
    private static final String[][] FILTER_ROW_DEFS = {
            {"category_dim_theme", "主题"},
            {"category_dim_role", "设定"},
            {"category_dim_epoch", "背景"},
            {"sort", "推荐"},
            {"gender", "受众"},
            {"online_time", "时间"}
    };

    private static String filterRowLabel(String type) {
        for (String[] def : FILTER_ROW_DEFS) {
            if (def[0].equals(type)) return def[1];
        }
        return type;
    }

    private static boolean isFilterRowType(String type) {
        for (String[] def : FILTER_ROW_DEFS) {
            if (def[0].equals(type)) return true;
        }
        return false;
    }

    /**
     * 分类页数据（仅 App 接口，不走网页兜底：网页版不支持筛选，静默降级会让筛选看起来失效）。
     * {@code wantSelector=true} 时服务端会额外下发 {@code selector_rows}，用于渲染筛选胶囊。
     */
    public CatalogPage category(String genreKey, String scene, int offset, String sessionId,
                                Map<String, String> filters, boolean wantSelector) throws Exception {
        AppLog.INSTANCE.d(TAG, "category genre=" + genreKey + " scene=" + scene
                + " offset=" + offset + " filters=" + filters + " selector=" + wantSelector);
        JSONObject selectItems = new JSONObject();
        JSONArray genreArr = new JSONArray();
        if (genreKey != null && !genreKey.isEmpty()) genreArr.put(genreKey);
        selectItems.put("genre", genreArr);
        // 推荐为空时按热度排：服务端对空 sort 的容错未知，且「全部」在交互上等价于默认推荐
        String sort = filters == null ? null : filters.get("sort");
        selectItems.put("sort", new JSONArray().put(sort == null || sort.isEmpty() ? "hot_score" : sort));
        selectItems.put("gender", filterArray(filters, "gender"));
        selectItems.put("category_dim_theme", filterArray(filters, "category_dim_theme"));
        selectItems.put("category_dim_role", filterArray(filters, "category_dim_role"));
        selectItems.put("category_dim_epoch", filterArray(filters, "category_dim_epoch"));
        selectItems.put("online_time", filterArray(filters, "online_time"));
        selectItems.put("creation_status", new JSONArray());

        JSONObject body = new JSONObject();
        body.put("req_scene", scene == null ? "" : scene);
        body.put("offset", offset);
        body.put("limit", 18);
        body.put("req_type", wantSelector ? "default" : "only_content");
        body.put("need_selector_panel", wantSelector);
        body.put("client_req_type", wantSelector ? 3 : (offset > 0 ? 2 : 3));
        body.put("session_id", sessionId == null ? "" : sessionId);
        body.put("filter_ids", "");
        body.put("select_items", selectItems);

        JSONObject root = appPost("/reading/distribution/category/landpage/v/", body.toString());
        JSONObject data = root.optJSONObject("data");
        if (data == null) throw new Exception("红果分类接口未返回数据");

        JSONArray rows = data.optJSONArray("video_data");
        List<Drama> dramas = new ArrayList<>();
        if (rows != null) {
            for (int i = 0; i < rows.length(); i++) {
                JSONObject row = rows.optJSONObject(i);
                if (row == null) continue;
                Drama d = parseDrama(row.optJSONObject("video_data"), row);
                if (d != null && d.id != null && !d.id.isEmpty()) dramas.add(d);
            }
        }
        boolean hasMore = data.optBoolean("has_more", false);
        int nextOffset = data.optInt("next_offset", offset + (rows == null ? 0 : rows.length()));
        String nextSession = data.optString("session_id", sessionId == null ? "" : sessionId);
        List<FilterRow> filterRows = wantSelector
                ? parseFilterRows(data.optJSONArray("selector_rows"))
                : new ArrayList<FilterRow>();
        AppLog.INSTANCE.d(TAG, "category 返回 " + dramas.size() + " 条 hasMore=" + hasMore
                + " 筛选项=" + filterRows.size() + " 行");
        return new CatalogPage(dramas, hasMore, nextOffset, nextSession, filterRows);
    }

    private static JSONArray filterArray(Map<String, String> filters, String key) {
        JSONArray arr = new JSONArray();
        if (filters == null) return arr;
        String value = filters.get(key);
        if (value != null && !value.isEmpty()) arr.put(value);
        return arr;
    }

    /** 解析 selector_rows：[{type, items:[{selector_item_id, show_name}]}] → 固定 6 行。 */
    private static List<FilterRow> parseFilterRows(JSONArray rows) {
        List<FilterRow> out = new ArrayList<>();
        Map<String, FilterRow> byType = new HashMap<>();
        if (rows != null) {
            for (int i = 0; i < rows.length(); i++) {
                JSONObject row = rows.optJSONObject(i);
                if (row == null) continue;
                String type = optText(row, "type");
                if (!isFilterRowType(type)) continue;
                JSONArray items = row.optJSONArray("items");
                if (items == null) continue;
                List<FilterOption> options = new ArrayList<>();
                Set<String> seen = new HashSet<>();
                for (int j = 0; j < items.length(); j++) {
                    JSONObject item = items.optJSONObject(j);
                    if (item == null) continue;
                    String id = firstNonEmpty(optText(item, "selector_item_id"));
                    String label = firstNonEmpty(optText(item, "show_name"));
                    if (id.isEmpty() || label.isEmpty() || seen.contains(id)) continue;
                    seen.add(id);
                    options.add(new FilterOption(id, label));
                }
                if (!options.isEmpty()) byType.put(type, new FilterRow(type, filterRowLabel(type), options));
            }
        }
        // 固定顺序输出；排序/时间两行即使服务端没给也本地兜底（id 语义已确认）
        for (String[] def : FILTER_ROW_DEFS) {
            FilterRow row = byType.get(def[0]);
            if (row == null) row = fallbackFilterRow(def[0]);
            if (row != null) out.add(row);
        }
        return out;
    }

    /** 排序 / 时间两行的本地兜底（其余维度依赖服务端下发的 cate_* id，不伪造）。 */
    private static FilterRow fallbackFilterRow(String type) {
        List<FilterOption> options = new ArrayList<>();
        if ("sort".equals(type)) {
            options.add(new FilterOption("online_time", "最新上架"));
            options.add(new FilterOption("hot_score", "最高热度"));
            options.add(new FilterOption("hot_collect", "最高收藏"));
        } else if ("online_time".equals(type)) {
            options.add(new FilterOption("days_7", "7天内上新"));
            options.add(new FilterOption("days_14", "14天内上新"));
            options.add(new FilterOption("days_30", "30天内上新"));
            options.add(new FilterOption("days_90", "90天内上新"));
        } else {
            return null;
        }
        return new FilterRow(type, filterRowLabel(type), options);
    }

    /* ==================== 分类（网页版兜底） ==================== */

    /** 网页版分类每页条数（服务端固定 24）。 */
    private static final int WEB_CATEGORY_PAGE_SIZE = 24;

    /** 网页版分类最大页码（防御脏数据导致无限翻页）。 */
    private static final int WEB_CATEGORY_MAX_PAGE = 200;

    /** genre key → 网页版分类路径（与 HGTV 一致）。 */
    private static String webCategoryPath(String genreKey) {
        if ("comic_series".equals(genreKey)) return "comic-drama";
        if ("ai_series".equals(genreKey)) return "ai-drama";
        return "real-drama";
    }

    /**
     * 网页版分类：{@code GET /category/&lt;path&gt;[?page=N]}，免签名免 Cookie。
     * 列表内嵌在页面 {@code _ROUTER_DATA} 里：{@code loaderData} →
     * {@code category_$}（字面量 key）/ {@code category_page} →
     * {@code recommendList}（或 {@code categoryData.recommendList}），
     * {@code pagination.totalPages} 判断是否还有下一页。
     */
    public CatalogPage webCatalog(String genreKey, int offset) throws Exception {
        int page = offset / WEB_CATEGORY_PAGE_SIZE + 1;
        if (page < 1) page = 1;
        if (page > WEB_CATEGORY_MAX_PAGE) throw new Exception("栏目翻页超出上限");
        String url = WEB_SITE + "/category/" + webCategoryPath(genreKey)
                + (page > 1 ? "?page=" + page : "");
        AppLog.INSTANCE.d(TAG, "【栏目】网页版 genre=" + genreKey + " url=" + url);
        JSONObject root = fetchRouterData(url);
        JSONObject pageObj = findLoaderPage(root, "category_$", "category_page");
        if (pageObj == null) {
            throw new Exception("栏目页面结构异常");
        }
        JSONArray rows = pageObj.optJSONArray("recommendList");
        if (rows == null) {
            JSONObject categoryData = pageObj.optJSONObject("categoryData");
            if (categoryData != null) rows = categoryData.optJSONArray("recommendList");
        }
        List<Drama> dramas = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        if (rows != null) {
            for (int i = 0; i < rows.length(); i++) {
                JSONObject row = rows.optJSONObject(i);
                if (row == null) continue;
                Drama d = parseDrama(row.optJSONObject("video_data"), row);
                // 与 HGTV valid() 一致：id 必须是纯数字、标题非空、按 id 去重
                if (d == null || d.id == null || d.title == null || d.title.isEmpty()) continue;
                if (!d.id.matches("\\d{1,30}")) continue;
                if (!seen.add(d.id)) continue;
                dramas.add(d);
            }
        }
        JSONObject pagination = pageObj.optJSONObject("pagination");
        int totalPages = pagination == null ? page : pagination.optInt("totalPages", page);
        boolean hasMore = page < totalPages;
        AppLog.INSTANCE.d(TAG, "【栏目】网页版返回 " + dramas.size()
                + " 条 page=" + page + "/" + totalPages + " hasMore=" + hasMore);
        return new CatalogPage(dramas, hasMore, page * WEB_CATEGORY_PAGE_SIZE, "");
    }

    /**
     * 拉取某个分类的全量片单（翻页到底），用于构建本地片名索引。
     * @param maxPages 翻页上限（防御，避免接口异常导致无限拉取）
     */
    public List<Drama> catalogAll(String genreKey, String scene, int maxPages) throws Exception {
        List<Drama> all = new ArrayList<>();
        int offset = 0;
        String session = "";
        for (int page = 0; page < maxPages; page++) {
            CatalogPage p;
            try {
                p = catalog(genreKey, scene, offset, session);
            } catch (Exception e) {
                if (page == 0) throw e;
                // 中途某页失败：保留已拉到的数据。旧实现直接抛出 → 上层丢弃整个分类
                // （日志实测 ai_series 尾页 404 把 3412 条索引覆写成 0 条）。
                AppLog.INSTANCE.e(TAG, "catalogAll genre=" + genreKey + " 第" + (page + 1)
                        + "页失败，保留前 " + all.size() + " 条", e);
                break;
            }
            all.addAll(p.dramas);
            if (!p.hasMore || p.dramas.isEmpty()) break;
            offset = p.nextOffset;
            session = p.sessionId;
        }
        AppLog.INSTANCE.d(TAG, "catalogAll genre=" + genreKey + " 共 " + all.size() + " 条");
        return all;
    }

    /* ==================== 榜单（网页版） ==================== */

    /**
     * 拉取红果官方榜单一页：{@code GET /rank/&lt;board.path&gt;}（page&gt;1 时加
     * {@code ?page=N}）。页面是 SSR 直出的 HTML，榜单数据内嵌在
     * {@code data-fn-args} 脚本属性里（两种形态：双引号包裹的 mergeLoaderData、
     * 单引号包裹的 run-router-data-fn），解析出 {@code rankList} 数组。
     * 与搜索同站免签名，复用 {@link #getText(String)}。
     *
     * <p><b>解析级重试 + 缓存兜底</b>：{@link #getText(String)} 只保证拿到非空
     * HTML，实测仍有约 8% 的响应 200 + 空 body、或解析不出 rankList。这里把
     * 「抓取 + 解析 + 校验」整体重试 {@link #RANK_FETCH_ATTEMPTS} 轮；全部失败时
     * 返回同页最近一次成功的缓存结果（≤24h），把「白屏/错误页」变成「稍旧内容」。
     */
    public RankingPage ranking(RankBoard board, int page) throws Exception {
        if (board == null || page < 1 || page > RANK_MAX_PAGE) {
            throw new Exception("榜单页码无效");
        }
        Exception lastError = null;
        for (int attempt = 0; attempt < RANK_FETCH_ATTEMPTS; attempt++) {
            if (attempt > 0) {
                try {
                    Thread.sleep(RANK_RETRY_BACKOFF_MS * attempt);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            try {
                RankingPage p = fetchRanking(board, page);
                RANK_CACHE.put(board.boardId + "#" + page,
                        new CachedRanking(p, System.currentTimeMillis()));
                return p;
            } catch (Exception e) {
                lastError = e;
                AppLog.INSTANCE.e(TAG, "ranking 第" + (attempt + 1) + "轮失败 board="
                        + board.boardId + " page=" + page, e);
            }
        }
        // 缓存兜底：宁可给旧数据也不给白屏
        CachedRanking cached = RANK_CACHE.get(board.boardId + "#" + page);
        if (cached != null
                && System.currentTimeMillis() - cached.at <= RANK_CACHE_TTL_MS) {
            AppLog.INSTANCE.d(TAG, "ranking 使用缓存兜底 board=" + board.boardId
                    + " page=" + page + " age_ms=" + (System.currentTimeMillis() - cached.at));
            return cached.page;
        }
        throw lastError != null ? lastError : new Exception("榜单暂无数据");
    }

    /** 单次「抓取 + 解析 + 校验」，不含重试与缓存。 */
    private RankingPage fetchRanking(RankBoard board, int page) throws Exception {
        String url = WEB_SITE + "/rank/" + board.path + (page > 1 ? "?page=" + page : "");
        AppLog.INSTANCE.d(TAG, "ranking board=" + board.boardId + " page=" + page);
        List<RankItem> items;
        String html;
        JSONObject content;
        try {
            html = getText(url);
            if (html == null || html.isEmpty() || html.length() > 4 << 20) {
                throw new Exception("榜单页面无响应或过大");
            }
            content = extractRankContent(html);
            if (content == null || !content.optBoolean("isSuccess", false)) {
                throw new Exception("榜单数据不可用");
            }
            JSONArray rows = content.optJSONArray("rankList");
            items = new ArrayList<>();
            if (rows != null) {
                int previous = (page - 1) * RANK_PAGE_SIZE;
                for (int i = 0; i < rows.length(); i++) {
                    JSONObject row = rows.optJSONObject(i);
                    if (row == null) continue;
                    RankItem item = parseRankRow(row, previous);
                    if (item != null) items.add(item);
                }
            }
        } catch (Exception e) {
            AppLog.INSTANCE.e(TAG, "ranking 失败 board=" + board.boardId, e);
            throw e;
        }
        if (items.isEmpty()) {
            throw new Exception("榜单暂无数据");
        }
        JSONObject pagination = content.optJSONObject("pagination");
        int pageNum = pagination == null ? page : pagination.optInt("pageNum", page);
        int totalPages = pagination == null ? page : pagination.optInt("totalPages", page);
        boolean hasMore = pageNum < totalPages && !isEmpty(pagination == null ? null : pagination.optString("nextUrl", ""));
        // updatedText 在 loaderData 的 rank_*/page 页对象里，脚本形态二没有；用页面级解析兜底
        String updatedText = extractRankUpdatedText(html);
        AppLog.INSTANCE.d(TAG, "ranking 返回 " + items.size() + " 条 page=" + pageNum + "/" + totalPages);
        return new RankingPage(board.boardId, pageNum, totalPages, hasMore, updatedText, items);
    }

    /**
     * 从 HTML 提取榜单数据对象（{@code isSuccess}/{@code rankList}/{@code pagination}）。
     * 两种脚本形态统一兼容：先扫单引号属性（形态二 JSON 直接在参数数组里），
     * 再扫双引号属性（形态二嵌套在 routerDataFnArgs[0] 字符串里）。
     */
    private static JSONObject extractRankContent(String html) {
        // 形态二（单引号）：data-fn-args='[key, "content", {isSuccess, rankList...}]'
        Matcher sq = RANK_FN_ARGS_SQ.matcher(html);
        while (sq.find()) {
            Object arr = parseJsonLenient(unescapeHtml(sq.group(1)));
            if (!(arr instanceof JSONArray)) continue;
            JSONArray a = (JSONArray) arr;
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o != null && o.has("rankList")) return o;
            }
        }
        // 形态一（双引号）：mergeLoaderData 的 routerDataFnArgs[0] 是 JSON 字符串
        Matcher dq = RANK_FN_ARGS_DQ.matcher(html);
        while (dq.find()) {
            Object arr = parseJsonLenient(unescapeHtml(dq.group(1)));
            if (!(arr instanceof JSONArray)) continue;
            JSONArray a = (JSONArray) arr;
            for (int i = 0; i < a.length(); i++) {
                JSONObject entry = a.optJSONObject(i);
                if (entry == null) continue;
                JSONArray fnArgs = entry.optJSONArray("routerDataFnArgs");
                if (fnArgs == null || fnArgs.length() == 0) continue;
                Object v = parseJsonLenient(fnArgs.optString(0));
                if (v instanceof JSONObject && ((JSONObject) v).has("rankList")) {
                    return (JSONObject) v;
                }
            }
        }
        return null;
    }

    /** 解析单条榜单行。rank 必须落在当前页区间且不重复出现倒退（防御脏数据）。 */
    private static RankItem parseRankRow(JSONObject row, int previousMax) {
        String id = firstNonEmpty(optText(row, "seriesId"), optText(row, "id"));
        if (id == null || id.isEmpty() || !id.matches("\\d{10,30}")) return null;
        String title = optText(row, "title");
        if (title == null || title.isEmpty()) return null;
        int rank = row.optInt("rank", 0);
        if (rank <= previousMax || rank > previousMax + RANK_PAGE_SIZE) return null;

        String cover = optText(row, "cover");
        String intro = optText(row, "description");
        String heat = optText(row, "heatText");
        String score = optText(row, "scoreText");
        if (score != null) score = score.replaceFirst("^评分", "");
        String favorite = optText(row, "favoriteText");
        String like = optText(row, "likeText");
        // 展示行：热度 + 状态标签（第1名另有金色徽标由 UI 处理）
        StringBuilder remark = new StringBuilder();
        if (heat != null && !heat.isEmpty()) remark.append(heat);
        List<String> statusTags = new ArrayList<>();
        JSONArray st = row.optJSONArray("statusTags");
        if (st != null) {
            for (int i = 0; i < st.length(); i++) {
                String t = st.optString(i);
                if (t != null && !t.isEmpty()) statusTags.add(t);
            }
        }
        List<String> tags = new ArrayList<>();
        JSONArray tg = row.optJSONArray("tags");
        if (tg != null) {
            for (int i = 0; i < tg.length(); i++) {
                addTag(tags, tg.optString(i));
            }
        }
        if (statusTags.size() > 0) {
            addTag(tags, statusTags.get(0));
        }
        Drama drama = new Drama(id, title, cover, intro, "", remark.toString(), tags);
        return new RankItem(rank, drama, heat, score, favorite, like, statusTags);
    }

    /** 从页面 JSON 里抠出 updatedText（形态二没有此字段，找不到就空串）。 */
    private static String extractRankUpdatedText(String html) {
        int i = html.indexOf("\"updatedText\"");
        if (i < 0) i = html.indexOf("updatedText");
        if (i < 0) return "";
        int colon = html.indexOf(':', i);
        if (colon < 0) return "";
        int q1 = html.indexOf('"', colon + 1);
        if (q1 < 0) return "";
        int q2 = html.indexOf('"', q1 + 1);
        if (q2 < 0) return "";
        String raw = html.substring(q1 + 1, q2);
        // 该字段在 _ROUTER_DATA 的 JSON 字符串里，中文是原样、斜杠可能被转义
        return unescapeHtml(raw).replace("\\u002F", "/");
    }

    /** HTML 实体反转义（仅处理 &quot; &amp; &lt; &gt; &#39; 与数字实体）。 */
    private static String unescapeHtml(String s) {
        if (s == null || s.indexOf('&') < 0) return s;
        return s.replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&amp;", "&");
    }

    /** 宽容 JSON 解析：用 JSONTokener 只取前缀合法值，失败返回 null。 */
    private static Object parseJsonLenient(String s) {
        if (s == null) return null;
        s = s.trim();
        if (s.isEmpty()) return null;
        try {
            JSONTokener t = new JSONTokener(s);
            Object v = t.nextValue();
            return v;
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean isEmpty(String s) {
        return s == null || s.isEmpty();
    }

    /* ==================== 详情（App 版） ==================== */

    public SeriesDetail detail(String seriesId) throws Exception {
        AppLog.INSTANCE.d(TAG, "detail series_id=" + seriesId);
        // 先试 App 版详情（X-Argus 签名族，实测有数据）；失败/为空时回退网页版详情（返回完整 vid_list）。
        try {
            SeriesDetail d = appDetail(seriesId);
            if (!d.episodes.isEmpty()) {
                return d;
            }
            AppLog.INSTANCE.d(TAG, "App 详情为空，回退网页版详情");
        } catch (Exception e) {
            AppLog.INSTANCE.e(TAG, "App 详情失败，回退网页版详情", e);
        }
        try {
            SeriesDetail w = webDetail(seriesId);
            if (w.episodes.isEmpty()) {
                // 两路都拿不到剧集：该 series_id 在服务端无有效内容（已下架/无效条目）。
                // 抛明确异常，让详情页提示"可能已下架"，而不是让用户看到黑屏卡死。
                throw new Exception("该内容可能已下架或暂时无法观看");
            }
            return w;
        } catch (Exception e) {
            AppLog.INSTANCE.e(TAG, "detail 全部失败 series_id=" + seriesId, e);
            throw e;
        }
    }

    /** App 版详情：data.video_data.video_list[]（每项 vid + vid_index）。 */
    private SeriesDetail appDetail(String seriesId) throws Exception {
        JSONObject body = new JSONObject();
        body.put("series_id", seriesId);

        JSONObject root = appPost("/novel/player/video_detail/v1/", body.toString());
        JSONObject data = root.optJSONObject("data");
        if (data == null) data = new JSONObject();
        JSONObject vd = data.optJSONObject("video_data");
        if (vd == null || vd.length() == 0) vd = data;

        String title = firstNonEmpty(optText(vd, "series_title"), optText(vd, "series_name"),
                optText(vd, "title"), seriesId);
        String cover = firstNonEmpty(optText(vd, "series_cover"), optText(vd, "cover"));
        String intro = firstNonEmpty(optText(vd, "series_intro"), optText(vd, "video_desc"));

        List<Episode> episodes = new ArrayList<>();
        JSONArray list = vd.optJSONArray("video_list");
        if (list == null) list = data.optJSONArray("video_list");
        if (list != null) {
            for (int i = 0; i < list.length(); i++) {
                JSONObject item = list.optJSONObject(i);
                if (item == null) continue;
                String vid = item.optString("vid");
                int idx = item.optInt("vid_index", 0);
                if (vid.isEmpty() || idx < 1) continue;
                episodes.add(new Episode(vid, idx, "第" + idx + "集"));
            }
        }
        Collections.sort(episodes, new Comparator<Episode>() {
            @Override
            public int compare(Episode a, Episode b) {
                return a.index - b.index;
            }
        });
        AppLog.INSTANCE.d(TAG, "App detail 返回 " + episodes.size() + " 集");
        return new SeriesDetail(seriesId, title, cover, intro, episodes);
    }

    /** 网页版详情：seriesDetail.vid_list 字符串数组，集号按下标 + 1。 */
    private SeriesDetail webDetail(String seriesId) throws Exception {
        String url = WEB_SITE + "/detail?series_id=" + encode(seriesId);
        JSONObject root = fetchRouterData(url);
        JSONObject pageObj = findLoaderPage(root, "detail_page", "detail_");
        if (pageObj == null) {
            throw new Exception("红果详情数据不可用");
        }
        JSONObject detail = pageObj.optJSONObject("seriesDetail");
        if (detail == null || detail.length() == 0) {
            throw new Exception("红果详情为空");
        }

        String title = firstNonEmpty(optText(detail, "series_name"),
                optText(detail, "series_title"), optText(detail, "name"), seriesId);
        String cover = firstNonEmpty(optText(detail, "series_cover"), optText(detail, "cover"));
        String intro = firstNonEmpty(optText(detail, "series_intro"), optText(detail, "video_desc"));

        List<Episode> episodes = new ArrayList<>();
        JSONArray vids = detail.optJSONArray("vid_list");
        if (vids != null) {
            for (int i = 0; i < vids.length(); i++) {
                String vid = vids.optString(i);
                if (vid == null) continue;
                vid = vid.trim();
                if (vid.isEmpty() || "<nil>".equals(vid)) continue;
                int idx = i + 1;
                episodes.add(new Episode(vid, idx, "第" + idx + "集"));
            }
        }
        AppLog.INSTANCE.d(TAG, "web detail 返回 " + episodes.size() + " 集");
        return new SeriesDetail(seriesId, title, cover, intro, episodes);
    }

    /* ==================== 播放（App 版） ==================== */

    /**
     * 取播放模型：{@code POST /novel/player/video_model/v1/}（video_id 单值）。
     *
     * <p><b>2026-10-14 签名链变更（重要）</b>：播放接口只认完整签名链
     * （x-gorgon + x-helios + x-medusa，且 _rticket 必须抖动到 branch==0），
     * 旧的单段 X-Gorgon 会被判 {@code code -1}。签名 query 用「播放器版本档案」
     * （27 字段，见 {@link PlaybackSigner#defaultDeviceProfile}），不是 App 层档案。
     *
     * <p>请求体（<b>只认 video_id 单值</b>；mixed_video_id_map/数组形态一律
     * {@code 100001 invalid param}）：
     * <pre>
     * {
     *   "video_id": "&lt;分集ID&gt;",
     *   "biz_param": { "detail_page_version":0, "device_level":3,
     *                  "disable_digg_stat":false, "need_all_video_definition":true,
     *                  "need_mp4_align":false, "use_os_player":false,
     *                  "use_server_dns":false, "video_platform":1024 }
     * }
     * </pre>
     *
     * <p>三道门槛（2026-10-15 实测）：
     * <ul>
     *   <li>未注册设备回 {@code 200 + 0 字节} 或 {@code 101000 服务异常}——
     *       请求前必须兜注册，且必须带注册下发的 <b>Cookie</b>（只补 Cookie，
     *       不补 x-tt-token：该接口对 token 长度敏感，≥99 字符直接判死）；</li>
     *   <li>网关挡掉时是 200 + 空体：业务码取不到会默认成 0，必须先判空并原地重试；</li>
     *   <li>{@code x-ss-stub} 必须是 body 的 MD5 大写（签名栈已内置）。</li>
     * </ul>
     *
     * <p>响应：{@code data.video_model}（可能是字符串化 JSON）内含
     * {@code fallback_api}——可用播放资源在该地址（见 {@link #fallbackStreamModel}）。
     *
     * @param blockedOut BUG-104：输出参数。true = 本次全部尝试都是「200 空 body」
     *                   且无任何网络级异常（身份被网关判废的强指纹）。
     */
    private JSONObject fetchVideoModel(String vid, AtomicBoolean blockedOut) throws Exception {
        // 取地址前同步兜一次注册：未注册设备一律 200 空 body / 101000。
        // 注册失败不阻塞请求：播放对身份要求松（不在黑名单即可），可继续尝试。
        try {
            DeviceRegister.ensureRegistered(client);
        } catch (Exception e) {
            AppLog.INSTANCE.e(TAG, "注册兜底失败（继续请求）", e);
        }
        // 日志增强（C10）：把身份年龄带进每次取流，便于区分「新身份也不放行」与「老身份被判废」。
        long identityAge = DeviceRegister.identityAgeMs();
        AppLog.INSTANCE.d(TAG, "取流前身份 registered=" + DeviceRegister.registered()
                + (identityAge >= 0L
                ? "，已使用 " + identityAge / (60 * 60 * 1000L) + " 小时"
                : "，年龄未知"));

        JSONObject bizParam = new JSONObject();
        bizParam.put("detail_page_version", 0);
        bizParam.put("device_level", 3);
        bizParam.put("disable_digg_stat", false);
        bizParam.put("need_all_video_definition", true);
        bizParam.put("need_mp4_align", false);
        bizParam.put("use_os_player", false);
        bizParam.put("use_server_dns", false);
        bizParam.put("video_platform", 1024);

        JSONObject body = new JSONObject();
        body.put("video_id", vid);
        body.put("biz_param", bizParam);
        byte[] payload = body.toString().getBytes(UTF8);

        Exception lastError = null;
        // 区分「网关/身份拦截」（全部 200 + 空 body）与「真·没数据」：
        // 前者是用户能自救的网络问题，后者才是内容侧限制，提示文案必须分开。
        // BUG-104：判定结果只写回调用方传入的局部标记，不再落实例字段。
        blockedOut.set(false);
        // BUG-107：整个 App 通道共享一份总耗时预算，超预算即放弃，交给网页兜底。
        long deadline = System.currentTimeMillis() + APP_CHANNEL_BUDGET_MS;
        int emptyBodyCount = 0;
        int dataResponses = 0;
        // BUG-105：非「200 空 body」的网络异常（连接超时 / DNS 失败 / 5xx）单独计数。
        // 这类失败说明身份本身没问题，换身份救不了，只会白烧一次注册。
        int networkErrorCount = 0;
        int attemptCount = Math.min(APP_HOST_ATTEMPTS, HostPool.size());
        int start = HostPool.currentIndex();
        // 主机轮询最多 2 轮（B4）：第 1 轮若「一次非空响应都没有、全是 200 空 body」，
        // 当前身份大概率已被服务端判废（线上实证：旧身份全空 body，换新身份立即恢复）。
        // 此时强制丢弃身份 → 重新注册 → 用新身份再试一轮；注册失败才放弃。
        outer:
        for (int round = 1; round <= 2; round++) {
            for (int i = 0; i < attemptCount; i++) {
                if (System.currentTimeMillis() >= deadline) break outer; // BUG-107
                String host = HostPool.at(start + i);
                // 同一域名内先按 EMPTY_BODY_RETRIES 重试「200 空 body」——
                // 该现象实测是瞬时的（同域名再发一次往往就有数据）。
                for (int r = 0; r <= EMPTY_BODY_RETRIES; r++) {
                    JSONObject json;
                    try {
                        json = playbackPost(host + "/novel/player/video_model/v1/", payload);
                    } catch (Exception e) {
                        lastError = e;
                        networkErrorCount++; // BUG-105：真·网络错误，不是身份指纹
                        break; // 该域名本次不可用，换域名
                    }
                    if (json == null) {
                        emptyBodyCount++;
                        lastError = new Exception("播放接口空响应");
                        continue; // 网关挡掉：原地重试
                    }
                    dataResponses++;
                    JSONObject model = extractVideoModel(json, vid);
                    if (model != null) {
                        HostPool.reportSuccess(host);
                        return model;
                    }
                    lastError = new Exception("红果播放信息为空");
                    break; // 响应正常但没模型：换域名
                }
                HostPool.reportFailure(host);
            }
            if (round == 1) {
                if (dataResponses > 0 || emptyBodyCount == 0 || networkErrorCount > 0) {
                    // 已有有效数据 / 没有任何 200 空 body / 混入了真·网络错误（超时、DNS、5xx）
                    // ——后两者都不是「身份被判废」的强指纹，换身份救不了（BUG-105）
                    break;
                }
                try {
                    DeviceRegister.resetIdentity();
                    DeviceRegister.registerNow(client);
                    AppLog.INSTANCE.e(TAG, "第 1 轮全部 200 空 body → 已换新设备身份，重试一轮"
                            + "（新 device_id=" + DeviceRegister.deviceId() + "）", null);
                    start = HostPool.currentIndex();
                } catch (Exception e) {
                    AppLog.INSTANCE.e(TAG, "换新设备身份失败，不再重试播放接口", e);
                    break;
                }
            }
        }

        // 兜底：旧 multi_video_model/preload 路径（2026-10-14 已下线，保留以防回切）
        // BUG-107：预算已耗尽就不再补这一跳（它同样要签名 + 走网络，救不回来还拖时间）。
        if (System.currentTimeMillis() < deadline) {
            try {
                JSONObject legacyBiz = new JSONObject();
                legacyBiz.put("need_all_video_definition", true);
                legacyBiz.put("video_platform", 1024);
                JSONObject map = new JSONObject();
                map.put("1004", new JSONArray().put(vid));
                JSONObject legacy = new JSONObject();
                legacy.put("biz_param", legacyBiz);
                legacy.put("dr_scene", "preload");
                legacy.put("mixed_video_id_map", map);
                JSONObject root = appPost("/novel/player/multi_video_model/preload/v1/", legacy.toString());
                if (root == null) emptyBodyCount++; else dataResponses++;
                JSONObject model = extractVideoModel(root, vid);
                if (model != null) return model;
            } catch (Exception e) {
                lastError = e;
                networkErrorCount++; // BUG-105：网络级失败，不是「全空 body」指纹
            }
        }

        // 一次非空响应都没拿到、且全是 200 空 body → 判定为设备身份/网络问题
        // （服务端对该 identity 不放行，统一回 200 空 body），交给上层出对应文案。
        // 注意：不再要求 registered()——已注册但被判废的身份同样会全空 body，
        // 若仍按「未注册才报身份问题」判断，这类用户会被误导成「该集未开放」。
        // BUG-105：混入任何网络级异常都不算身份指纹（空 body 只是「没说话」，
        // 超时/DNS/5xx 说明链路本身有问题，换身份解决不了）。
        boolean blocked = dataResponses == 0 && emptyBodyCount > 0 && networkErrorCount == 0;
        blockedOut.set(blocked); // BUG-104：只影响本次取流的文案判定
        if (blocked) {
            AppLog.INSTANCE.e(TAG, "App 播放通道 " + emptyBodyCount
                    + " 次全部返回 200 空 body（已尝试换新身份重试）| "
                    + "device_id=" + DeviceRegister.deviceId(), null);
        }
        AppLog.INSTANCE.e(TAG, "videoModel 取流失败 vid=" + vid, lastError);
        throw lastError != null ? lastError : new Exception("红果播放信息为空");
    }

    /**
     * 播放器签名通道：PlaybackSigner（branch-0 抖动 + helios/medusa）+ 设备注册 Cookie。
     * 返回 null 表示「200 + 空 body」（网关挡掉），由调用方原地重试。
     */
    private JSONObject playbackPost(String url, byte[] payload) throws Exception {
        // 播放接口用合并档案：内置 27 字段 + 注册下发的本机身份（device_id/iid）。
        Map<String, String> profile = PlaybackSigner.defaultDeviceProfile();
        String dev = DeviceRegister.deviceId();
        String iid = DeviceRegister.installId();
        if (dev != null && !dev.trim().isEmpty()) profile.put("device_id", dev.trim());
        if (iid != null && !iid.trim().isEmpty()) profile.put("iid", iid.trim());
        playbackSigner.updateDevice(profile);

        // 签名必须每次重算：ts/_rticket/随机数都在变，缓存即作废。
        PlaybackSigner.Request req = playbackSigner.sign(url, profile, payload);
        Request.Builder rb = new Request.Builder().url(req.url);
        for (Map.Entry<String, String> e : req.headers.entrySet()) {
            rb.header(e.getKey(), e.getValue());
        }
        // 只补 Cookie：该接口对 x-tt-token 长度敏感，多带反而被判死。
        String cookie = DeviceRegister.cookieHeader();
        if (!cookie.isEmpty()) {
            rb.header("Cookie", cookie);
        }
        rb.post(RequestBody.create(JSON_TYPE, payload));
        AppLog.INSTANCE.setLastGorgon(req.headers.get("x-gorgon"));
        AppLog.INSTANCE.d(TAG, "PLAYBACK POST " + req.url);

        try (Response resp = client.newCall(rb.build()).execute()) {
            int code = resp.code();
            byte[] bytes = resp.body() == null ? new byte[0] : resp.body().bytes();
            String text = new String(bytes, UTF8);
            AppLog.INSTANCE.d(TAG, "PLAYBACK -> HTTP " + code + " len=" + bytes.length
                    + " body=" + (text.length() > 400 ? text.substring(0, 400) : text));
            // 日志增强（C10）：服务端 Date 与本地时钟偏差过大时，签名 ts 会被判过期，
            // 表现为 200 空 body / 业务码异常——把偏差记下来，避免再把时钟问题当身份问题查。
            checkClockSkew(resp.header("Date"));
            if (code != 200) {
                throw new Exception("播放接口 HTTP " + code);
            }
            if (text.isEmpty()) {
                // 空 body 时记录网关响应头：线上实证空响应是源站网关主动回 200+0 字节，
                // 记下 via / logid / 边缘 IP 才能判断用户被打到哪个节点、是否所有域名都空。
                AppLog.INSTANCE.d(TAG, "PLAYBACK 空 body 头 | via=" + resp.header("via")
                        + " | logid=" + resp.header("X-Tt-Logid")
                        + " | ip=" + resp.header("x-request-ip")
                        + " | origin=" + resp.header("X-Dsa-Origin-Status")
                        + " | timing=" + resp.header("Server-Timing"));
                return null; // 网关挡掉：200 + 0 字节，必须当失败
            }
            JSONObject json = new JSONObject(text);
            int bizCode = json.optInt("Code", json.optInt("code", 0));
            if (bizCode != 0) {
                throw new Exception("播放接口业务码 " + bizCode);
            }
            return json;
        }
    }

    /**
     * 日志增强（C10）：比较服务端 {@code Date} 响应头与本地时钟。
     * 偏差超过 5 分钟时记一条告警——签名 ts 依赖本地时钟，偏差过大会被判过期，
     * 现象与「身份被判废」高度相似（200 空 body），不记下来极易误判。
     */
    private void checkClockSkew(String dateHeader) {
        if (dateHeader == null || dateHeader.isEmpty()) return;
        try {
            SimpleDateFormat fmt = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US);
            Date server = fmt.parse(dateHeader);
            if (server == null) return;
            long skew = System.currentTimeMillis() - server.getTime();
            if (Math.abs(skew) > 5L * 60 * 1000) {
                AppLog.INSTANCE.e(TAG, "本机时钟与服务端偏差 " + (skew / 1000 / 60)
                        + " 分钟（本地=" + new Date() + "，服务端=" + dateHeader
                        + "）——签名可能被判过期，请校准电视时间", null);
            }
        } catch (Exception ignored) {
            // 头格式不标准就当没有，不影响主流程
        }
    }

    /**
     * 从 multi_video_model 响应里取出 video_model 对象。
     * 响应形如 {@code {"code":0,"data":{"<vid>":{"video_model":"{\"video_list\":[...]}"}}}}；
     * video_model 通常是<b>内嵌 JSON 字符串</b>，也兼容直接是对象的情况。
     *
     * <p><b>必须按 vid 精确匹配</b>：绝不能退化成「取 data 里第一个条目」——
     * 那会在本集取不到时拿到<b>别的集</b>的播放地址，导致用户点第 5 集播出第 1 集。
     * 取不到就老实返回 null，由上层报错。
     */
    private JSONObject extractVideoModel(JSONObject root, String vid) {
        if (root == null) return null;
        JSONObject data = root.optJSONObject("data");
        if (data == null || data.length() == 0) return null;

        Object entry = data.opt(vid);
        if (entry instanceof JSONObject) {
            JSONObject item = (JSONObject) entry;
            JSONObject model = optModelObject(item.opt("video_model"));
            if (model != null) return model;
            // data[vid] 本身就是 video_model（个别形态）
            if (item.optJSONArray("video_list") != null) return item;
            return null;
        }

        // 兼容：响应没有按 vid 分组，data 直接就是 model（或 model 的包装）。
        // 这种情况无法校验归属，仅在最新接口形态下作为兜底。
        JSONObject model = optModelObject(data.opt("video_model"));
        if (model != null) return model;
        if (data.optJSONArray("video_list") != null) return data;

        AppLog.INSTANCE.e(TAG, "videoModel 响应缺少 vid=" + vid + " 条目（data keys 数=" + data.length() + "）", null);
        return null;
    }

    /** video_model 既可能是内嵌 JSON 字符串，也可能是对象；统一转成 JSONObject。 */
    private JSONObject optModelObject(Object value) {
        if (value instanceof JSONObject) return (JSONObject) value;
        if (value instanceof String) {
            String s = ((String) value).trim();
            if (!s.isEmpty()) {
                try {
                    return new JSONObject(s);
                } catch (Exception ignored) {
                }
            }
        }
        return null;
    }

    public VideoModel videoModel(String vid) throws Exception {
        return videoModel(vid, 0);
    }

    /**
     * 取播放信息。
     *
     * @param maxHeight 清晰度上限（像素高）；&lt;=0 表示不限制。低端设备（Android 5.x /
     *                  小内存）由调用方传入 720，把 1080p 变体过滤掉——否则硬解
     *                  1080p HEVC 本就贴着上限，倍速或 seek 时必然掉帧/卡死。
     *                  仅当存在不超过上限的变体时才过滤，保证任何设备都有可播源。
     */
    public VideoModel videoModel(String vid, int maxHeight) throws Exception {
        AppLog.INSTANCE.d(TAG, "videoModel video_id=" + vid);
        // BUG-104：身份拦截判定必须是「本次取流局部」的——NetClient 是全局单例，
        // 起播与预取下一集并发时，实例字段会被另一路请求的结果覆盖。
        AtomicBoolean appBlocked = new AtomicBoolean(false);
        // App 通道：video_model/v1（需注册身份 + Cookie），失败回退网页版公开直链。
        Exception appErr = null;
        try {
            return appVideoModel(vid, maxHeight, appBlocked);
        } catch (Exception e) {
            appErr = e;
            AppLog.INSTANCE.e(TAG, "App 取流失败，回退网页版公开直链 vid=" + vid, e);
        }
        try {
            return webVideoModel(vid);
        } catch (Exception webErr) {
            AppLog.INSTANCE.e(TAG, "网页版公开直链也不可用 vid=" + vid, webErr);
            webErr.addSuppressed(appErr);
            // BUG-101：App 通道已拿到内容侧的权威结论（片源解密失败 / 只有 AV1），
            // 不能再被网页兜底那句猜测性的「该集未开放免费观看」覆盖，否则用户
            // 永远看不到真正可操作的提示。
            if (appErr != null && isAuthoritativeContentError(appErr)) {
                AppLog.INSTANCE.e(TAG, "采用 App 通道的权威错误（非网页兜底猜测）vid=" + vid, webErr);
                throw new Exception(appErr.getMessage(), webErr);
            }
            if (appBlocked.get()) {
                // App 通道被「200 空 body」全拦 → 不是该集的版权限制，
                // 别用「该集未开放免费观看」误导用户，直接给出可自救的提示。
                AppLog.INSTANCE.e(TAG, "判定为本机设备身份问题（非该集版权限制）vid=" + vid, webErr);
                throw new Exception(DEVICE_IDENTITY_FAILED_MSG, webErr);
            }
            throw webErr;
        }
    }

    /**
     * App 通道的「权威内容错误」：接口确实返回了数据，只是本机解不了。
     * BUG-101：这类文案来自 {@link #appVideoModel} 的变体收集阶段，可信度高于
     * 网页兜底推测出的「该集未开放免费观看」。
     */
    private static boolean isAuthoritativeContentError(Exception e) {
        String m = e.getMessage();
        return m != null && (m.contains("红果媒体密钥不可用") || m.contains("该集片源只有 AV1"));
    }

    private VideoModel appVideoModel(String vid, int maxHeight, AtomicBoolean blockedOut) throws Exception {
        JSONObject model = fetchVideoModel(vid, blockedOut);
        if (model == null) throw new Exception("红果播放信息为空");

        // 新路由的可用资源在 fallback_api：普通 GET（播放器 UA，免签名）拿
        // video_info.data.{key_seed, video_list{...}}，再按清晰度挑一档。
        // video_model 自带的 video_list 往往是占位数据，优先走这一跳。
        try {
            VideoModel viaFallback = fallbackStreamModel(vid, model, maxHeight);
            if (viaFallback != null) return viaFallback;
        } catch (Exception e) {
            AppLog.INSTANCE.e(TAG, "fallback_api 取流失败，回退 video_model 直读", e);
        }

        // 收集非 AV1 变体（保留 h264 + bytevc1/HEVC），再按设备解码能力排序选择。
        // 旧逻辑注释写的「只收集 bytevc1」与实现不符：实际只跳过 bytevc2(AV1)。
        List<Variant> variants = new ArrayList<>();
        Exception keyErr = null;
        // 【检验环节1】直读路径同样记录原始档位（fallback 失败回退到这里时能看到全貌）
        StringBuilder rawDesc = new StringBuilder();
        int skippedBvc2 = 0;
        for (JSONObject v : videoListVariants(model)) {
            JSONObject meta = v.optJSONObject("video_meta");
            String codec = meta == null ? "" : meta.optString("codec_type", "").toLowerCase();
            rawDesc.append(' ').append(codec.isEmpty() ? "?" : codec).append('@')
                    .append(definitionHeight(meta));
            if (codec.contains("bytevc2")) {
                skippedBvc2++;
                continue;
            }
            List<String> addrs = extractMediaAddresses(v);
            if (addrs.isEmpty()) continue;

            // 解析 encrypt_info（对齐 Go：spade_a 非空 || encrypt==true || cenc-aes-ctr 即视为加密）
            JSONObject encryption = v.optJSONObject("encrypt_info");
            byte[] cencKey = null;
            boolean encrypted = isEncrypted(encryption);
            if (encrypted) {
                String spade = encryption == null ? "" : encryption.optString("spade_a", "");
                try {
                    // 与 HGTV stream() 一致：spade_a 走 MediaCrypto.deriveKey
                    cencKey = MediaCrypto.deriveKey(spade);
                } catch (Exception e) {
                    keyErr = e;
                    AppLog.INSTANCE.e(TAG, "videoModel 密钥派生失败，跳过该变体", e);
                    continue;
                }
            }

            int height = definitionHeight(meta);
            variants.add(new Variant(codec, height, addrs, cencKey, encrypted));
        }
        // 先按设备解码能力筛/排序（不支持 HEVC 的设备只留 H.264），再套清晰度上限
        sortVariantsForDevice(variants);

        // ===== 低端设备:限制清晰度上限 =====
        // 1080p HEVC 在 Android 5.x / 小内存设备上硬解余量极小,倍速(≥1.5x)或
        // 拖动 seek 时解码吞吐跟不上 → 掉帧卡顿、音频来不及初始化而无声。
        // 降一档到 720p 给解码器留出余量,观感损失远小于卡顿。
        // 只在「确实存在不超上限的变体」时才过滤,避免某些剧只有 1080p 时反而无源可播。
        int cappedHeight = 0;
        if (maxHeight > 0) {
            List<Variant> lower = new ArrayList<>();
            for (Variant v : variants) {
                if (v.height > 0 && v.height <= maxHeight) lower.add(v);
            }
            if (!lower.isEmpty() && lower.size() < variants.size()) {
                cappedHeight = variants.get(0).height;
                variants = lower;
            }
        }

        if (variants.isEmpty()) {
            AppLog.INSTANCE.d(TAG, "【检验环节1】video_model 直读原始下发:" + rawDesc
                    + (skippedBvc2 > 0 ? " | bytevc2 剔除 " + skippedBvc2 + " 档" : "")
                    + " → 全部不可播");
            if (keyErr != null) {
                throw new Exception("红果媒体密钥不可用（片源解密失败），请换一集或稍后再试");
            }
            throw new Exception("该集片源只有 AV1（bytevc2）编码，本机无法解码，请换一集或反馈给开发者");
        }
        AppLog.INSTANCE.d(TAG, "【检验环节1】video_model 直读原始下发:" + rawDesc
                + (skippedBvc2 > 0 ? " | bytevc2 剔除 " + skippedBvc2 + " 档" : ""));

        int duration = parseDoubleInt(model.opt("video_duration"));
        if (duration <= 0) duration = parseDoubleInt(model.opt("duration"));

        Variant selected = variants.get(0);
        AppLog.INSTANCE.d(TAG, "videoModel 取得 " + variants.size() + " 个变体，选中 codec="
                + selected.codec + " height=" + selected.height + " encrypted=" + selected.encrypted
                + " urls=" + selected.urls.size() + " duration=" + duration
                + (cappedHeight > 0 ? " [低端设备限高 " + maxHeight + "p，已从 " + cappedHeight + "p 降档]" : ""));
        return new VideoModel(vid, selected.urls, duration, "https://novel.snssdk.com/",
                selected.cencKey, selected.encrypted, variants, selected.codec, selected.height);
    }

    /**
     * 走 {@code fallback_api} 第二跳取流：新路由 {@code video_model/v1} 的可用资源
     * 就在这个地址（video_model 自带的 video_list 多为占位数据）。
     *
     * <p>对应 HGTV {@code stream()}：普通 GET（播放器 UA，免签名）得到
     * {@code video_info.data.{key_seed, video_list{...}}}；跳过 AV1（bytevc2/bvc2），
     * 清晰度按 {@code quality_desc → height → vheight → quality → key} 依次兜底；
     * {@code key_seed} 非空则用 {@link MediaCrypto#decryptUrl} 解出真实地址，
     * {@code spade_a} 非空则派生 16 字节 CENC 内容密钥。
     */
    private VideoModel fallbackStreamModel(String vid, JSONObject model, int maxHeight) throws Exception {
        String fallback = resolveFallbackApi(model);
        String text = getPlaybackText(fallback);
        JSONObject info = new JSONObject(text).optJSONObject("video_info");
        if (info != null) info = info.optJSONObject("data");
        if (info == null) throw new Exception("没有播放资源");
        JSONObject videoList = info.optJSONObject("video_list");
        if (videoList == null) throw new Exception("没有可用清晰度");

        String seed = info.optString("key_seed", "").trim();
        byte[] seedBytes = null;
        if (!seed.isEmpty()) {
            try {
                seedBytes = MediaCrypto.decode64(seed);
            } catch (Exception e) {
                AppLog.INSTANCE.e(TAG, "key_seed 解码失败，按明文地址处理", e);
            }
        }

        List<Variant> variants = new ArrayList<>();
        // 【检验环节1】服务端原始下发档位清单（含将被剔除的 bytevc2），
        // 真机排查「某集播不了」先看这条：资源到底给了什么、剔完剩什么。
        StringBuilder rawDesc = new StringBuilder();
        int skippedBvc2 = 0;
        for (java.util.Iterator<String> kit = videoList.keys(); kit.hasNext();) {
            String key = kit.next();
            JSONObject v = videoList.optJSONObject(key);
            if (v == null) continue;
            String codec = v.optString("codec_type", "").toLowerCase();
            rawDesc.append(' ').append(key).append('=').append(codec).append('@')
                    .append(playbackQuality(v, key));
            // AV1（bytevc2）电视端普遍不支持，直接跳过
            if ("bytevc2".equals(codec) || "bvc2".equals(codec)) {
                skippedBvc2++;
                continue;
            }
            String main = cleanUrl(v.optString("main_url", ""));
            if (main.isEmpty()) continue;
            if (seedBytes != null) {
                try {
                    main = MediaCrypto.decryptUrl(main, seedBytes);
                } catch (Exception e) {
                    AppLog.INSTANCE.e(TAG, "decryptUrl 失败，跳过 " + key, e);
                    continue;
                }
            }
            if (!main.startsWith("https://") && !main.startsWith("http://")) continue;

            byte[] cencKey = null;
            boolean encrypted = false;
            String spade = v.optString("spade_a", "").trim();
            if (!spade.isEmpty()) {
                try {
                    cencKey = MediaCrypto.deriveKey(spade);
                    encrypted = cencKey != null;
                } catch (Exception e) {
                    AppLog.INSTANCE.e(TAG, "spade_a 密钥派生失败，跳过 " + key, e);
                    continue;
                }
            }
            List<String> urls = new ArrayList<>();
            urls.add(main);
            variants.add(new Variant(codec, playbackQuality(v, key), urls, cencKey, encrypted));
        }
        AppLog.INSTANCE.d(TAG, "【检验环节1】fallback 原始下发 " + videoList.length()
                + " 档:" + rawDesc + (skippedBvc2 > 0
                ? " | bytevc2 剔除 " + skippedBvc2 + " 档" : ""));
        if (variants.isEmpty()) throw new Exception("没有电视可播放的清晰度");

        sortVariantsForDevice(variants);

        // 上限匹配：取 ≤ maxHeight 的最高档；全超限则取最低档当「兼容资源」
        Variant selected = null;
        if (maxHeight > 0) {
            for (Variant v : variants) {
                if (v.height > 0 && v.height <= maxHeight) {
                    selected = v;
                    break;
                }
            }
        } else {
            selected = variants.get(0);
        }
        if (selected == null) {
            selected = variants.get(variants.size() - 1);
        }

        int duration = parseDoubleInt(model.opt("video_duration"));
        if (duration <= 0) duration = parseDoubleInt(model.opt("duration"));
        AppLog.INSTANCE.d(TAG, "fallback 取得 " + variants.size() + " 档，选中 codec="
                + selected.codec + " height=" + selected.height
                + " encrypted=" + selected.encrypted + " duration=" + duration);
        return new VideoModel(vid, selected.urls, duration, "https://novel.snssdk.com/",
                selected.cencKey, selected.encrypted, variants, selected.codec, selected.height);
    }

    /**
     * {@code fallback_api} 的形态不固定：可能是对象（取其 fallback_api 字段）、
     * 数组（取第 0 个）、字符串化 JSON（再解析一层）。最终必须落到一个 https 地址。
     */
    private static String resolveFallbackApi(JSONObject model) throws Exception {
        Object raw = model.opt("fallback_api");
        String value = "";
        if (raw instanceof JSONObject) {
            value = ((JSONObject) raw).optString("fallback_api", "");
        } else if (raw instanceof JSONArray) {
            Object first = ((JSONArray) raw).opt(0);
            if (first instanceof JSONObject) {
                value = ((JSONObject) first).optString("fallback_api", "");
            } else {
                value = ((JSONArray) raw).optString(0, "");
            }
        } else if (raw != null) {
            String s = raw.toString().trim();
            if (s.startsWith("{")) {
                try {
                    value = new JSONObject(s).optString("fallback_api", "");
                } catch (Exception ignore) {
                    value = s;
                }
            } else {
                value = s;
            }
        }
        value = value.trim();
        if (!value.startsWith("https://")) {
            throw new Exception("播放地址格式异常");
        }
        return value;
    }

    /** 清晰度档位：quality_desc → height → vheight → quality → 键名，依次取首个数字。 */
    private static int playbackQuality(JSONObject v, String key) {
        int q = leadingInt(v.optString("quality_desc", ""));
        if (q > 0) return q;
        q = parseDoubleInt(v.opt("height"));
        if (q > 0) return q;
        q = parseDoubleInt(v.opt("vheight"));
        if (q > 0) return q;
        q = parseDoubleInt(v.opt("quality"));
        if (q > 0) return q;
        return leadingInt(key);
    }

    /** 串里取首个连续数字（"1080p" → 1080），没有则返回 0。 */
    private static int leadingInt(String s) {
        if (s == null || s.isEmpty()) return 0;
        Matcher m = Pattern.compile("\\d+").matcher(s);
        return m.find() ? Integer.parseInt(m.group()) : 0;
    }

    /** URL 里的转义序列归一（红果下发的 main_url 常见 \\/ 和 \u0026）。 */
    private static String cleanUrl(String s) {
        return s.replace("\\/", "/")
                .replace("\\u002F", "/")
                .replace("\\u002f", "/")
                .replace("\\u0026", "&")
                .replace("&amp;", "&")
                .trim();
    }

    /**
     * fallback_api 的普通 GET：播放器 UA、免签名。
     * 按 {@code WEB_ATTEMPTS} 次退避重试；非 200 / 空体都当失败重试。
     */
    private String getPlaybackText(String url) throws Exception {
        Exception last = null;
        for (int attempt = 0; attempt < WEB_ATTEMPTS; attempt++) {
            if (attempt > 0) {
                try {
                    Thread.sleep(WEB_RETRY_BACKOFF_MS * attempt);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            try {
                Request req = new Request.Builder().url(url)
                        .header("User-Agent", PlaybackSigner.VIDEO_UA)
                        .header("Accept-Language", "zh-CN,zh;q=0.9")
                        .get()
                        .build();
                try (Response resp = client.newCall(req).execute()) {
                    if (resp.code() != 200) {
                        last = new Exception("播放地址 HTTP " + resp.code());
                        continue;
                    }
                    byte[] bytes = resp.body() == null ? new byte[0] : resp.body().bytes();
                    if (bytes.length == 0) {
                        last = new Exception("播放地址响应为空");
                        continue;
                    }
                    return new String(bytes, UTF8);
                }
            } catch (Exception e) {
                last = e;
            }
        }
        throw last != null ? last : new Exception("播放地址请求失败");
    }

    /**
     * 网页版公开直链兜底（免签名、免 device_id、免解密）。
     *
     * <p><b>2026-10-21 实测（probe 系列）</b>：
     * <ul>
     *   <li>网页版 {@code /video/public/{vid}/360p.mp4} 是一段<b>普通明文 mp4</b>
     *       （无 CENC、无字节跳动签名头），仅对<b>前 accessible_episode_cnt 集</b>开放，
     *       之后的集一律 HTTP 404。抽样 14 部剧边界完全吻合，当前该值恒为 3。</li>
     *   <li>该直链对 UA / Referer <b>不敏感</b>：App UA + novel.snssdk.com、浏览器 UA、
     *       不带 Referer 均 200，所以下载端无需为它改 header。</li>
     *   <li>早期观测窗口里同一个 vid 出现过随机 200/404（概率门），故探测带退避重试。</li>
     * </ul>
     *
     * <p>因此这里只需按 vid 探一次直链：200 → 返回单变体 360p 明文模型；否则报
     * 「该集未开放免费观看」，由 UI 提示用户（而不是给黑屏）。
     */
    private VideoModel webVideoModel(String vid) throws Exception {
        String url = WEB_SITE + "/video/public/" + vid + "/360p.mp4";
        int code = probeHttpCode(url);
        if (code != 200) {
            if (code == 404) {
                throw new Exception("该集未开放免费观看（红果网页版仅前几集免费）");
            }
            throw new Exception("网页版免费源不可用（HTTP " + code + "）");
        }
        List<String> urls = new ArrayList<>();
        urls.add(url);
        List<Variant> variants = new ArrayList<>();
        variants.add(new Variant("h264", 360, urls, null, false));
        AppLog.INSTANCE.d(TAG, "webVideoModel 命中网页版公开直链 vid=" + vid);
        return new VideoModel(vid, urls, 0, WEB_SITE + "/", null, false, variants, "h264", 360);
    }

    /**
     * 探测 URL 的 HTTP 状态码（HEAD，带退避重试）；网络层异常一律返回 -1。
     * 只关心「能不能下」，不读 body。
     *
     * <p>4xx 视为确定性结果立即返回（尤其 404 = 该集未开放，重试无意义）；
     * 只有网络异常与 5xx 才退避重试（早期观测到的概率门 / CDN 抖动）。
     */
    private int probeHttpCode(String url) {
        int lastCode = -1;
        for (int attempt = 0; attempt < WEB_ATTEMPTS; attempt++) {
            if (attempt > 0) {
                try {
                    Thread.sleep(WEB_RETRY_BACKOFF_MS * attempt);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            try {
                Request.Builder rb = new Request.Builder().url(url)
                        .header("User-Agent", WEB_UA)
                        .header("Referer", WEB_SITE + "/")
                        .header("Accept", "*/*")
                        .head();
                try (Response resp = client.newCall(rb.build()).execute()) {
                    lastCode = resp.code();
                    if (lastCode < 500) return lastCode;
                    AppLog.INSTANCE.d(TAG, "probeHttp 第" + (attempt + 1) + "次 HTTP "
                            + lastCode + " url=" + url);
                }
            } catch (Exception e) {
                AppLog.INSTANCE.e(TAG, "probeHttp 探测异常 第" + (attempt + 1) + "次 url=" + url, e);
            }
        }
        return lastCode;
    }

    /* ==================== 弹幕（App 版） ==================== */

    public List<DanmakuItem> danmaku(String seriesId, String videoId, long startMs, long durationMs) throws Exception {
        AppLog.INSTANCE.d(TAG, "danmaku video=" + videoId + " window=" + startMs);

        JSONArray guide = new JSONArray();
        guide.put(1).put(3).put(4).put(2);
        JSONObject bizParam = new JSONObject();
        bizParam.put("book_id", seriesId == null ? "" : seriesId);
        bizParam.put("start_offset_time", startMs);
        bizParam.put("playlet_item_duration", durationMs);
        bizParam.put("need_danmaku_guide_type", guide);

        JSONObject body = new JSONObject();
        body.put("comment_source", 601);
        body.put("server_channel", 1000);
        body.put("group_id", videoId);
        body.put("group_type", 30);
        body.put("comment_type", 20);
        body.put("sort", 1);
        body.put("count", 90);
        body.put("cursor", "");
        body.put("aid", 8662);
        body.put("compliance_status", 0);
        body.put("business_param", bizParam);

        // 弹幕接口必须走评论专用签名（普通 X-Gorgon 会返回 110001）
        JSONObject root = appPost("/novel/commentapi/comment/list/" + videoId + "/v1/", body.toString(), true);
        JSONObject data = root.optJSONObject("data");
        if (data == null) {
            throw new Exception("红果弹幕接口未返回数据");
        }

        // 时间窗口推进值（本窗口右边界）。
        // ⚠️ 服务端已改版：老的 extra.next_query_danmaku_list_time 不再下发（恒为 null），
        // 若仍按旧字段解析会得到 -1L → 每窗口都提前判空返回，
        // 结果就是"弹幕开了跟没开一样"。新字段在 common_list_info.cursor 里，
        // 它本身是一个被字符串化的 JSON，内含 end_offset_time。
        JSONObject cli = data.optJSONObject("common_list_info");
        long nextMs = -1L;
        long totalCount = -1L;
        if (cli != null) {
            String cursorRaw = cli.optString("cursor", "");
            if (!cursorRaw.isEmpty()) {
                try {
                    JSONObject cursor = new JSONObject(cursorRaw);
                    nextMs = parseLongStr(cursor.optString("end_offset_time"));
                    if (cursor.has("danmaku_count")) {
                        totalCount = cursor.optLong("danmaku_count", -1L);
                    }
                } catch (Exception e) {
                    AppLog.INSTANCE.e(TAG, "danmaku cursor 解析失败: " + cursorRaw, e);
                }
            }
        }
        // 兼容旧版字段（服务端灰度期间可能仍下发）
        if (nextMs <= 0) {
            JSONObject extra = data.optJSONObject("extra");
            long legacy = parseLongStr(extra == null ? null : extra.optString("next_query_danmaku_list_time"));
            if (legacy > 0) nextMs = legacy;
        }
        // 最后兜底：服务端两个窗口字段都没给时，按 startMs + 30s 推进。
        // 不能直接返回空——否则整集弹幕永远为 0（本次 bug 的病灶）。
        if (nextMs <= startMs) {
            nextMs = startMs + WINDOW_MS;
            AppLog.INSTANCE.d(TAG, "danmaku 无窗口字段，兜底推进到 " + nextMs);
        }
        // 对齐参考实现：最后一窗的 next 会超过剧集时长，需截断到 duration 而不是丢弃整窗
        if (durationMs > 0) nextMs = Math.min(nextMs, durationMs);

        List<DanmakuItem> items = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        JSONArray list = data.optJSONArray("data_list");
        if (list != null) {
            for (int i = 0; i < list.length(); i++) {
                JSONObject item = list.optJSONObject(i);
                if (item == null) continue;
                JSONObject comment = item.optJSONObject("comment");
                if (comment == null) continue;
                JSONObject common = comment.optJSONObject("common");
                JSONObject content = common == null ? null : common.optJSONObject("content");
                String text = content == null ? "" : content.optString("text", "");
                String groupId = common == null ? "" : common.optString("group_id", "");
                String status = common == null ? "" : common.optString("status", "");
                JSONObject expand = comment.optJSONObject("expand");
                long offsetMs = parseLongStr(expand == null ? null : expand.optString("offset_time"));
                String id = comment.optString("comment_id", "");

                if (!"1".equals(status)) continue;
                if (!videoId.equals(groupId)) continue;
                if (offsetMs < startMs || offsetMs >= nextMs) continue;
                if (text == null || text.isEmpty()) continue;
                if (id == null || id.isEmpty() || id.length() > MAX_COMMENT_ID_LEN) continue;
                if (!seen.add(id)) continue;

                text = cleanDanmakuText(expandDanmakuEmoji(text));
                if (text.isEmpty()) continue;
                items.add(new DanmakuItem(id, text, offsetMs));
            }
        }
        Collections.sort(items, new Comparator<DanmakuItem>() {
            @Override
            public int compare(DanmakuItem a, DanmakuItem b) {
                return a.timeMs < b.timeMs ? -1 : (a.timeMs > b.timeMs ? 1 : 0);
            }
        });
        // totalCount 已在上方解析 cursor 时取出，用于日志诊断"数据是否为空"
        AppLog.INSTANCE.d(TAG, "danmaku 返回 " + items.size() + " 条 (窗口 " + startMs
                + "→" + nextMs + "，整集 danmaku_count=" + totalCount + ")");
        return items;
    }

    /* ==================== 搜索（网页版 + 建议接口） ==================== */

    /**
     * 关键词搜索。
     *
     * 用户反馈：搜索只显示 10 条，多季短剧显示不全。根因是网页版搜索页
     * /search/&lt;kw&gt; 的 searchList 固定只返回第一页 10 条（totalCount 却高达上百），
     * 且没有可用的分页参数。
     *
     * 解决：四路合一
     *   0) <b>官方全库搜索</b> {@code GET /reading/bookapi/search/tab/v/}（签名请求，
     *      红果 App 自己用的搜索）：返回「综合」tab 的搜索结果单元格，每个单元格的
     *      {@code video_data[0].video_detail} 与详情接口同构，含 series_id / 片名 /
     *      集数，且一次就能把同一部剧的各季全列出来。这是唯一能搜到「镜像建议接口
     *      词条缺失的季」的通道（例如「…凶万倍第三季/第四季」）。
     *   1) 网页搜索页 searchList（10 条，含完整 video_data）；
     *   2) 搜索建议接口 /incent_resource/suggestion（最多约 20 条，其中
     *      word_type=="short_play_name" 的记录带完整 video_data，可突破 10 条）；
     *   3) 多季补全——识别「第N季/第N部」系列，对缺失季数再发变体查询补齐。
     * 最后按「与关键词的匹配度 + 同系列季数」排序，保证多季剧按季序排列。
     *
     * <p><b>并行 + 总预算</b>：路由页 CDN 约 50% 的请求挂起 20~30s，
     * 建议接口与官方搜索却稳定（&lt;500ms）。串行时网页页挂起会拖死整次搜索
     * （实测最坏 ~50s）；并行后谁慢谁被放弃，最坏 {@link #SEARCH_LEGS_DEADLINE_MS} 出结果。
     *
     * <p>行为与同步调用完全一致；需要边搜边出结果请用
     * {@link #search(String, SearchSink)}。
     */
    public List<Drama> search(String keyword) throws Exception {
        return search(keyword, null);
    }

    /** 搜索的流式回调：每取得一批新结果就在<b>搜索后台线程</b>上被调用一次。 */
    public interface SearchSink {
        /**
         * @param batch 本批结果：{@code done == false} 时是<b>增量</b>（调用方自行去重追加）
         * @param done  {@code true} 表示搜索已结束，此时 {@code batch} 是<b>排序后的全量</b>
         *              （含多季补全），调用方应整体替换而不是追加
         */
        void onBatch(List<Drama> batch, boolean done);
    }

    /**
     * 关键词搜索（流式版本）。
     *
     * <p>与 {@link #search(String)} 同一条三路并行流水线，区别是把结果<b>分批推出</b>：
     * 官方腿每翻完一页推一批、另两条腿各完成时推一批，全部收尾（含多季补全 + 排序）后
     * 再推一次 {@code done=true} 的全量。
     *
     * <p>意义在于首屏不再受「总预算」压制：既然结果能边拿边显示，官方腿就可以用更长的
     * {@link #SEARCH_OFFICIAL_STREAM_BUDGET_MS} 多翻几页，慢网络下也不会因为 7s 到点而
     * 把已经拿到的翻页成果整腿丢掉。返回值仍是最终全量（与 {@code done=true} 那批相同）。
     */
    public List<Drama> search(String keyword, SearchSink sink) throws Exception {
        AppLog.INSTANCE.d(TAG, "search keyword=" + keyword
                + (sink == null ? "" : " (流式)"));
        List<Drama> merged = new ArrayList<>();
        Map<String, Integer> byId = new LinkedHashMap<>();
        Set<String> idSeen = new HashSet<>();

        // ---- 0) + 1) + 2) 官方搜索 / 建议接口 / 网页搜索页并行（总预算内谁没回来就放弃谁）----
        ExecutorService pool = Executors.newFixedThreadPool(3);
        try {
            long start = System.currentTimeMillis();
            long legsDeadline = start + SEARCH_LEGS_DEADLINE_MS;
            // 流式时首屏不等官方腿，可以给它更长的翻页预算；同步调用维持原 7s
            long officialDeadline = sink == null ? legsDeadline
                    : start + Math.max(SEARCH_OFFICIAL_STREAM_BUDGET_MS, SEARCH_LEGS_DEADLINE_MS);
            // 官方腿是「分页增量产出」：每拿到一条就先写进 live，
            // 万一整腿超时被 cancel，也能把已取到的部分带回来，而不是归零。
            List<Drama> officialLive = Collections.synchronizedList(new ArrayList<Drama>());
            Future<List<Drama>> officialF = pool.submit(
                    () -> fetchOfficialSearchDramas(keyword, officialDeadline, officialLive, sink));
            Future<List<Drama>> sugF = pool.submit(
                    () -> fetchSuggestDramas(keyword, SEARCH_SUGGEST_COUNT));
            Future<List<Drama>> webF = pool.submit(() -> fetchSearchPageDramas(keyword));
            List<Drama> official = awaitOfficial(officialF, officialDeadline, officialLive);
            mergeDramas(merged, byId, idSeen, official);
            List<Drama> suggested = awaitLeg(sugF, legsDeadline, "建议接口");
            mergeDramas(merged, byId, idSeen, suggested);
            emitSearch(sink, suggested, false);
            List<Drama> page = awaitLeg(webF, legsDeadline, "网页搜索页");
            mergeDramas(merged, byId, idSeen, page);
            emitSearch(sink, page, false);
        } finally {
            pool.shutdownNow();
        }

        // ---- 3) 多季补全（对识别出的系列补齐缺失季数）----
        if (!merged.isEmpty()) {
            try {
                completeSeasons(keyword, merged, byId, idSeen);
            } catch (Exception e) {
                AppLog.INSTANCE.e(TAG, "search 多季补全失败（忽略）", e);
            }
        }

        sortSearchResult(merged, keyword);
        AppLog.INSTANCE.d(TAG, "search 返回 " + merged.size() + " 条（去重后）");
        emitSearch(sink, merged, true);
        return merged;
    }

    /**
     * 向 {@code sink} 推一批结果。用 {@code sink} 自身作锁，保证三条腿并发推送时
     * 批次不交错（UI 侧只需按序接收）。
     */
    private void emitSearch(SearchSink sink, List<Drama> batch, boolean done) {
        if (sink == null || (!done && (batch == null || batch.isEmpty()))) return;
        List<Drama> snapshot = batch == null
                ? new ArrayList<Drama>() : new ArrayList<Drama>(batch);
        synchronized (sink) {
            sink.onBatch(snapshot, done);
        }
    }

    /**
     * 等待搜索的一条数据腿；超时或失败都降级为空结果（另一腿照常生效），
     * 保证单路 CDN 挂起不再拖死整次搜索。
     */
    private List<Drama> awaitLeg(Future<List<Drama>> leg, long deadlineMs, String name) {
        try {
            long wait = Math.max(1L, deadlineMs - System.currentTimeMillis());
            return leg.get(wait, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            leg.cancel(true);
            AppLog.INSTANCE.e(TAG, "search " + name + "失败/超时（忽略）", e);
            return new ArrayList<>();
        }
    }

    /**
     * 官方腿专用等待：分页是「增量产出」，与 {@link #awaitLeg} 不同——超时/失败时
     * 保住 {@code live} 里<b>已取到的部分</b>，而不是整条腿归零（QA BUG-202）。
     */
    private List<Drama> awaitOfficial(Future<List<Drama>> leg, long deadlineMs, List<Drama> live) {
        try {
            long wait = Math.max(1L, deadlineMs - System.currentTimeMillis());
            return leg.get(wait, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            leg.cancel(true);
            AppLog.INSTANCE.e(TAG, "search 官方搜索失败/超时（保留已取 " + live.size() + " 条）", e);
            synchronized (live) {
                return new ArrayList<>(live);
            }
        }
    }

    /** 网页版 /search/&lt;kw&gt; 解析（原逻辑，固定 10 条）。 */
    private List<Drama> fetchSearchPageDramas(String keyword) throws Exception {
        String url = WEB_SITE + "/search/" + encode(keyword);
        JSONObject root = fetchRouterData(url, searchClient);
        JSONObject pageObj = findLoaderPage(root, "search_");
        List<Drama> result = new ArrayList<>();
        if (pageObj == null || !pageObj.optBoolean("isSuccess", false)
                || !keyword.equals(optText(pageObj, "query"))) {
            return result;
        }
        JSONArray rows = pageObj.optJSONArray("searchList");
        if (rows != null) {
            for (int i = 0; i < rows.length(); i++) {
                JSONObject row = rows.optJSONObject(i);
                if (row == null) continue;
                Drama d = parseDrama(row.optJSONObject("video_data"), row);
                if (d != null && d.id != null && !d.id.isEmpty()) {
                    result.add(d);
                }
            }
        }
        return result;
    }

    /**
     * 搜索建议接口：{@code GET /incent_resource/suggestion?app_id=8662&query=<kw>&count=N}
     * 返回 {@code suggest_list}；仅取 {@code word_type=="short_play_name"} 且带有效
     * series_id 的记录。{@code video_data} 结构与搜索页一致，直接复用 parseDrama。
     */
    private List<Drama> fetchSuggestDramas(String keyword, int count) throws Exception {
        String url = WEB_SITE + "/incent_resource/suggestion?app_id=" + SUGGEST_APP_ID
                + "&query=" + encode(keyword) + "&count=" + count;
        Request.Builder rb = new Request.Builder().url(url)
                .header("User-Agent", WEB_UA)
                .header("Referer", WEB_SITE + "/")
                .header("Accept", "application/json")
                .header("Accept-Language", "zh-CN,zh;q=0.9");
        // 建议接口是搜索主数据源（网页搜索页固定只有 10 条）。原先只发一次，
        // CDN 偶发失败/返回 200+截断 JSON 就直接掉到「只有 10 条」，
        // 这里把「传输 + 解析」整体重试两轮（与 fetchRouterData 同策略）。
        Exception lastError = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            if (attempt > 0) {
                try {
                    Thread.sleep(WEB_RETRY_BACKOFF_MS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            try (Response resp = searchClient.newCall(rb.build()).execute()) {
                if (!resp.isSuccessful()) {
                    throw new Exception("红果建议接口 HTTP " + resp.code());
                }
                byte[] bytes = resp.body() == null ? new byte[0] : resp.body().bytes();
                String text = new String(bytes, UTF8);
                if (text.isEmpty()) {
                    lastError = new Exception("红果建议接口无响应");
                    continue;
                }
                return parseSuggestDramas(text);
            } catch (Exception e) {
                lastError = e;
                AppLog.INSTANCE.d(TAG, "建议接口失败 第" + (attempt + 1) + "次 err=" + e.getMessage());
            }
        }
        if (lastError != null) throw lastError;
        return new ArrayList<>();
    }

    /** 解析建议接口 JSON：只取 word_type=="short_play_name" 且带有效 series_id 的记录。 */
    private List<Drama> parseSuggestDramas(String text) throws org.json.JSONException {
        List<Drama> result = new ArrayList<>();
        JSONObject root = new JSONObject(text);
        JSONArray list = root.optJSONArray("suggest_list");
        if (list == null) {
            JSONObject data = root.optJSONObject("data");
            list = data == null ? null : data.optJSONArray("suggest_list");
        }
        if (list == null) return result;

        for (int i = 0; i < list.length(); i++) {
            JSONObject item = list.optJSONObject(i);
            if (item == null) continue;
            // 只认 short_play_name：它是唯一携带完整 video_data（含 series_id/封面/集数）
            // 的类型。title / common_query 类只有数字 keyword，并不对应可播放的剧集
            // （参考真果鉴 fetchHongguoSearchNames 同样只取 short_play_name）。
            if (!"short_play_name".equals(optText(item, "word_type"))) continue;
            JSONObject vd = item.optJSONObject("video_data");
            if (vd == null || vd.length() == 0) continue;

            Drama d = parseDrama(vd, item);
            if (d == null || d.id == null || d.id.isEmpty()) continue;
            if (!isNumericId(d.id)) continue;
            result.add(d);
        }
        return result;
    }

    /**
     * <b>官方全库搜索</b>：{@code GET /reading/bookapi/search/tab/v/?query=<kw>&count=N
     * &offset=n}（签名请求，红果 App 自己用的搜索接口）。
     *
     * <p>不传 {@code search_type}：实测 short_play / comic_series / ai_series 与不传
     * 返回<b>完全一致</b>（该参数被服务端忽略），而结果里本来就同时覆盖短剧、漫剧与
     * AI 剧（{@code category_schema} 带 3D / 玄幻 等标签），不传即可全类型命中。
     *
     * <p>返回 {@code search_tabs}（综合/短剧/短视频/…），tab 的 {@code data}
     * 是搜索结果单元格；每个单元格的 {@code video_data} 是<b>数组</b>（本集首集信息），
     * 其 {@code video_detail} 与详情接口同构（series_id / series_title / series_cover /
     * episode_cnt），直接复用 parseDrama。
     *
     * <p>与镜像建议接口的本质差别：这是全库相关度检索，能把同一部剧的各季全部列出
     * （镜像建议接口只有部分季的词条，例如「…凶万倍」只有 S1/S2，S3/S4 连词条都没有）。
     *
     * <p>分页由 tab 级 {@code has_more} + {@code next_offset} 驱动，但服务端在数据取尽后
     * 仍会把 {@code has_more} 保持为 true，故这里设两道闸：<b>本页 0 条新结果即停</b>、
     * <b>下一页剩余预算不足即停</b>（{@link #SEARCH_OFFICIAL_PAGE_MIN_MS}）。结果
     * 边翻页边写入 {@code live}，即使本腿意外超时，{@link #awaitOfficial} 也能保住
     * 已取到的部分而不是整腿归零。
     *
     * <p>{@code sink} 非空时，每翻完一页就把<b>本页新增</b>推一次，实现边翻边出。
     */
    private List<Drama> fetchOfficialSearchDramas(String keyword, long deadlineMs,
                                                  List<Drama> live, SearchSink sink) throws Exception {
        Set<String> seen = new HashSet<>();
        int offset = 0;
        for (int page = 0; page < SEARCH_OFFICIAL_MAX_PAGES; page++) {
            if (System.currentTimeMillis() + SEARCH_OFFICIAL_PAGE_MIN_MS > deadlineMs) {
                AppLog.INSTANCE.d(TAG, "官方搜索预算不足，停止翻页（已取 " + live.size() + " 条）");
                break;
            }
            String path = "/reading/bookapi/search/tab/v/?query=" + encode(keyword)
                    + "&count=" + SEARCH_OFFICIAL_COUNT + "&offset=" + offset;
            JSONObject root = appGet(path);
            JSONArray tabs = root.optJSONArray("search_tabs");
            if (tabs == null) break;

            int added = 0;
            int next = -1;
            List<Drama> pageItems = new ArrayList<>();
            for (int i = 0; i < tabs.length(); i++) {
                JSONObject tab = tabs.optJSONObject(i);
                if (tab == null) continue;
                JSONArray cells = tab.optJSONArray("data");
                if (cells != null) {
                    for (int j = 0; j < cells.length(); j++) {
                        JSONObject cell = cells.optJSONObject(j);
                        if (cell == null) continue;
                        JSONArray vdArr = cell.optJSONArray("video_data");
                        if (vdArr == null || vdArr.length() == 0) continue;
                        JSONObject vd0 = vdArr.optJSONObject(0);
                        if (vd0 == null) continue;
                        Drama d = parseTabCell(vd0, cell);
                        if (d == null || d.id == null || d.id.isEmpty()
                                || !isNumericId(d.id)) continue;
                        // 杂项单元格 video_detail 无片名，parseDrama 会把标题退化成
                        // 纯数字 id——这类不是可播短剧，直接丢弃
                        if (d.title == null || d.title.isEmpty()
                                || d.title.equals(d.id)) continue;
                        if (!seen.add(d.id)) continue;
                        live.add(d);
                        pageItems.add(d);
                        added++;
                    }
                }
                if (next < 0 && tab.optBoolean("has_more", false)) {
                    next = tab.optInt("next_offset", -1);
                }
            }
            emitSearch(sink, pageItems, false);
            if (added == 0) break;
            if (next <= offset) break;
            offset = next;
        }
        AppLog.INSTANCE.d(TAG, "官方搜索返回 " + live.size() + " 条");
        synchronized (live) {
            return new ArrayList<>(live);
        }
    }

    /**
     * 官方搜索单元格 → Drama：优先取 {@code video_data[0].video_detail}（与详情接口
     * 同构），缺失时退回单元格首元素本身（series_id / title / cover / episode_cnt）。
     */
    private Drama parseTabCell(JSONObject vd0, JSONObject cell) {
        if (vd0 == null) return null;
        JSONObject detail = vd0.optJSONObject("video_detail");
        JSONObject src = (detail != null && detail.length() > 0) ? detail : vd0;
        return parseDrama(src, cell);
    }

    /** series_id 必须是纯数字（suggest 里非剧集条目会给出 "1_f8597..." 这类伪 id）。 */
    private static boolean isNumericId(String id) {
        if (id == null || id.isEmpty() || id.length() > 24) return false;
        for (int i = 0; i < id.length(); i++) {
            if (!Character.isDigit(id.charAt(i))) return false;
        }
        return true;
    }

    /** 按 series_id 去重合并，保留字段更全的那条。 */
    private static void mergeDramas(List<Drama> target, Map<String, Integer> byId,
                                    Set<String> idSeen, List<Drama> batch) {
        for (Drama d : batch) {
            if (d == null || d.id == null || d.id.isEmpty()) continue;
            if (!idSeen.add(d.id)) {
                Integer at = byId.get(d.id);
                if (at != null && at >= 0 && at < target.size()) {
                    Drama old = target.get(at);
                    // 用字段更全的新数据补齐（封面/简介/集数）
                    Drama better = richer(old, d);
                    if (better != old) target.set(at, better);
                }
                continue;
            }
            byId.put(d.id, target.size());
            target.add(d);
        }
    }

    /** 选择字段更完整的一条（封面/简介/集数非空者优先）。 */
    private static Drama richer(Drama a, Drama b) {
        int sa = score(a), sb = score(b);
        return sb > sa ? b : a;
    }

    private static int score(Drama d) {
        if (d == null) return -1;
        int s = 0;
        if (d.cover != null && !d.cover.isEmpty()) s += 3;
        if (d.intro != null && !d.intro.isEmpty()) s += 2;
        if (d.episodeCount != null && !d.episodeCount.isEmpty()) s += 1;
        if (d.remark != null && !d.remark.isEmpty()) s += 1;
        return s;
    }

    /* ---- 多季补全 ---- */

    /**
     * 详情页「同系列其他季」联动：给定本剧标题，召回该系列全部季，按季号升序返回
     * （不含本剧）。两步走，<b>准确优先</b>：
     *
     * <ol>
     *   <li>建议接口按裸基础名快速捞一批（&lt;300ms），能中的季直接省掉逐季探测；</li>
     *   <li>官方全库搜索（{@link #fetchOfficialSearchDramas}）逐季并行补全：对每个
     *   候选季号拼「基础名＋第N季/部」查询（只翻第 1 页），向下补齐已见最大季以内的
     *   缺号，向上按批探到整批 0 命中为止。</li>
     * </ol>
     *
     * <p>为什么必须走官方全库搜索：建议接口的索引缺季词条（实测「…凶万倍」只有
     * S1/S2，S3/S4 连词条都没有），漏掉的季只能靠全库检索按全名命中补上。
     * 每季一个请求、6 路并行，墙钟约等于单次请求（1~3s/批）；详情页后台协程里跑、
     * 不挡首屏。IO 线程调用。
     */
    public List<Drama> findSiblingSeasons(String title, String selfId) {
        long t0 = System.currentTimeMillis();
        List<Drama> out = new ArrayList<>();
        String t = title == null ? "" : title.trim();
        if (t.isEmpty()) return out;
        String[] ps = parseSeason(t);
        int cur = Integer.parseInt(ps[1]);
        String base = cur > 0 ? ps[0] : t;              // 无季标记 → 基础名就是片名
        String unit = ps[2];
        String baseKey = normTitle(base);
        if (baseKey.isEmpty()) return out;

        // 季号 → Drama；收集时排除本剧自身、季/部不混
        Map<Integer, Drama> picked = new TreeMap<>();

        // 第 1 步：建议接口白捡一次（快）
        try {
            harvestSeasons(picked, fetchSuggestDramas(base, SEARCH_SUGGEST_COUNT),
                    baseKey, unit, selfId, cur);
        } catch (Exception e) {
            AppLog.INSTANCE.d(TAG, "findSiblingSeasons 建议接口失败 " + e.getMessage());
        }

        int maxKnown = cur;
        for (Integer s : picked.keySet()) maxKnown = Math.max(maxKnown, s);

        ExecutorService pool = Executors.newFixedThreadPool(DETAIL_SEASON_PARALLEL);
        try {
            // 第 2 步 a：向下补齐已见最大季以内的缺号（含第 1 季，通常不带后缀）
            Set<Integer> down = new LinkedHashSet<>();
            for (int s = Math.max(1, maxKnown - DETAIL_SEASON_LOOKBACK); s <= maxKnown; s++) {
                if (s != cur && !picked.containsKey(s)) down.add(s);
            }
            if (!down.isEmpty()) {
                for (Drama d : probeSeasons(pool, base, unit, baseKey, selfId, down)) putBest(picked, d);
            }

            // 第 2 步 b：向上按批探，整批 0 命中即停（防个别季索引缺词导致误判到头）
            int from = Math.max(1, maxKnown + 1);
            for (int b = 0; b < DETAIL_SEASON_MAX_BATCH; b++) {
                Set<Integer> batch = new LinkedHashSet<>();
                for (int i = 0; i < DETAIL_SEASON_BATCH; i++) {
                    int s = from + i;
                    if (s > SEARCH_SEASON_MAX) break;
                    if (s != cur) batch.add(s);
                }
                if (batch.isEmpty()) break;
                List<Drama> hits = probeSeasons(pool, base, unit, baseKey, selfId, batch);
                if (hits.isEmpty()) break;
                for (Drama d : hits) putBest(picked, d);
                from += DETAIL_SEASON_BATCH;
            }
        } finally {
            pool.shutdownNow();
        }

        for (Map.Entry<Integer, Drama> e : picked.entrySet()) out.add(e.getValue());
        AppLog.INSTANCE.d(TAG, "findSiblingSeasons title=" + t + " → " + out.size()
                + " 个兄弟季（" + (System.currentTimeMillis() - t0) + "ms）");
        return out;
    }

    /**
     * 一批季号的并行探测：每个季号一个任务（官方全库搜索，只翻第 1 页），
     * 返回本批所有通过校验的命中（调用方自行按 id 去重合并）。
     */
    private List<Drama> probeSeasons(ExecutorService pool, String base, String unit, String baseKey,
                                     String selfId, Collection<Integer> seasons) {
        List<Future<List<Drama>>> fs = new ArrayList<>();
        for (Integer si : seasons) {
            final int s = si;
            fs.add(pool.submit(() -> probeSeason(base, s, unit, baseKey, selfId)));
        }
        List<Drama> out = new ArrayList<>();
        for (int i = 0; i < fs.size(); i++) {
            List<Drama> hit;
            try {
                hit = fs.get(i).get(DETAIL_SEASON_PROBE_TIMEOUT_S, TimeUnit.SECONDS);
            } catch (Exception e) {
                AppLog.INSTANCE.d(TAG, "probeSeasons 季探测失败 " + e.getMessage());
                continue;
            }
            if (hit == null || hit.isEmpty()) continue;
            out.addAll(hit);
        }
        return out;
    }

    /** 单个季号探测：官方全库搜索按「基础名＋第N季/部」查询；第 1 季先按裸基础名查。 */
    private List<Drama> probeSeason(String base, int season, String unit, String baseKey,
                                    String selfId) {
        List<Drama> hit = new ArrayList<>();
        if (season == 1) {
            // 第 1 季通常不带「第N季」后缀（片名就是裸的基础名）
            List<Drama> raw = new ArrayList<>();
            try {
                fetchOfficialSearchDramas(base, System.currentTimeMillis()
                        + SEARCH_OFFICIAL_PAGE_MIN_MS, raw, null);
            } catch (Exception ignored) {
            }
            collectSeason(raw, season, baseKey, unit, selfId, hit);
            if (!hit.isEmpty()) return hit;
        }
        // 中文数字与阿拉伯数字两种写法各试一次（服务端对两者召回不同）
        String[] labels = new String[]{chineseNum(season), String.valueOf(season)};
        for (String label : labels) {
            String q = base + "第" + label + unit;
            List<Drama> batch = new ArrayList<>();
            try {
                fetchOfficialSearchDramas(q, System.currentTimeMillis()
                        + SEARCH_OFFICIAL_PAGE_MIN_MS, batch, null);
            } catch (Exception ignored) {
                continue;
            }
            collectSeason(batch, season, baseKey, unit, selfId, hit);
            if (!hit.isEmpty()) return hit;
        }
        return hit;
    }

    /** 从一批搜索结果里挑出「正好是目标季号」的剧（防串剧：基础名/季号/单位全对上才算）。 */
    private void collectSeason(List<Drama> batch, int season, String baseKey, String unit,
                               String selfId, List<Drama> hit) {
        for (Drama d : batch) {
            if (d == null || d.id == null || d.id.isEmpty()) continue;
            if (selfId != null && selfId.equals(d.id)) continue;
            String[] q = parseSeason(d.title);
            int s = Integer.parseInt(q[1]);
            if (s == 0) {
                // 裸片名只可能是第 1 季，且必须与基础名完全一致
                if (season == 1 && normTitle(d.title).equals(baseKey)) hit.add(d);
                continue;
            }
            if (s != season) continue;
            if (!normTitle(q[0]).equals(baseKey)) continue;
            if (!unit.isEmpty() && !unit.equals(q[2])) continue;
            hit.add(d);
        }
    }

    /** 把一批候选季并入 picked：同基础名（+ 同单位），排除本剧自身，同季号留字段更全者。 */
    private void harvestSeasons(Map<Integer, Drama> picked, List<Drama> batch, String baseKey,
                                String unit, String selfId, int cur) {
        for (Drama d : batch) {
            if (d == null || d.id == null || d.id.isEmpty()) continue;
            if (selfId != null && selfId.equals(d.id)) continue;   // 本剧自身
            String[] q = parseSeason(d.title);
            if (!normTitle(q[0]).equals(baseKey)) continue;
            int s = Integer.parseInt(q[1]);
            if (s <= 0) {
                // 无后缀：只有片名完全等于基础名才算第 1 季（否则是同前缀的别的剧）
                if (!normTitle(d.title).equals(baseKey)) continue;
                s = 1;
            } else if (!unit.isEmpty() && !unit.equals(q[2])) {
                continue;                                // 季/部不混
            }
            if (s == cur) continue;                      // 本剧自身（无标记时 cur=0，不会命中）
            putBest(picked, d);
        }
    }

    /** 同季号保留字段更完整的一条。 */
    private static void putBest(Map<Integer, Drama> picked, Drama d) {
        if (d == null || d.id == null || d.id.isEmpty()) return;
        Integer season = null;
        String[] q = parseSeason(d.title);
        int s = Integer.parseInt(q[1]);
        if (s == 0) season = 1;
        else if (s > 0) season = s;
        if (season == null) return;
        Drama old = picked.get(season);
        if (old == null || score(d) > score(old)) picked.put(season, d);
    }

    /**
     * 详情页「相关推荐」。网页详情页虽然有 {@code recommendations} 字段，但 SSR
     * 实测恒为空对象、也没有独立的推荐接口可调，所以这里用<b>建议接口</b>按
     * 「基础名 → 全名 → 前 4 字」逐级查询兜底：召回的本身就是标题相近的剧，
     * 语义上等价于「相关」。排除本剧自身与同系列其它季（它们已在「同系列剧集」
     * 里单独展示，重复出现很怪）。最多 {@link #DETAIL_RELATED_MAX} 条，IO 线程调用。
     */
    public List<Drama> findRelated(String title, String excludeId) {
        List<Drama> out = new ArrayList<>();
        String t = title == null ? "" : title.trim();
        if (t.isEmpty()) return out;
        String[] ps = parseSeason(t);
        String base = ps[0];
        String baseKey = normTitle(base);

        Set<String> queries = new LinkedHashSet<>();
        if (!base.isEmpty() && !base.equals(t)) queries.add(base);
        queries.add(t);
        if (t.length() > 4) queries.add(t.substring(0, 4));

        Set<String> seen = new HashSet<>();
        String selfKey = normTitle(t);
        for (String q : queries) {
            if (out.size() >= DETAIL_RELATED_MAX) break;
            try {
                for (Drama d : fetchSuggestDramas(q, 30)) {
                    if (out.size() >= DETAIL_RELATED_MAX) break;
                    if (d.id == null || d.id.isEmpty() || !seen.add(d.id)) continue;
                    if (d.id.equals(excludeId)) continue;                 // 本剧自身
                    if (normTitle(d.title).equals(selfKey)) continue;      // 同名条目
                    // 同系列兄弟季已在「同系列剧集」区块展示，这里跳过
                    if (!baseKey.isEmpty()
                            && normTitle(parseSeason(d.title)[0]).equals(baseKey)) continue;
                    out.add(d);
                }
            } catch (Exception e) {
                AppLog.INSTANCE.d(TAG, "findRelated q=" + q + " 失败 " + e.getMessage());
            }
        }
        AppLog.INSTANCE.d(TAG, "findRelated title=" + t + " → " + out.size() + " 条");
        return out;
    }

    /**
     * 识别结果里的「同系列多季」剧集，对缺失的季数用官方全库搜索逐季并行补齐
     * （复用 {@link #probeSeason}/{@link #probeSeasons} 机制，与详情页同款）。
     *
     * <p>为什么不能用建议接口：它的索引缺季词条（实测「…凶万倍」只有 S1/S2，
     * S3/S4 连词条都没有），只能靠官方全库搜索按「基础名＋第N季/部」全名命中。
     * 向下补齐 {@link #DETAIL_SEASON_LOOKBACK} 以内的缺号，向上按批探到整批
     * 0 命中为止（最多 {@link #DETAIL_SEASON_MAX_BATCH} 批）。IO 线程调用。
     */
    private void completeSeasons(String keyword, List<Drama> merged, Map<String, Integer> byId,
                                 Set<String> idSeen) {
        // 关键词本身已带季/部标记时不做补全
        if (seasonOf(keyword) > 0) return;

        Map<String, List<Drama>> groups = new LinkedHashMap<>();
        for (Drama d : merged) {
            String[] ps = parseSeason(d.title);
            int season = Integer.parseInt(ps[1]);
            String base = ps[0];
            String unit = ps[2];
            if (season <= 0) continue;
            String key = normTitle(base) + "#" + unit;
            if (!key.contains(normTitle(keyword))) continue;
            List<Drama> g = groups.get(key);
            if (g == null) {
                g = new ArrayList<>();
                groups.put(key, g);
            }
            g.add(d);
        }
        if (groups.isEmpty()) return;

        for (Map.Entry<String, List<Drama>> e : groups.entrySet()) {
            String unit = e.getKey().substring(e.getKey().lastIndexOf('#') + 1);
            String base = parseSeason(e.getValue().get(0).title)[0];
            String baseKey = normTitle(base);
            Set<Integer> known = new HashSet<>();
            int max = 0;
            for (Drama d : e.getValue()) {
                int s = seasonOf(d.title);
                if (s > 0) {
                    known.add(s);
                    if (s > max) max = s;
                }
            }
            if (max <= 0) continue;

            ExecutorService pool = Executors.newFixedThreadPool(DETAIL_SEASON_PARALLEL);
            try {
                // 向下补齐已见最大季以内的缺号（含第 1 季，probeSeason 会先按裸基础名查）
                Set<Integer> down = new LinkedHashSet<>();
                for (int s = Math.max(1, max - DETAIL_SEASON_LOOKBACK); s <= max; s++) {
                    if (!known.contains(s)) down.add(s);
                }
                if (!down.isEmpty()) {
                    List<Drama> hits = probeSeasons(pool, base, unit, baseKey, null, down);
                    if (!hits.isEmpty()) mergeDramas(merged, byId, idSeen, hits);
                }

                // 向上按批探，整批 0 命中即停（防个别季索引缺词导致误判到头）
                int from = max + 1;
                for (int b = 0; b < DETAIL_SEASON_MAX_BATCH; b++) {
                    Set<Integer> batch = new LinkedHashSet<>();
                    for (int i = 0; i < DETAIL_SEASON_BATCH; i++) {
                        int s = from + i;
                        if (s > SEARCH_SEASON_MAX) break;
                        batch.add(s);
                    }
                    if (batch.isEmpty()) break;
                    List<Drama> hits = probeSeasons(pool, base, unit, baseKey, null, batch);
                    if (hits.isEmpty()) break;
                    mergeDramas(merged, byId, idSeen, hits);
                    from += DETAIL_SEASON_BATCH;
                }
            } finally {
                pool.shutdownNow();
            }
        }
    }

    /** 取季/部编号（无标记返回 0）。 */
    private static int seasonOf(String title) {
        try {
            return Integer.parseInt(parseSeason(title)[1]);
        } catch (Exception e) {
            return 0;
        }
    }

    /** 解析「第N季/第N部」：返回 [基础名, 季数, 单位]；无标记时季数为 0。 */
    static String[] parseSeason(String title) {
        String t = title == null ? "" : title.trim();
        Matcher m = SEASON_MARK.matcher(t);
        if (!m.find()) return new String[]{t, "0", ""};
        String base = t.substring(0, m.start());
        // 去掉尾部的分隔符/空白
        while (base.length() > 0) {
            char c = base.charAt(base.length() - 1);
            if (Character.isWhitespace(c) || c == '-' || c == '：' || c == ':' || c == '《'
                    || c == '·' || c == '_') {
                base = base.substring(0, base.length() - 1);
            } else {
                break;
            }
        }
        int n = chineseOrArabic(m.group(1));
        String unit = m.group(2);
        if (base.isEmpty() || n < 1 || n > SEARCH_SEASON_MAX) {
            return new String[]{t, "0", ""};
        }
        return new String[]{base, String.valueOf(n), unit};
    }

    /** 归一化标题用于比较：全角转半角、去空白与常见标点、转小写。 */
    private static String normTitle(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 0xFF01 && c <= 0xFF5E) c = (char) (c - 0xFEE0); // 全角→半角
            if (c == 0x3000) c = ' ';
            if (Character.isWhitespace(c) || c == '《' || c == '》' || c == '·' || c == '_'
                    || c == '-' || c == '：' || c == ':' || c == '，' || c == ',' || c == '！'
                    || c == '!' || c == '？' || c == '?' || c == '"' || c == '\'' || c == '“'
                    || c == '”' || c == '‘' || c == '’') {
                continue;
            }
            sb.append(Character.toLowerCase(c));
        }
        return sb.toString();
    }

    /**
     * 中文数字 / 阿拉伯数字 → int；失败返回 0。
     *
     * 两处坑（都有回归用例覆盖）：
     * 1) 不能用 "零〇一二…".indexOf(c) 取位——「〇」与「零」都表示 0，会让「一」落到
     *    索引 2，季数整体偏移一位（曾把「第一季」解析成第 2 季）。
     * 2) 复合数字必须区分「待定数字 digit」与「累计值 number」：遇到十/百时执行
     *    number += max(digit,1) * 单位 并清空 digit。若写成 value = value*10+d，
     *    则「十四」会算成 104、「第十二季」会算成第 102 季。
     */
    private static int chineseOrArabic(String label) {
        if (label == null || label.isEmpty()) return 0;
        try {
            return Integer.parseInt(label);
        } catch (Exception ignored) {
        }
        int digit = 0, number = 0, previous = 1000;
        for (int i = 0; i < label.length(); i++) {
            char c = label.charAt(i);
            int d = digitOf(c);
            if (d >= 0) {
                digit = digit * 10 + d;
                if (digit > SEARCH_SEASON_MAX) return 0;
                continue;
            }
            int unit;
            if (c == '百') unit = 100;
            else if (c == '十') unit = 10;
            else return 0;
            if (unit >= previous || digit > 9) return 0;
            number += Math.max(digit, 1) * unit;
            if (number > SEARCH_SEASON_MAX) return 0;
            digit = 0;
            previous = unit;
        }
        number += digit;
        return number > SEARCH_SEASON_MAX ? 0 : number;
    }

    /** 单个中文数字字符 → 数值（含全角数字）；非数字返回 -1。 */
    private static int digitOf(char c) {
        switch (c) {
            case '零': case '〇': return 0;
            case '一': return 1;
            case '二': case '两': case '兩': return 2;
            case '三': return 3;
            case '四': return 4;
            case '五': return 5;
            case '六': return 6;
            case '七': return 7;
            case '八': return 8;
            case '九': return 9;
            default: break;
        }
        if (c >= '０' && c <= '９') return c - '０';
        return -1;
    }

    /** 1~200 的阿拉伯数字 → 中文数字（用于变体查询）。 */
    private static String chineseNum(int n) {
        if (n < 1 || n > SEARCH_SEASON_MAX) return String.valueOf(n);
        final char[] d = "零一二三四五六七八九".toCharArray();
        if (n < 10) return String.valueOf(d[n]);
        if (n < 20) return "十" + (n % 10 == 0 ? "" : String.valueOf(d[n % 10]));
        if (n < 100) {
            return String.valueOf(d[n / 10]) + "十" + (n % 10 == 0 ? "" : String.valueOf(d[n % 10]));
        }
        StringBuilder sb = new StringBuilder();
        sb.append(d[n / 100]).append('百');
        int rest = n % 100;
        if (rest == 0) return sb.toString();
        if (rest < 10) sb.append('零');
        else if (rest < 20) sb.append('一');
        sb.append(chineseNum(rest));
        return sb.toString();
    }

    /**
     * 结果排序：先按与关键词的匹配度（完全相等 &gt; 前缀 &gt; 包含 &gt; 同系列 &gt; 其它），
     * 再按（系列基础名 → 单位 → 季数 → series_id）的词典序排列，
     * 保证多季剧聚簇且按季序展示。
     *
     * 注意：比较器必须是严格全序（词典序键保证这一点）。不能写成「同系列按季数比、
     * 跨系列按 id 比」——两种键可能冲突破坏传递性（例：A系列s1(id=9) &lt; A系列s2(id=5)，
     * 但 s2(id=5) &lt; 其它(id=7) &lt; s1(id=9)），元素多于 32 个时 TimSort 可能抛
     * IllegalArgumentException（被 catch 后静默放弃排序）。
     */
    private static void sortSearchResult(List<Drama> list, String keyword) {
        final String q = normTitle(keyword);
        try {
            Collections.sort(list, new Comparator<Drama>() {
                @Override
                public int compare(Drama a, Drama b) {
                    int ra = matchRank(a.title, q), rb = matchRank(b.title, q);
                    if (ra != rb) return ra < rb ? -1 : 1;

                    String[] pa = parseSeason(a.title), pb = parseSeason(b.title);
                    int baseCmp = normTitle(pa[0]).compareTo(normTitle(pb[0]));
                    if (baseCmp != 0) return baseCmp;
                    int unitCmp = pa[2].compareTo(pb[2]);
                    if (unitCmp != 0) return unitCmp;
                    int sa = Integer.parseInt(pa[1]), sb = Integer.parseInt(pb[1]);
                    if (sa != sb) return sa < sb ? -1 : 1;
                    String ia = a.id == null ? "" : a.id;
                    String ib = b.id == null ? "" : b.id;
                    return ia.compareTo(ib);
                }
            });
        } catch (Exception ignored) {
            // 排序失败保持原序
        }
    }

    private static int matchRank(String title, String q) {
        String t = normTitle(title);
        if (q.isEmpty()) return 4;
        if (t.equals(q)) return 0;
        if (t.startsWith(q)) return 1;
        if (t.contains(q)) return 2;
        String[] ps = parseSeason(title);
        if (ps != null && !normTitle(ps[0]).isEmpty() && normTitle(ps[0]).contains(q)) return 3;
        return 4;
    }


    /* ==================== App 版请求执行 ==================== */

    private JSONObject appPost(String path, String jsonBody) throws Exception {
        return appPost(path, jsonBody, false);
    }

    /** @param comment 评论（弹幕）接口使用专用评论签名 */
    private JSONObject appPost(String path, String jsonBody, boolean comment) throws Exception {
        // 内容接口的门槛是 X-Argus（已由 SignedRequest 处理），身份不是必需；
        // 但取流 video_model/v1 必须用注册过的 device_id+install_id，所以仍在这里兜一次注册。
        try {
            DeviceRegister.ensureRegistered(client);
        } catch (Exception e) {
            AppLog.INSTANCE.e(TAG, "注册兜底失败（继续请求）", e);
        }
        byte[] body = jsonBody.getBytes(UTF8);
        int attemptCount = Math.min(APP_HOST_ATTEMPTS, HostPool.size());
        int start = HostPool.currentIndex();
        Exception lastError = null;
        for (int i = 0; i < attemptCount; i++) {
            String host = HostPool.at(start + i);

            // 同一域名内先按 EMPTY_BODY_RETRIES 重试「200 空 body」——
            // 该现象实测是**瞬时的**（同域名再发一次往往就有数据），
            // 直接换域名会白白浪费一个候选位。
            for (int r = 0; r <= EMPTY_BODY_RETRIES; r++) {
                SignedRequest req = new SignedRequest(path, body, comment, host);
                String sig = req.headers.get("X-Argus");
                AppLog.INSTANCE.setLastGorgon(sig != null ? sig : req.headers.get("X-Gorgon"));
                AppLog.INSTANCE.d(TAG, "POST " + req.url);

                Request.Builder rb = new Request.Builder().url(req.url);
                for (Map.Entry<String, String> e : req.headers.entrySet()) {
                    rb.header(e.getKey(), e.getValue());
                }
                rb.post(RequestBody.create(JSON_TYPE, body));

                try (Response resp = client.newCall(rb.build()).execute()) {
                    int code = resp.code();
                    byte[] bytes = resp.body() == null ? new byte[0] : resp.body().bytes();
                    String text = new String(bytes, UTF8);
                    AppLog.INSTANCE.d(TAG, "POST " + path + " -> HTTP " + code
                            + " host=" + req.baseUrl
                            + " body=" + (text.length() > 400 ? text.substring(0, 400) : text));
                    if (code != 200) {
                        lastError = new Exception("红果 App 接口 HTTP " + code);
                        break; // 该域名 HTTP 层不可用，换域名
                    }
                    if (text.isEmpty()) {
                        // 200 但空体：先在本域名重试，仍空才放弃该域名
                        lastError = new Exception("红果 App 接口未返回有效数据");
                        if (r < EMPTY_BODY_RETRIES) continue;
                        break;
                    }
                    JSONObject json = new JSONObject(text);
                    int bizCode = json.optInt("code", 0);
                    if (bizCode != 0) {
                        // 业务码非 0（限流/后端异常）：换域名再试，视为该域名不可用
                        lastError = new Exception("红果 App 接口业务码 " + bizCode);
                        break;
                    }
                    HostPool.reportSuccess(host);
                    return json;
                } catch (Exception e) {
                    AppLog.INSTANCE.e(TAG, "POST " + path + " 失败 host=" + host, e);
                    lastError = e;
                    break; // 连接/解析异常：换域名
                }
            }
            // 走到这里说明该域名本次不可用：推进游标，避免下个请求又从它开始重撞
            HostPool.reportFailure(host);
        }
        AppLog.INSTANCE.e(TAG, "POST " + path + " 全部 " + attemptCount + " 个域名均失败", lastError);
        throw lastError != null ? lastError : new Exception("红果 App 接口不可用");
    }

    /**
     * App 版 GET（参数在 query 上，无 body；签名与 POST 同源）。域名池/重试策略与
     * {@link #appPost(String, String, boolean)} 完全一致。
     *
     * @param pathAndQuery 已带 query 的路径，如 {@code /reading/bookapi/search/tab/v/?query=xx}
     */
    private JSONObject appGet(String pathAndQuery) throws Exception {
        try {
            DeviceRegister.ensureRegistered(client);
        } catch (Exception e) {
            AppLog.INSTANCE.e(TAG, "注册兜底失败（继续请求）", e);
        }
        int attemptCount = Math.min(APP_HOST_ATTEMPTS, HostPool.size());
        int start = HostPool.currentIndex();
        Exception lastError = null;
        for (int i = 0; i < attemptCount; i++) {
            String host = HostPool.at(start + i);
            for (int r = 0; r <= EMPTY_BODY_RETRIES; r++) {
                SignedRequest req = new SignedRequest(pathAndQuery, new byte[0], false, host);
                String sig = req.headers.get("X-Argus");
                AppLog.INSTANCE.setLastGorgon(sig != null ? sig : req.headers.get("X-Gorgon"));
                AppLog.INSTANCE.d(TAG, "GET " + req.url);

                Request.Builder rb = new Request.Builder().url(req.url);
                for (Map.Entry<String, String> e : req.headers.entrySet()) {
                    rb.header(e.getKey(), e.getValue());
                }

                try (Response resp = client.newCall(rb.build()).execute()) {
                    int code = resp.code();
                    byte[] bytes = resp.body() == null ? new byte[0] : resp.body().bytes();
                    String text = new String(bytes, UTF8);
                    AppLog.INSTANCE.d(TAG, "GET " + pathAndQuery + " -> HTTP " + code
                            + " host=" + req.baseUrl
                            + " body=" + (text.length() > 400 ? text.substring(0, 400) : text));
                    if (code != 200) {
                        lastError = new Exception("红果 App 接口 HTTP " + code);
                        break; // 该域名 HTTP 层不可用，换域名
                    }
                    if (text.isEmpty()) {
                        lastError = new Exception("红果 App 接口未返回有效数据");
                        if (r < EMPTY_BODY_RETRIES) continue;
                        break;
                    }
                    JSONObject json = new JSONObject(text);
                    int bizCode = json.optInt("code", 0);
                    if (bizCode != 0) {
                        lastError = new Exception("红果 App 接口业务码 " + bizCode);
                        break;
                    }
                    HostPool.reportSuccess(host);
                    return json;
                } catch (Exception e) {
                    AppLog.INSTANCE.e(TAG, "GET " + pathAndQuery + " 失败 host=" + host, e);
                    lastError = e;
                    break; // 连接/解析异常：换域名
                }
            }
            HostPool.reportFailure(host);
        }
        AppLog.INSTANCE.e(TAG, "GET " + pathAndQuery + " 全部 " + attemptCount + " 个域名均失败", lastError);
        throw lastError != null ? lastError : new Exception("红果 App 接口不可用");
    }

    /* ==================== 网页版请求执行（搜索） ==================== */

    /**
     * 网页版页面 → {@code _ROUTER_DATA}。除 {@link #getText(String)} 的传输层重试外，
     * 这里再做一次「解析级」重试：CDN 偶发返回被截断/换壳的 HTML（日志里的
     * 「红果页面结构异常」），重新抓一次通常就好了。
     */
    private JSONObject fetchRouterData(String url) throws Exception {
        return fetchRouterData(url, webClient);
    }

    /** 同上，可指定客户端（搜索页用短超时的 {@link #searchClient}）。 */
    private JSONObject fetchRouterData(String url, OkHttpClient http) throws Exception {
        Exception lastError = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            if (attempt > 0) {
                try {
                    Thread.sleep(WEB_RETRY_BACKOFF_MS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            try {
                String html = getText(url, http);
                if (html == null || html.isEmpty()) {
                    throw new Exception("红果接口无响应");
                }
                String json = extractRouterData(html);
                if (json == null) {
                    throw new Exception("红果页面结构异常");
                }
                return new JSONObject(json);
            } catch (Exception e) {
                lastError = e;
                AppLog.INSTANCE.d(TAG, "页面解析失败 第" + (attempt + 1) + "次 url=" + url
                        + " err=" + e.getMessage());
            }
        }
        throw lastError != null ? lastError : new Exception("红果页面不可用");
    }

    /**
     * 网页版 GET（榜单 / 搜索 / 网页详情共用出口）。
     *
     * <p><b>必须带重试</b>：hongguoduanju.com 底层是同一个字节 CDN，实测单次成功率
     * 只有约六成（偶发连接超时、5xx）。此前只有一次机会，失败直接把异常抛给 UI——
     * 这正是用户反馈「榜单要刷新好几次才刷得出来」的真因（日志里 hot-drama page=1
     * 被连续请求 5 次，间隔 2~3 秒，是用户手动按了 5 次，而非程序重试）。
     *
     * <p>重试按 {@code WEB_RETRY_BACKOFF_MS × n} 退避，3 次可把成功率推到 95% 以上；
     * 全部失败仍抛最后一次异常，行为对调用方不变。
     */
    private String getText(String url) throws Exception {
        return getText(url, webClient);
    }

    /** 同上，可指定客户端（搜索用 {@link #searchClient}，单次封顶 5s）。 */
    private String getText(String url, OkHttpClient http) throws Exception {
        Exception lastError = null;
        for (int attempt = 0; attempt < WEB_ATTEMPTS; attempt++) {
            if (attempt > 0) {
                try {
                    Thread.sleep(WEB_RETRY_BACKOFF_MS * attempt);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            try {
                Request.Builder rb = new Request.Builder().url(url)
                        .header("User-Agent", WEB_UA)
                        .header("Referer", WEB_SITE + "/")
                        .header("Accept-Language", "zh-CN,zh;q=0.9");
                try (Response resp = http.newCall(rb.build()).execute()) {
                    if (!resp.isSuccessful()) {
                        lastError = new Exception("红果接口 HTTP " + resp.code());
                        AppLog.INSTANCE.d(TAG, "GET 失败 第" + (attempt + 1) + "次 HTTP "
                                + resp.code() + " url=" + url);
                        continue;
                    }
                    byte[] bytes = resp.body() == null ? new byte[0] : resp.body().bytes();
                    String text = new String(bytes, UTF8);
                    if (text.isEmpty()) {
                        lastError = new Exception("红果接口无响应");
                        AppLog.INSTANCE.d(TAG, "GET 失败 第" + (attempt + 1) + "次 空响应 url=" + url);
                        continue;
                    }
                    return text;
                }
            } catch (Exception e) {
                lastError = e;
                AppLog.INSTANCE.e(TAG, "GET 失败 第" + (attempt + 1) + "次 url=" + url, e);
            }
        }
        throw lastError != null ? lastError : new Exception("红果接口不可用");
    }

    /* ==================== 解析辅助 ==================== */

    private Drama parseDrama(JSONObject vd, JSONObject m) {
        if (vd == null || vd.length() == 0) vd = m;
        if (vd == null) return null;

        String id = firstNonEmpty(
                optText(vd, "series_id_str"), optText(vd, "series_id"), optText(vd, "keyword"),
                optText(m, "series_id_str"), optText(m, "series_id"), optText(m, "keyword"));
        if (id.isEmpty()) return null;

        String title = firstNonEmpty(
                optText(vd, "series_title"), optText(vd, "series_name"), optText(vd, "title"),
                optText(m, "series_name"), optText(m, "name"), id);
        String cover = firstNonEmpty(optText(vd, "series_cover"), optText(vd, "cover"),
                optText(m, "series_cover"));
        String intro = firstNonEmpty(optText(vd, "series_intro"), optText(vd, "video_desc"),
                optText(m, "series_intro"));
        String count = firstNonEmpty(optText(vd, "episode_cnt"), optText(m, "episode_cnt"));
        String remark = firstNonEmpty(optText(vd, "episode_right_text"),
                optText(m, "episode_right_text"));
        // 数据源偶发集数字段为 0（文案「全0集」），与详情页真实集数冲突（QA Bug-02）。
        // 0 集无意义，宁可留空也不展示错误数据；remark 失效但有有效集数时用「共N集」重建。
        if (isZeroCount(count)) count = "";
        if (isZeroCountRemark(remark)) remark = "";
        if (remark.isEmpty() && !count.isEmpty()) {
            remark = "共" + count + "集";
        }

        List<String> tags = new ArrayList<>();
        JSONArray tagArr = vd.optJSONArray("tags");
        if (tagArr != null) {
            for (int i = 0; i < tagArr.length(); i++) {
                addTag(tags, tagArr.optString(i));
            }
        }
        JSONArray catList = vd.optJSONArray("category_list");
        if (catList != null) {
            for (int i = 0; i < catList.length(); i++) {
                addTag(tags, optText(catList.optJSONObject(i), "name"));
            }
        }
        String schema = optText(vd, "category_schema");
        if (!schema.isEmpty()) {
            try {
                JSONArray arr = new JSONArray(schema);
                for (int i = 0; i < arr.length(); i++) {
                    addTag(tags, optText(arr.optJSONObject(i), "name"));
                }
            } catch (Exception ignored) {
            }
        }
        addTag(tags, firstNonEmpty(optText(vd, "category_name"),
                optText(vd, "categoryName"), optText(vd, "category")));

        return new Drama(id, title, cover, intro, count, remark, tags);
    }

    private static void addTag(List<String> tags, String tag) {
        if (tag == null) return;
        tag = tag.trim();
        if (tag.isEmpty()) return;
        for (String t : tags) {
            if (t.equals(tag)) return;
        }
        tags.add(tag);
    }

    /** 集数字段是否为无效的 0（数据源偶发返回「0」，见 QA Bug-02）。 */
    private static boolean isZeroCount(String count) {
        if (count == null || count.isEmpty()) return false;
        try {
            return Integer.parseInt(count.trim()) <= 0;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /** 集数文案是否为「全0集 / 共0集 / 更新至0集」这类无效值。 */
    private static boolean isZeroCountRemark(String remark) {
        if (remark == null || remark.isEmpty()) return false;
        return ZERO_COUNT_REMARK.matcher(remark).find();
    }

    /** video_model.video_list 可能是对象（按清晰度 key）或数组，统一转成变体列表。 */
    private static List<JSONObject> videoListVariants(JSONObject model) {
        List<JSONObject> variants = new ArrayList<>();
        Object vl = model == null ? null : model.opt("video_list");
        if (vl instanceof JSONArray) {
            JSONArray arr = (JSONArray) vl;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o != null) variants.add(o);
            }
        } else if (vl instanceof JSONObject) {
            JSONObject map = (JSONObject) vl;
            JSONArray names = map.names();
            if (names != null) {
                for (int i = 0; i < names.length(); i++) {
                    JSONObject o = map.optJSONObject(names.optString(i));
                    if (o != null) variants.add(o);
                }
            }
        }
        return variants;
    }

    /** 取清晰度高度：优先 definition 内数字，其次 vheight。 */
    private static int definitionHeight(JSONObject meta) {
        if (meta == null) return 0;
        String def = optText(meta, "definition");
        Matcher mm = Pattern.compile("\\d+").matcher(def);
        if (mm.find()) {
            try {
                int h = Integer.parseInt(mm.group());
                if (h > 0) return h;
            } catch (NumberFormatException ignored) {
            }
        }
        return meta.optInt("vheight", 0);
    }

    /** 判断变体是否 CENC 加密：对齐 Go 的 spade_a 非空 || encrypt==true || cenc-aes-ctr。 */
    private static boolean isEncrypted(JSONObject encryption) {
        if (encryption == null || encryption.length() == 0) return false;
        String spade = encryption.optString("spade_a", "");
        boolean encrypt = encryption.optBoolean("encrypt", false);
        String method = encryption.optString("encryption_method", "");
        return !spade.isEmpty() || encrypt || "cenc-aes-ctr".equals(method);
    }

    /** 从变体对象按顺序扫描多地址字段，扁平化 + 去重 + Base64 兜底。 */
    private static List<String> extractMediaAddresses(JSONObject info) {
        Set<String> seen = new LinkedHashSet<>();
        String[] keys = {"main_url", "backup_url", "backup_url_1",
                "backup_url_2", "backup_urls", "url_list"};
        for (String key : keys) {
            collectAddresses(info == null ? null : info.opt(key), seen);
        }
        return new ArrayList<>(seen);
    }

    private static void collectAddresses(Object value, Set<String> out) {
        if (value == null || value == JSONObject.NULL) return;
        if (value instanceof JSONArray) {
            JSONArray arr = (JSONArray) value;
            for (int i = 0; i < arr.length(); i++) {
                collectAddresses(arr.opt(i), out);
            }
            return;
        }
        String s = valueToString(value).trim();
        if (s.isEmpty() || s.length() > 8192) return;
        if (!isHttpMediaUrl(s)) {
            String decoded = decodeBase64(s).trim();
            if (decoded.isEmpty()) return;
            s = decoded;
        }
        if (isHttpMediaUrl(s)) out.add(s);
    }

    private static boolean isHttpMediaUrl(String s) {
        if (!s.startsWith("http://") && !s.startsWith("https://")) return false;
        try {
            URL u = new URL(s);
            String host = u.getHost();
            if (host == null || host.isEmpty()) return false;
            String userInfo = u.getUserInfo();
            return userInfo == null || userInfo.isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    private static String decodeBase64(String s) {
        try {
            byte[] b = Base64.decode(s, Base64.NO_WRAP);
            if (b != null && b.length > 0) return new String(b, UTF8);
        } catch (Exception ignored) {
        }
        try {
            String padded = s.trim();
            int rem = padded.length() % 4;
            if (rem != 0) {
                StringBuilder sb = new StringBuilder(padded);
                for (int i = 0; i < 4 - rem; i++) sb.append('=');
                padded = sb.toString();
            }
            byte[] b = Base64.decode(padded, Base64.NO_WRAP);
            if (b != null && b.length > 0) return new String(b, UTF8);
        } catch (Exception ignored) {
        }
        return "";
    }

    /** 从 HTML 中提取 _ROUTER_DATA 的 JSON 子串 */
    private static String extractRouterData(String html) {
        Matcher m = ROUTER_DATA.matcher(html);
        if (!m.find()) return null;
        int start = m.end();
        int brace = html.indexOf('{', start);
        if (brace < 0) return null;
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = brace; i < html.length(); i++) {
            char c = html.charAt(i);
            if (inString) {
                if (escaped) escaped = false;
                else if (c == '\\') escaped = true;
                else if (c == '"') inString = false;
            } else {
                if (c == '"') inString = true;
                else if (c == '{') depth++;
                else if (c == '}') {
                    depth--;
                    if (depth == 0) return html.substring(brace, i + 1);
                }
            }
        }
        return null;
    }

    /** 定位 loaderData 下第一个非空 page 对象。name 末尾的 $ 是字面量 key，前缀匹配时去掉 $。 */
    private static JSONObject findLoaderPage(JSONObject root, String... names) {
        JSONObject loader = root == null ? null : root.optJSONObject("loaderData");
        if (loader == null) return null;
        for (String n : names) {
            JSONObject page = loader.optJSONObject(n);
            if (page != null && page.length() > 0) return page;
        }
        JSONArray keys = loader.names();
        if (keys != null) {
            for (int i = 0; i < keys.length(); i++) {
                String key = keys.optString(i);
                if (key == null) continue;
                for (String n : names) {
                    String prefix = n.endsWith("$") ? n.substring(0, n.length() - 1) : n;
                    if (!prefix.isEmpty() && key.startsWith(prefix)) {
                        JSONObject page = loader.optJSONObject(key);
                        if (page != null && page.length() > 0) return page;
                    }
                }
            }
        }
        return null;
    }

    /* ==================== 弹幕解析辅助 ==================== */

    private static final int MAX_COMMENT_ID_LEN = 120;

    /** 弹幕时间窗口长度，需与 PlayerActivity.DANMAKU_WINDOW_MS 保持一致。 */
    private static final long WINDOW_MS = 30000L;

    private static final Pattern DANMAKU_EMOJI_PATTERN = Pattern.compile("\\[[^\\[\\]\\s]{1,10}\\]");

    private static final Map<String, String> DANMAKU_EMOJI = danmakuEmojiMap();

    private static Map<String, String> danmakuEmojiMap() {
        Map<String, String> m = new HashMap<>();
        m.put("[笑哭]", "😂");   m.put("[大笑]", "😆");   m.put("[微笑]", "🙂");
        m.put("[呲牙]", "😁");   m.put("[偷笑]", "🤭");   m.put("[捂脸]", "🤦");
        m.put("[害羞]", "😊");   m.put("[调皮]", "😜");   m.put("[爱慕]", "😍");
        m.put("[色]", "😍");     m.put("[酷]", "😎");     m.put("[白眼]", "🙄");
        m.put("[抠鼻]", "🤨");   m.put("[鄙视]", "😒");   m.put("[思考]", "🤔");
        m.put("[什么]", "🤔");   m.put("[惊讶]", "😲");   m.put("[尴尬]", "😅");
        m.put("[汗]", "😅");     m.put("[闭嘴]", "🤐");   m.put("[嘘]", "🤫");
        m.put("[困]", "😴");     m.put("[睡]", "😴");     m.put("[哈欠]", "🥱");
        m.put("[流泪]", "😢");   m.put("[大哭]", "😭");   m.put("[委屈]", "🥺");
        m.put("[可怜]", "🥺");   m.put("[没看够]", "🥺"); m.put("[抓狂]", "😫");
        m.put("[发怒]", "😡");   m.put("[吐]", "🤮");     m.put("[打脸]", "🤦");
        m.put("[灵光一闪]", "💡"); m.put("[赞]", "👍");  m.put("[强]", "👍");
        m.put("[给力]", "👍");   m.put("[弱]", "👎");     m.put("[鼓掌]", "👏");
        m.put("[ok]", "👌");     m.put("[胜利]", "✌️");  m.put("[耶]", "✌️");
        m.put("[奋斗]", "💪");   m.put("[加油]", "💪");   m.put("[威武]", "💪");
        m.put("[抱抱]", "🤗");   m.put("[比心]", "🫰");   m.put("[心]", "❤️");
        m.put("[爱心]", "❤️");   m.put("[送花]", "🌹");   m.put("[玫瑰]", "🌹");
        m.put("[鲜花]", "💐");   m.put("[钱]", "💰");     m.put("[狗头]", "🐶");
        m.put("[doge]", "🐶");
        return m;
    }

    private static String expandDanmakuEmoji(String text) {
        if (text == null || text.indexOf('[') < 0) return text;
        Matcher mm = DANMAKU_EMOJI_PATTERN.matcher(text);
        StringBuffer sb = new StringBuffer();
        while (mm.find()) {
            String key = mm.group();
            String rep = DANMAKU_EMOJI.get(key);
            mm.appendReplacement(sb, Matcher.quoteReplacement(rep != null ? rep : key));
        }
        mm.appendTail(sb);
        return sb.toString();
    }

    private static String cleanDanmakuText(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x20 || c == 0x7f) sb.append(' ');
            else sb.append(c);
        }
        String t = sb.toString().trim();
        if (t.length() > 100) t = t.substring(0, 100) + "…";
        return t;
    }

    private static long parseLongStr(String s) {
        if (s == null) return -1L;
        s = s.trim();
        if (s.isEmpty()) return -1L;
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return -1L;
        }
    }

    /* ==================== 工具 ==================== */

    private static String optText(JSONObject o, String key) {
        if (o == null) return "";
        return valueToString(o.opt(key));
    }

    private static String valueToString(Object v) {
        if (v == null || v == JSONObject.NULL) return "";
        if (v instanceof String) return (String) v;
        // 整型（尤其 series_id 这类 19 位长整型）必须直接 toString，严禁经 double：
        // 超过 2^53 的 long 转 double 会丢失末几位精度（实例：7683457453203147801
        // → 7683457453203147776）。此前建议接口返回的数字型 series_id 全被改坏，
        // 与网页搜索页的字符串型 series_id_str 去重时互不相等 → 同一部剧出现
        // 两张卡片，坏 ID 那张点进去必然 404（用户看到的「重复的第十/九季，
        // 一个连不上一个能看」即此因）。
        if (v instanceof Long || v instanceof Integer) return String.valueOf(v);
        if (v instanceof Number) {
            double d = ((Number) v).doubleValue();
            if (d == Math.floor(d) && !Double.isInfinite(d)) {
                return String.valueOf((long) d);
            }
            return String.valueOf(d);
        }
        return String.valueOf(v);
    }

    private static int parseDoubleInt(Object v) {
        try {
            return (int) Double.parseDouble(valueToString(v).trim());
        } catch (Exception e) {
            return 0;
        }
    }

    private static String firstNonEmpty(String... values) {
        for (String v : values) {
            if (v != null && !v.isEmpty()) return v;
        }
        return "";
    }

    private static String encode(String s) {
        try {
            return java.net.URLEncoder.encode(s, "UTF-8")
                    .replace("+", "%20")
                    .replace("*", "%2A");
        } catch (Exception e) {
            return s;
        }
    }
}
