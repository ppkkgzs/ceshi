package com.alltoolbox.cleanup;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.documentfile.provider.DocumentFile;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.alltoolbox.core.file.FileUtil;
import com.alltoolbox.core.task.TaskExecutor;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 文件搜索：按文件名关键词，递归搜索用户通过系统目录选择器选中的目录树。
 * 使用 SAF（DocumentFile）遍历，兼容 Android 10+ 分区存储；结果点击可打开。
 */
public class FileSearchActivity extends AppCompatActivity {

    private static final int MAX_RESULTS = 5000;

    private final List<FileResult> results = new ArrayList<>();

    private EditText input;
    private TextView countView;
    private RecyclerView list;
    private SearchAdapter adapter;

    private DocumentFile rootDoc;   // 已选目录对应的 DocumentFile 根
    private Uri rootUri;

    private final ActivityResultLauncher<Uri> pickDir =
            registerForActivityResult(new ActivityResultContracts.OpenDocumentTree(),
                    uri -> {
                        if (uri == null) return; // 用户取消
                        rootUri = uri;
                        try {
                            getContentResolver()
                                    .takePersistableUriPermission(uri,
                                            Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        } catch (SecurityException ignored) {
                            // 部分场景拿不到持久权限，仍可当次使用
                        }
                        rootDoc = DocumentFile.fromTreeUri(this, uri);
                        if (rootDoc == null) {
                            rootDoc = DocumentFile.fromSingleUri(this, uri);
                        }
                        Toast.makeText(this,
                                getString(R.string.file_search_chosen, safeName(rootDoc)),
                                Toast.LENGTH_SHORT).show();
                        search(); // 选择目录后立即按当前关键词搜索
                    });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_file_search);
        setTitle(R.string.file_search_title);

        input = findViewById(R.id.fs_input);
        countView = findViewById(R.id.fs_count);
        list = findViewById(R.id.fs_list);
        Button searchBtn = findViewById(R.id.fs_search);
        Button pickBtn = findViewById(R.id.fs_pick);

        adapter = new SearchAdapter();
        list.setLayoutManager(new LinearLayoutManager(this));
        list.setAdapter(adapter);

