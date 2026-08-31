package com.alltoolbox.cleanup;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.alltoolbox.core.task.TaskExecutor;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 空间清理：扫描指定目录下的大文件，长按多选后删除。
 */
public class CleanupActivity extends AppCompatActivity {

    private static final long MIN_SIZE = 20L * 1024 * 1024; // 20MB

    private RecyclerView list;
    private Button deleteBtn;
    private CleanupAdapter adapter;
    private List<File> files = new ArrayList<>();
    private final Set<File> selected = new HashSet<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_cleanup);
        setTitle(R.string.cleanup_title);

        list = findViewById(R.id.cleanup_list);
        deleteBtn = findViewById(R.id.cleanup_delete);
        adapter = new CleanupAdapter();
        list.setLayoutManager(new LinearLayoutManager(this));
        list.setAdapter(adapter);

        deleteBtn.setOnClickListener(v -> confirmDelete());
        if (files.isEmpty()) {
            scan();
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(com.alltoolbox.cleanup.R.menu.menu_cleanup, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == com.alltoolbox.cleanup.R.id.cleanup_scan) {
            scan();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private List<File> roots() {
        List<File> roots = new ArrayList<>();
        File ext = getExternalFilesDir(null);
        if (ext != null) roots.add(ext);
        File downloads = android.os.Environment
                .getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS);
        if (downloads.exists()) roots.add(downloads);
        return roots;
    }

    private void scan() {
        files.clear();
        selected.clear();
        adapter.notifyDataSetChanged();
        Toast.makeText(this, R.string.cleanup_scanning, Toast.LENGTH_SHORT).show();
        TaskExecutor.get().scan().execute(() -> {
            Scanner.scanLarge(roots(), MIN_SIZE, files);
            runOnUiThread(() -> {
                adapter.notifyDataSetChanged();
                updateDeleteState();
            });
        });
    }

    private void confirmDelete() {
        if (selected.isEmpty()) return;
        // 使用系统 UI 化的确认对话框（依赖 material：此处用基础 AlertDialog）
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(R.string.cleanup_delete)
                .setMessage(R.string.cleanup_confirm)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, (d, w) -> doDelete())
                .show();
    }

    private void doDelete() {
        TaskExecutor.get().io().execute(() -> {
            int ok = 0;
            for (File f : selected) {
                if (deleteRecursive(f)) ok++;
            }
            final int fOk = ok;
            runOnUiThread(() -> {
                if (fOk == selected.size()) {
                    Toast.makeText(this,
                            getString(R.string.cleanup_deleted, fOk), Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(this, R.string.cleanup_fail, Toast.LENGTH_SHORT).show();
                }
                selected.clear();
                scan();
            });
        });
    }

    private static boolean deleteRecursive(File f) {
        if (!f.exists()) return true;
        if (f.isDirectory()) {
            File[] c = f.listFiles();
            if (c != null) for (File x : c) deleteRecursive(x);
        }
        return f.delete();
    }

    private void updateDeleteState() {
        deleteBtn.setEnabled(!selected.isEmpty());
    }

    private final class CleanupAdapter extends RecyclerView.Adapter<CleanupAdapter.VH> {

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(com.alltoolbox.cleanup.R.layout.item_cleanup, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int position) {
            File f = files.get(position);
            h.name.setText(f.getName());
            h.detail.setText(formatSize(f.length()) + "  ·  " + f.getAbsolutePath());
            h.check.setChecked(selected.contains(f));
            h.itemView.setOnClickListener(v -> {
                if (selected.contains(f)) selected.remove(f);
                else selected.add(f);
                h.check.setChecked(selected.contains(f));
                updateDeleteState();
            });
        }

        @Override
        public int getItemCount() {
            return files.size();
        }

        final class VH extends RecyclerView.ViewHolder {
            final TextView name, detail;
            final CheckBox check;

            VH(View v) {
                super(v);
                name = v.findViewById(com.alltoolbox.cleanup.R.id.item_name);
                detail = v.findViewById(com.alltoolbox.cleanup.R.id.item_detail);
                check = v.findViewById(com.alltoolbox.cleanup.R.id.item_check);
            }
        }
    }

    private static String formatSize(long b) {
        if (b >= 1024L * 1024 * 1024) return String.format(java.util.Locale.ROOT, "%.1f GB", b / 1073741824.0);
        if (b >= 1024L * 1024) return String.format(java.util.Locale.ROOT, "%.1f MB", b / 1048576.0);
        if (b >= 1024L) return String.format(java.util.Locale.ROOT, "%.1f KB", b / 1024.0);
        return b + " B";
    }
}