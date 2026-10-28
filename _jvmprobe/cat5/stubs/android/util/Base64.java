package android.util;

/**
 * JVM 探针专用 Base64 桩：等价 android.util.Base64.NO_WRAP 语义。
 * 仅探针使用，不属于 App 交付物。
 */
public final class Base64 {
    public static final int NO_WRAP = 2;

    private Base64() {
    }

    public static byte[] decode(String str, int flags) {
        return java.util.Base64.getDecoder().decode(str);
    }

    public static byte[] decode(byte[] input, int flags) {
        return java.util.Base64.getDecoder().decode(new String(input));
    }

    public static String encodeToString(byte[] input, int flags) {
        return java.util.Base64.getEncoder().encodeToString(input);
    }
}
