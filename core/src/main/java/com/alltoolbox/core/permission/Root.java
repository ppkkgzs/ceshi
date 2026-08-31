package com.alltoolbox.core.permission;

import java.io.File;

/**
 * Root 检测工具。仅做检测，供 UI 决定是否显示 Root 增强入口。
 * 无 Root 设备自动隐藏相关功能。
 */
public final class Root {

    private Root() {
    }

    private static Boolean sRooted;

    /** 检测设备当前是否具备 Root 能力（su 可用）。 */
    public static boolean isRooted() {
        if (sRooted != null) return sRooted;
        sRooted = detect();
        return sRooted;
    }

    private static boolean detect() {
        String[] paths = {
                "/system/bin/su", "/system/xbin/su",
                "/sbin/su", "/system/app/Superuser.apk",
                "/system/app/SuperSU", "/system/xbin/daemonsu"
        };
        for (String p : paths) {
            if (new File(p).exists()) return true;
        }
        // 尝试执行 su -c true
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", "true"});
            int code = p.waitFor();
            // 退出码 0 表示 su 可用
            return code == 0;
        } catch (Exception e) {
            return false;
        }
    }

    /** 获取 shell 命令是否可用（非 Root 亦可执行普通命令）。 */
    public static String[] runShell(String... commands) {
        StringBuilder out = new StringBuilder();
        StringBuilder err = new StringBuilder();
        Process process = null;
        try {
            process = Runtime.getRuntime().exec("sh");
            java.io.OutputStream stdin = process.getOutputStream();
            java.io.InputStream stdout = process.getInputStream();
            java.io.InputStream stderr = process.getErrorStream();
            for (String cmd : commands) {
                stdin.write((cmd + "\n").getBytes());
            }
            stdin.write("exit\n".getBytes());
            stdin.flush();
            java.util.Scanner so = new java.util.Scanner(stdout).useDelimiter("\\A");
            java.util.Scanner se = new java.util.Scanner(stderr).useDelimiter("\\A");
            if (so.hasNext()) out.append(so.next());
            if (se.hasNext()) err.append(se.next());
            process.waitFor();
        } catch (Exception e) {
            err.append(e.getMessage());
        } finally {
            if (process != null) process.destroy();
        }
        return new String[]{out.toString(), err.toString()};
    }
}