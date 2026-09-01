package com.alltoolbox.core;

import android.content.Context;
import android.os.Build;

/**
 * 应用上下文持有者。在各模块中通过 {@code AppContext.get()} 获取上下文，
 * 避免依赖注入框架带来的复杂度（手动 DI，Service Locator 模式）。
 */
public final class AppContext {

    private static Context sAppContext;

    private AppContext() {
    }

    /** 必须在 Application.onCreate() 中初始化。 */
    public static void init(Context context) {
        if (sAppContext == null) {
            sAppContext = context.getApplicationContext();
        }
    }

    public static Context get() {
        if (sAppContext == null) {
            throw new IllegalStateException("AppContext 尚未初始化，请在 Application.onCreate 中调用 init");
        }
        return sAppContext;
    }

    public static boolean isAtLeastM() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.M;
    }

    public static boolean isAtLeastN() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.N;
    }

    public static boolean isAtLeastQ() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q;
    }

    public static boolean isAtLeastR() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.R;
    }

    /** Android 13（API 33）及以上：媒体权限细分为 图片/视频/音频。 */
    public static boolean isAtLeastT() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU;
    }
}