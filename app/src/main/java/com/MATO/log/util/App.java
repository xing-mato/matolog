package com.MATO.log.util;

import android.app.Activity;
import android.app.Application;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

import com.MATO.log.rec.AppFilter;

import java.util.ArrayList;
import java.util.List;

/**
 * 应用入口。
 *
 * <p><b>它管两件事</b>：记住当前活着的界面；配色档位一改，让它们全部重建。
 *
 * <p><b>为什么不在这里改 base context 了</b>：早期版本在 {@code attachBaseContext} 里
 * 把用户选的夜间位写进 Application 的 base context，本想「一处生效、全局受益」。实测有两个问题：
 * <ol>
 *   <li>Android 15 上它**并不能**让 Activity 的 @color 取到 values-night
 *       （各界面仍需自己包一层，见 {@code ui/BaseActivity}），收益本来就有限；</li>
 *   <li>更要紧的是它把配置**冻结在进程启动那一刻**。之后用户从「始终深色」改回
 *       「跟随系统」，界面从 Application 拿到的仍是被写坏的夜间位，而
 *       {@link DarkTheme#wrap} 无从判断系统的真实档位（它看到的已经是被改过的配置），
 *       只能原样返回 —— 于是界面继续是深色，非得杀掉进程重开才恢复。
 *       这正是「改了主题要重启应用才完全生效」的根因之一。</li>
 * </ol>
 * 现在 Application 保留系统给的原始配置，着色全部交给 {@link DarkTheme#wrap}
 * 在界面附着时按当前偏好**现算**：它拿到的是真实系统档位，
 * 跟随/始终深/始终浅三档在任意切换顺序下都能算对。
 */
public class App extends Application {

    private static App inst;

    /**
     * 当前活着的界面。
     *
     * <p>只在主线程读写（onCreate / onDestroy / 切换档位都发生在主线程），所以不加锁。
     * 但遍历时必须先拷一份：{@code recreate()} 会立刻引发 onDestroy 回调去改这张表，
     * 直接在原表上迭代会抛 ConcurrentModificationException。
     */
    private static final List<Activity> LIVE = new ArrayList<>();

    public static App get() {
        return inst;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        inst = this;
        // 语言要**最先**定下来：DateUtil 那类拿不到 Context 的地方读的是它的静态值，
        // 定晚了第一帧的日期还是上一种语言写的
        Lang.refresh(this);
        // 崩溃处理器要**最早**装上：装晚了，启动过程中崩掉的那一次就漏了
        ErrorLog.installCrashHandler(this);
        resolveShellPackages();
        migrateOnce();
    }

