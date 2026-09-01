package com.alltoolbox.fops;

import com.alltoolbox.core.file.FileUtil;
import com.alltoolbox.core.permission.ShizukuShell;
import com.alltoolbox.core.task.TaskExecutor;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

/**
 * 文件操作核心：复制、移动、删除、重命名、新建、批量重命名、哈希、属性统计。
 *
 * 注意：Android 10+ 外部公共目录目标需要 SAF（由上层通过 MediaStore/DocumentFile
 * 处理）；此控制器的 File 直写路径用于应用数据目录、Root 或已授权环境。
 */
public final class FileOpsController {

    // 剪切/复制 剪贴板状态
    private List<File> clipSources = new ArrayList<>();
    private boolean cutMode = false;

    private static final FileOpsController sInstance = new FileOpsController();

    public static FileOpsController get() {
        return sInstance;
    }

    private FileOpsController() {
    }

    // ---------- 剪贴板 ----------

    public void setClip(List<File> sources, boolean cut) {
        this.clipSources = new ArrayList<>(sources);
        this.cutMode = cut;
    }

    public List<File> getClipSources() {
        return clipSources;
    }

    public boolean isCutMode() {
        return cutMode;
    }

    public void clearClip() {
        clipSources = new ArrayList<>();
    }

    public boolean hasClip() {
        return !clipSources.isEmpty();
    }

    // ---------- 新建 ----------

    public File createFolder(File parent, String name) {
        File f = new File(parent, name);
        if (f.mkdirs() || f.isDirectory()) return f;
        // 受限目录：普通 API 失败时走 Shizuku shell
        if (ShizukuShell.isReady() && ShizukuShell.mkdir(f.getAbsolutePath())) return f;
        return null;
    }

    public File createFile(File parent, String name) {
        File f = new File(parent, name);
        if (f.exists()) return null;
        try {
            if (f.createNewFile()) return f;
        } catch (IOException e) {
            // 忽略
        }
        // 受限目录：普通 API 失败时走 Shizuku shell
        if (ShizukuShell.isReady() && ShizukuShell.createFile(f.getAbsolutePath())) return f;
        return null;
    }

    // ---------- 重命名 ----------

    public boolean rename(File src, String newName) {
        if (newName == null || newName.trim().isEmpty()) return false;
        File dst = new File(src.getParentFile(), newName);
        if (!src.canRead()) {
            // 受限目录：普通 API 无法读取，走 Shizuku shell 移动
            return ShizukuShell.isReady()
                    && ShizukuShell.move(src.getAbsolutePath(), dst.getAbsolutePath());
        }
        return !dst.exists() && src.renameTo(dst);
    }

    // ---------- 删除 ----------

    public boolean delete(List<File> files) {
        boolean ok = true;
        for (File f : files) {
            ok &= deleteOne(f);
        }
        return ok;
    }

    private boolean deleteOne(File f) {
        // 受限目录（普通 API 无法读取/删除会误报成功）时优先走 Shizuku shell
        if (!f.canRead() && ShizukuShell.isReady()) {
            return ShizukuShell.delete(f.getAbsolutePath());
        }
        return FileUtil.deleteRecursively(f);
    }

    // ---------- 复制 / 移动 ----------

    public interface ProgressListener {
        void onProgress(long done, long total, File current);
    }

    /** 深度复制。 */
    public void copy(File src, File dstDir, ProgressListener listener) throws IOException {
        File target = new File(dstDir, src.getName());
        // 受限目录源：普通 API 无法读取，走 Shizuku shell 复制
        if (!src.canRead() && ShizukuShell.isReady()) {
            if (!ShizukuShell.copy(src.getAbsolutePath(), target.getAbsolutePath())) {
                throw new IOException("复制失败（Shizuku）: " + src);
            }
            if (listener != null) listener.onProgress(1, 1, src);
            return;
        }
        copyRecursively(src, target, listener);
    }

    private void copyRecursively(File src, File dst, ProgressListener listener) throws IOException {
        if (src.isDirectory()) {
            if (!dst.exists() && !dst.mkdirs()) {
                throw new IOException("无法创建目录 " + dst);
            }
            File[] children = src.listFiles();
            if (children != null) {
                for (File child : children) {
                    copyRecursively(child, new File(dst, child.getName()), listener);
                }
            }
        } else {
            copyFile(src, dst, listener);
        }
    }

