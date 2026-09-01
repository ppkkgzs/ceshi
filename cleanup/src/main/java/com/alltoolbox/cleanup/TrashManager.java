package com.alltoolbox.cleanup;

import android.content.Context;

import com.alltoolbox.core.AppContext;
import com.alltoolbox.core.file.FileUtil;
import com.alltoolbox.core.task.TaskExecutor;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 回收站：删除文件移入应用内部目录，可恢复或清空，可开关。
 * 回收站位置：{dataDir}/files/trash
 */
public final class TrashManager {

    private static volatile TrashManager sInstance;
    private boolean enabled = true;

    public static TrashManager get() {
        if (sInstance == null) {
            synchronized (TrashManager.class) {
                if (sInstance == null) sInstance = new TrashManager();
            }
        }
        return sInstance;
    }

    public File trashDir() {
        Context ctx = AppContext.get();
        return new File(ctx.getFilesDir(), "trash");
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /** 将文件移入回收站。返回是否成功。 */
    public boolean trash(List<File> files) {
        if (!enabled) {
            boolean ok = true;
            for (File f : files) ok &= FileUtil.deleteRecursively(f);
            return ok;
        }
        File dir = trashDir();
        if (!dir.exists() && !dir.mkdirs()) return false;
        boolean ok = true;
        for (File f : files) {
            File dst = new File(dir, f.getName());
            if (dst.exists()) dst = new File(dir, f.getName() + "_" + System.currentTimeMillis());
            try {
                copyMove(f, dst);
                ok &= FileUtil.deleteRecursively(f);
            } catch (Exception e) {
                ok = false;
            }
        }
        return ok;
    }

    private void copyMove(File src, File dst) throws Exception {
        if (src.isDirectory()) {
            if (!dst.mkdirs()) throw new Exception("mkdir fail");
            File[] ch = src.listFiles();
            if (ch != null) for (File c : ch) copyMove(c, new File(dst, c.getName()));
        } else {
            File parent = dst.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            try (java.io.InputStream in = new java.io.FileInputStream(src);
                 java.io.OutputStream out = new java.io.FileOutputStream(dst)) {
                byte[] b = new byte[65536];
                int n;
                while ((n = in.read(b)) != -1) out.write(b, 0, n);
            }
        }
    }

    /** 回收站中项目列表。 */
    public List<File> listTrash() {
        File dir = trashDir();
        File[] ch = dir.listFiles();
        List<File> out = new ArrayList<>();
        if (ch != null) java.util.Collections.addAll(out, ch);
        return out;
    }

    /** 从回收站恢复单个项目到目标目录。 */
    public boolean restore(File item, File targetDir) {
        File dst = new File(targetDir, item.getName());
        try {
            copyMove(item, dst);
            return FileUtil.deleteRecursively(item);
        } catch (Exception e) {
            return false;
        }
    }

    /** 彻底清空回收站。 */
    public boolean clear() {
        File dir = trashDir();
        if (!dir.exists()) return true;
        return FileUtil.deleteRecursively(dir) || !dir.exists();
    }

    /** 清空回收站（异步）。 */
    public void clearAsync(Runnable onDone) {
        TaskExecutor.get().io().execute(() -> {
            clear();
            if (onDone != null) onDone.run();
        });
    }
}