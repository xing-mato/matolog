import com.MATO.log.data.Event;
import com.MATO.log.util.EventCodec;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;

/** JSON 交换格式的往返校验：用 android.jar 里真实的 org.json 实现跑 */
public class JsonCheck {

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
        String outPath = args.length > 0 ? args[0] : "out.json";

        String quotes = "带\"引号\" 和 \\反斜杠\\ 的描述";
        String multiline = "多行\n第二行\t带制表符";
        String emoji = "emoji \uD83D\uDE00 与中文标点，。！？";

        List<Event> events = new ArrayList<Event>();
        events.add(new Event(0L, 1789315200000L, "普通一条记录", 1789315201000L));
        events.add(new Event(0L, 1789315300000L, quotes, 1789315301000L));
        events.add(new Event(0L, 1789315400000L, multiline, 1789315401000L));
        events.add(new Event(0L, 1789315500000L, emoji, 1789315501000L));
        events.add(new Event(0L, 1789315600000L, "", 1789315601000L));

        String json = EventCodec.encode(events, 1789315700000L, true);
        System.out.println("encoded length = " + json.length());

        EventCodec.Payload p = EventCodec.decode(json);
        check("条数", Integer.valueOf(5), Integer.valueOf(p.events.size()));
        check("导出时间", Long.valueOf(1789315700000L), Long.valueOf(p.exportedAt));
        check("declaredCount", Integer.valueOf(5), Integer.valueOf(p.declaredCount));
        check("第1条文本", "普通一条记录", p.events.get(0).text);
        check("第2条文本(引号/反斜杠)", quotes, p.events.get(1).text);
        check("第3条文本(换行/制表)", multiline, p.events.get(2).text);
        check("第4条文本(emoji)", emoji, p.events.get(3).text);
        check("第5条空文本", "", p.events.get(4).text);
        check("时间戳", Long.valueOf(1789315200000L), Long.valueOf(p.events.get(0).time));
        check("createdAt", Long.valueOf(1789315201000L), Long.valueOf(p.events.get(0).createdAt));

        String again = EventCodec.encode(p.events, 1789315700000L, true);
        check("二次编码完全一致", Boolean.TRUE, Boolean.valueOf(json.equals(again)));

        String loose = "{\"events\":[{\"time\":\"1789315200000\",\"text\":\"宽松解析\"}]}";
        check("字符串型时间戳", Long.valueOf(1789315200000L),
                Long.valueOf(EventCodec.decode(loose).events.get(0).time));

        String loose2 = "{\"events\":[{\"createdAt\":1789315200000,\"text\":\"回填时间\"}]}";
        check("createdAt 回填 time", Long.valueOf(1789315200000L),
                Long.valueOf(EventCodec.decode(loose2).events.get(0).time));

        boolean threw = false;
        try {
            EventCodec.decode("this is not json");
        } catch (Exception e) {
            threw = true;
        }
        check("坏文件抛异常", Boolean.TRUE, Boolean.valueOf(threw));

        threw = false;
        try {
            EventCodec.decode("{\"app\":\"MATOlog\"}");
        } catch (Exception e) {
            threw = true;
        }
        check("缺少 events 抛异常", Boolean.TRUE, Boolean.valueOf(threw));

        File f = new File(outPath);
        Writer w = new OutputStreamWriter(new FileOutputStream(f), utf8.newEncoder());
        w.write(json);
        w.close();

        System.out.println("结果: pass=" + pass + " fail=" + fail);
        if (fail > 0) {
            System.exit(1);
        }
    }
}
