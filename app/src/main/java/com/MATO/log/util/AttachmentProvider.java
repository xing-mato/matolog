package com.MATO.log.util;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.webkit.MimeTypeMap;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * 把应用内的附件以 content:// 形式交给系统应用打开。
 *
 * 为什么不直接用 FileProvider：那是支持库（support-v4 / AndroidX）里的类，
 * 本工程按需求不引任何三方依赖；而只在清单里声明、包里却没有实现类的写法会让
 * 应用在启动装 provider 时直接 ClassNotFoundException 崩溃。所以这里自己写一个
 * 只读、只暴露 files/attachments/ 一个目录的最小实现。
 *
 * 地址形如：content://com.MATO.log.fileprovider/attachments/&lt;内部文件名&gt;
 * provider 本身 exported=false，只有本应用显式授权（FLAG_GRANT_READ_URI_PERMISSION）
 * 的那个读取方才打得开。
 */
public class AttachmentProvider extends ContentProvider {

    /** 与清单里 authorities 的后缀保持一致 */
    public static final String AUTHORITY_SUFFIX = ".fileprovider";
    public static final String PATH = "attachments";

    public static Uri uriFor(android.content.Context ctx, String localName) {
        return Uri.parse("content://" + ctx.getPackageName() + AUTHORITY_SUFFIX + "/" + PATH + "/"
                + Uri.encode(localName));
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    /** 只允许读，写操作一概不接 */
    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public String getType(Uri uri) {
        File f = resolve(uri);
        if (f == null || !f.isFile()) {
            return null;
        }
        String name = uri.getLastPathSegment();
        int dot = name == null ? -1 : name.lastIndexOf('.');
        if (dot >= 0 && dot < name.length() - 1) {
            String mime = MimeTypeMap.getSingleton()
                    .getMimeTypeFromExtension(name.substring(dot + 1).toLowerCase());
            if (mime != null) {
                return mime;
            }
        }
        return "application/octet-stream";
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs,
                        String sortOrder) {
        File f = resolve(uri);
        if (f == null || !f.isFile()) {
            return null;
        }
        String[] cols = projection != null ? projection
                : new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
        MatrixCursor c = new MatrixCursor(cols, 1);
        MatrixCursor.RowBuilder row = c.newRow();
        for (String col : cols) {
            if (OpenableColumns.DISPLAY_NAME.equals(col)) {
                row.add(f.getName());
            } else if (OpenableColumns.SIZE.equals(col)) {
                row.add(Long.valueOf(f.length()));
            } else {
                row.add(null);
            }
        }
        return c;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (mode != null && mode.contains("w")) {
            throw new FileNotFoundException("只读");
        }
        File f = resolve(uri);
        if (f == null || !f.isFile()) {
            throw new FileNotFoundException("附件不存在");
        }
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    /** 有些应用的 ACTION_VIEW 走的是流类型而不是 getType */
    @Override
    public String[] getStreamTypes(Uri uri, String mimeTypeFilter) {
        String t = getType(uri);
        return t == null ? null : new String[]{t};
    }

    /**
     * 把地址还原成文件。
     * 只认 files/attachments/ 底下的直接子文件：文件名里不许出现路径分隔符，
     * 再核对一次规范路径确实落在附件目录内，防止越权读到别的文件。
     */
    private File resolve(Uri uri) {
        if (uri == null) {
            return null;
        }
        String name = uri.getLastPathSegment();
        if (name == null || name.length() == 0) {
            return null;
        }
        if (name.indexOf('/') >= 0 || name.indexOf('\\') >= 0 || name.contains("..")) {
            return null;
        }
        if (getContext() == null) {
            return null;
        }
        File dir = AttachmentStore.dir(getContext());
        File f = new File(dir, name);
        try {
            String base = dir.getCanonicalPath();
            String path = f.getCanonicalPath();
            if (!path.startsWith(base + File.separator)) {
                return null;
            }
        } catch (Exception e) {
            return null;
        }
        return f;
    }
}
