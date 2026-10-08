import com.MATO.log.rec.ForegroundState;

import java.util.ArrayList;
import java.util.List;

/**
 * 「前台状态机」的离线用例 —— 纯 Java，不需要设备、不需要 Android 运行时。
 *
 * <p><b>为什么要这份</b>：小窗 / 分屏下「谁在前台」的判定错得极其隐蔽 ——
 * 表现是「半小时的游戏记录被两分钟的聊天整个吃掉」，而这种事装到手机上要跑半小时
 * 才看得出来一次。做成纯 Java 之后，把事件序列直接喂进来就能断言结论，
 * 一秒钟跑完。
 *
 * <p><b>它测什么</b>：{@link ForegroundState#of} 在各种事件序列下的输出。
 * 这些序列是按 Android 的真实语义构造的（事件类型取值见 UsageReader.events 的注释）：
 * 应用拿焦点 -> ACTIVITY_RESUMED；失去焦点但仍可见（小窗压在上面）-> ACTIVITY_PAUSED；
 * 彻底退到后台 -> 也是 2（这套常量里 PAUSED / MOVE_TO_BACKGROUND / STOPPED 同值）。
 *
 * <p>用法（与其它 Check 一致，只数 ASCII 的 pass=/fail=）：
 * <pre>
 *   javac -encoding UTF-8 -cp "&lt;release classes&gt;;&lt;android.jar&gt;" -d out tools/ForegroundCheck.java
 *   java -cp "out;&lt;release classes&gt;;&lt;android.jar&gt;" ForegroundCheck
 * </pre>
 */
public final class ForegroundCheck {

    private static int pass = 0;
    private static int fail = 0;

    private static final String GAME = "com.example.game";
    private static final String CHAT = "com.example.chat";
    private static final String MAIL = "com.example.mail";

    public static void main(String[] args) {
        smallWindowOverGame();
        smallWindowWithoutPause();
        normalSwitch();
        backToHome();
        splitScreenSwap();
        reResumeBumpsToTop();
        emptyStream();
        pausedOnly();
        prependKeepsOrder();
        stackOrder();
        cappedRealisticTimeline();
        closedWindowMustNotLinger();
        noEventOnCloseIsModelLimit();
        coldStartSamePackage();
        intraAppNavigation();
        samePackageFullyLeft();
        coldStartThenSwitch();

        System.out.println("pass=" + pass + " fail=" + fail);
        if (fail > 0) {
            System.exit(1);
        }
    }

    // ---------------- 用例 ----------------

    /**
     * 核心场景：游戏 30 分钟，中途小窗聊天 2 分钟，关掉小窗回到游戏。
     *
     * <p>事件序列：游戏进前台 → 小窗聊天进前台（游戏 PAUSED）→ 关小窗（游戏重新 RESUMED）。
     * 修复前，采样层只看「最后一个 RESUMED」，在小窗关闭后如果拿不到新的 RESUMED
     * 就会一直认为是聊天；修复后必须回到游戏。
     */
    private static void smallWindowOverGame() {
        List<ForegroundState.Ev> evs = new ArrayList<>();
        evs.add(new ForegroundState.Ev(ForegroundState.RESUMED, GAME, t(0)));
        evs.add(new ForegroundState.Ev(ForegroundState.PAUSED, GAME, t(10)));
        evs.add(new ForegroundState.Ev(ForegroundState.RESUMED, CHAT, t(10)));
        // 小窗关掉：游戏重新拿到焦点
        evs.add(new ForegroundState.Ev(ForegroundState.RESUMED, GAME, t(12)));
        check("小窗关闭后回到游戏", GAME, ForegroundState.of(evs));
    }

