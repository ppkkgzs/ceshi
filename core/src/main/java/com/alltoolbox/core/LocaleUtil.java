package com.alltoolbox.core;

import android.content.Context;

import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.os.LocaleListCompat;

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
 * 在启动时（{@code Application.onCreate}）调用 {@link #applyToApp(Context)}，
 * 通过 AppCompat 官方机制把语言应用到每一个 AppCompatActivity。
 */
public final class LocaleUtil {

    private LocaleUtil() {
    }

    /** 解析当前应使用的语言（不修改任何状态）。 */
    public static Locale resolve(Context ctx) {
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

    /**
     * 把当前所选语言应用到应用范围（AppCompat 官方机制）。
     *
     * 与单纯在 Application.attachBaseContext 里改 Configuration 不同，系统创建每个
     * Activity 的上下文时会重新生成 Configuration，并不会继承应用层的设置；而
     * {@link AppCompatDelegate#setApplicationLocales} 会在每个 AppCompatActivity 的
     * attachBaseContext 阶段重新套用 locale，因此界面文本能真正按所选语言解析。
     *
     * - "en"/"zh" -> 显式指定语言
     * - "auto"/其它 -> 清空覆盖，跟随系统默认语言（中文系统回退到应用默认中文资源）
     */
    public static void applyToApp(Context ctx) {
        String lang = ctx.getSharedPreferences(Settings.PREFS, Context.MODE_PRIVATE)
                .getString(Settings.KEY_LANGUAGE, "auto");
        if (lang == null || "auto".equals(lang)) {
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList());
        } else {
            Locale loc = resolve(ctx, lang);
            AppCompatDelegate.setApplicationLocales(
                    LocaleListCompat.forLanguageTags(loc.getLanguage()));
        }
    }
}