package com.alltoolbox.security;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.AppCompatEditText;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.alltoolbox.core.task.TaskExecutor;
import com.alltoolbox.security.R;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * AES 加密保险箱：密码解锁、AES 加密保存文件、解密查看、退出上锁。
 */
public class VaultActivity extends AppCompatActivity {

    private VaultManager vault;
    private RecyclerView list;
    private View empty;
    private Button add;
    private final List<File> items = new ArrayList<>();
    private VaultAdapter adapter;

    private ActivityResultLauncher<String> addLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_vault);
        setTitle(getString(R.string.vault_title));

        vault = VaultManager.get(this);
        list = findViewById(R.id.vault_list);
        empty = findViewById(R.id.vault_empty);
        add = findViewById(R.id.vault_add);

        adapter = new VaultAdapter();
        list.setLayoutManager(new LinearLayoutManager(this));
        list.setAdapter(adapter);

        addLauncher = registerForActivityResult(new ActivityResultContracts.GetContent(), this::encryptPicked);

        add.setOnClickListener(v -> addLauncher.launch("*/*"));
        adapter.setListener(name -> decryptAndOpen(name));

        // 首次使用：设置密码
        if (!vault.isSetup()) {
            promptSetPassword();
        } else if (!vault.isUnlocked()) {
            promptUnlock();
        } else {
            refresh();
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_vault, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == R.id.vault_lock) {
            vault.lock();
            Toast.makeText(this, getString(R.string.vault_locked), Toast.LENGTH_SHORT).show();
            promptUnlock();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // 退出即上锁
        vault.lock();
    }

    private void promptSetPassword() {
        final AppCompatEditText input = new AppCompatEditText(this);
        input.setHint(getString(R.string.vault_set_password_hint));
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.vault_init_title))
                .setMessage(getString(R.string.vault_init_message))
                .setView(input)
                .setPositiveButton(getString(R.string.vault_confirm), (d, w) -> {
                    char[] pw = input.getText().toString().toCharArray();
                    if (pw.length < 4) {
                        Toast.makeText(this, getString(R.string.vault_password_too_short), Toast.LENGTH_SHORT).show();
                        promptSetPassword();
                        return;
                    }
                    if (vault.setPassword(pw)) {
                        VaultShared.set(pw);
                        Toast.makeText(this, getString(R.string.vault_created), Toast.LENGTH_SHORT).show();
                        refresh();
                    } else {
                        Toast.makeText(this, getString(R.string.vault_set_failed), Toast.LENGTH_SHORT).show();
                        finish();
                    }
                    Arrays.fill(pw, '\0');
                })
                .setNegativeButton(getString(R.string.vault_cancel), (d, w) -> finish())
                .setCancelable(false)
                .show();
    }

    private void promptUnlock() {
        final AppCompatEditText input = new AppCompatEditText(this);
        input.setHint(getString(R.string.vault_unlock_hint));
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.vault_unlock_title))
                .setView(input)
                .setPositiveButton(getString(R.string.vault_unlock_button), (d, w) -> {
                    char[] pw = input.getText().toString().toCharArray();
                    if (vault.unlock(pw)) {
                        VaultShared.set(pw);
                        Toast.makeText(this, getString(R.string.vault_unlocked), Toast.LENGTH_SHORT).show();
                        refresh();
                    } else {
                        Toast.makeText(this, getString(R.string.vault_wrong_password), Toast.LENGTH_SHORT).show();
                        promptUnlock();
                    }
                    Arrays.fill(pw, '\0');
                })
                .setNegativeButton(getString(R.string.vault_exit), (d, w) -> finish())
                .setCancelable(false)
                .show();
    }

    private void refresh() {
        add.setVisibility(View.VISIBLE);
        items.clear();
        File[] files = vault.encDir().listFiles();
        if (files != null) items.addAll(Arrays.asList(files));
        adapter.notifyDataSetChanged();
        boolean showEmpty = items.isEmpty();
        list.setVisibility(showEmpty ? View.GONE : View.VISIBLE);
        empty.setVisibility(showEmpty ? View.VISIBLE : View.GONE);
    }

    private void encryptPicked(Uri uri) {
        if (uri == null) return;
        TaskExecutor.get().io().execute(() -> {
            try {
                String name = lastPathSegment(uri);
                File out = new File(vault.encDir(), safeName(name, 0));
                File tmp = copyToTemp(uri);
                boolean ok = AesUtil.encryptFile(tmp, out, currentPasswordFromUser());
                tmp.delete();
                runOnUiThread(() -> {
                    if (ok) {
                        Toast.makeText(this, getString(R.string.vault_saved_encrypted), Toast.LENGTH_SHORT).show();
                        refresh();
                    } else {
                        Toast.makeText(this, getString(R.string.vault_encrypt_failed), Toast.LENGTH_SHORT).show();
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, getString(R.string.vault_add_failed, e.getMessage()), Toast.LENGTH_SHORT).show());
            }
        });
    }

    private char[] currentPasswordFromUser() {
        // 重新要求输入的封装回调：此处简化 — 由 VaultActivity 实例持缓存的密码
        return VaultShared.getPassword();
    }

    private void decryptAndOpen(String name) {
        File enc = new File(vault.encDir(), name);
        if (!enc.exists()) return;
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.vault_file_encrypted_title))
                .setMessage(getString(R.string.vault_decrypt_open_confirm, name))
                .setPositiveButton(getString(R.string.vault_decrypt_view_btn), (d, w) -> {
                    final AppCompatEditText input = new AppCompatEditText(this);
                    input.setHint(getString(R.string.vault_enter_password_hint));
                    new AlertDialog.Builder(this)
                            .setTitle(getString(R.string.vault_decrypt_title))
                            .setView(input)
                            .setPositiveButton(getString(R.string.vault_decrypt_button), (d2, w2) -> doDecrypt(enc, name, input.getText().toString().toCharArray()))
                            .setNegativeButton(getString(R.string.vault_cancel), null)
                            .show();
                })
                .setNegativeButton(getString(R.string.vault_delete), (d, w) -> {
                    vault.delete(enc);
                    refresh();
                })
                .show();
    }

    private void doDecrypt(File enc, String name, char[] pw) {
        TaskExecutor.get().io().execute(() -> {
            File dec = new File(vault.decDir(), name);
            boolean ok = AesUtil.decryptFile(enc, dec, pw);
            runOnUiThread(() -> {
                if (ok) {
                    openFile(dec);
                } else {
                    Toast.makeText(this, getString(R.string.vault_decrypt_failed), Toast.LENGTH_LONG).show();
                }
            });
        });
    }

    private void openFile(File f) {
        Intent intent = new Intent(Intent.ACTION_VIEW);
        // 通过 FileProvider 分享，避免 Android 7+ FileUriExposedException
        try {
            Uri uri = androidx.core.content.FileProvider.getUriForFile(
                    this, getPackageName() + ".fileprovider", f);
            String mime = com.alltoolbox.core.file.FileUtil.getMimeType(f.getName());
            intent.setDataAndType(uri, mime);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(intent, getString(R.string.vault_open_file)));
        } catch (Exception e) {
            Toast.makeText(this, getString(R.string.vault_open_failed, e.getMessage()), Toast.LENGTH_SHORT).show();
        }
    }

    private File copyToTemp(Uri uri) throws Exception {
        String name = lastPathSegment(uri);
        File tmp = new File(getCacheDir(), "pick_" + name);
        try (InputStream in = getContentResolver().openInputStream(uri);
             FileOutputStream fos = new FileOutputStream(tmp)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) fos.write(buf, 0, n);
        }
        return tmp;
    }

    private String lastPathSegment(Uri uri) {
        String p = uri.getLastPathSegment();
        return p != null && !p.isEmpty() ? p : "file";
    }

    private String safeName(String name, int tries) {
        File f = new File(vault.encDir(), tries == 0 ? name : insertBeforeExt(name, tries));
        return f.exists() ? safeName(name, tries + 1) : f.getName();
    }

    private String insertBeforeExt(String name, int num) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) + "(" + num + ")" + name.substring(dot)
                : name + "(" + num + ")";
    }

    /** 保持密码的进程级缓存（替代复杂回调链）。 */
    static final class VaultShared {
        private static char[] pwd;
        static void set(char[] p) {
            pwd = p;
        }
        static char[] getPassword() {
            return pwd == null ? new char[0] : pwd.clone();
        }
    }

    private final class VaultAdapter extends RecyclerView.Adapter<VaultAdapter.VH> {
        private Listener listener;

        interface Listener {
            void onOpen(String name);
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_vault, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int position) {
            File f = items.get(position);
            h.name.setText(f.getName());
            h.size.setText(formatSize(f.length()));
            h.itemView.setOnClickListener(v -> {
                if (listener != null) listener.onOpen(f.getName());
            });
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        void setListener(Listener l) {
            this.listener = l;
        }

        private String formatSize(long l) {
            if (l < 1024) return l + " B";
            if (l < 1024 * 1024) return String.format("%.1f KB", l / 1024.0);
            return String.format("%.1f MB", l / (1024 * 1024.0));
        }

        final class VH extends RecyclerView.ViewHolder {
            final TextView name, size;

            VH(View v) {
                super(v);
                name = v.findViewById(R.id.vault_name);
                size = v.findViewById(R.id.vault_size);
            }
        }
    }
}