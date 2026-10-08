package com.MATO.log.ui;

import android.view.View;

/** 跨度工厂：由宿主界面提供，OnyxHost 用它逐层搭建二级界面 */
public interface LevelFactory {
    LevelView make(int level, OnyxHost host);
}
