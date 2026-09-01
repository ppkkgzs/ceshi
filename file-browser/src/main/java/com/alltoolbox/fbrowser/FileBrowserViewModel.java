package com.alltoolbox.fbrowser;

import android.app.Application;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.alltoolbox.core.permission.Permissions;
import com.alltoolbox.core.permission.ShizukuShell;
import com.alltoolbox.core.task.TaskExecutor;
import com.alltoolbox.fbrowser.model.FileInfo;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 文件浏览视图模型：加载目录、排序（文件夹优先）、隐藏文件过滤、搜索过滤。
 */
public class FileBrowserViewModel extends AndroidViewModel {

    private final MutableLiveData<List<FileInfo>> files = new MutableLiveData<>();
    private final MutableLiveData<Boolean> loading = new MutableLiveData<>(false);
    private final MutableLiveData<String> currentPath = new MutableLiveData<>();
    /** 当前目录是否为受限目录且无法读取（需 SAF 授权）。 */
    private final MutableLiveData<Boolean> restricted = new MutableLiveData<>(false);

    private File root;
    private boolean showHidden = false;
    private String filter = "";
    private boolean gridMode = false;

    /** 上一页 / 下一页 历史栈。 */
    private final java.util.Deque<String> backStack = new java.util.ArrayDeque<>();
    private final java.util.Deque<String> forwardStack = new java.util.ArrayDeque<>();

    /** 排序方式：0=名称 1=大小 2=修改时间 3=类型。 */
    private int sortMode = 0;

    public FileBrowserViewModel(@NonNull Application application) {
        super(application);
    }

    public LiveData<List<FileInfo>> getFiles() {
        return files;
    }

    public LiveData<Boolean> getLoading() {
        return loading;
    }

    public LiveData<String> getCurrentPath() {
        return currentPath;
    }

    public LiveData<Boolean> getRestricted() {
        return restricted;
    }

    public void setRoot(File root) {
        this.root = root;
        navigateTo(root == null ? new File("/") : root);
    }

    public File getRoot() {
        return root;
    }

    public void navigateTo(File target) {
        String cur = currentPath.getValue();
        if (cur != null && !cur.equals(target.getAbsolutePath())) {
            backStack.push(cur);
            forwardStack.clear();
        }
        currentPath.setValue(target.getAbsolutePath());
        loadDirectory(target);
    }

    public boolean canGoBack() {
        return !backStack.isEmpty();
    }

    public boolean canGoForward() {
        return !forwardStack.isEmpty();
    }

    /** 上一页：回到历史中的前一目录。 */
    public boolean goBack() {
        if (backStack.isEmpty()) return false;
        String cur = currentPath.getValue();
        if (cur != null) forwardStack.push(cur);
        String prev = backStack.pop();
        changeTo(prev);
        return true;
    }

    /** 下一页：前进到历史中的后一目录。 */
    public boolean goForward() {
        if (forwardStack.isEmpty()) return false;
        String cur = currentPath.getValue();
        if (cur != null) backStack.push(cur);
        String next = forwardStack.pop();
        changeTo(next);
        return true;
    }

    private void changeTo(String path) {
        currentPath.setValue(path);
        loadDirectory(new File(path));
    }

    public void loadDirectory(File dir) {
        loading.setValue(true);
        TaskExecutor.get().io().execute(() -> {
            boolean restrictedDir = Permissions.isRestrictedAndroidDir(dir);
            List<FileInfo> list = new ArrayList<>();
            boolean readOk = false;
            File[] children = dir.listFiles();
            if (children != null) {
                readOk = true;
                for (File f : children) {
                    if (!showHidden && (f.isHidden() || f.getName().startsWith("."))) continue;
                    list.add(new FileInfo(f));
                }
            } else if (ShizukuShell.isReady()) {
                // 普通 File API 读取失败（受限目录），Shizuku 就绪时改用 shell 权限列出
                List<ShizukuShell.Entry> entries = ShizukuShell.listDir(dir.getAbsolutePath());
                if (entries != null) {
                    readOk = true;
                    for (ShizukuShell.Entry e : entries) {
                        if (!showHidden && (e.name.startsWith("."))) continue;
                        list.add(FileInfo.fromShizukuEntry(e));
                    }
                }
            }
            // 文件夹优先，再按排序方式（默认名称）
            Collections.sort(list, (a, b) -> {
                if (a.isDirectory() != b.isDirectory()) return a.isDirectory() ? -1 : 1;
                switch (sortMode) {
                    case 1: // 大小
                        return Long.compare(b.getSize(), a.getSize());
                    case 2: // 修改时间
                        return Long.compare(b.getLastModified(), a.getLastModified());
                    case 3: // 类型（按扩展名）
                        return ext(a.getName()).compareToIgnoreCase(ext(b.getName()));
                    default: // 名称
                        return a.getName().compareToIgnoreCase(b.getName());
                }
            });
            List<FileInfo> finalList = applyFilter(list);
            List<FileInfo> ui = Collections.unmodifiableList(finalList);
            files.postValue(ui);
            // 受限目录且未能读到任何条目时，标记为受限，供 UI 显示授权入口。
            restricted.postValue(restrictedDir && !readOk);
            loading.postValue(false);
        });
    }

    private List<FileInfo> applyFilter(List<FileInfo> source) {
        if (filter == null || filter.isEmpty()) return source;
        String kw = filter.toLowerCase();
        List<FileInfo> out = new ArrayList<>();
        for (FileInfo fi : source) {
            if (fi.getName().toLowerCase().contains(kw)) out.add(fi);
        }
        return out;
    }

    /** 打开/关闭隐藏文件显示。 */
    public void toggleShowHidden() {
        showHidden = !showHidden;
        File cur = new File(currentPath.getValue() != null ? currentPath.getValue() : "/");
        loadDirectory(cur);
    }

    public boolean isShowHidden() {
        return showHidden;
    }

    /** 在目录内设置搜索过滤。 */
    public void setFilter(String keyword) {
        this.filter = keyword;
        File cur = new File(currentPath.getValue() != null ? currentPath.getValue() : "/");
        loadDirectory(cur);
    }

    public boolean isGridMode() {
        return gridMode;
    }

    public void setGridMode(boolean grid) {
        this.gridMode = grid;
    }

    public void setSortMode(int mode) {
        this.sortMode = mode;
        File cur = new File(currentPath.getValue() != null ? currentPath.getValue() : "/");
        loadDirectory(cur);
    }

    private static String ext(String name) {
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot) : "";
    }
}