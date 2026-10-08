package com.MATO.log.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.text.InputType;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

import com.MATO.log.R;
import com.MATO.log.data.Attachment;
import com.MATO.log.data.Event;
import com.MATO.log.util.AttachmentStore;
import com.MATO.log.util.DateUtil;
import com.MATO.log.util.ErrorLog;
import com.MATO.log.util.EventCodec;
import com.MATO.log.util.SecurityHelper;
import com.MATO.log.util.Storage;

import java.util.List;

/**
 * 导出 / 导入的公共水道。
 *
 * 导出的「选哪些日期、带不带附件」由 {@link ExportActivity} 决定，
 * 这里只负责剩下两件跟系统打交道的事：
 * - 内容先落本机草稿，再复制到用户选的位置（后台管控激进的机型上会被杀进程，草稿是保险）；
 * - 直接写系统「下载」目录，不经过任何界面。
 *
 * 导入则自动识别明文与密文：本应用的加密文件直接解开，旧版（1.3 及更早）的
 * 口令加密文件才提示输入口令。
 */
public final class FileTransfer {

    public interface Callback {
        /** 导入失败（口令错误、文件损坏、格式不认识） */
        void onImportFailed();

        /** 成功拿到事件列表 */
        void onImported(List<Event> events);

        /** 用户在导入弹窗里选定了合并方式：0 合并 / 1 追加 / 2 覆盖 */
        void onImportModeChosen(int mode);

        /** 导出成功 */
        void onExported();
    }

    private FileTransfer() {
    }

    public static final int REQ_EXPORT = 101;
    public static final int REQ_EXPORT_RETRY = 111;
    public static final int REQ_WRITE_PERMISSION = 112;

    // ---------------- 导出：两条落盘路线 ----------------

    /** 把已经算好的字节写进系统「下载」目录；返回的三种结果与 Storage.writeToDownloads 一致 */
    public static String writeToDownloads(Activity act, String fileName, byte[] data) {
        return Storage.writeToDownloads(act, fileName, data);
    }

    /** 申请写权限之后：拿到权限就把草稿补写进下载目录；被拒就改走「自己挑位置」 */
    public static void onWritePermissionResult(Activity act, int[] grantResults, Callback cb) {
        boolean granted = false;
        for (int r : grantResults) {
            if (r == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                granted = true;
            }
        }
        java.io.File draft = Storage.findDraft(act);
        if (granted && draft != null) {
            try {
                byte[] data = Storage.readFileBytes(draft);
                // 用正式文件名，别把内部草稿名暴露给用户
                String name = Storage.suggestedFileName(parseStamp(Storage.draftStampOf(draft)));
                String where = Storage.writeToDownloads(act, name, data);
                if (where != null && where.length() > 0) {
                    Storage.clearDraft(draft);
                    Toast.makeText(act, act.getString(R.string.export_saved_to, where),
                            Toast.LENGTH_LONG).show();
                    if (cb != null) {
                        cb.onExported();
                    }
                    return;
                }
            } catch (Exception ignored) {
            }
        }
        Toast.makeText(act, R.string.export_need_permission, Toast.LENGTH_LONG).show();
        if (draft != null) {
            act.startActivityForResult(
                    pickExportTarget(parseStamp(Storage.draftStampOf(draft))), REQ_EXPORT);
        }
    }

