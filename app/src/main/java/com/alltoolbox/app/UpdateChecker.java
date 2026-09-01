package com.alltoolbox.app;

import android.content.Context;
import android.content.pm.PackageManager;

import com.alltoolbox.core.task.TaskExecutor;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * GitHub 版本检测与公告。
 *
 * 公告：应用启动时展示（见 MainActivity）。
 * 版本：请求 GitHub 标签订阅源（tags.atom，无 API 限流）与本地 versionName 比较，回调给出是否更新。
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

    /** 正式版 Tag 列表（Atom feed，无 API 限流），供「选择版本下载」与最新版检测使用。 */
    private static final String STABLE_TAGS_ATOM =
            "https://github.com/ppkkgzs/ceshi/tags.atom";

    /** Beta 版 Tag 列表（Atom feed，无 API 限流）。 */
    private static final String BETA_TAGS_ATOM =
            "https://github.com/ppkkgzs/ceshi-beta/tags.atom";

    /**
     * jsDelivr CDN 版版本列表（{@code version.json}，含 {@code stable} / {@code beta} 两个数组）。
     * 直连 GitHub Atom feed 失败（用户网络无法连接 GitHub）时回退到该源。
     */
    public static final String CDN_VERSION_JSON =
            "https://cdn.jsdelivr.net/gh/ppkkgzs/ceshi@main/version.json";

    /**
     * GitHub 下载镜像前缀列表（按可达性/优先级排序）。
     * 原始直连 github.com 失败（国内网络被墙/不稳定）时，按此顺序依次尝试各镜像。
     */
    private static final String[] MIRROR_PREFIXES = {
            "https://ghfast.top/",
            "https://ghproxy.net/",
            "https://gh-proxy.com/",
            "https://mirror.ghproxy.com/"
    };

    /** 返回全部下载镜像前缀（供下载回退逐个尝试）。 */
    public static String[] mirrorPrefixes() {
        return MIRROR_PREFIXES;
    }

    /** 用指定镜像前缀把 GitHub 原始直链转为镜像直链；非 GitHub 链接返回 null。下载回退用。 */
    public static String mirrorUrl(String githubUrl, String prefix) {
        if (githubUrl == null || prefix == null) return null;
        if (githubUrl.startsWith("https://github.com/")) {
            return prefix + githubUrl;
        }
        return null;
    }

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

    /**
     * 单个可用版本：{@code tag}=Release 标签，{@code downloadUrl}=安装包真实直链，{@code beta}=是否 Beta 通道。
     * 直链取自 Release 资产（browser_download_url），不再靠字符串拼接猜测，避免下载失败/无进度。
     */
    public static final class VersionInfo {
        public final String tag;
        public final String downloadUrl;
        public final boolean beta;

        public VersionInfo(String tag, String downloadUrl, boolean beta) {
            this.tag = tag;
            this.downloadUrl = downloadUrl;
            this.beta = beta;
        }
    }

    /** 拉取到的版本列表结果：{@code stable}=正式版，{@code beta}=测试版，{@code error}=失败原因。 */
    public interface AllVersionsCallback {
        void onResult(List<VersionInfo> stable, List<VersionInfo> beta, String error);
    }

    /**
     * 异步拉取正式版与 Beta 版的全部分支版本列表，供「选择版本下载」使用。
     * 成功时 {@code error} 为 null；失败时两个列表为空。
     */
    public static void fetchAllVersionsAsync(Context ctx, AllVersionsCallback cb) {
        TaskExecutor.get().io().execute(() -> {
            try {
                List<VersionInfo> stable = fetchVersionInfos(CHANNEL_STABLE, false);
                List<VersionInfo> beta = fetchVersionInfos(CHANNEL_BETA, true);
                if (cb != null) cb.onResult(stable, beta, null);
            } catch (Exception e) {
                if (cb != null) {
                    cb.onResult(java.util.Collections.emptyList(),
                            java.util.Collections.emptyList(), "无法连接更新源：" + e.getMessage());
                }
            }
        });
    }

    private static final String CHANNEL_STABLE = "stable";
    private static final String CHANNEL_BETA = "beta";

    /**
     * 从 tag 列表拉取版本信息（标签 + 安装包直链）。
     * 先直连 GitHub 原子订阅源（tags.atom，无 API 限流）；连接失败时回退到
     * jsDelivr CDN 的 {@code version.json}，保证用户网络无法连接 GitHub 时仍能检测。
     *
     * @param channel 通道：{@code "stable"} 或 {@code "beta"}。
     * @param beta    下载链接归属的通道（true = ceshi-beta）。
     */
    private static List<VersionInfo> fetchVersionInfos(String channel, boolean beta) throws Exception {
        List<String> tags = fetchTagsWithFallback(channel);
        List<VersionInfo> out = new ArrayList<>();
        for (String tag : tags) {
            if (tag.isEmpty()) continue;
            String url = beta ? apkDirectUrlBeta(tag) : apkDirectUrl(tag);
            out.add(new VersionInfo(tag, url, beta));
        }
        return out;
    }

    /**
     * 拉取指定通道的 tag 列表——三个独立检测源分别尝试：
     * ① 直连 GitHub Atom feed；② 方案：jsDelivr CDN 的 version.json；③ 各 GitHub 镜像代理的 Atom feed。
     * 每个源都单独判定（互不依赖），任意一个取到数据即返回，全部失败才抛异常。
     */
    private static List<String> fetchTagsWithFallback(String channel) throws Exception {
        String atom = CHANNEL_BETA.equals(channel) ? BETA_TAGS_ATOM : STABLE_TAGS_ATOM;
        List<Throwable> errors = new ArrayList<>();

        // ① 直连 GitHub tags.atom（原站）
        try {
            return fetchTags(atom);
        } catch (Exception e1) {
            errors.add(e1);
        }

        // ② 方案：jsDelivr CDN version.json（国内可达）
        try {
            return fetchTagsFromCdn(channel);
        } catch (Exception e2) {
            errors.add(e2);
        }

        // ③ 镜像：按序尝试各 GitHub 代理前缀，等价于把 ① 的 Atom feed 转发加速
        for (String prefix : mirrorPrefixes()) {
            String mirrorFeed = mirrorUrl(atom, prefix);
            if (mirrorFeed == null) continue;
            try {
                return fetchTags(mirrorFeed);
            } catch (Exception em) {
                errors.add(em);
            }
        }

        throw new IOException("直连(Atom)/方案(CDN)/镜像(Mirror) 均失败："
                + (errors.isEmpty() ? "未知错误" : errors.get(errors.size() - 1).getMessage()));
    }

    /**
     * 从 jsDelivr CDN 的 {@code version.json} 拉取指定通道的 tag 列表。
     * json 形如 {@code {"stable":["v1.8.8",...],"beta":[...]}}。
     */
    private static List<String> fetchTagsFromCdn(String channel) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(CDN_VERSION_JSON).openConnection();
        conn.setConnectTimeout(8000);
        conn.setReadTimeout(8000);
        conn.setRequestProperty("User-Agent", "AllToolbox");
        int code = conn.getResponseCode();
        if (code != 200) throw new IOException(code);
        InputStream in = conn.getInputStream();
        BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = r.readLine()) != null) sb.append(line);
        r.close();
        List<String> tags = new ArrayList<>();
        parseChannel(sb.toString(), channel, tags);
        if (tags.isEmpty()) throw new IOException("CDN 版本列表为空");
        return tags;
    }

    /** 轻量 JSON 数组解析（避免额外依赖）。 */
    private static void parseChannel(String json, String channel, List<String> tags) {
        String key = "\"" + channel + "\"";
        int k = json.indexOf(key);
        if (k < 0) return;
        int colon = json.indexOf(':', k);
        if (colon < 0) return;
        int open = json.indexOf('[', colon);
        if (open < 0) return;
        int close = json.indexOf(']', open);
        if (close < 0) return;
        String body = json.substring(open + 1, close);
        int idx = 0;
        while (true) {
            int q = body.indexOf('"', idx);
            if (q < 0) break;
            int e = body.indexOf('"', q + 1);
            if (e < 0) break;
            String tag = body.substring(q + 1, e).trim();
            if (!tag.isEmpty()) tags.add(tag);
            idx = e + 1;
        }
    }

    /**
     * 从 Atom feed 拉取全部 Release 标签（按时间倒序，最新在前）。
     * 从每条 entry 的 {@code <id>tag:github.com,2008:Repository/<仓库ID>/<tag></id>} 中截取 tag。
     */
    private static List<String> fetchTags(String feedUrl) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(feedUrl).openConnection();
        conn.setConnectTimeout(8000);
        conn.setReadTimeout(8000);
        conn.setRequestProperty("User-Agent", "AllToolbox");
        int code = conn.getResponseCode();
        if (code != 200) throw new IOException(code);
        InputStream in = conn.getInputStream();
        BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = r.readLine()) != null) sb.append(line);
        r.close();
        List<String> tags = new ArrayList<>();
        String xml = sb.toString();
        int idx = 0;
        while (true) {
            int s = xml.indexOf("<id>", idx);
            if (s < 0) break;
            s += "<id>".length();
            int e = xml.indexOf("</id>", s);
            if (e < 0) break;
            String id = xml.substring(s, e).trim();
            idx = e + "</id>".length();
            // 跳过 feed 级的 <id>，只保留仓库条目：.../Repository/<id>/<tag>
            if (!id.contains("Repository/")) continue;
            int slash = id.lastIndexOf('/');
            if (slash < 0) continue;
            String tag = id.substring(slash + 1).trim();
            if (!tag.isEmpty()) tags.add(tag);
        }
        return tags;
    }

    /** 是否为 Beta 版版本名（版本串中带「beta」字样）。 */
    public static boolean isBetaName(String v) {
        return v != null && v.toLowerCase(Locale.ROOT).contains("beta");
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
        return pickHighestTag(fetchTagsWithFallback(CHANNEL_STABLE));
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
     * 从 Atom 标签列表中选出最高的 tag 作为最新版。
     */
    private static String fetchLatestBetaTag() throws Exception {
        return pickHighestTag(fetchTagsWithFallback(CHANNEL_BETA));
    }

    /** 从 tag 列表选出数值最大者；无标签则抛异常。 */
    private static String pickHighestTag(List<String> tags) throws Exception {
        String best = "";
        for (String t : tags) {
            if (t != null && t.length() > 0 && compareVersions(t, best)) {
                best = t;
            }
        }
        if (best.isEmpty()) throw new IOException("仓库 Release 为空");
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