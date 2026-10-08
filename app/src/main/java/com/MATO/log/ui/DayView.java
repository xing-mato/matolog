package com.MATO.log.ui;

import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import com.MATO.log.R;
import com.MATO.log.data.DbHelper;
import com.MATO.log.data.Event;
import com.MATO.log.util.DateUtil;

/** 日跨度：把这一天记录过的事件全部列出来 */
public class DayView extends BaseView {

    public DayView(OnyxHost host) {
        super(host, Views.LEVEL_DAY);
    }

    @Override
    public void bind(long anchor) {
        begin();

        final long start = DateUtil.startOfDay(anchor);
        final long end = DateUtil.addDays(start, 1);

        OnyxHost.ViewAdapter self = host.adapterOf(Views.LEVEL_DAY);
        DbHelper db = host.db();
        if (self == null || db == null) {
            end();
            return;
        }

        self.events().clear();
        // queryByRange 已经按「从新到旧」给出顺序（见 DbHelper 里的说明），这里原样铺开。
        // 全应用只有这一处把事件逐条列出来，所以顺序只在这一处生效；
        // 周/月/年只画每天的条数，导出与备份另有自己的正序来源，都不受影响。
        self.events().addAll(db.queryByRange(start, end));
        totalCount = self.events().size();

        banner(DateUtil.y(start), DateUtil.fullDay(start));

        if (self.events().isEmpty()) {
            empty(ctx.getString(R.string.empty_day));
            end();
            return;
        }

        LinearLayout card = newCard();
        for (int i = 0; i < self.events().size(); i++) {
            final Event e = self.events().get(i);
            View row = Views.eventRow(ctx, e, Views.LEVEL_DAY, true);
            // 登记成可点格：轻点 = 编辑，长按 = 编辑 / 删除
            // 注意这里必须放事件 id，宿主是拿它去库里比对的
            cell(row, e.id);
            row.setTag(R.id.tag_kind, Integer.valueOf(1));
            Views.addChild(card, row);
            if (i < self.events().size() - 1) {
                Views.addChild(card, Views.rowDivider(ctx));
            }
        }
        Views.addChild(wrap, card);

        end();
    }
}
