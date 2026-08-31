package com.alltoolbox.apktools.tool;

import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.AppCompatEditText;

import com.alltoolbox.apktools.R;
import com.alltoolbox.core.task.TaskExecutor;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;

/**
 * 编辑反编译结果中的 AndroidManifest.xml（文本形式）。
 * 保存后回到反编译界面执行"回编译"即可应用修改。
 */
public class ManifestEditActivity extends AppCompatActivity {

    public static final String EXTRA_SRC = "src";
    public static final String EXTRA_DEST = "dest";

    private EditText content;
    private TextView pathText;
    private File dest;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_manifest_edit);
        setTitle("编辑 Manifest");

        content = findViewById(R.id.manifest_content);
        pathText = findViewById(R.id.manifest_path);
        Button save = findViewById(R.id.manifest_save);
        Button addPerm = findViewById(R.id.manifest_add_perm);

        String src = getIntent().getStringExtra(EXTRA_SRC);
        String destPath = getIntent().getStringExtra(EXTRA_DEST);
        dest = destPath != null ? new File(destPath) : null;
        if (dest != null) pathText.setText("保存至: " + destPath);

        if (src != null) {
            loadFile(new File(src));
        } else {
            loadSample();
        }

        save.setOnClickListener(v -> saveFile());
        addPerm.setOnClickListener(v -> showPermissionDialog());
    }

    private void loadFile(File f) {
        TaskExecutor.get().io().execute(() -> {
            try (FileInputStream fis = new FileInputStream(f)) {
                byte[] data = new byte[(int) f.length()];
                int read = 0;
                while (read < data.length) {
                    int n = fis.read(data, read, data.length - read);
                    if (n < 0) break;
                    read += n;
                }
                String text = new String(data, "UTF-8");
                runOnUiThread(() -> {
                    content.setText(text);
                    pathText.setText("已加载: " + f.getAbsolutePath() + "（" + f.length() + " 字节）");
                });
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, "读取失败: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        });
    }

    private void loadSample() {
        content.setText("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
                + "<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\"\n"
                + "    package=\"com.example.app\">\n\n</manifest>\n");
    }

    private void saveFile() {
        if (dest == null) {
            Toast.makeText(this, "未指定保存路径", Toast.LENGTH_SHORT).show();
            return;
        }
        TaskExecutor.get().io().execute(() -> {
            try {
                if (dest.getParentFile() != null) dest.getParentFile().mkdirs();
                try (FileOutputStream fos = new FileOutputStream(dest)) {
                    fos.write(content.getText().toString().getBytes("UTF-8"));
                }
                runOnUiThread(() -> {
                    pathText.setText("已保存: " + dest.getAbsolutePath());
                    Toast.makeText(this, "已保存，可回反编译界面执行回编译", Toast.LENGTH_LONG).show();
                });
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, "保存失败: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        });
    }

    /** 在 application 标签后插入 <uses-permission/>。 */
    private void showPermissionDialog() {
        final AppCompatEditText input = new AppCompatEditText(this);
        input.setHint("权限名，如 INTERNET / WRITE_EXTERNAL_STORAGE");
        new AlertDialog.Builder(this)
                .setTitle("快速加入权限")
                .setView(input)
                .setPositiveButton("加入", (d, w) -> {
                    String perm = input.getText().toString().trim();
                    if (!perm.isEmpty()) insertPermission(perm);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void insertPermission(String perm) {
        String tag = "    <uses-permission android:name=\"android.permission." + perm + "\" />\n";
        String text = content.getText().toString();
        int idx = text.indexOf("</manifest>");
        if (idx >= 0) {
            text = text.substring(0, idx) + tag + text.substring(idx);
        } else {
            text += "\n" + tag;
        }
        content.setText(text);
    }
}