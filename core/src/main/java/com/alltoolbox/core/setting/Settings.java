package com.alltoolbox.core.setting;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 全局设置存储（SharedPreferences 封装），供各模块读写。
 * 对应设置页各分类的选项项。
 */
public final class Settings {

    private static final String PREFS = "toolbox_settings";

    // 设置项 key
    /** 启动：默认首页目录（空=默认外部存储根）。 */
    public static final String KEY_HOME_PATH = "home_path";
    /** 启动：每次启动是否检测更新（默认开启）。 */
    public static final String KEY_UPDATE_CHECK = "update_check";
    /** 外观：主题模式（0跟随 1浅色 2深色）。 */
    public static final String KEY_THEME = "theme_mode";
    /** 外观：列表还是网格。true=网格。 */
    public static final String KEY_GRID_MODE = "grid_mode";
    /** 常规：是否显示隐藏文件。 */
    public static final String KEY_SHOW_HIDDEN = "show_hidden";
    /** 其他：时间/日期格式（simple date format 字符串）。 */
    public static final String KEY_DATETIME_FORMAT = "datetime_format";
    /** 其他：语言（zh / en / 跟随系统）。 */
    public static final String KEY_LANGUAGE = "language";

    private Settings() {
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static String getString(Context ctx, String key) {
        return prefs(ctx).getString(key, null);
    }

    public static String getString(Context ctx, String key, String def) {
        return prefs(ctx).getString(key, def);
    }

    public static void putString(Context ctx, String key, String value) {
        prefs(ctx).edit().putString(key, value).apply();
    }

    public static boolean getBoolean(Context ctx, String key, boolean def) {
        return prefs(ctx).getBoolean(key, def);
    }

    public static void putBoolean(Context ctx, String key, boolean value) {
        prefs(ctx).edit().putBoolean(key, value).apply();
    }

    public static int getInt(Context ctx, String key, int def) {
        return prefs(ctx).getInt(key, def);
    }

    public static void putInt(Context ctx, String key, int value) {
        prefs(ctx).edit().putInt(key, value).apply();
    }
}