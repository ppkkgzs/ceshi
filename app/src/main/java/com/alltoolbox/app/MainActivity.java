package com.alltoolbox.app;

import android.content.Intent;
import android.os.Bundle;
import android.text.method.LinkMovementMethod;
import android.text.util.Linkify;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.ActionBarDrawerToggle;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;

import com.alltoolbox.archive.ArchiveActivity;
import com.alltoolbox.cleanup.CleanupActivity;
import com.alltoolbox.core.permission.Permissions;
import com.alltoolbox.core.permission.ShizukuShell;
import com.alltoolbox.core.setting.Settings;
import com.alltoolbox.fbrowser.FileBrowserFragment;
import com.alltoolbox.transfer.TransferActivity;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.navigation.NavigationView;

import rikka.shizuku.Shizuku;

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

    /** Shizuku 权限申请结果监听。 */
    private final Shizuku.OnRequestPermissionResultListener shizukuResultListener =
            (requestCode, grantResult) -> {
                if (requestCode != REQUEST_CODE_SHIZUKU_STARTUP) return;
                runOnUiThread(() -> {
                    if (grantResult == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                        android.widget.Toast.makeText(this,
                                "Shizuku 已授权", android.widget.Toast.LENGTH_SHORT).show();
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

        // 启动公告 → 更新公告 → Shizuku 检测（按顺序弹出）
        Shizuku.addRequestPermissionResultListener(shizukuResultListener);
        showAnnouncement();
        checkUpdateQuietly(() -> checkShizukuOnStartup());

        setupBottomBar();
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
                .setTitle("检测到未开启 Shizuku")
                .setMessage("部分功能（如访问 Android/data 等受限目录、解压受限目录内文件）"
                        + "需要 Shizuku 权限。\n\n是否前往授权 Shizuku？")
                .setNegativeButton("暂不", null)
                .setPositiveButton("去授权", (d, w) -> gotoShizukuAuthorize())
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
                .setTitle("Shizuku 未启动")
                .setMessage("请先在本机安装并启动 Shizuku（moe.shizuku.privileged.api），"
                        + "并通过 adb/无线调试 或 Root 激活后，再回到本应用授权。\n\n"
                        + "点击「打开 Shizuku」进行设置。")
                .setNegativeButton("取消", null)
                .setPositiveButton("打开 Shizuku", (d, w) -> {
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
                animateIndicator(indicator, ok ? "◀ 上一页" : "已是第一页", ok);
            }
        });
        findViewById(R.id.btn_next).setOnClickListener(v -> {
            FileBrowserFragment f = findFileFragment();
            if (f != null) {
                boolean ok = f.goForwardDir();
                animateIndicator(indicator, ok ? "▶ 下一页" : "已到最后一页", ok);
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
                animateIndicator(indicator, "↑ 首页", true);
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
        String message = "欢迎使用 PK管理器 " + UpdateChecker.localVersion(this) + "！\n\n" +
                "本软件为本UP制作，请勿盗用或二次分发他人劳动成果，\n" +
                "本软件为测试版本，后续将持续优化与更新。\n\n" +
                "本次更新公告：\n" +
                "· 用户协议与隐私政策补充联系邮箱\n" +
                "· 启动弹窗顺序优化，启动自动检测 Shizuku 并引导授权\n" +
                "· 应用文件夹显示对应应用图标\n" +
                "· 压缩包支持在软件内直接解压，不跳转外部应用\n" +
                "· 点击文件弹打开方式，长按文件弹操作菜单\n\n" +
                "【开源声明】本软件以 GNU GPL v3 协议开源，可自由研究、修改与分发，分发时须遵守 GPL v3 条款。\n" +
                "开源仓库：github.com/ppkkgzs/ceshi\n\n" +
                "下载最新版本链接：\n" + UpdateChecker.DOWNLOAD_URL + "\n\n" +
                "联系邮箱：gexinggzs@163.com";
        MaterialAlertDialogBuilder builder =
                new MaterialAlertDialogBuilder(this)
                        .setTitle("公告")
                        .setMessage(message)
                        .setPositiveButton("知道了", null);
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
                            .setTitle("发现新版本 " + tag)
                            .setMessage("当前版本：" + UpdateChecker.localVersion(this)
                                    + "\n优化建议、Bug 反馈请发邮件：gexinggzs@163.com\n\n"
                                    + "点击「直接更新」将在应用内下载并自动进入安装。")
                            .setPositiveButton("直接更新", (d, w) ->
                                    Updater.downloadAndInstall(this, tag))
                            .setNeutralButton("去下载", (d, w) ->
                                    openBrowser(UpdateChecker.DOWNLOAD_URL))
                            .setNegativeButton("以后再说", null)
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

    private void openBrowser(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)));
        } catch (Exception e) {
            android.widget.Toast.makeText(this, "无法打开链接", android.widget.Toast.LENGTH_SHORT).show();
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