    /**
     * 同一场景，但**系统没有给小窗底下的游戏发 PAUSED**（部分 ROM 的语义）。
     *
     * <p>关键在于「小窗关闭」那一刻有没有事件。两种情况要分开看，别混为一谈：
     * <ul>
     *   <li><b>关闭时聊天收到 PAUSED</b>（正常情况下都会有：它退到后台了）——
     *       聊天被移出集合，顶部自然回到游戏，不需要游戏重新 RESUMED。
     *       <b>这正是修复生效的地方</b>；</li>
     *   <li><b>关闭时一条事件都没有</b> —— 这条流里没有任何信息说明聊天已经走了，
     *       它和游戏都还在集合里且它在上。**任何算法都推不出「回到游戏」**，
     *       因为事件流里压根没有这件事。见 {@link #noEventOnCloseIsModelLimit}。</li>
     * </ul>
     */
    private static void smallWindowWithoutPause() {
        // 小窗打开：游戏没收到 PAUSED，聊天压在它上面
        List<ForegroundState.Ev> evs = new ArrayList<>();
        evs.add(new ForegroundState.Ev(ForegroundState.RESUMED, GAME, t(0)));
        evs.add(new ForegroundState.Ev(ForegroundState.RESUMED, CHAT, t(10)));
        check("小窗打开后前台是聊天", CHAT, ForegroundState.of(evs));

        // 关小窗：聊天退到后台。游戏没有新事件，但它一直在集合里
        List<ForegroundState.Ev> evs2 = new ArrayList<>(evs);
        evs2.add(new ForegroundState.Ev(ForegroundState.PAUSED, CHAT, t(12)));
        check("小窗关闭（聊天 PAUSED）后回到游戏", GAME, ForegroundState.of(evs2));
    }

    /**
     * 时间线到「游戏退到后台」为止：栈顶会露出聊天。
     *
     * <p><b>这是两层分工的体现，不是 bug</b>：状态机只回答「谁压在最上面、
     * 下面还压着谁」，它**没有**「谁还露在屏幕上」这个信息；聊天早就不在屏幕上了，
     * 但它从没收到过退出事件，所以状态机只能把它留在栈里。
     * 真正的取舍在 Recorder 那层：它拿 {@link ForegroundState#altsOf} 交出来的候选
     * 去和系统的「可见应用」对一遍，看不见的就被排除掉（见 Recorder.pickForeground）。
     *
     * <p>所以这条用例断言的是**状态机的契约**：栈顶是聊天、而聊天出现在候选里
     * —— 也就是说，如果没有可见性数据兜底，它确实会被误判成前台。这个边界要钉住。
     */
    private static void closedWindowMustNotLinger() {
        List<ForegroundState.Ev> evs = new ArrayList<>();
        evs.add(new ForegroundState.Ev(ForegroundState.RESUMED, GAME, t(0)));
        evs.add(new ForegroundState.Ev(ForegroundState.RESUMED, CHAT, t(10)));
        evs.add(new ForegroundState.Ev(ForegroundState.RESUMED, GAME, t(12)));  // 关小窗
        evs.add(new ForegroundState.Ev(ForegroundState.PAUSED, GAME, t(14)));   // 游戏退到后台

        List<String> stack = ForegroundState.stackOf(evs);
        checkTrue("栈里仍留着聊天（它从没收到过退出事件）",
                stack.size() == 1 && CHAT.equals(stack.get(0)));

        List<ForegroundState.Ev> noGamePause = new ArrayList<>();
        noGamePause.add(new ForegroundState.Ev(ForegroundState.RESUMED, GAME, t(0)));
        noGamePause.add(new ForegroundState.Ev(ForegroundState.RESUMED, CHAT, t(10)));
        noGamePause.add(new ForegroundState.Ev(ForegroundState.RESUMED, GAME, t(12)));
        checkTrue("栈顶是游戏时，聊天应出现在候选里供可见性筛选",
                ForegroundState.altsOf(noGamePause).contains(CHAT));
    }

