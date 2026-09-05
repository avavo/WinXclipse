package com.winlator.cmod.xenvironment.components;

import android.content.Context;
import android.os.Process;
import android.util.Log;

import com.winlator.cmod.core.AppUtils;
import com.winlator.cmod.core.FileUtils;
import com.winlator.cmod.core.ProcessHelper;
import com.winlator.cmod.xconnector.UnixSocketConfig;
import com.winlator.cmod.xenvironment.EnvironmentComponent;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;

public class PulseAudioComponent extends EnvironmentComponent {
    private final UnixSocketConfig socketConfig;
    private final int volumePercent;
    private static int pid = -1;
    private static final Object lock = new Object();

    static {
        System.loadLibrary("winlator");
    }

    public PulseAudioComponent(UnixSocketConfig socketConfig) {
        this(socketConfig, 100);
    }

    public PulseAudioComponent(UnixSocketConfig socketConfig, int volumePercent) {
        this.socketConfig = socketConfig;
        this.volumePercent = Math.max(0, Math.min(100, volumePercent));
    }

    @Override
    public void start() {
        synchronized (lock) {
            stop();
            unlinkStaleSocket();
            pid = execPulseAudio();
        }
    }

    /** Recreates the daemon so its AAudio sink binds to the current route.
     * AAudio (mmap) streams die on audio-route changes (BT/headset/USB,
     * recorder submix) and nothing recreates them otherwise. */
    public void restart() {
        synchronized (lock) {
            stop();
            unlinkStaleSocket();
            pid = execPulseAudio();
        }
    }

    private void unlinkStaleSocket() {
        // SIGKILL leaves the unix socket file behind; the next server would
        // fail to bind ("Address already in use") and stay silent forever.
        try {
            if (socketConfig != null && socketConfig.path != null) new File(socketConfig.path).delete();
        }
        catch (Exception ignored) {}
    }

    @Override
    public void stop() {
        synchronized (lock) {
            if (pid != -1) {
                Process.killProcess(pid);
                pid = -1;
            }
        }
    }

    /** Swaps only module-aaudio-sink for a fresh instance; the daemon (and
     * every Wine client connected to PULSE_SERVER) stays alive. Needed
     * because Android's audio policy force-disconnects the sink's AAudio/mmap
     * stream when a recorder attaches a remote submix or the output route
     * changes (AAUDIO_ERROR_DISCONNECTED, result -899), and the prebuilt
     * module never reopens its stream — without this reload the game stays
     * silent until the container restarts. */
    public boolean reloadAaudioSink() {
        synchronized (lock) {
            if (pid == -1 || socketConfig == null || socketConfig.path == null) return false;
            try {
                boolean reloaded = nativeReloadAaudioSink(socketConfig.path, volumePercent);
                if (reloaded) Log.i("PulseAudioComponent", "module-aaudio-sink reloaded");
                else Log.e("PulseAudioComponent", "module-aaudio-sink reload failed");
                return reloaded;
            }
            catch (UnsatisfiedLinkError e) {
                Log.e("PulseAudioComponent", "PulseAudio reload bridge unavailable", e);
                return false;
            }
        }
    }

    private static native boolean nativeReloadAaudioSink(String serverPath, int volumePercent);
    
    private void copyFromLibraryDir(File dst) {
        String[] libs = new String[] {
            "libltdl.so", "libpulseaudio.so", "libpulse.so", "libpulsecommon-13.0.so", "libpulsecore-13.0.so", "libsndfile.so"
        };
        for (int i = 0; i < libs.length; i++) {
            String path = "lib/" + "arm64-v8a" + "/" + libs[i];
            ClassLoader loader = PulseAudioComponent.class.getClassLoader();
            URL res = loader != null ? loader.getResource(path) : null;
            Path dstDir = Paths.get(dst.getAbsolutePath() + "/" + libs[i]);
            try {
                InputStream is = res != null ? res.openStream() : null;
                if (is != null) {
                    Files.copy(is, dstDir, StandardCopyOption.REPLACE_EXISTING);
                    FileUtils.chmod(dstDir.toFile(), 0771);
                }
            }
            catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }

    private int execPulseAudio() {
        Context context = environment.getContext();
        File workingDir = new File(context.getFilesDir(), "/pulseaudio");
        if (!workingDir.isDirectory()) {
            workingDir.mkdirs();
            FileUtils.chmod(workingDir, 0771);
        }

        File configFile = new File(workingDir, "default.pa");
        int pulseVolume = Math.round(65536f * volumePercent / 100f);
        FileUtils.writeString(configFile, String.join("\n",
            "load-module module-native-protocol-unix auth-anonymous=1 auth-cookie-enabled=0 socket=\""+socketConfig.path+"\"",
            "load-module module-aaudio-sink",
            "set-default-sink AAudioSink",
            "set-sink-volume AAudioSink " + pulseVolume,
            "set-sink-mute AAudioSink no"
        ));

        String archName = AppUtils.getArchName();
        File modulesDir = new File(workingDir, "modules/"+archName);
        String systemLibPath = archName.equals("arm64") ? "/system/lib64" : "system/lib";

        ArrayList<String> envVars = new ArrayList<>();
        envVars.add("LD_LIBRARY_PATH="+systemLibPath+":"+modulesDir+":"+workingDir.getAbsolutePath());
        envVars.add("HOME="+workingDir);
        envVars.add("TMPDIR="+environment.getTmpDir());
        
        copyFromLibraryDir(workingDir);

        String command = workingDir.getAbsolutePath() + "/libpulseaudio.so";
        command += " --system=false";
        command += " --disable-shm=true";
        command += " --fail=false";
        command += " -n --file=default.pa";
        command += " --daemonize=false";
        command += " --use-pid-file=false";
        command += " --exit-idle-time=-1";

        return ProcessHelper.exec(command, envVars.toArray(new String[0]), workingDir);
    }
}
