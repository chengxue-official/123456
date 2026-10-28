package com.houg.tv.sign;

import java.io.ByteArrayOutputStream;
import java.security.SecureRandom;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * 红果评论（弹幕）接口专用签名，逐行还原自 guoapp native/core/provider_hongguo_comment_sign.go。
 * 与普通 X-Gorgon 不同：X-SS-STUB 恒为空，额外携带 X-Argus / X-Ladon，
 * 且请求头必须带 Comment-Source: 601、Server-Channel: 1000。
 */
public final class HongguoCommentSign {

    private static final SecureRandom RANDOM = new SecureRandom();

    private static final byte[] ARGUS_KEY = hexStringBytes(
            "ac1adaae95a7af94a5114ab3b3a97dd80050aa0a39314c40528caec95256c28c");

    private HongguoCommentSign() {
    }

    /** 签名结果（各请求头的值） */
    public static final class Result {
        public final String xGorgon;
        public final String xKhronos;
        public final String xArgus;
        public final String xLadon;
        public final String xReqTicket;

        Result(String xGorgon, String xKhronos, String xArgus, String xLadon, String xReqTicket) {
            this.xGorgon = xGorgon;
            this.xKhronos = xKhronos;
            this.xArgus = xArgus;
            this.xLadon = xLadon;
            this.xReqTicket = xReqTicket;
        }
    }

    /** 对评论接口签名。query 为已编码的 RawQuery（不含 _rticket 重写——调用方保证 query 与最终 URL 一致）。 */
    public static Result sign(String query, long nowMillis) {
        long stamp = nowMillis / 1000L;
        byte[] nonce = new byte[11];
        RANDOM.nextBytes(nonce);

        int head2 = ((nonce[0] & 0xFF) % 7 + 1) << 5;
        int head3 = 0xe0 | (nonce[1] & 15);

        byte[] seed = new byte[20];
        System.arraycopy(md5Bytes(query.getBytes(java.nio.charset.Charset.forName("UTF-8"))), 0, seed, 0, 4);
        seed[12] = 0;
        seed[13] = 1;
        seed[14] = 7;
        seed[15] = 4;
        putBigEndian(seed, 16, (int) stamp);

        byte[] key = {0x4a, 0, 0x16, (byte) head3, 0x47, 0x6c, 0, (byte) head2};
        rc4Crypt(seed, key);
        for (int i = 0; i < 20; i++) {
            int v = seed[i] & 0xFF;
            v = ((v << 4) | (v >>> 4)) & 0xFF;       // bits.RotateLeft8(v, 4)
            v = v ^ (seed[(i + 1) % 20] & 0xFF);
            v = Integer.reverse(v << 24) & 0xFF;      // bits.Reverse8
            seed[i] = (byte) ((v ^ 0xeb) & 0xFF);
        }

        byte[] prefix = {(byte) 0x84, 4, (byte) head2, (byte) head3, 0, 0};
        byte[] g = new byte[prefix.length + seed.length];
        System.arraycopy(prefix, 0, g, 0, prefix.length);
        System.arraycopy(seed, 0, g, prefix.length, seed.length);
        String xGorgon = hex(g);
        String xKhronos = String.valueOf(stamp);
        String xReqTicket = String.valueOf(nowMillis);
        String xArgus = commentArgus(query, (int) stamp, nonce);
        String xLadon = commentLadon("8662", (int) stamp, Arrays.copyOfRange(nonce, 2, 6));
        return new Result(xGorgon, xKhronos, xArgus, xLadon, xReqTicket);
    }

    /** RC4-style KSA + PRGA（对齐 Go：i 从 offset+1 开始，box 索引按字节回绕） */
    private static void rc4Crypt(byte[] seed, byte[] key) {
        int[] box = new int[256];
        for (int i = 0; i < 256; i++) box[i] = i;
        int j = 0;
        for (int i = 0; i < 256; i++) {
            j = (j + box[i] + (key[i & 7] & 0xFF)) & 0xFF;
            box[i] = box[j];  // 注意：Go 源码是 box[i]=box[j]，只赋值不交换（非标准 RC4）
        }
        j = 0;
        for (int offset = 0; offset < 20; offset++) {
            int i = (offset + 1) & 0xFF;
            j = (j + box[i]) & 0xFF;
            box[i] = box[j];  // 同样只赋值不交换
            seed[offset] ^= (byte) box[(box[i] + box[j]) & 0xFF];
        }
    }

