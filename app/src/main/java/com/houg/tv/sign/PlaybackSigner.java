package com.houg.tv.sign;

import java.io.ByteArrayOutputStream;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 红果播放接口签名栈（x-gorgon / x-helios / x-medusa）。
 *
 * <p>思路来自 HGTV（GPL-3.0）的 drpys 还原结果，这里是手写 Java 重实现，逐处对齐其运算语义：
 * <ul>
 *   <li>query 用 LinkedHashMap 保序编码，不排序；</li>
 *   <li>{@link #branch} 命中 0 号分支才出签名，因此 {@link #sign} 会在 ticket 上做 0..127 的抖动；</li>
 *   <li>hashF13 依赖 SM3 + MD5 + 类 SHA 扩展轮，AesV3 是协议自定义变换（非标准 AES）。</li>
 * </ul>
 *
 * <p>纯 JDK 实现（无 Android / BouncyCastle 依赖），可直接用 javac 跑 signing.json 对拍。
 */
public final class PlaybackSigner {

    public static final String VIDEO_UA =
            "com.phoenix.read/73932 (Linux; U; Android 16; zh_CN; 25053RT47C; Build/BP2A.250605.031.A3; "
                    + "Cronet/TTNetVersion:04657795 2026-01-23 QuicVersion:c67e9834 2025-09-08)";
    public static final String APP_UA =
            "com.phoenix.read/73932 (Linux; U; Android 16; zh_CN; 25053RT47C; Build/BP2A.250605.031.A3)";

    /** 播放器版本档案（27 字段，顺序即签名顺序，绝不能改成 TreeMap）。 */
    public static LinkedHashMap<String, String> defaultDeviceProfile() {
        LinkedHashMap<String, String> m = new LinkedHashMap<>();
        m.put("iid", "1905892595382586");
        m.put("device_id", "1905892595378490");
        m.put("ac", "wifi");
        m.put("channel", "update_64");
        m.put("aid", "8662");
        m.put("app_name", "novelread");
        m.put("version_code", "73932");
        m.put("version_name", "7.3.9.32");
        m.put("device_platform", "android");
        m.put("os", "android");
        m.put("ssmix", "a");
        m.put("device_type", "25053RT47C");
        m.put("device_brand", "Redmi");
        m.put("language", "zh");
        m.put("os_api", "36");
        m.put("os_version", "16");
        m.put("manifest_version_code", "73932");
        m.put("resolution", "1280*2772");
        m.put("dpi", "520");
        m.put("update_version_code", "73932");
        m.put("host_abi", "arm64-v8a");
        m.put("dragon_device_type", "phone");
        m.put("pv_player", "73932");
        m.put("compliance_status", "0");
        m.put("need_personal_recommend", "1");
        m.put("player_so_load", "1");
        m.put("is_android_pad_screen", "0");
        return m;
    }

    /** 设备档案内置兜底值（x-medusa 的 deviceProto 会读它）。 */
    private static final Map<String, String> BUILTIN = defaultDeviceProfile();

    /** AesV3 用的 1024 字节替换盒。 */
    private static final byte[] SBOX = decode64(
            "+n0Ia5xZs0sEXznQOEqRmQBnpiCf9U2Ccybu3xhmgzOAAxn72f6uqqmwUsYL83klTni0NqxdGieeiNu9PGPsSRXBMB/cuFbUbM3KCUPINaPv"
                    + "HvSW0vwOcnuUhNHqRVpiAj/TEoE0K91+5ijypUYTATsh9mE3KSoN7Yyvv51cuyR2D3XkU4nhmI2xmmVwT1RMWKtub4sjxAcRDLrP"
                    + "oKSO2AU9FLLadMPX577Wf95IFj6FkKFVt3dCIsmGUC4X+WQxLJvxbRxEaOPpqJOXyzJX6+Vxaq3AzMfF/WAdoi1Hp+JRaV56zgpB"
                    + "tpWP97mH4DoGEIq1+FvV8LyS/3wvwugbQOwb2r26mJEMsiuDQTRn+wrYdrVGBVlhI3WQhyrjUBVMrLF5667llUcEaPCGPVGLD8qO"
                    + "5LlO8hKCvA7V9+8oJc9bXelqVQLhM76T5/WtnT45JKji+hdX0HoNCDDWuKON/QeaxB5uImSX0h2wv0VmP2zd2yeApxHcpsVS+MC2"
                    + "yFwAc2B7oBkTqsk1SEvTpM2fmfMQREBUfin0Bh+iq6EvPPavhWI2IX9e3yAas7Tm/3KEj2UmlFp36kN4x0rMLBRrxuh0U/zUHM4x"
                    + "cAMYjJY4MonxOl/X+alpt2M3WMI7w3HLnpIBigtNiJu7T21v4P6lSd5WFgntnMEt7oF92XzRLkJbTcGmXepE/UVOG6E/0YnhfS+q"
                    + "26utWcuxzpooyeD2cDlK1/8w9d28VzsRjbLuALbmGlp8+d7EzS6Au7lMpZ+ECMZvQmzwJ+eLOpxR+2chdUExp8ogQyq3v9l68rX4"
                    + "jCwjg0+PYKAEEzcU4wHFY2ZcdIHfWL1okD3SszT0GZMyKdZJrg1L2AeerB4tC0C4crp2EHGo5FYdSP7lwkeR2ocmnR+Ia8CYviUJ"
                    + "lzOjhRZef9xuVOn3qcjow3fQgivsAmKKkg4+sA8F8/GWeDiGNhg8JM8KtFPMYWWkx5TVFX5t73kiNRJqjlIGVXtGZFCV4gzt0xcD"
                    + "opuZ6xz8r9Rzafpf9ywev8jh8592gHFIqpStZPuJxmDDMrNN0uBE3V+oscdoIzTJbRJ/t+sVvqnReJOgDJKk10fjisJwqyZBmnmn"
                    + "2BSFj8BvVtCMEbkuPOKdzw7eA11GPs04Qw8zWtkaZWwiO/wwpojqN6K0jY5RnNZA7vn4hPSul+nKCkVnVwQvg1zVxcSCtqORmB9K"
                    + "rJaBbssbCQivGJVJfVTt+hYxOtq4ZvWl8f4QAQZ0zGPffCgl9s6yT4vlvIdpu4YhBwA25wtQWZsc6GJYGWHyvSdeuh3mmUI9DSq13"
                    + "Fsp8C1MU3tqc04/df9LoTUXVXI5INOw/e8C7Hd+5CvbkMEFnnrUUmskEw==");

    /** hashF13 的轮常数表（64 项）。 */
    private static final int[] HASH_TABLE = {
            0xebb64faf, 0x07aadcc2, 0xcf3187bf, 0xe01138ff, 0x6d0bfcff, 0x5a30a3be, 0xb41ad638, 0x34180eb8,
            0xf233eb6f, 0xb1a584cc, 0xccc30dc7, 0x47d1db51, 0xd55653de, 0x70a84fa1, 0x57473c12, 0xf76f0288,
            0x2c077f0a, 0xda0dcad0, 0xfbb86f6c, 0xfdc4cf00, 0x688a020d, 0xe676c6a6, 0x8cd6338b, 0x1a3c8d0e,
            0xcce8b06b, 0x6ad0ed0b, 0xa0522717, 0xdc71ac83, 0x2285db71, 0xd5b4dda6, 0x736f8650, 0x6560306c,
            0x617ce2a6, 0xe423417e, 0x0a40e143, 0x544e4032, 0x88dffb2a, 0x716c1ae0, 0x4c467a88, 0x05b23bb3,
            0xe1d0b866, 0xbaa3dcb8, 0xae3374d3, 0xc3381a50, 0x1702f75b, 0xfe6da368, 0xf0b4cf48, 0x4e0ffbb8,
            0x72aad10d, 0x26c53a3d, 0xf2bce0f6, 0xb4557581, 0x4a257fdd, 0x8c3182a2, 0xab0b3b86, 0x3d5dfb14,
            0x4f103634, 0xd37b52d7, 0x444eff16, 0xeb0a33d1, 0x6ca86f6e, 0x0284ba7, 0x08387cfa, 0x5fb37586
    };

    private static final int[] IV_V1 = {
            0xc4a78580, 0xb3c0fd39, 0xc58c5686, 0xc9aa3ba7, 0xf5a7adf2, 0x963c2ed1
    };

    private static final int[][] CHOSEN = {
            {101, 5, 7, 6, 3, 2, 1, 0, 5, 4, 3},
            {96, 0, 6, 7, 5, 3, 2, 1, 5, 4, 4},
            {96, 7, 6, 2, 1, 4, 0, 5, 4, 3, 5},
            {99, 3, 6, 2, 4, 5, 1, 0, 0, 7, 6},
            {96, 0, 5, 6, 7, 3, 1, 2, 5, 4, 4},
            {100, 2, 0, 3, 5, 4, 6, 7, 2, 1, 5}
    };

    private static final int[] INITIAL_V = {
            0x7aba4fc8, 0x67166507, 0x6403fa00, 0x340f512f, 984304912,
            (int) 3005047866L, (int) 2874125293L, (int) 2152413264L
    };

    /** 已签名的请求。 */
    public static final class Request {
        public final String url;
        public final LinkedHashMap<String, String> headers;
        public final byte[] body;

        Request(String url, LinkedHashMap<String, String> headers, byte[] body) {
            this.url = url;
            this.headers = headers;
            this.body = body;
        }
    }

    /** 随机源：返回 [0,1)。生产用 Math.random，对拍用固定种子 LCG。 */
    public interface Random01 {
        double next();
    }

    private final Random01 random;
    private final Clock clock;
    private Map<String, String> device = new LinkedHashMap<>();

    public interface Clock {
        long now();
    }

    public PlaybackSigner(Random01 random, Clock clock) {
        this.random = random;
        this.clock = clock;
    }

    public PlaybackSigner() {
        this(Math::random, System::currentTimeMillis);
    }

    /** 更新设备档案；空白值丢弃；缺项回落内置档案。 */
    public void updateDevice(Map<String, String> values) {
        LinkedHashMap<String, String> kept = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : values.entrySet()) {
            if (e.getValue() != null && !e.getValue().trim().isEmpty()) {
                kept.put(e.getKey(), e.getValue());
            }
        }
        device = kept;
    }

    public Map<String, String> getDevice() {
        return device;
    }

    // ---------------------------------------------------------------- 基础工具

    private static int u(byte b) {
        return b & 255;
    }

    private static byte[] hex(String s) {
        int n = s.length() / 2;
        byte[] out = new byte[n];
        for (int i = 0; i < n; i++) {
            out[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    private static String hex(byte[] data) {
        StringBuilder sb = new StringBuilder(data.length * 2);
        for (byte b : data) {
            sb.append(Character.forDigit((b >> 4) & 15, 16)).append(Character.forDigit(b & 15, 16));
        }
        return sb.toString();
    }

    private static byte[] decode64(String s) {
        return B64.decode(s.replace('-', '+').replace('_', '/'));
    }

    private static String base64(byte[] data) {
        return B64.encodeToString(data);
    }

    private static byte[] le32(int n) {
        return ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(n).array();
    }

    private static byte[] be32(int n) {
        return ByteBuffer.allocate(4).putInt(n).array();
    }

    private static byte[] le64(long n) {
        return ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(n).array();
    }

    private static int i32(byte[] b, int at, boolean little) {
        return ByteBuffer.wrap(b, at, 4).order(little ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN).getInt();
    }

    private static long i64(byte[] b, int at, boolean little) {
        return ByteBuffer.wrap(b, at, 8).order(little ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN).getLong();
    }

    private static byte[] md5(byte[] v) {
        return digest("MD5", v);
    }

    private static byte[] digest(String algo, byte[] v) {
        try {
            return MessageDigest.getInstance(algo).digest(v);
        } catch (Exception e) {
            throw new IllegalStateException(algo + " 不可用", e);
        }
    }

    private static byte[] sm3(byte[] v) {
        return HongguoSm3.digest(v);
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] p : parts) {
            out.write(p, 0, p.length);
        }
        return out.toByteArray();
    }

    private static byte[] xorBytes(byte[] a, byte[] b) {
        byte[] out = new byte[a.length];
        for (int i = 0; i < a.length; i++) {
            out[i] = (byte) (u(a[i]) ^ u(b[i % b.length]));
        }
        return out;
    }

    /** (random() * 2^32) 取低 32 位；与 Kotlin rand() 的 toLong().toInt() 一致。 */
    private int rand() {
        return (int) (long) (random.next() * 4294967296.0);
    }

    private double rnd() {
        return random.next();
    }

    // ---------------------------------------------------------------- 设备档案取值

    private String deviceValue(String key, String fallback) {
        String v = device.get(key);
        return (v != null && !v.trim().isEmpty()) ? v : fallback;
    }

    private String deviceId() {
        return deviceValue("device_id", BUILTIN.get("device_id"));
    }

    private String deviceType() {
        return deviceValue("device_type", "25053RT47C");
    }

    private String deviceBrand() {
        return deviceValue("device_brand", "Redmi");
    }

    private String versionName() {
        return deviceValue("version_name", BUILTIN.get("version_name"));
    }

    private String manufacturer() {
        String brand = deviceBrand();
        String lower = brand.toLowerCase();
        if (lower.contains("samsung")) {
            return "Samsung";
        }
        if (lower.contains("redmi") || lower.contains("xiaomi")) {
            return "Xiaomi";
        }
        return brand;
    }

    // ---------------------------------------------------------------- protobuf 变体

    private static final class Field {
        final int tag;
        final Object value;
        final String type;

        Field(int tag, Object value, String type) {
            this.tag = tag;
            this.value = value;
            this.type = type;
        }
    }

    private static Field fS(int tag, long value) {
        return new Field(tag, value, "sint");
    }

    private static Field f(int tag, String value) {
        return new Field(tag, value, "string");
    }

    private static Field fB(int tag, byte[] value) {
        return new Field(tag, value, "bytes");
    }

    private static Field fF(int tag, double value) {
        return new Field(tag, value, "float");
    }

    private static byte[] varint(long value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        long n = value;
        while (n > 127) {
            out.write((int) ((n & 127) | 128));
            n >>>= 7;
        }
        out.write((int) n);
        return out.toByteArray();
    }

    private static byte[] proto(Field... fields) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (Field field : fields) {
            if ("sint".equals(field.type)) {
                long n = ((Number) field.value).longValue();
                byte[] head = varint(field.tag << 3);
                out.write(head, 0, head.length);
                byte[] body = varint(n < 0 ? (-n * 2 - 1) : (n * 2));
                out.write(body, 0, body.length);
            } else if ("float".equals(field.type)) {
                byte[] head = varint((field.tag << 3) | 5);
                out.write(head, 0, head.length);
                byte[] bits = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
                        .putFloat(((Number) field.value).floatValue()).array();
                out.write(bits, 0, bits.length);
            } else {
                byte[] bytes = field.value instanceof byte[]
                        ? (byte[]) field.value
                        : field.value.toString().getBytes(StandardCharsets.UTF_8);
                if (bytes.length == 0) {
                    continue;
                }
                byte[] head = varint((field.tag << 3) | 2);
                byte[] len = varint(bytes.length);
                out.write(head, 0, head.length);
                out.write(len, 0, len.length);
                out.write(bytes, 0, bytes.length);
            }
        }
        return out.toByteArray();
    }

    // ---------------------------------------------------------------- iv / branch / hash

    private static int getIv(int initial, byte[] data) {
        int value = initial;
        for (int i = 0; i < data.length; i++) {
            if ((i & 1) == 0) {
                value = (value >>> 4) ^ value ^ (value << 6) ^ u(data[i]);
            } else {
                value = ~((value >>> 7) ^ value ^ (u(data[i]) | (value << 12)));
            }
        }
        return value;
    }

    private static int iv(byte[] query, byte[] body, int ts) {
        return getIv(getIv(getIv(0x20230928, query), body), le32(ts));
    }

    /** 哈希分支；只有 0 号分支被本协议实现支持，所以签名时要抖动 ticket。 */
    public int branch(String query, byte[] body, int ts) {
        byte[] q = sm3(query.getBytes(StandardCharsets.UTF_8));
        byte[] b = body == null ? new byte[16] : md5(body);
        return (iv(q, b, ts) & 15) % 3;
    }

    private static int sumMd5(byte[] data) {
        int check = 0x20220420;
        for (int i = 0; i < 12; i++) {
            int temp = ((i & 1) == 0 ? check >>> 3 : check >>> 5) ^ check;
            if ((i & 1) == 0) {
                check = u(data[i]) ^ (check << 7);
            } else {
                check = ~(u(data[i]) | (check << 11));
            }
            check ^= temp;
        }
        return (check | 4) ^ 0x1000000;
    }

    /** 20 字节哈希体（x-medusa 的 f13）。query/body 必须是已 hash 的字节数组。 */
    public byte[] hashF13(byte[] query, byte[] body, int ts) {
        int ivValue = iv(query, body, ts);
        int index = (ivValue & 15) / 3;
        if ((ivValue & 15) % 3 != 0) {
            throw new IllegalArgumentException("不支持的哈希分支");
        }
        int ivV1 = IV_V1[index];
        int count = ivV1 + ts;
        int[] tt = new int[HASH_TABLE.length];
        for (int i = 0; i < HASH_TABLE.length; i++) {
            tt[i] = Integer.rotateRight(HASH_TABLE[i], (count + 1) & 255);
        }
        int shift = (count + 2) & 7;
        int[] padConst = {0xfa, 0x45, 0x61, 0xd7};
        byte[] pad = new byte[4];
        for (int i = 0; i < 4; i++) {
            pad[i] = (byte) ((padConst[i] | (padConst[i] << 8)) >> shift);
        }
        byte[] data = concat(query, body, le32(ts), pad, hex("00000000000001a0"));
        List<Integer> di = new ArrayList<>();
        for (int at = 0; at + 4 <= data.length; at += 4) {
            di.add(i32(data, at, false));
        }
        for (int i = 0; i < 112; i++) {
            int x = di.get(i + 1);
            int y = di.get(i + 14);
            di.add(di.get(i) + di.get(i + 9)
                    + (Integer.rotateLeft(x, 14) ^ Integer.rotateLeft(x, 25) ^ (x >>> 3))
                    + (Integer.rotateLeft(y, 13) ^ Integer.rotateLeft(y, 15) ^ (y >>> 10)));
        }
        int[] initial = new int[8];
        for (int i = 0; i < 8; i++) {
            initial[i] = Integer.rotateRight(INITIAL_V[i], count);
        }
        int[] ch = CHOSEN[index];
        List<Integer> d = new ArrayList<>(8);
        for (int v : initial) {
            d.add(v);
        }
        for (int i = 0; i < ch[0]; i++) {
            int base = ivV1 + i;
            int d1 = d.get(ch[1]);
            int d2 = d.get(ch[2]);
            int d3 = d.get(ch[3]);
            int d6 = d.get(ch[6]);
            int d7 = d.get(ch[7]);
            int n1 = ((d3 ^ d.get(ch[4])) & d1) ^ d3;
            int n2 = Integer.rotateLeft(d1, 26) ^ Integer.rotateLeft(d1, 21) ^ Integer.rotateLeft(d1, 7);
            int n4 = di.get(base & 127) + n1 + n2 + tt[base & 63] + d.get(ch[5]);
            int n5 = Integer.rotateLeft(d2, 30) ^ Integer.rotateLeft(d2, 19) ^ Integer.rotateLeft(d2, 10);
            int n6 = (d2 & d6) | ((d2 | d6) & d7);
            int old = d.get(ch[9]);
            int moved = d.remove(7);
            d.add(0, moved);
            d.set(ch[10], n5 + n6 + n4);
            d.set(ch[8], old + n4);
        }
        byte[] ret = new byte[32];
        for (int i = 0; i < 8; i++) {
            System.arraycopy(be32(d.get(i) + initial[i]), 0, ret, i * 4, 4);
        }
        byte[] front = new byte[16];
        byte[] back = new byte[16];
        System.arraycopy(ret, 0, front, 0, 16);
        System.arraycopy(ret, 16, back, 0, 16);
        byte[] folded = new byte[16];
        for (int i = 0; i < 16; i++) {
            folded[i] = (byte) (front[i] ^ back[i]);
        }
        return concat(folded, le32(sumMd5(folded)));
    }

    // ---------------------------------------------------------------- x-gorgon

    public String gorgon(String query, byte[] body, int ts, int randomValue) {
        byte[] q4 = new byte[4];
        System.arraycopy(md5(query.getBytes(StandardCharsets.UTF_8)), 0, q4, 0, 4);
        byte[] b4;
        if (body == null) {
            b4 = new byte[4];
        } else {
            b4 = new byte[4];
            System.arraycopy(md5(body), 0, b4, 0, 4);
        }
        byte[] input = concat(q4, b4, new byte[4], le32(67503104), be32(ts));
        int[] key = {0x4a, 0x40, 0x16, (randomValue >> 8) & 255, 0x47, 0x6c, 1, randomValue & 255};
        int[] s = new int[256];
        for (int i = 0; i < 256; i++) {
            s[i] = i;
        }
        int j = 0;
        for (int i = 0; i < 256; i++) {
            j = (j + s[i] + key[i % 8]) & 255;
            s[i] = s[j];
        }
        byte[] out = new byte[input.length];
        j = 0;
        for (int n = 0; n < input.length; n++) {
            int i = n + 1;
            j += s[i];
            int y = s[j & 255];
            s[i] = y;
            out[n] = (byte) (u(input[n]) ^ s[(y + y) & 255]);
        }
        for (int i = 0; i < out.length; i++) {
            int value = ((u(out[i]) >>> 4) | (u(out[i]) << 4)) & 255;
            int next = u(out[(i + 1 < out.length) ? i + 1 : 0]);
            int reversed = Integer.reverse((next ^ value) & 255) >>> 24;
            out[i] = (byte) (~(reversed ^ 20));
        }
        return hex(concat(hex("8404"),
                new byte[]{(byte) (int) randomValue, (byte) (randomValue >> 8), 0x40, 1}, out));
    }

    // ---------------------------------------------------------------- x-medusa

    private byte[] deviceProto() {
        String osApi = deviceValue("os_api", "36");
        int osApiValue;
        try {
            osApiValue = Integer.parseInt(osApi.trim());
        } catch (NumberFormatException e) {
            osApiValue = 36;
        }
        return proto(
                fS(1, 1),
                fS(2, 2),
                f(3, "8662"),
                f(4, deviceId()),
                f(5, "Ai6svO3PyrwDOUSmO6ZcResxu"),
                f(6, "!noperm!"),
                fS(7, -888888L),
                fS(8, -888888L),
                fS(9, 3),
                fS(10, -888888L),
                f(11, "!notset!"),
                f(12, "Asia/Shanghai,8"),
                f(13, "zh_CN"),
                fS(14, 4),
                fF(16, 255.24993896484375d),
                fF(17, 35.58599090576172d),
                fF(18, 3.467449188232422d),
                fF(19, 3.467449188232422d),
                fF(20, 255.1754913330078d),
                fF(21, 42.17544174194336d),
                f(22, deviceValue("os_version", "16")),
                fS(23, 41),
                fS(24, osApiValue),
                fS(25, 1728388016635L),
                fS(26, 1728388016635L),
                fS(27, 1728388016635L),
                fS(28, 1728388016637L),
                fS(29, -1),
                f(30, deviceType()),
                f(31, deviceBrand()),
                f(32, deviceType()),
                f(33, deviceType()),
                f(34, manufacturer()),
                f(35, deviceBrand()),
                f(36, deviceBrand()),
                fS(38, 31));
    }

    private static byte[] xmxor(byte[] data, byte[] key) {
        int size = data.length;
        int[] encoded = new int[size];
        for (int i = 0; i < size; i++) {
            int at = (i * 4) & 28;
            int d0 = u(key[at]);
            int d1 = u(key[at + 1]);
            int v = u(data[i]);
            int d2 = (((v << 4) | (v >>> 4)) & 255) + d0;
            d2 = ~d2 ^ d1;
            d2 = ((((d2 & 255) << 3) | ((d2 & 255) >>> 5)) & 255);
            d2 = (d2 + d1) & 255;
            d2 = (d2 ^ d0) & 255;
            encoded[size - i - 1] = ~d2 & 255;
        }
        int last = encoded[size - 1] ^ encoded[size - 2];
        int first = encoded[0];
        encoded[0] = (~last + first) & 255;
        encoded[1] = ((encoded[0] ^ encoded[size - 1] ^ 254) + encoded[1]) & 255;
        encoded[2] = (encoded[2] + ((last - first)
                ^ (((encoded[1] << 3) | (encoded[1] >>> 5)) & 255) ^ 2)) & 255;
        for (int i = 0; i < size - 4; i++) {
            int temp = ((((encoded[i + 2] << 3) | (encoded[i + 2] >>> 5)) & 255)
                    ^ encoded[i + 1] ^ (i + 3));
            encoded[i + 3] = (~temp + encoded[i + 3]) & 255;
        }
        encoded[size - 1] = encoded[size - 1] ^ encoded[size - 2];
        int tailSum = 0;
        for (int i = 1; i < size; i++) {
            tailSum += encoded[i];
        }
        encoded[0] = (((encoded[0] ^ encoded[1]) + tailSum) & 255);
        byte[] out = new byte[size];
        for (int i = 0; i < size; i++) {
            out[i] = (byte) encoded[i];
        }
        return out;
    }

    public String medusa(String url, byte[] body, int ts) {
        byte[] bodyMd5 = body == null ? new byte[16] : md5(body);
        int qAt = url.indexOf('?');
        String queryString = qAt < 0 ? url : url.substring(qAt + 1);
        byte[] query = queryString.getBytes(StandardCharsets.UTF_8);
        byte[] querySm3 = sm3(query);
        byte[] queryBodyTs = hashF13(querySm3, bodyMd5, ts);
        byte[] nested = proto(fS(1, 111), fS(2, 10), fS(3, 694367), fS(5, 586952199));
        long messageRand = rand() & 0xffffffffL;
        int envLaunch = (int) (rnd() * 21) + 100;
        int envPid = (int) (rnd() * 2000) + 10001;
        String version = versionName();
        byte[] env = proto(
                fS(1, envLaunch),
                fS(2, 146331399),
                fS(3, 146331396),
                fS(5, 7),
                f(6, "v04.06.04.03-bugfix"),
                fS(7, envPid),
                fB(12, deviceProto()),
                fB(13, proto(fS(1, clock.now() / 1000), fS(2, -2), fS(4, 200))),
                f(14, version));
        byte[] queryHash = sm3(concat(query, bodyMd5, "none".getBytes(StandardCharsets.UTF_8)));
        byte[] message = proto(
                fB(1, hex("f7e85ffad7d7dc3bd62ac87057cf6118")),
                fS(2, 3),
                fS(3, messageRand),
                f(4, "8662"),
                f(5, deviceId()),
                f(6, "1588093228"),
                f(7, version),
                f(8, "v04.06.04-ml-android"),
                fS(9, 67503104),
                fB(10, hex("4001000000000000")),
                fS(12, ts),
                fB(13, queryBodyTs),
                fB(14, java.util.Arrays.copyOf(querySm3, 6)),
                fB(15, nested),
                f(16, "AXYQOS6n2m60x1fVZHIrH3iol"),
                fS(17, ts),
                fB(19, queryHash),
                f(20, "none"),
                fS(21, 312),
                fB(23, env),
                f(24, "{\"cmr\":16777216,\"cmr2\":16777216,\"un_h\":1879194040,\"vpn\":0,\"kd\":0,"
                        + "\"fkd\":3672518972,\"pd\":-1872573247,\"dyn\":\"\",\"do\":0,\"tk\":true}"));
        int r = rand();
        byte[] signKey = hex("8ebdfa3806ecc5cee79423e6029ed82540bc2218bb7eaef71cb691f7aa8aa2f5");
        byte[] hash = sm3(concat(signKey, le32(r), signKey));
        int d1 = (r >> 16) & 255;
        byte[] seed = le32(~(((d1 << 11) | (r >>> 24)) ^ (d1 >> 5) ^ d1));
        byte[] transformed = concat(hex("4001000000000000"), xmxor(message, hash));
        for (int i = 0, n = transformed.length; i < n / 2; i++) {
            byte t = transformed[i];
            transformed[i] = transformed[n - 1 - i];
            transformed[n - 1 - i] = t;
        }
        for (int i = 0; i < transformed.length; i++) {
            transformed[i] = (byte) (u(transformed[i]) ^ u(seed[(~i) & 3]));
        }
        int check = ((u(querySm3[0]) & 63) << 14) | 0x18000001 | ((u(queryBodyTs[0]) & 63) << 8);
        byte[] packed = concat(new byte[]{0x35}, le32(rand()), le32(check), transformed,
                new byte[]{(byte) (r >> 16), (byte) (r >> 24)});
        byte[] encrypted = new AesV3(hex("f1593376766ea98d34f31b057a9d5be4"), ts)
                .encrypt(packed, hex("1fe109a4125283f418de9e051a969e12"));
        byte[] versionBytes = hex("03000000f7e85ffad7d7dc3bd62ac87057cf6118");
        ByteArrayOutputStream prefix = new ByteArrayOutputStream();
        for (int i = 0; i < 20; i += 4) {
            byte[] b = le32(i32(versionBytes, i, true) ^ ts);
            prefix.write(b, 0, b.length);
        }
        return base64(concat(prefix.toByteArray(),
                new byte[]{(byte) r, (byte) (r >> 8), 0, 1}, encrypted));
    }

    // ---------------------------------------------------------------- x-helios

    public String helios(int ts) {
        int randomValue = rand();
        byte[] ascii = hex(md5(concat(le32(randomValue), "8662".getBytes(StandardCharsets.UTF_8))))
                .getBytes(StandardCharsets.US_ASCII);
        long[] w = new long[4];
        for (int i = 0; i < 4; i++) {
            w[i] = i64(ascii, i * 8, true);
        }
        List<Long> words = new ArrayList<>();
        words.add(w[2]);
        words.add(w[3]);
        List<Long> table = new ArrayList<>();
        table.add(w[0]);
        long b0 = w[0];
        long b8 = w[1];
        for (int i = 0; i < 34; i++) {
            long x8 = (Long.rotateRight(b8, 8) + b0) ^ i;
            words.add(x8);
            x8 = x8 ^ Long.rotateRight(b0, 61);
            table.add(x8);
            b0 = x8;
            b8 = words.remove(0);
        }
        byte[] text = (ts + "-1588093228-8662").getBytes(StandardCharsets.UTF_8);
        int padding = 16 - text.length % 16;
        byte[] pad = new byte[padding];
        for (int i = 0; i < padding; i++) {
            pad[i] = (byte) padding;
        }
        byte[] data = concat(text, pad);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] head = le32(randomValue);
        out.write(head, 0, head.length);
        for (int at = 0; at < data.length; at += 16) {
            long a = i64(data, at, true);
            long b = i64(data, at + 8, true);
            for (int i = 0; i < 34; i++) {
                b = table.get(i) ^ (a + Long.rotateRight(b, 8));
                a = b ^ Long.rotateRight(a, 61);
            }
            byte[] block = concat(le64(a), le64(b));
            out.write(block, 0, block.length);
        }
        return base64(out.toByteArray());
    }

    // ---------------------------------------------------------------- sign

    /** Java URLEncoder 语义与 Kotlin 侧一致，只需把 + 还原成 %20、%7E 还原成 ~。 */
    public static String encodeValue(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8").replace("+", "%20").replace("%7E", "~");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static String encodeQuery(Map<String, String> values) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : values.entrySet()) {
            if (sb.length() > 0) {
                sb.append('&');
            }
            sb.append(encodeValue(e.getKey())).append('=').append(encodeValue(e.getValue()));
        }
        return sb.toString();
    }

    /** 生成签名请求：在 ticket 上抖动 0..127 直到命中 0 号哈希分支。 */
    public Request sign(String base, Map<String, String> values, byte[] body) {
        long now = clock.now();
        int ts = (int) (now / 1000);
        LinkedHashMap<String, String> full = new LinkedHashMap<>(values);
        full.put("ts", String.valueOf(ts));
        full.put("_rticket", String.valueOf(now));
        for (int offset = 0; offset <= 127; offset++) {
            long ticket = now + offset;
            full.put("_rticket", String.valueOf(ticket));
            String query = encodeQuery(full);
            if (branch(query, body, ts) != 0) {
                continue;
            }
            String url = base + "?" + query;
            int randomValue = (int) (rnd() * 65536);
            LinkedHashMap<String, String> headers = new LinkedHashMap<>();
            headers.put("User-Agent", body != null ? VIDEO_UA : APP_UA);
            headers.put("Accept", "application/json; charset=utf-8,application/x-protobuf");
            headers.put("x-ss-req-ticket", String.valueOf(ticket));
            headers.put("x-tt-request-tag", "t=0;n=0");
            headers.put("sdk-version", "2");
            headers.put("passport-sdk-version", "50561");
            headers.put("x-xs-from-web", "0");
            headers.put("x-khronos", String.valueOf(ts));
            headers.put("x-ladon", base64(be32(ts)));
            headers.put("x-argus", base64(le32(ts)));
            headers.put("x-gorgon", gorgon(query, body, ts, randomValue));
            headers.put("x-helios", helios(ts));
            headers.put("x-medusa", medusa(url, body, ts));
            headers.put("x-tt-dt", "");
            if (body != null) {
                headers.put("Content-Type", "application/json; charset=UTF-8");
                headers.put("x-vc-bdturing-sdk-version", "3.7.2.cn");
                headers.put("x-ss-stub", hex(md5(body)).toUpperCase());
            }
            return new Request(url, headers, body);
        }
        throw new IllegalStateException("无法生成请求签名");
    }

    // ---------------------------------------------------------------- 协议自定义 AES

    /** 协议自定义分组变换（不是标准 AES，替换盒与列顺序都来自 vendor 还原）。 */
    static final class AesV3 {
        private static final int[][] CON = {
                {1, 0, 2, 3}, {2, 0, 3, 1}, {0, 1, 3, 2}, {1, 0, 2, 3}
        };
        private static final int[][] ORDER = {
                {0, 9, 14, 11, 4, 13, 2, 7, 8, 1, 6, 15, 12, 5, 10, 3},
                {0, 9, 14, 15, 4, 13, 2, 7, 8, 1, 6, 3, 12, 5, 10, 11},
                {0, 9, 14, 7, 4, 13, 2, 11, 8, 1, 6, 3, 12, 5, 10, 15},
                {0, 9, 14, 11, 4, 13, 2, 7, 8, 1, 6, 15, 12, 5, 10, 3}
        };
        private static final int[] INITIAL = {
                0xca025ddc, 0x823dc546, 0xc9420583, 0xc298225f
        };

        private final int word;
        private final int[] box = new int[256];
        private final int[] con;
        private final int[] order;
        private final int[][] keys = new int[12][4];

        AesV3(byte[] key, int timestamp) {
            word = timestamp & 3;
            for (int i = 0; i < 256; i++) {
                box[i] = u(SBOX[word * 256 + i]);
            }
            con = CON[word];
            order = ORDER[word];
            int initial = INITIAL[word];
            byte[] repeated = concat(le32(initial), le32(initial), le32(initial), le32(initial));
            byte[] mixed = xorBytes(repeated, key);
            int[] expanded = new int[48];
            for (int i = 0; i < 16; i++) {
                expanded[i] = u(mixed[i]);
            }
            int rounds = 8;
            for (int i = 4; i < 12; i++) {
                int at = 4 * (i - 1);
                int a = expanded[at];
                int b = expanded[at + 1];
                int c = expanded[at + 2];
                int d = expanded[at + 3];
                if ((i & 3) == 0) {
                    int t = ((initial >> (rounds & 24)) ^ box[b]) & 255;
                    b = box[c];
                    c = box[d];
                    d = box[a];
                    a = t;
                }
                rounds += 2;
                expanded[at + 4] = a ^ expanded[at - 12];
                expanded[at + 5] = b ^ expanded[at - 11];
                expanded[at + 6] = c ^ expanded[at - 10];
                expanded[at + 7] = d ^ expanded[at - 9];
            }
            for (int i = 0; i < 12; i++) {
                System.arraycopy(expanded, i * 4, keys[i], 0, 4);
            }
        }

        private byte[] block(byte[] value) {
            int[][] rows = new int[4][4];
            for (int i = 0; i < 4; i++) {
                for (int j = 0; j < 4; j++) {
                    rows[i][j] = u(value[i * 4 + j]);
                }
            }
            addCon(rows, 0);
            for (int round = 1; round <= 2; round++) {
                for (int i = 0; i < 4; i++) {
                    for (int j = 0; j < 4; j++) {
                        rows[i][j] = box[rows[i][j]];
                    }
                }
                int[][] before = new int[4][4];
                for (int i = 0; i < 4; i++) {
                    System.arraycopy(rows[i], 0, before[i], 0, 4);
                }
                for (int i = 0; i < 4; i++) {
                    System.arraycopy(before[con[i]], 0, rows[i], 0, 4);
                }
                int[] flat = new int[16];
                for (int i = 0; i < 4; i++) {
                    System.arraycopy(rows[i], 0, flat, i * 4, 4);
                }
                for (int i = 0; i < 16; i++) {
                    rows[i / 4][i % 4] = flat[order[i]];
                }
                if (round == 1) {
                    for (int i = 0; i < 4; i++) {
                        int[] old = rows[i].clone();
                        for (int j = 0; j < 4; j++) {
                            rows[i][j] = old[con[j]];
                        }
                    }
                    for (int i = 0; i < 4; i++) {
                        int t = rows[0][i] ^ rows[1][i] ^ rows[2][i] ^ rows[3][i];
                        int uu = rows[0][i];
                        rows[0][i] = rows[0][i] ^ t ^ xt(rows[0][i] ^ rows[1][i]);
                        rows[1][i] = rows[1][i] ^ t ^ xt(rows[1][i] ^ rows[2][i]);
                        rows[2][i] = rows[2][i] ^ t ^ xt(rows[2][i] ^ rows[3][i]);
                        rows[3][i] = rows[3][i] ^ t ^ xt(rows[3][i] ^ uu);
                    }
                }
                addCon(rows, round * 4);
            }
            for (int i = 0; i < 4; i++) {
                for (int j = 0; j < 4; j++) {
                    rows[i][j] ^= keys[4 + i][j];
                }
            }
            byte[] out = new byte[16];
            for (int i = 0; i < 4; i++) {
                for (int j = 0; j < 4; j++) {
                    out[i * 4 + j] = (byte) rows[i][j];
                }
            }
            return out;
        }

        private void addCon(int[][] rows, int offset) {
            for (int i = 0; i < 4; i++) {
                for (int j = 0; j < 4; j++) {
                    rows[i][j] ^= keys[offset + i][con[j]];
                }
            }
        }

        private static int xt(int x) {
            return ((x << 1) ^ ((x & 128) != 0 ? 0x1b : 0)) & 255;
        }

        byte[] encrypt(byte[] source, byte[] iv) {
            if (source.length < 248) {
                throw new IllegalArgumentException("加密数据过短");
            }
            byte[] plaintext = new byte[32];
            for (int i = 0; i < 31; i++) {
                int at = i * 8;
                plaintext[i] = (byte) (((u(source[at]) >>> 4) & 2)
                        | (u(source[at + 1]) & 64)
                        | ((u(source[at + 2]) >>> 2) & 1)
                        | ((u(source[at + 3]) << 3) & 128)
                        | ((u(source[at + 4]) >>> 1) & 4)
                        | ((u(source[at + 5]) << 3) & 16)
                        | ((u(source[at + 6]) << 5) & 32)
                        | ((u(source[at + 7]) >>> 4) & 8));
            }
            plaintext[31] = 1;
            byte[] front = new byte[16];
            byte[] back = new byte[16];
            System.arraycopy(plaintext, 0, front, 0, 16);
            System.arraycopy(plaintext, 16, back, 0, 16);
            byte[] first = block(xorBytes(front, iv));
            byte[] key = concat(first, block(xorBytes(back, first)));
            byte[] out = source.clone();
            for (int i = 0; i < 31; i++) {
                int at = i * 8;
                int k = u(key[i]);
                out[at] = (byte) ((u(out[at]) & 0xdf) | ((k << 4) & 32));
                out[at + 1] = (byte) ((u(out[at + 1]) & 0xbf) | (k & 64));
                out[at + 2] = (byte) ((u(out[at + 2]) & 0xfb) | ((k << 2) & 4));
                out[at + 3] = (byte) ((u(out[at + 3]) & 0xef) | ((k >> 3) & 16));
                out[at + 4] = (byte) ((u(out[at + 4]) & 0xf7) | ((k + k) & 8));
                out[at + 5] = (byte) ((u(out[at + 5]) & 0xfd) | ((k >> 3) & 2));
                out[at + 6] = (byte) ((u(out[at + 6]) & 0xfe) | ((k >> 5) & 1));
                out[at + 7] = (byte) ((u(out[at + 7]) & 0x7f) | ((k << 4) & 128));
            }
            return concat(new byte[]{key[31]}, out);
        }
    }
}
