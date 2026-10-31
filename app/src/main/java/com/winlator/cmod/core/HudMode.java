package com.winlator.cmod.core;

/** Persistent IDs, separate from visible labels and native panel appearance. */
public final class HudMode {
    private HudMode() {}
    public static final String[] IDS = {"winlator", "mangohud1", "mangohud", "dxvk",
            "winlator_dxvk", "mangohud1_dxvk", "mangohud2_dxvk"};
    public static String normalize(String mode) {
        if ("mangohud2".equals(mode)) return "mangohud";
        for (String id : IDS) if (id.equals(mode)) return id;
        return "winlator";
    }
    public static int index(String mode) {
        String id = normalize(mode);
        for (int i = 0; i < IDS.length; i++) if (IDS[i].equals(id)) return i;
        return 0;
    }
    public static String at(int index) { return IDS[Math.max(0, Math.min(IDS.length - 1, index))]; }
    public static boolean dxvk(String mode) {
        String id = normalize(mode);
        return id.equals("dxvk") || id.endsWith("_dxvk");
    }
    public static int nativeStyle(String mode) {
        String id = normalize(mode);
        if (id.equals("dxvk")) return -1;
        if (id.startsWith("mangohud1")) return 1;
        if (id.equals("mangohud") || id.startsWith("mangohud2")) return 2;
        return 0;
    }
    public static String withStyle(String mode, int style) {
        if (style < 0) return "dxvk";
        String id = style == 1 ? "mangohud1" : style == 2 ? "mangohud" : "winlator";
        if (!dxvk(mode)) return id;
        return style == 2 ? "mangohud2_dxvk" : id + "_dxvk";
    }
}
