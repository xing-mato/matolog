package com.MATO.log.util;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * 存敏感短字符串（主要是 API Key）用的加解密封装。
 *
 * <p><b>为什么不复用 {@link SecurityHelper} 那套</b>：那一套的密钥来自「用户口令」——
 * 用户输入口令，派生出 AES 密钥。但存 API Key 时没有口令可依赖：后台整理任务是
 * 无人值守跑的，不可能每次都让用户输一遍。若为了复用而把口令也存下来，
 * 等于把钥匙和锁放在一起，加密就退化成了一层障眼法。
 *
 * <p><b>所以换一种密钥来源</b>：用系统 KeyStore 里一把**永远导不出应用之外**的 AES 密钥。
 * 密文落盘，密钥由系统的 KeyStore 守护（在有硬件支持时由 TEE/StrongBox 保管），
 * 应用自己都读不到密钥本身，只能请求它做加解密。这是 Android 上存这类凭据的标准做法，
 * 而且系统自带、不需要任何依赖。
 *
 * <p><b>算法沿用工程既有那套</b>：AES/GCM/NoPadding + 随机 IV + 盐/IV 前置的封装结构，
 * 与 {@link SecurityHelper} 的导出加密保持一致，读起来不用切换思维。
 *
 * <p><b>失败就降级，不让功能不可用</b>：极少数 ROM 上 KeyStore 会异常（定制系统、
 * 首次开机密钥还没就绪）。这时退回明文存储，并记一个标记，由设置页**如实告诉用户**，
 * 而不是把 Key 弄丢让用户以为配置没生效、反复重填。
 */
public final class SafeStore {

    private static final String ANDROID_KEYSTORE = "AndroidKeyStore";
    private static final String KEY_ALIAS = "matolog_field_key";
    private static final String TRANSFORM = "AES/GCM/NoPadding";
    private static final int GCM_TAG_BITS = 128;
    private static final int KEY_BITS = 256;

    /** 密文前缀：用来区分「加密过的值」和「历史遗留的明文值」 */
    private static final String PREFIX = "enc:";
    /** 降级标记所在键 */
    private static final String FLAG_PLAIN = "secure_degraded";

    private SafeStore() {
    }

    /**
     * 加密后写回同一个键。
     *
     * @param owner 该键所属的 SharedPreferences（降级标记也记在这里）
     */
    public static void put(Context c, SharedPreferences owner, String key, String value) {
        String v = value == null ? "" : value;
        if (v.isEmpty()) {
            owner.edit().putString(key, "").apply();
            return;
        }
        String enc = encrypt(v);
        if (enc == null) {
            owner.edit().putString(key, v).putBoolean(FLAG_PLAIN, true).apply();
        } else {
            owner.edit().putString(key, PREFIX + enc).putBoolean(FLAG_PLAIN, false).apply();
        }
    }

    /**
     * 读回明文。
     *
     * <p>没有前缀的值原样返回 —— 这样即便某次写入走了降级路径，读的时候也不会出错。
     * 解密失败（换过设备、清过 KeyStore）时返回空串而不是假装有值：
     * 界面会显示「未填写」，用户重填一次即可，比拿着一段解不开的密文去发请求要好。
     */
    public static String get(Context c, SharedPreferences owner, String key) {
        String raw = owner.getString(key, "");
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        if (!raw.startsWith(PREFIX)) {
            return raw;
        }
        String plain = decrypt(raw.substring(PREFIX.length()));
        return plain == null ? "" : plain;
    }

    /** 当前是否处于「加密不可用、只能明文存」的降级状态；界面上要如实说明 */
    public static boolean degraded(Context c, SharedPreferences owner) {
        return owner.getBoolean(FLAG_PLAIN, false);
    }

    // ---------------- 内部 ----------------

    private static SecretKey key() {
        try {
            KeyStore ks = KeyStore.getInstance(ANDROID_KEYSTORE);
            ks.load(null);
            KeyStore.Entry e = ks.getEntry(KEY_ALIAS, null);
            if (e instanceof KeyStore.SecretKeyEntry) {
                return ((KeyStore.SecretKeyEntry) e).getSecretKey();
            }
            KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,
                    ANDROID_KEYSTORE);
            kg.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(KEY_BITS)
                    // 不要求用户认证：后台整理是无人值守的，弹指纹就没法自动跑了
                    .setUserAuthenticationRequired(false)
                    .build());
            return kg.generateKey();
        } catch (Throwable t) {
            return null;
        }
    }

    /** 布局：1 字节 IV 长度 ‖ IV ‖ GCM 密文（含 tag），整体 base64 */
    private static String encrypt(String plain) {
        try {
            SecretKey k = key();
            if (k == null) {
                return null;
            }
            Cipher ci = Cipher.getInstance(TRANSFORM);
            ci.init(Cipher.ENCRYPT_MODE, k);
            byte[] iv = ci.getIV();
            byte[] body = ci.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[1 + iv.length + body.length];
            out[0] = (byte) iv.length;
            System.arraycopy(iv, 0, out, 1, iv.length);
            System.arraycopy(body, 0, out, 1 + iv.length, body.length);
            return Base64.encodeToString(out, Base64.NO_WRAP);
        } catch (Throwable t) {
            return null;
        }
    }

    private static String decrypt(String b64) {
        try {
            SecretKey k = key();
            if (k == null) {
                return null;
            }
            byte[] in = Base64.decode(b64, Base64.NO_WRAP);
            int ivLen = in[0] & 0xFF;
            if (ivLen <= 0 || ivLen + 1 > in.length) {
                return null;
            }
            byte[] iv = new byte[ivLen];
            System.arraycopy(in, 1, iv, 0, ivLen);
            Cipher ci = Cipher.getInstance(TRANSFORM);
            ci.init(Cipher.DECRYPT_MODE, k, new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] plain = ci.doFinal(in, 1 + ivLen, in.length - 1 - ivLen);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (Throwable t) {
            return null;
        }
    }
}
