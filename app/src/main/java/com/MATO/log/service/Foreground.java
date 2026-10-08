package com.MATO.log.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import com.MATO.log.R;
import com.MATO.log.ui.MainActivity;

/**
 * 自动记录的常驻通知。
 *
 * <p>这不是「打扰」，是功能的工作条件：Android 8 起后台服务必须前台化，
 * 否则进程随时被冻结、采样就断了。
 *
 * <p>内容刻意克制：只显示「正在记什么 + 时长 + 今天几条」，外加**刷新 / 关闭**两个动作。
 * **不显示记录正文**，并设为 {@code VISIBILITY_SECRET} —— 锁屏上被别人看到的风险
 * 要提前掐掉，而不是等用户抱怨。
 *
 * <p><b>为什么是「刷新」和「关闭」这两个。</b>1.3.3 及以前这里是「暂停 / 停止」：
 * 「暂停」只是把这一条的状态字改掉（采样其实照跑），而「停止」是真的关掉自动记录，
 * 两者看起来都是在让记录停一下，用户没法从字面上分清该点哪个。
 * 现在只留一个动作表达「不记了」——「关闭」，它做的事和设置页里那个总开关完全一样；
 * 另一个位置给「刷新」：采样是一分钟一轮，想让通知立刻反映当下正在用什么，点它就行。
 */
public final class Foreground {

    public static final int NOTI_ID = 4011;
    private static final String CHANNEL = "recording";

    /** 立刻采一次并刷新这条通知 */
    public static final String ACTION_REFRESH = "com.MATO.log.REC_REFRESH";
    /** 关掉自动记录并撤掉这条通知（等同设置页里的总开关） */
    public static final String ACTION_STOP = "com.MATO.log.REC_STOP";

    private Foreground() {
    }

    public static void ensureChannel(Context c) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationManager nm =
                (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null || nm.getNotificationChannel(CHANNEL) != null) {
            return;
        }
        NotificationChannel ch = new NotificationChannel(CHANNEL,
                c.getString(R.string.rec_channel_name),
                NotificationManager.IMPORTANCE_LOW);
        ch.setDescription(c.getString(R.string.rec_channel_desc));
        ch.setShowBadge(false);
        ch.setSound(null, null);
        ch.enableVibration(false);
        // 锁屏上不显示内容
        ch.setLockscreenVisibility(Notification.VISIBILITY_SECRET);
        nm.createNotificationChannel(ch);
    }

    public static Notification build(Context c, String title, String text) {
        ensureChannel(c);
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            b = new Notification.Builder(c, CHANNEL);
        } else {
            b = new Notification.Builder(c);
        }

        Intent open = new Intent(c, MainActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent content = PendingIntent.getActivity(c, 0, open, flags());

        b.setSmallIcon(R.drawable.ic_notify_log)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(content)
                // 没有「暂停」了，这条通知在服务活着的时候一直有效
                .setOngoing(true)
                .setShowWhen(false)
                .setOnlyAlertOnce(true)
                .setPriority(Notification.PRIORITY_LOW)
                .setVisibility(Notification.VISIBILITY_SECRET);

        b.addAction(new Notification.Action.Builder(null, c.getString(R.string.rec_action_refresh),
                action(c, ACTION_REFRESH, 1)).build());
        b.addAction(new Notification.Action.Builder(null, c.getString(R.string.rec_action_stop),
                action(c, ACTION_STOP, 2)).build());
        return b.build();
    }

    public static void update(Context c, String title, String text) {
        try {
            NotificationManager nm =
                    (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                nm.notify(NOTI_ID, build(c, title, text));
            }
        } catch (Throwable ignored) {
            // 通知被用户关掉时个别 ROM 会抛；记录本身不该因此中断
        }
    }

    public static void cancel(Context c) {
        try {
            NotificationManager nm =
                    (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                nm.cancel(NOTI_ID);
            }
        } catch (Throwable ignored) {
        }
    }

    private static PendingIntent action(Context c, String action, int req) {
        Intent i = new Intent(c, RecordService.class);
        i.setAction(action);
        return PendingIntent.getService(c, req, i, flags());
    }

    private static int flags() {
        return PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
    }
}
