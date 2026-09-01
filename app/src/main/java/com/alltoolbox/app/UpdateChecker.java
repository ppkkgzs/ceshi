package com.alltoolbox.app;

import android.content.Context;
import android.content.pm.PackageManager;

import com.alltoolbox.core.task.TaskExecutor;

import org.json.JSONArray;
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

    /** 所有版本下载页（正式版 + Beta 版都在此列出）。 */
    public static final String DOWNLOAD_URL =
            "https://github.com/ppkkgzs/ceshi/releases";

    /** Beta 版本下载页（仅显示全部 Beta 测试版预发行版本）。 */
    public static final String BETA_DOWNLOAD_URL =
            "https://github.com/ppkkgzs/ceshi-beta/releases";

    /** Release 安装包直链前缀（正式版，见 {@link #apkDirectUrl}）。 */
    static final String RELEASE_BASE =
            "https://github.com/ppkkgzs/ceshi/releases/download/%s/AllToolbox_%s.apk";

    /** Beta 版安装包直链前缀（见 {@link #apkDirectUrlBeta}）。 */
    static final String BETA_RELEASE_BASE =
            "https://github.com/ppkkgzs/ceshi-beta/releases/download/%s/AllToolbox_%s.apk";

    private static final String RELEASES_API =
            "https://api.github.com/repos/ppkkgzs/ceshi/releases/latest";

    /** 拉取最近若干 Release（含预发行），供 Beta 通道选最高 tag。 */
    private static final String ALL_RELEASES_API =
            "https://api.github.com/repos/ppkkgzs/ceshi-beta/releases?per_page=50&page=1";

    /** 由 tag（如 v1.6.6）构造正式版安装包直接下载链接。 */
    public static String apkDirectUrl(String tag) {
        return apkDirectUrlFor(RELEASE_BASE, tag);
    }

    /** 由 tag（如 v1.8.0.6-beta）构造 Beta 版安装包直接下载链接。 */
    public static String apkDirectUrlBeta(String tag) {
        return apkDirectUrlFor(BETA_RELEASE_BASE, tag);
    }

    private static String apkDirectUrlFor(String base, String tag) {
        if (tag == null) tag = "";
        String v = tag;
        if (v.toLowerCase(Locale.ROOT).startsWith("v")) v = v.substring(1);
        return String.format(base, tag, v);
    }

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

    /** 异步检查 Beta 测试版更新；异常也走回调（message 携带原因）。 */
    public static void checkBetaAsync(Context ctx, Result cb) {
        TaskExecutor.get().io().execute(() -> {
            String latestBeta = null;
            try {
                latestBeta = fetchLatestBetaTag();
            } catch (Exception e) {
                if (cb != null) {
                    cb.onResult(true, null, "无法连接 GitHub：" + e.getMessage());
                }
                return;
            }
            boolean isLatest = compareVersions(localVersion(ctx), latestBeta);
            if (cb != null) {
                cb.onResult(isLatest, latestBeta,
                        isLatest ? "当前已是最新 Beta 版本" : "发现 Beta 新版本：" + latestBeta);
            }
        });
    }

    /**
     * 从 Releases 列表里选出最新的预发行（Prerelease）版 tag。
     * 预发型即测试版（Beta），正式版不带「beta」字样。
     */
    private static String fetchLatestBetaTag() throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(ALL_RELEASES_API).openConnection();
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
        JSONArray arr = new JSONArray(sb.toString());
        String best = "";
        for (int i = 0; i < arr.length(); i++) {
            JSONObject rel = arr.optJSONObject(i);
            if (rel == null) continue;
            // 只认预发行（Beta）版本
            if (!rel.optBoolean("prerelease", false)) continue;
            if (rel.optBoolean("draft", false)) continue;
            String tag = rel.optString("tag_name", "");
            if (tag.isEmpty()) continue;
            if (compareVersions(tag, best)) best = tag;
        }
        if (best.isEmpty()) throw new IOException("仓库暂无 Beta 预发行版本");
        return best;
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