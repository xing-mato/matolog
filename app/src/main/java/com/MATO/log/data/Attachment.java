package com.MATO.log.data;

/**
 * 一条附件记录。
 *
 * 附件本体有两种存法：
 * - internal = true ：文件被复制进应用私有目录（files/attachments），原件删掉也不影响；
 * - internal = false：只登记用户在选择器里挑中的那个文件的引用，文件仍在原位置，
 *   应用只保管访问授权（没授权时打开会失败）。
 */
public class Attachment {

    public long id;
    /** 所属事件；0 表示已经被删掉事件的「孤立附件」 */
    public long eventId;
    /** 展示用的文件名 */
    public String name;
    public String mime;
    public long size;
    /** true = 存进了应用内部存储；false = 只引用外部文件 */
    public boolean internal;
    /** 内部存储时的文件名；外部引用时为空 */
    public String localName;
    /** 外部引用时的 Uri 字符串；内部存储时为空 */
    public String uri;
    public long createdAt;

    /** 本体是否还在（列表里用来提示「文件不在了」） */
    public boolean missing;

    /** 导出 / 导入过程中内嵌的内容；平时为 null，不参与展示 */
    public byte[] payload;

    public Attachment() {
        this.name = "";
        this.mime = "";
    }

    public boolean isImage() {
        return mime != null && mime.startsWith("image/");
    }

    public String modeText(android.content.Context c) {
        return c.getString(internal
                ? com.MATO.log.R.string.attach_mode_internal_long
                : com.MATO.log.R.string.attach_mode_reference_long);
    }

    public Attachment copy() {
        Attachment a = new Attachment();
        a.id = id;
        a.eventId = eventId;
        a.name = name;
        a.mime = mime;
        a.size = size;
        a.internal = internal;
        a.localName = localName;
        a.uri = uri;
        a.createdAt = createdAt;
        a.missing = missing;
        a.payload = payload;
        return a;
    }

    @Override
    public String toString() {
        return "Attachment{id=" + id + ", eventId=" + eventId + ", name=" + name
                + ", internal=" + internal + "}";
    }
}
