package com.MATO.log.ui;

import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.DatePicker;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.TimePicker;
import android.widget.Toast;

import com.MATO.log.R;
import com.MATO.log.data.Attachment;
import com.MATO.log.data.DbHelper;
import com.MATO.log.data.Event;
import com.MATO.log.util.AttachmentStore;
import com.MATO.log.util.DateUtil;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

/**
 * 新建 / 编辑一条记录：时间（默认取系统时间）+ 描述 + 附件。
 *
 * 附件有两种存法：存进应用内（复制一份，原件删了也在）或引用原文件（不复制）。
 * 新建时附件先挂在内存里，等记录落库拿到 id 之后再一起写进去。
 */
public class EditEventActivity extends BaseActivity {

    public static final String EXTRA_ID = "event_id";
    public static final String EXTRA_TIME = "event_time";

    private static final int REQ_PICK_ATTACH = 301;

    private DbHelper db;
    private long currentId;
    private long currentTime;

    private TextView timeDisplay;
    private EditText editDesc;
    private LinearLayout rowDelete;
    private LinearLayout listAttach;
    private TextView attachHint;

    /** 已经在库里的附件（带 id） */
    private final List<Attachment> saved = new ArrayList<>();
    /** 本次新加的、还没落库的附件 */
    private final List<Attachment> pending = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_edit);

        db = new DbHelper(getApplicationContext());

        timeDisplay = findViewById(R.id.time_display);
        editDesc = findViewById(R.id.edit_desc);
        rowDelete = findViewById(R.id.row_delete);
        listAttach = findViewById(R.id.list_attach);
        attachHint = findViewById(R.id.text_attach_hint);
        Button save = findViewById(R.id.btn_save);
        TextView editorTitle = findViewById(R.id.editor_title);
        ImageButton back = findViewById(R.id.btn_back);

        long id = getIntent().getLongExtra(EXTRA_ID, 0L);
        Event existing = id > 0 ? db.getById(id) : null;
        if (existing != null) {
            currentId = existing.id;
            currentTime = existing.time;
            editDesc.setText(existing.text);
            editorTitle.setText(R.string.edit_title_edit);
            rowDelete.setVisibility(View.VISIBLE);
            saved.addAll(db.queryAttachments(existing.id));
        } else {
            currentId = 0;
            currentTime = getIntent().getLongExtra(EXTRA_TIME, System.currentTimeMillis());
            editorTitle.setText(R.string.edit_title_new);
        }
        renderTime();
        renderAttachments();

        findViewById(R.id.row_time).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickDate();
            }
        });
        findViewById(R.id.row_add_attach).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                askAttachWay();
            }
        });
        save.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                save();
            }
        });
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        rowDelete.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                confirmDelete();
            }
        });
    }

    private void renderTime() {
        timeDisplay.setText(DateUtil.fullDay(currentTime) + "  " + DateUtil.hhmm(currentTime));
    }

    // ---------------- 附件 ----------------

    private void askAttachWay() {
        // 这里曾经用 AlertDialog.setItems，在 vivo Z1 / Android 9 上「标题 + 说明 + 列表」
        // 会渲染成空框（列表一段都不画出来），改用自绘列表，见 Choices 里的说明。
        Choices.show(this, getString(R.string.attach_way_title), getString(R.string.attach_hint),
                new CharSequence[]{
                        getString(R.string.attach_way_internal),
                        getString(R.string.attach_way_reference)
                }, new Choices.OnPick() {
                    @Override
                    public void onPick(int index) {
                        // 先记下存法再挑文件，别让两个动作的先后顺序影响结果
                        internalMode = index == 0;
                        startActivityForResult(AttachmentStore.pickIntent(), REQ_PICK_ATTACH);
                    }
                });
    }

    /** 用户在上一步选了哪种存法 */
    private boolean internalMode = true;

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PICK_ATTACH || resultCode != RESULT_OK || data == null) {
            return;
        }
        Uri uri = data.getData();
        if (uri == null) {
            return;
        }
        long owner = currentId > 0 ? currentId : 0L;
        Attachment a;
        if (internalMode) {
            a = AttachmentStore.copyIn(this, uri, owner);
            if (a == null) {
                Toast.makeText(this, R.string.attach_failed, Toast.LENGTH_SHORT).show();
                return;
            }
            if (currentId > 0) {
                // 已经在编辑既有记录：直接落库，记录不用等保存
                db.insertAttachment(a);
                saved.add(a);
            } else {
                pending.add(a);
            }
        } else {
            a = AttachmentStore.reference(this, uri, owner);
            if (a == null) {
                Toast.makeText(this, R.string.attach_failed, Toast.LENGTH_SHORT).show();
                return;
            }
            if (currentId > 0) {
                db.insertAttachment(a);
                saved.add(a);
            } else {
                pending.add(a);
            }
        }
        renderAttachments();
    }

    private void renderAttachments() {
        listAttach.removeAllViews();
        int n = saved.size() + pending.size();
        attachHint.setText(getString(R.string.attach_hint) + " · " + getString(R.string.attach_count, n));
        if (n == 0) {
            return;
        }
        for (int i = 0; i < saved.size(); i++) {
            addAttachRow(saved.get(i), true);
        }
        for (int i = 0; i < pending.size(); i++) {
            addAttachRow(pending.get(i), false);
        }
    }

    private void addAttachRow(final Attachment a, final boolean stored) {
        View v = getLayoutInflater().inflate(R.layout.item_attachment, listAttach, false);
        ImageView icon = v.findViewById(R.id.attach_icon);
        TextView name = v.findViewById(R.id.attach_name);
        TextView meta = v.findViewById(R.id.attach_meta);
        TextView open = v.findViewById(R.id.attach_open);
        ImageView remove = v.findViewById(R.id.attach_remove);
        TextView delete = v.findViewById(R.id.attach_delete);

        icon.setImageResource(a.isImage() ? R.drawable.ic_star : R.drawable.ic_attach);
        name.setText(a.name);
        StringBuilder sb = new StringBuilder();
        sb.append(a.internal ? getString(R.string.attach_mode_internal)
                : getString(R.string.attach_mode_reference));
        if (a.size > 0) {
            sb.append(" · ").append(DateUtil.humanSize(a.size));
        }
        meta.setText(sb.toString());

        // 编辑页用一个小叉收在行尾，不用整行的「打开/删除」两枚按钮
        open.setVisibility(View.GONE);
        delete.setVisibility(View.GONE);
        remove.setVisibility(View.VISIBLE);
        remove.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                confirmRemove(a, stored);
            }
        });
        v.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                try {
                    AttachmentStore.open(EditEventActivity.this, a);
                } catch (Exception e) {
                    Toast.makeText(EditEventActivity.this, R.string.attach_open_failed,
                            Toast.LENGTH_SHORT).show();
                }
            }
        });

        listAttach.addView(v, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        listAttach.addView(Views.rowDivider(this, 16));
    }

    private void confirmRemove(final Attachment a, final boolean stored) {
        Motion.showBuilder(new AlertDialog.Builder(this)
                .setTitle(R.string.attach_delete_title)
                .setMessage(getString(R.string.attach_delete_msg, a.name))
                .setPositiveButton(R.string.action_delete, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        remove(a, stored);
                    }
                })
                .setNegativeButton(R.string.action_cancel, null));
    }

    private void remove(Attachment a, boolean stored) {
        if (stored) {
            AttachmentStore.deleteFile(this, a);
            db.deleteAttachment(a.id);
            saved.remove(a);
        } else {
            AttachmentStore.deleteFile(this, a);
            pending.remove(a);
        }
        Toast.makeText(this, R.string.attach_deleted, Toast.LENGTH_SHORT).show();
        renderAttachments();
    }

    // ---------------- 时间 ----------------

    private void pickDate() {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(currentTime);
        new DatePickerDialog(this, new DatePickerDialog.OnDateSetListener() {
            @Override
            public void onDateSet(DatePicker view, int year, int month, int dayOfMonth) {
                Calendar cal = Calendar.getInstance();
                cal.setTimeInMillis(currentTime);
                cal.set(Calendar.YEAR, year);
                cal.set(Calendar.MONTH, month);
                cal.set(Calendar.DAY_OF_MONTH, dayOfMonth);
                currentTime = cal.getTimeInMillis();
                renderTime();
                pickTime();
            }
        }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show();
    }

    private void pickTime() {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(currentTime);
        new TimePickerDialog(this, new TimePickerDialog.OnTimeSetListener() {
            @Override
            public void onTimeSet(TimePicker view, int hourOfDay, int minute) {
                Calendar cal = Calendar.getInstance();
                cal.setTimeInMillis(currentTime);
                cal.set(Calendar.HOUR_OF_DAY, hourOfDay);
                cal.set(Calendar.MINUTE, minute);
                cal.set(Calendar.SECOND, 0);
                currentTime = cal.getTimeInMillis();
                renderTime();
            }
        }, c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), true).show();
    }

    // ---------------- 保存 / 删除 ----------------

    private void save() {
        String text = editDesc.getText() == null ? "" : editDesc.getText().toString().trim();
        if (TextUtils.isEmpty(text)) {
            Toast.makeText(this, R.string.edit_empty_warning, Toast.LENGTH_SHORT).show();
            editDesc.requestFocus();
            return;
        }
        if (currentId > 0) {
            Event e = new Event(currentId, currentTime, text, 0);
            db.update(e);
        } else {
            Event e = new Event(currentTime, text);
            db.insert(e);
            currentId = e.id;
            // 记录先落库拿到 id，再把本次新加的附件挂上去
            if (!pending.isEmpty()) {
                db.insertAttachments(currentId, pending);
                pending.clear();
            }
        }
        Toast.makeText(this, R.string.edit_saved, Toast.LENGTH_SHORT).show();
        setResult(RESULT_OK);
        finish();
    }

    private void confirmDelete() {
        if (saved.isEmpty()) {
            Motion.showBuilder(new AlertDialog.Builder(this)
                    .setTitle(R.string.delete_confirm_title)
                    .setMessage(editDesc.getText())
                    .setPositiveButton(R.string.action_delete, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            deleteEvent(false);
                        }
                    })
                    .setNegativeButton(R.string.action_cancel, null));
            return;
        }
        // 带着附件：让用户自己决定文件跟不跟着走
        Motion.showBuilder(new AlertDialog.Builder(this)
                .setTitle(R.string.delete_attach_title)
                .setMessage(getString(R.string.delete_attach_msg, saved.size()))
                .setPositiveButton(R.string.delete_attach_together, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        deleteEvent(true);
                    }
                })
                .setNeutralButton(R.string.delete_attach_keep, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        deleteEvent(false);
                    }
                })
                .setNegativeButton(R.string.action_cancel, null));
    }

    /**
     * @param withFiles true = 附件一起删；false = 只摘掉归属，文件留到「附带文件」里
     */
    private void deleteEvent(boolean withFiles) {
        if (withFiles) {
            for (Attachment a : saved) {
                AttachmentStore.deleteFile(this, a);
            }
            db.deleteAttachmentsOf(currentId);
        } else {
            db.detachAttachmentsOf(currentId);
        }
        db.delete(currentId);
        Toast.makeText(EditEventActivity.this, R.string.edit_deleted, Toast.LENGTH_SHORT).show();
        setResult(RESULT_OK);
        finish();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (db != null) {
            db.close();
        }
    }
}
