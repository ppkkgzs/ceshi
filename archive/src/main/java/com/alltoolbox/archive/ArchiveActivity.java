package com.alltoolbox.archive;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.documentfile.provider.DocumentFile;

import java.util.ArrayList;
import java.util.List;

/**
 * 压缩 / 解压工具 UI（基于 SAF，兼容 Android 10+ 分区存储）。
 *
 * 压缩：多选文件 / 选一整个目录 → 系统保存框选输出 ZIP 位置与文件名。
 * 解压：选 zip/7z/tar 压缩包 → 系统目录框选解压目标目录。
 *
 * 全程使用 DocumentFile + ContentResolver，不再直接写公共目录，杜绝“压缩失效”。
 */
public class ArchiveActivity extends AppCompatActivity {

    private ActivityResultLauncher<String[]> multiPicker;   // 多选文件（压缩源）
    private ActivityResultLauncher<String[]> extractPicker; // 选压缩包
    private ActivityResultLauncher<String> createDoc;       // 创建输出 zip
    private ActivityResultLauncher<Uri> treeForDir;         // 选目录（压缩整个目录的源）
    private ActivityResultLauncher<Uri> treeForExtract;     // 选解压目标目录

    private List<DocumentFile> compressSources = new ArrayList<>();
    private DocumentFile dirSource;
    private DocumentFile extractSource;
    private boolean awaitingOutputName; // true=待选输出 zip（来自多选或目录压缩）

    private ProgressBar progress;
    private TextView status;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_archive);
        setTitle(R.string.archive_output_root);

        progress = findViewById(R.id.archive_progress);
        status = findViewById(R.id.archive_status);

        multiPicker = registerForActivityResult(
                new ActivityResultContracts.OpenMultipleDocuments(), this::onMultiPicked);
        extractPicker = registerForActivityResult(
                new ActivityResultContracts.OpenDocument(), this::onExtractPicked);
        createDoc = registerForActivityResult(
                new ActivityResultContracts.CreateDocument("application/zip"), this::onOutputPicked);
        treeForDir = registerForActivityResult(
                new ActivityResultContracts.OpenDocumentTree(), this::onDirSourcePicked);
        treeForExtract = registerForActivityResult(
                new ActivityResultContracts.OpenDocumentTree(), this::onExtractTargetPicked);

        Button compress = findViewById(R.id.btn_compress);
        Button compressDir = findViewById(R.id.btn_compress_dir);
        Button extract = findViewById(R.id.btn_extract);

        compress.setOnClickListener(v -> multiPicker.launch(new String[]{"*/*"}));
        compressDir.setOnClickListener(v -> treeForDir.launch(null));
        extract.setOnClickListener(v -> extractPicker.launch(new String[]{
                "application/zip", "application/x-7z-compressed",
                "application/x-tar", "*/*"}));
    }

    // ---- 压缩：多选文件 ----
    private void onMultiPicked(List<Uri> list) {
        if (list == null || list.isEmpty()) return;
        compressSources = new ArrayList<>();
        for (Uri u : list) {
            DocumentFile d = DocumentFile.fromSingleUri(this, u);
            if (d != null && d.exists()) compressSources.add(d);
        }
        if (compressSources.isEmpty()) {
            toast(R.string.archive_error, getString(R.string.archive_empty_src));
            return;
        }
        awaitingOutputName = true;
        createDoc.launch("压缩包.zip");
    }

    // ---- 压缩：整个目录 ----
    private void onDirSourcePicked(Uri treeUri) {
        if (treeUri == null) return;
        takePersistable(treeUri);
        dirSource = DocumentFile.fromTreeUri(this, treeUri);
        if (dirSource == null) return;
        awaitingOutputName = true;
        createDoc.launch(dirSource.getName() + ".zip");
    }

    // ---- 压缩：输出 zip 位置确定，开始压缩 ----
    private void onOutputPicked(Uri outUri) {
        if (outUri == null || !awaitingOutputName) return;
        awaitingOutputName = false;
        if (dirSource != null) {
            compressSources = new ArrayList<>();
            compressSources.add(dirSource);
            dirSource = null;
        }
        compressNow(outUri);
    }

    private void compressNow(Uri outUri) {
        if (compressSources.isEmpty()) {
            toast(R.string.archive_error, getString(R.string.archive_empty_src));
            return;
        }
        status.setText(R.string.archive_working);
        progress.setVisibility(android.view.View.VISIBLE);
        DocumentFile[] sources = compressSources.toArray(new DocumentFile[0]);
        ArchiveManager.get().compressZipAsync(getContentResolver(), sources, outUri,
                (done, total, name) -> runOnUiThread(() -> {
                    if (total > 0) progress.setMax((int) Math.max(total, 1));
                    progress.setProgress((int) done);
                    status.setText(name);
                }),
                () -> runOnUiThread(() -> {
                    progress.setVisibility(android.view.View.INVISIBLE);
                    toast(R.string.archive_done_zip, outUri.getLastPathSegment());
                }),
                e -> runOnUiThread(() -> {
                    progress.setVisibility(android.view.View.INVISIBLE);
                    toast(R.string.archive_error, String.valueOf(e.getMessage()));
                }));
    }

    // ---- 解压：选压缩包 ----
    private void onExtractPicked(Uri uri) {
        if (uri == null) return;
        DocumentFile d = DocumentFile.fromSingleUri(this, uri);
        if (d == null || !d.exists()) {
            toast(R.string.archive_error, getString(R.string.archive_empty_src));
            return;
        }
        extractSource = d;
        status.setText(R.string.archive_pick_dir);
        treeForExtract.launch(null);
    }

    // ---- 解压：选目标目录，开始解压 ----
    private void onExtractTargetPicked(Uri treeUri) {
        if (treeUri == null || extractSource == null) return;
        takePersistable(treeUri);
        DocumentFile dest = DocumentFile.fromTreeUri(this, treeUri);
        if (dest == null) return;
        status.setText(R.string.archive_working);
        progress.setVisibility(android.view.View.VISIBLE);
        ArchiveManager.get().decompressAsync(getContentResolver(), extractSource, dest,
                (done, total, name) -> runOnUiThread(() -> {
                    if (total > 0) progress.setMax((int) Math.max(total, 1));
                    progress.setProgress((int) done);
                    status.setText(name);
                }),
                () -> runOnUiThread(() -> {
                    progress.setVisibility(android.view.View.INVISIBLE);
                    toast(R.string.archive_done_extract, dest.getName());
                }),
                e -> runOnUiThread(() -> {
                    progress.setVisibility(android.view.View.INVISIBLE);
                    toast(R.string.archive_error, String.valueOf(e.getMessage()));
                }));
    }

    private void takePersistable(Uri treeUri) {
        try {
            getContentResolver().takePersistableUriPermission(treeUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        } catch (Exception ignored) {
        }
    }

    private void toast(int res, Object arg) {
        String s = getString(res, String.valueOf(arg));
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
        status.setText(s);
    }
}