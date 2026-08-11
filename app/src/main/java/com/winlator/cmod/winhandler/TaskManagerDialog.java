package com.winlator.cmod.winhandler;

import android.app.ActivityManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.os.Build;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.util.TypedValue;

import com.winlator.cmod.R;
import com.winlator.cmod.XServerDisplayActivity;
import com.winlator.cmod.contentdialog.ContentDialog;
import com.winlator.cmod.core.CPUStatus;
import com.winlator.cmod.core.FileUtils;
import com.winlator.cmod.core.ProcessHelper;
import com.winlator.cmod.widget.CPUListView;
import com.winlator.cmod.widget.MemoryUsageBarView;
import com.winlator.cmod.xenvironment.ImageFs;
import com.winlator.cmod.xserver.Window;
import com.winlator.cmod.xserver.XLock;
import com.winlator.cmod.xserver.XServer;

import java.io.File;
import java.util.Timer;
import java.util.TimerTask;

public class TaskManagerDialog extends ContentDialog implements OnGetProcessInfoListener {
    private final XServerDisplayActivity activity;
    private final LayoutInflater inflater;
    private Timer timer;
    private final Object lock = new Object();

    public TaskManagerDialog(XServerDisplayActivity activity) {
        super(activity, R.layout.task_manager_dialog);
        this.activity = activity;
        setCancelable(false);
        setTitle(R.string.task_manager);
        setIcon(R.drawable.icon_task_manager);

        Button cancelButton = findViewById(R.id.BTCancel);
        cancelButton.setText(R.string.new_task);
        cancelButton.setOnClickListener((v) -> {
            dismiss();
            ContentDialog.prompt(activity, R.string.new_task, "taskmgr.exe", (command) -> activity.getWinHandler().exec(command));
        });

        setOnDismissListener((dialog) -> {
            if (timer != null) {
                timer.cancel();
                timer = null;
            }

            activity.getWinHandler().setOnGetProcessInfoListener(null);
        });

        FileUtils.clear(getIconDir(activity));
        inflater = LayoutInflater.from(getContext());
    }

    private void update() {
        synchronized (lock) {
            activity.getWinHandler().listProcesses();

            final LinearLayout container = findViewById(R.id.LLProcessList);
            if (container.getChildCount() == 0) findViewById(R.id.TVEmptyText).setVisibility(View.VISIBLE);
        }

        updateCPUInfoView();
        updateMemoryInfoView();
    }

    private void showListItemMenu(final View anchorView, final ProcessInfo processInfo) {
        PopupMenu listItemMenu = new PopupMenu(getContext(), anchorView);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) listItemMenu.setForceShowIcon(true);

