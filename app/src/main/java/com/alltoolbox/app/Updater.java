package com.alltoolbox.app;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import com.alltoolbox.core.task.TaskExecutor;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 应用内直接更新。
 *
 * 下载：由应用自身（HttpURLConnection）把更新包下载到「应用专属外部存储」
 * （getExternalFilesDir，无需分区存储越权，天然对其它应用可见），
 * 下载完成后用 FileProvider 以确定性方式拉起安装界面。
 *
 * 说明：早期版本曾使用系统 DownloadManager + 公共 Download 目录。这种方式在
 * 真机上不稳定——同名文件会被改名为 xxx-1.apk，导致按名字重建路径失效；回退到
 * DownloadManager 的 content:// uri 时安装器又往往缺少读授权，因而频繁「跳转失败」。
 * 现改为自下载 + 专属目录 + FileProvider，规避上述所有问题。
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
     * 下载进度监听。所有回调均在主线程调用。
     */
    public interface DownloadProgressListener {
        /** 下载开始。{@code totalBytes} 为服务器返回的总大小，未知时为 -1。 */
        void onStarted(long totalBytes);

        /**
         * 进度更新。
         *
         * @param downloadedBytes 已下载字节数
         * @param totalBytes      总字节数（未知为 -1）
         * @param speedBps        最近统计区间的平均速度（字节/秒）
         * @param remainingSeconds 预计剩余秒数（未知为 -1）
         */
        void onProgress(long downloadedBytes, long totalBytes, long speedBps, long remainingSeconds);

        /** 下载结束。{@code success=false} 时 {@code message} 为失败原因。 */
        void onFinish(boolean success, String message);
    }

    /**
     * 下载并安装指定 Release 标签对应的最新版 APK（无进度回调版本）。
     *
     * @param tag Release 标签（如 v1.7.0），用于构造直链与文件名。
     */
    public static void downloadAndInstall(Context ctx, String tag) {
        downloadAndInstall(ctx, tag, null);
    }

    /**
     * 下载并安装正式版 Beta Release 标签对应的最新版 APK。下载直链取自 {@link UpdateChecker#apkDirectUrlBeta}，
     * 即全面对齐新 Beta 库 `ceshi-beta`。
     */
    public static void downloadAndInstallBeta(Context ctx, String tag) {
        downloadAndInstallBeta(ctx, tag, null);
    }

    /**
     * 下载并安装指定 Release 标签对应的最新版 APK。
     *
     * @param tag      Release 标签（如 v1.7.0），用于构造直链与文件名。
     * @param listener 可选：下载进度回调（在应用内显示百分比/速度/预计时长），可为 null。
     */
    public static void downloadAndInstall(Context ctx, String tag, DownloadProgressListener listener) {
        downloadAndInstall(ctx, tag, listener, UpdateChecker.apkDirectUrl(tag));
    }

    /**
     * 下载并安装 Beta 版 Release 标签对应的最新版 APK（Beta 通道，直链指向 `ceshi-beta` 库）。
     *
     * @param tag      Beta Release 标签（如 v1.8.0.6-beta）。
     * @param listener 可选：下载进度回调，可为 null。
     */
    public static void downloadAndInstallBeta(Context ctx, String tag, DownloadProgressListener listener) {
        downloadAndInstall(ctx, tag, listener, UpdateChecker.apkDirectUrlBeta(tag));
    }

    /**
     * 按真实直链下载并安装指定版本（直链来自 Release 资产，避免拼接文件名导致 404）。
     *
     * @param tag      版本标签，仅用于本地文件名展示。
     * @param directUrl 安装包的浏览器直链。
     * @param listener 可选：下载进度回调，可为 null。
     */
    public static void downloadAndInstallUrl(Context ctx, String tag, String directUrl,
                                             DownloadProgressListener listener) {
        downloadAndInstall(ctx, tag, listener, directUrl);
    }

    private static void downloadAndInstall(Context ctx, String tag,
                                           DownloadProgressListener listener, String directUrl) {
        // Android 8.0+ 未授权安装未知来源时，先引导用户去系统设置开启
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !canInstallApps(ctx)) {
            try {
                ctx.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + ctx.getPackageName()))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                Toast.makeText(ctx, ctx.getString(R.string.update_enable_source_goto),
                        Toast.LENGTH_LONG).show();
            } catch (Exception e) {
                Toast.makeText(ctx, ctx.getString(R.string.update_enable_source),
                        Toast.LENGTH_LONG).show();
            }
            return;
        }

        final String fileName =
                "AllToolbox_" + tag.replaceFirst("^[vV]", "") + ".apk";
        // 应用专属外部存储：免分区存储越权，且 FileProvider 可直接分享给安装器。
        File dir = ctx.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS);
        if (dir == null) dir = ctx.getCacheDir();
        final File target = new File(dir, fileName);
        // 清理同版本残留，避免旧文件残留
        if (target.exists()) target.delete();

        Toast.makeText(ctx, ctx.getString(R.string.update_download_started), Toast.LENGTH_LONG).show();

        final String selfVersion = UpdateChecker.localVersion(ctx);
        TaskExecutor.get().io().execute(() -> {
            try {
                download(directUrl, target, selfVersion, ctx, listener);
                postMain(ctx, () -> {
                    if (listener != null) listener.onFinish(true, null);
                    install(ctx, target);
                });
            } catch (final Exception e) {
                final String msg = e.getMessage();
                postMain(ctx, () -> {
                    if (listener != null) listener.onFinish(false, msg);
                    Toast.makeText(ctx, ctx.getString(R.string.update_download_failed, msg), Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    /** 应用自身把直链下载到目标文件（带进度、速度、预计剩余时长统计）。 */
    private static void download(String url, File target, String selfVersion,
                                 Context ctx, DownloadProgressListener listener) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(15000);
        conn.setInstanceFollowRedirects(true);
        conn.setRequestProperty("User-Agent", "AllToolbox/" + selfVersion);
        int code = conn.getResponseCode();
        if (code != 200) throw new java.io.IOException("HTTP " + code);
        long total = conn.getContentLength(); // 服务器可能返回 -1（未知）
        postStarted(ctx, listener, total);

        File tmp = new File(target.getAbsolutePath() + ".tmp");
        long downloaded = 0;
        long segStartBytes = 0;
        long segStartTime = System.currentTimeMillis();
        try (InputStream in = conn.getInputStream();
             OutputStream out = new java.io.FileOutputStream(tmp)) {
            byte[] buf = new byte[32768];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
                downloaded += n;
                long now = System.currentTimeMillis();
                long elapsed = now - segStartTime;
                // 每 500ms 汇报一次，避免过于频繁刷新 UI
                if (elapsed >= 500) {
                    long speed = Math.max(1, (downloaded - segStartBytes) * 1000L / elapsed);
                    long remaining = total > 0
                            ? Math.max(0, (total - downloaded) / speed)
                            : -1;
                    postProgress(ctx, listener, downloaded, total, speed, remaining);
                    segStartBytes = downloaded;
                    segStartTime = now;
                }
            }
        } finally {
            conn.disconnect();
        }
        if (tmp.length() == 0) throw new java.io.IOException(ctx.getString(R.string.update_download_empty));
        if (!tmp.renameTo(target)) {
            throw new java.io.IOException(ctx.getString(R.string.update_write_failed));
        }
    }

    private static void postStarted(Context ctx, DownloadProgressListener l, long total) {
        if (l == null) return;
        final DownloadProgressListener lf = l;
        final long t = total;
        postMain(ctx, () -> lf.onStarted(t));
    }

    private static void postProgress(Context ctx, DownloadProgressListener l,
                                     long d, long t, long speed, long remaining) {
        if (l == null) return;
        final DownloadProgressListener lf = l;
        final long dd = d, tt = t, ss = speed, rr = remaining;
        postMain(ctx, () -> lf.onProgress(dd, tt, ss, rr));
    }

    /** 用 FileProvider 拉起系统安装界面（确定性交付，不会「跳转失败」）。 */
    private static void install(Context ctx, File target) {
        if (target == null || !target.exists() || target.length() == 0) {
            Toast.makeText(ctx, ctx.getString(R.string.update_missing), Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            String authority = ctx.getPackageName() + ".fileprovider";
            Uri apkUri = FileProvider.getUriForFile(ctx, authority, target);
            Intent install = new Intent(Intent.ACTION_VIEW);
            install.setDataAndType(apkUri, APK_MIME);
            install.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            install.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            ctx.startActivity(install);
        } catch (Exception e) {
            Toast.makeText(ctx, ctx.getString(R.string.update_open_install_failed, e.getMessage()), Toast.LENGTH_LONG).show();
        }
    }

    private static void postMain(Context ctx, Runnable r) {
        new Handler(Looper.getMainLooper()).post(r);
    }
}