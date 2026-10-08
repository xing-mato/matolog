package com.MATO.log.ui;

import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.DatePicker;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.MATO.log.R;
import com.MATO.log.data.Attachment;
import com.MATO.log.data.DbHelper;
import com.MATO.log.data.Event;
import com.MATO.log.util.AttachmentStore;
import com.MATO.log.util.DateUtil;
import com.MATO.log.util.EventCodec;
import com.MATO.log.util.SecurityHelper;
import com.MATO.log.util.Storage;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 导出的第一步：先选「导出哪些日期」，再选保存位置。
 *
 * 默认整个区间是全量导出（跟以前一样），也可以只导当天 / 本周 / 本月 / 本年，
 * 或者自己划一段起止日期。区间只在覆盖到有记录的日子时才是有效的，
 * 所以每次改动都会立刻统计条数并写在下面。
 *
 * 附件默认不跟着走（只留一份「这条记录有几个附件」的登记）；
 * 勾上「把附件内容一起带走」才会把文件本体内嵌进 JSON。
 */
public class ExportActivity extends BaseActivity implements FileTransfer.Callback {

    // 范围类型
    private static final int RANGE_TODAY = 0;
    private static final int RANGE_WEEK = 1;
    private static final int RANGE_MONTH = 2;
    private static final int RANGE_YEAR = 3;
    private static final int RANGE_ALL = 4;
    private static final int RANGE_CUSTOM = 5;

    private DbHelper db;
    private LinearLayout rangeList;
    private LinearLayout customBox;
    private CheckBox checkFiles;
    private CheckBox checkEncrypt;
    private TextView textFrom;
    private TextView textTo;
    private TextView textSummary;
    private Button btnExport;

    private int range = RANGE_ALL;
    private long customFrom;
    private long customTo;

    /** 本次导出的区间（左闭右开） */
    private long rangeFrom;
    private long rangeTo = Long.MAX_VALUE;
    /** 本次导出选中的事件（已带附件登记） */
    private List<Event> picked = new ArrayList<>();
    private int pickedAttachments;
    private boolean pickedMissingFiles;

