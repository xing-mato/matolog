import com.MATO.log.util.DateUtil;

import java.util.Calendar;

/** DateUtil 的独立校验：不依赖 Android，直接用 JDK 跑 */
public class DateCheck {

    static int pass = 0;
    static int fail = 0;

    static void eq(String what, Object expected, Object actual) {
        boolean ok = expected == null ? actual == null : expected.equals(actual);
        if (ok) {
            pass++;
            System.out.println("[OK]   " + what + " = " + actual);
        } else {
            fail++;
            System.out.println("[FAIL] " + what + " expected=" + expected + " actual=" + actual);
        }
    }

    static long at(int y, int mo, int d, int h, int mi) {
        Calendar c = Calendar.getInstance();
        c.set(y, mo - 1, d, h, mi, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    public static void main(String[] args) {
        System.out.println("=== 周起点（周一为一周第一天）===");
        // 2026-09-20 是周日
        eq("2026-09-20 周起点", DateUtil.startOfWeek(at(2026, 9, 20, 12, 0)),
                DateUtil.startOfDay(at(2026, 9, 14, 0, 0)));
        eq("2026-09-20 是周几(6=周日)", 6, DateUtil.weekdayIndex(at(2026, 9, 20, 0, 0)));
        eq("2026-09-14 是周几(0=周一)", 0, DateUtil.weekdayIndex(at(2026, 9, 14, 0, 0)));
        eq("2026-09-19 周起点", DateUtil.startOfWeek(at(2026, 9, 19, 23, 59)),
                DateUtil.startOfDay(at(2026, 9, 14, 0, 0)));

        System.out.println();
        System.out.println("=== 月 / 年边界 ===");
        eq("2026-09-20 月起点", DateUtil.startOfMonth(at(2026, 9, 20, 8, 0)),
                DateUtil.startOfDay(at(2026, 9, 1, 0, 0)));
        eq("2026-09-20 年起点", DateUtil.startOfYear(at(2026, 9, 20, 8, 0)),
                DateUtil.startOfDay(at(2026, 1, 1, 0, 0)));
        eq("9月天数", 30, DateUtil.daysInMonth(at(2026, 9, 1, 0, 0)));
        eq("2月天数(闰年2028)", 29, DateUtil.daysInMonth(at(2028, 2, 1, 0, 0)));
        eq("2月天数(平年2026)", 28, DateUtil.daysInMonth(at(2026, 2, 1, 0, 0)));
        eq("2026-09-01 首行留白(周二=1)", 1, DateUtil.firstWeekdayOffset(at(2026, 9, 1, 0, 0)));
        eq("2026-11-01 首行留白(周日=6)", 6, DateUtil.firstWeekdayOffset(at(2026, 11, 1, 0, 0)));

        System.out.println();
        System.out.println("=== 加减跨月跨年 ===");
        eq("9-30 +1 天", DateUtil.startOfDay(at(2026, 10, 1, 0, 0)), DateUtil.addDays(at(2026, 9, 30, 5, 0), 1));
        eq("1-01 -1 天", DateUtil.startOfDay(at(2025, 12, 31, 0, 0)), DateUtil.addDays(at(2026, 1, 1, 5, 0), -1));
        eq("12月 +1 月", DateUtil.startOfDay(at(2027, 1, 1, 0, 0)), DateUtil.addMonths(at(2026, 12, 15, 0, 0), 1));
        eq("1月 -1 月", DateUtil.startOfDay(at(2025, 12, 1, 0, 0)), DateUtil.addMonths(at(2026, 1, 15, 0, 0), -1));
        eq("+1 年", DateUtil.startOfDay(at(2027, 1, 1, 0, 0)), DateUtil.addYears(at(2026, 7, 4, 0, 0), 1));

        System.out.println();
        System.out.println("=== 跨月周的起点（导出页「本周」区间用）===");
        // 注意：「周」这一层跨度已在 1.3.5 删掉，但 DateUtil.startOfWeek **没有**跟着删 ——
        // 导出页的「本周」区间预设还在用它。删跨度不等于删这个函数，两者别一起清。
        long wk = DateUtil.startOfWeek(at(2026, 9, 30, 0, 0)); // 9/28(一) - 10/4(日)
        eq("跨月周起点", DateUtil.startOfDay(at(2026, 9, 28, 0, 0)), wk);

        System.out.println();
        System.out.println("=== 展示格式 ===");
        eq("fullDay", "2026年9月20日 周日", DateUtil.fullDay(at(2026, 9, 20, 15, 4)));
        eq("shortDay", "9月20日 周日", DateUtil.shortDay(at(2026, 9, 20, 15, 4)));
        eq("hh:mm", "15:04", DateUtil.hhmm(at(2026, 9, 20, 15, 4)));
        eq("文件时间戳", "20260920-150400", DateUtil.fileStamp(at(2026, 9, 20, 15, 4)));
        eq("月历表头[0]（周一）", "一", DateUtil.weekHead()[0]);
        eq("月历表头[6]（周日）", "日", DateUtil.weekHead()[6]);

        System.out.println();
        System.out.println("=== 同一天判断 ===");
        eq("同一天", true, DateUtil.isSameDay(at(2026, 9, 20, 0, 1), at(2026, 9, 20, 23, 59)));
        eq("不同天", false, DateUtil.isSameDay(at(2026, 9, 20, 23, 59), at(2026, 9, 21, 0, 1)));
        eq("daysBetween", 5, DateUtil.daysBetween(at(2026, 9, 20, 0, 0), at(2026, 9, 25, 0, 0)));

        System.out.println();
        System.out.println("结果: pass=" + pass + " fail=" + fail);
        if (fail > 0) {
            System.exit(1);
        }
    }
}
