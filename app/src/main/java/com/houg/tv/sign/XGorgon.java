package com.houg.tv.sign;

import java.nio.charset.Charset;
import java.security.MessageDigest;

/**
 * 红果 App 接口 X-Gorgon 签名（对齐 Go provider_hongguo_sign.go 的 signHongguoRequest）。
 *
 * payload 20 字节布局（★ 关键）：
 *   [0..4)   = md5(rawQuery)[0..4]
 *   [4..8)   = md5(body)[0..4]（body 非空时）
 *   [8..12)  = 0（留空）
 *   [12..16) = {0x00,0x06,0x0B,0x1C}（MAGIC）
 *   [16..20) = 大端 uint32(unix 秒)
 * 之后整体与 SALT 逐字节异或，再做 rotate/reverse 变换，最后拼 6 字节前缀转大写 hex。
 */
public final class XGorgon {

    /** API 14 兼容：StandardCharsets 是 API 19 才有的 */
    private static final Charset UTF8 = Charset.forName("UTF-8");

    /** 20 字节固定 salt */
    private static final byte[] SALT = {
        (byte)0x44,(byte)0xB9,(byte)0xB9,(byte)0xD9,(byte)0xA4,(byte)0xAE,(byte)0xF9,(byte)0xFC,
        (byte)0xA4,(byte)0x93,(byte)0xAA,(byte)0x75,(byte)0x7C,(byte)0xA3,(byte)0xC2,(byte)0xC4,
        (byte)0xA4,(byte)0x96,(byte)0x93,(byte)0x8F
    };

    /** 6 字节输出前缀 */
    private static final byte[] PREFIX = { (byte)0x84, 0x04, 0x40, 0x1C, 0x00, 0x00 };

    /** 固定魔数，位于 payload[12..16] */
    private static final byte[] MAGIC = { 0x00, 0x06, 0x0B, 0x1C };

    /** 8 位倒序表 */
    private static final int[] BITREV = new int[256];
    static {
        for (int i = 0; i < 256; i++) {
            int v = 0, src = i;
            for (int b = 0; b < 8; b++) {
                v = (v << 1) | (src & 1);
                src >>= 1;
            }
            BITREV[i] = v;
        }
    }

    public static final String USER_AGENT =
        "com.phoenix.read/73932 (Linux; U; Android 16; zh_CN; 25053RT47C; "
      + "Build/BP2A.250605.031.A3; Cronet/TTNetVersion:04657795 2026-01-23 "
      + "QuicVersion:c67e9834 2025-09-08)";

    private XGorgon() {}

    /** 主签名算法，返回 52 字符大写 HEX。query 为编码后的完整 query 串。 */
    public static String sign(String query, byte[] body, long unixSec) {
        if (query == null) query = "";
        if (body == null) body = new byte[0];

        byte[] buf = new byte[20];

        // [0..4] = MD5(query)[0..4]
        System.arraycopy(md5(query.getBytes(UTF8)), 0, buf, 0, 4);

        // [4..8] = MD5(body)[0..4]，body 为空则保持 0x00
        if (body.length != 0) {
            System.arraycopy(md5(body), 0, buf, 4, 4);
        }

        // [8..12] 留空（0）

        // [12..16] 固定魔数
        System.arraycopy(MAGIC, 0, buf, 12, 4);

        // [16..20] unix 秒，大端
        long sec = unixSec & 0xFFFFFFFFL;
        buf[16] = (byte)(sec >>> 24);
        buf[17] = (byte)(sec >>> 16);
        buf[18] = (byte)(sec >>> 8);
        buf[19] = (byte)sec;

        // 步骤 1：与 SALT 逐字节异或
        for (int i = 0; i < 20; i++) buf[i] ^= SALT[i];

        // 步骤 2：原地逐字节变换（i=19 时读到已被改写的 buf[0]）
        for (int i = 0; i < 20; i++) {
            int v = buf[i] & 0xFF;
            v = ((v << 4) | (v >>> 4)) & 0xFF;   // rotateLeft8
            v ^= (buf[(i + 1) % 20] & 0xFF);     // 异或下一字节
            v = BITREV[v];                       // reverse8
            buf[i] = (byte)((~v) ^ 0x14);        // ^0xff ^ 0x14
        }

        // 步骤 3：前缀 + 20 字节 → 大写 HEX
        byte[] out = new byte[26];
        System.arraycopy(PREFIX, 0, out, 0, 6);
        System.arraycopy(buf, 0, out, 6, 20);
        return toHex(out);
    }

    /** X-SS-STUB = body 的 MD5 大写 HEX */
    public static String ssStub(byte[] body) {
        if (body == null) body = new byte[0];
        return toHex(md5(body));
    }

    public static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format("%02X", b & 0xFF));
        return sb.toString();
    }

    private static byte[] md5(byte[] data) {
        try {
            return MessageDigest.getInstance("MD5").digest(data);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
