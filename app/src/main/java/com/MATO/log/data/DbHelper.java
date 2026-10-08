package com.MATO.log.data;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 本地 SQLite 存储：全部数据只留在这台设备上 */
public class DbHelper extends SQLiteOpenHelper {

    private static final String DB_NAME = "matolog.db";

    /**
     * v1：事件表
     * v2：新增附件表（不动用户已有数据）
     * v3：事件表加「区间与来源」六列 + 新增应用名单表（同样不动已有数据）
     *
     * <p>1.3.5 从 private 改成 public：诊断报告要写上库版本 ——
     * 「老库升上来的机器」和「全新安装的机器」出的问题往往不是同一个，
     * 而报告里没有这一项时，光看别的字段分不出来。
     */
    public static final int DB_VERSION = 3;

    public static final String TABLE = "event";
    public static final String TABLE_ATTACH = "attachment";
    /** v3 新增：每个应用是否参与自动记录 */
    public static final String TABLE_RULE = "apprule";

    /** 应用名单：不参与自动记录 */
    public static final int RULE_EXCLUDE = 1;
    /**
     * 应用名单：只记这些（白名单，非空时其余一律不记）。
     *
     * <p><b>遗留，代码不再产生它，也不再有判定作用。</b>1.3.1 把这个功能去掉了，
     * 升级后首次启动会用 {@link #clearOnlyRules()} 清掉库里剩下的行。常量与清理方法都留着：
     * 常量是给清理用的，方法要能重复跑（标记可被写坏、旧备份可能带回来）。
     */
    public static final int RULE_ONLY = 2;

    private static final String CREATE =
            "CREATE TABLE IF NOT EXISTS " + TABLE + " ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + "time INTEGER NOT NULL,"
                    + "text TEXT NOT NULL DEFAULT '',"
                    + "created_at INTEGER NOT NULL"
                    + ");";

    private static final String CREATE_INDEX =
            "CREATE INDEX IF NOT EXISTS idx_event_time ON " + TABLE + " (time);";

    private static final String CREATE_ATTACH =
            "CREATE TABLE IF NOT EXISTS " + TABLE_ATTACH + " ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + "event_id INTEGER NOT NULL DEFAULT 0,"
                    + "name TEXT NOT NULL DEFAULT '',"
                    + "mime TEXT NOT NULL DEFAULT '',"
                    + "size INTEGER NOT NULL DEFAULT 0,"
                    + "internal INTEGER NOT NULL DEFAULT 1,"
                    + "local_name TEXT NOT NULL DEFAULT '',"
                    + "uri TEXT NOT NULL DEFAULT '',"
                    + "created_at INTEGER NOT NULL"
                    + ");";

    private static final String CREATE_ATTACH_INDEX =
            "CREATE INDEX IF NOT EXISTS idx_attach_event ON " + TABLE_ATTACH + " (event_id);";

    /**
     * v3 的六列。
     *
     * 每一条都带 NOT NULL DEFAULT —— 现有建表语句全是这个风格，而且给了默认值之后
     * 旧记录升级上来天然合法（end_ms=0 就是「瞬时事件」），不需要回填任何数据。
     */
    private static final String[] ALTER_V3 = {
            "ALTER TABLE " + TABLE + " ADD COLUMN end_ms INTEGER NOT NULL DEFAULT 0",
            "ALTER TABLE " + TABLE + " ADD COLUMN source INTEGER NOT NULL DEFAULT 1",
            "ALTER TABLE " + TABLE + " ADD COLUMN app_pkg TEXT NOT NULL DEFAULT ''",
            "ALTER TABLE " + TABLE + " ADD COLUMN app_label TEXT NOT NULL DEFAULT ''",
            "ALTER TABLE " + TABLE + " ADD COLUMN organized INTEGER NOT NULL DEFAULT 0",
            "ALTER TABLE " + TABLE + " ADD COLUMN ignored INTEGER NOT NULL DEFAULT 0",
    };

    private static final String CREATE_RULE =
            "CREATE TABLE IF NOT EXISTS " + TABLE_RULE + " ("
                    + "pkg TEXT PRIMARY KEY,"
                    + "label TEXT NOT NULL DEFAULT '',"
                    + "rule INTEGER NOT NULL DEFAULT 0"
                    + ");";

    /** event 表的完整列清单。查询一律显式列出，不写 SELECT * —— 见下面 read() 的说明。 */
    private static final String[] EVENT_COLS = {
            "id", "time", "text", "created_at",
            "end_ms", "source", "app_pkg", "app_label", "organized", "ignored"
    };

