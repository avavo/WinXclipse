package com.winlator.cmod.renderer;

import com.winlator.cmod.core.EnvVars;
import org.json.JSONObject;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/** Deploy only a manifest; the Android linker loads the packaged ARM64 layer. */
public final class DisplayXDeployment {
    private DisplayXDeployment(){}
    public static boolean install(File library,File imageRoot,EnvVars environment) throws IOException {
        if(!library.isFile()||library.length()==0)return false;
        File directory=new File(imageRoot,"usr/share/vulkan/explicit_layer.d");
        if(!directory.isDirectory()&&!directory.mkdirs())throw new IOException("Cannot create DisplayX layer directory");
        File temporary=File.createTempFile("displayx-",".json.part",directory);
        try {
            JSONObject layer=new JSONObject();layer.put("name",DisplayXSession.LAYER);layer.put("type","GLOBAL");
            layer.put("library_path",library.getAbsolutePath());layer.put("api_version","1.1.0");
            layer.put("implementation_version","1");layer.put("description","WinXclipse DisplayX AHardwareBuffer transport");
            JSONObject manifest=new JSONObject();manifest.put("file_format_version","1.2.0");manifest.put("layer",layer);
            Files.write(temporary.toPath(),manifest.toString(2).getBytes(StandardCharsets.UTF_8));
            Files.move(temporary.toPath(),new File(directory,"winxclipse_displayx.json").toPath(),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);
            return true;
        } catch(org.json.JSONException error){throw new IOException("DisplayX manifest failed",error);}
        finally {Files.deleteIfExists(temporary.toPath());}
    }
}
