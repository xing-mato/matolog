package com.MATO.log.rec;

import com.MATO.log.data.Session;

/**
 * 自动记录的分段引擎。
 *
 * <p><b>它做什么</b>：外部每隔一段时间告诉它「现在前台是谁」（{@link #observe}），
 * 它把这串离散的观测切成一段段「连续在用某个应用」的区间，每段结束时按规则决定
 * 要不要留下一条记录。
 *
 * <p><b>为什么不按固定间隔开新记录</b>：那样「连续用了一小时同一个应用」会被切成
 * 60 条一分钟的记录。用户要的是「事件」，所以必须由应用切换（或熄屏 / 采样断档）来切段。
 *
 * <p><b>时长怎么算</b>：结束时刻取「最后一次确认它还在前台」的时刻，而不是「发现它被
 * 切走」的时刻。宁可少算几十秒，也不把用户已经离开的那段时间记进去。
 *
 * <p><b>为什么不碰 Android API</b>：整套判定（切段、断档、最小时长、名单过滤）是最容易
 * 出细微错误的地方。做成纯 Java 就是为了能用 javac 直接跑用例验证，不必每次都装到设备上试。
 * 注意本仓库目前**没有**这份用例：{@code tools/} 下只有 Date/Json/Crypto/Level/Attach 五个
 * Check，早先那个 {@code tools/SessionCheck.java} 属于已废弃的独立原型 {@code MATOlog-beta/}，
 * 从没进过本工程。要补的话这里是最该补的地方。
 */
public final class SessionTracker {

    /**
     * 片段算完了交给谁。
     *
     * <p>两个回调**互斥**：够长的走 {@link #onSessionFinished}，不够长的走
     * {@link #onSessionDropped}。落库方必须两个都接 —— 只接前者会漏掉「回收」那一步，
     * 而这一段的记录在它一开始落库时就已经写进去了。
     */
    public interface Sink {
        void onSessionFinished(Session finished);

        /**
         * 片段不够长，被丢掉了。
         *
         * <p><b>为什么丢弃也要回调</b>：这一段的记录在它**开始**时就已经落库了 ——
         * 不这样做，进程被杀时这一段的开头就没了。所以「不够长」这个结论必须回传给
         * 落库方，让它把那一行收回去。少了这一步，时间轴上会留下一条没有时长、
         * 也永远不会结束的自动记录，表现就是「明明设了低于 N 分钟不计，却还是生成了事件」。
         */
        void onSessionDropped(Session dropped);
    }

    private final Sink sink;
    private final AppFilter filter;

    /** 判定「还是同一个应用」时允许的时间容差 */
    private long sameSlotMs = 90_000L;
    /** 采样断档超过这个倍数就当作中间断过，不许把前后接上 */
    private int gapFactor = 3;
    /**
     * 断档判定的**绝对下限**：3 分钟。
     *
     * <p>没有它的话，采样间隔一调密，断档判定就跟着变紧 —— 采样间隔 5 秒时
     * {@code sampleMs * gapFactor} 只有 15 秒，漏掉两三拍就会被当成「中间断过」，
     * 把一段连续的记录切成两条。断档要防的是「进程被杀 / 长时间没采样」，
     * 那是分钟级的现象，与采样密度无关。
     */
    private static final long MIN_GAP_MS = 3 * 60_000L;

    private long minKeepMs = 5 * 60_000L;
    private long sampleMs = 60_000L;
    /** 当前生效的断档阈值，由 {@link #timing} 算好 */
    private long gapMs = MIN_GAP_MS;

    // ---- 当前正在累积的片段 ----
    private String pkg;
    private String label;
    private long startMs;
    private long lastSeenMs;
    /** 当前是不是在记（被名单排除时仍然更新 lastSeen，但不累积时长） */
    private boolean counting;

    // ---- 顺带统计，给界面显示「为什么刚才那个没记上」 ----
    private long sampledCount;
    private long keptCount;
    private long droppedCount;
    private String lastDroppedLabel = "";
    private long lastDroppedMs;

    public SessionTracker(Sink sink, AppFilter filter) {
        this.sink = sink;
        this.filter = filter;
    }

    public SessionTracker timing(long sampleMs, long minKeepMs) {
        if (sampleMs > 0) {
            this.sampleMs = sampleMs;
            this.sameSlotMs = Math.max(90_000L, sampleMs * 2);
            this.gapMs = Math.max(MIN_GAP_MS, sampleMs * gapFactor);
        }
        if (minKeepMs >= 0) {
            this.minKeepMs = minKeepMs;
        }
        return this;
    }

    public SessionTracker gapFactor(int f) {
        if (f >= 2) {
            this.gapFactor = f;
            this.gapMs = Math.max(MIN_GAP_MS, sampleMs * gapFactor);
        }
        return this;
    }

    public long minKeepMs() {
        return minKeepMs;
    }

    public long sampleMs() {
        return sampleMs;
    }

    public long sampledCount() {
        return sampledCount;
    }

    public long keptCount() {
        return keptCount;
    }

    public long droppedCount() {
        return droppedCount;
    }

    public String lastDroppedLabel() {
        return lastDroppedLabel;
    }

    public long lastDroppedMs() {
        return lastDroppedMs;
    }

    /** 当前正在累积的包名，没有则空串 */
    public String currentPkg() {
        return counting && pkg != null ? pkg : "";
    }

    public String currentLabel() {
        return counting ? (label == null ? "" : label) : "";
    }

