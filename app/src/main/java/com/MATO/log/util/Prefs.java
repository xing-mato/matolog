package com.MATO.log.util;

import android.content.Context;
import android.content.SharedPreferences;

import com.MATO.log.R;

/**
 * 应用偏好。
 *
 * <p>工程原先没有任何偏好层 —— 全仓唯一一处 SharedPreferences 藏在
 * {@link SecurityHelper} 内部且是 private，只存口令相关的东西。设置页要有落脚点，
 * 所以这里建一个统一的入口，把键名集中在一处，免得各处硬编码字符串拼错。
 *
 * <p>用另一个文件名（{@code matolog_settings}）而不是复用 {@code matolog_security}：
 * 口令那一路是安全层，和界面偏好混在一个文件里，将来做导出/清理时要多一层顾忌。
 *
 * <p>读写都很频繁（记录服务每分钟都要问一次「现在要不要记」），所以直接用
 * SharedPreferences 自带的内存缓存，不再加一层包装。
 */
public final class Prefs {

    private static final String FILE = "matolog_settings";

    // ---------------- 外观 ----------------

    /** 深色模式，取值见 {@link #DARK_FOLLOW} 等 */
    public static final String DARK_MODE = "dark_mode";

    /** 跟随系统 */
    public static final int DARK_FOLLOW = 0;
    /** 始终深色 */
    public static final int DARK_ON = 1;
    /** 始终浅色 */
    public static final int DARK_OFF = 2;

    // ---------------- 自动记录 ----------------

    public static final String AUTO_RECORD = "auto_record";
    public static final String SAMPLE_MS = "sample_ms";
    public static final String MIN_KEEP_MS = "min_keep_ms";
    public static final String KEEP_ALIVE = "keep_alive";

    // 遗留键：rec_paused。1.3.4 把通知里的「暂停 / 继续」并成了「关闭」，
    // 这个布尔再也没有读取方 —— 库里可能还留着 true，但它不影响任何行为：
    // 「记不记」现在只由 AUTO_RECORD 这一个总开关表达。
    // （不去清它：清了没有收益，而多一次写入就多一次出错的机会。）

    // ---------------- 一次性迁移 ----------------

    /**
     * 1.3.1：清「只记这些」白名单这件事做过了没有。
     *
     * <p>迁移本身幂等，这个标记只是省掉每次冷启动的一次开库 + DELETE。
     * 放在这里而不是 {@code SecurityHelper} 那类口令库里：它是应用自己的状态，
     * 与口令无关。
     */
    public static final String DONE_DROP_ONLY_RULES = "done_drop_only_rules";

    /**
     * 1.3.4：采样间隔的默认值从 60 秒改成 5 秒，这件事做过了没有。
     *
     * <p>按「存着的值就是那个旧默认值 → 说明用户没自己挑过 → 跟着新默认走」处理。
     * 用户自己填过的其它值一律不动。用标记挡住重复执行，否则他哪天特意调回 60 秒，
     * 下次冷启动又会被改掉。
     */
    public static final String DONE_FAST_SAMPLE = "done_fast_sample";

    public static boolean doneDropOnlyRules(Context c) {
        return sp(c).getBoolean(DONE_DROP_ONLY_RULES, false);
    }

    public static void setDoneDropOnlyRules(Context c, boolean v) {
        sp(c).edit().putBoolean(DONE_DROP_ONLY_RULES, v).apply();
    }

    public static boolean doneFastSample(Context c) {
        return sp(c).getBoolean(DONE_FAST_SAMPLE, false);
    }

    public static void setDoneFastSample(Context c, boolean v) {
        sp(c).edit().putBoolean(DONE_FAST_SAMPLE, v).apply();
    }

