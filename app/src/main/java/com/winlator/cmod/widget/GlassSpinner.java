package com.winlator.cmod.widget;

import android.content.Context;
import android.content.res.TypedArray;
import android.database.DataSetObserver;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ListAdapter;
import android.widget.SpinnerAdapter;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.AppCompatSpinner;
import androidx.appcompat.widget.ListPopupWindow;

import com.winlator.cmod.core.PopupStyle;

/** Preserves adapter objects/labels and selection callbacks while styling both popup modes. */
public class GlassSpinner extends AppCompatSpinner {
    private final int popupMode;
    private ListPopupWindow anchored;
    private AlertDialog choices;

    public GlassSpinner(Context context) { this(context, null); }
    public GlassSpinner(Context context, AttributeSet attrs) { this(context, attrs, androidx.appcompat.R.attr.spinnerStyle); }
    public GlassSpinner(Context context, AttributeSet attrs, int style) {
        super(context, attrs, style);
        TypedArray values = context.obtainStyledAttributes(attrs, new int[]{android.R.attr.spinnerMode}, style, 0);
        popupMode = values.getInt(0, android.widget.Spinner.MODE_DROPDOWN);
        values.recycle();
    }

    @Override public boolean performClick() {
        if (!isEnabled() || getAdapter() == null || getAdapter().getCount() == 0) return false;
        DropDownAdapter adapter = new DropDownAdapter(getAdapter());
        if (popupMode == android.widget.Spinner.MODE_DIALOG) {
            if (choices != null && choices.isShowing()) return true;
            choices = new PopupStyle.Builder(getContext()).setTitle(getPrompt())
                    .setSingleChoiceItems(adapter, getSelectedItemPosition(), (dialog, which) -> {
                        setSelection(which);
                        dialog.dismiss();
                    }).create();
            choices.show();
        } else {
            if (anchored != null && anchored.isShowing()) return true;
            anchored = new ListPopupWindow(getContext());
            anchored.setAnchorView(this);
            anchored.setModal(true);
            anchored.setAdapter(adapter);
            int margin = Math.round(32 * getResources().getDisplayMetrics().density);
            int preferred = Math.max(getWidth(), Math.round(192 * getResources().getDisplayMetrics().density));
            anchored.setWidth(Math.max(1, Math.min(preferred, getResources().getDisplayMetrics().widthPixels - margin)));
            anchored.setOnItemClickListener((parent, view, position, id) -> {
                setSelection(position);
                anchored.dismiss();
            });
            anchored.show();
            anchored.setSelection(getSelectedItemPosition());
            PopupStyle.styleAnchoredList(anchored.getListView());
        }
        return true;
    }

    @Override protected void onDetachedFromWindow() {
        if (choices != null) { choices.dismiss(); choices = null; }
        if (anchored != null) { anchored.dismiss(); anchored = null; }
        super.onDetachedFromWindow();
    }

    private static final class DropDownAdapter extends BaseAdapter {
        private final SpinnerAdapter source;
        DropDownAdapter(SpinnerAdapter source) { this.source = source; }
        @Override public int getCount() { return source.getCount(); }
        @Override public Object getItem(int position) { return source.getItem(position); }
        @Override public long getItemId(int position) { return source.getItemId(position); }
        @Override public boolean hasStableIds() { return source.hasStableIds(); }
        @Override public int getItemViewType(int position) { return source.getItemViewType(position); }
        @Override public int getViewTypeCount() { return source.getViewTypeCount(); }
        @Override public boolean areAllItemsEnabled() { return !(source instanceof ListAdapter) || ((ListAdapter)source).areAllItemsEnabled(); }
        @Override public boolean isEnabled(int position) { return !(source instanceof ListAdapter) || ((ListAdapter)source).isEnabled(position); }
        @Override public View getView(int position, View convertView, ViewGroup parent) { return source.getDropDownView(position, convertView, parent); }
        @Override public void registerDataSetObserver(DataSetObserver observer) { source.registerDataSetObserver(observer); }
        @Override public void unregisterDataSetObserver(DataSetObserver observer) { source.unregisterDataSetObserver(observer); }
    }
}
