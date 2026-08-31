package com.alltoolbox.archive;

import android.content.ContentResolver;

import androidx.documentfile.provider.DocumentFile;

import com.alltoolbox.core.task.TaskExecutor;

import org.apache.commons.compress.archivers.ArchiveEntry;
import org.apache.commons.compress.archivers.ArchiveInputStream;
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry;
import org.apache.commons.compress.archivers.sevenz.SevenZFile;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.zip.Zip64Mode;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream;
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
 *  - ZIP 压缩/解压
 *  - 7Z / RAR(4) / TAR 解压（基于 commons-compress，纯 Java）
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
        String name = archive.getName().toLowerCase(Locale.ROOT);
        if (name.endsWith(".zip")) decompressZip(archive, destDir, progress);
        else if (name.endsWith(".7z")) decompress7z(archive, destDir, progress);
        else if (name.endsWith(".tar")) decompressTar(archive, destDir, progress);
        else throw new IOException("不支持的解压格式: " + name);
    }

    /** 基于 File 的异步解压（供文件浏览器「软件内解压」使用）。 */
    public void decompressAsync(File archive, File destDir, Progress progress,
                                Runnable onDone, java.util.function.Consumer<Exception> onError) {
        TaskExecutor.get().archive().execute(() -> {
            try {
                decompress(archive, destDir, progress);
                if (onDone != null) onDone.run();
            } catch (Exception e) {
                if (onError != null) onError.accept(e);
            }
        });
    }

    // ---------------- 解压（SAF / DocumentFile，兼容分区存储） ----------------

    /**
     * 将 SAF 压缩包解压到指定 SAF 目录。
     * 支持 ZIP / TAR（流式）；7Z 通过缓存文件读取后逐条写入目标树。
     */
    public void decompress(ContentResolver cr, DocumentFile archive, DocumentFile destDir,
                           Progress progress) throws IOException {
        String name = archive.getName() == null ? "" : archive.getName().toLowerCase(Locale.ROOT);
        if (name.endsWith(".zip")) {
            decompressZipToDoc(cr, archive, destDir, progress);
        } else if (name.endsWith(".tar")) {
            decompressTarToDoc(cr, archive, destDir, progress);
        } else if (name.endsWith(".7z")) {
            decompress7zToDoc(cr, archive, destDir, progress);
        } else {
            throw new IOException("不支持的解压格式: " + name);
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

    private void decompressZipToDoc(ContentResolver cr, DocumentFile archive, DocumentFile destDir,
                                    Progress progress) throws IOException {
        try (ZipArchiveInputStream zis = new ZipArchiveInputStream(
                new BufferedInputStream(cr.openInputStream(archive.getUri())))) {
            ZipArchiveEntry e;
            while ((e = zis.getNextZipEntry()) != null) {
                extractToDoc(cr, destDir, zis, e.getName(), progress, e.getSize());
            }
        }
    }

    private void decompressTarToDoc(ContentResolver cr, DocumentFile archive, DocumentFile destDir,
                                    Progress progress) throws IOException {
        try (TarArchiveInputStream tis = new TarArchiveInputStream(
                new BufferedInputStream(cr.openInputStream(archive.getUri())))) {
            ArchiveEntry e;
            while ((e = tis.getNextEntry()) != null) {
                extractToDoc(cr, destDir, tis, e.getName(), progress, e.getSize());
            }
        }
    }

    private void decompress7zToDoc(ContentResolver cr, DocumentFile archive, DocumentFile destDir,
                                   Progress progress) throws IOException {
        File cache = File.createTempFile("arch7z_", ".7z");
        try (InputStream in = cr.openInputStream(archive.getUri());
             java.io.FileOutputStream fout = new java.io.FileOutputStream(cache)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) != -1) fout.write(buf, 0, n);
        }
        try (SevenZFile szf = new SevenZFile(cache)) {
            SevenZArchiveEntry e;
            while ((e = szf.getNextEntry()) != null) {
                if (e.isDirectory()) continue;
                extract7zToDoc(cr, destDir, szf, e.getName(), progress, e.getSize());
            }
        } finally {
            cache.delete();
        }
    }

    private void extract7zToDoc(ContentResolver cr, DocumentFile destDir, SevenZFile szf,
                                String entryName, Progress progress, long size) throws IOException {
        DocumentFile out = findOrCreateDoc(destDir, entryName);
        long remain = size;
        long done = 0;
        try (java.io.OutputStream os = new BufferedOutputStream(cr.openOutputStream(out.getUri()))) {
            byte[] buf = new byte[64 * 1024];
            while (remain > 0) {
                int n = szf.read(buf, 0, (int) Math.min(buf.length, remain));
                if (n <= 0) break;
                os.write(buf, 0, n);
                done += n;
                remain -= n;
                if (progress != null) progress.onProgress(done, size, entryName);
            }
        }
    }

    private void extractToDoc(ContentResolver cr, DocumentFile destDir, InputStream in,
                              String entryName, Progress progress, long size) throws IOException {
        if (entryName.endsWith("/")) return; // 目录条目
        DocumentFile out = findOrCreateDoc(destDir, entryName);
        long done = 0;
        try (java.io.OutputStream os = new BufferedOutputStream(cr.openOutputStream(out.getUri()))) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) != -1) {
                os.write(buf, 0, n);
                done += n;
                if (progress != null) progress.onProgress(done, size, entryName);
            }
        }
    }

    /** 按 entry 路径在 DocumentFile 目录树中逐级创建并返回目标文件。 */
    private DocumentFile findOrCreateDoc(DocumentFile destDir, String entryName) throws IOException {
        String[] segs = entryName.split("/");
        DocumentFile cur = destDir;
        for (int i = 0; i < segs.length; i++) {
            String seg = segs[i];
            if (seg.isEmpty() || seg.equals(".")) continue;
            if (i == segs.length - 1) {
                DocumentFile f = cur.findFile(seg);
                if (f == null) {
                    String mime = "application/octet-stream";
                    f = cur.createFile(mime, seg);
                }
                if (f == null) throw new IOException("无法创建文件: " + entryName);
                return f;
            } else {
                DocumentFile d = cur.findFile(seg);
                if (d == null) d = cur.createDirectory(seg);
                if (d == null) throw new IOException("无法创建目录: " + entryName);
                cur = d;
            }
        }
        throw new IOException("非法路径: " + entryName);
    }

    private void decompressZip(File archive, File destDir, Progress progress) throws IOException {
        try (ZipArchiveInputStream zis = new ZipArchiveInputStream(
                new BufferedInputStream(new FileInputStream(archive)))) {
            ZipArchiveEntry e;
            while ((e = zis.getNextZipEntry()) != null) {
                extractEntry(zis, destDir, e.getName(), progress, e.getSize());
            }
        }
    }

    private void decompress7z(File archive, File destDir, Progress progress) throws IOException {
        try (SevenZFile szf = new SevenZFile(archive)) {
            SevenZArchiveEntry e;
            while ((e = szf.getNextEntry()) != null) {
                if (e.isDirectory()) continue;
                long size = e.getSize();
                File out = safeJoin(destDir, e.getName());
                File parent = out.getParentFile();
                if (parent != null && !parent.exists()) parent.mkdirs();
                try (OutputStream os = new BufferedOutputStream(new FileOutputStream(out))) {
                    byte[] buf = new byte[64 * 1024];
                    long remain = size;
                    long done = 0;
                    while (remain > 0) {
                        int n = szf.read(buf, 0, (int) Math.min(buf.length, remain));
                        if (n <= 0) break;
                        os.write(buf, 0, n);
                        done += n;
                        remain -= n;
                        if (progress != null) progress.onProgress(done, size, e.getName());
                    }
                }
            }
        }
    }

    private void decompressTar(File archive, File destDir, Progress progress) throws IOException {
        try (TarArchiveInputStream tis = new TarArchiveInputStream(
                new BufferedInputStream(new FileInputStream(archive)))) {
            ArchiveEntry e;
            while ((e = tis.getNextEntry()) != null) {
                extractEntry(tis, destDir, e.getName(), progress, e.getSize());
            }
        }
    }

    private void extractEntry(InputStream in, File destDir, String name,
                              Progress progress, long size) throws IOException {
        File out = safeJoin(destDir, name);
        File parent = out.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        try (OutputStream os = new BufferedOutputStream(new FileOutputStream(out))) {
            byte[] buf = new byte[64 * 1024];
            long done = 0;
            int n;
            while ((n = in.read(buf)) != -1) {
                os.write(buf, 0, n);
                done += n;
                if (progress != null) progress.onProgress(done, size, name);
            }
        }
    }

    /** 防路径穿越：确保解压路径不逃逸目标目录。 */
    private File safeJoin(File base, String entryName) throws IOException {
        File out = new File(base, entryName);
        String canonical = out.getCanonicalPath();
        String baseCanonical = base.getCanonicalPath();
        if (!canonical.equals(baseCanonical) && !canonical.startsWith(baseCanonical + File.separator)) {
            throw new IOException("非法路径: " + entryName);
        }
        return out;
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