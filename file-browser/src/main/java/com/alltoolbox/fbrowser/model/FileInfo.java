package com.alltoolbox.fbrowser.model;

import com.alltoolbox.core.file.FileUtil;
import com.alltoolbox.core.permission.ShizukuShell;

import java.io.File;
import java.util.Objects;

/**
 * 文件条目模型，封装磁盘 File 的展示所需信息。
 */
public final class FileInfo {

    private final File file;
    private final String name;
    private final boolean directory;
    private final long size;
    private final long lastModified;
    private final boolean hidden;
    private final FileUtil.FileKind kind;

    public FileInfo(File file) {
        this(file, file.getName(), file.isDirectory(),
                file.isDirectory() ? 0 : file.length(),
                file.lastModified(),
                file.isHidden() || file.getName().startsWith("."),
                FileUtil.getKind(file));
    }

    private FileInfo(File file, String name, boolean directory, long size,
                     long lastModified, boolean hidden, FileUtil.FileKind kind) {
        this.file = file;
        this.name = name;
        this.directory = directory;
        this.size = size;
        this.lastModified = lastModified;
        this.hidden = hidden;
        this.kind = kind;
    }

    /**
     * 由 Shizuku（adb/shell 权限）读取到的受限目录条目构造。
     * 受限目录（Android/data、Android/obb）普通 File API 无法读取元数据，
     * 元数据全部来自 {@link ShizukuShell.Entry}。
     */
    public static FileInfo fromShizukuEntry(ShizukuShell.Entry e) {
        File f = new File(e.path);
        FileUtil.FileKind kind = e.directory ? FileUtil.FileKind.FOLDER : FileUtil.getKind(f);
        return new FileInfo(f, e.name, e.directory, e.size, e.lastModified,
                e.name.startsWith("."), kind);
    }

    public File getFile() {
        return file;
    }

    public String getName() {
        return name;
    }

    public boolean isDirectory() {
        return directory;
    }

    public long getSize() {
        return size;
    }

    public long getLastModified() {
        return lastModified;
    }

    public boolean isHidden() {
        return hidden;
    }

    public FileUtil.FileKind getKind() {
        return kind;
    }

    public String getPath() {
        return file.getAbsolutePath();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof FileInfo)) return false;
        return Objects.equals(file, ((FileInfo) o).file);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(file);
    }
}