package com.alltoolbox.apktools.sign;

import com.alltoolbox.core.task.TaskExecutor;

import java.io.File;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.cert.X509Certificate;
import java.util.Enumeration;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * APK 签名信息查看（v1 JAR 签名）。
 * 读取 META-INF 签名块并解析证书信息。
 */
public final class ApkSignatureUtil {

    public interface Result {
        void onResult(CertificateInfo cert, String error);
    }

    private ApkSignatureUtil() {
    }

    /** 异步读取 APK 签名证书信息。 */
    public static void readAsync(File apk, Result cb) {
        TaskExecutor.get().heavy().execute(() -> {
            try {
                CertificateInfo info = read(apk);
                if (info != null) cb.onResult(info, null);
                else cb.onResult(null, "未找到签名信息");
            } catch (Exception e) {
                cb.onResult(null, e.getMessage());
            }
        });
    }

    public static CertificateInfo read(File apk) throws Exception {
        try (JarFile jar = new JarFile(apk)) {
            Enumeration<JarEntry> en = jar.entries();
            while (en.hasMoreElements()) {
                JarEntry entry = en.nextElement();
                if (entry.isDirectory()) continue;
                try {
                    java.security.cert.Certificate[] certs = entry.getCertificates();
                    if (certs != null && certs.length > 0 && certs[0] instanceof X509Certificate) {
                        return describe((X509Certificate) certs[0]);
                    }
                } catch (Exception ignore) {
                    // 该 entry 无签名
                }
            }
        }
        return null;
    }

    private static CertificateInfo describe(X509Certificate cert) throws Exception {
        CertificateInfo info = new CertificateInfo();
        info.subject = cert.getSubjectX500Principal().toString();
        info.issuer = cert.getIssuerX500Principal().toString();
        info.serial = cert.getSerialNumber().toString(16);
        info.notBefore = cert.getNotBefore().toString();
        info.notAfter = cert.getNotAfter().toString();
        info.signAlg = cert.getSigAlgName();
        // 证书指纹（SHA-256）
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] digest = md.digest(cert.getEncoded());
        StringBuilder sb = new StringBuilder();
        for (byte b : digest) {
            sb.append(String.format("%02X:", b));
        }
        if (sb.length() > 0) sb.deleteCharAt(sb.length() - 1);
        info.sha256 = sb.toString();
        return info;
    }

    public static final class CertificateInfo {
        public String subject = "";
        public String issuer = "";
        public String serial = "";
        public String notBefore = "";
        public String notAfter = "";
        public String signAlg = "";
        public String sha256 = "";

        public String summary() {
            return "主体: " + subject
                    + "\n签发者: " + issuer
                    + "\n序列号: " + serial
                    + "\n签名算法: " + signAlg
                    + "\n生效: " + notBefore
                    + "\n到期: " + notAfter
                    + "\nSHA-256 指纹: " + sha256;
        }
    }
}