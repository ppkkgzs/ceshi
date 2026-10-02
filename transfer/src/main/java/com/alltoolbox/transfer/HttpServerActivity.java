package com.alltoolbox.transfer;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.documentfile.provider.DocumentFile;

import java.io.IOException;

/**
 * HTTP 文件服务器：选择 SAF 目录树后启动局域网 Web 文件下载服务。
 */
public class HttpServerActivity extends AppCompatActivity {

    private static final String PREF = "transfer_prefs";
    private static final String KEY_TREE_URI = "tree_uri";
    private static final int PORT = 8080;
    private static final int REQ_TREE = 1001;

    private DocumentFile rootTree;
    private HttpServer server;

    private TextView tvDir;
    private TextView tvUrl;
    private TextView tvLog;
    private Button btnStartStop;
    private Button btnCopy;
    private TextView tvLogTitle;
    private ImageView ivQr;
    private TextView tvQrHint;
    private boolean running;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_http);

        Toolbar toolbar = findViewById(R.id.tbHttp);
        if (toolbar != null) {
            setSupportActionBar(toolbar);
            if (getSupportActionBar() != null) {
                getSupportActionBar().setDisplayHomeAsUpEnabled(true);
                getSupportActionBar().setTitle(R.string.http_server);
            }
        }

        tvDir = findViewById(R.id.tvDir);
        tvUrl = findViewById(R.id.tvUrl);
        tvLog = findViewById(R.id.tvLog);
        tvLogTitle = findViewById(R.id.tvLogTitle);
        btnStartStop = findViewById(R.id.btnStartStop);
        btnCopy = findViewById(R.id.btnCopy);
        ivQr = findViewById(R.id.ivQr);
        tvQrHint = findViewById(R.id.tvQrHint);

        findViewById(R.id.btnPickDir).setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            startActivityForResult(intent, REQ_TREE);
        });

        btnStartStop.setOnClickListener(v -> {
            if (running) {
                stopServer();
            } else {
                startServer();
            }
        });

        btnCopy.setOnClickListener(v -> {
            String url = tvUrl.getText().toString();
            if (!url.isEmpty()) {
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                cm.setPrimaryClip(ClipData.newPlainText("url", url));
                Toast.makeText(this, R.string.http_copied, Toast.LENGTH_SHORT).show();
            }
        });

        // 恢复已保存的目录
        String saved = getSharedPreferences(PREF, MODE_PRIVATE).getString(KEY_TREE_URI, null);
        if (saved != null) {
            Uri treeUri = Uri.parse(saved);
            try {
                getContentResolver().takePersistableUriPermission(
                        treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Exception ignore) {
            }
            applyTree(DocumentFile.fromTreeUri(this, treeUri));
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_TREE && resultCode == RESULT_OK && data != null) {
            Uri treeUri = data.getData();
            if (treeUri == null) return;
            try {
                getContentResolver().takePersistableUriPermission(
                        treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Exception ignore) {
            }
            getSharedPreferences(PREF, MODE_PRIVATE).edit()
                    .putString(KEY_TREE_URI, treeUri.toString()).apply();
            applyTree(DocumentFile.fromTreeUri(this, treeUri));
        }
    }

    private void applyTree(DocumentFile tree) {
        rootTree = tree;
        tvDir.setVisibility(android.view.View.VISIBLE);
        tvDir.setText(getString(R.string.http_dir) + " " + (tree == null ? "?" : tree.getName()));
        appendLog("已选择目录：" + (tree == null ? "?" : tree.getName()));
    }

    private void startServer() {
        if (rootTree == null) {
            Toast.makeText(this, R.string.http_empty, Toast.LENGTH_SHORT).show();
            return;
        }
        btnStartStop.setEnabled(false);
        new Thread(() -> {
            try {
                server = new HttpServer(rootTree, PORT, getContentResolver(), this::appendLog);
                server.start();
                running = true;
                String url = "http://" + HttpServer.getLocalAddress().getHostAddress()
                        + ":" + PORT + "/";
                runOnUiThread(() -> {
                    tvUrl.setVisibility(View.VISIBLE);
                    tvUrl.setText(getString(R.string.http_url) + "\n" + url);
                    btnCopy.setVisibility(View.VISIBLE);
                    btnStartStop.setText(R.string.http_stop);
                    btnStartStop.setEnabled(true);
                    showQr(url);
                });
            } catch (IOException e) {
                running = false;
                runOnUiThread(() -> {
                    btnStartStop.setEnabled(true);
                    Toast.makeText(this, getString(R.string.http_start_fail, e.getMessage()),
                            Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    private void stopServer() {
        if (server != null) server.stop();
        running = false;
        tvUrl.setVisibility(View.GONE);
        btnCopy.setVisibility(View.GONE);
        ivQr.setVisibility(View.GONE);
        tvQrHint.setVisibility(View.GONE);
        btnStartStop.setText(R.string.http_start);
    }

    /** 用 ZXing 生成 URL 二维码并显示，供同局域网设备扫码。 */
    private void showQr(String url) {
        try {
            int size = 512;
            com.google.zxing.BarcodeFormat fmt = com.google.zxing.BarcodeFormat.QR_CODE;
            java.util.Map<com.google.zxing.EncodeHintType, Object> hints = new java.util.HashMap<>();
            hints.put(com.google.zxing.EncodeHintType.CHARACTER_SET, "UTF-8");
            hints.put(com.google.zxing.EncodeHintType.MARGIN, 1);
            com.google.zxing.qrcode.QRCodeWriter writer = new com.google.zxing.qrcode.QRCodeWriter();
            com.google.zxing.common.BitMatrix matrix = writer.encode(url, fmt, size, size, hints);
            android.graphics.Bitmap bmp = android.graphics.Bitmap.createBitmap(size, size,
                    android.graphics.Bitmap.Config.RGB_565);
            for (int x = 0; x < size; x++) {
                for (int y = 0; y < size; y++) {
                    bmp.setPixel(x, y, matrix.get(x, y) ? 0xFF000000 : 0xFFFFFFFF);
                }
            }
            ivQr.setImageBitmap(bmp);
            ivQr.setVisibility(View.VISIBLE);
            tvQrHint.setVisibility(View.VISIBLE);
        } catch (Exception e) {
            ivQr.setVisibility(View.GONE);
        }
    }

    private void appendLog(String line) {
        runOnUiThread(() -> {
            tvLogTitle.setVisibility(android.view.View.VISIBLE);
            String cur = tvLog.getText().toString();
            tvLog.setText(cur.isEmpty() ? line : cur + "\n" + line);
            tvLog.post(() -> tvLog.getParent().requestLayout());
        });
    }

    @Override
    protected void onDestroy() {
        if (server != null) server.stop();
        super.onDestroy();
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }
}