package android.content;

import java.util.HashMap;
import java.util.Map;

/**
 * JVM 探针专用内存版 Context 桩：SharedPreferences 全部落在进程内 Map。
 * 仅探针使用，不属于 App 交付物。
 */
public class Context {
    public static final int MODE_PRIVATE = 0;

    private static final Map<String, Prefs> PREFS = new HashMap<>();

    public Context getApplicationContext() {
        return this;
    }

    public synchronized SharedPreferences getSharedPreferences(String name, int mode) {
        Prefs p = PREFS.get(name);
        if (p == null) {
            p = new Prefs();
            PREFS.put(name, p);
        }
        return p;
    }

    /** 探针不做真实安装信息读取：返回内存桩，firstInstallTime 可兜底。 */
    public android.content.pm.PackageManager getPackageManager() {
        return new android.content.pm.PackageManager();
    }

    public String getPackageName() {
        return "com.phoenix.read";
    }

    /** 内存版 SharedPreferences。 */
    public static final class Prefs implements SharedPreferences {
        private final Map<String, Object> map = new HashMap<>();

        @Override
        public synchronized String getString(String key, String defValue) {
            Object v = map.get(key);
            return v == null ? defValue : String.valueOf(v);
        }

        @Override
        public synchronized long getLong(String key, long defValue) {
            Object v = map.get(key);
            return v == null ? defValue : ((Number) v).longValue();
        }

        @Override
        public synchronized boolean contains(String key) {
            return map.containsKey(key);
        }

        @Override
        public Editor edit() {
            return new Editor() {
                @Override
                public Editor putString(String key, String value) {
                    synchronized (Prefs.this) {
                        map.put(key, value);
                    }
                    return this;
                }

                @Override
                public Editor putLong(String key, long value) {
                    synchronized (Prefs.this) {
                        map.put(key, value);
                    }
                    return this;
                }

                @Override
                public Editor clear() {
                    synchronized (Prefs.this) {
                        map.clear();
                    }
                    return this;
                }

                @Override
                public void apply() {
                }
            };
        }
    }
}
