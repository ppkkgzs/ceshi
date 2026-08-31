package com.alltoolbox.app;

import android.content.Context;
import android.content.pm.PackageManager;

import com.alltoolbox.core.task.TaskExecutor;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;

/**
 * GitHub 版本检测与公告。
 *
 * 公告：应用启动时展示（见 MainActivity）。
 * 版本：请求 GitHub Releases API 与本地 versionName 比较，回调给出是否更新。
 */
public final class UpdateChecker {

    /** 最新版本下载页。 */
    public static final String DOWNLOAD_URL =
            "https://github.com/ppkkgzs/ceshi/tree/main/release";

    private static final String RELEASES_API =
            "https://api.github.com/repos/ppkkgzs/ceshi/releases/latest";

    private UpdateChecker() {
    }

    public interface Result {
        void onResult(boolean isLatest, String latestTag, String message);
    }

    /** 读取本地版本名。 */
    public static String localVersion(Context ctx) {
        try {
            return ctx.getPackageManager()
                    .getPackageInfo(ctx.getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "1.0.0";
        }
    }

    /** 异步检查更新；异常也走回调（message 携带原因）。 */
    public static void checkAsync(Context ctx, Result cb) {
        TaskExecutor.get().io().execute(() -> {
            String latest = null;
            try {
                latest = fetchLatestTag();
            } catch (Exception e) {
                // 网络不可用 / 仓库无 release
                if (cb != null) {
                    cb.onResult(true, null, "无法连接 GitHub：" + e.getMessage());
                }
                return;
            }
            boolean isLatest = compareVersions(localVersion(ctx), latest);
            if (cb != null) {
                cb.onResult(isLatest, latest,
                        isLatest ? "当前已是最新版本" : "发现新版本：" + latest);
            }
        });
    }

    private static String fetchLatestTag() throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(RELEASES_API).openConnection();
        conn.setConnectTimeout(8000);
        conn.setReadTimeout(8000);
        conn.setRequestProperty("Accept", "application/vnd.github+json");
        conn.setRequestProperty("User-Agent", "AllToolbox");
        int code = conn.getResponseCode();
        if (code != 200) throw new IOException(code);
        InputStream in = conn.getInputStream();
        BufferedReader r = new BufferedReader(new InputStreamReader(in));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = r.readLine()) != null) sb.append(line);
        r.close();
        JSONObject obj = new JSONObject(sb.toString());
        String tag = obj.optString("tag_name", "");
        if (tag.isEmpty()) throw new IOException("仓库 Release 为空");
        return tag;
    }

    private static final class IOException extends java.io.IOException {
        IOException(int code) {
            super("HTTP " + code);
        }

        IOException(String msg) {
            super(msg);
        }
    }

    /** 比较版本号 a vs b。数字段逐位比较，a>=b 返回 true。 */
    public static boolean compareVersions(String a, String b) {
        if (a == null && b == null) return true;
        if (a == null) return false;
        if (b == null) return true;
        String[] x = seg(a);
        String[] y = seg(b);
        int n = Math.max(x.length, y.length);
        for (int i = 0; i < n; i++) {
            long vx = i < x.length ? parse(x[i]) : 0;
            long vy = i < y.length ? parse(y[i]) : 0;
            if (vx != vy) return vx >= vy;
        }
        return true;
    }

    private static String[] seg(String v) {
        String s = v.trim();
        // 去掉前导 v
        if (s.toLowerCase(Locale.ROOT).startsWith("v")) s = s.substring(1);
        return s.split("[._-]");
    }

    private static long parse(String p) {
        StringBuilder digits = new StringBuilder();
        for (char c : p.toCharArray()) {
            if (Character.isDigit(c)) digits.append(c);
        }
        if (digits.length() == 0) return 0;
        try {
            return Long.parseLong(digits.toString());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}