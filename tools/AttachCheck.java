import com.MATO.log.data.Attachment;
import com.MATO.log.data.Event;
import com.MATO.log.util.AttachmentStore;
import com.MATO.log.util.DateUtil;
import com.MATO.log.util.EventCodec;

import java.io.File;
import java.lang.reflect.Method;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 1.2 新增能力的校验：
 * - JSON v2 的附件登记与内嵌内容往返（默认不带内容 / 带上内容两种）
 * - 顶层 files 去重：同一份附件只编码一次
 * - 旧格式（只有 attachCount）仍能读进来
 * - 自带 base64 编解码的边界（长度 0/1/2/3 与二进制全字节）
 * - 导出区间换算（当天 / 本周 / 本月 / 本年 / 自选区间）
 */
public class AttachCheck {

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
        Charset utf8 = Charset.forName("UTF-8");

        // ---------- 1. 只登记、不搬内容 ----------
        Event e1 = new Event(0L, 1789315200000L, "带附件的记录", 1789315201000L);
        Attachment a1 = attach("报表.pdf", "application/pdf", 12345L, true);
        a1.id = 11;
        Attachment a2 = attach("现场照片.jpg", "image/jpeg", 987654L, false);
        a2.id = 12;
        e1.addAttachment(a1);
        e1.addAttachment(a2);
        e1.attachmentCount = 2;

        Event e2 = new Event(0L, 1789315300000L, "没有附件", 1789315301000L);

        List<Event> events = new ArrayList<Event>();
        events.add(e1);
        events.add(e2);

        String json = EventCodec.encode(events, 1789315700000L, true);
        check("默认导出不带内容(withFiles=false)", Boolean.TRUE,
                Boolean.valueOf(json.contains("\"withFiles\": false")));
        check("默认导出没有 files 段", Boolean.FALSE, Boolean.valueOf(json.contains("\"files\"")));

        EventCodec.Payload p = EventCodec.decode(json);
        check("登记往返：条数", Integer.valueOf(2), Integer.valueOf(p.events.size()));
        check("登记往返：附件个数", Integer.valueOf(2),
                Integer.valueOf(p.events.get(0).attachments.size()));
        check("登记往返：第二个附件名", "现场照片.jpg", p.events.get(0).attachments.get(1).name);
        check("登记往返：mime", "image/jpeg", p.events.get(0).attachments.get(1).mime);
        check("登记往返：存法", Boolean.FALSE,
                Boolean.valueOf(p.events.get(0).attachments.get(1).internal));
        check("登记往返：没有内容", Integer.valueOf(0), Integer.valueOf(p.carriedFiles));
        check("登记往返：只带登记的份数", Integer.valueOf(2), Integer.valueOf(p.registeredOnly));
        check("登记往返：无附件的事件", Integer.valueOf(0),
                Integer.valueOf(p.events.get(1).attachments == null
                        ? 0 : p.events.get(1).attachments.size()));
        check("version 写出为 2", Boolean.TRUE, Boolean.valueOf(json.contains("\"version\": 2")));

        // ---------- 2. 带上内容 ----------
        Map<Long, byte[]> payloads = new HashMap<Long, byte[]>();
        byte[] body = new byte[4096];
        for (int i = 0; i < body.length; i++) {
            body[i] = (byte) (i & 0xFF);
        }
        payloads.put(Long.valueOf(11), body);
        payloads.put(Long.valueOf(12), "第二个文件的内容".getBytes(utf8));

        String withFiles = EventCodec.encode(events, 1789315700000L, true, payloads);
        EventCodec.Payload p2 = EventCodec.decode(withFiles);
        check("带内容：withFiles=true", Boolean.TRUE,
                Boolean.valueOf(withFiles.contains("\"withFiles\": true")));
        check("带内容：份数", Integer.valueOf(2), Integer.valueOf(p2.files.size()));
        check("带内容：第一个文件字节一致", Boolean.TRUE,
                Boolean.valueOf(java.util.Arrays.equals(body,
                        p2.events.get(0).attachments.get(0).payload)));
        check("带内容：第二个文件字节一致", Boolean.TRUE,
                Boolean.valueOf(java.util.Arrays.equals("第二个文件的内容".getBytes(utf8),
                        p2.events.get(0).attachments.get(1).payload)));
        check("带内容：size 以实际内容为准", Integer.valueOf(4096),
                Integer.valueOf((int) p2.events.get(0).attachments.get(0).size));
        check("带内容：不再算只登记", Integer.valueOf(0), Integer.valueOf(p2.registeredOnly));
        check("带内容：carriedFiles", Integer.valueOf(2), Integer.valueOf(p2.carriedFiles));

