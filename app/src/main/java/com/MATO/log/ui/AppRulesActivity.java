package com.MATO.log.ui;

import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.MATO.log.R;
import com.MATO.log.data.DbHelper;
import com.MATO.log.rec.AppFilter;
import com.MATO.log.rec.Recorder;
import com.MATO.log.util.AppInfo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 应用名单：给每个应用点一枚「排除」，被排除的不参与自动记录，并且**置顶**。
 *
 * <p><b>这一页只有「排除」一个功能，因为规则里也只有这一条。</b>没被排除的就会记录 ——
 * 界面上看到的和引擎真会做的是同一件事，不需要用户先知道任何隐藏规则。
 *
 * <p>1.3.1 之前这里还有第二枚标记「只记这些」。它被去掉的原因不是做不到，而是<b>说不清</b>：
 * 两个标记长在每一行上、语义相反、还互相压制（白名单一旦非空，「排除」就整个失效）。
 * 界面上必须写一段话解释，而用户仍然会在点完之后才意识到自己刚才动的是另一套规则。
 * <b>一个要靠说明才成立的开关，本身就是误导。</b>
 *
 * <p>1.3 到 1.3.3 之间还藏着一条同类的规则：<b>系统应用默认不记</b>，硬编码在
 * {@code AppFilter} 里。于是名单页里那些系统应用显示为「未排除」，用户以为会记，
 * 通知栏却说「系统应用，默认不记」—— 界面上看不见、也改不掉。1.3.3 把它删了：
 * 只有「排除」这一层，系统应用照记。要挡的那些（桌面、状态栏、安装器）归
 * {@code AppFilter.SHELL} 管，它们不出现在这一页里 —— 一行按下去什么都不会发生的
 * 开关，比不列出来更让人费解。
 *
 * <p>库里遗留的白名单由 {@code App.onCreate} 里的一次性迁移清掉（见 {@code DbHelper.clearOnlyRules}）。
 *
 * <p>行上不套用系统列表控件：工程既有风格就是手搭 LinearLayout（也因为实测
 * 有些 ROM 的系统列表弹窗有毛病，见交接说明 2.1）。这里沿用同一套写法。
 *
 * <p><b>为什么分页而不是全量铺</b>：列表列的是「全部已安装应用」（见 {@link AppInfo}），
 * 含几百个没有启动图标的系统组件，而每一条都要取一次应用图标、新建好几个 View。
 * 手搭 View 没有复用池，能省的只有「先不建」—— 所以先铺 {@code PAGE_SIZE} 条，
 * 底部留一行「显示更多」往后追加，搜索词或数据一变就回到第一页。
 */
public class AppRulesActivity extends BaseActivity {

    /**
     * 一次铺多少行。
     *
     * <p>50 是折中：行高约 52dp，50 行差不多四五屏 —— 翻几下就够确认「没有我要找的」，
     * 又不会为了翻到底一次建几百个带图标的 View。继续往下翻的收益很低：真要找某个应用，
     * 搜索比翻页快（搜索是在**全部**应用上做的，不受分页影响）。
     */
    private static final int PAGE_SIZE = 50;

    private DbHelper db;
    private LinearLayout listBox;
    private EditText search;
    private TextView summary;

    /** 当前搜索词（小写）。空串 = 全显示 */
    private String query = "";

    /** 现在最多渲染多少条。随「显示更多」增长，随 query 或数据变化回到 {@code PAGE_SIZE} */
    private int renderLimit = PAGE_SIZE;

    private List<AppInfo> apps = new ArrayList<>();
    private final Set<String> excluded = new HashSet<>();

    /**
     * 「已排除的排前面」，其余保持原顺序。
     *
     * <p>返回 0 的两行不会被重新洗牌 —— {@link Collections#sort} 是稳定排序，
     * 所以两组内部仍然是 {@link AppInfo} 给出的那套顺序：普通应用在前、各自按名称。
     * 每次渲染现算，所以点一下「排除」，那一行会当场升到最上面。
     */
    private final Comparator<AppInfo> EXCLUDED_FIRST = new Comparator<AppInfo>() {
        @Override
        public int compare(AppInfo a, AppInfo b) {
            boolean ea = excluded.contains(a.pkg);
            boolean eb = excluded.contains(b.pkg);
            if (ea == eb) {
                return 0;
            }
            return ea ? -1 : 1;
        }
    };

