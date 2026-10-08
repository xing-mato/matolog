package com.MATO.log.ui;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.MATO.log.R;
import com.MATO.log.data.Event;
import com.MATO.log.data.Session;
import com.MATO.log.util.DateUtil;

import java.util.List;

/** 各跨度界面的公共构造：跨度常量、层级关系、以及视图小工具 */
public final class Views {

    /*
     * 跨度层级：日 / 月 / 年 三级。
     *
     * 1.3.5 删掉了「周」这一层（需求：主页不要周）。三级的层级关系是
     * 年 → 月 → 日，与原来的 年 → 月 → 周 → 日 相比只是少了一跳。
     *
     * 注意这三个常量是**层级排序**，不是历史编号：删掉「周」之后
     * 月 / 年 的数值跟着前移（2/3 → 1/2）。它们不落任何持久化存储
     * （OnyxHost 不写 savedInstanceState），所以重排是安全的。
     */
    public static final int LEVEL_DAY = 0;
    public static final int LEVEL_MONTH = 1;
    public static final int LEVEL_YEAR = 2;

    private Views() {
    }

    /** 返回「日」＝0 … 「年」＝2，方便做层级比较 */
    public static int depthOf(int level) {
        switch (level) {
            case LEVEL_DAY:
                return 0;
            case LEVEL_MONTH:
                return 1;
            default:
                return 2;
        }
    }

    public static boolean canPush(int level) {
        return level != LEVEL_DAY;
    }

    /** 某层级往下推一层后的新层级；推到「日」为止 */
    public static int childLevelOf(int level) {
        switch (level) {
            case LEVEL_YEAR:
                return LEVEL_MONTH;
            case LEVEL_MONTH:
            default:
                return LEVEL_DAY;
        }
    }

    public static long step(int level, long anchor, int direction) {
        switch (level) {
            case LEVEL_DAY:
                return DateUtil.startOfDay(DateUtil.addDays(anchor, direction));
            case LEVEL_YEAR:
                return DateUtil.startOfYear(DateUtil.addYears(anchor, direction));
            case LEVEL_MONTH:
            default:
                return DateUtil.startOfMonth(DateUtil.addMonths(anchor, direction));
        }
    }

    /** 标题栏文字 */
    public static String title(int level, long anchor) {
        switch (level) {
            case LEVEL_DAY:
                return DateUtil.fullDay(anchor);
            case LEVEL_YEAR:
                return DateUtil.y(anchor);
            case LEVEL_MONTH:
            default:
                return DateUtil.ym(anchor);
        }
    }

    public static int emptyTextOf(int level) {
        switch (level) {
            case LEVEL_DAY:
                return R.string.empty_day;
            case LEVEL_YEAR:
                return R.string.empty_year;
            case LEVEL_MONTH:
            default:
                return R.string.empty_month;
        }
    }

    // ---------------- 视图小工具 ----------------

    public static LayoutInflater inflater(Context ctx) {
        return LayoutInflater.from(ctx);
    }