    /**
     * 采样间隔与「短于多久不计」的允许范围（毫秒）。
     *
     * <p>1.3.1 起由用户自己填分钟数，上下限在这里统一夹住 —— 界面只负责取到一个整数，
     * 判定集中在一处，免得两边的边界慢慢长歪。
     *
     * <p><b>为什么下限是 5 秒而不是 1 分钟</b>：1.3.4 之前是 1 分钟，理由是「每次采样都要查
     * 一次 UsageStats，耗电与收益不成比例」。实测下来这个估计太保守了，代价被高估：
     * 屏幕关着时采样在问系统之前就返回了（见 {@code Recorder.sampleOnce}），
     * 所以密采**只在亮屏时**发生；而且它换来两样都实打实的东西 ——
     * 常驻通知能跟上应用切换（原来最多滞后一个采样周期），
     * 分段边界也从「晚几十秒」变成「晚几秒」（当年指望无障碍服务换的就是这个精度）。
     *
     * <p>真正费的那一次是 API 29+ 的可见性查询（要聚合一天的统计），
     * 那一条单独做了短缓存（见 {@code UsageReader.visiblePackages}）。
     *
     * <p><b>为什么「短于多久不计」的下限是 0</b>：0 = 全部都记（1.3 就有的语义）。
     * 它不是「一个很小的阈值」，而是「不设阈值」，所以夹取时要放行。
     */
    public static final long SAMPLE_MIN = 5_000L;
    /** 采样间隔默认值：5 秒。通知要跟得上应用切换，几秒是「看起来实时」的量级 */
    public static final long SAMPLE_DEFAULT = 5_000L;
    public static final long SAMPLE_MAX = 60 * 60_000L;
    /** 「短于多久不计」的上限：24 小时。再长的阈值等于什么都不记，没有意义 */
    public static final long MIN_KEEP_MAX = 24 * 60 * 60_000L;

    private Prefs() {
    }

    public static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    // ---------------- 外观 ----------------

    /**
     * 应用内语言。**空串 = 跟随系统**（默认）。
     *
     * <p>取值见 {@link Lang#TAGS}。这里存的是标签而不是序号：
     * 序号一旦增删语言就会错位，而标签自解释、翻偏好文件时也看得懂。
     */
    public static final String LANG = "lang";

    public static String langTag(Context c) {
        String v = sp(c).getString(LANG, Lang.FOLLOW);
        return v == null ? Lang.FOLLOW : v;
    }

    public static void setLangTag(Context c, String tag) {
        sp(c).edit().putString(LANG, tag == null ? Lang.FOLLOW : tag).apply();
    }

    public static int darkMode(Context c) {
        return sp(c).getInt(DARK_MODE, DARK_FOLLOW);
    }

    public static void setDarkMode(Context c, int v) {
        sp(c).edit().putInt(DARK_MODE, v).apply();
    }

    /** 深色档位的文案，设置页与状态提示共用 */
    public static String darkModeLabel(Context c, int mode) {
        switch (mode) {
            case DARK_ON:
                return c.getString(R.string.dark_on);
            case DARK_OFF:
                return c.getString(R.string.dark_off);
            case DARK_FOLLOW:
            default:
                return c.getString(R.string.dark_follow);
        }
    }

    // ---------------- 自动记录 ----------------

    /** 自动记录总开关。默认关：这是新功能，不该在用户没同意前就常驻后台。 */
    public static boolean autoRecord(Context c) {
        return sp(c).getBoolean(AUTO_RECORD, false);
    }

    public static void setAutoRecord(Context c, boolean v) {
        sp(c).edit().putBoolean(AUTO_RECORD, v).apply();
    }

    /** 采样间隔，默认 {@link #SAMPLE_DEFAULT}（5 秒）。 */
    public static long sampleMs(Context c) {
        long v = sp(c).getLong(SAMPLE_MS, SAMPLE_DEFAULT);
        if (v < SAMPLE_MIN) {
            return SAMPLE_MIN;
        }
        return v > SAMPLE_MAX ? SAMPLE_MAX : v;
    }

