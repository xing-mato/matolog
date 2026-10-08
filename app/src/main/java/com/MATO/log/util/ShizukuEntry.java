package com.MATO.log.util;

import rikka.shizuku.ShizukuProvider;

/**
 * Shizuku 接入用的 provider。
 *
 * <p>Shizuku 要跟应用通信，靠的是查一个固定 authority 的 ContentProvider
 * （{@code <包名>.shizuku}），通过它把服务 binder 交给应用。这个 provider 必须
 * 由应用自己声明在清单里 —— 我们用的是 {@code compileOnly/implementation} 引入，
 * AAR 自带的清单不会参与合并（实测确认）。
 *
 * <p>所以这里做一个**空子类**：逻辑全在 {@link ShizukuProvider} 里，
 * 我们只负责给它一个属于本包的类名，好在清单里声明。这样比自己复刻整个
 * provider 协议稳妥 —— 那套 binder 往返握手一旦写错是静默失败，
 * 只在部分设备上表现，不值得自己扛。
 */
public class ShizukuEntry extends ShizukuProvider {
}
