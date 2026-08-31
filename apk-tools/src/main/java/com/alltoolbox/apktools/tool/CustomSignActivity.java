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
import com.alltoolbox.apktools.sign.CustomSignUtil;
import com.alltoolbox.core.task.TaskExecutor;
import com.google.android.material.textfield.TextInputEditText;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * 自定义 APK 签名：选择 APK + 选择自己的密钥库（.keystore/.jks/.p12）+ 输入密码/别名，
 * 用用户自己的私钥证书对 APK 进行 v1/v2/v3 签名，结果经系统保存框另存为新 APK。
 * 支持文件浏览器直传路径（EXTRA_PATH）。
 */
public class CustomSignActivity extends AppCompatActivity {

    public static final String EXTRA_PATH = "path";

    private TextView apkText, keystoreText, infoText;
    private TextInputEditText storePassInput, aliasInput, keyPassInput;
    private Button saveBtn;

    private ActivityResultLauncher<String[]> apkLauncher;
    private ActivityResultLauncher<String[]> ksLauncher;
    private ActivityResultLauncher<String> saveLauncher;

    private File apkCache;
    private File ksCache;
    private File outputCache;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_custom_sign);
        setTitle("自定义 APK 签名");

        apkText = findViewById(R.id.cs_apk_path);
        keystoreText = findViewById(R.id.cs_keystore_path);
        infoText = findViewById(R.id.cs_info);
        storePassInput = findViewById(R.id.cs_store_pass);
        aliasInput = findViewById(R.id.cs_alias);
        keyPassInput = findViewById(R.id.cs_key_pass);
        saveBtn = findViewById(R.id.cs_save);

        Button pickApk = findViewById(R.id.cs_pick_apk);
        Button pickKs = findViewById(R.id.cs_pick_keystore);
        Button sign = findViewById(R.id.cs_sign);

        apkLauncher = registerForActivityResult(
                new ActivityResultContracts.OpenDocument(),
                uri -> { if (uri != null) copyToCache(uri, false); });
        ksLauncher = registerForActivityResult(
                new ActivityResultContracts.OpenDocument(),
                uri -> { if (uri != null) copyToCache(uri, true); });
        saveLauncher = registerForActivityResult(
                new ActivityResultContracts.CreateDocument("application/vnd.android.package-archive"),
                uri -> { if (uri != null) writeTo(uri); });

        pickApk.setOnClickListener(v ->
                apkLauncher.launch(new String[]{"application/vnd.android.package-archive", "*/*"}));
        pickKs.setOnClickListener(v ->
                ksLauncher.launch(new String[]{"*/*"}));
        sign.setOnClickListener(v -> signInBackground());
        saveBtn.setOnClickListener(v -> {
            if (outputCache == null || !outputCache.exists()) {
                toast("暂无签名结果");
                return;
            }
            saveLauncher.launch("signed-" + System.currentTimeMillis() + ".apk");
        });

        String direct = getIntent().getStringExtra(EXTRA_PATH);
        if (direct != null) {
            File f = new File(direct);
            if (f.exists() && f.isFile()) {
                apkCache = f;
                apkText.setText(f.getName());
            }
        }
    }

    private void copyToCache(Uri uri, boolean isKeystore) {
        final File target = isKeystore
                ? new File(getCacheDir(), "custom_ks_" + System.currentTimeMillis())
                : new File(getCacheDir(), "custom_apk_" + System.currentTimeMillis() + ".apk");
        try (InputStream in = getContentResolver().openInputStream(uri);
             FileOutputStream out = new FileOutputStream(target)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            if (isKeystore) {
                ksCache = target;
                keystoreText.setText(queryDisplayName(uri));
            } else {
                apkCache = target;
                apkText.setText(queryDisplayName(uri));
            }
        } catch (Exception e) {
            infoText.setText("读取失败: " + e.getMessage());
        }
    }

    private String queryDisplayName(Uri uri) {
        try (android.database.Cursor c = getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) return c.getString(idx);
            }
        } catch (Exception ignore) {
        }
        return uri.getLastPathSegment();
    }

    private void signInBackground() {
        if (apkCache == null) { toast("请先选择 APK"); return; }
        if (ksCache == null) { toast("请先选择密钥库文件"); return; }
        final String storePass = val(storePassInput);
        final String alias = val(aliasInput);
        final String keyPass = val(keyPassInput);
        if (storePass.isEmpty() || alias.isEmpty() || keyPass.isEmpty()) {
            toast("请填写密钥库密码、别名、密钥密码");
            return;
        }

        infoText.setText("正在用自定义密钥签名…");
        saveBtn.setEnabled(false);
        outputCache = new File(getCacheDir(), "custom_out_" + System.currentTimeMillis() + ".apk");
        TaskExecutor.get().heavy().execute(() -> {
            final String[] report = {null};
            final String[] err = {null};
            try {
                report[0] = CustomSignUtil.sign(apkCache, outputCache,
                        ksCache, storePass, alias, keyPass);
            } catch (Exception e) {
                err[0] = e.getMessage();
            }
            runOnUiThread(() -> {
                if (err[0] != null) {
                    infoText.setText("签名失败: " + err[0]);
                } else {
                    infoText.setText(report[0] + "\n\n大小: " + (outputCache.length() / 1024) + " KB");
                    saveBtn.setEnabled(true);
                    toast("签名完成");
                }
            });
        });
    }

    private void writeTo(Uri uri) {
        try (OutputStream os = getContentResolver().openOutputStream(uri, "w");
             InputStream in = new FileInputStream(outputCache)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) os.write(buf, 0, n);
            toast("已保存签名结果");
        } catch (Exception e) {
            toast("保存失败: " + e.getMessage());
        }
    }

    private static String val(TextInputEditText t) {
        return t.getText() == null ? "" : t.getText().toString().trim();
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }
}