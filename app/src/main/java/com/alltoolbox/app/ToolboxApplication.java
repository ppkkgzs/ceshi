package com.alltoolbox.app;

import android.app.Application;
import android.content.Context;

import com.alltoolbox.core.AppContext;
import com.alltoolbox.core.LocaleUtil;
import com.alltoolbox.core.theme.ThemeManager;

/**
 * 应用入口：初始化全局上下文、应用主题、后台线程池。
 * 纯本地离线，无网络、无广告、无登录。
 */
public class ToolboxApplication extends Application {

    /** 启动时即应用所选语言，使全局资源按该语言解析。 */
    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(LocaleUtil.wrap(base));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        AppContext.init(this);
        ThemeManager.apply(this);
    }
}