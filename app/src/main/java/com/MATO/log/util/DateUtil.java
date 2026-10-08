package com.MATO.log.util;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

/**
 * 时间计算与展示。
 * 全部基于设备本地时区；「周」一律以周一为一周的第一天。
 *
 * <p><b>1.3.5：展示部分跟着应用内语言走。</b>这个类是纯静态工具、拿不到 Context，
 * 所以语言从 {@link Lang#locale()} 取（Application 启动时与用户改语言时各刷新一次）。
 * 日期的写法不是翻译问题而是语言事实 —— 中文写「2026年10月8日」、英文写
 * 「Oct 8, 2026」、韩文写「2026년 10월 8일」—— 所以它们留在这里，
 * 不进 strings.xml 让翻译去猜。星期的名字直接交给 {@link SimpleDateFormat}
 * 的 {@code EEE}（各语言 JDK 自带的星期缩写本来就是对的），
 * 只有月历表头要的单字名得自己列一张表。
 */
public final class DateUtil {

    private DateUtil() {
    }

    // ---------------- 语言相关的展示表 ----------------

    /** 一种语言下的日期写法与时长说法 */
    private static final class Fmt {
        /** 日期：2026年10月8日 / 2026年10月8日 / 2026년 10월 8일 / 2026年10月8日 / Oct 8, 2026 */
        final String ymd;
        /** 短日期：10月8日 / 10月8日 / 10월 8일 / 10月8日 / Oct 8 */
        final String md;
        /** 年月：2026年10月 / … / 2026년 10월 / 2026年10月 / October 2026 */
        final String ym;
        /** 年：2026年 / … / 2026년 / 2026年 / 2026 */
        final String y;
        /** 月份名：10月 / … / 10월 / 10月 / October */
        final String monthName;
        /** 带星期的整日：2026年10月8日 周四 / EEE, MMM d, yyyy … */
        final String fullDay;
        /** 带星期的短日 */
        final String shortDay;
        /** 带时分的时间戳 */
        final String stamp;
        /** 时长：分钟 / 小时 … */
        final String durMin;
        final String durHour;
        final String durHourMin;
        final String durNone;
        final String durLessThanOne;
        /** 月历表头的单字星期名，索引 0 = 周一 */
        final String[] head;

        Fmt(String ymd, String md, String ym, String y, String monthName,
            String fullDay, String shortDay, String stamp,
            String durMin, String durHour, String durHourMin,
            String durNone, String durLessThanOne, String[] head) {
            this.ymd = ymd;
            this.md = md;
            this.ym = ym;
            this.y = y;
            this.monthName = monthName;
            this.fullDay = fullDay;
            this.shortDay = shortDay;
            this.stamp = stamp;
            this.durMin = durMin;
            this.durHour = durHour;
            this.durHourMin = durHourMin;
            this.durNone = durNone;
            this.durLessThanOne = durLessThanOne;
            this.head = head;
        }
    }

    private static final String[] HEAD_ZH = {"一", "二", "三", "四", "五", "六", "日"};
    private static final String[] HEAD_JA = {"月", "火", "水", "木", "金", "土", "日"};
    private static final String[] HEAD_KO = {"월", "화", "수", "목", "금", "토", "일"};
    private static final String[] HEAD_EN = {"M", "T", "W", "T", "F", "S", "S"};

    private static final Fmt ZH_CN = new Fmt(
            "yyyy年M月d日", "M月d日", "yyyy年M月", "yyyy年", "M月",
            "yyyy年M月d日 EEE", "M月d日 EEE", "yyyy年MM月dd日 HH:mm",
            "%d 分钟", "%d 小时", "%d 小时 %d 分", "0 分钟", "不到 1 分钟", HEAD_ZH);

    private static final Fmt ZH_TW = new Fmt(
            "yyyy年M月d日", "M月d日", "yyyy年M月", "yyyy年", "M月",
            "yyyy年M月d日 EEE", "M月d日 EEE", "yyyy年MM月dd日 HH:mm",
            "%d 分鐘", "%d 小時", "%d 小時 %d 分", "0 分鐘", "不到 1 分鐘", HEAD_ZH);

    private static final Fmt JA = new Fmt(
            "yyyy年M月d日", "M月d日", "yyyy年M月", "yyyy年", "M月",
            "yyyy年M月d日 (EEE)", "M月d日 (EEE)", "yyyy年MM月dd日 HH:mm",
            "%d 分", "%d 時間", "%d 時間 %d 分", "0 分", "1 分未満", HEAD_JA);