    public DbHelper(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL(CREATE);
        db.execSQL(CREATE_INDEX);
        db.execSQL(CREATE_ATTACH);
        db.execSQL(CREATE_ATTACH_INDEX);
        // 新库直接建成最终形态：先按老结构建表，再把 v3 的列补上。
        // 这样「新建库」和「老库升级」走的是同一批 ALTER 语句，两条路径不会长歪。
        alterToV3(db);
        db.execSQL(CREATE_RULE);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // 累加式：每一步只管自己那一版要加的东西，既有数据一律不动。
        // 不用 DROP/重建 —— 用户的事件不能因为一次升级就没了。
        if (oldVersion < 2) {
            db.execSQL(CREATE_ATTACH);
            db.execSQL(CREATE_ATTACH_INDEX);
        }
        if (oldVersion < 3) {
            alterToV3(db);
        }
    }

    /**
     * 补上 v3 的六列。
     *
     * <p>逐个 try：极少数情况下（用户手动改过库、上一次升级中断）某一列可能已经存在，
     * 重复 ALTER 会抛异常。这里选择「存在就跳过」，让升级尽量能走完，
     * 而不是因为一列的问题把整个升级挡在门外 —— 挡住的后果是应用起不来。
     */
    private static void alterToV3(SQLiteDatabase db) {
        for (String sql : ALTER_V3) {
            try {
                db.execSQL(sql);
            } catch (Throwable ignored) {
                // 该列已存在，继续
            }
        }
        db.execSQL(CREATE_RULE);
    }

    // ---------------- 查询 ----------------

    /**
     * 左闭右开区间 [from, to) 内的事件，<b>按时间从新到旧</b>。
     *
     * <p>1.3.1 起改成倒序：1.3 及以前是正序，日页面看当天记录要一路划到底才看到最近的一条。
     *
     * <p>倒序放在 SQL 里、而不是取完正序再由界面反转：界面反转只能对付「整段一起反转」，
     * 而这里一旦以后要按天分组、分页，反转就得跟着改一遍。让库给出最终顺序，
     * 调用方拿到什么就画什么。
     *
     * <p>导出与本机备份**不用**这个方法（它们走 {@link #queryAll()}，仍是正序）——
     * 文件里的先后不该被界面的显示顺序带着变。
     */
    public List<Event> queryByRange(long from, long to) {
        return query("time >= ? AND time < ?",
                new String[]{String.valueOf(from), String.valueOf(to)},
                "time DESC, id DESC", null);
    }

    /** 区间内事件条数 */
    public int countByRange(long from, long to) {
        Cursor c = null;
        try {
            c = getReadableDatabase().rawQuery(
                    "SELECT COUNT(*) FROM " + TABLE + " WHERE time >= ? AND time < ?",
                    new String[]{String.valueOf(from), String.valueOf(to)});
            if (c != null && c.moveToFirst()) {
                return c.getInt(0);
            }
        } finally {
            closeQuietly(c);
        }
        return 0;
    }

    /**
     * 按「天」统计区间内的事件数。
     * 返回数组下标 0 对应 from 当天，长度 = 天数。
     */
    public int[] countByDay(long from, long to, int days) {
        int[] counts = new int[Math.max(days, 0)];
        if (counts.length == 0) {
            return counts;
        }
        long dayMillis = 86400000L;
        Cursor c = null;
        try {
            c = getReadableDatabase().query(TABLE, new String[]{"time"},
                    "time >= ? AND time < ?", new String[]{String.valueOf(from), String.valueOf(to)},
                    null, null, "time ASC");
            while (c != null && c.moveToNext()) {
                long t = c.getLong(0);
                int idx = (int) ((t - from) / dayMillis);
                if (idx >= 0 && idx < counts.length) {
                    counts[idx]++;
                }
            }
        } finally {
            closeQuietly(c);
        }
        return counts;
    }

    /** 全部事件，按时间正序 */
    public List<Event> queryAll() {
        return query(null, null, "time ASC, id ASC", null);
    }

    /**
     * 关键词检索：描述文本**或应用名**里含这个词的事件，按时间从新到旧。
     *
     * <p>为什么要一起搜应用名：自动记录下来的那些事件，正文本身就是应用名
     * （见 {@code Session.toEvent}），但手动补记时用户可能把应用名写在正文里、
     * 也可能只在 {@code app_label} 上留着。只搜 text 会漏掉后者，而用户看不出
     * 「为什么明明有这个应用却搜不到」。
     *
     * <p><b>不过滤 {@code ignored}</b>：这个检索要跟「日」页面看到的完全一致 ——
     * 日页面走 {@link #queryByRange}，它也不过滤。检索结果里少一条，
     * 用户只会当成「搜不到」，而那条记录其实就在那儿。
     *
     * <p><b>通配符要转义</b>：{@code LIKE} 里 {@code %} 和 {@code _} 是有含义的，
     * 用户搜「100%」或「a_b」时不该被当成通配符去匹配别的东西。
     *
     * <p>大小写：SQLite 的 {@code LIKE} 对 ASCII 天然不区分大小写，中文本来就是
     * 按字符比，不需要额外处理。
     *
     * @param limit 上限，&le;0 时用默认值。防的是一次把几万条全捞进内存 ——
     *              结果页也确实画不了那么多行
     */
    public List<Event> search(String keyword, int limit) {
        String kw = keyword == null ? "" : keyword.trim();
        if (kw.isEmpty()) {
            return new ArrayList<>();
        }
        String like = "%" + escapeLike(kw) + "%";
        return query("(text LIKE ? ESCAPE '\\' OR app_label LIKE ? ESCAPE '\\')",
                new String[]{like, like},
                "time DESC, id DESC",
                String.valueOf(limit > 0 ? limit : SEARCH_LIMIT_DEFAULT));
    }

