package android.content;

/** JVM 探针专用 SharedPreferences 桩（仅覆盖 DeviceRegister 用到的面）。 */
public interface SharedPreferences {
    String getString(String key, String defValue);

    long getLong(String key, long defValue);

    boolean contains(String key);

    Editor edit();

    interface Editor {
        Editor putString(String key, String value);

        Editor putLong(String key, long value);

        Editor clear();

        void apply();
    }
}
