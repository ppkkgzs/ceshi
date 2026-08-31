package com.alltoolbox.fbrowser;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.alltoolbox.core.permission.Permissions;
import com.alltoolbox.core.permission.Root;
import com.alltoolbox.fbrowser.model.FileInfo;
import com.alltoolbox.fops.FileOpsController;
import com.alltoolbox.fops.ShareUtil;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 文件浏览主界面。
 * 功能：列表/网格切换、文件夹优先、路径点击跳转、隐藏文件开关、
 * 搜索过滤、长按多选 + 复制/剪切/粘贴/删除/重命名/新建/批量重命名/属性/分享。
 *
 * Android 10+ 外部目录写入需 SAF / MANAGE_EXTERNAL_STORAGE，此处通过权限门控。
 */
public class FileBrowserFragment extends Fragment {

    public static final String ARG_PATH = "path";

    private FileBrowserViewModel viewModel;
    private RecyclerView recycler;
    private TextView emptyView;
    private LinearLayout pathBar;
    private LinearLayout selectionBar;
    private ImageButton toggleView;
    private EditText searchInput;
    private FileAdapter adapter;
    private GridLayoutManager layoutManager;

    private boolean gridMode = false;

    // 权限请求
    private ActivityResultLauncher<String> storagePermissionLauncher;
    private ActivityResultLauncher<Intent> allFilesAccessLauncher;
    private ActivityResultLauncher<Uri> docTreeLauncher;

    /** 底栏等宿主监听目录变化，用于自动切换图标动画。 */
    private Runnable pathChangeListener;

    public void setPathChangeListener(Runnable r) {
        this.pathChangeListener = r;
    }

    public String getCurrentPathString() {
        String p = viewModel != null && viewModel.getCurrentPath() != null
                ? viewModel.getCurrentPath().getValue() : null;
        return p != null ? p : "/";
    }