    /** 检索默认上限 */
    public static final int SEARCH_LIMIT_DEFAULT = 500;

    /** 把 LIKE 的通配符转义掉，交给 {@code ESCAPE '\'} 还原成普通字符 */
    private static String escapeLike(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '%' || c == '_' || c == '\\') {
                sb.append('\\');
            }
            sb.append(c);
        }
        return sb.toString();
    }

    public int countAll() {
        Cursor c = null;
        try {
            c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM " + TABLE, null);
            if (c != null && c.moveToFirst()) {
                return c.getInt(0);
            }
        } finally {
            closeQuietly(c);
        }
        return 0;
    }

    public Event getById(long id) {
        List<Event> list = query("id = ?", new String[]{String.valueOf(id)}, null, "1");
        if (list.isEmpty()) {
            return null;
        }
        Event e = list.get(0);
        e.attachmentCount = countAttachments(e.id);
        return e;
    }

    public boolean existsSame(long time, String text) {
        Cursor c = null;
        try {
            c = getReadableDatabase().query(TABLE, new String[]{"id"},
                    "time = ? AND text = ?",
                    new String[]{String.valueOf(time), text == null ? "" : text},
                    null, null, null, "1");
            return c != null && c.moveToFirst();
        } finally {
            closeQuietly(c);
        }
    }

    /** 统一的查询入口 */
    private List<Event> query(String selection, String[] args, String orderBy, String limit) {
        ArrayList<Event> list = new ArrayList<>();
        Cursor c = null;
        try {
            c = getReadableDatabase().query(TABLE, EVENT_COLS, selection, args,
                    null, null, orderBy, limit);
            while (c != null && c.moveToNext()) {
                list.add(read(c));
            }
        } finally {
            closeQuietly(c);
        }
        fillAttachmentCounts(list);
        return list;
    }

    // ---------------- 自动记录相关 ----------------

    /**
     * 还没收尾的片段（end_ms=0 且来源是自动记录）。
     *
     * <p>服务被系统回收、进程重启之后靠它把状态接回来 —— 不然一次连续使用
     * 会被切成两段，列表里看着像两条碎片。
     */
    public List<Event> openAutoEvents() {
        return query("end_ms = 0 AND source = ? AND ignored = 0",
                new String[]{String.valueOf(Session.SRC_AUTO)}, "time ASC", null);
    }

    /**
     * 写入一条「正在累集中」的片段（end_ms=0）。
     *
     * <p><b>为什么单独一个方法</b>：{@link #insertOrMerge} 的合并前提是「上一条已经收尾」
     * （要求 {@code prev.end_ms > 0}），而这里要处理的恰恰是「上一条也是未收尾」的情况。
     * 早期版本用 insertOrMerge 走这条路，结果每写一次就新插一行 ——
     * 测试里一分钟里出现三条起点完全相同、都收不了尾的记录，时长永远是 0。
     *
     * <p>语义上这是「延长」，不是「新增」：同一应用同一时刻只应该有一条正在进行的记录。
     *
     * @return 所在行的 id
     */
    public long saveRunning(Event e) {
        Event open = openAutoOf(e.appPkg);
        if (open != null) {
            // 同一应用已经有未收尾的行：只把起点往后延，不新增
            long newStart = Math.min(open.time, e.time);
            ContentValues v = new ContentValues();
            v.put("time", newStart);
            // app_label 可能第一次没查到（应用刚装/刚启动），补上
            if ((open.appLabel == null || open.appLabel.isEmpty())
                    && e.appLabel != null && !e.appLabel.isEmpty()) {
                v.put("app_label", e.appLabel);
            }
            getWritableDatabase().update(TABLE, v, "id = ?",
                    new String[]{String.valueOf(open.id)});
            e.id = open.id;
            e.time = newStart;
            return open.id;
        }
        return insert(e);
    }

    /**
     * 某应用当前那条还没收尾的自动记录。
     *
     * <p>理论上同一应用同时最多只该有一条；真出现多条（历史脏数据、或上一次写入
     * 被中断）时取最早的那条，把其余的当垃圾留给调用方清理 —— 不在这里静默删除，
     * 免得误删用户看得见的东西。
     */
    public Event openAutoOf(String pkg) {
        if (pkg == null || pkg.isEmpty()) {
            return null;
        }
        List<Event> list = query("app_pkg = ? AND source = ? AND end_ms = 0 AND ignored = 0",
                new String[]{pkg, String.valueOf(Session.SRC_AUTO)},
                "time ASC, id ASC", "1");
        return list.isEmpty() ? null : list.get(0);
    }

    /** 清理同一应用下重复的「未收尾」记录，只留最早那条。返回清掉的条数。 */
    public int pruneDuplicateOpen(String pkg) {
        if (pkg == null || pkg.isEmpty()) {
            return 0;
        }
        Event keep = openAutoOf(pkg);
        if (keep == null) {
            return 0;
        }
        return getWritableDatabase().delete(TABLE,
                "app_pkg = ? AND source = ? AND end_ms = 0 AND id != ?",
                new String[]{pkg, String.valueOf(Session.SRC_AUTO), String.valueOf(keep.id)});
    }

    /**
     * 收尾一条片段：把「正在进行」的那一行补上结束时刻。
     *
     * <p><b>这是自动记录落库的正确入口。</b>片段在累积期间已经由 {@link #saveRunning}
     * 写成了一行 end_ms=0 的记录，结束时要做的是**关掉那一行**，而不是再插一行新的。
     * 早期版本这里错用了 {@link #insertOrMerge}，结果是每段都留下两条：
     * 一条永远收不了尾的空壳 + 一条实际记录，时间轴上会出现莫名其妙的重复。
     *
     * <p>传入的 {@code e.time} 可能比库里那行的起点更早（合并了相邻片段），此时把起点
     * 一并前移，保证「起点 ≤ 终点」且时长覆盖完整。
     *
     * @return 被收尾的行 id；没找到对应行时退回插入一条完整记录，返回新行 id
     */
    public long finishEvent(Event e) {
        Event open = openAutoOf(e.appPkg);
        if (open == null) {
            // 没有对应的未收尾行（服务中途被杀、库被外部改过）：退化成直接插入一条完整记录，
            // 至少不让这一段的时长丢掉
            return insert(e);
        }
        long start = Math.min(open.time, e.time > 0 ? e.time : open.time);
        long end = Math.max(e.endMs, start + 1);

        ContentValues v = new ContentValues();
        v.put("time", start);
        v.put("end_ms", end);
        if (e.text != null && !e.text.isEmpty()) {
            v.put("text", e.text);
            v.put("organized", e.organized ? 1 : 0);
        }
        getWritableDatabase().update(TABLE, v, "id = ?",
                new String[]{String.valueOf(open.id)});

        e.id = open.id;
        e.time = start;
        e.endMs = end;
        return open.id;
    }

    /**
     * 收回一条「正在累积」的自动记录 —— 这一段的最终时长没够，按设置不该留。
     *
     * <p><b>为什么需要它</b>：「低于 N 分钟不计」这个规则只有在片段**结束时**才算得出来，
     * 可是记录在片段一开始就已经落库了（见 {@link #saveRunning}：不这么写，进程被杀时
     * 这一段的开头就没了）。所以判定为「不计」的那一刻，必须回头把那一行删掉 ——
     * 否则时间轴上会留下一条没有时长、也永远不会结束的自动记录，用户看到的就是
     * 「明明设了低于 N 分钟不计，却还是生成了事件」。判定在
     * {@link com.MATO.log.rec.SessionTracker}，回收在这里。
     *
     * <p><b>两把钥匙都要</b>：优先按行号删（{@code rowId} 是 {@link #saveRunning}
     * 返回的那一行，最准）。拿不到行号时按「包名 + 起点」删 —— 进程刚重启、
     * 状态是从库里接回来的那种情况就属于这种，此时那一行是上一轮进程写下的。
     *
     * <p><b>只删未收尾的行</b>：两条路径都带 {@code end_ms = 0} 条件。这样即使行号
     * 已经过期、指到了一条正常收尾的记录上，也不会把用户的真实记录删掉。
     *
     * @return 删掉的条数；0 表示那一行已经不在了，属正常情况（比如被
     *         {@link #pruneDuplicateOpen} 清过），不必当成错误
     */
    public int deleteRunning(String pkg, long startMs, long rowId) {
        if (rowId > 0) {
            int n = getWritableDatabase().delete(TABLE,
                    "id = ? AND end_ms = 0 AND source = ?",
                    new String[]{String.valueOf(rowId), String.valueOf(Session.SRC_AUTO)});
            if (n > 0) {
                return n;
            }
        }
        if (pkg == null || pkg.isEmpty() || startMs <= 0) {
            return 0;
        }
        return getWritableDatabase().delete(TABLE,
                "app_pkg = ? AND source = ? AND end_ms = 0 AND time = ?",
                new String[]{pkg, String.valueOf(Session.SRC_AUTO), String.valueOf(startMs)});
    }

    /**
     * 写入一条记录；若它和「同一应用的上一条已收尾记录」首尾相接，就并进去。
     *
     * <p>为什么要合并：服务重启、进程被回收重建之后，同一次连续使用会被切成两段。
     * 不合并的话列表里会出现两条紧挨着、各自都不长、单看都像碎片的东西 ——
     * 而这正是「五分钟以下不计」想避免的观感。
     *
     * <p><b>⚠️ 这个方法目前没有任何调用点</b>（{@link #lastAutoOf} 也只被它用）。
     * 自动记录的落库现在走 {@link #saveRunning} + {@link #finishEvent} 这一对 ——
     * 那对修掉了早期「每写一次就新插一行」和「收尾时新建一行」两个缺陷，但合并
     * 这一步在迁移时被落下了，于是同一次连续使用仍会被切成多条相邻的短记录
     * （2 分钟的间隔也会切成两条）。要不要把合并接回去，是尚未决定的事，
     * 所以先把方法留着。
     *
     * @param joinGapMs 允许合并的最大空隙
     * @return 落库或合并到的行 id
     */
    public long insertOrMerge(Event e, long joinGapMs) {
        Event prev = lastAutoOf(e.appPkg);
        if (prev != null && prev.endMs > 0
                && e.time >= prev.endMs
                && e.time - prev.endMs <= joinGapMs) {
            long newEnd = Math.max(prev.endMs, e.endMs);
            ContentValues v = new ContentValues();
            v.put("end_ms", newEnd);
            // 合并后若已有整理结果，保留它（说明这段内容已经描述过）
            if (prev.text != null && !prev.text.isEmpty()) {
                v.put("text", prev.text);
                v.put("organized", prev.organized ? 1 : 0);
            }
            getWritableDatabase().update(TABLE, v, "id = ?",
                    new String[]{String.valueOf(prev.id)});
            e.id = prev.id;
            e.endMs = newEnd;
            return prev.id;
        }
        return insert(e);
    }

    /** 某应用最近一条已收尾的自动记录 */
    public Event lastAutoOf(String pkg) {
        if (pkg == null || pkg.isEmpty()) {
            return null;
        }
        List<Event> list = query("app_pkg = ? AND source = ? AND ignored = 0",
                new String[]{pkg, String.valueOf(Session.SRC_AUTO)},
                "time DESC, id DESC", "1");
        return list.isEmpty() ? null : list.get(0);
    }

    /**
     * 还没被整理过的自动记录。
     *
     * <p>给自动整理（LLM）补内容用。只取已经收尾的 —— 正在进行的那段
     * 时长还在长，拿它去问模型等于让模型猜。
     */
    public List<Event> unorganizedAuto(int limit) {
        return query("organized = 0 AND ignored = 0 AND end_ms > 0 AND source = ?",
                new String[]{String.valueOf(Session.SRC_AUTO)},
                "time DESC", String.valueOf(Math.max(1, limit)));
    }

    public void setIgnored(long id, boolean ignored) {
        ContentValues v = new ContentValues();
        v.put("ignored", ignored ? 1 : 0);
        getWritableDatabase().update(TABLE, v, "id = ?", new String[]{String.valueOf(id)});
    }

    public int deleteBefore(long cutoffMs) {
        return getWritableDatabase().delete(TABLE, "time < ?",
                new String[]{String.valueOf(cutoffMs)});
    }

    // ---------------- 应用名单 ----------------

    /**
     * 设置某个应用的记录策略；传 0（或任何不认识的值）表示清除设置、回到默认。
     */
    public void setRule(String pkg, String label, int rule) {
        if (pkg == null || pkg.isEmpty()) {
            return;
        }
        if (rule != RULE_EXCLUDE) {
            getWritableDatabase().delete(TABLE_RULE, "pkg = ?", new String[]{pkg});
            return;
        }
        ContentValues v = new ContentValues();
        v.put("pkg", pkg);
        v.put("label", label == null ? "" : label);
        v.put("rule", rule);
        getWritableDatabase().insertWithOnConflict(TABLE_RULE, null, v,
                SQLiteDatabase.CONFLICT_REPLACE);
    }

    public int ruleOf(String pkg) {
        if (pkg == null || pkg.isEmpty()) {
            return 0;
        }
        Cursor c = null;
        try {
            c = getReadableDatabase().query(TABLE_RULE, new String[]{"rule"}, "pkg = ?",
                    new String[]{pkg}, null, null, null, "1");
            if (c != null && c.moveToFirst()) {
                return c.getInt(0);
            }
        } finally {
            closeQuietly(c);
        }
        return 0;
    }

    /** 某一类名单的全部包名 */
    public Set<String> pkgsWithRule(int rule) {
        Set<String> out = new HashSet<>();
        Cursor c = null;
        try {
            c = getReadableDatabase().query(TABLE_RULE, new String[]{"pkg"}, "rule = ?",
                    new String[]{String.valueOf(rule)}, null, null, null);
            while (c != null && c.moveToNext()) {
                out.add(c.getString(0));
            }
        } finally {
            closeQuietly(c);
        }
        return out;
    }

    public Set<String> excludedPkgs() {
        return pkgsWithRule(RULE_EXCLUDE);
    }

    /** 遗留的「只记这些」白名单。只给清理与核对用，判定侧不再使用 */
    public Set<String> onlyPkgs() {
        return pkgsWithRule(RULE_ONLY);
    }

    public int countRule(int rule) {
        Cursor c = null;
        try {
            c = getReadableDatabase().rawQuery(
                    "SELECT COUNT(*) FROM " + TABLE_RULE + " WHERE rule = ?",
                    new String[]{String.valueOf(rule)});
            if (c != null && c.moveToFirst()) {
                return c.getInt(0);
            }
        } finally {
            closeQuietly(c);
        }
        return 0;
    }

    public void clearRules() {
        getWritableDatabase().delete(TABLE_RULE, null, null);
    }

    /**
     * 清掉 1.3 遗留的「只记这些」白名单（rule = {@link #RULE_ONLY}）。
     *
     * <p>1.3.1 把这个功能连同它的按钮一起去掉了 —— 它与「排除」互斥、白名单非空时排除失效，
     * 界面上要写一大段话才解释得清，误导性强。库里存着的白名单必须一起清：
     * 只藏界面不删数据的话，它会继续在 {@link com.MATO.log.rec.AppFilter} 里生效，
     * 用户看不到、也改不掉，却实实在在地控制着哪些记录会被写下。
     *
     * <p><b>为什么是删除而不是降级成「排除」</b>：两者的记录范围正好相反。
     * 「只记 A」= 只写 A；「排除 A」= 除 A 外全写。降级会在用户完全不知情的情况下
     * 把记录范围放大一大截，比删掉更糟 —— 删掉至少是「回到默认」这一件说得清的事。
     *
     * @return 清掉的条数
     */
    public int clearOnlyRules() {
        return getWritableDatabase().delete(TABLE_RULE,
                "rule = ?", new String[]{String.valueOf(RULE_ONLY)});
    }

    /** 应用被卸载后名单里可能还留着它的包名，顺手清掉 */
    public int pruneRules(Set<String> installedPkgs) {
        if (installedPkgs == null || installedPkgs.isEmpty()) {
            return 0;
        }
        List<String> stale = new ArrayList<>();
        Cursor c = null;
        try {
            c = getReadableDatabase().query(TABLE_RULE, new String[]{"pkg"},
                    null, null, null, null, null);
            while (c != null && c.moveToNext()) {
                String p = c.getString(0);
                if (!installedPkgs.contains(p)) {
                    stale.add(p);
                }
            }
        } finally {
            closeQuietly(c);
        }
        int n = 0;
        for (String p : stale) {
            n += getWritableDatabase().delete(TABLE_RULE, "pkg = ?", new String[]{p});
        }
        return n;
    }

    // ---------------- 写入 ----------------

    public long insert(Event e) {
        ContentValues v = values(e);
        long id = getWritableDatabase().insert(TABLE, null, v);
        e.id = id;
        return id;
    }

    public int update(Event e) {
        return getWritableDatabase().update(TABLE, values(e), "id = ?",
                new String[]{String.valueOf(e.id)});
    }

    public int delete(long id) {
        return getWritableDatabase().delete(TABLE, "id = ?", new String[]{String.valueOf(id)});
    }

    public void deleteAll() {
        getWritableDatabase().delete(TABLE, null, null);
    }

    /** 整体替换：清空后写入（导入「清空后导入」/ 恢复本机备份时使用） */
    public int replaceAll(List<Event> events) {
        SQLiteDatabase db = getWritableDatabase();
        int n = 0;
        db.beginTransaction();
        try {
            db.delete(TABLE, null, null);
            for (Event e : events) {
                if (e == null || e.text == null) {
                    continue;
                }
                long id = db.insert(TABLE, null, values(e));
                n++;
                // 导入进来的附件只保留登记，本体要等用户「找回附件」时再落下
                insertAttachments(db, id, e);
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        return n;
    }

    /** 追加写入，返回写入条数 */
    public int insertAll(List<Event> events) {
        SQLiteDatabase db = getWritableDatabase();
        int n = 0;
        db.beginTransaction();
        try {
            for (Event e : events) {
                if (e == null || e.text == null) {
                    continue;
                }
                long id = db.insert(TABLE, null, values(e));
                n++;
                insertAttachments(db, id, e);
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        return n;
    }

    private void insertAttachments(SQLiteDatabase db, long eventId, Event e) {
        if (e.attachments == null || e.attachments.isEmpty()) {
            return;
        }
        for (Attachment a : e.attachments) {
            if (a == null) {
                continue;
            }
            db.insert(TABLE_ATTACH, null, attachValues(eventId, a));
        }
    }

    // ---------------- 行映射 ----------------

    private static ContentValues values(Event e) {
        ContentValues v = new ContentValues();
        v.put("time", e.time);
        v.put("text", e.text == null ? "" : e.text);
        v.put("created_at", e.createdAt > 0 ? e.createdAt : System.currentTimeMillis());
        v.put("end_ms", e.endMs);
        v.put("source", e.source);
        v.put("app_pkg", e.appPkg == null ? "" : e.appPkg);
        v.put("app_label", e.appLabel == null ? "" : e.appLabel);
        v.put("organized", e.organized ? 1 : 0);
        v.put("ignored", e.ignored ? 1 : 0);
        return v;
    }

    /**
     * 按**列名**取，不用下标。
     *
     * <p>原先是 getLong(0)、getString(1) 这种写法。加了 v3 六列之后下标全变，
     * 那种写法一旦漏改一处就是「text 读成了 app_pkg」这种不报错、只是数据错位的 bug。
     * 改成按名取之后，将来再加列也不会影响这里。
     */
    private static Event read(Cursor c) {
        Event e = new Event();
        e.id = c.getLong(c.getColumnIndexOrThrow("id"));
        e.time = c.getLong(c.getColumnIndexOrThrow("time"));
        e.text = str(c, "text");
        e.createdAt = c.getLong(c.getColumnIndexOrThrow("created_at"));
        e.endMs = c.getLong(c.getColumnIndexOrThrow("end_ms"));
        e.source = c.getInt(c.getColumnIndexOrThrow("source"));
        e.appPkg = str(c, "app_pkg");
        e.appLabel = str(c, "app_label");
        e.organized = c.getInt(c.getColumnIndexOrThrow("organized")) != 0;
        e.ignored = c.getInt(c.getColumnIndexOrThrow("ignored")) != 0;
        return e;
    }

    private static String str(Cursor c, String col) {
        String v = c.getString(c.getColumnIndexOrThrow(col));
        return v == null ? "" : v;
    }

    // ---------------- 附件 ----------------

    private static ContentValues attachValues(long eventId, Attachment a) {
        ContentValues v = new ContentValues();
        v.put("event_id", eventId);
        v.put("name", a.name == null ? "" : a.name);
        v.put("mime", a.mime == null ? "" : a.mime);
        v.put("size", a.size);
        v.put("internal", a.internal ? 1 : 0);
        v.put("local_name", a.localName == null ? "" : a.localName);
        v.put("uri", a.uri == null ? "" : a.uri);
        v.put("created_at", a.createdAt > 0 ? a.createdAt : System.currentTimeMillis());
        return v;
    }

    public long insertAttachment(Attachment a) {
        long id = getWritableDatabase().insert(TABLE_ATTACH, null, attachValues(a.eventId, a));
        a.id = id;
        return id;
    }

    /** 把一批附件挂到某个事件上 */
    public void insertAttachments(long eventId, List<Attachment> list) {
        if (list == null || list.isEmpty()) {
            return;
        }
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            for (Attachment a : list) {
                if (a == null) {
                    continue;
                }
                long id = db.insert(TABLE_ATTACH, null, attachValues(eventId, a));
                a.id = id;
                a.eventId = eventId;
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    private static final String[] ATTACH_COLS =
            {"id", "event_id", "name", "mime", "size", "internal", "local_name", "uri", "created_at"};

    private static Attachment readAttachment(Cursor c) {
        Attachment a = new Attachment();
        a.id = c.getLong(0);
        a.eventId = c.getLong(1);
        a.name = c.getString(2);
        a.mime = c.getString(3);
        a.size = c.getLong(4);
        a.internal = c.getInt(5) != 0;
        a.localName = c.getString(6);
        a.uri = c.getString(7);
        a.createdAt = c.getLong(8);
        return a;
    }

    /** 某个事件的全部附件，按加入顺序 */
    public List<Attachment> queryAttachments(long eventId) {
        ArrayList<Attachment> list = new ArrayList<>();
        Cursor c = null;
        try {
            c = getReadableDatabase().query(TABLE_ATTACH, ATTACH_COLS,
                    "event_id = ?", new String[]{String.valueOf(eventId)}, null, null, "id ASC");
            while (c != null && c.moveToNext()) {
                list.add(readAttachment(c));
            }
        } finally {
            closeQuietly(c);
        }
        return list;
    }

    /** 一次性把一批事件的附件数查出来，避免逐条查库 */
    public void fillAttachmentCounts(List<Event> events) {
        if (events == null || events.isEmpty()) {
            return;
        }
        Map<Long, Integer> map = countAttachmentsOf(idsOf(events));
        for (Event e : events) {
            Integer n = map.get(Long.valueOf(e.id));
            e.attachmentCount = n == null ? 0 : n.intValue();
        }
    }

    private static long[] idsOf(List<Event> events) {
        long[] ids = new long[events.size()];
        for (int i = 0; i < events.size(); i++) {
            ids[i] = events.get(i).id;
        }
        return ids;
    }

    /** id → 附件数 */
    public Map<Long, Integer> countAttachmentsOf(long[] eventIds) {
        HashMap<Long, Integer> map = new HashMap<>();
        if (eventIds == null || eventIds.length == 0) {
            return map;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < eventIds.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(eventIds[i]);
        }
        Cursor c = null;
        try {
            c = getReadableDatabase().rawQuery(
                    "SELECT event_id, COUNT(*) FROM " + TABLE_ATTACH
                            + " WHERE event_id IN (" + sb + ") GROUP BY event_id", null);
            while (c != null && c.moveToNext()) {
                map.put(Long.valueOf(c.getLong(0)), Integer.valueOf(c.getInt(1)));
            }
        } finally {
            closeQuietly(c);
        }
        return map;
    }

    public int countAttachments(long eventId) {
        Cursor c = null;
        try {
            c = getReadableDatabase().rawQuery(
                    "SELECT COUNT(*) FROM " + TABLE_ATTACH + " WHERE event_id = ?",
                    new String[]{String.valueOf(eventId)});
            if (c != null && c.moveToFirst()) {
                return c.getInt(0);
            }
        } finally {
            closeQuietly(c);
        }
        return 0;
    }

    /** 全部附件，按事件与加入顺序 */
    public List<Attachment> queryAllAttachments() {
        ArrayList<Attachment> list = new ArrayList<>();
        Cursor c = null;
        try {
            c = getReadableDatabase().query(TABLE_ATTACH, ATTACH_COLS,
                    null, null, null, null, "event_id ASC, id ASC");
            while (c != null && c.moveToNext()) {
                list.add(readAttachment(c));
            }
        } finally {
            closeQuietly(c);
        }
        return list;
    }

    /** 没有归属事件的附件（事件被删掉但选择保留附件） */
    public List<Attachment> queryOrphanAttachments() {
        ArrayList<Attachment> list = new ArrayList<>();
        Cursor c = null;
        try {
            c = getReadableDatabase().rawQuery(
                    "SELECT " + colList() + " FROM " + TABLE_ATTACH + " a"
                            + " WHERE NOT EXISTS (SELECT 1 FROM " + TABLE + " e WHERE e.id = a.event_id)"
                            + " ORDER BY a.id ASC", null);
            while (c != null && c.moveToNext()) {
                list.add(readAttachment(c));
            }
        } finally {
            closeQuietly(c);
        }
        return list;
    }

    private static String colList() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < ATTACH_COLS.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("a.").append(ATTACH_COLS[i]);
        }
        return sb.toString();
    }

    public Attachment getAttachment(long id) {
        Cursor c = null;
        try {
            c = getReadableDatabase().query(TABLE_ATTACH, ATTACH_COLS,
                    "id = ?", new String[]{String.valueOf(id)}, null, null, null);
            if (c != null && c.moveToFirst()) {
                return readAttachment(c);
            }
        } finally {
            closeQuietly(c);
        }
        return null;
    }

    public int deleteAttachment(long id) {
        return getWritableDatabase().delete(TABLE_ATTACH, "id = ?", new String[]{String.valueOf(id)});
    }

    public int deleteAttachmentsOf(long eventId) {
        return getWritableDatabase().delete(TABLE_ATTACH, "event_id = ?",
                new String[]{String.valueOf(eventId)});
    }

    /**
     * 只删事件、把附件改成「无归属」，交给「附带文件」页统一管理。
     * 返回被摘下来的附件条数。
     */
    public int detachAttachmentsOf(long eventId) {
        ContentValues v = new ContentValues();
        v.put("event_id", 0);
        return getWritableDatabase().update(TABLE_ATTACH, v, "event_id = ?",
                new String[]{String.valueOf(eventId)});
    }

    public int countOrphans() {
        Cursor c = null;
        try {
            c = getReadableDatabase().rawQuery(
                    "SELECT COUNT(*) FROM " + TABLE_ATTACH + " a"
                            + " WHERE NOT EXISTS (SELECT 1 FROM " + TABLE + " e WHERE e.id = a.event_id)",
                    null);
            if (c != null && c.moveToFirst()) {
                return c.getInt(0);
            }
        } finally {
            closeQuietly(c);
        }
        return 0;
    }

    public void clearAttachments() {
        getWritableDatabase().delete(TABLE_ATTACH, null, null);
    }

    private static void closeQuietly(Cursor c) {
        if (c != null) {
            try {
                c.close();
            } catch (Exception ignored) {
            }
        }
    }
}
