package com.alltoolbox.app;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.alltoolbox.apktools.apk.ApkInfoActivity;
import com.alltoolbox.apktools.tool.DecompileActivity;
import com.alltoolbox.apktools.tool.EncodeActivity;
import com.alltoolbox.apktools.tool.ManifestEditActivity;
import com.alltoolbox.apktools.tool.SignatureActivity;
import com.alltoolbox.apktools.tool.SoPatchActivity;
import com.alltoolbox.archive.ArchiveActivity;
import com.alltoolbox.cleanup.CleanupActivity;
import com.alltoolbox.cleanup.FileSearchActivity;
import com.alltoolbox.editor.DiffActivity;
import com.alltoolbox.editor.HexViewerActivity;
import com.alltoolbox.editor.TextEditorActivity;
import com.alltoolbox.security.VaultActivity;
import com.alltoolbox.root.RootActivity;
import com.alltoolbox.transfer.TransferActivity;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 工具箱：聚合 编辑器 / 压缩归档 / 空间清理 / APK 逆向 / 安全 五大类工具入口。
 * 需要打开某个文件时，会先经系统文档选择器挑选，再拷入缓存交给对应工具。
 */
public class ToolboxActivity extends AppCompatActivity {

    // 文档选择器：选中后拷入缓存，再启动目标工具
    private ActivityResultLauncher<String[]> docPicker;
    private Class<?> pendingTarget;
    private String pickSuffix;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_toolbox);
        setTitle("工具箱");

        docPicker = registerForActivityResult(new ActivityResultContracts.OpenDocument(), this::onPicked);

        List<ToolEntry> entries = new ArrayList<>();
        // 编辑器
        entries.add(new ToolEntry("文本/代码编辑", "编辑 TXT / 代码 / Smali 等", TextEditorActivity.class, PICK_TEXT));
        entries.add(new ToolEntry("十六进制查看", "二进制阅读、偏移跳转", HexViewerActivity.class, PICK_ANY));
        entries.add(new ToolEntry("文本对比", "双栏高亮差异", DiffActivity.class, PICK_NONE));
        // 压缩归档
        entries.add(new ToolEntry("压缩/解压", "ZIP 打包与解包", ArchiveActivity.class, PICK_NONE));
        // 清理
        entries.add(new ToolEntry("空间清理", "扫描大文件与可清理项", CleanupActivity.class, PICK_NONE));
        entries.add(new ToolEntry("文件搜索", "按关键词全目录查找文件", FileSearchActivity.class, PICK_NONE));
        // APK 逆向
        entries.add(new ToolEntry("编码转换", "Unicode / Base64 / Hex 互转", EncodeActivity.class, PICK_NONE));
        entries.add(new ToolEntry("APK 包详情", "版本/权限/签名指纹等信息", ApkInfoActivity.class, PICK_NONE));
        entries.add(new ToolEntry("提取安装包", "从已安装应用中提取 APK 到下载目录", ExtractApkActivity.class, PICK_NONE));
        entries.add(new ToolEntry("签名信息", "查看 APK 签名证书指纹", SignatureActivity.class, PICK_APK));
        entries.add(new ToolEntry("DEX 转 Smali", "解析 DEX 为结构级 smali 骨架", com.alltoolbox.apktools.tool.DexSmaliActivity.class, PICK_APK));
        entries.add(new ToolEntry("APK 重签名", "用调试密钥重签 APK (v1/v2/v3)", com.alltoolbox.apktools.tool.ReSignActivity.class, PICK_APK));
        entries.add(new ToolEntry("自定义 APK 签名", "用自己的密钥库/密码签名 APK", com.alltoolbox.apktools.tool.CustomSignActivity.class, PICK_APK));
        entries.add(new ToolEntry("SO 补丁", "SO 库十六进制等长补丁", SoPatchActivity.class, PICK_SO));
        entries.add(new ToolEntry("反编译/回编译", "apktool 反编译与回编译", DecompileActivity.class, PICK_APK));
        entries.add(new ToolEntry("编辑 Manifest", "编辑反编译的 AndroidManifest.xml", ManifestEditActivity.class, PICK_NONE));
        // 文件管理
        entries.add(new ToolEntry("双栏文件管理", "左右两栏并排浏览与跨栏复制", com.alltoolbox.fbrowser.DualPaneActivity.class, PICK_NONE));
        entries.add(new ToolEntry("FTP 远程文件", "连接 FTP 浏览/下载/上传", com.alltoolbox.fbrowser.ftp.FtpActivity.class, PICK_NONE));
        // 安全
        entries.add(new ToolEntry("加密保险箱", "AES 加密保存与私密文件", VaultActivity.class, PICK_NONE));
        // 系统（仅 Root 设备显示）
        if (com.alltoolbox.core.permission.Root.isRooted()) {
            entries.add(new ToolEntry("Root 增强", "冻结/解冻、卸载、系统文件访问", RootActivity.class, PICK_NONE));
        }
        // 传输与外设
        entries.add(new ToolEntry("传输与外设", "HTTP 文件服务器、蓝牙发送", TransferActivity.class, PICK_NONE));

        RecyclerView list = findViewById(R.id.tool_list);
        list.setLayoutManager(new LinearLayoutManager(this));
        list.setAdapter(new ToolAdapter(entries));
    }

    // 选择类型
    private static final int PICK_NONE = 0;            // 直接启动
    private static final int PICK_TEXT = 1;            // 文本类
    private static final int PICK_ANY = 2;             // 任意文件
    private static final int PICK_APK = 3;             // apk
    private static final int PICK_SO = 4;              // so

    /** 需要文件数据的工具：先弹系统选择器。 */
    private void pickFile(Class<?> target, int type, String suffix) {
        pendingTarget = target;
        pickSuffix = suffix;
        switch (type) {
            case PICK_TEXT: docPicker.launch(new String[]{"text/*", "application/xml", "*/*"}); break;
            case PICK_APK: docPicker.launch(new String[]{"application/vnd.android.package-archive", "*/*"}); break;
            case PICK_SO: docPicker.launch(new String[]{"*/*"}); break;
            default: docPicker.launch(new String[]{"*/*"}); break;
        }
    }

    private void onPicked(Uri uri) {
        if (uri == null || pendingTarget == null) return;
        final Class<?> target = pendingTarget;
        pendingTarget = null;
        File cache = new File(getCacheDir(), "toolbox_" + System.currentTimeMillis()
                + "_" + (pickSuffix == null ? "file" : pickSuffix));
        // 拷贝搬到后台线程，避免大文件在 UI 线程阻塞导致“打开慢/卡顿”
        com.alltoolbox.core.task.TaskExecutor.get().io().execute(() -> {
            final String[] errMsg = {null};
            try (InputStream in = getContentResolver().openInputStream(uri);
                 FileOutputStream out = new FileOutputStream(cache)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            } catch (Exception e) {
                errMsg[0] = e.getMessage();
            }
            runOnUiThread(() -> {
                if (isFinishing()) return;
                if (errMsg[0] != null) {
                    android.widget.Toast.makeText(this, "读取所选文件失败: " + errMsg[0],
                            android.widget.Toast.LENGTH_SHORT).show();
                    return;
                }
                Intent i = new Intent(this, target);
                i.putExtra(com.alltoolbox.editor.TextEditorActivity.EXTRA_PATH, cache.getAbsolutePath());
                startActivity(i);
            });
        });
    }

    private static final class ToolEntry {
        final String title;
        final String desc;
        final Class<?> target;
        final int pickType;

        ToolEntry(String title, String desc, Class<?> target, int pickType) {
            this.title = title;
            this.desc = desc;
            this.target = target;
            this.pickType = pickType;
        }
    }

    private final class ToolAdapter extends RecyclerView.Adapter<ToolAdapter.VH> {
        private final List<ToolEntry> data;

        ToolAdapter(List<ToolEntry> data) {
            this.data = data;
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_tool, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int position) {
            ToolEntry e = data.get(position);
            h.title.setText(e.title);
            h.desc.setText(e.desc);
            h.itemView.setOnClickListener(v -> {
                if (e.pickType == PICK_NONE) {
                    startActivity(new Intent(ToolboxActivity.this, e.target));
                } else {
                    pickFile(e.target, e.pickType, fileLabel(e));
                }
            });
        }

        @Override
        public int getItemCount() {
            return data.size();
        }

        final class VH extends RecyclerView.ViewHolder {
            final TextView title, desc;

            VH(View v) {
                super(v);
                title = v.findViewById(R.id.tool_title);
                desc = v.findViewById(R.id.tool_desc);
            }
        }
    }

    private static String fileLabel(ToolEntry e) {
        if (e.target == SignatureActivity.class
                || e.target == DecompileActivity.class) return "selected.apk";
        if (e.target == SoPatchActivity.class) return "selected.so";
        return "selected.txt";
    }
}