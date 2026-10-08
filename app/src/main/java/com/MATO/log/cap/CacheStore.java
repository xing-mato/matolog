package com.MATO.log.cap;

import android.content.Context;

import java.io.File;
import java.util.Locale;

/**
 * 本版本产生的缓存，统一放在一个**单独目录**下（需求原文：需要一个单独的文件夹放置可能产生的缓存）。
 *
 * <pre>
 *   &lt;应用专属目录&gt;/mato-cache/
 *       tmp/         导出、导入等中间产物
 *       README.txt   目录用途说明，用户用文件管理器打开时能看到
 * </pre>
 *
 * <p>1.3.5 删掉「自动整理」之后，原来的 {@code logs/}（AI 请求日志 ai.log）
 * 不再产生新内容；老设备上那个目录可能还留着，应用里的「缓存目录」页可以一并清掉。
 *
 * <p>原先还有一个 {@code frames/}（屏幕分析抽下来的帧）。屏幕分析已在 1.3 移除，
 * 这个子目录不再产生新内容；老设备上可能还留着，应用里的「缓存目录」页可以一并清掉。
 * 目录名也从 {@code beta-cache} 改成了 {@code mato-cache}（1.3 转正），
 * 老目录会留在原处不再增长 —— 它是缓存，删不删都不影响记录。
 *
 * <p><b>为什么放 getExternalFilesDir 而不是内部私有目录</b>：这是「缓存」不是「数据」。
 * 放在应用专属外部目录（Android/data/&lt;包名&gt;/files/）有两个好处：
 * 用户插上数据线或用文件管理器就能自己查看、自己删，不必依赖本应用提供清理入口；
 * 而且从 Android 10 起这个目录不需要任何存储权限，卸载应用时系统会一并清掉，不留垃圾。
 * 拿到不到外部目录时（极少数情况）自动退回内部私有目录，功能不受影响。
 *
 * <p><b>和数据库分开</b>：记录本身在 SQLite 里，删缓存绝不会动到用户的记录。
 */
public final class CacheStore {

    private static final String ROOT = "mato-cache";

    private CacheStore() {
    }

    /** 缓存根目录；永远返回一个可用目录，外部存储不可用时退回内部 */
    public static File root(Context c) {
        File ext = c.getExternalFilesDir(null);
        File base = (ext != null) ? ext : c.getFilesDir();
        File dir = new File(base, ROOT);
        ensure(dir);
        return dir;
    }

    public static File tmpDir(Context c) {
        File d = new File(root(c), "tmp");
        ensure(d);
        return d;
    }

    /**
     * 目录用途说明；用户打开这个文件夹时能明白里面是什么、能删。
     *
     * <p>「内容变了才重写」，不是「存在就跳过」：1.3.5 删掉自动整理之后这份说明
     * 变了，而老设备上还躺着上一版写下的 README.txt —— 跳过就等于让用户对着
     * 一份描述 logs/ 的旧说明发呆。
     */
    public static void writeReadme(Context c) {
        File f = new File(root(c), "README.txt");
        String text = "MATOlog 的缓存目录\n"
                + "========================\n\n"
                + "这个目录里放的都是「可以随时删掉」的中间文件，删掉不会影响\n"
                + "已经记录下来的事件，也不会影响任何设置。\n\n"
                + "tmp/      导入导出等操作过程中的临时文件。\n\n"
                + "在应用里「设置 → 缓存目录」可以看到占用大小并一键清空。\n";
        if (f.exists() && text.equals(Txt.read(f))) {
            return;
        }
        Txt.writeQuietly(f, text);
    }

    // ---------------- 统计与清理 ----------------

    public static long sizeBytes(Context c) {
        return size(root(c));
    }

    public static long size(File f) {
        if (f == null || !f.exists()) {
            return 0;
        }
        if (f.isFile()) {
            return f.length();
        }
        File[] kids = f.listFiles();
        if (kids == null) {
            return 0;
        }
        long sum = 0;
        for (File k : kids) {
            sum += size(k);
        }
        return sum;
    }

    public static int fileCount(Context c) {
        return count(root(c));
    }

    public static int count(File f) {
        if (f == null || !f.exists()) {
            return 0;
        }
        if (f.isFile()) {
            return 1;
        }
        File[] kids = f.listFiles();
        if (kids == null) {
            return 0;
        }
        int n = 0;
        for (File k : kids) {
            n += count(k);
        }
        return n;
    }

    /** 清空缓存内容，但保留目录本身与 README */
    public static int clear(Context c) {
        return clearInner(root(c), true);
    }

    private static int clearInner(File dir, boolean keepReadme) {
        File[] kids = dir.listFiles();
        if (kids == null) {
            return 0;
        }
        int n = 0;
        for (File f : kids) {
            if (keepReadme && "README.txt".equals(f.getName())) {
                continue;
            }
            if (deleteDeep(f)) {
                n++;
            }
        }
        return n;
    }

    private static boolean deleteDeep(File f) {
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) {
                for (File k : kids) {
                    deleteDeep(k);
                }
            }
        }
        return f.delete();
    }

    /** 人类可读的大小 */
    public static String human(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return String.format(Locale.US, "%.1f KB", bytes / 1024.0);
        }
        return String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0));
    }

    private static void ensure(File d) {
        if (!d.exists()) {
            //noinspection ResultOfMethodCallIgnored
            d.mkdirs();
        }
    }
}
