package com.houg.tv.sign;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.os.Build;

import com.houg.tv.util.AppLog;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.List;
import java.util.TimeZone;
import java.util.UUID;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * 设备注册。
 *
 * HG内容接口按设备放行：搜索必须用服务端注册过的 device_id + install_id 对，
 * 播放要求 device_id 不在黑名单。注册本身匿名，无需账号，一次请求完成。
 * 本请求【不带任何签名头】（x-gorgon/x-khronos/x-ladon/x-argus/helios/medusa 都不发，
 * query 里也不带 ts/_rticket），发了反而被判异常客户端。
 *
 * 协议事实（抓包确认，功能性事实不受版权保护，本实现为独立编写）：
 *  1) POST https://log5-applog-lf.fqn\u006fvel.com/service/2/device_register/
 *       ?tt_data=a&cronet_version=8d40f833_2026-03-03
 *       &ttnet_version=4.2.243.31-douyin&use_store_region_cookie=1
 *     （is.snssdk.com 等价）
 *  2) 头：Content-Type: application/octet-stream;tt-data=a / log-encode-type: gzip /
 *     x-ss-dp: 8662 / x-ss-req-ticket: 毫秒 / x-vc-bdturing-sdk-version: 4.0.3.cn /
 *     lc: 101 / sdk-version: 2 / passport-sdk-version: 5051452 /
 *     x-neptune: -8|50:51:59:30:40:47:49:39 / x-ss-stub: body 的 MD5 大写 /
 *     User-Agent: com.phoenix.read/73932 (...)
 *  3) body = ttEncrypt(gzip(JSON))，原始二进制（发 base64 文本必回 device_id=0）：
 *     MAGIC(74 63 05 10 00 00) || rnd[32] ||
 *     AES-128-CBC(key = SHA512(SHA512(rnd) || KDF_SALT)[0:16],
 *                 iv  = SHA512(SHA512(rnd) || KDF_SALT)[16:32],
 *                 明文 = PKCS7(SHA512(gz) || gz))
 *     KDF_SALT = hex "4dd4c2e6...dd58"（见常量）
 *  4) JSON：{"magic_tag":"ss_app_log","header":{...50 字段...},"_gen_time":毫秒}
 *     不带 install_id/device_id（由服务端下发）；
 *     os_version 必须是字符串（数字必失败）。
 *  5) 响应：device_id/install_id（数字或 *_str 字符串，优先取 *_str）+ device_token
 *     + Set-Cookie（store-region/install_id/ttreq），后续请求要带上 Cookie。
 *     判成功：两个 id 都存在且非 0；回 device_id=0 即被拒。
 *  6) 响应可能 gzip/brotli 压缩——不要手动设 accept-encoding，
 *     OkHttp 自己加的头才会透明解压；此处再兜一手 gzip 魔数嗅探。
 */
public final class DeviceRegister {

    /** 注册接口路径 + query（各主机一致）。 */
    private static final String REGISTER_PATH =
            "/service/2/device_register/"
                    + "?tt_data=a&cronet_version=8d40f833_2026-03-03"
                    + "&ttnet_version=4.2.243.31-douyin&use_store_region_cookie=1";

    /**
     * 注册主机（按优先级逐个回退）。
     *
     * <p>单主机硬绑定的代价实测过：海信 VIDAA 电视上 {@code log5-applog-lf.fqn\u006fvel.com}
     * 解析不到（同一次日志里 {@code h\u006fnggu\u006f.kdns.fr} 也报 Unable to resolve host），
     * 注册永远失败 → 播放接口只回 200 空 body → 只能落到网页版前 3 集免费直链。
     * 三个主机都是等价的匿名注册入口（前两个为抓包确认，第三个见类注释 1）。
     */
    private static final String[] REGISTER_HOSTS = {
            "https://log5-applog-lf.fqn\u006fvel.com",
            "https://log.snssdk.com",
            "https://is.snssdk.com",
    };

    private static final String REGISTER_UA =
            "com.phoenix.read/73932 (Linux; U; Android 16; zh_CN; 25053RT47C; "
                    + "Build/BP2A.250605.031.A3)";

    /** ttEncrypt 魔数（6 字节）。 */
    private static final byte[] MAGIC = {0x74, 0x63, 0x05, 0x10, 0x00, 0x00};

