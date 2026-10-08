import com.MATO.log.data.Session;
import com.MATO.log.rec.AppFilter;
import com.MATO.log.rec.SessionTracker;

import java.util.ArrayList;
import java.util.List;

/**
 * 分段引擎（{@link SessionTracker}）的离线用例 —— 纯 Java，不需要设备。
 *
 * <p><b>为什么要这份</b>：交接说明里点名的欠账就是它 —— 1.3.3 补的
 * {@code ForegroundCheck} 测的是前台状态机（谁在前台），而「怎么把一串观测切成
 * 一条条记录、时长按哪一刻收尾、断档算不算断」这套判定，一直只有注释没有断言。
 * 1.3.4 把采样间隔从 60 秒降到 5 秒，动到了断档判定，正好是必须先把口径钉住的时候。
 *
 * <p><b>它守的是什么</b>：
 * <ul>
 *   <li>连续用同一个应用是**一条**（不是每个采样点一条）；</li>
 *   <li>时长按「最后一次确认它还在前台」收尾，**不把已经离开的时间算进去**；</li>
 *   <li>断档阈值有 3 分钟下限 —— 采样调密之后，漏两三拍不该把一段切成两条；</li>
 *   <li>不够长的片段走 dropped（落库方要据此把那一行收回去，不能只回调 kept）。</li>
 * </ul>
 *
 * <p>用法（与其它 Check 一致，只数 ASCII 的 pass=/fail=）：
 * <pre>
 *   javac -encoding UTF-8 -cp "&lt;release classes&gt;" -d out tools/SessionCheck.java
 *   java -cp "out;&lt;release classes&gt;" SessionCheck
 * </pre>
 */
public final class SessionCheck {

    private static int pass = 0;
    private static int fail = 0;

    private static final String A = "com.example.a";
    private static final String B = "com.example.b";
    /** 固定基准，不依赖真实时钟 */
    private static final long T0 = 1_700_000_000_000L;

    private static final long SEC = 1000L;
    private static final long MIN = 60_000L;

    public static void main(String[] args) {
        sameAppIsOneSession();
        switchClosesAtLastSeen();
        shortGapDoesNotCutAtFastSampling();
        longGapCuts();
        dropBelowMinKeep();
        emptyPkgEndsSession();
        screenOffEndsAtLastSeen();

        System.out.println("pass=" + pass + " fail=" + fail);
        if (fail > 0) {
            System.exit(1);
        }
    }

    // ---------------- 用例 ----------------

    /**
     * 连续用同一个应用 = **一条**，而不是每个采样点一条。
     *
     * <p>这是整个分段设计的前提：用户要的是「事件」，按采样点开记录会把一小时切成
     * 七百多条（5 秒采样的话更夸张）。
     */
    private static void sameAppIsOneSession() {
        Sink sink = new Sink();
        SessionTracker t = tracker(sink, 5 * SEC, 0);
        for (int i = 0; i <= 10; i++) {
            t.observe(T0 + i * 5 * SEC, A, "A");
        }
        t.flush(T0 + 50 * SEC);

        checkInt("连续观测只产生一条", 1, sink.kept.size());
        Session s = sink.kept.get(0);
        checkLong("起点是第一次确认", T0, s.startMs);
        checkLong("终点是最后一次确认", T0 + 50 * SEC, s.endMs);
    }

    /**
     * 换应用时，上一段结束在**上一次确认它的时刻**，不是「发现它被切走」的时刻。
     *
     * <p>宁可少算几十秒（现在是几秒），也不把用户已经离开的那段记进去。
     */
    private static void switchClosesAtLastSeen() {
        Sink sink = new Sink();
        SessionTracker t = tracker(sink, 5 * SEC, 0);
        t.observe(T0, A, "A");
        t.observe(T0 + 5 * SEC, A, "A");
        t.observe(T0 + 10 * SEC, B, "B");
        t.flush(T0 + 15 * SEC);

        checkInt("换应用产生两条", 2, sink.kept.size());
        checkLong("A 段结束在上一次确认处", T0 + 5 * SEC, sink.kept.get(0).endMs);
        checkLong("B 段从被切到的时刻开始", T0 + 10 * SEC, sink.kept.get(1).startMs);
    }

