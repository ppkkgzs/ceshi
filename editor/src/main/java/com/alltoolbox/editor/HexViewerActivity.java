package com.alltoolbox.editor;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.alltoolbox.core.task.TaskExecutor;

import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 十六进制查看器：二进制阅读、偏移跳转。每行展示 偏移 + 16字节HEX + ASCII。
 * 打开方式：{@code newIntent(context, filePath)}。
 */
public class HexViewerActivity extends AppCompatActivity {

    public static final String EXTRA_PATH = "path";

    private static final int BYTES_PER_ROW = 16;

    private byte[] data;
    private RecyclerView recycler;
    private EditText offsetInput;
    private TextView sizeText;
    private HexAdapter adapter;
    private LinearLayoutManager layoutManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_hex_viewer);

        String path = getIntent().getStringExtra(EXTRA_PATH);
        File file = path != null ? new File(path) : null;
        if (file == null || !file.exists()) {
            finish();
            return;
        }
        setTitle(file.getName());

        recycler = findViewById(R.id.hex_recycler);
        offsetInput = findViewById(R.id.offset_input);
        sizeText = findViewById(R.id.hex_size);
        Button jump = findViewById(R.id.btn_jump);

        layoutManager = new LinearLayoutManager(this);
        recycler.setLayoutManager(layoutManager);
        adapter = new HexAdapter(new ArrayList<>());
        recycler.setAdapter(adapter);

        jump.setOnClickListener(v -> jumpToOffset());

        TaskExecutor.get().io().execute(() -> {
            data = readAll(file);
            final List<HexRow> rows = buildRows(data);
            runOnUiThread(() -> {
                sizeText.setText(String.format("%d 字节", data.length));
                adapter.submit(rows);
            });
        });
    }

    private void jumpToOffset() {
        if (data == null) return;
        String input = offsetInput.getText().toString().trim();
        if (input.isEmpty()) return;
        try {
            long off = Long.parseLong(input, 16);
            if (off < 0 || off >= data.length) {
                offsetInput.setError("超出范围");
                return;
            }
            int row = (int) (off / BYTES_PER_ROW);
            layoutManager.scrollToPositionWithOffset(row, 0);
        } catch (NumberFormatException e) {
            offsetInput.setError("请输入十六进制偏移");
        }
    }

    private List<HexRow> buildRows(byte[] bytes) {
        List<HexRow> rows = new ArrayList<>();
        for (int i = 0; i < bytes.length; i += BYTES_PER_ROW) {
            int end = Math.min(i + BYTES_PER_ROW, bytes.length);
            rows.add(new HexRow(i, bytes, i, end));
        }
        return rows;
    }

    private byte[] readAll(File f) {
        try (java.io.FileInputStream fis = new java.io.FileInputStream(f);
             java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = fis.read(buf)) != -1) bos.write(buf, 0, n);
            return bos.toByteArray();
        } catch (Exception e) {
            return new byte[0];
        }
    }

    private static final class HexRow {
        final long offset;
        final String hex;
        final String ascii;

        HexRow(long offset, byte[] bytes, int from, int to) {
            this.offset = offset;
            StringBuilder h = new StringBuilder();
            StringBuilder a = new StringBuilder();
            for (int i = from; i < to; i++) {
                int b = bytes[i] & 0xFF;
                if (i > from && i % 2 == 0) h.append(' ');
                h.append(String.format("%02X", b));
                char c = (b >= 0x20 && b < 0x7F) ? (char) b : '.';
                a.append(c);
            }
            this.hex = h.toString();
            this.ascii = a.toString();
        }
    }

    private static final class HexAdapter extends RecyclerView.Adapter<HexAdapter.VH> {
        private List<HexRow> data;

        HexAdapter(List<HexRow> data) {
            this.data = data;
        }

        void submit(List<HexRow> rows) {
            this.data = rows;
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_hex, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int position) {
            HexRow r = data.get(position);
            h.offset.setText(String.format("%08X", r.offset));
            h.hex.setText(r.hex);
            h.ascii.setText(r.ascii);
        }

        @Override
        public int getItemCount() {
            return data.size();
        }

        static final class VH extends RecyclerView.ViewHolder {
            final TextView offset, hex, ascii;

            VH(View v) {
                super(v);
                offset = v.findViewById(R.id.hex_offset);
                hex = v.findViewById(R.id.hex_hex);
                ascii = v.findViewById(R.id.hex_ascii);
            }
        }
    }
}