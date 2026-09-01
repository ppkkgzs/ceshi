package com.alltoolbox.fbrowser;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.alltoolbox.archive.ArchiveManager;
import com.alltoolbox.core.task.TaskExecutor;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 压缩包内部浏览器（支持“包中包”逐层进入）。
 *
 * 需求：压缩包里套着多个压缩包，用户想一层一层点进去，直到找到目标压缩文件。
 * 实现：外层文件浏览器点击 zip/apk/jar 的“打开”后进入本界面。
 *  - 目录条目：进入（前缀下钻，仍在当前 zip 内）；
 *  - zip/apk/jar 文件条目：先解到临时文件再进入（切换 zip、回到根）；
 *  - 其它文件条目：可单独解压到指定目录。
 * 顶部菜单支持“解压当前层”到指定目录。
 */
public class ZipBrowserActivity extends AppCompatActivity {

    public static final String EXTRA_ZIP = "extra_zip";

    private RecyclerView list;
    private TextView emptyView;
    private TextView pathView;
    private EntryAdapter adapter;

    private String originalZipName = "archive.zip";

    /** 浏览栈：每一格是 { 当前 zip 文件, 虚拟目录前缀 }。 */
    private final java.util.Deque<Frame> stack = new java.util.ArrayDeque<>();

    private int tempSeq = 0;

    private static final class Frame {
        final File zip;
        final String prefix; // "" 表示 zip 根；否则是不带尾斜杠的虚拟目录前缀
        Frame(File zip, String prefix) {
            this.zip = zip;
            this.prefix = prefix;
        }
    }

    /** 列表中的一个条目（目录或文件）。 */
    private static final class ScreenEntry {
        final String name;
        final boolean directory;
        final long size;
        /** 当前这条是否直接来自正在浏览的“包中包”根层。 */
        final boolean isTempChild;
        ScreenEntry(String name, boolean directory, long size, boolean isTempChild) {
            this.name = name;
            this.directory = directory;
            this.size = size;
            this.isTempChild = isTempChild;
        }
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_zip_browser);

        androidx.appcompat.widget.Toolbar toolbar = findViewById(R.id.zip_toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        list = findViewById(R.id.zip_list);
        emptyView = findViewById(R.id.zip_empty);
        pathView = findViewById(R.id.zip_path);
        list.setHasFixedSize(true);
        list.setItemViewCacheSize(24);
        LinearLayoutManager lm = new LinearLayoutManager(this);
        list.setLayoutManager(lm);
        adapter = new EntryAdapter(callbacks());
        list.setAdapter(adapter);

        String zipPath = getIntent() != null ? getIntent().getStringExtra(EXTRA_ZIP) : null;
        if (zipPath == null) {
            toast("未指定压缩包");
            finish();
            return;
        }
        File zip = new File(zipPath);
        originalZipName = zip.getName();
        stack.push(new Frame(zip, ""));
        load();
    }

