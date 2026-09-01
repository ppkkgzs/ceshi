package com.alltoolbox.apktools.bridge;

import android.content.Context;

import com.alltoolbox.core.task.TaskExecutor;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * APK 反编译/回编译桥接（混合方案：命令行 apktool）。
 *
 * 说明：
 *  - 从 assets/apktool.jar 拷贝到内部目录（需在打包时放入该文件）。
 *  - Android 设备上运行 jar 需要 java 运行时；若设备无 java，则回退到
 *    "PC 协作模式"：保存已选参数与 APK 路径，提示用户在电脑执行 apktool。
 *  - 加固加壳 APK 无法正常反编译回编译（需求备注）。
 */
public final class ApkToolBridge {

    public interface Callback {
        void onDone(boolean ok, String output);
    }

    private static volatile ApkToolBridge sInstance;

    public static ApkToolBridge get() {
        if (sInstance == null) {
            synchronized (ApkToolBridge.class) {
                if (sInstance == null) sInstance = new ApkToolBridge();
            }
        }
        return sInstance;
    }

    private ApkToolBridge() {
    }

    private File apktoolJar(Context ctx) {
        return new File(ctx.getFilesDir(), "bin/apktool.jar");
    }

    /** 确保 assets 中的 apktool.jar 已解压到内部目录。 */
    public boolean prepare(Context ctx) {
        File jar = apktoolJar(ctx);
        if (jar.exists()) return true;
        try {
            File dir = jar.getParentFile();
            if (dir != null && !dir.exists()) dir.mkdirs();
            try (java.io.InputStream in = ctx.getAssets().open("apktool.jar");
                 java.io.OutputStream out = new FileOutputStream(jar)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            }
            return jar.exists();
        } catch (Exception e) {
            return false;
        }
    }

    /** 设备当前是否具备可用的 java 运行时（决定能否本机反编译）。 */
    public boolean hasJavaRuntime() {
        try {
            Process p = Runtime.getRuntime().exec("java -version");
            int code = p.waitFor();
            return code == 0;
        } catch (Exception e) {
            return false;
        }
    }

    /** 反编译 APK 到工作区。 */
    public void decompile(Context ctx, File apk, File outDir, Callback cb) {
        TaskExecutor.get().heavy().execute(() -> {
            if (!prepare(ctx)) {
                cb.onDone(false, "缺少 apktool.jar（请放置于 assets）");
                return;
            }
            if (!hasJavaRuntime()) {
                cb.onDone(false, "本机无 java 运行时，请在电脑执行: " + pcCommand("d", apk, outDir));
                return;
            }
            List<String> cmd = new ArrayList<>();
            cmd.add("java");
            cmd.add("-jar");
            cmd.add(apktoolJar(ctx).getAbsolutePath());
            cmd.add("d");
            cmd.add("-f");
            cmd.add("-s"); // 不反编译 resources，仅解包（速度）——按需
            cmd.add(apk.getAbsolutePath());
            cmd.add("-o");
            cmd.add(outDir.getAbsolutePath());
            String out = run(cmd);
            cb.onDone(outDir.exists() && outDir.list().length > 0, out);
        });
    }

    /** 回编译工作区为未签名 APK。 */
    public void build(Context ctx, File workDir, File outApk, Callback cb) {
        TaskExecutor.get().heavy().execute(() -> {
            if (!prepare(ctx)) {
                cb.onDone(false, "缺少 apktool.jar");
                return;
            }
            if (!hasJavaRuntime()) {
                cb.onDone(false, "本机无 java 运行时，请在电脑执行: " + pcCommand("b", workDir, outApk));
                return;
            }
            List<String> cmd = new ArrayList<>();
            cmd.add("java");
            cmd.add("-jar");
            cmd.add(apktoolJar(ctx).getAbsolutePath());
            cmd.add("b");
            cmd.add(workDir.getAbsolutePath());
            cmd.add("-o");
            cmd.add(outApk.getAbsolutePath());
            String out = run(cmd);
            cb.onDone(outApk.exists(), out);
        });
    }

    /** 生成 PC 协作命令文本（非 Root/无 java 时提示）。 */
    private String pcCommand(String action, File src, File out) {
        return "apktool.${action} -f " + src.getAbsolutePath()
                + (action.equals("d") ? " -o " : " -o ") + out.getAbsolutePath();
    }

    private String run(List<String> cmd) {
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            StringBuilder sb = new StringBuilder();
            try (BufferedInputStream in = new BufferedInputStream(p.getInputStream())) {
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) != -1) sb.append(new String(buf, 0, n, "UTF-8"));
            }
            p.waitFor();
            return sb.toString();
        } catch (Exception e) {
            return "执行失败: " + e.getMessage();
        }
    }
}