package com.houg.tv.net;

import com.houg.tv.util.AppLog;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * 观果（api.drama.9ddm.com）内容源：免签名、免 Cookie、免设备注册。
 *
 * <p>HG App 取流接口（multi_video_model）与网页播放页已双双下线（2026-10），
 * 观果成为当前唯一可稳定出流的源，故作为默认内容源接入：
 * <ul>
 *   <li>目录：{@code POST /drama/home/search}，按题材筛选、page 分页（每页 30）；</li>
 *   <li>详情：{@code GET /drama/play/detail?oneId=...&amp;playOrder=1}，
 *       用 {@code episodeCount} 合成 1..N 集；</li>
 *   <li>取流：同一接口带 {@code playOrder=<集号>}，返回
 *       {@code videoClarityList[{name:"1080P",url}]} 明文 mp4 直链
 *       （CDN 对 UA/Referer 不敏感，HEAD 免鉴权）。</li>
 * </ul>
 *
 * <p>ID 约定（与HG ID 区分，便于 NetClient 分流）：
 * 剧 {@code gg:<oneId>}，集 {@code gg:<oneId>:<order>}。
 */
public final class GuanguoSource {

    private static final String TAG = "Guanguo";

    /** 剧/集 ID 前缀。 */
    static final String PREFIX = "gg:";

    private static final String BASE = "https://api.drama.9ddm.com";

    private static final Charset UTF8 = Charset.forName("UTF-8");

    private static final MediaType JSON_TYPE = MediaType.parse("application/json; charset=utf-8");

    /** 服务端每页条数上限（传更大也封顶 30）。 */
    private static final int PAGE_SIZE = 30;

    /** 目录最大页码（防御脏数据无限翻页）。 */
    private static final int MAX_PAGE = 60;

    /** 需要客户端过滤的栏目（漫剧/AI剧）单次最多向后扫几页，避免整页被过滤后空屏。 */
    private static final int MAX_SCAN_PAGES = 4;

    private static final int HTTP_ATTEMPTS = 2;

    private static final long RETRY_BACKOFF_MS = 250L;

    private final OkHttpClient client;

    GuanguoSource(OkHttpClient client) {
        this.client = client;
    }

    /** 是否观果 ID。 */
    static boolean isGuanguo(String id) {
        return id != null && id.startsWith(PREFIX);
    }

    /* ==================== 目录 ==================== */

    /**
     * 栏目分页。{@code offset} 语义与HG一致：{@code offset / 30 + 1} 为页码。
     *
     * <p>栏目映射（实测）：真人剧 → {@code subject=""}；漫剧/AI剧 → {@code subject="动漫"}，
     * 再用条目的 {@code aigc} 标记区分（0=漫剧，1=AI剧）。观果的动漫目录里 AI 剧稀疏
     * （约 8/60），所以过滤型栏目会向后多扫几页，避免首屏空白。
     */
    NetClient.CatalogPage catalog(String genreKey, int offset) throws Exception {
        String subject = subjectFor(genreKey);
        boolean filtered = !"short_play".equals(genreKey);
        int startPage = offset / PAGE_SIZE + 1;
        if (startPage < 1) startPage = 1;

        List<NetClient.Drama> dramas = new ArrayList<NetClient.Drama>();
        Set<String> seen = new HashSet<String>();
        int lastPage = startPage - 1;
        int scan = filtered ? MAX_SCAN_PAGES : 1;
        boolean hasMore = false;

        for (int i = 0; i < scan; i++) {
            int page = startPage + i;
            if (page > MAX_PAGE) break;
            JSONArray rows = fetchCatalogPage(subject, page);
            lastPage = page;
            int rawCount = rows == null ? 0 : rows.length();
            hasMore = rawCount >= PAGE_SIZE && page < MAX_PAGE;
            for (int j = 0; j < rawCount; j++) {
                JSONObject row = rows.optJSONObject(j);
                if (row == null || !accept(genreKey, row)) continue;
                NetClient.Drama d = parseDrama(row);
                if (d == null || !seen.add(d.id)) continue;
                dramas.add(d);
            }
            if (!hasMore) break;
            // 真人剧一页就够；过滤型栏目拿到一批就停，避免首屏等太久。
            if (dramas.size() >= 10) break;
        }

        AppLog.INSTANCE.d(TAG, "catalog genre=" + genreKey + " page=" + startPage + "~" + lastPage
                + " 返回 " + dramas.size() + " 条 hasMore=" + hasMore);
        return new NetClient.CatalogPage(dramas, hasMore, lastPage * PAGE_SIZE, "");
    }