    /**
     * 模型边界：关闭小窗时如果**一条事件都没有、而且之后也没有任何事件**，
     * 那么「前台回到了游戏」这件事在事件流里不可见 —— 状态机只能认为最后拿焦点的是前台。
     *
     * <p>这条用例**故意断言当前（不理想的）行为**，把这个边界钉在测试里，
     * 免得以后有人以为它被修好了。真实设备上关闭小窗时聊天一般都会收到 PAUSED，
     * 所以正常路径不会走到这里（见 {@link #smallWindowWithoutPause} 与
     * {@link #closedWindowMustNotLinger}）。
     */
    private static void noEventOnCloseIsModelLimit() {
        List<ForegroundState.Ev> evs = new ArrayList<>();
        evs.add(new ForegroundState.Ev(ForegroundState.RESUMED, GAME, t(0)));
        evs.add(new ForegroundState.Ev(ForegroundState.RESUMED, CHAT, t(10)));
        ForegroundState.Now now = ForegroundState.of(evs);
        checkTrue("无任何后续事件时，只能认为最后拿焦点的是前台（已知边界）",
                CHAT.equals(now.pkg));
    }

    /** 普通切换：A 完全退到后台，B 上来。 */
    private static void normalSwitch() {
        List<ForegroundState.Ev> evs = new ArrayList<>();
        evs.add(new ForegroundState.Ev(ForegroundState.RESUMED, GAME, t(0)));
        evs.add(new ForegroundState.Ev(ForegroundState.PAUSED, GAME, t(5)));
        evs.add(new ForegroundState.Ev(ForegroundState.RESUMED, CHAT, t(5)));
        evs.add(new ForegroundState.Ev(ForegroundState.PAUSED, CHAT, t(9)));
        evs.add(new ForegroundState.Ev(ForegroundState.RESUMED, MAIL, t(9)));
        check("正常切换到最后进入的应用", MAIL, ForegroundState.of(evs));
    }

    /**
     * 应用退到桌面（桌面被 SHELL 滤掉，不进事件流）。
     *
     * <p>此时集合为空，结论必须是「没有前台应用」——**不能**退回「最后一个 RESUMED
     * 的应用」，否则会把「已经退到桌面」误判成「还在那个应用里」，时长继续无脑累加。
     */
    private static void backToHome() {
        List<ForegroundState.Ev> evs = new ArrayList<>();
        evs.add(new ForegroundState.Ev(ForegroundState.RESUMED, GAME, t(0)));
        evs.add(new ForegroundState.Ev(ForegroundState.PAUSED, GAME, t(5)));
        ForegroundState.Now now = ForegroundState.of(evs);
        checkTrue("退回桌面后不应再算作该应用在前台", now.empty());
        checkTrue("退回桌面是确定结论、不是猜的", !now.guess);
    }

    /** 分屏：两个都在前台，最后拿焦点的那个在顶。 */
    private static void splitScreenSwap() {
        List<ForegroundState.Ev> evs = new ArrayList<>();
        evs.add(new ForegroundState.Ev(ForegroundState.RESUMED, GAME, t(0)));
        evs.add(new ForegroundState.Ev(ForegroundState.RESUMED, CHAT, t(3)));
        check("分屏下取最后拿焦点的那个", CHAT, ForegroundState.of(evs));

        // 用户点了游戏那一半
        evs.add(new ForegroundState.Ev(ForegroundState.RESUMED, GAME, t(6)));
        check("分屏下换焦点后跟着换", GAME, ForegroundState.of(evs));

        // 关掉聊天那一半
        evs.add(new ForegroundState.Ev(ForegroundState.PAUSED, CHAT, t(8)));
        check("分屏关掉一半后剩下那个", GAME, ForegroundState.of(evs));
    }

