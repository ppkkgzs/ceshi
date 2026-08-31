package com.alltoolbox.fops;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import androidx.core.content.FileProvider;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 文件分享：多文件系统分享、复制路径。
 */
public final class ShareUtil {

    private ShareUtil() {
    }

    /** 把路径写入剪贴板。 */
    public static void copyPath(Context context, String path) {
        android.content.ClipboardManager cm =
                (android.content.ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(android.content.ClipData.newPlainText("path", path));
    }

    /** 多文件分享（ACTION_SEND_MULTIPLE），Android 7+ 通过 FileProvider 安全共享。 */
    public static void shareFiles(Context context, List<File> files) {
        String authority = context.getPackageName() + ".fileprovider";
        ArrayList<Uri> uris = new ArrayList<>();
        for (File f : files) {
            uris.add(FileProvider.getUriForFile(context, authority, f));
        }
        Intent intent = new Intent(Intent.ACTION_SEND_MULTIPLE);
        intent.setType("*/*");
        intent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        context.startActivity(Intent.createChooser(intent, "分享"));
    }
}