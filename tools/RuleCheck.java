import com.MATO.log.rec.AppFilter;

import java.util.HashSet;
import java.util.Set;

/**
 * 「记不记」判定的离线用例 —— 纯 Java，不需要设备、不需要 Android 运行时
 * （{@link AppFilter} 本身也不碰 Android API）。
 *
 * <p><b>为什么要这份</b>：这一层出过的错是「界面上看不见的规则」——
 * 名单页里显示未排除、实际却不记。这种错不会崩、不会报异常，只会让用户对着
 * 时间轴反复怀疑自己。它也没有任何界面能自证，所以必须有一份能跑的断言。
 *
 * <p><b>它守的是什么</b>：规则一共四条（自身 / 系统界面 / 排除 / 其余都记），
 * 顺序错了结果就变。所以每个用例都同时断言「该记的记」和「该不记的不记」——
 * 只断言一半的话，把 {@code allow} 改成恒 true 也能全过。
 *
 * <p>用法（与其它 Check 一致，只数 ASCII 的 pass=/fail=）：
 * <pre>
 *   javac -encoding UTF-8 -cp "&lt;release classes&gt;" -d out tools/RuleCheck.java
 *   java -cp "out;&lt;release classes&gt;" RuleCheck
 * </pre>
 */
public final class RuleCheck {

    private static int pass = 0;
    private static int fail = 0;

    /** 一个普通应用（用户自己装的） */
    private static final String NOTE = "com.example.note";
    /** 一个系统应用：相机。名单页里能选到，1.3.3 之前永远不记 */
    private static final String CAMERA = "com.android.camera";
    /** 系统自带的笔记类应用：正是用户会想记的那类 */
    private static final String SYS_NOTE = "com.vivo.notepad";
    /** 桌面（AOSP 包名） */
    private static final String LAUNCHER = "com.android.launcher3";
    /** 状态栏 */
    private static final String SYSTEMUI = "com.android.systemui";

    public static void main(String[] args) {
        defaultAllowsNormalApps();
        systemAppsAreRecordedByDefault();
        shellIsNeverRecorded();
        exclusionBeatsEverything();
        selfIsNeverRecorded();
        deviceShellIsResolved();
        explainMatchesAllow();

        System.out.println("pass=" + pass + " fail=" + fail);
        if (fail > 0) {
            System.exit(1);
        }
    }

    // ---------------- 用例 ----------------

    /** 空名单：普通应用就该记。这是默认态，也是最常见的一态 */
    private static void defaultAllowsNormalApps() {
        AppFilter f = base();
        checkTrue("空名单：普通应用会记", f.allow(NOTE));
        checkInt("空名单：普通应用的原因码", AppFilter.WHY_OK, f.why(NOTE));
    }

    /**
     * 核心用例：**系统应用默认也记**。
     *
     * <p>1.3.3 之前这条是反的 —— 系统应用被一条硬编码规则挡着，而名单页里
     * 它们显示为「未排除」。用户看到的是「没排除」，通知栏报的是「系统应用，默认不记」，
     * 两处互相矛盾且改不掉后者。删掉那条规则之后，名单页的「排除」才成为唯一的控制。
     */
    private static void systemAppsAreRecordedByDefault() {
        AppFilter f = base();
        checkTrue("空名单：系统应用（相机）也记", f.allow(CAMERA));
        checkTrue("空名单：系统自带笔记也记", f.allow(SYS_NOTE));
        checkInt("系统应用的原因码不再是「默认不记」", AppFilter.WHY_OK, f.why(CAMERA));
    }

    /** 系统界面：硬性不记，排除名单管不着它，用户也不该在名单页看到它 */
    private static void shellIsNeverRecorded() {
        AppFilter f = base();
        checkTrue("状态栏不记", !f.allow(SYSTEMUI));
        checkTrue("桌面（AOSP 包名）不记", !f.allow(LAUNCHER));
        checkInt("系统界面的原因码", AppFilter.WHY_SHELL, f.why(SYSTEMUI));
        checkTrue("系统界面就是系统界面", AppFilter.isShell(SYSTEMUI));
        checkTrue("普通应用不是系统界面", !AppFilter.isShell(NOTE));

        // 即使有人手工把它写进排除名单之外的地方，也不该被认成可记录的应用
        AppFilter f2 = base().excluded(setOf(SYSTEMUI));
        checkTrue("系统界面进了排除名单也还是不记", !f2.allow(SYSTEMUI));
    }

