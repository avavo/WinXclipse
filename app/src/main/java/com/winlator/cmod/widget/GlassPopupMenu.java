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
    private final View anchor;

    public GlassPopupMenu(Context context, View anchor) {
        super(context, anchor);
        this.anchor = anchor;
        popup = new MenuPopupHelper(context, (MenuBuilder)getMenu(), anchor, false,
                androidx.appcompat.R.attr.popupMenuStyle);
    }

    @Override public void show() {
        for (int i = 0; i < getMenu().size(); i++) {
            android.view.MenuItem item = getMenu().getItem(i);
            if (item.getIcon() != null) item.setIcon(androidx.core.graphics.drawable.DrawableCompat.wrap(
                    item.getIcon().mutate()));
            if (item.getIcon() != null) androidx.core.graphics.drawable.DrawableCompat.setTint(item.getIcon(),
                    com.winlator.cmod.core.AppUtils.isDarkMode(anchor.getContext()) ? 0xFFF5F1FF : 0xFF39265C);
        }
        popup.show();
        PopupStyle.styleAnchoredList(((androidx.appcompat.view.menu.ShowableListMenu)popup.getPopup()).getListView(), anchor);
    }
    @Override public void dismiss() { popup.dismiss(); }
    @Override public void setGravity(int gravity) { popup.setGravity(gravity); }
    @Override public int getGravity() { return popup.getGravity(); }
    @Override public void setForceShowIcon(boolean force) { popup.setForceShowIcon(force); }
    @Override public void setOnDismissListener(OnDismissListener listener) {
        popup.setOnDismissListener(() -> { if (listener != null) listener.onDismiss(this); });
    }
}
