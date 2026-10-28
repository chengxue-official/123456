package com.houg.tv.playback;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * 单个样本的 CENC-AES-128-CTR 解密。
 * 16 字节计数器 = 8 字节逐样本 IV ‖ 8×0x00；无子样本加密，整块解密。
 */
public final class CencSampleDecryptor {

    private static final String ALGO = "AES/CTR/NoPadding";

    private CencSampleDecryptor() {}

    /** 解密单个样本字节。key 为 16 字节 AES-128 密钥。 */
    public static byte[] decrypt(byte[] sample, byte[] iv8, byte[] key) throws Exception {
        byte[] iv16 = new byte[16];
        System.arraycopy(iv8, 0, iv16, 0, Math.min(iv8.length, 16));
        Cipher cipher = Cipher.getInstance(ALGO);
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv16));
        return cipher.doFinal(sample);
    }
}