    /** 排除是唯一的开关：命中就不记，普通应用与系统应用一视同仁 */
    private static void exclusionBeatsEverything() {
        AppFilter f = base().excluded(setOf(NOTE, SYS_NOTE));
        checkTrue("被排除的普通应用不记", !f.allow(NOTE));
        checkTrue("被排除的系统应用不记", !f.allow(SYS_NOTE));
        checkInt("被排除的原因码", AppFilter.WHY_EXCLUDED, f.why(NOTE));
        // 没被排除的那些不受影响 —— 排除名单不该有「非空时其余全不记」那种副作用
        checkTrue("同名单里没被排除的应用照记", f.allow(CAMERA));
    }

    /** 自身：任何时候都不记，否则切到设置页再回来就会自己记录自己，滚雪球 */
    private static void selfIsNeverRecorded() {
        AppFilter f = base().self("com.MATO.log", "com.MATO.log.debug");
        checkTrue("正式包不记自己", !f.allow("com.MATO.log"));
        checkTrue("调试包也不记（同机并存时）", !f.allow("com.MATO.log.debug"));
        checkInt("自身的原因码", AppFilter.WHY_SELF, f.why("com.MATO.log"));
        checkTrue("别的应用不受影响", f.allow(NOTE));
    }

    /**
     * 本机桌面包名是**运行时解析**出来的，不靠那张写死的表。
     *
     * <p>这台 vivo Z1 上的桌面是 {@code com.bbk.launcher2}，兜底表里一个都不匹配 ——
     * 漏掉的后果是「按 Home 回桌面」被记成一条应用使用。
     */
    private static void deviceShellIsResolved() {
        String vivoLauncher = "com.bbk.launcher2";
        checkTrue("解析之前：不在兜底表里", !AppFilter.isShell(vivoLauncher));

        AppFilter.addDeviceShell(vivoLauncher, null, "");
        checkTrue("注入之后：认出来了", AppFilter.isShell(vivoLauncher));
        checkTrue("注入之后：它不会被记", !base().allow(vivoLauncher));
        checkInt("注入之后：原因码是系统界面", AppFilter.WHY_SHELL, base().why(vivoLauncher));
        // 只影响被注入的那一个，不该顺手把别的应用也关了
        checkTrue("注入不影响普通应用", base().allow(NOTE));
    }

    /**
     * {@code why()} 的分支顺序必须与 {@code allow()} 一致。
     *
     * <p>两处一旦分叉，就会出现「说明说会记、实际不记」这类正好是这次要修的毛病。
     * 这里逐个包对照：判为 {@code WHY_OK} 的必须真的会记，判成别的必须真的不记。
     *
     * <p>1.3.5：断言从「文案是不是『记录中』」改成「原因码是不是 WHY_OK」——
     * 文案现在跟应用内语言走，拿它当断言会让用例随语言漂移，
     * 而且这个用例本来就是纯逻辑、连 android.jar 都不需要。
     */
    private static void explainMatchesAllow() {
        AppFilter f = base()
                .self("com.MATO.log")
                .excluded(setOf(NOTE));
        String[] pkgs = {
                "com.MATO.log", NOTE, CAMERA, SYS_NOTE, SYSTEMUI, LAUNCHER,
                "com.bbk.launcher2", "",
        };
        for (String p : pkgs) {
            boolean allowed = f.allow(p);
            int why = f.why(p);
            boolean saidOk = why == AppFilter.WHY_OK;
            checkTrue("原因码与判定一致：" + (p.isEmpty() ? "(空包名)" : p)
                            + " allow=" + allowed + " why=" + why,
                    allowed == saidOk);
        }
    }

    // ---------------- 工具 ----------------

    private static AppFilter base() {
        return new AppFilter();
    }

    private static Set<String> setOf(String... pkgs) {
        Set<String> s = new HashSet<>();
        for (String p : pkgs) {
            s.add(p);
        }
        return s;
    }

    private static void check(String what, String expect, String actual) {
        if (expect.equals(actual)) {
            pass++;
        } else {
            fail++;
            System.out.println("FAIL: " + what + "  期望=" + expect + "  实际=" + actual);
        }
    }

    /** 原因码的断言（1.3.5：判定改成返回码之后加的） */
    private static void checkInt(String what, int expect, int actual) {
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
