package com.alltoolbox.app;

import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
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
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.tabs.TabLayout;

import java.io.File;
import java.io.FileInputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 提取安装包：列出本机已安装的应用（区分用户/系统应用），选择后弹出
 * 详情对话框，可查看包名、版本、签名、加固、UID 等信息，并可把其 APK
 * 提取（复制）到公共「下载」目录。
 *
 * 需要存储权限（Android 11+ 会申请「所有文件访问」）。部分系统应用 APK
 * 路径不可读时会给出提示。
 */
public class ExtractApkActivity extends AppCompatActivity {

    private ActivityResultLauncher<String> storagePermLauncher;
    private ActivityResultLauncher<Intent> allFilesLauncher;
    private RecyclerView list;
    private TextView emptyView;
    private TabLayout tabLayout;

    private final List<AppItem> userApps = new ArrayList<>();
    private final List<AppItem> systemApps = new ArrayList<>();
    private final List<AppItem> filteredUser = new ArrayList<>();
    private final List<AppItem> filteredSystem = new ArrayList<>();
    private final AppAdapter adapter = new AppAdapter();
    private String query = "";
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
        tabLayout = findViewById(R.id.tab_layout);
        list.setLayoutManager(new LinearLayoutManager(this));
        list.setAdapter(adapter);

