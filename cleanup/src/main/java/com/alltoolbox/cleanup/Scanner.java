package com.alltoolbox.cleanup;

import com.alltoolbox.core.file.FileUtil;
import com.alltoolbox.core.task.TaskExecutor;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 扫描工具：大文件扫描、重复文件扫描、按类型筛选。
 */
public final class Scanner {

    public interface Callback {
        void onProgress(String currentPath);

        void onDone(List<File> result);
    }

    private Scanner() {
    }

    /** 大文件扫描：递归收集超过 minSize 的文件，按大小降序。 */
    public void largeFiles(File root, long minSize, int max,
                           Callback cb) {
        TaskExecutor.get().scan().execute(() -> {
            List<File> out = new ArrayList<>();
            walk(root, minSize, cb, out);
            out.sort((a, b) -> Long.compare(b.length(), a.length()));
            if (out.size() > max) out = new ArrayList<>(out.subList(0, max));
            List<File> r = out;
            cb.onDone(r);
        });
    }

    private void walk(File dir, long minSize, Callback cb, List<File> acc) {
        File[] children = dir.listFiles();
        if (children == null) return;
        for (File f : children) {
            if (f.isDirectory()) {
                if (cb != null) cb.onProgress(f.getAbsolutePath());
                walk(f, minSize, cb, acc);
            } else if (f.length() >= minSize) {
                acc.add(f);
                if (acc.size() >= 5000) return; // 防止过多
            }
        }
    }

    /** 同步扫描多个根目录的大文件到给定列表，按大小降序（上限 5000 项）。供 UI 直接复用。 */
    public static void scanLarge(List<File> roots, long minSize, List<File> out) {
        for (File root : roots) {
            if (root == null || !root.exists() || !root.isDirectory()) continue;
            collectLarge(root, minSize, out);
        }
        out.sort((a, b) -> Long.compare(b.length(), a.length()));
    }

    private static void collectLarge(File dir, long minSize, List<File> acc) {
        File[] children = dir.listFiles();
        if (children == null) return;
        for (File f : children) {
            if (f.isDirectory()) collectLarge(f, minSize, acc);
            else if (f.length() >= minSize && acc.size() < 5000) acc.add(f);
        }
    }

    /** 重复文件扫描：按 大小+文件名 分组，组内再校验；返回候选重复列表。 */
    public void duplicateFiles(File root, Callback cb) {
        TaskExecutor.get().scan().execute(() -> {
            Map<String, List<File>> groups = new HashMap<>();
            index(root, cb, groups);
            List<File> duplicates = new ArrayList<>();
            for (List<File> g : groups.values()) {
                if (g.size() > 1) {
                    // 用 MD5 二次确认
                    Map<String, File> hashSeen = new HashMap<>();
                    for (File f : g) {
                        String md5 = md5(f);
                        if (md5.isEmpty()) continue;
                        if (hashSeen.containsKey(md5)) {
                            duplicates.add(f); // 与已见文件重复
                        } else {
                            hashSeen.put(md5, f);
                        }
                    }
                }
            }
            cb.onDone(duplicates);
        });
    }

    private void index(File dir, Callback cb, Map<String, List<File>> groups) {
        File[] children = dir.listFiles();
        if (children == null) return;
        for (File f : children) {
            if (f.isDirectory()) {
                if (cb != null) cb.onProgress(f.getAbsolutePath());
                index(f, cb, groups);
            } else {
                String key = f.length() + "|" + f.getName();
                groups.computeIfAbsent(key, k -> new ArrayList<>()).add(f);
            }
        }
    }

    private String md5(File f) {
        try (var in = new java.io.FileInputStream(f)) {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) != -1) md.update(buf, 0, n);
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest()) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    // ---------------- 类型筛选 ----------------

    public static final int TYPE_IMAGE = 1;
    public static final int TYPE_VIDEO = 2;
    public static final int TYPE_AUDIO = 3;
    public static final int TYPE_APK = 4;
    public static final int TYPE_DOC = 5;

    private static final String[][] MATCH = {
            {}, // 占位
            {"jpg", "jpeg", "png", "gif", "bmp", "webp", "svg"},
            {"mp4", "mkv", "avi", "mov", "wmv", "flv", "webm"},
            {"mp3", "wav", "flac", "aac", "ogg", "m4a"},
            {"apk", "xapk"},
            {"doc", "docx", "xls", "xlsx", "ppt", "pptx", "pdf", "txt", "md", "html"}
    };

    /** 递归按类型筛选文件。扩展名不匹配时用魔数嗅探兜底，识别改后缀/无扩展名文件。 */
    public void byType(File root, int type, Callback cb) {
        TaskExecutor.get().scan().execute(() -> {
            List<File> out = new ArrayList<>();
            collectByType(root, type, cb, out);
            cb.onDone(out);
        });
    }

    private void collectByType(File dir, int type, Callback cb, List<File> acc) {
        File[] children = dir.listFiles();
        if (children == null) return;
        for (File f : children) {
            if (f.isDirectory()) {
                collectByType(f, type, cb, acc);
            } else if (matches(f, type)) {
                acc.add(f);
            }
        }
    }

    /** 判断文件是否属于目标类型：优先扩展名；不匹配时用 FileUtil 魔数嗅探兜底。 */
    private boolean matches(File f, int type) {
        String ext = ext(f.getName());
        for (String c : MATCH[type]) {
            if (ext.equals(c)) return true;
        }
        // 兜底：仅对魔数能可靠判定的类型做嗅探，减少无谓 IO
        switch (type) {
            case TYPE_IMAGE: return FileUtil.sniffKind(f) == FileUtil.FileKind.IMAGE;
            case TYPE_VIDEO: return FileUtil.sniffKind(f) == FileUtil.FileKind.VIDEO;
            case TYPE_AUDIO: return FileUtil.sniffKind(f) == FileUtil.FileKind.AUDIO;
            case TYPE_APK: return FileUtil.sniffKind(f) == FileUtil.FileKind.APK;
            case TYPE_DOC:
                FileUtil.FileKind k = FileUtil.sniffKind(f);
                return k == FileUtil.FileKind.PDF || k == FileUtil.FileKind.text;
            default: return false;
        }
    }

    private String ext(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(java.util.Locale.ROOT);
    }
}