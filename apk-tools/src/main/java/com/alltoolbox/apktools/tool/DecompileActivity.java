package com.alltoolbox.apktools.tool;

import android.net.Uri;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import com.alltoolbox.apktools.R;
import com.alltoolbox.apktools.bridge.ApkToolBridge;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/**
 * APK 反编译/回编译（apktool 混合桥接）。
 * 无 java 运行时自动生成 PC 协作命令。加固 APK 无法完整反编译。
 */
public class DecompileActivity extends AppCompatActivity {

    /** 文件浏览器直传的文件路径（可空）。 */
    public static final String EXTRA_PATH = "path";

    private TextView pathText;
    private EditText outLog;
    private File apk;
    private File workDir;

    private ActivityResultLauncher<String> fileLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_decompile);
        setTitle("反编译/回编译");

        pathText = findViewById(R.id.dc_path);
        outLog = findViewById(R.id.dc_out);
        Button pick = findViewById(R.id.dc_pick);
        Button decompile = findViewById(R.id.dc_decompile);
        Button build = findViewById(R.id.dc_build);

        fileLauncher = registerForActivityResult(
                new ActivityResultContracts.GetContent(),
                uri -> {
                    if (uri != null) loadApk(uri);
                });

        pick.setOnClickListener(v -> fileLauncher.launch("application/vnd.android.package-archive"));
        decompile.setOnClickListener(v -> doDecompile());
        build.setOnClickListener(v -> doBuild());

        // 来自文件浏览器：直接载入本地 APK
        String direct = getIntent().getStringExtra(EXTRA_PATH);
        if (direct != null) {
            File f = new File(direct);
            if (f.exists()) loadLocal(f);
        }
    }

    private void loadApk(Uri uri) {
        try {
            File out = new File(getCacheDir(), "apk_" + System.currentTimeMillis() + ".apk");
            try (InputStream in = getContentResolver().openInputStream(uri);
                 FileOutputStream fos = new FileOutputStream(out)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) fos.write(buf, 0, n);
            }
            loadLocal(out);
        } catch (Exception e) {
            outLog.setText("载入失败: " + e.getMessage());
        }
    }

    private void loadLocal(File f) {
        apk = f;
        pathText.setText("待处理: " + f.getName());
        workDir = new File(getCacheDir(), "work_" + System.currentTimeMillis());
        outLog.setText("APK 已载入: " + apk.getAbsolutePath() + "\n工作目录: " + workDir);
    }

    private void doDecompile() {
        if (apk == null) {
            Toast.makeText(this, "请先选择 APK", Toast.LENGTH_SHORT).show();
            return;
        }
        outLog.setText("反编译中…\n");
        ApkToolBridge.get().decompile(this, apk, workDir, (ok, out) ->
                runOnUiThread(() -> {
                    outLog.append("\n" + out);
                    outLog.append(ok ? "\n[完成] 已输出到 " + workDir : "\n[失败或需要 PC 协作]");
                    if (ok) {
                        Toast.makeText(this, "反编译完成", Toast.LENGTH_SHORT).show();
                    }
                }));
    }

    private void doBuild() {
        if (workDir == null || !workDir.exists()) {
            Toast.makeText(this, "请先反编译", Toast.LENGTH_SHORT).show();
            return;
        }
        outLog.setText("回编译中…\n");
        File outApk = new File(getCacheDir(), "unsigned_" + System.currentTimeMillis() + ".apk");
        ApkToolBridge.get().build(this, workDir, outApk, (ok, out) ->
                runOnUiThread(() -> {
                    outLog.append("\n" + out);
                    outLog.append(ok ? "\n[完成] 未签名 APK: " + outApk.getAbsolutePath()
                            : "\n[失败或需要 PC 协作]");
                }));
    }
}