package com.MATO.log.rec;

import android.app.usage.UsageEvents;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.os.Build;

import com.MATO.log.util.Perm;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 问系统「刚才前台是谁」。
 *
 * <p><b>为什么用 UsageEvents 而不是 queryUsageStats</b>：queryUsageStats 给的是按时间桶
 * 聚合过的统计，粒度受系统控制，拿不到「切换发生的那一秒」。queryEvents 能拿到真实的
 * MOVE_TO_FOREGROUND 事件流，用它推断前台应用既准确，又能顺便知道切换时刻。
 *
 * <p><b>权限</b>：需要用户在系统设置里授予「使用情况访问」。没授权时系统不报错，
 * 只是返回空事件流 —— 所以调用方必须能区分「真的没人在用」和「没有权限」，
 * 这也是 {@link #hasPermission} 存在的原因。
 *
 * <p><b>版本差异</b>：Android 10（API 29）起 MOVE_TO_FOREGROUND 改名为 ACTIVITY_RESUMED，
 * 两者取值相同（都是 1），所以只判一次即可。
 */
public final class UsageReader {

    /** 一次观测的结果 */
    public static final class Snap {
        public final String pkg;
        public final long atMs;

        Snap(String pkg, long atMs) {
            this.pkg = pkg == null ? "" : pkg;
            this.atMs = atMs;
        }

        public boolean empty() {
            return pkg.isEmpty();
        }
    }

    private static final Snap NONE = new Snap("", 0);

    private UsageReader() {
    }

    /** 有没有「使用情况访问」权限 */
    public static boolean hasPermission(Context c) {
        return Perm.usageAccess(c);
    }

    /**
     * 是不是「界面壳子」型的包名。
     *
     * <p>名单在 {@link AppFilter#SHELL} —— 规则只有一个出处。这里保留这个入口，
     * 是因为采样层拿它做一次省事的粗筛（壳子包连状态机都不必喂），
     * 但**判定归判定**：真正的「记不记」仍然由 {@link AppFilter#allow} 说了算。
     */
    public static boolean isShell(String pkg) {
        return AppFilter.isShell(pkg);
    }

    /**
     * 回看最近一段时间，返回**最后一次**「某应用进入前台」的结果。
     *
     * @param lookBackMs 回看窗口。比采样间隔略大即可；太大反而会被很久以前的事件干扰。
     */
    public static Snap lastForeground(Context c, long lookBackMs) {
        long now = System.currentTimeMillis();
        return lastForegroundBetween(c, now - Math.max(1000L, lookBackMs), now);
    }

    /**
     * 一段时间里的**前台状态事件流**（进入 / 离开前台都要），按时间升序。
     *
     * <p><b>为什么不复用 {@link #lastForeground}</b>：那个只回「最后一个进入前台的应用」，
     * 拿不到「谁已经离开了」。小窗场景里这正是致命的 —— 详情见
     * {@link ForegroundState} 的类注释。
     *
     * <p>只收「进入前台」与「离开前台」两类事件，界面壳子（桌面、systemui 等）一律滤掉 ——
     * 它们进前台不算「用户在做事」。
     *
     * <p><b>活动类名要带上。</b>系统的事件是按**活动**发的：同一个包里从一个活动换到
     * 另一个活动（启动页 → 主界面、设置里点进二级页）会发出 {@code RESUMED(包)}
     * 紧跟 {@code PAUSED(包)}。只传包名的话，状态机会把后到的那条 PAUSED 当成
     * 「整个应用退出了」，把包从栈里摘掉 —— 表现就是冷启动之后的一两分钟里
     * 一律显示「没有前台应用」。详见 {@link ForegroundState} 里记账键的说明。
     */
    public static List<ForegroundState.Ev> events(Context c, long fromMs, long toMs) {
        List<ForegroundState.Ev> out = new ArrayList<>();
        if (toMs <= fromMs) {
            return out;
        }
        UsageStatsManager usm =
                (UsageStatsManager) c.getSystemService(Context.USAGE_STATS_SERVICE);
        if (usm == null) {
            return out;
        }
        UsageEvents events;
        try {
            events = usm.queryEvents(fromMs, toMs);
        } catch (Throwable t) {
            return out;
        }
        if (events == null) {
            return out;
        }
        UsageEvents.Event e = new UsageEvents.Event();
        while (events.hasNextEvent()) {
            if (!events.getNextEvent(e)) {
                break;
            }
            int type = e.getEventType();
            boolean resumed = type == UsageEvents.Event.ACTIVITY_RESUMED;
            // 这套常量里 ACTIVITY_PAUSED 与 MOVE_TO_BACKGROUND 同值，
            // ACTIVITY_STOPPED 也与它们同值（都是 2），所以判一次就覆盖了三种写法
            boolean left = type == UsageEvents.Event.ACTIVITY_PAUSED;
            if (!resumed && !left) {
                continue;
            }
            String p = e.getPackageName();
            if (p == null || p.isEmpty() || isShell(p)) {
                continue;
            }
            out.add(new ForegroundState.Ev(
                    resumed ? ForegroundState.RESUMED : ForegroundState.PAUSED,
                    p, className(e), e.getTimeStamp()));
        }
        return out;
    }

    /**
     * 活动类名；拿不到就返回空串（状态机退回按包名记账，行为与 1.3.3 一致）。
     *
     * <p>单独包一层 try：个别 ROM 在这上面抛过异常，而一条事件取不到类名
     * 不该把整轮采样带崩 —— 那样连「谁在前台」都算不出来了。
     */
    private static String className(UsageEvents.Event e) {
        try {
            return e.getClassName();
        } catch (Throwable t) {
            return "";
        }
    }

    /**
     * 直接算出「此刻前台是谁」。
     *
     * <p>与 {@link #lastForeground} 的区别见 {@link #events}：多窗口（小窗 / 分屏）里
     * 只有这个方法能得到正确结果。
     *
     * @param stillActive 回看窗口起点之前就已经在前台的应用。窗口看不到它「进入前台」的
     *                    那条事件，需要调用方把它补进来，否则窗口一截断就会误判成
     *                    「没有前台应用」。传空串表示没有。
     */
    public static ForegroundState.Now foreground(Context c, long fromMs, long toMs,
                                                 String stillActive) {
        List<ForegroundState.Ev> evs = events(c, fromMs, toMs);
        if (evs.isEmpty()) {
            // 一条事件都没有：可能一直没切过应用。把调用方手上那个补进来，
            // 让状态机有东西可算；补不出来就如实回一个 guess，由调用方沿用旧值
            if (stillActive != null && !stillActive.isEmpty()) {
                return new ForegroundState.Now(stillActive, true, 0);
            }
            return new ForegroundState.Now("", true, 0);
        }
        List<ForegroundState.Ev> full = ForegroundState.prepend(evs, stillActive, fromMs);
        return ForegroundState.of(full);
    }

    /**
     * 栈顶以下还在「压着」的那些应用（按压得越上面越靠前）。
     *
     * <p>它们是「进过前台、之后被压住、又没明确退出过」的那批 —— 多窗口场景里
     * 需要用「谁还可见」去筛一遍，才知道其中有没有接管了焦点的那个。
     * 见 {@link #visiblePackages} 与 Recorder 里的挑选逻辑。
     */
    public static List<String> foregroundAlts(Context c, long fromMs, long toMs,
                                              String stillActive) {
        List<ForegroundState.Ev> evs = events(c, fromMs, toMs);
        if (evs.isEmpty()) {
            return new ArrayList<>();
        }
        return ForegroundState.altsOf(ForegroundState.prepend(evs, stillActive, fromMs));
    }

    /**
     * 现在**还在屏幕上可见**的那些应用。
     *
     * <p><b>为什么需要它</b>：事件流只说明「谁进入了 / 离开了前台」，说不清
     * 「谁还露在屏幕上」。分屏里两个应用同时可见、只有一个有焦点，而当有焦点的那个
     * 退到后台时，事件流只留下「它退出了」，**不会告诉我们另一个是否接管了焦点**。
     * 这种时候得再问一句「谁还在屏幕上」才能定夺 —— 不然就会把早就没有焦点的那个
     * 当成前台，一路把时长记到它头上。
     *
     * <p>用的口径是 {@code totalTimeVisible > 0}：这是系统自己统计的「可见时长」，
     * 比起猜事件语义可靠。
     *
     * <p><b>这份结果带 15 秒缓存</b>：采样间隔降到 5 秒之后，这个方法一分钟会被问 12 次，
     * 而它要 {@code queryUsageStats} 聚合一整天的统计，是整轮采样里最贵的一步。
     * 可见性不会在几百毫秒内变来变去，缓存 15 秒足够；
     * 真正要抢时间的是「事件流」那一路，那条没有被缓存。
     *
     * @param lookBackMs 统计窗口。系统按时间桶聚合，给太小的窗口会拿不到数据，
     *                   所以这里用小时级的窗口
     */
    public static Set<String> visiblePackages(Context c, long lookBackMs) {
        long now = System.currentTimeMillis();
        if (visibleCache != null && now - visibleCacheAt < VISIBLE_TTL_MS) {
            return visibleCache;
        }
        Set<String> out = queryVisible(c, lookBackMs, now);
        visibleCache = out;
        visibleCacheAt = now;
        return out;
    }

    /** 缓存：见 {@link #visiblePackages} 的说明 */
    private static Set<String> visibleCache;
    private static long visibleCacheAt;
    private static final long VISIBLE_TTL_MS = 15_000L;

    private static Set<String> queryVisible(Context c, long lookBackMs, long now) {
        Set<String> out = new HashSet<>();
        // GetTotalTimeVisible 是 API 29 才有的。低版本上拿不到可见性 —— 那就如实什么都不返回，
        // 让调用方退回「只用事件流」的老路（宁可不做这层判断，也不用错的数据去判断）。
        // 注意别拿 getLastTimeUsed 凑数：那是「最后一次进入前台」，退出后仍非零，区分不出还在不在。
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return out;
        }
        UsageStatsManager usm =
                (UsageStatsManager) c.getSystemService(Context.USAGE_STATS_SERVICE);
        if (usm == null) {
            return out;
        }
        List<UsageStats> stats;
        try {
            stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY,
                    now - Math.max(60_000L, lookBackMs), now);
        } catch (Throwable t) {
            return out;
        }
        if (stats == null) {
            return out;
        }
        for (UsageStats s : stats) {
            if (s == null) {
                continue;
            }
            String p = s.getPackageName();
            if (p == null || p.isEmpty() || isShell(p)) {
                continue;
            }
            // 可见时长大于 0 就算「还露在屏幕上」
            if (s.getTotalTimeVisible() > 0) {
                out.add(p);
            }
        }
        return out;
    }

    public static Snap lastForegroundBetween(Context c, long fromMs, long toMs) {
        List<Snap> all = resumeEvents(c, fromMs, toMs);
        if (all.isEmpty()) {
            return NONE;
        }
        return all.get(all.size() - 1);
    }

    /**
     * 把一段时间里的「进入前台」事件按时间升序取出来。
     *
     * <p>两个用途：一是取最后一次以判断当前前台；二是服务被系统回收过之后，
     * 用这段时间的真实事件把断档补回来 —— 补的是实际发生过的事，不是猜的。
     */
    public static List<Snap> resumeEvents(Context c, long fromMs, long toMs) {
        List<Snap> out = new ArrayList<>();
        if (toMs <= fromMs) {
            return out;
        }
        UsageStatsManager usm =
                (UsageStatsManager) c.getSystemService(Context.USAGE_STATS_SERVICE);
        if (usm == null) {
            return out;
        }
        UsageEvents events;
        try {
            events = usm.queryEvents(fromMs, toMs);
        } catch (Throwable t) {
            return out;
        }
        if (events == null) {
            return out;
        }
        UsageEvents.Event e = new UsageEvents.Event();
        while (events.hasNextEvent()) {
            if (!events.getNextEvent(e)) {
                break;
            }
            if (e.getEventType() != UsageEvents.Event.MOVE_TO_FOREGROUND) {
                continue;
            }
            String p = e.getPackageName();
            if (p == null || p.isEmpty() || isShell(p)) {
                continue;
            }
            out.add(new Snap(p, e.getTimeStamp()));
        }
        return out;
    }

    /**
     * 屏幕是否处于亮屏且已解锁的状态。
     *
     * <p>锁屏界面在系统里也算一个「前台应用」，但它不是用户在做的事。
     * 熄屏期间不应该给任何应用累积时长。
     */
    public static boolean screenInteractive(Context c) {
        try {
            android.os.PowerManager pm =
                    (android.os.PowerManager) c.getSystemService(Context.POWER_SERVICE);
            return pm != null && pm.isInteractive();
        } catch (Throwable t) {
            return true;
        }
    }
}
