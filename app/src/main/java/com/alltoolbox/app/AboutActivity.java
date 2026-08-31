package com.alltoolbox.app;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

/**
 * 关于页：应用版本、GitHub 下载链接、检查更新。
 */
public class AboutActivity extends AppCompatActivity {

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_about);

        androidx.appcompat.widget.Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        ((TextView) findViewById(R.id.about_version))
                .setText("版本 " + UpdateChecker.localVersion(this));

        findViewById(R.id.btn_open_github).setOnClickListener(v -> {
            openBrowser(UpdateChecker.DOWNLOAD_URL);
        });

        findViewById(R.id.btn_check_update).setOnClickListener(v -> checkUpdate(true));
    }

    private void checkUpdate(boolean manual) {
        TextView result = findViewById(R.id.update_result);
        result.setText("正在检查更新…");
        UpdateChecker.checkAsync(this, (isLatest, tag, message) ->
                runOnUiThread(() -> {
                    result.setText(message);
                    if (!isLatest) {
                        new MaterialAlertDialogBuilder(this)
                                .setTitle("发现新版本")
                                .setMessage("最新版本：" + tag + "\n当前版本：" + UpdateChecker.localVersion(this)
                                        + "\n\n点击「去下载」前往 GitHub 获取最新版本安装包。")
                                .setPositiveButton("去下载", (d, w) -> openBrowser(UpdateChecker.DOWNLOAD_URL))
                                .setNegativeButton("取消", null)
                                .show();
                    } else if (manual) {
                        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
                    }
                }));
    }

    private void openBrowser(String url) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            startActivity(i);
        } catch (Exception e) {
            Toast.makeText(this, "无法打开链接", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public boolean onOptionsItemSelected(android.view.MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}