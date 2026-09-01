package com.alltoolbox.core.shell;

import android.os.Build;
import android.os.ParcelFileDescriptor;

import com.alltoolbox.core.permission.Root;
import com.alltoolbox.core.permission.ShizukuShell;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 独立 Shell 脚本执行模块（.sh）。
 *
 * 与 7-Zip / 文件浏览互不关联。负责把一个 .sh 脚本放到独立进程中执行，
 * 支持三类运行环境：
 *   - Root：以 su 执行完整脚本（能力最全）；
 *   - 普通（非 Root）：以 sh 执行（仅无特权命令）；
 *   - Shizuku：以 adb/shell 身份执行（适合简单命令）。
 *
 * 能力：
 *   - 实时回调 stdout / stderr（区分输出流）；
 *   - 支持停止（destroy 进程）；
 *   - 支持超时自动 kill（默认 0=不限制）；
 *   - 所有传入路径做单引号转义，避免空格 / 中文 / 特殊符号解析错乱。
 *
 * 注意：运行在后台线程（内部有阻塞 IO），严禁 UI 线程调用。
 */
public final class ShellRunner {

    private ShellRunner() {
    }

    /** 一次脚本任务：可通过 stop() 终止进程。 */
    public interface Task {
        /** 终止正在执行的进程。 */
        void stop();
    }

    /** 执行结果回调（在主线程回调）。 */
    public interface Callback {
        /** 实时输出片段。@param stderr true=错误输出，false=标准输出。 */
        void onOutput(String chunk, boolean stderr);
        /** 进程结束。@param exitCode 退出码（-1 表示被强制终止/异常）。 */
        void onExit(int exitCode);
    }

    /** 运行环境类型。 */
    public enum Env {
        ROOT,       // su 提权，能力最全
        NORMAL,     // 普通 sh，无特权
        SHIZUKU,    // adb/shell 身份，适合简单命令
        NONE        // 无任何可用环境
    }

    /** 当前设备可用的最佳运行环境。 */
    public static Env bestEnv() {
        if (Root.isRooted()) return Env.ROOT;
        if (ShizukuShell.isReady()) return Env.SHIZUKU;
        return Env.NORMAL;
    }

    /**
     * 运行一个 .sh 脚本。
     *
     * @param script  脚本文件（建议已 chmod +x；未设置也可用 sh 解释执行）
     * @param workDir 工作目录（脚本相对路径的基准）；可为 null 表示脚本所在目录
     * @param env     运行环境
     * @param timeoutSeconds 超时（秒），<=0 表示不限时
     * @param args    传给脚本的参数；可为 null
     * @param cb      回调（主线程）
     * @return Task 句柄，可 stop()
     */
    public static Task runScript(File script, File workDir, Env env,
                                 long timeoutSeconds, List<String> args, Callback cb) {
        File wd = workDir != null ? workDir : script.getParentFile();
        if (env == null || env == Env.NONE) env = bestEnv();

        switch (env) {
            case SHIZUKU:
                return runShizuku(script, wd, args, cb);
            case ROOT:
                return runRoot(script, wd, timeoutSeconds, args, cb);
            default:
                return runNormal(script, wd, timeoutSeconds, args, cb);
        }
    }

    // ---------------- 普通（sh） ----------------

    private static Task runNormal(File script, File wd, long timeoutSeconds,
                                  List<String> args, Callback cb) {
        String[] cmd = buildCommand("sh", script.getAbsolutePath(), args);
        return launch(cmd, wd, timeoutSeconds, cb);
    }

    // ---------------- Root（su） ----------------

    private static Task runRoot(File script, File wd, long timeoutSeconds,
                                List<String> args, Callback cb) {
        String scriptPath = quote(script.getAbsolutePath());
        StringBuilder inner = new StringBuilder();
        if (wd != null) {
            inner.append("cd ").append(quote(wd.getAbsolutePath())).append(" && ");
        }
        inner.append("sh ").append(scriptPath);
        if (args != null) {
            for (String a : args) inner.append(" ").append(quote(a));
        }
        String[] cmd = {"su", "-c", inner.toString()};
        return launch(cmd, wd, timeoutSeconds, cb);
    }

    // ---------------- Shizuku ----------------

    private static Task runShizuku(File script, File wd, List<String> args, Callback cb) {
        final AtomicBoolean stopped = new AtomicBoolean(false);
        final Object lock = new Object();

        final InputStreamHolder[] outH = new InputStreamHolder[1];
        final InputStreamHolder[] errH = new InputStreamHolder[1];

        Thread t = new Thread(() -> {
            try {
                moe.shizuku.server.IShizukuService svc = moe.shizuku.server.IShizukuService.Stub
                        .asInterface(rikka.shizuku.Shizuku.getBinder());
                if (svc == null) {
                    emit(cb, "Shizuku 服务不可用\n", true);
                    emitExit(cb, -1);
                    return;
                }
                String[] cmd = buildCommand("sh", script.getAbsolutePath(), args);
                String dir = wd != null ? wd.getAbsolutePath() : null;
                moe.shizuku.server.IRemoteProcess proc = svc.newProcess(cmd, null, dir);
                if (proc == null) {
                    emit(cb, "无法通过 Shizuku 启动进程\n", true);
                    emitExit(cb, -1);
                    return;
                }
                outH[0] = new InputStreamHolder(proc.getInputStream());
                errH[0] = new InputStreamHolder(proc.getErrorStream());

                Thread outT = pump(outH[0], false, cb, stopped);
                Thread errT = pump(errH[0], true, cb, stopped);
                outT.start();
                errT.start();

                int code = proc.waitFor();
                outT.join(2000);
                errT.join(2000);
                synchronized (lock) {
                    if (!stopped.get()) emitExit(cb, code);
                }
            } catch (Throwable e) {
                emit(cb, "执行异常: " + e + "\n", true);
                emitExit(cb, -1);
            }
        });
        t.setDaemon(true);
        t.start();

        return () -> {
            stopped.set(true);
            close(outH[0]);
            close(errH[0]);
        };
    }

