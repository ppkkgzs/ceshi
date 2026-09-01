package com.alltoolbox.apktools.tool;

import android.net.Uri;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import com.alltoolbox.apktools.R;
import com.alltoolbox.core.task.TaskExecutor;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * DEX 反编译为 smali（结构级骨架）。
 * 支持直接选择 .dex，或选择 APK 后自动提取其中的 classes*.dex 逐一反编译。
 */
public class DexSmaliActivity extends AppCompatActivity {

    public static final String EXTRA_PATH = "path";

    private TextView pathText, outText;
    private ActivityResultLauncher<String[]> fileLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_dex_smali);
        setTitle("DEX 转 Smali");

        pathText = findViewById(R.id.dex_path);
        outText = findViewById(R.id.dex_out);
        Button pick = findViewById(R.id.dex_pick);

        fileLauncher = registerForActivityResult(
                new ActivityResultContracts.OpenDocument(),
                uris -> {
                    if (uris != null) {
                        try {
                            load(copyToCache(uris));
                        } catch (Exception e) {
                            outText.setText("读取文件失败: " + e.getMessage());
                        }
                    }
                });

        pick.setOnClickListener(v ->
                fileLauncher.launch(new String[]{"application/octet-stream", "*/*"}));

        String direct = getIntent().getStringExtra(EXTRA_PATH);
        if (direct != null) {
            File f = new File(direct);
            if (f.exists()) load(f);
        }
    }

    private void load(File f) {
        pathText.setText(f.getName() + "  (解析中…)");
        TaskExecutor.get().io().execute(() -> {
            final StringBuilder result = new StringBuilder();
            final String[] error = {null};
            try {
                if (f.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".apk")) {
                    result.append("# APK 包含的 DEX（多 dex 依次反编译）\n\n");
                    extractAndDecompileApk(f, result);
                } else {
                    result.append(DexSmali.decompile(f));
                }
            } catch (Exception e) {
                error[0] = e.getMessage();
            }
            runOnUiThread(() -> {
                pathText.setText(f.getName());
                if (error[0] != null) {
                    outText.setText("反编译失败: " + error[0] + "\n(可能为加固或非标准 DEX)");
                } else {
                    outText.setText(result.toString());
                }
                Toast.makeText(this, "反编译完成", Toast.LENGTH_SHORT).show();
            });
        });
    }

    private void extractAndDecompileApk(File apk, StringBuilder out) throws Exception {
        try (ZipFile zf = new ZipFile(apk)) {
            Enumeration<? extends ZipEntry> en = zf.entries();
            int idx = 0;
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                String name = e.getName();
                if (name.startsWith("assets/") || !name.contains("classes") || !name.endsWith(".dex")) continue;
                InputStream in = zf.getInputStream(e);
                java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
                in.close();
                out.append("# ===== ").append(name).append(" =====\n\n");
                out.append(DexSmali.decompile(bos.toByteArray())).append('\n');
                idx++;
            }
            if (idx == 0) out.append("# 未在 APK 中找到 classes*.dex\n");
        }
    }

    private File copyToCache(Uri uri) throws Exception {
        String name = "input_" + System.currentTimeMillis();
        File out = new File(getCacheDir(), name);
        try (InputStream in = getContentResolver().openInputStream(uri);
             FileOutputStream fos = new FileOutputStream(out)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) fos.write(buf, 0, n);
        }
        return out;
    }
}