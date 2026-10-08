package com.MATO.log.ui;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.MATO.log.R;
import com.MATO.log.cap.CacheStore;
import com.MATO.log.data.DbHelper;
import com.MATO.log.rec.Recorder;
import com.MATO.log.service.RecordService;
import com.MATO.log.util.App;
import com.MATO.log.util.DarkTheme;
import com.MATO.log.util.DateUtil;
import com.MATO.log.util.ErrorLog;
import com.MATO.log.util.Lang;
import com.MATO.log.util.Perm;
import com.MATO.log.util.Prefs;
import com.MATO.log.util.Storage;

/**
 * 设置页。
 *
 * <p>按「这条设置影响哪一块」分组，而不是把所有开关堆在一起：
 * 外观 / 自动记录 / 名单与权限。每一行右边显示**当前值**，点进去才改 ——
 * 这样一屏就能看出现在是什么状态，不用逐个点开确认。
 *
 * <p>写法沿用工程既有风格：整页 LinearLayout 手搭、卡片用 {@link Views#card}、
 * 选项弹窗用 {@link Choices}（自绘，不碰系统列表弹窗，规避 vivo 上不渲染的坑）。
 */
public class SettingsActivity extends BaseActivity {

    private LinearLayout body;
    private DbHelper db;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        db = new DbHelper(getApplicationContext());
        findViewById(R.id.btn_back).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        body = findViewById(R.id.settings_body);
        DarkTheme.applyWindow(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 从权限页 / 名单页回来，当前值与状态都可能变了；整页重建最省事也最准
        build();
    }

    // ---------------- 页面 ----------------

    private void build() {
        body.removeAllViews();

        // ---------- 外观 ----------
        section(R.string.settings_section_look);
        LinearLayout look = card();
        valueRow(look, getString(R.string.settings_lang), Lang.label(this), new Runnable() {
            @Override
            public void run() {
                pickLang();
            }
        });
        look.addView(Views.rowDivider(this));
        valueRow(look, getString(R.string.settings_dark),
                Prefs.darkModeLabel(this, Prefs.darkMode(this)), new Runnable() {
                    @Override
                    public void run() {
                        pickDarkMode();
                    }
                });
        body.addView(look);

        // ---------- 自动记录 ----------
        section(R.string.settings_section_record);
        LinearLayout rec = card();

        valueRow(rec, getString(R.string.settings_auto_record),
                getString(Prefs.autoRecord(this)
                        ? R.string.settings_on : R.string.settings_off), new Runnable() {
                    @Override
                    public void run() {
                        toggleAutoRecord();
                    }
                });

        rec.addView(Views.rowDivider(this));
        // 两行都用「分钟」这一个口径显示：点进去填的也是分钟数，显示值与输入值同单位，
        // 不会出现「显示 1 小时、点进去要填 60」这种要心算的落差
        valueRow(rec, getString(R.string.settings_interval),
                intervalLabel(), new Runnable() {
                    @Override
                    public void run() {
                        pickInterval();
                    }
                });

        rec.addView(Views.rowDivider(this));
        valueRow(rec, getString(R.string.settings_min_keep),
                minKeepLabel(), new Runnable() {
                    @Override
                    public void run() {
                        pickMinKeep();
                    }
                });
        body.addView(rec);

        // 当前状态写在设置页上：用户不必进权限页才发现「原来没授权，所以什么都没记」
        note(recordStateText());

        // ---------- 名单与权限 ----------
        section(R.string.settings_section_more);
        LinearLayout more = card();
        int ex = db.countRule(DbHelper.RULE_EXCLUDE);
        valueRow(more, getString(R.string.settings_rules), rulesSummary(ex), new Runnable() {
            @Override
            public void run() {
                startActivity(new Intent(SettingsActivity.this, AppRulesActivity.class));
            }
        });

        more.addView(Views.rowDivider(this));
        valueRow(more, getString(R.string.settings_perm),
                getString(Perm.usageAccess(this)
                        ? R.string.settings_perm_ok : R.string.settings_perm_need), new Runnable() {
                    @Override
                    public void run() {
                        startActivity(new Intent(SettingsActivity.this, PermissionsActivity.class));
                    }
                });
        body.addView(more);

        // ---------- 数据与缓存 ----------
        section(R.string.settings_section_data);
        LinearLayout data = card();
        valueRow(data, getString(R.string.settings_cache),
                CacheStore.human(CacheStore.sizeBytes(this)), new Runnable() {
                    @Override
                    public void run() {
                        startActivity(new Intent(SettingsActivity.this, CacheActivity.class));
                    }
                });
        body.addView(data);

        // ---------- 诊断 ----------
        // 「导出报错日志」故意做成**出了问题才能用**：应用还没记录到任何异常时，
        // 那一行只显示「暂无可导出内容」，点它也只说一句，不生成任何文件。
        // 一个随时能导、导出来却总是空文件的按钮，会让用户白跑一趟还以为是应用又坏了。
        final int errs = ErrorLog.count(this);
        section(R.string.settings_section_diag);
        LinearLayout diag = card();
        valueRow(diag, getString(R.string.settings_errorlog),
                errs > 0
                        ? getString(R.string.settings_errorlog_n, errs, ErrorLog.lastLabel(this))
                        : getString(R.string.settings_errorlog_none),
                new Runnable() {
                    @Override
                    public void run() {
                        exportErrorLog();
                    }
                });
        if (errs > 0) {
            diag.addView(Views.rowDivider(this));
            valueRow(diag, getString(R.string.settings_errorlog_clear), "", new Runnable() {
                @Override
                public void run() {
                    confirmClearErrorLog(errs);
                }
            });
        }
        body.addView(diag);
    }

