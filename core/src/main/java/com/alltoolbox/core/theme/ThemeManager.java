package com.alltoolbox.core.theme;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;

import androidx.appcompat.app.AppCompatDelegate;

/**
 * 主题管理：浅色 / 深色 / 跟随系统。
 */
public final class ThemeManager {

    public static final String PREFS = "toolbox_prefs";
    public static final String KEY_MODE = "theme_mode"; // 0跟随 1浅色 2深色

    private static final int MODE_FOLLOW_SYSTEM = AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM;
    private static final int MODE_LIGHT = AppCompatDelegate.MODE_NIGHT_NO;
    private static final int MODE_DARK = AppCompatDelegate.MODE_NIGHT_YES;

    private ThemeManager() {
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** 应用保存的主题模式。 */
    public static void apply(Context context) {
        AppCompatDelegate.setDefaultNightMode(readMode(context));
    }

    /** 设置并立即应用主题。 */
    public static void setMode(Context context, int mode) {
        prefs(context).edit().putInt(KEY_MODE, mode).apply();
        AppCompatDelegate.setDefaultNightMode(mode);
        if (context instanceof Activity) {
            ((Activity) context).recreate();
        }
    }

    public static int readMode(Context context) {
        return prefs(context).getInt(KEY_MODE, MODE_FOLLOW_SYSTEM);
    }
}