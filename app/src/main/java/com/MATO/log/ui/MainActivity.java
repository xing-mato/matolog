package com.MATO.log.ui;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import com.MATO.log.R;
import com.MATO.log.data.Attachment;
import com.MATO.log.data.DbHelper;
import com.MATO.log.data.Event;
import com.MATO.log.service.RecordService;
import com.MATO.log.util.AttachmentStore;
import com.MATO.log.util.DateUtil;
import com.MATO.log.util.Prefs;
import com.MATO.log.util.Prefs;
import com.MATO.log.util.Storage;

import java.util.List;

/**
 * 主界面：记录 + 按天 / 月 / 年检索。
 * 三层跨度共用同一个容器（OnyxHost），点进去就是二级界面。
 * （1.3.5 删掉了「周」这一层，层级关系见 {@link Views}。）
 */
public class MainActivity extends BaseActivity
        implements OnyxHost.Listener, LevelFactory, FileTransfer.Callback {

    private static final int REQ_EXPORT = FileTransfer.REQ_EXPORT;
    private static final int REQ_IMPORT = 102;
    private static final int REQ_EDIT = 103;

    private DbHelper db;
    private OnyxHost host;
    private LinearLayout segmentBar;
    /**
     * 顶部三档的顺序（1.3.5：「周」已按需求删掉）。
     * 段栏按钮的下标与它一一对应 —— 别再用「下标恰好等于层级值」那种巧合，
     * 层级值前移过一次（删周时 月/年 从 2/3 挪到 1/2），巧合是会断的。
     */
    private static final int[] SEGMENT_LEVELS =
            {Views.LEVEL_DAY, Views.LEVEL_MONTH, Views.LEVEL_YEAR};

    private final Button[] segments = new Button[SEGMENT_LEVELS.length];
    private View fab;
    private List<Event> pendingImport;
    private boolean pendingExportRecovery;
    /** 「新建」当前是否可见，用来决定要不要放出现动画 */
    private boolean fabShown;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        db = new DbHelper(getApplicationContext());

        segmentBar = findViewById(R.id.segment_bar);
        buildSegments();
        // 用自绘容器替换布局里的占位 FrameLayout
        View content = findViewById(R.id.content_frame);
        ViewGroup parent = (ViewGroup) content.getParent();
        ViewGroup.LayoutParams lp = content.getLayoutParams();
        parent.removeView(content);

        host = new OnyxHost(this);
        host.setId(R.id.content_frame);
        host.setLayoutParams(lp);
        host.setFactory(this);
        host.setDb(db);
        host.setListener(this);
        parent.addView(host);

        // 「新建」只在「日」页面出现，别的跨度下没有它（见 onScopeChanged）
        fab = findViewById(R.id.fab);
        fab.setVisibility(View.GONE);
        fab.setAlpha(0f);
        fab.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openEditor(0, defaultTimeFor(host.currentDepth(), host.getAnchor()));
            }
        });
        findViewById(R.id.btn_search).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, SearchActivity.class));
            }
        });
        findViewById(R.id.btn_more).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showMenu();
            }
        });

        // 容器在 attach 之后才会搭好第一层视图，这里补一次初始渲染
        host.post(new Runnable() {
            @Override
            public void run() {
                host.refresh();
                applyFabState();
            }
        });

        // 说明：这里原本会弹「设置应用锁」的引导页。1.3.0-b 起移除 ——
        // 绝大多数手机系统已自带应用锁，应用再拦一道是重复的，而且全新安装时
        // 第一眼看到的是口令页而不是记录本，反而挡住了主要用途。
        // （导出的加密口令是另一回事，它拦的是「把记录带走」这条出口，仍然保留。）
    }

    @Override
    protected void onResume() {
        super.onResume();
        maybeRecoverExport();
        // 用户开过自动记录，就把记录服务拉起来（幂等，已经跑着不会重复启动）。
        // 放在这里而不是只靠开机广播：后台启动前台服务在 Android 12+ 会被系统拒绝，
        // 「用户正打开着界面」是最可靠、最不会被拦的启动时机。
        if (Prefs.autoRecord(this)) {
            RecordService.start(this);
        }
        // 回到界面时重新读一次库。
        //
        // 必须有这一步：自动记录是在后台往库里写的，用户从别的应用切回来时，
        // 屏幕上还是他离开时的那个列表 —— 会让人以为「根本没记上」。
        // 而且这个刷新同时会把「正在积累、还没结束」的那条的最新时长显示出来。
        refreshOnReturn();
    }

    /** 回到前台后刷新检索界面（容器可能还没搭好，交给它自己 post） */
    private void refreshOnReturn() {
        if (host == null) {
            return;
        }
        host.post(new Runnable() {
            @Override
            public void run() {
                if (host != null) {
                    host.refresh();
                }
            }
        });
    }

    /** vivo 等系统会在切后台约一秒内回收进程，导出可能没落盘；回到前台时补问一次 */
    private void maybeRecoverExport() {
        if (pendingExportRecovery || !FileTransfer.hasPendingExport(this)) {
            return;
        }
        pendingExportRecovery = true;
        FileTransfer.recoverPendingExport(this, this);
    }

    // ---------------- 跨度切换 ----------------

    private void buildSegments() {
        segmentBar.removeAllViews();
        // 1.3.5：三档 —— 日 / 月 / 年（「周」这一档已按需求删掉）
        int[] labels = {R.string.level_day, R.string.level_month, R.string.level_year};
        for (int i = 0; i < SEGMENT_LEVELS.length; i++) {
            final int lv = SEGMENT_LEVELS[i];
            Button b = new Button(this);
            b.setText(labels[i]);
            b.setTextAppearance(this, R.style.Widget_MATOlog_Segment);
            b.setBackgroundResource(R.drawable.seg_background);
            b.setAllCaps(false);
            b.setPadding(0, 0, 0, 0);
            b.setMinWidth(0);
            b.setMinimumWidth(0);
            b.setMinHeight(0);
            b.setMinimumHeight(0);
            b.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    host.setLevel(lv);
                    applySegmentState();
                }
            });
            LinearLayout.LayoutParams lp =
                    new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
            lp.rightMargin = i < SEGMENT_LEVELS.length - 1 ? Views.dp(this, 2) : 0;
            segmentBar.addView(b, lp);
            segments[i] = b;
        }
    }

    private void applySegmentState() {
        // 高亮的是「实际正在看的跨度」：从「月」点进某一天时，真正在看的是「日」
        int shown = host == null ? 0 : host.currentDepth();
        for (int i = 0; i < segments.length; i++) {
            Button b = segments[i];
            if (b == null) {
                continue;
            }
            boolean on = SEGMENT_LEVELS[i] == shown;
            b.setSelected(on);
            b.setBackgroundResource(on ? R.drawable.seg_background_selected : R.drawable.seg_background);
            b.setTextColor(Views.color(this, on ? R.color.ink : R.color.ink_soft));
        }
    }

    @Override
    public LevelView make(int level, OnyxHost h) {
        switch (level) {
            case Views.LEVEL_DAY:
                return new DayView(h);
            case Views.LEVEL_YEAR:
                return new YearView(h);
            case Views.LEVEL_MONTH:
            default:
                return new MonthView(h);
        }
    }

    // ---------------- OnyxHost.Listener ----------------

    @Override
    public void onScopeChanged(int level, long anchor) {
        applySegmentState();
        applyFabState();
    }

    /**
     * 「新建」只在停在「日」页面时出现。
     *
     * 为什么要这样：新建要写死一个「哪一天」，而月 / 年这两层都是范围，
     * 在没有指定哪一天的情况下拿它们当默认日期，记录就会落到用户没预期的日子里。
     * 从年 → 月 → 日一路点进来（或者直接切到「日」）落到日页面时，
     * 按钮按滑入 + 淡入出现，不是硬生生地闪现。
     */
    private void applyFabState() {
        if (fab == null || host == null) {
            return;
        }
        // 用实际所在层判定：从「月」点进某一天后在看的就是「日」，按钮该出现
        boolean show = host.currentDepth() == Views.LEVEL_DAY;
        if (show == fabShown) {
            return;
        }
        fabShown = show;
        fab.animate().cancel();
        if (show) {
            // 轻微过冲再收回：按键是「弹」出来的，不是硬切出来
            fab.setVisibility(View.VISIBLE);
            fab.setAlpha(0f);
            fab.setTranslationY(Views.dp(this, 16));
            fab.setScaleX(0.9f);
            fab.setScaleY(0.9f);
            fab.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(Motion.ENTER_MS)
                    .setInterpolator(Motion.OVERSHOOT)
                    .start();
        } else {
            // 收起时干脆一点，别拖
            fab.animate()
                    .alpha(0f)
                    .translationY(Views.dp(this, 10))
                    .scaleX(0.94f)
                    .scaleY(0.94f)
                    .setDuration(Motion.EXIT_MS)
                    .setInterpolator(Motion.ACCELERATE)
                    .withEndAction(new Runnable() {
                        @Override
                        public void run() {
                            if (!fabShown) {
                                fab.setVisibility(View.GONE);
                            }
                        }
                    })
                    .start();
        }
    }

    @Override
    public void onEventClick(Event e) {
        openEditor(e.id, e.time);
    }

    /** 长按条目：只弹「编辑」「删除」两个按钮 */
    @Override
    public void onEventLongClick(final Event e) {
        Choices.show(this, null, null, new CharSequence[]{
                getString(R.string.action_edit),
                getString(R.string.action_delete)
        }, new Choices.OnPick() {
            @Override
            public void onPick(int index) {
                if (index == 0) {
                    openEditor(e.id, e.time);
                } else {
                    confirmDelete(e);
                }
            }
        });
    }

    /** 删除前的二次确认；带着附件时再问一次文件怎么处理 */
    private void confirmDelete(final Event e) {
        final List<Attachment> files = db.queryAttachments(e.id);
        if (files.isEmpty()) {
            Motion.showBuilder(new AlertDialog.Builder(this)
                    .setTitle(R.string.delete_confirm_title)
                    .setMessage(e.text)
                    .setPositiveButton(R.string.action_delete, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            deleteEvent(e, false, files);
                        }
                    })
                    .setNegativeButton(R.string.action_cancel, null));
            return;
        }
        Motion.showBuilder(new AlertDialog.Builder(this)
                .setTitle(R.string.delete_attach_title)
                .setMessage(getString(R.string.delete_attach_msg, files.size()))
                .setPositiveButton(R.string.delete_attach_together, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        deleteEvent(e, true, files);
                    }
                })
                .setNeutralButton(R.string.delete_attach_keep, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        deleteEvent(e, false, files);
                    }
                })
                .setNegativeButton(R.string.action_cancel, null));
    }

    /**
     * @param withFiles true = 附件文件一起删；false = 只摘掉归属，文件留到「附带文件」页
     */
    private void deleteEvent(Event e, boolean withFiles, List<Attachment> files) {
        if (withFiles) {
            for (Attachment a : files) {
                AttachmentStore.deleteFile(this, a);
            }
            db.deleteAttachmentsOf(e.id);
        } else if (!files.isEmpty()) {
            db.detachAttachmentsOf(e.id);
        }
        db.delete(e.id);
        host.refresh();
        Toast.makeText(this, withFiles || files.isEmpty()
                ? getString(R.string.edit_deleted)
                : getString(R.string.delete_attach_kept_hint), Toast.LENGTH_SHORT).show();
    }

    // ---------------- 新建 / 编辑 ----------------

    private void openEditor(long id, long defaultTime) {
        Intent it = new Intent(this, EditEventActivity.class);
        if (id > 0) {
            it.putExtra(EditEventActivity.EXTRA_ID, id);
        } else {
            it.putExtra(EditEventActivity.EXTRA_TIME, defaultTime);
        }
        startActivityForResult(it, REQ_EDIT);
    }

    /**
     * 新建时的默认时间。
     *
     * 「新建」只在「日」页面存在（月 / 年没有它），所以这里永远有一个明确的「哪一天」：
     * - 看的就是今天：精准取当前系统时间（含时分）；
     * - 看的是别的一天：沿用那一天的此刻时分，别把记录塞到别的日期上。
     */
    private long defaultTimeFor(int level, long anchor) {
        long now = System.currentTimeMillis();
        if (level != Views.LEVEL_DAY) {
            return now;
        }
        long viewing = DateUtil.startOfDay(anchor);
        if (viewing == DateUtil.startOfToday()) {
            return now;
        }
        // 补记：取该天 + 此刻的时分秒
        java.util.Calendar src = java.util.Calendar.getInstance();
        src.setTimeInMillis(now);
        java.util.Calendar dst = java.util.Calendar.getInstance();
        dst.setTimeInMillis(viewing);
        dst.set(java.util.Calendar.HOUR_OF_DAY, src.get(java.util.Calendar.HOUR_OF_DAY));
        dst.set(java.util.Calendar.MINUTE, src.get(java.util.Calendar.MINUTE));
        dst.set(java.util.Calendar.SECOND, src.get(java.util.Calendar.SECOND));
        dst.set(java.util.Calendar.MILLISECOND, 0);
        return dst.getTimeInMillis();
    }

    // ---------------- 结果回调 ----------------

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        switch (requestCode) {
            case REQ_EDIT:
                if (resultCode == RESULT_OK) {
                    host.refresh();
                }
                break;

            case REQ_EXPORT:
                FileTransfer.onExportPicked(this, resultCode, data, this);
                break;

            case FileTransfer.REQ_EXPORT_RETRY:
                pendingExportRecovery = false;
                FileTransfer.onRetryResult(this, resultCode, data, this);
                break;

            case REQ_IMPORT:
                if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                    FileTransfer.importFrom(this, data.getData(), this);
                }
                break;

            default:
                break;
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == FileTransfer.REQ_WRITE_PERMISSION) {
            FileTransfer.onWritePermissionResult(this, grantResults, this);
        }
    }

    // ---------------- FileTransfer.Callback ----------------

    @Override
    public void onImportFailed() {
        Toast.makeText(this, R.string.backup_import_failed, Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onImported(List<Event> events) {
        pendingImport = events;
        FileTransfer.askImportMode(this, events, this);
    }

    @Override
    public void onImportModeChosen(int mode) {
        if (pendingImport == null) {
            return;
        }
        // 覆盖之前先自动留一份当前数据的本机备份
        Storage.writeBackup(this, db.queryAll(), db);
        int n;
        if (mode == 2) {
            n = db.replaceAll(pendingImport);
        } else if (mode == 0) {
            int c = 0;
            for (Event e : pendingImport) {
                if (!db.existsSame(e.time, e.text)) {
                    db.insert(e);
                    if (e.attachments != null && !e.attachments.isEmpty()) {
                        db.insertAttachments(e.id, e.attachments);
                    }
                    c++;
                }
            }
            n = c;
        } else {
            n = db.insertAll(pendingImport);
        }
        pendingImport = null;
        host.refresh();
        Toast.makeText(this, getString(R.string.backup_imported, n), Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onExported() {
        Toast.makeText(this, R.string.backup_done, Toast.LENGTH_SHORT).show();
    }

    // ---------------- 菜单 ----------------

    private void showMenu() {
        View anchor = findViewById(R.id.btn_more);
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add(Menu.NONE, 1, 1, R.string.menu_export);
        menu.getMenu().add(Menu.NONE, 2, 2, R.string.menu_import);
        menu.getMenu().add(Menu.NONE, 3, 3, R.string.menu_backup);
        menu.getMenu().add(Menu.NONE, 6, 4, R.string.menu_attachments);
        // 菜单里不再有「应用锁」：系统自带应用锁已经覆盖了这个场景，
        // 应用再拦一道是重复的（详见 onCreate 里的说明）。导出加密仍在导出页里单独询问。
        menu.getMenu().add(Menu.NONE, 5, 6, R.string.menu_about);
        menu.getMenu().add(Menu.NONE, 7, 7, R.string.menu_settings);
        menu.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(MenuItem item) {
                switch (item.getItemId()) {
                    case 1:
                        startActivity(new Intent(MainActivity.this, ExportActivity.class));
                        return true;
                    case 2:
                        startActivityForResult(FileTransfer.pickImportSource(), REQ_IMPORT);
                        return true;
                    case 3:
                        startActivity(new Intent(MainActivity.this, BackupActivity.class));
                        return true;
                    case 5:
                        startActivity(new Intent(MainActivity.this, AboutActivity.class));
                        return true;
                    case 6:
                        startActivity(new Intent(MainActivity.this, AttachmentActivity.class));
                        return true;
                    case 7:
                        startActivity(new Intent(MainActivity.this, SettingsActivity.class));
                        return true;
                    default:
                        return false;
                }
            }
        });
        menu.show();
    }

    // ---------------- 返回键：逐层退出二级界面 ----------------

    @Override
    public void onBackPressed() {
        if (host != null && host.popStackAnimated()) {
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (db != null) {
            db.close();
        }
    }
}
