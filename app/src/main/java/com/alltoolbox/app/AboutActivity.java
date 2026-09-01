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

        findViewById(R.id.btn_check_beta).setOnClickListener(v -> checkBetaUpdate());

        findViewById(R.id.btn_signing_note).setOnClickListener(v -> showSigningNote());

        findViewById(R.id.btn_open_source).setOnClickListener(v ->
                new MaterialAlertDialogBuilder(this)
                        .setTitle("开源声明")
                        .setMessage("本软件以 GNU 通用公共许可证第 3 版（GPL v3）开源。\n\n"
                                + "· 您有权免费获取、研究、修改与再分发本软件源代码\n"
                                + "· 任何修改版或衍生作品再分发时，须以相同协议（GPL v3）开源并保留署名\n"
                                + "· 本软件按「原样」提供，作者不承担担保\n\n"
                                + "第三方组件：\n"
                                + "· 7-Zip-JBinding-4Android（原生 7-Zip 解压引擎）"
                                + "——由 omicronapps 维护，以其 LGPL-2.1 许可一并分发。\n"
                                + "  编码与底层 7-Zip 由 Igor Pavlov 编写 © 1999-2016。\n"
                                + "  本项目以「库」形式链接使用并随 APK 分发该动态库，按 LGPL-2.1"
                                + " 允许通过动态链接引用；您可替换/升级该库，也可以通过修改该库后重新链接"
                                + "（反汇编解码参考 LGPL-2.1 第 6 节）。\n\n"
                                + "开源仓库：github.com/ppkkgzs/ceshi")
                        .setPositiveButton("打开仓库", (d, w) -> openBrowser("https://github.com/ppkkgzs/ceshi"))
                        .setNegativeButton("关闭", null)
                        .show());
    }

    /** 在应用内展示「签名变更说明」，内容与仓库 SIGNING_CHANGE.md 保持一致。 */
    private void showSigningNote() {
        String note = "自 v1.7.0 起，安装包签名密钥已更换。\n\n"
                + "【旧版本 v1.6.x 及更早】\n使用旧密钥，签名证书主题：\n"
                + "CN=PK Manager, OU=PKTools, O=PKTools, L=Shenzhen, ST=Guangdong, C=CN\n\n"
                + "【新版本 v1.7.0 及后续】\n使用新密钥 alltoolbox.jks，签名证书主题：\n"
                + "CN=AllToolbox, OU=AllToolbox, O=AllToolbox, L=X, ST=X, C=CN\n\n"
                + "【影响与升级说明】\nAndroid 不允许用不同签名覆盖安装已存在的应用。"
                + "如果你手机上安装的是旧签名版本（v1.6.x 及更早），"
                + "直接覆盖安装新版本会提示「签名异常 / 应用未安装 / INSTALL_FAILED_UPDATE_INCOMPATIBLE」。\n"
                + "解决办法：先卸载旧版，再安装新版（v1.7.0+）。\n"
                + "（卸载会清除本应用本地的设置、书签、保险箱等数据）\n\n"
                + "【之后】从 v1.7.0 起所有版本均使用同一把 AllToolbox 密钥签名，"
                + "后续兄弟版本间可直接覆盖升级，不会再出现签名异常。";
        new MaterialAlertDialogBuilder(this)
                .setTitle("签名变更说明")
                .setMessage(note)
                .setPositiveButton("知道了", null)
                .show();
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

    private void checkBetaUpdate() {
        android.app.ProgressDialog pd = new android.app.ProgressDialog(this);
        pd.setTitle("检查 Beta 更新");
        pd.setMessage("正在检查最新测试版本…");
        pd.setIndeterminate(true);
        pd.setCancelable(false);
        pd.show();

        UpdateChecker.checkBetaAsync(this, (isLatest, tag, message) ->
                runOnUiThread(() -> {
                    pd.dismiss();
                    if (!isLatest && tag != null && !tag.isEmpty()) {
                        new MaterialAlertDialogBuilder(this)
                                .setTitle("发现 Beta 新版本")
                                .setMessage("最新 Beta 版本：" + tag + "\n当前版本：" + UpdateChecker.localVersion(this)
                                        + "\n\n是否立即下载并安装？")
                                .setNegativeButton("取消", null)
                                .setPositiveButton("立即下载", (d, w) ->
                                        Updater.downloadAndInstall(this, tag))
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