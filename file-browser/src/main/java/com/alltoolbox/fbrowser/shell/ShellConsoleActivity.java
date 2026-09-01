package com.alltoolbox.fbrowser.shell;

import android.content.ClipData;
import android.os.Bundle;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.View;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import com.alltoolbox.core.shell.ShellRunner;
import com.alltoolbox.fbrowser.R;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * .sh 脚本执行控制台。
 *
 * 通过 {@link ShellRunner} 在后台线程执行脚本（阻塞 IO 与主线程解耦），
 * stdout 与 stderr 用不同颜色滚动输出；提供 运行/停止/复制日志/清空 按钮。
 * 脚本运行身份（Root / Shizuku / 普通 sh）由调用方弹窗确认后传入。
 */
public class ShellConsoleActivity extends AppCompatActivity {

    public static final String EXTRA_SCRIPT = "extra_script";
    public static final String EXTRA_WORKDIR = "extra_workdir";
    public static final String EXTRA_ARGS = "extra_args";
    public static final String EXTRA_ENV = "extra_env";

    private static final int C_STDOUT = 0xFFE8E8E8;
    private static final int C_STDERR = 0xFFFF6B6B;
    private static final int C_INFO = 0xFFFFD54F;

    private TextView output;
    private EditText argsInput;
    private final SpannableStringBuilder log = new SpannableStringBuilder();
    private ShellRunner.Task task;
    private boolean running = false;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_shell_console);

        Toolbar tb = findViewById(R.id.shell_toolbar);
        setSupportActionBar(tb);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        output = findViewById(R.id.shell_output);
        argsInput = findViewById(R.id.shell_args);

        findViewById(R.id.shell_run).setOnClickListener(v -> run());
        findViewById(R.id.shell_stop).setOnClickListener(v -> stopTask());
        findViewById(R.id.shell_copy).setOnClickListener(v -> copyAll());
        findViewById(R.id.shell_clear).setOnClickListener(v -> {
            log.clear();
            output.setText("");
        });

        String script = getIntent().getStringExtra(EXTRA_SCRIPT);
        if (script != null && getSupportActionBar() != null) {
            getSupportActionBar().setTitle(script);
            getSupportActionBar().setSubtitle(new File(script).getName());
        }

        String[] args = getIntent().getStringArrayExtra(EXTRA_ARGS);
        if (args != null && args.length > 0) {
            StringBuilder sb = new StringBuilder();
            for (String a : args) {
                if (sb.length() > 0) sb.append(' ');
                sb.append(a);
            }
            argsInput.setText(sb.toString());
        }

        // 展示运行身份
        ShellRunner.Env env = envFromExtra(getIntent().getStringExtra(EXTRA_ENV));
        append("\u2500 运行环境: " + envName(env) + " \u2500\n", C_INFO);
        append("工作目录: " + safeWorkDir() + "\n", C_INFO);
    }

    private ShellRunner.Env envFromExtra(String s) {
        if (s == null) return ShellRunner.bestEnv();
        try {
            return ShellRunner.Env.valueOf(s);
        } catch (Exception e) {
            return ShellRunner.bestEnv();
        }
    }

    private String safeWorkDir() {
        String wd = getIntent().getStringExtra(EXTRA_WORKDIR);
        return wd != null ? wd : "/";
    }

    private void run() {
        if (running) {
            toast("脚本正在运行中");
            return;
        }
        final String script = getIntent().getStringExtra(EXTRA_SCRIPT);
        if (script == null || !new File(script).isFile()) {
            append("\u26A0 脚本路径无效或文件不存在\n", C_STDERR);
            return;
        }
        final List<String> args = parseArgs(argsInput.getText().toString());
        final ShellRunner.Env env = envFromExtra(getIntent().getStringExtra(EXTRA_ENV));

        running = true;
        argsInput.setEnabled(false);
        append("\n$ sh " + script + (args.isEmpty() ? "" : " " + join(args)) + "\n", C_INFO);

        // ShellRunner 内部自行开线程执行，回调已切回主线程
        task = ShellRunner.runScript(new File(script), new File(safeWorkDir()), env, 0, args,
                new ShellRunner.Callback() {
                    @Override
                    public void onOutput(String chunk, boolean stderr) {
                        append(chunk, stderr ? C_STDERR : C_STDOUT);
                    }

                    @Override
                    public void onExit(int exitCode) {
                        running = false;
                        task = null;
                        argsInput.setEnabled(true);
                        append("\n[进程结束，退出码 " + exitCode + "]\n", C_INFO);
                    }
                });
    }

    private void stopTask() {
        if (task != null) {
            task.stop();
            append("\n[用户终止]\n", C_INFO);
        } else {
            toast("当前没有运行中的任务");
        }
    }

    private void copyAll() {
        android.content.ClipboardManager cm =
                (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("shell_log", log.toString()));
            toast("日志已复制");
        }
    }

    /** 追加一段彩色文本并滚动到底部。 */
    private void append(String text, int color) {
        if (text == null || text.isEmpty()) return;
        int start = log.length();
        log.append(text);
        log.setSpan(new ForegroundColorSpan(color), start, log.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        output.setText(log);
        ScrollView scroll = findViewById(R.id.shell_scroll);
        if (scroll != null) {
            scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
        }
    }

    /** 简单参数解析：支持单/双引号包裹，其余按空白切分。 */
    private static List<String> parseArgs(String s) {
        List<String> out = new ArrayList<>();
        if (s == null || s.trim().isEmpty()) return out;
        Matcher m = Pattern.compile("\"([^\"]*)\"|'([^']*)'|(\\S+)").matcher(s);
        while (m.find()) {
            if (m.group(1) != null) out.add(m.group(1));
            else if (m.group(2) != null) out.add(m.group(2));
            else out.add(m.group(3));
        }
        return out;
    }

    private static String join(List<String> args) {
        StringBuilder sb = new StringBuilder();
        for (String a : args) {
            if (sb.length() > 0) sb.append(' ');
            sb.append('\'').append(a).append('\'');
        }
        return sb.toString();
    }

    private static String envName(ShellRunner.Env env) {
        switch (env) {
            case ROOT: return "Root（su，能力最全）";
            case SHIZUKU: return "Shizuku（adb/shell 身份）";
            default: return "普通 sh（无 Root/Shizuku）";
        }
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull android.view.MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}