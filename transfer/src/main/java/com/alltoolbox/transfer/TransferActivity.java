package com.alltoolbox.transfer;

import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

/**
 * 传输与外设入口：HTTP 文件服务器、蓝牙发送。
 */
public class TransferActivity extends AppCompatActivity {

    private ActivityResultLauncher<String[]> filePickForBt;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_transfer);

        Toolbar toolbar = findViewById(R.id.tbTransfer);
        if (toolbar != null) {
            setSupportActionBar(toolbar);
            if (getSupportActionBar() != null) {
                getSupportActionBar().setDisplayHomeAsUpEnabled(true);
                getSupportActionBar().setTitle(R.string.transfer_title);
            }
        }

        filePickForBt = registerForActivityResult(
                new ActivityResultContracts.OpenDocument(), this::onBtFilePicked);

        findViewById(R.id.layHttp).setOnClickListener(v ->
                startActivity(new Intent(this, HttpServerActivity.class)));

        findViewById(R.id.layBt).setOnClickListener(v ->
                filePickForBt.launch(new String[]{"*/*"}));
    }

    private void onBtFilePicked(Uri uri) {
        if (uri == null) return;
        grantRead(uri);
        // 系统分享，让用户选择蓝牙设备
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("*/*");
        send.putExtra(Intent.EXTRA_STREAM, uri);
        send.setClipData(ClipData.newUri(getContentResolver(), "share", uri));
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(Intent.createChooser(send, getString(R.string.bluetooth_send)));
        } catch (Exception e) {
            Toast.makeText(this, "未找到可发送的蓝牙/分享应用", Toast.LENGTH_SHORT).show();
        }
    }

    private void grantRead(Uri uri) {
        try {
            getContentResolver().takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Exception ignore) {
        }
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }
}