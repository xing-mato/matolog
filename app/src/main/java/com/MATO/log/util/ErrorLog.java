package com.MATO.log.util;

import android.content.Context;
import android.os.Build;

import com.MATO.log.cap.Txt;
import com.MATO.log.data.DbHelper;
import com.MATO.log.rec.Recorder;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * 报错日志（1.3.5 新增）。
 *
 * <p><b>为什么要有它</b>：这个应用出问题时，用户能提供的只有一句「它不好使」。
 * 而这个应用的大量行为发生在后台（采样、切段、进库），出了岔子往往不留任何痕迹 ——
 * 开发者拿到设备之前基本无从下手。所以让应用自己把**真实发生过的异常**记下来，
 * 需要时由用户导出一份带走。
 *
 * <p><b>为什么放在私有目录 {@code files/errors/}，而不是 {@code mato-cache/}</b>：
 * 缓存目录是给用户随手清空的地方（设置里就有一个「清空全部缓存」），
 * 而这份日志是「出了事之后要留住的证据」—— 两者不该同命运。
 * 卸载应用时它跟着消失，这是对的：它是给这一次故障用的，不是长期档案。
 *
 * <p><b>门槛</b>：设置页那一行只有在 {@link #count} 大于 0 时才可点。
 * 一个随时能导、导出来却总是空文件的按钮，只会让用户白跑一趟并怀疑是应用又坏了 ——
 * 「防空包」是需求点名要的。
 *
 * <p><b>不记事件正文</b>：报告里只有条数、设置、设备信息和异常本身。
 * 把使用记录抄进一份准备发给别人的文件里，是另一件需要单独同意的事。
 */
public final class ErrorLog {

    /** 私有目录下的子目录名 */
    private static final String DIR = "errors";
    private static final String FILE = "error.log";

    /** 每条记录的头部前缀。数条数、取最后一条的时刻都靠它，别改 */
    private static final String HEAD = "=== ";

    /** 最多留多少条；超了就丢最旧的 */
    private static final int MAX_ENTRIES = 200;
    /** 文件超过这个大小就裁剪一次 */
    private static final long MAX_BYTES = 256 * 1024L;

    private static final Object LOCK = new Object();

    private ErrorLog() {
    }

    // ---------------- 落点 ----------------

    /**
     * 日志所在目录。**会创建**，所以只给写入路径用。
     *
     * <p>读路径（{@link #count} / {@link #readEntries}）不要走这里：
     * 光是打开设置页看一眼条数，不该在磁盘上留下一个空目录。
     */
    public static File dir(Context c) {
        File d = new File(c.getFilesDir(), DIR);
        if (!d.exists()) {
            //noinspection ResultOfMethodCallIgnored
            d.mkdirs();
        }
        return d;
    }

    /** 日志文件路径。**不创建任何东西** —— 读的时候它多半不存在，那正是「没出过问题」 */
    public static File file(Context c) {
        return new File(new File(c.getFilesDir(), DIR), FILE);
    }

    // ---------------- 写入 ----------------

    public static void record(Context c, String type, String detail) {
        record(c, type, detail, null);
    }

    /**
     * 记一条。**这个方法是给「真的出问题了」用的**，不要拿它当日志用 ——
     * 它每一次调用都会让设置页那个导出入口亮起来，记进去的噪音
     * 会让真正的那条淹掉。
     */
    public static void record(Context c, String type, String detail, Throwable t) {
        if (c == null) {
            return;
        }
        String now = stamp(System.currentTimeMillis());
        StringBuilder sb = new StringBuilder(256);
        sb.append(HEAD).append(now).append(" | ").append(nz(type))
                .append(" | ").append(Thread.currentThread().getName()).append('\n');
        if (detail != null && !detail.isEmpty()) {
            sb.append(nz(detail)).append('\n');
        }
        if (t != null) {
            sb.append(t.getClass().getName());
            if (t.getMessage() != null) {
                sb.append(": ").append(t.getMessage());
            }
            sb.append('\n');
            StackTraceElement[] st = t.getStackTrace();
            for (int i = 0; i < st.length && i < 40; i++) {
                sb.append("    at ").append(st[i]).append('\n');
            }
        }
        sb.append('\n');

        synchronized (LOCK) {
            try {
                dir(c);          // 只有真正要写的时候才建目录
                File f = file(c);
                Txt.append(f, sb.toString());
                trimIfHuge(c, f);
            } catch (Throwable ignored) {
                // 记日志这件事本身失败了也不能再抛 —— 它多半是在异常处理路径里被调用的
            }
        }
    }

    /** 超过上限就只保留最近 {@link #MAX_ENTRIES} 条 */
    private static void trimIfHuge(Context c, File f) {
        if (f.length() <= MAX_BYTES) {
            return;
        }
        List<String> all = readEntries(c);
        if (all.size() <= MAX_ENTRIES) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = all.size() - MAX_ENTRIES; i < all.size(); i++) {
            sb.append(all.get(i));
        }
        Txt.writeQuietly(f, sb.toString());
    }

    // ---------------- 读取 ----------------

    /** 全部记录，最旧的在前 */
    public static List<String> readEntries(Context c) {
        ArrayList<String> out = new ArrayList<>();
        if (c == null) {
            return out;
        }
        String text;
        try {
            text = Txt.read(file(c));
        } catch (Throwable t) {
            return out;
        }
        if (text == null || text.isEmpty()) {
            return out;
        }
        StringBuilder cur = null;
        String[] lines = text.split("\n", -1);
        for (String line : lines) {
            if (line.startsWith(HEAD)) {
                if (cur != null) {
                    out.add(cur.toString());
                }
                cur = new StringBuilder();
            }
            if (cur != null) {
                cur.append(line).append('\n');
            }
        }
        if (cur != null) {
            out.add(cur.toString());
        }
        return out;
    }

    /** 记了多少条 —— 设置页那个门槛判的就是它 */
    public static int count(Context c) {
        return readEntries(c).size();
    }

    /** 有没有真出过问题 */
    public static boolean hasProblems(Context c) {
        return count(c) > 0;
    }

    /** 最后一条的时刻（毫秒）；没有则 0 */
    public static long lastAt(Context c) {
        List<String> all = readEntries(c);
        if (all.isEmpty()) {
            return 0L;
        }
        return parseStamp(all.get(all.size() - 1));
    }

    /** 最后一条的短标签，如 {@code 10-08 03:21}；没有则空串 */
    public static String lastLabel(Context c) {
        long at = lastAt(c);
        if (at <= 0) {
            return "";
        }
        return new SimpleDateFormat("MM-dd HH:mm", Locale.US).format(new Date(at));
    }

    public static void clear(Context c) {
        if (c == null) {
            return;
        }
        synchronized (LOCK) {
            try {
                File f = file(c);
                if (f.exists()) {
                    //noinspection ResultOfMethodCallIgnored
                    f.delete();
                }
            } catch (Throwable ignored) {
            }
        }
    }

    // ---------------- 崩溃 ----------------

    /**
     * 装上未捕获异常的记录器。
     *
     * <p><b>一定要把异常交回给原来的处理器</b>：系统要靠它弹那个「应用已停止运行」、
     * 也要靠它走完崩溃流程。我们只是**顺手记一笔**，不是要把崩溃吞掉 ——
     * 一个悄悄吞掉崩溃的应用，比一个会崩的应用更难查。
     */
    public static void installCrashHandler(final Context c) {
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread t, Throwable e) {
                try {
                    record(c, "crash", "未捕获异常，线程：" + t.getName(), e);
                } catch (Throwable ignored) {
                }
                if (prev != null) {
                    prev.uncaughtException(t, e);
                }
            }
        });
    }

    // ---------------- 导出 ----------------

    /**
     * 拼一份诊断报告。
     *
     * <p>分三段：设备、应用、当前状态，最后把异常原文附上。
     * 前两段看着像废话，但它们决定了这份报告能不能独立看懂 ——
     * 一台 vivo Android 9 上的采样异常和一台 Android 15 上的很可能是两回事。
     *
     * <p>**不含任何事件正文**，只有条数。
     */
    public static String buildReport(Context c) {
        StringBuilder sb = new StringBuilder(4096);
        sb.append("MATOlog 诊断报告\n");
        sb.append("生成时间：").append(stamp(System.currentTimeMillis())).append('\n');
        sb.append("说明：本报告不含任何记录正文，只有条数、设置与异常信息。\n");

        sb.append("\n【设备】\n");
        sb.append("厂商 / 型号：").append(nz(Build.MANUFACTURER)).append(' ').append(nz(Build.MODEL)).append('\n');
        sb.append("Android：").append(nz(Build.VERSION.RELEASE))
                .append("（API ").append(Build.VERSION.SDK_INT).append("）\n");
        sb.append("构建显示名：").append(nz(Build.DISPLAY)).append('\n');
        sb.append("ABI：").append(join(Build.SUPPORTED_ABIS)).append('\n');
        sb.append("语言 / 国家：").append(nz(Locale.getDefault().toString())).append('\n');
        sb.append("时区：").append(nz(TimeZone.getDefault().getID())).append('\n');

        sb.append("\n【应用】\n");
        sb.append("包名：").append(c.getPackageName()).append('\n');
        sb.append("版本：").append(versionName(c)).append("（").append(versionCode(c)).append("）\n");
        sb.append("调试包：").append(isDebug(c) ? "是" : "否").append('\n');
        sb.append("数据库版本：").append(DbHelper.DB_VERSION).append('\n');

        sb.append("\n【记录状态】\n");
        sb.append("自动记录：").append(Prefs.autoRecord(c) ? "开" : "关").append('\n');
        sb.append("采样间隔：").append(Prefs.sampleMs(c) / 1000L).append(" 秒\n");
        sb.append("短于多久不计：").append(Prefs.minKeepMs(c) / 60_000L).append(" 分钟\n");
        sb.append("使用情况访问：").append(Perm.usageAccess(c) ? "已授予" : "未授予").append('\n');
        try {
            DbHelper db = new DbHelper(c);
            try {
                sb.append("事件条数：").append(db.countAll()).append('\n');
                sb.append("排除名单：").append(db.countRule(DbHelper.RULE_EXCLUDE)).append(" 个\n");
            } finally {
                db.close();
            }
        } catch (Throwable t) {
            sb.append("事件条数：读取失败（").append(t.getClass().getSimpleName()).append("）\n");
        }
        Recorder rec = Recorder.get();
        sb.append("记录服务：").append(rec == null ? "未在运行" : "运行中").append('\n');

        List<String> all = readEntries(c);
        sb.append("\n【问题 ").append(all.size()).append(" 条");
        if (!all.isEmpty()) {
            sb.append("，最近 ").append(lastLabel(c));
        }
        sb.append("】\n");
        if (all.isEmpty()) {
            sb.append("（没有记录到任何问题）\n");
        } else {
            for (int i = 0; i < all.size(); i++) {
                sb.append("\n----- ").append(i + 1).append('/').append(all.size()).append(" -----\n");
                sb.append(all.get(i));
            }
        }
        return sb.toString();
    }

    /** 报告文件名，形如 {@code MATOlog-error-20261008-032133.txt} */
    public static String reportFileName(long millis) {
        return "MATOlog-error-" + DateUtil.fileStamp(millis) + ".txt";
    }

    // ---------------- 小工具 ----------------

    private static String stamp(long millis) {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date(millis));
    }

    private static long parseStamp(String entry) {
        if (entry == null || entry.length() < HEAD.length() + 19) {
            return 0L;
        }
        try {
            String s = entry.substring(HEAD.length(), HEAD.length() + 19);
            return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).parse(s).getTime();
        } catch (Throwable t) {
            return 0L;
        }
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static String join(String[] a) {
        if (a == null || a.length == 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < a.length; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(a[i]);
        }
        return sb.toString();
    }

    private static String versionName(Context c) {
        try {
            return nz(c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName);
        } catch (Throwable t) {
            return "?";
        }
    }

    private static long versionCode(Context c) {
        try {
            android.content.pm.PackageInfo i =
                    c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            if (Build.VERSION.SDK_INT >= 28) {
                return i.getLongVersionCode();
            }
            return i.versionCode;
        } catch (Throwable t) {
            return 0L;
        }
    }

    private static boolean isDebug(Context c) {
        return (c.getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0;
    }
}