    private static String subjectFor(String genreKey) {
        if ("comic_series".equals(genreKey) || "ai_series".equals(genreKey)) return "动漫";
        return "";
    }

    /** 栏目过滤：AI剧只留 aigc=1；漫剧只留 aigc=0；真人剧排除动漫/AI 条目。 */
    private static boolean accept(String genreKey, JSONObject row) {
        int aigc = optInt(row.opt("aigc"));
        if ("ai_series".equals(genreKey)) return aigc == 1;
        if ("comic_series".equals(genreKey)) return aigc == 0;
        return aigc == 0 && optInt(row.opt("contentCategory")) != 1;
    }

    private JSONArray fetchCatalogPage(String subject, int page) throws Exception {
        JSONObject body = new JSONObject();
        body.put("audience", "全部");
        body.put("order", "最新");
        body.put("page", page);
        body.put("pageSize", PAGE_SIZE);
        body.put("searchWord", "");
        body.put("subject", subject);
        JSONObject root = post("/drama/home/search", body);
        Object data = root.opt("data");
        if (data instanceof JSONArray) return (JSONArray) data;
        return null;
    }

    private NetClient.Drama parseDrama(JSONObject row) {
        String oneId = firstNonEmpty(text(row, "oneId"), text(row, "id"));
        if (oneId.isEmpty()) return null;
        String title = text(row, "title");
        if (title.isEmpty()) return null;
        String cover = firstNonEmpty(text(row, "vertPoster"), text(row, "horzPoster"));
        String intro = text(row, "description");
        int count = optInt(row.opt("episodeCount"));

        List<String> tags = new ArrayList<String>();
        JSONArray subjects = row.optJSONArray("subjectTags");
        if (subjects != null) {
            for (int i = 0; i < subjects.length() && tags.size() < 3; i++) {
                String t = subjects.optString(i, "").trim();
                if (!t.isEmpty()) tags.add(t);
            }
        }

        String countText = count > 0 ? String.valueOf(count) : "";
        String remark = count > 0 ? "共" + count + "集" : "";
        return new NetClient.Drama(PREFIX + oneId, title, cover, intro, countText, remark, tags);
    }

    /* ==================== 详情 ==================== */

    NetClient.SeriesDetail detail(String seriesId) throws Exception {
        String oneId = seriesId.substring(PREFIX.length());
        JSONObject data = playDetail(oneId, 1);
        int count = optInt(data.opt("episodeCount"));
        if (count <= 0) {
            throw new Exception("观果未返回剧集信息");
        }
        if (count > 200) count = 200;

        String title = firstNonEmpty(text(data, "title"), oneId);
        String cover = text(data, "vertPoster");
        String intro = text(data, "description");

        List<NetClient.Episode> episodes = new ArrayList<NetClient.Episode>(count);
        for (int i = 1; i <= count; i++) {
            episodes.add(new NetClient.Episode(PREFIX + oneId + ":" + i, i, "第" + i + "集"));
        }
        AppLog.INSTANCE.d(TAG, "detail oneId=" + oneId + " title=" + title + " 共 " + episodes.size() + " 集");
        return new NetClient.SeriesDetail(seriesId, title, cover, intro, episodes);
    }

    /* ==================== 取流 ==================== */

