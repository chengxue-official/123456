package com.houg.tv.sign;

/**
 * 标准 Base64（RFC 4648，含 {@code =} 填充、不换行）。
 *
 * <p>为什么不用 {@code java.util.Base64}：那是 <b>API 26</b> 才有的类，本工程
 * {@code minSdk = 21}，未启用 core library desugaring —— 在 Android 5/6/7 上一碰
 * 就抛 {@link NoClassDefFoundError}。而签名链路的 SBOX 是在<b>静态初始化</b>里
 * 解码的，等于一进播放流程就崩，比运行期失败更早。
 *
 * <p>也不能改用 {@code android.util.Base64}：{@link PlaybackSigner} 与
 * {@link MediaCrypto} 同时被 JVM 单测（{@code signing.json} 固定向量对拍）和
 * 在线探针加载，JVM 侧的 android.jar 全是 stub，调用即 "not mocked"。
 *
 * <p>因此这里手写一份纯 Java 实现，行为与
 * {@code Base64.getEncoder().encodeToString} / {@code Base64.getDecoder().decode}
 * 一致，任何 JVM/Android 版本都能跑。URL-safe 的 {@code -}{@code _} 仍由调用方
 * 先归一成 {@code +}{@code /}（沿用既有约定），本类只认标准字母表。
 */
public final class B64 {

    private static final char[] ALPHABET =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".toCharArray();

    /** 字符 -> 6bit 值；-1 表示非法。仅覆盖 ASCII，够用且比查 HashMap 快。 */
    private static final int[] REVERSE = new int[128];

    static {
        for (int i = 0; i < REVERSE.length; i++) {
            REVERSE[i] = -1;
        }
        for (int i = 0; i < ALPHABET.length; i++) {
            REVERSE[ALPHABET[i]] = i;
        }
    }

    private B64() {
    }

    /** 等价于 {@code Base64.getEncoder().encodeToString(data)}。 */
    public static String encodeToString(byte[] data) {
        int len = data.length;
        StringBuilder sb = new StringBuilder((len + 2) / 3 * 4);
        int i = 0;
        while (i + 3 <= len) {
            int chunk = ((data[i] & 0xff) << 16) | ((data[i + 1] & 0xff) << 8) | (data[i + 2] & 0xff);
            sb.append(ALPHABET[(chunk >> 18) & 63])
                    .append(ALPHABET[(chunk >> 12) & 63])
                    .append(ALPHABET[(chunk >> 6) & 63])
                    .append(ALPHABET[chunk & 63]);
            i += 3;
        }
        int rest = len - i;
        if (rest == 1) {
            int chunk = (data[i] & 0xff) << 16;
            sb.append(ALPHABET[(chunk >> 18) & 63])
                    .append(ALPHABET[(chunk >> 12) & 63])
                    .append("==");
        } else if (rest == 2) {
            int chunk = ((data[i] & 0xff) << 16) | ((data[i + 1] & 0xff) << 8);
            sb.append(ALPHABET[(chunk >> 18) & 63])
                    .append(ALPHABET[(chunk >> 12) & 63])
                    .append(ALPHABET[(chunk >> 6) & 63])
                    .append('=');
        }
        return sb.toString();
    }

    /**
     * 等价于 {@code Base64.getDecoder().decode(s)}：忽略末尾 {@code =}，
     * 遇非法字符抛 {@link IllegalArgumentException}（与原实现同样严格，
     * 不把坏数据静默吞成错误密钥）。
     */
    public static byte[] decode(String s) {
        int n = s.length();
        while (n > 0 && s.charAt(n - 1) == '=') {
            n--;
        }
        // 输出长度 = 完整 4 组贡献 3 字节；尾部零头：2 字符 12bit → 1 字节，3 字符 18bit → 2 字节
        int full = n / 4;
        int tail = n % 4;
        if (tail == 1) {
            // 单个 6bit 组凑不满 1 字节，java.util.Base64 同样拒绝
            throw new IllegalArgumentException("Base64 长度异常");
        }
        int outLen = full * 3 + (tail == 0 ? 0 : tail == 2 ? 1 : 2);
        byte[] out = new byte[outLen];
        int o = 0;
        int acc = 0;
        int bits = 0;
        for (int i = 0; i < n; i++) {
            char c = s.charAt(i);
            int v = c < REVERSE.length ? REVERSE[c] : -1;
            if (v < 0) {
                throw new IllegalArgumentException("Base64 字符非法: " + c);
            }
            acc = (acc << 6) | v;
            bits += 6;
            if (bits >= 8) {
                bits -= 8;
                out[o++] = (byte) ((acc >> bits) & 0xff);
            }
        }
        if (o != outLen) {
            throw new IllegalArgumentException("Base64 长度异常");
        }
        return out;
    }
}
