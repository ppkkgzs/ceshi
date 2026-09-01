package com.alltoolbox.app;

import android.content.Intent;
import android.app.Dialog;
import android.os.Bundle;
import android.text.method.LinkMovementMethod;
import android.text.util.Linkify;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.ActionBarDrawerToggle;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.core.app.ActivityCompat;

import android.Manifest;
import android.content.pm.PackageManager;

import com.alltoolbox.archive.ArchiveActivity;
import com.alltoolbox.cleanup.CleanupActivity;
import com.alltoolbox.core.AppContext;
import com.alltoolbox.core.permission.Permissions;
import com.alltoolbox.core.permission.ShizukuShell;
import com.alltoolbox.core.setting.Settings;
import com.alltoolbox.fbrowser.FileBrowserFragment;
import com.alltoolbox.transfer.TransferActivity;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.navigation.NavigationView;

import rikka.shizuku.Shizuku;

import java.util.Map;

/**
 * 主界面：侧边栏 + 内容区。
 * 第一期以文件浏览为主页面，侧边栏预留后续模块入口。
 */
public class MainActivity extends AppCompatActivity {

    private DrawerLayout drawerLayout;
    private NavigationView navigationView;
    private TextView rootBadge;

    /** 启动时申请 Shizuku 权限的请求码。 */
    private static final int REQUEST_CODE_SHIZUKU_STARTUP = 10086;

    /** 存储运行时权限申请 launcher（用户在系统授权页点允许/拒绝）。 */
    private ActivityResultLauncher<String[]> runtimePermLauncher;
    /** 「全部文件访问」系统设置页 launcher（返回后继续安全限制引导）。 */
    private ActivityResultLauncher<Intent> allFilesLauncher;

    /** Shizuku 权限申请结果监听。 */
    private final Shizuku.OnRequestPermissionResultListener shizukuResultListener =
            (requestCode, grantResult) -> {
                if (requestCode != REQUEST_CODE_SHIZUKU_STARTUP) return;
                runOnUiThread(() -> {
                    if (grantResult == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                        android.widget.Toast.makeText(this,
                                getString(R.string.perm_shizuku_granted), android.widget.Toast.LENGTH_SHORT).show();
                    }
                });
            };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        drawerLayout = findViewById(R.id.drawer_layout);
        navigationView = findViewById(R.id.nav_view);
        rootBadge = navigationView.getHeaderView(0) != null
                ? navigationView.getHeaderView(0).findViewById(R.id.nav_root_badge)
                : null;

        ActionBarDrawerToggle toggle = new ActionBarDrawerToggle(
                this, drawerLayout, toolbar,
                R.string.nav_home, R.string.nav_home);
        drawerLayout.addDrawerListener(toggle);
        toggle.syncState();

        if (rootBadge != null) {
            rootBadge.setVisibility(Permissions.isRooted() ? View.VISIBLE : View.GONE);
        }

        navigationView.setNavigationItemSelectedListener(item -> {
            drawerLayout.closeDrawer(GravityCompat.START);
            onNavSelected(item);
            return true;
        });

        if (savedInstanceState == null) {
            String home = Settings.getString(this, Settings.KEY_HOME_PATH, "");
            Fragment f = (home != null && !home.isEmpty())
                    ? FileBrowserFragment.newInstance(home)
                    : new FileBrowserFragment();
            getSupportFragmentManager().beginTransaction()
                    .replace(R.id.content_frame, f)
                    .commit();
        }

