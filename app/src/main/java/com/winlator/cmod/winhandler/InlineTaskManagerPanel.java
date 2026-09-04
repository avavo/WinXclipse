package com.winlator.cmod.winhandler;

import android.app.ActivityManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;

import com.winlator.cmod.R;
import com.winlator.cmod.XServerDisplayActivity;
import com.winlator.cmod.contentdialog.ContentDialog;
import com.winlator.cmod.core.CPUStatus;
import com.winlator.cmod.core.ProcessHelper;
import com.winlator.cmod.widget.CPUListView;
import com.winlator.cmod.widget.MemoryUsageBarView;
import com.winlator.cmod.xserver.Window;
import com.winlator.cmod.xserver.XLock;
import com.winlator.cmod.xserver.XServer;

/** Lightweight task manager embedded in the in-session sidebar. */
public class InlineTaskManagerPanel extends LinearLayout implements OnGetProcessInfoListener {
    private final XServerDisplayActivity activity;
    private final LayoutInflater inflater;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean running;

    private final Runnable refreshRunnable = new Runnable() {
        @Override public void run() {
            if (!running) return;
            updateHardwareInfo();
            activity.getWinHandler().listProcesses();
            handler.postDelayed(this, 1000);
        }
    };

    public InlineTaskManagerPanel(XServerDisplayActivity activity) {
        super(activity);
        this.activity = activity;
        this.inflater = LayoutInflater.from(activity);
        setOrientation(VERTICAL);
        inflater.inflate(R.layout.inline_task_manager, this, true);
        findViewById(R.id.BTInlineNewTask).setOnClickListener(v ->
                ContentDialog.prompt(activity, R.string.new_task, "taskmgr.exe",
                        command -> activity.getWinHandler().exec(command)));
    }

    public void start() {
        if (running) return;
        running = true;
        findViewById(R.id.SVInlineCPUInfo).scrollTo(0, 0);
        findViewById(R.id.SVInlineProcessList).scrollTo(0, 0);
        activity.getWinHandler().setOnGetProcessInfoListener(this);
        handler.post(refreshRunnable);
    }

    public void stop() {
        running = false;
        handler.removeCallbacks(refreshRunnable);
        if (activity.getWinHandler() != null)
            activity.getWinHandler().setOnGetProcessInfoListener(null);
    }

    private int resolveOnSurfaceColor() {
        TypedValue typed = new TypedValue();
        if (activity != null && activity.getTheme() != null
                && activity.getTheme().resolveAttribute(R.attr.winxOnSurfaceColor, typed, true)) {
            return typed.data;
        }
        return 0xFFF8FAFC;
    }

    private void updateHardwareInfo() {
        short[] clocks = CPUStatus.getCurrentClockSpeeds();
        LinearLayout list = findViewById(R.id.LLInlineCPUInfo);
        while (list.getChildCount() < clocks.length) {
            TextView core = new TextView(activity);
            core.setTextColor(resolveOnSurfaceColor());
            core.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            core.setSingleLine(true);
            list.addView(core);
        }
        while (list.getChildCount() > clocks.length)
            list.removeViewAt(list.getChildCount() - 1);

        int totalCurrent = 0;
        int totalMaximum = 0;
        for (int i = 0; i < clocks.length; i++) {
            int current = Math.max(0, clocks[i]);
            int maximum = Math.max(0, CPUStatus.getMaxClockSpeed(i));
            int usage = maximum > 0 ? Math.min(100, Math.round(current * 100f / maximum)) : 0;
            totalCurrent += current;
            totalMaximum += maximum;
            ((TextView) list.getChildAt(i)).setText("Core " + (i + 1) + "   "
                    + current + " / " + maximum + " MHz   " + usage + "%");
        }
        int cpuUsage = totalMaximum > 0
                ? Math.min(100, Math.round(totalCurrent * 100f / totalMaximum)) : 0;
        ((TextView) findViewById(R.id.TVInlineCPUTitle)).setText("CPU (" + cpuUsage + "%)");
        ActivityManager manager = (ActivityManager) activity.getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo info = new ActivityManager.MemoryInfo();
        manager.getMemoryInfo(info);
        long used = info.totalMem - info.availMem;
        int percent = info.totalMem > 0 ? (int) Math.min(100, Math.round(used * 100d / info.totalMem)) : 0;
        ((MemoryUsageBarView) findViewById(R.id.InlineMemoryUsageBar))
                .setMemoryUsage(percent, used, info.totalMem);
    }

