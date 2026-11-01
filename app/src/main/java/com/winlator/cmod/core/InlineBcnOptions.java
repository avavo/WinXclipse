package com.winlator.cmod.core;
import android.view.View;
import android.widget.CheckBox;
import com.winlator.cmod.R;
import com.winlator.cmod.contentdialog.GraphicsDriverConfigDialog;
import java.util.HashMap;

public final class InlineBcnOptions {
    private InlineBcnOptions() {}
    public static void bind(View root,View anchor,CheckBox legacy) {
        CheckBox disabled=root.findViewById(R.id.CBDisableExperimentalBCN),optimized=root.findViewById(R.id.CBInlineBcnOptimized);
        HashMap<String,String> initial=GraphicsDriverConfigDialog.parseGraphicsDriverConfig(String.valueOf(anchor.getTag()));
        boolean active="1".equals(initial.getOrDefault("experimentalBcn",legacy.isChecked()?"1":"0"));
        disabled.setChecked(!active);optimized.setChecked(active&&"1".equals(initial.get("bcnXclipseOptimized")));
        Runnable update=()->{
            boolean enabled=!disabled.isChecked();legacy.setChecked(enabled);
            if(!enabled)optimized.setChecked(false);
            root.findViewById(R.id.LLInlineBcnOptimized).setVisibility(enabled?View.VISIBLE:View.GONE);
            HashMap<String,String> options=GraphicsDriverConfigDialog.parseGraphicsDriverConfig(String.valueOf(anchor.getTag()));
            options.put("experimentalBcn",enabled?"1":"0");options.put("bcnXclipseOptimized",enabled&&optimized.isChecked()?"1":"0");
            anchor.setTag(GraphicsDriverConfigDialog.toGraphicsDriverConfig(options));
        };
        disabled.setOnCheckedChangeListener((button,value)->update.run());
        optimized.setOnCheckedChangeListener((button,value)->update.run());update.run();
        root.findViewById(R.id.BTInlineBcnOptimizedHelp).setOnClickListener(v->AppUtils.showHelpBox(root.getContext(),v,R.string.bcn_xclipse_optimized_description));
        root.findViewById(R.id.BTDisableExperimentalBCNHelp).setOnClickListener(v->AppUtils.showHelpBox(root.getContext(),v,R.string.experimental_bcn_description));
    }
}
