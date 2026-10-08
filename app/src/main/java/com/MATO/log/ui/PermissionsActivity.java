package com.MATO.log.ui;

import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.MATO.log.R;
import com.MATO.log.rec.Recorder;
import com.MATO.log.service.RecordService;
import com.MATO.log.util.AppInfo;
import com.MATO.log.util.Perm;
import com.MATO.log.util.Prefs;
import com.MATO.log.util.ShizukuBridge;
import com.MATO.log.util.ShizukuShell;

/**
 * 权限与保活引导。
 *
 * <p><b>写法上借鉴了参考应用的取舍</b>：它把用户直接丢进系统无障碍界面，
 * 靠一句话把路径说清（「无障碍 > 已下载的应用 > ❤️李跳跳」），不做冗长教程。
 * 这里沿用这个思路 —— 每一项给一句「为什么要它」，加一个直接跳转的入口。
 *
 * <p>两件事刻意做得和很多同类应用不同：
 * <ol>
 *   <li><b>必需项与建议项分开列。</b>「使用情况访问」不给就没法记录，是必需；
 *       电池优化白名单、自启动只是让它更稳，不给照样能用。混在一起列会让人
 *       以为全都要给，那是在吓唬用户。</li>
 *   <li><b>不索取用不上的权限。</b>没有悬浮窗、没有后台弹出、没有读取日志。</li>
 * </ol>
 */
public class PermissionsActivity extends BaseActivity {

    /** 向 Shizuku 申请授权用的请求码，取什么值都行，只要回调里对得上 */
    private static final int REQ_SHIZUKU = 8801;

    private LinearLayout body;
    private boolean shizukuListenerOn;

    /** 「自启动」那一行下面的说明。内容随「点了之后打到了哪一页」变，见 updateAutoStartNote */
    private TextView autoStartNote;

    /**
     * 上一次点「自启动」打到了哪一类页面。
     *
     * <p><b>为什么存成字段而不是只写进 TextView</b>：这一页的 {@code onResume} 会整页重建
     * （从系统设置回来时每一项的授予状态都可能变了），重建会把 TextView 换掉并重置成初始文案 ——
     * 于是用户点了自启动、从系统设置返回，看到的是「还没点过」那句，等于白点。
     * 试出来的结果要活过重建，所以存在这里，由 {@code build()} 回填。
     */
    private int autoStartLevel = -1;
    private String autoStartVendor = "";

    /**
     * Shizuku 授权的回调。
     *
     * <p>它的结果是**异步**回来的（用户在 Shizuku 的弹窗上点同意之后），
     * 所以必须注册监听；只看 requestPermission 的返回值会误判成失败。
     */
    private final rikka.shizuku.Shizuku.OnRequestPermissionResultListener shizukuResult =
            new rikka.shizuku.Shizuku.OnRequestPermissionResultListener() {
                @Override
                public void onRequestPermissionResult(int requestCode, int grantResult) {
                    if (requestCode != REQ_SHIZUKU) {
                        return;
                    }
                    if (grantResult == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                        Toast.show(PermissionsActivity.this, R.string.perm_shizuku_granted);
                    } else {
                        Toast.show(PermissionsActivity.this, R.string.perm_shizuku_denied);
                    }
                    // 授权完可能就能自动补上「使用情况访问」了，直接试一次
                    tryAutoGrantUsage();
                    build();
                }
            };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        ((TextView) findViewById(R.id.settings_title)).setText(R.string.perm_title);
        findViewById(R.id.btn_back).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        body = findViewById(R.id.settings_body);

