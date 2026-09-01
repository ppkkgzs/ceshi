package com.alltoolbox.archive;

import android.content.ContentResolver;

import androidx.documentfile.provider.DocumentFile;

import com.alltoolbox.core.task.TaskExecutor;

import org.apache.commons.compress.archivers.zip.Zip64Mode;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.Deflater;

/**
 * 压缩解压核心。
 *  - ZIP 压缩（commons-compress）
 *  - 解压统一基于原生 7-Zip 引擎（7-Zip-JBinding），可稳定处理
 *    Data Descriptor / Zip64 特征的 ZIP 及 7z / rar / tar 等多种格式
 *  - ZIP 包内直接增删、重命名条目（无需完整解压）
 */
public class ArchiveManager {

    public interface Progress {
        void onProgress(long done, long total, String name);
    }

    private static final ArchiveManager sInstance = new ArchiveManager();

    public static ArchiveManager get() {
        return sInstance;
    }

    private ArchiveManager() {
    }

    // ---------------- 压缩 ----------------

    /** 将多个文件/目录递归压缩为 zip。 */
    public void compressZip(List<File> sources, File zipFile, Progress progress) throws IOException {
        try (ZipArchiveOutputStream zos = new ZipArchiveOutputStream(
                new BufferedOutputStream(new FileOutputStream(zipFile)))) {
            zos.setUseZip64(Zip64Mode.AsNeeded);
            zos.setLevel(Deflater.DEFAULT_COMPRESSION);
            for (File src : sources) {
                String base = src.getName();
                addToZip(zos, src, base, progress);
            }
        }
    }

    public void compressZipAsync(List<File> sources, File zipFile, Progress progress,
                                 Runnable onDone, java.util.function.Consumer<Exception> onError) {
        TaskExecutor.get().archive().execute(() -> {
            try {
                compressZip(sources, zipFile, progress);
                if (onDone != null) onDone.run();
            } catch (Exception e) {
                if (onError != null) onError.accept(e);
            }
        });
    }

    // ---------------- 压缩（SAF / DocumentFile，兼容分区存储） ----------------

    /**
     * 将多个 SAF 源（文件或目录树）压缩为 zip，写入指定 Uri。
     * 兼容 Android 10+ 分区存储：源与目标均为 DocumentFile / ContentResolver。
     */
    public void compressZip(ContentResolver cr, DocumentFile[] sources,
                            android.net.Uri destUri, Progress progress) throws IOException {
        try (java.io.OutputStream os = new BufferedOutputStream(cr.openOutputStream(destUri));
             ZipArchiveOutputStream zos = new ZipArchiveOutputStream(os)) {
            zos.setUseZip64(Zip64Mode.AsNeeded);
            zos.setLevel(Deflater.DEFAULT_COMPRESSION);
            for (DocumentFile src : sources) {
                if (src != null) addToZip(cr, zos, src, src.getName(), progress);
            }
            zos.finish();
        }
    }

    public void compressZipAsync(ContentResolver cr, DocumentFile[] sources,
                                 android.net.Uri destUri, Progress progress,
                                 Runnable onDone, java.util.function.Consumer<Exception> onError) {
        TaskExecutor.get().archive().execute(() -> {
            try {
                compressZip(cr, sources, destUri, progress);
                if (onDone != null) onDone.run();
            } catch (Exception e) {
                if (onError != null) onError.accept(e);
            }
        });
    }

    private void addToZip(ContentResolver cr, ZipArchiveOutputStream zos, DocumentFile df,
                          String entryName, Progress progress) throws IOException {
        if (df.isDirectory()) {
            String dirName = entryName.endsWith("/") ? entryName : entryName + "/";
            zos.putArchiveEntry(new ZipArchiveEntry(dirName));
            zos.closeArchiveEntry();
            for (DocumentFile child : df.listFiles()) {
                if (child != null) addToZip(cr, zos, child, dirName + child.getName(), progress);
            }
        } else {
            try (BufferedInputStream in = new BufferedInputStream(cr.openInputStream(df.getUri()))) {
                ZipArchiveEntry entry = new ZipArchiveEntry(entryName);
                entry.setSize(df.length());
                entry.setTime(df.lastModified());
                zos.putArchiveEntry(entry);
                byte[] buf = new byte[64 * 1024];
                long done = 0;
                long total = df.length();
                int n;
                while ((n = in.read(buf)) != -1) {
                    zos.write(buf, 0, n);
                    done += n;
                    if (progress != null) progress.onProgress(done, total, entryName);
                }
                zos.closeArchiveEntry();
            }
        }
    }

