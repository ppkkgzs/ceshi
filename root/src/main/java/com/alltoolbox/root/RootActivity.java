package com.alltoolbox.root;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.alltoolbox.core.permission.Root;
import com.alltoolbox.core.task.TaskExecutor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Root 增强页：显示 Root 状态，列出用户应用，支持冻结/解冻、卸载与应用数据清理。
 */
public class RootActivity extends AppCompatActivity {

    private final List<AppInfo> apps = new ArrayList<>();
    private final List<AppInfo> filtered = new ArrayList<>();
    private AppAdapter adapter;
    private TextView tvStatus;

    private static final class AppInfo {
        String label;
        String packageName;
        boolean enabled;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_root);

        Toolbar toolbar = findViewById(R.id.tbRoot);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle(R.string.root_title);
        }

        tvStatus = findViewById(R.id.tvRootStatus);
        RecyclerView rv = findViewById(R.id.rvApps);
        rv.setLayoutManager(new LinearLayoutManager(this));
        adapter = new AppAdapter();
        rv.setAdapter(adapter);

        EditText etSearch = findViewById(R.id.etSearch);
        etSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { applyFilter(); }
            @Override public void afterTextChanged(Editable s) { }
        });

        refreshStatus();
        loadApps();
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }

    private void refreshStatus() {
        TaskExecutor.get().io().execute(() -> {
            final boolean rooted = Root.isRooted();
            runOnUiThread(() -> {
                tvStatus.setText(rooted ? R.string.root_status_rooted : R.string.root_status_no_root);
                tvStatus.setTextColor(rooted ? 0xFF27AE60 : 0xFFC0392B);
            });
        });
    }

    private void loadApps() {
        PackageManager pm = getPackageManager();
        TaskExecutor.get().scan().execute(() -> {
            List<ApplicationInfo> installed = pm.getInstalledApplications(0);
            apps.clear();
            for (ApplicationInfo ai : installed) {
                if ((ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0) continue; // 仅用户应用
                AppInfo item = new AppInfo();
                item.label = ai.loadLabel(pm).toString();
                item.packageName = ai.packageName;
                int state = pm.getApplicationEnabledSetting(ai.packageName);
                item.enabled = state != PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                        && state != PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER;
                apps.add(item);
            }
            Collections.sort(apps, (a, b) -> a.label.compareToIgnoreCase(b.label));
            runOnUiThread(this::applyFilter);
        });
    }

    private void applyFilter() {
        EditText etSearch = findViewById(R.id.etSearch);
        String q = etSearch.getText().toString().trim().toLowerCase();
        filtered.clear();
        for (AppInfo a : apps) {
            if (q.isEmpty()
                    || a.label.toLowerCase().contains(q)
                    || a.packageName.toLowerCase().contains(q)) {
                filtered.add(a);
            }
        }
        adapter.notifyDataSetChanged();
    }

    /** 执行需要 root 的应用操作，完成后刷新列表。 */
    private void runAppOp(int doneMsgResKey, AppInfo app, OnAppOp op) {
        Toast.makeText(this, R.string.root_working, Toast.LENGTH_SHORT).show();
        TaskExecutor.get().io().execute(() -> {
            RootService.Result r = op.run(app.packageName);
            runOnUiThread(() -> {
                if (r.ok()) {
                    String done = getString(doneMsgResKey, app.label);
                    Toast.makeText(this, done, Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(this, getString(R.string.root_fail, trim(r.error)), Toast.LENGTH_LONG).show();
                }
                loadApps();
            });
        });
    }

    private interface OnAppOp {
        RootService.Result run(String pkg);
    }

    private String trim(String s) {
        if (s == null || s.trim().isEmpty()) return "未知错误";
        String t = s.trim();
        return t.length() > 120 ? t.substring(0, 120) : t;
    }

    // ---------------- Adapter ----------------

    private final class AppAdapter extends RecyclerView.Adapter<AppHolder> {

        @NonNull
        @Override
        public AppHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_root_app, parent, false);
            return new AppHolder(v);
        }

        @Override
        public void onBindViewHolder(@NonNull AppHolder h, int pos) {
            h.bind(filtered.get(pos));
        }

        @Override
        public int getItemCount() {
            return filtered.size();
        }
    }

    private final class AppHolder extends RecyclerView.ViewHolder {
        final TextView tvName;
        final TextView tvPackage;
        final Button btnPrimary;
        final Button btnUninstall;

        AppHolder(View v) {
            super(v);
            tvName = v.findViewById(R.id.tvAppName);
            tvPackage = v.findViewById(R.id.tvAppPackage);
            btnPrimary = v.findViewById(R.id.btnPrimary);
            btnUninstall = v.findViewById(R.id.btnUninstall);
        }

        void bind(AppInfo app) {
            tvName.setText(app.label);
            tvPackage.setText(app.packageName);

            btnPrimary.setText(app.enabled ? R.string.root_freeze : R.string.root_unfreeze);
            btnPrimary.setOnClickListener(v -> {
                int msg = app.enabled ? R.string.root_done_frozen : R.string.root_done_unfrozen;
                OnAppOp op = app.enabled ? RootService::freezePackage : RootService::unfreezePackage;
                runAppOp(msg, app, op);
            });

            btnUninstall.setOnClickListener(v ->
                    new AlertDialog.Builder(RootActivity.this)
                            .setTitle("卸载应用")
                            .setMessage("确认卸载 " + app.label + " ？此操作不可撤销。")
                            .setNegativeButton("取消", null)
                            .setNeutralButton("清除数据", (d, w) ->
                                    runAppOp(R.string.root_done_clear, app, pkg -> RootService.execRoot(60,
                                            "pm clear " + pkg)))
                            .setPositiveButton("卸载", (d, w) ->
                                    runAppOp(R.string.root_done_uninstalled, app, pkg -> RootService.execRoot(60,
                                            "pm uninstall " + pkg)))
                            .show());
        }
    }
}