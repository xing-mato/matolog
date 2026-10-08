package com.MATO.log.ui;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;

import com.MATO.log.util.App;
import com.MATO.log.util.DarkTheme;
import com.MATO.log.util.Lang;

/**
 * 所有界面的基类。
 *
 * <p>它做两件事：在 Context 附着之前按用户选的档位改写配置里的夜间位；
 * 以及把自己登记到 {@link App} 的存活表里，好让档位改变时能被统一重建。
 *
 * <p><b>为什么着色必须放在这里</b>：实测在 Android 15 上，只改 Application 的
 * base context 是**不生效**的 —— Activity 取 @color 时走的仍是自己那份 Configuration，
 * values-night 还是按系统状态来选。结果是「用户选了始终浅色、系统是深色 → 界面依然全黑」。
 * 这是真机截图确认过的。
 *
 * <p><b>为什么用 attachBaseContext 而不是 applyOverrideConfiguration</b>：
 * 后者要求在任何资源访问之前调用，时机稍晚就会抛
 * 「Cannot apply override to a context that has already been used」，
 * 而这个「稍晚」在不同 ROM 上边界并不一致。attachBaseContext 时机固定、没有这个不确定性。
 *
 * <p>包一层 Context 的代价极小（只在界面创建时做一次），换来的是全局配色可开关 ——
 * 这是唯一一处需要每个界面都参与的地方，所以用继承强制统一，
 * 避免将来新增界面时漏掉、出现「别的页面都变深色了就这一页没变」。
 */
public class BaseActivity extends Activity {

    @Override
    protected void attachBaseContext(Context newBase) {
        // 先按用户选的语言包一层，再按配色档位包一层。
        // 顺序有讲究：语言决定取哪一份 strings、配色决定取哪一份 color，两者都靠
        // Configuration，必须叠在同一个 Context 上；先语言后配色，
        // 让后者在前者算好的那份配置上继续改。
        super.attachBaseContext(DarkTheme.wrap(Lang.wrap(newBase)));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 登记进来，好让配色档位改变时 App 能把这一页也重建掉
        App.watch(this);
    }

    @Override
    protected void onDestroy() {
        App.forget(this);
        super.onDestroy();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 状态栏/导航栏图标的明暗没法靠资源切换解决，每次回到前台都对一次，
        // 避免用户在系统设置里改了深色之后出现「底黑了字还黑」的状态栏
        DarkTheme.applyWindow(this);
    }
}