    /* ---------------- X-Ladon ---------------- */

    private static String commentLadon(String aid, int timestamp, byte[] prefix) {
        byte[] md5Bytes = md5Bytes(concat(prefix, aid.getBytes(java.nio.charset.Charset.forName("UTF-8"))));
        // Go: digest := []byte(hex.EncodeToString(hash[:])) —— 是 hex 字符串的 ASCII 字节（32B），不是解析后的原始字节
        byte[] digestHex = hex(md5Bytes).getBytes(java.nio.charset.Charset.forName("UTF-8"));

        long left = leU64(digestHex, 0);
        long right = leU64(digestHex, 8);
        long[] queue = new long[64];
        int qLen = 0;
        queue[qLen++] = leU64(digestHex, 16);
        queue[qLen++] = leU64(digestHex, 24);
        int qHead = 0;
        long[] keys = new long[34];
        for (int i = 0; i < 34; i++) {
            keys[i] = left;
            long mixed = ((Long.rotateRight(right, 8)) + left) ^ ((long) i & 0xFFFFFFFFL);
            mixed &= 0xFFFFFFFFFFFFFFFFL;
            queue[qLen++] = mixed;
            left = (mixed ^ Long.rotateLeft(left, 3)) & 0xFFFFFFFFFFFFFFFFL;
            right = queue[qHead++];
        }
        byte[] data = pad16((timestamp + "-1611921764-3019")
                .getBytes(java.nio.charset.Charset.forName("UTF-8")));
        for (int off = 0; off < data.length; off += 16) {
            long l = leU64(data, off);
            long r = leU64(data, off + 8);
            for (int i = 0; i < 34; i++) {
                r = keys[i] ^ ((l + Long.rotateRight(r, 8)) & 0xFFFFFFFFFFFFFFFFL);
                l = r ^ Long.rotateLeft(l, 3);
            }
            putLeU64(data, off, l);
            putLeU64(data, off + 8, r);
        }
        byte[] out = new byte[prefix.length + data.length];
        System.arraycopy(prefix, 0, out, 0, prefix.length);
        System.arraycopy(data, 0, out, prefix.length, data.length);
        // java.util.Base64 是 API 26+ 才有，Android 7 以下会 NoClassDefFoundError 闪退
        // （日志实锤：MagicBox_M16S API 22 拉弹幕即崩）。统一用 android.util.Base64。
        return android.util.Base64.encodeToString(out, android.util.Base64.NO_WRAP);
    }

    /* ---------------- X-Argus ---------------- */

