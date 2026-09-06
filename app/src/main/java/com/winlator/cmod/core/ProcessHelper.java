package com.winlator.cmod.core;

import android.os.Process;
import android.util.Log;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FilenameFilter;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public abstract class ProcessHelper {
    public static final boolean PRINT_DEBUG = false;
    private static final int MAX_RECENT_DEBUG_LINES = 2000;
    private static final CopyOnWriteArrayList<Callback<String>> debugCallbacks = new CopyOnWriteArrayList<>();
    private static final ArrayDeque<String> recentDebugLines = new ArrayDeque<>();
    private static final Object recentDebugLock = new Object();
    private static final Object diagnosticLogLock = new Object();
    private static BufferedWriter dxvkDiagnosticWriter;
    private static BufferedWriter vkd3dDiagnosticWriter;
    private static final ExecutorService debugExecutor = Executors.newCachedThreadPool();
    private static final byte SIGCONT = 18;
    private static final byte SIGSTOP = 19;
    private static final byte SIGTERM = 15;
    private static final byte SIGKILL = 9;

    public static void suspendProcess(int pid) {
        Process.sendSignal(pid, SIGSTOP);
//        Log.d("ProcessHelper", "Process suspended with pid: " + pid);
    }

    public static void resumeProcess(int pid) {
        Process.sendSignal(pid, SIGCONT);
//        Log.d("ProcessHelper", "Process resumed with pid: " + pid);
    }

    public static void terminateProcess(int pid) {
        Process.sendSignal(pid, SIGTERM);
//        Log.d("ProcessHelper", "Process terminated with pid: " + pid);
    }

    public static void killProcess(int pid) {
        Process.sendSignal(pid, SIGKILL);
//        Log.d("ProcessHelper", "Process killed with pid: " + pid);
    }

    public static void terminateAllWineProcesses() {
        for (String process : listRunningWineProcesses()) {
            terminateProcess(Integer.parseInt(process));
        }
    }

    public static void pauseAllWineProcesses() {
        for (String process : listRunningWineProcesses()) {
            suspendProcess(Integer.parseInt(process));
        }
    }

    public static void resumeAllWineProcesses() {
        for (String process : listRunningWineProcesses()) {
            resumeProcess(Integer.parseInt(process));
        }
    }

    public static int exec(String command) {
        return exec(command, null);
    }

    public static int exec(String command, String[] envp) {
        return exec(command, envp, null);
    }

    public static int exec(String command, String[] envp, File workingDir) {
        return exec(command, envp, workingDir, null);
    }

    public static int exec(String command, String[] envp, File workingDir, Callback<Integer> terminationCallback) {
        Log.d("ProcessHelper", "env: " + Arrays.toString(envp) + "\ncmd: " + command);

        // Store env vars for future use
        EnvironmentManager.setEnvVars(envp);

        int pid = -1;
        try {
            Log.d("ProcessHelper", "Splitting command: " + command);
            String[] splitCommand = splitCommand(command);
            Log.d("ProcessHelper", "Split command result: " + Arrays.toString(splitCommand));
            Log.d("ProcessHelper", "Starting process...");
            ProcessBuilder pb = new ProcessBuilder(splitCommand);
            pb.directory(workingDir);
            pb.environment().putAll(EnvironmentManager.getEnvVars());
            java.lang.Process process = pb.start();

            // Accessing hidden field
            Log.d("ProcessHelper", "Accessing hidden field to get PID");
            Field pidField = process.getClass().getDeclaredField("pid");
            pidField.setAccessible(true);
            pid = pidField.getInt(process);
            pidField.setAccessible(false);
            Log.d("ProcessHelper", "Process started with pid: " + pid);

            createDebugThread(process.getInputStream(), "stdout", pid);
            createDebugThread(process.getErrorStream(), "stderr", pid);

            final int processPid = pid;
            debugExecutor.execute(() -> {
                try {
                    int exitCode = process.waitFor();
                    Log.w("WineProc", "[pid=" + processPid + "][exit] code=" + exitCode);
                    emitDebugLine("[pid=" + processPid + "][exit] code=" + exitCode);
                    if (terminationCallback != null) terminationCallback.call(exitCode);
                }
                catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    emitDebugLine("[pid=" + processPid + "][exit] wait interrupted");
                }
            });

        }
        catch (Exception e) {
            Log.e("ProcessHelper", "Error executing command: " + command, e);
        }
        return pid;
    }

    private static void createDebugThread(final InputStream inputStream, String streamName, int pid) {
        debugExecutor.execute(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    // Always mirror guest output to logcat: without this the guest's
                    // dying words are invisible unless the wine-debug setting is on.
                    Log.i("WineProc", "[pid=" + pid + "][" + streamName + "] " + line);
                    if (PRINT_DEBUG) System.out.println(line);
                    emitDebugLine("[pid=" + pid + "][" + streamName + "] " + line);
                }
            }
            catch (IOException e) {
                Log.e("ProcessHelper", "Error in debug thread", e);
            }
        });
    }

    private static void emitDebugLine(String line) {
        synchronized (recentDebugLock) {
            recentDebugLines.addLast(line);
            while (recentDebugLines.size() > MAX_RECENT_DEBUG_LINES) recentDebugLines.removeFirst();
        }
        appendDiagnosticLine(line);
        for (Callback<String> callback : debugCallbacks) {
            try {
                callback.call(line);
            }
            catch (RuntimeException e) {
                Log.e("ProcessHelper", "Debug callback failed", e);
            }
        }
    }

    public static List<String> getRecentDebugLines() {
        synchronized (recentDebugLock) {
            return new ArrayList<>(recentDebugLines);
        }
    }

    /** Starts the per-container DXVK/VKD3D diagnostic side logs for this session. */
    public static void startDiagnosticLogs(File directory, String sessionStamp) {
        stopDiagnosticLogs();
        if (directory == null || sessionStamp == null || sessionStamp.isEmpty()) return;
        if (!directory.isDirectory() && !directory.mkdirs()) {
            Log.w("ProcessHelper", "Could not create diagnostic log directory: " + directory);
            return;
        }
        synchronized (diagnosticLogLock) {
            try {
                dxvkDiagnosticWriter = new BufferedWriter(new FileWriter(
                        new File(directory, "dxvk_" + sessionStamp + ".log"), false));
                vkd3dDiagnosticWriter = new BufferedWriter(new FileWriter(
                        new File(directory, "vkd3d_" + sessionStamp + ".log"), false));
                String header = "WinXclipse graphics diagnostic\nSession: " + sessionStamp + "\n\n";
                dxvkDiagnosticWriter.write(header);
                vkd3dDiagnosticWriter.write(header);
                dxvkDiagnosticWriter.flush();
                vkd3dDiagnosticWriter.flush();
            }
            catch (IOException e) {
                Log.e("ProcessHelper", "Could not open graphics diagnostic logs", e);
                closeDiagnosticWritersLocked();
            }
        }
    }

    /** Flushes and closes the per-container DXVK/VKD3D diagnostic side logs. */
    public static void stopDiagnosticLogs() {
        synchronized (diagnosticLogLock) {
            closeDiagnosticWritersLocked();
        }
    }

    private static void appendDiagnosticLine(String line) {
        if (line == null) return;
        String lower = line.toLowerCase(Locale.US);
        boolean vkd3d = lower.contains("vkd3d") || lower.contains("d3d12")
                || lower.contains("shader model");
        boolean dxvk = lower.contains("dxvk") || lower.contains("d3d11")
                || lower.contains("d3d10") || lower.contains("dxgi")
                || lower.contains("vulkan") || lower.contains("pipeline")
                || lower.contains("chunk");
        synchronized (diagnosticLogLock) {
            try {
                if (vkd3d && vkd3dDiagnosticWriter != null) {
                    vkd3dDiagnosticWriter.write(line);
                    vkd3dDiagnosticWriter.newLine();
                    vkd3dDiagnosticWriter.flush();
                }
                if (dxvk && dxvkDiagnosticWriter != null) {
                    dxvkDiagnosticWriter.write(line);
                    dxvkDiagnosticWriter.newLine();
                    dxvkDiagnosticWriter.flush();
                }
            }
            catch (IOException e) {
                Log.w("ProcessHelper", "Graphics diagnostic log write failed", e);
                closeDiagnosticWritersLocked();
            }
        }
    }

    private static void closeDiagnosticWritersLocked() {
        if (dxvkDiagnosticWriter != null) {
            try { dxvkDiagnosticWriter.close(); } catch (IOException ignored) {}
            dxvkDiagnosticWriter = null;
        }
        if (vkd3dDiagnosticWriter != null) {
            try { vkd3dDiagnosticWriter.close(); } catch (IOException ignored) {}
            vkd3dDiagnosticWriter = null;
        }
    }

    public static void removeAllDebugCallbacks() {
        debugCallbacks.clear();
        Log.d("ProcessHelper", "All debug callbacks removed");
    }

    public static void addDebugCallback(Callback<String> callback) {
        if (callback != null) debugCallbacks.addIfAbsent(callback);
        Log.d("ProcessHelper", "Added debug callback: " + callback);
    }

    public static void removeDebugCallback(Callback<String> callback) {
        debugCallbacks.remove(callback);
        Log.d("ProcessHelper", "Removed debug callback: " + callback);
    }

    public static String[] splitCommand(String command) {
        ArrayList<String> result = new ArrayList<>();
        boolean startedQuotes = false;
        String value = "";
        char currChar, nextChar;
        for (int i = 0, count = command.length(); i < count; i++) {
            currChar = command.charAt(i);

            if (startedQuotes) {
                if (currChar == '"') {
                    startedQuotes = false;
                    if (!value.isEmpty()) {
                        value += '"';
                        result.add(value);
                        value = "";
                    }
                }
                else value += currChar;
            }
            else if (currChar == '"') {
                startedQuotes = true;
                value += '"';
            }
            else {
                nextChar = i < count-1 ? command.charAt(i+1) : '\0';
                if (currChar == ' ' || (currChar == '\\' && nextChar == ' ')) {
                    if (currChar == '\\') {
                        value += ' ';
                        i++;
                    }
                    else if (!value.isEmpty()) {
                        result.add(value);
                        value = "";
                    }
                }
                else {
                    value += currChar;
                    if (i == count-1) {
                        result.add(value);
                        value = "";
                    }
                }
            }
        }

        return result.toArray(new String[0]);
    }

    public static String getAffinityMaskAsHexString(String cpuList) {
        String[] values = cpuList.split(",");
        int affinityMask = 0;
        for (String value : values) {
            byte index = Byte.parseByte(value);
            affinityMask |= (int)Math.pow(2, index);
        }
        return Integer.toHexString(affinityMask);
    }

    public static int getAffinityMask(String cpuList) {
        if (cpuList == null || cpuList.isEmpty()) return 0;
        String[] values = cpuList.split(",");
        int affinityMask = 0;
        for (String value : values) {
            byte index = Byte.parseByte(value);
            affinityMask |= (int)Math.pow(2, index);
        }
        return affinityMask;
    }

    public static int getAffinityMask(boolean[] cpuList) {
        int affinityMask = 0;
        for (int i = 0; i < cpuList.length; i++) {
            if (cpuList[i]) affinityMask |= (int)Math.pow(2, i);
        }
        return affinityMask;
    }

    public static int getAffinityMask(int from, int to) {
        int affinityMask = 0;
        for (int i = from; i < to; i++) affinityMask |= (int)Math.pow(2, i);
        return affinityMask;
    }

    public static ArrayList<String> listRunningWineProcesses(){
        File proc = new File("/proc");
        String[] filters = {"wine", "exe"};
        String[] allPids;
        ArrayList<String> filteredPids = new ArrayList<String>();
        List<String> filterList = Arrays.asList(filters);
        allPids = proc.list(new FilenameFilter(){
            public boolean accept(File proc, String filename){
                return new File(proc, filename).isDirectory() && filename.matches("[0-9]+");
            }
        });

        if (allPids == null) return filteredPids;
        for (String currentPid : allPids) {
            String data = "";
            try (FileInputStream fr = new FileInputStream(proc + "/" + currentPid + "/stat");
                 BufferedReader br = new BufferedReader(new InputStreamReader(fr))) {
                String line = br.readLine();
                if (line != null) data = line;
            }
            catch (IOException ignored) {}
            for (String filter : filterList) {
                if (data.contains(filter)) {
                    filteredPids.add(currentPid);
                    break;
                }
            }
        }
        return filteredPids;
    }

    /**
     * Returns the guest process names visible in /proc. Wine sets the Linux
     * comm field to the Windows image name (for example "re3.exe"), which is
     * considerably more reliable than parsing the command line through a
     * translator. Malformed or already-exited entries are simply skipped.
     */
    public static ArrayList<String> listRunningWineProcessNames() {
        ArrayList<String> names = new ArrayList<>();
        for (String pid : listRunningWineProcesses()) {
            String name = readProcessName(pid);
            if (!name.isEmpty()) names.add(name);
        }
        return names;
    }

    /** Snapshot used by lifecycle diagnostics. Each entry is formatted as pid:name. */
    public static ArrayList<String> listRunningWineProcessDetails() {
        ArrayList<String> details = new ArrayList<>();
        for (String pid : listRunningWineProcesses()) {
            String name = readProcessName(pid);
            if (!name.isEmpty()) details.add(pid + ":" + name);
        }
        return details;
    }

    /** Terminates matching Wine guest images owned by this app process. */
    public static int terminateWineProcessesByName(String processName) {
        if (processName == null || processName.trim().isEmpty()) return 0;
        int terminated = 0;
        String target = processName.trim();
        for (String pid : listRunningWineProcesses()) {
            if (!target.equalsIgnoreCase(readProcessName(pid))) continue;
            try {
                terminateProcess(Integer.parseInt(pid));
                terminated++;
            }
            catch (NumberFormatException ignored) {}
        }
        return terminated;
    }

    private static String readProcessName(String pid) {
        File stat = new File("/proc/" + pid + "/stat");
        try (FileInputStream input = new FileInputStream(stat);
             BufferedReader reader = new BufferedReader(new InputStreamReader(input))) {
            String line = reader.readLine();
            if (line == null) return "";
            int open = line.indexOf('(');
            int close = line.lastIndexOf(')');
            return open >= 0 && close > open + 1
                    ? line.substring(open + 1, close).trim() : "";
        }
        catch (IOException ignored) {
            return "";
        }
    }
}
