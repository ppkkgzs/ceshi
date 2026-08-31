package com.alltoolbox.app;

import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.Settings;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import java.io.File;

/**
 * 应用内直接更新：
 * 使用系统 DownloadManager 下载最新安装包，下载完成后自动拉起安装界面。
 */
public final class Updater {

    private static final String APK_MIME = "application/vnd.android.package-archive";

    private Updater() {
    }

    /** 是否已获得「安装未知来源应用」授权（Android 8.0+ 需要）。 */
    public static boolean canInstallApps(Context ctx) {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.O
                || ctx.getPackageManager().canRequestPackageInstalls();
    }

    /**
     * 用系统下载管理器下载最新版 APK，下载完成后自动拉起安装界面。
     *
     * @param tag Release 标签（如 v1.6.6），用于构造直链与文件名。
     */
    public static void downloadAndInstall(Context ctx, String tag) {
        // Android 8.0+ 未授权安装未知来源时，先引导用户去系统设置开启
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !canInstallApps(ctx)) {
            try {
                ctx.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + ctx.getPackageName()))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                Toast.makeText(ctx, "请在系统设置中开启「允许安装未知来源应用」后再次更新",
                        Toast.LENGTH_LONG).show();
            } catch (Exception e) {
                Toast.makeText(ctx, "请在系统设置中允许安装未知来源应用",
                        Toast.LENGTH_LONG).show();
            }
            return;
        }

        DownloadManager dm = (DownloadManager) ctx.getSystemService(Context.DOWNLOAD_SERVICE);
        if (dm == null) {
            Toast.makeText(ctx, "系统下载服务不可用", Toast.LENGTH_SHORT).show();
            return;
        }

        final String fileName =
                "AllToolbox_" + tag.replaceFirst("^[vV]", "") + ".apk";
        try {
            DownloadManager.Request req = new DownloadManager.Request(
                    Uri.parse(UpdateChecker.apkDirectUrl(tag)));
            req.setTitle("PK管理器 更新");
            req.setDescription("正在下载 " + fileName);
            req.setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName);

            long downloadId = dm.enqueue(req);
            Toast.makeText(ctx, "更新包开始下载，完成后会自动进入安装界面",
                    Toast.LENGTH_LONG).show();

            // 注册本次下载的完成广播；回调里重建 file 路径（与新文件名一致）
            Context app = ctx.getApplicationContext();
            app.registerReceiver(new CompleteReceiver(downloadId, fileName),
                    new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE));
        } catch (Exception e) {
            Toast.makeText(ctx, "下载启动失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    /** 每个下载任务独立注册的完成接收器，持有目标文件名以便用 FileProvider 分享。 */
    private static final class CompleteReceiver extends BroadcastReceiver {
        private final long downloadId;
        private final String fileName;

        CompleteReceiver(long downloadId, String fileName) {
            this.downloadId = downloadId;
            this.fileName = fileName;
        }

        @Override
        public void onReceive(Context context, Intent intent) {
            context.unregisterReceiver(this);
            if (!DownloadManager.ACTION_DOWNLOAD_COMPLETE.equals(intent.getAction())) return;
            if (intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L) != downloadId) return;

            File dir = Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS);
            File target = new File(dir, fileName);
            Uri apkUri;
            try {
                String authority = context.getPackageName() + ".fileprovider";
                apkUri = FileProvider.getUriForFile(context, authority, target);
            } catch (Exception e) {
                // 文件名对不上时用 DownloadManager 返回的 content uri
                DownloadManager dm = (DownloadManager) context.getSystemService(
                        Context.DOWNLOAD_SERVICE);
                apkUri = dm != null ? dm.getUriForDownloadedFile(downloadId) : null;
            }

            if (apkUri == null || !target.exists()) {
                Toast.makeText(context, "更新包下载失败或被清除", Toast.LENGTH_SHORT).show();
                return;
            }
            install(context, apkUri);
        }
    }

    private static void install(Context ctx, Uri uri) {
        try {
            Intent install = new Intent(Intent.ACTION_VIEW);
            install.setDataAndType(uri, APK_MIME);
            install.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                install.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            }
            ctx.startActivity(install);
        } catch (Exception e) {
            Toast.makeText(ctx, "无法打开安装界面：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }
}