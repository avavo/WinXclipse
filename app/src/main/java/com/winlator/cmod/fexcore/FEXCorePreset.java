package com.winlator.cmod.fexcore;

public class FEXCorePreset {
    public static final String STABILITY = "stability";
    public static final String COMPATIBILITY = "compatibility";
    public static final String INTERMEDIATE = "intermediate";
    public static final String PERFORMANCE = "performance";

    public final String id;
    public final String name;

    public FEXCorePreset(String id, String name) {
        this.id = id;
        this.name = name;
    }

    @Override
    public String toString() {
        return name;
    }
}