    /**
     * 同一个应用被 RESUMED 两次（多实例 / 重新拿焦点），必须提到最上而不是留在原位。
     *
     * <p>LinkedHashSet 的语义陷阱：不先 remove 再 add 的话，「重新进入前台」不会改变顺序，
     * 顶部会一直是最早进来的那个，小窗场景就修不好了。
     */
    private static void reResumeBumpsToTop() {
        List<ForegroundState.Ev> evs = new ArrayList<>();
        evs.add(new ForegroundState.Ev(ForegroundState.RESUMED, GAME, t(0)));
        evs.add(new ForegroundState.Ev(ForegroundState.RESUMED, CHAT, t(1)));
        evs.add(new ForegroundState.Ev(ForegroundState.RESUMED, MAIL, t(2)));
        evs.add(new ForegroundState.Ev(ForegroundState.RESUMED, GAME, t(3)));
        check("重新 RESUMED 的应用要排到最上", GAME, ForegroundState.of(evs));
    }

    /** 空事件流：让调用方沿用上一次观测，不能凭空断言「没有前台应用」。 */
    private static void emptyStream() {
        ForegroundState.Now now = ForegroundState.of(new ArrayList<ForegroundState.Ev>());
        checkTrue("空事件流应标记为 guess", now.guess);
        checkTrue("空事件流不给包名", now.empty());
    }

    /** 只有 PAUSED、没有 RESUMED：集合为空，且是确定结论。 */
    private static void pausedOnly() {
        List<ForegroundState.Ev> evs = new ArrayList<>();
        evs.add(new ForegroundState.Ev(ForegroundState.PAUSED, GAME, t(1)));
        ForegroundState.Now now = ForegroundState.of(evs);
        checkTrue("只有 PAUSED 时没有前台应用", now.empty());
        checkTrue("只有 PAUSED 是确定结论", !now.guess);
    }

    /** prepend：补在最前面，后来的 RESUMED 才能正常压在它上面。 */
    private static void prependKeepsOrder() {
        List<ForegroundState.Ev> evs = new ArrayList<>();
        evs.add(new ForegroundState.Ev(ForegroundState.RESUMED, CHAT, t(10)));
        List<ForegroundState.Ev> full = ForegroundState.prepend(evs, GAME, t(0));
        check("prepend 之后后来者仍在顶", CHAT, ForegroundState.of(full));

        List<ForegroundState.Ev> onlyGame = ForegroundState.prepend(
                new ArrayList<ForegroundState.Ev>(), GAME, t(0));
        check("只 prepend 一个应用时就是它", GAME, ForegroundState.of(onlyGame));
    }

    /** stackOf 的迭代顺序：最新拿焦点的排最前。 */
    private static void stackOrder() {
        List<ForegroundState.Ev> evs = new ArrayList<>();
        evs.add(new ForegroundState.Ev(ForegroundState.RESUMED, GAME, t(0)));
        evs.add(new ForegroundState.Ev(ForegroundState.RESUMED, CHAT, t(1)));
        evs.add(new ForegroundState.Ev(ForegroundState.RESUMED, MAIL, t(2)));
        List<String> stack = ForegroundState.stackOf(evs);
        checkTrue("栈顶应是最后进来的", stack.size() == 3 && MAIL.equals(stack.get(0)));
        checkTrue("栈底应是最早进来的", GAME.equals(stack.get(2)));
    }

