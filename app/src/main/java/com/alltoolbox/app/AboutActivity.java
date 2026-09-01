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
                .setText(getString(R.string.version) + " " + UpdateChecker.localVersion(this));

        findViewById(R.id.btn_dl_stable).setOnClickListener(v -> openBrowser(UpdateChecker.DOWNLOAD_URL));

        findViewById(R.id.btn_dl_beta).setOnClickListener(v -> openBrowser(UpdateChecker.BETA_DOWNLOAD_URL));

        findViewById(R.id.btn_dl_history).setOnClickListener(v -> openBrowser(UpdateChecker.DOWNLOAD_URL));

        findViewById(R.id.btn_check_update).setOnClickListener(v -> checkUpdate(true));

        findViewById(R.id.btn_check_beta).setOnClickListener(v -> checkBetaUpdate());

        findViewById(R.id.btn_signing_note).setOnClickListener(v -> showSigningNote());

        findViewById(R.id.btn_open_source).setOnClickListener(v ->
                new MaterialAlertDialogBuilder(this)
                        .setTitle(R.string.about_source_title)
                        .setMessage(getString(R.string.about_open_source_msg))
                        .setPositiveButton(R.string.about_open_repo, (d, w) -> openBrowser("https://github.com/ppkkgzs/ceshi"))
                        .setNegativeButton(R.string.close, null)
                        .show());
    }

    /** 在应用内展示「签名变更说明」，内容与仓库 SIGNING_CHANGE.md 保持一致。 */
    private void showSigningNote() {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.about_signing_note)
                .setMessage(getString(R.string.about_signing_note_msg))
                .setPositiveButton(R.string.ok, null)
                .show();
    }

    private void checkUpdate(boolean manual) {
        TextView result = findViewById(R.id.update_result);
        result.setText(getString(R.string.about_checking_update));
        UpdateChecker.checkAsync(this, (isLatest, tag, message) ->
                runOnUiThread(() -> {
                    result.setText(message);
                    if (!isLatest) {
                        new MaterialAlertDialogBuilder(this)
                                .setTitle(R.string.set_found_update_title)
                                .setMessage(getString(R.string.about_found_update_msg,
                                        tag, UpdateChecker.localVersion(this)))
                                .setPositiveButton(R.string.go_download, (d, w) -> openBrowser(UpdateChecker.DOWNLOAD_URL))
                                .setNegativeButton(R.string.cancel, null)
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
            Toast.makeText(this, getString(R.string.open_link_failed), Toast.LENGTH_SHORT).show();
        }
    }

    private void checkBetaUpdate() {
        android.app.ProgressDialog pd = new android.app.ProgressDialog(this);
        pd.setTitle(getString(R.string.set_check_beta_title));
        pd.setMessage(getString(R.string.set_check_beta_msg));
        pd.setIndeterminate(true);
        pd.setCancelable(false);
        pd.show();

        UpdateChecker.checkBetaAsync(this, (isLatest, tag, message) ->
                runOnUiThread(() -> {
                    pd.dismiss();
                    if (!isLatest && tag != null && !tag.isEmpty()) {
                        new MaterialAlertDialogBuilder(this)
                                .setTitle(R.string.set_found_beta_title)
                                .setMessage(getString(R.string.about_found_beta_msg,
                                        tag, UpdateChecker.localVersion(this)))
                                .setNegativeButton(R.string.cancel, null)
                                .setPositiveButton(R.string.download_now, (d, w) ->
                                        Updater.downloadAndInstallBeta(this, tag))
                                .show();
                    } else {
                        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
                    }
                }));
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