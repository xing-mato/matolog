package com.MATO.log.rec;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;

import com.MATO.log.R;
import com.MATO.log.data.DbHelper;
import com.MATO.log.data.Event;
import com.MATO.log.data.Session;
import com.MATO.log.util.AppInfo;
import com.MATO.log.util.DateUtil;
import com.MATO.log.util.ErrorLog;
import com.MATO.log.util.Perm;
import com.MATO.log.util.Prefs;

import java.util.List;
import java.util.Set;

/**
 * 自动记录引擎的门面。
 *
 * <p>把 {@link SessionTracker}（纯逻辑，决定「这一段算不算一条」）、
 * {@link UsageReader}（问系统前台是谁）、{@link DbHelper}（落库）三件事串起来，
 * 并管住采样节奏。
 *
 * <p><b>谁启动它</b>：{@link com.MATO.log.service.RecordService}。它是进程内单例；
 * 无障碍服务收到窗口切换时也通过 {@link #get()} 把实时信号递给它 ——
 * 两者在同一个进程里，不需要跨进程通信。
 *
 * <p><b>落库形态</b>：一段片段转成一条普通的 {@link Event}（source=自动），
 * 所以它自动获得现有的日/周/月/年检索、导出、备份、应用锁 —— 混排是需求定下的呈现方式。
 */
public final class Recorder implements SessionTracker.Sink {

    /** 观测状态变化，给界面显示「现在正在记什么」 */
    public interface Listener {
        void onRecordingState(String label, long durationMs);

        void onSessionKept(Event e);

        void onSessionDropped(String label, long durationMs);
    }

    private static volatile Recorder inst;

    /** 日志标签：排查记录链路时用 logcat -s 过滤它 */
    private static final String TAG = "MATOrec";

    /**
     * 诊断开关：把每次采样判定的结果打到 logcat。
     *
     * <p><b>由构建类型决定，不是给用户用的设置项。</b>正式包里
     * {@code res/values/bools.xml} 把它写成 false，调试包里
     * {@code app/src/debug/res/values/bools.xml} 覆盖成 true ——
     * 需要取证时出调试包即可，不必再改源码（也就不会忘了改回来）。
     *
     * <p>它是给小窗 / 分屏这类没法凭空复现的问题用的：装到出问题的那台机器上，
     * {@code adb logcat -s MATOrec} 就能看到每一刻判成了谁、以及为什么。
     * 注意有些 ROM（实测 vivo Z1）默认把应用日志压到 E 级，得先
     * {@code setprop log.tag.MATOrec V} 放开。
     *
     * <p>只打**包名与候选列表**，不带任何记录内容 —— 这是全工程日志的一贯口径
     * （见交接说明的数据面说明）。
     */
    private final boolean traceForeground;

    public static Recorder get() {
        return inst;
    }

    private final Context app;
    private final DbHelper db;
    private final SessionTracker tracker;
    private final AppFilter filter = new AppFilter();
    private final HandlerThread thread;
    private final Handler handler;

    private Listener listener;
    private boolean sampling;
    /** 上一次采样时屏幕是不是亮着。决定下一次隔多久（见 {@link #nextDelayMs}） */
    private boolean screenOn = true;
    private String note = "";
    private long lastRunningRowAt;
    private long lastRunningRowId;
    /** 上一次观测到的前台包名。回看窗口里没有新事件时用它延续 */
    private String lastSamplePkg = "";

    private Recorder(Context c) {
        this.app = c.getApplicationContext();
        this.db = new DbHelper(app);
        this.tracker = new SessionTracker(this, filter);
        this.traceForeground = app.getResources().getBoolean(R.bool.trace_foreground);
        this.thread = new HandlerThread("mato-record");
        this.thread.start();
        this.handler = new Handler(thread.getLooper());
    }

    /** 由 RecordService 调用。重复调用返回已有实例。 */
    public static synchronized Recorder start(Context c) {
        if (inst != null) {
            return inst;
        }
        Recorder r = new Recorder(c);
        r.reload();
        r.resumeOpenSessions();
        r.backfillIfPossible();
        inst = r;
        return r;
    }

