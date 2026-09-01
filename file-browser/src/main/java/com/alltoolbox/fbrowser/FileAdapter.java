package com.alltoolbox.fbrowser;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.content.res.AppCompatResources;
import androidx.recyclerview.widget.RecyclerView;

import com.alltoolbox.core.file.FileUtil;
import com.alltoolbox.fbrowser.model.FileInfo;
import com.alltoolbox.fbrowser.R;

import java.util.List;
import java.util.Set;

/**
 * 文件列表/网格适配器。支持长按多选与选择态高亮。
 */
public class FileAdapter extends RecyclerView.Adapter<FileAdapter.Holder> {

    public interface Listener {
        void onOpen(FileInfo file);

        void onLongPress(FileInfo file);

        void onSelectionChanged(int count);
    }

    private List<FileInfo> items;
    private final boolean grid;
    private final Set<Integer> selected = new java.util.HashSet<>();
    private final Listener listener;

    public FileAdapter(List<FileInfo> items, boolean grid, Listener listener) {
        this.items = items;
        this.grid = grid;
        this.listener = listener;
    }

    /** 更新数据集并刷新。 */
    public void submit(List<FileInfo> newItems) {
        this.items = newItems;
        selected.clear();
        notifyDataSetChanged();
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        int layout = grid ? R.layout.item_file_grid : R.layout.item_file_list;
        View v = LayoutInflater.from(parent.getContext()).inflate(layout, parent, false);
        return new Holder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder h, int position) {
        FileInfo fi = items.get(position);
        h.bind(fi, selected.contains(position));
    }

    // ---------- 选择管理 ----------

    public boolean isInSelectionMode() {
        return !selected.isEmpty();
    }

    public void toggleSelectAll() {
        if (selected.size() == items.size()) {
            selected.clear();
        } else {
            for (int i = 0; i < items.size(); i++) selected.add(i);
        }
        notifyDataSetChanged();
        listener.onSelectionChanged(selected.size());
    }

    public List<FileInfo> getSelectedItems() {
        List<FileInfo> out = new java.util.ArrayList<>();
        for (Integer i : selected) out.add(items.get(i));
        return out;
    }

    public void clearSelection() {
        selected.clear();
        notifyDataSetChanged();
        listener.onSelectionChanged(0);
    }

    /** 单选某个位置：清空其余选择并选中该项（用于操作菜单里的“多选/单选”）。 */
    public void selectSingle(int position) {
        if (position < 0 || position >= items.size()) return;
        selected.clear();
        selected.add(position);
        notifyDataSetChanged();
        listener.onSelectionChanged(selected.size());
    }

    // ---------- Holder ----------

    class Holder extends RecyclerView.ViewHolder {
        private final ImageView icon;
        private final TextView name;
        private final TextView info;

        Holder(@NonNull View itemView) {
            super(itemView);
            icon = itemView.findViewById(R.id.f_icon);
            name = itemView.findViewById(R.id.f_name);
            info = itemView.findViewById(R.id.f_info);
        }

        void bind(FileInfo fi, boolean isSelected) {
            name.setText(fi.getName());
            if (info != null) {
                if (fi.isDirectory()) {
                    info.setText("文件夹");
                } else {
                    info.setText(formatSize(fi.getSize()));
                }
            }

            // 图片/视频：缩略图已加载时优先展示，否则按类型占位
            FileUtil.FileKind kind = fi.getKind();
            boolean media = kind == FileUtil.FileKind.IMAGE || kind == FileUtil.FileKind.VIDEO;
            if (fi.isDirectory() && isAppFolder(fi)) {
                android.graphics.drawable.Drawable appIcon = resolveAppIcon(fi, itemView.getContext());
                if (appIcon != null) {
                    icon.setImageDrawable(appIcon);
                } else {
                    icon.setImageResource(R.drawable.ic_folder);
                }
            } else if (fi.isDirectory() || !media || thumbMap.get(fi.getPath()) == null) {
                icon.setImageResource(iconFor(fi));
            } else {
                icon.setImageBitmap(thumbMap.get(fi.getPath()));
            }
            if (!fi.isDirectory() && media && !thumbMap.containsKey(fi.getPath())) {
                loadThumbnail(this, fi, kind);
            }

            itemView.setActivated(isSelected);
            if (isSelected) {
                itemView.setBackgroundResource(com.alltoolbox.fbrowser.R.color.selection_bg);
            } else {
                itemView.setBackgroundColor(android.graphics.Color.TRANSPARENT);
            }

            itemView.setOnClickListener(v -> {
                if (isInSelectionMode()) {
                    boolean sel = selected.contains(getBindingAdapterPosition());
                    if (sel) selected.remove(getBindingAdapterPosition());
                    else selected.add(getBindingAdapterPosition());
                    notifyItemChanged(getBindingAdapterPosition());
                    listener.onSelectionChanged(selected.size());
                } else {
                    listener.onOpen(fi);
                }
            });
            // 长按：不自动进入多选，直接弹出操作菜单
            itemView.setOnLongClickListener(v -> {
                listener.onLongPress(fi);
                return true;
            });
            itemView.setTag(fi);
        }
    }

