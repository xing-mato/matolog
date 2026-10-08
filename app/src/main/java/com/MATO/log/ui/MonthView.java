package com.MATO.log.ui;

import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.MATO.log.R;
import com.MATO.log.data.DbHelper;
import com.MATO.log.util.DateUtil;

/**
 * 月跨度：一整个月的日历。
 * 有记录的日期下方有一个小点，点任意一天进入二级界面（以天为跨度）。
 *
 * <p>1.3.5 之前这里推下去是「周」（月 → 周 → 日），删掉周这一层之后
 * 直接落到「日」。{@code onTap} 不用改 —— 推哪一层由
 * {@link Views#childLevelOf} 决定。
 */
public class MonthView extends BaseView {

    public MonthView(OnyxHost host) {
        super(host, Views.LEVEL_MONTH);
    }

    @Override
    public void bind(long anchor) {
        begin();

        final long monthStart = DateUtil.startOfMonth(anchor);
        final long monthEnd = DateUtil.addMonths(monthStart, 1);

        OnyxHost.ViewAdapter self = host.adapterOf(Views.LEVEL_MONTH);
        DbHelper db = host.db();
        if (self == null || db == null) {
            end();
            return;
        }

        int days = DateUtil.daysInMonth(monthStart);
        int[] counts = db.countByDay(monthStart, monthEnd, days);
        int total = 0;
        for (int c : counts) {
            total += c;
        }
        totalCount = total;

        banner(DateUtil.y(monthStart), DateUtil.ym(monthStart));

        // 周标题
        LinearLayout weekHead = Views.row(ctx);
        weekHead.setPadding(0, 0, 0, Views.dp(ctx, 6));
        for (int i = 0; i < 7; i++) {
            TextView t = Views.label(ctx, R.style.Text_Faint, DateUtil.weekHead()[i]);
            t.setGravity(Gravity.CENTER);
            t.setTextSize(11);
            weekHead.addView(t, Views.llp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
        Views.addChild(wrap, weekHead);

        LinearLayout card = Views.card(ctx);
        int offset = DateUtil.firstWeekdayOffset(monthStart);
        int cursor = -offset;
        for (int r = 0; r < 6; r++) {
            LinearLayout weekRow = Views.row(ctx);
            for (int c = 0; c < 7; c++) {
                int dayIndex = cursor + c;
                LinearLayout cellView;
                if (dayIndex < 0 || dayIndex >= days) {
                    // 补白：属于上/下一个月的那几格，也允许点进去
                    long other = DateUtil.addDays(monthStart, dayIndex);
                    cellView = dayCell(other, -1, false);
                } else {
                    long day = DateUtil.addDays(monthStart, dayIndex);
                    cellView = dayCell(day, counts[dayIndex], DateUtil.isToday(day));
                    cell(cellView, day);
                }
                weekRow.addView(cellView, Views.llp(0, Views.dp(ctx, 46), 1f));
            }
            Views.addChild(card, weekRow);
            if (r < 5) {
                Views.addChild(card, Views.rowDivider(ctx));
            }
            cursor += 7;
        }
        Views.addChild(wrap, card);

        LinearLayout foot = Views.row(ctx);
        foot.setPadding(Views.dp(ctx, 2), Views.dp(ctx, 12), Views.dp(ctx, 2), 0);
        foot.addView(Views.text(ctx, R.style.Text_Faint, R.string.hint_tap_day));
        Views.addChild(wrap, foot);

        end();
    }

    /** 一格：日期数字 + 有记录时下方一个柔和的小点；周一到周日各占 1/7 */
    private LinearLayout dayCell(long day, int count, boolean today) {
        LinearLayout box = Views.column(ctx);
        box.setGravity(Gravity.CENTER);
        box.setBackgroundResource(R.drawable.bg_month_cell);
        box.setSelected(today);

        TextView num = Views.label(ctx, R.style.Text_Body, String.valueOf(DateUtil.dayOfMonth(day)));
        num.setGravity(Gravity.CENTER);
        num.setTextSize(14);
        if (count < 0) {
            num.setTextColor(Views.color(ctx, R.color.ink_faint));
        }
        box.addView(num, Views.llp(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        View dot = new View(ctx);
        LinearLayout.LayoutParams dp = Views.llp(Views.dp(ctx, 4), Views.dp(ctx, 4));
        dp.topMargin = Views.dp(ctx, 5);
        if (count > 0) {
            dot.setBackgroundResource(R.drawable.shape_dot);
        } else {
            dot.setBackgroundColor(0x00000000);
        }
        box.addView(dot, dp);
        return box;
    }

    @Override
    public void onTap(long dayMillis) {
        host.drill(dayMillis);
    }
}
