package com.alltoolbox.core.permission;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.Settings;

import androidx.activity.result.ActivityResultLauncher;
import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.alltoolbox.core.AppContext;

import java.io.File;

/**
 * 存储与系统权限管理。
 *
 * 适配 Android 5.0(A21) ~ 14(34) 的文件权限矩阵：
 *  - API < 23            : 无需运行时权限，清单声明即可
 *  - API 23 ~ 28         : 运行时申请 READ/WRITE_EXTERNAL_STORAGE
 *  - API 29 (Android 10) : Scoped Storage，外部公共目录写需 MediaStore / SAF
 *  - API 30+ (Android 11+): MANAGE_EXTERNAL_STORAGE(全部文件) 或 SAF
 *  Root 场景可绕过限制，见 RootUtil。
 */
public final class Permissions {

    private Permissions() {
    }

    /** 是否需要申请全部文件访问权限（MANAGE_EXTERNAL_STORAGE）。 */
    public static boolean requiresAllFilesAccess() {
        return AppContext.isAtLeastR();
    }

    /** 全部文件访问权限是否已授予。 */
    public static boolean hasAllFilesAccess(Context context) {
        if (!AppContext.isAtLeastR()) {
            return hasLegacyStoragePermission(context);
        }
        return Environment.isExternalStorageManager();
    }

    private static boolean hasLegacyStoragePermission(Context context) {
        if (!AppContext.isAtLeastM()) return true; // <6.0 无需运行时权限
        int read = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE);
        if (!AppContext.isAtLeastQ()) {
            int write = ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE);
            return read == PackageManager.PERMISSION_GRANTED
                    && write == PackageManager.PERMISSION_GRANTED;
        }
        return read == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * 若为 Android 11+，返回 true 表示应引导用户到"所有文件访问"设置页。
     */
    public static boolean requestAllFilesAccess(Activity activity,
                                                ActivityResultLauncher<Intent> launcher) {
        if (!AppContext.isAtLeastR()) return false;
        if (Environment.isExternalStorageManager()) return false;
        try {
            Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
            intent.setData(Uri.parse("package:" + activity.getPackageName()));
            launcher.launch(intent);
        } catch (Exception e) {
            launcher.launch(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
        }
        return true;
    }

    /** 启动 SAF 目录授权（用于 Android 10+ 外部目录 / OTG）。 */
    public static void openDirectoryPicker(ActivityResultLauncher<Uri> launcher) {
        // OpenDocumentTree 契约输入为起始 Uri，传 null 即从默认位置开始
        launcher.launch(null);
    }

    /** Android 10 读取媒体是否有权限。 */
    public static boolean hasReadMedia(Context context) {
        return AppContext.isAtLeastM()
                ? ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED
                : true;
    }

    /**
     * 目标目录是否位于应用外部公共存储，需要 SAF 处理。
     */
    public static boolean shouldHandleViaSaf(File target) {
        if (!AppContext.isAtLeastQ()) return false; // 10 以下直接 File 写
        if (AppContext.isAtLeastR() && Environment.isExternalStorageManager()) return false;
        String path = target.getAbsolutePath();
        String external = Environment.getExternalStorageDirectory().getAbsolutePath();
        return path.startsWith(external);
    }

    /**
     * 获取可用的根路径可读目录（Root 下返回 /），否则返回外部存储根与自身数据目录。
     */
    public static File[] getBrowseableRoots(Context context) {
        if (Root.isRooted()) {
            return new File[]{new File("/")};
        }
        File primary = Environment.getExternalStorageDirectory();
        return new File[]{primary, context.getFilesDir().getParentFile()};
    }

    /** Root 是否可用（无 Root 自动隐藏相关入口）。 */
    public static boolean isRooted() {
        return Root.isRooted();
    }
}