    private static final Fmt KO = new Fmt(
            "yyyy년 M월 d일", "M월 d일", "yyyy년 M월", "yyyy년", "M월",
            "yyyy년 M월 d일 EEE", "M월 d일 EEE", "yyyy년 MM월 dd일 HH:mm",
            "%d분", "%d시간", "%d시간 %d분", "0분", "1분 미만", HEAD_KO);

    private static final Fmt EN = new Fmt(
            "MMM d, yyyy", "MMM d", "MMMM yyyy", "yyyy", "MMMM",
            "EEE, MMM d, yyyy", "EEE, MMM d", "MMM d, yyyy HH:mm",
            "%d min", "%d h", "%d h %d m", "0 min", "<1 min", HEAD_EN);

    /** 当前语言对应的那一套写法 */
    private static Fmt fmt() {
        Locale l = Lang.locale();
        String lang = l == null ? "zh" : l.getLanguage();
        if ("en".equals(lang)) {
            return EN;
        }
        if ("ja".equals(lang)) {
            return JA;
        }
        if ("ko".equals(lang)) {
            return KO;
        }
        // 中文分简繁：只差「分鐘 / 小時」这类用词，日期写法相同
        if ("zh".equals(lang)) {
            String country = l.getCountry();
            if ("TW".equals(country) || "HK".equals(country) || "MO".equals(country)) {
                return ZH_TW;
            }
        }
        return ZH_CN;
    }

    private static Locale fmtLocale() {
        Locale l = Lang.locale();
        return l == null ? Locale.SIMPLIFIED_CHINESE : l;
    }

    /** 月历表头用的单字星期名，索引 0 = 周一 */
    public static String[] weekHead() {
        return fmt().head;
    }

    // ---------------- 计算 ----------------

    public static Calendar calendar() {
        return Calendar.getInstance();
    }

    /** 当天 00:00:00.000 */
    public static long startOfToday() {
        return startOfDay(System.currentTimeMillis());
    }