        // 只在本机装了 Shizuku 时才注册监听：没装就没必要挂
        if (ShizukuBridge.installed(this)) {
            ShizukuBridge.addPermissionListener(shizukuResult);
            shizukuListenerOn = true;
        }
        build();
    }

    @Override
    protected void onDestroy() {
        if (shizukuListenerOn) {
            ShizukuBridge.removePermissionListener(shizukuResult);
            shizukuListenerOn = false;
        }
        super.onDestroy();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 从系统设置回来后，每一项的授予状态可能都变了，整页重建最省事也最准
        build();
    }

    private void build() {
        body.removeAllViews();

        // ---------- 必需 ----------
        // 这一段不挂小标题：权限页只有「必需」和「可选」两组，组内的权限名本身就说明
        // 了用途，再压一句「必须开启，否则记不了」只是重复。
        LinearLayout need = card();
        statusRow(need, getString(R.string.perm_usage), "",
                Perm.usageAccess(this), new Runnable() {
                    @Override
                    public void run() {
                        Perm.openUsageAccess(PermissionsActivity.this);
                    }
                }, true);
        need.addView(Views.rowDivider(this));
        statusRow(need, getString(R.string.perm_notify), "",
                Perm.notifications(this), new Runnable() {
                    @Override
                    public void run() {
                        Perm.openNotificationSettings(PermissionsActivity.this);
                    }
                }, false);
        body.addView(need);

        // ---------- 可选 ----------
        LinearLayout opt = card();
        statusRow(opt, getString(R.string.perm_battery), "",
                Perm.ignoringBattery(this), new Runnable() {
                    @Override
                    public void run() {
                        Perm.requestIgnoreBattery(PermissionsActivity.this);
                    }
                }, false);
        opt.addView(Views.rowDivider(this));

        statusRow(opt, getString(R.string.perm_autostart), "",
                Prefs.autoStartSet(this), new Runnable() {
                    @Override
                    public void run() {
                        onAutoStartClick();
                    }
                }, false);
        opt.addView(Views.rowDivider(this));

        body.addView(opt);

        // 自启动那一行的说明单列，因为它要随「点完到底打到了哪一页」变
        autoStartNote = Views.label(this, R.style.Text_Faint, "");
        autoStartNote.setLineSpacing(0, 1.35f);
        LinearLayout.LayoutParams np = Views.llp(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        np.topMargin = Views.dp(this, 10);
        np.leftMargin = Views.dp(this, 2);
        np.rightMargin = Views.dp(this, 2);
        autoStartNote.setLayoutParams(np);
        body.addView(autoStartNote);
        // 回填上一次的试跳结果，而不是无脑重置成初始文案 —— 见 autoStartLevel 的说明
        updateAutoStartNote(autoStartLevel, autoStartVendor);

        // ---------- Shizuku（装了才显示） ----------
        //
        // 这一段刻意做「存在即显示」而不是常驻：没装 Shizuku 的用户不该看到
        // 一个自己用不上的选项，更不该因此以为自己缺了什么。
        //
        // 小标题写「实验性功能」而不是「Shizuku」：它是可选增强，而且依赖第三方应用
        // 与它的授权状态，随时可能不可用 —— 摆成正式功能会让人以为缺了它就不完整。
        if (ShizukuBridge.installed(this)) {
            body.addView(Views.sectionHeader(this, getString(R.string.perm_section_shizuku), ""));

            LinearLayout sk = card();
            final boolean ready = ShizukuBridge.ready();
            statusRow(sk, getString(R.string.perm_shizuku), "",
                    ready, new Runnable() {
                        @Override
                        public void run() {
                            onShizukuClick(ready);
                        }
                    }, false);
            body.addView(sk);
        }

        // ---------- 当前实际状态 ----------
        body.addView(Views.sectionHeader(this, getString(R.string.perm_section_state), ""));

        LinearLayout state = card();
        TextView info = Views.label(this, R.style.Text_Muted, stateText());
        info.setLineSpacing(0, 1.35f);
        int pad = Views.dp(this, 16);
        info.setPadding(pad, pad, pad, pad);
        state.addView(info, Views.llp(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        body.addView(state);
    }

    /**
     * 点「自启动」那一行。
     *
     * <p><b>为什么点完还要改下面那行字</b>：这个入口没有任何官方 API，只能一家家试组件名，
     * 所以「点进去看到的是不是自启动页」本身是不确定的。与其让用户自己判断
     * 「这一页怎么没有自启动」，不如如实告诉他这次打到了哪里、接下来该找什么词。
     *
     * @param level 见 {@link Perm.AutoStartResult}；-1 表示还没点过
     * @param vendor 打到厂商自启动页时，那个页面对应的应用名
     */
    private void updateAutoStartNote(int level, String vendor) {
        if (autoStartNote == null) {
            return;
        }
        String text;
        if (level < 0) {
            // 还没点过：1.3.5 后续把原来那段「自启动决定两件事…」整段去掉了，
            // 没点过又没标记过就什么都不说；点过之后仍如实回显打到了哪一页。
            text = Prefs.autoStartSet(this)
                    ? getString(R.string.perm_autostart_note_done)
                    : "";
        } else if (level == Perm.AutoStartResult.LEVEL_VENDOR) {
            text = getString(R.string.perm_autostart_note_vendor, vendor);
        } else if (level == Perm.AutoStartResult.LEVEL_MANAGER) {
            text = getString(R.string.perm_autostart_note_manager);
        } else {
            text = getString(R.string.perm_autostart_note_detail);
        }
        // 空文案就把这一行收起来，免得留一条空白
        autoStartNote.setVisibility(text.isEmpty() ? View.GONE : View.VISIBLE);
        autoStartNote.setText(text);
    }

    private void onAutoStartClick() {
        // 已经标记过「设置好了」时，再点一次 = 取消标记。
        // 换机器、或者在系统里把自启动关掉之后，用户总得有地方把它改回来 ——
        // 否则那一行会一直停在「已开启」，而他什么也做不了。
        if (Prefs.autoStartSet(this)) {
            Prefs.setAutoStartSet(this, false);
            autoStartLevel = -1;
            autoStartVendor = "";
            build();
            Toast.show(this, getString(R.string.perm_autostart_unmarked));
            return;
        }

        Perm.AutoStartResult r = Perm.openAutoStart(this);
        autoStartLevel = r.level;
        autoStartVendor = r.vendor;
        // 记下「用户已经把自启动这件事走过一遍了」，那一行据此显示「已开启」。
        // 注意这**不是**系统状态的读数（读不到，见 Prefs.AUTOSTART_SET 的说明），
        // 只是让用户不必对着永远不变的「去开启」反复点
        Prefs.setAutoStartSet(this, true);
        updateAutoStartNote(r.level, r.vendor);
        build();
        if (!r.isVendorPage()) {
            // 没打到自启动页时给一句即时反馈：否则用户按下返回键回到这一页，
            // 只能看到下面那行小字变了，容易以为「点了没反应」
            Toast.show(this, getString(R.string.perm_autostart_fallback));
        }
    }

    /** 当前这套权限下，自动记录实际会是什么表现 —— 比一堆勾选状态有用 */
    private String stateText() {
        StringBuilder sb = new StringBuilder();
        boolean usage = Perm.usageAccess(this);

        if (!usage) {
            sb.append(getString(R.string.perm_state_cannot));
        } else {
            // 采样周期是用户可调的，这里跟着实际值说 —— 写死「按分钟、晚几十秒」
            // 在间隔改成 5 秒之后就成了错话
            long ms = Prefs.sampleMs(this);
            String every = (ms >= 60_000L && ms % 60_000L == 0)
                    ? getString(R.string.settings_interval_min, ms / 60_000L)
                    : getString(R.string.settings_interval_sec, ms / 1000L);
            sb.append(getString(R.string.perm_state_can));
            sb.append(getString(R.string.settings_interval_note, every));
        }

        Recorder rec = Recorder.get();
        if (rec != null) {
            // 只报「服务在不在」。之前这里还会接一句「当前状态：<前台应用>：<为什么没记>」，
            // 那句话随前台不断变化、又长，还容易被读成故障提示 —— 去掉。
            sb.append(getString(R.string.perm_state_running));
        }
        // 服务没在运行时原本还有一段「自动记录已开启，但服务当前没在运行。
        // 打开一次主界面就会把它拉起来。」—— 1.3.5 后续整段去掉：
        // 那是个打开一次主界面就自愈的瞬时状态，不值得占两行字。

        // 名单规模也顺带说一句：用户改了名单后能在这里确认生效范围
        com.MATO.log.data.DbHelper db = new com.MATO.log.data.DbHelper(this);
        int ex = db.countRule(com.MATO.log.data.DbHelper.RULE_EXCLUDE);
        if (ex > 0) {
            sb.append(getString(R.string.perm_state_rules, ex));
        }
        return sb.toString();
    }

    /**
     * 一行：左边标题 + 说明，右边是「已开启 / 去开启」。
     *
     * <p>{@code why} 传空串时不建那一行说明 —— 否则会留下一条带间距的空白，
     * 看起来像漏渲染了什么。
     *
     * @param required true = 必需项，未授予时右侧用主色强调
     */
    private void statusRow(LinearLayout parent, String title, String why, boolean on,
                           final Runnable onClick, boolean required) {
        LinearLayout row = Views.row(this);
        int pad = Views.dp(this, 16);
        row.setPadding(pad, Views.dp(this, 13), pad, Views.dp(this, 13));
        row.setBackgroundResource(R.drawable.ripple_round);

        LinearLayout texts = Views.column(this);
        TextView t = Views.label(this, R.style.Text_Body, title);
        texts.addView(t);
        if (why != null && !why.isEmpty()) {
            TextView w = Views.label(this, R.style.Text_Faint, why);
            w.setLineSpacing(0, 1.3f);
            LinearLayout.LayoutParams wp = Views.llp(LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            wp.topMargin = Views.dp(this, 3);
            w.setLayoutParams(wp);
            texts.addView(w);
        }
        row.addView(texts, Views.llp(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView mark = Views.label(this, R.style.Text_Muted,
                getString(on ? R.string.perm_mark_on : R.string.perm_mark_go));
        mark.setTextColor(on
                ? Views.color(this, R.color.ink_faint)
                : Views.color(this, required ? R.color.primary : R.color.ink_soft));
        LinearLayout.LayoutParams mp = Views.llp(LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        mp.leftMargin = Views.dp(this, 10);
        mark.setLayoutParams(mp);
        row.addView(mark);

        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // 已经开启的项也允许点：有些 ROM 点进去能改细节，
                // 而且「点了没反应」比「多跳一次」更让人困惑
                onClick.run();
            }
        });

        parent.addView(row, Views.llp(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
    }

    private LinearLayout card() {
        LinearLayout c = Views.card(this);
        c.setLayoutParams(Views.llp(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        return c;
    }

    /** 卡片下面那行小字说明 */
    private void note(String text) {
        TextView t = Views.label(this, R.style.Text_Faint, text);
        t.setLineSpacing(0, 1.35f);
        LinearLayout.LayoutParams p = Views.llp(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        p.topMargin = Views.dp(this, 10);
        p.leftMargin = Views.dp(this, 2);
        p.rightMargin = Views.dp(this, 2);
        t.setLayoutParams(p);
        body.addView(t);
    }

    /**
     * 点 Shizuku 那一行。
     *
     * <p>三种情况的出路不一样，分开处理而不是笼统地弹一句「不可用」：
     * 服务没起来 → 打开 Shizuku 应用让用户自己启；没授权 → 发起授权申请；
     * 已经可用 → 直接尝试把「使用情况访问」补上。
     */
    private void onShizukuClick(boolean ready) {
        if (ready) {
            tryAutoGrantUsage();
            return;
        }
        if (!ShizukuBridge.serviceAlive()) {
            ShizukuBridge.openApp(this);
            return;
        }
        if (ShizukuBridge.needGrant()) {
            if (!ShizukuBridge.requestPermission(REQ_SHIZUKU)) {
                Toast.show(this, R.string.perm_shizuku_request_failed);
            }
            return;
        }
        if (ShizukuBridge.isPreV11()) {
            ShizukuBridge.openApp(this);
            return;
        }
        // 其余情况（服务在线、也已授权，但 ready() 仍为 false）：把状态如实说出来
        Toast.show(this, ShizukuBridge.status(this));
    }

    /**
     * 用 Shizuku 把「使用情况访问」直接开上，省掉用户去系统设置里翻。
     *
     * <p>做不到就如实说做不到，并把人引到系统设置 —— 不假装成功，
     * 因为「以为开了、其实没开」比「明确说没开」糟糕得多。
     */
    private void tryAutoGrantUsage() {
        if (Perm.usageAccess(this)) {
            Toast.show(this, R.string.perm_usage_already);
            return;
        }
        if (!ShizukuBridge.ready()) {
            Toast.show(this, getString(R.string.perm_shizuku_not_ready));
            return;
        }
        boolean ok = ShizukuShell.grantUsageAccess(this);
        Toast.show(this, ok ? getString(R.string.perm_usage_granted)
                : getString(R.string.perm_usage_grant_failed));
        build();
    }}
