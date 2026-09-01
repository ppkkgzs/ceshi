package com.alltoolbox.core;

import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.Build;

import com.alltoolbox.core.setting.Settings;

import java.util.Locale;

/**
 * 应用内语言管理。
 *
 * 依据 {@link Settings#KEY_LANGUAGE} 决定界面语言：
 *  - "zh"  -> 简体中文
 *  - "en"  -> English
 *  - "auto"/其它 -> 跟随系统默认语言（中文系统回退到简体中文）
 *
 * 在 {@code Application.attachBaseContext} 中调用 {@link #wrap(Context)}
 * 把语言应用到启动时的 base context，从而让全局资源（string.xml 等）按所选语言解析。
 */
public final class LocaleUtil {

    private LocaleUtil() {
    }

    /** 解析当前应使用的语言（不修改任何状态）。 */
    public static Locale resolve(Context ctx) {
        // 注意：在 Application.attachBaseContext 阶段 getApplicationContext()
        // 可能尚未就绪，因此直接对传入的 base 上下文读取偏好设置。
        String lang = ctx.getSharedPreferences(Settings.PREFS, Context.MODE_PRIVATE)
                .getString(Settings.KEY_LANGUAGE, "auto");
        return resolve(ctx, lang);
    }

    /** 解析指定设置值对应语言；"auto" 时取系统默认（中文系统落回简体中文）。 */
    public static Locale resolve(Context ctx, String lang) {
        if ("zh".equals(lang)) return Locale.SIMPLIFIED_CHINESE;
        if ("en".equals(lang)) return Locale.ENGLISH;
        Locale sys = Locale.getDefault();
        String l = sys != null ? sys.getLanguage() : "";
        if (l != null && l.toLowerCase(Locale.ROOT).startsWith("zh")) {
            return Locale.SIMPLIFIED_CHINESE;
        }
        return sys != null ? sys : Locale.ENGLISH;
    }

    /** 根据当前设置包装 base context，用于 Application.attachBaseContext。 */
    public static Context wrap(Context base) {
        return apply(base, resolve(base));
    }

    /** 把指定语言应用到 base context。 */
    public static Context apply(Context base, Locale loc) {
        if (base == null || loc == null) return base;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            // API 17+：用 createConfigurationContext 以独立资源配置，不污染全局
            Configuration config = new Configuration(base.getResources().getConfiguration());
            config.setLocale(loc);
            config.setLayoutDirection(loc);
            return base.createConfigurationContext(config);
        }
        // 极老设备回退：修改全局资源配置
        Resources res = base.getResources();
        Configuration config = res.getConfiguration();
        config.locale = loc;
        res.updateConfiguration(config, res.getDisplayMetrics());
        return base;
    }
}