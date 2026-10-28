package com.houg.tv.sign;

import android.util.Base64;

import java.nio.charset.Charset;

/**
 * 红果 CENC-AES-CTR 媒体密钥派生。
 * 逐字节对齐 Go provider_hongguo_playback.go 的 hongguoContentKey / decodeHongguoBase64。
 * 兼容 Android 4.0（API 14）：Base64 用 android.util.Base64，UTF-8 用 Charset.forName。
 */
public final class HongguoCrypto {

    private static final Charset UTF8 = Charset.forName("UTF-8");

    private HongguoCrypto() {}

    /**
     * 由 encrypt_info.spade_a 派生 16 字节 AES-128 密钥。
     * 与 Go hongguoContentKey 逐字节一致，校验条件与错误信息完全对应。
     */
    public static byte[] contentKey(String spadeA) throws Exception {
        if (spadeA == null) spadeA = "";
        if (spadeA.length() > 1024) {
            throw new Exception("红果媒体密钥数据过长");
        }
        byte[] raw = decodeHongguoBase64(spadeA);
        if (raw == null || raw.length < 3) {
            throw new Exception("红果媒体密钥编码无效");
        }
        // Go: tagLength := int(raw[0]^raw[1]^raw[2]) - 48（byte 为无符号）
        int tagLength = ((raw[0] ^ raw[1] ^ raw[2]) & 0xFF) - 48;
        int contentLength = raw.length - tagLength - 1;
        if (tagLength < 1 || contentLength < 33 || contentLength >= raw.length) {
            throw new Exception("红果媒体密钥结构无效");
        }
        byte seed = (byte) (raw[raw.length - tagLength - 2] ^ raw[raw.length - tagLength - 1]);
        byte[] tag = new byte[tagLength];
        for (int i = 0; i < tagLength; i++) {
            tag[i] = (byte) (raw[raw.length - tagLength + i] ^ seed);
        }
        String tagStr = new String(tag, UTF8);
        if ("app_v2".equals(tagStr) || "web_v2".equals(tagStr)) {
            throw new Exception("红果媒体密钥版本暂不支持");
        }
        byte[] decoded = new byte[contentLength];
        byte previousEven = (byte) 250;
        byte previousOdd = (byte) 85;
        for (int index = 0; index < contentLength; index++) {
            byte current = raw[1 + index];
            byte previous;
            if (index % 2 == 0) {
                previous = previousEven;
                previousEven = current;
            } else {
                previous = previousOdd;
                previousOdd = current;
            }
            // Go: decoded[index] = byte(int(previous^current) - 21 - bits.OnesCount(uint(index)))
            int v = ((previous & 0xFF) ^ (current & 0xFF)) - 21 - Integer.bitCount(index);
            decoded[index] = (byte) v;
        }
        // Go: strconv.ParseUint(string(decoded[:1]), 36, 8)
        int padding = Character.digit((char) (decoded[0] & 0xFF), 36);
        if (padding < 0 || contentLength - padding - 1 != 32) {
            throw new Exception("红果媒体密钥内容无效");
        }
        // Go: hex.DecodeString(string(decoded[1:33]))
        byte[] key = hexDecode(decoded, 1, 32);
        if (key == null || key.length != 16) {
            throw new Exception("红果媒体密钥不是有效的 AES-128 密钥");
        }
        return key;
    }

    /** 对应 Go decodeHongguoBase64：TrimSpace 后先严格标准解码，失败再 RawStd（补 padding）。 */
    private static byte[] decodeHongguoBase64(String value) {
        String s = value.trim();
        try {
            return Base64.decode(s, Base64.NO_WRAP);
        } catch (IllegalArgumentException e) {
            // 标准解码失败，尝试无 padding 的 raw 解码
        }
        try {
            String padded = s;
            int rem = padded.length() % 4;
            if (rem != 0) {
                StringBuilder sb = new StringBuilder(padded);
                for (int i = 0; i < 4 - rem; i++) sb.append('=');
                padded = sb.toString();
            }
            return Base64.decode(padded, Base64.NO_WRAP);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** 对应 Go hex.DecodeString：32 个 hex 字符 -> 16 字节，非法字符返回 null。 */
    private static byte[] hexDecode(byte[] src, int off, int len) {
        if (src == null || off < 0 || len < 0 || src.length < off + len || (len & 1) != 0) {
            return null;
        }
        byte[] out = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            int hi = Character.digit((char) (src[off + i] & 0xFF), 16);
            int lo = Character.digit((char) (src[off + i + 1] & 0xFF), 16);
            if (hi < 0 || lo < 0) return null;
            out[i / 2] = (byte) ((hi << 4) | lo);
        }
        return out;
    }
}
