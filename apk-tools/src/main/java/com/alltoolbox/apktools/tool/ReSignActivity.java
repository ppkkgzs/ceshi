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
import com.alltoolbox.apktools.sign.ReSignUtil;
import com.alltoolbox.core.task.TaskExecutor;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * APK 重签名：选择 APK，用内嵌调试密钥重签（v1/v2/v3），结果经系统保存框另存为新 APK。
 * 支持文件浏览器直传路径（EXTRA_PATH）。
 */
public class ReSignActivity extends AppCompatActivity {

    public static final String EXTRA_PATH = "path";

    private TextView pathText, infoText;
    private Button saveBtn;
    private ActivityResultLauncher<String[]> pickLauncher;
    private ActivityResultLauncher<String> saveLauncher;

    private File inputCache;
    private File outputCache;
    private String signReport = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_resign);
        setTitle("APK 重签名");

        pathText = findViewById(R.id.sign_path);
        infoText = findViewById(R.id.sign_info);
        saveBtn = findViewById(R.id.sign_save);
        Button pick = findViewById(R.id.sign_pick);

        pickLauncher = registerForActivityResult(
                new ActivityResultContracts.OpenDocument(),
                uri -> { if (uri != null) copyAndResign(uri); });
        saveLauncher = registerForActivityResult(
                new ActivityResultContracts.CreateDocument("application/vnd.android.package-archive"),
                uri -> { if (uri != null) writeTo(uri); });

        pick.setOnClickListener(v ->
                pickLauncher.launch(new String[]{"application/vnd.android.package-archive", "*/*"}));

        saveBtn.setOnClickListener(v -> {
            if (outputCache == null || !outputCache.exists()) {
                toast("暂无签名结果");
                return;
            }
            saveLauncher.launch("resigned-" + System.currentTimeMillis() + ".apk");
        });

        String direct = getIntent().getStringExtra(EXTRA_PATH);
        if (direct != null) {
            File f = new File(direct);
            if (f.exists() && f.isFile()) {
                inputCache = f;
                pathText.setText(f.getName());
                resignInBackground();
            }
        }
    }

    private void copyAndResign(Uri uri) {
        pathText.setText("读取文件…");
        try {
            inputCache = new File(getCacheDir(), "resign_in_" + System.currentTimeMillis() + ".apk");
            try (InputStream in = getContentResolver().openInputStream(uri);
                 FileOutputStream fos = new FileOutputStream(inputCache)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) fos.write(buf, 0, n);
            }
            pathText.setText(inputCache.getName());
            resignInBackground();
        } catch (Exception e) {
            infoText.setText("读取失败: " + e.getMessage());
        }
    }

    private void resignInBackground() {
        infoText.setText("正在重新签名…");
        saveBtn.setEnabled(false);
        outputCache = new File(getCacheDir(), "resign_out_" + System.currentTimeMillis() + ".apk");
        TaskExecutor.get().heavy().execute(() -> {
            final String[] report = {null};
            final String[] err = {null};
            try {
                report[0] = ReSignUtil.resign(this, inputCache, outputCache);
            } catch (Exception e) {
                err[0] = e.getMessage();
            }
            runOnUiThread(() -> {
                if (err[0] != null) {
                    infoText.setText("重签名失败: " + err[0]);
                } else {
                    signReport = report[0];
                    infoText.setText(report[0] + "\n\n大小: " + (outputCache.length() / 1024) + " KB");
                    saveBtn.setEnabled(true);
                    toast("重签名完成");
                }
            });
        });
    }

    private void writeTo(Uri uri) {
        try (OutputStream os = getContentResolver().openOutputStream(uri, "w");
             InputStream in = new java.io.FileInputStream(outputCache)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) os.write(buf, 0, n);
            toast("已保存签名结果");
        } catch (Exception e) {
            toast("保存失败: " + e.getMessage());
        }
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }
}