    /** 当前片段的起点；没有则 0 */
    public long currentStartMs() {
        return counting ? startMs : 0;
    }

    /** 当前片段最后一次被确认的时刻 */
    public long currentLastSeenMs() {
        return counting ? lastSeenMs : 0;
    }

    /** 当前片段已确认的时长 */
    public long currentDurationMs() {
        if (!counting) {
            return 0;
        }
        long d = lastSeenMs - startMs;
        return d > 0 ? d : 0;
    }

    /** 这一条现在够不够长、值不值得留 */
    public boolean currentWouldKeep() {
        return counting && currentDurationMs() >= minKeepMs;
    }

    /**
     * 一次前台观测。
     *
     * @param nowMs 观测时刻
     * @param pkg   前台应用包名；空串表示「当前没有可记的前台应用」
     * @param label 应用显示名，落库时留个可读的名字
     */
    public void observe(long nowMs, String pkg, String label) {
        sampledCount++;
        if (pkg == null) {
            pkg = "";
        }

        // 采样断档：进程可能刚被拉起来，或者中间休眠了一大段。
        // 此时不能把断档前后的同一个应用接成一条，否则会把中间几小时都算进去。
        // 阈值是 gapMs（带 3 分钟下限），不是采样间隔本身 —— 见 MIN_GAP_MS 的说明
        if (counting && nowMs - lastSeenMs > gapMs) {
            finishAs(lastSeenMs);
        }

        // 记不记只看排除名单与系统界面（见 AppFilter）。这里不再传「是不是系统应用」——
        // 1.3.3 之前要传，因为系统应用默认不记；那条规则已经删了
        boolean allowed = filter != null && filter.allow(pkg);

        if (allowed) {
            if (counting && sameApp(pkg) && nowMs - lastSeenMs <= sameSlotMs) {
                lastSeenMs = nowMs;
                this.label = pickLabel(this.label, label);
                return;
            }
            if (counting) {
                // 换了应用：上一段结束。结束时刻用「上一次确认」，
                // 不把两次采样之间那段不确定的时间算给任何一个应用。
                finishAs(lastSeenMs);
            }
            begin(nowMs, pkg, label);
            return;
        }

        // 不在记录范围内：先把手上这段收尾，再清空
        if (counting) {
            finishAs(lastSeenMs);
        }
        clear();
        lastSeenMs = nowMs;
    }

    /** 用户熄屏 / 锁屏 / 手动暂停：立刻收尾，别把熄屏后的时间算进去 */
    public void screenOff(long nowMs) {
        if (counting) {
            finishAs(Math.min(lastSeenMs, nowMs));
        }
        clear();
        lastSeenMs = nowMs;
    }

    /** 设备重启或服务重建后，用一条「已存在的未完成片段」把状态接回来 */
    public void resume(String pkg, String label, long startMs, long lastSeenMs) {
        if (pkg == null || pkg.isEmpty() || startMs <= 0) {
            return;
        }
        this.pkg = pkg;
        this.label = label;
        this.startMs = startMs;
        this.lastSeenMs = lastSeenMs > 0 ? lastSeenMs : startMs;
        this.counting = true;
    }

    /** 服务停止时把正在累积的片段收尾，不留悬空记录 */
    public void flush(long nowMs) {
        if (counting) {
            finishAs(Math.min(lastSeenMs, nowMs));
        }
        clear();
    }

    // ---------------- 内部 ----------------

    private boolean sameApp(String other) {
        return pkg != null && pkg.equals(other);
    }

    private void begin(long nowMs, String pkg, String label) {
        this.pkg = pkg;
        this.label = label == null ? "" : label;
        this.startMs = nowMs;
        this.lastSeenMs = nowMs;
        this.counting = true;
    }

    private void clear() {
        pkg = null;
        label = null;
        startMs = 0;
        counting = false;
    }

    private static String pickLabel(String old, String now) {
        if (now != null && !now.isEmpty()) {
            return now;
        }
        return old == null ? "" : old;
    }

    /**
     * 结束当前片段：时长够就回调出去；不够就丢掉，并把这一段在累积期间落库的那一行
     * 一并交给落库方收回去（仍记一笔统计，好让界面能回答「为什么刚才那个应用没记上」）。
     */
    private void finishAs(long endMs) {
        if (!counting) {
            return;
        }
        long end = Math.max(endMs, startMs);
        long dur = end - startMs;

        if (dur >= minKeepMs) {
            Session s = new Session(pkg, label, startMs);
            s.endMs = end;
            s.lastSeenMs = end;
            s.source = Session.SRC_AUTO;
            keptCount++;
            if (sink != null) {
                sink.onSessionFinished(s);
            }
        } else {
            droppedCount++;
            lastDroppedLabel = (label == null || label.isEmpty())
                    ? (pkg == null ? "" : pkg) : label;
            lastDroppedMs = dur;
            // 这一段的记录早在它开始时就写进库了（落库方一发现片段开始就会写下
            // 一行「进行中」，不这么做的话进程被杀就丢掉了开头），所以「不够长」
            // 不能只是自己心里有数 —— 得让落库方把那一行收回去，否则设置里那句
            // 「低于 N 分钟不计」就只是不落库，记录照样留在时间轴上。
            if (sink != null) {
                Session s = new Session(pkg, label, startMs);
                s.endMs = end;
                s.lastSeenMs = end;
                s.source = Session.SRC_AUTO;
                sink.onSessionDropped(s);
            }
        }
        clear();
    }
}
