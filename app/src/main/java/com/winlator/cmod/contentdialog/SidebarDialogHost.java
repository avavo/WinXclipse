package com.winlator.cmod.contentdialog;

/** An in-session editor can share its content and callbacks with a drawer host. */
public interface SidebarDialogHost {
    boolean presentSidebarDialog(ContentDialog dialog);
    void dismissSidebarDialog(ContentDialog dialog);
}
