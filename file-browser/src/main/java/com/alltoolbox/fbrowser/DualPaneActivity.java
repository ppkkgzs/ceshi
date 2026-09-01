package com.alltoolbox.fbrowser;

import android.content.Intent;
import android.os.Bundle;
import android.view.GestureDetector;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayDeque;
import java.util.Deque;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.alltoolbox.core.permission.Permissions;
import com.alltoolbox.core.task.TaskExecutor;
import com.alltoolbox.fbrowser.model.FileInfo;
import com.alltoolbox.fops.FileOpsController;
import com.alltoolbox.fops.ShareUtil;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 双栏分屏文件管理器（类 MT 管理器）。
 * 左右两栏各自独立浏览目录，支持：
 *   - 单/双击进入目录，返回键向上一级
 *   - 长按多选：复制/删除/更多（沿用上层能力）
 *   - 中缝按钮：把一侧选中项 复制/移动 到另一侧当前目录
 *   - 点击文件：按类型路由到内置编辑器/预览/播放/APK 工具
 *
 * 数据来源未作 SAF 抽象，沿用 File 直读；Android 10+ 经“全部文件访问”门控（同单栏）。
 */
public class DualPaneActivity extends AppCompatActivity {

    private ActivityResultLauncher<String> storagePermissionLauncher;
    private ActivityResultLauncher<Intent> allFilesAccessLauncher;
    private ActivityResultLauncher<android.net.Uri> docTreeLauncher;

    private Pane left;
    private Pane right;

    /** 当前选中的栏：返回键只在选中栏内回退。 */
    private Side activeSide = Side.LEFT;

    /** 当前选中剪贴板（复制到另一侧用）。 */
    private List<File> crossClip = new ArrayList<>();
    private boolean crossCut = false;