    // ---------------- 底层启动 ----------------

    private static Task launch(final String[] command, File wd, long timeoutSeconds, Callback cb) {
        final AtomicBoolean stopped = new AtomicBoolean(false);
        final Process[] pHolder = new Process[1];
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            if (wd != null && wd.isDirectory()) pb.directory(wd);
            Process proc = pb.start();
            pHolder[0] = proc;

            Thread outT = pump(new InputStreamHolder(proc.getInputStream()), false, cb, stopped);
            Thread errT = pump(new InputStreamHolder(proc.getErrorStream()), true, cb, stopped);
            outT.start();
            errT.start();

            Thread waiter = new Thread(() -> {
                int code;
                try {
                    code = proc.waitFor();
                    outT.join(3000);
                    errT.join(3000);
                } catch (InterruptedException e) {
                    code = -1;
                }
                if (stopped.get()) code = -1;
                emitExit(cb, code);
            });
            waiter.setDaemon(true);
            waiter.start();

            if (timeoutSeconds > 0) {
                Thread timer = new Thread(() -> {
                    try {
                        Thread.sleep(timeoutSeconds * 1000L);
                    } catch (InterruptedException ignored) {
                    }
                    if (!stopped.get() && proc.isAlive()) {
                        stopped.set(true);
                        proc.destroy();
                        emit(cb, "\n[已超时，进程被终止]\n", true);
                    }
                });
                timer.setDaemon(true);
                timer.start();
            }
        } catch (IOException e) {
            emit(cb, "无法启动进程: " + e + "\n", true);
            emitExit(cb, -1);
        }
        return () -> {
            stopped.set(true);
            Process p = pHolder[0];
            if (p != null && p.isAlive()) {
                p.destroy();
                if (Build.VERSION.SDK_INT >= 26) {
                    try {
                        p.destroyForcibly();
                    } catch (Throwable ignored) {
                    }
                }
            }
        };
    }

    private static String[] buildCommand(String shell, String scriptPath, List<String> args) {
        int n = 2 + (args == null ? 0 : args.size());
        String[] cmd = new String[n];
        cmd[0] = shell;
        cmd[1] = scriptPath;
        if (args != null) {
            for (int i = 0; i < args.size(); i++) cmd[i + 2] = args.get(i);
        }
        return cmd;
    }

    /** 把路径包进单引号（含中文 / 空格 / 特殊符号），防止 shell 解析错乱。 */
    public static String quote(String s) {
        if (s == null) return "''";
        // 单引号内出现 ' 需转义：'\''
        return "'" + s.replace("'", "'\\''") + "'";
    }

    // ---------------- 流泵 ----------------

    private static Thread pump(InputStreamHolder h, boolean stderr, Callback cb,
                               AtomicBoolean stopped) {
        return new Thread(() -> {
            try (InputStream in = h.asStream()) {
                byte[] buf = new byte[8192];
                int n;
                while (!stopped.get() && (n = in.read(buf)) != -1) {
                    if (n > 0) emit(cb, new String(buf, 0, n), stderr);
                }
            } catch (Throwable ignored) {
            }
        });
    }

    private static void emit(final Callback cb, final String text, final boolean stderr) {
        if (cb == null || text == null || text.isEmpty()) return;
        android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
        main.post(() -> {
            try {
                cb.onOutput(text, stderr);
            } catch (Throwable ignored) {
            }
        });
    }

    private static void emitExit(final Callback cb, final int code) {
        if (cb == null) return;
        android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
        main.post(() -> {
            try {
                cb.onExit(code);
            } catch (Throwable ignored) {
            }
        });
    }

    private static void close(InputStreamHolder h) {
        if (h != null) h.close();
    }

    /** 输入流持有（统一封装 pfd / InputStream 关闭）。 */
    private static final class InputStreamHolder {
        private final ParcelFileDescriptor pfd;
        private final InputStream stream;

        InputStreamHolder(ParcelFileDescriptor pfd) {
            this.pfd = pfd;
            this.stream = new ParcelFileDescriptor.AutoCloseInputStream(pfd);
        }

        InputStreamHolder(InputStream stream) {
            this.pfd = null;
            this.stream = stream;
        }

        InputStream asStream() {
            return stream;
        }

        void close() {
            try {
                if (stream != null) stream.close();
            } catch (Throwable ignored) {
            }
            try {
                if (pfd != null) pfd.close();
            } catch (Throwable ignored) {
            }
        }
    }

    /** 一个可写输出流（供 Console Activity 使用，写入即触发回调）。 */
    public interface StreamSink {
        void write(String text);
        void write(byte[] data, int off, int len);
    }

    /** 便捷工具：把一个字节流泵成文本回调（供外部复用）。 */
    public static Thread pump(InputStream in, final String tag, final StreamSink sink) {
        return new Thread(() -> {
            try {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) {
                    if (n > 0 && sink != null) sink.write(buf, 0, n);
                }
            } catch (Throwable ignored) {
            }
        });
    }
}