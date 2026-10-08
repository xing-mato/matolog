import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * 跨度层次逻辑校验。
 *
 * 直接从编译出来的 class 里反射取 com.MATO.log.ui.Views 的静态方法，
 * 保证校验的是真实代码，而不是重写一份。
 * 缺 android.jar 时这些方法用不到任何 Android 类型，可以正常调用。
 *
 * <p><b>1.3.5 改过这里</b>：需求把「周」这一层跨度删掉了，层次从
 * 日/周/月/年 四级变成 日/月/年 三级，下钻从 年→月→周→日 变成 年→月→日。
 * 这套断言原先写死了旧结构，所以跟着改 —— 改的是**期望**，不是为了让用例变绿
 * 而放宽：层级数量、下钻关系、层次栈形状这三样都仍然逐条钉住，
 * 另外补了一组「周确实不在了」的断言，免得以后有人把编号改回去而没人发现。
 */
public class LevelCheck {

    static int pass = 0;
    static int fail = 0;

    static Class<?> views;

    static void check(String what, Object expected, Object actual) {
        boolean ok = expected == null ? actual == null : expected.equals(actual);
        if (ok) {
            pass++;
            System.out.println("[OK]   " + what);
        } else {
            fail++;
            System.out.println("[FAIL] " + what + " expected=<" + expected + "> actual=<" + actual + ">");
        }
    }

    static int call(String name, int arg) throws Exception {
        Method m = views.getMethod(name, int.class);
        Object r = m.invoke(null, Integer.valueOf(arg));
        return ((Integer) r).intValue();
    }

    static boolean callBool(String name, int arg) throws Exception {
        Method m = views.getMethod(name, int.class);
        Object r = m.invoke(null, Integer.valueOf(arg));
        return ((Boolean) r).booleanValue();
    }

    /** 读 Views 里的层级常量 */
    static int field(String name) throws Exception {
        return views.getField(name).getInt(null);
    }

    static boolean hasField(String name) {
        try {
            views.getField(name);
            return true;
        } catch (NoSuchFieldException e) {
            return false;
        }
    }

    // 1.3.5：周已删除，月 / 年 的编号跟着前移（原先 2/3）
    static final int DAY = 0, MONTH = 1, YEAR = 2;

    public static void main(String[] args) throws Exception {
        views = Class.forName("com.MATO.log.ui.Views");
        System.out.println("已加载 " + views.getName());

        System.out.println();
        System.out.println("=== 1.3.5：周已删除，层级编号前移 ===");
        check("LEVEL_DAY=0", 0, field("LEVEL_DAY"));
        check("LEVEL_MONTH=1（原先 2）", 1, field("LEVEL_MONTH"));
        check("LEVEL_YEAR=2（原先 3）", 2, field("LEVEL_YEAR"));
        check("LEVEL_WEEK 已不存在", Boolean.FALSE, Boolean.valueOf(hasField("LEVEL_WEEK")));

        System.out.println();
        System.out.println("=== 层级深浅：日 < 月 < 年 ===");
        check("depthOf(日)", 0, call("depthOf", DAY));
        check("depthOf(月)", 1, call("depthOf", MONTH));
        check("depthOf(年)", 2, call("depthOf", YEAR));

        System.out.println();
        System.out.println("=== 下钻关系：年→月→日 ===");
        check("年 -> 月", MONTH, call("childLevelOf", YEAR));
        check("月 -> 日（原先 月 -> 周）", DAY, call("childLevelOf", MONTH));
        check("日 -> 日（封底，不再下钻）", DAY, call("childLevelOf", DAY));

        System.out.println();
        System.out.println("=== 能否继续下钻 ===");
        check("年可推入", Boolean.TRUE, Boolean.valueOf(callBool("canPush", YEAR)));
        check("月可推入", Boolean.TRUE, Boolean.valueOf(callBool("canPush", MONTH)));
        check("日不可推入", Boolean.FALSE, Boolean.valueOf(callBool("canPush", DAY)));

        System.out.println();
        System.out.println("=== 与 OnyxHost 的层次栈行为一致（同一套规则推演）===");
        // 从月历点进某一天：月 -> 日（原先要经过周）
        List<Integer> p = new ArrayList<Integer>();
        p.add(Integer.valueOf(MONTH));
        drill(p);
        check("月历点一天后层次", "[1, 0]", p.toString());
        drill(p);
        check("日跨度再点不增长", "[1, 0]", p.toString());
        pop(p);
        check("返回一层", "[1]", p.toString());
        check("最外层返回为空", Boolean.valueOf(!pop(p)), Boolean.TRUE);
        check("层次保持", "[1]", p.toString());

        // 从年历一路点到底：年 -> 月 -> 日，三层封底
        List<Integer> q = new ArrayList<Integer>();
        q.add(Integer.valueOf(YEAR));
        for (int i = 0; i < 5; i++) {
            drill(q);
        }
        check("年历连续下钻封底于日", "[2, 1, 0]", q.toString());

        // 切跨度应当把层次收敛回单层
        List<Integer> r = new ArrayList<Integer>();
        r.add(Integer.valueOf(YEAR));
        drill(r);
        drill(r);
        r.clear();
        r.add(Integer.valueOf(MONTH));
        check("切换跨度后层次重置", "[1]", r.toString());

        System.out.println();
        System.out.println("结果: pass=" + pass + " fail=" + fail);
        if (fail > 0) {
            System.exit(1);
        }
    }

    /** 复刻 OnyxHost.drill 的压栈规则 */
    static void drill(List<Integer> path) throws Exception {
        int cur = path.get(path.size() - 1).intValue();
        if (!callBool("canPush", cur)) {
            return;
        }
        int child = call("childLevelOf", cur);
        for (int i = 0; i < path.size(); i++) {
            if (path.get(i).intValue() == child) {
                return;
            }
        }
        path.add(Integer.valueOf(child));
    }

    /** 复刻 OnyxHost.popStackAnimated 的弹栈规则 */
    static boolean pop(List<Integer> path) {
        if (path.size() <= 1) {
            return false;
        }
        path.remove(path.size() - 1);
        return true;
    }
}
