package com.MATO.log.util;

import com.MATO.log.data.Attachment;
import com.MATO.log.data.Event;
import com.MATO.log.data.Session;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * MATOlog 的 JSON 交换格式。
 *
 * v1（不带附件）：
 * {
 *   "app": "MATOlog", "format": "matolog.events", "version": 1,
 *   "exportedAt": 1758360000000, "count": 12,
 *   "events": [ { "time": 1758350000000, "text": "…", "createdAt": 1758350001000 } ]
 * }
 *
 * v2 在 v1 之上加附件。默认只登记、不搬运内容（"carried": false）；
 * 用户选择「连附件一起带走」时，内容以 base64 内嵌在顶层 files 里，
 * 事件侧用 handle 指过去（同一份内容只存一次）。
 * {
 *   "version": 2,
 *   "events": [ { …, "attachments": [ { "name": "图.jpg", "mime": "image/jpeg",
 *                  "size": 8123, "internal": true, "handle": 0 } ] } ],
 *   "files": [ { "handle": 0, "data": "<base64>" } ]
 * }
 */
public final class EventCodec {

    public static final String FORMAT = "matolog.events";
    /** 当前写出的版本 */
    public static final int VERSION = 2;

    /** 元素为 android.util.Base64 常量；为 null 时 = 明文 JSON */
    private static final String KEY_B64 = "b64";

    private EventCodec() {
    }

    // ---------------- 写出 ----------------

    /** 只登记附件（不搬运内容），清单要带走时用 encode(events, at, pretty, map) */
    public static String encode(List<Event> events, long exportedAt, boolean pretty) {
        return encode(events, exportedAt, pretty, null);
    }

    /**
     * @param payloads 附件 id → 内容；为 null 或某条附件不在里面时，只登记元信息
     */
    public static String encode(List<Event> events, long exportedAt, boolean pretty,
                                Map<Long, byte[]> payloads) {
        return encode(events, exportedAt, pretty, payloads,
                payloads == null || payloads.isEmpty() ? null : Integer.valueOf(0));
    }

    /**
     * @param base64Flags 与 android.util.Base64 的常量一致；null = 明文，0 = 默认不带换行
     */
    public static String encode(List<Event> events, long exportedAt, boolean pretty,
                                Map<Long, byte[]> payloads, Integer base64Flags) {
        JSONObject root = new JSONObject();
        try {
            root.put("app", "MATOlog");
            root.put("format", FORMAT);
            root.put("version", VERSION);
            root.put("exportedAt", exportedAt);
            root.put("count", events == null ? 0 : events.size());

            boolean carry = payloads != null && !payloads.isEmpty();
            root.put("withFiles", carry);

            JSONArray arr = new JSONArray();
            JSONArray files = new JSONArray();
            // 附件 id → 在 files 里的下标，同一份内容只编码一次
            Map<Long, Integer> handles = carry ? new HashMap<Long, Integer>() : null;

            if (events != null) {
                for (Event e : events) {
                    if (e == null) {
                        continue;
                    }
                    JSONObject o = new JSONObject();
                    o.put("time", e.time);
                    o.put("text", e.text == null ? "" : e.text);
                    o.put("createdAt", e.createdAt);

                    // v3 新增：区间与来源。
                    // 只在真的有内容时才写，瞬时的手动记录跟 v1/v2 的文件长得一模一样 ——
                    // 老版本导入这种文件完全不受影响（它本来就不看这些键）。
                    if (e.isSpan()) {
                        o.put("end", e.endMs);
                    }
                    if (e.source != Session.SRC_MANUAL) {
                        o.put("source", e.source);
                    }
                    if (e.appPkg != null && !e.appPkg.isEmpty()) {
                        o.put("appPkg", e.appPkg);
                    }
                    if (e.appLabel != null && !e.appLabel.isEmpty()) {
                        o.put("appLabel", e.appLabel);
                    }
                    if (e.organized) {
                        o.put("organized", true);
                    }
                    if (e.ignored) {
                        o.put("ignored", true);
                    }

                    List<Attachment> list = e.attachments;
                    if (list != null && !list.isEmpty()) {
                        JSONArray as = new JSONArray();
                        for (Attachment a : list) {
                            if (a == null) {
                                continue;
                            }
                            JSONObject ao = new JSONObject();
                            ao.put("name", a.name == null ? "" : a.name);
                            ao.put("mime", a.mime == null ? "" : a.mime);
                            ao.put("size", a.size);
                            ao.put("internal", a.internal);
                            if (a.internal && a.localName != null && a.localName.length() > 0) {
                                // 内部附件的实际文件名。
                                // 写上它，同一台设备上重新导入时才能按名字找回那份文件 ——
                                // 不写的话导入侧只能标成「文件不在了」，即便文件好好躺在
                                // files/attachments/ 里也打不开。
                                ao.put("local", a.localName);
                            }

                            Integer handle = null;
                            if (carry && handles != null && !handles.containsKey(Long.valueOf(a.id))) {
                                byte[] data = payloads.get(Long.valueOf(a.id));
                                if (data != null && data.length > 0) {
                                    handle = Integer.valueOf(files.length());
                                    handles.put(Long.valueOf(a.id), handle);
                                    JSONObject fo = new JSONObject();
                                    fo.put("handle", handle.intValue());
                                    fo.put("data", base64(data, base64Flags));
                                    files.put(fo);
                                }
                            } else if (carry && handles != null) {
                                handle = handles.get(Long.valueOf(a.id));
                            }
                            if (handle != null) {
                                ao.put("handle", handle.intValue());
                            }
                            as.put(ao);
                        }
                        if (as.length() > 0) {
                            o.put("attachments", as);
                        }
                    } else if (e.attachmentCount > 0) {
                        // 只有计数、没有明细（例如从旧版本导入的），留个记号让导入端提示
                        o.put("attachCount", e.attachmentCount);
                    }
                    arr.put(o);
                }
            }
            root.put("events", arr);
            if (carry) {
                root.put("files", files);
            }
            return pretty ? root.toString(2) : root.toString();
        } catch (Exception ex) {
            return "{}";
        }
    }

