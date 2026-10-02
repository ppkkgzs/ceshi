package com.alltoolbox.hide;

import android.net.Uri;
import android.os.Bundle;
import android.widget.Button;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;

/**
 * 文件隐身/伪装工具：
 *  - 隐藏：将文件移入应用私有（外部）目录并放置 .nomedia，使其在媒体库不显示；
 *  - 伪装：在文件内容前追加 JPEG 头，伪装为 .jpg 图片（保留原名）；
 *  - 还原：剥除伪装头，恢复原文件。
 */
public class HideActivity extends AppCompatActivity {

    private static final byte[] JPG_HEADER = {
            (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0x00, 0x10,
            'J', 'F', 'I', 'F', 0x00, 0x01
    };

    private ActivityResultLauncher<String[]> picker;
    private Uri lastUri;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_hide);
        setTitle(R.string.hide_title);

        picker = registerForActivityResult(new ActivityResultContracts.OpenDocument(), u -> lastUri = u);

        Button btnHide = findViewById(R.id.btn_hide);
        Button btnDisguise = findViewById(R.id.btn_disguise);
        Button btnRestore = findViewById(R.id.btn_restore);

        btnHide.setOnClickListener(v -> {
            if (lastUri == null) { picker.launch(new String[]{"*/*"}); return; }
            runOp(() -> {
                File src = copyToCache(lastUri);
                hideToPrivateDir(src);
                return getString(R.string.hide_hidden_here);
            });
        });
        btnDisguise.setOnClickListener(v -> {
            if (lastUri == null) { picker.launch(new String[]{"*/*"}); return; }
            runOp(() -> {
                File src = copyToCache(lastUri);
                disguiseAsImage(src);
                return getString(R.string.hide_ok);
            });
        });
        btnRestore.setOnClickListener(v -> {
            if (lastUri == null) { picker.launch(new String[]{"*/*"}); return; }
            runOp(() -> {
                File src = copyToCache(lastUri);
                restoreFromDisguise(src);
                return getString(R.string.hide_ok);
            });
        });
    }

    private interface Op {
        String run() throws Exception;
    }

    /** 在后台线程执行文件操作，避免阻塞主线程。 */
    private void runOp(Op op) {
        if (lastUri == null) return;
        final Uri uri = lastUri;
        lastUri = null;
        com.alltoolbox.core.task.TaskExecutor.get().io().execute(() -> {
            final String[] msg = {null};
            try {
                msg[0] = op.run();
            } catch (Exception e) {
                msg[0] = getString(R.string.hide_fail, e.getMessage() == null ? "err" : e.getMessage());
            }
            final String m = msg[0];
            runOnUiThread(() -> Toast.makeText(this, m, Toast.LENGTH_SHORT).show());
        });
    }

    private File copyToCache(Uri uri) throws Exception {
        File tmp = new File(getCacheDir(), "hide_" + System.currentTimeMillis() + ".file");
        try (InputStream in = getContentResolver().openInputStream(uri);
             FileOutputStream out = new FileOutputStream(tmp)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        }
        return tmp;
    }

    /** 把文件移入应用私有目录并放置 .nomedia。 */
    private void hideToPrivateDir(File src) throws Exception {
        File dir = new File(getExternalFilesDir(null), "hidden");
        if (!dir.exists()) dir.mkdirs();
        File dst = new File(dir, src.getName());
        copy(src, dst);
        src.delete();
        File nomedia = new File(dir, ".nomedia");
        if (!nomedia.exists()) {
            FileOutputStream fos = new FileOutputStream(nomedia);
            fos.close();
        }
    }

    /** 在文件内容前追加 JPEG 头，伪装为图片。 */
    private void disguiseAsImage(File src) throws Exception {
        File out = new File(src.getParent(), src.getName() + ".jpg");
        try (FileInputStream in = new FileInputStream(src);
             FileOutputStream fos = new FileOutputStream(out)) {
            fos.write(JPG_HEADER);
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) != -1) fos.write(buf, 0, n);
        }
        src.delete();
    }

    /** 还原：剥除头部字节。 */
    private void restoreFromDisguise(File src) throws Exception {
        long len = src.length();
        if (len <= JPG_HEADER.length) throw new Exception("not disguised");
        String name = src.getName();
        File out = new File(src.getParent(), name.endsWith(".jpg")
                ? name.substring(0, name.length() - 4) : name + ".restored");
        try (FileInputStream in = new FileInputStream(src);
             FileOutputStream fos = new FileOutputStream(out)) {
            in.skip(JPG_HEADER.length);
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) != -1) fos.write(buf, 0, n);
        }
        src.delete();
    }

    private void copy(File src, File dst) throws Exception {
        try (FileInputStream in = new FileInputStream(src);
             FileOutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        }
    }
}