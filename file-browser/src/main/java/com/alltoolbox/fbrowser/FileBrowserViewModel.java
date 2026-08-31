package com.alltoolbox.fbrowser;

import android.app.Application;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

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

    private File root;
    private boolean showHidden = false;
    private String filter = "";
    private boolean gridMode = false;

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

    public void setRoot(File root) {
        this.root = root;
        navigateTo(root == null ? new File("/") : root);
    }

    public File getRoot() {
        return root;
    }

    public void navigateTo(File target) {
        currentPath.setValue(target.getAbsolutePath());
        loadDirectory(target);
    }

    public void loadDirectory(File dir) {
        loading.setValue(true);
        TaskExecutor.get().io().execute(() -> {
            File[] children = dir.listFiles();
            List<FileInfo> list = new ArrayList<>();
            if (children != null) {
                for (File f : children) {
                    if (!showHidden && (f.isHidden() || f.getName().startsWith("."))) continue;
                    list.add(new FileInfo(f));
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