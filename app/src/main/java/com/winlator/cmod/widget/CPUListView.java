package com.winlator.cmod.widget;

import android.content.Context;
import android.view.Gravity;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.Space;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.winlator.cmod.R;

import java.util.Arrays;
import java.util.List;

public class CPUListView extends LinearLayout {
    private List<String> checkedCPUList;
    private final byte numProcessors;

    public CPUListView(Context context) {
        this(context, null);
    }

    public CPUListView(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public CPUListView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        // Each generated group is a row. Keeping the parent horizontal placed
        // the 4-core/5-core groups beside one another and made the grid crooked.
        setOrientation(VERTICAL);
        setGravity(Gravity.CENTER_HORIZONTAL);
        numProcessors = (byte)Runtime.getRuntime().availableProcessors();
        refreshContent();
    }

    private void refreshContent() {
        removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(getContext());
        float density = getResources().getDisplayMetrics().density;

        /* Wrap cores into centered rows of at most 4-5 so every CPU stays
         * reachable on decacore devices instead of overflowing off-screen. */
        int perRow = numProcessors > 8 ? 5 : 4;
        int rowCount = (numProcessors + perRow - 1) / perRow;
        for (int row = 0; row < rowCount; row++) {
            LinearLayout rowLayout = new LinearLayout(getContext());
            rowLayout.setOrientation(HORIZONTAL);
            rowLayout.setGravity(Gravity.CENTER);
            LayoutParams layoutParams = new LayoutParams(
                    LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
            if (row > 0) layoutParams.topMargin = (int)(density * 6);
            rowLayout.setLayoutParams(layoutParams);

            int first = row * perRow;
            int last = Math.min((row + 1) * perRow, numProcessors);
            int missing = perRow - (last - first);
            if (missing > 0) addWeightedSpace(rowLayout, missing / 2f);

            for (int i = first; i < last; i++) {
                View itemView = inflater.inflate(R.layout.cpu_list_item, rowLayout, false);
                String tag = "CPU"+i;
                CheckBox checkBox = itemView.findViewById(R.id.CheckBox);
                checkBox.setTag(tag);
                checkBox.setChecked(checkedCPUList == null || checkedCPUList.contains(String.valueOf(i)));

                ((TextView)itemView.findViewById(R.id.TextView)).setText(tag);
                rowLayout.addView(itemView);
            }
            if (missing > 0) addWeightedSpace(rowLayout, missing - missing / 2f);
            addView(rowLayout);
        }
    }

    private void addWeightedSpace(LinearLayout row, float weight) {
        if (weight <= 0) return;
        Space space = new Space(getContext());
        space.setLayoutParams(new LinearLayout.LayoutParams(0, 1, weight));
        row.addView(space);
    }

    public void setCheckedCPUList(String checkedCPUList) {
        this.checkedCPUList = Arrays.asList(checkedCPUList.split(","));
        refreshContent();
    }

    public void setCheckedCPUList(int from, int to) {
        checkedCPUList.clear();
        for (int i = from; i < to; i++) checkedCPUList.add(String.valueOf(i));
        refreshContent();
    }

    public String getCheckedCPUListAsString() {
        String cpuList = "";

        for (int i = 0; i < numProcessors; i++) {
            CheckBox checkBox = findViewWithTag("CPU"+i);
            if (checkBox.isChecked()) cpuList += (!cpuList.isEmpty() ? "," : "")+i;
        }
        return cpuList;
    }

    public boolean[] getCheckedCPUList() {
        boolean[] cpuList = new boolean[numProcessors];
        for (int i = 0; i < numProcessors; i++) {
            CheckBox checkBox = findViewWithTag("CPU"+i);
            cpuList[i] = checkBox.isChecked();
        }
        return cpuList;
    }

    public byte getNumProcessors() {
        return numProcessors;
    }
}
