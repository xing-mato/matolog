package com.MATO.log.service;

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;

import com.MATO.log.R;
import com.MATO.log.data.Event;
import com.MATO.log.rec.Recorder;
import com.MATO.log.util.DateUtil;
import com.MATO.log.util.Perm;
import com.MATO.log.util.Prefs;

/**
 * 自动记录的常驻服务。
 *
 * <p><b>为什么必须是前台服务</b>：Android 8 起后台进程会被随时冻结，采样就断了。
 * 要长期、按时地采样，唯一被系统承认的做法就是前台服务 + 常驻通知。
 *
 * <p><b>为什么不学参考 app 只靠无障碍服务</b>：那个应用「没有无障碍就等于不能用」，
 * 而本项目的自动记录在只给「使用情况访问」时也必须工作（退化为按分钟采样）。
 * 那条路径没有无障碍服务的持久性待遇，前台服务是唯一的保活手段。
 * 无障碍在本项目里是**精度增强**，不是生存前提。
 *
 * <p><b>保活做到什么程度</b>：标准做法 —— 前台化 + START_STICKY（被杀后系统尝试重建）
 * + 开机自启 + 熄屏/解锁广播。<b>不做</b>静默播放音频、应用互拉、一像素悬浮窗这类
 * 「流氓保活」：它们在新系统上基本已被堵死，代价是耗电和被当作恶意行为。
 * 真正有效的保活是把权限配齐，这件事交给设置页里的引导。
 *
 * <p><b>被系统杀掉之后</b>：重启时 {@link Recorder} 会用系统的使用情况事件把断档补回来，
 * 所以短时间的回收不会在记录里留下空洞。
 */
public final class RecordService extends Service implements Recorder.Listener {

    private static final String ACTION_START = "com.MATO.log.REC_START";