        tabLayout.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab tab) {
                adapter.notifyDataSetChanged();
                emptyView.setVisibility(getVisibleApps().isEmpty() ? View.VISIBLE : View.GONE);
            }

            @Override
            public void onTabUnselected(TabLayout.Tab tab) {
            }

            @Override
            public void onTabReselected(TabLayout.Tab tab) {
            }
        });

        // 搜索：按应用名 / 包名过滤当前 Tab
        android.widget.EditText search = findViewById(R.id.search_input);
        search.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
                query = s != null ? s.toString().trim().toLowerCase() : "";
                applyFilter();
            }

            @Override
            public void afterTextChanged(android.text.Editable s) {
            }
        });

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
            final List<AppItem>[] buckets = loadInstalledApps(this);
            runOnUiThread(() -> {
                userApps.clear();
                systemApps.clear();
                userApps.addAll(buckets[0]);
                systemApps.addAll(buckets[1]);
                applyFilter();
                emptyView.setVisibility(getVisibleApps().isEmpty() ? View.VISIBLE : View.GONE);
            });
        });
    }

    /** 返回 [用户应用, 系统应用] 两组列表。 */
    private static List<AppItem>[] loadInstalledApps(Context ctx) {
        List<AppItem> user = new ArrayList<>();
        List<AppItem> sys = new ArrayList<>();
        final PackageManager pm = ctx.getPackageManager();
        try {
            List<ApplicationInfo> apps = pm.getInstalledApplications(0);
            for (ApplicationInfo ai : apps) {
                String label = String.valueOf(ai.loadLabel(pm));
                if (label == null || label.isEmpty()) label = ai.packageName;
                String version = "";
                try {
                    PackageInfo pi = pm.getPackageInfo(ai.packageName, 0);
                    version = pi.versionName != null ? pi.versionName : "";
                } catch (Exception ignored) {
                }
                long size = new File(ai.sourceDir).length();
                if (ai.splitSourceDirs != null) {
                    for (String s : ai.splitSourceDirs) size += new File(s).length();
                }
                AppItem item = new AppItem(label, ai, version, size,
                        (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0);
                if (item.system) sys.add(item);
                else user.add(item);
            }
            user.sort((a, b) -> a.label.compareToIgnoreCase(b.label));
            sys.sort((a, b) -> a.label.compareToIgnoreCase(b.label));
        } catch (Exception ignored) {
        }
        return new List[]{user, sys};
    }

    private List<AppItem> getVisibleApps() {
        return tabLayout.getSelectedTabPosition() == 1 ? filteredSystem : filteredUser;
    }

    /** 按关键词过滤用户 / 系统应用并刷新列表。 */
    private void applyFilter() {
        filteredUser.clear();
        filteredSystem.clear();
        if (query.isEmpty()) {
            filteredUser.addAll(userApps);
            filteredSystem.addAll(systemApps);
        } else {
            for (AppItem it : userApps) {
                if (matches(it)) filteredUser.add(it);
            }
            for (AppItem it : systemApps) {
                if (matches(it)) filteredSystem.add(it);
            }
        }
        adapter.notifyDataSetChanged();
        emptyView.setVisibility(getVisibleApps().isEmpty() ? View.VISIBLE : View.GONE);
    }

    private boolean matches(AppItem it) {
        return it.label.toLowerCase().contains(query)
                || it.packageName().toLowerCase().contains(query);
    }

    /** 弹出详情对话框（图二样式），底部「更多 / 提取安装包」。 */
    private void showDetail(AppItem app) {
        View v = LayoutInflater.from(this).inflate(R.layout.dialog_apk_detail, null);
        ImageView icon = v.findViewById(R.id.detail_icon);
        TextView name = v.findViewById(R.id.detail_name);
        TextView version = v.findViewById(R.id.detail_version);
        LinearLayout rows = v.findViewById(R.id.detail_rows);

        final PackageManager pm = getPackageManager();
        Drawable ic = app.ai.loadIcon(pm);
        if (ic != null) icon.setImageDrawable(ic);
        name.setText(app.label);
        version.setText(app.versionName != null && !app.versionName.isEmpty()
                ? app.versionName : "版本未知");

        // 详情字段（包名、版本号、大小、签名状态、加固状态、数据目录、APK路径、UID）
        List<String[]> infos = new ArrayList<>();
        PackageInfo pi = null;
        try {
            pi = pm.getPackageInfo(app.packageName(),
                    PackageManager.GET_SIGNATURES);
        } catch (Exception ignored) {
        }
        infos.add(new String[]{"包名", app.packageName()});
        infos.add(new String[]{"版本号", pi != null ? String.valueOf(pi.versionCode) : "未知"});
        infos.add(new String[]{"安装包大小", formatSize(app.size)});
        infos.add(new String[]{"签名状态", signatureStatus(app, pi)});
        infos.add(new String[]{"加固状态", detectProtection(new File(app.apkPath()))});
        infos.add(new String[]{"数据目录", "/data/user/0/" + app.packageName()});
        infos.add(new String[]{"APK路径", app.apkPath()});
        infos.add(new String[]{"UID", String.valueOf(app.ai.uid)});

        for (String[] kv : infos) {
            View row = LayoutInflater.from(this)
                    .inflate(R.layout.item_apk_detail_row, rows, false);
            ((TextView) row.findViewById(R.id.row_key)).setText(kv[0]);
            ((TextView) row.findViewById(R.id.row_value)).setText(kv[1]);
            rows.addView(row);
        }

        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this)
                .setView(v)
                .setNegativeButton("更多", (d, w) -> showMoreMenu(app))
                .setPositiveButton("提取安装包", (d, w) -> extract(app));
        builder.show();
    }

    /** 更多：启动 / 详情 / 卸载。 */
    private void showMoreMenu(AppItem app) {
        final String[] actions = {"启动", "详情", "卸载"};
        new MaterialAlertDialogBuilder(this)
                .setTitle(app.label)
                .setItems(actions, (d, w) -> {
                    switch (w) {
                        case 0:
                            launch(app);
                            break;
                        case 1:
                            openAppDetails(app);
                            break;
                        case 2:
                            uninstall(app);
                            break;
                    }
                })
                .show();
    }

    private void launch(AppItem app) {
        try {
            Intent i = getPackageManager().getLaunchIntentForPackage(app.packageName());
            if (i != null) startActivity(i);
            else toast("该应用没有可启动的入口");
        } catch (Exception e) {
            toast("启动失败：" + e.getMessage());
        }
    }

    private void openAppDetails(AppItem app) {
        try {
            startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + app.packageName())));
        } catch (Exception e) {
            toast("无法打开应用详情");
        }
    }

    private void uninstall(AppItem app) {
        try {
            startActivity(new Intent(Intent.ACTION_DELETE,
                    Uri.parse("package:" + app.packageName())));
        } catch (Exception e) {
            toast("无法发起卸载");
        }
    }

    /** 签名方案：V1（JAR 签名）+ V2/V3（APK 签名块）。 */
    private String signatureStatus(AppItem app, PackageInfo pi) {
        boolean v1 = pi != null && pi.signatures != null && pi.signatures.length > 0;
        boolean v2 = hasApkSignatureBlock(new File(app.apkPath()));
        if (v1 && v2) return "V1 + V2";
        if (v1) return "V1";
        if (v2) return "V2";
        return "未签名";
    }

    /**
     * 检测 APK 是否含 v2/v3 签名块：签名块位于中央目录之前，
     * 末尾 24 字节 = 8 字节块大小 + "APK Sig Block 42" 魔数。
     */
    private static boolean hasApkSignatureBlock(File apk) {
        if (apk == null || !apk.isFile() || apk.length() < 22 + 24) return false;
        try (RandomAccessFile raf = new RandomAccessFile(apk, "r")) {
            long len = raf.length();
            long eocdPos = len - 22;
            raf.seek(eocdPos + 20);
            int commentLen = raf.readUnsignedShort();
            eocdPos -= commentLen;
            if (eocdPos < 0) return false;
            raf.seek(eocdPos + 16);
            long centralDirOffset = Integer.toUnsignedLong(raf.readInt());
            if (centralDirOffset < 24) return false;
            raf.seek(centralDirOffset - 24);
            byte[] buf = new byte[24];
            raf.readFully(buf);
            String magic = new String(buf, 16, 8, StandardCharsets.UTF_8);
            return "APK Sig Block 42".equals(magic);
        } catch (Exception e) {
            return false;
        }
    }

    /** 通过特征文件判断是否加壳（加固）。 */
    private static String detectProtection(File apk) {
        if (apk == null || !apk.isFile()) return "未加固";
        final String[][] markers = {
                {"jiagu", "360加固"}, {"qihoo", "360加固"}, {"libjiagu", "360加固"},
                {"secneo", "爱加密"}, {"ijiami", "爱加密"}, {"libexec", "爱加密"},
                {"secshell", "梆梆加固"}, {"SecShell", "梆梆加固"},
                {"shellx", "梆梆加固"},
                {"stubshell", "腾讯乐固"}, {"DexHelper", "腾讯乐固"},
                {"shella", "腾讯乐固"},
                {"baiduprotect", "百度加固"}, {"libprotect", "百度加固"},
                {"chaosvmp", "娜迦加固"}, {"wnaggezhly", "娜迦加固"},
                {"nqshield", "娜迦加固"},
                {"apkprotect", "APKProtect"},
                {"libfake", "腾讯乐固"}
        };
        try (ZipFile zf = new ZipFile(apk)) {
            Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                String n = en.nextElement().getName().toLowerCase();
                for (String[] m : markers) {
                    if (n.contains(m[0].toLowerCase())) return m[1];
                }
            }
        } catch (Exception ignored) {
        }
        return "未加固";
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
        final File src = new File(app.apkPath());
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
        final ApplicationInfo ai;
        final String versionName;
        final long size;
        final boolean system;

        AppItem(String label, ApplicationInfo ai, String versionName, long size, boolean system) {
            this.label = label;
            this.ai = ai;
            this.versionName = versionName;
            this.size = size;
            this.system = system;
        }

        String packageName() {
            return ai.packageName;
        }

        String apkPath() {
            return ai.sourceDir;
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
            AppItem it = getVisibleApps().get(position);
            Drawable ic = it.ai.loadIcon(getPackageManager());
            if (ic != null) h.icon.setImageDrawable(ic);
            h.name.setText(it.label);
            h.pkg.setText(it.packageName());
            h.version.setText(it.versionName != null ? it.versionName : "");
            h.info.setText(formatSize(it.size));
            h.itemView.setOnClickListener(v -> showDetail(it));
        }

        @Override
        public int getItemCount() {
            return getVisibleApps().size();
        }

        final class VH extends RecyclerView.ViewHolder {
            final ImageView icon;
            final TextView name, pkg, version, info;

            VH(View v) {
                super(v);
                icon = v.findViewById(R.id.app_icon);
                name = v.findViewById(R.id.app_name);
                pkg = v.findViewById(R.id.app_pkg);
                version = v.findViewById(R.id.app_version);
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
