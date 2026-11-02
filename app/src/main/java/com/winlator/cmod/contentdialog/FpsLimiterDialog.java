package com.winlator.cmod.contentdialog;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import com.winlator.cmod.R;
import java.util.function.IntConsumer;

/** The preset list and custom input share the sidebar's ordinary editor page. */
public final class FpsLimiterDialog extends ContentDialog {
    private static final int[] PRESETS={24,30,45,60,75,90,120};
    private final RadioGroup choices;
    private final EditText custom;
    private final IntConsumer onSelected;

    public FpsLimiterDialog(Context context,int configured,IntConsumer onSelected) {
        super(context,R.layout.sidebar_fps_limiter_dialog);
        this.onSelected=onSelected;
        setTitle(R.string.fps_limit);
        setIcon(R.drawable.icon_settings);
        setSidebarLiveUpdates(true);
        choices=findViewById(R.id.RGFpsLimit);
        custom=findViewById(R.id.ETFpsLimit);
        configured=Math.max(15,Math.min(240,configured));
        custom.setText(String.valueOf(configured));
        int checked=View.NO_ID;
        for(int fps:PRESETS) {
            RadioButton button=choice(fps+" FPS",fps);
            if(fps==configured)checked=button.getId();
        }
        RadioButton other=choice(context.getString(R.string.custom)+"…",0);
        if(checked==View.NO_ID)checked=other.getId();
        choices.check(checked);
        updateCustomVisibility();
        choices.setOnCheckedChangeListener((group,id)->updateCustomVisibility());
        setOnConfirmCallback(this::applySelection);
        // A standalone use retains OK/Cancel; invalid input must not close it.
        findViewById(R.id.BTConfirm).setOnClickListener(v->{if(applySelection())dismiss();});
    }
    private RadioButton choice(String label,int value) {
        RadioButton button=new RadioButton(getContext());
        button.setId(View.generateViewId());
        button.setTag(value);
        button.setText(label);
        button.setTextSize(16);
        button.setMinHeight(dp(48));
        button.setPadding(dp(8),dp(8),dp(8),dp(8));
        choices.addView(button,new RadioGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT));
        return button;
    }
    private int dp(int value){return Math.round(value*getContext().getResources().getDisplayMetrics().density);}
    private int selectedPreset() {
        View selected=choices.findViewById(choices.getCheckedRadioButtonId());
        return selected==null?0:(Integer)selected.getTag();
    }
    private void updateCustomVisibility() {
        custom.setVisibility(selectedPreset()==0?View.VISIBLE:View.GONE);
        custom.setError(null);
    }
    private boolean applySelection() {
        int value=selectedPreset();
        if(value==0) {
            try {value=Integer.parseInt(custom.getText().toString().trim());}
            catch(NumberFormatException invalid){custom.setError("15–240 FPS");return false;}
            if(value<15||value>240){custom.setError("15–240 FPS");return false;}
        }
        custom.setError(null);
        onSelected.accept(value);
        return true;
    }
}
