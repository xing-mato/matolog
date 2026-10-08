package com.MATO.log.ui;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.MATO.log.R;
import com.MATO.log.data.Attachment;
import com.MATO.log.data.DbHelper;
import com.MATO.log.util.AttachmentStore;
import com.MATO.log.util.ErrorLog;
import com.MATO.log.util.DateUtil;

import java.util.List;

/**
 * 附带文件：专门收留「事件删了、附件留下」的那些文件。
 *
 * 删除记录时如果选择保留附件，附件会被摘成无归属状态落到这里，
 * 免得既占着空间又找不到入口。可以逐个打开看看，或者清掉腾空间。
 */
public class AttachmentActivity extends BaseActivity {

    private DbHelper db;
    private LinearLayout list;
    private TextView empty;
    private TextView used;
    private LinearLayout boxExternal;
    private TextView textExternal;
    private Button clear;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_attachments);

        db = new DbHelper(getApplicationContext());
        list = findViewById(R.id.list_attach);
        empty = findViewById(R.id.text_empty);
        used = findViewById(R.id.text_used);
        boxExternal = findViewById(R.id.box_external);
        textExternal = findViewById(R.id.text_external);
        clear = findViewById(R.id.btn_clear);

        ImageButton back = findViewById(R.id.btn_back);
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        clear.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                confirmClearAll();
            }
        });
        findViewById(R.id.row_sweep).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                sweep();
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        reload();
    }

    private void reload() {
        list.removeAllViews();
        List<Attachment> orphans = db.queryOrphanAttachments();
        empty.setVisibility(orphans.isEmpty() ? View.VISIBLE : View.GONE);
        clear.setEnabled(!orphans.isEmpty());
        clear.setAlpha(orphans.isEmpty() ? 0.45f : 1f);

        int external = 0;
        for (int i = 0; i < orphans.size(); i++) {
            Attachment a = orphans.get(i);
            if (!a.internal) {
                external++;
            }
            a.missing = !AttachmentStore.exists(this, a);
            list.addView(row(a), new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            if (i < orphans.size() - 1) {
                list.addView(Views.rowDivider(this, 16));
            }
        }

        used.setText(getString(R.string.attach_page_total,
                AttachmentStore.fileCount(this),
                DateUtil.humanSize(AttachmentStore.usedBytes(this))));
        boxExternal.setVisibility(external > 0 ? View.VISIBLE : View.GONE);
        if (external > 0) {
            textExternal.setText(getString(R.string.attach_external_note, external));
        }
    }

    private View row(final Attachment a) {
        View v = getLayoutInflater().inflate(R.layout.item_attachment, list, false);
        ImageView icon = v.findViewById(R.id.attach_icon);
        TextView name = v.findViewById(R.id.attach_name);
        TextView meta = v.findViewById(R.id.attach_meta);
        TextView open = v.findViewById(R.id.attach_open);
        TextView delete = v.findViewById(R.id.attach_delete);

        icon.setImageResource(a.isImage() ? R.drawable.ic_star : R.drawable.ic_attach);
        name.setText(a.name);

        StringBuilder sb = new StringBuilder();
        sb.append(a.internal ? getString(R.string.attach_mode_internal)
                : getString(R.string.attach_mode_reference));
        if (a.size > 0) {
            sb.append(" · ").append(DateUtil.humanSize(a.size));
        }
        if (a.missing) {
            sb.append(" · ").append(getString(R.string.attach_missing));
        }
        meta.setText(sb.toString());

        v.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                open(a);
            }
        });
        open.setVisibility(a.missing ? View.GONE : View.VISIBLE);
        open.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                open(a);
            }
        });
        delete.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                confirmDelete(a);
            }
        });
        return v;
    }

    private void open(Attachment a) {
        try {
            AttachmentStore.open(this, a);
        } catch (Exception e) {
            // 「打开附件失败」对用户只是一句提示，但对排查是条实打实的线索：
            // 内部附件打不开通常意味着文件没了（数据丢失），引用型打不开多半是授权掉了。
            ErrorLog.record(this, "attach", "打开附件失败：" + a.name, e);
            Toast.makeText(this, R.string.attach_open_failed, Toast.LENGTH_SHORT).show();
        }
    }

    private void confirmDelete(final Attachment a) {
        Motion.showBuilder(new AlertDialog.Builder(this)
                .setTitle(R.string.attach_delete_title)
                .setMessage(getString(R.string.attach_delete_msg, a.name))
                .setPositiveButton(R.string.action_delete, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        delete(a);
                    }
                })
                .setNegativeButton(R.string.action_cancel, null));
    }

    private void delete(Attachment a) {
        AttachmentStore.deleteFile(this, a);
        db.deleteAttachment(a.id);
        Toast.makeText(this, R.string.attach_deleted, Toast.LENGTH_SHORT).show();
        reload();
    }

    private void confirmClearAll() {
        final List<Attachment> orphans = db.queryOrphanAttachments();
        if (orphans.isEmpty()) {
            return;
        }
        long bytes = 0;
        for (Attachment a : orphans) {
            if (a.internal) {
                bytes += a.size;
            }
        }
        Motion.showBuilder(new AlertDialog.Builder(this)
                .setTitle(R.string.attach_page_clear_title)
                .setMessage(getString(R.string.attach_page_clear_msg, orphans.size(),
                        DateUtil.humanSize(bytes)))
                .setPositiveButton(R.string.action_delete, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        for (Attachment a : orphans) {
                            AttachmentStore.deleteFile(AttachmentActivity.this, a);
                            db.deleteAttachment(a.id);
                        }
                        reload();
                    }
                })
                .setNegativeButton(R.string.action_cancel, null));
    }

    /** 库里没登记、目录里却躺着的文件，一并扫掉 */
    private void sweep() {
        long freed = AttachmentStore.sweepOrphans(this, db.queryAllAttachments());
        if (freed > 0) {
            Toast.makeText(this, getString(R.string.attach_page_swept,
                    DateUtil.humanSize(freed)), Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, R.string.attach_page_swept_none, Toast.LENGTH_SHORT).show();
        }
        reload();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (db != null) {
            db.close();
        }
    }
}