    /**
     * 一条贴近真实的完整时间线：游戏 30 分钟，第 10 分钟小窗聊天 2 分钟，
     * 第 25 分钟切到邮件看一眼再回来，最后退到桌面。
     *
     * <p>逐步断言每个采样时刻的结论 —— 这是最接近「手机上真跑一遍」的一条，
     * 但只需要几毫秒。
     */
    private static void cappedRealisticTimeline() {
        List<ForegroundState.Ev> evs = new ArrayList<>();
        evs.add(new ForegroundState.Ev(ForegroundState.RESUMED, GAME, t(0)));
        evs.add(new ForegroundState.Ev(ForegroundState.PAUSED, GAME, t(10)));
        evs.add(new ForegroundState.Ev(ForegroundState.RESUMED, CHAT, t(10)));
        evs.add(new ForegroundState.Ev(ForegroundState.RESUMED, GAME, t(12)));   // 关小窗
        evs.add(new ForegroundState.Ev(ForegroundState.PAUSED, GAME, t(25)));
        evs.add(new ForegroundState.Ev(ForegroundState.RESUMED, MAIL, t(25)));
        evs.add(new ForegroundState.Ev(ForegroundState.PAUSED, MAIL, t(26)));
        evs.add(new ForegroundState.Ev(ForegroundState.RESUMED, GAME, t(26)));
        evs.add(new ForegroundState.Ev(ForegroundState.PAUSED, GAME, t(30)));    // 退回桌面

        check("时间线@第11分钟（小窗中）", CHAT, ForegroundState.of(upto(evs, t(11))));
        check("时间线@第13分钟（小窗已关）", GAME, ForegroundState.of(upto(evs, t(13))));
        check("时间线@第20分钟（一直在游戏）", GAME, ForegroundState.of(upto(evs, t(20))));
        check("时间线@第25分钟半（在看邮件）", MAIL, ForegroundState.of(upto(evs, t(25) + 1)));
        check("时间线@第28分钟（回到游戏）", GAME, ForegroundState.of(upto(evs, t(28))));
        // 最后一刻游戏退到后台：栈顶会露出聊天（它从没收到过退出事件）。
        // 这个残留由 Recorder 那层的「可见性」筛选负责排除，不是状态机的职责 —— 见
        // closedWindowMustNotLinger 的说明。
        check("时间线@第31分钟（退到后台，栈顶残留由上层筛掉）",
                CHAT, ForegroundState.of(evs));
    }

    // ---------------- 1.3.4：包内活动切换 ----------------

    /**
     * **冷启动一个应用**：启动页 → 主界面，两个活动同属一个包。
     *
     * <p>系统的事件是按活动发的，所以序列里会出现 {@code RESUMED(包)} 紧跟
     * {@code PAUSED(包)}。1.3.4 之前状态机按包名记账、一个包在栈里只有一格，
     * 后到的 PAUSED 会把整个包摘掉 —— 栈一空就报「没有前台应用」。
     * 真实表现：任何应用冷启动后的一两分钟里，通知都显示「等待前台应用」，
     * 等那条 PAUSED 滑出回看窗口才自愈。
     */
    private static void coldStartSamePackage() {
        List<ForegroundState.Ev> evs = new ArrayList<>();
        evs.add(new ForegroundState.Ev(ForegroundState.RESUMED, GAME, GAME + ".Splash", t(0)));
        evs.add(new ForegroundState.Ev(ForegroundState.RESUMED, GAME, GAME + ".Main", t(0)));
        // 启动页随后被pause掉 —— 应用好端端在前台，不该因此「没有前台应用」
        evs.add(new ForegroundState.Ev(ForegroundState.PAUSED, GAME, GAME + ".Splash", t(0)));

        check("冷启动（启动页→主界面）后仍应认得出这个应用", GAME, ForegroundState.of(evs));
        checkTrue("栈里只应剩它一个",
                ForegroundState.stackOf(evs).size() == 1
                        && GAME.equals(ForegroundState.stackOf(evs).get(0)));
    }

    /**
     * 包内跳转：设置主页 → 二级页。主页面pause在后、二级页resume在前，
     * 是最容易踩到的一条顺序。
     */
    private static void intraAppNavigation() {
        List<ForegroundState.Ev> evs = new ArrayList<>();
        evs.add(new ForegroundState.Ev(ForegroundState.RESUMED, MAIL, MAIL + ".List", t(0)));
        evs.add(new ForegroundState.Ev(ForegroundState.RESUMED, MAIL, MAIL + ".Detail", t(1)));
        evs.add(new ForegroundState.Ev(ForegroundState.PAUSED, MAIL, MAIL + ".List", t(1)));

        check("包内跳转后仍应是这个应用", MAIL, ForegroundState.of(evs));

        // 反过来（pause 在前）也必须对
        List<ForegroundState.Ev> rev = new ArrayList<>();
        rev.add(new ForegroundState.Ev(ForegroundState.RESUMED, MAIL, MAIL + ".List", t(0)));
        rev.add(new ForegroundState.Ev(ForegroundState.PAUSED, MAIL, MAIL + ".List", t(1)));
        rev.add(new ForegroundState.Ev(ForegroundState.RESUMED, MAIL, MAIL + ".Detail", t(1)));
        check("包内跳转（pause 在前）同样应认得出", MAIL, ForegroundState.of(rev));
    }