    @Override
    public boolean onCreateOptionsMenu(android.view.Menu menu) {
        getMenuInflater().inflate(R.menu.menu_zip_browser, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull android.view.MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_zip_extract) {
            promptExtract("解压当前层");
            return true;
        }
        if (id == R.id.action_zip_refresh) {
            load();
            return true;
        }
        if (id == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onBackPressed() {
        if (!goUp()) super.onBackPressed();
    }

    // ------------------------------------------------------------------

    private EntryAdapter.Listener callbacks() {
        return new EntryAdapter.Listener() {
            @Override
            public void onOpen(ScreenEntry entry) {
                if (entry.directory) {
                    enterDir(entry.name);
                } else if (isNestedArchive(entry.name) && !entry.isTempChild) {
                    enterNestedZip(entry);
                } else {
                    showEntryMenu(entry);
                }
            }
        };
    }

    private boolean isNestedArchive(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        return n.endsWith(".zip") || n.endsWith(".apk") || n.endsWith(".jar");
    }

    private String fullPath(String prefix, String name) {
        if (prefix == null || prefix.isEmpty()) return name;
        return prefix + "/" + name;
    }

    /** 进入压缩包内的某个目录（前缀下钻，不切换 zip）。 */
    private void enterDir(String name) {
        Frame f = stack.peek();
        String childPrefix = fullPath(f.prefix, name);
        stack.push(new Frame(f.zip, childPrefix));
        load();
    }

    /** 进入“包中包”：把嵌套压缩包解到临时文件后切换进去。 */
    private void enterNestedZip(final ScreenEntry entry) {
        final Frame f = stack.peek();
        final String target = fullPath(f.prefix, entry.name);
        final File tmp = new File(getCacheDir(), "nested_" + (++tempSeq) + "_"
                + System.currentTimeMillis() + ".zip");
        TaskExecutor.get().io().execute(() -> {
            try {
                ArchiveManager.get().extractZipEntryToFile(f.zip, target, tmp);
                runOnUiThread(() -> {
                    stack.push(new Frame(tmp, ""));
                    load();
                });
            } catch (Exception e) {
                runOnUiThread(
                        () -> toast("无法进入 " + entry.name + "：" + e.getMessage()));
            }
        });
    }

    private boolean goUp() {
        if (stack.size() <= 1) return false;
        Frame was = stack.pop();
        Frame next = stack.peek();
        // 只在真正“离开”某个解出的临时“包中包”时才删除临时文件；
        // 若仍在该临时包内部（上下格是同一 zip）则保留，以便继续浏览。
        boolean isTemp = was.zip != null
                && was.zip.getAbsolutePath().startsWith(getCacheDir().getAbsolutePath());
        if (isTemp && (next == null || !next.zip.equals(was.zip))) {
            was.zip.delete();
        }
        load();
        return true;
    }

    /** 非压缩包文件：提供“解压本文件”等操作。 */
    private void showEntryMenu(final ScreenEntry entry) {
        final Frame f = stack.peek();
        final String target = fullPath(f.prefix, entry.name);
        new MaterialAlertDialogBuilder(this)
                .setTitle(entry.name)
                .setItems(new String[]{getString(R.string.zip_extract_entry),
                        getString(R.string.cl_path)}, (d, w) -> {
                            if (w == 0) {
                                promptExtractSingle(target, entry.name);
                            } else {
                                File tmp = new File(getCacheDir(), entry.name);
                                TaskExecutor.get().io().execute(() -> {
                                    try {
                                        ArchiveManager.get().extractZipEntryToFile(f.zip, target, tmp);
                                        runOnUiThread(() -> com.alltoolbox.fops.ShareUtil
                                                .copyPath(ZipBrowserActivity.this, tmp.getAbsolutePath()));
                                    } catch (Exception e) {
                                        runOnUiThread(() -> toast("复制路径失败：" + e.getMessage()));
                                    }
                                });
                            }
                        })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 解压当前层到用户指定目录。 */
    private void promptExtract(String title) {
        promptExtractDir(title, "", this::extractCurrentLayer);
    }

    private void extractCurrentLayer(final File destDir) {
        final Frame f = stack.peek();
        Toast.makeText(this, "正在解压…", Toast.LENGTH_SHORT).show();
        ArchiveManager.get().extractZipPrefixAsync(f.zip, f.prefix, destDir, null,
                () -> runOnUiThread(() ->
                        toast(getString(R.string.zip_extract_done, destDir.getAbsolutePath()))),
                e -> runOnUiThread(() ->
                        toast(getString(R.string.zip_extract_failed, e.getMessage()))));
    }

    /** 解压当前选中的单个文件到指定目录。 */
    private void promptExtractSingle(final String targetPath, final String fileName) {
        promptExtractDir(getString(R.string.zip_extract_entry_dialog), "", destDir -> {
            final Frame f = stack.peek();
            final File out = new File(destDir, fileName);
            Toast.makeText(this, "正在解压…", Toast.LENGTH_SHORT).show();
            TaskExecutor.get().io().execute(() -> {
                try {
                    ArchiveManager.get().extractZipEntryToFile(f.zip, targetPath, out);
                    runOnUiThread(() ->
                            toast(getString(R.string.zip_extract_done, out.getAbsolutePath())));
                } catch (Exception e) {
                    runOnUiThread(() ->
                            toast(getString(R.string.zip_extract_failed, e.getMessage())));
                }
            });
        });
    }

    /** 让用户输入一个目标目录，默认放到系统下载目录。 */
    private void promptExtractDir(String title, String hint, java.util.function.Consumer<File> onPicked) {
        final File download = android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_DOWNLOADS);
        String base = stripExt(originalZipName);
        final EditText et = new EditText(this);
        et.setText(new File(download, base + "_解压").getAbsolutePath());
        new MaterialAlertDialogBuilder(this)
                .setTitle(title)
                .setMessage(getString(R.string.zip_extract_dialog))
                .setView(et)
                .setNegativeButton("取消", null)
                .setPositiveButton("解压", (d, w) -> {
                    String path = et.getText().toString().trim();
                    if (path.isEmpty()) {
                        toast("目录不能为空");
                        return;
                    }
                    onPicked.accept(new File(path));
                })
                .show();
    }

    private String stripExt(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    /** 后台读取当前虚拟目录，刷新列表。 */
    private void load() {
        final Frame f = stack.peek();
        final Frame displayed = f;
        File dir = f.zip;
        // 显示当前所在的“虚拟路径”
        String display = f.prefix == null || f.prefix.isEmpty()
                ? dir.getName()
                : dir.getName() + "/" + f.prefix;
        pathView.setText(display);

        TaskExecutor.get().io().execute(() -> {
            List<ScreenEntry> out = new ArrayList<>();
            boolean isTempChild = dir != null
                    && dir.getAbsolutePath().startsWith(getCacheDir().getAbsolutePath());
            try {
                List<ArchiveManager.ZipEntryInfo> entries = ArchiveManager.get().listZipAt(dir, f.prefix);
                for (ArchiveManager.ZipEntryInfo e : entries) {
                    // 嵌套“包中包”文件条目需要特殊标记，避免把临时子包再次视为可进入
                    out.add(new ScreenEntry(e.name, e.directory, e.size, isTempChild));
                }
            } catch (Exception e) {
                runOnUiThread(() -> toast("读取压缩包失败：" + e.getMessage()));
            }
            final List<ScreenEntry> result = out;
            runOnUiThread(() -> {
                // 读取期间用户可能已变换层级：仅在仍是同一条帧时应用结果
                if (stack.peek() != displayed) return;
                adapter.submit(result);
                emptyView.setVisibility(result.isEmpty() ? View.VISIBLE : View.GONE);
            });
        });
    }

    private void toast(String msg) {
        if (msg == null) return;
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    // ------------------------------------------------------------------
    // 列表适配器
    // ------------------------------------------------------------------

    private static final class EntryAdapter
            extends RecyclerView.Adapter<EntryAdapter.Holder> {

        interface Listener {
            void onOpen(ScreenEntry entry);
        }

        private final List<ScreenEntry> items = new ArrayList<>();
        private final Listener listener;

        EntryAdapter(Listener listener) {
            this.listener = listener;
        }

        void submit(List<ScreenEntry> data) {
            items.clear();
            items.addAll(data);
            notifyDataSetChanged();
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_file_list, parent, false);
            return new Holder(v);
        }

        @Override
        public void onBindViewHolder(@NonNull Holder h, int position) {
            final ScreenEntry e = items.get(position);
            h.name.setText(e.name);
            if (e.directory) {
                h.info.setText("文件夹");
                h.icon.setImageResource(R.drawable.ic_folder);
            } else {
                h.info.setText(FileAdapter.formatSize(e.size));
                h.icon.setImageResource(iconFor(e.name));
            }
            h.itemView.setOnClickListener(v -> listener.onOpen(e));
            h.itemView.setOnLongClickListener(v -> {
                listener.onOpen(e);
                return true;
            });
        }

        private int iconFor(String name) {
            String n = name.toLowerCase(Locale.ROOT);
            if (n.endsWith(".zip") || n.endsWith(".jar")) return R.drawable.ic_archive;
            if (n.endsWith(".apk")) return R.drawable.ic_apk;
            if (n.endsWith(".txt") || n.endsWith(".log") || n.endsWith(".xml")
                    || n.endsWith(".json") || n.endsWith(".java")) return R.drawable.ic_text;
            if (n.endsWith(".pdf")) return R.drawable.ic_pdf;
            return R.drawable.ic_file;
        }

        static final class Holder extends RecyclerView.ViewHolder {
            final ImageView icon;
            final TextView name;
            final TextView info;
            Holder(@NonNull View v) {
                super(v);
                icon = v.findViewById(R.id.f_icon);
                name = v.findViewById(R.id.f_name);
                info = v.findViewById(R.id.f_info);
            }
        }
    }
}