    /**
     * 取某集播放地址：{@code playOrder} 越界时服务端会回落成第 1 集，调用前已钳制。
     * 观果给的是明文 mp4（无 CENC），走 downloader 直下。
     */
    NetClient.VideoModel videoModel(String vid, int maxHeight) throws Exception {
        String[] parts = parseVid(vid);
        String oneId = parts[0];
        int order = Integer.parseInt(parts[1]);
        JSONObject data = playDetail(oneId, order);

        JSONArray clarity = data.optJSONArray("videoClarityList");
        List<NetClient.Variant> variants = new ArrayList<NetClient.Variant>();
        if (clarity != null) {
            for (int i = 0; i < clarity.length(); i++) {
                JSONObject c = clarity.optJSONObject(i);
                if (c == null) continue;
                String url = text(c, "url");
                if (!url.startsWith("http")) continue;
                int height = parseHeight(text(c, "name"));
                if (height <= 0) continue;
                variants.add(new NetClient.Variant("h264", height,
                        Collections.singletonList(url), null, false));
            }
        }
        if (variants.isEmpty()) {
            throw new Exception("观果未返回可用清晰度");
        }
        Collections.sort(variants, new Comparator<NetClient.Variant>() {
            @Override
            public int compare(NetClient.Variant a, NetClient.Variant b) {
                return b.height - a.height;
            }
        });

        // 低端设备限高：只要存在不超过上限的档位就降档，避免 1080p 硬解余量不足。
        if (maxHeight > 0) {
            List<NetClient.Variant> lower = new ArrayList<NetClient.Variant>();
            for (NetClient.Variant v : variants) {
                if (v.height <= maxHeight) lower.add(v);
            }
            if (!lower.isEmpty() && lower.size() < variants.size()) {
                AppLog.INSTANCE.d(TAG, "限高 " + maxHeight + "p：从 " + variants.get(0).height
                        + "p 降档，保留 " + lower.size() + " 档");
                variants = lower;
            }
        }

        NetClient.Variant selected = variants.get(0);
        AppLog.INSTANCE.d(TAG, "videoModel vid=" + vid + " 档位=" + variants.size()
                + " 选中=" + selected.height + "P url=" + abbreviate(selected.urls.get(0)));
        return new NetClient.VideoModel(vid, selected.urls, 0, BASE + "/",
                null, false, variants, "h264", selected.height);
    }

    /** {@code gg:<oneId>:<order>} → [oneId, order]。 */
    private static String[] parseVid(String vid) throws Exception {
        if (!isGuanguo(vid)) throw new Exception("非观果视频 ID");
        String rest = vid.substring(PREFIX.length());
        int sep = rest.lastIndexOf(':');
        if (sep <= 0 || sep >= rest.length() - 1) throw new Exception("观果视频 ID 异常");
        String oneId = rest.substring(0, sep);
        String orderText = rest.substring(sep + 1);
        int order;
        try {
            order = Integer.parseInt(orderText);
        } catch (NumberFormatException e) {
            throw new Exception("观果集号异常");
        }
        if (order < 1) order = 1;
        return new String[]{oneId, String.valueOf(order)};
    }

