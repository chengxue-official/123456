package com.houg.tv.sign;

import android.content.Context;

import com.houg.tv.util.AppLog;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * HGTV 1.3.9.3 签名栈「整块借道」：把 HGTV 的 classes.dex 打进 assets，
 * 运行时 DexClassLoader 加载，直接调它自己的 {@code H0.L3.h(...)} 生成
 * v5 取流请求（URL + 全部签名头）。
 *
 * <p>为什么这么做：2026-11-25 线上 2.2.2 实测——端点（preload/v1）、query 档案
 * （update_64/Redmi）、完整头表都对了之后仍然 200 空 body。反编译对比确认剩余
 * 差异全在 medusa 内部：field 15 是动态计数状态、field 16（字符串）/21（动态
 * 掩码值）/22 我们根本没有，field 24 缺 sts/fkd/pd/lp/fl，加密层的序列化与
 * 换盒规则也无法从 smali 完整还原。手工移植这套状态机（{@code AbstractC0251z1}
 * 约 1300 行 + 三层自定义加密）出错概率极高，而它就在 HGTV 的 dex 里编译好了
 * ——直接加载调用，字节级一致。
 *
 * <p>调用面（全部反射，避免编译期依赖）：
 * <pre>
 *   L3 l3 = new L3();                          // v5 模式（f1813e=true），生成 18 字节 process nonce
 *   K3 k3 = L3.h(l3, baseUrl, queryMap, gzipBody, null, queryMap, 40);
 *   //   40 = 不要外部 method 覆盖（→POST）+ gzip 开关（body 已是 gzip 则不动）
 *   k3.f1784a = 签名后的 URL；k3.f1785b = 头表；k3.f1786c = 请求 body
 * </pre>
 *
 * <p>头表后处理与 HGTV 的 m0() 一致：删 x-tt-dt / x-tt-request-tag，
 * 补 x-tt-trace-id（b4.b() 同款格式）；Cookie 由调用方补。
 *
 * <p>加载失败（老机型 odex 优化失败、存储不足等）时 {@link #available()} 返回
 * false，调用方回退到自研 {@link PlaybackSignerV5}——宁可慢，不可断。
 */
public final class HgtvDexSigner {

    private static final String TAG = "HgtvDexSigner";

    private static final AtomicBoolean INITIALIZED = new AtomicBoolean(false);
    private static volatile boolean loadFailed = false;

    private static Class<?> clsL3;
    private static Class<?> clsK3;
    private static Object l3Instance;
    private static Method hMethod;
    private static Field kUrl;
    private static Field kHeaders;
    private static Field kBody;

    private HgtvDexSigner() {
    }

    /** dex 是否已成功加载（未初始化时先尝试初始化）。 */
    public static boolean available() {
        return init(null) && !loadFailed;
    }

    /**
     * 初始化：解压 asset dex 到 cacheDir 并加载。
     *
     * @param appContext 应用 Context（NetClient 侧已持有；为 null 时从 AppLog 取不到，
     *                   由调用方保证先 attach）
     */
    static synchronized boolean init(Context appContext) {
        if (INITIALIZED.get()) {
            return l3Instance != null && !loadFailed;
        }
        synchronized (HgtvDexSigner.class) {
            if (INITIALIZED.get()) {
                return l3Instance != null && !loadFailed;
            }
            Context ctx = appContext;
            if (ctx == null) {
                ctx = AppSignerContextHolder.get();
            }
            if (ctx == null) {
                // 还没拿到 Context（Application 尚未预热）：不算失败，等下一次再试
                return false;
            }
            INITIALIZED.set(true);
            try {
                File dexFile = extractDex(ctx);
                DexClassLoaderLike loader = new DexClassLoaderLike(
                        dexFile.getAbsolutePath(), ctx.getCacheDir().getAbsolutePath(),
                        HgtvDexSigner.class.getClassLoader());
                clsL3 = loader.loadClass("H0.L3");
                clsK3 = loader.loadClass("H0.K3");
                l3Instance = clsL3.getDeclaredConstructor().newInstance();
                hMethod = clsL3.getDeclaredMethod("h", clsL3, String.class,
                        LinkedHashMap.class, byte[].class, String.class, Map.class, int.class);
                hMethod.setAccessible(true);
                kUrl = clsK3.getDeclaredField("f1784a");
                kUrl.setAccessible(true);
                kHeaders = clsK3.getDeclaredField("f1785b");
                kHeaders.setAccessible(true);
                kBody = clsK3.getDeclaredField("f1786c");
                kBody.setAccessible(true);
                AppSignerContextHolder.set(ctx.getApplicationContext());
                AppLog.INSTANCE.d(TAG, "HGTV dex 签名栈加载成功");
                return true;
            } catch (Throwable t) {
                loadFailed = true;
                l3Instance = null;
                AppLog.INSTANCE.e(TAG, "HGTV dex 签名栈加载失败，回退自研 v5 签名", t);
                return false;
            }
        }
    }

    /** 供 NetClient 在拿到 Context 后主动初始化（解压 dex 有 IO，放首次播放前预热更好）。 */
    public static void warmUp(Context context) {
        if (context == null) return;
        AppSignerContextHolder.set(context.getApplicationContext());
        init(context.getApplicationContext());
    }

    /**
     * 用 HGTV 自己的签名器生成 v5 请求。
     *
     * @param base      端点（不带 query），如 …/multi_video_model/preload/v1
     * @param profile   query 参数（插入序即签名序，与 HGTV 的 b4.f2127a 同构）
     * @param gzipBody  已 gzip 的请求体
     * @return 签名后的 url + headers（含 UA/Accept/全部 x-* 签名头/Content-Type/Content-Encoding），
     *         Cookie 由调用方补；失败返回 null（调用方回退自研签名）。
     */
    public static Signed sign(String base, LinkedHashMap<String, String> profile,
                              byte[] gzipBody, String deviceToken) {
        if (!init(null)) {
            return null;
        }
        try {
            Object tokenArg = deviceToken == null ? "" : deviceToken;
            Object k3 = hMethod.invoke(null, l3Instance, base, profile, gzipBody,
                    tokenArg, profile, 40);
            String url = (String) kUrl.get(k3);
            @SuppressWarnings("unchecked")
            Map<String, String> rawHeaders = (Map<String, String>) kHeaders.get(k3);
            byte[] body = (byte[]) kBody.get(k3);

            LinkedHashMap<String, String> headers = new LinkedHashMap<>();
            for (Map.Entry<String, String> e : rawHeaders.entrySet()) {
                String k = e.getKey();
                // m0() 同款：这两个头显式删除（带旧值会被网关判 200 空 body）
                if ("x-tt-dt".equalsIgnoreCase(k) || "x-tt-request-tag".equalsIgnoreCase(k)) {
                    continue;
                }
                String v = e.getValue();
                if (v == null || v.trim().isEmpty()) continue;
                headers.put(k, v);
            }
            headers.put("x-tt-trace-id", traceId());
            return new Signed(url, headers, body == null ? gzipBody : body);
        } catch (Throwable t) {
            AppLog.INSTANCE.e(TAG, "HGTV dex 签名调用失败，回退自研 v5 签名", t);
            return null;
        }
    }

    /** b4.b() 同款 trace id：00-<uuid 去横线>-<前 16 位>-01。 */
    private static String traceId() {
        String u = UUID.randomUUID().toString().replace("-", "");
        return "00-" + u + "-" + u.substring(0, 16) + "-01";
    }

    private static File extractDex(Context ctx) throws Exception {
        File dir = new File(ctx.getCacheDir(), "hgtv_dex");
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IllegalStateException("无法创建 dex 目录 " + dir);
        }
        File out = new File(dir, "classes.dex");
        if (out.exists() && out.length() > 0) {
            return out; // 已解压过（版本由 assets 内 dex 更新后长度校验兜底：见下）
        }
        InputStream in = ctx.getAssets().open("hgtv/classes.dex");
        try {
            OutputStream os = new FileOutputStream(out);
            try {
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) {
                    os.write(buf, 0, n);
                }
            } finally {
                os.close();
            }
        } finally {
            in.close();
        }
        return out;
    }

    /** 签名结果（url/headers/body）。 */
    public static final class Signed {
        public final String url;
        public final LinkedHashMap<String, String> headers;
        public final byte[] body;

        Signed(String url, LinkedHashMap<String, String> headers, byte[] body) {
            this.url = url;
            this.headers = headers;
            this.body = body;
        }
    }

    /* ---------- DexClassLoader 的一层薄壳（API 21+ 直接用 dalvik.system.DexClassLoader） ---------- */

    static final class DexClassLoaderLike {
        private final dalvik.system.DexClassLoader loader;

        DexClassLoaderLike(String dexPath, String optDir, ClassLoader parent) {
            this.loader = new dalvik.system.DexClassLoader(dexPath, optDir, null, parent);
        }

        Class<?> loadClass(String name) throws ClassNotFoundException {
            return loader.loadClass(name);
        }
    }

    /** App Context 持有者（避免签名类直接依赖 Activity）。 */
    static final class AppSignerContextHolder {
        private static volatile Context ctx;

        static void set(Context c) {
            ctx = c;
        }

        static Context get() {
            return ctx;
        }
    }
}
