package com.houg.tv.sign;

import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Map;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * 播放通道的 X-Argus / X-Ladon（逐行移植自 HGTV 1.3.6.2 的 {@code F0.o.e}）。
 *
 * <p>背景：播放签名栈原先只发 {@code base64(ts)} 占位值，2026-11-10 起服务端对
 * {@code /novel/player/*} 的取流接口收紧校验，占位值被网关判成「200 + 空 body」
 * （线上多台设备日志实证）；同期 HGTV 1.3.6.2 恢复可播，其签名器发的就是这里的
 * 完整 Argus/Ladon。内容接口（搜索/详情）用的 {@link HgCommentSign} 是同一族算法
 * 的旧版本参数，播放通道按 HGTV 新版参数实现。
 *
 * <p>参数来源（HGTV F0.o.e）：
 * <ul>
 *   <li>argus：SM3(query) / SM3(MD5(body)) 各取前 6 字节，proto 封装后
 *       72 轮变换 + xor + 反转 + AES-CBC，前缀 {@code 3ccc}，Base64；</li>
 *   <li>ladon：md5(nonce[2..6] + aid) 生成 34 轮密钥，处理
 *       {@code "<ts>-1611921764-<aid>"}，前缀 nonce[2..6]，Base64。</li>
 * </ul>
 */
public final class HgArgus {

    private static final byte[] ARGUS_KEY = hex(
            "ac1adaae95a7af94a5114ab3b3a97dd80050aa0a39314c40528caec95256c28c");

    private static final byte[] XOR_MASK = hex("d04ffdffd04ffdff");

    private static final byte[] ARGUS_HEAD = hex("a6e783ee7001100918");

    private static final byte[] ARGUS_TAIL = hex("567b");

    private static final SecureRandom RANDOM = new SecureRandom();

    /** 72 轮密钥（ARGUS_KEY 派生，常量）。 */
    private static final long[] ROUNDS = buildRounds();

    private HgArgus() {
    }

    public static final class Result {
        public final String argus;
        public final String ladon;

        Result(String argus, String ladon) {
            this.argus = argus;
            this.ladon = ladon;
        }
    }

    /**
     * @param query  已编码的 query（含 ts/_rticket，与最终 URL 一致）
     * @param body   请求体（无 body 时传 null）
     * @param ts     秒级时间戳
     * @param device 设备档案（device_type/os_version/channel/device_id/version_name/aid）
     */
    public static Result sign(String query, byte[] body, int ts, Map<String, String> device) {
        byte[] nonce = new byte[11];
        RANDOM.nextBytes(nonce);

        byte[] bodyHash = (body != null && body.length > 0)
                ? HgSm3.digest(md5(body))
                : HgSm3.digest(new byte[16]);
        byte[] queryHash = HgSm3.digest(query.getBytes(java.nio.charset.Charset.forName("UTF-8")));

        String aid = get(device, "aid", "8662");
        String deviceId = get(device, "device_id", "");
        String deviceType = get(device, "device_type", "25053RT47C");
        String osVersion = get(device, "os_version", "16");
        String channel = get(device, "channel", "update_64");
        String versionName = get(device, "version_name", "7.3.9.32");

        // 内层 bundle（field 1/2/3/7）
        byte[] inner = concat(
                concat(concat(varint(1, 1), varint(2, 1)), varint(3, 1)),
                varint(7, 3348294860L));
        // device bundle（field 1..4，作为 field 23 下发）
        byte[] deviceBundle = concat(
                concat(concat(
                        bytesField(1, utf8(deviceType)),
                        bytesField(2, utf8(osVersion))),
                        bytesField(3, utf8(channel))),
                varint(4, 2147483648L));

        ByteArrayOutputStream msg = new ByteArrayOutputStream();
        writeVarint(msg, 1, 1077940818L);
        writeVarint(msg, 2, 2);
        writeVarint(msg, 3, leU32(nonce, 6) & 0x7FFFFFFFL);
        writeBytes(msg, 4, utf8(aid));
        writeBytes(msg, 5, utf8(deviceId));
        writeBytes(msg, 6, utf8("1611921764"));
        writeBytes(msg, 7, utf8(versionName));
        writeBytes(msg, 8, utf8("v04.07.01-ml-android"));
        writeVarint(msg, 9, 135135744L);
        writeBytes(msg, 10, new byte[8]);
        writeVarint(msg, 11, 0);
        writeVarint(msg, 12, (ts & 0xFFFFFFFFL) * 2L);
        writeBytes(msg, 13, Arrays.copyOf(bodyHash, 6));
        writeBytes(msg, 14, Arrays.copyOf(queryHash, 6));
        writeBytes(msg, 15, inner);
        writeBytes(msg, 16, new byte[0]);
        writeBytes(msg, 20, utf8("none"));
        writeVarint(msg, 21, 738);
        writeBytes(msg, 23, deviceBundle);
        writeVarint(msg, 25, 2);

        byte[] data = pad16(msg.toByteArray());
        for (int off = 0; off < data.length; off += 16) {
            long l = leU64(data, off);
            long r = leU64(data, off + 8);
            for (int i = 0; i < ROUNDS.length; i++) {
                long nr = r;
                r = l ^ ((Long.rotateLeft(r, 1) & Long.rotateLeft(r, 8))
                        ^ Long.rotateLeft(r, 2)) ^ ROUNDS[i];
                l = nr;
            }
            putLeU64(data, off, l);
            putLeU64(data, off + 8, r);
        }
        for (int i = 0; i < data.length; i++) {
            data[i] ^= XOR_MASK[i % XOR_MASK.length];
        }
        data = concat(XOR_MASK, data);
        for (int l = 0, r = data.length - 1; l < r; l++, r--) {
            byte t = data[l];
            data[l] = data[r];
            data[r] = t;
        }
        byte[] container = pad16(concat(concat(ARGUS_HEAD, data), ARGUS_TAIL));
        String argus;
        try {
            Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE,
                    new SecretKeySpec(md5(Arrays.copyOfRange(ARGUS_KEY, 0, 16)), "AES"),
                    new IvParameterSpec(md5(Arrays.copyOfRange(ARGUS_KEY, 16, 32))));
            byte[] encrypted = cipher.doFinal(container);
            argus = base64(concat(new byte[]{0x3c, (byte) 0xcc}, encrypted));
        } catch (Exception e) {
            throw new IllegalStateException("X-Argus 加密失败", e);
        }

        // ---- X-Ladon ----
        byte[] prefix = Arrays.copyOfRange(nonce, 2, 6);
        byte[] digestHex = hex(md5(concat(prefix, utf8(aid)))).getBytes(
                java.nio.charset.Charset.forName("UTF-8"));
        long left = leU64(digestHex, 0);
        long right = leU64(digestHex, 8);
        long[] queue = new long[80];
        int qLen = 0;
        int qHead = 0;
        queue[qLen++] = leU64(digestHex, 16);
        queue[qLen++] = leU64(digestHex, 24);
        long[] keys = new long[34];
        for (int i = 0; i < 34; i++) {
            keys[i] = left;
            long mixed = (Long.rotateRight(right, 8) + left) ^ (long) i;
            queue[qLen++] = mixed;
            left = Long.rotateLeft(left, 3) ^ mixed;
            right = queue[qHead++];
        }
        byte[] ld = pad16(utf8(ts + "-1611921764-" + aid));
        for (int off = 0; off < ld.length; off += 16) {
            long l = leU64(ld, off);
            long r = leU64(ld, off + 8);
            for (int i = 0; i < keys.length; i++) {
                r = keys[i] ^ (l + Long.rotateRight(r, 8));
                l = Long.rotateLeft(l, 3) ^ r;
            }
            putLeU64(ld, off, l);
            putLeU64(ld, off + 8, r);
        }
        String ladon = base64(concat(prefix, ld));

        return new Result(argus, ladon);
    }

    // ---------------------------------------------------------------- crypto

    private static long[] buildRounds() {
        byte[] seed = concat(concat(ARGUS_KEY, new byte[]{0x3c, (byte) 0xcc, 0x56, 0x7b}), ARGUS_KEY);
        byte[] derived = HgSm3.digest(seed);
        long[] rounds = new long[72];
        for (int i = 0; i < 4; i++) {
            rounds[i] = leU64(derived, i * 8);
        }
        for (int i = 4; i < 72; i++) {
            long mixed = Long.rotateLeft(rounds[i - 1], 61) ^ rounds[i - 3];
            mixed ^= Long.rotateLeft(mixed, 63);
            long bits = (0x3dc94c3a046d678bL >>> ((i - 4) % 62)) & 1L;
            rounds[i] = (~rounds[i - 4] ^ mixed ^ bits ^ 3L);
        }
        return rounds;
    }

    // ---------------------------------------------------------------- helpers

    private static String get(Map<String, String> m, String key, String def) {
        String v = m == null ? null : m.get(key);
        return v == null || v.isEmpty() ? def : v;
    }

    private static byte[] utf8(String s) {
        return s.getBytes(java.nio.charset.Charset.forName("UTF-8"));
    }

    private static byte[] varint(long field, long value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeVarint(out, field, value);
        return out.toByteArray();
    }

    private static byte[] bytesField(long field, byte[] value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeBytes(out, field, value);
        return out.toByteArray();
    }

    private static void writeVarint(ByteArrayOutputStream out, long field, long value) {
        appendUvarint(out, field * 8);
        appendUvarint(out, value);
    }

    private static void writeBytes(ByteArrayOutputStream out, long field, byte[] value) {
        appendUvarint(out, field * 8 + 2);
        appendUvarint(out, value.length);
        out.write(value, 0, value.length);
    }

    private static void appendUvarint(ByteArrayOutputStream out, long v) {
        while (true) {
            int b = (int) (v & 0x7F);
            v >>>= 7;
            if (v != 0) {
                out.write(b | 0x80);
            } else {
                out.write(b);
                return;
            }
        }
    }

    private static byte[] pad16(byte[] data) {
        int padding = 16 - data.length % 16;
        byte[] out = new byte[data.length + padding];
        System.arraycopy(data, 0, out, 0, data.length);
        Arrays.fill(out, data.length, out.length, (byte) padding);
        return out;
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    private static long leU64(byte[] data, int off) {
        long v = 0;
        for (int i = 7; i >= 0; i--) {
            v = (v << 8) | (data[off + i] & 0xFFL);
        }
        return v;
    }

    private static void putLeU64(byte[] data, int off, long v) {
        for (int i = 0; i < 8; i++) {
            data[off + i] = (byte) (v >>> (i * 8));
        }
    }

    private static long leU32(byte[] data, int off) {
        return (data[off] & 0xFFL) | (data[off + 1] & 0xFFL) << 8
                | (data[off + 2] & 0xFFL) << 16 | (data[off + 3] & 0xFFL) << 24;
    }

    private static byte[] md5(byte[] input) {
        try {
            return MessageDigest.getInstance("MD5").digest(input);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] hex(String s) {
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    /** 标准 Base64（不换行），纯 JDK 实现以兼容 API 19 以下。 */
    private static String base64(byte[] data) {
        final String table = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
        StringBuilder sb = new StringBuilder((data.length + 2) / 3 * 4);
        int i = 0;
        while (i < data.length) {
            int b0 = data[i] & 0xFF;
            int b1 = i + 1 < data.length ? data[i + 1] & 0xFF : 0;
            int b2 = i + 2 < data.length ? data[i + 2] & 0xFF : 0;
            sb.append(table.charAt(b0 >> 2));
            sb.append(table.charAt(((b0 & 3) << 4) | (b1 >> 4)));
            sb.append(i + 1 < data.length ? table.charAt(((b1 & 15) << 2) | (b2 >> 6)) : '=');
            sb.append(i + 2 < data.length ? table.charAt(b2 & 63) : '=');
            i += 3;
        }
        return sb.toString();
    }
}
