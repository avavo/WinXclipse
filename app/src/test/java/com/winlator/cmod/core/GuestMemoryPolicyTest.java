package com.winlator.cmod.core;

import java.io.File;
import java.nio.file.Files;
import org.junit.Test;
import static org.junit.Assert.*;

public class GuestMemoryPolicyTest {
    @Test public void policyReachesGuestPreservesInputPreloadsAndCanBeDisabled() throws Exception {
        File dir = Files.createTempDirectory("guest-memory-").toFile();
        File library = new File(dir, "libwinx_memory.so") {
            @Override public String getAbsolutePath() { return "/data/app/test/lib/arm64/libwinx_memory.so"; }
        };
        Files.write(library.toPath(), new byte[]{1});
        try {
            EnvVars env = new EnvVars();
            env.put("LD_PRELOAD", "/usr/lib/libfakeinput.so:/usr/lib/libevshim.so");
            env.put(GuestMemoryPolicy.KEY, "1");
            GuestMemoryPolicy.apply(env, library);
            GuestMemoryPolicy.apply(env, library);
            assertEquals("/usr/lib/libfakeinput.so:/usr/lib/libevshim.so:" + library.getAbsolutePath(), env.get("LD_PRELOAD"));
            env.put(GuestMemoryPolicy.KEY, "0");
            GuestMemoryPolicy.apply(env, library);
            assertEquals("/usr/lib/libfakeinput.so:/usr/lib/libevshim.so", env.get("LD_PRELOAD"));
        } finally { library.delete(); dir.delete(); }
    }
    @Test public void missingLibraryDoesNotBreakLaunch() {
        EnvVars env = new EnvVars();env.put(GuestMemoryPolicy.KEY, "1");
        GuestMemoryPolicy.apply(env, new File("missing/libwinx_memory.so"));
        assertEquals("", env.get("LD_PRELOAD"));
    }
}
