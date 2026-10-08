package com.MATO.log.ui;

import android.view.View;

/**
 * 一个「跨度」界面：日列表 / 周列表 / 月历 / 年历。
 * 二级界面就是同一个接口的另一层实现，由 OnyxHost 负责堆叠。
 */
public interface LevelView {

    /** 生成未被加入视图树的根视图 */
    View createView();

    /** 用给定的时间锚点渲染自己（锚点在该跨度内的任意时刻即可） */
    void bind(long anchor);

    /** 点击了某一格：dayMillis 为该格代表的日期零点 */
    void onTap(long dayMillis);

    void onSwipeHorizontal(boolean forward);

    View getRoot();
}
