package com.MATO.log.ui;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.MATO.log.R;
import com.MATO.log.cap.CacheStore;
import com.MATO.log.util.DateUtil;

import java.io.File;

/**
 * 缓存目录。
 *
 * <p>需求原文是「需要一个单独的文件夹放置可能产生的缓存」。这里把它做成一个
 * 用户能看见、能定位、能自己清的页面 —— 而不是藏在私有目录里悄悄长大。
 *
 * <p>目录本身在应用专属外部目录下（{@code Android/data/<包名>/files/mato-cache/}），
 * 从 Android 10 起写它不需要任何存储权限；插上数据线或用文件管理器就能自己查看和删除，
 * 卸载应用时系统会一并清掉。具体的取舍见 {@link CacheStore} 的类注释。
 */
public class CacheActivity extends BaseActivity {

    private LinearLayout body;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        ((TextView) findViewById(R.id.settings_title)).setText(R.string.cache_title);
        findViewById(R.id.btn_back).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        body = findViewById(R.id.settings_body);
        build();
    }

    @Override
    protected void onResume() {
        super.onResume();
        build();
    }

    private void build() {
        body.removeAllViews();

        // ---------- 占用情况 ----------
        section(R.string.cache_section_usage);
        LinearLayout info = card();
        TextView t = Views.label(this, R.style.Text_Muted, usageText());
        t.setLineSpacing(0, 1.4f);
        int pad = Views.dp(this, 16);
        t.setPadding(pad, pad, pad, pad);
        info.addView(t, Views.llp(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        body.addView(info);

        // 路径单独显示并等宽字体：用户要照着它在文件管理器里找
        File root = CacheStore.root(this);
        LinearLayout path = card();
        LinearLayout.LayoutParams pt = Views.llp(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        pt.topMargin = Views.dp(this, 10);
        path.setLayoutParams(pt);
        TextView pv = Views.label(this, R.style.Text_Mono, root.getAbsolutePath());
        pv.setTextSize(11f);
        pv.setPadding(pad, Views.dp(this, 12), pad, Views.dp(this, 12));
        path.addView(pv, Views.llp(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        body.addView(path);

        // ---------- 操作 ----------
        section(R.string.cache_section_actions);
        LinearLayout acts = card();
        valueRow(acts, getString(R.string.cache_clear_all), "", new Runnable() {
            @Override
            public void run() {
                int n = CacheStore.clear(CacheActivity.this);
                Toast.show(CacheActivity.this, getString(R.string.cache_cleared_n, n));
                build();
            }
        });
        body.addView(acts);

        // 「自动清理」那一段随「自动整理」一起删掉了：
        // 它下面只有一个「整理日志记录内容摘要」的勾选，勾的是 AI 请求日志的内容，
        // 而 1.3.5 已经没有 AI 请求了（需求：删掉设置里所有关于 LLM 自动整理的内容）。
        // 留一个勾什么都没用的开关，比没有这个开关更糟。

        TextView foot = Views.label(this, R.style.Text_Faint, getString(R.string.cache_note));
        foot.setLineSpacing(0, 1.35f);
        LinearLayout.LayoutParams fp = Views.llp(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        fp.topMargin = Views.dp(this, 12);
        fp.leftMargin = Views.dp(this, 2);
        fp.rightMargin = Views.dp(this, 2);
        foot.setLayoutParams(fp);
        body.addView(foot);
    }

    /**
     * 占用情况。
     *
     * <p>分类说清楚，而不是只报一个总数：用户看到「缓存 12MB」时的第一反应是
     * 「什么东西占的、能不能删」，所以直接把各类摆出来 —— 而且明确说「删了不影响记录」。
     */
    private String usageText() {
        long tmp = CacheStore.size(CacheStore.tmpDir(this));

        StringBuilder sb = new StringBuilder();
        sb.append(getString(R.string.cache_tmp_label, CacheStore.human(tmp))).append("\n\n");
        sb.append(getString(R.string.cache_total, CacheStore.human(tmp)));
        sb.append(getString(R.string.cache_usage_note));
        return sb.toString();
    }

    // ---------------- 小工具 ----------------

    private void section(int titleRes) {
        body.addView(Views.sectionHeader(this, getString(titleRes), ""));
    }

    private LinearLayout card() {
        LinearLayout c = Views.card(this);
        c.setLayoutParams(Views.llp(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        return c;
    }

    private void valueRow(LinearLayout parent, String title, String value, final Runnable onClick) {
        LinearLayout row = Views.row(this);
        int pad = Views.dp(this, 16);
        row.setPadding(pad, Views.dp(this, 14), pad, Views.dp(this, 14));
        row.setBackgroundResource(R.drawable.ripple_round);

        TextView t = Views.label(this, R.style.Text_Body, title);
        row.addView(t, Views.llp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (value != null && !value.isEmpty()) {
            row.addView(Views.label(this, R.style.Text_Muted, value));
        }
        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onClick.run();
            }
        });
        parent.addView(row, Views.llp(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
    }
}