    /** 密钥派生盐（64 字节，hex 摘自抓包请求的功能性常量）。 */
    private static final byte[] KDF_SALT = hexDecode(
            "4dd4c2e6b83162090e52b3c7a6733ba41cb2462b829ab58a196b39db57177524"
                    + "f49baf7f08e8d68d26a72e37c1a95a2f1f05a51892aef2949732b62a38aadd58");

    /** 与 SignedRequest 固定 query 画像保持一致（注册与业务请求须是同一台“设备”）。 */
    private static final String PROFILE_MODEL = "25053RT47C";
    private static final String PROFILE_BRAND = "Redmi";
    private static final String PROFILE_MANUFACTURER = "Xiaomi";
    private static final String PROFILE_CPU_ABI = "arm64-v8a";
    private static final String PROFILE_OS_VERSION = "16";   // 必须字符串
    private static final int PROFILE_OS_API = 36;
    private static final String PROFILE_RESOLUTION = "1280*2772";
    private static final int PROFILE_DPI = 520;
    /** 与播放画像（PlaybackSigner/SignedRequest）保持一致：注册时声明为手机端。 */
    private static final String PROFILE_DRAGON_DEVICE_TYPE = "phone";

    private static final Object LOCK = new Object();
    private static final SecureRandom RANDOM = new SecureRandom();

    /** 注册整体失败后的冷却期：避免每次切集都重打全部主机，把网络差的电视拖到起播超时。 */
    private static final long REGISTER_COOLDOWN_MS = 60_000L;

    /**
     * 设备身份有效期：超过后自动弃用并注册新身份（等价于用户卸载重装）。
     *
     * <p>线上实证（2026-10-26 VIDAA）：同一台电视旧身份打播放接口全 200 空 body，
     * 卸载重装拿到新身份后立即恢复——服务端会周期性把旧 device_id 判废，而身份
     * 永久持久化、永不自愈，导致「过几天突然全都不能看」。7 天是经验值：
     * 覆盖「用几周后失效」的用户，又不会频繁重注册触发风控。
     */
    private static final long IDENTITY_TTL_MS = 7L * 24 * 60 * 60 * 1000;

    private static Context appContext;
    private static Result cached;      // 进程内缓存（避免每次读 prefs）
    private static String lastError = "";
    private static volatile long lastFailedAt = 0L;

    private static volatile String fallbackDeviceId;
    private static volatile String fallbackIid;

    /** 注册结果。 */
    public static final class Result {
        public final String deviceId;
        public final String installId;
        public final String deviceToken;   // 可能为空
        public final String cookieHeader;  // 拼好的 Cookie 头值（可能为空）
        public final boolean alreadyRegistered;

        Result(String deviceId, String installId, String deviceToken,
               String cookieHeader, boolean alreadyRegistered) {
            this.deviceId = deviceId;
            this.installId = installId;
            this.deviceToken = deviceToken;
            this.cookieHeader = cookieHeader;
            this.alreadyRegistered = alreadyRegistered;
        }
    }

    private DeviceRegister() {
    }

    /** App 启动时注入上下文（读落盘身份用），必须在首次构造 NetClient 之前。 */
    public static void attach(Context context) {
        appContext = context.getApplicationContext();
    }

    public static boolean registered() {
        return load() != null;
    }

    public static String deviceId() {
        Result r = load();
        if (r != null) return r.deviceId;
        return fallbackRandom()[0];
    }

    public static String installId() {
        Result r = load();
        if (r != null) return r.installId;
        return fallbackRandom()[1];
    }

    /** 已注册时的 Cookie 头值；未注册返回空串。 */
    public static String cookieHeader() {
        Result r = load();
        return r == null ? "" : r.cookieHeader;
    }

    public static String lastError() {
        return lastError;
    }

    /**
     * 确保已注册：已注册直接返回；未注册同步注册（一把锁收敛并发）。
     * 整体失败后有 [REGISTER_COOLDOWN_MS] 冷却：冷却期内直接抛出缓存的原因，
     * 不再重打全部主机（否则每次切集都要付一遍全部主机的连接/DNS 超时代价）。
     * 失败抛出，由调用方决定是否降级继续（播放可继续用随机身份尝试）。
     */
    public static Result ensureRegistered(OkHttpClient client) throws Exception {
        Result r = load();
        if (r != null) return r;
        synchronized (LOCK) {
            r = load();
            if (r != null) return r;
            if (lastFailedAt != 0L) {
                long since = System.currentTimeMillis() - lastFailedAt;
                if (since < REGISTER_COOLDOWN_MS) {
                    throw new Exception("设备注册冷却中（"
                            + (REGISTER_COOLDOWN_MS - since) / 1000 + "s 后自动重试）：" + lastError);
                }
            }
            return registerNow(client);
        }
    }

