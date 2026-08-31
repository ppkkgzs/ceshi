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
import com.alltoolbox.core.setting.Settings;
import com.alltoolbox.fbrowser.FileBrowserFragment;
import com.alltoolbox.transfer.TransferActivity;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.navigation.NavigationView;

/**
 * 主界面：侧边栏 + 内容区。
 * 第一期以文件浏览为主页面，侧边栏预留后续模块入口。
 */
public class MainActivity extends AppCompatActivity {

    private DrawerLayout drawerLayout;
    private NavigationView navigationView;
    private TextView rootBadge;

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

        // 启动公告弹窗
        showAnnouncement();

        // 启动时检查更新
        if (Settings.getBoolean(this, Settings.KEY_UPDATE_CHECK, true)) {
            checkUpdateQuietly();
        }
    }

    /** 每次打开 APP 的公告弹窗，含可点击的下载链接。 */
    private void showAnnouncement() {
        String message = "该软件为本UP制作，请勿盗用他人劳动成果，" +
                "该软件为测试版本，后续将会优化以及更新。\n\n" +
                "下载最新版本链接：\n" + UpdateChecker.DOWNLOAD_URL;
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

    private void checkUpdateQuietly() {
        UpdateChecker.checkAsync(this, (isLatest, tag, msg) -> {
            if (!isLatest) {
                runOnUiThread(() -> new MaterialAlertDialogBuilder(this)
                        .setTitle("发现新版本")
                        .setMessage("最新版本：" + tag
                                + "\n当前版本：" + UpdateChecker.localVersion(this)
                                + "\n\n点击「去下载」前往 GitHub 获取最新版本。")
                        .setPositiveButton("去下载", (d, w) ->
                                openBrowser(UpdateChecker.DOWNLOAD_URL))
                        .setNegativeButton("以后再说", null)
                        .show());
            }
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
        if (id == R.id.action_settings_home) {
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
                f.refreshCurrent();
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