package com.houg.tv.sign;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * 播放侧地址/密钥解密（思路对齐 HGTV MediaCrypto，手写重实现）。
 *
 * <p>video_model 返回的 {@code key_seed} 解出 16 字节内容密钥；
 * {@code main_url} 之类的加密字段解出明文播放地址。
 */
public final class MediaCrypto {

    private static final byte[] URL_CONSTANTS = hexBytes(
            "4dd4c2e6b83162090e52b3c7a6733ba41cb2462b829ab58a196b39db57177524"
                    + "f49baf7f08e8d68d26a72e37c1a95a2f1f05a51892aef2949732b62a38aadd58");

    private MediaCrypto() {
    }

    /** 由 base64 的 key_seed 推导 16 字节内容密钥。 */
    public static byte[] deriveKey(String value) {
        byte[] raw = decode64(value);
        if (raw.length < 3) {
            throw new IllegalArgumentException("内容密钥格式异常");
        }
        int mixed = (raw[0] & 255) ^ (raw[1] & 255) ^ (raw[2] & 255);
        int length = Math.min(raw.length - mixed + 47, raw.length - 1);
        if (length < 33) {
            throw new IllegalArgumentException("内容密钥过短");
        }
        byte[] work = new byte[length];
        System.arraycopy(raw, 1, work, 0, length);
        int a = 85;
        int b = 246;
        for (int i = 0; i < work.length; i++) {
            int old = work[i] & 255;
            int previous = (i & 1) != 0 ? a : b;
            if ((i & 1) != 0) {
                a = old;
            } else {
                b = old;
            }
            work[i] = (byte) (-21 - Integer.bitCount(i) + (previous ^ old));
        }
        StringBuilder ascii = new StringBuilder(32);
        for (int i = 1; i < 33; i++) {
            ascii.append((char) (work[i] & 255));
        }
        byte[] key = hexBytes(ascii.toString());
        if (key.length != 16) {
            throw new IllegalArgumentException("内容密钥长度异常");
        }
        return key;
    }

    /** 解密加密的播放地址；seed 为内容密钥。 */
    public static String decryptUrl(String value, byte[] seed) {
        byte[] raw = decode64(value);
        if (raw.length < 20 || (raw[0] & 255) != 0xa8 || (raw[2] & 255) != 1 || (raw[3] & 255) != 0) {
            throw new IllegalArgumentException("视频地址格式异常");
        }
        byte[] h = digest("SHA-512", concat(digest("SHA-512", seed), URL_CONSTANTS));
        int n = (raw.length - 4) / 16 * 16;
        byte[] data = new byte[n];
        System.arraycopy(raw, 4, data, 0, n);
        byte[] key = new byte[16];
        System.arraycopy(h, 0, key, 0, 16);
        byte[] iv = new byte[16];
        System.arraycopy(h, 16, iv, 0, 16);
        byte[] bytes = aes("CBC", key, iv, data);
        int end = bytes.length;
        int padding = bytes[end - 1] & 255;
        if (padding >= 1 && padding <= 16 && padding <= end) {
            end -= padding;
        }
        while (end > 0 && (bytes[end - 1] & 255) == 0) {
            end--;
        }
        return new String(bytes, 0, end, StandardCharsets.UTF_8);
    }

    // ---------------------------------------------------------------- 基础工具

    /** 红果 key_seed / spade_a 的 URL-safe Base64 解码（-_ 归一为 +/），供 NetClient 解 key_seed 用。 */
    public static byte[] decode64(String s) {
        return B64.decode(s.replace('-', '+').replace('_', '/'));
    }

    private static byte[] hexBytes(String s) {
        int n = s.length() / 2;
        byte[] out = new byte[n];
        for (int i = 0; i < n; i++) {
            out[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    private static byte[] digest(String algorithm, byte[] input) {
        try {
            return MessageDigest.getInstance(algorithm).digest(input);
        } catch (Exception e) {
            throw new IllegalStateException("摘要算法不可用: " + algorithm, e);
        }
    }

    private static byte[] aes(String mode, byte[] key, byte[] iv, byte[] data) {
        try {
            Cipher cipher = Cipher.getInstance("AES/" + mode + "/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
            return cipher.doFinal(data);
        } catch (Exception e) {
            throw new IllegalStateException("AES 解密失败", e);
        }
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }
}