    private enum Side { LEFT, RIGHT }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_dual_pane);
        setTitle(getString(R.string.title_dual_pane) + "（测试版）");

        androidx.appcompat.widget.Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        storagePermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(),
                granted -> { if (granted) refreshBoth(); else toast("需要存储权限"); });
        allFilesAccessLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> refreshBoth());
        docTreeLauncher = registerForActivityResult(
                new ActivityResultContracts.OpenDocumentTree(),
                treeUri -> {
                    if (treeUri != null) {
                        getContentResolver().takePersistableUriPermission(treeUri,
                                Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                        toast("已授权目录");
                    }
                });

        left = new Pane(
                findViewById(R.id.left_list), findViewById(R.id.left_path), findViewById(R.id.left_empty),
                Side.LEFT, rootFor(0));
        right = new Pane(
                findViewById(R.id.right_list), findViewById(R.id.right_path), findViewById(R.id.right_empty),
                Side.RIGHT, rootFor(1));

        View copyL2R = findViewById(R.id.copy_left_to_right);
        View copyR2L = findViewById(R.id.copy_right_to_left);
        copyL2R.setOnClickListener(v -> showCrossMenu(Side.LEFT, Side.RIGHT));
        copyR2L.setOnClickListener(v -> showCrossMenu(Side.RIGHT, Side.LEFT));

        // 底栏：选择当前操作的栏
        findViewById(R.id.tab_left).setOnClickListener(v -> setActiveSide(Side.LEFT));
        findViewById(R.id.tab_right).setOnClickListener(v -> setActiveSide(Side.RIGHT));
        // 点击某一栏空白处也可选中该栏
        findViewById(R.id.left_pane).setOnClickListener(v -> setActiveSide(Side.LEFT));
        findViewById(R.id.right_pane).setOnClickListener(v -> setActiveSide(Side.RIGHT));

        // 底栏：上一页 / 下一页 / 添加 / 回到首页（针对当前选中的栏）
        findViewById(R.id.dual_prev).setOnClickListener(v -> {
            Pane active = currentPane();
            boolean ok = active.goBack();
            if (!ok) ok = active.goUp();
            animateTab();
            toast(ok ? "上一页 " + active.currentPath : "已是第一页");
        });
        findViewById(R.id.dual_next).setOnClickListener(v -> {
            Pane active = currentPane();
            boolean ok = active.goForward();
            animateTab();
            toast(ok ? "下一页 " + active.currentPath : "已到最后一页");
        });
        findViewById(R.id.dual_add).setOnClickListener(v -> showAddDialog(currentPane()));
        findViewById(R.id.dual_home).setOnClickListener(v -> {
            Pane active = currentPane();
            String home = com.alltoolbox.core.setting.Settings.getString(
                    this, com.alltoolbox.core.setting.Settings.KEY_HOME_PATH, "");
            File h = (home != null && !home.isEmpty() && new File(home).isDirectory())
                    ? new File(home) : rootFor(0);
            active.load(h);
            toast("回到首页 " + h.getAbsolutePath());
        });

        ensureStoragePermission();
        left.load(null);
        right.load(null);
        setActiveSide(Side.LEFT);
    }

    /** 选中某一栏，并高亮该栏。返回键只在选中栏内回退。 */
    private void setActiveSide(Side side) {
        activeSide = side;
        findViewById(R.id.left_pane).setBackgroundColor(side == Side.LEFT ? 0x331A73E8 : 0x05FFFFFF);
        findViewById(R.id.right_pane).setBackgroundColor(side == Side.RIGHT ? 0x331A73E8 : 0x05FFFFFF);
        findViewById(R.id.tab_left).setSelected(side == Side.LEFT);
        findViewById(R.id.tab_right).setSelected(side == Side.RIGHT);
        animateTab();
    }

    /** 底栏选中图标/按钮的弹跳切换动画（随左右栏切换而切换）。 */
    private void animateTab() {
        animateTabButton(findViewById(R.id.tab_left));
        animateTabButton(findViewById(R.id.tab_right));
    }

    private void animateTabButton(View v) {
        if (v == null) return;
        v.animate().cancel();
        v.setScaleX(0.82f);
        v.setScaleY(0.82f);
        v.animate().scaleX(1f).scaleY(1f).setDuration(280)
                .setStartDelay(30).start();
    }

    /** 当前选中的栏对应的 Pane。 */
    private Pane currentPane() {
        return activeSide == Side.LEFT ? left : right;
    }

    /** 底栏：在当前选中目录新建文件夹/文件。 */
    private void showAddDialog(Pane pane) {
        File dir = new File(pane.currentPath);
        if (!dir.exists() || !dir.isDirectory()) {
            toast("当前目录不可用");
            return;
        }
        final File parent = dir;
        String[] options = {"新建文件夹", "新建文件"};
        new MaterialAlertDialogBuilder(this)
                .setTitle("添加到 " + parent.getAbsolutePath())
                .setItems(options, (d, w) -> promptNewName(parent, w == 0))
                .show();
    }

    private void promptNewName(final File parent, final boolean isFolder) {
        final android.widget.EditText input = new android.widget.EditText(this);
        input.setHint(isFolder ? "输入文件夹名称" : "输入文件名（含扩展名）");
        new MaterialAlertDialogBuilder(this)
                .setTitle(isFolder ? "新建文件夹" : "新建文件")
                .setView(input)
                .setNegativeButton("取消", null)
                .setPositiveButton("创建", (d, w) -> {
                    String name = input.getText().toString().trim();
                    if (name.isEmpty()) {
                        toast("名称不能为空");
                        return;
                    }
                    File target = new File(parent, name);
                    boolean ok = isFolder ? target.mkdirs() : createNew(target);
                    toast(ok ? "已创建 " + name : "创建失败");
                    refreshBoth();
                })
                .show();
    }

    static boolean createNew(File f) {
        if (f.exists()) return false;
        try {
            return f.createNewFile();
        } catch (Exception e) {
            return false;
        }
    }

    /** 左/右栏默认根目录：均为外部存储（root 时为文件系统根），切到单栏即从主界面进入。 */
    private File rootFor(int index) {
        if (Permissions.isRooted()) return new File("/");
        return android.os.Environment.getExternalStorageDirectory();
    }

    // ------------------------------------------------------------------
    // 权限
    // ------------------------------------------------------------------

    private void ensureStoragePermission() {
        if (Permissions.hasAllFilesAccess(this)) return;
        if (Permissions.requiresAllFilesAccess()) {
            Permissions.requestAllFilesAccess(this, allFilesAccessLauncher);
        } else if (android.os.Build.VERSION.SDK_INT >= 23) {
            storagePermissionLauncher.launch(android.Manifest.permission.READ_EXTERNAL_STORAGE);
        }
    }

    // ------------------------------------------------------------------
    // 中缝跨栏：复制/移动
    // ------------------------------------------------------------------

    private void showCrossMenu(Side from, Side to) {
        Pane srcPane = from == Side.LEFT ? left : right;
        List<File> sel = srcPane.selectedFiles();
        if (sel.isEmpty()) {
            toast("请先在" + (from == Side.LEFT ? "左" : "右") + "栏长按选中文件");
            return;
        }
        Pane dstPane = to == Side.LEFT ? left : right;
        File dstDir = new File(dstPane.currentPath);
        if (!dstDir.exists() || !dstDir.isDirectory()) {
            toast("目标目录不可用");
            return;
        }
        final boolean move = srcPane.isCutMode();
        new MaterialAlertDialogBuilder(this)
                .setTitle(from == Side.LEFT ? "发送到右栏" : "发送到左栏")
                .setMessage("将 " + sel.size() + " 项" + (move ? "移动" : "复制")
                        + " 到:\n" + dstDir.getAbsolutePath())
                .setNegativeButton("取消", null)
                .setPositiveButton(move ? "移动" : "复制", (d, w) -> {
                    crossClip = new ArrayList<>(sel);
                    crossCut = move;
                    FileOpsController.get().setClip(crossClip, crossCut);
                    doCrossCopy(dstDir);
                }).show();
    }

    private void doCrossCopy(File dstDir) {
        if (!dstWritable(dstDir)) {
            requestStorageAccess();
            return;
        }
        toast("正在" + (crossCut ? "移动" : "复制") + "…");
        FileOpsController.get().pasteAsync(dstDir, null,
                () -> {
                    runOnUiThread(() -> {
                        toast(crossCut ? "移动完成" : "复制完成");
                        refreshBoth();
                    });
                },
                e -> runOnUiThread(() -> toast("操作失败: " + e.getMessage())));
    }

    private boolean dstWritable(File dir) {
        return dir.canWrite() || !Permissions.shouldHandleViaSaf(dir)
                || Permissions.hasAllFilesAccess(this);
    }

    private void requestStorageAccess() {
        if (Permissions.requiresAllFilesAccess()) {
            Permissions.requestAllFilesAccess(this, allFilesAccessLauncher);
        } else {
            Permissions.openDirectoryPicker(docTreeLauncher);
        }
    }

    // ------------------------------------------------------------------
    // 刷新
    // ------------------------------------------------------------------

    private void refreshBoth() {
        left.load(left.currentPath == null ? null : new File(left.currentPath));
        right.load(right.currentPath == null ? null : new File(right.currentPath));
    }

    @Override
    public void onBackPressed() {
        // 只在选中的栏内回退（底栏左/右）
        Pane active = activeSide == Side.LEFT ? left : right;
        if (active.inSelection()) {
            active.adapter.clearSelection();
            return;
        }
        if (active.goUp()) return;
        super.onBackPressed();
    }

    @Override
    public boolean onCreateOptionsMenu(android.view.Menu menu) {
        getMenuInflater().inflate(R.menu.menu_dual_pane, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull android.view.MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_single_pane) {
            switchToSinglePane();
            return true;
        }
        if (id == R.id.action_refresh) {
            refreshBoth();
            toast("已刷新");
            return true;
        }
        if (id == R.id.action_exit) {
            finishAffinity();
            return true;
        }
        if (id == R.id.action_settings_home) {
            startActivity(new Intent().setClassName(getPackageName(), "com.alltoolbox.app.SettingsActivity"));
            return true;
        }
        if (id == R.id.action_add_bookmark) {
            Pane active = activeSide == Side.LEFT ? left : right;
            String p = active.currentPath;
            com.alltoolbox.cleanup.BookmarkManager.get().toggle(this, p);
            toast(com.alltoolbox.cleanup.BookmarkManager.get().isBookmarked(this, p)
                    ? "已添加书签" : "已移除书签");
            return true;
        }
        if (id == R.id.action_sort) {
            showSortDialog();
            return true;
        }
        if (id == R.id.action_select_all) {
            Pane active = activeSide == Side.LEFT ? left : right;
            active.adapter.selectAll();
            return true;
        }
        if (id == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void showSortDialog() {
        String[] items = {"名称", "大小", "修改时间", "类型"};
        new MaterialAlertDialogBuilder(this)
                .setTitle("排序方式")
                .setItems(items, (d, w) -> {
                    Pane active = activeSide == Side.LEFT ? left : right;
                    active.sortMode = w;
                    active.load(new File(active.currentPath));
                    toast("已按" + items[w] + "排序");
                })
                .show();
    }

    /** 切到单栏文件浏览器（回到主界面单栏）。 */
    private void switchToSinglePane() {
        // 用包名启动主界面，避免库模块对 app 的编译期依赖
        Intent i = new Intent();
        i.setClassName(getPackageName(), "com.alltoolbox.app.MainActivity");
        i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(i);
        finish();
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    // ------------------------------------------------------------------
    // 单个栏的内部实现
    // ------------------------------------------------------------------

    private static final class ListItemAdapter
            extends RecyclerView.Adapter<ListItemAdapter.Holder> {
        interface Listener {
            void onOpen(FileInfo fi);
            void onSelect(int count);
        }

        private final List<FileInfo> items = new ArrayList<>();
        private final boolean grid;
        private final Listener listener;
        private final java.util.Set<Integer> selected = new java.util.HashSet<>();

        ListItemAdapter(boolean grid, Listener listener) {
            this.grid = grid;
            this.listener = listener;
        }

        void submit(List<FileInfo> data) {
            items.clear();
            items.addAll(data);
            selected.clear();
            notifyDataSetChanged();
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        @androidx.annotation.NonNull
        @Override
        public Holder onCreateViewHolder(@androidx.annotation.NonNull ViewGroup parent, int viewType) {
            // 复用 file-browser 的条目布局以保持视觉一致
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_file_list, parent, false);
            return new Holder(v);
        }

        @Override
        public void onBindViewHolder(@androidx.annotation.NonNull Holder h, int position) {
            FileInfo fi = items.get(position);
            h.name.setText(fi.getName());
            h.info.setText(fi.isDirectory() ? "文件夹" : FileAdapter.formatSize(fi.getSize()));
            h.icon.setImageResource(iconFor(fi));
            boolean sel = selected.contains(position);
            h.itemView.setActivated(sel);
            h.itemView.setSelected(sel);
            h.itemView.setOnClickListener(v -> {
                if (!selected.isEmpty()) {
                    toggle(position);
                } else {
                    listener.onOpen(fi);
                }
            });
            h.itemView.setOnLongClickListener(v -> {
                if (!selected.contains(position)) {
                    selected.add(position);
                    notifyItemChanged(position);
                    listener.onSelect(selected.size());
                }
                return true;
            });
        }

        private void toggle(int position) {
            if (!selected.remove(position)) selected.add(position);
            notifyItemChanged(position);
            listener.onSelect(selected.size());
        }

        void clearSelection() {
            selected.clear();
            notifyDataSetChanged();
            listener.onSelect(0);
        }

        void selectAll() {
            selected.clear();
            for (int i = 0; i < items.size(); i++) selected.add(i);
            notifyDataSetChanged();
            listener.onSelect(selected.size());
        }

        List<FileInfo> selectedItems() {
            List<FileInfo> out = new ArrayList<>();
            for (int i : selected) out.add(items.get(i));
            return out;
        }

        boolean inSelection() {
            return !selected.isEmpty();
        }

        boolean isCut = false;

        private int iconFor(FileInfo fi) {
            if (fi.isDirectory()) return R.drawable.ic_folder;
            switch (fi.getKind()) {
                case IMAGE: return R.drawable.ic_image;
                case VIDEO: return R.drawable.ic_video;
                case AUDIO: return R.drawable.ic_audio;
                case APK: return R.drawable.ic_apk;
                case ARCHIVE: return R.drawable.ic_archive;
                case PDF: return R.drawable.ic_pdf;
                case DOCUMENT: return R.drawable.ic_document;
                case text: return R.drawable.ic_text;
                default: return R.drawable.ic_file;
            }
        }

        static final class Holder extends RecyclerView.ViewHolder {
            final android.widget.ImageView icon;
            final TextView name, info;
            Holder(@androidx.annotation.NonNull View v) {
                super(v);
                icon = v.findViewById(R.id.f_icon);
                name = v.findViewById(R.id.f_name);
                info = v.findViewById(R.id.f_info);
            }
        }
    }

    /** 栏：持有当前目录、列表、路径显示，独立导航。 */
    private final class Pane {
        final RecyclerView list;
        final TextView pathView;
        final TextView emptyView;
        final Side side;
        final ListItemAdapter adapter;
        final RecyclerView.LayoutManager lm;
        String currentPath;
        boolean showHidden = false;
        int sortMode = 0;
        /** 历史栈：后退/前进。 */
        final Deque<String> backStack = new ArrayDeque<>();
        final Deque<String> forwardStack = new ArrayDeque<>();

        Pane(RecyclerView list, TextView pathView, TextView emptyView, Side side, File root) {
            this.list = list;
            this.pathView = pathView;
            this.emptyView = emptyView;
            this.side = side;
            this.lm = new LinearLayoutManager(DualPaneActivity.this);
            this.list.setLayoutManager(lm);
            // 列表滚动流畅度优化：固定尺寸 + 放大缓存，减少滑动时重复绑定
            this.list.setHasFixedSize(true);
            this.list.setItemViewCacheSize(24);
            this.adapter = new ListItemAdapter(false, new ListItemAdapter.Listener() {
                @Override public void onOpen(FileInfo fi) { Pane.this.onOpen(fi); }

                @Override public void onSelect(int count) {
                    // 顶部提示可选
                }
            });
            this.list.setAdapter(adapter);
            this.currentPath = root.getAbsolutePath();

            // 横向滑动该栏即立刻切换为选中该栏；纵向滚动不拦截（保持丝滑）。
            final GestureDetector gd = new GestureDetector(DualPaneActivity.this,
                    new GestureDetector.SimpleOnGestureListener() {
                        @Override public boolean onScroll(MotionEvent e1, MotionEvent e2,
                                                          float distanceX, float distanceY) {
                            if (e1 != null && e2 != null) {
                                float dx = e2.getX() - e1.getX();
                                float dy = e2.getY() - e1.getY();
                                if (Math.abs(dx) > 14 && Math.abs(dx) > Math.abs(dy)) {
                                    setActiveSide(side);
                                }
                            }
                            return false; // 不消费，让列表正常纵向滚动
                        }

                        @Override public boolean onFling(MotionEvent e1, MotionEvent e2,
                                                         float vx, float vy) {
                            if (e1 != null && e2 != null
                                    && Math.abs(e2.getX() - e1.getX())
                                       > Math.abs(e2.getY() - e1.getY())
                                    && Math.abs(e2.getX() - e1.getX()) > 50) {
                                setActiveSide(side);
                            }
                            return true;
                        }
                    });
            this.list.setOnTouchListener((v, ev) -> gd.onTouchEvent(ev));
        }

        /** 进入目录：记录历史并加载。 */
        void navigate(@Nullable File target) {
            if (target == null) return;
            String tp = target.getAbsolutePath();
            if (!tp.equals(currentPath)) {
                backStack.push(currentPath);
                forwardStack.clear();
            }
            load(target);
        }

        boolean goBack() {
            if (backStack.isEmpty()) return false;
            String cur = currentPath;
            if (cur != null) forwardStack.push(cur);
            String p = backStack.pop();
            load(new File(p));
            return true;
        }

        boolean goForward() {
            if (forwardStack.isEmpty()) return false;
            String cur = currentPath;
            if (cur != null) backStack.push(cur);
            String p = forwardStack.pop();
            load(new File(p));
            return true;
        }

        void load(@Nullable File target) {
            final File dir = (target != null) ? target
                    : (currentPath == null ? null : new File(currentPath));
            if (dir == null) return;
            currentPath = dir.getAbsolutePath();
            pathView.setText(currentPath);
            TaskExecutor.get().io().execute(() -> {
                final List<FileInfo> out = new ArrayList<>();
                File[] children = dir.listFiles();
                if (children != null) {
                    for (File c : children) {
                        if (!showHidden && (c.isHidden() || c.getName().startsWith("."))) continue;
                        out.add(new FileInfo(c));
                    }
                }
                Collections.sort(out, (a, b) -> {
                    if (a.isDirectory() != b.isDirectory()) return a.isDirectory() ? -1 : 1;
                    switch (sortMode) {
                        case 1: return Long.compare(b.getSize(), a.getSize());
                        case 2: return Long.compare(b.getLastModified(), a.getLastModified());
                        case 3: return ext(a.getName()).compareToIgnoreCase(ext(b.getName()));
                        default: return a.getName().compareToIgnoreCase(b.getName());
                    }
                });
                runOnUiThread(() -> {
                    if (!currentPath.equals(dir.getAbsolutePath())) return;
                    adapter.submit(out);
                    emptyView.setVisibility(out.isEmpty() ? View.VISIBLE : View.GONE);
                });
            });
        }

        boolean goUp() {
            File cur = new File(currentPath);
            File parent = cur.getParentFile();
            if (parent != null && !cur.getAbsolutePath().equals("/")) {
                load(parent);
                return true;
            }
            return false;
        }

        boolean inSelection() {
            return adapter.inSelection();
        }

        List<File> selectedFiles() {
            List<File> files = new ArrayList<>();
            for (FileInfo fi : adapter.selectedItems()) files.add(fi.getFile());
            return files;
        }

        boolean isCutMode() {
            return adapter.isCut;
        }

        void onOpen(FileInfo fi) {
            adapter.clearSelection();
            if (fi.isDirectory()) {
                File target = fi.getFile();
                FileOpsController.get().setClip(new ArrayList<>(), false);
                if (side == Side.LEFT) {
                    if (adapter.isCut) adapter.isCut = false;
                    crossClip.clear();
                }
                // 进入目录即视为选中该栏
                setActiveSide(side);
                navigate(target);
            } else {
                DualPaneActivity.this.onOpenFile(fi.getFile());
            }
        }
    }

    private static String ext(String name) {
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot) : "";
    }

    // ------------------------------------------------------------------
    // 文件打开路由（与单栏文件浏览器保持一致）
    // ------------------------------------------------------------------

    private void onOpenFile(File f) {
        String name = f.getName();
        // 压缩包（含“包中包”）：进入压缩包浏览器逐层浏览
        if (com.alltoolbox.archive.ArchiveManager.isBrowseableArchive(name)) {
            startActivity(new Intent(this, ZipBrowserActivity.class)
                    .putExtra(ZipBrowserActivity.EXTRA_ZIP, f.getAbsolutePath()));
            return;
        }
        String ext = com.alltoolbox.core.file.FileUtil.getExtension(name);
        switch (ext) {
            case "txt": case "log": case "md": case "xml": case "json":
            case "java": case "kt": case "smali": case "c": case "cpp":
            case "py": case "js": case "sh": case "html": case "htm":
            case "css": case "yml": case "yaml": case "ini": case "conf":
            case "properties": case "gradle":
                startActivity(new Intent(this, com.alltoolbox.editor.TextEditorActivity.class)
                        .putExtra(com.alltoolbox.editor.TextEditorActivity.EXTRA_PATH, f.getAbsolutePath()));
                return;
            default:
                break;
        }
        switch (ext) {
            case "jpg": case "jpeg": case "png": case "gif":
            case "webp": case "bmp":
                startActivity(new Intent(this, ImagePreviewActivity.class)
                        .putExtra(ImagePreviewActivity.EXTRA_PATH, f.getAbsolutePath()));
                return;
            default:
                break;
        }
        switch (ext) {
            case "mp4": case "3gp": case "webm": case "mkv": case "avi":
                startActivity(new Intent(this, MediaPlayActivity.class)
                        .putExtra(MediaPlayActivity.EXTRA_PATH, f.getAbsolutePath())
                        .putExtra(MediaPlayActivity.EXTRA_TYPE, MediaPlayActivity.TYPE_VIDEO));
                return;
            default:
                break;
        }
        switch (ext) {
            case "mp3": case "wav": case "flac": case "aac": case "ogg":
                startActivity(new Intent(this, MediaPlayActivity.class)
                        .putExtra(MediaPlayActivity.EXTRA_PATH, f.getAbsolutePath())
                        .putExtra(MediaPlayActivity.EXTRA_TYPE, MediaPlayActivity.TYPE_AUDIO));
                return;
            default:
                break;
        }
        if (ext.equals("apk")) {
            showApkTools(f);
            return;
        }
        // 文档/PDF/归档 → 交由系统其它应用打开（必要时系统会给出选择器）
        switch (ext) {
            case "pdf": case "doc": case "docx": case "xls": case "xlsx":
            case "ppt": case "pptx": case "zip": case "rar": case "7z":
            case "tar": case "gz": case "bz2": case "txt":
                openFallback(f);
                return;
            default:
                break;
        }
        // 无匹配时按魔数兜底（识别被改后缀/无后缀文件）
        switch (com.alltoolbox.core.file.FileUtil.sniffKind(f)) {
            case IMAGE:
                startActivity(new Intent(this, ImagePreviewActivity.class)
                        .putExtra(ImagePreviewActivity.EXTRA_PATH, f.getAbsolutePath()));
                return;
            case AUDIO:
                startActivity(new Intent(this, MediaPlayActivity.class)
                        .putExtra(MediaPlayActivity.EXTRA_PATH, f.getAbsolutePath())
                        .putExtra(MediaPlayActivity.EXTRA_TYPE, MediaPlayActivity.TYPE_AUDIO));
                return;
            case VIDEO:
                startActivity(new Intent(this, MediaPlayActivity.class)
                        .putExtra(MediaPlayActivity.EXTRA_PATH, f.getAbsolutePath())
                        .putExtra(MediaPlayActivity.EXTRA_TYPE, MediaPlayActivity.TYPE_VIDEO));
                return;
            default:
                break;
        }
        openFallback(f);
    }

    /** 双栏兜底：用系统其它应用打开；失败则仅提示。 */
    private void openFallback(File f) {
        if (!com.alltoolbox.fops.OpenWithUtil.openWith(this, f)) {
            toast("打开: " + f.getName());
        }
    }

    private void showApkTools(File apk) {
        String[] items = {"查看签名", "反编译/回编译", "APK 包详情", "DEX 反编译", "重签名"};
        new MaterialAlertDialogBuilder(this)
                .setTitle(apk.getName())
                .setItems(items, (d, w) -> {
                    Intent i = new Intent();
                    switch (w) {
                        case 0:
                            i.setClass(this, com.alltoolbox.apktools.tool.SignatureActivity.class);
                            break;
                        case 1:
                            i.setClass(this, com.alltoolbox.apktools.tool.DecompileActivity.class);
                            break;
                        case 2:
                            i.setClass(this, com.alltoolbox.apktools.apk.ApkInfoActivity.class);
                            break;
                        case 3:
                            i.setClass(this, com.alltoolbox.apktools.tool.DexSmaliActivity.class);
                            break;
                        case 4:
                            i.setClass(this, com.alltoolbox.apktools.tool.ReSignActivity.class);
                            break;
                        default:
                            return;
                    }
                    i.putExtra(com.alltoolbox.apktools.tool.SignatureActivity.EXTRA_PATH, apk.getAbsolutePath());
                    i.putExtra(com.alltoolbox.apktools.tool.DecompileActivity.EXTRA_PATH, apk.getAbsolutePath());
                    i.putExtra(com.alltoolbox.apktools.apk.ApkInfoActivity.EXTRA_PATH, apk.getAbsolutePath());
                    i.putExtra(com.alltoolbox.apktools.tool.DexSmaliActivity.EXTRA_PATH, apk.getAbsolutePath());
                    i.putExtra(com.alltoolbox.apktools.tool.ReSignActivity.EXTRA_PATH, apk.getAbsolutePath());
                    startActivity(i);
                }).show();
    }
}