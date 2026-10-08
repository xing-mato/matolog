package com.MATO.log.util;

import android.content.Context;
import android.content.res.Configuration;

import java.util.Locale;

/**
 * 应用内语言（1.3.5 新增）。
 *
 * <p>五种语言：简体中文、繁體中文、한국어、日本語、English，外加「跟随系统」。
 *
 * <p><b>为什么自己包 Context，而不是用系统的按应用语言（API 33+ 的
 * {@code LocaleManager.setApplicationLocales}）</b>：那个 API 只管得了 Android 13 及以上，
 * 而本应用 minSdk 是 24 —— 低版本上还是得自己来。两套并存会出现「13 以上走系统、
 * 以下走自己」的分叉，同一个功能两条代码路径，是这类工程最容易长歪的地方。
 * 统一走 {@link #wrap}，所有版本行为一致。
 *
 * <p>代价说清楚：系统的「应用语言」设置页里不会跟着变（那里显示的仍是系统语言）。
 * 本应用自己的设置页才是唯一入口。
 *
 * <p><b>为什么还要留一个静态的 {@link #locale()}</b>：{@link DateUtil} 那类地方
 * 是纯静态工具、拿不到 Context，而日期的写法（2026年10月8日 / Oct 8, 2026 /
 * 2026년 10월 8일）必须跟着语言走。让它们去读一个静态字段，比给每个格式化方法
 * 都加一个 Context 参数要干净得多。这个字段在 Application 启动时与用户改语言时各刷新一次。
 */
public final class Lang {

    /** 跟随系统 */
    public static final String FOLLOW = "";

    /** 可选项，顺序即设置页里的顺序 */
    public static final String[] TAGS = {"zh-CN", "zh-TW", "ja", "ko", "en"};

    /**
     * 当前生效的语言，给 {@link DateUtil} 这类拿不到 Context 的地方用。
     *
     * <p>初值是系统语言：万一在 Application 起来之前就被读到，至少不会比原来更差。
     */
    private static Locale current = Locale.getDefault();

    private Lang() {
    }

    /** 标签 → Locale；认不出来的一律当「跟随系统」 */
    public static Locale localeOf(String tag) {
        if (tag == null || tag.isEmpty()) {
            return systemLocale();
        }
        if ("zh-CN".equals(tag)) {
            return Locale.SIMPLIFIED_CHINESE;
        }
        if ("zh-TW".equals(tag)) {
            return Locale.TRADITIONAL_CHINESE;
        }
        if ("ja".equals(tag)) {
            return Locale.JAPANESE;
        }
        if ("ko".equals(tag)) {
            return Locale.KOREAN;
        }
        if ("en".equals(tag)) {
            return Locale.ENGLISH;
        }
        return systemLocale();
    }

    private static Locale systemLocale() {
        Locale l = Locale.getDefault();
        return l == null ? Locale.SIMPLIFIED_CHINESE : l;
    }

    public static Locale locale() {
        return current;
    }

    /**
     * 把当前语言写进配置，交给界面/服务在 {@code attachBaseContext} 里用。
     *
     * <p>跟随系统时**原样返回**：让系统按它自己的配置去挑资源，
     * 免得我们算出来的默认值和系统的不一致（比如系统是 zh-Hant-HK）。
     */
    public static Context wrap(Context base) {
        if (base == null) {
            return null;
        }
        String tag = Prefs.langTag(base);
        if (tag == null || tag.isEmpty()) {
            return base;
        }
        Configuration cfg = new Configuration(base.getResources().getConfiguration());
        cfg.setLocale(localeOf(tag));
        return base.createConfigurationContext(cfg);
    }

    /**
     * 刷新静态语言值。在 Application 启动时与用户改语言时各调一次。
     *
     * <p>必须在 {@code super.onCreate()} 之后、且在任何带日期的界面之前调用 ——
     * 否则第一帧的日期还是上一语言写的。
     */
    public static void refresh(Context c) {
        if (c == null) {
            return;
        }
        current = localeOf(Prefs.langTag(c));
    }

    /** 设置页里那一行右边显示的当前值 */
    public static String label(Context c) {
        String tag = Prefs.langTag(c);
        if (tag == null || tag.isEmpty()) {
            return c.getString(com.MATO.log.R.string.lang_follow);
        }
        switch (tag) {
            case "zh-CN":
                return c.getString(com.MATO.log.R.string.lang_zh_cn);
            case "zh-TW":
                return c.getString(com.MATO.log.R.string.lang_zh_tw);
            case "ja":
                return c.getString(com.MATO.log.R.string.lang_ja);
            case "ko":
                return c.getString(com.MATO.log.R.string.lang_ko);
            case "en":
                return c.getString(com.MATO.log.R.string.lang_en);
            default:
                return c.getString(com.MATO.log.R.string.lang_follow);
        }
    }
}
