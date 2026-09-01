package com.alltoolbox.archive;

import net.sf.sevenzipjbinding.ExtractAskMode;
import net.sf.sevenzipjbinding.ExtractOperationResult;
import net.sf.sevenzipjbinding.IArchiveExtractCallback;
import net.sf.sevenzipjbinding.IArchiveOpenCallback;
import net.sf.sevenzipjbinding.IInArchive;
import net.sf.sevenzipjbinding.ISequentialOutStream;
import net.sf.sevenzipjbinding.PropID;
import net.sf.sevenzipjbinding.SevenZip;
import net.sf.sevenzipjbinding.SevenZipException;
import net.sf.sevenzipjbinding.impl.RandomAccessFileInStream;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 基于 7-Zip-JBinding（原生 7-Zip 引擎）的解压实现。
 *
 * 相比纯 Java 的 commons-compress / java.util.zip，原生引擎能稳定处理
 * 带 Data Descriptor / Zip64 的 ZIP，以及 7z、rar 等更多格式（自动识别格式）。
 *
 * 本类同时承载解压前/中的异常处理与前置校验：
 *  - 支持加密压缩包（密码解压、密码错误识别 {@link #MSG_PASSWORD}）
 *  - 前置校验：完整性 / 磁盘空间 / 写入权限 / 是否需要密码 / 同名冲突计数
 *  - 异常分层：把底层 0x1 / WRONG_PASSWORD 等转成对应中文提示，不再笼统报“解压失败”
 *
 * 注：本项目按 LGPL-2.1 以「库」形式使用并随 APK 分发，已附开源声明与许可证文本。
 *
 * 用法：
 *  - {@link #extract(File, File, ArchiveManager.Progress)}                全量解压
 *  - {@link #extract(File, File, String, boolean, ArchiveManager.Progress)} 全量解压（密码/覆盖策略）
 *  - {@link #extractPrefix(File, String, File, String, boolean, ArchiveManager.Progress)}  解压某个虚拟目录层
 *  - {@link #extractEntry(File, String, File, String)}                    导出单个条目
 */
public final class SevenZipEngine {

    private SevenZipEngine() {
    }

    // ------------------------------------------------------------------
    // 解压入口
    // ------------------------------------------------------------------

    /** 全量解压整个压缩包到目标目录。 */
    public static void extract(File archive, File destDir, ArchiveManager.Progress progress)
            throws IOException {
        extractTo(archive, destDir, null, null, null, null, true, progress);
    }

    /** 全量解压（可指定密码与同名覆盖策略）。 */
    public static void extract(File archive, File destDir, String password,
                               boolean overwriteExisting, ArchiveManager.Progress progress)
            throws IOException {
        extractTo(archive, destDir, null, null, null, password, overwriteExisting, progress);
    }

    /**
     * 解压某个前缀（虚拟目录层）下的内容到目标目录。
     *
     * @param prefix 相对压缩包根的目录前缀；顶层传 "" 或 null，表示解压整个压缩包。
     * 目标目录中会重建该前缀下的相对路径。
     */
    public static void extractPrefix(File archive, String prefix, File destDir,
                                     ArchiveManager.Progress progress) throws IOException {
        extractPrefix(archive, prefix, destDir, null, true, progress);
    }

    /** 解压某个虚拟目录层（可指定密码与同名覆盖策略）。 */
    public static void extractPrefix(File archive, String prefix, File destDir,
                                     String password, boolean overwriteExisting,
                                     ArchiveManager.Progress progress) throws IOException {
        String p = (prefix == null) ? "" : prefix;
        if (!p.isEmpty() && !p.endsWith("/")) p = p + "/";
        extractTo(archive, destDir, p, null, null, password, overwriteExisting, progress);
    }

    /** 把压缩包内某个条目原样导出到指定文件（用于打开「包中包」前先解到临时文件）。 */
    public static void extractEntry(File archive, String entryPath, File outFile)
            throws IOException {
        extractEntry(archive, entryPath, outFile, null);
    }

    /** 导出单个条目（可指定密码）。 */
    public static void extractEntry(File archive, String entryPath, File outFile, String password)
            throws IOException {
        File parent = outFile.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        extractTo(archive, parent, null, entryPath, outFile, password, true, null);
    }

    // ------------------------------------------------------------------
    // 前置校验（解压执行前在 Java 上层尽量做完，减少底层 0x1 无差别报错）
    // ------------------------------------------------------------------

    /** 解压失败/异常提示文案。 */
    public static final String MSG_PASSWORD =
            "压缩包密码错误，请核对密码；也可能压缩包文件已损坏";
    public static final String MSG_CORRUPT =
            "压缩包文件已损坏或为空，请检查文件是否完整后重试";
    public static final String MSG_SPACE =
            "手机存储空间不足，请清理手机空间后重试，或更换解压存储位置";
    public static final String MSG_PERMISSION =
            "解压失败，请检查：手机存储空间、目录写入权限，或者压缩包文件是否损坏。"
                    + "可以尝试更换解压路径，或切换文件名编码重试";

    /** 完整性简单校验：文件存在且大小 &gt; 0。 */
    public static void checkIntegrity(File archive) throws IOException {
        if (archive == null || !archive.exists() || archive.length() == 0) {
            throw new IOException(MSG_CORRUPT);
        }
    }

    /** 估算压缩包解压后的原始大小（各条目公共 Size 求和；读取失败返回 0）。 */
    public static long estimateUncompressedSize(File archive) {
        RandomAccessFile raf = null;
        RandomAccessFileInStream is = null;
        IInArchive in = null;
        try {
            raf = new RandomAccessFile(archive, "r");
            is = new RandomAccessFileInStream(raf);
            in = SevenZip.openInArchive(null, is, (IArchiveOpenCallback) null);
            int n = in.getNumberOfItems();
            long sum = 0;
            for (int i = 0; i < n; i++) {
                Object v = in.getProperty(i, PropID.SIZE);
                if (v instanceof Long) sum += (Long) v;
            }
            return sum;
        } catch (Exception e) {
            return 0;
        } finally {
            closeQuietly(in, is, raf);
        }
    }

    /** 探测压缩包是否需要密码（加密了某些条目，或包头加密打开失败）。 */
    public static boolean requiresPassword(File archive) {
        RandomAccessFile raf = null;
        RandomAccessFileInStream is = null;
        IInArchive in = null;
        try {
            raf = new RandomAccessFile(archive, "r");
            is = new RandomAccessFileInStream(raf);
            try {
                in = SevenZip.openInArchive(null, is, (IArchiveOpenCallback) null);
            } catch (SevenZipException e) {
                // 包头加密：没有密码时打开即抛“密码”类异常
                return isPasswordLike(e);
            }
            int n = in.getNumberOfItems();
            for (int i = 0; i < n; i++) {
                Object v = in.getProperty(i, PropID.ENCRYPTED);
                if (Boolean.TRUE.equals(v)) return true;
            }
            return false;
        } catch (Exception e) {
            return false;
        } finally {
            closeQuietly(in, is, raf);
        }
    }

    /** 目录写入权限预检测：尝试创建并删除一个临时文件。 */
    public static boolean isWritable(File dir) {
        if (dir == null) return false;
        File test = new File(dir, ".ext_test_" + System.nanoTime());
        try {
            File p = test.getParentFile();
            if (p != null && !p.exists()) p.mkdirs();
            FileOutputStream fos = new FileOutputStream(test);
            fos.close();
            test.delete();
            return true;
        } catch (Exception e) {
            try { test.delete(); } catch (Exception ignored) { }
            return false;
        }
    }

    /** 磁盘可用空间是否足够。未知（free&lt;=0）时视为可通过。 */
    public static boolean diskSpaceOk(File dir, long neededBytes) {
        if (neededBytes <= 0) return true;
        File u = dir;
        while (u != null && !u.exists()) u = u.getParentFile();
        if (u == null) return true;
        long free = u.getFreeSpace();
        if (free <= 0) return true; // 无法判断，交由运行时兜底
        return free >= neededBytes;
    }

    /**
     * 统计解压到 {@code destDir} 时，压缩包（{@code prefix} 层下）将与之冲突的同名文件数。
     * 用于解压前弹「覆盖 / 跳过全部 / 取消」选择。
     */
    public static int countConflicts(File archive, String prefix, File destDir) {
        List<String> paths = listEntryPaths(archive, prefix);
        int conflicts = 0;
        for (String p : paths) {
            if (p.endsWith("/")) continue;
            try {
                File out = safeJoin(destDir, p);
                if (out.exists()) conflicts++;
            } catch (IOException ignored) {
            }
        }
        return conflicts;
    }

    /** 列出压缩包内某前缀下的全部条目路径（用于冲突统计等）。 */
    public static List<String> listEntryPaths(File archive, String prefix) {
        String p = (prefix == null) ? "" : prefix;
        if (!p.isEmpty() && !p.endsWith("/")) p = p + "/";
        List<String> out = new ArrayList<>();
        RandomAccessFile raf = null;
        RandomAccessFileInStream is = null;
        IInArchive in = null;
        try {
            raf = new RandomAccessFile(archive, "r");
            is = new RandomAccessFileInStream(raf);
            in = SevenZip.openInArchive(null, is, (IArchiveOpenCallback) null);
            int n = in.getNumberOfItems();
            for (int i = 0; i < n; i++) {
                String path = in.getStringProperty(i, PropID.PATH);
                if (path == null) continue;
                if (!p.isEmpty() && !path.startsWith(p)) continue;
                out.add(path);
            }
        } catch (Exception e) {
            // 返回当前已收集的部分
        } finally {
            closeQuietly(in, is, raf);
        }
        return out;
    }

    private static void closeQuietly(IInArchive in, RandomAccessFileInStream is,
                                     RandomAccessFile raf) {
        if (in != null) {
            try { in.close(); } catch (SevenZipException ignored) { }
        }
        if (is != null) {
            try { is.close(); } catch (IOException ignored) { }
        }
        if (raf != null) {
            try { raf.close(); } catch (IOException ignored) { }
        }
    }

    // ------------------------------------------------------------------

    private static IInArchive open(RandomAccessFileInStream in, String password)
            throws SevenZipException {
        if (password != null) {
            // 带密码打开（加密压缩包）
            return SevenZip.openInArchive(null, in, password);
        }
        return SevenZip.openInArchive(null, in, (IArchiveOpenCallback) null);
    }

    private static void extractTo(File archive, File destDir, String prefix, String onlyEntry,
                                  File singleOut, String password, boolean overwriteExisting,
                                  ArchiveManager.Progress progress) throws IOException {
        RandomAccessFile raf = null;
        RandomAccessFileInStream inStream = null;
        IInArchive inArchive = null;
        ExtractCallback cb = null;
        try {
            raf = new RandomAccessFile(archive, "r");
            inStream = new RandomAccessFileInStream(raf);
            inArchive = open(inStream, password);
            cb = new ExtractCallback(inArchive, prefix, onlyEntry, destDir,
                    singleOut, overwriteExisting, progress);
            inArchive.extract(null, false, cb);
            if (cb.passwordWrong) {
                throw new ArchivePasswordException(MSG_PASSWORD);
            }
            if (!cb.failed.isEmpty()) {
                throw new ArchiveErrorException(MSG_PERMISSION + "（" + cb.failed + "）");
            }
        } catch (SevenZipException e) {
            throw classify(e);
        } finally {
            if (cb != null) cb.closeStreams();
            closeQuietly(inArchive, inStream, raf);
        }
    }

    /** 异常分层：把底层 7z JNI 抛出的原始异常翻译成可读的中文提示。 */
    private static IOException classify(SevenZipException e) {
        String m = e.getMessage();
        String lm = (m == null) ? "" : m.toLowerCase();
        Throwable cause = e.getCause();
        String cl = (cause != null && cause.getMessage() != null)
                ? cause.getMessage().toLowerCase() : "";

        // 磁盘已满
        if (lm.contains("no space left on device") || cl.contains("no space left on device")
                || lm.contains("disk full")) {
            return new ArchiveDiskFullException(MSG_SPACE);
        }
        // 无写入权限
        if (lm.contains("permission denied") || cl.contains("permission denied")) {
            return new ArchivePermissionException(MSG_PERMISSION);
        }
        // 密码类错误（覆盖“WRONG_PASSWORD”等）
        if (isPasswordLike(e)) {
            return new ArchivePasswordException(MSG_PASSWORD);
        }
        // 通用失败（HRESULT:0x1 等）：给出综合排查文案
        return new ArchiveErrorException(String.format(MSG_PERMISSION, m));
    }

    private static boolean isPasswordLike(SevenZipException e) {
        String m = e.getMessage();
        if (m == null) return false;
        String k = m.toLowerCase();
        if (k.contains("wrong password") || k.contains("encryption")
                || k.contains("crypt") || k.contains("пароль")) {
            return true;
        }
        return k.contains("password");
    }

    /** 侦测密码类错误：用于打开压缩包失败时的判断。 */
    private static boolean isPasswordLike2(String message) {
        if (message == null) return false;
        String k = message.toLowerCase();
        return k.contains("wrong password") || k.contains("encryption")
                || k.contains("crypt") || k.contains("password")
                || k.contains("пароль");
    }

    private static boolean isFolder(IInArchive archive, int index, String path) {
        try {
            Object v = archive.getProperty(index, PropID.IS_FOLDER);
            if (v instanceof Boolean) return (Boolean) v;
        } catch (Exception ignore) {
        }
        return path.endsWith("/");
    }

    /** 防路径穿越：确保解压路径不逃逸目标目录。 */
    private static File safeJoin(File base, String entryName) throws IOException {
        File out = new File(base, entryName);
        String canonical = out.getCanonicalPath();
        String baseCanonical = base.getCanonicalPath();
        if (!canonical.equals(baseCanonical)
                && !canonical.startsWith(baseCanonical + File.separator)) {
            throw new IOException("非法路径: " + entryName);
        }
        return out;
    }

    // ------------------------------------------------------------------
    // 自定义异常
    // ------------------------------------------------------------------

    /** 密码错误（WRONG_PASSWORD）。 */
    public static final class ArchivePasswordException extends IOException {
        private static final long serialVersionUID = 1L;

        ArchivePasswordException(String message) {
            super(message);
        }
    }

    /** 通用解压错误（含 HRESULT:0x1 等），message 已含综合排查文案。 */
    public static final class ArchiveErrorException extends IOException {
        private static final long serialVersionUID = 1L;

        ArchiveErrorException(String message) {
            super(message);
        }
    }

    /** 磁盘空间不足。 */
    public static final class ArchiveDiskFullException extends IOException {
        private static final long serialVersionUID = 1L;

        ArchiveDiskFullException(String message) {
            super(message);
        }
    }

    /** 无写入权限。 */
    public static final class ArchivePermissionException extends IOException {
        private static final long serialVersionUID = 1L;

        ArchivePermissionException(String message) {
            super(message);
        }
    }

    // ------------------------------------------------------------------
    // 提取回调
    // ------------------------------------------------------------------

    /** 7-Zip 提取回调：把每个条目的字节流式写入目标文件。 */
    private static final class ExtractCallback implements IArchiveExtractCallback {

        private final IInArchive archive;
        private final String prefix;          // null=无过滤
        private final String onlyEntry;       // 仅导出该条目，否则 null
        private final File destDir;
        private final File singleOut;         // 与 onlyEntry 搭配：直接写入该文件
        private final boolean overwriteExisting;
        private final ArchiveManager.Progress progress;
        private final List<OutputStream> opened = new ArrayList<>();
        private final Set<String> failed = new HashSet<>();
        private boolean passwordWrong;
        private long total;

        ExtractCallback(IInArchive archive, String prefix, String onlyEntry, File destDir,
                        File singleOut, boolean overwriteExisting,
                        ArchiveManager.Progress progress) {
            this.archive = archive;
            this.prefix = prefix;
            this.onlyEntry = onlyEntry;
            this.destDir = destDir;
            this.singleOut = singleOut;
            this.overwriteExisting = overwriteExisting;
            this.progress = progress;
        }

        void closeStreams() {
            for (OutputStream os : opened) {
                try { os.close(); } catch (IOException ignore) { }
            }
            opened.clear();
        }

        private ISequentialOutStream streamFor(OutputStream os) {
            opened.add(os);
            return data -> {
                try {
                    os.write(data);
                    return data.length;
                } catch (IOException e) {
                    throw new SevenZipException(e);
                }
            };
        }

        @Override
        public ISequentialOutStream getStream(int index, ExtractAskMode extractAskMode)
                throws SevenZipException {
            if (extractAskMode != ExtractAskMode.EXTRACT) {
                return null; // 仅执行解压
            }
            String path;
            try {
                path = archive.getStringProperty(index, PropID.PATH);
            } catch (SevenZipException e) {
                path = null;
            }
            if (path == null || path.isEmpty()) return null;
            boolean folder = isFolder(archive, index, path);

            try {
                if (onlyEntry != null) {
                    if (!path.equals(onlyEntry)) return null;
                    if (folder) return null;
                    if (singleOut != null) {
                        File parent = singleOut.getParentFile();
                        if (parent != null && !parent.exists()) parent.mkdirs();
                        return streamFor(new FileOutputStream(singleOut));
                    }
                } else {
                    String rel = path;
                    if (prefix != null) {
                        if (!path.startsWith(prefix)) return null;
                        rel = path.substring(prefix.length());
                        if (rel.isEmpty()) return null;
                    }
                    if (folder) {
                        File d = safeJoin(destDir, rel);
                        if (!d.exists()) d.mkdirs();
                        return null;
                    }
                    File out = safeJoin(destDir, rel);
                    // 同名文件策略：跳过全部（不覆盖）时，已存在的文件直接不写入
                    if (!overwriteExisting && out.exists()) {
                        return null;
                    }
                    File parent = out.getParentFile();
                    if (parent != null && !parent.exists()) parent.mkdirs();
                    return streamFor(new FileOutputStream(out, !overwriteExisting && out.exists()));
                }
            } catch (IOException e) {
                throw new SevenZipException(e);
            }
            return null;
        }

        @Override
        public void prepareOperation(ExtractAskMode extractAskMode) {
        }

        @Override
        public void setOperationResult(ExtractOperationResult extractOperationResult)
                throws SevenZipException {
            if (extractOperationResult != ExtractOperationResult.OK) {
                if (extractOperationResult == ExtractOperationResult.WRONG_PASSWORD) {
                    passwordWrong = true;
                } else {
                    failed.add(extractOperationResult.toString());
                }
            }
        }

        @Override
        public void setTotal(long l) {
            total = l;
        }

        @Override
        public void setCompleted(long l) {
            if (progress != null) progress.onProgress(l, total, null);
        }
    }
}