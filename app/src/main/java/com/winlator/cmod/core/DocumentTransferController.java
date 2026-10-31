package com.winlator.cmod.core;

import android.app.Activity;
import android.content.Context;
import android.text.format.Formatter;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;
import androidx.fragment.app.Fragment;
import com.winlator.cmod.R;
import com.winlator.cmod.contentdialog.ContentDialog;
import com.winlator.cmod.widget.FileProgressDialog;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** UI orchestration shared by local and document browsers; provider I/O stays off the main thread. */
public final class DocumentTransferController {
    private final Fragment fragment;
    private final FileProgressDialog dialog;
    private final AtomicBoolean active, cancelled;
    private final Runnable refresh;
    private volatile TreeTransfer running;
    private volatile android.os.CancellationSignal providerIO;
    public DocumentTransferController(Fragment fragment, FileProgressDialog dialog,
                                      AtomicBoolean active, AtomicBoolean cancelled, Runnable refresh) {
        this.fragment = fragment; this.dialog = dialog; this.active = active; this.cancelled = cancelled; this.refresh = refresh;
    }
    private boolean alive(Activity owner, View view) {
        return !owner.isFinishing() && !owner.isDestroyed() && fragment.isAdded() && fragment.getView() == view;
    }
    public void cancel() {
        cancelled.set(true); TreeTransfer transfer = running; android.os.CancellationSignal signal = providerIO;
        if (transfer != null || signal != null) new Thread(() -> {
            if (signal != null) signal.cancel(); if (transfer != null) transfer.cancel();
        }, "DocumentTransferCancel").start();
    }
    public void paste(TreeTransfer.Node destination) {
        StorageClipboard.Snapshot snapshot = StorageClipboard.get();
        if (snapshot.items.isEmpty() || !active.compareAndSet(false, true)) return;
        Activity owner = fragment.requireActivity(); View view = fragment.requireView();
        Context context = owner;
        android.os.CancellationSignal checking = new android.os.CancellationSignal(); providerIO = checking;
        TreeTransfer.Node target = new StorageAccess(context.getContentResolver(), checking).cancellable(destination);
        cancelled.set(false); dialog.show(R.string.file_checking_transfer);
        new Thread(() -> {
            int conflicts = 0; String conflict = ""; Exception failure = null;
            try {
                Set<String> names = new HashSet<>();
                for (TreeTransfer.Node node : target.children()) names.add(node.listedInfo().name);
                for (StorageClipboard.Item item : snapshot.items) if (names.contains(item.name)) { conflicts++; conflict = item.name; }
            } catch (Exception error) { failure = error; }
            final int count = conflicts; final String name = conflict; final Exception error = failure;
            owner.runOnUiThread(() -> {
                providerIO = null; active.set(false); dialog.dismiss();
                if (!alive(owner, view) || cancelled.get()) return;
                if (error != null) {
                    android.util.Log.e("DocumentTransfer", "Cannot inspect destination", error);
                    ContentDialog.alert(owner, owner.getString(R.string.storage_document_unavailable), null); return;
                }
                if (count == 0) start(snapshot, destination, false);
                else {
                    ContentDialog confirm = new ContentDialog(owner);
                    confirm.setTitle(R.string.replace_existing_title);
                    confirm.setMessage(count == 1 ? owner.getString(R.string.replace_existing_single, name)
                            : owner.getString(R.string.replace_existing_multiple, count));
                    ((TextView)confirm.findViewById(R.id.BTConfirm)).setText(R.string.replace);
                    confirm.setOnConfirmCallback(() -> { if (alive(owner, view)) start(snapshot, destination, true); });
                    confirm.show();
                }
            });
        }, "DocumentTransferCheck").start();
    }
    private void start(StorageClipboard.Snapshot snapshot, TreeTransfer.Node destination, boolean replace) {
        if (StorageClipboard.get().version != snapshot.version || !active.compareAndSet(false, true)) return;
        Activity owner = fragment.requireActivity(); View view = fragment.requireView();
        Context context = owner;
        boolean keepScreen = (owner.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) != 0;
        AppUtils.keepScreenOn(owner); cancelled.set(false);
        dialog.show(snapshot.move ? R.string.moving_file : R.string.copying_file);
        android.os.CancellationSignal copying = new android.os.CancellationSignal(); providerIO = copying;
        new Thread(() -> {
            List<StorageClipboard.Item> completed = new ArrayList<>(); List<String> failures = new ArrayList<>();
            StorageAccess access = new StorageAccess(context.getContentResolver(), copying);
            TreeTransfer.Node target = access.cancellable(destination);
            long[] lastUpdate = {0}; int[] index = {0};
            TreeTransfer transfer = new TreeTransfer(cancelled, new TreeTransfer.Progress() {
                @Override public void verifying(String name) { dialog.updateIndeterminate(name, context.getString(R.string.file_verifying_copy)); }
                @Override public void copied(String name, long bytes) {
                    long now = android.os.SystemClock.uptimeMillis();
                    if (now - lastUpdate[0] >= 100) {
                        dialog.updateIndeterminate(name, context.getString(R.string.file_transfer_progress,
                                Formatter.formatFileSize(context, bytes), index[0] + 1, snapshot.items.size()));
                        lastUpdate[0] = now;
                    }
                }
            });
            running = transfer;
            try {
                for (StorageClipboard.Item item : snapshot.items) {
                    if (cancelled.get()) break;
                    dialog.updateIndeterminate(item.name, context.getString(R.string.file_transfer_item, index[0] + 1, snapshot.items.size()));
                    try {
                        TreeTransfer.Result result = transfer.transfer(access.resolve(item), target, snapshot.move, replace);
                        if (!snapshot.move || result.sourceRemoved) completed.add(item);
                        else failures.add(context.getString(R.string.file_source_retained, item.name));
                        if (!result.recovery.isEmpty()) failures.add(context.getString(R.string.file_recovery_items, String.join(", ", result.recovery)));
                    } catch (Exception error) {
                        android.util.Log.e("DocumentTransfer", "Transfer failed for " + item.name, error);
                        if (!cancelled.get()) failures.add(error instanceof TreeTransfer.ReplaceNotSupportedException
                                ? context.getString(R.string.file_replace_unsupported, item.name) : item.name);
                        if (error instanceof TreeTransfer.RecoveryException) failures.add(context.getString(R.string.file_recovery_items,
                                String.join(", ", ((TreeTransfer.RecoveryException)error).recovery)));
                    }
                    index[0]++;
                }
            } finally {
                running = null;
                providerIO = null;
                StorageClipboard.removeCompleted(snapshot, completed);
                owner.runOnUiThread(() -> {
                    active.set(false); dialog.dismiss();
                    if (!keepScreen) owner.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                    if (!alive(owner, view)) return;
                    if (!failures.isEmpty()) ContentDialog.alert(owner, owner.getString(R.string.file_transfer_errors, String.join("\n", failures)), null);
                    else if (cancelled.get()) Toast.makeText(owner, R.string.file_operation_cancelled, Toast.LENGTH_SHORT).show();
                    refresh.run();
                });
            }
        }, "DocumentTransfer").start();
    }
}