    private void copyFile(File src, File dst, ProgressListener listener) throws IOException {
        try (InputStream in = new FileInputStream(src);
             OutputStream out = new FileOutputStream(dst)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
                if (listener != null) listener.onProgress(src.length(), src.length(), src);
            }
        }
    }

    /** 移动 = 复制 + 删除源。 */
    public void move(File src, File dstDir, ProgressListener listener) throws IOException {
        File target = new File(dstDir, src.getName());
        // 受限目录：普通 API 无法读取源，直接走 Shizuku shell 移动
        if (!src.canRead() && ShizukuShell.isReady()) {
            if (!ShizukuShell.move(src.getAbsolutePath(), target.getAbsolutePath())) {
                throw new IOException("移动失败（Shizuku）: " + src);
            }
            if (listener != null) listener.onProgress(1, 1, src);
            return;
        }
        copy(src, dstDir, listener);
        FileUtil.deleteRecursively(src);
    }

    /** 粘贴剪贴板内容到目标目录。按模式执行。 */
    public void paste(File targetDir, ProgressListener listener) throws IOException {
        if (!hasClip() || targetDir == null) throw new IOException("剪贴板为空或目标无效");
        for (File src : clipSources) {
            File inDir = src.getParentFile();
            // 防止将目录移动/复制到自身子目录
            if (inDir != null && targetDir.getAbsolutePath().startsWith(src.getAbsolutePath())) {
                throw new IOException("无法复制到自身子目录");
            }
            if (cutMode) move(src, targetDir, listener);
            else copy(src, targetDir, listener);
        }
        if (cutMode) clearClip();
    }

    public void pasteAsync(File targetDir, ProgressListener listener,
                           Runnable onDone, java.util.function.Consumer<Exception> onError) {
        TaskExecutor.get().io().execute(() -> {
            try {
                paste(targetDir, listener);
                if (onDone != null) onDone.run();
            } catch (Exception e) {
                if (onError != null) onError.accept(e);
            }
        });
    }

    // ---------- 批量重命名 ----------

    /**
     * 批量重命名。支持三种规则，可通过模板组合。
     *
     * @param files   待重命名文件
     * @param mode    0=序号模板 1=前后缀 2=查找替换
     * @param startNo 序号起始值（mode=0）
     * @param name    主名称（mode=0/1 使用）
     * @param prefix  前缀（mode=1）
     * @param suffix  后缀（mode=1）
     * @param find    查找串（mode=2）
     * @param replace 替换串（mode=2）
     * @return 实际重命名成功的数量
     */
    public int batchRename(List<File> files, int mode, int startNo,
                           String name, String prefix, String suffix,
                           String find, String replace) {
        int ok = 0;
        int idx = startNo;
        for (File f : files) {
            if (f.isDirectory()) continue;
            String base = f.getName();
            String ext = "";
            int dot = base.lastIndexOf('.');
            if (dot > 0) {
                ext = base.substring(dot);
                base = base.substring(0, dot);
            }
            String newBase;
            switch (mode) {
                case 0: // 序号模板: 名称_序号
                    newBase = (name == null || name.isEmpty() ? base : name) + "_" + (idx++);
                    break;
                case 1: // 前后缀
                    newBase = (prefix == null ? "" : prefix) + base + (suffix == null ? "" : suffix);
                    break;
                case 2: // 查找替换
                    newBase = find == null || find.isEmpty()
                            ? base
                            : base.replace(find, replace == null ? "" : replace);
                    break;
                default:
                    continue;
            }
            File dst = new File(f.getParentFile(), newBase + ext);
            if (srcExists(dst)) continue;
            if (f.renameTo(dst)) ok++;
        }
        return ok;
    }

    private boolean srcExists(File f) {
        return f.exists();
    }

    // ---------- 哈希 ----------

    public interface HashCallback {
        void onHash(String hex);
    }

    public void hashAsync(File file, String algorithm, HashCallback cb) {
        TaskExecutor.get().io().execute(() -> {
            String hex = computeHash(file, algorithm);
            if (cb != null) cb.onHash(hex);
        });
    }

    public String computeHash(File file, String algorithm) {
        try (InputStream in = new FileInputStream(file)) {
            MessageDigest md = MessageDigest.getInstance(algorithm);
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) {
                md.update(buffer, 0, read);
            }
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest()) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }
}