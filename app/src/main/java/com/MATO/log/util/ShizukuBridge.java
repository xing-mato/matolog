package com.MATO.log.util;

import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;

import com.MATO.log.R;

import rikka.shizuku.Shizuku;

/**
 * Shizuku 接入。
 *
 * <p><b>它在本应用里只做一件事</b>：当用户已经装好并启用 Shizuku 时，
 * 把「使用情况访问」这类只能手动去系统设置里点的授权，变成应用内一次点击。
 * 没有它，功能照样完整 —— 只是要用户自己去设置里点几下。
 *
 * <p><b>为什么这个依赖不违背「零依赖」原则</b>：
 * <ul>
 *   <li>它是 {@code compileOnly}：编译期用它的类，运行时**不依赖 Shizuku**。
 *       没装或没启用时，下面每个方法都返回「不可用」，走手动授权那条路；</li>
 *   <li>它传递依赖的 {@code androidx.annotation} 只是 {@code @Nullable} 这类
 *       源码级注解，不进字节码、不影响本工程「全部系统原生控件」的取向；</li>
 *   <li>反过来，自己手写这套协议要处理 binder 往返握手与版本协商，
 *       出错是静默的、只在部分设备上表现 —— 那种复杂度不值得自己扛。</li>
 * </ul>
 *
 * <p><b>绝不假装成功</b>：Shizuku 不可用时返回明确的失败原因，由界面把这个原因
 * 原样显示出来，而不是吞掉。
 */
public final class ShizukuBridge {

    /** Shizuku 应用的包名，用来判断「装没装」 */
    private static final String PKG = "moe.shizuku.privileged.api";

    private ShizukuBridge() {
    }

    /**
     * 装了 Shizuku 吗（不看有没有启用）。
     *
     * <p><b>为什么用 getInstalledApplications 而不是 getPackageInfo</b>：
     * Android 11 起有包可见性限制，查询一个没在清单 &lt;queries&gt; 里声明过的包，
     * getPackageInfo 会抛 NameNotFoundException —— 那会被误判成「没装」。
     * 本应用有 QUERY_ALL_PACKAGES，getInstalledApplications 能拿到完整列表，
     * 所以改用它来包含式判断。
     *
     * <p>这个方法在权限页每次刷新时都会被调用，所以只走一次列表扫描。
     */
    public static boolean installed(Context c) {
        try {
            java.util.List<android.content.pm.ApplicationInfo> apps =
                    c.getPackageManager().getInstalledApplications(0);
            if (apps != null) {
                for (int i = 0; i < apps.size(); i++) {
                    if (PKG.equals(apps.get(i).packageName)) {
                        return true;
                    }
                }
            }
            return false;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 现在能通过 Shizuku 执行特权操作吗。
     *
     * <p>三个条件缺一不可：Shizuku 服务在线、本应用已获 Shizuku 授权、且服务可用。
     * 任何一步出问题都只是返回 false，不抛异常 —— 它会被权限页反复调用。
     */
    public static boolean ready() {
        try {
            if (!Shizuku.pingBinder()) {
                return false;
            }
            if (Shizuku.isPreV11()) {
                // v11 之前没有运行时授权模型，接口对不上，不尝试
                return false;
            }
            return Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 装了但还没授权（需要先向 Shizuku 申请） */
    public static boolean needGrant() {
        try {
            return Shizuku.pingBinder()
                    && !isPreV11()
                    && Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Shizuku 版本是否低于 v11（那套接口对不上，直接不尝试） */
    public static boolean isPreV11() {
        try {
            return Shizuku.isPreV11();
        } catch (Throwable t) {
            return true;
        }
    }

    /**
     * 向 Shizuku 申请授权。结果通过 {@link Shizuku.OnRequestPermissionResultListener} 回调，
     * 界面需要自己注册监听（见 PermissionsActivity）。
     */
    public static boolean requestPermission(int requestCode) {
        try {
            if (!Shizuku.pingBinder()) {
                return false;
            }
            Shizuku.requestPermission(requestCode);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    public static void addPermissionListener(Shizuku.OnRequestPermissionResultListener l) {
        try {
            Shizuku.addRequestPermissionResultListener(l);
        } catch (Throwable ignored) {
        }
    }

    public static void removePermissionListener(Shizuku.OnRequestPermissionResultListener l) {
        try {
            Shizuku.removeRequestPermissionResultListener(l);
        } catch (Throwable ignored) {
        }
    }

    /** Shizuku 应用是否在运行（用于给出「去启用」而不是「去安装」的提示） */
    public static boolean serviceAlive() {
        try {
            return Shizuku.pingBinder();
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 打开 Shizuku 应用本体。
     *
     * <p>用户没启动过 Shizuku 时，任何 API 都调不动它 —— 必须先由用户打开一次应用
     * 把服务跑起来。所以这里给一条明确的出路，而不是只说「不可用」。
     */
    public static void openApp(Context c) {
        Intent i = c.getPackageManager().getLaunchIntentForPackage(PKG);
        if (i == null) {
            // 没装：给一个查安装包的入口，而不是静默失败
            try {
                c.startActivity(new Intent(Intent.ACTION_VIEW,
                        Uri.parse("market://details?id=" + PKG))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return;
            } catch (ActivityNotFoundException e) {
                // 设备上没有应用市场（模拟器常见）：退到网页
                c.startActivity(new Intent(Intent.ACTION_VIEW,
                        Uri.parse("https://shizuku.rikka.app/"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return;
            }
        }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            c.startActivity(i);
        } catch (Throwable ignored) {
        }
    }

    /** 一句话说明当前为什么不能用，直接显示给用户 */
    public static String status(Context c) {
        if (!installed(c)) {
            return c.getString(R.string.shizuku_not_installed);
        }
        if (!serviceAlive()) {
            return c.getString(R.string.shizuku_not_running);
        }
        if (isPreV11()) {
            return c.getString(R.string.shizuku_too_old);
        }
        if (!ready()) {
            return c.getString(R.string.shizuku_no_permission);
        }
        return c.getString(R.string.shizuku_ready);
    }

    /** 把 ComponentName 转成可读文本，给调试用 */
    public static String describe(ComponentName cn) {
        return cn == null ? "" : cn.flattenToShortString();
    }
}