    /** 这一轮渲染出来的行（包名 -> 行控件）。重排动画要拿它读「刚才在哪」 */
    private final Map<String, View> rowViews = new HashMap<>();

    /**
     * 非 null = 这一次渲染要做重排动画，值是各行的旧位置（包名 -> 旧 top）。
     *
     * <p>只有点标签时才置它。搜索、翻页、从系统设置回到这一页都会重渲染，
     * 那些情况下列表本来就是从头铺的，整页跟着滑一遍只是噪音。
     */
    private Map<String, Integer> reorderFrom;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        db = new DbHelper(getApplicationContext());
        buildScaffold();
        // 这里**不**调 reload()：onResume 紧随其后，而它每次都 invalidate + reload。
        // 两边都调的话，冷启动会把几百个包的应用列表枚举两遍（每遍都要给每个包取一次
        // 名字），白白多花一次可能上百毫秒的开销。
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 从系统里装/卸了应用之后回来，列表要更新
        AppInfo.invalidate();
        reload();
    }

    // ---------------- 骨架 ----------------

    private void buildScaffold() {
        LinearLayout root = Views.column(this);
        root.setBackgroundColor(Views.color(this, R.color.paper));

        // 顶栏
        LinearLayout bar = Views.row(this);
        bar.setPadding(Views.dp(this, 10), Views.dp(this, 12), Views.dp(this, 14), Views.dp(this, 8));
        ImageView back = new ImageView(this);
        back.setImageResource(R.drawable.ic_chevron_left);
        back.setBackgroundResource(R.drawable.ripple_round);
        back.setPadding(Views.dp(this, 10), Views.dp(this, 10), Views.dp(this, 10), Views.dp(this, 10));
        back.setContentDescription(getString(R.string.action_back));
        back.setImageTintList(android.content.res.ColorStateList.valueOf(
                Views.color(this, R.color.ink)));
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        bar.addView(back, Views.llp(Views.dp(this, 40), Views.dp(this, 40)));
        TextView title = Views.label(this, R.style.Text_Body, getString(R.string.rules_title));
        title.setTextSize(16f);
        LinearLayout.LayoutParams tp = Views.llp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tp.leftMargin = Views.dp(this, 6);
        title.setLayoutParams(tp);
        bar.addView(title);
        root.addView(bar, Views.llp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        View line = new View(this);
        line.setBackgroundColor(Views.color(this, R.color.divider));
        root.addView(line, Views.llp(ViewGroup.LayoutParams.MATCH_PARENT, 1));

        // 搜索框
        search = new EditText(this);
        search.setTextAppearance(this, R.style.Widget_MATOlog_EditText);
        search.setBackgroundResource(R.drawable.bg_field);
        search.setHint(R.string.rules_search);
        search.setSingleLine(true);
        search.setPadding(Views.dp(this, 14), Views.dp(this, 12), Views.dp(this, 14), Views.dp(this, 12));
        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                query = s == null ? "" : s.toString().trim().toLowerCase(Locale.ROOT);
                // 搜索是在**全部**应用上做的，命中集合会整个变掉，页码必须跟着回到第一页。
                // 不然「先点两次显示更多、再搜一个词」会从第 151 条开始铺，看着像没搜到
                resetPage();
                renderList();
            }
        });
        LinearLayout.LayoutParams sp = Views.llp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        sp.leftMargin = Views.dp(this, 18);
        sp.rightMargin = Views.dp(this, 18);
        sp.topMargin = Views.dp(this, 12);
        search.setLayoutParams(sp);
        root.addView(search);

        // 说明
        summary = Views.label(this, R.style.Text_Faint, "");
        summary.setLineSpacing(0, 1.35f);
        LinearLayout.LayoutParams mp = Views.llp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        mp.leftMargin = Views.dp(this, 20);
        mp.rightMargin = Views.dp(this, 20);
        mp.topMargin = Views.dp(this, 12);
        summary.setLayoutParams(mp);
        root.addView(summary);

        // 列表
        ScrollView scroll = new ScrollView(this);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        listBox = Views.column(this);
        listBox.setPadding(Views.dp(this, 18), Views.dp(this, 12), Views.dp(this, 18), Views.dp(this, 40));
        scroll.addView(listBox, Views.llp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, Views.llp(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
    }

    // ---------------- 数据 ----------------

    private void reload() {
        Set<String> known = new HashSet<>(db.excludedPkgs());
        // 全部已安装应用；known 里那些已经卸载的包名也要并进来，
        // 否则名单里会留下一条在界面上改不掉的名字
        apps = AppInfo.all(this, known);

        excluded.clear();
        excluded.addAll(db.excludedPkgs());

        // 系统界面（桌面、状态栏、安装器这类）是硬性不记的，列在这里也改不动 ——
        // 一行按下去什么都不会发生的开关，比不列出来更让人费解。说明由顶部那句话承担。
        List<AppInfo> controllable = new ArrayList<>();
        for (AppInfo a : apps) {
            if (!AppFilter.isShell(a.pkg)) {
                controllable.add(a);
            }
        }
        apps = controllable;

        // 名字里存过、但已经卸载的包：顺手清掉，免得名单里留下改不掉的名字
        Set<String> installed = new HashSet<>();
        for (AppInfo a : apps) {
            installed.add(a.pkg);
        }
        db.pruneRules(installed);

        // 数据换了，页码跟着重来：第 3 页的「第 101~150 条」对新数据没有意义
        resetPage();
        renderList();
    }

    /** 回到第一页。数据或搜索词一变就得调用：页码和列表内容必须同时成立 */
    private void resetPage() {
        renderLimit = PAGE_SIZE;
    }

    private void renderList() {
        if (listBox == null) {
            return;
        }
        // 取走这一轮的重排起点（取走即清空，免得下一次渲染又照着旧位置动一遍）
        final Map<String, Integer> from = reorderFrom;
        reorderFrom = null;

        listBox.removeAllViews();
        rowViews.clear();
        updateSummary();

        // 先在**全部**应用上过滤，再按页码截断。反过来的话，搜索就只能搜到已经
        // 渲染出来的那几十条，「显示更多」之后的部分等于不存在
        List<AppInfo> matched = new ArrayList<>();
        for (AppInfo a : apps) {
            if (matches(a, query)) {
                matched.add(a);
            }
        }

        // 已排除的置顶。这一页是拿来管排除的，被排除的那几个就是用户要反复看的行 ——
        // 名单列的是全部已安装应用（几百条），沉在里面的那几行等于找不到。
        //
        // 排序放在截断**之前**：不然被排除的行会落在第 3 页上，「置顶」就只是
        // 「在当前这一页里靠前」，白搭。用稳定排序（Collections.sort），
        // 所以两组内部仍是 AppInfo 排好的那套顺序：普通应用在前、各自按名称。
        Collections.sort(matched, EXCLUDED_FIRST);

        if (matched.isEmpty()) {
            TextView empty = Views.label(this, R.style.Text_Faint, getString(R.string.rules_no_match));
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, Views.dp(this, 40), 0, 0);
            listBox.addView(empty, Views.llp(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            return;
        }

        LinearLayout card = Views.card(this);
        int end = Math.min(renderLimit, matched.size());
        for (int i = 0; i < end; i++) {
            if (i > 0) {
                card.addView(Views.rowDivider(this, 16));
            }
            View row = appRow(matched.get(i));
            rowViews.put(matched.get(i).pkg, row);
            card.addView(row, Views.llp(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        int rest = matched.size() - end;
        if (rest > 0) {
            card.addView(Views.rowDivider(this, 16));
            card.addView(moreRow(rest), Views.llp(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        listBox.addView(card, Views.llp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        if (from != null && !from.isEmpty()) {
            playReorder(from);
        }
    }

    /** 当前每一行的位置。行都在同一张卡片里，top 相互可比 */
    private Map<String, Integer> snapshotTops() {
        Map<String, Integer> out = new HashMap<>();
        for (Map.Entry<String, View> e : rowViews.entrySet()) {
            out.put(e.getKey(), Integer.valueOf(e.getValue().getTop()));
        }
        return out;
    }

    /**
     * 重排动画：让动过的行从它刚才的位置滑到新位置。
     *
     * <p>做法是 FLIP —— 先把行瞬移回原处（{@code translationY = 旧 top - 新 top}），
     * 再让这个位移归零，看上去就是它自己滑过去的。所以要等一次布局（{@code OnPreDraw}）
     * 才有「新 top」可读：刚 addView 的控件还没排版。
     *
     * <p><b>滑动期间给行铺一层卡片底色。</b>行本身是透明的（{@code Views.row} 不带背景），
     * 不铺的话它会和路过那些行的文字叠在一起，230 毫秒里糊成一团。底色用
     * {@code surface} 加卡片同款圆角，与卡片严丝合缝，动完再摘掉。
     *
     * <p>没动过的行不参与 —— 起一堆无位移的动画只是白费帧。
     */
    private void playReorder(final Map<String, Integer> from) {
        listBox.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                listBox.getViewTreeObserver().removeOnPreDrawListener(this);
                final float radius = getResources().getDimensionPixelSize(R.dimen.card_radius);
                final int surface = Views.color(AppRulesActivity.this, R.color.surface);

                for (Map.Entry<String, View> e : rowViews.entrySet()) {
                    Integer old = from.get(e.getKey());
                    if (old == null) {
                        // 这一轮新出现的行（原先在第 3 页、或刚被搜出来）：没有「刚才」，
                        // 没有起点可滑，直接待在原地
                        continue;
                    }
                    final View row = e.getValue();
                    float delta = old.intValue() - row.getTop();
                    if (Math.abs(delta) < 1f) {
                        continue;
                    }
                    GradientDrawable bg = new GradientDrawable();
                    bg.setColor(surface);
                    bg.setCornerRadius(radius);
                    row.setBackground(bg);
                    row.animate().cancel();
                    row.setTranslationY(delta);
                    row.animate()
                            .translationY(0f)
                            .setDuration(Motion.MOVE_MS)
                            .setInterpolator(Motion.DECELERATE)
                            .withEndAction(new Runnable() {
                                @Override
                                public void run() {
                                    row.setBackground(null);
                                }
                            })
                            .start();
                }
                return true;
            }
        });
    }

    /** 这条在不在当前搜索词里。空词 = 全部命中 */
    private static boolean matches(AppInfo a, String q) {
        if (q == null || q.isEmpty()) {
            return true;
        }
        String label = a.label == null ? "" : a.label.toLowerCase(Locale.ROOT);
        return label.contains(q) || a.pkg.toLowerCase(Locale.ROOT).contains(q);
    }

    /**
     * 列表底部那行「显示更多（还有 X 个）」。
     *
     * <p>点一次往后多铺 {@code PAGE_SIZE} 条，然后整段重渲染，而不是往卡片里增量插入：
     * 增量插入要另外维护「那一行现在在什么位置」，而重渲染让界面永远只是
     * 「按 renderLimit 渲染」这一个规则的结果 —— 需要维护的状态只有页码一个。
     *
     * @param rest 还没渲染出来的条数
     */
    private View moreRow(final int rest) {
        TextView t = Views.label(this, R.style.Text_Faint,
                getString(R.string.rules_show_more, rest));
        t.setTextSize(14f);
        // 主色 + 整行可点：这一行是操作，不是应用条目，颜色上要分得开
        t.setTextColor(Views.color(this, R.color.primary));
        t.setGravity(Gravity.CENTER);
        int pad = Views.dp(this, 14);
        t.setPadding(Views.dp(this, 12), pad, Views.dp(this, 12), pad);
        t.setBackgroundResource(R.drawable.bg_week_row);
        t.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                renderLimit += PAGE_SIZE;
                renderList();
            }
        });
        return t;
    }

    /**
     * 顶部那句说明。
     *
     * <p><b>必须与判定规则一致。</b>原先是「没排除任何应用时」说得准（会提一句系统应用
     * 默认不记），一旦排除了别的应用就变成「其余都会记录」—— 而系统应用其实仍然不记。
     * 用户一动手，页面就开始说错话。现在规则里只剩「排除」这一层，
     * 两句都成立，也不再需要分叉。
     */
    private void updateSummary() {
        int ex = excluded.size();
        if (ex > 0) {
            summary.setText(getString(R.string.rules_summary_some, ex));
        } else {
            summary.setText(R.string.rules_summary_none);
        }
    }

    // ---------------- 一行 ----------------

    private View appRow(final AppInfo a) {
        LinearLayout row = Views.row(this);
        int pad = Views.dp(this, 16);
        row.setPadding(pad, Views.dp(this, 12), Views.dp(this, 12), Views.dp(this, 12));

        ImageView icon = new ImageView(this);
        android.graphics.drawable.Drawable d = AppInfo.iconOf(this, a.pkg);
        if (d != null) {
            icon.setImageDrawable(d);
        }
        row.addView(icon, Views.llp(Views.dp(this, 30), Views.dp(this, 30)));

        LinearLayout texts = Views.column(this);
        LinearLayout.LayoutParams tp = Views.llp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tp.leftMargin = Views.dp(this, 12);
        texts.setLayoutParams(tp);

        TextView name = Views.label(this, R.style.Text_Body, a.label);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        texts.addView(name);

        // 包名小字：同名应用、或想确认到底是不是那个包时有用
        TextView sub = Views.label(this, R.style.Text_Faint, a.pkg);
        sub.setTextSize(11f);
        sub.setSingleLine(true);
        sub.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        texts.addView(sub);
        row.addView(texts);

        // 一个状态点：点亮 = 这个应用不记
        row.addView(toggle(a));
        return row;
    }

    /**
     * 那一枚可点的「排除」标记。点亮 = 这个应用不记，再点 = 恢复记录。
     *
     * <p><b>一个开关就够了。</b>规则里现在只有「排除」这一份名单（见 {@link AppFilter}），
     * 所以这一枚标记的含义是完整的：没点亮 = 会记录。1.3 那会儿不是 —— 系统应用即使
     * 没被排除也不会记，而界面上没有任何地方能看出这件事。那是规则的问题，不是
     * 少一个开关的问题；补第二枚标记只会把「说不清」再搬回来一次。
     */
    private View toggle(final AppInfo a) {
        final boolean on = excluded.contains(a.pkg);
        final TextView t = new TextView(this);
        t.setTextAppearance(this, R.style.Text_Faint);
        t.setTextSize(12f);
        t.setText(R.string.rules_exclude);
        t.setGravity(Gravity.CENTER);
        t.setPadding(Views.dp(this, 12), Views.dp(this, 6), Views.dp(this, 12), Views.dp(this, 6));
        styleToggle(t, on);

        LinearLayout.LayoutParams lp = Views.llp(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = Views.dp(this, 6);
        t.setLayoutParams(lp);

        t.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                boolean now = excluded.contains(a.pkg);
                if (now) {
                    // 再点一次 = 取消排除，回到「会记录」
                    excluded.remove(a.pkg);
                } else {
                    excluded.add(a.pkg);
                }
                db.setRule(a.pkg, a.label, now ? 0 : DbHelper.RULE_EXCLUDE);
                // 名单改了立刻生效，不用重启服务
                Recorder rec = Recorder.get();
                if (rec != null) {
                    rec.reload();
                }
                // 记下每一行现在在哪，渲染完按它把动过的行滑过去（见 playReorder）——
                // 「排除」会把这一行顶到最前面，不滑一下就是硬跳
                reorderFrom = snapshotTops();
                // 重渲染但**不动页码**：已经翻了 3 页的列表，不该因为点了一下标记就缩回第一页
                renderList();
            }
        });
        return t;
    }

    private void styleToggle(TextView t, boolean on) {
        if (on) {
            t.setBackgroundResource(R.drawable.bg_tag_on_exclude);
            t.setTextColor(Views.color(this, R.color.surface));
        } else {
            t.setBackgroundResource(R.drawable.bg_tag_off);
            t.setTextColor(Views.color(this, R.color.ink_faint));
        }
    }

    @Override
    protected void onDestroy() {
        if (db != null) {
            db.close();
        }
        super.onDestroy();
    }
}
