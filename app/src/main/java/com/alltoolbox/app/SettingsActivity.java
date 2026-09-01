package com.alltoolbox.app;

import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.alltoolbox.core.LocaleUtil;
import com.alltoolbox.core.setting.Settings;
import com.alltoolbox.core.theme.ThemeManager;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.File;

/**
 * 设置页：分组展示启动 / 外观 / 常规 / 安装 / 书签与底栏 / 其他 各选项。
 * 选项值写入 {@link Settings} 全局 SharedPreferences 中。
 */
public class SettingsActivity extends AppCompatActivity {

    private LinearLayout container;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        setTitle(getString(R.string.settings_title));

        androidx.appcompat.widget.Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        container = findViewById(R.id.settings_container);

        buildStartup();
        buildAppearance();
        buildGeneral();
        buildInstall();
        buildUpdate();
        buildBookmarksBottomBar();
        buildOthers();
    }

    @Override
    public boolean onOptionsItemSelected(android.view.MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    // ------------------------------------------------------------------
    // 各分组
    // ------------------------------------------------------------------

    private void buildStartup() {
        addSection("启动");
        // 默认首页目录
        addRow("默认首页目录",
                "当前：" + defaultHomeDesc(),
                v -> chooseHomePath());
        // 启动时检查更新
        addSwitch("启动时检查更新",
                "开启后每次打开应用自动检测 GitHub 最新版本",
                Settings.getBoolean(this, Settings.KEY_UPDATE_CHECK, true),
                (btn, checked) -> Settings.putBoolean(this, Settings.KEY_UPDATE_CHECK, checked));
    }

    private void buildAppearance() {
        addSection("外观");
        addRow("主题模式",
                themeDesc(),
                v -> chooseTheme());
        addRow("显示方式",
                Settings.getBoolean(this, Settings.KEY_GRID_MODE, false) ? "网格" : "列表",
                v -> chooseDisplayMode());
    }

    private void buildGeneral() {
        addSection("常规");
        addSwitch("显示隐藏文件",
                "开启后浏览器中显示 . 开头的隐藏文件",
                Settings.getBoolean(this, Settings.KEY_SHOW_HIDDEN, false),
                (btn, checked) -> Settings.putBoolean(this, Settings.KEY_SHOW_HIDDEN, checked));
        addSwitch("默认双栏模式",
                "进入双栏文件管理器时是否显示左右两栏；关闭则切到单栏（平板默认开启，小屏默认单栏）",
                Settings.getBoolean(this, Settings.KEY_DUAL_PANE, isTablet(this)),
                (btn, checked) -> Settings.putBoolean(this, Settings.KEY_DUAL_PANE, checked));
    }

    /** 判断是否为平板（大屏）设备，用于确定双栏模式的默认值。 */
    private static boolean isTablet(Context c) {
        int size = c.getResources().getConfiguration().screenLayout
                & android.content.res.Configuration.SCREENLAYOUT_SIZE_MASK;
        return size >= android.content.res.Configuration.SCREENLAYOUT_SIZE_LARGE;
    }

    private void buildInstall() {
        addSection("安装");
        addSwitch("允许提取/安装 APK",
                "开启后可从文件管理器/包详情提取安装包并请求安装权限",
                Settings.getBoolean(this, "install_extract", true),
                (btn, checked) -> Settings.putBoolean(this, "install_extract", checked));
        addRow("安装未知来源说明",
                "安装第三方 APK 时，系统会引导到「允许此来源的应用」设置页",
                v -> new MaterialAlertDialogBuilder(this)
                        .setTitle("安装未知来源")
                        .setMessage("提取的安装包可通过系统安装器安装。\n\n若系统弹出「未知来源」提示，请到\n设置 → 应用 → 本应用 → 允许安装未知应用\n中开启权限。")
                        .setPositiveButton("知道了", null)
                        .show());
        addRow("提取安装包",
                "提取已安装应用的 APK 到「下载」目录（需存储权限）",
                v -> startActivity(new android.content.Intent(this, ExtractApkActivity.class)));
    }

    private void buildUpdate() {
        addSection("更新");
        addRow("检查最新版本",
                "当前版本：" + UpdateChecker.localVersion(this) + "｜检测 GitHub 最新版并直接下载安装",
                v -> checkUpdate());
        addRow("检查 Beta 测试版更新（直链）",
                "获取最新测试版 Pre-Release，可直接下载安装",
                v -> checkBetaUpdate());
    }

    /** 点击「检查 Beta 测试版更新（直链）」：查找 GitHub 最新 Pre-Release 并直接下载。 */
    private void checkBetaUpdate() {
        android.app.ProgressDialog pd = new android.app.ProgressDialog(this);
        pd.setTitle("检查 Beta 更新");
        pd.setMessage("正在检查最新测试版本…");
        pd.setIndeterminate(true);
        pd.setCancelable(false);
        pd.show();

        UpdateChecker.checkBetaAsync(this, (isLatest, latestTag, message) -> {
            runOnUiThread(() -> {
                pd.dismiss();
                if (!isLatest && latestTag != null && !latestTag.isEmpty()) {
                    new MaterialAlertDialogBuilder(this)
                            .setTitle("发现 Beta 新版本")
                            .setMessage("检测到最新测试版本 " + latestTag + "\n是否立即下载并安装？")
                            .setNegativeButton("取消", null)
                            .setPositiveButton("立即下载", (d, w) ->
                                    Updater.downloadAndInstall(this, latestTag,
                                            new UpdateDownloadProgress()))
                            .show();
                } else {
                    String tip = (message == null || message.isEmpty())
                            ? "当前已是最新 Beta 版本"
                            : ("当前已是最新 Beta 版本；" + message);
                    new MaterialAlertDialogBuilder(this)
                            .setTitle("检查 Beta 更新")
                            .setMessage(tip)
                            .setPositiveButton("知道了", null)
                            .show();
                }
            });
        });
    }

    private void buildBookmarksBottomBar() {
        addSection("书签与底栏");
        addSwitch("启用底栏",
                "主界面底部显示功能导航栏",
                Settings.getBoolean(this, "bottom_bar", true),
                (btn, checked) -> Settings.putBoolean(this, "bottom_bar", checked));
        addRow("底栏左/右切换",
                "双栏模式下，底栏用于选择当前操作的栏（左栏/右栏）",
                v -> new MaterialAlertDialogBuilder(this)
                        .setTitle("底栏双栏说明")
                        .setMessage("双栏模式下，点击底栏的「左栏/右栏」选中当前操作的栏。\n返回键只在选中的栏内向上返回，不影响另一栏。")
                        .setPositiveButton("知道了", null)
                        .show());
        addRow("书签管理",
                "文件管理器右键/菜单「添加书签」，可在书签与底栏中跳转",
                v -> new MaterialAlertDialogBuilder(this)
                        .setTitle("书签")
                        .setMessage("书签功能已集成在文件管理器右上角菜单的「添加书签」中，添加后可在书签页快速跳转。")
                        .setPositiveButton("知道了", null)
                        .show());
    }

    private void buildOthers() {
        addSection("其他");
        // 时间/日期格式
        addRow("时间/日期格式",
                Settings.getString(this, Settings.KEY_DATETIME_FORMAT, "yyyy-MM-dd HH:mm:ss"),
                v -> chooseDatetimeFormat());
        // 语言
        addRow("语言",
                languageDesc(),
                v -> chooseLanguage());
        // 用户协议与隐私政策
        addRow("用户协议与隐私政策",
                "查看本应用的用户协议与隐私说明",
                v -> showPrivacy());
    }

    // ------------------------------------------------------------------
    // 对话框
    // ------------------------------------------------------------------

    private void chooseHomePath() {
        final EditText et = new EditText(this);
        et.setInputType(InputType.TYPE_CLASS_TEXT);
        et.setHint("输入绝对路径，如 /sdcard/Download");
        et.setText(Settings.getString(this, Settings.KEY_HOME_PATH, ""));
        new MaterialAlertDialogBuilder(this)
                .setTitle("默认首页目录")
                .setView(et)
                .setNegativeButton("清除", (d, w) -> {
                    Settings.putString(this, Settings.KEY_HOME_PATH, "");
                    toast("已清除，首页回到默认目录");
                    refreshRows();
                })
                .setPositiveButton("确定", (d, w) -> {
                    String p = et.getText().toString().trim();
                    if (p.isEmpty()) {
                        SelectItem.clearHome(this);
                        toast("已清除，首页回到默认目录");
                    } else if (new File(p).exists()) {
                        Settings.putString(this, Settings.KEY_HOME_PATH, p);
                        toast("已设置首页目录");
                    } else {
                        toast("路径不存在");
                    }
                    refreshRows();
                })
                .show();
    }

    private void chooseTheme() {
        String[] items = {"跟随系统", "浅色", "深色"};
        new MaterialAlertDialogBuilder(this)
                .setTitle("主题模式")
                .setSingleChoiceItems(items, ThemeManager.readMode(this), (d, w) -> {
                    ThemeManager.setMode(this, w);
                    d.dismiss();
                    refreshRows();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void chooseDisplayMode() {
        String[] items = {"列表", "网格"};
        boolean grid = Settings.getBoolean(this, Settings.KEY_GRID_MODE, false);
        new MaterialAlertDialogBuilder(this)
                .setTitle("显示方式")
                .setSingleChoiceItems(items, grid ? 1 : 0, (d, w) -> {
                    Settings.putBoolean(this, Settings.KEY_GRID_MODE, w == 1);
                    d.dismiss();
                    refreshRows();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void chooseDatetimeFormat() {
        String[] items = {
                "yyyy-MM-dd HH:mm:ss",
                "yyyy/MM/dd HH:mm",
                "yyyy年MM月dd日 HH:mm",
                "MM/dd/yyyy hh:mm a",
                "HH:mm:ss"
        };
        String cur = Settings.getString(this, Settings.KEY_DATETIME_FORMAT, "yyyy-MM-dd HH:mm:ss");
        int check = java.util.Arrays.asList(items).indexOf(cur);
        new MaterialAlertDialogBuilder(this)
                .setTitle("时间/日期格式")
                .setSingleChoiceItems(items, Math.max(check, 0), (d, w) -> {
                    Settings.putString(this, Settings.KEY_DATETIME_FORMAT, items[w]);
                    d.dismiss();
                    refreshRows();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void chooseLanguage() {
        String[] items = {"跟随系统", "简体中文", "English"};
        String cur = Settings.getString(this, Settings.KEY_LANGUAGE, "auto");
        int idx = "zh".equals(cur) ? 1 : ("en".equals(cur) ? 2 : 0);
        new MaterialAlertDialogBuilder(this)
                .setTitle("语言")
                .setSingleChoiceItems(items, idx, (d, w) -> {
                    String v = w == 0 ? "auto" : (w == 1 ? "zh" : "en");
                    // 同步落盘，确保进程退出后下次启动能读到
                    getSharedPreferences(Settings.PREFS, MODE_PRIVATE)
                            .edit().putString(Settings.KEY_LANGUAGE, v).commit();
                    d.dismiss();
                    // 先即时应用新语言（AppCompat 会重建界面），再退出
                    LocaleUtil.applyToApp(SettingsActivity.this);
                    // 直接退出应用，重新打开后即切换为所选语言
                    restartToApplyLanguage();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 语言生效需重建整个界面：直接退出应用，用户重新打开后即切换到所选语言。 */
    private void restartToApplyLanguage() {
        toast("语言已切换，正在退出应用，请重新打开");
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
            finishAffinity();
            android.os.Process.killProcess(android.os.Process.myPid());
            System.exit(0);
        }, 300);
    }

    private void showPrivacy() {
        String content = getString(R.string.privacy_text);
        ScrollView scrollView = new ScrollView(this);
        TextView textView = new TextView(this);
        int padding = dp(16);
        textView.setPadding(padding, padding/2, padding, padding);
        textView.setText(content);
        textView.setTextSize(14);
        scrollView.addView(textView);
        int maxH = (int) (getResources().getDisplayMetrics().heightPixels * 0.6f);
        scrollView.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, maxH));
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.privacy_title)
                .setView(scrollView)
                .setPositiveButton("我已阅读并同意", null)
                .show();
    }

    /** 点击「检查最新版本」：先显示检查动画，再按结果提示或直接下载。 */
    private void checkUpdate() {
        // 检查动画：带旋转进度条的对话框
        android.app.ProgressDialog pd = new android.app.ProgressDialog(this);
        pd.setTitle("检查更新");
        pd.setMessage("正在检查最新版本…");
        pd.setIndeterminate(true);
        pd.setCancelable(false);
        pd.show();

        UpdateChecker.checkAsync(this, (isLatest, latestTag, message) -> {
            runOnUiThread(() -> {
                pd.dismiss();
                if (!isLatest && latestTag != null && !latestTag.isEmpty()) {
                    // 有最新版本 -> 弹窗提示，点击后直接下载（直链）
                    new MaterialAlertDialogBuilder(this)
                            .setTitle("发现新版本")
                            .setMessage("检测到最新版本 " + latestTag + "\n是否立即下载并安装？")
                            .setNegativeButton("取消", null)
                            .setPositiveButton("立即下载", (d, w) ->
                                    Updater.downloadAndInstall(this, latestTag,
                                            new UpdateDownloadProgress()))
                            .show();
                } else {
                    // 已是最新（或网络异常），提示原因
                    String tip = (message == null || message.isEmpty())
                            ? "你已经是最新版本"
                            : ("你已经是最新版本；" + message);
                    new MaterialAlertDialogBuilder(this)
                            .setTitle("检查更新")
                            .setMessage(tip)
                            .setPositiveButton("知道了", null)
                            .show();
                }
            });
        });
    }

    /** 下载进度：以水平进度条对话框展示百分比、速度。回调均在主线程。 */
    private final class UpdateDownloadProgress implements Updater.DownloadProgressListener {
        private android.app.ProgressDialog pd;

        @Override
        public void onStarted(long totalBytes) {
            pd = new android.app.ProgressDialog(SettingsActivity.this);
            pd.setTitle("下载更新包");
            pd.setProgressStyle(android.app.ProgressDialog.STYLE_HORIZONTAL);
            pd.setIndeterminate(false);
            pd.setCancelable(false);
            if (totalBytes > 0) {
                pd.setMax((int) Math.min(Integer.MAX_VALUE, totalBytes));
            }
            pd.setMessage("0 B / " + (totalBytes > 0 ? fmtSize(totalBytes) : "未知"));
            pd.show();
        }

        @Override
        public void onProgress(long downloadedBytes, long totalBytes,
                               long speedBps, long remainingSeconds) {
            if (pd == null) return;
            if (totalBytes > 0 && pd.getMax() > 0) {
                pd.setProgress((int) Math.min(pd.getMax(), downloadedBytes));
            }
            pd.setMessage("已下载 " + fmtSize(downloadedBytes)
                    + (totalBytes > 0 ? " / " + fmtSize(totalBytes) : "")
                    + "\n速度：" + fmtSize(speedBps) + "/s");
        }

        @Override
        public void onFinish(boolean success, String message) {
            if (pd != null) {
                pd.dismiss();
                pd = null;
            }
        }
    }

    /** 字节数格式化：B / KB / MB / GB。 */
    private static String fmtSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(java.util.Locale.ROOT, "%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format(java.util.Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024));
        return String.format(java.util.Locale.ROOT, "%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }

    // ------------------------------------------------------------------
    // 描述文案
    // ------------------------------------------------------------------

    private String defaultHomeDesc() {
        String p = Settings.getString(this, Settings.KEY_HOME_PATH, "");
        return p.isEmpty() ? "默认（外部存储）" : p;
    }

    private String themeDesc() {
        switch (ThemeManager.readMode(this)) {
            case 1: return "浅色";
            case 2: return "深色";
            default: return "跟随系统";
        }
    }

    private String languageDesc() {
        String cur = Settings.getString(this, Settings.KEY_LANGUAGE, "auto");
        if ("zh".equals(cur)) return "简体中文";
        if ("en".equals(cur)) return "English";
        return "跟随系统";
    }

    /** 重建行列表以刷新摘要。 */
    private void refreshRows() {
        container.removeAllViews();
        buildStartup();
        buildAppearance();
        buildGeneral();
        buildInstall();
        buildUpdate();
        buildBookmarksBottomBar();
        buildOthers();
    }

    // ------------------------------------------------------------------
    // 构建行
    // ------------------------------------------------------------------

    private void addSection(String title) {
        TextView tv = new TextView(this);
        tv.setText(title);
        tv.setTextSize(14);
        tv.setPadding(dp(16), dp(18), dp(16), dp(6));
        tv.setTextColor(0xFF1A73E8);
        container.addView(tv);
    }

    private void addRow(String title, String summary, View.OnClickListener onClick) {
        View v = LayoutInflater.from(this).inflate(R.layout.item_settings_row, container, false);
        ((TextView) v.findViewById(R.id.row_title)).setText(title);
        ((TextView) v.findViewById(R.id.row_summary)).setText(summary);
        v.findViewById(R.id.row_chevron).setVisibility(View.VISIBLE);
        v.setOnClickListener(onClick);
        container.addView(v);
    }

    private void addSwitch(String title, String summary,
                           boolean checked, android.widget.CompoundButton.OnCheckedChangeListener l) {
        View v = LayoutInflater.from(this).inflate(R.layout.item_settings_switch, container, false);
        ((TextView) v.findViewById(R.id.sw_title)).setText(title);
        ((TextView) v.findViewById(R.id.sw_summary)).setText(summary);
        ((Switch) v.findViewById(R.id.sw_toggle)).setOnCheckedChangeListener(null);
        ((Switch) v.findViewById(R.id.sw_toggle)).setChecked(checked);
        ((Switch) v.findViewById(R.id.sw_toggle)).setOnCheckedChangeListener(l);
        container.addView(v);
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private void toast(String msg) {
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show();
    }

    /** 简单辅助类：清除首页目录。 */
    static final class SelectItem {
        static void clearHome(Context ctx) {
            Settings.putString(ctx, Settings.KEY_HOME_PATH, "");
        }
    }
}