        // ---------- 3. 同一份附件被两条记录共用时只编码一次 ----------
        Event e3 = new Event(0L, 1789315400000L, "又一条", 1789315401000L);
        Attachment shared = attach("同一份.bin", "application/octet-stream", 4096L, true);
        shared.id = 11;                      // 与 e1 的第一个附件同一个 id
        e3.addAttachment(shared);
        events.add(e3);

        String dedup = EventCodec.encode(events, 1789315700000L, true, payloads);
        EventCodec.Payload p3 = EventCodec.decode(dedup);
        check("复用附件：files 只有 2 份", Integer.valueOf(2), Integer.valueOf(p3.files.size()));
        check("复用附件：两条记录都指到同一份", Boolean.TRUE,
                Boolean.valueOf(java.util.Arrays.equals(
                        p3.events.get(0).attachments.get(0).payload,
                        p3.events.get(2).attachments.get(0).payload)));

        // ---------- 4. 部分内容缺失 ----------
        // 只给 11 号附件的内容，12 号（引用型、读不到）不给；
        // 第三条记录共用 11 号，所以「带到的份数」按登记条数算是 2 份（同一份内容复用）
        List<Event> partialEvents = new ArrayList<Event>();
        partialEvents.add(e1);
        partialEvents.add(e2);
        partialEvents.add(e3);
        Map<Long, byte[]> partial = new HashMap<Long, byte[]>();
        partial.put(Long.valueOf(11), body);
        String partJson = EventCodec.encode(partialEvents, 1789315700000L, true, partial);
        EventCodec.Payload p4 = EventCodec.decode(partJson);
        check("部分缺失：带到的份数", Integer.valueOf(2), Integer.valueOf(p4.carriedFiles));
        check("部分缺失：只登记的份数", Integer.valueOf(1), Integer.valueOf(p4.registeredOnly));
        check("部分缺失：没带到的仍然有登记", "现场照片.jpg",
                p4.events.get(0).attachments.get(1).name);
        check("部分缺失：没带到的没有内容", Boolean.TRUE,
                Boolean.valueOf(p4.events.get(0).attachments.get(1).payload == null));

        // ---------- 5. 旧格式（只有 attachCount） ----------
        String legacy = "{\"version\":1,\"events\":[{\"time\":1789315200000,\"text\":\"老文件\","
                + "\"attachCount\":3}]}";
        EventCodec.Payload p5 = EventCodec.decode(legacy);
        check("旧格式：计数读出来", Integer.valueOf(3),
                Integer.valueOf(p5.events.get(0).attachmentCount));
        check("旧格式：记成只登记", Integer.valueOf(3), Integer.valueOf(p5.registeredOnly));

        // ---------- 6. 自带 base64 的边界 ----------
        Method enc = EventCodec.class.getDeclaredMethod("encodeBase64", byte[].class);
        Method dec = EventCodec.class.getDeclaredMethod("decodeBase64Plain", String.class);
        enc.setAccessible(true);
        dec.setAccessible(true);
        for (int len = 0; len <= 8; len++) {
            byte[] in = new byte[len];
            for (int i = 0; i < len; i++) {
                in[i] = (byte) (0xF0 + i);
            }
            byte[] back = (byte[]) dec.invoke(null, (String) enc.invoke(null, in));
            check("base64 往返 len=" + len, Boolean.TRUE,
                    Boolean.valueOf(java.util.Arrays.equals(in, back)));
        }
        byte[] all = new byte[256];
        for (int i = 0; i < 256; i++) {
            all[i] = (byte) i;
        }
        byte[] allBack = (byte[]) dec.invoke(null, (String) enc.invoke(null, all));
        check("base64 往返 全字节", Boolean.TRUE,
                Boolean.valueOf(java.util.Arrays.equals(all, allBack)));

        // ---------- 7. 导出区间换算 ----------
        long today = DateUtil.startOfToday();
        check("本周起点是周一", Integer.valueOf(0),
                Integer.valueOf(DateUtil.weekdayIndex(DateUtil.startOfWeek(today))));
        check("本周七天", Integer.valueOf(7),
                Integer.valueOf(DateUtil.daysBetween(DateUtil.startOfWeek(today),
                        DateUtil.addDays(DateUtil.startOfWeek(today), 7))));
        check("本月区间右端是下月 1 日", Integer.valueOf(1),
                Integer.valueOf(DateUtil.dayOfMonth(DateUtil.addMonths(DateUtil.startOfMonth(today), 1))));
        check("本年区间右端是次年 1 月 1 日", Integer.valueOf(1),
                Integer.valueOf(DateUtil.month(DateUtil.addYears(DateUtil.startOfYear(today), 1))));
        long customFrom = DateUtil.startOfDay(today);
        long customTo = DateUtil.addDays(customFrom, 1);
        check("自选区间：单日时 start 小于 end", Boolean.TRUE,
                Boolean.valueOf(customFrom < customTo));
        check("自选区间：右开端点等于次日零点", Boolean.TRUE,
                Boolean.valueOf(DateUtil.isSameDay(customTo, DateUtil.addDays(today, 1))));