    /** 保存位置选好之后的回执：请求码沿用 FileTransfer 的 */
    private static final int REQ_SAVE = FileTransfer.REQ_EXPORT;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_export);

        db = new DbHelper(getApplicationContext());

        rangeList = findViewById(R.id.range_list);
        customBox = findViewById(R.id.custom_box);
        checkFiles = findViewById(R.id.check_files);
        checkEncrypt = findViewById(R.id.check_encrypt);
        textFrom = findViewById(R.id.text_from);
        textTo = findViewById(R.id.text_to);
        textSummary = findViewById(R.id.text_summary);
        btnExport = findViewById(R.id.btn_export);

        // 加密默认勾上：用户要防的就是「文件被别人捡到随手打开」，默认安全比默认方便合适。
        // 勾选状态只影响这一次导出，不落偏好 —— 下次进来又是默认的勾上，
        // 免得「上次为了搬家取消了勾」被一直记着，之后每一份导出都变成明文
        checkEncrypt.setChecked(true);
        checkEncrypt.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean isChecked) {
                applyEncryptLabels(isChecked);
            }
        });
        // 监听是挂在 setChecked 之后才注册的，所以初始文案得自己同步一次 ——
        // 上面那一行 setChecked(true) 不会触发刚注册的这个回调
        applyEncryptLabels(checkEncrypt.isChecked());

        long today = DateUtil.startOfToday();
        customFrom = DateUtil.startOfMonth(today);
        customTo = today;

        findViewById(R.id.btn_back).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        findViewById(R.id.field_from).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickDate(true);
            }
        });
        findViewById(R.id.field_to).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickDate(false);
            }
        });

        checkFiles.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                checkFiles.setText(isChecked ? R.string.export_files_on : R.string.export_files_off);
                refreshSummary();
            }
        });

        btnExport.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                start();
            }
        });

        buildRangeRows();
        applyRangeState();
        refreshSummary();
    }

    // ---------------- 区间 ----------------

    private void buildRangeRows() {
        rangeList.removeAllViews();
        addRangeRow(RANGE_TODAY, getString(R.string.export_range_today));
        addRangeRow(RANGE_WEEK, getString(R.string.export_range_week));
        addRangeRow(RANGE_MONTH, getString(R.string.export_range_month));
        addRangeRow(RANGE_YEAR, getString(R.string.export_range_year));
        addRangeRow(RANGE_ALL, getString(R.string.export_range_all));
        addRangeRow(RANGE_CUSTOM, getString(R.string.export_range_custom));
    }

    private void addRangeRow(final int which, String label) {
        View row = ChoiceRow.create(this, rangeList, label);
        row.setTag(Integer.valueOf(which));
        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                range = which;
                applyRangeState();
                refreshSummary();
            }
        });
        ChoiceRow.addTo(rangeList, row);
        if (which != RANGE_CUSTOM) {
            rangeList.addView(Views.rowDivider(this, 0));
        }
    }

    /** 把选中态刷到每一行上：整行浅绿底 + 文字变深 + 圆环里出勾 */
    private void applyRangeState() {
        for (int i = 0; i < rangeList.getChildCount(); i++) {
            View child = rangeList.getChildAt(i);
            Object tag = child.getTag();
            if (!(tag instanceof Integer)) {
                continue;
            }
            boolean on = ((Integer) tag).intValue() == range;
            ChoiceRow.setSelected(child, on);
        }
        customBox.setVisibility(range == RANGE_CUSTOM ? View.VISIBLE : View.GONE);
        renderCustom();
    }

    private void renderCustom() {
        textFrom.setText(DateUtil.ymd(customFrom));
        textTo.setText(DateUtil.ymd(customTo));
    }

    private void pickDate(final boolean isFrom) {
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.setTimeInMillis(isFrom ? customFrom : customTo);
        new DatePickerDialog(this, new DatePickerDialog.OnDateSetListener() {
            @Override
            public void onDateSet(DatePicker view, int year, int month, int dayOfMonth) {
                java.util.Calendar cal = java.util.Calendar.getInstance();
                cal.set(year, month, dayOfMonth, 0, 0, 0);
                cal.set(java.util.Calendar.MILLISECOND, 0);
                long pickedTime = cal.getTimeInMillis();
                if (isFrom) {
                    customFrom = pickedTime;
                } else {
                    customTo = pickedTime;
                }
                if (DateUtil.startOfDay(customTo) < DateUtil.startOfDay(customFrom)) {
                    // 起止反了就顺手对调，避免用户白选一次
                    long t = customFrom;
                    customFrom = customTo;
                    customTo = t;
                    Toast.makeText(ExportActivity.this, R.string.export_range_invalid,
                            Toast.LENGTH_SHORT).show();
                }
                range = RANGE_CUSTOM;
                applyRangeState();
                refreshSummary();
            }
        }, c.get(java.util.Calendar.YEAR), c.get(java.util.Calendar.MONTH),
                c.get(java.util.Calendar.DAY_OF_MONTH)).show();
    }

    /** 把当前选择的区间换算成左闭右开的时间范围 */
    private void resolveRange() {
        long today = DateUtil.startOfToday();
        switch (range) {
            case RANGE_TODAY:
                rangeFrom = today;
                rangeTo = DateUtil.addDays(today, 1);
                break;
            case RANGE_WEEK:
                rangeFrom = DateUtil.startOfWeek(today);
                rangeTo = DateUtil.addDays(rangeFrom, 7);
                break;
            case RANGE_MONTH:
                rangeFrom = DateUtil.startOfMonth(today);
                rangeTo = DateUtil.addMonths(rangeFrom, 1);
                break;
            case RANGE_YEAR:
                rangeFrom = DateUtil.startOfYear(today);
                rangeTo = DateUtil.addYears(rangeFrom, 1);
                break;
            case RANGE_CUSTOM:
                rangeFrom = DateUtil.startOfDay(customFrom);
                rangeTo = DateUtil.addDays(DateUtil.startOfDay(customTo), 1);
                break;
            case RANGE_ALL:
            default:
                rangeFrom = Long.MIN_VALUE;
                rangeTo = Long.MAX_VALUE;
                break;
        }
    }

    private void refreshSummary() {
        resolveRange();
        long from = rangeFrom;
        long to = rangeTo;
        List<Event> events = (from == Long.MIN_VALUE)
                ? db.queryAll() : db.queryByRange(from, to);
        picked = events;
        pickedMissingFiles = false;
        pickedAttachments = 0;
        for (Event e : events) {
            pickedAttachments += e.attachmentCount;
        }

        boolean hasFiles = pickedAttachments > 0;
        checkFiles.setVisibility(hasFiles ? View.VISIBLE : View.GONE);
        findViewById(R.id.text_files_note).setVisibility(hasFiles ? View.VISIBLE : View.GONE);
        if (!hasFiles) {
            checkFiles.setChecked(false);
        }

        String span = spanText();
        if (events.isEmpty()) {
            textSummary.setText(getString(R.string.export_range_none) + " · " + span);
            btnExport.setEnabled(false);
            btnExport.setAlpha(0.45f);
        } else {
            if (hasFiles && checkFiles.isChecked()) {
                textSummary.setText(getString(R.string.export_summary_files, events.size(), span,
                        pickedAttachments, DateUtil.humanSize(roughPayloadBytes())));
            } else {
                textSummary.setText(getString(R.string.export_summary, events.size(), span));
            }
            btnExport.setEnabled(true);
            btnExport.setAlpha(1f);
        }
    }

    private String spanText() {
        if (rangeFrom == Long.MIN_VALUE) {
            return getString(R.string.export_range_all);
        }
        long last = DateUtil.addDays(rangeTo, -1);
        if (DateUtil.isSameDay(rangeFrom, last)) {
            return DateUtil.ymd(rangeFrom);
        }
        return DateUtil.ymd(rangeFrom) + " – " + DateUtil.ymd(last);
    }

    /** 粗估带上附件后的大小：原文件字节 + base64 的 4/3 膨胀 */
    private long roughPayloadBytes() {
        long total = 0;
        for (Event e : picked) {
            List<Attachment> list = db.queryAttachments(e.id);
            e.attachments = list;
            for (Attachment a : list) {
                total += a.size > 0 ? a.size : 0;
            }
        }
        return total + total / 3 + 4096;
    }

    // ---------------- 导出 ----------------

    private void start() {
        if (picked.isEmpty()) {
            Toast.makeText(this, R.string.export_nothing, Toast.LENGTH_SHORT).show();
            return;
        }
        // 汇总附件登记，顺便记下有没有读不到的
        for (Event e : picked) {
            List<Attachment> list = db.queryAttachments(e.id);
            e.attachments = list;
            for (Attachment a : list) {
                if (!AttachmentStore.exists(this, a)) {
                    a.missing = true;
                    pickedMissingFiles = true;
                }
            }
        }
        if (pickedMissingFiles) {
        Motion.showBuilder(new AlertDialog.Builder(this)
                    .setTitle(R.string.export_missing_title)
                    .setMessage(R.string.export_missing_msg)
                    .setPositiveButton(R.string.export_missing_keep,
                            new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface dialog, int which) {
                                    destination();
                                }
                            })
                    .setNegativeButton(R.string.action_cancel, null));
                
            return;
        }
        destination();
    }

    /** 保存位置：与之前一致的两条路线，但改用本应用自己的选项弹窗 */
    private void destination() {
        Choices.show(this, getString(R.string.export_where_title),
                getString(R.string.export_where_msg, picked.size()),
                new CharSequence[]{
                        getString(R.string.export_where_download),
                        getString(R.string.export_where_pick)
                }, new Choices.OnPick() {
                    @Override
                    public void onPick(int index) {
                        afterWhere(index == 0);
                    }
                });
    }

    /**
     * 勾选框文字跟着勾选状态切换。初始状态与每次点击都走这里，只有一处文案来源。
     *
     * <p>1.3.5 后续：下面原本还有一行随状态切换的说明（加密成什么样、明文是什么样），
     * 整行去掉了 —— 勾选框自己的文字已经说清「加密这份文件 / 不加密（明文 JSON）」，
     * 再补一段就是在替用户复述他刚读过的字。
     */
    private void applyEncryptLabels(boolean on) {
        checkEncrypt.setText(on ? R.string.export_encrypt_on : R.string.export_encrypt_off);
    }

    /**
     * 保存位置选好之后，直接按当前勾选状态落盘。
     *
     * <p>加密由导出页上的勾选框决定，不再有额外的询问步骤。
     */
    private void afterWhere(final boolean toDownloads) {
        deliver(toDownloads);
    }

    /** 真正落盘：先写本机草稿（防被系统杀），再复制到用户选的位置 */
    private void deliver(boolean toDownloads) {
        long stamp = System.currentTimeMillis();
        try {
            String json = EventCodec.encode(picked, stamp, true, attachPayloads());
            byte[] data = checkEncrypt.isChecked()
                    ? SecurityHelper.encryptFixed(json)
                    : json.getBytes(Storage.UTF8);

            // 本机同时留一份完整的明文备份（含全部记录与附件登记）
            Storage.writeBackup(this, db.queryAll(), db);

            if (toDownloads) {
                String name = Storage.suggestedFileName(stamp);
                String where = Storage.writeToDownloads(this, name, data);
                if (where != null && where.length() > 0) {
                    Toast.makeText(this, getString(R.string.export_saved_to, where),
                            Toast.LENGTH_LONG).show();
                    finish();
                    return;
                }
                Storage.writeDraft(this, stamp, data);
                if (where != null && where.length() == 0) {
                    String[] perms = Storage.publicWritePermission();
                    if (perms.length > 0) {
                        requestPermissions(perms, FileTransfer.REQ_WRITE_PERMISSION);
                        return;
                    }
                }
                Toast.makeText(this, R.string.backup_export_failed, Toast.LENGTH_SHORT).show();
                return;
            }

            Storage.writeDraft(this, stamp, data);
            startActivityForResult(FileTransfer.pickExportTarget(stamp), REQ_SAVE);
        } catch (Exception e) {
            Toast.makeText(this, R.string.backup_export_failed, Toast.LENGTH_SHORT).show();
        }
    }

    /** 勾了「带上附件」才读出内容；读不到的跳过并记一笔 */
    private Map<Long, byte[]> attachPayloads() {
        Map<Long, byte[]> map = new HashMap<>();
        if (checkFiles.getVisibility() != View.VISIBLE || !checkFiles.isChecked()) {
            return map;
        }
        for (Event e : picked) {
            if (e.attachments == null) {
                continue;
            }
            for (Attachment a : e.attachments) {
                if (map.containsKey(Long.valueOf(a.id))) {
                    continue;
                }
                byte[] data = AttachmentStore.read(this, a);
                if (data != null && data.length > 0) {
                    map.put(Long.valueOf(a.id), data);
                }
            }
        }
        return map;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == FileTransfer.REQ_WRITE_PERMISSION) {
            FileTransfer.onWritePermissionResult(this, grantResults, this);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_SAVE) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                Uri uri = data.getData();
                java.io.File draft = Storage.findDraft(this);
                if (draft != null) {
                    try {
                        Storage.writeUriBytes(getContentResolver(), uri, Storage.readFileBytes(draft));
                        Storage.clearDraft(draft);
                        Toast.makeText(this, R.string.backup_done, Toast.LENGTH_SHORT).show();
                        finish();
                    } catch (Exception e) {
                        Toast.makeText(this, R.string.export_retry_msg, Toast.LENGTH_LONG).show();
                    }
                }
            } else {
                // 用户取消：草稿留着，下次进应用会问要不要补存
                finish();
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (db != null) {
            db.close();
        }
    }

    // ---------------- FileTransfer.Callback ----------------

    @Override
    public void onImportFailed() {
    }

    @Override
    public void onImported(List<Event> events) {
    }

    @Override
    public void onImportModeChosen(int mode) {
    }

    @Override
    public void onExported() {
        Toast.makeText(this, R.string.backup_done, Toast.LENGTH_SHORT).show();
        finish();
    }
}