    public static synchronized void shutdown() {
        Recorder r = inst;
        inst = null;
        if (r != null) {
            r.stopSampling();
            r.tracker.flush(System.currentTimeMillis());
            try {
                r.thread.quitSafely();
            } catch (Throwable ignored) {
            }
        }
    }

    // ---------------- 配置 ----------------

    /** 名单或开关变化后重新装载（界面上改完立刻生效，不用重启服务） */
    public void reload() {
        // 名单只有一份：排除。系统应用也照记 —— 不再有「是不是系统应用」这一层判定，
        // 所以名单页里没被排除的就是会被记录的（见 AppFilter 的类注释）。
        // 1.3 那份「只记这些」白名单不再装载：它已被去掉，且语义是「除名单外全不记」，
        // 会反过来压制排除名单。库里万一还留着这样的行，现在也不再有任何效果
        // （清理见 DbHelper.clearOnlyRules）。
        filter.self(AppInfo.selfPackages(app))
                .excluded(db.excludedPkgs());
        tracker.timing(Prefs.sampleMs(app), Prefs.minKeepMs(app));
    }

    public SessionTracker tracker() {
        return tracker;
    }

    public AppFilter filter() {
        return filter;
    }

    // 原先还有一个 public 的 note() 访问器，供设置页与权限页显示「当前状态：<前台应用>：
    // <为什么没记>」。那两处文字已在美化时删掉（随前台不断变化、又长、容易被读成故障），
    // 访问器随之失去全部调用点，一并删除；字段本身仍在 statusLine() 里用。

    public void setListener(Listener l) {
        this.listener = l;
        notifyState();
    }

    // ---------------- 采样 ----------------

    public void startSampling() {
        if (sampling) {
            return;
        }
        sampling = true;
        scheduleNext(0);
    }

    public void stopSampling() {
        sampling = false;
        handler.removeCallbacksAndMessages(null);
    }

    private void scheduleNext(long delayMs) {
        if (!sampling) {
            return;
        }
        handler.postDelayed(sampleTask, Math.max(0, delayMs));
    }

    private final Runnable sampleTask = new Runnable() {
        @Override
        public void run() {
            if (!sampling) {
                return;
            }
            long began = SystemClock.uptimeMillis();
            try {
                sampleOnce();
            } catch (Throwable t) {
                note = app.getString(R.string.rec_note_sample_error, t.getClass().getSimpleName());
                // 采样跑在后台的定时器上，出了错界面上通常一点痕迹都没有 ——
                // 不记一笔，这个故障就是完全无声的
                ErrorLog.record(app, "sample", "定时采样失败", t);
            }
            // 下一次按「距上次计划的间隔」算，把本次耗时扣掉，避免误差累积、越采越晚
            long spent = SystemClock.uptimeMillis() - began;
            scheduleNext(Math.max(1000L, nextDelayMs() - spent));
        }
    };

    /**
     * 下一次采样隔多久。
     *
     * <p>亮屏时就是用户设的采样间隔（默认 5 秒，通知要跟得上应用切换）。
     * <b>熄屏时退到至少一分钟</b> —— 那时不会有任何东西切应用，密采纯属白耗电；
     * 而亮屏那一刻由 {@code RecordService} 的屏幕广播调 {@link #sampleNow()} 立刻接上，
     * 不会漏掉开头。
     */
    private long nextDelayMs() {
        long base = Prefs.sampleMs(app);
        if (!screenOn) {
            return Math.max(base, 60_000L);
        }
        return base;
    }

