package com.alltoolbox.core.permission;

import android.content.pm.PackageManager;
import android.os.Build;
import android.os.ParcelFileDescriptor;

import androidx.annotation.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

import moe.shizuku.server.IRemoteProcess;
import moe.shizuku.server.IShizukuService;
import rikka.shizuku.Shizuku;

/**
 * Shizuku 简化封装：以 adb/shell 权限执行命令，用于访问
 * {@code Android/data}、{@code Android/obb}、{@code /data} 等普通应用无法读取的受限目录。
 *
 * 实现方式：直接通过 {@link IShizukuService#newProcess} 启动子进程（运行在 shell/adb
 * 身份下），不再依赖自定义 AIDL 服务，简单且稳健。
 *
 * 注意：所有方法需在后台线程调用（内部有阻塞 IO）。
 */
public final class ShizukuShell {

    private ShizukuShell() {
    }

    /** Shizuku 仅在 Android 6.0（API 23）及以上可用。 */
    public static boolean isSupported() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.M;
    }

    /** Shizuku 服务是否已连接（Shizuku 应用已启动且已通过 adb/Root 激活）。 */
    public static boolean isOnline() {
        try {
            return isSupported() && Shizuku.pingBinder();
        } catch (Throwable t) {
            return false;
        }
    }

    /** 是否已授予 Shizuku 权限。 */
    public static boolean isGranted() {
        try {
            return isSupported()
                    && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Shizuku 是否已就绪（在线且已授权）。 */
    public static boolean isReady() {
        return isOnline() && isGranted();
    }

    /** 发起 Shizuku 权限申请，结果通过 Shizuku.addRequestPermissionResultListener 回调。 */
    public static void requestPermission(int requestCode) {
        try {
            Shizuku.requestPermission(requestCode);
        } catch (Throwable ignored) {
        }
    }

    /** 命令执行结果。 */
    public static final class ExecResult {
        public final int exitCode;
        public final String stdout;
        public final String stderr;

        ExecResult(int exitCode, String stdout, String stderr) {
            this.exitCode = exitCode;
            this.stdout = stdout;
            this.stderr = stderr;
        }

        public boolean success() {
            return exitCode == 0;
        }
    }

    /** 目录条目。 */
    public static final class Entry {
        public final String path;
        public final String name;
        public final boolean directory;
        public final long size;
        public final long lastModified;

        Entry(String path, String name, boolean directory, long size, long lastModified) {
            this.path = path;
            this.name = name;
            this.directory = directory;
            this.size = size;
            this.lastModified = lastModified;
        }
    }

    /**
     * 以 shell 身份执行命令。
     *
     * @param cmd  命令与参数（不经 shell 转义，含空格路径安全）
     * @param dir  工作目录，可为 null
     * @param timeoutMs 超时
     * @return 执行结果；失败（未连接/未授权/异常）返回 null
     */
    @Nullable
    public static ExecResult exec(String[] cmd, @Nullable String dir, long timeoutMs) {
        if (!isReady()) return null;
        try {
            IShizukuService svc = IShizukuService.Stub.asInterface(Shizuku.getBinder());
            if (svc == null) return null;
            IRemoteProcess proc = svc.newProcess(cmd, null, dir);
            if (proc == null) return null;

            ReadTask out = new ReadTask(proc.getInputStream());
            ReadTask err = new ReadTask(proc.getErrorStream());
            out.start();
            err.start();

            int code = proc.waitFor();
            out.join(timeoutMs);
            err.join(timeoutMs);
            return new ExecResult(code, out.text(), err.text());
        } catch (Throwable t) {
            return null;
        }
    }

    /** 通过 sh -c 执行整条命令（需要 shell 解析时使用）。 */
    @Nullable
    public static ExecResult execShell(String command, long timeoutMs) {
        return exec(new String[]{"sh", "-c", command}, null, timeoutMs);
    }

    /**
     * 列出目录（shell 权限）。
     *
     * @return 条目列表；无法访问（未就绪/路径无效）返回 null
     */
    @Nullable
    public static List<Entry> listDir(String path) {
        ExecResult r = exec(new String[]{"ls", "-la", path}, null, 5000);
        if (r == null || !r.success()) return null;
        List<Entry> out = new ArrayList<>();
        String[] lines = r.stdout.split("\n");
        for (String line : lines) {
            if (line == null || line.isEmpty()) continue;
            if (line.startsWith("total")) continue;
            Entry e = parseLsLine(line, path);
            if (e != null && !".".equals(e.name) && !"..".equals(e.name)) {
                out.add(e);
            }
        }
        return out;
    }

    private static Entry parseLsLine(String line, String dir) {
        String[] tok = line.split("\\s+");
        if (tok.length < 7) return null;
        String perms = tok[0];
        if (perms.length() < 10) return null;
        char t = perms.charAt(0);
        if (t != '-' && t != 'd' && t != 'l') return null;
        long size;
        try {
            size = Long.parseLong(tok[4]);
        } catch (NumberFormatException e) {
            return null;
        }
        // 日期字段可能被空格拆分，重组日期字段（tok[5] 起，直到名字前）
        int nameIdx = 5;
        StringBuilder date = new StringBuilder(tok[5]);
        // GNU/toybox 日期格式：YYYY-MM-DD HH:mm 或 YYYY-MM-DD 或 HH:mm
        for (int i = 6; i < tok.length; i++) {
            if (tok[i].matches("^\\d{2}:\\d{2}$") || tok[i].matches("^\\d{4}-\\d{2}-\\d{2}$")) {
                date.append(' ').append(tok[i]);
                nameIdx = i + 1;
                break;
            }
            nameIdx = i;
        }
        long lastModified = parseDate(date.toString());
        StringBuilder name = new StringBuilder();
        for (int i = nameIdx; i < tok.length; i++) {
            if (name.length() > 0) name.append(' ');
            name.append(tok[i]);
        }
        if (name.length() == 0) return null;
        String nameStr = unescape(name.toString());
        String full = dir.endsWith("/") ? dir + nameStr : dir + "/" + nameStr;
        return new Entry(full, nameStr, t == 'd', t == 'd' ? 0 : size, lastModified);
    }

    private static String unescape(String s) {
        // 极少数名称含空格/反斜杠时，ls 可能以反斜杠转义；做常见还原
        return s.replace("\\ ", " ").replace("\\\\", "\\");
    }

    private static long parseDate(String date) {
        if (date == null || date.isEmpty()) return 0;
        date = date.trim();
        try {
            // YYYY-MM-DD HH:mm
            String[] parts = date.split("[- :]");
            if (parts.length >= 5) {
                Calendar c = Calendar.getInstance();
                c.clear();
                c.set(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]) - 1,
                        Integer.parseInt(parts[2]), Integer.parseInt(parts[3]),
                        Integer.parseInt(parts[4]));
                return c.getTimeInMillis();
            }
            // YYYY-MM-DD
            if (parts.length >= 3) {
                Calendar c = Calendar.getInstance();
                c.clear();
                c.set(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]) - 1,
                        Integer.parseInt(parts[2]));
                return c.getTimeInMillis();
            }
            // HH:mm —— 当年的今天
            if (parts.length == 2 && parts[0].length() == 2) {
                Calendar c = Calendar.getInstance();
                c.clear();
                c.set(Calendar.HOUR_OF_DAY, Integer.parseInt(parts[0]));
                c.set(Calendar.MINUTE, Integer.parseInt(parts[1]));
                return c.getTimeInMillis();
            }
        } catch (Exception ignored) {
        }
        return 0;
    }

    private static final class ReadTask extends Thread {
        private final ParcelFileDescriptor pfd;
        private final ByteArrayOutputStream buf = new ByteArrayOutputStream();

        ReadTask(ParcelFileDescriptor pfd) {
            this.pfd = pfd;
            setDaemon(true);
        }

        @Override
        public void run() {
            try (InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(pfd)) {
                byte[] b = new byte[8192];
                int n;
                while ((n = in.read(b)) != -1) {
                    buf.write(b, 0, n);
                    if (buf.size() > 1_048_576) break; // 上限 1MB
                }
            } catch (Exception ignored) {
            }
        }

        String text() {
            return buf.toString();
        }
    }

    // ---------- 常用文件操作（shell 权限） ----------

    /** 删除文件/目录（递归）。 */
    public static boolean delete(String path) {
        ExecResult r = exec(new String[]{"rm", "-rf", path}, null, 10000);
        return r != null && r.success();
    }

    /** 复制（目录递归）。 */
    public static boolean copy(String src, String dst) {
        ExecResult r = exec(new String[]{"cp", "-r", src, dst}, null, 60000);
        return r != null && r.success();
    }

    /** 移动/重命名。 */
    public static boolean move(String src, String dst) {
        ExecResult r = exec(new String[]{"mv", "-f", src, dst}, null, 60000);
        return r != null && r.success();
    }

    /** 创建目录（含父级）。 */
    public static boolean mkdir(String path) {
        ExecResult r = exec(new String[]{"mkdir", "-p", path}, null, 5000);
        return r != null && r.success();
    }

    /** 创建空文件。 */
    public static boolean createFile(String path) {
        ExecResult r = exec(new String[]{"touch", path}, null, 5000);
        return r != null && r.success();
    }
}
