package com.MATO.log.ui;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.MATO.log.R;

import java.util.ArrayList;

/** 各跨度界面的公共骨架：一个内边距容器 + 小节表头 + 卡片 + 空态 */
public abstract class BaseView implements LevelView {

    protected final OnyxHost host;
    protected final Context ctx;
    protected final LinearLayout root;
    protected final int level;

    protected LinearLayout wrap;
    protected ArrayList<View> cells = new ArrayList<>();

    protected BaseView(OnyxHost host, int level) {
        this.host = host;
        this.level = level;
        this.ctx = host.getContext();
        this.root = Views.column(ctx);
        this.root.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        // 先备好容器，避免 bind 还没走完 begin() 就被 empty() 用到
        this.wrap = newWrap();
    }

    @Override
    public View createView() {
        return root;
    }

    @Override
    public View getRoot() {
        return root;
    }

    private LinearLayout newWrap() {
        LinearLayout w = Views.column(ctx);
        w.setPadding(Views.dp(ctx, 18), Views.dp(ctx, 0), Views.dp(ctx, 18), Views.dp(ctx, 90));
        return w;
    }

    /** 开始一次渲染：清空并挂上一个新的内容容器 */
    protected void begin() {
        // 先卸掉旧容器，否则它仍然持有上一轮的子视图，
        // 下一次渲染复用同一批实例时会撞上「already has a parent」。
        if (wrap != null && wrap.getParent() == root) {
            root.removeView(wrap);
        }
        if (wrap != null) {
            wrap.removeAllViews();
            wrap = null;
        }
        root.removeAllViews();
        cells = new ArrayList<>();
        totalCount = 0;
        wrap = newWrap();
        root.addView(wrap);
    }

    /**
     * 顶部信息条：四个跨度统一用它。
     * 上行小字是所在的「年」，下行大字是当前范围，右侧是条数，两侧是上一段 / 下一段。
     */
    protected void banner(String contextLine, String mainLine) {
        Views.addChild(wrap, Views.pageBanner(ctx, contextLine, mainLine, countText(totalCount),
                new Views.OnStep() {
                    @Override
                    public void onStep(int direction) {
                        host.step(direction);
                    }
                }));
    }

    /** 当前跨度内的记录条数，由各视图在 bind 里填好 */
    protected int totalCount = 0;

    public int getTotalCount() {
        return totalCount;
    }

    protected void end() {
        host.registerCells(level, cells);
    }

    protected void header(String title, String count) {
        Views.addChild(wrap, Views.sectionHeader(ctx, title, count));
    }

    protected void empty(String text) {
        Views.addChild(wrap, emptyBlock(ctx, text));
    }

    protected LinearLayout newCard() {
        LinearLayout card = Views.card(ctx);
        Views.addChild(wrap, card);
        return card;
    }

    protected LinearLayout newCard(float weight) {
        LinearLayout card = Views.card(ctx);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0);
        p.weight = weight;
        p.topMargin = 0;
        card.setLayoutParams(p);
        Views.addChild(wrap, card);
        return card;
    }

    /** 登记一个可点格 */
    protected void cell(View v, long dayMillis) {
        v.setTag(R.id.tag_day, Long.valueOf(dayMillis));
        cells.add(v);
    }

    protected String countText(int n) {
        return n <= 0 ? ctx.getString(R.string.count_none)
                : n + " " + ctx.getString(R.string.count_unit);
    }

    @Override
    public void onTap(long dayMillis) {
        // 默认无下钻行为
    }

    @Override
    public void onSwipeHorizontal(boolean forward) {
        // 由宿主统一处理
    }

    static View emptyBlock(Context ctx, String text) {
        LinearLayout box = Views.column(ctx);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setPadding(0, Views.dp(ctx, 54), 0, Views.dp(ctx, 40));

        ImageView icon = new ImageView(ctx);
        icon.setImageResource(R.drawable.ic_empty);
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(
                Views.dp(ctx, 52), Views.dp(ctx, 52));
        ip.gravity = Gravity.CENTER_HORIZONTAL;
        box.addView(icon, ip);

        TextView t = Views.text(ctx, R.style.Text_Faint, 0);
        t.setText(text);
        t.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tp.topMargin = Views.dp(ctx, 14);
        box.addView(t, tp);

        TextView hint = Views.text(ctx, R.style.Text_Faint, R.string.empty_hint);
        hint.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hp.topMargin = Views.dp(ctx, 6);
        box.addView(hint, hp);
        return box;
    }
}
