package com.winlator.cmod.renderer;

import android.widget.Spinner;
import com.winlator.cmod.widget.ThemedSpinnerAdapter;
import java.util.Arrays;
import java.util.Locale;

/** Separate from Wine's Direct3D renderer and from the selected Vulkan driver. */
public final class DisplayBackend {
    public static final String KEY="displayRenderer", VULKAN="vulkan", OPENGL="gl";
    private DisplayBackend(){}
    public static String normalize(String value) {
        String name=value==null?"":value.trim().toLowerCase(Locale.ROOT);
        return name.equals("gl")||name.equals("opengl")||name.equals("gles")?OPENGL:VULKAN;
    }
    public static void bind(Spinner spinner,String value) {
        spinner.setAdapter(new ThemedSpinnerAdapter<>(spinner.getContext(),Arrays.asList("Vulkan","OpenGL")));
        spinner.setSelection(OPENGL.equals(normalize(value))?1:0);
    }
    public static String selected(Spinner spinner) {return spinner.getSelectedItemPosition()==1?OPENGL:VULKAN;}
}
