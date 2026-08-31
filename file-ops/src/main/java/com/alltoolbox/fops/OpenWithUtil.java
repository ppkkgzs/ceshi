package com.alltoolbox.fops;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import java.io.File;

/**
 * 文件打开辅助：对任意文件用系统「其它应用打开」（ACTION_VIEW + FileProvider）。
 * 用于文件浏览器中无法内置预览/编辑的类型，避免"打不开"。
 */
public final class OpenWithUtil {

    private OpenWithUtil() {
    }

    /**
     * 用系统其它应用打开文件。
     * @return 是否成功启动（即有应用能处理）。
     */
    public static boolean openWith(Context context, File file) {
        if (file == null || !file.exists()) {
            Toast.makeText(context, "文件不存在", Toast.LENGTH_SHORT).show();
            return false;
        }
        try {
            Uri uri = FileProvider.getUriForFile(context,
                    context.getPackageName() + ".fileprovider", file);
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, com.alltoolbox.core.file.FileUtil.getMimeType(file.getName()));
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            Intent chooser = Intent.createChooser(intent, "用其它应用打开");
            chooser.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            context.startActivity(chooser);
            return true;
        } catch (Exception e) {
            // 可能没有能处理的应用
            Toast.makeText(context, "没有可打开此文件的应用", Toast.LENGTH_SHORT).show();
            return false;
        }
    }
}