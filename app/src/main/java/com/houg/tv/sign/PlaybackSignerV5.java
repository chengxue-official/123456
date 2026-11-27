package com.houg.tv.sign;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * v5 播放签名栈（2026-11-24 端点迁移）。
 *
 * <p>背景：2026-11-24 18:07 起老端点 {@code /novel/player/multi_video_model/v1/}
 * 被站点整段改成恒回 HTTP 200 + 0 字节（五台线上设备日志实证；换身份、换域名、
 * 换签名模式均无效——它是端点级拦截，不是身份风控）。上游（海阔生态，2026-11-16
 * 已先行）把取流迁到了 a 域名的 v5 端点：
 *
 * <pre>
 * POST https://api5-normal-sinfonlinea.fqnovel.com/novel/player/multi_video_model/player/v1
 * </pre>
 *
 * <p>v5 与 v1 的差异（沙箱 A/B 实测，少一条都是 200 空体）：
 * <ol>
 *   <li>body 必须 <b>gzip</b> 压缩，带 {@code Content-Encoding: gzip}；
 *       签名（x-ss-stub / hashF13 / medusa）都算在压缩后的字节上；</li>
 *   <li>签名头只发 <b>x-khronos + x-helios + x-medusa</b>：
 *       不带 gorgon/ladon/argus/req-ticket/passport 等 v1 头；</li>
 *   <li>medusa 是全新版本：magic {@code 2d4b4fca…}、协议 version=5、
 *       SDK {@code v04.10.03}、新 signKey、field 19/16 删除、AesV5 全新换盒
 *       （4 套 SBOX + 自定义密钥扩展，见内部类 {@link AesV5}）；</li>
 *   <li>biz_param 增加 {@code dr_scene:"player"} 与
 *       {@code disable_video_relate_book:true}，mixed_video_id_map key 用 "1004"。</li>
 * </ol>
 *
 * <p>实现来源：第三方修复版（Python）逐行移植，含 sum_md5 的 {@code |0x20} 变体
 * （与 v1 的 {@code (^0x1000000)|4} 不同）、hashF13 固定 ivV1=0x9d4a97c2 与
 * chosen={96,1,3,4,0,6,5,2,0,7,7}。对拍脚本见工程外 {@code _v5_crosscheck.py}。
 */
public final class PlaybackSignerV5 {

    private static final String BASE =
            "https://api5-normal-sinfonlinea.fqnovel.com/novel/player/video_model/v1/";

    /** UA 模板（HGTV 1.3.9.3 的 b4.d 同款：带 Cronet/TTNet 后缀，%VC%/%OS%/%MODEL% 按档案填充）。 */
    private static final String UA_TEMPLATE =
            "com.phoenix.read/%VC% (Linux; U; Android %OS%; zh_CN; %MODEL%; "
                    + "Build/BP2A.250605.031.A3; Cronet/TTNetVersion:8d40f833 2026-03-03 "
                    + "QuicVersion:21ac1950 2025-11-18)";