    public static long startOfDay(long millis) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(millis);
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    /** 本周一 00:00:00.000 */
    public static long startOfWeek(long millis) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(startOfDay(millis));
        int shift = (c.get(Calendar.DAY_OF_WEEK) + 5) % 7; // 周日=1 → 6，周一=2 → 0
        c.add(Calendar.DAY_OF_MONTH, -shift);
        return c.getTimeInMillis();
    }

    /** 本月 1 日 00:00:00.000 */
    public static long startOfMonth(long millis) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(startOfDay(millis));
        c.set(Calendar.DAY_OF_MONTH, 1);
        return c.getTimeInMillis();
    }

    /** 本年 1 月 1 日 00:00:00.000 */
    public static long startOfYear(long millis) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(startOfDay(millis));
        c.set(Calendar.DAY_OF_MONTH, 1);
        c.set(Calendar.MONTH, Calendar.JANUARY);
        return c.getTimeInMillis();
    }

    /**
     * 按「天」平移。始终先归零到当天 00:00 再加减，
     * 这样 +6 天得到的一定是第 7 天的零点（可以直接当作右开区间的边界）。
     */
    public static long addDays(long millis, int days) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(startOfDay(millis));
        c.add(Calendar.DAY_OF_MONTH, days);
        return c.getTimeInMillis();
    }

    public static long addMonths(long millis, int months) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(millis);
        c.set(Calendar.DAY_OF_MONTH, 1);
        c.add(Calendar.MONTH, months);
        return c.getTimeInMillis();
    }

    public static long addYears(long millis, int years) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(millis);
        c.set(Calendar.DAY_OF_MONTH, 1);
        c.set(Calendar.MONTH, Calendar.JANUARY);
        c.add(Calendar.YEAR, years);
        return c.getTimeInMillis();
    }

    public static int year(long millis) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(millis);
        return c.get(Calendar.YEAR);
    }

    /** 1-12 */
    public static int month(long millis) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(millis);
        return c.get(Calendar.MONTH) + 1;
    }

    /** 1-31 */
    public static int dayOfMonth(long millis) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(millis);
        return c.get(Calendar.DAY_OF_MONTH);
    }

    /** 0=周一 … 6=周日 */
    public static int weekdayIndex(long millis) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(millis);
        return (c.get(Calendar.DAY_OF_WEEK) + 5) % 7;
    }

    public static int hour(long millis) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(millis);
        return c.get(Calendar.HOUR_OF_DAY);
    }

    public static int minute(long millis) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(millis);
        return c.get(Calendar.MINUTE);
    }

    public static int daysInMonth(long millis) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(startOfMonth(millis));
        return c.getActualMaximum(Calendar.DAY_OF_MONTH);
    }

    /** 该月 1 日是周几（0=周一 … 6=周日），用于月历首行留白 */
    public static int firstWeekdayOffset(long millis) {
        return weekdayIndex(startOfMonth(millis));
    }

    public static boolean isSameDay(long a, long b) {
        return startOfDay(a) == startOfDay(b);
    }

    public static boolean isToday(long millis) {
        return startOfDay(millis) == startOfToday();
    }

    public static int daysBetween(long from, long to) {
        return (int) ((startOfDay(to) - startOfDay(from)) / 86400000L);
    }

    // ---------- 展示 ----------

    public static String pad2(int v) {
        return v < 10 ? "0" + v : String.valueOf(v);
    }

    public static String leadingZero(int v) {
        return v < 10 ? "0" + v : String.valueOf(v);
    }

    /** 形如「15:04」。24 小时制，不跟语言走 —— 所有语言下都是这个写法 */
    public static String hhmm(long millis) {
        return pad2(hour(millis)) + ":" + pad2(minute(millis));
    }

    private static String format(long millis, String pattern) {
        return new SimpleDateFormat(pattern, fmtLocale()).format(new Date(millis));
    }

    public static String ymd(long millis) {
        return format(millis, fmt().ymd);
    }

    public static String md(long millis) {
        return format(millis, fmt().md);
    }

    public static String ym(long millis) {
        return format(millis, fmt().ym);
    }

    public static String y(long millis) {
        return format(millis, fmt().y);
    }

    /** 月份名：10月 / October / 10월 */
    public static String monthName(long millis) {
        return format(millis, fmt().monthName);
    }

    /** 形如「2026年10月8日 周四」/「Thu, Oct 8, 2026」 */
    public static String fullDay(long millis) {
        return format(millis, fmt().fullDay);
    }

    /** 形如「10月8日 周四」/「Thu, Oct 8」 */
    public static String shortDay(long millis) {
        return format(millis, fmt().shortDay);
    }

    public static String dayNumber(long millis) {
        return String.valueOf(dayOfMonth(millis));
    }

    /** 形如「2026年10月8日 17:33」/「Oct 8, 2026 17:33」 */
    public static String stamp(long millis) {
        return format(millis, fmt().stamp);
    }

    /** 形如「20261008-173301」，用于文件名。**不跟语言走**：文件名要能排序、能跨语言认出来 */
    public static String fileStamp(long millis) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(millis);
        return String.format(Locale.US, "%04d%02d%02d-%02d%02d%02d",
                c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH),
                c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), c.get(Calendar.SECOND));
    }

    public static String humanSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return (bytes / 1024) + " KB";
        }
        return String.format(Locale.US, "%.1f MB", bytes / 1048576.0);
    }

    // ---------------- 区间与时长的展示（1.3 新增） ----------------

    /**
     * 一段区间的文字，形如「09:12 – 09:47」。
     *
     * <p>跨天时要带上日期，否则「23:50 – 00:20」看着像是倒着走的。
     */
    public static String span(long startMs, long endMs) {
        if (endMs <= startMs) {
            return hhmm(startMs);
        }
        if (isSameDay(startMs, endMs)) {
            return hhmm(startMs) + " – " + hhmm(endMs);
        }
        return md(startMs) + " " + hhmm(startMs) + " – " + md(endMs) + " " + hhmm(endMs);
    }

    /** 列表右侧的紧凑时长：45m / 1h20。**不跟语言走** —— 它是标度，不是句子 */
    public static String durationShort(long ms) {
        long totalMin = Math.round(ms / 60000.0);
        if (totalMin < 1) {
            return "<1m";
        }
        if (totalMin < 60) {
            return totalMin + "m";
        }
        long h = totalMin / 60;
        long m = totalMin % 60;
        return m == 0 ? h + "h" : h + "h" + m;
    }

    /** 自然语言时长：「1 小时 20 分」「45 分钟」「不到 1 分钟」（随语言） */
    public static String duration(long ms) {
        Fmt f = fmt();
        if (ms <= 0) {
            return f.durNone;
        }
        long totalMin = Math.round(ms / 60000.0);
        if (totalMin < 1) {
            return f.durLessThanOne;
        }
        long h = totalMin / 60;
        long m = totalMin % 60;
        if (h == 0) {
            return String.format(fmtLocale(), f.durMin, totalMin);
        }
        if (m == 0) {
            return String.format(fmtLocale(), f.durHour, h);
        }
        return String.format(fmtLocale(), f.durHourMin, h, m);
    }
}