        listItemMenu.inflate(R.menu.process_popup_menu);
        listItemMenu.setOnMenuItemClickListener((menuItem) -> {
            int itemId = menuItem.getItemId();
            final WinHandler winHandler = activity.getWinHandler();
            if (itemId == R.id.process_affinity) {
                showProcessorAffinityDialog(processInfo);
            }
            else if (itemId == R.id.bring_to_front) {
                winHandler.bringToFront(processInfo.name);
                dismiss();
            }
            else if (itemId == R.id.process_end) {
                ContentDialog.confirm(activity, R.string.do_you_want_to_end_this_process, () -> {
                    winHandler.killProcess(processInfo.name);
                });
            }
            return true;
        });
        listItemMenu.show();
    }

    private void showProcessorAffinityDialog(final ProcessInfo processInfo) {
        ContentDialog dialog = new ContentDialog(activity, R.layout.cpu_list_dialog);
        dialog.setTitle(processInfo.name);
        dialog.setIcon(R.drawable.icon_cpu);
        final CPUListView cpuListView = dialog.findViewById(R.id.CPUListView);
        cpuListView.setCheckedCPUList(processInfo.getCPUList());
        dialog.setOnConfirmCallback(() -> {
            WinHandler winHandler = activity.getWinHandler();
            winHandler.setProcessAffinity(processInfo.pid, ProcessHelper.getAffinityMask(cpuListView.getCheckedCPUList()));
            update();
        });
        dialog.show();
    }

    public static File getIconDir(Context context) {
        File iconDir = new File(ImageFs.find(context).getRootDir(), "home/xuser/.local/share/icons/taskmgr");
        if (!iconDir.isDirectory()) iconDir.mkdirs();
        return iconDir;
    }

    @Override
    public void show() {
        update();
        activity.getWinHandler().setOnGetProcessInfoListener(this);

        timer = new Timer();
        timer.schedule(new TimerTask() {
            @Override
            public void run() {
                activity.runOnUiThread(TaskManagerDialog.this::update);
            }
        }, 0, 1000);
        super.show();
    }

    @Override
    public void onGetProcessInfo(int index, int numProcesses, ProcessInfo processInfo) {
        activity.runOnUiThread(() -> {
            synchronized (lock) {
                final LinearLayout container = findViewById(R.id.LLProcessList);
                setBottomBarText(activity.getString(R.string.processes)+": " + numProcesses);

                if (numProcesses == 0) {
                    container.removeAllViews();
                    findViewById(R.id.TVEmptyText).setVisibility(View.VISIBLE);
                    return;
                }

                findViewById(R.id.TVEmptyText).setVisibility(View.GONE);

                int childCount = container.getChildCount();
                View itemView = index < childCount ? container.getChildAt(index) : inflater.inflate(R.layout.process_info_list_item, container, false);
                ((TextView)itemView.findViewById(R.id.TVName)).setText(processInfo.name+(processInfo.wow64Process ? " *32" : ""));
                ((TextView)itemView.findViewById(R.id.TVPID)).setText(String.valueOf(processInfo.pid));
                ((TextView)itemView.findViewById(R.id.TVMemoryUsage)).setText(processInfo.getFormattedMemoryUsage());
                itemView.findViewById(R.id.BTMenu).setOnClickListener((v) -> showListItemMenu(v, processInfo));

                XServer xServer = activity.getXServer();
                Window window;

                try (XLock xlock = xServer.lock(XServer.Lockable.WINDOW_MANAGER)) {
                    window = xServer.windowManager.findWindowWithProcessId(processInfo.pid);
                }

                ImageView ivIcon = itemView.findViewById(R.id.IVIcon);
                ivIcon.setImageResource(R.drawable.taskmgr_process);
                if (window != null) {
                    Bitmap icon = xServer.pixmapManager.getWindowIcon(window);
                    if (icon != null) ivIcon.setImageBitmap(icon);
                }

                if (index >= childCount) container.addView(itemView);

                if (index == numProcesses-1 && childCount > numProcesses) {
                    for (int i = childCount-1; i >= numProcesses; i--) container.removeViewAt(i);
                }
            }
        });
    }

    private void updateCPUInfoView() {
        short[] clockSpeeds = CPUStatus.getCurrentClockSpeeds();
        int totalClockSpeed = 0;
        int totalMaxClockSpeed = 0;
        final LinearLayout cpuInfo = findViewById(R.id.LLCPUInfo);

        while (cpuInfo.getChildCount() < clockSpeeds.length) {
            TextView coreView = new TextView(getContext());
            coreView.setTextColor(resolveThemeColor(R.attr.winxOnSurfaceColor));
            coreView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            coreView.setSingleLine(true);
            coreView.setPadding(0, 1, 0, 1);
            cpuInfo.addView(coreView);
        }
        while (cpuInfo.getChildCount() > clockSpeeds.length) {
            cpuInfo.removeViewAt(cpuInfo.getChildCount() - 1);
        }

        for (int i = 0; i < clockSpeeds.length; i++) {
            int current = Math.max(0, clockSpeeds[i]);
            int maximum = Math.max(0, CPUStatus.getMaxClockSpeed(i));
            totalClockSpeed += current;
            totalMaxClockSpeed += maximum;
            ((TextView)cpuInfo.getChildAt(i)).setText("Core " + (i + 1) + "   "
                    + current + " / " + maximum + " MHz");
        }

        int cpuCount = clockSpeeds.length;
        int avgClockSpeed = cpuCount > 0 ? totalClockSpeed / cpuCount : 0;
        int avgMaxClockSpeed = cpuCount > 0 ? totalMaxClockSpeed / cpuCount : 0;
        int cpuUsagePercent = avgMaxClockSpeed > 0
                ? Math.min(100, Math.max(0, Math.round((avgClockSpeed * 100.0f) / avgMaxClockSpeed)))
                : 0;

        TextView tvCPUTitle = findViewById(R.id.TVCPUTitle);
        tvCPUTitle.setText("CPU ("+cpuUsagePercent+"%)");

    }

    private void updateMemoryInfoView() {
        ActivityManager activityManager = (ActivityManager)activity.getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo memoryInfo = new ActivityManager.MemoryInfo();
        activityManager.getMemoryInfo(memoryInfo);
        long usedMem = memoryInfo.totalMem - memoryInfo.availMem;
        int memUsagePercent = memoryInfo.totalMem > 0
                ? Math.min(100, Math.max(0, (int)Math.round(((double)usedMem / memoryInfo.totalMem) * 100.0d)))
                : 0;

        MemoryUsageBarView memoryBar = findViewById(R.id.MemoryUsageBar);
        memoryBar.setMemoryUsage(memUsagePercent, usedMem, memoryInfo.totalMem);
    }

    private int resolveThemeColor(int attr) {
        TypedValue value = new TypedValue();
        getContext().getTheme().resolveAttribute(attr, value, true);
        return value.resourceId != 0 ? getContext().getColor(value.resourceId) : value.data;
    }
}