    /**
     * 清空报错日志之前先问一次。
     *
     * <p><b>这一下必须确认。</b>那一行一点就把它删了，而它恰恰是「出了问题才有」的东西：
     * 顺手点掉之后，用户要等到**下一次**出问题才能再导出一份，中间这段时间等于白记。
     * 工程里别的地方（清缓存、删附件、删记录、清残留文件）都有二次确认，这里更该有。
     */
    private void confirmClearErrorLog(int n) {
        Motion.showBuilder(new AlertDialog.Builder(this)
                .setTitle(R.string.settings_errorlog_clear_title)
                .setMessage(getString(R.string.settings_errorlog_clear_msg, n,
                        ErrorLog.lastLabel(this)))
                .setPositiveButton(R.string.settings_errorlog_clear_ok,
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                ErrorLog.clear(SettingsActivity.this);
                                Toast.show(SettingsActivity.this,
                                        R.string.settings_errorlog_cleared);
                                build();
                            }
                        })
                .setNegativeButton(R.string.action_cancel, null));
    }

    /**
     * 导出诊断报告。
     *
     * <p><b>门槛在这里再判一次</b>，不只在画界面时判：上面那个条数是进入这一页时算的，
     * 这期间日志可能被「清空」按掉。只在画界面时判，就会留下「界面还亮着、
     * 点下去导出一个空文件」这条路 —— 而防空包恰恰是这一项需求。
     */
    private void exportErrorLog() {
        if (!ErrorLog.hasProblems(this)) {
            Toast.show(this, R.string.settings_errorlog_gate);
            build();
            return;
        }
        String report = ErrorLog.buildReport(this);
        String name = ErrorLog.reportFileName(System.currentTimeMillis());
        String where = Storage.writeToDownloads(this, name, Storage.toBytes(report), "text/plain");
        if (where == null || where.isEmpty()) {
            // 写不进去本身也是个真实问题，记一笔（于是门槛仍然亮着，用户可以再试）
            ErrorLog.record(this, "export", "诊断报告写不进「下载」目录");
            Toast.show(this, R.string.settings_errorlog_failed);
            build();
            return;
        }
        Toast.show(this, getString(R.string.export_saved_to, where));
        build();
    }

    /** 自动记录当前的实际状态 —— 一句话说清「有没有在记、为什么」 */
    /**
     * 自动记录当前的实际状态 —— 只说用户能据此采取行动的事。
     *
     * <p>1.3.5 后续：这里原先把「没开」「服务没在运行」也各写成一句话，
     * 现在都去掉了 —— 没开的时候开关本身就在右边写着「关」，再补一段解释是多余的；
     * 服务没在运行则是**打开一次主界面就会自愈**的瞬时状态，不值得占一段字。
     * 于是只剩「缺权限」这一条真的需要用户动手的状态。
     */
    private String recordStateText() {
        if (!Prefs.autoRecord(this)) {
            return "";
        }
        if (!Perm.usageAccess(this)) {
            return getString(R.string.settings_record_no_perm);
        }
        Recorder rec = Recorder.get();
        if (rec == null) {
            return "";
        }
        // 这句话必须跟着采样间隔走：写死「按分钟采样、晚几十秒」在间隔改成 5 秒之后
        // 就是在说假话 —— 而这一页正是用户用来确认「现在到底准不准」的地方
        return getString(R.string.settings_interval_note, intervalLabel());
    }

    private String rulesSummary(int ex) {
        return ex > 0 ? getString(R.string.settings_rules_n, ex)
                : getString(R.string.settings_rules_none);
    }

    /** 「每 N 秒」；整分钟时写成「每 N 分钟」，读起来顺眼些 */
    private String intervalLabel() {
        long ms = Prefs.sampleMs(this);
        if (ms >= 60_000L && ms % 60_000L == 0) {
            return getString(R.string.settings_interval_min, ms / 60_000L);
        }
        return getString(R.string.settings_interval_sec, ms / 1000L);
    }

    /** 阈值为 0 时不说「短于 0 分钟不计」——那等于没说 */
    private String minKeepLabel() {
        long min = Prefs.minKeepMs(this) / 60_000L;
        return min <= 0 ? getString(R.string.settings_min_keep_all)
                : getString(R.string.settings_min_keep_n, min);
    }

    // ---------------- 各项操作 ----------------

    /**
     * 应用内语言。
     *
     * <p>改完和改配色一样要**重建所有活着的界面**：语言是在 Context 附着那一刻定下的，
     * 已经存在的界面不会自己变。
     *
     * <p>这里比配色还多一步：{@link DateUtil} 里有一份静态的语言值（给拿不到
     * Context 的地方用），必须**先刷它、再重建**。顺序反了，重建出来的那一帧里
     * 日期还是旧语言写的。
     */
    private void pickLang() {
        final String[] tags = new String[Lang.TAGS.length + 1];
        tags[0] = Lang.FOLLOW;
        System.arraycopy(Lang.TAGS, 0, tags, 1, Lang.TAGS.length);

        CharSequence[] labels = new CharSequence[tags.length];
        for (int i = 0; i < tags.length; i++) {
            labels[i] = langLabel(tags[i]);
        }
        Choices.show(this, getString(R.string.settings_lang), null, labels, new Choices.OnPick() {
            @Override
            public void onPick(int index) {
                Prefs.setLangTag(SettingsActivity.this, tags[index]);
                Lang.refresh(SettingsActivity.this);
                App.onModeChanged();
            }
        });
    }

    /**
     * 选项文案。
     *
     * <p>五种语言的名字**一律用它自己的写法**（日本語、한국어、English），
     * 在任何语言下都一样 —— 用户找不到自己母语时，最不想看到的就是
     * 一排用他看不懂的语言写成的语言名。只有「跟随系统」这一项跟着翻。
     */
    private String langLabel(String tag) {
        switch (tag) {
            case "zh-CN":
                return getString(R.string.lang_zh_cn);
            case "zh-TW":
                return getString(R.string.lang_zh_tw);
            case "ja":
                return getString(R.string.lang_ja);
            case "ko":
                return getString(R.string.lang_ko);
            case "en":
                return getString(R.string.lang_en);
            default:
                return getString(R.string.lang_follow);
        }
    }

    /**
     * 深色模式三档。
     *
     * <p>给三档而不是一个开关：需求要的是「可开关」，但只给开/关两个值的话，
     * 用户想回到「跟着系统走」就没有入口了 —— 手机在白天/夜里自动切换是很多人的
     * 默认预期，砍掉它会变成「应用跟系统打架」。
     */
    private void pickDarkMode() {
        final int[] modes = {Prefs.DARK_FOLLOW, Prefs.DARK_ON, Prefs.DARK_OFF};
        CharSequence[] labels = new CharSequence[modes.length];
        for (int i = 0; i < modes.length; i++) {
            labels[i] = Prefs.darkModeLabel(this, modes[i]);
        }
        Choices.show(this, getString(R.string.settings_dark), null, labels, new Choices.OnPick() {
            @Override
            public void onPick(int index) {
                Prefs.setDarkMode(SettingsActivity.this, modes[index]);
                // 配色是在 Context 附着时定下的，已存在的界面不会自己变。
                // 这一步会把**所有活着的界面**重建（含这一页与它下面的主界面），
                // 所以这里不再单独 recreate —— 否则这一页会被重建两次。
                App.onModeChanged();
            }
        });
    }

    /**
     * 自动记录总开关。
     *
     * <p>开启前先检查必需权限：没有「使用情况访问」就开，用户会看到「已开启」
     * 但什么都没记下来 —— 那比不让开更糟。所以这里直接把人引到权限页。
     */
    private void toggleAutoRecord() {
        if (Prefs.autoRecord(this)) {
            Prefs.setAutoRecord(this, false);
            RecordService.stop(this);
            Toast.show(this, R.string.settings_record_stopped);
            build();
            return;
        }
        if (!Perm.usageAccess(this)) {
            Choices.show(this, getString(R.string.settings_need_perm_title),
                    getString(R.string.settings_need_perm_msg),
                    new CharSequence[]{getString(R.string.settings_go_grant),
                            getString(R.string.action_cancel)},
                    new Choices.OnPick() {
                        @Override
                        public void onPick(int index) {
                            if (index == 0) {
                                startActivity(new Intent(SettingsActivity.this,
                                        PermissionsActivity.class));
                            }
                        }
                    });
            return;
        }
        Prefs.setAutoRecord(this, true);
        RecordService.start(this);
        Toast.show(this, R.string.settings_record_started);
        build();
    }

    /**
     * 采样间隔：自己填**秒数**。
     *
     * <p>1.3.1 把固定档位改成了自填，方向是对的；1.3.4 把单位从分钟换成秒 ——
     * 因为默认值降到了 5 秒（常驻通知要跟得上应用切换），分钟这个粒度表达不了。
     * 输入框收的是秒，5 ~ 3600；显示时 60 的整数倍按分钟写，读起来更顺眼。
     *
     * <p>上下限放在 {@link Prefs} 里统一夹住，界面上的输入框只负责
     * 「取到一个能用的整数」——判定散在两处早晚会不一致。
     */
    private void pickInterval() {
        long cur = Prefs.sampleMs(this) / 1000L;
        inputNumber(R.string.settings_interval, R.string.settings_interval_msg,
                cur, Prefs.SAMPLE_MIN / 1000L, Prefs.SAMPLE_MAX / 1000L,
                R.string.settings_seconds_unit, R.string.settings_seconds_range, null,
                new OnNumber() {
                    @Override
                    public void onNumber(long seconds) {
                        Prefs.setSampleMs(SettingsActivity.this, seconds * 1000L);
                        reloadRecorder();
                        build();
                    }
                });
    }

    /**
     * 「短于多久不计」：自己填分钟数，0 = 全部都记。
     *
     * <p>0 这一档是需求原文之外的既有语义（1.3 就有「全部都记」），不能在改成自填时丢掉 ——
     * 丢掉之后「碎片也想留下」就没有入口了。所以留成特殊值，界面上再写一句。
     */
    private void pickMinKeep() {
        long cur = Prefs.minKeepMs(this) / 60_000L;
        inputNumber(R.string.settings_min_keep, R.string.settings_min_keep_msg,
                cur, 0L, Prefs.MIN_KEEP_MAX / 60_000L,
                R.string.settings_minutes_unit, R.string.settings_minutes_range,
                getString(R.string.settings_min_keep_zero_hint), new OnNumber() {
                    @Override
                    public void onNumber(long minutes) {
                        Prefs.setMinKeepMs(SettingsActivity.this, minutes * 60_000L);
                        reloadRecorder();
                        build();
                    }
                });
    }

    /** 自定义数字输入的回调 */
    private interface OnNumber {
        void onNumber(long value);
    }

    /**
     * 一个「填个数」的输入弹窗。单位与范围由调用方给 ——
     * 采样间隔用秒（默认 5 秒，分钟这个粒度表达不了），「短于多久不计」用分钟。
     *
     * <p>用 AlertDialog 装一个 EditText，而不是 {@link Choices}：Choices 是「一排选项」，
     * 没有输入位。这里要的是取一个数字，选项列表反而表达不了。
     *
     * <p>校验放在确定键里而不是实时：实时校验会在用户还在打字时就把确定键变灰，
     * 而「3」打到一半是「3」、要打「30」时中间那一瞬并不该被拦。
     *
     * @param zeroHint 非空时，在输入框上方多写一句（给「0 = 全部都记」这类特殊值用）
     */
    private void inputNumber(int titleRes, int msgRes, long current, final long min, final long max,
                             int unitRes, int rangeRes, String zeroHint, final OnNumber cb) {
        LinearLayout box = Views.column(this);
        int pad = Views.dp(this, 22);
        box.setPadding(pad, Views.dp(this, 6), pad, 0);

        if (zeroHint != null && !zeroHint.isEmpty()) {
            TextView hint = Views.label(this, R.style.Text_Faint, zeroHint);
            hint.setLineSpacing(0, 1.3f);
            box.addView(hint, Views.llp(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        LinearLayout line = Views.row(this);
        line.setGravity(android.view.Gravity.CENTER_VERTICAL);

        final EditText input = new EditText(this);
        input.setTextAppearance(this, R.style.Widget_MATOlog_EditText);
        input.setBackgroundResource(R.drawable.bg_field);
        input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        input.setSingleLine(true);
        input.setGravity(android.view.Gravity.CENTER);
        input.setText(String.valueOf(current));
        // 进来就把原值全选上：绝大多数情况是「改一个数」，全选之后直接打就行
        input.setSelectAllOnFocus(true);
        // 上下内边距必须对称且小：设成 12dp 时（其它页面的输入框用这个值）在**弹窗**里
        // 显得文字明显偏下 —— 弹窗的高度是按内容算的，输入框上方那一段空白就特别扎眼
        input.setPadding(Views.dp(this, 14), Views.dp(this, 9), Views.dp(this, 14), Views.dp(this, 9));
        LinearLayout.LayoutParams ip = Views.llp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        input.setLayoutParams(ip);
        line.addView(input);

        TextView unit = Views.label(this, R.style.Text_Muted, getString(unitRes));
        LinearLayout.LayoutParams up = Views.llp(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        up.leftMargin = Views.dp(this, 10);
        unit.setLayoutParams(up);
        line.addView(unit);

        box.addView(line, Views.llp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        // 先 create 再 dressDialog 再 show：Motion.showBuilder 里就是这个顺序，
        // 但它不把 dialog 交出来，而下面要拿确定键自己接管点击
        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(titleRes)
                .setMessage(msgRes)
                .setView(box)
                .setPositiveButton(R.string.action_ok, null)
                .setNegativeButton(R.string.action_cancel, null)
                .create();
        Motion.dressDialog(dialog);
        dialog.show();

        // 确定键自己接管：不合法就不关弹窗，把范围写在提示里。用 setPositiveButton 默认行为
        // 的话，点一下不管输入什么都先把弹窗关了，用户得从头再来一次
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                long n = parseNumber(input.getText() == null ? "" : input.getText().toString());
                if (n < min || n > max) {
                    Toast.show(SettingsActivity.this, getString(rangeRes, min, max));
                    return;
                }
                dialog.dismiss();
                cb.onNumber(n);
            }
        });
    }

    /** 把输入框里的字取成一个数。空、非数字、超长都当 0（再交给范围判断去拒绝） */
    private static long parseNumber(String s) {
        if (s == null) {
            return 0L;
        }
        String t = s.trim();
        if (t.isEmpty() || t.length() > 6) {
            return 0L;
        }
        try {
            return Long.parseLong(t);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    /** 改完设置让记录引擎立刻重新装载，不必重启服务 */
    private void reloadRecorder() {
        Recorder rec = Recorder.get();
        if (rec != null) {
            rec.reload();
        }
    }

    // ---------------- 小工具 ----------------

    private void section(int titleRes) {
        body.addView(Views.sectionHeader(this, getString(titleRes), ""));
    }

    private void note(String text) {
        // 空文案就整块不画：否则会留下一条带 10dp 上边距的空白，
        // 看起来像漏渲染了什么（这段文字现在有多个状态会返回空串）
        if (text == null || text.isEmpty()) {
            return;
        }
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

    private LinearLayout card() {
        LinearLayout c = Views.card(this);
        c.setLayoutParams(Views.llp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        return c;
    }

    /** 一行：左标题，右当前值 + 箭头，点整行都算 */
    private void valueRow(LinearLayout parent, String title, String value, final Runnable onClick) {
        LinearLayout row = Views.row(this);
        row.setPadding(Views.dp(this, 16), Views.dp(this, 14), Views.dp(this, 14), Views.dp(this, 14));
        row.setBackgroundResource(R.drawable.ripple_round);

        TextView t = Views.label(this, R.style.Text_Body, title);
        row.addView(t, Views.llp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView v = Views.label(this, R.style.Text_Muted, value);
        row.addView(v);

        ImageView arrow = new ImageView(this);
        arrow.setImageResource(R.drawable.ic_chevron_right);
        // ic_chevron_right 是白色图标，浅底上看不见，用 tint 拉成文字色
        arrow.setImageTintList(android.content.res.ColorStateList.valueOf(
                Views.color(this, R.color.ink_faint)));
        LinearLayout.LayoutParams ap = Views.llp(Views.dp(this, 16), Views.dp(this, 16));
        ap.leftMargin = Views.dp(this, 6);
        arrow.setLayoutParams(ap);
        row.addView(arrow);

        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                onClick.run();
            }
        });

        parent.addView(row, Views.llp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    @Override
    protected void onDestroy() {
        if (db != null) {
            db.close();
        }
        super.onDestroy();
    }
}
