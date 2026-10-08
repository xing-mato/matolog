package com.MATO.log.data;

import java.util.ArrayList;
import java.util.List;

/** 一条事件记录 */
public class Event {

    public long id;
    /** 事件发生时间（毫秒时间戳，添加时取系统时间） */
    public long time;
    /** 描述 */
    public String text;
    /** 入库时间，用于展示与去重 */
    public long createdAt;

    // ---------------- 1.3 新增：让一条记录也能表达「一段区间」 ----------------

    /**
     * 结束时刻。0 表示「瞬时事件」，也就是 1.3 之前所有记录以及手动新建的形态。
     *
     * <p>之所以用 0 当哨兵而不是存一个 duration：旧记录升级上来天然合法（默认 0），
     * 不需要任何数据回填；而按「时刻」存也避免了「改了开始时间却忘了同步时长」
     * 这类需要两处保持一致才能成立的错误。
     */
    public long endMs;

    /** 来源：见 {@link Session#SRC_AUTO} 等常量 */
    public int source = Session.SRC_MANUAL;

    /** 自动记录才有：前台应用包名 */
    public String appPkg = "";
    /** 自动记录才有：应用显示名快照 */
    public String appLabel = "";

    /** 是否已被整理过（自动整理补写过内容；早先屏幕分析也会置这一位） */
    public boolean organized;
    /** 用户标记为「这条不算」；列表里默认不显示，但数据仍在 */
    public boolean ignored;

    /** 附件条数；列表里用它决定要不要显示回形针。不入库，由查询时填充 */
    public int attachmentCount;

    /** 附带文件；只在导入 / 导出这类一次性流程里装满，平时是空的 */
    public List<Attachment> attachments;

    public Event() {
        this.text = "";
    }

    public Event(long time, String text) {
        this.time = time;
        this.text = text;
        this.createdAt = System.currentTimeMillis();
    }

    public Event(long id, long time, String text, long createdAt) {
        this.id = id;
        this.time = time;
        this.text = text;
        this.createdAt = createdAt;
    }

    /** 是不是一段有长度的区间（自动记录产生的），而不是一个瞬间 */
    public boolean isSpan() {
        return endMs > time && endMs > 0;
    }

    /** 时长；瞬时事件返回 0 */
    public long durationMs() {
        if (!isSpan()) {
            return 0;
        }
        return endMs - time;
    }

    /** 是不是自动记录产生的 */
    public boolean isAuto() {
        return source == Session.SRC_AUTO || source == Session.SRC_SCREEN;
    }

    /**
     * 列表里显示的标题。
     *
     * <p>优先用整理出来的描述；还没整理过时退到应用名，再退到包名。
     * 手动记录永远只用 text。
     */
    public String displayTitle() {
        if (text != null && !text.isEmpty()) {
            return text;
        }
        if (appLabel != null && !appLabel.isEmpty()) {
            return appLabel;
        }
        if (appPkg != null && !appPkg.isEmpty()) {
            return appPkg;
        }
        return "";
    }

    public Event copy() {
        Event e = new Event(id, time, text, createdAt);
        e.endMs = endMs;
        e.source = source;
        e.appPkg = appPkg;
        e.appLabel = appLabel;
        e.organized = organized;
        e.ignored = ignored;
        e.attachmentCount = attachmentCount;
        return e;
    }

    /** 取附件表；没有就建一个空的，省得调用方到处判 null */
    public List<Attachment> attachments() {
        if (attachments == null) {
            attachments = new ArrayList<>();
        }
        return attachments;
    }

    public void addAttachment(Attachment a) {
        attachments().add(a);
    }

    @Override
    public String toString() {
        return "Event{id=" + id + ", time=" + time + ", end=" + endMs
                + ", src=" + source + ", text=" + text + "}";
    }
}
