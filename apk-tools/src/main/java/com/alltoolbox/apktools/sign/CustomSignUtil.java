package com.alltoolbox.apktools.sign;

import com.android.apksig.ApkSigner;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.Collections;
import java.util.Locale;

/**
 * 自定义 APK 签名工具（基于官方 apksig）。
 * 允许用户提供自己的密钥库文件（.keystore / .jks / .p12 / .pfx）与密码，
 * 使用其中的私钥与证书对 APK 进行 v1 + v2 + v3 签名。
 */
public final class CustomSignUtil {

    private CustomSignUtil() {
    }

    /**
     * 判断密钥库类型：.p12/.pfx → PKCS12；.jks → JKS；其他先试 PKCS12，失败回退 JKS。
     */
    private static String guessKeystoreType(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".jks")) return "JKS";
        if (lower.endsWith(".p12") || lower.endsWith(".pfx")) return "PKCS12";
        return null; // 未知，最后自动探测
    }

    /** 从密钥库文件加载私钥与证书。 */
    private static Object[] loadKey(File keystore, String storePass, String alias, String keyPass)
            throws Exception {
        String type = guessKeystoreType(keystore.getName());
        KeyStore ks = null;
        if (type != null) {
            ks = KeyStore.getInstance(type);
            try (InputStream in = new FileInputStream(keystore)) {
                ks.load(in, storePass.toCharArray());
            }
        } else {
            // 自动探测：依次尝试 PKCS12、JKS
            for (String t : new String[]{"PKCS12", "JKS"}) {
                try {
                    KeyStore k = KeyStore.getInstance(t);
                    try (InputStream in = new FileInputStream(keystore)) {
                        k.load(in, storePass.toCharArray());
                    }
                    ks = k;
                    break;
                } catch (Exception ignore) {
                    // 尝试下一种
                }
            }
            if (ks == null) throw new SecurityException("无法识别的密钥库格式（已尝试 PKCS12 / JKS）");
        }

        PrivateKey priv = (PrivateKey) ks.getKey(alias, keyPass.toCharArray());
        Certificate cert = ks.getCertificate(alias);
        if (priv == null || !(cert instanceof X509Certificate)) {
            throw new SecurityException("密钥库中不存在别名 \"" + alias + "\" 的可用私钥/证书");
        }
        return new Object[]{priv, (X509Certificate) cert};
    }

    /**
     * 用用户自定义密钥库对输入 APK 重新签名，输出到目标文件。
     * 返回旧签名与本次签名信息（字符串）。
     */
    public static String sign(File inputApk, File outputApk,
                              File keystore, String storePass, String alias, String keyPass)
            throws Exception {
        String before = describeBefore(inputApk);

        Object[] key = loadKey(keystore, storePass, alias, keyPass);
        PrivateKey privKey = (PrivateKey) key[0];
        X509Certificate cert = (X509Certificate) key[1];

        ApkSigner.SignerConfig config = new ApkSigner.SignerConfig.Builder(
                alias, privKey, Collections.singletonList(cert))
                .build();

        new ApkSigner.Builder(Collections.singletonList(config))
                .setInputApk(inputApk)
                .setOutputApk(outputApk)
                .setMinSdkVersion(24)
                .setOtherSignersSignaturesPreserved(false)
                .build()
                .sign();

        return "签名完成\n\n原签名:\n" + before
                + "\n\n新签名密钥: 别名 \"" + alias + "\"\n"
                + "证书主体: " + cert.getSubjectX500Principal()
                + "\n签名方案: v1 + v2 + v3";
    }

    /** 用 ApkSignatureUtil 读取输入 APK 的旧签名摘要。 */
    private static String describeBefore(File apk) {
        try {
            ApkSignatureUtil.CertificateInfo info = ApkSignatureUtil.read(apk);
            return info != null ? "SHA-256: " + info.sha256 : "（未检出 / 未签名）";
        } catch (Exception e) {
            return "（读取失败: " + e.getMessage() + "）";
        }
    }
}