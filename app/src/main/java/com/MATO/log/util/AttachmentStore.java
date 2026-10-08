package com.MATO.log.util;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.webkit.MimeTypeMap;

import com.MATO.log.data.Attachment;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;

/**
 * 附带文件的落盘层。
 *
 * 两种存法：
 * - 「存进应用内」：把选中文件复制到 files/attachments/，之后跟原文件再无关系，原件删了也在；
 * - 「引用原文件」：本体不动，只登记它的 Uri，应用向系统要一个长期读取授权。
 *
 * 附件默认不参与导出；需要一起带走时由调用方把内容读出来内嵌进 JSON。
 */
public final class AttachmentStore {

    private static final String DIR = "attachments";

    private AttachmentStore() {
    }

    // ---------------- 目录与路径 ----------------

    public static File dir(Context ctx) {
        File d = new File(ctx.getFilesDir(), DIR);
        if (!d.exists()) {
            //noinspection ResultOfMethodCallIgnored
            d.mkdirs();
        }
        return d;
    }

    public static File fileOf(Context ctx, Attachment a) {
        if (a == null || !a.internal || a.localName == null || a.localName.length() == 0) {
            return null;
        }
        return new File(dir(ctx), a.localName);
    }

    /**
     * 内部存储的附件命名：`<时间戳>-<随机串>-<显示名>`。
     *
     * 为什么要把显示名留在文件名里：导出 JSON 只登记附件（默认不搬内容）时，
     * 同一台设备重新导入要能按名字把 files/attachments/ 里那份文件接回来 ——
     * 「导出 → 删除记录但保留附件 → 重新导入」这条路上全靠它。
     * 时间戳 + 随机串保证不撞车，显示名保证可回查。
     */
    public static String newLocalName(String displayName) {
        String safe = sanitizeForFileName(displayName);
        return System.currentTimeMillis() + "-" + Integer.toHexString(
                (int) (Math.random() * 0xFFFF + 1)) + "-" + safe;
    }