    /** v5 播放器 UA：按请求档案生成，与注册/其它业务请求保持同一台「设备」。 */
    /**
     * 把任意字符串净化成 HTTP 头安全的 ASCII 值。
     *
     * 线上事故（2.2.9）：投影仪型号「小明xmM3101智能投影仪」含中文，直接拼进
     * User-Agent 后 OkHttp 在**请求发出前**就抛 IllegalArgumentException
     * （unexpected char 0x5C0F），上层回退旧取流路径 → 旧路径恒 200 空 body → 播放失败。
     * 这里只保留 ASCII 可见字符与安全符号，剔空则退回默认机型。
     */
    public static String ascii(String raw, String fallback) {
        if (raw == null) return fallback;
        StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c >= 0x20 && c <= 0x7E) sb.append(c);
        }
        String out = sb.toString().replaceAll("[^A-Za-z0-9._\\- ]", " ")
                .replaceAll("\\s{2,}", " ").trim();
        int st = 0, en = out.length();
        while (st < en && !Character.isLetterOrDigit(out.charAt(st))) st++;
        while (en > st && !Character.isLetterOrDigit(out.charAt(en - 1))) en--;
        out = out.substring(st, en).trim();
        return out.isEmpty() ? fallback : out;
    }

    public static String ua(Map<String, String> profile) {
        return ascii(UA_TEMPLATE
                .replace("%VC%", str(profile, "version_code", "74732"))
                .replace("%OS%", str(profile, "os_version", "16"))
                .replace("%MODEL%", str(profile, "device_type", "25053RT47C")),
                "com.phoenix.read/74732 (Linux; U; Android 16; zh_CN; 25053RT47C)");
    }

    private static String str(Map<String, String> m, String key, String def) {
        String v = m == null ? null : m.get(key);
        return v == null || v.trim().isEmpty() ? def : v.trim();
    }

    /** medusa 协议魔数与 license（v5 新值；license 与 v1 相同）。 */
    private static final byte[] MAGIC = hex("2d4b4fca49750d433fb5ae2c226dcc56");
    private static final String LICENSE = "646637794";
    private static final int FIELD21 = 312;
    /** medusa 加密密钥（32 字节，v5 新值）。 */
    private static final byte[] SIGN_KEY = hex(
            "b23c2a470a20d105dc3f8c4438f8272ed46d6c89ffdcdedd5108698d26e9489d");
    private static final int IV0 = 0x20230928;
    /** hashF13 的固定 ivV1 与 chosen（v5 版本固定，不再按 branch 选）。 */
    private static final int IV_V1 = 0x9d4a97c2;
    private static final int[] CHOSEN = {96, 1, 3, 4, 0, 6, 5, 2, 0, 7, 7};
    private static final int[] INITIAL_V = {
            0x7aba4fc8, 0x67166507, 0x6403fa00, 0x340f512f,
            984304912, (int) 3005047866L, (int) 2874125293L, (int) 2152413264L
    };

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

    /** AesV5 四套换盒（每 word_size 256 字节）。 */
    private static final byte[] SBOX = hex(
            "53290fe9e51f316f90f74a7e034d367784ab49237cd0135cdc632c322fd3be0e"
                    + "2624a417ecd9151a7ff64f60730a87f9445ec6ada2f865d22b696c4188f24c0d"
                    + "f37b75510b939f6bc2fe6883e68bc8c722181ba0bdb08c9871b50c9a16b46ee"
                    + "a8eed20ba4b8a14e0fcf1468dbbef05a93e5a9d7dcf06898635c1fd99f5de287a"
                    + "80c594daaab19b6279fbd75070cb52eb2555c456e39e7839853874e2f0013ac0"
                    + "b64742d8ca10e4b79c955de8b843ce4ed464a6a72ed508091c92e7b3eedd1e1d"
                    + "d12afac321618fa3d65f9167c982f43d66a53c6a1997585b346dbf9672ff37cd"
                    + "bc073b2dae11453304df00815476b2a13fccdb30b957a8acafe1274002485912"
                    + "38922563e36406d37a24100b7902a028044e218453c2a399199c9d13dc40c55b"
                    + "a67e502273850ebdc1a2e2188b8f2d9e3cb909b8c46a2a9f4a6dd93007eafa65"
                    + "b7b3a881aa908cc74f0ad76bbbb58617c37012ca05a469fd44e62eecf1e4272c"
                    + "e860c0ae5eadf99b20d8f0234174006f3f110d7657d01645d4eff8f71471cbfe"
                    + "1cb2f52648727bf3c9de55153e031b4d61757db63dbf7782eeafe9018a889136"
                    + "2fb14759ac32bab03b349aeb5a377c5156be4c8dfbe13a684bda39cf836697c8"
                    + "94545fe0f2491acea9fc6252f4ff1e0fe72bd2df6e67a7f635e5bcc6d1a5dd95"
                    + "1d89960887a18eb40c785cdb1f80ed42299333ab98cdd67f316cd54346585dcc"
                    + "d759fb722789b08807cc0fda293f7e5e1819bb0353517020b31d3d00374952be"
                    + "a2c421e862040a9ec892a05b266b7a0d0cc316f483b81af39715657ffe32ad8d"
                    + "e0c13077646035236ee5363e5f900e9a7cf850a81cd9960b6df73317226f99ea"
                    + "a6fff15cec34acb98246639b918ab2851466a7cdfc4ad1b52c2ae95a58d3734e"
                    + "47ddd5b7560193f644ef9c408b7dee39b4d6aabf4de761e3252d4c7ba3843187"
                    + "745d55e6a5db05d42b45caed43c0c5d0b64bfa9d94a4a9d8f2f0e1a108dcfd28"
                    + "8fd2bc1fc7bd675412138169cb09249875ba3b68cfb11180afae104fc9789571"
                    + "c6dfce02413886796ae21e3c488cc21b9f423a066c2eabf98e2fdeeb5776e4f5"
                    + "d09a515ba58cbbab3e37b09fd4f6d9244df80512e84cd7956be301cf64f1429d"
                    + "58577cc241f3a2c655144f81aaf95a4e108da894b38970561ae9002619fbbac9"
                    + "1fa922ed202bfc62cb08e0de2a7f3631435f8b47c8a3c563b4db4071ce74c46d"
                    + "a6fa696a9e282d1e825d663b93bfd56c48dce1884bb10fccea063929f746443f"
                    + "d830a4232e53da21a1f2d1bdfebc3a02c10a1b8ad31d04fd616590d272ac737e"
                    + "98eb33af60528716bee78ec0ee8699031177277d9cff32542c67caf5f4459168"
                    + "1335ae0e178f3409a7803db83892dde5b9b7f0c750e24aef077badd678a09b85"
                    + "0b84ec1597e4963cb5e675df1c0d6fb618496ecd76c35c798325590c2fb27a5e");

    private PlaybackSignerV5() {
    }

    /* ==================== 对拍用可注入随机源 ==================== */

    /** 随机源：生产 = ThreadLocalRandom；对拍 = 固定 LCG（同 Python random.seed）。 */
    public interface RandomSource {
        int next32();

        RandomSource PRODUCTION = new RandomSource() {
            @Override
            public int next32() {
                return java.util.concurrent.ThreadLocalRandom.current().nextInt();
            }
        };
    }

    private static volatile RandomSource randomSource = RandomSource.PRODUCTION;

    /** 仅对拍脚本使用：注入确定性随机源（Python LCG 的 Java 复刻）。 */
    public static void useTestRandom(long seed) {
        randomSource = new Lcg(seed);
    }

    public static void useProductionRandom() {
        randomSource = RandomSource.PRODUCTION;
    }

    /** Python random.getrandbits(32) 的 Mersenne-Twister 太重，对拍用 LCG（两边同源）。 */
    static final class Lcg implements RandomSource {
        private final AtomicLong state;

        Lcg(long seed) {
            this.state = new AtomicLong(seed & 0xFFFFFFFFL);
        }

        @Override
        public int next32() {
            long s = state.updateAndGet(x -> (x * 1103515245L + 12345L) & 0xFFFFFFFFL);
            return (int) (s >>> 1);
        }
    }

    /* ==================== 基础工具 ==================== */

    private static int u(byte v) {
        return v & 255;
    }

    private static byte[] hex(String s) {
        int n = s.length() / 2;
        byte[] out = new byte[n];
        for (int i = 0; i < n; i++) {
            out[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    private static byte[] md5(byte[] data) {
        try {
            return java.security.MessageDigest.getInstance("MD5").digest(data);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] sm3(byte[] data) {
        return HgSm3.digest(data);
    }

    private static byte[] concat(byte[]... parts) {
        int n = 0;
        for (byte[] p : parts) n += p.length;
        byte[] out = new byte[n];
        int at = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, out, at, p.length);
            at += p.length;
        }
        return out;
    }

    private static byte[] le32(long v) {
        return new byte[]{
                (byte) (v & 255), (byte) ((v >>> 8) & 255),
                (byte) ((v >>> 16) & 255), (byte) ((v >>> 24) & 255)
        };
    }

    private static byte[] le64(long v) {
        byte[] out = new byte[8];
        for (int i = 0; i < 8; i++) {
            out[i] = (byte) ((v >>> (8 * i)) & 255);
        }
        return out;
    }

    /** 标准 Base64 → URL-safe（去填充），对齐 Python urlsafe_b64encode(...).rstrip("=")。 */
    private static String b64Url(byte[] data) {
        return B64.encodeToString(data)
                .replace('+', '-').replace('/', '_').replace("=", "");
    }

    /** 大写十六进制（x-ss-stub 要求大写）。 */
    private static String hexUpper(byte[] data) {
        StringBuilder sb = new StringBuilder(data.length * 2);
        for (byte b : data) {
            sb.append(String.format("%02X", u(b)));
        }
        return sb.toString();
    }

    private static int ror32(int x, int n) {
        return Integer.rotateRight(x, n & 31);
    }

    private static long u32(long x) {
        return x & 0xFFFFFFFFL;
    }

    /* ==================== protobuf（v5 语义：0 值字段整段省略） ==================== */

    private static byte[] varint(long v) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        long n = v;
        while (n > 0x7F) {
            out.write((int) ((n & 0x7F) | 0x80));
            n >>>= 7;
        }
        out.write((int) n);
        return out.toByteArray();
    }

    /** Python _sint：zigzag，不截断到 32 位（rand 可能超 2^31）。 */
    private static long sint(long value) {
        return value >= 0 ? value << 1 : (value << 1) ^ (~0L);
    }

    /**
     * v5 proto 编码。与 v1 的差别：值判断为空（null/""/b""/0/false）时整段省略。
     * kinds: 0=sint, 1=string, 2=bytes, 3=message。
     */
    private static byte[] proto(Object[][] fields) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (Object[] f : fields) {
            int tag = (Integer) f[0];
            Object value = f[1];
            int kind = (Integer) f[2];
            if (value == null) continue;
            if (kind == 0) {
                long n = ((Number) value).longValue();
                if (n == 0) continue;
                byte[] head = varint((tag << 3));
                out.write(head, 0, head.length);
                byte[] body = varint(sint(n));
                out.write(body, 0, body.length);
            } else {
                byte[] bytes;
                if (kind == 1) {
                    String s = value.toString();
                    if (s.isEmpty()) continue;
                    bytes = s.getBytes(StandardCharsets.UTF_8);
                } else {
                    bytes = (byte[]) value;
                    if (bytes.length == 0) continue;
                }
                byte[] head = varint((tag << 3) | 2);
                byte[] len = varint(bytes.length);
                out.write(head, 0, head.length);
                out.write(len, 0, len.length);
                out.write(bytes, 0, bytes.length);
            }
        }
        return out.toByteArray();
    }

    /* ==================== branch / hashF13 ==================== */

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

    /** v5 branch：iv&15==12 才可用（v1 是 %3==0 的三支协议）。 */
    public static int branch(String query, byte[] body, int ts) {
        byte[] q = sm3(query.getBytes(StandardCharsets.UTF_8));
        byte[] b = body == null ? new byte[16] : md5(body);
        int iv = getIv(getIv(getIv(IV0, q), b), le32(ts));
        return (iv & 15) == 12 ? 0 : 1;
    }

    /** Python sum_md5（与 v1 相同：结尾 {@code |4 ^0x1000000}；v5 只在调用处再加 |0x20）。 */
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

    /** v5 hashF13：固定 ivV1/CHOSEN；SM3(query)+MD5(body)+ts+pad+00000000000001a0。 */
    static byte[] hashF13(byte[] querySm3, byte[] bodyMd5, int ts) {
        int countV1 = (IV_V1 + ts + 1) & 255;
        int countV2 = (IV_V1 + ts);
        int[] tt = new int[HASH_TABLE.length];
        for (int i = 0; i < HASH_TABLE.length; i++) {
            tt[i] = ror32(HASH_TABLE[i], countV1);
        }
        int n0 = (countV2 + 2) & 7;
        int[] seed = {0xfa, 0x45, 0x61, 0xd7};
        byte[] pad = new byte[4];
        for (int i = 0; i < 4; i++) {
            pad[i] = (byte) (((seed[i] | (seed[i] << 8)) >> n0) & 255);
        }
        byte[] data = concat(querySm3, bodyMd5, le32(ts), pad, hex("00000000000001a0"));
        int dataLen = (data.length / 4) + 112 + 16;
        int[] di = new int[dataLen];
        int diLen = 0;
        for (int at = 0; at + 4 <= data.length; at += 4) {
            di[diLen++] = ((u(data[at]) << 24) | (u(data[at + 1]) << 16)
                    | (u(data[at + 2]) << 8) | u(data[at + 3]));
        }
        int di0 = di[0];
        for (int i = 0; i < 112; i++) {
            int d1 = di[i + 1];
            int d14 = di[i + 14];
            int r1 = Integer.rotateLeft(d1, 14) ^ Integer.rotateLeft(d1, 25) ^ (d1 >>> 3);
            int r2 = Integer.rotateLeft(d14, 13) ^ Integer.rotateLeft(d14, 15) ^ (d14 >>> 10);
            di[diLen++] = (di0 + di[i + 9] + r1 + r2);
            di0 = d1;
        }
        int[] initial = new int[8];
        for (int i = 0; i < 8; i++) {
            initial[i] = ror32(INITIAL_V[i], countV2 & 31);
        }
        int[] d = new int[8];
        System.arraycopy(initial, 0, d, 0, 8);
        for (int i = 0; i < CHOSEN[0]; i++) {
            int base = IV_V1 + i;
            int n0v = di[base & 127];
            int n1 = ((d[CHOSEN[3]] ^ d[CHOSEN[4]]) & d[CHOSEN[1]]) ^ d[CHOSEN[3]];
            int n2 = Integer.rotateLeft(d[CHOSEN[1]], 26)
                    ^ Integer.rotateLeft(d[CHOSEN[1]], 21)
                    ^ Integer.rotateLeft(d[CHOSEN[1]], 7);
            int n4 = (n0v + n1 + n2 + tt[base & 63] + d[CHOSEN[5]]);
            int n5 = Integer.rotateLeft(d[CHOSEN[2]], 30)
                    ^ Integer.rotateLeft(d[CHOSEN[2]], 19)
                    ^ Integer.rotateLeft(d[CHOSEN[2]], 10);
            int n6 = (d[CHOSEN[2]] & d[CHOSEN[6]]) | ((d[CHOSEN[2]] | d[CHOSEN[6]]) & d[CHOSEN[7]]);
            int n7 = (n5 + n6);
            int old = d[CHOSEN[9]];
            int last = d[7];
            System.arraycopy(d, 0, d, 1, 7);
            d[0] = last;
            d[CHOSEN[10]] = n7 + n4;
            d[CHOSEN[8]] = old + n4;
        }
        byte[] ret = new byte[32];
        for (int i = 0; i < 8; i++) {
            int v = d[i] + initial[i];
            ret[i * 4] = (byte) (v >>> 24);
            ret[i * 4 + 1] = (byte) (v >>> 16);
            ret[i * 4 + 2] = (byte) (v >>> 8);
            ret[i * 4 + 3] = (byte) v;
        }
        for (int i = 0; i < 16; i++) {
            ret[i] ^= ret[i + 16];
        }
        byte[] head = new byte[16];
        System.arraycopy(ret, 0, head, 0, 16);
        int tail = sumMd5(head) | 0x20;
        return concat(head, le32(tail));
    }

    /* ==================== x-helios（v5：64 位表 0x22 轮） ==================== */

    public static String helios(int ts, int randValue) {
        byte[] md = md5(concat(le32(randValue), "8662".getBytes(StandardCharsets.UTF_8)));
        StringBuilder asciiSb = new StringBuilder();
        for (byte b : md) {
            asciiSb.append(String.format("%02x", u(b)));
        }
        byte[] ascii = asciiSb.toString().getBytes(StandardCharsets.US_ASCII);
        long[] words = new long[4];
        for (int i = 0; i < 4; i++) {
            long w = 0;
            for (int j = 0; j < 8; j++) {
                w |= ((long) u(ascii[i * 8 + j])) << (8 * j);
            }
            words[i] = w;
        }
        List<Long> table = new ArrayList<>();
        List<Long> queue = new ArrayList<>();
        long first = words[0];
        long second = words[1];
        table.add(words[0]);
        queue.add(words[2]);
        queue.add(words[3]);
        for (int i = 0; i < 0x22; i++) {
            long v2 = (Long.rotateRight(second, 8) + first) & 0xFFFFFFFFFFFFFFFFL;
            v2 ^= i;
            queue.add(v2);
            v2 = (v2 ^ Long.rotateRight(first, 61)) & 0xFFFFFFFFFFFFFFFFL;
            table.add(v2);
            first = v2;
            second = queue.remove(0);
        }
        byte[] raw = (ts + "-" + LICENSE + "-8662").getBytes(StandardCharsets.UTF_8);
        int padding = 16 - raw.length % 16;
        byte[] padded = new byte[raw.length + padding];
        System.arraycopy(raw, 0, padded, 0, raw.length);
        for (int i = raw.length; i < padded.length; i++) {
            padded[i] = (byte) padding;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] head = le32(randValue);
        out.write(head, 0, head.length);
        for (int at = 0; at < padded.length; at += 16) {
            long a = 0;
            long b = 0;
            for (int j = 0; j < 8; j++) {
                a |= ((long) u(padded[at + j])) << (8 * j);
                b |= ((long) u(padded[at + 8 + j])) << (8 * j);
            }
            for (int i = 0; i < 0x22; i++) {
                b = (table.get(i) ^ ((a + Long.rotateRight(b, 8)) & 0xFFFFFFFFFFFFFFFFL));
                a = (b ^ Long.rotateRight(a, 61)) & 0xFFFFFFFFFFFFFFFFL;
            }
            out.write(le64(a), 0, 8);
            out.write(le64(b), 0, 8);
        }
        return B64.encodeToString(out.toByteArray());
    }

    public static String helios(int ts) {
        return helios(ts, randomSource.next32());
    }

    /* ==================== xmxor（与 v1 相同，此处内聚拷贝） ==================== */

    private static int rl8(int v, int n) {
        return ((v << n) | (v >>> (8 - n))) & 255;
    }

    private static byte[] xmxor(byte[] data, byte[] key) {
        int n = data.length;
        int[] encoded = new int[n];
        for (int index = 0; index < n; index++) {
            int position = (index * 4) & 28;
            int d0 = u(key[position]);
            int d1 = u(key[position + 1]);
            int d2 = (rl8(u(data[index]), 4) + d0) ^ d1;
            d2 = rl8((~d2) & 255, 3);
            d2 = ((d2 + d1) & 255) ^ d0;
            encoded[n - index - 1] = (~d2) & 255;
        }
        int[] v = encoded;
        int lastFlag = v[n - 1] ^ v[n - 2];
        int data0 = v[0];
        v[0] = ((~lastFlag) + v[0]) & 255;
        v[1] = ((v[0] ^ v[n - 1] ^ 254) + v[1]) & 255;
        v[2] = (v[2] + ((lastFlag - data0) ^ rl8(v[1], 3) ^ 2)) & 255;
        for (int index = 0; index < n - 4; index++) {
            int temp = rl8(v[index + 2], 3) ^ v[index + 1] ^ (index + 3);
            v[index + 3] = ((~temp) + v[index + 3]) & 255;
        }
        v[n - 1] ^= v[n - 2];
        int tailSum = 0;
        for (int i = 1; i < n; i++) {
            tailSum += v[i];
        }
        v[0] = ((v[0] ^ v[1]) + tailSum) & 255;
        byte[] out = new byte[n];
        for (int i = 0; i < n; i++) {
            out[i] = (byte) v[i];
        }
        return out;
    }

    /* ==================== AesV5（全新换盒 + 位压缩流密码） ==================== */

    private static int aesXt(int x) {
        return ((x << 1) ^ ((x & 128) != 0 ? 0x1b : 0)) & 255;
    }

    private static void aesMix(int[][] rows) {
        for (int i = 0; i < 4; i++) {
            int t = rows[0][i] ^ rows[1][i] ^ rows[2][i] ^ rows[3][i];
            int uu = rows[0][i];
            rows[0][i] ^= t ^ aesXt(rows[0][i] ^ rows[1][i]);
            rows[1][i] ^= t ^ aesXt(rows[1][i] ^ rows[2][i]);
            rows[2][i] ^= t ^ aesXt(rows[2][i] ^ rows[3][i]);
            rows[3][i] ^= t ^ aesXt(rows[3][i] ^ uu);
        }
    }

    static final class AesV5 {
        private static final int[][] CON2 = {
                {2, 0, 3, 1}, {1, 3, 2, 0}, {3, 2, 1, 0}, {2, 3, 1, 0}
        };
        private static final int[][] ORDER = {
                {0, 9, 14, 15, 4, 13, 2, 7, 8, 1, 6, 3, 12, 5, 10, 11},
                {0, 9, 14, 11, 4, 13, 2, 3, 8, 1, 6, 15, 12, 5, 10, 7},
                {0, 9, 14, 3, 4, 13, 2, 15, 8, 1, 6, 11, 12, 5, 10, 7},
                {0, 9, 14, 15, 4, 13, 2, 3, 8, 1, 6, 11, 12, 5, 10, 7}
        };
        private static final int[] WIRE = {6, 5, 3, 4, 2, 7, 1, 0};
        private static final int[] COMPACT = {1, 7, 3, 6, 2, 0, 4, 5};
        private static final int[] INIT = {
                0xbbb5de72, (int) 0xd4638d5bL, (int) 0x92a5f73bL, (int) 0x89f8a1c9L
        };

        private final int wordSize;
        private final int[] box = new int[256];
        private final int[] con2;
        private final int[] order;
        private final int init;
        private final int[][] keys = new int[12][4];

        AesV5(byte[] key, int khronos) {
            wordSize = khronos & 3;
            for (int i = 0; i < 256; i++) {
                box[i] = u(SBOX[wordSize * 256 + i]);
            }
            con2 = CON2[wordSize];
            order = ORDER[wordSize];
            init = INIT[wordSize];
            byte[] initial = new byte[16];
            for (int i = 0; i < 4; i++) {
                System.arraycopy(le32(init), 0, initial, i * 4, 4);
            }
            byte[] mk = new byte[16];
            for (int i = 0; i < 16; i++) {
                mk[i] = (byte) (u(initial[i]) ^ u(key[i]));
            }
            int[] expanded = new int[48];
            for (int i = 0; i < 16; i++) {
                expanded[i] = u(mk[i]);
            }
            int rounds = 8;
            for (int i = 4; i < 12; i++) {
                int at = 4 * (i - 1);
                int a = expanded[at];
                int b = expanded[at + 1];
                int c = expanded[at + 2];
                int d = expanded[at + 3];
                if ((i & 3) == 0) {
                    int t = ((init >>> (rounds & 24)) ^ box[b]) & 255;
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

        private int[][] matrix(byte[] value) {
            int[][] rows = new int[4][4];
            for (int i = 0; i < 4; i++) {
                for (int j = 0; j < 4; j++) {
                    rows[i][j] = u(value[i * 4 + j]);
                }
            }
            return rows;
        }

        private byte[] flat(int[][] rows) {
            byte[] out = new byte[16];
            for (int i = 0; i < 4; i++) {
                for (int j = 0; j < 4; j++) {
                    out[i * 4 + j] = (byte) rows[i][j];
                }
            }
            return out;
        }

        private int[][] subKeys(int start) {
            int[][] out = new int[4][4];
            for (int i = 0; i < 4; i++) {
                System.arraycopy(keys[start + i], 0, out[i], 0, 4);
            }
            return out;
        }

        private byte[] block(byte[] value) {
            int[][] rows = matrix(value);
            addCon(rows, subKeys(0));
            for (int round = 1; round <= 2; round++) {
                // _sub：先查换盒，再按 con2 做行置换
                for (int i = 0; i < 4; i++) {
                    for (int j = 0; j < 4; j++) {
                        rows[i][j] = box[rows[i][j]];
                    }
                }
                int[][] permuted = new int[4][4];
                for (int i = 0; i < 4; i++) {
                    System.arraycopy(rows[con2[i]], 0, permuted[i], 0, 4);
                }
                rows = permuted;
                // _shift：按 order 重排 16 个字节
                int[] flat16 = new int[16];
                for (int i = 0; i < 4; i++) {
                    for (int j = 0; j < 4; j++) {
                        flat16[i * 4 + j] = rows[i][j];
                    }
                }
                for (int i = 0; i < 16; i++) {
                    rows[i / 4][i % 4] = flat16[order[i]];
                }
                if (round == 1) {
                    // shift_con：按列置换
                    int[][] old = new int[4][4];
                    for (int i = 0; i < 4; i++) {
                        System.arraycopy(rows[i], 0, old[i], 0, 4);
                    }
                    for (int i = 0; i < 4; i++) {
                        for (int j = 0; j < 4; j++) {
                            rows[i][j] = old[i][con2[j]];
                        }
                    }
                    aesMix(rows);
                }
                addCon(rows, subKeys(round * 4));
            }
            int[][] last = subKeys(4);
            for (int i = 0; i < 4; i++) {
                for (int j = 0; j < 4; j++) {
                    rows[i][j] ^= last[i][j];
                }
            }
            return flat(rows);
        }

        private void addCon(int[][] rows, int[][] ks) {
            for (int i = 0; i < 4; i++) {
                for (int j = 0; j < 4; j++) {
                    rows[i][j] ^= ks[i][con2[j]];
                }
            }
        }

        /** 与 AesV3.encrypt 同构，但 WIRE/COMPACT 是 v5 的位映射。 */
        byte[] encrypt(byte[] data, byte[] iv) {
            int[] plaintext = new int[32];
            for (int i = 0; i < 31; i++) {
                int at = i * 8;
                int acc = 0;
                for (int j = 0; j < 8; j++) {
                    int b = at + j < data.length ? u(data[at + j]) : 0;
                    acc |= (((b >>> WIRE[j]) & 1) << COMPACT[j]);
                }
                plaintext[i] = acc & 255;
            }
            plaintext[31] = 1;
            byte[] front = new byte[16];
            byte[] back = new byte[16];
            for (int i = 0; i < 16; i++) {
                front[i] = (byte) plaintext[i];
                back[i] = (byte) plaintext[16 + i];
            }
            byte[] mixed = new byte[16];
            for (int i = 0; i < 16; i++) {
                mixed[i] = (byte) (u(front[i]) ^ u(iv[i]));
            }
            byte[] first = block(mixed);
            mixed = new byte[16];
            for (int i = 0; i < 16; i++) {
                mixed[i] = (byte) (u(back[i]) ^ u(first[i]));
            }
            byte[] second = block(mixed);
            byte[] key = concat(first, second);
            byte[] out = data.clone();
            for (int i = 0; i < 31; i++) {
                int at = i * 8;
                int k = u(key[i]);
                for (int j = 0; j < 8; j++) {
                    int bit = WIRE[j];
                    int mask = (1 << bit) & 255;
                    if (at + j < out.length) {
                        out[at + j] = (byte) ((u(out[at + j]) & (~mask & 255))
                                | (((k >>> COMPACT[j]) & 1) << bit));
                    }
                }
            }
            return concat(new byte[]{key[31]}, out);
        }
    }

    /* ==================== x-medusa（v5） ==================== */

    private static long rndInt32() {
        int v = randomSource.next32();
        return v;
    }

    /**
     * v5 x-medusa。proto 结构（相对 v1）：field 2 固定 5、field 10 空 8 字节、
     * 无 field 16/19、field 24 换 cmr 固定文案、env 换 SDK v04.10.03.04-bugfix。
     */
    public static String medusa(String url, byte[] body, int khronos, String deviceId,
                                String versionName, String deviceType, String channel,
                                String osVersion, String brand) {
        return medusa(url, body, khronos, deviceId, versionName, deviceType, channel,
                osVersion, brand, System.currentTimeMillis());
    }

    /** 对拍重载：started 显式传入（Python 侧把 time.time() 打桩成同一值）。 */
    static String medusa(String url, byte[] body, int khronos, String deviceId,
                         String versionName, String deviceType, String channel,
                         String osVersion, String brand, long started) {
        byte[] bodyMd5 = body == null ? new byte[16] : md5(body);
        String query = url.contains("?") ? url.substring(url.indexOf('?') + 1) : "";
        byte[] queryBytes = query.getBytes(StandardCharsets.UTF_8);
        byte[] querySm3 = sm3(queryBytes);
        byte[] f13 = hashF13(querySm3, bodyMd5, khronos);
        String version = versionName == null || versionName.isEmpty() ? "7.4.7.32" : versionName;
        byte[] nonceBytes = new byte[18];
        for (int i = 0; i < 18; i++) {
            nonceBytes[i] = (byte) (randomSource.next32() & 255);
        }
        String nonce = b64Url(nonceBytes);
        String chan = channel == null || channel.isEmpty() ? "update_64" : channel;
        String os = osVersion == null || osVersion.isEmpty() ? "16" : osVersion;
        String brd = brand == null || brand.isEmpty() ? "Redmi" : brand;
        byte[] deviceInfo = proto(new Object[][]{
                {1, 1L, 0}, {2, 2L, 0},
                {3, "8662", 1}, {4, deviceId, 1}, {5, nonce, 1}, {6, "!noperm!", 1},
                {12, "Asia/Shanghai,8", 1}, {13, "zh_CN", 1},
                {22, os, 1}, {25, started, 0},
                {30, deviceType, 1}, {31, brd, 1},
                {32, deviceType, 1}, {34, brd, 1}});
        byte[] env = proto(new Object[][]{
                {6, "v04.10.03.04-bugfix", 1},
                {8, chan, 1},
                {12, deviceInfo, 3},
                {14, version, 1}});
        byte[] message = proto(new Object[][]{
                {1, MAGIC, 2},
                {2, 5L, 0},
                {3, rndInt32(), 0},
                {4, "8662", 1},
                {5, deviceId, 1},
                {6, LICENSE, 1},
                {7, version, 1},
                {8, "v04.10.03-ml-android", 1},
                {9, 0x040A0300L, 0},
                {10, new byte[8], 2},
                {12, (long) khronos, 0},
                {13, f13, 2},
                {14, java.util.Arrays.copyOf(querySm3, 6), 2},
                {15, proto(new Object[][]{{1, 1L, 0}, {2, 1L, 0}, {3, 1L, 0}, {5, 1L, 0}}), 3},
                {17, (long) khronos, 0},
                {20, "none", 1},
                {21, (long) FIELD21, 0},
                {23, env, 3},
                {24, "{\"cmr\":16777216,\"cmr2\":16777216,\"un_h\":11,\"vpn\":0,\"kd\":0,"
                        + "\"dyn\":\"\",\"do\":1,\"tk\":true}", 1}});
        int signNonce = (int) (rndInt32() & 0xFFFFFFFFL);
        byte[] toHash = new byte[68];
        System.arraycopy(SIGN_KEY, 0, toHash, 0, 32);
        System.arraycopy(le32(signNonce), 0, toHash, 32, 4);
        System.arraycopy(SIGN_KEY, 0, toHash, 36, 32);
        byte[] hashBytes = sm3(toHash);
        int d1 = (signNonce >>> 16) & 255;
        int d2 = (d1 << 11) | (signNonce >>> 24);
        d2 ^= (d1 >>> 5) ^ d1;
        d2 = ~d2;
        byte[] seed = le32(d2);
        byte[] transformed = xmxor(message, hashBytes);
        byte[] withPrefix = new byte[8 + transformed.length];
        System.arraycopy(transformed, 0, withPrefix, 8, transformed.length);
        for (int i = 0, n = withPrefix.length; i < n / 2; i++) {
            byte t = withPrefix[i];
            withPrefix[i] = withPrefix[n - 1 - i];
            withPrefix[n - 1 - i] = t;
        }
        for (int i = 0; i < withPrefix.length; i++) {
            withPrefix[i] = (byte) (u(withPrefix[i]) ^ u(seed[(~i) & 3]));
        }
        int check = (((u(querySm3[0]) & 63) << 14) | 0x18000001
                | ((u(f13[0]) & 63) << 8));
        ByteArrayOutputStream packed = new ByteArrayOutputStream();
        packed.write(0xca);
        byte[] rnd = le32(rndInt32() & 0xFFFFFFFFL);
        packed.write(rnd, 0, 4);
        byte[] chk = le32(check);
        packed.write(chk, 0, 4);
        packed.write(withPrefix, 0, withPrefix.length);
        packed.write((signNonce >>> 16) & 255);
        packed.write((signNonce >>> 24) & 255);
        AesV5 aes = new AesV5(
                md5(java.util.Arrays.copyOfRange(SIGN_KEY, 0, 16)), khronos);
        byte[] encrypted = aes.encrypt(packed.toByteArray(),
                md5(java.util.Arrays.copyOfRange(SIGN_KEY, 16, 32)));
        byte[] prefix = concat(le32(5), MAGIC);
        byte[] tsLe = le32(khronos);
        for (int i = 0; i < prefix.length; i++) {
            prefix[i] ^= tsLe[i & 3];
        }
        return B64.encodeToString(concat(prefix,
                new byte[]{(byte) signNonce, (byte) (signNonce >>> 8), 0, 1}, encrypted));
    }

    /* ==================== query 编码与 sign ==================== */

    /**
     * v5 query 编码：Python quote(safe="-_.!~*'()") 之后再把 !'() 转义，
     * 等价于 Java URLEncoder 的结果（只需把 + 还原成 %20、%7E 还原成 ~；
     * * 两边都不转义，resolution 里的 * 保持字面）。
     */
    public static String encodeValue(String value) {
        try {
            return java.net.URLEncoder.encode(value, "UTF-8")
                    .replace("+", "%20")
                    .replace("%7E", "~");
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

    /** 已签名的 v5 请求。 */
    public static final class Request {
        public final String url;
        public final LinkedHashMap<String, String> headers;

        Request(String url, LinkedHashMap<String, String> headers) {
            this.url = url;
            this.headers = headers;
        }
    }

    /**
     * 生成 v5 签名请求：ticket 上抖动 0..1023 直到 branch_v5==0。
     * headers 只含 x-khronos / x-helios / x-medusa / x-ss-stub / Content-Type。
     */
    public static Request sign(String base, Map<String, String> values, byte[] body) {
        long now = System.currentTimeMillis();
        int ts = (int) (now / 1000);
        LinkedHashMap<String, String> full = new LinkedHashMap<>(values);
        // v5 的 query 只有 _rticket 一个动态参数（x-khronos 走请求头，不带 ts）
        full.put("_rticket", String.valueOf(now));
        String traceId = traceId();
        for (int offset = 0; offset <= 1023; offset++) {
            long ticket = now + offset;
            full.put("_rticket", String.valueOf(ticket));
            String query = encodeQuery(full);
            if (branch(query, body, ts) != 0) {
                continue;
            }
            String url = base + "?" + query;
            int randomValue = randomSource.next32() & 0xFFFF;
            LinkedHashMap<String, String> headers = new LinkedHashMap<>();
            headers.put("User-Agent", ua(values));
            headers.put("Accept", "application/json; charset=utf-8,application/x-protobuf");
            headers.put("x-ss-req-ticket", String.valueOf(ticket));
            headers.put("sdk-version", "2");
            headers.put("passport-sdk-version", "5051452");
            headers.put("lc", "101");
            headers.put("x-vc-bdturing-sdk-version", "4.0.3.cn");
            headers.put("x-xs-from-web", "0");
            headers.put("x-ss-dp", "8662");
            headers.put("x-tt-store-region", "cn-gd");
            headers.put("x-tt-store-region-src", "did");
            headers.put("x-khronos", String.valueOf(ts));
            // v5（HGTV 1.3.9.3 L3.h 分支）：ladon/argus 退化为 4 字节占位，
            // 真正的校验在 gorgon/helios/medusa 三者上。
            headers.put("x-ladon", B64.encodeToString(random4()));
            headers.put("x-argus", B64.encodeToString(le32(ts)));
            headers.put("x-gorgon", gorgon(query, body, ts, randomValue));
            headers.put("x-helios", helios(ts));
            headers.put("x-medusa", medusa(url, body, ts,
                    full.get("device_id"), full.get("version_name"), full.get("device_type"),
                    full.get("channel"), full.get("os_version"), full.get("device_brand")));
            if (body != null && body.length > 0) {
                headers.put("Content-Type", "application/json; charset=utf-8");
                headers.put("Content-Encoding", "gzip");
                headers.put("x-reading-request",
                        ticket + "-" + ((randomSource.next32() & 0xFFFFFFFFL)));
                headers.put("x-ss-stub", hexUpper(md5(body)));
                headers.put("x-tt-trace-id", traceId);
            }
            return new Request(url, headers);
        }
        throw new IllegalStateException("无法生成 v5 请求签名");
    }

    private static byte[] random4() {
        byte[] out = new byte[4];
        for (int i = 0; i < 4; i++) {
            out[i] = (byte) (randomSource.next32() & 255);
        }
        return out;
    }

    /** b4.b() 同款 trace id：00-<uuid 去横线>-<前 16 位>-01。 */
    private static String traceId() {
        String u = java.util.UUID.randomUUID().toString().replace("-", "");
        return "00-" + u + "-" + u.substring(0, 16) + "-01";
    }

    private static byte[] be32(int v) {
        return new byte[]{
                (byte) ((v >>> 24) & 255), (byte) ((v >>> 16) & 255),
                (byte) ((v >>> 8) & 255), (byte) (v & 255)
        };
    }

    /** x-gorgon（HGTV L3.e 同款，8404 前缀，v5 与 v1 共用）。 */
    static String gorgon(String query, byte[] body, int ts, int randomValue) {
        byte[] q4 = new byte[4];
        System.arraycopy(md5(query.getBytes(StandardCharsets.UTF_8)), 0, q4, 0, 4);
        byte[] b4 = new byte[4];
        if (body != null) {
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
            int t = s[i];
            s[i] = s[j];
            s[j] = t;
        }
        byte[] out = new byte[input.length];
        j = 0;
        for (int n = 0; n < input.length; n++) {
            int i = n + 1;
            j += s[i & 255];
            int y = s[j & 255];
            s[i & 255] = y;
            out[n] = (byte) (u(input[n]) ^ s[(y + y) & 255]);
        }
        for (int i = 0; i < out.length; i++) {
            int value = ((u(out[i]) >>> 4) | (u(out[i]) << 4)) & 255;
            int next = u(out[(i + 1 < out.length) ? i + 1 : 0]);
            int reversed = Integer.reverse((next ^ value) & 255) >>> 24;
            out[i] = (byte) (~(reversed ^ 20));
        }
        return hexUpper(concat(hex("8404"),
                new byte[]{(byte) randomValue, (byte) (randomValue >> 8), 0x40, 1}, out));
    }

    public static String base() {
        return BASE;
    }
}
