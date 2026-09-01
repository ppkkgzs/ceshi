package com.alltoolbox.apktools.sign;

import android.content.Context;

import com.android.apksig.ApkSigner;

import java.io.File;
import java.io.InputStream;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.Collections;

/**
 * APK 重签名工具（基于官方 apksig）。
 * 使用内嵌的调试密钥对 APK 重新签名，支持 v1 + v2 + v3 签名方案。
 */
public final class ReSignUtil {

    private static final String KEYSTORE_ASSET = "resign.keystore";
    private static final String STORE_PASS = "alltoolbox";
    private static final String KEY_PASS = "alltoolbox";
    private static final String ALIAS = "alltoolbox";

    private ReSignUtil() {
    }

    /** 从内嵌 assets 密钥库加载私钥与证书。 */
    private static Object[] loadKey(Context ctx) throws Exception {
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (InputStream in = ctx.getAssets().open(KEYSTORE_ASSET)) {
            ks.load(in, STORE_PASS.toCharArray());
        }
        PrivateKey priv = (PrivateKey) ks.getKey(ALIAS, KEY_PASS.toCharArray());
        Certificate cert = ks.getCertificate(ALIAS);
        if (priv == null || !(cert instanceof X509Certificate)) {
            throw new IllegalStateException("密钥库缺少可用密钥");
        }
        return new Object[]{priv, (X509Certificate) cert};
    }

    /**
     * 对输入 APK 重新签名，输出到目标文件。
     * 返回旧签名与签名覆盖信息（简化为字符串）。
     */
    public static String resign(Context ctx, File inputApk, File outputApk) throws Exception {
        // 记录修改签名前的指纹，便于界面展示
        String before = describeBefore(inputApk);

        Object[] key = loadKey(ctx);
        PrivateKey privKey = (PrivateKey) key[0];
        X509Certificate cert = (X509Certificate) key[1];

        ApkSigner.SignerConfig config = new ApkSigner.SignerConfig.Builder(
                "alltoolbox", privKey, Collections.singletonList(cert))
                .build();

        new ApkSigner.Builder(Collections.singletonList(config))
                .setInputApk(inputApk)
                .setOutputApk(outputApk)
                .setMinSdkVersion(24)
                .setOtherSignersSignaturesPreserved(false)
                .build()
                .sign();
        return "签名完成\n\n原签名:\n" + before
                + "\n\n新签名密钥: CN=AllToolbox (内嵌调试密钥)\n"
                + "签名方案: v1 + v2 + v3";
    }

    /** 用 ApkSignatureUtil 读取输入 APK 的旧签名摘要。 */
    private static String describeBefore(File apk) throws Exception {
        try {
            ApkSignatureUtil.CertificateInfo info = ApkSignatureUtil.read(apk);
            return info != null ? "SHA-256: " + info.sha256 : "（未检出 / 未签名）";
        } catch (Exception e) {
            return "（读取失败: " + e.getMessage() + "）";
        }
    }
}