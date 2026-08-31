package com.alltoolbox.apktools.apk;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.Uri;
import android.os.Bundle;
import android.util.Base64;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.alltoolbox.apktools.R;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * APK 包详情：选择本地 APK，用 PackageManager 解析并展示其包信息、
 * 版本、SDK、安装路径、大小、签名指纹与权限列表。
 * 自包含，不依赖 app 模块 Hub。
 */
public class ApkInfoActivity extends AppCompatActivity {

    /** 文件浏览器直传的本地 APK 路径（可空）。 */
    public static final String EXTRA_PATH = "path";

    private ActivityResultLauncher<String[]> fileLauncher;
    private TextView pathText;
    private RecyclerView list;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_apk_info);
        setTitle(R.string.apk_info_title);

        pathText = findViewById(R.id.apk_path);
        list = findViewById(R.id.apk_list);
        list.setLayoutManager(new LinearLayoutManager(this));

        Button pick = findViewById(R.id.apk_pick);

        fileLauncher = registerForActivityResult(
                new ActivityResultContracts.OpenDocument(),
                uri -> {
                    if (uri != null) {
                        try {
                            analyze(copyToCache(uri));
                        } catch (Exception e) {
                            showError(e);
                        }
                    }
                });

        pick.setOnClickListener(v ->
                fileLauncher.launch(new String[]{
                        "application/vnd.android.package-archive", "*/*"}));

        // 来自文件浏览器：本地直传，无需选择器
        String direct = getIntent().getStringExtra(EXTRA_PATH);
        if (direct != null) {
            File f = new File(direct);
            if (f.exists() && f.isFile()) analyze(f);
        }
    }

    private void analyze(File tmp) {
        String path = tmp.getAbsolutePath();
        pathText.setText(getString(R.string.apk_info_parsing, path));

        try {
            PackageManager pm = getPackageManager();
            PackageInfo pi = pm.getPackageArchiveInfo(path, PackageManager.GET_PERMISSIONS
                    | PackageManager.GET_SIGNATURES | PackageManager.GET_ACTIVITIES);
            if (pi == null) {
                pathText.setText(R.string.apk_info_hint);
                Toast.makeText(this, R.string.apk_info_read_fail, Toast.LENGTH_LONG).show();
                return;
            }

            List<Row> rows = new ArrayList<>();
            ApplicationInfo ai = pi.applicationInfo;

            rows.add(new Row(getString(R.string.apk_info_app_name), appLabel(pi, ai, pm)));
            rows.add(new Row(getString(R.string.apk_info_package), pi.packageName));
            rows.add(new Row(getString(R.string.apk_info_version_name), pi.versionName));
            rows.add(new Row(getString(R.string.apk_info_version_code),
                    String.valueOf(pi.versionCode)));
            rows.add(new Row(getString(R.string.apk_info_target_sdk),
                    String.valueOf(ai.targetSdkVersion)));
            rows.add(new Row(getString(R.string.apk_info_min_sdk),
                    String.valueOf(ai.minSdkVersion)));
            rows.add(new Row(getString(R.string.apk_info_path), path));
            rows.add(new Row(getString(R.string.apk_info_size), formatSize(tmp.length())));

            rows.addAll(signatureRows(pi));

            String[] perms = pi.requestedPermissions;
            if (perms != null && perms.length > 0) {
                rows.add(new Row(getString(R.string.apk_info_permissions) + " (" + perms.length + ")",
                        String.join("\n", perms)));
            } else {
                rows.add(new Row(getString(R.string.apk_info_permissions), getString(R.string.apk_info_none)));
            }

            list.setAdapter(new RowAdapter(rows));
        } catch (Exception e) {
            showError(e);
        }
    }

    /** 签名：取 Signature 字节，先 BASE64 编码，再分别计算 SHA-1 与 MD5 摘要。 */
    private List<Row> signatureRows(PackageInfo pi) throws Exception {
        List<Row> rows = new ArrayList<>();
        Signature[] sigs = pi.signatures;
        if (sigs == null || sigs.length == 0) {
            rows.add(new Row(getString(R.string.apk_info_sign_sha1), getString(R.string.apk_info_none)));
            rows.add(new Row(getString(R.string.apk_info_sign_md5), getString(R.string.apk_info_none)));
            return rows;
        }
        // 取第一个签名
        String base64 = Base64.encodeToString(sigs[0].toByteArray(), Base64.NO_WRAP);
        byte[] data = base64.getBytes(StandardCharsets.UTF_8);
        rows.add(new Row(getString(R.string.apk_info_sign_sha1), hexDigest("SHA-1", data)));
        rows.add(new Row(getString(R.string.apk_info_sign_md5), hexDigest("MD5", data)));
        return rows;
    }

    private static String hexDigest(String algo, byte[] data) throws Exception {
        MessageDigest md = MessageDigest.getInstance(algo);
        byte[] digest = md.digest(data);
        StringBuilder sb = new StringBuilder();
        for (byte b : digest) {
            sb.append(String.format("%02X", b));
        }
        return sb.toString();
    }

    private String appLabel(PackageInfo pi, ApplicationInfo ai, PackageManager pm) {
        CharSequence label = null;
        try {
            label = ai.loadLabel(pm);
        } catch (Exception ignore) {
            // 继续走 fallback
        }
        if (label != null && label.length() > 0) return label.toString();
        if (ai.nonLocalizedLabel != null) return ai.nonLocalizedLabel.toString();
        return pi.packageName;
    }

    private File copyToCache(Uri uri) throws Exception {
        File out = new File(getCacheDir(), "apk_" + System.currentTimeMillis() + ".apk");
        try (InputStream in = getContentResolver().openInputStream(uri);
             FileOutputStream fos = new FileOutputStream(out)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) fos.write(buf, 0, n);
        }
        return out;
    }

    private void showError(Exception e) {
        pathText.setText(getString(R.string.apk_info_read_fail, e.getMessage()));
        Toast.makeText(this, R.string.apk_info_read_fail, Toast.LENGTH_SHORT).show();
    }

    private static String formatSize(long bytes) {
        if (bytes < 0) return "0";
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.US, "%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) {
            return String.format(Locale.US, "%.2f MB", bytes / (1024.0 * 1024));
        }
        return String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }

    /** 单条 key/value。 */
    private static final class Row {
        final String key;
        final String value;

        Row(String key, String value) {
            this.key = key;
            this.value = value;
        }
    }

    private static final class RowAdapter extends RecyclerView.Adapter<RowAdapter.VH> {
        private final List<Row> data;

        RowAdapter(List<Row> data) {
            this.data = data;
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_apk_info, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int position) {
            Row r = data.get(position);
            h.key.setText(r.key);
            h.value.setText(r.value);
        }

        @Override
        public int getItemCount() {
            return data.size();
        }

        final class VH extends RecyclerView.ViewHolder {
            final TextView key, value;

            VH(View v) {
                super(v);
                key = v.findViewById(R.id.row_key);
                value = v.findViewById(R.id.row_value);
            }
        }
    }
}