    /**
     * **5 秒采样下的短断档不算断档。**
     *
     * <p>1.3.4 的回归点：断档阈值原来是 {@code 采样间隔 × 3}，采样一降到 5 秒就只剩
     * 15 秒 —— 漏两三拍（系统卡一下、进程被临时冻结）就会被当成「中间断过」，
     * 把一段连续使用切成两条。所以给它加了 3 分钟的绝对下限。
     */
    private static void shortGapDoesNotCutAtFastSampling() {
        Sink sink = new Sink();
        SessionTracker t = tracker(sink, 5 * SEC, 0);
        t.observe(T0, A, "A");
        // 30 秒没有观测到：远超 sampleMs*3（15 秒），但远低于 3 分钟下限
        t.observe(T0 + 30 * SEC, A, "A");

        checkInt("短断档不该切成两条", 0, sink.kept.size());
        checkLong("片段应当还在延续（起点没变）", T0, t.currentStartMs());
    }

    /** 真正的断档（进程被杀、长时间没采样）仍然要切断，不许把中间几小时算进去。 */
    private static void longGapCuts() {
        Sink sink = new Sink();
        SessionTracker t = tracker(sink, 5 * SEC, 0);
        t.observe(T0, A, "A");
        t.observe(T0 + 4 * MIN, A, "A");

        checkInt("超过 3 分钟的断档要切断", 1, sink.kept.size());
        checkLong("断档前那一段结束在断档前", T0, sink.kept.get(0).endMs);
        checkLong("断档后重新起一段", T0 + 4 * MIN, t.currentStartMs());
    }

    /**
     * 不够长的片段走 {@code onSessionDropped}。
     *
     * <p>两个回调**必须都接**：这一段的记录在它开始时就落库了，只回调「保留」
     * 而不管「丢弃」，时间轴上就会留下一条没有时长、永远不结束的空壳 ——
     * 那正是 1.3 里「设了低于 N 分钟不计、却还是生成了事件」的根因。
     */
    private static void dropBelowMinKeep() {
        Sink sink = new Sink();
        SessionTracker t = tracker(sink, 5 * SEC, 5 * MIN);
        t.observe(T0, A, "A");
        for (int i = 1; i <= 12; i++) {
            t.observe(T0 + i * 5 * SEC, A, "A");
        }
        t.screenOff(T0 + 60 * SEC);

        checkInt("短片段不进 kept", 0, sink.kept.size());
        checkInt("短片段要回调 dropped（落库方据此收回那一行）", 1, sink.dropped.size());
        checkLong("丢弃时也带上真实的起止", T0 + 60 * SEC, sink.dropped.get(0).endMs);
    }

    /** 前台变成「没有可记录的应用」时，手上这段要收尾并清空。 */
    private static void emptyPkgEndsSession() {
        Sink sink = new Sink();
        SessionTracker t = tracker(sink, 5 * SEC, 0);
        t.observe(T0, A, "A");
        t.observe(T0 + 5 * SEC, A, "A");
        t.observe(T0 + 10 * SEC, "", "");

        checkInt("空包名要把上一段收尾", 1, sink.kept.size());
        checkTrue("收尾之后不该还认为在记这个应用", t.currentPkg().isEmpty());
    }

    /** 熄屏／锁屏：立刻收尾，不把熄屏之后的时间算进上一个应用。 */
    private static void screenOffEndsAtLastSeen() {
        Sink sink = new Sink();
        SessionTracker t = tracker(sink, 5 * SEC, 0);
        t.observe(T0, A, "A");
        t.observe(T0 + 5 * SEC, A, "A");
        t.screenOff(T0 + 6 * SEC);

        checkInt("熄屏收尾", 1, sink.kept.size());
        checkLong("结束在上一次确认处，不是熄屏那一刻", T0 + 5 * SEC, sink.kept.get(0).endMs);
    }

    // ---------------- 工具 ----------------

    private static final class Sink implements SessionTracker.Sink {
        final List<Session> kept = new ArrayList<>();
        final List<Session> dropped = new ArrayList<>();

        @Override
        public void onSessionFinished(Session finished) {
            kept.add(finished);
        }

        @Override
        public void onSessionDropped(Session dropped) {
            this.dropped.add(dropped);
        }
    }

    private static SessionTracker tracker(Sink sink, long sampleMs, long minKeepMs) {
        return new SessionTracker(sink, new AppFilter()).timing(sampleMs, minKeepMs);
    }

    private static void checkInt(String what, int expect, int actual) {
        if (expect == actual) {
            pass++;
        } else {
            fail++;
            System.out.println("FAIL: " + what + "  期望=" + expect + "  实际=" + actual);
        }
    }

    private static void checkLong(String what, long expect, long actual) {
        if (expect == actual) {
            pass++;
        } else {
            fail++;
            System.out.println("FAIL: " + what + "  期望=" + expect + "  实际=" + actual);
        }
    }

    private static void checkTrue(String what, boolean ok) {
        if (ok) {
            pass++;
        } else {
            fail++;
            System.out.println("FAIL: " + what);
        }
    }
}