    public static void setSampleMs(Context c, long v) {
        if (v < SAMPLE_MIN) {
            v = SAMPLE_MIN;
        }
        if (v > SAMPLE_MAX) {
            v = SAMPLE_MAX;
        }
        sp(c).edit().putLong(SAMPLE_MS, v).apply();
    }

    /** 短于这个时长的不计。默认 5 分钟（需求原文）。0 = 全部都记，不设阈值。 */
    public static long minKeepMs(Context c) {
        long v = sp(c).getLong(MIN_KEEP_MS, 5 * 60_000L);
        if (v < 0) {
            return 0;
        }
        return v > MIN_KEEP_MAX ? MIN_KEEP_MAX : v;
    }

    public static void setMinKeepMs(Context c, long v) {
        if (v < 0) {
            v = 0;
        }
        if (v > MIN_KEEP_MAX) {
            v = MIN_KEEP_MAX;
        }
        sp(c).edit().putLong(MIN_KEEP_MS, v).apply();
    }

    public static boolean keepAlive(Context c) {
        return sp(c).getBoolean(KEEP_ALIVE, true);
    }

    public static void setKeepAlive(Context c, boolean v) {
        sp(c).edit().putBoolean(KEEP_ALIVE, v).apply();
    }

    // ---------------- 自启动（用户自报） ----------------

    /**
     * 用户是否已经把自启动开好了。
     *
     * <p><b>为什么是「用户自报」而不是应用读出来的</b>：自启动是各家 ROM 自己加的东西，
     * 不在 AOSP 里，也没有任何 API —— 真机上实测过，`cmd appops get` 里根本看不到这一项
     * （vivo 把状态存在自己的权限管理器里）。所以应用**读不到**，只能问用户，
     * 或者干脆装作不知道。装作不知道的代价是：用户明明去系统里开好了，
     * 回到权限页看到的还是「去开启」，会以为没生效、反复去点。
     *
     * <p>所以这里存一个由用户确认的状态位：点一次那一行 = 打开系统页面并标记为已设置，
     * 再点一次 = 取消标记（换机器、关了自启动时用得上）。
     * <b>它不代表系统里的真实状态</b>，界面上要把这一点说清楚，不能让人以为应用验过了。
     */
    public static final String AUTOSTART_SET = "autostart_set";

    public static boolean autoStartSet(Context c) {
        return sp(c).getBoolean(AUTOSTART_SET, false);
    }

    public static void setAutoStartSet(Context c, boolean v) {
        sp(c).edit().putBoolean(AUTOSTART_SET, v).apply();
    }

    // ---------------- 自动整理（LLM）已整块移除 ----------------
    //
    // 1.3.5 按需求删掉了「自动整理」，这里原本住着两批东西：
    //
    //   常量：AI_ON / AI_CONTEXT / AI_TIMEOUT_MS / AI_BASE / AI_KEY /
    //         AI_MODEL / AI_ACTIVE / PROVIDER_CUSTOM
    //   访问器：aiOn / aiActiveId / aiSlot* / aiBase / aiKey / aiModel /
    //           aiContext / aiTimeoutMs / aiConfigured / aiKeyDegraded /
    //           aiLogContent / aiModels / aiModelsOf
    //
    // 一并删掉，而不是留着当死代码：没人读的键留在偏好文件里，只会让下一个
    // 翻它的人以为功能还在 —— 这个工程已经在「界面显示的和实际发生的不一致」
    // 上栽过三次（缺陷 28/29/30），不该再留一处。
    //
    // 老机器上这些键**不会被清掉**（已经没有读取方，也不值得为此去开一次偏好）。
    // 翻到 ai_on=true 之类，当没看见即可 —— 它不再影响任何行为。
    //
    // 另：util/SafeStore 是给这些 Key 做加密落盘的，眼下也失去了全部调用方。
    // 它**留着不删** —— 理由与 SecurityHelper 里那套口令方法相同（见 10.6）：
    // 删掉它没有任何收益，却会把「以后重新接回模型」这条路堵死。
}