    /**
     * 但**整个应用真的退出去**时必须如实报空 —— 精确到活动不能变成「赖着不走」。
     *
     * <p>这是上一条的另一面：包内切换要保住，同一个包的活动全pause了就得让位。
     */
    private static void samePackageFullyLeft() {
        List<ForegroundState.Ev> evs = new ArrayList<>();
        evs.add(new ForegroundState.Ev(ForegroundState.RESUMED, MAIL, MAIL + ".List", t(0)));
        evs.add(new ForegroundState.Ev(ForegroundState.RESUMED, MAIL, MAIL + ".Detail", t(1)));
        evs.add(new ForegroundState.Ev(ForegroundState.PAUSED, MAIL, MAIL + ".List", t(2)));
        evs.add(new ForegroundState.Ev(ForegroundState.PAUSED, MAIL, MAIL + ".Detail", t(3)));

        checkTrue("同一个包的活动全部退到后台后，栈应当是空的",
                ForegroundState.stackOf(evs).isEmpty());
        checkTrue("这时应当如实报「没有前台应用」", ForegroundState.of(evs).empty());
    }

    /**
     * 冷启动之后紧接着切到别的应用。
     *
     * <p>这里钉的是「精确到活动」的**另一面**：一个包的活动全部pause之后，
     * 它就该彻底退出候选 —— 不能因为改了记账粒度就变成赖着不走。
     * （写这条用例时第一版把期望写反了：以为游戏会留在候选里。它其实是真的退出了。）
     */
    private static void coldStartThenSwitch() {
        List<ForegroundState.Ev> evs = new ArrayList<>();
        evs.add(new ForegroundState.Ev(ForegroundState.RESUMED, GAME, GAME + ".Splash", t(0)));
        evs.add(new ForegroundState.Ev(ForegroundState.RESUMED, GAME, GAME + ".Main", t(0)));
        evs.add(new ForegroundState.Ev(ForegroundState.PAUSED, GAME, GAME + ".Splash", t(0)));
        evs.add(new ForegroundState.Ev(ForegroundState.PAUSED, GAME, GAME + ".Main", t(5)));
        evs.add(new ForegroundState.Ev(ForegroundState.RESUMED, CHAT, CHAT + ".Main", t(5)));

        check("冷启动后切走，前台应是新来的那个", CHAT, ForegroundState.of(evs));
        checkTrue("整个应用退出后不该留在候选里",
                !ForegroundState.altsOf(evs).contains(GAME));
    }

    // ---------------- 工具 ----------------

    /** 第 n 分钟（毫秒）。用固定基准，避免依赖真实时钟 */
    private static long t(int minutes) {
        return 1_700_000_000_000L + minutes * 60_000L;
    }

    /** 截取到某个时刻为止的事件（模拟「采样发生在那一刻」） */
    private static List<ForegroundState.Ev> upto(List<ForegroundState.Ev> all, long atMs) {
        List<ForegroundState.Ev> out = new ArrayList<>();
        for (ForegroundState.Ev e : all) {
            if (e.atMs <= atMs) {
                out.add(e);
            }
        }
        return out;
    }

    private static void check(String what, String expectPkg, ForegroundState.Now now) {
        boolean ok = expectPkg.equals(now.pkg) && !now.guess;
        if (ok) {
            pass++;
        } else {
            fail++;
            System.out.println("FAIL: " + what
                    + "  期望=" + expectPkg
                    + "  实际=" + (now.pkg.isEmpty() ? "(空)" : now.pkg)
                    + (now.guess ? " [guess]" : ""));
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
