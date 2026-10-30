package com.lxj.xpopup;

import android.content.Context;
import android.view.View;

/** Compiles the public entry points expected by Java consumers. */
final class JavaApiCompatibility {
    private JavaApiCompatibility() {
    }

    static void verify(Context context, View anchor) {
        XPopup.setPrimaryColor(0);
        XPopup.setAnimationDuration(300);
        XPopup.setStatusBarBgColor(0);
        XPopup.setNavigationBarColor(0);
        XPopup.setShadowBgColor(0);
        XPopup.setIsLightStatusBar(true);
        XPopup.setIsLightNavigationBar(false);
        XPopup.isLightStatusBar = 1;
        XPopup.isLightNavigationBar = -1;

        new XPopup.Builder(context)
                .atView(anchor)
                .asConfirm("Title", "Message", () -> { })
                .show();

        new XPopup.Builder(context)
                .asBottomList("Title", new String[] {"One"}, (position, text) -> { })
                .show();

        new XPopup.Builder(context).asLoading().show();
    }
}