    /** 一次采样 */
    private void sampleOnce() {
        long now = System.currentTimeMillis();

        if (!Perm.usageAccess(app)) {
            note = app.getString(R.string.rec_note_no_perm);
            // 没权限时也要把手上这段收尾，不能让它无限延长
            tracker.screenOff(now);
            notifyState();
            return;
        }

        if (!UsageReader.screenInteractive(app)) {
            screenOn = false;
            note = app.getString(R.string.rec_note_screen_off);
            tracker.screenOff(now);
            notifyState();
            return;
        }
        screenOn = true;

        // 回看窗口的起点：从**当前片段开始那一刻**算起，而不是一个固定长度的滚动窗口。
        //
        // 这一处是修小窗 bug 的关键之一。原先用 max(120s, 采样间隔×2) 的滚动窗口，
        // 窗口一滑过去，当前应用「进入前台」的那条事件就掉出窗口了 —— 状态机再也看不到
        // 它是怎么上来的，只能靠调用方补一个 stillActive 硬撑。而从片段起点回看，
        // 这一段的完整事件流一直都在，谁压着谁一目了然。
        //
        // 上限 30 分钟：再长的片段也没必要每次采样都把半小时的事件捞一遍。
        long from = now - lookBackMsFor(now);

        // 用「前台状态机」而不是「最后一个进入前台的应用」：多窗口（小窗 / 分屏）里
        // 后者会把前台锁死在最后一个抢焦点的应用上 —— 详见 ForegroundState 的类注释。
        // stillActive 传上一次观测到的应用：它是「窗口起点之前就在前台」的那个，
        // 窗口里看不到它进来的那条事件，需要补进来。
        ForegroundState.Now cur = UsageReader.foreground(app, from, now, lastSamplePkg);

        // 状态机给出的栈顶，可能其实已经不在屏幕上了：多窗口里两个应用同时可见时，
        // 事件流无法说明「有焦点的那个退出后，另一个是否接管了焦点」。这时再问系统
        // 一句「谁还可见」来定夺 —— 这是修「小窗关掉后时长继续记到聊天头上」的关键一步。
        String picked = pickForeground(cur, from, now);

        if (traceForeground) {
            android.util.Log.i(TAG, "sample 前景=" + picked
                    + " 状态机栈顶=" + (cur.pkg.isEmpty() ? "(空)" : cur.pkg)
                    + " guess=" + cur.guess
                    + " 候选=" + UsageReader.foregroundAlts(app, from, now, lastSamplePkg)
                    + " 事件=" + traceEvents(from, now));
        }

        if (picked.isEmpty()) {
            if (cur.guess && lastSamplePkg != null && !lastSamplePkg.isEmpty()) {
                // 这段时间一条事件都没有：可能一直没切过应用。只要屏幕还亮着，
                // 就延续上一次观测到的应用（这条兜底路径必须留着）
                observe(lastSamplePkg, now);
            } else {
                // 有事件、但都退出去了（或只剩壳子）：确定现在没有可记的前台应用
                observe("", now);
            }
            return;
        }

        lastSamplePkg = picked;
        observe(picked, now);
    }

    /**
     * 从状态机的结论里挑出真正该记的那个应用。
     *
     * <p><b>为什么不能直接用栈顶</b>：分屏里 A、B 同时可见、B 有焦点。用户把 B 关掉，
     * 事件流只留下「B 退出了」，**不会说 A 是否接管了焦点** —— 这时栈顶会露出 A，
     * 但 A 可能只是还露在屏幕上、并没有焦点。反过来更糟：若栈顶那个其实早就退出去了
     * （事件流没收到它的退出事件），时长会一直记到它头上，正是这次要修的那个 bug。
     *
     * <p>所以规则是**以「还在屏幕上」为准** —— 栈顶可见就用栈顶；栈顶不可见，就从栈顶
     * 以下按顺序找第一个可见的；一个都不可见，才认为「没有前台应用」。
     *
     * <p>拿不到可见性数据时（没权限、系统不返回）一律退回栈顶，保持原有行为不变。
     */
    private String pickForeground(ForegroundState.Now cur, long from, long now) {
        if (cur.empty()) {
            return "";
        }
        Set<String> visible = UsageReader.visiblePackages(app, 24 * 60 * 60_000L);
        if (visible.isEmpty()) {
            return cur.pkg;
        }
        if (visible.contains(cur.pkg)) {
            return cur.pkg;
        }
        for (String alt : UsageReader.foregroundAlts(app, from, now, lastSamplePkg)) {
            if (visible.contains(alt)) {
                return alt;
            }
        }
        return "";
    }

