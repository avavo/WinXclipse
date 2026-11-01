package com.winlator.cmod.core;

import android.text.Editable;
import android.text.TextWatcher;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.SeekBar;
import android.widget.Spinner;
import com.winlator.cmod.widget.GlassSpinner;

/** Observes user edits without replacing the controls' existing change handlers. */
public final class LiveSettingsBinder implements AutoCloseable {
    private final View root;
    private final Runnable apply;
    private String saved;
    private boolean closed, applying;
    private final Runnable pending = this::flush;

    public LiveSettingsBinder(View root, Runnable apply) {
        this.root = root; this.apply = apply; saved = state(root);
        bind(root);
    }
    private void changed() {
        if (closed || applying) return;
        root.removeCallbacks(pending);
        root.postDelayed(pending, 80);
    }
    private void bind(View view) {
        if (view instanceof CompoundButton) view.setOnClickListener(ignored -> changed());
        else if (view instanceof GlassSpinner) ((GlassSpinner)view).setSelectionObserver(this::changed);
        else if (view instanceof EditText) ((EditText)view).addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s,int start,int count,int after) {}
            public void onTextChanged(CharSequence s,int start,int before,int count) { changed(); }
            public void afterTextChanged(Editable text) {}
        });
        else if (view instanceof SeekBar) view.setOnTouchListener((ignored, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_MOVE || event.getActionMasked() == MotionEvent.ACTION_UP)
                root.post(this::changed);
            return false;
        });
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup)view;
            for (int i=0;i<group.getChildCount();i++) bind(group.getChildAt(i));
        }
    }
    private static String state(View view) {
        String value = "";
        if (view instanceof CompoundButton) value = Boolean.toString(((CompoundButton)view).isChecked());
        else if (view instanceof Spinner) value = Integer.toString(((Spinner)view).getSelectedItemPosition());
        else if (view instanceof EditText) value = ((EditText)view).getText().toString();
        else if (view instanceof SeekBar) value = Integer.toString(((SeekBar)view).getProgress());
        StringBuilder result = new StringBuilder().append(value.length()).append(':').append(value);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup)view;
            for (int i=0;i<group.getChildCount();i++) result.append('[').append(state(group.getChildAt(i))).append(']');
        }
        return result.toString();
    }
    public void flush() { flush(false); }
    public void commit() { flush(true); }
    private void flush(boolean force) {
        root.removeCallbacks(pending);
        if (closed || applying || !force && state(root).equals(saved)) return;
        applying = true;
        try { apply.run(); saved = state(root); }
        finally { applying = false; }
    }
    @Override public void close() { closed = true; root.removeCallbacks(pending); }
}
