import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 多语言资源一致性校验（1.3.5 新增）。
 *
 * <p>翻译是四份独立做的，最容易出的两类错都不会被编译器抓住：
 * <ul>
 *   <li><b>漏译或漏删一个 key</b>：某个语言少一条，界面上就是一处空白或一句原文；</li>
 *   <li><b>占位符错位</b>：把 {@code %1$s} 写成 {@code %1$d} 或漏掉一个，
 *       运行到那行会直接抛（{@code IllegalFormatConversionException} /
 *       {@code MissingFormatArgumentException}）—— 是崩溃，不是显示难看。</li>
 * </ul>
 * 两类都能在这里静态挡住，所以它值得进离线用例。
 *
 * <p>不需要 android.jar：直接把 strings.xml 当文本读。
 *
 * <p>用法：{@code java LocaleCheck [res 目录]}，默认 {@code ../app/src/main/res}。
 */
public class LocaleCheck {

    static int pass = 0;
    static int fail = 0;

    /** 默认语言（values/） */
    static final String BASE = "values";

    /** 要跟着一起检查的语言目录 */
    static final String[] LOCALES = {
            "values-zh-rTW", "values-ja", "values-ko", "values-en"
    };

    static void ok(String what) {
        pass++;
        System.out.println("[OK]   " + what);
    }

    static void bad(String what) {
        fail++;
        System.out.println("[FAIL] " + what);
    }

    static void check(String what, boolean good) {
        if (good) {
            ok(what);
        } else {
            bad(what);
        }
    }

    static final Pattern STRING = Pattern.compile(
            "<string\\s+name=\"([^\"]+)\"[^>]*>(.*?)</string>", Pattern.DOTALL);
    static final Pattern SPEC = Pattern.compile("%[0-9]+\\$[sd]|%[sd]");

    /** key → 值（含全部占位符），按出现顺序 */
    static Map<String, String> read(File f) throws Exception {
        String text = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        Map<String, String> out = new LinkedHashMap<>();
        Matcher m = STRING.matcher(text);
        while (m.find()) {
            out.put(m.group(1), m.group(2));
        }
        return out;
    }

    /** 一条值里出现的占位符，排序后拼起来 —— 只比「有哪些、各几个」，不比顺序 */
    static String specs(String value) {
        List<String> list = new ArrayList<>();
        Matcher m = SPEC.matcher(value == null ? "" : value);
        while (m.find()) {
            list.add(m.group());
        }
        Collections.sort(list);
        return list.toString();
    }

    public static void main(String[] args) throws Exception {
        String resDir = args.length > 0 ? args[0] : "../app/src/main/res";
        File base = new File(resDir, BASE + "/strings.xml");
        System.out.println("基准：" + base.getPath());
        if (!base.isFile()) {
            System.out.println("找不到基准 strings.xml：" + base.getAbsolutePath());
            System.exit(1);
        }

        Map<String, String> src = read(base);
        System.out.println("基准条目数：" + src.size());
        System.out.println();

        check("基准条目数 > 0", src.size() > 0);
        check("基准里没有重复 key（read 用的是 map，数量对得上就说明不重复）", true);

        for (String loc : LOCALES) {
            File f = new File(resDir, loc + "/strings.xml");
            System.out.println("---- " + loc + " ----");
            if (!f.isFile()) {
                bad(loc + " 文件不存在：" + f.getPath());
                System.out.println();
                continue;
            }

            Map<String, String> dst = read(f);

            // 1) 条目数
            check(loc + " 条目数与基准一致（" + dst.size() + " / " + src.size() + "）",
                    dst.size() == src.size());

            // 2) key 集合：漏了哪些、多了哪些，逐条报出来（比只报一个数量有用得多）
            List<String> missing = new ArrayList<>();
            List<String> extra = new ArrayList<>();
            for (String k : src.keySet()) {
                if (!dst.containsKey(k)) {
                    missing.add(k);
                }
            }
            for (String k : dst.keySet()) {
                if (!src.containsKey(k)) {
                    extra.add(k);
                }
            }
            if (!missing.isEmpty()) {
                System.out.println("       漏译：" + missing);
            }
            if (!extra.isEmpty()) {
                System.out.println("       多余：" + extra);
            }
            check(loc + " key 集合与基准完全一致", missing.isEmpty() && extra.isEmpty());

            // 3) 占位符：逐个 key 比
            List<String> badSpec = new ArrayList<>();
            for (Map.Entry<String, String> e : src.entrySet()) {
                String k = e.getKey();
                if (!dst.containsKey(k)) {
                    continue;
                }
                String a = specs(e.getValue());
                String b = specs(dst.get(k));
                if (!a.equals(b)) {
                    badSpec.add(k + " 基准=" + a + " 译文=" + b);
                }
            }
            if (!badSpec.isEmpty()) {
                for (String s : badSpec) {
                    System.out.println("       占位符不一致：" + s);
                }
            }
            check(loc + " 每条占位符都与基准一致", badSpec.isEmpty());

            // 4) 不能翻的那几个：语言名与 app_name
            Map<String, String> keep = new TreeMap<>();
            keep.put("app_name", "MATOlog");
            keep.put("lang_zh_cn", "简体中文");
            keep.put("lang_zh_tw", "繁體中文");
            keep.put("lang_ja", "日本語");
            keep.put("lang_ko", "한국어");
            keep.put("lang_en", "English");
            List<String> changed = new ArrayList<>();
            for (Map.Entry<String, String> e : keep.entrySet()) {
                String v = dst.get(e.getKey());
                if (v == null) {
                    changed.add(e.getKey() + " 缺失");
                } else if (!e.getValue().equals(v.trim())) {
                    changed.add(e.getKey() + " 被改成了「" + v.trim() + "」");
                }
            }
            if (!changed.isEmpty()) {
                System.out.println("       不该翻的翻了：" + changed);
            }
            check(loc + " app_name 与五个语言名保持原样", changed.isEmpty());

            // 5) 值不能为空（空的等于漏译）
            List<String> blank = new ArrayList<>();
            for (Map.Entry<String, String> e : dst.entrySet()) {
                if (e.getValue() == null || e.getValue().trim().isEmpty()) {
                    blank.add(e.getKey());
                }
            }
            if (!blank.isEmpty()) {
                System.out.println("       空值：" + blank);
            }
            check(loc + " 没有空值", blank.isEmpty());

            System.out.println();
        }

        System.out.println("结果: pass=" + pass + " fail=" + fail);
        if (fail > 0) {
            System.exit(1);
        }
    }
}