    /**
     * 执行一次注册（无论是否已注册都重发）。
     *
     * <p>失败重试共 3 轮，每轮内按 [REGISTER_HOSTS] 逐个主机回退（DNS 解析失败 /
     * 域名被拦时立刻换下一个，不等超时兜圈子）；轮与轮之间短退避。
     * 全部主机全部轮次都失败才抛出，且 {@link #lastError()} 会带上
     * 「尝试过哪些主机 + 最后一条错误」，供日志页与播放失败提示直接定位。
     */
    public static Result registerNow(OkHttpClient client) throws Exception {
        // BUG-106：与 ensureRegistered 共用同一把锁。播放自动换身份（NetClient 第 1 轮
        // 全空 body）与日志页「重置身份」按钮可能并发进入；无锁时两条注册流程会交错
        // save()，一方刚拿到的新身份又被另一方覆盖 → 请求签名用 A、Cookie 是 B，两边
        // 一起失败。Java 监视器可重入，ensureRegistered 持锁调用本方法不受影响。
        synchronized (LOCK) {
            return registerNowLocked(client);
        }
    }

    private static Result registerNowLocked(OkHttpClient client) throws Exception {
        Throwable last = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            for (String host : REGISTER_HOSTS) {
                try {
                    Result r = doRegister(client, host + REGISTER_PATH);
                    lastError = "";
                    lastFailedAt = 0L;
                    save(r);
                    AppLog.INSTANCE.d("DeviceRegister", "注册成功 device_id=" + r.deviceId
                            + " install_id=" + r.installId
                            + " host=" + host
                            + (r.alreadyRegistered ? "（已存在设备）" : "（新建设备）")
                            + (attempt > 1 ? "（第 " + attempt + " 轮）" : ""));
                    AppLog.INSTANCE.setInterfaceInfo(SignedRequest.BASE_URL, r.deviceId, r.installId);
                    return r;
                } catch (Throwable t) {
                    last = t;
                    AppLog.INSTANCE.e("DeviceRegister", "注册失败 host=" + host
                            + "（第 " + attempt + "/3 轮）", t);
                }
            }
            if (attempt < 3) {
                try {
                    Thread.sleep(800L * attempt); // 短退避，避免连续打同一批接口
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw ie;
                }
            }
        }
        lastError = "已尝试主机 " + Arrays.toString(REGISTER_HOSTS)
                + "，最后错误：" + (last == null ? "未知" : last.getMessage() == null ? last.toString() : last.getMessage());
        lastFailedAt = System.currentTimeMillis();
        AppLog.INSTANCE.e("DeviceRegister", "设备注册最终失败：" + lastError, last);
        if (last instanceof Exception) throw (Exception) last;
        throw new Exception(last);
    }

    /* ==================== 请求构造与响应解析 ==================== */

    private static Result doRegister(OkHttpClient client, String url) throws Exception {
        long now = System.currentTimeMillis();

        JSONObject header = new JSONObject();
        header.put("display_name", "HG免费短剧");
        header.put("aid", 8662);
        header.put("channel", "69310258a");
        header.put("package", "com.phoenix.read");
        header.put("version_code", 73932);
        header.put("app_version", "7.3.9.32");
        header.put("sdk_version", "3.7.0-rc.34-fanqie-xiaoshuo");
        header.put("sdk_target_version", 29);
        header.put("git_hash", "3a88f4e");
        header.put("release_build", "b5e187f");
        header.put("os", "Android");
        header.put("os_version", PROFILE_OS_VERSION); // 字符串！
        header.put("os_api", PROFILE_OS_API);
        header.put("device_model", PROFILE_MODEL);
        header.put("device_brand", PROFILE_BRAND);
        header.put("device_manufacturer", PROFILE_MANUFACTURER);
        header.put("cpu_abi", PROFILE_CPU_ABI);
        header.put("density_dpi", PROFILE_DPI);
        header.put("display_density", "xxhdpi");
        header.put("resolution", PROFILE_RESOLUTION);
        header.put("language", "zh");
        header.put("timezone", 8);
        header.put("access", "wifi");
        header.put("carrier", "CHINA MOBILE");
        header.put("mcc_mnc", "46000");
        header.put("rom", rom());
        header.put("rom_version", romVersion());
        header.put("cdid", prefs().getString("cdid", newUuid()));
        header.put("sig_hash", "56a962410c494bbaf0b58dba20cae56f");
        header.put("openudid", openudid());
        header.put("clientudid", prefs().getString("clientudid", newUuid()));
        header.put("region", "CN");
        header.put("tz_name", TimeZone.getDefault().getID());
        header.put("tz_offset", TimeZone.getDefault().getRawOffset());
        header.put("sim_region", "cn");
        header.put("sim_serial_number", new JSONArray());
        header.put("oaid_may_support", false);
        header.put("req_id", UUID.randomUUID().toString());
        header.put("device_platform", "android");
        JSONObject custom = new JSONObject();
        custom.put("account_region_src", "fallback");
        custom.put("host_bit", 64);
        custom.put("account_region", "cn");
        custom.put("dragon_device_type", PROFILE_DRAGON_DEVICE_TYPE);
        header.put("custom", custom);
        header.put("pre_installed_channel", "");
        header.put("apk_first_install_time", firstInstallTime());
        header.put("is_system_app", 0);
        header.put("sdk_flavor", "china");
        header.put("guest_mode", 0);

        JSONObject root = new JSONObject();
        root.put("magic_tag", "ss_app_log");
        root.put("header", header);
        root.put("_gen_time", now);
        byte[] json = root.toString().getBytes("UTF-8");
        persistClientIds(header); // cdid/openudid/clientudid 固化，重试不换身份特征

        byte[] body = ttEncrypt(gzip(json));

        Request.Builder rb = new Request.Builder().url(url)
                .post(RequestBody.create(
                        MediaType.parse("application/octet-stream;tt-data=a"), body))
                .header("Content-Type", "application/octet-stream;tt-data=a")
                .header("log-encode-type", "gzip")
                .header("x-ss-dp", "8662")
                .header("x-ss-req-ticket", String.valueOf(now))
                .header("x-vc-bdturing-sdk-version", "4.0.3.cn")
                .header("lc", "101")
                .header("sdk-version", "2")
                .header("passport-sdk-version", "5051452")
                .header("x-neptune", "-8|50:51:59:30:40:47:49:39")
                .header("x-ss-stub", XGorgon.ssStub(body))
                .header("User-Agent", REGISTER_UA);
        // 刻意不加签名头、不加 accept-encoding（见类注释 6）

        Response resp = client.newCall(rb.build()).execute();
        try {
            byte[] raw = resp.body() == null ? new byte[0] : resp.body().bytes();
            if (raw.length >= 2 && raw[0] == 0x1f && raw[1] == (byte) 0x8b) {
                // OkHttp 未透明解压时的兜底（gzip 魔数）
                try (GZIPInputStream gz = new GZIPInputStream(new java.io.ByteArrayInputStream(raw))) {
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    byte[] buf = new byte[4096];
                    int n;
                    while ((n = gz.read(buf)) > 0) out.write(buf, 0, n);
                    raw = out.toByteArray();
                }
            }
            if (resp.code() != 200) {
                throw new IllegalStateException("HTTP " + resp.code()
                        + " body=" + excerpt(raw));
            }
            JSONObject jo = new JSONObject(new String(raw, "UTF-8"));
            String dev = pickId(jo, "device_id_str", "device_id");
            String iid = pickId(jo, "install_id_str", "install_id");
            if (!validId(dev) || !validId(iid)) {
                throw new IllegalStateException("注册被拒 device_id=" + dev
                        + " install_id=" + iid + " body=" + excerpt(raw));
            }
            String token = jo.optString("device_token", "");
            return new Result(dev, iid, token, cookieHeader(resp), false);
        } finally {
            resp.close();
        }
    }

    /**
     * ttEncrypt：MAGIC || rnd[32] || AES-128-CBC(key/iv 派生自 SHA512(SHA512(rnd)||SALT))。
     * 明文 = PKCS7(SHA512(data) || data)，PKCS7 由 JCE PKCS5Padding 等价完成。
     */
    static byte[] ttEncrypt(byte[] data) throws Exception {
        SecureRandom sr = new SecureRandom();
        byte[] rnd = new byte[32];
        sr.nextBytes(rnd);
        byte[] h = sha512(concat(sha512(rnd), KDF_SALT));
        byte[] key = Arrays.copyOfRange(h, 0, 16);
        byte[] iv = Arrays.copyOfRange(h, 16, 32);

        ByteArrayOutputStream plain = new ByteArrayOutputStream(data.length + 64);
        plain.write(sha512(data));
        plain.write(data);

        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"),
                new IvParameterSpec(iv));
        byte[] ct = cipher.doFinal(plain.toByteArray());

        ByteArrayOutputStream out = new ByteArrayOutputStream(MAGIC.length + rnd.length + ct.length);
        out.write(MAGIC);
        out.write(rnd);
        out.write(ct);
        return out.toByteArray();
    }

    /* ==================== 持久化 ==================== */

    private static Result load() {
        if (cached != null) return cached;
        Context ctx = appContext;
        if (ctx == null) return null;
        SharedPreferences p = prefs();
        String dev = p.getString("registered-device-id", "");
        String iid = p.getString("registered-install-id", "");
        if (dev.isEmpty() || iid.isEmpty()) return null;
        long savedAt = p.getLong("registered-time", 0L);
        if (savedAt <= 0L || System.currentTimeMillis() - savedAt > IDENTITY_TTL_MS) {
            // 旧版本没有 registered-time，或身份已超期：服务端可能已把该 device_id 判废
            // （旧身份全 200 空 body，卸载重装即恢复），弃用并自动换新。
            String reason = savedAt <= 0L
                    ? "旧版本遗留（无注册时间）"
                    : "已使用 " + (System.currentTimeMillis() - savedAt) / (24 * 60 * 60 * 1000L) + " 天";
            AppLog.INSTANCE.d("DeviceRegister", "设备身份超期，自动换新：" + reason);
            clearIdentity();
            return null;
        }
        Result r = new Result(dev, iid,
                p.getString("registered-token", ""),
                p.getString("registered-cookie", ""), true);
        cached = r;
        return r;
    }

    /**
     * 强制丢弃当前设备身份（等价卸载重装）：清空全部持久化特征
     * （device_id/iid/cookie/token + cdid/openudid/clientudid），
     * 下次取流/ensureRegistered 会注册全新身份。日志页「重置身份」按钮与
     * 播放通道「全空 body 自动换新」共用。
     */
    public static void resetIdentity() {
        synchronized (LOCK) {
            cached = null;
            lastFailedAt = 0L;
            lastError = "";
            fallbackDeviceId = null;
            fallbackIid = null;
            Context ctx = appContext;
            if (ctx != null) prefs().edit().clear().apply();
            AppLog.INSTANCE.d("DeviceRegister", "设备身份已重置（下次请求将注册新身份）");
        }
    }

    /** 清空身份持久化（供超期换新用，不动进程内状态）。 */
    private static void clearIdentity() {
        Context ctx = appContext;
        if (ctx == null) return;
        prefs().edit().clear().apply();
    }

    /** 当前身份的使用时长（毫秒）；未注册或时间未知返回 -1。供日志增强（C10）使用。 */
    public static long identityAgeMs() {
        Context ctx = appContext;
        if (ctx == null) return -1L;
        long t = prefs().getLong("registered-time", 0L);
        if (t <= 0L) return -1L;
        return System.currentTimeMillis() - t;
    }

    private static void save(Result r) {
        cached = r;
        Context ctx = appContext;
        if (ctx == null) return;
        prefs().edit()
                .putString("registered-device-id", r.deviceId)
                .putString("registered-install-id", r.installId)
                .putString("registered-token", r.deviceToken)
                .putString("registered-cookie", r.cookieHeader)
                .putLong("registered-time", System.currentTimeMillis())
                .apply();
    }

    private static SharedPreferences prefs() {
        Context ctx = appContext;
        if (ctx == null) throw new IllegalStateException("DeviceRegister.attach 未调用");
        return ctx.getSharedPreferences("h\u006fnggu\u006f_identity", Context.MODE_PRIVATE);
    }

    /** 把本次注册用的 cdid/openudid/clientudid 固化，保证重试时设备特征稳定。 */
    private static void persistClientIds(JSONObject header) {
        try {
            Context ctx = appContext;
            if (ctx == null) return;
            SharedPreferences p = prefs();
            SharedPreferences.Editor e = p.edit();
            if (!p.contains("cdid")) e.putString("cdid", header.getString("cdid"));
            if (!p.contains("openudid")) e.putString("openudid", header.getString("openudid"));
            if (!p.contains("clientudid")) e.putString("clientudid", header.getString("clientudid"));
            e.apply();
        } catch (Throwable ignored) {
        }
    }

    /* ==================== 小工具 ==================== */

    /** Set-Cookie 列表 → Cookie 请求头值（取每条的首个 k=v）。 */
    private static String cookieHeader(Response resp) {
        List<String> setCookies = resp.headers("Set-Cookie");
        if (setCookies.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (String sc : setCookies) {
            int cut = sc.indexOf(';');
            String pair = (cut >= 0 ? sc.substring(0, cut) : sc).trim();
            if (pair.isEmpty() || !pair.contains("=")) continue;
            if (sb.length() > 0) sb.append("; ");
            sb.append(pair);
        }
        return sb.toString();
    }

    /** 优先取字符串键；数字键转字符串。服务端两种形态都发。 */
    private static String pickId(JSONObject jo, String strKey, String numKey) {
        String s = jo.optString(strKey, "");
        if (!s.isEmpty()) return s;
        Object v = jo.opt(numKey);
        if (v == null) return "";
        if (v instanceof String) return (String) v;
        return String.valueOf(v);
    }

    private static boolean validId(String s) {
        if (s == null || s.isEmpty()) return false;
        try {
            return Long.parseLong(s.trim()) != 0L;
        } catch (NumberFormatException e) {
            return !s.equals("0"); // 非数字形态但非空，视为有效
        }
    }

    /** 未注册时的进程内随机兜底身份（注册成功后即被覆盖）。 */
    private static String[] fallbackRandom() {
        String dev = fallbackDeviceId;
        String iid = fallbackIid;
        if (dev == null || iid == null) {
            synchronized (DeviceRegister.class) {
                if (fallbackDeviceId == null) fallbackDeviceId = random16();
                if (fallbackIid == null) fallbackIid = random16();
                dev = fallbackDeviceId;
                iid = fallbackIid;
            }
        }
        return new String[]{dev, iid};
    }

    private static String random16() {
        // 16 位数字，首位 1-9
        StringBuilder sb = new StringBuilder(16);
        sb.append(1 + RANDOM.nextInt(9));
        for (int i = 1; i < 16; i++) sb.append(RANDOM.nextInt(10));
        return sb.toString();
    }

    private static String openudid() {
        SharedPreferences p = prefs();
        String v = p.getString("openudid", null);
        if (v != null) return v;
        StringBuilder sb = new StringBuilder(16);
        for (int i = 0; i < 16; i++) sb.append("0123456789abcdef".charAt(RANDOM.nextInt(16)));
        return sb.toString(); // 由 persistClientIds 落盘
    }

    private static String newUuid() {
        return UUID.randomUUID().toString();
    }

    private static long firstInstallTime() {
        try {
            PackageInfo pi = appContext.getPackageManager()
                    .getPackageInfo(appContext.getPackageName(), 0);
            return pi.firstInstallTime;
        } catch (Throwable t) {
            return System.currentTimeMillis();
        }
    }

    private static String rom() {
        try {
            String inc = Build.VERSION.INCREMENTAL;
            return (inc == null || inc.isEmpty()) ? "eng.root.20231116.160340" : inc;
        } catch (Throwable t) {
            return "eng.root.20231116.160340";
        }
    }

    private static String romVersion() {
        try {
            return Build.MODEL + " release-keys";
        } catch (Throwable t) {
            return "release-keys";
        }
    }

    private static byte[] gzip(byte[] data) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream(data.length / 2 + 64);
        try (GZIPOutputStream gz = new GZIPOutputStream(out)) {
            gz.write(data);
        }
        return out.toByteArray();
    }

    private static byte[] sha512(byte[] data) throws Exception {
        return MessageDigest.getInstance("SHA-512").digest(data);
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    static byte[] hexDecode(String hex) {
        int n = hex.length() / 2;
        byte[] out = new byte[n];
        for (int i = 0; i < n; i++) {
            out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    private static String excerpt(byte[] raw) {
        int n = Math.min(raw.length, 160);
        return new String(raw, 0, n).replaceAll("\\s+", " ");
    }
}
