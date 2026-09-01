package com.alltoolbox.fbrowser;

import android.content.Intent;
import android.content.pm.PackageManager;
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
import com.alltoolbox.core.permission.ShizukuShell;
import com.alltoolbox.fbrowser.model.FileInfo;
import com.alltoolbox.fops.FileOpsController;
import com.alltoolbox.fops.ShareUtil;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

import rikka.shizuku.Shizuku;

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
    private View restrictedView;
    private LinearLayout pathBar;
    private LinearLayout selectionBar;
    private ImageButton toggleView;
    private EditText searchInput;
    private FileAdapter adapter;
    private GridLayoutManager layoutManager;

    private boolean gridMode = false;

    /** 各目录最近一次的文件签名（名称+大小+修改时间），用于检测文件是否有增加/变化，无变化则不刷新。 */
    private final java.util.Map<String, String> dirSignatures = new java.util.HashMap<>();

    // 权限请求
    private ActivityResultLauncher<String> storagePermissionLauncher;
    private ActivityResultLauncher<Intent> allFilesAccessLauncher;
    private ActivityResultLauncher<Uri> docTreeLauncher;

    // Shizuku
    private static final int REQUEST_CODE_SHIZUKU = 10086;
    private final Shizuku.OnRequestPermissionResultListener shizukuResultListener =
            (requestCode, grantResult) -> {
                if (requestCode != REQUEST_CODE_SHIZUKU) return;
                if (getActivity() == null) return;
                getActivity().runOnUiThread(() -> {
                    if (grantResult == PackageManager.PERMISSION_GRANTED) {
                        toast(getString(R.string.shizuku_ok));
                        reload();
                    } else {
                        toast("Shizuku 授权被拒绝");
                    }
                });
            };

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
        Shizuku.addRequestPermissionResultListener(shizukuResultListener);

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
                    // SAF tree 目录读写：保存授权并重新加载
                    if (treeUri != null) {
                        getContext().getContentResolver()
                                .takePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                                        | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                        toast("已授权目录");
                        reload();
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
    public void onDestroy() {
        super.onDestroy();
        Shizuku.removeRequestPermissionResultListener(shizukuResultListener);
    }

    /** 是否已显示过一次（首次 onResume 用于初始加载，后续从其它界面返回时做变化检测刷新）。 */
    private boolean resumedOnce = false;

    @Override
    public void onResume() {
        super.onResume();
        if (resumedOnce) {
            // 从其它界面返回（如提取安装包后）：检测文件是否有增加，有则刷新，无则不刷新
            reload();
        }
        resumedOnce = true;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        recycler = view.findViewById(R.id.file_recycler);
        emptyView = view.findViewById(R.id.empty_view);
        restrictedView = view.findViewById(R.id.restricted_view);
        pathBar = view.findViewById(R.id.path_bar);
        selectionBar = view.findViewById(R.id.selection_bar);
        toggleView = view.findViewById(R.id.toggle_view);
        searchInput = view.findViewById(R.id.search_input);

        // 受限目录授权入口：SAF 授权
        view.findViewById(R.id.btn_saf_authorize)
                .setOnClickListener(v -> Permissions.openDirectoryPicker(docTreeLauncher));
        // 受限目录授权入口：Shizuku（无需 Root，adb/shell 权限）
        view.findViewById(R.id.btn_shizuku_authorize)
                .setOnClickListener(v -> authorizeShizuku());

        layoutManager = new GridLayoutManager(getContext(), 1);
        recycler.setLayoutManager(layoutManager);
        // 列表滚动流畅度优化：固定尺寸 + 放大缓存，减少滑动动画时重新绑定
        recycler.setHasFixedSize(true);
        recycler.setItemViewCacheSize(24);
        recycler.setItemAnimator(new androidx.recyclerview.widget.DefaultItemAnimator());
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
            updateEmptyUi(Boolean.TRUE.equals(viewModel.getRestricted().getValue()));
            if (recycler.getAdapter() != null && recycler.getAdapter() instanceof FileAdapter) {
                ((FileAdapter) recycler.getAdapter()).submit(list);
            }
            // 目录加载完成后，以当前磁盘状态作为变化检测的基准签名
            String p = viewModel.getCurrentPath().getValue();
            if (p != null) {
                String sig = directorySignature(new File(p));
                if (sig != null) dirSignatures.put(p, sig);
            }
        });
        viewModel.getRestricted().observe(getViewLifecycleOwner(), this::updateEmptyUi);
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
                    showOpenMethodDialog(file);
                }
            }

            @Override
            public void onLongPress(FileInfo file) {
                showFileOperationMenu(file);
            }

            @Override
            public void onSelectionChanged(int count) {
                updateSelectionMode(count);
            }
        };
    }

    /** 点击文件：弹出“打开方式”弹窗（含下载链接样式，与图例一致）。 */
    private void showOpenMethodDialog(FileInfo file) {
        String ext = com.alltoolbox.core.file.FileUtil
                .getExtension(file.getName()).toLowerCase();
        boolean archive = ext.equals("zip") || ext.equals("7z")
                || ext.equals("tar") || ext.equals("rar") || ext.equals("gz");
        String[] items = archive
                ? new String[]{"打开", "解压", "详情", "分享"}
                : new String[]{"打开", "详情", "分享"};
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(file.getName())
                .setItems(items, (d, w) -> {
                    if (archive) {
                        if (w == 0) {
                            onOpenFile(file);
                        } else if (w == 1) {
                            decompressSingle(file.getFile());
                        } else if (w == 2) {
                            showFileDetailDialog(file);
                        } else {
                            ShareUtil.shareFiles(requireContext(),
                                    java.util.Collections.singletonList(file.getFile()));
                        }
                    } else if (w == 0) {
                        onOpenFile(file);
                    } else if (w == 1) {
                        showFileDetailDialog(file);
                    } else {
                        ShareUtil.shareFiles(requireContext(),
                                java.util.Collections.singletonList(file.getFile()));
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 长按文件：弹出操作菜单（打开/详情/复制/剪切/重命名/删除/分享/多选）。 */
    private void showFileOperationMenu(FileInfo file) {
        final File f = file.getFile();
        String[] items = {"打开", "详情", "复制", "剪切", "重命名",
                "删除", "分享", "压缩", "解压", "复制路径", "多选"};
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(file.getName())
                .setItems(items, (d, w) -> {
                    switch (w) {
                        case 0:
                            if (file.isDirectory()) {
                                viewModel.navigateTo(f);
                            } else {
                                onOpenFile(file);
                            }
                            break;
                        case 1:
                            showFileDetailDialog(file);
                            break;
                        case 2:
                            FileOpsController.get().setClip(
                                    java.util.Collections.singletonList(f), false);
                            toast("已复制");
                            break;
                        case 3:
                            FileOpsController.get().setClip(
                                    java.util.Collections.singletonList(f), true);
                            toast("已剪切");
                            break;
                        case 4:
                            renameDialog(f);
                            break;
                        case 5:
                            new MaterialAlertDialogBuilder(requireContext())
                                    .setTitle("删除")
                                    .setMessage("确定删除 " + f.getName() + " ？")
                                    .setNegativeButton("取消", null)
                                    .setPositiveButton("删除", (d2, w2) -> {
                                        FileOpsController.get().delete(
                                                java.util.Collections.singletonList(f));
                                        reload();
                                    }).show();
                            break;
                        case 6:
                            ShareUtil.shareFiles(requireContext(),
                                    java.util.Collections.singletonList(f));
                            break;
                        case 7:
                            compressSelected(java.util.Collections.singletonList(f));
                            break;
                        case 8:
                            decompressSingle(f);
                            break;
                        case 9:
                            ShareUtil.copyPath(requireContext(), f.getAbsolutePath());
                            break;
                        case 10:
                            enterMultiSelect(file);
                            break;
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 详情弹窗：名称/大小/路径/时间 + 提示去打开或解压。 */
    private void showFileDetailDialog(FileInfo file) {
        StringBuilder sb = new StringBuilder();
        sb.append("名称：").append(file.getName()).append('\n');
        sb.append("大小：").append(file.isDirectory() ? "文件夹"
                : FileAdapter.formatSize(file.getSize())).append('\n');
        sb.append("路径：").append(file.getPath()).append('\n');
        sb.append("修改时间：")
                .append(new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm",
                        java.util.Locale.getDefault())
                        .format(new java.util.Date(file.getLastModified())));
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(file.isDirectory() ? "文件夹详情" : "文件详情")
                .setMessage(sb.toString())
                .setNegativeButton("关闭", null)
                .setPositiveButton("打开", (d, w) -> {
                    if (file.isDirectory()) viewModel.navigateTo(file.getFile());
                    else onOpenFile(file);
                })
                .show();
    }

    /** 操作菜单“多选”：选中单项并进入多选模式。 */
    private void enterMultiSelect(FileInfo file) {
        List<FileInfo> cur = viewModel.getFiles().getValue();
        if (cur == null) return;
        int idx = -1;
        for (int i = 0; i < cur.size(); i++) {
            if (cur.get(i).getPath().equals(file.getPath())) {
                idx = i;
                break;
            }
        }
        if (idx >= 0) adapter.selectSingle(idx);
    }

    /** 重命名单个文件。 */
    private void renameDialog(File f) {
        final EditText et = new EditText(requireContext());
        et.setText(f.getName());
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle("重命名")
                .setView(et)
                .setNegativeButton("取消", null)
                .setPositiveButton("确定", (d, w) -> {
                    FileOpsController.get().rename(f, et.getText().toString());
                    reload();
                }).show();
    }

    /** 软件内解压单个压缩包到当前目录（不跳转外部）。 */
    private void decompressSingle(File f) {
        decompressSelected(java.util.Collections.singletonList(f));
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
                    precheckAndExtract(arc, dest);
                }).show();
    }

    /**
     * 解压前前置检测（完整性 / 磁盘空间 / 是否加密 / 同名冲突）。
     * 全部通过后再进解压流程，减少底层 JNI 无差别 0x1 报错。
     */
    private void precheckAndExtract(final File arc, final File dest) {
        final android.app.Activity activity = getActivity();
        com.alltoolbox.core.task.TaskExecutor.get().io().execute(() -> {
            // 1. 压缩包完整性简单校验（存在且大小 > 0）
            try {
                com.alltoolbox.archive.SevenZipEngine.checkIntegrity(arc);
            } catch (Exception e) {
                postToast(activity, e.getMessage());
                return;
            }
            // 2. 磁盘可用空间预检测
            long need = com.alltoolbox.archive.SevenZipEngine.estimateUncompressedSize(arc);
            if (!com.alltoolbox.archive.SevenZipEngine.diskSpaceOk(dest, need)) {
                postToast(activity, com.alltoolbox.archive.SevenZipEngine.MSG_SPACE);
                return;
            }
            // 3. 探测是否加密 + 统计同名冲突（回 UI 线程让用户决定）
            boolean needPw = com.alltoolbox.archive.SevenZipEngine.requiresPassword(arc);
            int conflicts = com.alltoolbox.archive.SevenZipEngine.countConflicts(arc, "", dest);
            final boolean pw = needPw;
            final int cf = conflicts;
            postToUi(activity, () -> afterPrecheck(arc, dest, pw, cf));
        });
    }

    private void afterPrecheck(final File arc, final File dest, boolean needPw, int conflicts) {
        if (needPw) {
            // 密码输入框：先去除首尾空格，再传给解压接口
            final EditText pw = new EditText(requireContext());
            pw.setHint("压缩包密码");
            pw.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                    | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
            new MaterialAlertDialogBuilder(requireContext())
                    .setTitle("压缩包已加密")
                    .setMessage(com.alltoolbox.archive.SevenZipEngine.MSG_PASSWORD)
                    .setView(pw)
                    .setNegativeButton("取消", null)
                    .setPositiveButton("解压", (d, w) ->
                            afterPassword(arc, dest, pw.getText().toString().trim(), conflicts))
                    .show();
        } else {
            afterPassword(arc, dest, null, conflicts);
        }
    }

    private void afterPassword(final File arc, final File dest, String password, int conflicts) {
        if (conflicts > 0) {
            // 同名文件冲突：覆盖 / 跳过全部 / 取消
            new MaterialAlertDialogBuilder(requireContext())
                    .setTitle("发现 " + conflicts + " 个同名文件")
                    .setMessage("目标目录已存在同名文件，请选择处理方式")
                    .setNegativeButton("取消", null)
                    .setNeutralButton("跳过全部", (d, w) ->
                            startExtract(arc, dest, password, false))
                    .setPositiveButton("覆盖", (d, w) ->
                            startExtract(arc, dest, password, true))
                    .show();
        } else {
            startExtract(arc, dest, password, true);
        }
    }

    private void startExtract(final File arc, final File dest, String password, boolean overwrite) {
        toast("正在解压…");
        final android.app.Activity activity = getActivity();
        com.alltoolbox.archive.ArchiveManager.get().decompressAsync(
                arc, dest, password, overwrite, null,
                () -> {
                    if (activity != null) activity.runOnUiThread(() -> {
                        toast("解压完成，见 " + dest.getName());
                        exitSelection();
                        reload();
                    });
                },
                e -> {
                    if (activity != null) activity.runOnUiThread(
                            () -> toast("解压失败: " + e.getMessage()));
                });
    }

    private void postToast(final android.app.Activity activity, String msg) {
        if (activity != null) activity.runOnUiThread(() -> toast(msg));
    }

    private void postToUi(final android.app.Activity activity, Runnable r) {
        if (activity != null) activity.runOnUiThread(r);
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
        // 压缩包（含“包中包”）：进入压缩包浏览器逐层浏览
        if (com.alltoolbox.archive.ArchiveManager.isBrowseableArchive(name)) {
            Intent zi = new Intent(requireContext(), ZipBrowserActivity.class);
            zi.putExtra(ZipBrowserActivity.EXTRA_ZIP, f.getAbsolutePath());
            startActivity(zi);
            return;
        }
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

    /** 在“普通空目录”与“受限目录需授权”两种空态展示间切换。 */
    private void updateEmptyUi(Boolean restricted) {
        List<FileInfo> cur = viewModel.getFiles().getValue();
        boolean isEmpty = cur == null || cur.isEmpty();
        boolean isRestricted = Boolean.TRUE.equals(restricted);
        emptyView.setVisibility(isEmpty && !isRestricted ? View.VISIBLE : View.GONE);
        restrictedView.setVisibility(isRestricted ? View.VISIBLE : View.GONE);
    }

    private void requestStorageAccess() {
        if (Permissions.requiresAllFilesAccess()) {
            Permissions.requestAllFilesAccess(requireActivity(), allFilesAccessLauncher);
        } else {
            Permissions.openDirectoryPicker(docTreeLauncher);
        }
    }

    /** Shizuku 授权入口：未运行提示先启动；未授权发起申请；已就绪直接刷新。 */
    private void authorizeShizuku() {
        if (!ShizukuShell.isSupported()) {
            toast("Shizuku 需 Android 6.0 及以上");
            return;
        }
        if (!ShizukuShell.isOnline()) {
            toast(getString(R.string.shizuku_not_online));
            return;
        }
        if (ShizukuShell.isGranted()) {
            toast(getString(R.string.shizuku_ok));
            reload();
            return;
        }
        ShizukuShell.requestPermission(REQUEST_CODE_SHIZUKU);
    }

    private boolean FilesWritable(File dir) {
        return dir.canWrite() || !Permissions.shouldHandleViaSaf(dir)
                || Permissions.hasAllFilesAccess(requireContext());
    }

    private void reload() {
        File dir = currentDir();
        // 先检测目录文件是否有增加/变化：无变化则不刷新
        if (!directoryChanged(dir)) return;
        viewModel.loadDirectory(dir);
    }

    /**
     * 检测当前目录文件是否发生变化（对比名称+大小+修改时间的签名）。
     * 有增加/删除/改动时返回 true，无变化返回 false。
     */
    private boolean directoryChanged(File dir) {
        String path = dir.getAbsolutePath();
        String signature = directorySignature(dir);
        if (signature == null) {
            // 受限目录（Android/data、Android/obb）普通 File API 读取不到，
            // 保守起见直接视为变化，交由 loadDirectory 重新加载。
            return true;
        }
        String last = dirSignatures.get(path);
        if (signature.equals(last)) {
            return false; // 文件没有增加也没有变化，不刷新
        }
        dirSignatures.put(path, signature);
        return true;
    }

    /** 计算目录内容签名：每个条目 名称|类型|大小|修改时间，排序后拼接。 */
    private String directorySignature(File dir) {
        File[] children = dir.listFiles();
        if (children == null) return null;
        StringBuilder sb = new StringBuilder();
        File[] arr = children.clone();
        Arrays.sort(arr, Comparator.comparing(File::getName));
        for (File f : arr) {
            if (!viewModel.isShowHidden() && (f.isHidden() || f.getName().startsWith("."))) continue;
            sb.append(f.getName()).append('|')
              .append(f.isDirectory() ? 'd' : 'f')
              .append('|').append(f.length())
              .append('|').append(f.lastModified()).append(';');
        }
        return sb.toString();
    }

    // ---------- 右上角三点菜单动作 ----------

    /** 刷新当前目录。 */
    public void refreshCurrent() {
        forceReload();
        toast("已刷新");
    }

    /** 强制重新加载当前目录（跳过文件变化检测）。 */
    private void forceReload() {
        dirSignatures.remove(currentDir().getAbsolutePath());
        reload();
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
        forceReload();
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