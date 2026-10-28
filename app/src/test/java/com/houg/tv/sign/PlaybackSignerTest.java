package com.houg.tv.sign;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

/**
 * 签名栈离线回归：固定向量 {@code src/test/resources/signing.json} × {@link PlaybackSigner}。
 *
 * <p>每行用 seed=17 的 LCG 随机源 + clock=ts*1000（保证抖动/时间熵可复现），
 * 断言 branch / hash / gorgon / helios / medusa 五项与蓝本逐字节一致。
 * 改动签名逻辑后跑 {@code gradlew :app:testDebugUnitTest} 即可发现偏差。
 */
public class PlaybackSignerTest {

    @Test
    public void matchesFixedVectors() throws Exception {
        List<Map<String, Object>> rows = loadVectors();
        assertEquals("向量条数", 12, rows.size());
        for (int i = 0; i < rows.size(); i++) {
            Map<String, Object> row = rows.get(i);
            int ts = ((Number) row.get("ts")).intValue();
            String url = (String) row.get("url");
            String query = url.contains("?") ? url.substring(url.indexOf('?') + 1) : url;
            Object rawBody = row.get("body");
            byte[] body = rawBody instanceof String
                    ? ((String) rawBody).getBytes(StandardCharsets.UTF_8) : null;

            long[] state = {17L};
            PlaybackSigner signer = new PlaybackSigner(
                    () -> {
                        state[0] = (state[0] * 1664525L + 1013904223L) & 0xffffffffL;
                        return state[0] / 4294967296.0;
                    },
                    () -> ts * 1000L);

            assertEquals("row " + i + " branch",
                    "0", String.valueOf(signer.branch(query, body, ts)));
            byte[] bodyHash = body == null ? new byte[16] : md5(body);
            assertEquals("row " + i + " hash",
                    row.get("hash"),
                    hex(signer.hashF13(HgSm3.digest(query.getBytes(StandardCharsets.UTF_8)),
                            bodyHash, ts)));
            assertEquals("row " + i + " gorgon",
                    row.get("gorgon"), signer.gorgon(query, body, ts, 42131));
            assertEquals("row " + i + " helios",
                    row.get("helios"), signer.helios(ts));
            assertEquals("row " + i + " medusa",
                    row.get("medusa"), signer.medusa(url, body, ts));
        }
    }

    private static List<Map<String, Object>> loadVectors() throws Exception {
        try (InputStream in = PlaybackSignerTest.class.getResourceAsStream("/signing.json")) {
            assertNotNull("找不到 signing.json", in);
            java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int n;
            while ((n = in.read(chunk)) > 0) {
                buf.write(chunk, 0, n);
            }
            return parseArray(new String(buf.toByteArray(), StandardCharsets.UTF_8).trim());
        }
    }

    private static byte[] md5(byte[] data) throws Exception {
        return MessageDigest.getInstance("MD5").digest(data);
    }

    private static String hex(byte[] data) {
        StringBuilder sb = new StringBuilder(data.length * 2);
        for (byte b : data) {
            sb.append("0123456789abcdef".charAt((b >> 4) & 15))
                    .append("0123456789abcdef".charAt(b & 15));
        }
        return sb.toString();
    }

    // ------------------------------------------------ 迷你 JSON 解析：数组[{str,num,null}]

    private static List<Map<String, Object>> parseArray(String src) {
        Parser p = new Parser(src);
        p.ws();
        p.expect('[');
        List<Map<String, Object>> out = new ArrayList<>();
        if (p.peek() == ']') {
            return out;
        }
        while (true) {
            out.add(p.object());
            if (p.peek() == ',') {
                p.next();
            } else {
                break;
            }
        }
        p.expect(']');
        return out;
    }

    private static final class Parser {
        private final String s;
        private int pos;

        Parser(String s) {
            this.s = s;
        }

        void ws() {
            while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) {
                pos++;
            }
        }

        char peek() {
            ws();
            return s.charAt(pos);
        }

        void next() {
            pos++;
        }

        void expect(char c) {
            if (peek() != c) {
                throw new IllegalStateException("JSON 解析错误: 期待 " + c + " at " + pos);
            }
            pos++;
        }

        Map<String, Object> object() {
            expect('{');
            Map<String, Object> map = new LinkedHashMap<>();
            if (peek() == '}') {
                pos++;
                return map;
            }
            while (true) {
                String key = string();
                expect(':');
                ws();
                char c = s.charAt(pos);
                Object value;
                if (c == '"') {
                    value = string();
                } else if (s.startsWith("null", pos)) {
                    value = null;
                    pos += 4;
                } else {
                    value = number();
                }
                map.put(key, value);
                if (peek() == ',') {
                    next();
                } else {
                    expect('}');
                    return map;
                }
            }
        }

        String string() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (true) {
                char c = s.charAt(pos++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c == '\\') {
                    char e = s.charAt(pos++);
                    switch (e) {
                        case '"': sb.append('"'); break;
                        case '\\': sb.append('\\'); break;
                        case '/': sb.append('/'); break;
                        case 'n': sb.append('\n'); break;
                        case 't': sb.append('\t'); break;
                        case 'r': sb.append('\r'); break;
                        case 'b': sb.append('\b'); break;
                        case 'f': sb.append('\f'); break;
                        case 'u':
                            sb.append((char) Integer.parseInt(s.substring(pos, pos + 4), 16));
                            pos += 4;
                            break;
                        default:
                            throw new IllegalStateException("未知转义 \\" + e);
                    }
                } else {
                    sb.append(c);
                }
            }
        }

        double number() {
            ws();
            int start = pos;
            while (pos < s.length() && "+-0123456789.eE".indexOf(s.charAt(pos)) >= 0) {
                pos++;
            }
            return Double.parseDouble(s.substring(start, pos));
        }
    }
}
