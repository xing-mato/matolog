package com.MATO.log.ui;

import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextWatcher;
import android.text.style.BackgroundColorSpan;
import android.text.style.StyleSpan;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.MATO.log.R;
import com.MATO.log.data.DbHelper;
import com.MATO.log.data.Event;
import com.MATO.log.util.DateUtil;

import java.util.List;
import java.util.Locale;

/**
 * 主页关键词检索（1.3.5 新增）。
 *
 * <p>搜的是**全部记录**，不限于当前正在看的那个跨度 —— 「检索」要解决的问题是
 * 「我记得写过这件事，但忘了是哪天」，而按跨度搜只能搜到你已经知道在哪的那些。
 *
 * <p>结果按时间从新到旧，每条带上日期：日页面那种只写 {@code HH:mm} 的写法
 * 放到跨天的结果列表里，会变成一串分不清是哪天的时刻。
 *
 * <p>命中的正文片段加一层柔和底色。底色取 {@code primary_soft} ——
 * 它在 values 与 values-night 里各有一份，所以深色模式下不会出现
 * 「高亮块比正文还刺眼」。
 *
 * <p>点一条进编辑页，而不是跳回那天的列表：结果里已经写着完整正文和日期了，
 * 搜到之后想做的下一件事通常是看/改这一条，而不是在一个日列表里再找它一次。
 */
public class SearchActivity extends BaseActivity {

    private static final int REQ_EDIT = 201;

    /**
     * 打字停顿多久才去查库。
     *
     * <p>不是为了省那点查询 —— 是为了别每敲一个字就把整列结果重建一次，
     * 那会让列表在打字过程中不停闪。
     */
    private static final long DEBOUNCE_MS = 180L;

