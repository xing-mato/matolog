package com.MATO.log.ui;

import android.animation.TimeInterpolator;
import android.app.Dialog;
import android.content.Context;
import android.graphics.drawable.ColorDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.PathInterpolator;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;

import com.MATO.log.R;

/**
 * 全应用的运动参数与非线性曲线。
 *
 * 一处定义、各处复用，免得每个界面各写一套时长和缓动 —— 那样最容易出现
 * 「有的地方跟手、有的地方生硬」的不协调感。
 *
 * 曲线取的是 Material 那两条经典非线性缓动：
 * - EMPHASIZED_DECELERATE：进场与「开始移动」用。前段起步快、后段徐徐收住，
 *   所以元素是「冲出来再稳住」，而不是匀速或先快后慢地僵住。
 * - EMPHASIZED_ACCELERATE：退场用。起步慢、末尾快，消失得干净利落。
 * - OVERSHOOT：需要一点回弹的小动作（按钮出现、弹窗进入）用，略微过冲再收回。
 */
final class Motion {

    private Motion() {
    }

    /** 标记：这个容器已经是本工具包过的，别重复包 */
    private static final Object DRESSED = new Object();

    /** 进场 / 位移：快起慢收 */
    static final TimeInterpolator DECELERATE = new PathInterpolator(0.05f, 0.7f, 0.1f, 1f);

    /** 退场：慢起快收，干脆地消失 */
    static final TimeInterpolator ACCELERATE = new PathInterpolator(0.3f, 0f, 0.8f, 0.15f);

    /** 标准 ease-in-out，用于循环性的状态变化 */
    static final TimeInterpolator STANDARD = new PathInterpolator(0.2f, 0f, 0.2f, 1f);

    /** 略微过冲再收回：幅度很小，只是让动作「有重量」 */
    static final TimeInterpolator OVERSHOOT = new PathInterpolator(0.2f, 0f, 0.15f, 1.3f);

    // 时长（毫秒）：进场略长、退场略短，符合「进慢出快」的观感
    static final long ENTER_MS = 260;
    static final long MOVE_MS = 230;
    static final long EXIT_MS = 170;
    static final long FADE_MS = 190;
    /** 列表逐项出现的间隔 */
    static final long STEP_MS = 26;
    /** 逐项动画最多铺开多少个，超过就不再累加延迟 */
    static final int STEP_MAX = 8;

    static int dp(Context ctx, float v) {
        return (int) (ctx.getResources().getDisplayMetrics().density * v + 0.5f);
    }

    /**
     * 换页的入场：新的一层从 direction 方向滑入并淡入。
     *
     * @param direction 1 = 往里进 / 下一段（从右滑入）；-1 = 往后退 / 上一段（从左滑入）
     */
    static void enterPage(View v, int direction, float distanceDp) {
        if (v == null) {
            return;
        }
        v.animate().cancel();
        v.setAlpha(0f);
        v.setTranslationX(direction * dp(v.getContext(), distanceDp));
        v.animate()
                .alpha(1f)
                .translationX(0f)
                .setDuration(ENTER_MS)
                .setInterpolator(DECELERATE)
                .start();
    }

    /**
     * 换页的退场：被替换掉的那一层朝相反方向轻微退开并淡出。
     * 有它才有「一层压着一层」的层次感，否则旧层是啪地消失。
     */
    static void exitPage(View v, int direction, float distanceDp) {
        if (v == null) {
            return;
        }
        v.animate().cancel();
        v.animate()
                .alpha(0f)
                .translationX(-direction * dp(v.getContext(), distanceDp))
                .setDuration(EXIT_MS)
                .setInterpolator(ACCELERATE)
                .start();
    }

    /** 列表逐项出现：轻微上浮 + 淡入，按 index 依次错开 */
    static void enterItem(View v, int index) {
        if (v == null) {
            return;
        }
        long delay = Math.min(index, STEP_MAX) * STEP_MS;
        v.setAlpha(0f);
        v.setTranslationY(dp(v.getContext(), 7));
        v.animate()
                .alpha(1f)
                .translationY(0f)
                .setStartDelay(delay)
                .setDuration(MOVE_MS)
                .setInterpolator(DECELERATE)
                .start();
    }

    /** 淡入：用于状态变化（例如按钮由不可用转为可用） */
    static void fadeIn(View v, long duration) {
        if (v == null) {
            return;
        }
        v.animate().cancel();
        v.animate().alpha(1f).setDuration(duration).setInterpolator(STANDARD).start();
    }

    static void fadeTo(View v, float alpha, long duration) {
        if (v == null) {
            return;
        }
        v.animate().cancel();
        v.animate().alpha(alpha).setDuration(duration).setInterpolator(STANDARD).start();
    }

    // ---------------- 弹窗 ----------------

    /**
     * 显示一个弹窗，并按本应用的曲线进场。
     *
     * 调用点写成 `Motion.showBuilder(new AlertDialog.Builder(act)...链...);`
     * 取代原来的 `...链....show();`。
     * 之所以要包一层：AlertDialog.Builder.setOnShowListener 返回 void，
     * 接不进那条链式调用，只能在链的收尾处做。
     */
    static void showBuilder(android.app.AlertDialog.Builder b) {
        android.app.AlertDialog dialog = b.create();
        dressDialog(dialog);
        dialog.show();
    }

    /**
     * 让一个弹窗改用本应用的运动曲线进场。
     *
     * 系统的 AlertDialog 是默认的淡入淡出，和别处的节奏对不上；而
     * AlertDialog.Builder.setOnShowListener 返回 void、接不进链式调用。
     * 所以由 {@link #showBuilder} 在 create 之后、show 之前调用这里：
     * 把内容区包一层带圆角的卡片，按轻微放大 + 淡入进场
     * （只动内容区、不动窗口背景，免得 9-patch 被拉变形）。
     */
    static void dressDialog(final Dialog dialog) {
        if (dialog == null) {
            return;
        }
        Window w = dialog.getWindow();
        if (w == null) {
            return;
        }
        w.setDimAmount(0.42f);
        w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);

        View content = w.findViewById(android.R.id.content);
        if (!(content instanceof ViewGroup)) {
            return;
        }
        final ViewGroup group = (ViewGroup) content;
        if (group.getChildCount() == 0) {
            return;
        }
        View inner = group.getChildAt(0);
        if (inner instanceof LinearLayout && ((LinearLayout) inner).getTag() == DRESSED) {
            return;     // 已经打扮过（例如 Choices 自己包的），别套两层
        }
        group.removeView(inner);
        final LinearLayout box = new LinearLayout(dialog.getContext());
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundResource(R.drawable.bg_dialog);
        box.setTag(DRESSED);
        box.addView(inner, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        group.addView(box, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        box.setAlpha(0f);
        box.setScaleX(0.94f);
        box.setScaleY(0.94f);
        box.setTranslationY(dp(dialog.getContext(), 8));
        box.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .translationY(0f)
                .setDuration(ENTER_MS)
                .setInterpolator(OVERSHOOT)
                .start();
    }
}
