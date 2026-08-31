package com.alltoolbox.security;

import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.security.SecureRandom;
import java.util.Arrays;

/**
 * AES 加密工具（AES/GCM）。
 *  - 密钥由密码 + 随机盐经 PBKDF2 派生。
 *  - GCM 自带认证，防篡改。
 *  - 密文格式：12字节salt || 12字节iv || ciphertext。
 */
public final class AesUtil {

    private static final int SALT_LEN = 12;
    private static final int IV_LEN = 12;
    private static final int T_LEN = 16; // GCM tag

    private AesUtil() {
    }

    /** 派生 AES 密钥（256 位）。 */
    private static SecretKeySpec deriveKey(char[] password, byte[] salt) throws Exception {
        // 采用 PBKDF2WithHmacSHA1：Android 全版本可用（SHA256 仅 API26+）
        PBEKeySpec spec = new PBEKeySpec(password, salt, 100_000, 256);
        SecretKeyFactory f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1");
        byte[] key = f.generateSecret(spec).getEncoded();
        spec.clearPassword();
        return new SecretKeySpec(key, "AES");
    }

    /** 加密文件并写入目标，返回是否成功。 */
    public static boolean encryptFile(File plain, File cipher, char[] password) {
        try {
            byte[] content = readAll(plain);
            byte[] salt = new byte[SALT_LEN];
            new SecureRandom().nextBytes(salt);
            byte[] iv = new byte[IV_LEN];
            new SecureRandom().nextBytes(iv);

            SecretKeySpec key = deriveKey(password, salt);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(T_LEN * 8, iv));
            byte[] enc = c.doFinal(content);

            try (FileOutputStream fos = new FileOutputStream(cipher)) {
                fos.write(salt);
                fos.write(iv);
                fos.write(enc);
            }
            Arrays.fill(content, (byte) 0);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** 解密文件到目标，返回是否成功。 */
    public static boolean decryptFile(File cipher, File plain, char[] password) {
        try {
            byte[] all = readAll(cipher);
            byte[] salt = Arrays.copyOfRange(all, 0, SALT_LEN);
            byte[] iv = Arrays.copyOfRange(all, SALT_LEN, SALT_LEN + IV_LEN);
            byte[] enc = Arrays.copyOfRange(all, SALT_LEN + IV_LEN, all.length);

            SecretKeySpec key = deriveKey(password, salt);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(T_LEN * 8, iv));
            byte[] plainBytes = c.doFinal(enc);

            try (FileOutputStream fos = new FileOutputStream(plain)) {
                fos.write(plainBytes);
            }
            Arrays.fill(plainBytes, (byte) 0);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** 计算密码的指纹（用于校验密码而不明文存储）。 */
    public static String passwordFingerprint(char[] password) throws Exception {
        byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);
        SecretKeySpec key = deriveKey(password, salt);
        java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
        byte[] h = md.digest(key.getEncoded());
        StringBuilder sb = new StringBuilder();
        for (byte b : h) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private static byte[] readAll(File f) throws Exception {
        try (FileInputStream fis = new FileInputStream(f);
             ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = fis.read(buf)) != -1) bos.write(buf, 0, n);
            return bos.toByteArray();
        }
    }
}