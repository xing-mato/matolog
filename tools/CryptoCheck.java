import com.MATO.log.util.SecurityHelper;

/** 加密导出的往返校验：AES-256-GCM + PBKDF2 */
public class CryptoCheck {

    static int pass = 0;
    static int fail = 0;

    static void check(String what, Object expected, Object actual) {
        boolean ok = expected == null ? actual == null : expected.equals(actual);
        if (ok) {
            pass++;
            System.out.println("[OK]   " + what);
        } else {
            fail++;
            System.out.println("[FAIL] " + what + " expected=<" + expected + "> actual=<" + actual + ">");
        }
    }

    public static void main(String[] args) throws Exception {
        String plain = "{\"app\":\"MATOlog\",\"events\":[{\"time\":1789315200000,\"text\":\"机密记录\"}]}";
        String passcode = "123456";

        byte[] enc = SecurityHelper.encrypt(plain, passcode);
        check("密文非空", Boolean.TRUE, Boolean.valueOf(enc.length > 0));
        check("密文比明文长(GCM tag)", Boolean.TRUE,
                Boolean.valueOf(enc.length > plain.getBytes("UTF-8").length));
        check("魔数识别为密文", Boolean.TRUE, Boolean.valueOf(SecurityHelper.isEncrypted(enc)));
        check("明文不识别为密文", Boolean.FALSE,
                Boolean.valueOf(SecurityHelper.isEncrypted(plain.getBytes("UTF-8"))));

        String back = SecurityHelper.decrypt(enc, passcode);
        check("正确口令解回原文", plain, back);

        boolean threw = false;
        try {
            SecurityHelper.decrypt(enc, "654321");
        } catch (Exception e) {
            threw = true;
        }
        check("错误口令被拒绝", Boolean.TRUE, Boolean.valueOf(threw));

        // 同一明文两次加密应当产生不同密文（随机盐 + 随机 IV）
        byte[] enc2 = SecurityHelper.encrypt(plain, passcode);
        check("随机盐/IV：两次密文不同", Boolean.FALSE,
                Boolean.valueOf(java.util.Arrays.equals(enc, enc2)));
        check("但都能解开", plain, SecurityHelper.decrypt(enc2, passcode));

        // 篡改一个字节应当解不开
        byte[] tampered = enc.clone();
        tampered[tampered.length - 3] ^= 0x01;
        threw = false;
        try {
            SecurityHelper.decrypt(tampered, passcode);
        } catch (Exception e) {
            threw = true;
        }
        check("篡改后无法解密", Boolean.TRUE, Boolean.valueOf(threw));

        // 中文 + emoji 内容
        String tricky = "多行\n文本 \"引号\" \\斜杠\\ \uD83D\uDE00";
        check("特殊字符往返", tricky,
                SecurityHelper.decrypt(SecurityHelper.encrypt(tricky, passcode), passcode));

        System.out.println("结果: pass=" + pass + " fail=" + fail);
        if (fail > 0) {
            System.exit(1);
        }
    }
}
