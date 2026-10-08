package com.MATO.log.ui;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
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
import com.MATO.log.util.Storage;

import java.io.File;
import java.util.HashSet;
import java.util.List;

/** 备份与恢复：本机自动备份列表 + 导出 / 导入 JSON */
public class BackupActivity extends BaseActivity {

    private static final int REQ_EXPORT = 201;
    private static final int REQ_IMPORT = 202;

    private DbHelper db;
    private LinearLayout list;
    private TextView empty;
    private List<Event> pendingImport;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_backup);

        db = new DbHelper(getApplicationContext());
        list = findViewById(R.id.list_backups);
        empty = findViewById(R.id.text_empty);

        findViewById(R.id.btn_back).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        findViewById(R.id.btn_export).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                exportToFile();
            }
        });
        findViewById(R.id.btn_import).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                importFromFile();
            }
        });
        findViewById(R.id.btn_backup_now).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                backupNow();
            }
        });
    }

    /** 手动留一份本机备份 */
    private void backupNow() {
        java.io.File f = Storage.writeBackup(this, db.queryAll(), db);
        Toast.makeText(this, f == null ? R.string.backup_export_failed : R.string.backup_now_done,
                Toast.LENGTH_SHORT).show();
        reload();
    }

    @Override
    protected void onResume() {
        super.onResume();
        reload();
    }

    private void reload() {
        list.removeAllViews();
        List<Storage.BackupFile> files = Storage.listBackupFiles(this);
        empty.setVisibility(files.isEmpty() ? View.VISIBLE : View.GONE);

        for (int i = 0; i < files.size(); i++) {
            final Storage.BackupFile bf = files.get(i);
            View row = getLayoutInflater().inflate(R.layout.item_backup, list, false);
            TextView title = row.findViewById(R.id.row_title);
            TextView sub = row.findViewById(R.id.row_sub);
            TextView action = row.findViewById(R.id.row_action);

            title.setText(bf.file.getName());
            sub.setText(DateUtil.stamp(bf.time)
                    + " · " + (bf.count >= 0 ? bf.count + getString(R.string.count_unit) : "?")
                    + " · " + DateUtil.humanSize(bf.size()));
            // 点一行 = 恢复；右侧文字 = 删除
            row.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    confirmRestore(bf);
                }
            });
            action.setText(R.string.backup_delete);
            action.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    confirmDelete(bf);
                }
            });

            list.addView(row, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            if (i < files.size() - 1) {
                list.addView(Views.rowDivider(this, 16));
            }
        }
    }

    private void confirmRestore(final Storage.BackupFile bf) {
        Motion.showBuilder(new AlertDialog.Builder(this)
                .setTitle(R.string.backup_restore_title)
                .setMessage(R.string.backup_restore_msg)
                .setPositiveButton(R.string.backup_restore, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        restore(bf);
                    }
                })
                .setNegativeButton(R.string.action_cancel, null));
    }

    private void restore(Storage.BackupFile bf) {
        try {
            List<Event> events = Storage.readBackup(bf.file);
            // 先用当前数据留一份，之后再覆盖
            Storage.writeBackup(this, db.queryAll(), db);
            // 备份里带的附件明细：文件若还在本机就续上原来的那一份
            reattachFromBackup(events);
            dropAttachmentsNotIn(events);
            int n = db.replaceAll(events);
            Toast.makeText(this, getString(R.string.backup_imported, n), Toast.LENGTH_SHORT).show();
            reload();
        } catch (Exception e) {
            Toast.makeText(this, R.string.backup_import_failed, Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * 本机备份里的附件只是登记（名字 + 内部文件名）。
     * 文件还在就按文件名接回去；不在了就标成缺失，用户可以在「附带文件」里看到。
     */
    private void reattachFromBackup(List<Event> events) {
        java.util.Map<String, Long> known = Storage.idByLocalName(db);
        for (Event e : events) {
            if (e.attachments == null) {
                continue;
            }
            for (Attachment a : e.attachments) {
                if (a.internal) {
                    // 先按内部文件名（或显示名）接回本机已有的那份文件
                    a.missing = !AttachmentStore.relinkFromLocal(this, a);
                    if (a.localName != null && a.localName.length() > 0) {
                        Long id = known.get(a.localName);
                        if (id != null) {
                            a.id = id.longValue();
                        }
                    }
                } else {
                    a.missing = !AttachmentStore.exists(this, a);
                }
            }
        }
    }

    /**
     * 覆盖导入会换掉全部事件，旧附件记录也就跟着失去归属。
     * 这里把「这次恢复要留下的内部文件」先列出来，其余的内部文件连同登记一起清掉，
     * 免得恢复一次就在本机多攒一堆没人认领的文件。
     */
    private void dropAttachmentsNotIn(List<Event> events) {
        HashSet<String> keep = new HashSet<>();
        for (Event e : events) {
            if (e.attachments == null) {
                continue;
            }
            for (Attachment a : e.attachments) {
                if (a.internal && a.localName != null && a.localName.length() > 0) {
                    keep.add(a.localName);
                }
            }
        }
        for (Attachment old : db.queryAllAttachments()) {
            boolean keepFile = old.internal && old.localName != null && keep.contains(old.localName);
            if (!keepFile) {
                AttachmentStore.deleteFile(this, old);
            }
        }
        db.clearAttachments();
    }

    private void confirmDelete(final Storage.BackupFile bf) {
        Motion.showBuilder(new AlertDialog.Builder(this)
                .setTitle(R.string.backup_delete_title)
                .setMessage(bf.file.getName())
                .setPositiveButton(R.string.backup_delete, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        //noinspection ResultOfMethodCallIgnored
                        bf.file.delete();
                        reload();
                    }
                })
                .setNegativeButton(R.string.action_cancel, null));
    }

    private void exportToFile() {
        Intent it = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        it.addCategory(Intent.CATEGORY_OPENABLE);
        it.setType(Storage.MIME_JSON);
        it.putExtra(Intent.EXTRA_TITLE, Storage.suggestedFileName(System.currentTimeMillis()));
        startActivityForResult(it, REQ_EXPORT);
    }

    private void importFromFile() {
        Intent it = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        it.addCategory(Intent.CATEGORY_OPENABLE);
        it.setType("*/*");
        it.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{Storage.MIME_JSON, "text/plain", "*/*"});
        startActivityForResult(it, REQ_IMPORT);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        Uri uri = data.getData();

        if (requestCode == REQ_EXPORT) {
            try {
                // 这里走的是「整库导出」，附件只登记内容不搬（与主界面导出的默认行为一致）
                List<Event> all = Storage.attachAll(db.queryAll(), db);
                Storage.writeUri(getContentResolver(), uri,
                        EventCodec.encode(all, System.currentTimeMillis(), true));
                Storage.writeBackup(this, all, db);
                Toast.makeText(this, R.string.backup_done, Toast.LENGTH_SHORT).show();
                reload();
            } catch (Exception e) {
                Toast.makeText(this, R.string.backup_export_failed, Toast.LENGTH_SHORT).show();
            }
            return;
        }

        if (requestCode == REQ_IMPORT) {
            try {
                String json = Storage.readUri(getContentResolver(), uri);
                EventCodec.Payload p = EventCodec.decode(json);
                if (p.events.isEmpty()) {
                    Toast.makeText(this, R.string.backup_import_failed, Toast.LENGTH_SHORT).show();
                    return;
                }
                pendingImport = p.events;
                askImportMode();
            } catch (Exception e) {
                Toast.makeText(this, R.string.backup_import_failed, Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void askImportMode() {
        final List<Event> listData = pendingImport;
        long min = Long.MAX_VALUE;
        long max = Long.MIN_VALUE;
        for (Event e : listData) {
            min = Math.min(min, e.time);
            max = Math.max(max, e.time);
        }
        // 同 FileTransfer：这个组合在部分 ROM 上列表不渲染，走自绘列表
        Choices.show(this, getString(R.string.backup_import_title),
                getString(R.string.backup_summary, listData.size(),
                        DateUtil.ymd(min) + " – " + DateUtil.ymd(max)),
                new CharSequence[]{
                        getString(R.string.import_mode_merge),
                        getString(R.string.import_mode_append),
                        getString(R.string.import_mode_replace)
                }, new Choices.OnPick() {
                    @Override
                    public void onPick(int index) {
                        doImport(listData, index);
                    }
                });
    }

    private void doImport(List<Event> events, int mode) {
        Storage.writeBackup(this, db.queryAll(), db);
        if (mode == 2) {
            // 覆盖前先把旧的附件登记与文件清干净（新的事件 id 会变，留着也没法对应）
            reattachFromBackup(events);
            dropAttachmentsNotIn(events);
        } else {
            // 合并 / 追加：附件先按内部文件名（或显示名）接回本机已有的那份，
            // 接上了就不该再标缺失；接不上才标「文件不在了」
            java.util.Map<String, Long> known = Storage.idByLocalName(db);
            for (Event e : events) {
                if (e.attachments == null) {
                    continue;
                }
                for (Attachment a : e.attachments) {
                    if (a.internal) {
                        a.missing = !AttachmentStore.relinkFromLocal(this, a);
                        if (a.localName != null && a.localName.length() > 0) {
                            Long id = known.get(a.localName);
                            if (id != null) {
                                a.id = id.longValue();
                            }
                        }
                    } else {
                        a.missing = !AttachmentStore.exists(this, a);
                    }
                }
            }
        }
        int n;
        if (mode == 2) {
            n = db.replaceAll(events);
        } else if (mode == 0) {
            int c = 0;
            for (Event e : events) {
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
            n = db.insertAll(events);
        }
        Toast.makeText(this, getString(R.string.backup_imported, n), Toast.LENGTH_SHORT).show();
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
