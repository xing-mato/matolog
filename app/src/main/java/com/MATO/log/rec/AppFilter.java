package com.MATO.log.rec;

import android.content.Context;

import com.MATO.log.R;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * 判断一个应用要不要被记录。**全工程关于「记不记」的规则只有这一处**。
 *
 * <p>不碰 Android API，规则本身可以离线跑（{@code tools/RuleCheck.java}）。
 *
 * <p>规则一共三条，从上到下先命中先算：
 * <ol>
 *   <li>应用自己的包名 —— 永远不记录，否则会自己记录自己，滚雪球</li>
 *   <li>系统界面（{@link #SHELL}）—— 永远不记录。桌面、状态栏、权限弹窗、安装器
 *       会频繁拿到前台焦点，但它们不是「用户在用它做事」</li>
 *   <li>排除名单 —— 命中即不记录</li>
 *   <li>其余一律记录</li>
 * </ol>
 *
 * <p><b>这里曾经还有第 5 条「系统应用默认不记」，1.3.3 删掉了。</b>它是个没有出口的
 * 硬编码默认值：名单页里那些系统应用显示为「未排除」，用户以为会记，实际上一直被它拦着 ——
 * 界面上看不见、也改不掉的规则。它的判定还与界面互相矛盾（行上写「未排除」，
 * 通知栏却报「系统应用，默认不记」）。
 *
 * <p>删掉之后规则与界面终于对得上：**名单页里没被排除的，就是会被记录的**，
 * 一个开关（排除）说清全部。要在名单页之外再判断一次「是不是系统应用」，
 * 这件事本身就不该存在 —— 记录范围是用户的选择，不是包管理器给的类型标签决定的。
 *
 * <p>{@link #SHELL} 原先藏在 {@code UsageReader} 里（采样层先滤一遍）。现在收拢到这里，
 * 采样层通过 {@code UsageReader.isShell} 复用它：**规则只有一个出处**。另外它现在会
 * 把本机真正的桌面包名并进来（见 {@link #addDeviceShell}）—— 写死的那几个是 AOSP 的包名，
 * 厂商换了自己的实现就漏网，那样「回桌面」会被记成一条应用使用。
 */
public final class AppFilter {

    /**
     * 明确「不算用户行为」的界面壳子（AOSP 包名）。
     *
     * <p>注意：系统「设置」**不在此列** —— 用户确实会在设置里停留很久，那算一件事。
     *
     * <p>这份表只是兜底，真正靠得住的是 {@link #addDeviceShell} 解析出来的
     * 本机桌面包名。加条目要谨慎：进了这张表就再也点不亮、也不出现在名单页里。
     */
    private static final Set<String> SHELL = new HashSet<>(Arrays.asList(
            "com.android.systemui",
            "android",
            "com.android.launcher",
            "com.android.launcher3",
            "com.google.android.apps.nexuslauncher",
            "com.android.permissioncontroller",
            "com.google.android.permissioncontroller",
            "com.android.packageinstaller",
            "com.google.android.packageinstaller",
            "com.android.providers.settings",
            "com.android.keychain",
            "com.android.server.telecom",
            "com.android.incallui",
            "com.android.internal.systemui.navbar.gestural"
    ));

    /**
     * 本机解析出来的界面壳子（桌面），进程启动时灌一次。
     *
     * <p>要动态解析而不能写死：桌面是厂商换得最勤的一个包名（这台 vivo Z1 上是
     * {@code com.bbk.launcher2}，上面那张表里一个都不匹配）。漏掉的后果很具体 ——
     * 用户按 Home 回到桌面，那条「桌面」会被当成一次应用使用记进时间轴。
     *
     * <p><b>但灌进来的必须只有「当前默认桌面」这一个包。</b>误伤的代价比漏掉大得多：
     * 进了这张表的包既不会被记录、也不出现在应用名单里，而用户看不到任何原因。
     * 踩过一次 —— 系统设置里的 {@code com.android.settings.FallbackHome}（开机兜底首页）
     * 也声明了 CATEGORY_HOME，照「所有匹配 HOME 的包」收，就把整个「设置」废掉了。
     * 解析方见 {@code util.App.resolveShellPackages}。
     */
    private static final Set<String> DEVICE_SHELL = new HashSet<>();

    /** 由 {@code util.App} 在进程启动时调用，传入本机所有桌面应用的包名 */
    public static synchronized void addDeviceShell(String... pkgs) {
        if (pkgs == null) {
            return;
        }
        for (String p : pkgs) {
            if (p != null && !p.isEmpty()) {
                DEVICE_SHELL.add(p);
            }
        }
    }

    /** 是不是「界面壳子」型的包名。硬性不记，也不出现在名单页里 */
    public static synchronized boolean isShell(String pkg) {
        return pkg != null && (SHELL.contains(pkg) || DEVICE_SHELL.contains(pkg));
    }

    private final Set<String> excluded = new HashSet<>();
    private final Set<String> selfPkgs = new HashSet<>();

    public AppFilter self(String... pkgs) {
        selfPkgs.clear();
        if (pkgs != null) {
            for (String p : pkgs) {
                if (p != null && !p.isEmpty()) {
                    selfPkgs.add(p);
                }
            }
        }
        return this;
    }

    /** 排除名单：这些不记 */
    public AppFilter excluded(Set<String> pkgs) {
        excluded.clear();
        if (pkgs != null) {
            excluded.addAll(pkgs);
        }
        return this;
    }

    public boolean isSelf(String pkg) {
        return pkg != null && selfPkgs.contains(pkg);
    }

    public boolean isExcluded(String pkg) {
        return pkg != null && excluded.contains(pkg);
    }

    /** 这个包现在会不会被记录 */
    public boolean allow(String pkg) {
        if (pkg == null || pkg.isEmpty()) {
            return false;
        }
        if (selfPkgs.contains(pkg)) {
            return false;
        }
        if (isShell(pkg)) {
            return false;
        }
        return !excluded.contains(pkg);
    }

    /**
     * 「为什么不记」的原因码。
     *
     * <p><b>为什么返回码而不是文案</b>：文案要跟应用内语言走，而这一类判定是纯逻辑、
     * 不该碰 Android —— {@code tools/RuleCheck} 正是靠这一点保持「连 android.jar 都不需要」。
     * 1.3.5 加多语言时先把文案塞进了这里，用例当场编译不过，
     * 于是退回正确的分层：**判定给码，界面给字**。
     */
    public static final int WHY_NO_FOREGROUND = 0;
    public static final int WHY_SELF = 1;
    public static final int WHY_SHELL = 2;
    public static final int WHY_EXCLUDED = 3;
    public static final int WHY_OK = 4;

    /**
     * 这个包现在为什么不记。分支顺序必须与 {@link #allow} 一致，
     * 否则会出现「说明说会记、实际不记」——那正是 1.3.4 要修掉的那类问题。
     */
    public int why(String pkg) {
        if (pkg == null || pkg.isEmpty()) {
            return WHY_NO_FOREGROUND;
        }
        if (selfPkgs.contains(pkg)) {
            return WHY_SELF;
        }
        if (isShell(pkg)) {
            return WHY_SHELL;
        }
        if (excluded.contains(pkg)) {
            return WHY_EXCLUDED;
        }
        return WHY_OK;
    }

    /** 给界面用：把原因码翻成当前语言下的一句话 */
    public String explain(Context c, String pkg) {
        switch (why(pkg)) {
            case WHY_NO_FOREGROUND:
                return c.getString(R.string.rec_note_no_foreground);
            case WHY_SELF:
                return c.getString(R.string.rec_note_self);
            case WHY_SHELL:
                return c.getString(R.string.rec_note_shell);
            case WHY_EXCLUDED:
                return c.getString(R.string.rec_note_excluded);
            default:
                return c.getString(R.string.rec_note_recording);
        }
    }
}