    /**
     * 把本机**真正的**桌面包名交给 {@link AppFilter}。
     *
     * <p>为什么非要动态解析：桌面是厂商换得最勤的一个包名。{@code AppFilter} 里那张
     * 兜底表写的是 AOSP 的名字（{@code com.android.launcher3} 这些），
     * 而这台 vivo Z1 上是 {@code com.bbk.launcher2} —— 一个都匹配不上。
     *
     * <p>漏掉的后果很具体：按 Home 回桌面时，桌面的 ACTIVITY_RESUMED 会被当成一次
     * 「用户在用它做事」，时间轴上多出一条「桌面」。
     *
     * <p><b>只取「当前默认」那一个桌面，不能用 queryIntentActivities 把匹配 HOME 的包全收进来。</b>
     * 这一点踩过：AOSP 的系统设置里有个 {@code com.android.settings.FallbackHome}
     * （开机时真桌面还没起来的兜底首页，priority -1000），它同样声明了 CATEGORY_HOME。
     * 按包收的结果是**整个「设置」被当成系统界面** —— 记不了、应用名单里也看不到，
     * 而且因为它是被静默滤掉的，光看界面根本查不出为什么。
     *
     * <p>代价说清楚：用户在运行期间换了桌面，要等下次应用重启才会认新的那个。
     * 换桌面是极少见的动作，而上面那个误伤的代价大得多 —— 宁可少认，不能多认。
     */
    private void resolveShellPackages() {
        try {
            PackageManager pm = getPackageManager();
            Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);

            // MATCH_DEFAULT_ONLY + 按优先级取最匹配的那个，得到的就是真正在用的桌面。
            // 不要用 queryIntentActivities 把匹配 HOME 的包全收进来：那样会把
            // com.android.settings 一起收走（见方法注释里的 FallbackHome）。
            ResolveInfo best = pm.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY);
            if (best != null && best.activityInfo != null) {
                AppFilter.addDeviceShell(best.activityInfo.packageName);
            }
        } catch (Throwable ignored) {
            // 查不到就退回 AppFilter 里那张兜底表：少挡一层，不该拦着应用启动
        }
    }

    /**
     * 一次性数据迁移。
     *
     * <p><b>1.3.1</b>：清掉 1.3 遗留的「只记这些」白名单。这个功能连同它的按钮一起去掉了，
     * 但库里那份白名单还留着 —— 一行看不见、改不掉、却控制着记录范围的旧配置。
     * （判定侧现在已经完全不认它了，见 {@code AppFilter}；这里清的是数据本身，
     * 免得日后有人翻库时被它误导。）
     *
     * <p><b>为什么在这里同步做、而不是丢到后台线程</b>：记录服务起来时会读一次名单，
     * 这是「先读后写」的顺序问题 —— 挪到后台就有机会在读完之后才删掉，
     * 那这次运行仍按旧白名单记，而用户已经看不到它了。一条 DELETE 命中的是
     * 几行最多几十行的小表，放在 onCreate 里做不值当为它引入一次竞态。
     *
     * <p>用偏好里的标记挡住重复执行：迁移本身是幂等的，但每次冷启动都去开一次库、
     * 发一条 DELETE 是白费的 IO。
     */
    private void migrateOnce() {
        dropLegacyOnlyRules();
        adoptFastSampleDefault();
    }

    /**
     * 1.3.1：清掉 1.3 遗留的「只记这些」白名单。这个功能连同它的按钮一起去掉了，
     * 但库里那份白名单还留着 —— 一行看不见、改不掉、却控制着记录范围的旧配置。
     * （判定侧现在已经完全不认它了，见 {@code AppFilter}；这里清的是数据本身，
     * 免得日后有人翻库时被它误导。）
     *
     * <p><b>为什么在这里同步做、而不是丢到后台线程</b>：记录服务起来时会读一次名单，
     * 这是「先读后写」的顺序问题 —— 挪到后台就有机会在读完之后才删掉，
     * 那这次运行仍按旧白名单记，而用户已经看不到它了。一条 DELETE 命中的是
     * 几行最多几十行的小表，放在 onCreate 里做不值当为它引入一次竞态。
     */
    private void dropLegacyOnlyRules() {
        if (Prefs.doneDropOnlyRules(this)) {
            return;
        }
        try {
            com.MATO.log.data.DbHelper db = new com.MATO.log.data.DbHelper(this);
            try {
                db.clearOnlyRules();
            } finally {
                db.close();
            }
        } catch (Throwable ignored) {
            // 迁移失败不该拦着应用启动 —— 下次冷启动会再试一次
            return;
        }
        Prefs.setDoneDropOnlyRules(this, true);
    }

    /**
     * 1.3.4：采样间隔的默认值从 60 秒改成 5 秒，让常驻通知跟得上应用切换。
     *
     * <p>存着旧默认值（正好 60 秒）的老用户，按「他没自己挑过」处理，跟着新默认走 ——
     * 否则这次改动对已装的机器等于没发生（值早就写进偏好里了，新默认只在「没存过」时生效）。
     * 用户自己填过的其它值一律不动。
     *
     * <p>用标记挡住重复执行：不然他哪天特意调回 1 分钟省电，下次冷启动又被改回去。
     */
    private void adoptFastSampleDefault() {
        if (Prefs.doneFastSample(this)) {
            return;
        }
        if (Prefs.sampleMs(this) == 60_000L) {
            Prefs.setSampleMs(this, Prefs.SAMPLE_DEFAULT);
        }
        Prefs.setDoneFastSample(this, true);
    }

    /** 界面创建时登记。由 {@code ui/BaseActivity} 调用 */
    public static void watch(Activity a) {
        if (a != null && !LIVE.contains(a)) {
            LIVE.add(a);
        }
    }

    /** 界面销毁时注销。由 {@code ui/BaseActivity} 调用 */
    public static void forget(Activity a) {
        LIVE.remove(a);
    }

    /**
     * 用户在设置里改了配色档位之后调用。
     *
     * <p><b>为什么要把所有活着的界面都重建，而不是只重建设置页</b>：配色是在 Context
     * 附着那一刻按当时的偏好定下的，改完之后**已经存在**的界面不会自己变。只让设置页
     * {@code recreate()} 只能解决当前这一页 —— 用户按返回键回到主界面，那里仍是旧配色，
     * 看起来就像「改了一半、要重启才全生效」。这里遍历全部活着的界面逐个重建：
     * 它们会重新走 attachBaseContext 拿到新配色，返回栈里的每一层也都是对的。
     */
    public static void onModeChanged() {
        if (LIVE.isEmpty()) {
            return;
        }
        List<Activity> snapshot = new ArrayList<>(LIVE);
        for (int i = 0; i < snapshot.size(); i++) {
            Activity a = snapshot.get(i);
            if (a == null || a.isFinishing() || a.isDestroyed()) {
                continue;
            }
            a.recreate();
        }
    }
}
