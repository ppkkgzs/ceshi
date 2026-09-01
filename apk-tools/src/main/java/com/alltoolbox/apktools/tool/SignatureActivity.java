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
import com.alltoolbox.apktools.sign.ApkSignatureUtil;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/**
 * APK 签名信息查看：选择 APK，读取 v1 签名证书信息。
 * 非 Root 下仅查看；正式签名需电脑（见反编译签名工具说明）。
 */
public class SignatureActivity extends AppCompatActivity {

    /** 文件浏览器直传的文件路径（可空）。 */
    public static final String EXTRA_PATH = "path";

    private TextView pathText, infoText;
    private ActivityResultLauncher<String> fileLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_signature);
        setTitle("签名信息");

        pathText = findViewById(R.id.sig_path);
        infoText = findViewById(R.id.sig_info);
        Button pick = findViewById(R.id.sig_pick);

        fileLauncher = registerForActivityResult(
                new ActivityResultContracts.GetContent(),
                uri -> {
                    if (uri != null) {
                        try {
                            analyze(copyToCache(uri));
                        } catch (Exception e) {
                            infoText.setText("读取 APK 失败: " + e.getMessage());
                        }
                    }
                });

        pick.setOnClickListener(v -> fileLauncher.launch("application/vnd.android.package-archive"));

        // 来自文件浏览器：直接解析（本地文件，无需复制）
        String direct = getIntent().getStringExtra(EXTRA_PATH);
        if (direct != null) analyze(new java.io.File(direct));
    }

    private void analyze(File tmp) {
        pathText.setText(tmp.getAbsolutePath());
        infoText.setText("正在解析签名…");
        ApkSignatureUtil.readAsync(tmp, (cert, error) -> runOnUiThread(() -> {
            if (cert != null) {
                infoText.setText(cert.summary());
            } else {
                infoText.setText("未找到签名信息。\n" + (error != null ? error : ""));
                Toast.makeText(this, "可能为加固或未签名 APK", Toast.LENGTH_LONG).show();
            }
        }));
    }

    private File copyToCache(Uri uri) throws Exception {
        File out = new File(getCacheDir(), "sig_" + System.currentTimeMillis() + ".apk");
        try (InputStream in = getContentResolver().openInputStream(uri);
             FileOutputStream fos = new FileOutputStream(out)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) fos.write(buf, 0, n);
        }
        return out;
    }
}