    private static String base64(byte[] data, Integer flags) {
        try {
            if (flags != null) {
                Class<?> c = Class.forName("android.util.Base64");
                java.lang.reflect.Method m = c.getMethod("encodeToString", byte[].class, int.class);
                return (String) m.invoke(null, data, flags);
            }
        } catch (Throwable ignored) {
        }
        return encodeBase64(data);
    }

    private static byte[] decodeBase64(String s) {
        try {
            Class<?> c = Class.forName("android.util.Base64");
            java.lang.reflect.Method m = c.getMethod("decode", String.class, int.class);
            Object out = m.invoke(null, s, Integer.valueOf(0));
            if (out instanceof byte[]) {
                return (byte[]) out;
            }
        } catch (Throwable ignored) {
        }
        return decodeBase64Plain(s);
    }

    // 自带一份 base64，纯 JVM 自检（没有 android.jar）时也能跑通往返

    private static final char[] B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
            .toCharArray();

    static String encodeBase64(byte[] data) {
        StringBuilder sb = new StringBuilder(((data.length + 2) / 3) * 4);
        int i = 0;
        while (i + 2 < data.length) {
            int n = ((data[i] & 0xFF) << 16) | ((data[i + 1] & 0xFF) << 8) | (data[i + 2] & 0xFF);
            sb.append(B64[(n >> 18) & 63]).append(B64[(n >> 12) & 63])
                    .append(B64[(n >> 6) & 63]).append(B64[n & 63]);
            i += 3;
        }
        int rest = data.length - i;
        if (rest == 1) {
            int n = (data[i] & 0xFF) << 16;
            sb.append(B64[(n >> 18) & 63]).append(B64[(n >> 12) & 63]).append("==");
        } else if (rest == 2) {
            int n = ((data[i] & 0xFF) << 16) | ((data[i + 1] & 0xFF) << 8);
            sb.append(B64[(n >> 18) & 63]).append(B64[(n >> 12) & 63])
                    .append(B64[(n >> 6) & 63]).append('=');
        }
        return sb.toString();
    }

