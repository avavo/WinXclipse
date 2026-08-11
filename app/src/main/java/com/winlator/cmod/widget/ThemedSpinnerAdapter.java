package com.winlator.cmod.widget;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.TextView;

import androidx.core.content.ContextCompat;
import androidx.preference.PreferenceManager;

import com.winlator.cmod.R;

import java.util.List;

/** Keeps the selected value and popup rows legible in both application themes. */
public class ThemedSpinnerAdapter<T> extends ArrayAdapter<T> {
    private final float textSizeSp;

    public ThemedSpinnerAdapter(Context context, T[] items) {
        this(context, items, 16f);
    }

    public ThemedSpinnerAdapter(Context context, List<T> items) {
        this(context, items, 16f);
    }

    public ThemedSpinnerAdapter(Context context, T[] items, float textSizeSp) {
        super(context, R.layout.spinner_item, items);
        this.textSizeSp = textSizeSp;
        setDropDownViewResource(R.layout.spinner_dropdown_item);
    }

    public ThemedSpinnerAdapter(Context context, List<T> items, float textSizeSp) {
        super(context, R.layout.spinner_item, items);
        this.textSizeSp = textSizeSp;
        setDropDownViewResource(R.layout.spinner_dropdown_item);
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        View view = super.getView(position, convertView, parent);
        styleText(view, position);
        return view;
    }

    @Override
    public View getDropDownView(int position, View convertView, ViewGroup parent) {
        View view = super.getDropDownView(position, convertView, parent);
        styleText(view, position);
        return view;
    }

    private void styleText(View view, int position) {
        if (!(view instanceof TextView)) return;
        TextView textView = (TextView) view;
        T item = getItem(position);
        textView.setText(item == null ? "" : item.toString());
        textView.setTextSize(textSizeSp);
        boolean dark = PreferenceManager.getDefaultSharedPreferences(getContext())
                .getBoolean("dark_mode", false);
        textView.setTextColor(ContextCompat.getColor(getContext(),
                dark ? android.R.color.white : android.R.color.black));
    }
}