    /** 把显示名弄成能安全落盘的文件名（去路径分隔符、去控制字符、限长） */
    public static String sanitizeForFileName(String name) {
        if (name == null || name.trim().length() == 0) {
            return "附件";
        }
        StringBuilder sb = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            boolean bad = c == '/' || c == '\\' || c == ':' || c == '*' || c == '?'
                    || c == '"' || c == '<' || c == '>' || c == '|' || c < 0x20;
            sb.append(bad ? '_' : c);
        }
        String s = sb.toString().trim();
        if (s.length() == 0 || s.equals(".") || s.equals("..")) {
            return "附件";
        }
        if (s.length() > 60) {
            String ext = extensionOf(s);
            int keep = ext.length() == 0 ? 60 : Math.max(1, 60 - ext.length() - 1);
            s = s.substring(0, Math.min(keep, s.length())) + (ext.length() == 0 ? "" : "." + ext);
        }
        return s;
    }

    /** 生成名里那条尾巴（去掉时间戳与随机串） */
    private static String tailOf(String fileName) {
        java.util.regex.Matcher m = GENERATED.matcher(fileName);
        return m.matches() ? m.group(1) : null;
    }

    private static final java.util.regex.Pattern GENERATED =
            java.util.regex.Pattern.compile("^\\d{10,16}-[0-9a-fA-F]{1,8}-(.+)$");

    /**
     * 把「只带登记、没带内容」的内部附件接回本机已有的那份文件。
     *
     * 用在导入 / 恢复上：同一台设备重新导入自己导出的 JSON 时，附件本体本来就在
     * files/attachments/ 里（例如记录删了但选了保留附件），这时不该当成「读不到」。
     *
     * 匹配顺序：先按导出时记下的内部文件名，再按显示名在其后带「-数字」后缀的形态
     * （早期导出的文件里没写 local 字段，只能这样兜）。
     *
     * @return 接上了返回 true
     */
    public static boolean relinkFromLocal(Context ctx, Attachment a) {
        if (a == null || !a.internal) {
            return false;
        }
        if (a.localName != null && a.localName.length() > 0) {
            File f = new File(dir(ctx), a.localName);
            if (f.isFile() && f.length() > 0) {
                a.missing = false;
                a.uri = "";
                a.size = f.length();
                return true;
            }
        }
        File hit = findByName(ctx, a.name);
        if (hit == null) {
            return false;
        }
        a.localName = hit.getName();
        a.internal = true;
        a.uri = "";
        a.size = hit.length();
        a.missing = false;
        return true;
    }

    /** 按显示名找内部附件文件：`报告.pdf` ↔ `1758360000000-a1b2.pdf` */
    public static File findByName(Context ctx, String displayName) {
        File hit = matchIn(dir(ctx).listFiles(), displayName);
        return hit;
    }

    /**
     * 从一堆文件里挑出与显示名对应的那一个。
     *
     * 认这几种命名：
     * 1. 与显示名完全同名（用户手工放进去的）；
     * 2. `<时间戳>-<随机串>-<显示名>`（现在生成的形状）；
     * 3. `<时间戳>-<随机串>[.后缀]`（早期版本的形状，只能靠后缀猜）。
     *
     * 不碰 Android API，方便用纯 JVM 测试把匹配规则钉住（见 tools/AttachCheck）。
     */
    public static File matchIn(File[] files, String displayName) {
        if (files == null || displayName == null || displayName.length() == 0) {
            return null;
        }
        String ext = extensionOf(displayName);
        String nameNoExt = ext.length() == 0 ? displayName
                : displayName.substring(0, displayName.length() - ext.length() - 1);
        File byTail = null;
        File byExt = null;
        for (java.io.File f : files) {
            if (f == null || !f.isFile()) {
                continue;
            }
            String n = f.getName();
            if (n.equals(displayName)) {
                return f;
            }
            String tail = tailOf(n);
            if (tail != null) {
                if (tail.equals(displayName) || tail.equalsIgnoreCase(nameNoExt)) {
                    return f;
                }
                continue;
            }
            // 早期形状：时间戳-随机串[.后缀]
            java.util.regex.Matcher m = LEGACY.matcher(n);
            if (m.matches()) {
                String fileExt = m.group(3) == null ? "" : m.group(3);
                boolean sameExt = ext.length() == 0
                        ? fileExt.length() == 0
                        : ext.equalsIgnoreCase(fileExt);
                if (sameExt && byExt == null) {
                    byExt = f;
                }
            }
            if (byTail == null && n.equalsIgnoreCase(nameNoExt)) {
                byTail = f;
            }
        }
        return byTail != null ? byTail : byExt;
    }

    /** 早期形状：`<时间戳>-<随机串>`，可带 `.后缀`，也允许后面直接接名字 */
    private static final java.util.regex.Pattern LEGACY =
            java.util.regex.Pattern.compile("^\\d{10,16}-([0-9a-fA-F]{1,8})(\\.([0-9A-Za-z]{0,8}))?$");

    public static String extensionOf(String name) {
        if (name == null) {
            return "";
        }
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) {
            return "";
        }
        String ext = name.substring(dot + 1);
        if (ext.length() > 8) {
            return "";
        }
        for (int i = 0; i < ext.length(); i++) {
            char c = ext.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9');
            if (!ok) {
                return "";
            }
        }
        return ext.toLowerCase(Locale.US);
    }

    /** 把「引用原文件」登记进去的 Uri 还原出来；拿不到返回 null */
    public static Uri uriOf(Attachment a) {
        if (a == null || a.uri == null || a.uri.length() == 0) {
            return null;
        }
        try {
            return Uri.parse(a.uri);
        } catch (Exception e) {
            return null;
        }
    }

    // ---------------- 落地 ----------------

    /** 把用户选中的文件复制进应用私有目录，返回登记好的附件；失败返回 null */
    public static Attachment copyIn(Context ctx, Uri uri, long eventId) {
        if (uri == null) {
            return null;
        }
        ContentResolver cr = ctx.getContentResolver();
        String name = safeName(displayName(ctx, uri));
        String local = newLocalName(name);
        File target = new File(dir(ctx), local);
        InputStream in = null;
        OutputStream out = null;
        long written = 0;
        try {
            in = cr.openInputStream(uri);
            if (in == null) {
                return null;
            }
            out = new FileOutputStream(target, false);
            byte[] buf = new byte[16 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                written += n;
            }
            out.flush();
        } catch (Exception e) {
            //noinspection ResultOfMethodCallIgnored
            target.delete();
            return null;
        } finally {
            closeQuietly(in);
            closeQuietly(out);
        }

        Attachment a = new Attachment();
        a.eventId = eventId;
        a.name = name;
        a.mime = mimeOf(ctx, uri, name);
        a.size = written;
        a.internal = true;
        a.localName = local;
        a.uri = "";
        a.createdAt = System.currentTimeMillis();
        return a;
    }

    /** 只登记引用，不复制；要先把读取授权持久化，否则重启后就打不开了 */
    public static Attachment reference(Context ctx, Uri uri, long eventId) {
        if (uri == null) {
            return null;
        }
        String name = safeName(displayName(ctx, uri));
        Attachment a = new Attachment();
        a.eventId = eventId;
        a.name = name;
        a.mime = mimeOf(ctx, uri, name);
        a.size = sizeOf(ctx, uri);
        a.internal = false;
        a.localName = "";
        a.uri = uri.toString();
        a.createdAt = System.currentTimeMillis();
        persistRead(ctx, uri);
        return a;
    }

    /**
     * 把 Uri 的读取授权长期留住（重启后仍可读）。
     * 失败也无所谓——权限只在当次会话里有效时，下次打开会提示文件读不到。
     */
    public static boolean persistRead(Context ctx, Uri uri) {
        try {
            ctx.getContentResolver().takePersistableUriPermission(uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** 让系统文件选择器给出的授权不再需要 */
    public static void releaseRead(Context ctx, String uriString) {
        Uri uri = uriOf0(uriString);
        if (uri == null) {
            return;
        }
        try {
            ctx.getContentResolver().releasePersistableUriPermission(uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Exception ignored) {
        }
    }

    private static Uri uriOf0(String s) {
        if (s == null || s.length() == 0) {
            return null;
        }
        try {
            return Uri.parse(s);
        } catch (Exception e) {
            return null;
        }
    }

    // ---------------- 读 / 查 ----------------

    /** 把附件内容读出来；读不到返回 null */
    public static byte[] read(Context ctx, Attachment a) {
        if (a == null) {
            return null;
        }
        if (a.internal) {
            File f = fileOf(ctx, a);
            if (f == null || !f.isFile()) {
                return null;
            }
            try {
                return Storage.readFileBytes(f);
            } catch (Exception e) {
                return null;
            }
        }
        Uri uri = uriOf(a);
        if (uri == null) {
            return null;
        }
        InputStream in = null;
        try {
            in = ctx.getContentResolver().openInputStream(uri);
            if (in == null) {
                return null;
            }
            return Storage.readAllBytes(in);
        } catch (Exception e) {
            return null;
        } finally {
            closeQuietly(in);
        }
    }

    /** 内部附件的真实文件；localName 缺了（老数据）时按显示名兜一次 */
    private static File resolved(Context ctx, Attachment a) {
        File f = fileOf(ctx, a);
        if (f != null && f.isFile()) {
            return f;
        }
        // 登记里没有内部文件名时，按显示名在附件目录里找找看
        return findByName(ctx, a.name);
    }

    /** 内容是否还读得到 */
    public static boolean exists(Context ctx, Attachment a) {
        if (a == null) {
            return false;
        }
        if (a.internal) {
            File f = resolved(ctx, a);
            return f != null && f.isFile() && f.length() > 0;
        }
        Uri uri = uriOf(a);
        if (uri == null) {
            return false;
        }
        InputStream in = null;
        try {
            in = ctx.getContentResolver().openInputStream(uri);
            return in != null;
        } catch (Exception e) {
            return false;
        } finally {
            closeQuietly(in);
        }
    }

    /** 本体不在就抛异常，交给调用方提示 */
    public static void open(Context ctx, Attachment a) throws Exception {
        Uri uri;
        if (a.internal) {
            File f = resolved(ctx, a);
            if (f == null || !f.isFile()) {
                throw new java.io.FileNotFoundException("本地附件不存在");
            }
            // 顺手把找到的文件名补回登记，下次就不用再找一遍
            if (a.localName == null || a.localName.length() == 0) {
                a.localName = f.getName();
            }
            uri = shareUri(ctx, f);
            if (uri == null) {
                throw new java.io.FileNotFoundException("拿不到对外分享的地址");
            }
        } else {
            uri = uriOf(a);
            if (uri == null) {
                throw new java.io.FileNotFoundException("附件地址无效");
            }
        }
        Intent it = new Intent(Intent.ACTION_VIEW);
        it.setDataAndType(uri, a.mime == null || a.mime.length() == 0 ? "*/*" : a.mime);
        it.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        ctx.startActivity(Intent.createChooser(it, a.name));
    }

    /**
     * 把私有目录里的文件变成带临时授权的 content:// 地址。
     *
     * 走本工程自写的 {@link AttachmentProvider}（只读、只暴露 files/attachments/）。
     * 不用支持库的 FileProvider：那要引三方依赖，而且只在清单里声明、
     * 包里却没有实现类时会在启动装 provider 的一刻直接 ClassNotFoundException 崩溃。
     * 拿不到地址就返回 null，由调用方提示「打不开」。
     */
    public static Uri shareUri(Context ctx, File f) {
        if (f == null) {
            return null;
        }
        try {
            return AttachmentProvider.uriFor(ctx, f.getName());
        } catch (Throwable t) {
            return null;
        }
    }

    // ---------------- 删除 ----------------

    /** 删掉附件记录对应的文件（引用型只撤销授权，不动原文件） */
    public static void deleteFile(Context ctx, Attachment a) {
        if (a == null) {
            return;
        }
        if (a.internal) {
            File f = fileOf(ctx, a);
            if (f != null) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        } else {
            releaseRead(ctx, a.uri);
        }
    }

    /**
     * 把孤儿文件清掉：目录里存在、但没有任何记录指向的内部附件。
     * 返回清掉的字节数。
     */
    public static long sweepOrphans(Context ctx, java.util.List<Attachment> known) {
        java.util.HashSet<String> keep = new java.util.HashSet<>();
        if (known != null) {
            for (Attachment a : known) {
                if (a != null && a.internal && a.localName != null && a.localName.length() > 0) {
                    keep.add(a.localName);
                }
            }
        }
        long freed = 0;
        File[] files = dir(ctx).listFiles();
        if (files == null) {
            return 0;
        }
        for (File f : files) {
            if (f.isFile() && !keep.contains(f.getName())) {
                freed += f.length();
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        }
        return freed;
    }

    /** 内部附件占用的总字节数 */
    public static long usedBytes(Context ctx) {
        long total = 0;
        File[] files = dir(ctx).listFiles();
        if (files == null) {
            return 0;
        }
        for (File f : files) {
            if (f.isFile()) {
                total += f.length();
            }
        }
        return total;
    }

    /** 内部附件的实际个数（含没登记进库的残留，用来核对） */
    public static int fileCount(Context ctx) {
        File[] files = dir(ctx).listFiles();
        if (files == null) {
            return 0;
        }
        int n = 0;
        for (File f : files) {
            if (f.isFile()) {
                n++;
            }
        }
        return n;
    }

    // ---------------- 元信息 ----------------

    public static String displayName(Context ctx, Uri uri) {
        Cursor c = null;
        try {
            c = ctx.getContentResolver().query(uri,
                    new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null);
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0 && !c.isNull(idx)) {
                    return c.getString(idx);
                }
            }
        } catch (Exception ignored) {
        } finally {
            closeQuietly(c);
        }
        String last = uri.getLastPathSegment();
        return last == null ? "" : last;
    }

    public static long sizeOf(Context ctx, Uri uri) {
        Cursor c = null;
        try {
            c = ctx.getContentResolver().query(uri,
                    new String[]{OpenableColumns.SIZE}, null, null, null);
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.SIZE);
                if (idx >= 0 && !c.isNull(idx)) {
                    return c.getLong(idx);
                }
            }
        } catch (Exception ignored) {
        } finally {
            closeQuietly(c);
        }
        return 0;
    }

    /** Uri 的 MIME；系统不给就按扩展名猜 */
    public static String mimeOf(Context ctx, Uri uri, String name) {
        String mime = null;
        try {
            mime = ctx.getContentResolver().getType(uri);
        } catch (Exception ignored) {
        }
        if (mime == null || mime.length() == 0) {
            String ext = extensionOf(name);
            mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
        }
        return mime == null ? "" : mime;
    }

    private static String safeName(String name) {
        if (name == null || name.length() == 0) {
            return "附件";
        }
        // 只留文件名本身，别把路径分隔符带进来
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        String s = slash >= 0 ? name.substring(slash + 1) : name;
        return s.length() == 0 ? "附件" : s;
    }

    /** 文件选择器 Intent：挑一个文件作为附件 */
    public static Intent pickIntent() {
        Intent it = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        it.addCategory(Intent.CATEGORY_OPENABLE);
        it.setType("*/*");
        it.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        it.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        return it;
    }

    private static void closeQuietly(java.io.Closeable c) {
        if (c != null) {
            try {
                c.close();
            } catch (Exception ignored) {
            }
        }
    }

    /** 供导入端使用：把内嵌内容落成内部附件 */
    public static Attachment writeIn(Context ctx, long eventId, String name, String mime, byte[] data) {
        if (data == null || data.length == 0) {
            return null;
        }
        String safe = safeName(name);
        String local = newLocalName(safe);
        File target = new File(dir(ctx), local);
        try {
            Storage.writeFileBytes(target, data);
        } catch (Exception e) {
            return null;
        }
        Attachment a = new Attachment();
        a.eventId = eventId;
        a.name = safe;
        a.mime = mime == null ? "" : mime;
        a.size = data.length;
        a.internal = true;
        a.localName = local;
        a.uri = "";
        a.createdAt = System.currentTimeMillis();
        return a;
    }

    /** 读内部附件用的输入流（主要给自检用） */
    public static InputStream openIn(Context ctx, Attachment a) throws Exception {
        File f = fileOf(ctx, a);
        if (f == null || !f.isFile()) {
            throw new java.io.FileNotFoundException("附件不存在");
        }
        return new FileInputStream(f);
    }
}
