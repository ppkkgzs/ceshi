package com.alltoolbox.fbrowser.model;

import com.alltoolbox.core.file.FileUtil;

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
        this.file = file;
        this.name = file.getName();
        this.directory = file.isDirectory();
        this.size = directory ? 0 : file.length();
        this.lastModified = file.lastModified();
        this.hidden = file.isHidden() || name.startsWith(".");
        this.kind = FileUtil.getKind(file);
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