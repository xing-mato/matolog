package com.MATO.log.ui;

import android.content.Context;

/**
 * Toast 的薄封装。
 *
 * <p>存在的理由很实际：工程里到处写 {@code Toast.makeText(this, ..., Toast.LENGTH_SHORT).show()}
 * 又长又容易漏 {@code .show()}（漏了不报错，只是什么都不显示，最难查）。
 * 收成一个方法之后，调用点短且不会写错。
 */
public final class Toast {

    private Toast() {
    }

    public static void show(Context c, String text) {
        if (c == null || text == null || text.isEmpty()) {
            return;
        }
        try {
            android.widget.Toast.makeText(c, text, android.widget.Toast.LENGTH_SHORT).show();
        } catch (Throwable ignored) {
        }
    }

    public static void show(Context c, int textRes) {
        if (c == null) {
            return;
        }
        show(c, c.getString(textRes));
    }
}
