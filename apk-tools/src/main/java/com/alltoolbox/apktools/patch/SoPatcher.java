package com.alltoolbox.apktools.patch;

import com.alltoolbox.core.task.TaskExecutor;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * SO 库十六进制等长补丁修改。
 * 在二进制中查找十六进制序列并替换（等长替换保住偏移/重定位；不等长默认拒绝）。
 */
public final class SoPatcher {

    public interface Callback {
        void onDone(boolean success, int replacements, String message);
    }

    private SoPatcher() {
    }

    /** 异步打十六进制补丁。 */
    public static void patchAsync(File src, String hexFind, String hexReplace,
                                  Callback cb) {
        TaskExecutor.get().heavy().execute(() -> {
            try {
                byte[] find = hexToBytes(hexFind);
                byte[] replace = hexToBytes(hexReplace);
                if (find.length == 0) {
                    cb.onDone(false, 0, "查找字节不能为空");
                    return;
                }
                if (find.length != replace.length) {
                    cb.onDone(false, 0, "等长补丁要求查找与替换字节数相同（否则破坏偏移/重定位）");
                    return;
                }
                byte[] data = readAll(src);
                int count = applyPatch(data, find, replace);
                if (count > 0) {
                    writeAll(src, data);
                    cb.onDone(true, count, "已应用 " + count + " 处补丁");
                } else {
                    cb.onDone(false, 0, "未找到匹配字节串");
                }
            } catch (Exception e) {
                cb.onDone(false, 0, "补丁失败: " + e.getMessage());
            }
        });
    }

    /** 就地替换所有匹配片段，返回替换次数。 */
    private static int applyPatch(byte[] data, byte[] find, byte[] replace) {
        int count = 0;
        for (int i = 0; i <= data.length - find.length; i++) {
            boolean match = true;
            for (int j = 0; j < find.length; j++) {
                if (data[i + j] != find[j]) {
                    match = false;
                    break;
                }
            }
            if (match) {
                System.arraycopy(replace, 0, data, i, replace.length);
                count++;
                i += find.length - 1;
            }
        }
        return count;
    }

    private static byte[] hexToBytes(String hex) throws IllegalArgumentException {
        hex = hex.replaceAll("[\\s:,]", "");
        if (hex.indexOf(',') >= 0) hex = hex.replace(",", "");
        if (hex.length() % 2 != 0) throw new IllegalArgumentException("十六进制长度为偶数");
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    private static byte[] readAll(File f) throws IOException {
        try (FileInputStream fis = new FileInputStream(f);
             java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = fis.read(buf)) != -1) bos.write(buf, 0, n);
            return bos.toByteArray();
        }
    }

    private static void writeAll(File f, byte[] data) throws IOException {
        try (FileOutputStream fos = new FileOutputStream(f)) {
            fos.write(data);
        }
    }
}