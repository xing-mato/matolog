package com.MATO.log.util;

import android.app.AppOpsManager;
import android.app.NotificationManager;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.TextUtils;

/**
 * 权限与系统开关的「现在到底有没有」判定 + 「怎么去开」的跳转。
 *
 * <p>这些判定分散在各处很容易写错（尤其不同 Android 版本字段不同），集中在这里，
 * 界面上只问结论。每个方法都必须能在没权限的情况下安全返回 false ——
 * 它被设置页每秒级的刷新调用，抛异常就等于设置页打不开。
 *
 * <p><b>本应用不依赖 root，也不依赖 Shizuku。</b>下面这些权限在普通用户设备上
 * 都能通过系统界面拿到，只是有的要用户手动点几步 —— 所以每一步都给了跳转入口
 * 和一句「为什么要它」的说明。
 */
public final class Perm {

    private Perm() {
    }

    // ---------------- 使用情况访问 ----------------

    /**
     * 有没有「使用情况访问」权限。
     *
     * <p>这个权限没有运行时申请接口（不属于危险权限那套），只能读 AppOps 的状态。
     * Android 10 起要额外判断 mode 是否为 default，所以两种查法都试一遍。
     */
    public static boolean usageAccess(Context c) {
        try {
            AppOpsManager aom = (AppOpsManager) c.getSystemService(Context.APP_OPS_SERVICE);
            if (aom == null) {
                return false;
            }
            int mode;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                mode = aom.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                        android.os.Process.myUid(), c.getPackageName());
            } else {
                mode = aom.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                        android.os.Process.myUid(), c.getPackageName());
            }
            return mode == AppOpsManager.MODE_ALLOWED;
        } catch (Throwable t) {
            return false;
        }
    }

    public static void openUsageAccess(Context c) {
        go(c, new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS));
    }

    // ---------------- 通知 ----------------

    public static boolean notifications(Context c) {
        try {
            NotificationManager nm =
                    (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            return nm == null || nm.areNotificationsEnabled();
        } catch (Throwable t) {
            // 判定不了就当有，不要因为查不到就拦着用户
            return true;
        }
    }

    public static void openNotificationSettings(Context c) {
        Intent i = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
        i.putExtra(Settings.EXTRA_APP_PACKAGE, c.getPackageName());
        go(c, i);
    }

    // ---------------- 电池优化 ----------------

    /**
     * 是否已在电池优化白名单里。
     *
     * <p>不在白名单里也能跑，只是更容易被系统在后台回收 —— 所以这是「建议项」
     * 而不是「必需项」，界面上要如实这么写。
     */
    public static boolean ignoringBattery(Context c) {
        try {
            PowerManager pm = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
            return pm != null && pm.isIgnoringBatteryOptimizations(c.getPackageName());
        } catch (Throwable t) {
            return false;
        }
    }

    public static void requestIgnoreBattery(Context c) {
        Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
        i.setData(Uri.parse("package:" + c.getPackageName()));
        if (!go(c, i)) {
            go(c, new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
        }
    }

    // ---------------- 自启动 ----------------

    /**
     * 一次「找自启动入口」的结果。
     *
     * <p><b>为什么要把它交回给界面，而不是只返回一个 boolean</b>：这个入口在所有厂商 ROM 上
     * 都是**尽力而为**的，注定有一批机器找不到（标准 Android 根本没有这个东西）。
     * 只回一个「成功/失败」的话，界面除了说一句「没找到」之外无事可做，而这句「没找到」
     * 对用户毫无用处 —— 他需要的是「那就去哪个页面、找哪个词」。
     */
    public static final class AutoStartResult {
        /** 打开的到底是哪一类页面，界面据此说不同的话 */
        public static final int LEVEL_VENDOR = 1;
        public static final int LEVEL_MANAGER = 2;
        public static final int LEVEL_APP_DETAIL = 3;

        public final int level;
        /** 厂商自启动页所在的应用名（LEVEL_VENDOR 时有值），例如「权限管理」 */
        public final String vendor;

        AutoStartResult(int level, String vendor) {
            this.level = level;
            this.vendor = vendor == null ? "" : vendor;
        }

        /** 打到了厂商自己的自启动页 */
        public boolean isVendorPage() {
            return level == LEVEL_VENDOR;
        }
    }

    /**
     * 跳到厂商的「自启动管理」。
     *
     * <p>这**不是** AOSP 标准入口：各家 ROM 的组件名都不一样，也没有任何官方 API。
     * 而且近几年的 ROM（尤其 vivo 的 OriginOS）把「自启动」从权限管理里挪走了，
     * 同品牌不同版本之间路径也会变 —— 没有任何一张表能覆盖全部机器。
     *
     * <p>所以这里做的是**三级兜底**，绝不静默失败：
     * <ol>
     *   <li>逐个试已知的厂商自启动页组件名，命中就进去；</li>
     *   <li>都没有时，退到该厂的**权限管理应用主页**。这一级是 1.3.1 新加的：
     *       用户反馈里最常见的不是「找不到自启动」，而是「压根不知道它在哪个应用里」——
     *       把人送到权限管理的主页，比送到系统统一的「应用详情」更接近目标；</li>
     *   <li>再不行才退回系统应用详情页。</li>
     * </ol>
     *
     * @return 实际打到了哪一类页面（见 {@link AutoStartResult}）
     */
    public static AutoStartResult openAutoStart(Context c) {
        String hit = tryVendorAutoStart(c);
        if (hit != null) {
            return new AutoStartResult(AutoStartResult.LEVEL_VENDOR, hit);
        }
        if (openVendorManager(c)) {
            return new AutoStartResult(AutoStartResult.LEVEL_MANAGER, "");
        }
        openAppDetail(c);
        return new AutoStartResult(AutoStartResult.LEVEL_APP_DETAIL, "");
    }

    /**
     * 已知的厂商自启动页。
     *
     * <p>同一家往往有好几个候选：ROM 换代时类名会变，而不同产品线（如 OPPO 与 realme）
     * 用的是同一个包的不同版本 —— 所以是「一个个试」而不是「按品牌查表」。
     *
     * <p><b>这份表一定会过期</b>，这是它的性质决定的：它依赖的是各厂没有对外承诺过的
     * 内部类名。加新条目时不必删旧的 —— 多余的候选只会多一次 resolve 失败，
     * 而删掉一条就可能让某台老机器失去入口。
     *
     * @return 命中页面的应用名（给界面用）；全都没命中返回 null
     */
    private static String tryVendorAutoStart(Context c) {
        String[][] targets = {
                // 小米 / 红米（MIUI、HyperOS）
                {"com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"},
                // OPPO / realme（ColorOS）
                {"com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"},
                {"com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"},
                {"com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"},
                {"com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity2"},
                // vivo / iQOO（Funtouch、OriginOS）
                {"com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"},
                {"com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.PurviewTabActivity"},
                {"com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"},
                {"com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager"},
                // 华为 / 荣耀（EMUI、HarmonyOS）
                {"com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"},
                {"com.huawei.systemmanager", "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity"},
                {"com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity"},
                // 三星（One UI）
                {"com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity"},
                {"com.samsung.android.sm", "com.samsung.android.sm.ui.battery.BatteryActivity"},
                // 魅族（Flyme）
                {"com.meizu.safe", "com.meizu.safe.security.SHOW_APPSEC"},
                // 一加（氢/氧 OS，老机型）
                {"com.oneplus.security", "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity"},
                // 乐视、华硕、中兴、诺基亚、联想 —— 都是老机型，留着不碍事
                {"com.letv.android.letsafe", "com.letv.android.letsafe.AutobootManageActivity"},
                {"com.asus.mobilemanager", "com.asus.mobilemanager.powersaver.PowerSaverSettings"},
                {"com.zte.powersave", "com.zte.powersave.activity.AutoStartAppActivity"},
                {"com.evenwell.powersaving.g3", "com.evenwell.powersaving.g3.exception.PowerSavingExceptionListActivity"},
                {"com.lenovo.security", "com.lenovo.security.purebackground.PureBackgroundActivity"},
        };
        for (String[] t : targets) {
            Intent i = new Intent();
            i.setComponent(new ComponentName(t[0], t[1]));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                if (c.getPackageManager().resolveActivity(i, 0) != null) {
                    c.startActivity(i);
                    return appNameOf(c, t[0]);
                }
            } catch (Throwable ignored) {
                // 试下一个
            }
        }
        return null;
    }

    /**
     * 退而求其次：打开该厂「权限管理」应用的主页。
     *
     * <p>用户反馈里最常见的情形是「系统权限管理里只有关联启动、没有自启动」——
     * 那是因为自启动根本不在这台机器这一个页面里，而在厂商自己的权限管理应用中。
     * 当具体页面找不到时，把人送到这个应用的主页，他至少能顺着「权限 / 自启动 / 后台」
     * 这些字眼找下去。
     *
     * <p>用 {@code getLaunchIntentForPackage} 而不是写死类名：主页类名各家各版都可能不同，
     * 而「这个包有没有一个能被启动的入口」是系统能直接回答的。
     */
    private static boolean openVendorManager(Context c) {
        String[] pkgs = {
                "com.vivo.permissionmanager", "com.iqoo.secure",
                "com.miui.securitycenter", "com.coloros.safecenter", "com.oppo.safe",
                "com.huawei.systemmanager", "com.samsung.android.lool", "com.meizu.safe",
        };
        for (String pkg : pkgs) {
            try {
                Intent launch = c.getPackageManager().getLaunchIntentForPackage(pkg);
                if (launch == null) {
                    continue;
                }
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                c.startActivity(launch);
                return true;
            } catch (Throwable ignored) {
                // 试下一个
            }
        }
        return false;
    }

    /**
     * 某个包在桌面上的名字，用作「已打开：XXX」。
     *
     * <p><b>为什么取不到名字时回退到包名，而不是回退到厂商名</b>：真机上实测过 ——
     * vivo 的权限管理器给系统进程报的标签只是一个光秃秃的「权限管理」，
     * 而对用户来说毫无信息量（他要找的是「哪个应用被打开了」）。回退成包名至少是准确的。
     */
    private static String appNameOf(Context c, String pkg) {
        try {
            String label = String.valueOf(c.getPackageManager().getApplicationLabel(
                    c.getPackageManager().getApplicationInfo(pkg, 0)));
            if (label != null && !label.isEmpty() && !"null".equals(label)) {
                return label;
            }
        } catch (Throwable ignored) {
            // 落到包名
        }
        return pkg;
    }

    public static void openAppDetail(Context c) {
        Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        i.setData(Uri.parse("package:" + c.getPackageName()));
        go(c, i);
    }

    // ---------------- 工具 ----------------

    private static boolean go(Context c, Intent i) {
        try {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(i);
            return true;
        } catch (ActivityNotFoundException e) {
            return false;
        } catch (Throwable t) {
            return false;
        }
    }
}