    // 缩略图缓存：path -> bitmap
    private final java.util.Map<String, android.graphics.Bitmap> thumbMap = new java.util.concurrent.ConcurrentHashMap<>();

    /** 后台解码缩略图（采样加载，避免大图 OOM），成功后切回 UI 线程刷新该项。 */
    private void loadThumbnail(Holder h, FileInfo fi, FileUtil.FileKind kind) {
        final String path = fi.getPath();
        com.alltoolbox.core.task.TaskExecutor.get().io().execute(() -> {
            if (thumbMap.containsKey(path)) return;
            android.graphics.Bitmap bitmap =
                    kind == FileUtil.FileKind.VIDEO ? decodeVideoThumb(path) : decodeThumb(path);
            if (bitmap == null) {
                thumbMap.put(path, null); // 标记失败，避免反复尝试
                return;
            }
            android.graphics.Bitmap prev = thumbMap.putIfAbsent(path, bitmap);
            if (prev != null) return;
            h.itemView.post(() -> {
                // 仅当该项仍展示同一文件时才刷新，避免复用错位
                if (h.getBindingAdapterPosition() < 0) return;
                if (h.itemView.getTag() == fi) {
                    notifyItemChanged(h.getBindingAdapterPosition());
                }
            });
        });
    }

    /** 视频抽帧缩略图：取接近首帧的画面。 */
    private static android.graphics.Bitmap decodeVideoThumb(String path) {
        android.media.MediaMetadataRetriever retriever = new android.media.MediaMetadataRetriever();
        try {
            retriever.setDataSource(path);
            return retriever.getFrameAtTime(1_000_000/*1ms附近*/,
                    android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
        } catch (Exception e) {
            return null;
        } finally {
            try {
                retriever.release();
            } catch (Exception ignored) {
            }
        }
    }

    private static android.graphics.Bitmap decodeThumb(String path) {
        // 读取边界信息，按目标尺寸计算采样率
        android.graphics.BitmapFactory.Options bounds = new android.graphics.BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        android.graphics.BitmapFactory.decodeFile(path, bounds);
        int reqW = 160, reqH = 160;
        int inSample = 1;
        while (bounds.outWidth / (inSample * 2) >= reqW
                || bounds.outHeight / (inSample * 2) >= reqH) {
            inSample *= 2;
        }
        android.graphics.BitmapFactory.Options opts = new android.graphics.BitmapFactory.Options();
        opts.inSampleSize = inSample;
        opts.inPreferredConfig = android.graphics.Bitmap.Config.RGB_565;
        return android.graphics.BitmapFactory.decodeFile(path, opts);
    }

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

    /**
     * 是否为“应用文件夹”：位于 Android/data、Android/obb、/data/app 下，
     * 且文件夹名与已安装应用包名一致（此时显示该应用的图标）。
     */
    private static boolean isAppFolder(FileInfo fi) {
        String parent = fi.getFile().getParent();
        if (parent == null) return false;
        String p = parent.endsWith("/") ? parent : parent + "/";
        return p.contains("/Android/data/")
                || p.contains("/Android/obb/")
                || p.contains("/data/app/")
                || p.contains("/data/user/");
    }

    /** 根据应用文件夹名（包名）解析已安装应用的图标；未安装返回 null。 */
    private static android.graphics.drawable.Drawable resolveAppIcon(FileInfo fi,
            android.content.Context context) {
        if (!isAppFolder(fi)) return null;
        try {
            android.content.pm.PackageManager pm = context.getPackageManager();
            return pm.getApplicationIcon(fi.getName());
        } catch (Throwable t) {
            return null;
        }
    }

    public static String formatSize(long size) {
        if (size < 0) return "";
        if (size < 1024) return size + " B";
        if (size < 1024 * 1024) return String.format(java.util.Locale.ROOT, "%.1f KB", size / 1024f);
        if (size < 1024 * 1024 * 1024)
            return String.format(java.util.Locale.ROOT, "%.1f MB", size / (1024f * 1024f));
        return String.format(java.util.Locale.ROOT, "%.2f GB", size / (1024f * 1024f * 1024f));
    }

    /** 供 AndroidManifest 依赖提示的兼容引用。 */
    public static FileUtil.FileKind kindOf(FileInfo fi) {
        return fi.getKind();
    }
}