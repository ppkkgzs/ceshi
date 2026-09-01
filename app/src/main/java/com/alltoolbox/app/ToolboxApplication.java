package com.alltoolbox.app;

import android.app.Application;

import com.alltoolbox.core.AppContext;
import com.alltoolbox.core.theme.ThemeManager;

/**
 * 应用入口：初始化全局上下文、应用主题、后台线程池。
 * 纯本地离线，无网络、无广告、无登录。
 */
public class ToolboxApplication extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        AppContext.init(this);
        ThemeManager.apply(this);
    }
}