    private static String commentArgus(String query, int timestamp, byte[] nonce) {
        ByteArrayOutputStream message = new ByteArrayOutputStream();

        varint(message, 1, 0x20200929L * 2);
        varint(message, 2, 2);
        varint(message, 3, leU32(nonce, 6) & 0xFFFFFFFFL);
        bytesField(message, 4, new byte[]{'3', '0', '1', '9'});
        bytesField(message, 6, new byte[]{'1', '6', '1', '1', '9', '2', '1', '7', '6', '4'});
        bytesField(message, 7, new byte[]{'6', '.', '8', '.', '1', '.', '3', '2'});
        bytesField(message, 8, "v04.07.01-ml-android".getBytes(java.nio.charset.Charset.forName("UTF-8")));
        varint(message, 9, 135135744L);
        bytesField(message, 10, new byte[8]);
        varint(message, 12, (timestamp & 0xFFFFFFFFL) * 2);
        byte[] bodyHash = HongguoSm3.digest(new byte[16]);
        byte[] queryHash = HongguoSm3.digest(query.getBytes(java.nio.charset.Charset.forName("UTF-8")));
        bytesField(message, 13, Arrays.copyOfRange(bodyHash, 0, 6));
        bytesField(message, 14, Arrays.copyOfRange(queryHash, 0, 6));
        ByteArrayOutputStream nested = new ByteArrayOutputStream();
        nested.write(8);
        nested.write(2 + (nonce[10] & 0xFF) % 49 * 2);
        for (int field : new int[]{2, 3, 4}) {
            varint(nested, field, 1388734L);
        }
        bytesField(message, 15, nested.toByteArray());
        bytesField(message, 20, new byte[]{'n', 'o', 'n', 'e'});
        varint(message, 21, 738);

        byte[] data = pad16(message.toByteArray());
        byte[] seed = concat(concat(ARGUS_KEY, new byte[]{0x3c, (byte) 0xcc, 0x56, 0x7b}), ARGUS_KEY);
        byte[] derived = HongguoSm3.digest(seed);
        long[] rounds = new long[72];
        for (int i = 0; i < 4; i++) {
            rounds[i] = leU64(derived, i * 8);
        }
        for (int i = 4; i < 72; i++) {
            long mixed = Long.rotateLeft(rounds[i - 1], 61) ^ rounds[i - 3]; // rotl(x,-3)
            mixed ^= Long.rotateLeft(mixed, 63);                              // rotl(mixed,-1)
            long bits = (0x3dc94c3a046d678bL >>> ((i - 4) % 62)) & 1L;
            rounds[i] = (~rounds[i - 4] ^ mixed ^ bits ^ 3L) & 0xFFFFFFFFFFFFFFFFL;
        }
        for (int off = 0; off < data.length; off += 16) {
            long l = leU64(data, off);
            long r = leU64(data, off + 8);
            for (int i = 0; i < 72; i++) {
                long nr = r;
                r = l ^ ((Long.rotateLeft(r, 1) & Long.rotateLeft(r, 8)) ^ Long.rotateLeft(r, 2)) ^ rounds[i];
                l = nr;
            }
            putLeU64(data, off, l);
            putLeU64(data, off + 8, r);
        }
        byte[] xor = {(byte) 0xd0, 0x4f, (byte) 0xfd, (byte) 0xff,
                (byte) 0xd0, 0x4f, (byte) 0xfd, (byte) 0xff};
        for (int i = 0; i < data.length; i++) {
            data[i] ^= xor[i % 8];
        }
        data = concat(xor, data);
        for (int l = 0, r = data.length - 1; l < r; l++, r--) {
            byte t = data[l]; data[l] = data[r]; data[r] = t;
        }
        ByteArrayOutputStream container = new ByteArrayOutputStream();
        byte[] header = {(byte) 0xa6, (byte) 0xe7, (byte) 0x83, (byte) 0xee,
                0x70, 0x01, 0x10, 0x09, 0x18};
        container.write(header, 0, header.length);
        container.write(data, 0, data.length);
        container.write(0x56);
        container.write(0x7b);
        byte[] padded = pad16(container.toByteArray());

        try {
            byte[] aesKey = md5Bytes(Arrays.copyOfRange(ARGUS_KEY, 0, 16));
            byte[] iv = md5Bytes(Arrays.copyOfRange(ARGUS_KEY, 16, 32));
            Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(aesKey, "AES"), new IvParameterSpec(iv));
            byte[] encrypted = cipher.doFinal(padded);
            byte[] out = new byte[2 + encrypted.length];
            out[0] = 0x3c;
            out[1] = (byte) 0xcc;
            System.arraycopy(encrypted, 0, out, 2, encrypted.length);
            // 同上：android.util.Base64 兼容 API 19+（NO_WRAP 无换行，等价 java.util 标准编码）
            return android.util.Base64.encodeToString(out, android.util.Base64.NO_WRAP);
        } catch (Exception e) {
            throw new IllegalStateException("X-Argus 加密失败", e);
        }
    }

    /* ---------------- 工具 ---------------- */

    private static void varint(ByteArrayOutputStream out, long field, long value) {
        appendUvarint(out, field * 8);
        appendUvarint(out, value);
    }

    private static void bytesField(ByteArrayOutputStream out, long field, byte[] value) {
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

    private static void putBigEndian(byte[] dst, int off, int v) {
        dst[off] = (byte) (v >>> 24);
        dst[off + 1] = (byte) (v >>> 16);
        dst[off + 2] = (byte) (v >>> 8);
        dst[off + 3] = (byte) v;
    }

    private static byte[] md5Bytes(byte[] input) {
        try {
            return java.security.MessageDigest.getInstance("MD5").digest(input);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] hexStringBytes(String hex) {
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
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
}
