package com.MATO.log.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.MATO.log.R;

import java.util.ArrayList;

/**
 * 选项弹窗：把「标题 + 说明 + 一排选项」做成本应用自己的列表，而不是交给
 * {@link AlertDialog.Builder#setItems}。
 *
 * 为什么不用 setItems：实测 vivo Z1 / Android 9（Funtouch）上，「标题 + 说明 + 列表」这个组合
 * 会渲染成只有一个空框 —— 列表一段都不画出来，只剩标题、说明和「取消」，功能等于不可用。
 * 同一台机器上「无标题无说明的列表」（长按条目的编辑/删除）却正常，说明是该 ROM 在
 * 这种组合下的布局问题，靠猜没法保证下次系统更新不复发。
 *
 * 自绘列表没有这个不确定性：它就是几个普通的 LinearLayout，跟导出页的区间列表同一套做法，
 * 样式也更贴本应用的卡片风格（1px 描边 + 3dp 圆角）。
 */
public final class Choices {

    private Choices() {
    }

    public interface OnPick {
        void onPick(int index);
    }

    /**
     * @param title   标题；null 或空则不显示
     * @param message 说明；null 或空则不显示
     * @param labels  选项文字，顺序即回调里的下标
     */
    public static AlertDialog show(Activity act, CharSequence title, CharSequence message,
                                   CharSequence[] labels, final OnPick pick) {
        return show(act, title, message, labels, false, pick);
    }

    /**
     * 与 {@link #show} 相同，但选项很多时用这个。
     *
     * <p><b>为什么需要它</b>：不套滚动容器时，选项多了会把弹窗撑出屏幕 ——
     * 上端顶到状态栏、下端被导航栏切掉，**底部的选项永远点不到**，而用户往往
     * 根本不知道下面还有内容。拉取模型清单就是这种场景：一次可能返回上百个型号。
     */
    public static AlertDialog showScrollable(Activity act, CharSequence title, CharSequence message,
                                             CharSequence[] labels, final OnPick pick) {
        return show(act, title, message, labels, true, pick);
    }

    private static AlertDialog show(Activity act, CharSequence title, CharSequence message,
                                    CharSequence[] labels, boolean scrollable, final OnPick pick) {
        if (labels == null || labels.length == 0) {
            return null;
        }
        LinearLayout box = Views.column(act);
        int pad = Views.dp(act, 22);
        box.setPadding(pad, Views.dp(act, 4), pad, 0);

        LinearLayout card = Views.card(act);
        final ArrayList<View> rows = new ArrayList<View>();
        for (int i = 0; i < labels.length; i++) {
            View r = ChoiceRow.create(act, card, labels[i]);
            // 弹窗里的选项不需要单选圆环，只要文字行
            View mark = r.findViewById(R.id.choice_mark);
            if (mark != null) {
                mark.setVisibility(View.GONE);
            }
            rows.add(r);
            card.addView(r, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            if (i < labels.length - 1) {
                card.addView(Views.rowDivider(act, 0));
            }
        }
        box.addView(card);

        View content = box;
        if (scrollable) {
            CappedScroll sv = new CappedScroll(act, Views.dp(act, 380));
            sv.setOverScrollMode(View.OVER_SCROLL_NEVER);
            sv.addView(box, new android.widget.FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            content = sv;
        }

        AlertDialog.Builder b = new AlertDialog.Builder(act);
        if (title != null && title.length() > 0) {
            b.setTitle(title);
        }
        if (message != null && message.length() > 0) {
            b.setMessage(message);
        }
        b.setView(content);
        b.setNegativeButton(R.string.action_cancel, (DialogInterface.OnClickListener) null);
        final AlertDialog dialog = b.create();

        // 对话框要先建出来才能被行引用（此时 dialog 已是 final），点一行就关掉它
        for (int k = 0; k < rows.size(); k++) {
            final int index = k;
            rows.get(k).setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    dialog.dismiss();
                    if (pick != null) {
                        pick.onPick(index);
                    }
                }
            });
        }
        dialog.show();
        return dialog;
    }

    /**
     * 限高的 ScrollView。
     *
     * <p>ScrollView 本身没有 maximumHeight，而弹窗里除了列表还有标题、说明和按钮 ——
     * 列表必须能被压住。用 AT_MOST 而不是 EXACTLY：内容短时仍然按内容高度显示，
     * 只有超过上限才滚动，免得三个选项也撑出一大片空白。
     */
    private static final class CappedScroll extends android.widget.ScrollView {

        private final int maxPx;

        CappedScroll(Context c, int maxPx) {
            super(c);
            this.maxPx = maxPx;
        }

        @Override
        protected void onMeasure(int widthSpec, int heightSpec) {
            super.onMeasure(widthSpec,
                    MeasureSpec.makeMeasureSpec(maxPx, MeasureSpec.AT_MOST));
        }
    }
}
