package com.winlator.cmod.widget;

import android.content.Context;
import android.view.View;

import androidx.appcompat.view.menu.MenuBuilder;
import androidx.appcompat.view.menu.MenuPopupHelper;
import androidx.appcompat.widget.PopupMenu;

import com.winlator.cmod.core.PopupStyle;

/** Uses the same Menu and dispatch callbacks as PopupMenu, with a styled attached window. */
public class GlassPopupMenu extends PopupMenu {
    private final MenuPopupHelper popup;

    public GlassPopupMenu(Context context, View anchor) {
        super(context, anchor);
        popup = new MenuPopupHelper(context, (MenuBuilder)getMenu(), anchor, false,
                androidx.appcompat.R.attr.popupMenuStyle);
    }

    @Override public void show() {
        popup.show();
        PopupStyle.styleAnchoredList(((androidx.appcompat.view.menu.ShowableListMenu)popup.getPopup()).getListView());
    }
    @Override public void dismiss() { popup.dismiss(); }
    @Override public void setGravity(int gravity) { popup.setGravity(gravity); }
    @Override public int getGravity() { return popup.getGravity(); }
    @Override public void setForceShowIcon(boolean force) { popup.setForceShowIcon(force); }
    @Override public void setOnDismissListener(OnDismissListener listener) {
        popup.setOnDismissListener(() -> { if (listener != null) listener.onDismiss(this); });
    }
}