    private DbHelper db;
    private EditText input;
    private LinearLayout body;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable doSearch = new Runnable() {
        @Override
        public void run() {
            render();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_search);

        db = new DbHelper(getApplicationContext());

        findViewById(R.id.btn_back).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });

        body = findViewById(R.id.search_body);
        input = findViewById(R.id.search_input);

        input.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                scheduleSearch();
            }
        });

        // 输入法上的「搜索」键 = 立刻查，不等那 180ms
        input.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                    ui.removeCallbacks(doSearch);
                    render();
                    hideKeyboard();
                    return true;
                }
                return false;
            }
        });

        // 进来就聚焦并弹键盘：这一页唯一要做的事就是打字，让用户再点一下输入框是多余的
        input.requestFocus();
        input.post(new Runnable() {
            @Override
            public void run() {
                InputMethodManager im =
                        (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
                if (im != null) {
                    im.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT);
                }
            }
        });

        render();
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacks(doSearch);
        if (db != null) {
            db.close();
        }
        super.onDestroy();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        // 改完之后这条可能已经不再命中关键词（也可能本来就不命中），重查一次最省事
        if (requestCode == REQ_EDIT && resultCode == RESULT_OK) {
            render();
        }
    }

    // ---------------- 检索与呈现 ----------------

    private void scheduleSearch() {
        ui.removeCallbacks(doSearch);
        ui.postDelayed(doSearch, DEBOUNCE_MS);
    }

    private void render() {
        if (body == null || db == null) {
            return;
        }
        String kw = input.getText() == null ? "" : input.getText().toString().trim();
        body.removeAllViews();

        if (kw.isEmpty()) {
            centered(getString(R.string.search_empty_hint));
            return;
        }

        List<Event> hits = db.search(kw, DbHelper.SEARCH_LIMIT_DEFAULT);
        if (hits.isEmpty()) {
            centered(getString(R.string.search_no_result, kw));
            return;
        }

        // 条数放在最上面：检索完第一件想知道的事就是「有几条」
        TextView count = Views.label(this, R.style.Text_Faint,
                getString(R.string.search_count, hits.size()));
        LinearLayout.LayoutParams cp = Views.llp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        cp.leftMargin = Views.dp(this, 2);
        cp.bottomMargin = Views.dp(this, 8);
        count.setLayoutParams(cp);
        body.addView(count);

        LinearLayout card = Views.card(this);
        card.setLayoutParams(Views.llp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        for (int i = 0; i < hits.size(); i++) {
            if (i > 0) {
                Views.addChild(card, Views.rowDivider(this));
            }
            Views.addChild(card, resultRow(hits.get(i), kw));
        }
        Views.addChild(body, card);

        // 撞到上限就说一声。不说的话，用户会以为「一共就这么多」——
        // 而检索结果少一条，比查不出来更容易让人误判
        if (hits.size() >= DbHelper.SEARCH_LIMIT_DEFAULT) {
            footnote(getString(R.string.search_capped, DbHelper.SEARCH_LIMIT_DEFAULT));
        }
    }

    /** 一条结果：上行日期时刻，下行正文（命中处高亮） */
    private View resultRow(final Event e, String kw) {
        LinearLayout box = Views.column(this);
        int padH = Views.dp(this, 16);
        box.setPadding(padH, Views.dp(this, 13), padH, Views.dp(this, 13));
        box.setBackgroundResource(R.drawable.ripple_round);

        TextView when = Views.label(this, R.style.Text_Mono, whenText(e));
        when.setTextSize(12f);
        box.addView(when, Views.llp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView text = Views.label(this, R.style.Text_Body, "");
        text.setTextSize(15f);
        text.setLineSpacing(0, 1.25f);
        text.setText(highlight(e.displayTitle(), kw));
        LinearLayout.LayoutParams tp = Views.llp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        tp.topMargin = Views.dp(this, 3);
        text.setLayoutParams(tp);
        box.addView(text);

        box.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openEditor(e);
            }
        });
        return box;
    }

    /**
     * 结果里的时间。自动记录那条带上时段与时长，与日页面同一套写法；
     * 区别只在前面**一定要有日期** —— 结果跨很多天。
     */
    private String whenText(Event e) {
        String day = DateUtil.ymd(e.time);
        if (e.isAuto() && e.durationMs() > 0) {
            return day + " " + DateUtil.span(e.time, e.endMs)
                    + " · " + DateUtil.durationShort(e.durationMs());
        }
        return day + " " + DateUtil.hhmm(e.time);
    }

    /**
     * 把命中的片段标出来。
     *
     * <p>只标正文里命中的部分：如果这条是靠**应用名**命中的（{@code app_label}），
     * 正文里根本没有那个词，那就原样返回、不做标记 —— 硬标一个不存在的位置
     * 只会标错地方。
     */
    private CharSequence highlight(String text, String kw) {
        if (text == null) {
            return "";
        }
        if (text.isEmpty() || kw == null || kw.isEmpty()) {
            return text;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        String k = kw.toLowerCase(Locale.ROOT);
        int at = lower.indexOf(k);
        if (at < 0) {
            return text;
        }
        SpannableString s = new SpannableString(text);
        int bg = Views.color(this, R.color.primary_soft);
        while (at >= 0) {
            int end = at + k.length();
            s.setSpan(new BackgroundColorSpan(bg), at, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            s.setSpan(new StyleSpan(Typeface.BOLD), at, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            at = lower.indexOf(k, end);
        }
        return s;
    }

    private void openEditor(Event e) {
        Intent it = new Intent(this, EditEventActivity.class);
        it.putExtra(EditEventActivity.EXTRA_ID, e.id);
        startActivityForResult(it, REQ_EDIT);
    }

    private void hideKeyboard() {
        InputMethodManager im = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (im != null) {
            im.hideSoftInputFromWindow(input.getWindowToken(), 0);
        }
    }

    // ---------------- 小工具 ----------------

    /** 居中一段灰字：空输入、没结果都用它 */
    private void centered(String text) {
        TextView t = Views.label(this, R.style.Text_Faint, text);
        t.setLineSpacing(0, 1.4f);
        t.setGravity(Gravity.CENTER);
        int v = Views.dp(this, 36);
        t.setPadding(Views.dp(this, 8), v, Views.dp(this, 8), v);
        body.addView(t, Views.llp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    /** 卡片下面的一句说明 */
    private void footnote(String text) {
        TextView t = Views.label(this, R.style.Text_Faint, text);
        t.setLineSpacing(0, 1.35f);
        LinearLayout.LayoutParams p = Views.llp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        p.topMargin = Views.dp(this, 10);
        p.leftMargin = Views.dp(this, 2);
        p.rightMargin = Views.dp(this, 2);
        t.setLayoutParams(p);
        body.addView(t);
    }
}