    static byte[] decodeBase64Plain(String s) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(s.length() * 3 / 4 + 3);
        int buf = 0;
        int bits = 0;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            int v;
            if (ch >= 'A' && ch <= 'Z') {
                v = ch - 'A';
            } else if (ch >= 'a' && ch <= 'z') {
                v = ch - 'a' + 26;
            } else if (ch >= '0' && ch <= '9') {
                v = ch - '0' + 52;
            } else if (ch == '+') {
                v = 62;
            } else if (ch == '/') {
                v = 63;
            } else {
                continue;   // '=' 与空白直接跳过
            }
            buf = (buf << 6) | v;
            bits += 6;
            if (bits >= 8) {
                bits -= 8;
                out.write((buf >> bits) & 0xFF);
            }
        }
        return out.toByteArray();
    }

    // ---------------- 读入 ----------------

    /** 解析结果：事件列表 + 文件本身携带的导出时间（可能为 0） */
    public static class Payload {
        public final List<Event> events = new ArrayList<>();
        public long exportedAt;
        public int declaredCount = -1;
        /** 顶层 files 里的内容：handle → 字节 */
        public final Map<Integer, byte[]> files = new HashMap<>();
        /** 文件里登记过附件、但没带内容的条数 */
        public int registeredOnly;
        /** 带内容的附件份数 */
        public int carriedFiles;
    }

    /**
     * 宽松解析：只要求存在 events 数组，字段缺失时按 0 处理。
     *
     * @throws Exception 文件不是可识别的 JSON 时抛出
     */
    public static Payload decode(String json) throws Exception {
        Payload p = new Payload();
        JSONObject root = new JSONObject(json);

        p.exportedAt = root.optLong("exportedAt", 0L);
        p.declaredCount = root.optInt("count", -1);

        JSONArray filesArr = root.optJSONArray("files");
        if (filesArr != null) {
            for (int i = 0; i < filesArr.length(); i++) {
                JSONObject fo = filesArr.optJSONObject(i);
                if (fo == null) {
                    continue;
                }
                String data = fo.optString("data", "");
                if (data.length() == 0) {
                    continue;
                }
                int handle = fo.optInt("handle", i);
                try {
                    p.files.put(Integer.valueOf(handle), decodeBase64(data));
                } catch (Throwable ignored) {
                }
            }
        }

        JSONArray arr = root.optJSONArray("events");
        if (arr == null && root.optJSONArray("list") != null) {
            arr = root.optJSONArray("list");
        }
        if (arr == null) {
            throw new IllegalArgumentException("no events array");
        }

        for (int i = 0; i < arr.length(); i++) {
            Object item = arr.opt(i);
            if (item instanceof JSONObject) {
                JSONObject o = (JSONObject) item;
                Event e = new Event();
                e.time = readLong(o, "time", 0L);
                e.text = o.optString("text", "");
                e.createdAt = readLong(o, "createdAt", 0L);
                if (e.time <= 0 && e.createdAt > 0) {
                    e.time = e.createdAt;
                }
                if (e.time <= 0) {
                    continue;
                }

                // v3 字段。老文件里没有这些键 → opt 给默认值 → 就是一条普通的瞬时手动记录，
                // 与升级前完全一致。所以不需要按 version 分支处理。
                e.endMs = readLong(o, "end", 0L);
                if (e.endMs <= e.time) {
                    e.endMs = 0;    // 区间必须真的比起点晚，否则按瞬时处理
                }
                e.source = o.optInt("source", Session.SRC_MANUAL);
                if (e.source != Session.SRC_AUTO && e.source != Session.SRC_SCREEN) {
                    e.source = Session.SRC_MANUAL;
                }
                e.appPkg = o.optString("appPkg", "");
                e.appLabel = o.optString("appLabel", "");
                e.organized = o.optBoolean("organized", false);
                e.ignored = o.optBoolean("ignored", false);

                // 自动记录产生的条目允许 text 为空（标题等整理后再补），
                // 手动记录仍然要求有内容 —— 否则时间轴上会出现一行什么都没有的东西。
                if ((e.text == null || e.text.isEmpty()) && !e.isAuto()) {
                    continue;
                }
                if (e.text == null) {
                    e.text = "";
                }
                readAttachments(o, e, p);
                p.events.add(e);
            } else if (item != null) {
                // 容错：极简格式 ["时间戳","描述"]
                String s = String.valueOf(item);
                if (s.length() > 0) {
                    Event e = new Event();
                    e.text = s;
                    e.time = 0;
                    p.events.add(e);
                }
            }
        }
        return p;
    }

    private static void readAttachments(JSONObject o, Event e, Payload p) {
        JSONArray as = o.optJSONArray("attachments");
        if (as == null) {
            int legacy = o.optInt("attachCount", 0);
            if (legacy > 0) {
                e.attachmentCount = legacy;
                p.registeredOnly += legacy;
            }
            return;
        }
        for (int i = 0; i < as.length(); i++) {
            JSONObject ao = as.optJSONObject(i);
            if (ao == null) {
                continue;
            }
            Attachment a = new Attachment();
            a.name = ao.optString("name", "附件");
            a.mime = ao.optString("mime", "");
            a.size = ao.optLong("size", 0L);
            a.internal = ao.optBoolean("internal", true);
            a.localName = ao.optString("local", "");
            a.createdAt = 0;
            if (ao.has("handle")) {
                byte[] data = p.files.get(Integer.valueOf(ao.optInt("handle", -1)));
                if (data != null && data.length > 0) {
                    a.payload = data;
                    a.size = data.length;
                    p.carriedFiles++;
                } else {
                    p.registeredOnly++;
                }
            } else {
                p.registeredOnly++;
            }
            e.addAttachment(a);
        }
        e.attachmentCount = e.attachments().size();
    }

    private static long readLong(JSONObject o, String key, long def) {
        Object v = o.opt(key);
        if (v == null) {
            return def;
        }
        if (v instanceof Number) {
            return ((Number) v).longValue();
        }
        try {
            return Long.parseLong(String.valueOf(v).trim());
        } catch (Exception ignored) {
            return def;
        }
    }
}
