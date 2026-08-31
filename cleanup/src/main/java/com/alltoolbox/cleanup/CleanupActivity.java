package com.alltoolbox.cleanup;

import android.app.AppOpsManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Process;
import android.os.StatFs;
import android.provider.Settings;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.alltoolbox.core.AppContext;
import com.alltoolbox.core.task.TaskExecutor;

import java.io.File;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 扫描清理：手机内存显示、缓存垃圾扫描与清理、安装包检测与清理、
 * 以及超过 7 天未打开的不常用应用检测与卸载。
 */
public class CleanupActivity extends AppCompatActivity {

    private static final long SEVEN_DAYS = 7L * 24 * 60 * 60 * 1000;

    private TextView storageUsed, storageFree, storageTotal;
    private ProgressBar storageProgress;
    private TextView cacheSize, apkSize, rareCount, rareEmpty;
    private Button cleanCache, cleanApk, openUsageAccess;
    private RecyclerView rareList;
    private RareAdapter rareAdapter;
    private List<RareApp> rareApps = new ArrayList<>();

    private long cacheTotal = 0;
    private List<File> apkFiles = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_cleanup);
        setTitle(R.string.cleanup_title);

        storageUsed = findViewById(R.id.storage_used);
        storageFree = findViewById(R.id.storage_free);
        storageTotal = findViewById(R.id.storage_total);
        storageProgress = findViewById(R.id.storage_progress);
        cacheSize = findViewById(R.id.cache_size);
        apkSize = findViewById(R.id.apk_size);
        rareCount = findViewById(R.id.rare_count);
        rareEmpty = findViewById(R.id.rare_empty);
        cleanCache = findViewById(R.id.clean_cache);
        cleanApk = findViewById(R.id.clean_apk);
        openUsageAccess = findViewById(R.id.open_usage_access);
        rareList = findViewById(R.id.rare_list);

        rareAdapter = new RareAdapter();
        rareList.setLayoutManager(new LinearLayoutManager(this));
        rareList.setAdapter(rareAdapter);

        cleanCache.setOnClickListener(v -> confirmCleanCache());
        cleanApk.setOnClickListener(v -> confirmCleanApk());
        openUsageAccess.setOnClickListener(v -> openUsageAccessSettings());
        findViewById(R.id.rare_rescan).setOnClickListener(v -> scanRareApps());

        showStorage();
        scanCacheFiles();
        scanApkFiles();
        scanRareApps();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_cleanup, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == R.id.cleanup_scan) {
            onMenuRefresh();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void onMenuRefresh() {
        showStorage();
        scanCacheFiles();
        scanApkFiles();
        scanRareApps();
    }

    // ---------------- 手机内存显示 ----------------

    private void showStorage() {
        try {
            StatFs stat = new StatFs(Environment.getDataDirectory().getAbsolutePath());
            long total = stat.getTotalBytes();
            long free = stat.getAvailableBytes();
            long used = total - free;
            storageUsed.setText(getString(R.string.storage_used, formatSize(used)));
            storageFree.setText(getString(R.string.storage_free, formatSize(free)));
            storageTotal.setText(getString(R.string.storage_total, formatSize(total)));
            storageProgress.setProgress(total == 0 ? 0 : (int) (used * 100 / total));
        } catch (Exception e) {
            storageTotal.setText(R.string.storage_dummy);
        }
    }

    // ---------------- 缓存垃圾 ----------------

    private void scanCacheFiles() {
        TaskExecutor.get().scan().execute(() -> {
            final List<File> caches = new ArrayList<>();
            caches.add(getCacheDir());
            File androidData = new File(Environment.getExternalStorageDirectory(), "Android/data");
            File[] apps = androidData.listFiles();
            if (apps != null) {
                for (File a : apps) {
                    File c = new File(a, "cache");
                    if (c.exists() && c.isDirectory()) caches.add(c);
                }
            }
            final long size = totalSize(caches);
            runOnUiThread(() -> {
                cacheTotal = size;
                cacheSize.setText(getString(R.string.cache_scanned, formatSize(size)));
                cleanCache.setEnabled(size > 0);
            });
        });
    }

    private void confirmCleanCache() {
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(R.string.clean_cache_title)
                .setMessage(R.string.cache_clean_confirm)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, (d, w) -> doCleanCache())
                .show();
    }

    private void doCleanCache() {
        TaskExecutor.get().io().execute(() -> {
            final long before = cacheTotal;
            clearCacheDir(getCacheDir());
            File androidData = new File(Environment.getExternalStorageDirectory(), "Android/data");
            File[] apps = androidData.listFiles();
            long reclaimed = 0;
            if (apps != null) {
                for (File a : apps) {
                    File c = new File(a, "cache");
                    if (c.exists()) reclaimed += sizeOf(c);
                    deleteRecursive(c);
                }
            }
            reclaimed += sizeOf(getCacheDir());
            deleteRecursive(getCacheDir());
            final long fReclaimed = reclaimed;
            runOnUiThread(() -> {
                cacheTotal = 0;
                cacheSize.setText(getString(R.string.cache_scanned, formatSize(0)));
                cleanCache.setEnabled(false);
                Toast.makeText(this,
                        getString(R.string.cache_cleaned, formatSize(Math.max(before, fReclaimed))),
                        Toast.LENGTH_SHORT).show();
            });
        });
    }

    private void clearCacheDir(File dir) {
        File[] c = dir.listFiles();
        if (c != null) for (File x : c) deleteRecursive(x);
    }

    // ---------------- 安装包 ----------------

    private void scanApkFiles() {
        TaskExecutor.get().scan().execute(() -> {
            List<File> out = new ArrayList<>();
            collectApk(Environment.getExternalStorageDirectory(), out);
            out.sort((a, b) -> Long.compare(b.length(), a.length()));
            final long size = totalSize(out);
            final int count = out.size();
            runOnUiThread(() -> {
                apkFiles = out;
                apkSize.setText(getString(R.string.apk_scanned, count, formatSize(size)));
                cleanApk.setEnabled(count > 0);
            });
        });
    }

    private void collectApk(File dir, List<File> acc) {
        if (dir == null || !dir.exists() || !dir.isDirectory()) return;
        File[] c = dir.listFiles();
        if (c == null) return;
        for (File f : c) {
            if (f.isDirectory()) {
                if (acc.size() >= 4000) break;
                collectApk(f, acc);
            } else if (f.getName().toLowerCase(Locale.ROOT).endsWith(".apk")) {
                acc.add(f);
            }
        }
    }

    private void confirmCleanApk() {
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(R.string.clean_apk_title)
                .setMessage(R.string.apk_clean_confirm)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, (d, w) -> doCleanApk())
                .show();
    }

    private void doCleanApk() {
        final List<File> targets = new ArrayList<>(apkFiles);
        TaskExecutor.get().io().execute(() -> {
            long reclaimed = 0;
            int ok = 0;
            for (File f : targets) {
                long s = f.length();
                if (deleteRecursive(f)) {
                    ok++;
                    reclaimed += s;
                }
            }
            final int fOk = ok;
            final long fReclaimed = reclaimed;
            runOnUiThread(() -> {
                apkFiles = new ArrayList<>();
                apkSize.setText(getString(R.string.apk_scanned, 0, formatSize(0)));
                cleanApk.setEnabled(false);
                Toast.makeText(this,
                        getString(R.string.apk_cleaned, fOk, formatSize(fReclaimed)),
                        Toast.LENGTH_SHORT).show();
                scanApkFiles();
            });
        });
    }

    // ---------------- 不常用应用 ----------------

    private boolean hasUsageAccess() {
        if (!AppContext.isAtLeastM()) return false;
        try {
            AppOpsManager aom = (AppOpsManager) getSystemService(Context.APP_OPS_SERVICE);
            int mode = aom.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(), getPackageName());
            return mode == AppOpsManager.MODE_ALLOWED;
        } catch (Exception e) {
            return false;
        }
    }

    private void openUsageAccessSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS));
        } catch (Exception e) {
            Toast.makeText(this, R.string.open_usage_access, Toast.LENGTH_SHORT).show();
        }
    }

    private void scanRareApps() {
        if (!hasUsageAccess()) {
            openUsageAccess.setVisibility(View.VISIBLE);
            rareCount.setText(R.string.rare_empty);
            rareEmpty.setVisibility(View.GONE);
            return;
        }
        openUsageAccess.setVisibility(View.GONE);
        TaskExecutor.get().scan().execute(() -> {
            final List<RareApp> result = computeRareApps();
            runOnUiThread(() -> {
                rareApps = result;
                rareAdapter.notifyDataSetChanged();
                rareCount.setText(result.isEmpty()
                        ? getString(R.string.rare_no_unused)
                        : getString(R.string.rare_count_hint, result.size()));
                rareEmpty.setVisibility(result.isEmpty() ? View.VISIBLE : View.GONE);
            });
        });
    }

    @Nullable
    private List<RareApp> computeRareApps() {
        try {
            long now = System.currentTimeMillis();
            UsageStatsManager usm = (UsageStatsManager) getSystemService(Context.USAGE_STATS_SERVICE);
            Map<String, UsageStats> usage =
                    usm.queryAndAggregateUsageStats(now - 365L * 24 * 3600 * 1000, now);

            PackageManager pm = getPackageManager();

            // 找出有桌面入口的应用
            Intent launcher = new Intent(Intent.ACTION_MAIN);
            launcher.addCategory(Intent.CATEGORY_LAUNCHER);
            List<ResolveInfo> resolvers = pm.queryIntentActivities(launcher, 0);
            Set<String> launcherSet = new HashSet<>();
            for (ResolveInfo r : resolvers) launcherSet.add(r.activityInfo.packageName);

            List<RareApp> out = new ArrayList<>();
            for (ApplicationInfo app : pm.getInstalledApplications(0)) {
                if (app.packageName.equals(getPackageName())) continue;
                if (!launcherSet.contains(app.packageName)) continue;
                long lastUsed = 0;
                UsageStats s = usage.get(app.packageName);
                if (s != null) lastUsed = s.getLastTimeUsed();
                // 超过 7 天未打开（含从未打开）
                if (now - lastUsed <= SEVEN_DAYS) continue;
                String label = app.loadLabel(pm).toString();
                Drawable icon = app.loadIcon(pm);
                out.add(new RareApp(app.packageName, label, icon, -1, lastUsed));
            }
            out.sort(Comparator.comparingLong(RareApp::lastUsed));
            return out;
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    private void confirmUninstall(RareApp app) {
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(R.string.clean_rare_delete)
                .setMessage(getString(R.string.rare_delete_confirm, app.label))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    try {
                        startActivity(new Intent(Intent.ACTION_DELETE,
                                Uri.parse("package:" + app.packageName)));
                    } catch (Exception e) {
                        Toast.makeText(this, R.string.cleanup_fail, Toast.LENGTH_SHORT).show();
                    }
                })
                .show();
    }

    // ---------------- 工具 ----------------

    private static boolean deleteRecursive(File f) {
        if (f == null || !f.exists()) return false;
        if (f.isDirectory()) {
            File[] c = f.listFiles();
            if (c != null) for (File x : c) deleteRecursive(x);
        }
        return f.delete() || !f.exists();
    }

    private static long sizeOf(File f) {
        if (f == null || !f.exists()) return 0;
        if (f.isFile()) return f.length();
        long sum = 0;
        File[] c = f.listFiles();
        if (c != null) for (File x : c) sum += sizeOf(x);
        return sum;
    }

    private static long totalSize(List<File> files) {
        long sum = 0;
        for (File f : files) sum += f.length();
        return sum;
    }

    static String formatSize(long b) {
        if (b >= 1024L * 1024 * 1024) return String.format(Locale.ROOT, "%.1f GB", b / 1073741824.0);
        if (b >= 1024L * 1024) return String.format(Locale.ROOT, "%.1f MB", b / 1048576.0);
        if (b >= 1024L) return String.format(Locale.ROOT, "%.1f KB", b / 1024.0);
        return b + " B";
    }

    /** 模型：一个可清理的不常用应用。 */
    private static final class RareApp {
        final String packageName;
        final String label;
        final Drawable icon;
        final long bytes;
        final long lastUsed;

        RareApp(String packageName, String label, Drawable icon, long bytes, long lastUsed) {
            this.packageName = packageName;
            this.label = label;
            this.icon = icon;
            this.bytes = bytes;
            this.lastUsed = lastUsed;
        }

        long lastUsed() {
            return lastUsed;
        }
    }

    private final class RareAdapter extends RecyclerView.Adapter<RareAdapter.VH> {

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_rare_app, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int position) {
            final RareApp app = rareApps.get(position);
            h.icon.setImageDrawable(app.icon);
            h.name.setText(app.label);
            String size = app.bytes >= 0 ? " · " + formatSize(app.bytes) : "";
            String when = app.lastUsed > 0
                    ? DateFormat.getDateTimeInstance().format(new Date(app.lastUsed))
                    : getString(R.string.rare_never_used);
            h.info.setText(getString(R.string.rare_last_used, when) + size);
            h.uninstall.setOnClickListener(v -> confirmUninstall(app));
        }

        @Override
        public int getItemCount() {
            return rareApps.size();
        }

        final class VH extends RecyclerView.ViewHolder {
            final ImageView icon;
            final TextView name, info;
            final Button uninstall;

            VH(View v) {
                super(v);
                icon = v.findViewById(R.id.rare_icon);
                name = v.findViewById(R.id.rare_name);
                info = v.findViewById(R.id.rare_info);
                uninstall = v.findViewById(R.id.rare_uninstall);
            }
        }
    }
}