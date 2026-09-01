package com.alltoolbox.fbrowser.shell;

import android.app.Activity;
import android.content.Intent;

import com.alltoolbox.core.shell.ShellRunner;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.File;
import java.util.List;

/**
 * .sh 脚本打开入口：弹出【编辑脚本 / 运行脚本 / 设置可执行权限】。
 *
 * 运行前先做环境检测并按身份展示确认弹窗（Root 重点警示），满足文档的
 * “运行前弹窗展示运行身份 + 安全提醒”约束。
 */
public final class ShellTools {

    private ShellTools() {
    }

    /** 展示 .sh 打开选项。workDir 为脚本工作目录（通常为当前浏览目录）。 */
    public static void showDialog(Activity activity, File script, File workDir) {
        showDialog(activity, script, workDir, null);
    }

    /** 展示 .sh 打开选项，并可选附带一组将要作为脚本参数的文件路径。 */
    public static void showDialog(Activity activity, File script, File workDir, List<String> args) {
        if (activity == null || script == null) return;
        String[] items = {"编辑脚本", "运行脚本", "设置可执行权限", "详情"};
        new MaterialAlertDialogBuilder(activity)
                .setTitle(script.getName())
                .setItems(items, (d, w) -> {
                    switch (w) {
                        case 0:
                            openEditor(activity, script);
                            break;
                        case 1:
                            confirmThenRun(activity, script, workDir, args);
                            break;
                        case 2:
                            chmodX(activity, script);
                            break;
                        default:
                            showDetail(activity, script);
                            break;
                    }
                })
                .show();
    }

    /** 带参数运行入口（供双栏多选把选中文件路径作为脚本入参）。 */
    public static void confirmThenRun(Activity activity, File script, File workDir,
                                      List<String> args) {
        if (activity == null || script == null) return;
        final ShellRunner.Env env = ShellRunner.bestEnv();
        final StringBuilder msg = new StringBuilder();
        switch (env) {
            case ROOT:
                msg.append("运行身份：Root（su）\n")
                   .append("\u26A0 警告：Root 权限极高，脚本可删除/修改任意系统文件。\n仅运行可信任的脚本！");
                break;
            case SHIZUKU:
                msg.append("运行身份：Shizuku（adb/shell）\n")
                   .append("Shizuku 仅适合简单命令，复杂脚本可能因缺少工具/环境变量而失败，建议使用 Root。\n")
                   .append("请谨慎运行脚本。");
                break;
            default:
                msg.append("运行身份：普通 shell\n")
                   .append("未检测到 Root/Shizuku，多数 MT 脚本依赖的 busybox 工具可能不存在，仅能运行基础命令。\n")
                   .append("请谨慎运行脚本。");
                break;
        }
        msg.append("\n\n脚本：").append(script.getAbsolutePath())
           .append("\n工作目录：").append(workDir != null ? workDir.getAbsolutePath() : "/");
        if (args != null && !args.isEmpty()) {
            msg.append("\n参数：").append(args.size()).append(" 个文件路径");
        }

        new MaterialAlertDialogBuilder(activity)
                .setTitle("确认运行脚本")
                .setMessage(msg.toString())
                .setNegativeButton("取消", null)
                .setPositiveButton("运行", (d, w) ->
                        launch(activity, script, workDir, args, env))
                .show();
    }

    private static void launch(Activity activity, File script, File workDir,
                               List<String> args, ShellRunner.Env env) {
        Intent i = new Intent(activity, ShellConsoleActivity.class)
                .putExtra(ShellConsoleActivity.EXTRA_SCRIPT, script.getAbsolutePath())
                .putExtra(ShellConsoleActivity.EXTRA_WORKDIR,
                        workDir != null ? workDir.getAbsolutePath() : "/")
                .putExtra(ShellConsoleActivity.EXTRA_ENV, env.name());
        if (args != null && !args.isEmpty()) {
            i.putExtra(ShellConsoleActivity.EXTRA_ARGS, args.toArray(new String[0]));
        }
        activity.startActivity(i);
    }

    private static void openEditor(Activity activity, File script) {
        Intent i = new Intent(activity, com.alltoolbox.editor.TextEditorActivity.class);
        i.putExtra(com.alltoolbox.editor.TextEditorActivity.EXTRA_PATH, script.getAbsolutePath());
        activity.startActivity(i);
    }

    /** 设置可执行权限（chmod +x）：普通 su 各试一次，尽力而为。 */
    private static void chmodX(final Activity activity, final File script) {
        new Thread(() -> {
            boolean ok = execQuiet("chmod", "u+x,a+x", script.getAbsolutePath());
            if (!ok && ShellRunner.bestEnv() == ShellRunner.Env.ROOT) {
                ok = execQuiet("su", "-c",
                        "chmod 755 " + ShellRunner.quote(script.getAbsolutePath()));
            }
            final boolean result = ok;
            activity.runOnUiThread(() -> {
                android.widget.Toast.makeText(activity,
                        result ? "已设置可执行权限" : "设置权限失败（可能无 Root/Shizuku）",
                        android.widget.Toast.LENGTH_SHORT).show();
            });
        }).start();
    }

    /** 执行一条命令并返回是否成功。 */
    private static boolean execQuiet(String... cmd) {
        try {
            Process p = new ProcessBuilder(cmd).start();
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static void showDetail(Activity activity, File script) {
        StringBuilder sb = new StringBuilder();
        sb.append("名称：").append(script.getName()).append('\n');
        sb.append("大小：").append(com.alltoolbox.fbrowser.FileAdapter.formatSize(script.length())).append('\n');
        sb.append("路径：").append(script.getAbsolutePath()).append('\n');
        sb.append("最近执行环境：")
          .append(ShellRunner.bestEnv() == ShellRunner.Env.ROOT ? "Root" : "Shizuku/普通");
        new MaterialAlertDialogBuilder(activity)
                .setTitle("脚本详情")
                .setMessage(sb.toString())
                .setNegativeButton("关闭", null)
                .show();
    }
}