        // 首启引导链：用户协议 → 所需权限 → 授权页 → 安全限制解除（仅首次）
        runtimePermLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestMultiplePermissions(),
                this::onRuntimePermissionResult);
        allFilesLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                r -> maybeGuideRestrictedDirs());

        Shizuku.addRequestPermissionResultListener(shizukuResultListener);
        runFirstLaunchFlow();

        setupBottomBar();
    }

    /** 首启引导链入口：按状态分步弹出。 */
    private void runFirstLaunchFlow() {
        if (!Settings.getBoolean(this, Settings.KEY_AGREEMENT_ACCEPTED, false)) {
            // 第一步：用户协议与隐私政策（必须同意才能使用）
            showAgreementDialog();
            return;
        }
        if (!Settings.getBoolean(this, Settings.KEY_FIRST_PERMISSION_PROMPTED, false)) {
            // 第二步：弹出所需权限说明，用户点允许→跳授权页
            startPermissionFlow();
            return;
        }
        // 非首次：正常走公告 → 更新 → Shizuku 检测
        showAnnouncement();
        checkUpdateQuietly(() -> checkShizukuOnStartup());
    }

    /** 第一步：用户协议与隐私政策，同意后进入权限引导；不同意则退出。 */
    private void showAgreementDialog() {
        String content = getString(R.string.privacy_text);
        ScrollView scrollView = new ScrollView(this);
        TextView textView = new TextView(this);
        int padding = dp(16);
        textView.setPadding(padding, padding / 2, padding, padding);
        textView.setText(content);
        textView.setTextSize(14);
        textView.setTextIsSelectable(true);
        scrollView.addView(textView);
        int maxH = (int) (getResources().getDisplayMetrics().heightPixels * 0.6f);
        scrollView.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, maxH));
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.privacy_title)
                .setView(scrollView)
                .setCancelable(false)
                .setNegativeButton(getString(R.string.perm_disagree_exit), (d, w) -> {
                    finishAffinity();
                    android.os.Process.killProcess(android.os.Process.myPid());
                })
                .setPositiveButton(getString(R.string.perm_agree_continue), (d, w) -> {
                    Settings.putBoolean(this, Settings.KEY_AGREEMENT_ACCEPTED, true);
                    startPermissionFlow();
                })
                .show();
    }

    /** 第二步：按系统版本弹出所需存储权限说明，点「允许」→ 跳系统授权页。 */
    private void startPermissionFlow() {
        String[] perms = requiredStoragePermissions();
        if (perms.length == 0) {
            maybeGuideRestrictedDirs();
            return;
        }
        // 已全部授权则直接进入安全限制检查
        boolean allGranted = true;
        for (String p : perms) {
            if (ActivityCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) {
                allGranted = false;
                break;
            }
        }
        if (allGranted) {
            maybeGuideRestrictedDirs();
            return;
        }
        Settings.putBoolean(this, Settings.KEY_FIRST_PERMISSION_PROMPTED, true);
        String reason = Permissions.requiresAllFilesAccess()
                ? getString(R.string.perm_reason_allfiles)
                : getString(R.string.perm_reason_storage);
        new MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.perm_storage_title))
                .setMessage(reason)
                .setCancelable(false)
                .setNegativeButton(getString(R.string.perm_later), (d, w) -> maybeGuideRestrictedDirs())
                .setPositiveButton(getString(R.string.perm_allow), (d, w) ->
                        runtimePermLauncher.launch(perms))
                .show();
    }

    /** 系统授权页结果：无论是否授权，继续安全限制检查。 */
    private void onRuntimePermissionResult(Map<String, Boolean> result) {
        maybeGuideRestrictedDirs();
    }

    /** 第三步：安全限制检查——Android 11+ 未开「所有文件访问」则引导去解除。 */
    private void maybeGuideRestrictedDirs() {
        if (!Permissions.requiresAllFilesAccess()) {
            // Android 11 以下无系统级“全部文件访问”安全限制
            checkShizukuOnStartup();
            return;
        }
        if (Permissions.hasAllFilesAccess(this)) {
            checkShizukuOnStartup();
            return;
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.perm_restricted_title))
                .setMessage(getString(R.string.perm_restricted_msg))
                .setCancelable(false)
                .setNegativeButton(getString(R.string.perm_later), (d, w) -> checkShizukuOnStartup())
                .setPositiveButton(getString(R.string.perm_resolve), (d, w) ->
                        Permissions.requestAllFilesAccess(this, allFilesLauncher))
                .show();
    }

    /** 依据系统版本计算需要申请的存储运行时权限。 */
    private String[] requiredStoragePermissions() {
        if (!AppContext.isAtLeastM()) return new String[0];   // <6.0 无需运行时权限
        if (!AppContext.isAtLeastQ()) {                       // 6.0 ~ 9.0
            return new String[]{
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE};
        }
        return new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}; // 10+ 仅需读
    }

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    @Override
    protected void onDestroy() {
        Shizuku.removeRequestPermissionResultListener(shizukuResultListener);
        super.onDestroy();
    }

    /**
     * 打开软件时检测 Shizuku：未开启则弹出提醒，用户确认后前往授权。
     * 仅在“支持 Shizuku 但尚未就绪”时打扰一次。
     */
    private void checkShizukuOnStartup() {
        if (!ShizukuShell.isSupported()) return; // Android 6.0 以下不支持，跳过
        if (ShizukuShell.isReady()) return;      // 已开启且已授权，无需提醒

        new MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.perm_shizuku_not_running_title))
                .setMessage(getString(R.string.perm_shizuku_not_running_msg))
                .setNegativeButton(getString(R.string.perm_later), null)
                .setPositiveButton(getString(R.string.perm_shizuku_authorize), (d, w) -> gotoShizukuAuthorize())
                .show();
    }

    /** 前往授权 Shizuku：已在线但未授权则发起申请；否则引导安装/启动 Shizuku。 */
    private void gotoShizukuAuthorize() {
        if (ShizukuShell.isOnline()) {
            ShizukuShell.requestPermission(REQUEST_CODE_SHIZUKU_STARTUP);
            return;
        }
        // Shizuku 未运行：引导安装/启动 Shizuku 应用
        new MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.perm_shizuku_not_started))
                .setMessage(getString(R.string.perm_shizuku_start_msg))
                .setNegativeButton(getString(R.string.cancel), null)
                .setPositiveButton(getString(R.string.perm_shizuku_open), (d, w) -> {
                    try {
                        startActivity(new Intent("android.intent.action.MAIN")
                                .setPackage("moe.shizuku.privileged.api")
                                .addCategory("android.intent.category.LAUNCHER"));
                    } catch (Exception e) {
                        // 未安装 Shizuku，跳转官网
                        openBrowser("https://shizuku.rikka.app/");
                    }
                }).show();
    }

    /** 单栏底栏：上一页 / 下一页 / 添加 / 回到首页。 */
    private void setupBottomBar() {
        View btnPrev = findViewById(R.id.btn_prev);
        if (btnPrev == null) return; // 布局未含底栏（双栏等）
        TextView indicator = findViewById(R.id.nav_indicator);
        btnPrev.setOnClickListener(v -> {
            FileBrowserFragment f = findFileFragment();
            if (f != null) {
                boolean ok = f.goBackDir();
                animateIndicator(indicator, ok ? getString(R.string.main_btn_prev) : getString(R.string.main_first_page), ok);
            }
        });
        findViewById(R.id.btn_next).setOnClickListener(v -> {
            FileBrowserFragment f = findFileFragment();
            if (f != null) {
                boolean ok = f.goForwardDir();
                animateIndicator(indicator, ok ? getString(R.string.main_btn_next) : getString(R.string.main_last_page), ok);
            }
        });
        findViewById(R.id.btn_add).setOnClickListener(v -> {
            FileBrowserFragment f = findFileFragment();
            if (f != null) f.showAddDialog();
        });
        findViewById(R.id.btn_home).setOnClickListener(v -> {
            FileBrowserFragment f = findFileFragment();
            if (f != null) {
                f.goHome();
                animateIndicator(indicator, getString(R.string.main_btn_home), true);
            }
        });

        // 目录变化时自动切换底栏指示图标（点击/进入目录都会触发）
        FileBrowserFragment frag = findFileFragment();
        if (frag != null) {
            frag.setPathChangeListener(() -> {
                String p = frag.getCurrentPathString();
                String name = p.equals("/") ? "/" : new java.io.File(p).getName();
                if (indicator != null) {
                    indicator.setText("▸ " + name);
                    indicator.setAlpha(0.35f);
                    indicator.animate().alpha(1f).setDuration(260).start();
                }
            });
        }
    }

    /** 底栏切换图标的淡入切换动画。 */
    private void animateIndicator(TextView view, String text, boolean succeeded) {
        if (view == null) return;
        view.setText(text);
        view.setAlpha(0.3f);
        view.animate().alpha(1f).setDuration(320)
                .setStartDelay(40).start();
        if (succeeded) {
            // 内容过渡动画
            FileBrowserFragment f = findFileFragment();
            if (f != null) f.animateContent();
        }
    }

    /** 每次打开 APP 的公告弹窗，含可点击的下载链接。 */
    private void showAnnouncement() {
        String message = getString(R.string.main_announcement_msg,
                UpdateChecker.localVersion(this), UpdateChecker.DOWNLOAD_URL);
        MaterialAlertDialogBuilder builder =
                new MaterialAlertDialogBuilder(this)
                        .setTitle(getString(R.string.main_announcement_title))
                        .setMessage(message)
                        .setPositiveButton(getString(R.string.ok), null);
        androidx.appcompat.app.AlertDialog dialog = builder.create();
        dialog.setOnShowListener(d -> {
            try {
                // 让链接可点击并跳转默认浏览器
                TextView tv = dialog.findViewById(android.R.id.message);
                if (tv != null) {
                    tv.setAutoLinkMask(Linkify.WEB_URLS);
                    tv.setMovementMethod(LinkMovementMethod.getInstance());
                }
            } catch (Exception ignored) {
            }
        });
        dialog.show();
    }

    private void checkUpdateQuietly(Runnable onDone) {
        if (!Settings.getBoolean(this, Settings.KEY_UPDATE_CHECK, true)) {
            if (onDone != null) onDone.run();
            return;
        }
        UpdateChecker.checkAsync(this, (isLatest, tag, msg) -> {
            runOnUiThread(() -> {
                if (!isLatest) {
                    new MaterialAlertDialogBuilder(this)
                            .setTitle(getString(R.string.main_found_update_title, tag))
                            .setMessage(getString(R.string.main_found_update_msg,
                                    UpdateChecker.localVersion(this)))
                            .setPositiveButton(getString(R.string.update_direct), (d, w) ->
                                    startUpdateDownload(tag))
                            .setNeutralButton(getString(R.string.go_download), (d, w) ->
                                    openBrowser(UpdateChecker.DOWNLOAD_URL))
                            .setNegativeButton(getString(R.string.update_later), null)
                            .setOnDismissListener(d -> {
                                if (onDone != null) onDone.run();
                            })
                            .show();
                } else if (onDone != null) {
                    onDone.run();
                }
            });
        });
    }

    /** 弹出带进度的更新下载对话框，实时显示百分比、下载速度与预计剩余时长。 */
    private void startUpdateDownload(String tag) {
        Dialog dlg = new Dialog(this);
        dlg.setContentView(R.layout.dialog_update_progress);
        dlg.setCanceledOnTouchOutside(false);
        dlg.setCancelable(false);
        ProgressBar bar = dlg.findViewById(R.id.progress_bar);
        TextView percent = dlg.findViewById(R.id.progress_percent);
        TextView status = dlg.findViewById(R.id.progress_status);
        TextView eta = dlg.findViewById(R.id.progress_eta);
        dlg.show();

        Updater.downloadAndInstall(this, tag, new Updater.DownloadProgressListener() {
            @Override
            public void onStarted(long totalBytes) {
                status.setText(getString(R.string.main_dl_started));
            }

            @Override
            public void onProgress(long downloaded, long total,
                                   long speedBps, long remainingSeconds) {
                if (total > 0) {
                    int p = (int) (downloaded * 100 / total);
                    bar.setProgress(p);
                    percent.setText(p + "%");
                    status.setText(getString(R.string.main_dl_downloaded,
                            fmtSize(downloaded), fmtSize(total), fmtSpeed(speedBps)));
                    eta.setText(getString(R.string.main_dl_eta, fmtEta(remainingSeconds)));
                } else {
                    bar.setIndeterminate(true);
                    percent.setText(fmtSize(downloaded));
                    status.setText(getString(R.string.main_dl_speed, fmtSpeed(speedBps)));
                    eta.setText(getString(R.string.main_dl_eta_calculating));
                }
            }

            @Override
            public void onFinish(boolean success, String message) {
                if (dlg.isShowing()) dlg.dismiss();
            }
        });
    }

    /** 字节数 → 人类可读大小，如 12.3 MB。 */
    private static String fmtSize(long bytes) {
        if (bytes >= 1024L * 1024 * 1024) {
            return String.format(java.util.Locale.ROOT, "%.2f GB", bytes / (1024.0 * 1024 * 1024));
        }
        if (bytes >= 1024 * 1024) {
            return String.format(java.util.Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024));
        }
        if (bytes >= 1024) {
            return String.format(java.util.Locale.ROOT, "%.0f KB", bytes / 1024.0);
        }
        return bytes + " B";
    }

    /** 速度（字节/秒）→ 人类可读，如 1.2 MB/s。 */
    private static String fmtSpeed(long bps) {
        if (bps >= 1024L * 1024) {
            return String.format(java.util.Locale.ROOT, "%.1f MB/s", bps / (1024.0 * 1024));
        }
        if (bps >= 1024) {
            return String.format(java.util.Locale.ROOT, "%.0f KB/s", bps / 1024.0);
        }
        return bps + " B/s";
    }

    /** 剩余秒数 → mm:ss 或 hh:mm:ss。 */
    private static String fmtEta(long sec) {
        if (sec < 0) return "--:--";
        long s = sec % 60;
        long m = (sec / 60) % 60;
        long h = sec / 3600;
        if (h > 0) {
            return String.format(java.util.Locale.ROOT, "%d:%02d:%02d", h, m, s);
        }
        return String.format(java.util.Locale.ROOT, "%02d:%02d", m, s);
    }

    private void openBrowser(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)));
        } catch (Exception e) {
            android.widget.Toast.makeText(this, getString(R.string.open_link_failed), android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    private void onNavSelected(@NonNull MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.nav_home) {
            switchTo(new FileBrowserFragment());
        } else if (id == R.id.nav_dual) {
            startActivity(new Intent(this, com.alltoolbox.fbrowser.DualPaneActivity.class));
        } else if (id == R.id.nav_ftp) {
            startActivity(new Intent(this, com.alltoolbox.fbrowser.ftp.FtpActivity.class));
        } else if (id == R.id.nav_tools) {
            startActivity(new Intent(this, ToolboxActivity.class));
        } else if (id == R.id.nav_cleanup) {
            startActivity(new Intent(this, CleanupActivity.class));
        } else if (id == R.id.nav_extract_apk) {
            startActivity(new Intent(this, ExtractApkActivity.class));
        } else if (id == R.id.nav_internal_storage) {
            // 内部内存：直接打开系统数据目录（受限目录走 SAF 授权）
            switchTo(FileBrowserFragment.newInstance("/data"));
        } else if (id == R.id.nav_archive) {
            startActivity(new Intent(this, ArchiveActivity.class));
        } else if (id == R.id.nav_transfer) {
            startActivity(new Intent(this, TransferActivity.class));
        } else if (id == R.id.nav_settings) {
            startActivity(new Intent(this, SettingsActivity.class));
        }
    }

    @Override
    public boolean onCreateOptionsMenu(@NonNull Menu menu) {
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_switch_dual) {
            startActivity(new Intent(this, com.alltoolbox.fbrowser.DualPaneActivity.class));
            return true;
        }
        if (id == R.id.action_settings) {
            startActivity(new Intent(this, SettingsActivity.class));
            return true;
        }
        if (id == R.id.action_settings_home) {
            FileBrowserFragment homeFrag = findFileFragment();
            if (homeFrag != null) {
                homeFrag.setCurrentAsHome();
                return true;
            }
            startActivity(new Intent(this, SettingsActivity.class));
            return true;
        }
        if (id == R.id.action_exit) {
            finishAffinity();
            return true;
        }
        FileBrowserFragment f = findFileFragment();
        if (f != null) {
            if (id == R.id.action_add_bookmark) {
                f.addCurrentBookmark();
                return true;
            }
            if (id == R.id.action_sort) {
                f.showSortDialog();
                return true;
            }
            if (id == R.id.action_select_all) {
                f.toggleSelectAll();
                return true;
            }
            if (id == R.id.action_refresh) {
                f.refreshWithAnimation();
                return true;
            }
        }
        return super.onOptionsItemSelected(item);
    }

    private FileBrowserFragment findFileFragment() {
        Fragment f = getSupportFragmentManager().findFragmentById(R.id.content_frame);
        return f instanceof FileBrowserFragment ? (FileBrowserFragment) f : null;
    }

    private void switchTo(Fragment fragment) {
        FragmentManager fm = getSupportFragmentManager();
        fm.beginTransaction()
                .replace(R.id.content_frame, fragment)
                .commit();
    }

    @Override
    public void onBackPressed() {
        // 让文件浏览自身处理返回（向上一级）；侧边栏打开时先关闭
        if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
            drawerLayout.closeDrawer(GravityCompat.START);
            return;
        }
        Fragment f = getSupportFragmentManager().findFragmentById(R.id.content_frame);
        if (f instanceof FileBrowserFragment) {
            if (((FileBrowserFragment) f).onBackPressed()) {
                return;
            }
        }
        super.onBackPressed();
    }
}