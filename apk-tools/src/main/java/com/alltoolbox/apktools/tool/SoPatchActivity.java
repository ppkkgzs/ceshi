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
import com.alltoolbox.apktools.patch.SoPatcher;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/**
 * SO 库十六进制补丁工具：等长字节替换。
 */
public class SoPatchActivity extends AppCompatActivity {

    /** 文件浏览器直传的文件路径（可空）。 */
    public static final String EXTRA_PATH = "path";

    private TextView pathText, logText;
    private EditText findInput, replaceInput;
    private File target;

    private ActivityResultLauncher<String> fileLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_so_patch);
        setTitle("SO 十六进制补丁");

        pathText = findViewById(R.id.so_path);
        logText = findViewById(R.id.so_log);
        findInput = findViewById(R.id.so_find);
        replaceInput = findViewById(R.id.so_replace);
        Button pick = findViewById(R.id.so_pick);
        Button apply = findViewById(R.id.so_apply);

        fileLauncher = registerForActivityResult(
                new ActivityResultContracts.GetContent(),
                uri -> {
                    if (uri != null) {
                        SetResult r = copyToCache(uri);
                        if (r != null) {
                            target = r.file;
                            pathText.setText(r.display);
                            logText.setText("已载入: " + r.display);
                        }
                    }
                });

        pick.setOnClickListener(v -> fileLauncher.launch("*/*"));
        apply.setOnClickListener(v -> applyPatch());

        // 来自文件浏览器：直接载入本地 SO/文件
        String direct = getIntent().getStringExtra(EXTRA_PATH);
        if (direct != null) {
            File f = new File(direct);
            if (f.exists()) {
                target = f;
                pathText.setText(f.getName());
                logText.setText("已载入: " + f.getAbsolutePath());
            }
        }
    }

    private void applyPatch() {
        if (target == null) {
            Toast.makeText(this, "请先选择 SO 文件", Toast.LENGTH_SHORT).show();
            return;
        }
        String find = findInput.getText().toString().trim();
        String replace = replaceInput.getText().toString().trim();
        logText.setText("正在打补丁…");
        SoPatcher.patchAsync(target, find, replace, (ok, count, msg) ->
                runOnUiThread(() -> logText.setText(msg)));
    }

    private SetResult copyToCache(Uri uri) {
        try {
            File out = new File(getCacheDir(),
                    "data" + System.currentTimeMillis() + targetSuffix(uri));
            try (InputStream in = getContentResolver().openInputStream(uri);
                 FileOutputStream fos = new FileOutputStream(out)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) fos.write(buf, 0, n);
            }
            return new SetResult(out, uri.getLastPathSegment());
        } catch (Exception e) {
            logText.setText("读取失败: " + e.getMessage());
            return null;
        }
    }

    private String targetSuffix(Uri uri) {
        String p = uri.getPath();
        return p != null && p.endsWith(".so") ? ".so" : ".data";
    }

    private static final class SetResult {
        final File file;
        final String display;

        SetResult(File file, String display) {
            this.file = file;
            this.display = display;
        }
    }
}