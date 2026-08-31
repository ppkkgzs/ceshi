package com.alltoolbox.cleanup;

import android.content.Context;
import android.content.SharedPreferences;

import com.alltoolbox.core.theme.ThemeManager;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 书签管理：收藏常用目录，侧边栏一键访问。
 * 以 Set<String> 路径存储于 SharedPreferences。
 */
public final class BookmarkManager {

    private static final String KEY = "bookmark_paths";
    private static volatile BookmarkManager sInstance;

    public static BookmarkManager get() {
        if (sInstance == null) {
            synchronized (BookmarkManager.class) {
                if (sInstance == null) sInstance = new BookmarkManager();
            }
        }
        return sInstance;
    }

    private SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(ThemeManager.PREFS, Context.MODE_PRIVATE);
    }

    public boolean isBookmarked(Context ctx, String path) {
        return all(ctx).contains(path);
    }

    public void toggle(Context ctx, String path) {
        Set<String> set = new HashSet<>(all(ctx));
        if (set.contains(path)) set.remove(path);
        else set.add(path);
        prefs(ctx).edit().putStringSet(KEY, set).apply();
    }

    public List<String> all(Context ctx) {
        return new ArrayList<>(prefs(ctx).getStringSet(KEY, new HashSet<>()));
    }
}