    /**
     * 取证用：把这一轮回看窗口里的事件按时间顺序写成一串。
     *
     * <p>格式 {@code [+包名/活动名, -包名/活动名]}，{@code +} 是进入前台、{@code -} 是离开。
     * 有它才能一眼看出「判定为空」到底是因为窗口里压根没事件，还是因为某条 PAUSED
     * 把应用摘掉了 —— 这是 1.3.4 修「冷启动后显示等待前台应用」时缺的那份数据。
     *
     * <p>只打类型、包名、活动名，**不含任何记录正文**，口径与全工程日志一致。
     * 只在调试包（{@code R.bool.trace_foreground}）里会走到。
     */
    private String traceEvents(long from, long now) {
        List<ForegroundState.Ev> evs = UsageReader.events(app, from, now);
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < evs.size(); i++) {
            ForegroundState.Ev e = evs.get(i);
            if (i > 0) {
                sb.append(',');
            }
            sb.append(e.type == ForegroundState.RESUMED ? '+' : '-').append(e.pkg);
            if (!e.cls.isEmpty()) {
                sb.append('/').append(e.cls);
            }
        }
        return sb.append(']').toString();
    }

    /**
     * 这次采样该往回看多久。
     *
     * <p>取「当前片段已持续的时间 + 一个采样间隔」，让窗口刚好盖住这一段的开头；
     * 没有片段在累积时退回一个采样间隔的量级。夹在 2 分钟与 30 分钟之间：
     * 太短会漏掉片段开头的事件，太长则每次采样都要捞一大段事件流。
     */
    private long lookBackMsFor(long now) {
        long sample = Math.max(1000L, Prefs.sampleMs(app));
        long base = sample * 2;
        long start = tracker.currentStartMs();
        if (start > 0 && start < now) {
            base = (now - start) + sample;
        }
        if (base < 120_000L) {
            base = 120_000L;
        }
        long cap = 30 * 60_000L;
        return base > cap ? cap : base;
    }

    private void observe(String pkg, long now) {
        boolean allowed = filter.allow(pkg);
        String label = allowed ? AppInfo.label(app, pkg) : "";
        tracker.observe(now, pkg, label);

        if (pkg.isEmpty()) {
            note = app.getString(R.string.rec_note_nothing);
        } else if (!allowed) {
            note = AppInfo.label(app, pkg) + "：" + filter.explain(app, pkg);
        } else {
            note = "";
        }
        persistRunningThrottled(now);
        notifyState();
    }


    /**
     * 立刻采一次，不等下一个周期。
     *
     * <p>用于「屏幕刚亮起／刚解锁」这类时刻：用户可能马上就切进某个应用，
     * 等下一个整分钟会漏掉开头那一段。
     *
     * <p>放在记录线程上执行，避免和周期采样并发动同一份状态。
     */
    public void sampleNow() {
        sampleNow(null);
    }

    /**
     * 立刻采一次，采完之后在**同一个记录线程**上回调 {@code after}。
     *
     * <p>通知栏那个「刷新」要用它：采样是异步的，不等到采完就刷新通知，
     * 读到的还是上一次的状态，看起来像点了没反应。
     *
     * @param after 可以为 null
     */
    public void sampleNow(final Runnable after) {
        handler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    sampleOnce();
                } catch (Throwable t) {
                    android.util.Log.w(TAG, "即时采样出错（已忽略）", t);
                    ErrorLog.record(app, "sample", "即时采样（通知刷新）失败", t);
                }
                if (after != null) {
                    try {
                        after.run();
                    } catch (Throwable ignored) {
                    }
                }
            }
        });
    }

    // ---------------- 落库 ----------------
    @Override
    public void onSessionFinished(Session finished) {
        if (finished == null) {
            return;
        }
        if (finished.durationMs() < Prefs.minKeepMs(app)) {
            // 兜底的第二道阈值：引擎按它当时的设置判定够长，但收尾这一刻的设置
            // 已经变了（用户刚把「低于 N 分钟不计」调大）。规则要一致 ——
            // 最终不够长就不留记录，把累积期间写下的那一行一并收回。
            dropRunningRow(finished);
            return;
        }
        Event e = finished.toEvent();
        // 收尾：关掉累积期间写下的那一行，而不是再插一条新的。
        // （早期版本在这里新建行，结果每段都留下「一条空壳 + 一条记录」两条）
        long id = db.finishEvent(e);
        android.util.Log.i(TAG, "片段结束 " + (finished.label == null ? finished.pkg : finished.label)
                + " " + (finished.durationMs() / 60000) + "min → row " + id);
        lastRunningRowId = 0;
        if (listener != null) {
            listener.onSessionKept(e);
        }
    }

    /**
     * 片段不够长，被丢掉了。
     *
     * <p>这一段在累积期间已经落过库（{@link #persistRunningThrottled}），所以这里要做的
     * 是把那一行收回去。少了这一步，「低于 N 分钟不计」就只是「不通知落库」，记录照样
     * 躺在时间轴上 —— 这就是用户报的「设了不计，却还是生成了事件」。
     */
    @Override
    public void onSessionDropped(Session dropped) {
        if (dropped == null) {
            return;
        }
        dropRunningRow(dropped);
        if (listener != null) {
            listener.onSessionDropped(dropped.label, dropped.durationMs());
        }
    }

    /** 收回这一段在库里那一行，并把行号句柄清掉 */
    private void dropRunningRow(Session s) {
        int removed = db.deleteRunning(s.pkg, s.startMs, lastRunningRowId);
        lastRunningRowId = 0;
        if (removed > 0) {
            android.util.Log.i(TAG, "片段丢弃 "
                    + ((s.label == null || s.label.isEmpty()) ? s.pkg : s.label)
                    + " " + (s.durationMs() / 1000) + "s → 收回 " + removed + " 行");
        }
    }

    private long joinGapMs() {
        return Math.max(5 * 60_000L, Prefs.sampleMs(app) * 3);
    }

    /**
     * 把「正在累积」的片段写进库（end_ms=0 表示进行中）。
     *
     * <p>为什么进行中的也要落库：进程随时可能被系统回收，不落库的话这一段的开头就丢了。
     * 落一条 end_ms=0 的记录，重启时能接着往后延长。
     *
     * <p><b>注意这里是「延长」而不是「新增」</b>：同一应用同一时刻只应该有一条
     * 正在进行的记录。早期版本这里错用了合并「已收尾记录」的方法，结果每写一次
     * 就新插一行 —— 一分钟里出现三条起点相同、永远收不了尾、时长恒为 0 的记录。
     */
    private void persistRunningThrottled(long now) {
        String cur = tracker.currentPkg();
        if (cur == null || cur.isEmpty()) {
            return;
        }
        long start = tracker.currentStartMs();
        if (start <= 0) {
            return;
        }
        if (now - lastRunningRowAt < 60_000L && lastRunningRowId != 0) {
            return;
        }
        lastRunningRowAt = now;

        Event e = new Event();
        e.time = start;
        e.text = "";
        e.createdAt = now;
        e.endMs = 0;
        e.source = Session.SRC_AUTO;
        e.appPkg = cur;
        e.appLabel = tracker.currentLabel();
        long id = db.saveRunning(e);
        lastRunningRowId = id;
        // 历史脏数据可能留下重复的未收尾行，顺手清掉
        int pruned = db.pruneDuplicateOpen(cur);
        if (pruned > 0) {
            android.util.Log.i(TAG, "pruned " + pruned + " duplicate open rows for " + cur);
        }
    }

    /**
     * 服务启动时处理上次留下的「进行中」记录（end_ms=0）。
     *
     * <p>判定依据是**入库时刻**（created_at）距现在多久，而不是片段起点：
     * 一条进行中的记录可能是三小时前开始、但一直写到现在，也可能刚写就被杀了。
     * created_at 是「上次写它」的时刻，正好用来判断「断了多久」。
     *
     * <ul>
     *   <li>断得不久（在允许合并的空隙内）→ 把状态接回来继续累积，
     *       用户不该看到一次连续使用被切成两条；</li>
     *   <li>断得久了 → 这条已经失去意义：既不能延长（中间不知道发生了什么），
     *       也没有时长（end_ms=0 时它根本不算一段）→ 直接删掉，
     *       别在时间轴上留一条时长 0 的空壳。</li>
     * </ul>
     *
     * <p>这里**没有**「断得久但已积累一定长度就按最后一次确认收尾」这条分支
     * （早期注释里写过，但代码一直没实现）。不补它的理由是：未收尾的行本身不含时长
     * 信息，时长只存在于 tracker 的内存里，进程一死就没了；而拿 created_at 当收尾时刻
     * 会凭空造出一段横跨几小时的记录 —— 那几小时里用什么应用我们并不知道。
     * 宁可删掉，也不要编。
     */
    private void resumeOpenSessions() {
        List<Event> open = db.openAutoEvents();
        if (open.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        long maxJoin = joinGapMs();
        for (Event e : open) {
            long sinceWritten = now - (e.createdAt > 0 ? e.createdAt : e.time);
            if (sinceWritten <= maxJoin) {
                // 刚断的：把状态接回来继续累积。
                //
                // 这里**不要**先去关掉那一行。早期版本调 db.closeEvent(id, time)，
                // 把结束时刻写成起点 —— 于是多出一条时长 0 的空壳，而当时以为之后
                // 会有一步「合并相邻片段」把它并回去，那一步其实从没被调用过
                // （见 DbHelper.insertOrMerge），空壳就永久留在了时间轴上。
                // 让这一行保持未收尾才对：saveRunning 会延长它，finishEvent 会按
                // 行 id 正常关掉它，一段自始至终只有一行。
                tracker.resume(e.appPkg, e.appLabel, e.time, now);
                lastSamplePkg = e.appPkg;
            } else {
                // 陈旧的空壳：删掉，不留噪音
                db.delete(e.id);
            }
        }
    }

    /**
     * 断档补记。
     *
     * <p>服务被系统回收、或用户中途关过自动记录时，这段时间里系统其实一直记着使用情况。
     * 回来看一眼，把真实发生过的切换补进来 —— 补的是事实，不是推测。
     *
     * <p>只补最近 6 小时：再往前翻意义不大，也容易把很久以前的碎片一次性灌进列表。
     */
    private void backfillIfPossible() {
        if (!Perm.usageAccess(app)) {
            return;
        }
        long now = System.currentTimeMillis();
        long from = now - 6 * 3600_000L;
        List<UsageReader.Snap> events = UsageReader.resumeEvents(app, from, now);
        if (events.size() < 2) {
            return;
        }
        // 只补「上次确认之后」的部分：已经记过的区间再喂一遍会重复计时
        long anchor = tracker.currentLastSeenMs();
        int fed = 0;
        long fedAt = 0;
        for (UsageReader.Snap e : events) {
            if (e.atMs <= anchor) {
                continue;
            }
            // 同一秒内的重复事件跳掉，避免把同一段切碎
            if (fed > 0 && e.atMs - fedAt < 1000L) {
                continue;
            }
            tracker.observe(e.atMs, e.pkg, AppInfo.label(app, e.pkg));
            fedAt = e.atMs;
            fed++;
        }
        if (fed > 0) {
            // 补到最后一个事件为止就收尾：之后交给正常采样继续累积。
            // 不能 flush(now)，否则会把「最后一次切换到现在」这段空白算给最后一个应用。
            tracker.flush(fedAt);
        }
    }

    // ---------------- 界面回调 ----------------

    private void notifyState() {
        Listener l = listener;
        if (l != null) {
            l.onRecordingState(tracker.currentLabel(), tracker.currentDurationMs());
        }
    }

    /** 服务/通知用的一句话摘要 */
    public String summary() {
        String cur = tracker.currentPkg();
        if (cur == null || cur.isEmpty()) {
            return note.isEmpty() ? app.getString(R.string.rec_note_waiting) : note;
        }
        return tracker.currentLabel() + " · " + DateUtil.duration(tracker.currentDurationMs());
    }

    public String statusLine() {
        if (!Perm.usageAccess(app)) {
            return app.getString(R.string.rec_note_no_perm_short);
        }
        if (!note.isEmpty()) {
            return note;
        }
        // 不写「按分钟」：采样间隔是用户可调的（默认 5 秒），把周期写进这句话
        // 就成了一句随时会过期的话
        return app.getString(R.string.rec_note_recording);
    }

    /** 今天已经留下了多少条自动记录（给通知显示） */
    public int todayKept() {
        long today = DateUtil.startOfDay(System.currentTimeMillis());
        return db.countByRange(today, today + 86400000L);
    }
}
