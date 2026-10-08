package com.MATO.log.util;

import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;

/**
 * 深色模式的统一判定点。
 *
 * <p><b>为什么不能只靠 values-night</b>：资源限定符只在**系统**进入夜间模式时生效。
 * 而需求要的是应用内的开关 —— 用户选了「始终深色」，可系统还是白天，此时
 * values-night 根本不会被选中。所以必须有一条「强制」路径。
 *
 * <p><b>强制怎么实现</b>：把当前配置里的 UI_MODE_NIGHT 位改成我们要的值，再拿去
 * 造 Resources / Context。Android 的夜间配色本质就是这个位参与资源选择，
 * 改掉它之后 values-night 会被正确选中 —— 也就是说，布局和 drawable 里那些
 * @color 引用一行都不用改，深色是「资源层」自然切换的结果，不是代码里到处 if。
 *
 * <p><b>为什么这套要放在 Context 而不是每个界面里自己判</b>：全工程有 7 个 Activity
 * 加若干弹窗，逐个判迟早漏一处。分三个层次兜住：
 * <ol>
 *   <li>{@link #configFor} 供 Application 用，走 createConfigurationContext，
 *       影响面覆盖几乎所有界面；</li>
 *   <li>{@link #wrap} 供任何 Context 兜底（Application 那条路在个别 ROM 上可能不生效）；</li>
 *   <li>{@link #applyWindow} 负责状态栏/导航栏图标的明暗 —— 这是唯一没法靠
 *       资源切换解决的部分，系统只给「浅底深图标」和「深底浅图标」两种选择，
 *       必须按当前用的是哪套配色显式设置。</li>
 * </ol>
 */
public final class DarkTheme {

    private DarkTheme() {
    }

    /** 当前应该用深色吗 */
    public static boolean isDark(Context c) {
        int mode = Prefs.darkMode(c);
        if (mode == Prefs.DARK_ON) {
            return true;
        }
        if (mode == Prefs.DARK_OFF) {
            return false;
        }
        int night = c.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK;
        return night == Configuration.UI_MODE_NIGHT_YES;
    }

    /**
     * 按用户选择改写配置里的夜间位。
     *
     * <p>「跟随系统」时原样返回，不动配置 —— 此时系统给什么就是什么，
     * 资源限定符本来就会自己选对。
     */
    public static Configuration configFor(Context c) {
        Configuration cfg = new Configuration(c.getResources().getConfiguration());
        int mode = Prefs.darkMode(c);
        if (mode == Prefs.DARK_FOLLOW) {
            return cfg;
        }
        cfg.uiMode = (cfg.uiMode & ~Configuration.UI_MODE_NIGHT_MASK)
                | (mode == Prefs.DARK_ON
                ? Configuration.UI_MODE_NIGHT_YES
                : Configuration.UI_MODE_NIGHT_NO);
        return cfg;
    }

    /** 造一个按用户选择着色的 Context；跟随系统时原样返回 */
    public static Context wrap(Context c) {
        if (c == null || Prefs.darkMode(c) == Prefs.DARK_FOLLOW) {
            return c;
        }
        Configuration cfg = configFor(c);
        int night = cfg.uiMode & Configuration.UI_MODE_NIGHT_MASK;
        int nowNight = c.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK;
        if (night == nowNight) {
            return c;
        }
        return c.createConfigurationContext(cfg);
    }

    /** 造一份按用户选择着色的 Resources；跟随系统时返回原对象 */
    public static Resources resourcesFor(Context c) {
        Context wrapped = wrap(c);
        return wrapped == c ? c.getResources() : wrapped.getResources();
    }

    /**
     * 状态栏 / 导航栏图标明暗。
     *
     * <p>系统只提供两种：APPEARANCE_LIGHT_*（浅底配深图标）和不设（深底配浅图标）。
     * 所以必须按**当前实际用的那套配色**来设，不能看系统是不是夜间模式 ——
     * 否则「应用内选深色、系统是浅色」时会出现白底白字的状态栏。
     */
    public static void applyWindow(android.app.Activity a) {
        if (a == null) {
            return;
        }
        android.view.Window w = a.getWindow();
        if (w == null) {
            return;
        }
        boolean dark = isDark(a);
        int bg = a.getResources().getColor(com.MATO.log.R.color.paper);
        w.setStatusBarColor(bg);
        w.setNavigationBarColor(bg);

        android.view.View decor = w.getDecorView();
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            android.view.WindowInsetsController ctl = w.getInsetsController();
            if (ctl != null) {
                int lightBars = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                        | android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                ctl.setSystemBarsAppearance(dark ? 0 : lightBars, lightBars);
            }
        } else if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            int flags = decor.getSystemUiVisibility();
            int light = android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                light |= android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            }
            decor.setSystemUiVisibility(dark ? (flags & ~light) : (flags | light));
        }
    }
}
