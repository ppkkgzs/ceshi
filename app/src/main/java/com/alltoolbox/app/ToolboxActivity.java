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
        setTitle(R.string.toolbox_title);

        docPicker = registerForActivityResult(new ActivityResultContracts.OpenDocument(), this::onPicked);

        List<ToolEntry> entries = new ArrayList<>();
        // 编辑器
        entries.add(new ToolEntry(R.string.tool_title_editor, R.string.tool_desc_editor, TextEditorActivity.class, PICK_TEXT));
        entries.add(new ToolEntry(R.string.tool_title_hex, R.string.tool_desc_hex, HexViewerActivity.class, PICK_ANY));
        entries.add(new ToolEntry(R.string.tool_title_diff, R.string.tool_desc_diff, DiffActivity.class, PICK_NONE));
        // 压缩归档
        entries.add(new ToolEntry(R.string.tool_title_archive, R.string.tool_desc_archive, ArchiveActivity.class, PICK_NONE));
        // 清理
        entries.add(new ToolEntry(R.string.tool_title_cleanup, R.string.tool_desc_cleanup, CleanupActivity.class, PICK_NONE));
        entries.add(new ToolEntry(R.string.tool_title_search, R.string.tool_desc_search, FileSearchActivity.class, PICK_NONE));
        // APK 逆向
        entries.add(new ToolEntry(R.string.tool_title_encode, R.string.tool_desc_encode, EncodeActivity.class, PICK_NONE));
        entries.add(new ToolEntry(R.string.tool_title_apkinfo, R.string.tool_desc_apkinfo, ApkInfoActivity.class, PICK_NONE));
        entries.add(new ToolEntry(R.string.tool_title_extract, R.string.tool_desc_extract, ExtractApkActivity.class, PICK_NONE));
        entries.add(new ToolEntry(R.string.tool_title_sign, R.string.tool_desc_sign, SignatureActivity.class, PICK_APK));
        entries.add(new ToolEntry(R.string.tool_title_dex2smali, R.string.tool_desc_dex2smali, com.alltoolbox.apktools.tool.DexSmaliActivity.class, PICK_APK));
        entries.add(new ToolEntry(R.string.tool_title_resign, R.string.tool_desc_resign, com.alltoolbox.apktools.tool.ReSignActivity.class, PICK_APK));
        entries.add(new ToolEntry(R.string.tool_title_customsign, R.string.tool_desc_customsign, com.alltoolbox.apktools.tool.CustomSignActivity.class, PICK_APK));
        entries.add(new ToolEntry(R.string.tool_title_sopatch, R.string.tool_desc_sopatch, SoPatchActivity.class, PICK_SO));
        entries.add(new ToolEntry(R.string.tool_title_decompile, R.string.tool_desc_decompile, DecompileActivity.class, PICK_APK));
        entries.add(new ToolEntry(R.string.tool_title_manifest, R.string.tool_desc_manifest, ManifestEditActivity.class, PICK_NONE));
        // 文件管理
        entries.add(new ToolEntry(R.string.tool_title_dual, R.string.tool_desc_dual, com.alltoolbox.fbrowser.DualPaneActivity.class, PICK_NONE));
        entries.add(new ToolEntry(R.string.tool_title_ftp, R.string.tool_desc_ftp, com.alltoolbox.fbrowser.ftp.FtpActivity.class, PICK_NONE));
        // 安全
        entries.add(new ToolEntry(R.string.tool_title_vault, R.string.tool_desc_vault, VaultActivity.class, PICK_NONE));
        // 系统（仅 Root 设备显示）
        if (com.alltoolbox.core.permission.Root.isRooted()) {
            entries.add(new ToolEntry(R.string.tool_title_root, R.string.tool_desc_root, RootActivity.class, PICK_NONE));
        }
        // 传输与外设
        entries.add(new ToolEntry(R.string.tool_title_transfer, R.string.tool_desc_transfer, TransferActivity.class, PICK_NONE));

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
                    android.widget.Toast.makeText(this, getString(R.string.tool_read_fail, errMsg[0]),
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
        final int titleRes;
        final int descRes;
        final Class<?> target;
        final int pickType;

        ToolEntry(int titleRes, int descRes, Class<?> target, int pickType) {
            this.titleRes = titleRes;
            this.descRes = descRes;
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
            h.title.setText(e.titleRes);
            h.desc.setText(e.descRes);
            // 列表项进入动画：淡入 + 上移，营造顺滑的顺次浮现效果
            h.itemView.setAlpha(0f);
            h.itemView.setTranslationY(h.itemView.getResources()
                    .getDisplayMetrics().density * 24f);
            h.itemView.animate().alpha(1f).translationY(0f)
                    .setDuration(260).setStartDelay(position * 40L)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator())
                    .start();
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