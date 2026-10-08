package com.MATO.log.ui;

import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.MATO.log.R;
import com.MATO.log.data.DbHelper;
import com.MATO.log.util.DateUtil;

/**
 * 年跨度：12 个月一览。
 * 每个格子里是这个月的事件数，点进去是二级界面（以月为跨度），再往下是周、日。
 */
public class YearView extends BaseView {

    private static final int COLS = 3;
    private static final int ROWS = 4;

    public YearView(OnyxHost host) {
        super(host, Views.LEVEL_YEAR);
    }

    @Override
    public void bind(long anchor) {
        begin();

        final long yearStart = DateUtil.startOfYear(anchor);
        OnyxHost.ViewAdapter self = host.adapterOf(Views.LEVEL_YEAR);
        DbHelper db = host.db();
        if (self == null || db == null) {
            end();
            return;
        }

        int[] counts = new int[12];
        int total = 0;
        for (int m = 0; m < 12; m++) {
            long ms = DateUtil.addMonths(yearStart, m);
            counts[m] = db.countByRange(ms, DateUtil.addMonths(ms, 1));
            total += counts[m];
        }
        totalCount = total;

        banner(ctx.getString(R.string.ctx_year_scope), DateUtil.y(yearStart));

        // 与「周」一致：没有记录也把 12 个月列出来，否则就下钻不进月历了
        LinearLayout grid = Views.column(ctx);
        for (int r = 0; r < ROWS; r++) {
            LinearLayout row = Views.row(ctx);
            for (int c = 0; c < COLS; c++) {
                int m = r * COLS + c;
                long ms = DateUtil.addMonths(yearStart, m);
                LinearLayout cellView = monthCell(ms, counts[m],
                        DateUtil.year(anchor) == DateUtil.year(ms)
                                && DateUtil.month(anchor) == DateUtil.month(ms));
                cell(cellView, ms);
                LinearLayout.LayoutParams p = Views.llp(0, Views.dp(ctx, 74), 1f);
                p.rightMargin = c < COLS - 1 ? Views.dp(ctx, 8) : 0;
                p.bottomMargin = r < ROWS - 1 ? Views.dp(ctx, 8) : 0;
                row.addView(cellView, p);
            }
            grid.addView(row);
        }
        Views.addChild(wrap, grid);

        LinearLayout foot = Views.row(ctx);
        foot.setPadding(Views.dp(ctx, 2), Views.dp(ctx, 12), Views.dp(ctx, 2), 0);
        foot.addView(Views.text(ctx, R.style.Text_Faint, R.string.hint_tap_month));
        Views.addChild(wrap, foot);

        end();
    }

    private LinearLayout monthCell(long monthStart, int count, boolean selected) {
        LinearLayout box = Views.column(ctx);
        box.setGravity(Gravity.CENTER);
        box.setBackgroundResource(R.drawable.bg_year_cell);
        box.setSelected(selected);
        box.setPadding(Views.dp(ctx, 6), Views.dp(ctx, 10), Views.dp(ctx, 6), Views.dp(ctx, 10));

        TextView name = Views.label(ctx, R.style.Text_Body, DateUtil.monthName(monthStart));
        name.setGravity(Gravity.CENTER);
        name.setTextSize(15);
        name.setLetterSpacing(0.06f);
        box.addView(name);

        TextView cnt = Views.label(ctx, R.style.Text_Faint,
                count > 0 ? count + " " + ctx.getString(R.string.count_unit) : "—");
        cnt.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cp.topMargin = Views.dp(ctx, 5);
        box.addView(cnt, cp);
        return box;
    }

    @Override
    public void onTap(long dayMillis) {
        host.drill(dayMillis);
    }
}
