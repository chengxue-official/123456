package com.houg.tv.sign;

import android.content.Context;
import android.util.Log;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 播放签名的可插拔后背链。
 *
 * <p>背景：官方（红果免费短剧 com.phoenix.read）的签名实现可能换代，历史上换过三次：
 * 版本档案 / 新增请求头 / v5 端点。若哪天算法本身变了，手工复刻成本极高
 * （medusa 内部状态机约 1300 行，且官方 dex 里已经搜不到 medusa/gorgon/ladon 字符串，
 * 说明大概率下沉到 native）。
 *
 * <p>这里把签名做成三级后备，换签名时**不用改调用方**，只需把材料丢进 assets：
 * <pre>
 *   1. builtin  ：自研 PlaybackSignerV5（现在生效）
 *   2. dex      ：assets/signer/official.dex + signer.json，运行时 DexClassLoader 反射调用
 *   3. so       ：需要把官方 .so 随 APK 打进 jniLibs（运行期不能从 assets 加载），
 *                 加载成功后同样经 dex 的 Java 胶水层调用
 * </pre>
 *
 * <p>signer.json 格式（放在 assets/signer/signer.json）：
 * <pre>
 * { "class": "com.xxx.Signer", "method": "sign", "dex": "official.dex" }
 * </pre>
 * 目标方法签名需兼容：{@code static Map<String,String> sign(String url, Map<String,String> profile, byte[] body)}
 * 或返回 {@code {url, headers}} 结构；不兼容时视为不可用并回退。
 */
public final class SignerChain {

    private static final String TAG = "SignerChain";
    private static final String ASSET_DIR = "signer";
    private static final String CONFIG = "signer.json";

    /** 当前生效的后端名（诊断用）。 */
    private static volatile String active = "builtin";
    /** 失败过的后端，避免每次取流都重试一遍（进程内）。 */
    private static final java.util.Set<String> failed = java.util.Collections.synchronizedSet(new java.util.HashSet<String>());
    private static Context appContext;

    private SignerChain() {
    }

    public static void attach(Context ctx) {
        appContext = ctx.getApplicationContext();
    }

    public static String activeBackend() {
        return active;
    }

    /** 统一入口：按 builtin → dex → so 顺序尝试，成功即缓存。 */
    public static PlaybackSignerV5.Request sign(String url, Map<String, String> profile, byte[] body) {
        Throwable last = null;

        if (!failed.contains("builtin")) {
            try {
                PlaybackSignerV5.Request r = PlaybackSignerV5.sign(url, profile, body);
                setActive("builtin");
                return r;
            } catch (Throwable t) {
                last = t;
                failed.add("builtin");
                Log.w(TAG, "builtin 签名失败: " + t);
            }
        }

        if (!failed.contains("dex")) {
            try {
                PlaybackSignerV5.Request r = signByOfficialDex(url, profile, body);
                if (r != null) {
                    setActive("dex");
                    return r;
                }
                failed.add("dex");
            } catch (Throwable t) {
                last = t;
                failed.add("dex");
                Log.w(TAG, "官方 dex 签名失败: " + t);
            }
        }

        if (!failed.contains("so")) {
            try {
                PlaybackSignerV5.Request r = signByOfficialSo(url, profile, body);
                if (r != null) {
                    setActive("so");
                    return r;
                }
                failed.add("so");
            } catch (Throwable t) {
                last = t;
                failed.add("so");
                Log.w(TAG, "官方 so 签名失败: " + t);
            }
        }

        // 全部失败：把 builtin 放回来再试一次（它是唯一不依赖外部材料的）
        try {
            PlaybackSignerV5.Request r = PlaybackSignerV5.sign(url, profile, body);
            setActive("builtin");
            return r;
        } catch (Throwable t) {
            if (last != null) {
                throw new IllegalStateException("所有签名后端均不可用: " + last.getMessage(), t);
            }
            throw new IllegalStateException("所有签名后端均不可用", t);
        }
    }

    private static void setActive(String name) {
        if (!name.equals(active)) {
            active = name;
            Log.i(TAG, "签名后端切换为 " + name);
        }
    }

    // ------------------------------------------------------------------ dex

    /** 用官方 dex 里的签名类（反射调用）。材料缺失返回 null。 */
    private static PlaybackSignerV5.Request signByOfficialDex(String url, Map<String, String> profile, byte[] body) throws Exception {
        return invokeOfficial(url, profile, body, false);
    }

