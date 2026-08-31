package com.alltoolbox.core.file;

import android.content.res.AssetManager;
import android.content.res.Resources;
import android.net.Uri;
import android.webkit.MimeTypeMap;

import java.io.File;
import java.io.IOException;
import java.util.Locale;

/**
 * 文件工具集：文件类型判断、专属图标识别、扩展名格式化等。
 */
public final class FileUtil {

    private FileUtil() {
    }

    /** 判断是否为文件夹。 */
    public static boolean isDir(File file) {
        return file != null && file.isDirectory();
    }

    /** 判断是否为隐藏文件（以 . 开头）。 */
    public static boolean isHidden(File file) {
        return file != null && file.getName().startsWith(".");
    }

    /** 获取扩展名（不含点，小写），无扩展名返回空串。 */
    public static String getExtension(String name) {
        if (name == null) return "";
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) return "";
        return name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /** 通过扩展名获取 MIME 类型。 */
    public static String getMimeType(String name) {
        String ext = getExtension(name);
        if (ext.isEmpty()) return "*/*";
        String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
        return mime != null ? mime : "*/*";
    }

    /** 文件类型分组，用于专属图标与筛选。 */
    public enum FileKind {
        FOLDER, IMAGE, VIDEO, AUDIO, APK, so, ARCHIVE, DOCUMENT, PDF, text, other
    }

    /** 依据扩展名判断文件所属分组。 */
    public static FileKind getKind(File file) {
        if (isDir(file)) return FileKind.FOLDER;
        String ext = getExtension(file.getName());
        switch (ext) {
            case "jpg": case "jpeg": case "png": case "gif": case "bmp":
            case "webp": case "svg": case "heic": return FileKind.IMAGE;
            case "mp4": case "mkv": case "avi": case "mov": case "wmv":
            case "flv": case "webm": case "3gp": case "ts": return FileKind.VIDEO;
            case "mp3": case "wav": case "flac": case "aac": case "ogg":
            case "m4a": case "wma": case "opus": case "amr": return FileKind.AUDIO;
            case "apk": case "xapk": case "split": return FileKind.APK;
            case "zip": case "rar": case "7z": case "tar": case "gz":
            case "bz2": case "xz": return FileKind.ARCHIVE;
            case "pdf": return FileKind.PDF;
            case "txt": case "log": case "md": case "xml": case "json":
            case "java": case "kt": case "smali": case "c": case "cpp":
            case "py": case "js": case "sh": case "html": case "htm":
            case "css": case "yml": case "yaml": case "ini": case "conf":
            case "properties": return FileKind.text;
            case "doc": case "docx": case "xls": case "xlsx": case "ppt":
            case "pptx": return FileKind.DOCUMENT;
            default: return FileKind.other;
        }
    }

    /**
     * 魔数嗅探：读取文件头字节判断真实类型，用于扩展名缺失或被改后缀的兜底。
     * 仅返回确定匹配的二进制/文本类型，无法识别时返回 {@link FileKind#other}。
     */
    public static FileKind sniffKind(File file) {
        if (file == null || isDir(file) || file.length() < 4) return FileKind.other;
        byte[] head = readHead(file, 16);
        if (head == null) return FileKind.other;

        // ELF（so 库）
        if (head[0] == 0x7F && head[1] == 'E' && head[2] == 'L' && head[3] == 'F') {
            return FileKind.so;
        }
        // JPEG
        if ((head[0] & 0xFF) == 0xFF && (head[1] & 0xFF) == 0xD8 && (head[2] & 0xFF) == 0xFF) {
            return FileKind.IMAGE;
        }
        // PNG
        if (head[0] == (byte) 0x89 && head[1] == 'P' && head[2] == 'N' && head[3] == 'G') {
            return FileKind.IMAGE;
        }
        // GIF
        String h1 = ascii(head, 4);
        if (h1.equals("GIF8")) return FileKind.IMAGE;
        // BMP
        if (ascii(head, 2).equals("BM")) return FileKind.IMAGE;
        // PDF
        if (ascii(head, 4).equals("%PDF")) return FileKind.PDF;
        // RIFF 容器（WebP 图片 / WAV 音频 / AVI 视频）
        if (ascii(head, 4).equals("RIFF")) {
            String riffType = ascii(head, 8).substring(4, 8);
            if (riffType.equals("WEBP")) return FileKind.IMAGE;
            if (riffType.equals("WAVE")) return FileKind.AUDIO;
            if (riffType.equals("AVI ")) return FileKind.VIDEO;
            return FileKind.other;
        }
        // FLAC
        if (ascii(head, 4).equals("fLaC")) return FileKind.AUDIO;
        // OGG
        if (ascii(head, 4).equals("OggS")) return FileKind.AUDIO;
        // MP3 ID3
        if (ascii(head, 3).equals("ID3")) return FileKind.AUDIO;
        // ZIP 容器（zip/jar/apk）
        if ((head[0] & 0xFF) == 0x50 && (head[1] & 0xFF) == 0x4B && (head[2] & 0xFF) == 0x03
                && (head[3] & 0xFF) == 0x04) {
            return FileKind.APK; // 统一按可拆分归档处理，具体按扩展名细化
        }
        return FileKind.other;
    }

    private static byte[] readHead(File file, int len) {
        try (java.io.BufferedInputStream in = new java.io.BufferedInputStream(new java.io.FileInputStream(file))) {
            byte[] buf = new byte[len];
            int n = in.read(buf, 0, len);
            if (n < 0) return null;
            byte[] out = new byte[n];
            System.arraycopy(buf, 0, out, 0, n);
            return out;
        } catch (IOException e) {
            return null;
        }
    }

    private static String ascii(byte[] b, int len) {
        StringBuilder sb = new StringBuilder(Math.min(len, b.length));
        for (int i = 0; i < b.length && i < len; i++) {
            sb.append((char) (b[i] & 0xFF));
        }
        return sb.toString();
    }

    /** 递归删除文件或目录，root 场景见 RootUtil。 */
    public static boolean deleteRecursively(File file) {
        if (file == null) return true;
        boolean success = true;
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                success &= deleteRecursively(child);
            }
        }
        return success && (file.delete() || !file.exists());
    }

    /**
     * Android R+ 上，targetSdk>=29 时应用外目录不能通过 File API 直接写，
     * 需借助 SAF（上层处理）。此方法对应用专属目录写使用原子创建。
     */
    public static boolean createNewFile(File file) {
        try {
            return file.createNewFile();
        } catch (IOException e) {
            return false;
        }
    }

    /** 令牌化路径，用于界面展示可点击的分段路径。 */
    public static String[] splitPathSegments(String path) {
        File f = new File(path);
        int depth = 0;
        File cur = f;
        while (cur != null) {
            depth++;
            cur = cur.getParentFile();
        }
        String[] segs = new String[depth];
        cur = f;
        int i = depth - 1;
        while (cur != null) {
            segs[i--] = cur.getAbsolutePath();
            cur = cur.getParentFile();
        }
        return segs;
    }
}