package com.alltoolbox.storage;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.text.NumberFormat;
import java.util.Locale;

/**
 * 存储空间分析界面：扫描后展示总占用、大文件 TOP、目录占用与重复文件。
 */
public class StorageActivity extends AppCompatActivity {

    private static final int REQ_PERM = 1001;

    private TextView tvTotal, tvFiles;
    private ProgressBar progress;
    private Button btnScan;
    private LinearLayout content;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_storage);
        setTitle(R.string.storage_title);

        tvTotal = findViewById(R.id.tv_total);
        tvFiles = findViewById(R.id.tv_files);
        progress = findViewById(R.id.progress);
        btnScan = findViewById(R.id.btn_scan);
        content = findViewById(R.id.content);

        btnScan.setOnClickListener(v -> requestOrScan());
    }

    private void requestOrScan() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED
                && ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    com.alltoolbox.core.AppContext.isAtLeastT()
                            ? new String[]{Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_AUDIO}
                            : new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, REQ_PERM);
            return;
        }
        start();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] perms, @NonNull int[] results) {
        super.onRequestPermissionsResult(requestCode, perms, results);
        if (requestCode == REQ_PERM) {
            boolean ok = false;
            for (int r : results) if (r == PackageManager.PERMISSION_GRANTED) { ok = true; break; }
            if (ok) start();
            else Toast.makeText(this, R.string.storage_permission_hint, Toast.LENGTH_LONG).show();
        }
    }

    private void start() {
        progress.setVisibility(View.VISIBLE);
        content.removeAllViews();
        btnScan.setEnabled(false);
        btnScan.setText(R.string.storage_scanning);
        tvTotal.setText("");

        com.alltoolbox.core.task.TaskExecutor.get().io().execute(() -> {
            final StorageScanner.Result r = StorageScanner.scan(scanned ->
                    runOnUiThread(() -> tvFiles.setText(scanned + " files…")));
            runOnUiThread(() -> apply(r));
        });
    }

    private void apply(StorageScanner.Result r) {
        progress.setVisibility(View.GONE);
        btnScan.setEnabled(true);
        btnScan.setText(R.string.storage_rescan);

        tvTotal.setText(formatBytes(r.totalBytes));
        tvFiles.setText(getString(R.string.storage_done) + " · " + r.fileCount + " files");

        // 大文件
        addHeader(R.string.storage_large_files);
        for (StorageScanner.FileEntry fe : r.largeFiles) {
            addRow(fe.file.getName(), formatBytes(fe.size));
        }
        // 目录占用
        addHeader(R.string.storage_dir_usage);
        for (StorageScanner.DirEntry de : r.dirs) {
            addRow(de.path, formatBytes(de.size));
        }
        // 重复文件
        addHeader(R.string.storage_duplicate);
        if (r.duplicates.isEmpty()) {
            addRow(getString(R.string.storage_no_dup), "");
        } else {
            for (StorageScanner.DuplicateGroup dg : r.duplicates) {
                addRow(dg.name + " × " + dg.count, formatBytes(dg.size));
            }
        }
    }

    private void addHeader(int resId) {
        TextView h = new TextView(this);
        h.setText(resId);
        h.setTextSize(16);
        h.setTypeface(null, android.graphics.Typeface.BOLD);
        h.setTextColor(0xFF202124);
        h.setPadding(0, dp(20), 0, dp(8));
        content.addView(h);
    }

    private void addRow(String name, String size) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(dp(4), dp(6), dp(4), dp(6));
        TextView n = new TextView(this);
        n.setText(name);
        n.setTextSize(14);
        n.setTextColor(0xFF333333);
        row.addView(n, new LinearLayout.LayoutParams(0, -2, 1));
        TextView s = new TextView(this);
        s.setText(size);
        s.setTextSize(14);
        s.setTextColor(0xFF80868b);
        row.addView(s);
        content.addView(row);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private String formatBytes(long b) {
        if (b < 1024) return b + " B";
        if (b < 1024L * 1024) return String.format(Locale.ROOT, "%.1f KB", b / 1024.0);
        if (b < 1024L * 1024 * 1024) return String.format(Locale.ROOT, "%.1f MB", b / 1048576.0);
        return String.format(Locale.ROOT, "%.2f GB", b / 1073741824.0);
    }
}