    /** 清晰度名转高度：1080P / 720P / 480P / 4K。 */
    private static int parseHeight(String name) {
        if (name == null) return 0;
        if (name.toUpperCase().contains("4K")) return 2160;
        StringBuilder digits = new StringBuilder();
        for (int i = 0; i < name.length(); i++) {
            char ch = name.charAt(i);
            if (ch >= '0' && ch <= '9') {
                digits.append(ch);
            } else if (digits.length() > 0) {
                break;
            }
        }
        if (digits.length() == 0) return 0;
        try {
            return Integer.parseInt(digits.toString());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /* ==================== 搜索 ==================== */

    /**
     * 观果没有搜索接口（{@code searchWord} 字段被服务端忽略，多个 search 端点均 500），
     * 只能扫目录页按标题/别名包含匹配。命中即提前返回，避免 TV 上等待过久。
     */
    List<NetClient.Drama> search(String keyword) throws Exception {
        String kw = keyword == null ? "" : keyword.trim();
        if (kw.isEmpty()) return new ArrayList<NetClient.Drama>();

        List<NetClient.Drama> out = new ArrayList<NetClient.Drama>();
        Set<String> seen = new HashSet<String>();
        String[] subjects = {"", "动漫"};
        for (String subject : subjects) {
            for (int page = 1; page <= 3; page++) {
                JSONArray rows = fetchCatalogPage(subject, page);
                if (rows == null || rows.length() == 0) break;
                for (int i = 0; i < rows.length(); i++) {
                    JSONObject row = rows.optJSONObject(i);
                    if (row == null) continue;
                    if (!matches(row, kw)) continue;
                    NetClient.Drama d = parseDrama(row);
                    if (d != null && seen.add(d.id)) out.add(d);
                    if (out.size() >= 30) break;
                }
                if (out.size() >= 30) break;
            }
            if (out.size() >= 30) break;
        }
        AppLog.INSTANCE.d(TAG, "search kw=" + kw + " 命中 " + out.size() + " 条");
        return out;
    }

    private static boolean matches(JSONObject row, String kw) {
        if (text(row, "title").contains(kw)) return true;
        JSONArray aliases = row.optJSONArray("aliases");
        if (aliases != null) {
            for (int i = 0; i < aliases.length(); i++) {
                if (aliases.optString(i, "").contains(kw)) return true;
            }
        }
        return false;
    }

    /* ==================== 榜单 ==================== */

    /**
     * 榜单：观果 {@code order} 参数只认「最新」，没有独立热度榜，因此按栏目题材取
     * 最新目录，名次即列表顺序。点进去可直接播放，比HG「能看榜不能播」可用。
     */
    NetClient.RankingPage ranking(NetClient.RankBoard board, int page) throws Exception {
        String genreKey;
        String boardId = board == null ? "" : board.boardId;
        if ("hot-comic-drama".equals(boardId)) {
            genreKey = "comic_series";
        } else if ("hot-ai-drama".equals(boardId)) {
            genreKey = "ai_series";
        } else {
            genreKey = "short_play";
        }
        NetClient.CatalogPage cp = catalog(genreKey, (page - 1) * PAGE_SIZE);

        List<NetClient.RankItem> items = new ArrayList<NetClient.RankItem>();
        int base = (page - 1) * PAGE_SIZE;
        for (int i = 0; i < cp.dramas.size(); i++) {
            NetClient.Drama d = cp.dramas.get(i);
            items.add(new NetClient.RankItem(base + i + 1, d, "", "", "", "",
                    Collections.<String>emptyList()));
        }
        if (items.isEmpty()) throw new Exception("观果榜单暂无数据");
        AppLog.INSTANCE.d(TAG, "ranking board=" + boardId + " page=" + page + " 返回 " + items.size() + " 条");
        return new NetClient.RankingPage(boardId, page, Math.max(page, page + (cp.hasMore ? 1 : 0)),
                cp.hasMore, "基于观果最新上线排序", items);
    }

    /* ==================== 请求 ==================== */

    private JSONObject playDetail(String oneId, int order) throws Exception {
        String url = BASE + "/drama/play/detail?" + query("oneId", oneId,
                "page", "1", "pageSize", "100", "userId", "0",
                "queryAll", "true", "playOrder", String.valueOf(order));
        return get(url);
    }

    private JSONObject post(String path, JSONObject body) throws Exception {
        String url = BASE + path + "?" + query();
        Request request = new Request.Builder().url(url)
                .header("User-Agent", "okhttp/5.1.0")
                .header("Referer", BASE + "/")
                .header("Accept", "application/json")
                .header("Accept-Language", "zh-CN,zh;q=0.9")
                .post(RequestBody.create(JSON_TYPE, body.toString().getBytes(UTF8)))
                .build();
        return execute(request);
    }

    private JSONObject get(String url) throws Exception {
        Request request = new Request.Builder().url(url)
                .header("User-Agent", "okhttp/5.1.0")
                .header("Referer", BASE + "/")
                .header("Accept", "application/json")
                .header("Accept-Language", "zh-CN,zh;q=0.9")
                .build();
        return execute(request);
    }

    private JSONObject execute(Request request) throws Exception {
        Exception last = null;
        for (int attempt = 0; attempt < HTTP_ATTEMPTS; attempt++) {
            if (attempt > 0) {
                try {
                    Thread.sleep(RETRY_BACKOFF_MS * attempt);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            try (Response resp = client.newCall(request).execute()) {
                if (!resp.isSuccessful()) {
                    last = new Exception("观果接口 HTTP " + resp.code());
                    continue;
                }
                byte[] bytes = resp.body() == null ? new byte[0] : resp.body().bytes();
                if (bytes.length == 0) {
                    last = new Exception("观果接口空响应");
                    continue;
                }
                JSONObject root = new JSONObject(new String(bytes, UTF8));
                int code = root.optInt("code", -1);
                if (code != 200) {
                    throw new Exception("观果接口 code=" + code + " msg=" + root.optString("msg"));
                }
                return root;
            } catch (Exception e) {
                last = e;
            }
        }
        throw last != null ? last : new Exception("观果接口不可用");
    }

    /**
     * 观果公共 query：12 个固定字段，其中 {@code clientInfo} 为
     * 毫秒时间戳后 10 位的 MD5（服务端用于风控，不校验内容）。
     * {@code extra} 为 key/value 交替的附加参数。
     */
    private static String query(String... extra) {
        StringBuilder sb = new StringBuilder();
        appendParam(sb, "version_code", "1500");
        appendParam(sb, "version_name", "1.5.0");
        appendParam(sb, "device_name", "Pixel 8 Pro");
        appendParam(sb, "device_type", "phone");
        appendParam(sb, "is_first_day", "true");
        appendParam(sb, "is_first_24h", "true");
        appendParam(sb, "app_launch_way", "icon");
        appendParam(sb, "default_homepage", "homepage_interaction");
        appendParam(sb, "device_owning_firm", "Google");
        appendParam(sb, "font_scale", "default");
        appendParam(sb, "os_type", "1");
        String millis = String.valueOf(System.currentTimeMillis());
        String tail = millis.length() > 10 ? millis.substring(millis.length() - 10) : millis;
        appendParam(sb, "clientInfo", md5(tail));
        for (int i = 0; i + 1 < extra.length; i += 2) {
            appendParam(sb, extra[i], extra[i + 1]);
        }
        return sb.toString();
    }

    private static void appendParam(StringBuilder sb, String key, String value) {
        if (sb.length() > 0) sb.append('&');
        sb.append(encode(key)).append('=').append(encode(value));
    }

    private static String encode(String s) {
        try {
            return URLEncoder.encode(s == null ? "" : s, "UTF-8")
                    .replace("+", "%20")
                    .replace("*", "%2A");
        } catch (Exception e) {
            return s;
        }
    }

    private static String md5(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(s.getBytes(UTF8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                String h = Integer.toHexString(b & 0xFF);
                if (h.length() == 1) hex.append('0');
                hex.append(h);
            }
            return hex.toString();
        } catch (Exception e) {
            return s;
        }
    }

    /* ==================== 解析辅助 ==================== */

    private static String text(JSONObject obj, String key) {
        if (obj == null) return "";
        Object v = obj.opt(key);
        if (v == null || v == JSONObject.NULL) return "";
        return String.valueOf(v).trim();
    }

    private static String firstNonEmpty(String... values) {
        for (String v : values) {
            if (v != null && !v.isEmpty()) return v;
        }
        return "";
    }

    /** 兼容数字/字符串两种形态（观果部分字段类型不稳定）。 */
    private static int optInt(Object value) {
        if (value instanceof Number) return ((Number) value).intValue();
        if (value instanceof String) {
            try {
                return Integer.parseInt(((String) value).trim());
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        return 0;
    }

    private static String abbreviate(String url) {
        if (url == null) return "";
        int q = url.indexOf('?');
        String head = q > 0 ? url.substring(0, q) : url;
        return head.length() > 80 ? head.substring(0, 80) + "..." : head;
    }
}
