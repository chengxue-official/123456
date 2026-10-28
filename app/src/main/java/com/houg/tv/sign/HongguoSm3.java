package com.houg.tv.sign;

/**
 * 国密 SM3 哈希（纯 Java，逐行还原自 guoapp native/core/provider_hongguo_comment_sign.go 的 hongguoSM3）。
 * 仅用于红果评论（弹幕）接口签名。
 */
public final class HongguoSm3 {

    private HongguoSm3() {
    }

    public static byte[] digest(byte[] input) {
        long bitLen = (long) input.length * 8;

        // padding: data || 0x80 || zeros || 8字节大端 bitLen
        int zeros = 0;
        int rem = (input.length + 1) % 64;
        zeros = rem <= 56 ? 56 - rem : 120 - rem;
        byte[] data = new byte[input.length + 1 + zeros + 8];
        System.arraycopy(input, 0, data, 0, input.length);
        data[input.length] = (byte) 0x80;
        longToBigEndian(data, data.length - 8, bitLen);

        int[] state = {
                0x7380166f, 0x4914b2b9, 0x172442d7, 0xda8a0600,
                0xa96f30bc, 0x163138aa, 0xe38dee4d, 0xb0fb0e4e
        };
        int[] words = new int[68];
        byte[] block = new byte[64];

        for (int offset = 0; offset < data.length; offset += 64) {
            System.arraycopy(data, offset, block, 0, 64);
            for (int i = 0; i < 16; i++) {
                words[i] = bigEndianWord(block, i * 4);
            }
            for (int i = 16; i < 68; i++) {
                int v = words[i - 16] ^ words[i - 9] ^ rotl(words[i - 3], 15);
                words[i] = v ^ rotl(v, 15) ^ rotl(v, 23) ^ rotl(words[i - 13], 7) ^ words[i - 6];
            }
            int a = state[0], b = state[1], c = state[2], d = state[3];
            int e = state[4], f = state[5], g = state[6], h = state[7];
            for (int i = 0; i < 64; i++) {
                int t, ff, gg;
                if (i < 16) {
                    t = 0x79cc4519;
                    ff = a ^ b ^ c;
                    gg = e ^ f ^ g;
                } else {
                    t = 0x7a879d8a;
                    ff = (a & b) | (a & c) | (b & c);
                    gg = (e & f) | (~e & g);
                }
                int ss1 = rotl(rotl(a, 12) + e + rotl(t, i % 32), 7);
                int tt1 = ff + d + (ss1 ^ rotl(a, 12)) + (words[i] ^ words[i + 4]);
                int tt2 = gg + h + ss1 + words[i];
                // Go: a, b, c, d = tt1, a, rotl(b, 9), c
                int na = tt1, nb = a, nc = rotl(b, 9), nd = c;
                // Go: e, f, g, h = tt2^rotl(tt2,9)^rotl(tt2,17), e, rotl(f,19), g
                int ne = tt2 ^ rotl(tt2, 9) ^ rotl(tt2, 17);
                int nf = e;
                int ng = rotl(f, 19);
                int nh = g;
                a = na; b = nb; c = nc; d = nd;
                e = ne; f = nf; g = ng; h = nh;
            }
            state[0] ^= a; state[1] ^= b; state[2] ^= c; state[3] ^= d;
            state[4] ^= e; state[5] ^= f; state[6] ^= g; state[7] ^= h;
        }

        byte[] out = new byte[32];
        for (int i = 0; i < 8; i++) {
            bigEndianPut(out, i * 4, state[i]);
        }
        return out;
    }

    private static void longToBigEndian(byte[] dst, int off, long v) {
        for (int i = 0; i < 8; i++) {
            dst[off + i] = (byte) (v >>> (56 - i * 8));
        }
    }

    private static void bigEndianPut(byte[] dst, int off, int v) {
        dst[off] = (byte) (v >>> 24);
        dst[off + 1] = (byte) (v >>> 16);
        dst[off + 2] = (byte) (v >>> 8);
        dst[off + 3] = (byte) v;
    }

    private static int bigEndianWord(byte[] src, int off) {
        return (src[off] & 0xFF) << 24 | (src[off + 1] & 0xFF) << 16
                | (src[off + 2] & 0xFF) << 8 | (src[off + 3] & 0xFF);
    }

    private static int rotl(int x, int n) {
        n %= 32;
        return (x << n) | (x >>> (32 - n));
    }
}
