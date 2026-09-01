package com.alltoolbox.apktools.tool;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.RadioGroup;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.alltoolbox.apktools.R;
import com.alltoolbox.apktools.util.EncodeUtil;

/**
 * 编码转换工具：Unicode / Base64 / Hex 互转，用于逆向猜解字符串。
 */
public class EncodeActivity extends AppCompatActivity {

    private EditText input, output;
    private RadioGroup modeGroup;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_encode);
        setTitle("编码转换");

        input = findViewById(R.id.enc_input);
        output = findViewById(R.id.enc_output);
        modeGroup = findViewById(R.id.enc_mode);
        Button encode = findViewById(R.id.btn_encode);
        Button decode = findViewById(R.id.btn_decode);
        Button copy = findViewById(R.id.btn_copy);

        encode.setOnClickListener(v -> {
            String result = encode(input.getText().toString(), getMode());
            output.setText(result);
        });
        decode.setOnClickListener(v -> {
            String result = decode(input.getText().toString(), getMode());
            output.setText(result);
        });
        copy.setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("result", output.getText().toString()));
            Toast.makeText(this, "已复制", Toast.LENGTH_SHORT).show();
        });
    }

    private int getMode() {
        int id = modeGroup.getCheckedRadioButtonId();
        if (id == R.id.mode_base64) return EncodeUtil.BASE64;
        if (id == R.id.mode_hex_utf8) return EncodeUtil.HEX;
        if (id == R.id.mode_hex_gbk) return EncodeUtil.GBK;
        return EncodeUtil.UNICODE;
    }

    private String encode(String text, int mode) {
        switch (mode) {
            case EncodeUtil.BASE64:
                return EncodeUtil.toBase64(text);
            case EncodeUtil.HEX:
                return EncodeUtil.toHex(text, EncodeUtil.UTF);
            case EncodeUtil.GBK:
                return EncodeUtil.toHex(text, EncodeUtil.GBK);
            default:
                return EncodeUtil.toUnicode(text);
        }
    }

    private String decode(String text, int mode) {
        switch (mode) {
            case EncodeUtil.BASE64:
                return EncodeUtil.fromBase64(text);
            case EncodeUtil.HEX:
                return EncodeUtil.fromHex(text, EncodeUtil.UTF);
            case EncodeUtil.GBK:
                return EncodeUtil.fromHex(text, EncodeUtil.GBK);
            default:
                return EncodeUtil.fromUnicode(text);
        }
    }
}