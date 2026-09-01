package com.alltoolbox.app;

import android.content.Context;
import android.content.Intent;
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
        addSection(getString(R.string.set_section_startup));
        // 默认首页目录
        addRow(getString(R.string.set_home_dir),
                getString(R.string.set_home_dir_summary, defaultHomeDesc()),
                v -> chooseHomePath());
        // 启动时检查更新
        addSwitch(getString(R.string.set_check_update_on_start),
                getString(R.string.set_check_update_on_start_summary),
                Settings.getBoolean(this, Settings.KEY_UPDATE_CHECK, true),
                (btn, checked) -> Settings.putBoolean(this, Settings.KEY_UPDATE_CHECK, checked));
    }

    private void buildAppearance() {
        addSection(getString(R.string.set_section_appearance));
        addRow(getString(R.string.set_theme),
                themeDesc(),
                v -> chooseTheme());
        addRow(getString(R.string.set_display_mode),
                Settings.getBoolean(this, Settings.KEY_GRID_MODE, false)
                        ? getString(R.string.set_grid) : getString(R.string.set_list),
                v -> chooseDisplayMode());
    }

    private void buildGeneral() {
        addSection(getString(R.string.set_section_general));
        addSwitch(getString(R.string.set_show_hidden),
                getString(R.string.set_show_hidden_summary),
                Settings.getBoolean(this, Settings.KEY_SHOW_HIDDEN, false),
                (btn, checked) -> Settings.putBoolean(this, Settings.KEY_SHOW_HIDDEN, checked));
        addSwitch(getString(R.string.set_dual_pane),
                getString(R.string.set_dual_pane_summary),
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
        addSection(getString(R.string.set_section_install));
        addSwitch(getString(R.string.set_install),
                getString(R.string.set_install_summary),
                Settings.getBoolean(this, "install_extract", true),
                (btn, checked) -> Settings.putBoolean(this, "install_extract", checked));
        addRow(getString(R.string.set_unknown_source),
                getString(R.string.set_unknown_source_summary),
                v -> new MaterialAlertDialogBuilder(this)
                        .setTitle(R.string.set_unknown_source_title)
                        .setMessage(R.string.set_unknown_source_msg)
                        .setPositiveButton(R.string.ok, null)
                        .show());
        addRow(getString(R.string.set_extract_apk),
                getString(R.string.set_extract_apk_summary),
                v -> startActivity(new android.content.Intent(this, ExtractApkActivity.class)));
    }

    private void buildUpdate() {
        addSection(getString(R.string.set_section_update));
        addRow(getString(R.string.set_check_latest),
                getString(R.string.set_check_latest_summary, UpdateChecker.localVersion(this)),
                v -> checkUpdate(false));
        addRow(getString(R.string.set_check_beta),
                getString(R.string.set_check_beta_summary),
                v -> checkUpdate(true));
    }

    /**
     * 统一更新检测（正式版与 Beta 共用同一弹窗）。
     *
     * @param forceBeta true 表示强制走 Beta 通道（“检查 Beta”行）；false 时若本地也装了 Beta 版则自动切到 Beta 通道。
     */
    private void checkUpdate(boolean forceBeta) {
        android.app.ProgressDialog pd = new android.app.ProgressDialog(this);
        pd.setTitle(getString(R.string.set_check_update_title));
        pd.setMessage(getString(R.string.set_check_update_msg));
        pd.setIndeterminate(true);
        pd.setCancelable(false);
        pd.show();

        UpdateChecker.fetchAllVersionsAsync(this, (stable, beta, error) -> runOnUiThread(() -> {
            pd.dismiss();
            if (error != null) {
                new MaterialAlertDialogBuilder(this)
                        .setTitle(R.string.set_check_update_title)
                        .setMessage(error)
                        .setPositiveButton(R.string.ok, null)
                        .show();
                return;
            }
            String local = UpdateChecker.localVersion(this);
            // 已安装 Beta 版则视为 Beta 通道；「检查 Beta」强制走 Beta 通道
            final boolean onBeta = forceBeta || UpdateChecker.isBetaName(local);
            if (onBeta) {
                UpdateChecker.VersionInfo target = highestAbove(beta, local);
                if (target == null) {
                    new MaterialAlertDialogBuilder(this)
                            .setTitle(R.string.set_check_beta_title)
                            .setMessage(getString(R.string.set_latest_beta))
                            .setPositiveButton(R.string.ok, null)
                            .show();
                    return;
                }
                showUnifiedUpdateDialog(true, target.tag);
            } else {
                UpdateChecker.VersionInfo target = highestAbove(stable, local);
                if (target == null) {
                    new MaterialAlertDialogBuilder(this)
                            .setTitle(R.string.set_check_update_title)
                            .setMessage(getString(R.string.update_latest_stable))
                            .setPositiveButton(R.string.ok, null)
                            .show();
                    return;
                }
                showUnifiedUpdateDialog(false, target.tag);
            }
        }));
    }

    /** 统一更新弹窗：可「选择版本下载 / 去链接更新 / 取消」。 */
    private void showUnifiedUpdateDialog(boolean isBeta, String target) {
        String head = isBeta ? getString(R.string.update_found_beta, target)
                : getString(R.string.update_found_stable, target);
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.set_found_update_title)
                .setMessage(head + getString(R.string.update_msg_suffix))
                .setNegativeButton(R.string.cancel, null)
                .setNeutralButton(R.string.update_goto_page, (d, w) ->
                        openBrowser(isBeta ? UpdateChecker.BETA_DOWNLOAD_URL : UpdateChecker.DOWNLOAD_URL))
                .setPositiveButton(R.string.update_select_download, (d, w) -> showVersionSelectDialog())
                .show();
    }

    /** 「选择版本下载」：列出正式版与 Beta 版，用户点选后确认下载。 */
    private void showVersionSelectDialog() {
        android.app.ProgressDialog pd = new android.app.ProgressDialog(this);
        pd.setTitle(getString(R.string.update_select_download));
        pd.setMessage(getString(R.string.update_select_loading));
        pd.setIndeterminate(true);
        pd.setCancelable(false);
        pd.show();

        UpdateChecker.fetchAllVersionsAsync(this, (stable, beta, error) -> runOnUiThread(() -> {
            pd.dismiss();
            if (error != null) {
                toast(error);
                return;
            }
            java.util.List<UpdateChecker.VersionInfo> all = new java.util.ArrayList<>();
            for (UpdateChecker.VersionInfo v : stable) {
                all.add(v);
            }
            for (UpdateChecker.VersionInfo v : beta) {
                all.add(v);
            }
            // 仅保留严格高于当前安装版本的选项，避免降到 / 重下相同或更低版本
            String local = UpdateChecker.localVersion(this);
            final java.util.List<UpdateChecker.VersionInfo> higher =
                    new java.util.ArrayList<>();
            for (UpdateChecker.VersionInfo v : all) {
                if (isStrictlyGreater(v.tag, local)) higher.add(v);
            }
            if (higher.isEmpty()) {
                new MaterialAlertDialogBuilder(this)
                        .setTitle(R.string.update_select_download)
                        .setMessage(R.string.update_select_empty)
                        .setPositiveButton(R.string.ok, null)
                        .show();
                return;
            }
            String[] items = new String[higher.size()];
            for (int i = 0; i < higher.size(); i++) {
                UpdateChecker.VersionInfo v = higher.get(i);
                int prefix = v.beta ? R.string.set_version_beta_prefix : R.string.set_version_stable_prefix;
                items[i] = getString(prefix) + " " + v.tag;
            }
            new MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.update_select_title)
                    .setItems(items, (d, which) -> confirmDownload(higher.get(which)))
                    .setNegativeButton(R.string.cancel, null)
                    .show();
        }));
    }

    /** 确认下载指定版本：使用 Release 资产真实直链，避免拼接文件名导致 404/无进度。 */
    private void confirmDownload(UpdateChecker.VersionInfo v) {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.set_found_update_title)
                .setMessage(getString(R.string.update_confirm_download, v.tag))
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.download_now, (d, w) ->
                        Updater.downloadAndInstallUrl(this, v.tag, v.downloadUrl, new UpdateDownloadProgress()))
                .show();
    }

    /** 在版本列表中找出严格高于 local 的最高版本；没有则返回 null。 */
    private static UpdateChecker.VersionInfo highestAbove(java.util.List<UpdateChecker.VersionInfo> list, String local) {
        UpdateChecker.VersionInfo best = null;
        for (UpdateChecker.VersionInfo v : list) {
            if (isStrictlyGreater(v.tag, local) && (best == null || isStrictlyGreater(v.tag, best.tag))) {
                best = v;
            }
        }
        return best;
    }

    private static boolean isStrictlyGreater(String a, String b) {
        return UpdateChecker.compareVersions(a, b) && !UpdateChecker.compareVersions(b, a);
    }

    private void openBrowser(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception e) {
            toast(getString(R.string.open_link_failed));
        }
    }

    private void buildBookmarksBottomBar() {
        addSection(getString(R.string.set_section_bookmarks));
        addSwitch(getString(R.string.set_bottom_bar),
                getString(R.string.set_bottom_bar_summary),
                Settings.getBoolean(this, "bottom_bar", true),
                (btn, checked) -> Settings.putBoolean(this, "bottom_bar", checked));
        addRow(getString(R.string.set_pane_switch),
                getString(R.string.set_pane_switch_summary),
                v -> new MaterialAlertDialogBuilder(this)
                        .setTitle(R.string.set_pane_switch_title)
                        .setMessage(R.string.set_pane_switch_msg)
                        .setPositiveButton(R.string.ok, null)
                        .show());
        addRow(getString(R.string.set_bookmarks),
                getString(R.string.set_bookmarks_summary),
                v -> new MaterialAlertDialogBuilder(this)
                        .setTitle(R.string.set_bookmarks_title)
                        .setMessage(R.string.set_bookmarks_msg)
                        .setPositiveButton(R.string.ok, null)
                        .show());
    }

    private void buildOthers() {
        addSection(getString(R.string.set_section_others));
        // 时间/日期格式
        addRow(getString(R.string.set_datetime_format),
                Settings.getString(this, Settings.KEY_DATETIME_FORMAT, "yyyy-MM-dd HH:mm:ss"),
                v -> chooseDatetimeFormat());
        // 语言
        addRow(getString(R.string.set_language),
                languageDesc(),
                v -> chooseLanguage());
        // 用户协议与隐私政策
        addRow(getString(R.string.set_privacy),
                getString(R.string.set_privacy_summary),
                v -> showPrivacy());
    }

    // ------------------------------------------------------------------
    // 对话框
    // ------------------------------------------------------------------

    private void chooseHomePath() {
        final EditText et = new EditText(this);
        et.setInputType(InputType.TYPE_CLASS_TEXT);
        et.setHint(R.string.set_home_path_hint);
        et.setText(Settings.getString(this, Settings.KEY_HOME_PATH, ""));
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.set_home_dir)
                .setView(et)
                .setNegativeButton(R.string.set_clear, (d, w) -> {
                    Settings.putString(this, Settings.KEY_HOME_PATH, "");
                    toast(getString(R.string.toast_home_cleared));
                    refreshRows();
                })
                .setPositiveButton(R.string.set_ok, (d, w) -> {
                    String p = et.getText().toString().trim();
                    if (p.isEmpty()) {
                        SelectItem.clearHome(this);
                        toast(getString(R.string.toast_home_cleared));
                    } else if (new File(p).exists()) {
                        Settings.putString(this, Settings.KEY_HOME_PATH, p);
                        toast(getString(R.string.toast_home_set));
                    } else {
                        toast(getString(R.string.toast_path_not_exist));
                    }
                    refreshRows();
                })
                .show();
    }

    private void chooseTheme() {
        String[] items = {
                getString(R.string.set_follow_system),
                getString(R.string.set_light),
                getString(R.string.set_dark)
        };
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.set_theme)
                .setSingleChoiceItems(items, ThemeManager.readMode(this), (d, w) -> {
                    ThemeManager.setMode(this, w);
                    d.dismiss();
                    refreshRows();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void chooseDisplayMode() {
        String[] items = {
                getString(R.string.set_list),
                getString(R.string.set_grid)
        };
        boolean grid = Settings.getBoolean(this, Settings.KEY_GRID_MODE, false);
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.set_display_mode)
                .setSingleChoiceItems(items, grid ? 1 : 0, (d, w) -> {
                    Settings.putBoolean(this, Settings.KEY_GRID_MODE, w == 1);
                    d.dismiss();
                    refreshRows();
                })
                .setNegativeButton(R.string.cancel, null)
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
                .setTitle(R.string.set_datetime_format)
                .setSingleChoiceItems(items, Math.max(check, 0), (d, w) -> {
                    Settings.putString(this, Settings.KEY_DATETIME_FORMAT, items[w]);
                    d.dismiss();
                    refreshRows();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void chooseLanguage() {
        String[] items = {
                getString(R.string.set_follow_system),
                getString(R.string.set_zh),
                "English"
        };
        String cur = Settings.getString(this, Settings.KEY_LANGUAGE, "auto");
        int idx = "zh".equals(cur) ? 1 : ("en".equals(cur) ? 2 : 0);
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.set_language)
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
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    /** 语言生效需重建整个界面：直接退出应用，用户重新打开后即切换到所选语言。 */
    private void restartToApplyLanguage() {
        toast(getString(R.string.toast_lang_switched));
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
                .setPositiveButton(R.string.set_i_agree, null)
                .show();
    }

    /** 下载进度：以水平进度条对话框展示百分比、速度。回调均在主线程。 */
    private final class UpdateDownloadProgress implements Updater.DownloadProgressListener {
        private android.app.ProgressDialog pd;

        @Override
        public void onStarted(long totalBytes) {
            pd = new android.app.ProgressDialog(SettingsActivity.this);
            pd.setTitle(getString(R.string.set_download_title));
            pd.setProgressStyle(android.app.ProgressDialog.STYLE_HORIZONTAL);
            pd.setIndeterminate(false);
            pd.setCancelable(false);
            if (totalBytes > 0) {
                pd.setMax((int) Math.min(Integer.MAX_VALUE, totalBytes));
            }
            pd.setMessage("0 B / " + (totalBytes > 0 ? fmtSize(totalBytes)
                    : getString(R.string.set_download_unknown)));
            pd.show();
        }

        @Override
        public void onProgress(long downloadedBytes, long totalBytes,
                               long speedBps, long remainingSeconds) {
            if (pd == null) return;
            if (totalBytes > 0 && pd.getMax() > 0) {
                pd.setProgress((int) Math.min(pd.getMax(), downloadedBytes));
            }
            String total = totalBytes > 0 ? " / " + fmtSize(totalBytes) : "";
            pd.setMessage(getString(R.string.set_download_progress,
                    fmtSize(downloadedBytes), total, fmtSize(speedBps)));
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
        return p.isEmpty() ? getString(R.string.set_default_home) : p;
    }

    private String themeDesc() {
        switch (ThemeManager.readMode(this)) {
            case 1: return getString(R.string.set_light);
            case 2: return getString(R.string.set_dark);
            default: return getString(R.string.set_follow_system);
        }
    }

    private String languageDesc() {
        String cur = Settings.getString(this, Settings.KEY_LANGUAGE, "auto");
        if ("zh".equals(cur)) return getString(R.string.set_zh);
        if ("en".equals(cur)) return "English";
        return getString(R.string.set_follow_system);
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
        tv.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
        tv.setPadding(dp(12), dp(18), dp(12), dp(6));
        tv.setTextColor(0xFF1A73E8);
        tv.setBackgroundResource(R.drawable.section_header_bg);
        container.addView(tv);
    }

    private void addRow(String title, String summary, View.OnClickListener onClick) {
        View v = LayoutInflater.from(this).inflate(R.layout.item_settings_row, container, false);
        ((TextView) v.findViewById(R.id.row_title)).setText(title);
        ((TextView) v.findViewById(R.id.row_summary)).setText(summary);
        v.findViewById(R.id.row_chevron).setVisibility(View.VISIBLE);
        v.setOnClickListener(onClick);
        // 行进入动画：淡入 + 上移，逐行顺次浮现
        int pos = container.getChildCount();
        v.setAlpha(0f);
        v.setTranslationY(dp(16));
        v.animate().alpha(1f).translationY(0f)
                .setDuration(240).setStartDelay(pos * 30L)
                .setInterpolator(new android.view.animation.DecelerateInterpolator())
                .start();
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