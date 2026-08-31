package com.alltoolbox.app;

import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.alltoolbox.core.permission.Permissions;
import com.alltoolbox.core.task.TaskExecutor;

import java.io.File;
import java.io.FileInputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 提取安装包：列出本机已安装的应用，选择后可把其 APK 提取（复制）到
 * 公共「下载」目录，便于备份 / 安装到其它设备。
 *
 * 需要存储权限（Android 11+ 会申请「所有文件访问」）。部分系统应用 APK
 * 路径不可读时会给出提示。
 */
public class ExtractApkActivity extends AppCompatActivity {

    private ActivityResultLauncher<String> storagePermLauncher;
    private ActivityResultLauncher<Intent> allFilesLauncher;
    private RecyclerView list;
    private TextView emptyView;
    private List<AppItem> apps = new ArrayList<>();
    private boolean extracting = false;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_extract_apk);
        setTitle("提取安装包");

        androidx.appcompat.widget.Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        list = findViewById(R.id.app_list);
        emptyView = findViewById(R.id.empty_view);
        list.setLayoutManager(new LinearLayoutManager(this));
        list.setAdapter(new AppAdapter());

        storagePermLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(),
                granted -> { if (granted) toast("已获得存储权限"); });
        allFilesLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> { /* 返回后由用户重试 */ });

        loadApps();
    }

    private void loadApps() {
        Toast.makeText(this, "正在读取应用列表…", Toast.LENGTH_SHORT).show();
        TaskExecutor.get().io().execute(() -> {
            final List<AppItem> out = loadInstalledApps(this);
            runOnUiThread(() -> {
                apps = out;
                list.getAdapter().notifyDataSetChanged();
                emptyView.setVisibility(apps.isEmpty() ? View.VISIBLE : View.GONE);
            });
        });
    }

    private static List<AppItem> loadInstalledApps(Context ctx) {
        List<AppItem> out = new ArrayList<>();
        final PackageManager pm = ctx.getPackageManager();
        final Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        try {
            List<android.content.pm.ResolveInfo> infos = pm.queryIntentActivities(launcher, 0);
            List<ApplicationInfo> apps = new ArrayList<>();
            for (android.content.pm.ResolveInfo ri : infos) {
                ApplicationInfo ai = ri.activityInfo != null ? ri.activityInfo.applicationInfo : null;
                if (ai != null && !apps.contains(ai)) apps.add(ai);
            }
            for (ApplicationInfo ai : apps) {
                String label = String.valueOf(ai.loadLabel(pm));
                if (label == null || label.isEmpty()) label = ai.packageName;
                out.add(new AppItem(label, ai.packageName, ai.sourceDir));
            }
        } catch (Exception ignored) {
        }
        java.util.Collections.sort(out, (a, b) -> a.label.compareToIgnoreCase(b.label));
        return out;
    }

    @Override
    public void onBackPressed() {
        super.onBackPressed();
    }

    private void extract(AppItem app) {
        if (extracting) return;
        // 权限门控：Android 11+ 需要「所有文件访问」才能读其它应用 APK 并写入公共目录
        if (Permissions.requiresAllFilesAccess()) {
            if (!Permissions.hasAllFilesAccess(this)) {
                Permissions.requestAllFilesAccess(this, allFilesLauncher);
                toast("正在请求「所有文件访问」权限，授权后请重新点击提取");
                return;
            }
        } else if (Build.VERSION.SDK_INT >= 23
                && !Permissions.hasReadMedia(this)) {
            storagePermLauncher.launch(android.Manifest.permission.READ_EXTERNAL_STORAGE);
            return;
        }

        extracting = true;
        Toast.makeText(this, "正在提取 " + app.label + " …", Toast.LENGTH_SHORT).show();
        TaskExecutor.get().io().execute(() -> {
            final String[] result = {null};
            try {
                result[0] = writeApk(app);
            } catch (Exception e) {
                result[0] = "提取失败: " + e.getMessage();
            }
            runOnUiThread(() -> {
                extracting = false;
                Toast.makeText(this, result[0], Toast.LENGTH_LONG).show();
            });
        });
    }

    /** 把应用的 APK 复制到「下载」目录。优先 MediaStore（Android 10+）。 */
    private String writeApk(AppItem app) throws Exception {
        final File src = new File(app.apkPath);
        final String fileName = sanitize(app.label) + ".apk";
        final long size = src.length();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.Downloads.DISPLAY_NAME, fileName);
            cv.put(MediaStore.Downloads.MIME_TYPE, "application/vnd.android.package-archive");
            cv.put(MediaStore.Downloads.RELATIVE_PATH, "PK2");
            Uri uri = getContentResolver().insert(MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), cv);
            if (uri == null) throw new Exception("无法写入主目录");
            try (FileInputStream in = new FileInputStream(src);
                 OutputStream out = getContentResolver().openOutputStream(uri)) {
                if (out == null) throw new Exception("无法打开输出流");
                copy(in, out);
            }
            return "已提取到「主页面/PK2」：" + fileName;
        }

        // 低版本：直接写主目录 PK2 文件夹
        File dir = new File(Environment.getExternalStorageDirectory(), "PK2");
        if (!dir.exists()) dir.mkdirs();
        File dst = new File(dir, fileName);
        try (FileInputStream in = new FileInputStream(src);
             java.io.FileOutputStream out = new java.io.FileOutputStream(dst)) {
            copy(in, out);
        }
        return "已提取到：" + dst.getAbsolutePath();
    }

    private static void copy(FileInputStream in, OutputStream out) throws Exception {
        byte[] buf = new byte[256 * 1024];
        int n;
        while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        out.flush();
    }

    private static String sanitize(String s) {
        return s.replaceAll("[\\\\/:*?\"<>|\\s]", "_").isEmpty() ? "app" : s;
    }

    @Override
    public boolean onOptionsItemSelected(android.view.MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    private static final class AppItem {
        final String label;
        final String packageName;
        final String apkPath;

        AppItem(String label, String packageName, String apkPath) {
            this.label = label;
            this.packageName = packageName;
            this.apkPath = apkPath;
        }
    }

    private final class AppAdapter extends RecyclerView.Adapter<AppAdapter.VH> {
        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_extract_apk, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int position) {
            AppItem it = apps.get(position);
            h.name.setText(it.label);
            h.pkg.setText(it.packageName);
            File f = new File(it.apkPath);
            h.info.setText("APK 大小：" + formatSize(f.length()));
            h.itemView.setOnClickListener(v -> extract(it));
        }

        @Override
        public int getItemCount() {
            return apps.size();
        }

        final class VH extends RecyclerView.ViewHolder {
            final TextView name, pkg, info;

            VH(View v) {
                super(v);
                name = v.findViewById(R.id.app_name);
                pkg = v.findViewById(R.id.app_pkg);
                info = v.findViewById(R.id.app_info);
            }
        }
    }

    private static String formatSize(long b) {
        if (b < 1024) return b + " B";
        if (b < 1024 * 1024) return String.format(java.util.Locale.US, "%.1f KB", b / 1024.0);
        if (b < 1024L * 1024 * 1024)
            return String.format(java.util.Locale.US, "%.2f MB", b / (1024.0 * 1024));
        return String.format(java.util.Locale.US, "%.2f GB", b / (1024.0 * 1024 * 1024));
    }
}