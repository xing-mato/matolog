package com.MATO.log.util;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import java.security.MessageDigest;
import java.security.SecureRandom;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * 安全层：应用锁口令 + 导出文件加密。
 *
 * <p>导出文件有两种格式，导入时必须都认：1.3.1 起的格式（{@link #encryptFixed}）与
 * 1.3 及更早的口令格式（{@link #encrypt}）。两者以文件头的魔数区分，
 * 判断顺序上先查前者。
 */
public final class SecurityHelper {

    private static final String PREF = "matolog_security";
    private static final String KEY_HASH = "pass_hash";
    private static final String KEY_SALT = "pass_salt";
    private static final String KEY_ASKED = "asked";

    /**
     * 1.3 及更早的加密文件魔数：密钥来自用户口令。
     *
     * <p>导入这种文件时还需要问一次口令。1.3.1 起不再产生它，但导入侧一直认得 ——
     * 用户手里可能就存着这样一份导出。
     */
    private static final byte[] MAGIC = {'M', 'A', 'T', 'O', 'L', 'O', 'G', '1'};

    /**
     * 1.3.1 起的加密文件魔数。
     *
     * <p>与 {@link #MAGIC} 只差最后一个字节，所以判断顺序上必须先看它 ——
     * 见 {@link #isSelfContained}。
     */
    private static final byte[] MAGIC_SELF = {'M', 'A', 'T', 'O', 'L', 'O', 'G', 'M'};

    private static final int PBKDF2_ROUNDS = 12000;
    private static final int KEY_BITS = 256;
    private static final int GCM_TAG_BITS = 128;
    private static final int SALT_LEN = 16;
    private static final int IV_LEN = 12;

    private SecurityHelper() {
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    public static boolean hasPasscode(Context ctx) {
        String h = prefs(ctx).getString(KEY_HASH, null);
        return h != null && h.length() > 0;
    }

    /** 是否已经问过用户「要不要上锁」 */
    public static boolean hasAsked(Context ctx) {
        return prefs(ctx).getBoolean(KEY_ASKED, false);
    }

    public static void markAsked(Context ctx) {
        prefs(ctx).edit().putBoolean(KEY_ASKED, true).apply();
    }

    public static void setPasscode(Context ctx, String pass) {
        byte[] salt = new byte[SALT_LEN];
        new SecureRandom().nextBytes(salt);
        String hash = hash(pass, salt);
        prefs(ctx).edit()
                .putString(KEY_HASH, hash)
                .putString(KEY_SALT, Base64.encodeToString(salt, Base64.NO_WRAP))
                .putBoolean(KEY_ASKED, true)
                .apply();
    }

    public static void clearPasscode(Context ctx) {
        prefs(ctx).edit().remove(KEY_HASH).remove(KEY_SALT).apply();
    }

    public static boolean verify(Context ctx, String pass) {
        String stored = prefs(ctx).getString(KEY_HASH, null);
        String saltB64 = prefs(ctx).getString(KEY_SALT, null);
        if (stored == null || saltB64 == null) {
            return false;
        }
        try {
            byte[] salt = Base64.decode(saltB64, Base64.NO_WRAP);
            return stored.equals(hash(pass, salt));
        } catch (Exception e) {
            return false;
        }
    }

    private static String hash(String pass, byte[] salt) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(salt);
            md.update(pass.getBytes(Storage.UTF8));
            byte[] out = md.digest();
            StringBuilder sb = new StringBuilder(out.length * 2);
            for (byte b : out) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    // ---------------- 导出加密 ----------------

    /** 这份文件是不是「加密导出」（两种格式都算） */
    public static boolean isEncrypted(byte[] data) {
        return isSelfContained(data) || startsWith(data, MAGIC);
    }

    /**
     * 是不是 1.3.1 起的格式。
     *
     * <p>它和 {@link #MAGIC} 只差最后一个字节（{@code 1} 对 {@code M}），所以必须先判它。
     */
    public static boolean isSelfContained(byte[] data) {
        return startsWith(data, MAGIC_SELF);
    }

    private static boolean startsWith(byte[] data, byte[] head) {
        if (data == null || data.length < head.length) {
            return false;
        }
        for (int i = 0; i < head.length; i++) {
            if (data[i] != head[i]) {
                return false;
            }
        }
        return true;
    }

    /** 用口令把明文 JSON 封装成密文（魔数 + 盐 + IV + GCM 密文） */
    public static byte[] encrypt(String plain, String pass) throws Exception {
        byte[] salt = new byte[SALT_LEN];
        byte[] iv = new byte[IV_LEN];
        SecureRandom rnd = new SecureRandom();
        rnd.nextBytes(salt);
        rnd.nextBytes(iv);

        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, derive(pass, salt), new GCMParameterSpec(GCM_TAG_BITS, iv));
        byte[] body = c.doFinal(plain.getBytes(Storage.UTF8));

        byte[] out = new byte[MAGIC.length + SALT_LEN + IV_LEN + body.length];
        System.arraycopy(MAGIC, 0, out, 0, MAGIC.length);
        System.arraycopy(salt, 0, out, MAGIC.length, SALT_LEN);
        System.arraycopy(iv, 0, out, MAGIC.length + SALT_LEN, IV_LEN);
        System.arraycopy(body, 0, out, MAGIC.length + SALT_LEN + IV_LEN, body.length);
        return out;
    }

    /** 口令错误或文件损坏都会抛异常 */
    public static String decrypt(byte[] data, String pass) throws Exception {
        int off = MAGIC.length;
        byte[] salt = new byte[SALT_LEN];
        byte[] iv = new byte[IV_LEN];
        System.arraycopy(data, off, salt, 0, SALT_LEN);
        System.arraycopy(data, off + SALT_LEN, iv, 0, IV_LEN);
        int bodyLen = data.length - off - SALT_LEN - IV_LEN;
        byte[] body = new byte[bodyLen];
        System.arraycopy(data, off + SALT_LEN + IV_LEN, body, 0, bodyLen);

        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, derive(pass, salt), new GCMParameterSpec(GCM_TAG_BITS, iv));
        return new String(c.doFinal(body), Storage.UTF8);
    }

    private static SecretKey derive(String pass, byte[] salt) throws Exception {
        SecretKeyFactory f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1");
        PBEKeySpec spec = new PBEKeySpec(pass.toCharArray(), salt, PBKDF2_ROUNDS, KEY_BITS);
        try {
            return new SecretKeySpec(f.generateSecret(spec).getEncoded(), "AES");
        } finally {
            spec.clearPassword();
        }
    }

    // ---------------- 导出加密（1.3.1 起的格式） ----------------

    /** 派生密钥用的应用标识 */
    private static final String APP_TAG = "MATOlog/export/1";

    /**
     * 把明文 JSON 封装成加密文件。
     *
     * <p>布局：{@code MAGIC_SELF(8) + 盐(16) + IV(12) + GCM 密文}。
     * 导入侧凭文件头认出它，不需要用户提供任何东西 —— 换手机、重装、清数据之后一样解得开。
     */
    public static byte[] encryptFixed(String plain) throws Exception {
        byte[] salt = new byte[SALT_LEN];
        byte[] iv = new byte[IV_LEN];
        SecureRandom rnd = new SecureRandom();
        rnd.nextBytes(salt);
        rnd.nextBytes(iv);

        // 盐与 IV 每次导出都重新随机：同一份明文两次导出不该产生相同的密文
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, derive(APP_TAG, salt), new GCMParameterSpec(GCM_TAG_BITS, iv));
        byte[] body = c.doFinal(plain.getBytes(Storage.UTF8));

        byte[] out = new byte[MAGIC_SELF.length + SALT_LEN + IV_LEN + body.length];
        System.arraycopy(MAGIC_SELF, 0, out, 0, MAGIC_SELF.length);
        System.arraycopy(salt, 0, out, MAGIC_SELF.length, SALT_LEN);
        System.arraycopy(iv, 0, out, MAGIC_SELF.length + SALT_LEN, IV_LEN);
        System.arraycopy(body, 0, out, MAGIC_SELF.length + SALT_LEN + IV_LEN, body.length);
        return out;
    }

    /**
     * 解开 1.3.1 起的加密文件。
     *
     * <p>不是这种格式、或内容被改动过（GCM 校验不过）都返回 null，
     * 由调用方决定接下来怎么办（1.3 的口令格式就转去问口令）。
     */
    public static String decryptFixed(byte[] data) {
        if (!isSelfContained(data)) {
            return null;
        }
        try {
            int off = MAGIC_SELF.length;
            byte[] salt = new byte[SALT_LEN];
            byte[] iv = new byte[IV_LEN];
            System.arraycopy(data, off, salt, 0, SALT_LEN);
            System.arraycopy(data, off + SALT_LEN, iv, 0, IV_LEN);
            int bodyLen = data.length - off - SALT_LEN - IV_LEN;
            if (bodyLen <= 0) {
                return null;
            }
            byte[] body = new byte[bodyLen];
            System.arraycopy(data, off + SALT_LEN + IV_LEN, body, 0, bodyLen);

            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, derive(APP_TAG, salt),
                    new GCMParameterSpec(GCM_TAG_BITS, iv));
            return new String(c.doFinal(body), Storage.UTF8);
        } catch (Throwable t) {
            // 解开失败是预期内的情形（文件被改过、或根本不是这种格式），不是异常路径
            return null;
        }
    }
}