    public static FileBrowserFragment newInstance(String path) {
        FileBrowserFragment f = new FileBrowserFragment();
        Bundle b = new Bundle();
        b.putString(ARG_PATH, path);
        f.setArguments(b);
        return f;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        viewModel = new ViewModelProvider(this).get(FileBrowserViewModel.class);

        storagePermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(),
                granted -> {
                    if (granted) reload();
                    else toast("需要存储权限");
                });
        allFilesAccessLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> reload());
        docTreeLauncher = registerForActivityResult(
                new ActivityResultContracts.OpenDocumentTree(),
                treeUri -> {
                    // SAF tree 目录读写：保存授权并挂载（本版通过权限门控后 File 直读）
                    if (treeUri != null) {
                        getContext().getContentResolver()
                                .takePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                                        | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                        toast("已授权目录");
                    }
                });
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_file_browser, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        recycler = view.findViewById(R.id.file_recycler);
        emptyView = view.findViewById(R.id.empty_view);
        pathBar = view.findViewById(R.id.path_bar);
        selectionBar = view.findViewById(R.id.selection_bar);
        toggleView = view.findViewById(R.id.toggle_view);
        searchInput = view.findViewById(R.id.search_input);

        layoutManager = new GridLayoutManager(getContext(), 1);
        recycler.setLayoutManager(layoutManager);
        adapter = new FileAdapter(new ArrayList<>(), gridMode, callbacks());
        recycler.setAdapter(adapter);

        // 视图切换
        toggleView.setOnClickListener(v -> {
            gridMode = !gridMode;
            viewModel.setGridMode(gridMode);
            layoutManager.setSpanCount(gridMode ? 3 : 1);
            adapter = new FileAdapter(viewModel.getFiles().getValue() != null
                    ? viewModel.getFiles().getValue() : new ArrayList<>(), gridMode, callbacks());
            recycler.setAdapter(adapter);
        });

        // 隐藏文件开关
        view.findViewById(R.id.toggle_hidden).setOnClickListener(v -> {
            viewModel.toggleShowHidden();
            reload();
        });

        // 搜索过滤
        searchInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
                viewModel.setFilter(s.toString());
            }

            @Override public void afterTextChanged(Editable s) { }
        });

        // 多选操作栏
        view.findViewById(R.id.act_paste).setOnClickListener(v -> pasteCurrent());
        view.findViewById(R.id.act_delete).setOnClickListener(v -> deleteSelected());
        view.findViewById(R.id.act_more).setOnClickListener(v -> showMoreMenu());

        // 数据观察
        viewModel.getFiles().observe(getViewLifecycleOwner(), list -> {
            emptyView.setVisibility(list == null || list.isEmpty() ? View.VISIBLE : View.GONE);
            if (recycler.getAdapter() != null && recycler.getAdapter() instanceof FileAdapter) {
                ((FileAdapter) recycler.getAdapter()).submit(list);
            }
        });
        viewModel.getCurrentPath().observe(getViewLifecycleOwner(), p -> buildPathBar(p));

        // 初始目录
        String argPath = getArguments() != null ? getArguments().getString(ARG_PATH) : null;
        ensureStoragePermission();
        if (argPath != null) {
            viewModel.setRoot(new File(argPath));
        } else {
            File[] roots = Permissions.getBrowseableRoots(requireContext());
            viewModel.setRoot(roots.length > 0 ? roots[0] : new File("/"));
        }
    }

    private FileAdapter.Listener callbacks() {
        return new FileAdapter.Listener() {
            @Override
            public void onOpen(FileInfo file) {
                if (file.isDirectory()) {
                    viewModel.navigateTo(file.getFile());
                } else {
                    onOpenFile(file);
                }
            }

            @Override
            public void onLongPress(FileInfo file) {
                // adapter 已把该项加入选择集
            }

            @Override
            public void onSelectionChanged(int count) {
                updateSelectionMode(count);
            }
        };
    }

    private void updateSelectionMode(int count) {
        selectionBar.setVisibility(count > 0 ? View.VISIBLE : View.GONE);
    }

    private List<FileInfo> findSelected() {
        return adapter.getSelectedItems();
    }

    private List<File> toFiles(List<FileInfo> infos) {
        List<File> files = new ArrayList<>();
        for (FileInfo fi : infos) files.add(fi.getFile());
        return files;
    }

    private void pasteCurrent() {
        File cur = new File(viewModel.getCurrentPath().getValue() != null
                ? viewModel.getCurrentPath().getValue() : "/");
        if (!FilesWritable(cur)) {
            requestStorageAccess();
            return;
        }
        FileOpsController.get().pasteAsync(cur, null,
                () -> getActivity().runOnUiThread(this::reload),
                e -> getActivity().runOnUiThread(() -> toast("粘贴失败: " + e.getMessage())));
    }

    private void deleteSelected() {
        List<File> files = toFiles(findSelected());
        if (files.isEmpty()) return;
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle("删除")
                .setMessage("确定删除 " + files.size() + " 个项目？删除后将移入回收站/删除。")
                .setNegativeButton("取消", null)
                .setPositiveButton("删除", (d, w) -> {
                    FileOpsController.get().delete(files);
                    exitSelection();
                    reload();
                }).show();
    }

    private void showMoreMenu() {
        List<File> sel = toFiles(findSelected());
        String[] items = {"复制", "剪切", "重命名", "新建文件夹", "新建文件",
                "批量重命名", "属性", "分享", "压缩", "解压", "复制路径", "高级工具"};
        new MaterialAlertDialogBuilder(requireContext())
                .setItems(items, (d, w) -> {
                    switch (w) {
                        case 0:
                            FileOpsController.get().setClip(sel, false);
                            toast("已复制 " + sel.size() + " 项");
                            exitSelection();
                            break;
                        case 1:
                            FileOpsController.get().setClip(sel, true);
                            toast("已剪切 " + sel.size() + " 项");
                            exitSelection();
                            break;
                        case 2:
                            renameSelected();
                            break;
                        case 3:
                            createFolderDialog();
                            break;
                        case 4:
                            createFileDialog();
                            break;
                        case 5:
                            batchRenameSelected();
                            break;
                        case 6:
                            showProperties();
                            break;
                        case 7:
                            ShareUtil.shareFiles(requireContext(), sel);
                            break;
                        case 8:
                            compressSelected(sel);
                            break;
                        case 9:
                            decompressSelected(sel);
                            break;
                        case 10:
                            if (!sel.isEmpty())
                                ShareUtil.copyPath(requireContext(), sel.get(0).getAbsolutePath());
                            break;
                        case 11:
                            showAdvancedTools(sel);
                            break;
                    }
                }).show();
    }

    /** 软件内压缩：把选中项打包为 zip 到当前目录（不跳转外部）。 */
    private void compressSelected(List<File> sel) {
        if (sel.isEmpty()) {
            toast("请先选中要压缩的项目");
            return;
        }
        File cur = currentDir();
        if (!FilesWritable(cur)) {
            requestStorageAccess();
            return;
        }
        String defName = sel.size() == 1
                ? sel.get(0).getName() + ".zip"
                : cur.getName() + ".zip";
        final EditText et = new EditText(requireContext());
        et.setText(defName);
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle("压缩为 ZIP")
                .setMessage("将在当前目录下创建压缩包")
                .setView(et)
                .setNegativeButton("取消", null)
                .setPositiveButton("压缩", (d, w) -> {
                    String name = et.getText().toString().trim();
                    if (name.isEmpty()) name = defName;
                    if (!name.toLowerCase().endsWith(".zip")) name += ".zip";
                    final File zip = new File(cur, name);
                    toast("正在压缩…");
                    com.alltoolbox.archive.ArchiveManager.get().compressZipAsync(
                            new ArrayList<>(sel), zip, null,
                            () -> getActivity().runOnUiThread(() -> {
                                toast("压缩完成：" + zip.getName());
                                exitSelection();
                                reload();
                            }),
                            e -> getActivity().runOnUiThread(
                                    () -> toast("压缩失败: " + e.getMessage())));
                }).show();
    }

    /** 软件内解压：把选中的压缩包解压到当前目录（不跳转外部）。 */
    private void decompressSelected(List<File> sel) {
        if (sel.size() != 1) {
            toast("请单选一个压缩包");
            return;
        }
        final File arc = sel.get(0);
        String ext = com.alltoolbox.core.file.FileUtil.getExtension(arc.getName()).toLowerCase();
        if (!(ext.equals("zip") || ext.equals("7z") || ext.equals("tar"))) {
            toast("仅支持解压 ZIP / 7Z / TAR");
            return;
        }
        File cur = currentDir();
        if (!FilesWritable(cur)) {
            requestStorageAccess();
            return;
        }
        String defDir = arc.getName().substring(0, arc.getName().lastIndexOf('.'));
        final EditText et = new EditText(requireContext());
        et.setText(defDir);
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle("解压到目录")
                .setMessage("将在当前目录下创建文件夹并解压")
                .setView(et)
                .setNegativeButton("取消", null)
                .setPositiveButton("解压", (d, w) -> {
                    String dirName = et.getText().toString().trim();
                    if (dirName.isEmpty()) dirName = defDir;
                    final File dest = new File(cur, dirName);
                    toast("正在解压…");
                    com.alltoolbox.archive.ArchiveManager.get().decompressAsync(
                            arc, dest, null,
                            () -> getActivity().runOnUiThread(() -> {
                                toast("解压完成，见 " + dest.getName());
                                exitSelection();
                                reload();
                            }),
                            e -> getActivity().runOnUiThread(
                                    () -> toast("解压失败: " + e.getMessage())));
                }).show();
    }

    /** 对选中的单个文件提供 APK 逆向/编辑等高级工具。 */
    private void showAdvancedTools(List<File> sel) {
        if (sel.size() != 1) {
            toast("请单选一个文件");
            return;
        }
        File f = sel.get(0);
        String ext = com.alltoolbox.core.file.FileUtil.getExtension(f.getName());
        String[] items;
        if (ext.equals("apk")) {
            items = new String[]{"查看签名", "反编译"};
        } else if (ext.equals("so")) {
            items = new String[]{"十六进制补丁"};
        } else if (ext.equals("xml")) {
            items = new String[]{"编辑 Manifest"};
        } else {
            toast("该文件暂无高级工具");
            return;
        }
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle("高级工具")
                .setItems(items, (d, w) -> {
                    if (ext.equals("apk")) {
                        Intent i;
                        if (w == 0) {
                            i = new Intent(requireContext(), com.alltoolbox.apktools.tool.SignatureActivity.class);
                        } else {
                            i = new Intent(requireContext(), com.alltoolbox.apktools.tool.DecompileActivity.class);
                        }
                        i.putExtra(com.alltoolbox.apktools.tool.SignatureActivity.EXTRA_PATH, f.getAbsolutePath());
                        i.putExtra(com.alltoolbox.apktools.tool.DecompileActivity.EXTRA_PATH, f.getAbsolutePath());
                        startActivity(i);
                    } else if (ext.equals("so")) {
                        Intent i = new Intent(requireContext(), com.alltoolbox.apktools.tool.SoPatchActivity.class);
                        i.putExtra(com.alltoolbox.apktools.tool.SoPatchActivity.EXTRA_PATH, f.getAbsolutePath());
                        startActivity(i);
                    } else {
                        // Manifest 编辑：拷入工作区再编辑
                        File work = new File(requireContext().getCacheDir(), "manifest_" + f.getName());
                        try {
                            java.io.FileInputStream in = new java.io.FileInputStream(f);
                            java.io.FileOutputStream out = new java.io.FileOutputStream(work);
                            byte[] b = new byte[8192];
                            int n;
                            while ((n = in.read(b)) != -1) out.write(b, 0, n);
                            in.close();
                            out.close();
                        } catch (Exception ignored) {
                        }
                        Intent i = new Intent(requireContext(), com.alltoolbox.apktools.tool.ManifestEditActivity.class);
                        i.putExtra(com.alltoolbox.apktools.tool.ManifestEditActivity.EXTRA_SRC, f.getAbsolutePath());
                        i.putExtra(com.alltoolbox.apktools.tool.ManifestEditActivity.EXTRA_DEST, work.getAbsolutePath());
                        startActivity(i);
                    }
                    // 关闭选择态
                    exitSelection();
                }).show();
    }

    private void renameSelected() {
        List<File> sel = toFiles(findSelected());
        if (sel.size() != 1) return;
        File target = sel.get(0);
        final EditText et = new EditText(requireContext());
        et.setText(target.getName());
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle("重命名")
                .setView(et)
                .setNegativeButton("取消", null)
                .setPositiveButton("确定", (d, w) -> {
                    FileOpsController.get().rename(target, et.getText().toString());
                    reload();
                }).show();
    }

    private void createFolderDialog() {
        final EditText et = new EditText(requireContext());
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle("新建文件夹")
                .setView(et)
                .setNegativeButton("取消", null)
                .setPositiveButton("确定", (d, w) -> {
                    File cur = currentDir();
                    FileOpsController.get().createFolder(cur, et.getText().toString());
                    reload();
                }).show();
    }

    private void createFileDialog() {
        final EditText et = new EditText(requireContext());
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle("新建文件")
                .setView(et)
                .setNegativeButton("取消", null)
                .setPositiveButton("确定", (d, w) -> {
                    File cur = currentDir();
                    FileOpsController.get().createFile(cur, et.getText().toString());
                    reload();
                }).show();
    }

    private void batchRenameSelected() {
        List<File> sel = toFiles(findSelected());
        if (sel.isEmpty()) return;
        final EditText name = new EditText(requireContext());
        name.setHint("新名称（空则保留原名）");
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle("批量重命名（序号模板）")
                .setMessage("将重命名 " + sel.size() + " 个文件为：名称_序号")
                .setView(name)
                .setNegativeButton("取消", null)
                .setPositiveButton("确定", (d, w) -> {
                    int ok = FileOpsController.get().batchRename(
                            sel, 0, 1, name.getText().toString(), "", "", "", "");
                    toast("成功重命名 " + ok + " 个文件");
                    exitSelection();
                    reload();
                }).show();
    }

    private void showProperties() {
        List<File> sel = toFiles(findSelected());
        if (sel.isEmpty()) return;
        StringBuilder sb = new StringBuilder();
        long total = 0, count = 0;
        for (File f : sel) {
            sb.append("· ").append(f.getName()).append('\n');
            if (f.isDirectory()) {
                long[] s = sizes(f);
                total += s[0];
                count += s[1];
            } else {
                total += f.length();
                count++;
            }
        }
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle("属性")
                .setMessage("选中项：" + sel.size() + "\n文件总数：" + count
                        + "\n总大小：" + FileAdapter.formatSize(total))
                .setPositiveButton("确定", null).show();
    }

    private long[] sizes(File dir) {
        long size = 0, count = 0;
        File[] ch = dir.listFiles();
        if (ch != null) {
            for (File c : ch) {
                if (c.isDirectory()) {
                    long[] sub = sizes(c);
                    size += sub[0];
                    count += sub[1];
                } else {
                    size += c.length();
                    count++;
                }
            }
        }
        return new long[]{size, count};
    }

    private File currentDir() {
        String p = viewModel.getCurrentPath().getValue();
        return new File(p != null ? p : "/");
    }

    private void exitSelection() {
        adapter.clearSelection(); // 内部回调 onSelectionChanged(0) 关闭操作栏
    }

    private void onOpenFile(FileInfo file) {
        File f = file.getFile();
        String name = f.getName();
        String ext = com.alltoolbox.core.file.FileUtil.getExtension(name);
        // 文本/代码/XML/配置 → 内置编辑器
        switch (ext) {
            case "txt": case "log": case "md": case "xml": case "json":
            case "java": case "kt": case "smali": case "c": case "cpp":
            case "py": case "js": case "sh": case "html": case "htm":
            case "css": case "yml": case "yaml": case "ini": case "conf":
            case "properties": case "gradle":
                Intent ti = new Intent(requireContext(), com.alltoolbox.editor.TextEditorActivity.class);
                ti.putExtra(com.alltoolbox.editor.TextEditorActivity.EXTRA_PATH, f.getAbsolutePath());
                startActivity(ti);
                return;
            default:
                break;
        }
        // 图片 → 内置预览
        switch (ext) {
            case "jpg": case "jpeg": case "png": case "gif":
            case "webp": case "bmp":
                Intent pi = new Intent(requireContext(), ImagePreviewActivity.class);
                pi.putExtra(ImagePreviewActivity.EXTRA_PATH, f.getAbsolutePath());
                startActivity(pi);
                return;
            default:
                break;
        }
        // 视频 → 内置播放
        switch (ext) {
            case "mp4": case "mkv": case "webm": case "avi": case "mov":
            case "wmv": case "flv": case "3gp":
                Intent vi = new Intent(requireContext(), MediaPlayActivity.class);
                vi.putExtra(MediaPlayActivity.EXTRA_PATH, f.getAbsolutePath());
                vi.putExtra(MediaPlayActivity.EXTRA_TYPE, MediaPlayActivity.TYPE_VIDEO);
                startActivity(vi);
                return;
            default:
                break;
        }
        // 音频 → 内置播放
        switch (ext) {
            case "mp3": case "wav": case "flac": case "aac": case "ogg":
            case "m4a": case "opus":
                Intent ai = new Intent(requireContext(), MediaPlayActivity.class);
                ai.putExtra(MediaPlayActivity.EXTRA_PATH, f.getAbsolutePath());
                ai.putExtra(MediaPlayActivity.EXTRA_TYPE, MediaPlayActivity.TYPE_AUDIO);
                startActivity(ai);
                return;
            default:
                break;
        }
        // 无匹配扩展名时按魔数兜底（识别被改后缀/无扩展名的文件）
        switch (com.alltoolbox.core.file.FileUtil.sniffKind(f)) {
            case IMAGE:
                Intent pi2 = new Intent(requireContext(), ImagePreviewActivity.class);
                pi2.putExtra(ImagePreviewActivity.EXTRA_PATH, f.getAbsolutePath());
                startActivity(pi2);
                return;
            case AUDIO:
                Intent ai2 = new Intent(requireContext(), MediaPlayActivity.class);
                ai2.putExtra(MediaPlayActivity.EXTRA_PATH, f.getAbsolutePath());
                ai2.putExtra(MediaPlayActivity.EXTRA_TYPE, MediaPlayActivity.TYPE_AUDIO);
                startActivity(ai2);
                return;
            case VIDEO:
                Intent vi2 = new Intent(requireContext(), MediaPlayActivity.class);
                vi2.putExtra(MediaPlayActivity.EXTRA_PATH, f.getAbsolutePath());
                vi2.putExtra(MediaPlayActivity.EXTRA_TYPE, MediaPlayActivity.TYPE_VIDEO);
                startActivity(vi2);
                return;
            default:
                break;
        }
        // 兜底：文档/归档/未知类型 → 交给系统其它应用打开
        openFallback(f);
    }

    /** 内置无法处理时，用系统其它应用打开；同时保留原"打开"提示兜底。 */
    private void openFallback(File f) {
        if (!com.alltoolbox.fops.OpenWithUtil.openWith(requireContext(), f)) {
            Toast.makeText(requireContext(), "打开: " + f.getName(), Toast.LENGTH_SHORT).show();
        }
    }

    // ---------- 路径面包屑 ----------

    private void buildPathBar(String path) {
        pathBar.removeAllViews();
        String[] segments = segmentPaths(path);
        for (String seg : segments) {
            TextView tv = new TextView(requireContext());
            String label = seg.equals("/") ? "/" : new java.io.File(seg).getName();
            tv.setText(label);
            tv.setTextSize(14);
            tv.setPadding(8, 4, 8, 4);
            tv.setTextColor(0xFF1A73E8);
            tv.setTag(seg);
            tv.setClickable(true);
            tv.setOnClickListener(v -> viewModel.navigateTo(new File((String) v.getTag())));
            pathBar.addView(tv);
        }
        if (pathChangeListener != null) pathChangeListener.run();
    }

    private String[] segmentPaths(String path) {
        List<String> segs = new ArrayList<>();
        File f = new File(path);
        List<String> reversed = new ArrayList<>();
        while (f != null) {
            reversed.add(0, f.getAbsolutePath());
            f = f.getParentFile();
        }
        for (String s : reversed) segs.add(s);
        return segs.toArray(new String[0]);
    }

    // ---------- 权限 ----------

    private void ensureStoragePermission() {
        if (Permissions.hasAllFilesAccess(requireContext())) return;
        if (Permissions.requiresAllFilesAccess()) {
            Permissions.requestAllFilesAccess(requireActivity(), allFilesAccessLauncher);
        } else if (android.os.Build.VERSION.SDK_INT >= 23) {
            storagePermissionLauncher.launch(android.Manifest.permission.READ_EXTERNAL_STORAGE);
        }
    }

    private void requestStorageAccess() {
        if (Permissions.requiresAllFilesAccess()) {
            Permissions.requestAllFilesAccess(requireActivity(), allFilesAccessLauncher);
        } else {
            Permissions.openDirectoryPicker(docTreeLauncher);
        }
    }

    private boolean FilesWritable(File dir) {
        return dir.canWrite() || !Permissions.shouldHandleViaSaf(dir)
                || Permissions.hasAllFilesAccess(requireContext());
    }

    private void reload() {
        viewModel.loadDirectory(currentDir());
    }

    // ---------- 右上角三点菜单动作 ----------

    /** 刷新当前目录。 */
    public void refreshCurrent() {
        reload();
        toast("已刷新");
    }

    /** 全选/取消全选当前目录。 */
    public void toggleSelectAll() {
        adapter.toggleSelectAll();
    }

    /** 排序方式选择。 */
    public void showSortDialog() {
        String[] items = {"名称", "大小", "修改时间", "类型"};
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle("排序方式")
                .setItems(items, (d, w) -> {
                    viewModel.setSortMode(w);
                    toast("已按" + items[w] + "排序");
                })
                .show();
    }

    /** 把当前目录加入/移出书签。 */
    public void addCurrentBookmark() {
        String p = currentDir().getAbsolutePath();
        com.alltoolbox.cleanup.BookmarkManager.get().toggle(requireContext(), p);
        boolean added = com.alltoolbox.cleanup.BookmarkManager.get().isBookmarked(requireContext(), p);
        toast(added ? "已添加书签" : "已移除书签");
    }

    private void toast(String msg) {
        if (getContext() != null) Toast.makeText(getContext(), msg, Toast.LENGTH_SHORT).show();
    }

    /** 主界面返回键联动：返回上一级目录。返回 true 表示已消费。 */
    public boolean onBackPressed() {
        String cur = viewModel.getCurrentPath().getValue();
        File cf = new File(cur != null ? cur : "/");
        File parent = cf.getParentFile();
        if (parent != null && !cf.getAbsolutePath().equals("/")) {
            viewModel.navigateTo(parent);
            return true;
        }
        return false;
    }

    // ------------------------------------------------------------------
    // 底栏联动：上一页 / 下一页 / 添加 / 回到首页 / 设为首页
    // ------------------------------------------------------------------

    /** 上一页（历史后退）。返回是否执行。 */
    public boolean goBackDir() {
        return viewModel.goBack();
    }

    /** 下一页（历史前进）。 */
    public boolean goForwardDir() {
        return viewModel.goForward();
    }

    public boolean canGoBackDir() {
        return viewModel.canGoBack();
    }

    public boolean canGoForwardDir() {
        return viewModel.canGoForward();
    }

    /** 回到设置的首页；未设置则回根目录。 */
    public void goHome() {
        String home = com.alltoolbox.core.setting.Settings.getString(
                requireContext(), com.alltoolbox.core.setting.Settings.KEY_HOME_PATH, "");
        File h = (home != null && !home.isEmpty() && new File(home).isDirectory())
                ? new File(home) : defaultRoot();
        viewModel.navigateTo(h);
    }

    /** 把当前目录设为首页。 */
    public void setCurrentAsHome() {
        String p = currentDir().getAbsolutePath();
        com.alltoolbox.core.setting.Settings.putString(
                requireContext(), com.alltoolbox.core.setting.Settings.KEY_HOME_PATH, p);
        toast("已设为首页：" + p);
    }

    private File defaultRoot() {
        File[] roots = Permissions.getBrowseableRoots(requireContext());
        return roots.length > 0 ? roots[0] : new File("/");
    }

    /** 底栏“+”号：新建文件夹或文件。 */
    public void showAddDialog() {
        String[] items = {"新建文件夹", "新建文件"};
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle("添加")
                .setItems(items, (d, w) -> {
                    if (w == 0) createFolderDialog();
                    else createFileDialog();
                }).show();
    }

    /** 带过渡动画的刷新。 */
    public void refreshWithAnimation() {
        reload();
        animateContent();
    }

    /** 内容淡入过渡动画，用于刷新/切换目录。 */
    public void animateContent() {
        if (getView() == null) return;
        View content = getView().findViewById(R.id.file_recycler);
        if (content == null) return;
        content.setAlpha(0.4f);
        content.animate().alpha(1f).setDuration(320).start();
    }
}