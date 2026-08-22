package com.winlator.cmod.saves;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.preference.PreferenceManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.winlator.cmod.R;
import com.winlator.cmod.core.AppUtils;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public class CustomFilePickerActivity extends AppCompatActivity {

    private File currentDirectory;
    private RecyclerView recyclerView;
    private FileAdapter fileAdapter;
    private Button confirmButton;
    private Button upButton;  // New Up button
    private final ExecutorService fileLoader = Executors.newSingleThreadExecutor();
    private final AtomicInteger loadGeneration = new AtomicInteger();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Check dark mode setting from shared preferences or system
        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);
        boolean isDarkMode = AppUtils.isDarkMode(this);

        // Apply the theme based on dark mode setting
        if (isDarkMode) {
            setTheme(R.style.AppTheme_Dark);  // Use your dark theme
        } else {
            setTheme(R.style.AppTheme);  // Use your light theme
        }

        // Set content view after setting the theme
        setContentView(R.layout.activity_file_picker);

        // Find and configure UI components
        recyclerView = findViewById(R.id.recyclerView);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        confirmButton = findViewById(R.id.confirmButton);
        upButton = findViewById(R.id.upButton);
        TextView pickerTitle = findViewById(R.id.TVPickerTitle);

        // Set background color and text color for dark mode
        if (isDarkMode) {
            recyclerView.setBackgroundColor(ContextCompat.getColor(this, R.color.content_dialog_background_dark));
            pickerTitle.setTextColor(ContextCompat.getColor(this, R.color.white));
            confirmButton.setBackgroundResource(R.drawable.edit_text_dark);
            upButton.setBackgroundResource(R.drawable.edit_text_dark);
        } else {
//            recyclerView.setBackgroundColor(ContextCompat.getColor(this, R.color.light_background));
//            pickerTitle.setTextColor(ContextCompat.getColor(this, R.color.black));
//            confirmButton.setBackgroundResource(R.drawable.button_light);
//            upButton.setBackgroundResource(R.drawable.button_light);
        }

        // Get the initial directory from the intent
        String initialDirectoryPath = getIntent().getStringExtra("initialDirectory");
        currentDirectory = new File(initialDirectoryPath);

        // Check if in editing mode
        boolean isEditing = getIntent().getBooleanExtra("isEditing", false);
        if (isEditing) {
            // Load the directory to the path of the file being edited
            String editingPath = getIntent().getStringExtra("editingPath");
            if (editingPath != null) {
                currentDirectory = new File(editingPath);
            }
        }

        loadFiles(currentDirectory);

        confirmButton.setOnClickListener(view -> {
            Intent resultIntent = new Intent();
            resultIntent.putExtra("selectedDirectory", currentDirectory.getAbsolutePath());
            setResult(Activity.RESULT_OK, resultIntent);
            finish();
        });

        upButton.setOnClickListener(view -> {
            File parentDirectory = currentDirectory.getParentFile();
            if (parentDirectory != null) {
                currentDirectory = parentDirectory;
                loadFiles(currentDirectory);
                confirmButton.setEnabled(false);
            }
        });
    }


    private void loadFiles(File directory) {
        upButton.setEnabled(directory.getParentFile() != null);
        final int generation = loadGeneration.incrementAndGet();
        final File requestedDirectory = directory;
        recyclerView.setEnabled(false);
        recyclerView.setAdapter(new FileAdapter(new File[0], this::onFileClicked));
        fileLoader.execute(() -> {
            File[] files = requestedDirectory.listFiles();
            if (files == null) files = new File[0];
            final File[] result = files;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()
                        || generation != loadGeneration.get()
                        || !requestedDirectory.equals(currentDirectory)) {
                    return;
                }
                fileAdapter = new FileAdapter(result, this::onFileClicked);
                recyclerView.setAdapter(fileAdapter);
                recyclerView.setEnabled(true);
            });
        });
    }

    @Override
    protected void onDestroy() {
        loadGeneration.incrementAndGet();
        fileLoader.shutdownNow();
        super.onDestroy();
    }

    private void onFileClicked(File file) {
        if (file.isDirectory()) {
            currentDirectory = file;
            loadFiles(currentDirectory);
            confirmButton.setEnabled(true);
        }
    }
}
