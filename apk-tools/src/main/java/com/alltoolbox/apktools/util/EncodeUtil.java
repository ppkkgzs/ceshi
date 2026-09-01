package com.alltoolbox.apktools.util;

import android.util.Base64;

import java.nio.charset.StandardCharsets;

/**
 * 编码转换工具：Unicode、Base64、十六进制 互转。
 */
public final class EncodeUtil {

    public static final int UTF = 0;
    public static final int BASE64 = 1;
    public static final int HEX = 2;
    public static final int UNICODE = 3;
    public static final int GBK = 4;

    private EncodeUtil() {
    }

    /** 将字符串编码为目标编码的字节十六进制（HEX）。 */
    public static String toHex(String text, int encoding) {
        byte[] bytes = getBytes(text, encoding);
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b & 0xFF));
        }
        return sb.toString();
    }

    /** 从十六进制字节解码回文本。 */
    public static String fromHex(String hex, int encoding) {
        hex = hex.replaceAll("\\s", "");
        if (hex.length() % 2 != 0) return "";
        byte[] bytes = new byte[hex.length() / 2];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return decode(bytes, encoding);
    }

    public static String toBase64(String text) {
        return Base64.encodeToString(getBytes(text, UTF), Base64.NO_WRAP);
    }

    public static String fromBase64(String b64) {
        try {
            return new String(Base64.decode(b64, Base64.DEFAULT), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    /** 中文转 \\uXXXX 转义。 */
    public static String toUnicode(String text) {
        StringBuilder sb = new StringBuilder();
        for (char c : text.toCharArray()) {
            if (c > 0x7E || c < 0x20) {
                sb.append(String.format("\\u%04x", (int) c));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** 解析 \\uXXXX 转义回文本。 */
    public static String fromUnicode(String text) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\\' && i + 1 < text.length() && text.charAt(i + 1) == 'u'
                    && i + 5 < text.length()) {
                sb.append((char) Integer.parseInt(text.substring(i + 2, i + 6), 16));
                i += 5;
            } else if (c == '\\' && i + 1 < text.length() && text.charAt(i + 1) == 'n') {
                sb.append('\n');
                i++;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static byte[] getBytes(String text, int encoding) {
        try {
            switch (encoding) {
                case GBK: return text.getBytes("GBK");
                default: return text.getBytes(StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            return text.getBytes(StandardCharsets.UTF_8);
        }
    }

    private static String decode(byte[] bytes, int encoding) {
        try {
            switch (encoding) {
                case GBK: return new String(bytes, "GBK");
                default: return new String(bytes, StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }
}