    @Override
    public void onGetProcessInfo(int index, int count, ProcessInfo processInfo) {
        activity.runOnUiThread(() -> {
            if (!running) return;
            LinearLayout list = findViewById(R.id.LLInlineProcessList);
            TextView empty = findViewById(R.id.TVInlineEmptyText);
            ((TextView) findViewById(R.id.TVInlineProcessCount))
                    .setText(activity.getString(R.string.processes) + ": " + count);

            if (count == 0) {
                list.removeAllViews();
                empty.setVisibility(VISIBLE);
                return;
            }
            empty.setVisibility(GONE);
            int children = list.getChildCount();
            View item = index < children ? list.getChildAt(index)
                    : inflater.inflate(R.layout.process_info_list_item, list, false);
            ((TextView) item.findViewById(R.id.TVName)).setText(
                    processInfo.name + (processInfo.wow64Process ? " *32" : ""));
            ((TextView) item.findViewById(R.id.TVPID)).setText(String.valueOf(processInfo.pid));
            ((TextView) item.findViewById(R.id.TVMemoryUsage))
                    .setText(processInfo.getFormattedMemoryUsage());
            item.findViewById(R.id.BTMenu).setOnClickListener(v -> showProcessMenu(v, processInfo));
            updateProcessIcon(item, processInfo);
            if (index >= children) list.addView(item);
            if (index == count - 1 && children > count) {
                for (int i = children - 1; i >= count; i--) list.removeViewAt(i);
            }
        });
    }

    private void updateProcessIcon(View item, ProcessInfo processInfo) {
        ImageView icon = item.findViewById(R.id.IVIcon);
        icon.setImageResource(R.drawable.taskmgr_process);
        XServer xServer = activity.getXServer();
        try (XLock ignored = xServer.lock(XServer.Lockable.WINDOW_MANAGER)) {
            Window window = xServer.windowManager.findWindowWithProcessId(processInfo.pid);
            if (window != null) {
                Bitmap bitmap = xServer.pixmapManager.getWindowIcon(window);
                if (bitmap != null) icon.setImageBitmap(bitmap);
            }
        }
    }

    private void showProcessMenu(View anchor, ProcessInfo processInfo) {
        PopupMenu menu = new PopupMenu(activity, anchor);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) menu.setForceShowIcon(true);
        menu.inflate(R.menu.process_popup_menu);
        menu.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == R.id.process_affinity) {
                showAffinityDialog(processInfo);
            } else if (item.getItemId() == R.id.bring_to_front) {
                activity.getWinHandler().bringToFront(processInfo.name);
                activity.closeInlineTaskManager(true);
            } else if (item.getItemId() == R.id.process_end) {
                ContentDialog.confirm(activity, R.string.do_you_want_to_end_this_process,
                        () -> activity.getWinHandler().killProcess(processInfo.name));
            }
            return true;
        });
        menu.show();
    }

    private void showAffinityDialog(ProcessInfo processInfo) {
        ContentDialog dialog = new ContentDialog(activity, R.layout.cpu_list_dialog);
        dialog.setTitle(processInfo.name);
        dialog.setIcon(R.drawable.icon_cpu);
        CPUListView cpuList = dialog.findViewById(R.id.CPUListView);
        cpuList.setCheckedCPUList(processInfo.getCPUList());
        dialog.setOnConfirmCallback(() -> activity.getWinHandler().setProcessAffinity(
                processInfo.pid, ProcessHelper.getAffinityMask(cpuList.getCheckedCPUList())));
        dialog.show();
    }
}