        // ---------- 8. 导出带上内部文件名 / 按名字回接 ----------
        // 导出侧：写了 local 字段的登记能不能原样读回来
        Event e8 = new Event(0L, 1789315500000L, "带内部文件名的登记", 1789315501000L);
        Attachment a8 = attach("Screenshot_x.jpg", "image/jpeg", 199397L, true);
        a8.id = 21;
        a8.localName = "1790073310540-a40.jpg";
        e8.addAttachment(a8);
        List<Event> list8 = new ArrayList<Event>();
        list8.add(e8);
        String json8 = EventCodec.encode(list8, 1789315700000L, true);
        check("导出带 local 字段", Boolean.TRUE,
                Boolean.valueOf(json8.contains("\"local\": \"1790073310540-a40.jpg\"")));
        EventCodec.Payload p8 = EventCodec.decode(json8);
        check("导入读回 localName", "1790073310540-a40.jpg",
                p8.events.get(0).attachments.get(0).localName);

        // 只有 attachCount 的老文件不该造出假的 localName
        EventCodec.Payload p9 = EventCodec.decode(legacy);
        check("老格式无 localName", "",
                p9.events.get(0).attachments == null || p9.events.get(0).attachments.isEmpty()
                        ? "" : p9.events.get(0).attachments.get(0).localName);

        // 回接规则：内部文件名 → 显示名；就用在「导出→删除记录保留附件→重新导入」这条路上
        File notDir = new File(System.getProperty("java.io.tmpdir"), "matolog-attachcheck");
        notDir.mkdirs();
        for (File f : notDir.listFiles()) {
            f.delete();
        }
        String[] names = {
                "1790073310540-a40-Screenshot_x.jpg",   // 现在的形状：尾名就是显示名
                "1790073310541-b7c2-记录.pdf",
                "1790073310542-c001.pdf",               // 早期形状：只剩后缀可猜
                "手工放的.pdf"                            // 用户自己放进去的同名文件
        };
        for (String n : names) {
            java.io.FileOutputStream o = new java.io.FileOutputStream(new File(notDir, n));
            o.write(new byte[]{1, 2, 3});
            o.close();
        }
        File[] existing = notDir.listFiles();
        check("按尾名找到", "1790073310540-a40-Screenshot_x.jpg",
                name(AttachmentStore.matchIn(existing, "Screenshot_x.jpg")));
        check("中文名也认", "1790073310541-b7c2-记录.pdf",
                name(AttachmentStore.matchIn(existing, "记录.pdf")));
        check("同名文件精确匹配", "手工放的.pdf",
                name(AttachmentStore.matchIn(existing, "手工放的.pdf")));
        check("早期形状按后缀兜", "1790073310542-c001.pdf",
                name(AttachmentStore.matchIn(existing, "别的.pdf")));
        check("扩展名不符则不误配", null,
                name(AttachmentStore.matchIn(existing, "Screenshot_x.png")));
        check("不存在的名字不误配", null,
                name(AttachmentStore.matchIn(existing, "完全没这个.bin")));
        check("空显示名不匹配", null, name(AttachmentStore.matchIn(existing, "")));
        check("空目录不匹配", null, name(AttachmentStore.matchIn(new File[0], "a.jpg")));

        // 生成名里必须带显示名，否则回接无从谈起
        String made = AttachmentStore.newLocalName("现场 照片.jpg");
        check("生成名带显示名", Boolean.TRUE,
                Boolean.valueOf(made.contains("现场 照片.jpg")));
        check("显示名里的路径分隔符被净化", Boolean.FALSE,
                Boolean.valueOf(AttachmentStore.newLocalName("a/b\\c.jpg").contains("/")));
        check("空显示名有兜底", "附件", AttachmentStore.sanitizeForFileName(""));
        check("只有点的显示名有兜底", "附件", AttachmentStore.sanitizeForFileName(".."));

        System.out.println("结果: pass=" + pass + " fail=" + fail);
        if (fail > 0) {
            System.exit(1);
        }
    }

    private static String name(File f) {
        return f == null ? null : f.getName();
    }

    private static Attachment attach(String name, String mime, long size, boolean internal) {
        Attachment a = new Attachment();
        a.name = name;
        a.mime = mime;
        a.size = size;
        a.internal = internal;
        a.localName = internal ? "local-" + name : "";
        a.uri = internal ? "" : "content://test/" + name;
        return a;
    }
}
