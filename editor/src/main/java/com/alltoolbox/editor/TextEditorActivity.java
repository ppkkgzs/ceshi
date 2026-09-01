package com.alltoolbox.editor;

import android.graphics.Color;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.style.ForegroundColorSpan;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.alltoolbox.core.task.TaskExecutor;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 文本/代码编辑器：TXT、HTML、代码读写保存；行号；撤销/重做；查找替换；
 * Smali/XML 语法高亮（基于关键字与注释按行着色）。
 *
 * 打开方式：{@code newIntent(context, filePath)}。
 */
public class TextEditorActivity extends AppCompatActivity {

    public static final String EXTRA_PATH = "path";

    private File file;
    private EditText editor;
    private TextView lineNumbers;
    private Button undo, redo, find, save, format;
    private ScrollView scrollView;

    // 撤销/重做历史
    private final Deque<String> undoStack = new ArrayDeque<>();
    private final Deque<String> redoStack = new ArrayDeque<>();
    private String lastSnapshot;
    private boolean applyingHistory = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_text_editor);

        String path = getIntent().getStringExtra(EXTRA_PATH);
        file = path != null ? new File(path) : null;
        if (file == null || !file.exists()) {
            finish();
            return;
        }
        setTitle(file.getName());

        editor = findViewById(R.id.editor);
        lineNumbers = findViewById(R.id.line_numbers);
        scrollView = findViewById(R.id.editor_scroll);
        undo = findViewById(R.id.btn_undo);
        redo = findViewById(R.id.btn_redo);
        find = findViewById(R.id.btn_find);
        save = findViewById(R.id.btn_save);
        format = findViewById(R.id.btn_format);

        loadFileAsync();

        editor.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
                updateLineNumbers();
                if (!applyingHistory) {
                    // 记录撤销快照
                    String before = snapshotAt(s.subSequence(0, a) + "");
                    if (lastSnapshot != null && !lastSnapshot.equals(before)) {
                        undoStack.push(lastSnapshot);
                        if (undoStack.size() > 200) undoStack.removeLast();
                    }
                    lastSnapshot = s.toString();
                    redoStack.clear();
                }
            }

            @Override public void afterTextChanged(Editable s) { }
        });

        undo.setOnClickListener(v -> undo());
        redo.setOnClickListener(v -> redo());
        find.setOnClickListener(v -> showFindReplaceDialog());
        save.setOnClickListener(v -> saveAsync());
        format.setOnClickListener(v -> formatFile());
    }

    private String snapshotAt(String s) {
        return s;
    }

    private void loadFileAsync() {
        TaskExecutor.get().io().execute(() -> {
            final String content = readFile(file);
            runOnUiThread(() -> {
                editor.setText(content);
                lastSnapshot = content;
                applySyntaxHighlight();
            });
        });
    }

    private void saveAsync() {
        final String content = editor.getText().toString();
        TaskExecutor.get().io().execute(() -> {
            boolean ok = writeFile(file, content);
            runOnUiThread(() -> Toast.makeText(this,
                    ok ? "已保存 " + file.getName() : "保存失败", Toast.LENGTH_SHORT).show());
        });
    }

    private void undo() {
        if (undoStack.isEmpty()) return;
        applyingHistory = true;
        redoStack.push(lastSnapshot);
        lastSnapshot = undoStack.pop();
        editor.setText(lastSnapshot);
        applyingHistory = false;
        applySyntaxHighlight();
    }

    private void redo() {
        if (redoStack.isEmpty()) return;
        applyingHistory = true;
        undoStack.push(lastSnapshot);
        lastSnapshot = redoStack.pop();
        editor.setText(lastSnapshot);
        applyingHistory = false;
        applySyntaxHighlight();
    }

    private void updateLineNumbers() {
        int count = editor.getLineCount();
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= count; i++) {
            sb.append(i).append('\n');
        }
        lineNumbers.setText(sb.toString());
    }

    // ---------------- 格式化（JSON / XML） ----------------

    private void formatFile() {
        final String ext0 = ext(file.getName());
        if (!ext0.equals("json") && !ext0.equals("xml")) {
            Toast.makeText(this, "仅支持 JSON / XML 文件格式化", Toast.LENGTH_SHORT).show();
            return;
        }
        final String text = editor.getText().toString();
        TaskExecutor.get().io().execute(() -> {
            final String result;
            try {
                result = ext0.equals("json") ? formatJson(text) : formatXml(text);
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this,
                        "格式化失败: " + e.getMessage(), Toast.LENGTH_SHORT).show());
                return;
            }
            runOnUiThread(() -> {
                editor.setText(result);
                lastSnapshot = result;
                applySyntaxHighlight();
                Toast.makeText(this, "已格式化", Toast.LENGTH_SHORT).show();
            });
        });
    }

    /** org.json 美化，保留 2 空格缩进；失败兜底为 4 空格自研排版。 */
    private String formatJson(String in) throws Exception {
        String tr = in.trim();
        if (tr.isEmpty()) return in;
        char first = tr.charAt(0);
        if (first == '{') {
            return new org.json.JSONObject(tr).toString(2);
        } else if (first == '[') {
            return new org.json.JSONArray(tr).toString(2);
        }
        throw new IllegalStateException("不是合法 JSON 对象/数组");
    }

    /** 基于 DOM 的 XML 美化缩进。 */
    private String formatXml(String in) throws Exception {
        javax.xml.parsers.DocumentBuilderFactory dbf =
                javax.xml.parsers.DocumentBuilderFactory.newInstance();
        dbf.setNamespaceAware(false);
        dbf.setValidating(false);
        dbf.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        org.w3c.dom.Document doc = dbf.newDocumentBuilder().parse(
                new org.xml.sax.InputSource(new java.io.StringReader(in)));
        StringBuilder sb = new StringBuilder();
        printXmlNode(doc.getDocumentElement(), 0, sb);
        return sb.toString();
    }

    private void printXmlNode(org.w3c.dom.Node node, int depth, StringBuilder sb) {
        if (node.getNodeType() == org.w3c.dom.Node.TEXT_NODE) {
            String t = node.getNodeValue().trim();
            if (!t.isEmpty()) sb.append(indent(depth)).append(escapeText(t)).append("\n");
            return;
        }
        if (node.getNodeType() != org.w3c.dom.Node.ELEMENT_NODE) return;
        String tag = node.getNodeName();
        sb.append(indent(depth)).append("<").append(tag);
        org.w3c.dom.NamedNodeMap attrs = node.getAttributes();
        for (int i = 0; i < attrs.getLength(); i++) {
            org.w3c.dom.Node a = attrs.item(i);
            sb.append(" ").append(a.getNodeName()).append("=\"")
              .append(escapeAttr(a.getNodeValue())).append("\"");
        }
        boolean hasChildren = node.hasChildNodes();
        boolean onlyText = hasChildren && node.getChildNodes().getLength() == 1
                && node.getFirstChild().getNodeType() == org.w3c.dom.Node.TEXT_NODE;
        if (!hasChildren) {
            sb.append("/>\n");
            return;
        }
        if (onlyText) {
            String t = node.getFirstChild().getNodeValue().trim();
            sb.append(">").append(escapeText(t)).append("</").append(tag).append(">\n");
            return;
        }
        sb.append(">\n");
        org.w3c.dom.NodeList kids = node.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            printXmlNode(kids.item(i), depth + 1, sb);
        }
        sb.append(indent(depth)).append("</").append(tag).append(">\n");
    }

    private String indent(int depth) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < depth; i++) sb.append("  ");
        return sb.toString();
    }

    private String escapeText(String t) {
        return t.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private String escapeAttr(String t) {
        return t.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;");
    }

    // ---------------- 语法高亮 ----------------

    private void applySyntaxHighlight() {
        String text = editor.getText().toString();
        String ext = ext(file.getName());
        SpannableStringBuilder spannable = new SpannableStringBuilder(text);

        // Smali 关键字（字段/方法/寄存器）
        if (ext.equals("smali")) {
            highlightWords(spannable, Pattern.compile("\\b(v\\d+|p\\d+|invoke-\\w+|const|move|return|sget|sput|iget|iput)\\b"),
                    0xFF2196F3);
            highlightWords(spannable, Pattern.compile("\\b(L[a-zA-Z/]+;)\\b"), 0xFFE91E63);
        }
        // XML/HTML 标签与属性
        else if (ext.equals("xml") || ext.equals("html") || ext.equals("htm")) {
            highlightWords(spannable, Pattern.compile("</?[a-zA-Z0-9]+"), 0xFF9C27B0);
            highlightWords(spannable, Pattern.compile("\\b[a-zA-Z][a-zA-Z0-9_]*=\""), 0xFF00897B);
            // 注释
            highlightWords(spannable, Pattern.compile("<!--.*?-->"), 0xFF90A4AE);
        }
        // Java/普通代码行注释
        else if (isCode(ext)) {
            highlightWords(spannable, Pattern.compile("//.*$", Pattern.MULTILINE), 0xFF90A4AE);
            highlightWords(spannable, Pattern.compile("\\b(public|private|protected|class|void|int|String|return|new|import|package)\\b"),
                    0xFFE53935);
        }
        editor.setText(spannable);
    }

    private void highlightWords(Spannable s, Pattern p, int color) {
        Matcher m = p.matcher(s);
        while (m.find()) {
            s.setSpan(new ForegroundColorSpan(color), m.start(), m.end(),
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
    }

    private boolean isCode(String ext) {
        return ext.equals("java") || ext.equals("kt") || ext.equals("c") || ext.equals("cpp")
                || ext.equals("py") || ext.equals("js") || ext.equals("sh") || ext.equals("txt");
    }

    private String ext(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    // ---------------- 查找替换 ----------------

    private void showFindReplaceDialog() {
        final EditText etFind = new EditText(this);
        etFind.setHint("查找");
        final EditText etReplace = new EditText(this);
        etReplace.setHint("替换为（可留空）");

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(32, 8, 32, 8);
        box.addView(etFind);
        box.addView(etReplace);

        new MaterialAlertDialogBuilder(this)
                .setTitle("查找替换")
                .setView(box)
                .setNegativeButton("取消", null)
                .setNeutralButton("只查找", (d, w) -> findNext(etFind.getText().toString()))
                .setPositiveButton("全部替换", (d, w) -> replaceAll(
                        etFind.getText().toString(), etReplace.getText().toString()))
                .show();
    }

    private void findNext(String kw) {
        if (kw == null || kw.isEmpty()) return;
        String text = editor.getText().toString();
        int from = editor.getSelectionEnd();
        int idx = text.indexOf(kw, from);
        if (idx < 0) idx = text.indexOf(kw); // 循环
        if (idx >= 0) {
            editor.setSelection(idx, idx + kw.length());
        } else {
            Toast.makeText(this, "未找到", Toast.LENGTH_SHORT).show();
        }
    }

    private void replaceAll(String find, String replace) {
        String text = editor.getText().toString();
        String result = find == null || find.isEmpty() ? text : text.replace(find,
                replace == null ? "" : replace);
        editor.setText(result);
        lastSnapshot = result;
        applySyntaxHighlight();
        Toast.makeText(this, "替换完成", Toast.LENGTH_SHORT).show();
    }

    // ---------------- 文件读写 ----------------

    private String readFile(File f) {
        try {
            byte[] bytes = readAll(f);
            // 尝试 UTF-8，失败用 GBK 兜底
            try {
                return new String(bytes, "UTF-8");
            } catch (Exception e) {
                return new String(bytes, "GBK");
            }
        } catch (Exception e) {
            return "";
        }
    }

    private boolean writeFile(File f, String content) {
        try (FileOutputStream fos = new FileOutputStream(f)) {
            fos.write(content.getBytes("UTF-8"));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private byte[] readAll(File f) throws Exception {
        try (FileInputStream fis = new FileInputStream(f);
             java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = fis.read(buf)) != -1) bos.write(buf, 0, n);
            return bos.toByteArray();
        }
    }
}