    /**
     * native 版本：先确认随包的官方 .so 已加载（jniLibs 打包），再走同一套 dex 胶水。
     * 运行期无法从 assets 加载 .so，所以这里只检查是否已随包提供。
     */
    private static PlaybackSignerV5.Request signByOfficialSo(String url, Map<String, String> profile, byte[] body) throws Exception {
        if (appContext == null) return null;
        // 官方签名若下沉到 native，通常由它的 Java 类 System.loadLibrary 完成；
        // 我们能做的是把 .so 随 APK 打包（jniLibs/arm64-v8a/），这里只做可用性探测。
        File libDir = new File(appContext.getApplicationInfo().nativeLibraryDir);
        File[] libs = libDir.listFiles();
        if (libs == null || libs.length == 0) return null;
        boolean hasEncryptor = false;
        for (File f : libs) {
            if (f.getName().contains("Encryptor")) {
                hasEncryptor = true;
                break;
            }
        }
        if (!hasEncryptor) return null;
        return invokeOfficial(url, profile, body, true);
    }

    private static PlaybackSignerV5.Request invokeOfficial(String url, Map<String, String> profile, byte[] body, boolean needSo) throws Exception {
        if (appContext == null) return null;
        JSONObject cfg = readConfig();
        if (cfg == null) return null;
        String dexName = cfg.optString("dex", "official.dex");
        String className = cfg.optString("class", "");
        String methodName = cfg.optString("method", "sign");
        if (className.isEmpty()) return null;

        File dexFile = materialize(dexName);
        if (dexFile == null) return null;

        File outDir = new File(appContext.getCacheDir(), "signer_odex");
        if (!outDir.exists() && !outDir.mkdirs()) return null;
        ClassLoader cl = new dalvik.system.DexClassLoader(
                dexFile.getAbsolutePath(), outDir.getAbsolutePath(), null, SignerChain.class.getClassLoader());
        Class<?> c = Class.forName(className, true, cl);
        Method m = findMethod(c, methodName);
        if (m == null) return null;
        Object raw = m.invoke(null, url, new LinkedHashMap<>(profile), body);
        return toRequest(raw);
    }

    private static Method findMethod(Class<?> c, String name) {
        for (Method m : c.getDeclaredMethods()) {
            if (!m.getName().equals(name)) continue;
            Class<?>[] p = m.getParameterTypes();
            if (p.length == 3 && p[0] == String.class && Map.class.isAssignableFrom(p[1]) && p[2] == byte[].class) {
                m.setAccessible(true);
                return m;
            }
        }
        return null;
    }

    /** 把官方方法返回值适配成我们内部的 Request（支持 Map 或 {url,headers} JSON）。 */
    @SuppressWarnings("unchecked")
    private static PlaybackSignerV5.Request toRequest(Object raw) {
        if (raw == null) return null;
        if (raw instanceof PlaybackSignerV5.Request) return (PlaybackSignerV5.Request) raw;
        String u = null;
        LinkedHashMap<String, String> headers = new LinkedHashMap<>();
        if (raw instanceof Map) {
            Map<?, ?> map = (Map<?, ?>) raw;
            Object ho = map.get("headers");
            if (ho instanceof Map) {
                for (Map.Entry<?, ?> e : ((Map<?, ?>) ho).entrySet()) {
                    headers.put(String.valueOf(e.getKey()), String.valueOf(e.getValue()));
                }
            } else {
                for (Map.Entry<?, ?> e : map.entrySet()) {
                    headers.put(String.valueOf(e.getKey()), String.valueOf(e.getValue()));
                }
            }
            Object uo = map.get("url");
            if (uo != null) u = String.valueOf(uo);
        } else if (raw instanceof JSONObject) {
            JSONObject o = (JSONObject) raw;
            u = o.optString("url", null);
            JSONObject h = o.optJSONObject("headers");
            if (h != null) {
                java.util.Iterator<String> it = h.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    headers.put(k, h.optString(k, ""));
                }
            }
        }
        if (headers.isEmpty()) return null;
        return new PlaybackSignerV5.Request(u == null ? "" : u, headers);
    }

    private static JSONObject readConfig() {
        try {
            InputStream in = appContext.getAssets().open(ASSET_DIR + "/" + CONFIG);
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            in.close();
            return new JSONObject(new String(bos.toByteArray(), "UTF-8"));
        } catch (Throwable t) {
            return null;
        }
    }

    /** 把 assets/signer/xxx.dex 复制到私有目录（DexClassLoader 不能直接读 assets）。 */
    private static File materialize(String name) {
        try {
            File dir = new File(appContext.getFilesDir(), "signer");
            if (!dir.exists() && !dir.mkdirs()) return null;
            File out = new File(dir, name);
            InputStream in = appContext.getAssets().open(ASSET_DIR + "/" + name);
            FileOutputStream fos = new FileOutputStream(out);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) fos.write(buf, 0, n);
            fos.close();
            in.close();
            return out;
        } catch (Throwable t) {
            return null;
        }
    }
}
