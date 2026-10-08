package com.MATO.log.ui;

import android.app.Activity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.MATO.log.R;

/**
 * 一行可选中的条目（导出范围就是用这个）。
 *
 * 为什么单独抽一个类：选中态必须真正走到背景选择器上。
 * 之前是直接 `mark.setBackgroundResource(on ? bg_check_on : bg_check)`，
 * 而这两个 drawable 的 <item> 带 `state_checked`/`state_selected` 条件，
 * 挂在普通 View 上又不去设置对应状态，条件永远不成立 —— 结果画出来**始终是未选中态**，
 * 用户点了十几下界面都没反应，合理地被当成「页面卡住了」。
 *
 * 现在的做法：行上 setSelected(on)（驱动整行底色与指示器），
 * 指示器再 setChecked(on)（双保险，顺便让 state_checked 也成立）。
 */
final class ChoiceRow {

    private ChoiceRow() {
    }

    /** 生成一行；调用方负责设文字、挂点击、塞进容器 */
    static View create(Activity act, ViewGroup parent, CharSequence label) {
        View row = act.getLayoutInflater().inflate(R.layout.item_choice, parent, false);
        TextView t = row.findViewById(R.id.choice_label);
        t.setText(label == null ? "" : label);
        return row;
    }

    /** 设置选中态 */
    static void setSelected(View row, boolean on) {
        if (row == null) {
            return;
        }
        // 行：驱动整行底色（bg_choice 是带 state_selected 的选择器）
        row.setSelected(on);
        // 指示器：直接换图，不依赖任何状态 —— 这一步以前漏了，才会「点了没反应」
        View mark = row.findViewById(R.id.choice_mark);
        if (mark != null) {
            mark.setBackgroundResource(on ? R.drawable.bg_check_on : R.drawable.bg_check);
        }
        View label = row.findViewById(R.id.choice_label);
        if (label instanceof TextView) {
            ((TextView) label).setTextColor(Views.color(
                    row.getContext(), on ? R.color.ink : R.color.ink_soft));
        }
    }

    /** 行本身占满宽度 */
    static void addTo(ViewGroup parent, View row) {
        parent.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }
}
