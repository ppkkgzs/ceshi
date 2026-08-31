package com.alltoolbox.fbrowser.ftp;

import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.alltoolbox.core.task.TaskExecutor;
import com.alltoolbox.fbrowser.R;
import com.alltoolbox.fbrowser.ftp.FtpClient.FtpEntry;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * FTP 远程文件访问：连接后浏览/进入目录、下载到本机、上传本地文件。
 * 基于内置 FtpClient（raw socket），无需第三方库。
 */
public class FtpActivity extends AppCompatActivity {

    private EditText hostText, portText, userText, passText;
    private TextView pathText, emptyView;
    private FtpClient client;
    private String currentPath = "/";
    private final List<FtpEntry> entries = new ArrayList<>();
    private FtpAdapter adapter;

    private ActivityResultLauncher<String[]> uploadLauncher;
    private ActivityResultLauncher<String> saveLauncher;
    private String pendingDownloadName;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_ftp);
        setTitle("FTP 远程文件");

        hostText = findViewById(R.id.ftp_host);
        portText = findViewById(R.id.ftp_port);
        userText = findViewById(R.id.ftp_user);
        passText = findViewById(R.id.ftp_pass);
        pathText = findViewById(R.id.ftp_path);
        emptyView = findViewById(R.id.ftp_empty);
        RecyclerView list = findViewById(R.id.ftp_list);
        list.setLayoutManager(new LinearLayoutManager(this));
        adapter = new FtpAdapter();
        list.setAdapter(adapter);

        findViewById(R.id.ftp_connect).setOnClickListener(v -> connect());
        findViewById(R.id.ftp_up).setOnClickListener(v -> goUp());
        findViewById(R.id.ftp_refresh).setOnClickListener(v -> refresh());
        findViewById(R.id.ftp_upload).setOnClickListener(v -> pickUpload());

        uploadLauncher = registerForActivityResult(
                new ActivityResultContracts.OpenDocument(),
                uri -> { if (uri != null) startUpload(uri); });
        saveLauncher = registerForActivityResult(
                new ActivityResultContracts.CreateDocument("*/*"),
                uri -> { if (uri != null) saveDownload(uri); });
    }

    // ------------------------------------------------------------------
    // 连接 / 列表
    // ------------------------------------------------------------------

    private void connect() {
        final String host = hostText.getText().toString().trim();
        if (host.isEmpty()) {
            toast("请填写主机/IP");
            return;
        }
        final int port = parsePort();
        final String user = userText.getText().toString().trim();
        final String pass = passText.getText().toString();
        setConnecting(true);
        TaskExecutor.get().io().execute(() -> {
            final String[] err = {null};
            if (client == null) {
                client = new FtpClient();
            } else if (client.isConnected()) {
                client.close();
                client = new FtpClient();
            }
            try {
                client.connect(host, port, 15000);
                client.login(user, pass);
                client.sendTypeBinary();
                currentPath = "/";
            } catch (Exception e) {
                err[0] = e.getMessage();
                try { client.close(); } catch (Exception ignore) {
                }
            }
            runOnUiThread(() -> {
                setConnecting(false);
                if (err[0] != null) {
                    toast("连接失败: " + err[0]);
                } else {
                    toast("已连接");
                    refresh();
                }
            });
        });
    }

    private void refresh() {
        if (client == null || !client.isConnected()) {
            toast("尚未连接");
            return;
        }
        TaskExecutor.get().io().execute(() -> {
            final List<FtpEntry> out = new ArrayList<>();
            final String[] err = {null};
            try {
                out.addAll(client.list());
            } catch (Exception e) {
                err[0] = e.getMessage();
            }
            Collections.sort(out, (a, b) -> {
                if (a.directory != b.directory) return a.directory ? -1 : 1;
                return a.name.compareToIgnoreCase(b.name);
            });
            runOnUiThread(() -> {
                if (err[0] != null) {
                    toast("列表失败: " + err[0]);
                    return;
                }
                entries.clear();
                entries.addAll(out);
                adapter.notifyDataSetChanged();
                pathText.setText(currentPath);
                emptyView.setVisibility(entries.isEmpty() ? View.VISIBLE : View.GONE);
            });
        });
    }

    private void goUp() {
        if (client == null || !client.isConnected()) return;
        if (currentPath.equals("/")) return;
        int idx = currentPath.lastIndexOf('/');
        if (idx <= 0) {
            currentPath = "/";
        } else {
            currentPath = currentPath.substring(0, idx);
        }
        TaskExecutor.get().io().execute(() -> {
            final String[] err = {null};
            try {
                client.cwd(currentPath);
            } catch (Exception e) {
                err[0] = e.getMessage();
            }
            runOnUiThread(() -> {
                if (err[0] != null) toast("上级失败: " + err[0]);
                else refresh();
            });
        });
    }

    // ------------------------------------------------------------------
    // 上传 / 下载
    // ------------------------------------------------------------------

    private void pickUpload() {
        if (client == null || !client.isConnected()) {
            toast("尚未连接");
            return;
        }
        uploadLauncher.launch(new String[]{"*/*"});
    }

    private void startUpload(Uri uri) {
        TaskExecutor.get().io().execute(() -> {
            final String[] err = {null};
            try {
                java.io.InputStream in = getContentResolver().openInputStream(uri);
                String name = queryName(uri, "upload_" + System.currentTimeMillis());
                String remote = currentPath.equals("/") ? name : currentPath + "/" + name;
                client.upload(remote, in);
                in.close();
            } catch (Exception e) {
                err[0] = e.getMessage();
            }
            runOnUiThread(() -> {
                if (err[0] != null) toast("上传失败: " + err[0]);
                else {
                    toast("上传完成");
                    refresh();
                }
            });
        });
    }

    private void startDownload(String remoteName) {
        if (client == null || !client.isConnected()) return;
        pendingDownloadName = remoteName;
        saveLauncher.launch(remoteName);
    }

    private void saveDownload(Uri uri) {
        final String name = pendingDownloadName;
        if (name == null) return;
        TaskExecutor.get().io().execute(() -> {
            final String[] err = {null};
            try (OutputStream os = getContentResolver().openOutputStream(uri, "w")) {
                String remote = currentPath.equals("/") ? name : currentPath + "/" + name;
                client.download(remote, os);
            } catch (Exception e) {
                err[0] = e.getMessage();
            }
            runOnUiThread(() -> {
                if (err[0] != null) toast("下载失败: " + err[0]);
                else toast("下载完成");
            });
        });
    }

    private String queryName(Uri uri, String fallback) {
        String disp = null;
        android.database.Cursor c = getContentResolver().query(uri, null, null, null, null);
        if (c != null) {
            int idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
            if (idx >= 0 && c.moveToFirst()) disp = c.getString(idx);
            c.close();
        }
        return disp == null ? fallback : disp;
    }

    private void setConnecting(boolean connecting) {
        findViewById(R.id.ftp_connect).setEnabled(!connecting);
    }

    private int parsePort() {
        try {
            return Integer.parseInt(portText.getText().toString().trim());
        } catch (Exception e) {
            return FtpClient.DEFAULT_PORT;
        }
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    // ------------------------------------------------------------------
    // 列表适配器
    // ------------------------------------------------------------------

    private final class FtpAdapter extends RecyclerView.Adapter<FtpAdapter.Holder> {
        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(FtpActivity.this)
                    .inflate(R.layout.item_file_list, parent, false);
            return new Holder(v);
        }

        @Override
        public void onBindViewHolder(@NonNull Holder h, int position) {
            FtpEntry e = entries.get(position);
            h.name.setText(e.name);
            h.info.setText(e.directory ? "文件夹" : formatSize(e.size));
            h.icon.setImageResource(e.directory ? R.drawable.ic_folder : R.drawable.ic_file);
            // 点击：目录进入；文件弹菜单
            h.itemView.setOnClickListener(v -> {
                if (e.directory) {
                    enterDir(e.name);
                } else {
                    new MaterialAlertDialogBuilder(FtpActivity.this)
                            .setTitle(e.name)
                            .setItems(new String[]{"下载到本机"}, (d, w) -> startDownload(e.name))
                            .show();
                }
            });
        }

        @Override
        public int getItemCount() {
            return entries.size();
        }

        final class Holder extends RecyclerView.ViewHolder {
            final ImageView icon;
            final TextView name, info;

            Holder(@NonNull View v) {
                super(v);
                icon = v.findViewById(R.id.f_icon);
                name = v.findViewById(R.id.f_name);
                info = v.findViewById(R.id.f_info);
            }
        }
    }

    private void enterDir(String dirName) {
        final String next = currentPath.equals("/") ? "/" + dirName : currentPath + "/" + dirName;
        TaskExecutor.get().io().execute(() -> {
            final String[] err = {null};
            try {
                client.cwd(next);
                currentPath = next;
            } catch (Exception e) {
                err[0] = e.getMessage();
            }
            runOnUiThread(() -> {
                if (err[0] != null) toast("进入失败: " + err[0]);
                else refresh();
            });
        });
    }

    private static String formatSize(long size) {
        if (size < 1024) return size + " B";
        if (size < 1024 * 1024) return String.format("%.1f KB", size / 1024.0);
        if (size < 1024 * 1024 * 1024) return String.format("%.1f MB", size / (1024.0 * 1024));
        return String.format("%.1f GB", size / (1024.0 * 1024 * 1024));
    }
}