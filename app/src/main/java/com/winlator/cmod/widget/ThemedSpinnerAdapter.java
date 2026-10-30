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
        // getItem() stays invariant: runtime configuration code uses these values.
        textView.setText(item == null ? "" : displayLabel(item.toString()));
        textView.setTextSize(textSizeSp);
        boolean dark = com.winlator.cmod.core.AppUtils.isDarkMode(getContext());
        textView.setTextColor(ContextCompat.getColor(getContext(),
                dark ? android.R.color.white : android.R.color.black));
    }

    private String displayLabel(String value) {
        int label;
        switch (value) {
            case "Custom": label = R.string.custom; break;
            case "System": label = R.string.system; break;
            case "None": label = R.string.none; break;
            case "Enabled": label = R.string.enabled; break;
            case "Disabled": label = R.string.disabled; break;
            case "Fastest": label = R.string.fastest; break;
            case "Fast": label = R.string.fast; break;
            case "Slow": label = R.string.slow; break;
            case "Slowest": label = R.string.slowest; break;
            default: return value;
        }
        return getContext().getString(label);
    }
}
