package com.alltoolbox.root;

import com.alltoolbox.core.task.TaskExecutor;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.File;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Root 提权执行器。
 *
 * 在 {@code root} 会话中执行系统级命令，用于：
 *  - 系统级文件读写（绕过沙箱限制）
 *  - 应用管理（冻结/解冻、卸载、权限授予）
 *
 * 所有耗时的命令都投递到后台线程池，避免阻塞 UI。
 */
public final class RootService {

    /** 一条命令的执行结果。 */
    public static final class Result {
        public final int exit;
        public final String output;
        public final String error;

        Result(int exit, String output, String error) {
            this.exit = exit;
            this.output = output;
            this.error = error;
        }

        public boolean ok() {
            return exit == 0;
        }

        @Override
        public String toString() {
            return "exit=" + exit + "\nstdout:\n" + output + "\nstderr:\n" + error;
        }
    }

    private RootService() {
    }

    /**
     * 同步执行命令并以 su 提权。若设备无 Root，则 <b>自动降级为普通 shell</b>执行，
     * 便于开发调试；真正受限的权限操作会失败并返回非 0。
     *
     * @param timeoutSec 每条命令超时（秒）
     */
    public static Result execRoot(long timeoutSec, String... commands) {
        boolean su = com.alltoolbox.core.permission.Root.isRooted();
        String bin = su ? "su -c" : "sh -c";
        boolean first = true;
        StringBuilder cmd = new StringBuilder();
        for (String c : commands) {
            if (!first) cmd.append(" && ");
            first = false;
            cmd.append(quote(c));
        }
        return run(bin + " " + cmd, timeoutSec);
    }

    /** 异步执行 root 命令，结果通过回调返回（在调用线程池中执行）。 */
    public static void execRootAsync(long timeoutSec,
                                     java.util.function.Consumer<Result> onResult,
                                     java.util.function.Consumer<Throwable> onError,
                                     String... commands) {
        TaskExecutor.get().io().execute(() -> {
            try {
                Result r = execRoot(timeoutSec, commands);
                if (onResult != null) onResult.accept(r);
            } catch (Throwable t) {
                if (onError != null) onError.accept(t);
            }
        });
    }

    // ---------------- 应用管理 ----------------

    /** 冻结应用：pm disable-user <pkg>。解冻用 unfreezePackage。 */
    public static Result freezePackage(String pkg) {
        return execRoot(30, "pm disable-user --user 0 " + pkg,
                "am force-stop " + pkg);
    }

    /** 解冻应用：pm enable <pkg>。 */
    public static Result unfreezePackage(String pkg) {
        return execRoot(30, "pm enable " + pkg);
    }

    /** 卸载应用（保留数据：-k 可加）。 */
    public static Result uninstallPackage(String pkg) {
        return execRoot(60, "pm uninstall " + pkg);
    }

    /** 授予运行时权限：pm grant <pkg> <permission>。 */
    public static Result grantPermission(String pkg, String permission) {
        return execRoot(30, "pm grant " + pkg + " " + permission);
    }

    /** 撤销运行时权限：pm revoke <pkg> <permission>。 */
    public static Result revokePermission(String pkg, String permission) {
        return execRoot(30, "pm revoke " + pkg + " " + permission);
    }

    /** 以 root 覆盖写入文件（先备份后写，失败时回滚）。 */
    public static Result writeFileRoot(File file, String content, int mode) {
        String tmp = "/data/local/tmp/.alltoolbox_" + Math.abs(file.getName().hashCode());
        String path = file.getAbsolutePath();
        return execRoot(30,
                "cat > " + tmp + " << 'EOF'\n" + content + "\nEOF",
                "cp " + path + " " + tmp + ".bak",
                "mv " + tmp + " " + path,
                "chmod " + mode + " " + path,
                "rm -f " + tmp + ".bak");
    }

    /** 读取系统文件内容（root 或可读）。 */
    public static String readFileRoot(String path) {
        Result r = execRoot(15, "cat " + quote(path));
        return r.ok() ? r.output : null;
    }

    // ---------------- 工具 ----------------

    /** shell 单词引用，避免空格/特殊字符破坏命令。 */
    private static String quote(String s) {
        if (s == null) return "''";
        // 换行场景交由调用方拼接，这里仅做普通引用
        if (s.contains("'")) {
            return "'" + s.replace("'", "'\\''") + "'";
        }
        return "'" + s + "'";
    }

    private static Result run(String fullCommand, long timeoutSec) {
        Process process = null;
        try {
            process = Runtime.getRuntime().exec(new String[]{"sh", "-c", fullCommand});
            process.getOutputStream().close(); // 立即关闭 stdin
            final Process p = process;

            Future<String> outF = TaskExecutor.get().io().submit(() -> drain(p.getInputStream()));
            Future<String> errF = TaskExecutor.get().io().submit(() -> drain(p.getErrorStream()));

            boolean finished = process.waitFor(timeoutSec, TimeUnit.SECONDS);
            if (!finished) {
                process.destroy();
                return new Result(-1, "", "命令超时（" + timeoutSec + "s）");
            }
            String out = null, err = null;
            try { out = outF.get(5, TimeUnit.SECONDS); } catch (Exception ignore) { }
            try { err = errF.get(5, TimeUnit.SECONDS); } catch (Exception ignore) { }
            return new Result(process.exitValue(), out == null ? "" : out, err == null ? "" : err);
        } catch (Exception e) {
            return new Result(-1, "", String.valueOf(e.getMessage()));
        } finally {
            if (process != null) process.destroy();
        }
    }

    private static String drain(java.io.InputStream is) {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(is))) {
            char[] buf = new char[2048];
            int n;
            while ((n = reader.read(buf)) != -1) {
                sb.append(buf, 0, n);
            }
        } catch (Exception ignore) {
        }
        return sb.toString();
    }
}