package com.alltoolbox.security;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;

/**
 * 加密保险箱管理器：维护密码设置状态、解锁会话、保险箱路径与文件列表。
 * 密码不保存明文，仅保存其指纹用于校验。退出上锁后需重新输入密码。
 */
public final class VaultManager {

    private static final String PREFS = "vault";
    private static final String KEY_SETUP = "setup";
    private static final String KEY_FP = "fingerprint";
    private static final String KEY_SALT = "fp_salt";

    private static volatile VaultManager sInstance;

    private final Context ctx;
    private SharedPreferences prefs;
    private boolean unlocked;

    public static VaultManager get(Context ctx) {
        if (sInstance == null) {
            synchronized (VaultManager.class) {
                if (sInstance == null) sInstance = new VaultManager(ctx.getApplicationContext());
            }
        }
        return sInstance;
    }

    private VaultManager(Context ctx) {
        this.ctx = ctx;
        this.prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public boolean isSetup() {
        return prefs.getBoolean(KEY_SETUP, false);
    }

    public boolean isUnlocked() {
        return unlocked;
    }

    public void lock() {
        unlocked = false;
    }

    /** 首次设置密码。 */
    public boolean setPassword(char[] password) {
        try {
            prefs.edit().putString(KEY_FP, AesUtil.passwordFingerprint(password)).putBoolean(KEY_SETUP, true).apply();
            unlocked = true;
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** 用密码验证并解锁。 */
    public boolean unlock(char[] password) {
        if (!isSetup()) return false;
        try {
            String fp = AesUtil.passwordFingerprint(password);
            if (prefs.getString(KEY_FP, "").equals(fp)) {
                unlocked = true;
                return true;
            }
        } catch (Exception ignore) {
        }
        return false;
    }

    /** 保险箱根目录。 */
    public File vaultDir() {
        File dir = new File(ctx.getFilesDir(), "vault");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    /** 加密存储目录（已加密文件）。 */
    public File encDir() {
        File d = new File(vaultDir(), "encrypted");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    /** 解码临时目录。 */
    public File decDir() {
        File d = new File(vaultDir(), "decrypted");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    public boolean delete(File f) {
        return f.delete();
    }
}