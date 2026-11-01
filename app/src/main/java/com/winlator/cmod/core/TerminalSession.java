package com.winlator.cmod.core;

import android.content.Context;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import com.winlator.cmod.R;
import com.winlator.cmod.contents.ContentsManager;
import com.winlator.cmod.xenvironment.ImageFs;
import com.winlator.cmod.xenvironment.XEnvironment;
import com.winlator.cmod.xenvironment.components.BionicProgramLauncherComponent;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;

/** Shared terminal controller for the standalone screen and in-session sidebar. */
public final class TerminalSession implements AutoCloseable {
    private final TextView output;
    private final EditText input;
    private final Button execute;
    private final Function<String, String> runner;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private volatile boolean closed;

    public TerminalSession(View root) { this(root, createRunner(root.getContext())); }

    TerminalSession(View root, Function<String, String> runner) {
        this.runner = runner;
        output = root.findViewById(R.id.outputTextView);
        input = root.findViewById(R.id.commandInput);
        execute = root.findViewById(R.id.executeButton);
        if (runner == null) {
            output.setText(R.string.terminal_imagefs_invalid);
            execute.setEnabled(false);
        } else execute.setOnClickListener(view -> executeCommand());
    }

    private static Function<String, String> createRunner(Context context) {
        ImageFs imageFs = ImageFs.find(context);
        if (!imageFs.isValid()) return null;
        XEnvironment environment = new XEnvironment(context, imageFs);
        BionicProgramLauncherComponent launcher = new BionicProgramLauncherComponent(new ContentsManager(context), null, null);
        environment.addComponent(launcher);
        File[] binaries = new File(imageFs.getRootDir(), "usr/bin").listFiles();
        if (binaries != null) for (File binary : binaries) FileUtils.chmod(binary, 0755);
        return launcher::execShellCommand;
    }

    private void executeCommand() {
        if (closed || runner == null) return;
        String command = input.getText().toString().trim();
        if (command.isEmpty()) return;
        input.setText("");
        output.append("\n$ " + command + "\n");
        if (command.equals("bash") || command.equals("dash")) {
            output.append(output.getContext().getString(R.string.terminal_interactive_unsupported) + "\n");
            return;
        }
        execute.setEnabled(false);
        worker.execute(() -> {
            String result;
            try { result = runner.apply(command); }
            catch (Exception error) {
                result = output.getContext().getString(R.string.terminal_command_error, error.getMessage());
            }
            String text = result;
            output.post(() -> {
                if (closed) return;
                output.append(text + "\n");
                execute.setEnabled(true);
            });
        });
    }

    @Override public void close() {
        closed = true;
        execute.setEnabled(false);
        execute.setOnClickListener(null);
        worker.shutdownNow();
    }
}