    /**
     * 服务也要跟着应用内语言走。
     *
     * <p>常驻通知是这个应用**唯一**会主动出现在用户眼前的东西，而它是在服务里拼的 ——
     * 服务不包这一层，用户把语言改成英文之后通知仍然是中文，
     * 偏偏那还是他一整天都看得见的那一块。
     */
    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(com.MATO.log.util.Lang.wrap(newBase));
    }

    private Recorder recorder;
    private Handler ui;
    private HandlerThread uiThread;
    private boolean receiverOn;
    private long lastNotiAt;
    private String lastNotiTitle = "";
    private String lastNotiText = "";

    /**
     * 由界面 / 开机 / 无障碍调用，统一走这个入口。
     *
     * <p>Android 12+ 在后台启动前台服务会被系统拒绝，这里不把它当错误处理 ——
     * 那是系统策略，不是配置问题。等用户下次打开界面时自然会起来。
     */
    public static void start(Context c) {
        Intent i = new Intent(c, RecordService.class);
        i.setAction(ACTION_START);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                c.startForegroundService(i);
            } else {
                c.startService(i);
            }
        } catch (Throwable ignored) {
        }
    }

    public static void stop(Context c) {
        Intent i = new Intent(c, RecordService.class);
        i.setAction(Foreground.ACTION_STOP);
        try {
            c.startService(i);
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Foreground.ensureChannel(this);
        uiThread = new HandlerThread("mato-noti");
        uiThread.start();
        ui = new Handler(uiThread.getLooper());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();

        if (Foreground.ACTION_STOP.equals(action)) {
            // 用户显式停止：把开关落盘，避免下次开机又自己起来
            Prefs.setAutoRecord(this, false);
            teardown();
            stopForegroundCompat();
            stopSelf();
            return START_NOT_STICKY;
        }

        if (Foreground.ACTION_REFRESH.equals(action)) {
            // 「刷新」= 现在就去采一次，把这条通知更新到当下。
            // 采样跑在记录线程上，所以要等它真正做完再刷通知 ——
            // 立刻刷会读到采样之前的旧状态，看起来就像点了没反应。
            if (recorder != null) {
                recorder.sampleNow(new Runnable() {
                    @Override
                    public void run() {
                        ui.post(new Runnable() {
                            @Override
                            public void run() {
                                pushNotification(true);
                            }
                        });
                    }
                });
            }
            return START_STICKY;
        }

        if (recorder == null) {
            startAsForeground();
            recorder = Recorder.start(this);
            recorder.setListener(this);
            recorder.startSampling();
            registerScreenReceiver();
            scheduleNotificationTick();
        }

        pushNotification(true);
        return START_STICKY;
    }

    private void startAsForeground() {
        try {
            android.app.Notification n = Foreground.build(this,
                    getString(R.string.rec_preparing_title), getString(R.string.rec_preparing_text));
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Android 10+ 要显式声明类型，且必须与清单里声明的一致
                startForeground(Foreground.NOTI_ID, n,
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
                                ? ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                                : 0);
            } else {
                startForeground(Foreground.NOTI_ID, n);
            }
        } catch (Throwable t) {
            // 极少数 ROM 会在这里抛（通知被彻底禁用等）。仍然把服务留着让记录继续跑，
            // 界面上的权限页会提示用户把通知打开。
            try {
                startForeground(Foreground.NOTI_ID, Foreground.build(this,
                        getString(R.string.rec_recording_title), getString(R.string.rec_no_noti_perm)));
            } catch (Throwable ignored) {
            }
        }
    }

    // ---------------- 通知 ----------------

    /** 定时刷新的那一跳（保留引用，好按它自己取消，别把别的回调一起清掉） */
    private final Runnable notiTick = new Runnable() {
        @Override
        public void run() {
            pushNotification(false);
            scheduleNotificationTick();
        }
    };

    /**
     * 采样刚判完就对齐一次通知。
     *
     * <p>为什么要单独来这一下：定时器是 30 秒一跳，而采样是 60 秒一轮 ——
     * 采样已经知道「换应用了」，通知却还要再等最多半分钟才跟上。
     * 切应用时那一下的滞后本来就已经有一个采样周期，再加上这半分钟就更明显了。
     *
     * <p>放在通知线程上跑：{@link #pushNotification} 会读写它自己那几个「上次发了什么」
     * 的字段，得和定时器串行。
     */
    private final Runnable notiSync = new Runnable() {
        @Override
        public void run() {
            pushNotification(false);
        }
    };

    private void scheduleNotificationTick() {
        if (ui == null) {
            return;
        }
        ui.removeCallbacks(notiTick);
        ui.postDelayed(notiTick, 30_000L);
    }

    /**
     * 刷新常驻通知。
     *
     * <p>每 30 秒一次，且只在文字真的变了才 notify —— 否则系统会以为应用在刷通知，
     * 既费电也惹人烦。
     */
    private void pushNotification(boolean force) {
        if (recorder == null) {
            return;
        }
        String title;
        String text;
        if (!Perm.usageAccess(this)) {
            title = getString(R.string.rec_inactive_title);
            text = getString(R.string.rec_inactive_text);
        } else {
            String cur = recorder.tracker().currentPkg();
            if (cur == null || cur.isEmpty()) {
                title = getString(R.string.rec_waiting_title);
                text = recorder.statusLine();
            } else {
                title = getString(R.string.rec_now_title, recorder.tracker().currentLabel());
                text = getString(R.string.rec_noti_text,
                        DateUtil.duration(recorder.tracker().currentDurationMs()),
                        recorder.todayKept());
            }
        }
        long now = System.currentTimeMillis();
        if (!force && title.equals(lastNotiTitle) && text.equals(lastNotiText)
                && now - lastNotiAt < 10 * 60_000L) {
            return;
        }
        lastNotiTitle = title;
        lastNotiText = text;
        lastNotiAt = now;
        Foreground.update(this, title, text);
    }

    // ---------------- 屏幕状态 ----------------

    /**
     * 熄屏 / 解锁广播。
     *
     * <p>有了它，熄屏那一刻就能立刻收尾，而不是等下一次采样才发现「屏幕关了」——
     * 那样会把熄屏后到采样之间的几十秒也算进上一个应用。
     */
    private void registerScreenReceiver() {
        if (receiverOn) {
            return;
        }
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_SCREEN_OFF);
        f.addAction(Intent.ACTION_SCREEN_ON);
        f.addAction(Intent.ACTION_USER_PRESENT);
        try {
            registerReceiver(screenReceiver, f);
            receiverOn = true;
        } catch (Throwable ignored) {
        }
    }

    private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null || intent.getAction() == null || recorder == null) {
                return;
            }
            String a = intent.getAction();
            if (Intent.ACTION_SCREEN_OFF.equals(a)) {
                recorder.tracker().screenOff(System.currentTimeMillis());
                pushNotification(true);
            } else if (Intent.ACTION_SCREEN_ON.equals(a) || Intent.ACTION_USER_PRESENT.equals(a)) {
                // 亮屏/解锁后立刻确认一次，别等下一个整分钟
                recorder.sampleNow();
            }
        }
    };

    // ---------------- 生命周期 ----------------

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        // 用户从最近任务里划掉了界面。记录是「长期开着」的功能，不该因此停掉；
        // 但如果用户已经关了自动记录，就不用再拉起来。
        if (Prefs.autoRecord(this)) {
            start(this);
        }
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onDestroy() {
        teardown();
        super.onDestroy();
    }

    private void teardown() {
        if (ui != null) {
            ui.removeCallbacksAndMessages(null);
        }
        if (receiverOn) {
            try {
                unregisterReceiver(screenReceiver);
            } catch (Throwable ignored) {
            }
            receiverOn = false;
        }
        if (recorder != null) {
            recorder.setListener(null);
            Recorder.shutdown();
            recorder = null;
        }
        Foreground.cancel(this);
    }

    private void stopForegroundCompat() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE);
            } else {
                stopForeground(true);
            }
        } catch (Throwable ignored) {
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ---------------- Recorder.Listener ----------------

    @Override
    public void onRecordingState(String label, long durationMs) {
        // 每次采样都会回调到这里（含屏幕开关时的即时采样）。把通知对齐放到通知线程上做 ——
        // pushNotification 自己会比对文字，没变化就什么都不发，所以这里可以每次都来一下。
        // 只靠那个 30 秒定时器的话，「切了应用」到「通知跟上」会白白多出最多半分钟。
        if (ui != null) {
            ui.removeCallbacks(notiSync);
            ui.post(notiSync);
        }
    }

    @Override
    public void onSessionKept(Event e) {
        pushNotification(true);
    }

    @Override
    public void onSessionDropped(String label, long durationMs) {
        // 被最小时长过滤掉的片段不打扰用户，但通知里那句「今天 N 条」会自然反映结果
    }
}
