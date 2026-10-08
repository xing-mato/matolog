package com.MATO.log.data;

import java.util.List;

/**
 * 自动记录出来的一段「应用使用片段」。
 *
 * <p><b>存到哪</b>：它最终会变成一条普通的 {@link Event} 落进同一张 event 表 ——
 * 混排显示是需求定下的（自动记录与手动记录在同一条时间轴上）。这个类是**中间的
 * 计算产物**，不是另一张表：引擎算出一段片段，交给落库那一侧转成 Event。
 *
 * <p><b>为什么不直接复用 Event</b>：Event 是「已经存下来的东西」，带 id、带附件计数；
 * 而这个类是「刚算出来、还没存」的。两者职责不同，混用会出现「id 还是 0 的记录
 * 被当成已存在的」这类难查的错。
 *
 * <p><b>为什么不碰 Android API</b>：分段判定（切段、断档、最小时长、名单过滤）
 * 是最容易出细微错误的地方，做成纯 Java 就是为了能用 javac 直接跑用例验证，
 * 不必每次都装到设备上试。注意本仓库目前没有这份用例（详见
 * {@link com.MATO.log.rec.SessionTracker} 的类注释）。
 */
public final class Session {

    /** 来源：自动记录 */
    public static final int SRC_AUTO = 0;
    /** 来源：手动新建 */
    public static final int SRC_MANUAL = 1;
    /**
     * 来源：屏幕画面分析。
     *
     * <p><b>这条来源已经不再产生新数据</b>（该功能在 1.3 移除），但常量必须留着：
     * 老设备库里可能还有 source=2 的记录，导出文件里也可能有。删掉它会让那些
     * 记录在显示、导出、导入时全部失配 —— 一条只剩数字 2 的记录既不该被当成手动，
     * 也不该被当成自动。
     */
    public static final int SRC_SCREEN = 2;

    /** 包名；手动新建的可以没有 */
    public String pkg = "";
    /** 应用显示名，落库保存 —— 以后应用卸载了，列表里也不至于只剩一个包名 */
    public String label = "";
    /** 片段开始时刻 */
    public long startMs;
    /** 片段结束时刻 */
    public long endMs;
    /** 这一段里最后一次确认「它还在前台」的时刻 */
    public long lastSeenMs;
    public int source = SRC_AUTO;

    public Session() {
    }

    public Session(String pkg, String label, long startMs) {
        this.pkg = pkg == null ? "" : pkg;
        this.label = label == null ? "" : label;
        this.startMs = startMs;
        this.lastSeenMs = startMs;
    }

    /**
     * 时长。
     *
     * <p>结束时刻用 {@code endMs}；还没收尾的用 {@code lastSeenMs} —— 也就是
     * 「最后一次确认它还在前台」的那一刻，而不是「现在」。宁可少算几十秒，
     * 也不把用户其实已经离开的那段时间记到它头上。
     */
    public long durationMs() {
        long end = endMs > 0 ? endMs : lastSeenMs;
        long d = end - startMs;
        return d > 0 ? d : 0;
    }

    /** 转成可落库的事件。text 留空，等整理（手动或自动）再填。 */
    public Event toEvent() {
        Event e = new Event(startMs, "");
        e.endMs = endMs;
        e.source = source;
        e.appPkg = pkg == null ? "" : pkg;
        e.appLabel = label == null ? "" : label;
        e.organized = false;
        e.ignored = false;
        return e;
    }

    /** 批量转换，给补记用 */
    public static void toEvents(List<Session> sessions, List<Event> out) {
        if (sessions == null || out == null) {
            return;
        }
        for (Session s : sessions) {
            if (s != null) {
                out.add(s.toEvent());
            }
        }
    }

    @Override
    public String toString() {
        return "Session{" + pkg + " " + startMs + "→" + (endMs > 0 ? endMs : lastSeenMs)
                + " d=" + durationMs() + " src=" + source + "}";
    }
}