    /** 用户选好位置后：把草稿复制过去。写不进去就保留草稿并问是否换位置。 */
    public static void exportTo(final Activity act, final Uri uri, final long stamp,
                                final Callback cb) {
        java.io.File draft = Storage.draftFile(act, stamp);
        if (!draft.exists()) {
            // 传进来的编号不管用（进程重启等），退回最近一份草稿
            draft = Storage.findDraft(act);
        }
        if (draft == null) {
            // 草稿不见了：要么被清理掉了，要么当初写草稿那一步就没成。
            // 这是「导出静默失败」的一条真实路径（vivo 上尤其容易走到），值得留痕
            ErrorLog.record(act, "export", "导出失败：找不到待写入的草稿文件");
            Toast.makeText(act, R.string.backup_export_failed, Toast.LENGTH_SHORT).show();
            return;
        }
        final long useStamp = parseStamp(Storage.draftStampOf(draft));
        try {
            byte[] data = Storage.readFileBytes(draft);
            Storage.writeUriBytes(act.getContentResolver(), uri, data);
            Storage.clearDraft(draft);
            if (cb != null) {
                cb.onExported();
            }
        } catch (final Exception e) {
            ErrorLog.record(act, "export", "写入所选位置失败", e);
            // 目标写不进去：草稿保留，问用户要不要换个位置再存
            Motion.showBuilder(new AlertDialog.Builder(act)
                    .setTitle(R.string.export_retry_title)
                    .setMessage(act.getString(R.string.export_retry_msg))
                    .setPositiveButton(R.string.export_retry_yes, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            act.startActivityForResult(
                                    pickExportTarget(useStamp), REQ_EXPORT_RETRY);
                        }
                    })
                    .setNegativeButton(R.string.export_retry_keep, null));
        }
    }

    /** 用户在保存界面选好位置后的回执 */
    public static void onExportPicked(Activity act, int resultCode, Intent data, Callback cb) {
        if (resultCode == Activity.RESULT_OK && data != null && data.getData() != null) {
            java.io.File draft = Storage.findDraft(act);
            exportTo(act, data.getData(), parseStamp(Storage.draftStampOf(draft)), cb);
        }
        // 用户取消：草稿留着，下次进应用会问要不要补存
    }

    /** 有没有上次没存完的导出 */
    public static boolean hasPendingExport(Activity act) {
        return Storage.findDraft(act) != null;
    }

    /** 上次的导出没落盘时，问用户要不要接着存 */
    public static void recoverPendingExport(final Activity act, final Callback cb) {
        final java.io.File draft = Storage.findDraft(act);
        if (draft == null) {
            return;
        }
        final long stamp = parseStamp(Storage.draftStampOf(draft));
        Motion.showBuilder(new AlertDialog.Builder(act)
                .setTitle(R.string.export_pending_title)
                .setMessage(R.string.export_pending_msg)
                .setPositiveButton(R.string.export_pending_save, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        act.startActivityForResult(pickExportTarget(stamp), REQ_EXPORT_RETRY);
                    }
                })
                .setNegativeButton(R.string.export_pending_discard, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        Storage.clearDraft(draft);
                    }
                })
                
                );
    }

    /** 重试保存的结果回执 */
    public static void onRetryResult(Activity act, int resultCode, Intent data, Callback cb) {
        if (resultCode == Activity.RESULT_OK && data != null && data.getData() != null) {
            java.io.File draft = Storage.findDraft(act);
            exportTo(act, data.getData(), parseStamp(Storage.draftStampOf(draft)), cb);
        }
    }

    private static long parseStamp(String s) {
        try {
            return Long.parseLong(s);
        } catch (Exception e) {
            return 0L;
        }
    }

    // ---------------- 导入 ----------------

    public static void importFrom(final Activity act, final Uri uri, final Callback cb) {
        final byte[] raw;
        try {
            raw = Storage.readUriBytes(act.getContentResolver(), uri);
        } catch (Exception e) {
            cb.onImportFailed();
            return;
        }
        // 1.3.1 起的格式：认出它就直接解开，不问任何东西
        if (SecurityHelper.isSelfContained(raw)) {
            String plain = SecurityHelper.decryptFixed(raw);
            if (plain != null) {
                decode(act, plain, cb);
                return;
            }
            // 是本应用的格式却解不开，只可能是文件被改坏了（GCM 校验不过）
            Toast.makeText(act, R.string.import_corrupt, Toast.LENGTH_LONG).show();
            cb.onImportFailed();
            return;
        }
        // 1.3 及更早的口令加密：那种文件只能问用户当初设的口令
        if (SecurityHelper.isEncrypted(raw)) {
            askImportPasscode(act, raw, cb);
            return;
        }
        decode(act, new String(raw, Storage.UTF8), cb);
    }

    private static void askImportPasscode(final Activity act, final byte[] raw, final Callback cb) {
        final EditText input = passcodeInput(act);
        Motion.showBuilder(new AlertDialog.Builder(act)
                .setTitle(R.string.import_passcode_title)
                .setMessage(R.string.import_passcode_msg)
                .setView(input)
                .setPositiveButton(R.string.action_ok, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        String pass = input.getText() == null ? "" : input.getText().toString().trim();
                        try {
                            decode(act, SecurityHelper.decrypt(raw, pass), cb);
                        } catch (Exception e) {
                            Toast.makeText(act, R.string.import_passcode_wrong, Toast.LENGTH_SHORT).show();
                        }
                    }
                })
                .setNegativeButton(R.string.action_cancel, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        cb.onImportFailed();
                    }
                }));
    }

    private static void decode(Activity act, String json, Callback cb) {
        try {
            EventCodec.Payload p = EventCodec.decode(json);
            if (p.events.isEmpty() && p.carriedFiles == 0) {
                cb.onImportFailed();
                return;
            }
            if (p.events.isEmpty()) {
                cb.onImportFailed();
                return;
            }
            // 内嵌的文件先落到本机，再交给界面决定怎么入库
            materializeAttachments(act, p);
            if (p.registeredOnly > 0) {
                Toast.makeText(act, act.getString(R.string.import_files_registered_only,
                        p.registeredOnly), Toast.LENGTH_LONG).show();
            }
            cb.onImported(p.events);
        } catch (Exception e) {
            ErrorLog.record(act, "import", "解析导入文件失败", e);
            cb.onImportFailed();
        }
    }

    /**
     * 处理导入进来的附件。
     *
     * 顺序很重要：**先试着接回本机已有的那份文件**，接不上才写内嵌内容。
     * 反过来的话，同一台设备上重新导入自己导出的 JSON（哪怕勾了「带附件」）
     * 也会把文件白复制一份 —— 记录删了但选了保留附件的情形尤其明显，
     * 文件本来就躺在 files/attachments/ 里，没必要再存一遍。
     *
     * 都接不上又没内容的，才标成「文件不在了」。
     */
    private static void materializeAttachments(Activity act, EventCodec.Payload p) {
        for (Event e : p.events) {
            if (e.attachments == null || e.attachments.isEmpty()) {
                continue;
            }
            for (Attachment a : e.attachments) {
                if (AttachmentStore.relinkFromLocal(act, a)) {
                    a.payload = null;
                    continue;
                }
                if (a.payload != null && a.payload.length > 0) {
                    Attachment written = AttachmentStore.writeIn(act, 0, a.name, a.mime, a.payload);
                    if (written != null) {
                        a.internal = true;
                        a.localName = written.localName;
                        a.uri = "";
                        a.size = written.size;
                        a.payload = null;
                        continue;
                    }
                }
                a.missing = true;
            }
        }
    }

    /** 导入确认弹窗：合并 / 追加 / 覆盖 */
    public static void askImportMode(final Activity act, final List<Event> events,
                                     final Callback cb) {
        long min = Long.MAX_VALUE;
        long max = Long.MIN_VALUE;
        for (Event e : events) {
            min = Math.min(min, e.time);
            max = Math.max(max, e.time);
        }
        // 「标题 + 说明 + 列表」这个组合在部分 ROM（实测 vivo Z1 / Android 9）上不渲染列表，
        // 所以走自绘列表，见 Choices 的说明
        Choices.show(act, act.getString(R.string.backup_import_title),
                act.getString(R.string.backup_summary, events.size(),
                        DateUtil.ymd(min) + " – " + DateUtil.ymd(max)),
                new CharSequence[]{
                        act.getString(R.string.import_mode_merge),
                        act.getString(R.string.import_mode_append),
                        act.getString(R.string.import_mode_replace)
                }, new Choices.OnPick() {
                    @Override
                    public void onPick(int index) {
                        cb.onImportModeChosen(index);
                    }
                });
    }

    public static EditText passcodeInput(Context ctx) {
        EditText e = new EditText(ctx);
        e.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        e.setHint(R.string.passcode_hint);
        e.setSingleLine(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        int m = (int) (ctx.getResources().getDisplayMetrics().density * 20);
        lp.leftMargin = m;
        lp.rightMargin = m;
        e.setLayoutParams(lp);
        return e;
    }

    /** 文件选择器 Intent：导出目标 */
    public static Intent pickExportTarget(long stamp) {
        Intent it = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        it.addCategory(Intent.CATEGORY_OPENABLE);
        it.setType(Storage.MIME_JSON);
        it.putExtra(Intent.EXTRA_TITLE, Storage.suggestedFileName(stamp));
        return it;
    }

    /** 文件选择器 Intent：导入来源 */
    public static Intent pickImportSource() {
        Intent it = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        it.addCategory(Intent.CATEGORY_OPENABLE);
        it.setType("*/*");
        it.putExtra(Intent.EXTRA_MIME_TYPES,
                new String[]{Storage.MIME_JSON, "application/octet-stream", "text/plain", "*/*"});
        return it;
    }
}
