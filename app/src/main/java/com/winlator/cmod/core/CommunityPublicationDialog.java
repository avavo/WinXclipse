package com.winlator.cmod.core;

import android.app.Activity;
import android.content.Context;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.appcompat.app.AlertDialog;
import com.winlator.cmod.R;
import java.io.File;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/** Export completion: publish immediately, then offer an explicit, confirmed withdrawal. */
public final class CommunityPublicationDialog {
    private static final Executor IO = Executors.newFixedThreadPool(2);
    interface Service {
        String publish(Context context, File archive) throws Exception;
        void withdraw(Context context, String id) throws Exception;
    }
    private final Activity host;
    private final File output;
    private final Runnable share;
    private final Service service;
    private final Executor executor;
    private AlertDialog resultDialog;
    private boolean withdrawing;

    public static void show(Activity host, File output, Runnable share) {
        new CommunityPublicationDialog(host, output, share, new Service() {
            public String publish(Context context, File archive) throws Exception { return CommunityService.publish(context, archive).id; }
            public void withdraw(Context context, String id) throws Exception { CommunityService.withdraw(context, id); }
        }, IO).start();
    }

    CommunityPublicationDialog(Activity host, File output, Runnable share, Service service, Executor executor) {
        this.host = host; this.output = output; this.share = share; this.service = service; this.executor = executor;
    }
    private boolean alive() { return !host.isFinishing() && !host.isDestroyed(); }
    private int dp(int value) { return Math.round(value * host.getResources().getDisplayMetrics().density); }

    void start() {
        if (!alive()) return;
        PreloaderDialog progress = new PreloaderDialog(host); progress.show(R.string.community_publishing);
        Context app = host.getApplicationContext();
        executor.execute(() -> {
            String id = null; int message = R.string.community_publish_success;
            try { id = service.publish(app, output); }
            catch (CommunityService.ServiceException error) {
                message = error.status == 429 ? R.string.community_publish_limited : R.string.community_publish_failed;
            } catch (Exception error) { message = R.string.community_publish_failed; }
            String published = id; int result = message;
            host.runOnUiThread(() -> {
                progress.close();
                if (alive()) showResult(published, result);
            });
        });
    }

    private Button button(LinearLayout parent, int text) {
        Button button = new Button(host); button.setText(text); button.setAllCaps(false);
        parent.addView(button, new LinearLayout.LayoutParams(-1, -2)); return button;
    }
    private void showResult(String id, int message) {
        LinearLayout body = new LinearLayout(host); body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(20), dp(8), dp(20), dp(12));
        TextView status = new TextView(host);
        status.setText(host.getString(message) + "\n\n" + host.getString(R.string.config_export_saved, output.getAbsolutePath()));
        body.addView(status, new LinearLayout.LayoutParams(-1, -2));
        ScrollView scroll = new ScrollView(host); scroll.addView(body);
        resultDialog = new PopupStyle.Builder(host).setTitle(R.string.config_export_complete).setView(scroll).create();
        button(body, R.string.config_export_share).setOnClickListener(v -> share.run());
        if (id == null) button(body, R.string.community_retry_action).setOnClickListener(v -> { resultDialog.dismiss(); start(); });
        button(body, android.R.string.ok).setOnClickListener(v -> resultDialog.dismiss());
        // A vertical layout guarantees this action stays BELOW OK, including narrow screens.
        if (id != null) button(body, R.string.community_cancel_submission).setOnClickListener(v -> {
            if (withdrawing) return;
            new PopupStyle.Builder(host).setTitle(R.string.community_cancel_submission)
                    .setMessage(R.string.community_cancel_confirm)
                    .setNegativeButton(R.string.no, null)
                    .setPositiveButton(R.string.yes, (dialog, which) -> withdraw(id, status, (Button)v)).show();
        });
        resultDialog.show();
    }

    private void withdraw(String id, TextView status, Button cancel) {
        if (withdrawing) return;
        withdrawing = true; cancel.setEnabled(false); status.setText(R.string.community_withdrawing);
        Context app = host.getApplicationContext();
        executor.execute(() -> {
            boolean removed;
            try { service.withdraw(app, id); removed = true; }
            catch (Exception error) { removed = false; }
            boolean success = removed;
            host.runOnUiThread(() -> {
                withdrawing = false;
                if (!alive() || !resultDialog.isShowing()) return;
                status.setText(success ? R.string.community_withdrawn : R.string.community_withdraw_failed);
                if (success) cancel.setVisibility(android.view.View.GONE);
                else cancel.setEnabled(true);
            });
        });
    }
}