        input.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) {
                search();
                return true;
            }
            return false;
        });
        pickBtn.setOnClickListener(v -> pickDir.launch(null));
        searchBtn.setOnClickListener(v -> search());

        countView.setText(R.string.file_search_empty);
    }

    private String safeName(DocumentFile df) {
        if (df == null) return "";
        String n = df.getName();
        return n == null || n.isEmpty() ? "" : n;
    }

    private String keyword() {
        Editable e = input.getText();
        return e == null ? "" : e.toString().trim();
    }

    private void search() {
        String kw = keyword();
        if (TextUtils.isEmpty(kw)) {
            Toast.makeText(this, R.string.file_search_need_kw, Toast.LENGTH_SHORT).show();
            return;
        }
        if (rootDoc == null) {
            Toast.makeText(this, R.string.file_search_need_dir, Toast.LENGTH_SHORT).show();
            return;
        }
        final String needle = kw.toLowerCase(Locale.ROOT);
        Toast.makeText(this, R.string.file_search_scanning, Toast.LENGTH_SHORT).show();
        // 复用扫描专用线程池，避免阻塞主线程/普通 IO
        TaskExecutor.get().scan().execute(() -> {
            List<FileResult> out = new ArrayList<>();
            walk(rootDoc, "", needle, out);
            final List<FileResult> finalOut = out;
            runOnUiThread(() -> showResults(finalOut));
        });
    }

    /** 递归遍历 DocumentFile 树，收集文件名（忽略大小写）包含关键词的文件。 */
    private void walk(DocumentFile dir, String parentPath, String needle, List<FileResult> acc) {
        if (dir == null || !dir.canRead()) return;
        DocumentFile[] children = dir.listFiles();
        if (children == null) return;
        for (DocumentFile child : children) {
            String name = child.getName();
            if (name == null) continue;
            String subPath = parentPath.length() == 0
                    ? name
                    : parentPath + "/" + name;
            if (child.isDirectory()) {
                walk(child, subPath, needle, acc);
                if (acc.size() >= MAX_RESULTS) break;
            } else if (name.toLowerCase(Locale.ROOT).contains(needle)) {
                acc.add(FileResult.of(child, subPath));
                if (acc.size() >= MAX_RESULTS) break;
            }
        }
    }

    private void showResults(List<FileResult> out) {
        results.clear();
        results.addAll(out);
        adapter.notifyDataSetChanged();
        countView.setText(getString(R.string.file_search_count, results.size()));
        if (results.isEmpty()) {
            Toast.makeText(this, R.string.file_search_empty, Toast.LENGTH_SHORT).show();
        }
    }

    private void open(FileResult r) {
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(r.uri, r.mime);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(intent);
        } catch (Exception first) {
            // MIME 猜测失败时退回不指定类型，让系统自行判断
            Intent fallback = new Intent(Intent.ACTION_VIEW);
            fallback.setData(r.uri);
            fallback.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            try {
                startActivity(fallback);
            } catch (Exception second) {
                Toast.makeText(this, R.string.file_search_open_fail, Toast.LENGTH_SHORT).show();
            }
        }
    }

    private static String formatSize(long b) {
        if (b >= 1024L * 1024 * 1024) {
            return String.format(Locale.ROOT, "%.1f GB", b / 1073741824.0);
        }
        if (b >= 1024L * 1024) {
            return String.format(Locale.ROOT, "%.1f MB", b / 1048576.0);
        }
        if (b >= 1024L) {
            return String.format(Locale.ROOT, "%.1f KB", b / 1024.0);
        }
        return b + " B";
    }

    /** 一条搜索结果。 */
    private static final class FileResult {
        final String name;
        final String parentPath;
        final long size;
        final Uri uri;
        final String mime;

        FileResult(String name, String parentPath, long size, Uri uri, String mime) {
            this.name = name;
            this.parentPath = parentPath;
            this.size = size;
            this.uri = uri;
            this.mime = mime;
        }

        static FileResult of(DocumentFile df, String path) {
            String name = df.getName();
            if (name == null) name = "";
            String mime = FileUtil.getMimeType(name);
            String parent = parentOf(path);
            return new FileResult(name, parent, df.length(), df.getUri(), mime);
        }

        private static String parentOf(String path) {
            int idx = path.lastIndexOf('/');
            return idx < 0 ? "" : path.substring(0, idx);
        }
    }

    private final class SearchAdapter extends RecyclerView.Adapter<SearchAdapter.VH> {

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_file_search, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int position) {
            FileResult r = results.get(position);
            h.name.setText(r.name);
            h.parent.setText(r.parentPath);
            h.size.setText(formatSize(r.size));
            h.icon.setImageResource(iconFor(r));
            h.itemView.setOnClickListener(v -> open(r));
        }

        @Override
        public int getItemCount() {
            return results.size();
        }

        private int iconFor(FileResult r) {
            switch (FileUtil.getKind(new java.io.File(r.name))) {
                case IMAGE: return android.R.drawable.ic_menu_gallery;
                case VIDEO: return android.R.drawable.ic_media_play;
                case AUDIO: return android.R.drawable.ic_media_play;
                case ARCHIVE: return android.R.drawable.ic_menu_manage;
                case APK: return android.R.drawable.ic_menu_compass;
                default: return android.R.drawable.ic_menu_sort_by_size;
            }
        }

        final class VH extends RecyclerView.ViewHolder {
            final ImageView icon;
            final TextView name, parent, size;

            VH(View v) {
                super(v);
                icon = v.findViewById(R.id.fs_item_icon);
                name = v.findViewById(R.id.fs_item_name);
                parent = v.findViewById(R.id.fs_item_parent);
                size = v.findViewById(R.id.fs_item_size);
            }
        }
    }
}