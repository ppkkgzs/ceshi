package com.alltoolbox.storage;

import android.os.Environment;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 存储扫描器：遍历外部存储，统计各目录占用、找出最大的若干文件，并按「大小+文件名」匹配重复文件。
 */
public final class StorageScanner {

    /** 重复检测的最小文件大小（1MB 以上才参与），避免大量零碎文件误报。 */
    private static final long DUP_MIN = 1024 * 1024;

    public interface Callback {
        /** 进度回调，scanned 为已扫描文件数。 */
        void onProgress(int scanned);
    }

    public static final class FileEntry {
        public final File file;
        public final long size;
        FileEntry(File file, long size) { this.file = file; this.size = size; }
    }

    public static final class DirEntry {
        public final String path;
        public final long size;
        DirEntry(String path, long size) { this.path = path; this.size = size; }
    }

    public static final class DuplicateGroup {
        public long size;
        public String name;
        public int count;
        public String firstPath;
    }

    private StorageScanner() {
    }

    /**
     * 扫描外部存储根目录。阻塞调用，请在后台线程执行。
     */
    public static Result scan(Callback cb) {
        final File root = Environment.getExternalStorageDirectory();

        final long[] total = {0};
        final int[] count = {0};
        final Map<String, Long> dirUsage = new HashMap<>();
        final List<FileEntry> large = new ArrayList<>();
        // 重复检测：key = size|name
        final Map<Long, List<FileEntry>> bySize = new HashMap<>();

        walk(root, cb, count, total, dirUsage, large, bySize, 0);

        large.sort((a, b) -> Long.compare(b.size, a.size));
        List<FileEntry> top = large.size() > 50 ? new ArrayList<>(large.subList(0, 50)) : large;

        List<DirEntry> dirs = new ArrayList<>();
        for (Map.Entry<String, Long> e : dirUsage.entrySet()) {
            dirs.add(new DirEntry(e.getKey(), e.getValue()));
        }
        dirs.sort((a, b) -> Long.compare(b.size, a.size));
        List<DirEntry> topDirs = dirs.size() > 30 ? new ArrayList<>(dirs.subList(0, 30)) : dirs;

        // 重复文件分组
        Map<String, DuplicateGroup> dupMap = new HashMap<>();
        for (Map.Entry<Long, List<FileEntry>> e : bySize.entrySet()) {
            long size = e.getKey();
            if (size < DUP_MIN) continue;
            Map<String, Integer> nameCount = new HashMap<>();
            String firstName = null;
            for (FileEntry fe : e.getValue()) {
                String n = fe.file.getName();
                if (firstName == null) firstName = fe.file.getAbsolutePath();
                nameCount.merge(n, 1, Integer::sum);
            }
            for (Map.Entry<String, Integer> nc : nameCount.entrySet()) {
                if (nc.getValue() >= 2) {
                    String key = size + "|" + nc.getKey();
                    DuplicateGroup dg = dupMap.get(key);
                    if (dg == null) {
                        dg = new DuplicateGroup();
                        dg.size = size;
                        dg.name = nc.getKey();
                        dg.count = 0;
                        dg.firstPath = firstName;
                        dupMap.put(key, dg);
                    }
                    dg.count = Math.max(dg.count, nc.getValue());
                }
            }
        }
        List<DuplicateGroup> dups = new ArrayList<>(dupMap.values());
        dups.sort((a, b) -> Long.compare(b.size, a.size));

        return new Result(total[0], count[0], top, topDirs, dups);
    }

    private static void walk(File dir, Callback cb, int[] count, long[] total,
                             Map<String, Long> dirUsage, List<FileEntry> large,
                             Map<Long, List<FileEntry>> bySize, int depth) {
        if (depth > 8) return;
        File[] files = dir.listFiles();
        if (files == null) return;
        String dirKey = dir.getAbsolutePath();
        for (File f : files) {
            if (f.isDirectory()) {
                walk(f, cb, count, total, dirUsage, large, bySize, depth + 1);
            } else {
                count[0]++;
                long len = f.length();
                total[0] += len;
                dirUsage.merge(dirKey, len, Long::sum);
                large.add(new FileEntry(f, len));
                bySize.computeIfAbsent(len, k -> new ArrayList<>()).add(new FileEntry(f, len));
                if (cb != null && (count[0] & 0x3FF) == 0) cb.onProgress(count[0]);
            }
        }
    }

    public static final class Result {
        public final long totalBytes;
        public final int fileCount;
        public final List<FileEntry> largeFiles;
        public final List<DirEntry> dirs;
        public final List<DuplicateGroup> duplicates;

        Result(long totalBytes, int fileCount, List<FileEntry> largeFiles,
               List<DirEntry> dirs, List<DuplicateGroup> duplicates) {
            this.totalBytes = totalBytes;
            this.fileCount = fileCount;
            this.largeFiles = largeFiles;
            this.dirs = dirs;
            this.duplicates = duplicates;
        }
    }
}