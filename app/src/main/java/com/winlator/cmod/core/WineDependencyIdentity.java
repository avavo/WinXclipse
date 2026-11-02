package com.winlator.cmod.core;
import java.io.File;
/** Only the runtime, actual ICD and API/layer selection invalidate dependency probes. */
public final class WineDependencyIdentity {
    private WineDependencyIdentity() {}
    public static String build(String runtime, String osBuild, EnvVars env) {
        StringBuilder result=new StringBuilder(runtime).append("\n").append(osBuild);
        for(String key:new String[]{"VK_ICD_FILENAMES","VK_DRIVER_FILES","ADRENOTOOLS_DRIVER_PATH",
                "ADRENOTOOLS_DRIVER_NAME","WRAPPER_VK_VERSION","WRAPPER_EXTENSION_BLACKLIST",
                "WRAPPER_DEVICE_NAME","VK_INSTANCE_LAYERS","VK_LAYER_PATH"})
            result.append("\n").append(key).append("=").append(env.get(key));
        String path=env.get("ADRENOTOOLS_DRIVER_PATH"),name=env.get("ADRENOTOOLS_DRIVER_NAME");
        if(!path.isEmpty()&&!name.isEmpty()) stamp(result,new File(path,name));
        for(String key:new String[]{"VK_ICD_FILENAMES","VK_DRIVER_FILES"})
            for(String file:env.get(key).split(":")) if(!file.isEmpty())stamp(result,new File(file));
        return result.toString();
    }
    private static void stamp(StringBuilder result,File file) {
        result.append("\nfile=").append(file.getAbsolutePath()).append(":").append(file.length()).append(":").append(file.lastModified());
    }
}