    public static LinearLayout column(Context ctx) {
        LinearLayout l = new LinearLayout(ctx);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    public static LinearLayout row(Context ctx) {
        LinearLayout l = new LinearLayout(ctx);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    public static LinearLayout.LayoutParams llp(int w, int h) {
        return new LinearLayout.LayoutParams(w, h);
    }

    public static LinearLayout.LayoutParams llp(int w, int h, float weight) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.weight = weight;
        return p;
    }

    public static int dp(Context ctx, float v) {
        return (int) (ctx.getResources().getDisplayMetrics().density * v + 0.5f);
    }

    public static TextView text(Context ctx, int styleRes, int textRes) {
        TextView t = new TextView(ctx);
        t.setTextAppearance(ctx, styleRes);
        if (textRes != 0) {
            t.setText(textRes);
        }
        return t;
    }

    public static TextView label(Context ctx, int styleRes, String value) {
        TextView t = new TextView(ctx);
        t.setTextAppearance(ctx, styleRes);
        if (value != null) {
            t.setText(value);
        }
        return t;
    }

    public static int color(Context ctx, int colorRes) {
        return ctx.getResources().getColor(colorRes, ctx.getTheme());
    }

    public static Drawable drawable(Context ctx, int res) {
        return ctx.getResources().getDrawable(res, ctx.getTheme());
    }

    /** 把子视图挂到父视图上；若它还挂在别处，先摘下来 */
    public static void addChild(ViewGroup parent, View child) {
        if (parent == null || child == null) {
            return;
        }
        android.view.ViewParent p = child.getParent();
        if (p == parent) {
            parent.removeView(child);
        } else if (p instanceof ViewGroup) {
            ((ViewGroup) p).removeView(child);
        }
        parent.addView(child);
    }

    /** 卡片容器（白底 + 1px 描边 + 3dp 圆角），子项是行 */
    public static LinearLayout card(Context ctx) {
        LinearLayout l = column(ctx);
        l.setBackgroundResource(R.drawable.bg_card);
        return l;
    }

    // ---------------- 顶部信息条 ----------------
    // 日 / 周 / 月 / 年 四个跨度统一用这一条：
    // 左侧上下两行（上行小字是所在的年，下行大字是当前范围），右侧是这一范围的记录条数，
    // 两侧是「上一段 / 下一段」。样式参照系统日期选择器的标题区。

    public static final int BANNER_PAD_H = 12;
    public static final int BANNER_PAD_V = 13;

    public interface OnStep {
        void onStep(int direction);
    }

    /**
     * @param contextLine 上行小字，例如「2026年」或「年视图」
     * @param mainLine    下行大字，例如「9月20日 周日」
     * @param countText   右侧计数，例如「8 条」
     */
    public static LinearLayout pageBanner(Context ctx, String contextLine, String mainLine,
                                          String countText, final OnStep step) {
        LinearLayout box = column(ctx);
        box.setBackgroundColor(color(ctx, R.color.primary));
        LinearLayout.LayoutParams boxLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        boxLp.leftMargin = -dp(ctx, BANNER_PAD_H);
        boxLp.rightMargin = -dp(ctx, BANNER_PAD_H);
        boxLp.topMargin = dp(ctx, 4);
        boxLp.bottomMargin = dp(ctx, 14);
        box.setLayoutParams(boxLp);

        LinearLayout row = row(ctx);
        row.setPadding(dp(ctx, BANNER_PAD_H), dp(ctx, BANNER_PAD_V),
                dp(ctx, BANNER_PAD_H), dp(ctx, BANNER_PAD_V));

        // 左箭头
        row.addView(chevron(ctx, R.drawable.ic_chevron_left_white, R.string.nav_prev, -1, step));

        // 文字区
        LinearLayout texts = column(ctx);
        texts.setPadding(dp(ctx, 4), 0, dp(ctx, 4), 0);

        if (contextLine != null && contextLine.length() > 0) {
            TextView ctxLine = label(ctx, R.style.Text_Banner_Context, contextLine);
            ctxLine.setSingleLine(true);
            texts.addView(ctxLine);
        }
        TextView main = label(ctx, R.style.Text_Banner_Main, mainLine);
        main.setSingleLine(true);
        main.setEllipsize(android.text.TextUtils.TruncateAt.END);
        if (contextLine != null && contextLine.length() > 0) {
            LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            mp.topMargin = dp(ctx, 3);
            main.setLayoutParams(mp);
        }
        texts.addView(main);
        row.addView(texts, llp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        // 右侧计数
        if (countText != null && countText.length() > 0) {
            TextView count = label(ctx, R.style.Text_Banner_Count, countText);
            count.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
            count.setSingleLine(true);
            row.addView(count);
        }

        // 右箭头
        row.addView(chevron(ctx, R.drawable.ic_chevron_right_white, R.string.nav_next, 1, step));

        box.addView(row);
        return box;
    }

    private static ImageButton chevron(Context ctx, int iconRes, int descRes, final int direction,
                                       final OnStep step) {
        int size = dp(ctx, 36);
        ImageButton b = new ImageButton(ctx);
        b.setImageResource(iconRes);
        b.setBackgroundResource(R.drawable.ripple_banner_round);
        b.setContentDescription(ctx.getString(descRes));
        b.setScaleType(android.widget.ImageView.ScaleType.CENTER_INSIDE);
        b.setPadding(dp(ctx, 7), dp(ctx, 7), dp(ctx, 7), dp(ctx, 7));
        b.setMinimumWidth(size);
        b.setMinimumHeight(size);
        if (step != null) {
            b.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    step.onStep(direction);
                }
            });
        }
        return b;
    }

    /** 行之间的 1px 分隔线，左缩进 16dp */
    public static View rowDivider(Context ctx) {
        return rowDivider(ctx, 16);
    }

    /** 行之间的 1px 分隔线，可指定左缩进 */
    public static View rowDivider(Context ctx, int leftMarginDp) {
        View v = new View(ctx);
        v.setBackgroundColor(color(ctx, R.color.divider_soft));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(ctx, 1) / 2));
        p.leftMargin = dp(ctx, leftMarginDp);
        v.setLayoutParams(p);
        return v;
    }

    /**
     * 列头：左边一行小字，右边一个计数。
     *
     * <p>上方留 16dp：设置类页面的结构是「列头 → 卡片 → 说明 → 列头 → 卡片」，
     * 不留这个间距的话，下一段的列头会贴着上一段的说明文字，读起来是糊在一起的一团。
     */
    public static LinearLayout sectionHeader(Context ctx, String title, String count) {
        LinearLayout r = row(ctx);
        r.setPadding(dp(ctx, 2), dp(ctx, 2), dp(ctx, 2), dp(ctx, 8));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(ctx, 16);
        r.setLayoutParams(lp);

        TextView t = label(ctx, R.style.Text_Section, title);
        r.addView(t, llp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView c = label(ctx, R.style.Text_Faint, count);
        r.addView(c, llp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return r;
    }

    /**
     * 事件行：上排是时刻 + 描述，右侧带附件时挂一个回形针和数量。
     *
     * <p>1.3 起自动记录与手动记录**混在同一条时间轴上**（需求定下的呈现方式），
     * 所以这一行同时要能表达两种东西：
     * <ul>
     *   <li>手动记录：一个时刻，例如「09:12」；</li>
     *   <li>自动记录：一段区间 + 时长，例如「09:12 – 09:47 · 35m」，
     *       并在后面挂一个「自动 / 屏幕」小标，让来源一眼可辨。</li>
     * </ul>
     * 没有另开一套列表样式：混排的价值就在于「一天里发生了什么」能一眼看全，
     * 分成两种长相反而要用户自己去对照。
     */
    public static View eventRow(Context ctx, Event e, int level, boolean showTime) {
        View v = inflater(ctx).inflate(R.layout.item_event, null, false);
        TextView time = v.findViewById(R.id.event_time);
        TextView body = v.findViewById(R.id.event_text);
        TextView tag = v.findViewById(R.id.event_source);

        if (!showTime) {
            time.setVisibility(View.GONE);
        } else if (e.isAuto() && e.durationMs() > 0) {
            time.setText(DateUtil.span(e.time, e.endMs)
                    + " · " + DateUtil.durationShort(e.durationMs()));
        } else {
            time.setText(DateUtil.hhmm(e.time));
        }

        // 「自动 / 屏幕」小标。手动记录不挂标 —— 大多数条目都是手动的，
        // 给默认情况加标记只会让每条都多一块东西，反而看不清哪些是自动来的。
        if (tag != null) {
            if (e.source == Session.SRC_SCREEN) {
                tag.setVisibility(View.VISIBLE);
                tag.setText(R.string.source_screen);
            } else if (e.source == Session.SRC_AUTO) {
                tag.setVisibility(View.VISIBLE);
                tag.setText(R.string.source_auto);
            } else {
                tag.setVisibility(View.GONE);
            }
        }

        body.setText(e.displayTitle());

        View box = v.findViewById(R.id.event_attach);
        TextView count = v.findViewById(R.id.event_attach_count);
        if (e.attachmentCount > 0) {
            box.setVisibility(View.VISIBLE);
            count.setText(e.attachmentCount > 1 ? String.valueOf(e.attachmentCount) : "");
        } else {
            box.setVisibility(View.GONE);
        }
        return v;
    }

    /**
     * 可点击的一格：左侧主字 + 可选副字，右侧计数。
     * 点它由宿主换算成 onTap。
     */
    public static LinearLayout tappableRow(Context ctx, String lead, String sub,
                                          String countText, long dayMillis,
                                          boolean highlight) {
        LinearLayout r = row(ctx);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setBackgroundResource(R.drawable.bg_week_row);
        r.setPadding(dp(ctx, 16), dp(ctx, 13), dp(ctx, 16), dp(ctx, 13));
        r.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        r.setTag(R.id.tag_day, Long.valueOf(dayMillis));
        r.setSelected(highlight);

        LinearLayout l = column(ctx);
        TextView leadView = label(ctx, R.style.Text_Body, lead);
        leadView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        l.addView(leadView);

        if (sub != null && sub.length() > 0) {
            TextView subView = label(ctx, R.style.Text_Faint, sub);
            LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            sp.topMargin = dp(ctx, 2);
            subView.setLayoutParams(sp);
            l.addView(subView);
        }
        r.addView(l, llp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView c = label(ctx, R.style.Text_Faint, countText);
        c.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        c.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        c.setMinWidth(dp(ctx, 26));
        r.addView(c);
        return r;
    }

    /** 计数展示：0 → 「—」 */
    public static String count(int n) {
        return n <= 0 ? "—" : String.valueOf(n);
    }
}
