package com.MATO.log.util;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;

import com.MATO.log.R;
import com.MATO.log.data.Attachment;
import com.MATO.log.data.DbHelper;
import com.MATO.log.data.Event;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** 导出 / 导入 / 本机备份的文件层 */
public final class Storage {

    public static final Charset UTF8 = Charset.forName("UTF-8");
    public static final String MIME_JSON = "application/json";

    private static final String BACKUP_DIR = "backups";
    private static final String PREFIX = "MATOlog-";
    private static final String SUFFIX = ".json";
    /** 本机自动备份最多保留份数 */
    private static final int KEEP_BACKUPS = 12;

    private Storage() {
    }

    // ---------------- 本机备份 ----------------

    public static File backupDir(Context ctx) {
        File d = new File(ctx.getFilesDir(), BACKUP_DIR);
        if (!d.exists()) {
            //noinspection ResultOfMethodCallIgnored
            d.mkdirs();
        }
        return d;
    }

    /** 备份文件：MATOlog-yyyyMMdd-HHmmss-<N>条.json */
    public static File backupFile(Context ctx, long stamp, int count) {
        return new File(backupDir(ctx), BACKUP_PREFIX(stamp, count));
    }

    private static String BACKUP_PREFIX(long stamp, int count) {
        return PREFIX + DateUtil.fileStamp(stamp) + "-" + count + SUFFIX;
    }

    /** 写一份本机备份并清理旧文件，返回文件；失败返回 null */
    public static File writeBackup(Context ctx, List<Event> events) {
        return writeBackup(ctx, events, null);
    }

    /**
     * 带上附件登记的备份。
     * db 不为空时会把每个事件的附件明细一起写进文件（内容不搬，内部附件本来就在本机），
     * 这样从备份恢复时附件不会跟记录脱钩。
     */
    public static File writeBackup(Context ctx, List<Event> events, DbHelper db) {
        try {
            long now = System.currentTimeMillis();
            attachAll(events, db);
            String json = EventCodec.encode(events, now, true);
            File f = backupFile(ctx, now, events.size());
            writeFile(f, json);
            prune(ctx);
            return f;
        } catch (Exception e) {
            return null;
        }
    }

    /** 给一批事件装上附件登记，导出 / 备份的编码阶段用 */
    public static List<Event> attachAll(List<Event> events, DbHelper db) {
        if (events == null || events.isEmpty() || db == null) {
            return events;
        }
        db.fillAttachmentCounts(events);
        for (Event e : events) {
            List<Attachment> list = db.queryAttachments(e.id);
            e.attachments = list;
            e.attachmentCount = list.size();
        }
        return events;
    }

    /** 备份恢复时按 localName 把附件续上原来的文件 */
    public static java.util.Map<String, Long> idByLocalName(DbHelper db) {
        java.util.HashMap<String, Long> map = new java.util.HashMap<>();
        if (db == null) {
            return map;
        }
        for (Attachment a : db.queryAllAttachments()) {
            if (a.localName != null && a.localName.length() > 0) {
                map.put(a.localName, Long.valueOf(a.id));
            }
        }
        return map;
    }

    /** 只保留最近的若干份本机备份 */
    public static void prune(Context ctx) {
        List<File> all = listBackups(ctx);
        for (int i = KEEP_BACKUPS; i < all.size(); i++) {
            //noinspection ResultOfMethodCallIgnored
            all.get(i).delete();
        }
    }

    // ---------------- 导出草稿 ----------------
    // 有些系统（实测 vivo）会在应用切到后台约一秒内回收进程。保存界面返回时进程若已被杀，
    // onActivityResult 不会执行，导出就会静默失败。所以导出的第一步永远是把内容完整写进本机草稿，
    // 再复制到用户选的位置；即使被系统杀掉，内容也还在，下次启动可以接着存。

    private static final String DRAFT_PREFIX = "export-draft-";

    public static File draftFile(Context ctx, long stamp) {
        return new File(backupDir(ctx), DRAFT_PREFIX + stamp + SUFFIX);
    }

