package com.winlator.cmod.widget;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import com.winlator.cmod.contents.XclipseDriverManager;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Keep selection ids intact, and preserve the spelling/case of the package's name on screen. */
public final class DriverSpinnerAdapter extends ThemedSpinnerAdapter<String> {
    private final Map<String, String> labels = new HashMap<>();

    public DriverSpinnerAdapter(Context context, List<String> ids) {
        super(context, ids);
        XclipseDriverManager manager = new XclipseDriverManager(context);
        List<String> installed = manager.enumerateInstalledDrivers();
        for (String id : ids) labels.put(id, installed.contains(id) ? manager.getDisplayName(id)
                : "System".equals(id) ? context.getString(com.winlator.cmod.R.string.system) : id);
    }

    @Override public View getView(int position, View convertView, ViewGroup parent) {
        return label(super.getView(position, convertView, parent), position);
    }

    @Override public View getDropDownView(int position, View convertView, ViewGroup parent) {
        return label(super.getDropDownView(position, convertView, parent), position);
    }

    private View label(View view, int position) {
        if (view instanceof TextView) ((TextView) view).setText(labels.get(getItem(position)));
        return view;
    }
}
