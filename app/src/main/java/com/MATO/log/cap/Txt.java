package com.MATO.log.cap;

import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * 极小的文本/字节文件读写工具。
 *
 * 工程不引三方依赖，这些几行的事就直接写，不为了一个 readString 去拉一个库。
 * 所有方法都不抛异常：读失败返回空串，写失败返回 false —— 调用方是记录服务和界面，
 * 一个读写失败不应该把整个流程带崩。
 */
public final class Txt {

    static final int MAX_TEXT = 4 * 1024 * 1024;

    private Txt() {
    }

    public static String read(File f) {
        if (f == null || !f.exists() || !f.isFile()) {
            return "";
        }
        InputStream in = null;
        try {
            in = new FileInputStream(f);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            int total = 0;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > MAX_TEXT) {
                    break;
                }
                bos.write(buf, 0, n);
            }
            return new String(bos.toByteArray(), StandardCharsets.UTF_8);
        } catch (Throwable t) {
            return "";
        } finally {
            close(in);
        }
    }

    public static boolean write(File f, String text) {
        if (f == null) {
            return false;
        }
        File parent = f.getParentFile();
        if (parent != null && !parent.exists()) {
            //noinspection ResultOfMethodCallIgnored
            parent.mkdirs();
        }
        OutputStream out = null;
        try {
            out = new FileOutputStream(f, false);
            out.write((text == null ? "" : text).getBytes(StandardCharsets.UTF_8));
            out.flush();
            return true;
        } catch (Throwable t) {
            return false;
        } finally {
            close(out);
        }
    }

    public static boolean append(File f, String text) {
        if (f == null) {
            return false;
        }
        File parent = f.getParentFile();
        if (parent != null && !parent.exists()) {
            //noinspection ResultOfMethodCallIgnored
            parent.mkdirs();
        }
        OutputStream out = null;
        try {
            out = new FileOutputStream(f, true);
            out.write((text == null ? "" : text).getBytes(StandardCharsets.UTF_8));
            out.flush();
            return true;
        } catch (Throwable t) {
            return false;
        } finally {
            close(out);
        }
    }

    public static boolean writeQuietly(File f, String text) {
        return write(f, text);
    }

    public static byte[] readBytes(File f) {
        if (f == null || !f.exists() || !f.isFile()) {
            return null;
        }
        InputStream in = null;
        try {
            in = new FileInputStream(f);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            return bos.toByteArray();
        } catch (Throwable t) {
            return null;
        } finally {
            close(in);
        }
    }

    /** 读成 base64，给「把图片塞进 JSON」用 */
    public static String readBase64(File f) {
        byte[] b = readBytes(f);
        if (b == null) {
            return "";
        }
        return Base64.encodeToString(b, Base64.NO_WRAP);
    }

    public static boolean writeBytes(File f, byte[] data) {
        if (f == null || data == null) {
            return false;
        }
        File parent = f.getParentFile();
        if (parent != null && !parent.exists()) {
            //noinspection ResultOfMethodCallIgnored
            parent.mkdirs();
        }
        OutputStream out = null;
        try {
            out = new FileOutputStream(f, false);
            out.write(data);
            out.flush();
            return true;
        } catch (Throwable t) {
            return false;
        } finally {
            close(out);
        }
    }

    private static void close(Object o) {
        try {
            if (o instanceof InputStream) {
                ((InputStream) o).close();
            } else if (o instanceof OutputStream) {
                ((OutputStream) o).close();
            }
        } catch (Throwable ignored) {
        }
    }
}