    public static void writeDraft(Context ctx, long stamp, byte[] data) throws IOException {
        File target = draftFile(ctx, stamp);
        writeFileBytes(target, data);
        File[] files = backupDir(ctx).listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.getName().startsWith(DRAFT_PREFIX) && !f.getName().equals(target.getName())) {
                    //noinspection ResultOfMethodCallIgnored
                    f.delete();
                }
            }
        }
    }

    public static byte[] readDraft(Context ctx, long stamp) throws IOException {
        return readFileBytes(draftFile(ctx, stamp));
    }

    /** 最近一份还没处理掉的导出草稿 */
    public static File findDraft(Context ctx) {
        File[] files = backupDir(ctx).listFiles();
        if (files == null) {
            return null;
        }
        File newest = null;
        for (File f : files) {
            if (f.getName().startsWith(DRAFT_PREFIX) && f.getName().endsWith(SUFFIX)) {
                if (newest == null || f.lastModified() > newest.lastModified()) {
                    newest = f;
                }
            }
        }
        return newest;
    }

    public static void clearDraft(File draft) {
        if (draft != null) {
            //noinspection ResultOfMethodCallIgnored
            draft.delete();
        }
    }

    /**
     * 直接写进系统「下载」目录，不经过系统的保存界面。
     *
     * 为什么要它：vivo 这类后台管控激进的机型，一旦把应用挤到后台就会回收进程，
     * 保存界面返回时 onActivityResult 不执行，导出会静默失败。直写这条路不依赖任何界面。
     *
     * 返回空串表示「系统不给权限，走别的方式」；null 表示真的写失败了。
     */
    public static String writeToDownloads(Context ctx, String fileName, byte[] data) {
        return writeToDownloads(ctx, fileName, data, MIME_JSON);
    }

    /**
     * 同上，但可以指定 MIME 类型。
     *
     * <p>1.3.5 加的：诊断报告是 {@code text/plain}，而上面那个重载把
     * {@code mime_type} 写死成 {@code application/json} —— 让「下载」里那份 .txt
     * 顶着 json 的类型，某些文件管理器会照着类型去打开它，打开就是一屏「无法解析」。
     */
    public static String writeToDownloads(Context ctx, String fileName, byte[] data, String mime) {
        // API 29+：MediaStore.Downloads，写自己创建的文件不需要任何权限（反射取类，低版本不会类加载失败）
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            try {
                android.content.ContentValues cv = new android.content.ContentValues();
                cv.put("_display_name", fileName);
                cv.put("mime_type", mime == null ? MIME_JSON : mime);
                android.net.Uri collection = (android.net.Uri) Class
                        .forName("android.provider.MediaStore$Downloads")
                        .getField("EXTERNAL_CONTENT_URI").get(null);
                android.net.Uri uri = ctx.getContentResolver().insert(collection, cv);
                if (uri != null) {
                    writeUriBytes(ctx.getContentResolver(), uri, data);
                    return ctx.getString(R.string.export_downloads_prefix, fileName);
                }
            } catch (Throwable ignored) {
            }
            return null;
        }

        // API 28 及以下：公共下载目录仍在文件系统里，有写权限就能直接落文件
        File dir = publicDownloads();
        if (dir == null || !dir.isDirectory() || !dir.canWrite()) {
            return "";
        }
        try {
            File f = new File(dir, fileName);
            writeFileBytes(f, data);
            return f.getAbsolutePath();
        } catch (Throwable t) {
            return null;
        }
    }

    /** 系统公共「下载」目录；拿不到返回 null */
    public static File publicDownloads() {
        try {
            File f = android.os.Environment
                    .getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS);
            return f != null && f.isDirectory() ? f : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 有没有写入公共目录的权限（Android 10 起不需要，视为有） */
    public static boolean hasPublicWrite(Context ctx) {
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            return true;
        }
        return ctx.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    /** 申请写入权限时要用的权限名；不需要则为空数组 */
    public static String[] publicWritePermission() {
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            return new String[0];
        }
        return new String[]{android.Manifest.permission.WRITE_EXTERNAL_STORAGE};
    }

    public static String draftStampOf(File draft) {
        if (draft == null) {
            return "";
        }
        String n = draft.getName();
        int a = n.indexOf(DRAFT_PREFIX);
        int b = n.lastIndexOf(SUFFIX);
        if (a < 0 || b <= a) {
            return "";
        }
        return n.substring(a + DRAFT_PREFIX.length(), b);
    }

    public static void writeFileBytes(File f, byte[] data) throws IOException {
        File parent = f.getParentFile();
        if (parent != null && !parent.exists()) {
            //noinspection ResultOfMethodCallIgnored
            parent.mkdirs();
        }
        FileOutputStream out = null;
        try {
            out = new FileOutputStream(f, false);
            out.write(data);
            out.flush();
        } finally {
            closeQuietly(out);
        }
    }

    public static byte[] readFileBytes(File f) throws IOException {
        InputStream in = null;
        try {
            in = new FileInputStream(f);
            return readAllBytes(in);
        } finally {
            closeQuietly(in);
        }
    }

    /** 备份列表，按时间从新到旧 */
    public static List<BackupFile> listBackupFiles(Context ctx) {
        ArrayList<BackupFile> out = new ArrayList<>();
        for (File f : listBackups(ctx)) {
            out.add(parseBackup(f));
        }
        Collections.sort(out, new Comparator<BackupFile>() {
            @Override
            public int compare(BackupFile a, BackupFile b) {
                return Long.compare(b.time, a.time);
            }
        });
        return out;
    }

    private static List<File> listBackups(Context ctx) {
        ArrayList<File> out = new ArrayList<>();
        File[] files = backupDir(ctx).listFiles();
        if (files == null) {
            return out;
        }
        for (File f : files) {
            if (f.isFile() && f.getName().startsWith(PREFIX) && f.getName().endsWith(SUFFIX)) {
                out.add(f);
            }
        }
        Collections.sort(out, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                return Long.compare(a.lastModified(), b.lastModified());
            }
        });
        return out;
    }

    public static class BackupFile {
        public File file;
        public long time;
        public int count = -1;

        public long size() {
            return file == null ? 0 : file.length();
        }
    }

    private static BackupFile parseBackup(File f) {
        BackupFile b = new BackupFile();
        b.file = f;
        b.time = f.lastModified();
        String name = f.getName();
        // MATOlog-20260920-173301-17.json
        try {
            String body = name.substring(PREFIX.length(), name.length() - SUFFIX.length());
            String[] parts = body.split("-");
            if (parts.length >= 3) {
                String date = parts[0];
                String clock = parts[1];
                String cnt = parts[2];
                b.count = Integer.parseInt(cnt);
                int y = Integer.parseInt(date.substring(0, 4));
                int mo = Integer.parseInt(date.substring(4, 6));
                int d = Integer.parseInt(date.substring(6, 8));
                int hh = Integer.parseInt(clock.substring(0, 2));
                int mm = Integer.parseInt(clock.substring(2, 4));
                int ss = Integer.parseInt(clock.substring(4, 6));
                java.util.Calendar c = java.util.Calendar.getInstance();
                c.set(y, mo - 1, d, hh, mm, ss);
                c.set(java.util.Calendar.MILLISECOND, 0);
                b.time = c.getTimeInMillis();
            }
        } catch (Exception ignored) {
        }
        if (b.count < 0) {
            b.count = countInFileSafe(f);
        }
        return b;
    }

    private static int countInFileSafe(File f) {
        try {
            EventCodec.Payload p = EventCodec.decode(readFile(f));
            return p.events.size();
        } catch (Exception e) {
            return -1;
        }
    }

    public static int countOf(File f) {
        return countInFileSafe(f);
    }

    /** 从备份文件恢复 */
    public static List<Event> readBackup(File f) throws Exception {
        return EventCodec.decode(readFile(f)).events;
    }

    // ---------------- 文件读写 ----------------

    public static void writeFile(File f, String content) throws IOException {
        File parent = f.getParentFile();
        if (parent != null && !parent.exists()) {
            //noinspection ResultOfMethodCallIgnored
            parent.mkdirs();
        }
        Writer w = null;
        try {
            w = new OutputStreamWriter(new FileOutputStream(f, false), UTF8.newEncoder());
            w.write(content);
            w.flush();
        } finally {
            closeQuietly(w);
        }
    }

    public static String readFile(File f) throws IOException {
        InputStream in = null;
        try {
            in = new FileInputStream(f);
            return readStream(in);
        } finally {
            closeQuietly(in);
        }
    }

    /** 写入外部目标（SAF 导出的 Uri） */
    public static void writeUri(ContentResolver cr, Uri uri, String content) throws IOException {
        OutputStream os = null;
        Writer w = null;
        try {
            os = cr.openOutputStream(uri, "wt");
            if (os == null) {
                throw new IOException("openOutputStream returned null");
            }
            w = new OutputStreamWriter(os, UTF8.newEncoder());
            w.write(content);
            w.flush();
        } finally {
            closeQuietly(w);
            closeQuietly(os);
        }
    }

    public static String readUri(ContentResolver cr, Uri uri) throws IOException {
        InputStream in = null;
        try {
            in = cr.openInputStream(uri);
            if (in == null) {
                throw new IOException("openInputStream returned null");
            }
            return readStream(in);
        } finally {
            closeQuietly(in);
        }
    }

    private static String readStream(InputStream in) throws IOException {
        BufferedReader r = new BufferedReader(new InputStreamReader(in, UTF8.newDecoder()));
        StringBuilder sb = new StringBuilder(4096);
        char[] buf = new char[4096];
        int n;
        while ((n = r.read(buf)) > 0) {
            sb.append(buf, 0, n);
        }
        return sb.toString();
    }

    // ---------------- 字节级读写（加密导出用）----------------

    public static byte[] readAllBytes(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            bos.write(buf, 0, n);
        }
        return bos.toByteArray();
    }

    public static byte[] readUriBytes(ContentResolver cr, Uri uri) throws IOException {
        InputStream in = null;
        try {
            in = cr.openInputStream(uri);
            if (in == null) {
                throw new IOException("openInputStream returned null");
            }
            return readAllBytes(in);
        } finally {
            closeQuietly(in);
        }
    }

    public static void writeUriBytes(ContentResolver cr, Uri uri, byte[] data) throws IOException {
        OutputStream os = null;
        try {
            os = cr.openOutputStream(uri, "wt");
            if (os == null) {
                throw new IOException("openOutputStream returned null");
            }
            os.write(data);
            os.flush();
        } finally {
            closeQuietly(os);
        }
    }

    /** 读取 Uri 的显示名，失败返回 null */
    public static String displayName(ContentResolver cr, Uri uri) {
        Cursor c = null;
        try {
            c = cr.query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null);
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) {
                    return c.getString(idx);
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (c != null) {
                c.close();
            }
        }
        String last = uri.getLastPathSegment();
        return last == null ? null : last;
    }

    public static String suggestedFileName(long stamp) {
        return String.format(Locale.US, "MATOlog-%s.json", DateUtil.fileStamp(stamp));
    }

    private static void closeQuietly(java.io.Closeable c) {
        if (c != null) {
            try {
                c.close();
            } catch (Exception ignored) {
            }
        }
    }

    /** 仅为兼容性保留：把字节数组转字符串（未使用，供未来扩展） */
    public static String fromBytes(byte[] data) {
        return new String(data, UTF8);
    }

    /** 仅为兼容性保留：把字符串转字节数组 */
    public static byte[] toBytes(String s) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try {
            bos.write(s.getBytes(UTF8));
        } catch (Exception ignored) {
        }
        return bos.toByteArray();
    }
}