    private void addToZip(ZipArchiveOutputStream zos, File file, String entryName, Progress progress)
            throws IOException {
        if (file.isDirectory()) {
            String dirName = entryName.endsWith("/") ? entryName : entryName + "/";
            zos.putArchiveEntry(new ZipArchiveEntry(dirName));
            zos.closeArchiveEntry();
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    addToZip(zos, child, dirName + child.getName(), progress);
                }
            }
        } else {
            zipEntry(zos, file, entryName);
        }
        if (progress != null) progress.onProgress(file.length(), file.length(), entryName);
    }

    private void zipEntry(ZipArchiveOutputStream zos, File file, String entryName) throws IOException {
        ZipArchiveEntry entry = new ZipArchiveEntry(entryName);
        entry.setSize(file.length());
        entry.setTime(file.lastModified());
        zos.putArchiveEntry(entry);
        try (InputStream in = new BufferedInputStream(new FileInputStream(file))) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) != -1) {
                zos.write(buf, 0, n);
            }
        }
        zos.closeArchiveEntry();
    }

    // ---------------- 解压 ----------------

    public void decompress(File archive, File destDir, Progress progress) throws IOException {
        decompress(archive, destDir, null, true, progress);
    }

    /**
     * 解压（可指定密码与同名文件覆盖策略）。
     *
     * @param password          压缩包密码；无密码传 null。
     * @param overwriteExisting true=覆盖同名文件；false=跳过已存在的文件。
     */
    public void decompress(File archive, File destDir, String password,
                           boolean overwriteExisting, Progress progress) throws IOException {
        String name = archive.getName().toLowerCase(Locale.ROOT);
        if (!isSupportedArchive(name)) {
            throw new IOException("不支持的解压格式: " + name);
        }
        // 核心解压交由原生 7-Zip 引擎（7-Zip-JBinding），可稳定处理
        // Data Descriptor / Zip64 ZIP 及 7z、rar、tar 等格式。
        // 密码错误 / 空间不足 / 权限 / 文件损坏等已由引擎翻译为可读中文异常。
        SevenZipEngine.extract(archive, destDir, password, overwriteExisting, progress);
    }

    private static boolean isSupportedArchive(String name) {
        return name.endsWith(".zip") || name.endsWith(".apk") || name.endsWith(".jar")
                || name.endsWith(".7z") || name.endsWith(".rar") || name.endsWith(".tar")
                || name.endsWith(".gz") || name.endsWith(".gzip") || name.endsWith(".tar.gz")
                || name.endsWith(".bz2") || name.endsWith(".bzip2") || name.endsWith(".tbz2")
                || name.endsWith(".xz") || name.endsWith(".lzma") || name.endsWith(".zst")
                || name.endsWith(".iso") || name.endsWith(".lzh") || name.endsWith(".cab")
                || name.endsWith(".wim") || name.endsWith(".udf") || name.endsWith(".deb")
                || name.endsWith(".rpm") || name.endsWith(".cpio") || name.endsWith(".arj")
                || name.endsWith(".z");
    }

    /** 基于 File 的异步解压（供文件浏览器「软件内解压」使用）。 */
    public void decompressAsync(File archive, File destDir, Progress progress,
                                Runnable onDone, java.util.function.Consumer<Exception> onError) {
        decompressAsync(archive, destDir, null, true, progress, onDone, onError);
    }

    /** 基于 File 的异步解压（可指定密码与同名覆盖策略）。 */
    public void decompressAsync(File archive, File destDir, String password,
                                boolean overwriteExisting, Progress progress,
                                Runnable onDone, java.util.function.Consumer<Exception> onError) {
        TaskExecutor.get().archive().execute(() -> {
            try {
                decompress(archive, destDir, password, overwriteExisting, progress);
                if (onDone != null) onDone.run();
            } catch (Exception e) {
                if (onError != null) onError.accept(e);
            }
        });
    }

    // ---------------- 解压（SAF / DocumentFile，兼容分区存储） ----------------

    /**
     * 将 SAF 压缩包解压到指定 SAF 目录。
     * 统一交由原生 7-Zip 引擎处理（自动识别 zip/7z/rar/tar 等格式）：
     * 先缓存为临时文件 → 解到临时目录 → 再写入 SAF 目标树。
     */
    public void decompress(ContentResolver cr, DocumentFile archive, DocumentFile destDir,
                           Progress progress) throws IOException {
        String name = archive.getName() == null ? "" : archive.getName().toLowerCase(Locale.ROOT);
        if (!isSupportedArchive(name)) {
            throw new IOException("不支持的解压格式: " + name);
        }
        File cache = File.createTempFile("arch_", ".bin");
        File tmpDir = new File(cache.getParentFile(), "arch_tmp_" + System.nanoTime());
        if (!tmpDir.mkdirs()) throw new IOException("无法创建临时目录");
        try {
            try (InputStream in = new BufferedInputStream(cr.openInputStream(archive.getUri()));
                 OutputStream fout = new BufferedOutputStream(new FileOutputStream(cache))) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) != -1) fout.write(buf, 0, n);
            }
            SevenZipEngine.extract(cache, tmpDir, progress);
            copyDirToDoc(cr, tmpDir, destDir);
        } finally {
            deleteRecursively(tmpDir);
            cache.delete();
        }
    }

    public void decompressAsync(ContentResolver cr, DocumentFile archive, DocumentFile destDir,
                                Progress progress, Runnable onDone,
                                java.util.function.Consumer<Exception> onError) {
        TaskExecutor.get().archive().execute(() -> {
            try {
                decompress(cr, archive, destDir, progress);
                if (onDone != null) onDone.run();
            } catch (Exception e) {
                if (onError != null) onError.accept(e);
            }
        });
    }

    private void copyDirToDoc(ContentResolver cr, File dir, DocumentFile destDir)
            throws IOException {
        File[] children = dir.listFiles();
        if (children == null) return;
        for (File child : children) {
            if (child.isDirectory()) {
                DocumentFile d = destDir.findFile(child.getName());
                if (d == null) d = destDir.createDirectory(child.getName());
                if (d != null) copyDirToDoc(cr, child, d);
            } else {
                DocumentFile f = destDir.findFile(child.getName());
                if (f == null) {
                    f = destDir.createFile("application/octet-stream", child.getName());
                }
                if (f == null) throw new IOException("无法创建文件: " + child.getName());
                try (InputStream in = new BufferedInputStream(new FileInputStream(child));
                     OutputStream os = new BufferedOutputStream(cr.openOutputStream(f.getUri()))) {
                    byte[] buf = new byte[64 * 1024];
                    int n;
                    while ((n = in.read(buf)) != -1) os.write(buf, 0, n);
                }
            }
        }
    }

    private void deleteRecursively(File f) {
        if (f.isDirectory()) {
            File[] ch = f.listFiles();
            if (ch != null) {
                for (File c : ch) deleteRecursively(c);
            }
        }
        f.delete();
    }

    // ---------------- ZIP 包内编辑 ----------------

    /** 读取 ZIP 条目列表。 */
    public List<ZipEntryInfo> listZipEntries(File zipFile) throws IOException {
        List<ZipEntryInfo> list = new ArrayList<>();
        try (java.util.zip.ZipFile zf = new java.util.zip.ZipFile(zipFile)) {
            var entries = zf.entries();
            while (entries.hasMoreElements()) {
                var e = entries.nextElement();
                list.add(new ZipEntryInfo(e.getName(), e.isDirectory(), e.getSize()));
            }
        }
        return list;
    }

    /**
     * 返回 zip 内某个“虚拟目录层”的直接子项（文件夹 + 文件）。
     *
     * @param prefix 相对 zip 根的目录前缀；顶层传 ""（空串）或 null。
     * 返回的子项不带前缀（即只含当前层内的名称），并区分目录/文件、给出大小。
     */
    public List<ZipEntryInfo> listZipAt(File zip, String prefix) throws IOException {
        String p = (prefix == null) ? "" : prefix;
        if (!p.isEmpty() && !p.endsWith("/")) p = p + "/";
        List<ZipEntryInfo> level = new ArrayList<>();
        java.util.Map<String, ZipEntryInfo> seen = new java.util.LinkedHashMap<>();
        try (java.util.zip.ZipFile zf = new java.util.zip.ZipFile(zip)) {
            var entries = zf.entries();
            while (entries.hasMoreElements()) {
                var e = entries.nextElement();
                String name = e.getName();
                if (!name.startsWith(p)) continue;
                // 找到以 p 开头且只多出一段的子项
                String rest = name.substring(p.length());
                if (rest.isEmpty()) continue;
                int slash = rest.indexOf('/');
                if (slash >= 0) {
                    String dirName = rest.substring(0, slash);
                    if (!seen.containsKey(dirName)) {
                        seen.put(dirName, new ZipEntryInfo(dirName, true, 0));
                    }
                } else {
                    if (!seen.containsKey(rest)) {
                        long size = e.isDirectory() ? 0 : e.getSize();
                        seen.put(rest, new ZipEntryInfo(rest, false, size));
                    }
                }
            }
        }
        level.addAll(seen.values());
        level.sort((a, b) -> {
            if (a.directory != b.directory) return a.directory ? -1 : 1;
            return a.name.compareToIgnoreCase(b.name);
        });
        return level;
    }

    /**
     * 把 zip 内某个虚拟目录（含其下所有子目录与文件）解压到目标目录。
     * prefix 为相对 zip 根的前缀；顶层传 ""，表示解压整个 zip。
     * 目标目录中会重建该前缀下的相对路径。
     */
    public void extractZipPrefix(File zip, String prefix, File destDir, Progress progress)
            throws IOException {
        // 交由原生 7-Zip 引擎处理，避免 java.util.zip 无法处理带 Zip64/Data Descriptor 的包
        SevenZipEngine.extractPrefix(zip, prefix, destDir, progress);
    }

    public void extractZipPrefixAsync(File zip, String prefix, File destDir, Progress progress,
                                      Runnable onDone,
                                      java.util.function.Consumer<Exception> onError) {
        TaskExecutor.get().archive().execute(() -> {
            try {
                extractZipPrefix(zip, prefix, destDir, progress);
                if (onDone != null) onDone.run();
            } catch (Exception e) {
                if (onError != null) onError.accept(e);
            }
        });
    }

    /** 把 zip 内某个条目原样导出到指定文件（用于打开“包中包”前先解到临时文件）。 */
    public void extractZipEntryToFile(File zip, String name, File outFile) throws IOException {
        // 交由原生 7-Zip 引擎处理，避免 java.util.zip 无法读取带 Zip64/Data Descriptor 的条目
        SevenZipEngine.extractEntry(zip, name, outFile);
    }

    /** 是否支持“包中包”浏览的扩展名（ZIP 系）。 */
    public static boolean isBrowseableArchive(String fileName) {
        if (fileName == null) return false;
        String n = fileName.toLowerCase(Locale.ROOT);
        return n.endsWith(".zip") || n.endsWith(".apk") || n.endsWith(".jar");
    }

    /**
     * 从 zip 中删除条目（重命名通过 删除+添加 实现）。
     * 通过重建 zip 完成，保持其它条目不变。
     */
    public File removeZipEntries(File zipFile, List<String> names) throws IOException {
        File tmp = new File(zipFile.getParentFile(), zipFile.getName() + ".tmp");
        try (java.util.zip.ZipFile zf = new java.util.zip.ZipFile(zipFile);
             java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(
                     new BufferedOutputStream(new FileOutputStream(tmp)))) {
            var entries = zf.entries();
            while (entries.hasMoreElements()) {
                var e = entries.nextElement();
                boolean skip = false;
                for (String n : names) {
                    if (e.getName().equals(n) || e.getName().startsWith(n + "/")) {
                        skip = true;
                        break;
                    }
                }
                if (skip) continue;
                zos.putNextEntry(new java.util.zip.ZipEntry(e.getName()));
                try (InputStream in = zf.getInputStream(e)) {
                    byte[] buf = new byte[64 * 1024];
                    int read;
                    while ((read = in.read(buf)) != -1) zos.write(buf, 0, read);
                }
                zos.closeEntry();
            }
        }
        if (!zipFile.delete() || !tmp.renameTo(zipFile)) {
            throw new IOException("更新 zip 失败");
        }
        return zipFile;
    }

    public void removeZipEntriesAsync(File zipFile, List<String> names,
                                      Runnable onDone, java.util.function.Consumer<Exception> onError) {
        TaskExecutor.get().archive().execute(() -> {
            try {
                removeZipEntries(zipFile, names);
                if (onDone != null) onDone.run();
            } catch (Exception e) {
                if (onError != null) onError.accept(e);
            }
        });
    }

    /** ZIP 条目摘要信息。 */
    public static final class ZipEntryInfo {
        public final String name;
        public final boolean directory;
        public final long size;

        ZipEntryInfo(String name, boolean directory, long size) {
            this.name = name;
            this.directory = directory;
            this.size = size;
        }
    }
}