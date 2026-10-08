package com.MATO.log.rec;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 「现在前台是谁」的状态机 —— 纯 Java，不碰 Android API。
 *
 * <p><b>它解决的问题之一</b>：只看「谁最近进入前台」会把前台**永久锁死**在最后一个抢焦点的
 * 应用上。小窗/分屏里最典型：在游戏里小窗开一下聊天，底下的游戏只会收到
 * {@code onPause}（它仍然可见，所以不会 {@code onStop}），聊天拿到焦点；
 * 等小窗关掉，游戏**不会重新产生一条 RESUMED 事件**（它从来没离开过 resumed 状态）。
 * 于是「回到游戏」这件事在事件流里根本不可见 —— 采样层只能一直认为前台还是聊天，
 * 那个两分钟的聊天片段被无限延长，把前面半小时的游戏整个吃掉。
 *
 * <p><b>怎么修</b>：把「前台」当**状态**而不是事件，并且要维护得有层次感 ——
 * 用一个「前台栈」加一张「已暂停表」：
 *
 * <ul>
 *   <li>{@code ACTIVITY_RESUMED} → 移出暂停表，压到栈顶；</li>
 *   <li>{@code ACTIVITY_PAUSED} / {@code ACTIVITY_STOPPED} → 移出栈，并记进暂停表
 *       （<b>记进表里是关键</b>：它虽然离开了前台，但仍压在栈里那些应用上面）；</li>
 *   <li>当前前台 = 栈顶；<b>若栈顶被移走，则不断弹出「已在暂停表里」的那些</b>，
 *       直到露出一个没被暂停的 —— 它才是真正回到前台的那个。</li>
 * </ul>
 *
 * <p><b>为什么非要那张暂停表</b>：只看「在不在栈里」是不够的。设想游戏跑到第 12 分钟
 * 小窗开了聊天（游戏 PAUSED、聊天 RESUMED），第 14 分钟关掉小窗（游戏 RESUMED）。
 * 游戏重新压回栈顶之后，聊天**再也没收到过任何事件** —— 它如果还留在栈里，
 * 第 25 分钟游戏退到后台时，栈顶就会露出这个早就关掉的聊天，记录又错了。
 *
 * <p>两种 ROM 语义都能得到正确结果，**不依赖「关闭小窗时一定有事件」这个假设**：
 * <ul>
 *   <li>若系统给小窗底下的应用发了 PAUSED：关窗后它重新 RESUMED，压回栈顶；</li>
 *   <li>若没发 PAUSED：它压根不在暂停表里，小窗应用一退，它自然就是栈顶。</li>
 * </ul>
 *
 * <p><b>账记在「活动」上，不是「包」上。</b>这是 1.3.4 修的另一处：
 * 系统发的事件是**按活动**的，而原先这里按包名记账、一个包在栈里只有一格 ——
 * 于是包内的活动切换（启动页 → 主界面、设置里点进二级页）会产生
 * {@code RESUMED(包X)} 紧跟 {@code PAUSED(包X)}，后到的那条把整个包从栈里摘掉，
 * 栈一空就报「没有前台应用」。表现是：**任何应用冷启动之后的一两分钟里，
 * 通知都显示「等待前台应用」**（冷启动必然走启动页→主活动这一次切换），
 * 等那条 PAUSED 滑出回看窗口才自己恢复。系统设置这类内部全是活动跳转的更是一直不对。
 *
 * <p>现在记账键是「包名 + 活动名」（拿不到活动名时退回包名，行为与以前一致）。
 * 对外仍然按包名回答 —— {@link #of} 给包名，{@link #stackOf} 给去重后的包名序列。
 *
 * <p><b>可离线验证</b>：{@code tools/ForegroundCheck.java} 直接构造事件序列喂给
 * {@link #of}，覆盖小窗、分屏、正常切换、熄屏、包内活动切换等场景，不需要设备。
 */
public final class ForegroundState {

    /** 事件类型。取值与 {@code android.app.usage.UsageEvents.Event} 无关，由调用方翻译 */
    public static final int RESUMED = 1;
    public static final int PAUSED = 2;
    public static final int STOPPED = 3;

    /** 一条前台相关事件 */
    public static final class Ev {
        public final int type;
        public final String pkg;
        /**
         * 活动类名。系统拿得到就填上，拿不到（或调用方只关心包名）就是空串 ——
         * 那时记账退化成按包名，与 1.3.3 的行为一致。
         */
        public final String cls;
        public final long atMs;

        /** 只给包名的构造：老用例与「补一个 stillActive」用 */
        public Ev(int type, String pkg, long atMs) {
            this(type, pkg, "", atMs);
        }

        public Ev(int type, String pkg, String cls, long atMs) {
            this.type = type;
            this.pkg = pkg == null ? "" : pkg;
            this.cls = cls == null ? "" : cls;
            this.atMs = atMs;
        }
    }

    /** 算出来的当前前台 */
    public static final class Now {
        public final String pkg;
        /**
         * 这个判断是「确定的」还是「只能沿用上一次观测」。
         *
         * <p>这段时间压根没有事件时（比如刚开机、事件被系统清过），只能回退到调用方
         * 上一次看到的应用 —— 那时它是 guess，调用方要照旧走自己那条兜底路径。
         */
        public final boolean guess;
        /** 事件流里最后一条事件发生的时刻；没有事件时为 0 */
        public final long atMs;

        Now(String pkg, boolean guess, long atMs) {
            this.pkg = pkg == null ? "" : pkg;
            this.guess = guess;
            this.atMs = atMs;
        }

        public boolean empty() {
            return pkg.isEmpty();
        }
    }

    private ForegroundState() {
    }

    /**
     * 按时间顺序吃完整条事件流，算出「此刻前台是谁」。
     *
     * @param events 必须**按时间升序**（调用方从系统拿到时就是这个顺序）
     * @return 栈顶那个应用；栈空时返回空包名
     */
    public static Now of(List<Ev> events) {
        List<String> keys = keyStackOf(events);
        if (keys.isEmpty()) {
            boolean noEvent = events == null || events.isEmpty();
            return new Now("", noEvent, lastAt(events));
        }
        return new Now(pkgOfKey(keys.get(0)), false, lastAt(events));
    }

    /**
     * 前台栈里的**包名**，栈顶在最前，同一个包只出现一次。
     *
     * <p>单独暴露出来是为了让用例能断言「还压着谁」—— 只看最终前台是谁，
     * 抓不出「聊天永久滞留」那种 bug（它在大部分时刻都不影响结论，
     * 只在栈顶被移走的那一刻才露出来）。
     *
     * <p>注意这里给的是**包名**去重后的结果，而状态机内部是按「包名 + 活动名」记账的：
     * 调用方关心的是「哪个应用在前台」，同一个应用开了几个活动不是它的事。
     */
    public static List<String> stackOf(List<Ev> events) {
        List<String> keys = keyStackOf(events);
        List<String> out = new ArrayList<>();
        for (int i = 0; i < keys.size(); i++) {
            String p = pkgOfKey(keys.get(i));
            if (!out.contains(p)) {
                out.add(p);
            }
        }
        return out;
    }

    /**
     * 内部真正的栈：栈顶在最前，元素是**记账键**（见 {@link #keyOf}）。
     */
    private static List<String> keyStackOf(List<Ev> events) {
        // 前台栈：越靠后越靠上（最后进入前台的压在别人上面）
        List<String> stack = new ArrayList<>();
        // 已暂停：它不再占着前台了 —— 这一条必须留着，否则早就退出的应用会赖在栈里
        Set<String> paused = new HashSet<>();

        if (events != null) {
            for (Ev e : events) {
                if (e == null || e.pkg.isEmpty()) {
                    continue;
                }
                String k = keyOf(e);
                if (e.type == RESUMED) {
                    paused.remove(k);
                    stack.remove(k);
                    stack.add(k);
                } else {
                    stack.remove(k);
                    paused.add(k);
                }
                // 栈顶若是已经暂停过的，说明它其实不在前台 —— 弹掉它
                while (!stack.isEmpty()
                        && paused.contains(stack.get(stack.size() - 1))) {
                    stack.remove(stack.size() - 1);
                }
            }
        }

        // 反转成「栈顶在最前」，对外读起来更自然
        List<String> topFirst = new ArrayList<>(stack);
        Collections.reverse(topFirst);
        return topFirst;
    }

    /**
     * 记账键：有活动名就精确到活动，没有就退回包名。
     *
     * <p>为什么非精确到活动不可：系统的事件是**按活动**发的，同一个包内从一个活动
     * 换到另一个活动会发出 {@code RESUMED(包)} 紧跟 {@code PAUSED(包)}。若按包名记账，
     * 后到的那条 PAUSED 会把整个包从栈里摘掉 —— 而用户其实好好待在这个应用里。
     * 冷启动（启动页 → 主界面）必然产生这样一对事件，所以表现是「刚打开的应用
     * 一律显示成没有前台应用」，一两分钟后 PAUSED 滑出回看窗口才自愈。
     *
     * <p>包名与类名都不会含 {@code '/'}，所以拿它当分隔符是安全的。
     */
    private static String keyOf(Ev e) {
        return e.cls.isEmpty() ? e.pkg : e.pkg + "/" + e.cls;
    }

    private static String pkgOfKey(String key) {
        int i = key.indexOf('/');
        return i < 0 ? key : key.substring(0, i);
    }

    /**
     * 栈顶**以下**的那些应用，按「压得越上面越靠前」排。
     *
     * <p>它们是「曾经进过前台、之后被后来的应用压住、且没有明确退出过」的那批。
     * 单独交出来是因为：<b>事件流说不清它们现在到底还在不在屏幕上</b>。
     * 多窗口里两个应用可以同时可见，而其中一个退出时，事件流不会告诉你另一个
     * 是否接管了焦点 —— 那时候只能再问系统一句「谁还可见」（见
     * {@code UsageReader.visiblePackages}），拿这个列表去对。
     */
    public static List<String> altsOf(List<Ev> events) {
        List<String> stack = stackOf(events);
        if (stack.size() <= 1) {
            return new ArrayList<>();
        }
        return new ArrayList<>(stack.subList(1, stack.size()));
    }

    private static long lastAt(List<Ev> events) {
        long at = 0;
        if (events != null) {
            for (Ev e : events) {
                if (e != null && !e.pkg.isEmpty() && e.atMs > at) {
                    at = e.atMs;
                }
            }
        }
        return at;
    }

    /**
     * 把一个应用补到事件流最前面。
     *
     * <p>给「回看窗口被截断」这种情况用 —— 窗口起点之前那个应用仍然在前台，
     * 但我们看不到它进入前台的那条事件了。补在最前面（而不是最后）语义才对：
     * 「它早就在，只是不在窗口里」，后面的 RESUMED 会正常压在它上面。
     */
    public static List<Ev> prepend(List<Ev> events, String pkg, long atMs) {
        List<Ev> out = new ArrayList<>();
        if (pkg != null && !pkg.isEmpty()) {
            out.add(new Ev(RESUMED, pkg, atMs));
        }
        if (events != null) {
            out.addAll(events);
        }
        return out;
    }
}
