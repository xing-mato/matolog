package com.MATO.log.ui;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.TextView;

import com.MATO.log.R;

/**
 * 开屏：品牌字轻微上浮淡入，缩短的横线展开，然后整体淡出进入主界面。
 * 全程约 1.1 秒，只在真正的冷启动出现（点图标进入时）。
 */
public class SplashActivity extends BaseActivity {

    private static final long HOLD_MS = 620;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean leaving;

    private final Runnable leave = new Runnable() {
        @Override
        public void run() {
            goMain();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_splash);

        TextView mark = findViewById(R.id.splash_mark);
        TextView sub = findViewById(R.id.splash_sub);
        View rule = findViewById(R.id.splash_rule);

        float markFrom = -getResources().getDisplayMetrics().density * 14f;
        float subFrom = -getResources().getDisplayMetrics().density * 8f;
        float ruleFrom = -getResources().getDisplayMetrics().density * 6f;

        mark.setAlpha(0f);
        mark.setTranslationY(markFrom);
        sub.setAlpha(0f);
        sub.setTranslationY(subFrom);
        rule.setAlpha(0f);
        rule.setTranslationY(ruleFrom);
        rule.setScaleX(0.25f);

        AnimatorSet set = new AnimatorSet();
        set.playTogether(
                together(mark, markFrom, 0),
                together(sub, subFrom, 120),
                together(rule, ruleFrom, 200));
        set.start();

        rule.animate().scaleX(1f).setStartDelay(200).setDuration(460)
                .setInterpolator(new DecelerateInterpolator()).start();

        handler.postDelayed(leave, HOLD_MS);
    }

    private AnimatorSet together(View v, float fromY, long delay) {
        ObjectAnimator a = ObjectAnimator.ofFloat(v, "alpha", 0f, 1f);
        ObjectAnimator b = ObjectAnimator.ofFloat(v, "translationY", fromY, 0f);
        AnimatorSet s = new AnimatorSet();
        s.playTogether(a, b);
        s.setStartDelay(delay);
        s.setDuration(420);
        s.setInterpolator(new DecelerateInterpolator());
        return s;
    }

    /** 整体淡出后进主界面 */
    private void goMain() {
        if (leaving || isFinishing()) {
            return;
        }
        leaving = true;
        View root = findViewById(android.R.id.content);
        root.animate().alpha(0f).setDuration(180).setListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                startActivity(new Intent(SplashActivity.this, MainActivity.class));
                overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
                finish();
            }
        }).start();
    }

    @Override
    public void onBackPressed() {
        handler.removeCallbacks(leave);
        goMain();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(leave);
        super.onDestroy();
    }
}
