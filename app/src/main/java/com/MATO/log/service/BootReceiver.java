package com.MATO.log.service;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.MATO.log.util.Prefs;

/**
 * 开机自启。
 *
 * <p>自动记录的价值取决于「它一直在」，所以开机后要能自己回来。
 *
 * <p><b>为什么除了 BOOT_COMPLETED 还监听 LOCKED_BOOT_COMPLETED</b>：
 * 部分机型上 BOOT_COMPLETED 要等用户解锁才发；只监听它的话，重启后到第一次解锁
 * 之间是空档。两者都监听，谁先到就用谁启动，重复启动由服务自身兜住。
 *
 * <p><b>不去抢厂商的「自启动」白名单</b>：那是 ROM 的管控，标准 Android 没有接口。
 * 这里只保证「系统允许我们起来的时候，我们确实起来了」，剩下的交给设置页里的引导入口。
 *
 * <p><b>与参考 app 的差异</b>：它只监听 BOOT_COMPLETED。我们多监听两个动作是
 * 为了覆盖「上了锁的开机」这种情况，属于净收益，保留。
 */
public final class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null || intent.getAction() == null) {
            return;
        }
        String action = intent.getAction();
        boolean boot = Intent.ACTION_BOOT_COMPLETED.equals(action)
                || "android.intent.action.QUICKBOOT_POWERON".equals(action)
                || Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action);
        if (!boot) {
            return;
        }
        // 用户没开自动记录，开机就不要自己起来
        if (!Prefs.autoRecord(context)) {
            return;
        }
        RecordService.start(context);
    }
}
