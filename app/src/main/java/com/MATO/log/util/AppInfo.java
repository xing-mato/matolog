package com.MATO.log.util;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 本机应用清单。
 *
 * <p><b>列哪些应用</b>：走 {@code getInstalledApplications(0)} —— <b>全部已安装包</b>，
 * 包括没有启动图标的系统组件（系统笔记、媒体存储、各种后台服务包）。
 *
 * <p><b>为什么不再只列「有启动图标」的</b>：原来的写法是
 * {@code queryIntentActivities(MAIN + LAUNCHER)}，只返回用户能主动打开的应用，
 * 列表确实短。但代价是：没有启动图标的系统应用在名单页里<b>根本找不到</b> ——
 * 用户想把系统自带的笔记应用放进「只记这些」白名单时，连入口都没有。
 * 名单的价值是「用户能选到它」，不是「列表短」，所以改成列全部。
 *
 * <p><b>代价说清楚</b>：条目从几十个涨到几百个（含大量用户永远不会碰的组件），
 * 而且首次枚举要为每个包取一次名字，比原来慢。所以名单页必须分页渲染
 * （见 {@code AppRulesActivity}），缓存也照旧保留 60 秒。
 *
 * <p>另有一条兜底：{@link #all} 会把「名单里存着、但已经卸载」的包名也并进结果。
 * 那些包问 PackageManager 已经查不到（名字退回包名），但必须还留在列表里，
 * 否则名单里会留下一条改不掉的名字。
 *
 * <p>带一层标签与系统标记的缓存：名单页要滚动，每次都问 PackageManager 会很卡。
 * 缓存 60 秒过期，避免用户刚装完应用却看不到。
 */
public final class AppInfo {

    public final String pkg;
    public final String label;
    public final boolean system;

    public AppInfo(String pkg, String label, boolean system) {
        this.pkg = pkg;
        this.label = label;
        this.system = system;
    }

    private static List<AppInfo> cache;
    private static long cacheAt;
    private static final long CACHE_MS = 60_000L;
    private static final Map<String, String> LABELS = new HashMap<>();
    private static final Map<String, Boolean> SYSTEM = new HashMap<>();

    /**
     * 全部已安装应用（含没有启动图标的系统组件），普通应用在前、系统应用在后，
     * 各自按名称排。
     *
     * @param extraPkgs 名单里存着、但已经卸载（或 PackageManager 查不到）的包名。
     *                  它们会被补进结果一起排 —— 缺了它们，用户就没法把名单里
     *                  那条改不掉的名字取消掉。可以为 null
     */
    public static List<AppInfo> all(Context c, Set<String> extraPkgs) {
        // 复制一份再往里面加：缓存那份是共享的，直接改会污染它
        List<AppInfo> base = new ArrayList<>(installed(c));
        if (extraPkgs == null || extraPkgs.isEmpty()) {
            return base;
        }
        Set<String> have = new HashSet<>();
        for (int i = 0; i < base.size(); i++) {
            have.add(base.get(i).pkg);
        }
        boolean added = false;
        for (String p : extraPkgs) {
            if (p == null || p.isEmpty() || have.contains(p)) {
                continue;
            }
            base.add(new AppInfo(p, label(c, p), isSystem(c, p)));
            added = true;
        }
        if (added) {
            sort(base);
        }
        return base;
    }

    /**
     * 已安装部分的枚举 + 排序，带 60 秒缓存。返回值就是缓存本身，调用方不要改它。
     *
     * <p><b>为什么用 getInstalledApplications 而不是 queryIntentActivities</b>：
     * 只有前者带得出没有启动图标的系统组件。本应用有 QUERY_ALL_PACKAGES，
     * 拿得到完整列表（用法同 {@code ShizukuBridge.installed}）。
     */
    private static List<AppInfo> installed(Context c) {
        long now = System.currentTimeMillis();
        if (cache != null && now - cacheAt < CACHE_MS) {
            return cache;
        }

        PackageManager pm = c.getPackageManager();
        List<AppInfo> out = new ArrayList<>();
        List<ApplicationInfo> found;
        try {
            found = pm.getInstalledApplications(0);
        } catch (Throwable t) {
            // 查不动就退化成空列表：名单页会显示「没有匹配的应用」，而不是崩掉
            found = null;
        }

        if (found != null) {
            // 一个包只有一条记录，不用像 queryIntentActivities 那样再去重
            for (int i = 0; i < found.size(); i++) {
                ApplicationInfo ai = found.get(i);
                if (ai == null) {
                    continue;
                }
                String p = ai.packageName;
                if (p == null || p.isEmpty() || isSelf(c, p)) {
                    continue;
                }
                String name;
                try {
                    name = String.valueOf(ai.loadLabel(pm));
                } catch (Throwable t) {
                    name = p;
                }
                boolean isSys = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
                LABELS.put(p, name);
                SYSTEM.put(p, Boolean.valueOf(isSys));
                out.add(new AppInfo(p, name, isSys));
            }
        }

        sort(out);
        cache = out;
        cacheAt = now;
        return out;
    }

    public static void invalidate() {
        cache = null;
        cacheAt = 0;
        LABELS.clear();
        SYSTEM.clear();
    }

    /** 应用名；查不到就退回包名，不留空白 */
    public static String label(Context c, String pkg) {
        if (pkg == null || pkg.isEmpty()) {
            return "";
        }
        String hit = LABELS.get(pkg);
        if (hit != null) {
            return hit;
        }
        String out = pkg;
        try {
            ApplicationInfo ai = c.getPackageManager().getApplicationInfo(pkg, 0);
            out = String.valueOf(ai.loadLabel(c.getPackageManager()));
        } catch (Throwable ignored) {
            // 应用已卸载：包名本身就是最准的说明
        }
        LABELS.put(pkg, out);
        return out;
    }

    public static boolean isSystem(Context c, String pkg) {
        if (pkg == null || pkg.isEmpty()) {
            return false;
        }
        Boolean hit = SYSTEM.get(pkg);
        if (hit != null) {
            return hit.booleanValue();
        }
        boolean out = false;
        try {
            ApplicationInfo ai = c.getPackageManager().getApplicationInfo(pkg, 0);
            out = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
        } catch (Throwable ignored) {
            // 卸载掉的按非系统处理，反正也不会再被记录
        }
        SYSTEM.put(pkg, Boolean.valueOf(out));
        return out;
    }

    public static Drawable iconOf(Context c, String pkg) {
        if (pkg == null || pkg.isEmpty()) {
            return null;
        }
        try {
            return c.getPackageManager().getApplicationIcon(pkg);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 本应用自己的包名。
     *
     * <p>正式包是 {@code com.MATO.log}、调试包是 {@code com.MATO.log.debug}；
     * 另外把 1.3 之前那一系列测试包的包名也列上 —— 它们可能仍装在测试机上，
     * 与正式包同机并存。**任何一种都不该被记录**，否则切到设置页再回来就会
     * 自己记录自己，滚雪球。
     *
     * <p>（{@code com.MATO.logbeta} 是早期那个被否决的独立原型，从未发布，
     * 1.3 转正时一并去掉了。）
     */
    public static String[] selfPackages(Context c) {
        Set<String> set = new HashSet<>();
        try {
            set.add(c.getPackageName());
        } catch (Throwable ignored) {
        }
        set.add("com.MATO.log");
        set.add("com.MATO.log.debug");
        set.add("com.MATO.log.beta");
        set.add("com.MATO.log.beta.debug");
        return set.toArray(new String[set.size()]);
    }

    private static boolean isSelf(Context c, String pkg) {
        String[] self = selfPackages(c);
        for (int i = 0; i < self.length; i++) {
            if (self[i].equals(pkg)) {
                return true;
            }
        }
        return false;
    }

    private static void sort(List<AppInfo> list) {
        final Collator coll = Collator.getInstance(Locale.CHINA);
        Collections.sort(list, new Comparator<AppInfo>() {
            @Override
            public int compare(AppInfo a, AppInfo b) {
                // 普通应用排前面：用户要管的绝大多数是自己装的
                if (a.system != b.system) {
                    return a.system ? 1 : -1;
                }
                int r = coll.compare(a.label, b.label);
                return r != 0 ? r : a.pkg.compareTo(b.pkg);
            }
        });
    }
}
