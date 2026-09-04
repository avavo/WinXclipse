package com.winlator.cmod;

import static androidx.core.content.ContextCompat.getSystemService;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.FileObserver;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.documentfile.provider.DocumentFile;
import androidx.fragment.app.Fragment;
import androidx.preference.PreferenceManager;
import androidx.recyclerview.widget.DividerItemDecoration;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.winlator.cmod.R;
import com.winlator.cmod.container.Container;
import com.winlator.cmod.container.ContainerManager;
import com.winlator.cmod.container.Shortcut;
import com.winlator.cmod.contentdialog.ContentDialog;
import com.winlator.cmod.contentdialog.ShortcutSettingsDialog;
import com.winlator.cmod.core.AppUtils;
import com.winlator.cmod.core.FileUtils;
import com.winlator.cmod.core.MSLink;
import com.winlator.cmod.core.PreloaderDialog;
import com.winlator.cmod.core.ShortcutArtworkManager;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ShortcutsFragment extends Fragment {
    private RecyclerView recyclerView;
    private TextView emptyTextView;
    private ContainerManager manager;
    private Shortcut currentShortcut;

    private ArrayList<FileObserver> fileObservers = new ArrayList<>();
    private PreloaderDialog preloaderDialog;
    private final ExecutorService shortcutWorker = Executors.newSingleThreadExecutor();
    private final Object shortcutLoadLock = new Object();
    private boolean shortcutLoadRunning;
    private boolean shortcutReloadPending;
    private volatile int shortcutViewGeneration;
    private volatile boolean wineDiscoveryFinished;

    private final ActivityResultLauncher<Intent> artworkPickerLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                    Uri uri = result.getData().getData();
                    if (uri != null && currentShortcut != null) {
                        handleSelectedArtwork(uri);
                    }
                }
            });

    private void openArtworkPicker(Shortcut shortcut) {
        this.currentShortcut = shortcut;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/*");
        artworkPickerLauncher.launch(intent);
    }

    private void showArtworkSourceDialog(final Shortcut shortcut) {
        Context context = requireContext();
        String[] entries = getResources().getStringArray(R.array.shortcut_artwork_mode_entries);
        String current = ShortcutArtworkManager.getMode(context, shortcut);
        int checked = ShortcutArtworkManager.MODE_EXE.equals(current) ? 1
                : ShortcutArtworkManager.MODE_CUSTOM.equals(current) ? 2 : 0;

        float density = getResources().getDisplayMetrics().density;
        LinearLayout body = new LinearLayout(context);
        body.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(20 * density);
        body.setPadding(pad, Math.round(pad / 2f), pad, Math.round(pad / 2f));

        ImageView preview = new ImageView(context);
        Bitmap cover = shortcut.getCoverArt();
        if (cover != null) preview.setImageBitmap(cover);
        else if (shortcut.icon != null) preview.setImageBitmap(shortcut.icon);
        else preview.setImageResource(R.drawable.cover_art_placeholder);
        preview.setScaleType(ImageView.ScaleType.CENTER_CROP);
        LinearLayout.LayoutParams previewParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.round(190 * density));
        previewParams.bottomMargin = Math.round(12 * density);
        body.addView(preview, previewParams);

        RadioGroup group = new RadioGroup(context);
        for (int i = 0; i < entries.length; i++) {
            RadioButton option = new RadioButton(context);
            option.setText(entries[i]);
            option.setTextSize(16);
            option.setTag(i);
            option.setPadding(0, Math.round(6 * density), 0, Math.round(6 * density));
            group.addView(option);
        }
        ((RadioButton) group.getChildAt(Math.max(0, Math.min(checked, entries.length - 1)))).setChecked(true);
        body.addView(group);

        AlertDialog dialog = new AlertDialog.Builder(context)
                .setTitle(R.string.shortcut_artwork)
                .setView(body)
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        group.setOnCheckedChangeListener((g, checkedId) -> {
            RadioButton selected = g.findViewById(checkedId);
            if (selected == null || selected.getTag() == null) return;
            dialog.dismiss();
            onArtworkModePicked(shortcut, (int) selected.getTag());
        });
        dialog.show();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(AppUtils.isDarkMode(context)
                    ? R.drawable.artwork_dialog_background
                    : R.drawable.artwork_dialog_background_light);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // System blur of the shortcuts grid behind the dialog,
                // matching the blurred community-config panels.
                dialog.getWindow().setBackgroundBlurRadius(48);
            }
            dialog.getWindow().addFlags(
                    android.view.WindowManager.LayoutParams.FLAG_BLUR_BEHIND);
        }
    }

    private void onArtworkModePicked(Shortcut shortcut, int which) {
        if (which == 2) {
            ShortcutArtworkManager.setMode(shortcut, ShortcutArtworkManager.MODE_CUSTOM);
            openArtworkPicker(shortcut);
            return;
        }
        clearArtwork(shortcut);
        String mode = which == 1 ? ShortcutArtworkManager.MODE_EXE
                : ShortcutArtworkManager.MODE_BROWSER;
        ShortcutArtworkManager.setMode(shortcut, mode);
        ShortcutArtworkManager.ensure(requireContext(), shortcut, true, success -> {
            loadShortcutsList();
            if (!success && isAdded()) Toast.makeText(getContext(),
                    R.string.shortcut_artwork_failed, Toast.LENGTH_SHORT).show();
        });
    }

    private void handleSelectedArtwork(Uri uri) {
        try (java.io.InputStream input = requireContext().getContentResolver().openInputStream(uri)) {
            Bitmap bitmap = BitmapFactory.decodeStream(input);
            if (bitmap == null) throw new IOException("Unsupported image");
            clearArtwork(currentShortcut);
            ShortcutArtworkManager.setMode(currentShortcut, ShortcutArtworkManager.MODE_CUSTOM);
            currentShortcut.saveCustomCoverArt(bitmap);
            loadShortcutsList();
            Toast.makeText(getContext(), R.string.shortcut_artwork_updated, Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(getContext(), R.string.shortcut_artwork_failed, Toast.LENGTH_SHORT).show();
            Log.e("ShortcutsFragment", "Error handling selected artwork", e);
        }
    }

    private void clearArtwork(Shortcut shortcut) {
        String custom = shortcut.getCustomCoverArtPath();
        if (custom != null && !custom.isEmpty()) shortcut.removeCustomCoverArt();
        ShortcutArtworkManager.deleteGeneratedArtwork(shortcut);
    }


    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(false);
    }

    @Override
    public void onDestroyView() {
        shortcutViewGeneration++;
        stopFileObservers(); // Stop watching to prevent memory leaks
        if (preloaderDialog != null) preloaderDialog.close();
        recyclerView = null;
        emptyTextView = null;
        preloaderDialog = null;
        super.onDestroyView();
    }

    @Override
    public void onDestroy() {
        shortcutWorker.shutdownNow();
        super.onDestroy();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        shortcutViewGeneration++;
        wineDiscoveryFinished = false;
        manager = new ContainerManager(getContext());
        preloaderDialog = new PreloaderDialog(getActivity());
        loadShortcutsList();
        startFileObservers(); // Start watching for new file
        discoverWineShortcutsAsync();
        ((AppCompatActivity)getActivity()).getSupportActionBar().setTitle(R.string.shortcuts);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {



        FrameLayout frameLayout = (FrameLayout)inflater.inflate(R.layout.shortcuts_fragment, container, false);
        recyclerView = frameLayout.findViewById(R.id.RecyclerView);
        emptyTextView = frameLayout.findViewById(R.id.TVEmptyText);
        recyclerView.setLayoutManager(new GridLayoutManager(recyclerView.getContext(), 2));
        return frameLayout;
    }

    private void startFileObservers() {
        stopFileObservers();
        ArrayList<Container> containers = manager.getContainers();
        Log.d("ShortcutObserver", "Starting observers for " + containers.size() + " containers.");

        for (Container container : containers) {
            File desktopDir = container.getDesktopDir();
            Log.d("ShortcutObserver", "Checking container " + container.id + " at path: " + desktopDir.getAbsolutePath());

            if (desktopDir.exists() && desktopDir.isDirectory()) {
                FileObserver observer = new FileObserver(desktopDir, FileObserver.CREATE) {
                    @Override
                    public void onEvent(int event, @Nullable String path) {
                        if (path != null && path.toLowerCase(Locale.ENGLISH).endsWith(".lnk")) {
                            Log.d("ShortcutObserver", "New .lnk file created: " + path);
                            final File newLnkFile = new File(desktopDir, path);
                            Activity activity = getActivity();
                            if (activity != null) {
                                activity.runOnUiThread(() -> {
                                    if (preloaderDialog != null) {
                                        preloaderDialog.show(R.string.creating_shortcut);
                                    }
                                    processNewLinkFile(newLnkFile, container);
                                });
                            }
                        }
                    }
                };
                observer.startWatching();
                fileObservers.add(observer);
            } else {
                Log.w("ShortcutObserver", "Desktop directory does not exist for container " + container.id + ": " + desktopDir.getAbsolutePath());
            }
        }
    }

    private void discoverWineShortcutsAsync() {
        if (shortcutWorker.isShutdown()) return;
        final int generation = shortcutViewGeneration;
        final ContainerManager taskManager = manager;
        shortcutWorker.execute(() -> {
            try {
                taskManager.discoverWineShortcuts();
            }
            catch (Throwable error) {
                Log.e("ShortcutsFragment", "Unable to discover Wine shortcuts", error);
            }

            Activity activity = getActivity();
            if (activity == null) return;
            activity.runOnUiThread(() -> {
                if (generation != shortcutViewGeneration || recyclerView == null) return;
                wineDiscoveryFinished = true;
                // Always refresh: when no link was converted this is also what
                // reveals the empty-state message after background discovery.
                loadShortcutsList();
            });
        });
    }

    private void processNewLinkFile(File lnkFile, Container container) {
        if (shortcutWorker.isShutdown()) return;
        shortcutWorker.execute(() -> {
            boolean shortcutCreated = false;
            try {
                shortcutCreated = createDesktopFileFromLnk(lnkFile, container);
            } finally {
                Activity activity = getActivity();
                if (activity != null) {
                    final boolean finalShortcutCreated = shortcutCreated;
                    activity.runOnUiThread(() -> {
                        if (preloaderDialog != null) preloaderDialog.close();
                        if (finalShortcutCreated) {
                            loadShortcutsList();
                            Toast.makeText(getContext(), "New shortcut created!", Toast.LENGTH_SHORT).show();
                        }
                    });
                }
            }
        });
    }




    private boolean createDesktopFileFromLnk(File lnkFile, Container container) {
        Context lnkContext = getContext();
        if (lnkContext == null) {
            Log.e("ShortcutCreation", "Fragment detached; skipping .lnk processing.");
            return false;
        }
        try {
            Log.d("ShortcutCreation", "Processing .lnk: " + lnkFile.getAbsolutePath());
            File desktopFile = MSLink.createDesktopFile(lnkFile, lnkContext, container);
            if (desktopFile == null) {
                Log.e("ShortcutCreation", "No executable target found in " + lnkFile.getName());
                return false;
            }
            Log.d("ShortcutCreation", "Created .desktop file at " + desktopFile.getAbsolutePath());
            return true;
        } catch (IOException e) {
            Log.e("ShortcutCreation", "IOException creating .desktop file from .lnk", e);
            return false;
        }
    }



    private void stopFileObservers() {
        for (FileObserver observer : fileObservers) {
            observer.stopWatching();
        }
        fileObservers.clear();
    }
    public void loadShortcutsList() {
        if (manager == null || shortcutWorker.isShutdown()) return;
        synchronized (shortcutLoadLock) {
            if (shortcutLoadRunning) {
                shortcutReloadPending = true;
                return;
            }
            shortcutLoadRunning = true;
        }

        final int generation = shortcutViewGeneration;
        final ContainerManager taskManager = manager;
        shortcutWorker.execute(() -> {
            ArrayList<Shortcut> shortcuts = new ArrayList<>();
            Throwable loadError = null;
            try {
                shortcuts.addAll(taskManager.loadShortcutsFast());
            }
            catch (Throwable fatal) {
                loadError = fatal;
                Log.e("ShortcutsFragment", "Fatal error while loading shortcuts!", fatal);
            }

            final ArrayList<Shortcut> loadedShortcuts = shortcuts;
            final Throwable finalLoadError = loadError;
            Activity activity = getActivity();
            if (activity == null) {
                finishShortcutLoad(false);
                return;
            }
            activity.runOnUiThread(() -> {
                boolean viewAlive = generation == shortcutViewGeneration
                        && recyclerView != null && emptyTextView != null;
                if (viewAlive) {
                    recyclerView.setAdapter(new ShortcutsAdapter(loadedShortcuts));
                    emptyTextView.setVisibility(loadedShortcuts.isEmpty()
                            && wineDiscoveryFinished ? View.VISIBLE : View.GONE);
                    if (finalLoadError != null) {
                        Toast.makeText(getContext(),
                                "Couldn’t load shortcuts (see log).", Toast.LENGTH_LONG).show();
                    }
                }
                // A newer view may have requested a refresh while this old
                // generation was finishing. Allow that request to run even
                // though these particular results must not be displayed.
                finishShortcutLoad(recyclerView != null && emptyTextView != null);
            });
        });
    }

    private void finishShortcutLoad(boolean viewAlive) {
        boolean reload;
        synchronized (shortcutLoadLock) {
            shortcutLoadRunning = false;
            reload = shortcutReloadPending;
            shortcutReloadPending = false;
        }
        if (reload && viewAlive) loadShortcutsList();
    }


    private class ShortcutsAdapter extends RecyclerView.Adapter<ShortcutsAdapter.ViewHolder> {
        private final List<Shortcut> data;

        private class ViewHolder extends RecyclerView.ViewHolder {
            private final ImageButton menuButton;
            private final ImageView imageView;
            private final TextView title;
            private final TextView subtitle;
            private final ImageView labelBackground;
            private final View innerArea;

            private ViewHolder(View view) {
                super(view);
                this.imageView = view.findViewById(R.id.ImageView);
                this.title = view.findViewById(R.id.TVTitle);
                this.subtitle = view.findViewById(R.id.TVSubtitle);
                this.menuButton = view.findViewById(R.id.BTMenu);
                this.labelBackground = view.findViewById(R.id.IVLabelBackground);
                this.innerArea = view.findViewById(R.id.LLInnerArea);
            }
        }

        public ShortcutsAdapter(List<Shortcut> data) {
            this.data = data;
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new ViewHolder(LayoutInflater.from(parent.getContext()).inflate(R.layout.shortcut_list_item, parent, false));
        }

        @Override
        public void onViewRecycled(@NonNull ViewHolder holder) {
            holder.menuButton.setOnClickListener(null);
            holder.innerArea.setOnClickListener(null);
            holder.imageView.setOnLongClickListener(null);
            super.onViewRecycled(holder);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            final Shortcut item = data.get(position);
            if (item.getCoverArt() != null) {
                holder.imageView.setImageBitmap(item.getCoverArt());
            } else if (item.icon != null) {
                holder.imageView.setImageBitmap(item.icon);
            } else {
                holder.imageView.setImageResource(R.drawable.cover_art_placeholder);
            }
            // Blurred artwork label, same language as the community cards.
            Bitmap labelArt = item.getCoverArt() != null ? item.getCoverArt() : item.icon;
            if (labelArt != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    holder.labelBackground.setImageBitmap(labelArt);
                    holder.labelBackground.setRenderEffect(android.graphics.RenderEffect.createBlurEffect(
                            22.0f, 22.0f, android.graphics.Shader.TileMode.CLAMP));
                }
                else {
                    // Cheap blur fallback without RenderScript on Android 8-11.
                    holder.labelBackground.setRenderEffect(null);
                    int width = Math.max(8, Math.min(48, labelArt.getWidth() / 12));
                    int height = Math.max(8, Math.min(64, labelArt.getHeight() / 12));
                    holder.labelBackground.setImageBitmap(
                            Bitmap.createScaledBitmap(labelArt, width, height, true));
                }
            }
            else {
                holder.labelBackground.setRenderEffect(null);
                holder.labelBackground.setImageResource(R.drawable.cover_art_placeholder);
            }
            if (item.getCoverArt() == null) {
                ShortcutArtworkManager.ensure(requireContext(), item, false, success -> {
                    // Only rebind when artwork actually appeared; otherwise every
                    // scroll would retry failed downloads forever.
                    if (!success) return;
                    int adapterPosition = holder.getBindingAdapterPosition();
                    if (adapterPosition != RecyclerView.NO_POSITION) notifyItemChanged(adapterPosition);
                });
            }
            holder.imageView.setOnLongClickListener(v -> {
                showArtworkSourceDialog(item);
                return true;
            });
            holder.title.setText(item.name);
            holder.subtitle.setText(item.container.getName());
            holder.menuButton.setOnClickListener((v) -> showListItemMenu(v, item));
            holder.innerArea.setOnClickListener((v) -> runFromShortcut(item));

        }

        @Override
        public final int getItemCount() {
            return data.size();
        }

        private void showListItemMenu(View anchorView, final Shortcut shortcut) {
            final Context context = getContext();
            PopupMenu listItemMenu = new PopupMenu(context, anchorView);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) listItemMenu.setForceShowIcon(true);

            listItemMenu.inflate(R.menu.shortcut_popup_menu);
            listItemMenu.setOnMenuItemClickListener((menuItem) -> {
                int itemId = menuItem.getItemId();
                if (itemId == R.id.shortcut_settings) {
                    (new ShortcutSettingsDialog(ShortcutsFragment.this, shortcut)).show();
                }
                else if (itemId == R.id.shortcut_change_artwork) {
                    showArtworkSourceDialog(shortcut);
                }
                else if (itemId == R.id.shortcut_remove) {
                    ContentDialog.confirm(context, R.string.do_you_want_to_remove_this_shortcut, () -> {
                        boolean desktopDeleted  = safeDelete(shortcut.file);
                        boolean iconDeleted     = safeDelete(shortcut.iconFile);
                        boolean lnkDeleted      = deletePairedLnkForShortcut(shortcut);

                        if (desktopDeleted) {
                            disableShortcutOnScreen(requireContext(), shortcut);
                            loadShortcutsList();
                        }

                        String msg;
                        if (desktopDeleted) {
                            if (lnkDeleted) {
                                msg = "Shortcut and paired .lnk removed.";
                            } else {
                                msg = "Shortcut removed." + (shortcut.file != null
                                        ? " (No paired .lnk found or could not delete.)"
                                        : "");
                            }
                        } else {
                            msg = "Failed to remove the shortcut. Please try again.";
                        }

                        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show();
                    });
                }
                else if (itemId == R.id.shortcut_clone_to_container) {
                    // Use the ContainerManager to get the list of containers
                    ContainerManager containerManager = new ContainerManager(context);
                    ArrayList<Container> containers = containerManager.getContainers();

                    // Show a container selection dialog
                    showContainerSelectionDialog(containers, new OnContainerSelectedListener() {
                        @Override
                        public void onContainerSelected(Container selectedContainer) {
                            // Use the selected container to clone the shortcut
                            if (shortcut.cloneToContainer(selectedContainer)) {
                                Toast.makeText(context, "Shortcut cloned successfully.", Toast.LENGTH_SHORT).show();
                                loadShortcutsList(); // Reload the shortcuts to show the cloned one
                            } else {
                                Toast.makeText(context, "Failed to clone shortcut.", Toast.LENGTH_SHORT).show();
                            }
                        }
                    });
                }
                else if (itemId == R.id.shortcut_add_to_home_screen) {
                    if (shortcut.getExtra("uuid").equals(""))
                        shortcut.genUUID();
                    addShortcutToScreen(shortcut);
                }
                else if (itemId == R.id.shortcut_export_to_frontend) {
                    exportShortcutToFrontend(shortcut);
                }
                else if (itemId == R.id.shortcut_properties) {
                    showShortcutProperties(shortcut);
                }
                else if (itemId == R.id.shortcut_set_favorite) {
                    if (shortcut.getExtra("uuid").equals("")) shortcut.genUUID();  // ensure UUID
                    PreferenceManager.getDefaultSharedPreferences(context)
                            .edit().putString("favorite_uuid", shortcut.getExtra("uuid")).apply();
                    Toast.makeText(context, "Favorite set to " + shortcut.name, Toast.LENGTH_SHORT).show();
                }
                return true;
            });
            listItemMenu.show();
        }

        // Define the listener interface for selecting a container
        public interface OnContainerSelectedListener {
            void onContainerSelected(Container container);
        }

        private void showContainerSelectionDialog(ArrayList<Container> containers, OnContainerSelectedListener listener) {
            // Create an AlertDialog to show the list of containers
            AlertDialog.Builder builder = new AlertDialog.Builder(getContext());
            builder.setTitle("Select a container");

            // Create an array of container names to display
            String[] containerNames = new String[containers.size()];
            for (int i = 0; i < containers.size(); i++) {
                containerNames[i] = containers.get(i).getName();
            }

            // Set up the list in the dialog
            builder.setItems(containerNames, (dialog, which) -> {
                // Call the listener when a container is selected
                listener.onContainerSelected(containers.get(which));
            });

            // Show the dialog
            builder.show();
        }






        private void runFromShortcut(Shortcut shortcut) {
            Activity activity = getActivity();

            if (!XrActivity.isEnabled(getContext())) {
                Intent intent = new Intent(activity, XServerDisplayActivity.class);
                intent.putExtra("container_id", shortcut.container.id);
                intent.putExtra("shortcut_path", shortcut.file.getPath());
                intent.putExtra("shortcut_name", shortcut.name); // Add this line to pass the shortcut name
                // Check if the shortcut has the disableXinput value; if not, default to false.
                String disableXinputValue = shortcut.getExtra("disableXinput", "0"); // Get value from shortcut or use "0" (false) by default
                intent.putExtra("disableXinput", disableXinputValue); // Use the actual value from the shortcut
                activity.startActivity(intent);
            }
            else XrActivity.openIntent(activity, shortcut.container.id, shortcut.file.getPath());
        }

        private void exportShortcutToFrontend(Shortcut shortcut) {
            // Check for a custom frontend export path in shared preferences
            SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(getContext());
            String uriString = sharedPreferences.getString("frontend_export_uri", null);

            File frontendDir;

            if (uriString != null) {
                // If custom URI is set, use it
                Uri folderUri = Uri.parse(uriString);
                DocumentFile pickedDir = DocumentFile.fromTreeUri(getContext(), folderUri);

                if (pickedDir == null || !pickedDir.canWrite()) {
                    Toast.makeText(getContext(), "Cannot write to the selected folder", Toast.LENGTH_SHORT).show();
                    return;
                }

                // Frontend export creates several regular files, so it requires
                // a storage-provider URI that can also be resolved to a public
                // filesystem path.
                String folderPath = FileUtils.getFilePathFromUri(getContext(), folderUri);
                if (folderPath == null) {
                    Toast.makeText(getContext(),
                            "The selected folder is not available as a filesystem path. Enable storage access or choose a local/USB storage folder.",
                            Toast.LENGTH_LONG).show();
                    return;
                }
                frontendDir = new File(folderPath);
            } else {
                // Default to Downloads\Winlator\Frontend if no custom URI is set
                frontendDir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Winlator/Frontend");
                if (!frontendDir.exists() && !frontendDir.mkdirs()) {
                    Toast.makeText(getContext(), "Failed to create default directory", Toast.LENGTH_SHORT).show();
                    return;
                }
            }


            // Check for FRONTEND_INSTRUCTIONS.txt
            File instructionsFile = new File(frontendDir, "FRONTEND_INSTRUCTIONS.txt");
            if (true) {
                try (FileWriter writer = new FileWriter(instructionsFile, false)) {
                    writer.write("Instructions for adding Winlator shortcuts to Frontends:\n\n");
                    writer.write("Daijisho:\n\n");
                    writer.write("1. Open Daijisho\n");
                    writer.write("2. Navigate to the Settings tab.\n");
                    writer.write("3. Navigate to Settings\\Library\n");
                    writer.write("4. Select, Import from Pegasus\n");
                    writer.write("5. Add the metadata.pegasus.txt file located in this directory (Downloads\\Winlator\\Frontend)\n");
                    writer.write("6. Set the Sync path to Downloads\\Winlator\\Frontend\n");
                    writer.write("7. Start your game!\n\n");
                    writer.write("Beacon:\n\n");
                    writer.write("1. Navigate to Settings\n");
                    writer.write("2. Click the + Icon\n");
                    writer.write("3. Set the following values:\n\n");
                    writer.write("Platform Type: Custom\n");
                    writer.write("Name: Windows (or Winlator, whatever you prefer)\n");
                    writer.write("Short name: windows\n");
                    writer.write("Player app: Select Winlator Cmod (or whichever fork you are using that has adopted this code)\n");
                    writer.write("ROMs folder: Use Android FilePicker to select the Downloads\\Winlator\\Frontend directory\n");
                    writer.write("Expand Advanced:\n");
                    writer.write("File handling: Default\n");
                    writer.write("Use custom launch: True\n");
                    writer.write("am start command: am start -n " + "com.winlator.cmod/com.winlator.cmod.XServerDisplayActivity -e shortcut_path {file_path}\n\n");
                    writer.write("4. Click Save\n");
                    writer.write("5. Scan the folder for your game\n");
                    writer.write("6. Launch your game!\n");
                    writer.flush();
                    Log.d("ShortcutsFragment", "FRONTEND_INSTRUCTIONS.txt created successfully.");
                } catch (IOException e) {
                    Log.e("ShortcutsFragment", "Failed to create FRONTEND_INSTRUCTIONS.txt", e);
                }
            }

            // Check for metadata.pegasus.txt
            File metadataFile = new File(frontendDir, "metadata.pegasus.txt");
            try (FileWriter writer = new FileWriter(metadataFile, false)) {
                writer.write("collection: Windows\n");
                writer.write("shortname: windows\n");
                writer.write("extensions: desktop\n");
                writer.write("launch: am start\n");
                writer.write("  -n " + "com.winlator.cmod/com.winlator.cmod.XServerDisplayActivity\n");
                writer.write("  -e shortcut_path {file.path}\n");
                writer.write("  --activity-clear-task\n");
                writer.write("  --activity-clear-top\n");
                writer.write("  --activity-no-history\n");
                writer.flush();
                Log.d("ShortcutsFragment", "metadata.pegasus.txt created or updated successfully.");
            } catch (IOException e) {
                Log.e("ShortcutsFragment", "Failed to create or update metadata.pegasus.txt", e);
            }

            // Create the export file in the Frontend directory
            File exportFile = new File(frontendDir, shortcut.file.getName());

            boolean fileExists = exportFile.exists();
            boolean containerIdFound = false;

            try {
                List<String> lines = new ArrayList<>();

                // Read the original file or existing file if it exists
                try (BufferedReader reader = new BufferedReader(new FileReader(shortcut.file))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (line.startsWith("container_id=")) {
                            // Replace the existing container_id line
                            lines.add("container_id=" + shortcut.container.id);
                            containerIdFound = true;
                        } else {
                            lines.add(line);
                        }
                    }
                }

                // If no container_id was found, add it
                if (!containerIdFound) {
                    lines.add("container_id=" + shortcut.container.id);
                }

                // Write the contents to the export file
                try (FileWriter writer = new FileWriter(exportFile, false)) {
                    for (String line : lines) {
                        writer.write(line + "\n");
                    }
                    writer.flush();
                }

                Log.d("ShortcutsFragment", "Shortcut exported successfully to " + exportFile.getPath());

                // Determine the toast message
                String message;
                if (fileExists) {
                    message = "Frontend Shortcut Updated at " + exportFile.getPath();
                } else {
                    message = "Frontend Shortcut Exported to " + exportFile.getPath();
                }

                // Show a toast message to the user
                Toast.makeText(getContext(), message, Toast.LENGTH_LONG).show();

            } catch (IOException e) {
                Log.e("ShortcutsFragment", "Failed to export shortcut", e);
                Toast.makeText(getContext(), "Failed to export shortcut", Toast.LENGTH_LONG).show();
            }
        }

        private void showShortcutProperties(Shortcut shortcut) {
            SharedPreferences playtimePrefs = getContext().getSharedPreferences("playtime_stats", Context.MODE_PRIVATE);

            String playtimeKey = shortcut.name + "_playtime";
            String playCountKey = shortcut.name + "_play_count";

            long totalPlaytime = playtimePrefs.getLong(playtimeKey, 0);
            int playCount = playtimePrefs.getInt(playCountKey, 0);

            // Convert playtime to human-readable format
            long seconds = (totalPlaytime / 1000) % 60;
            long minutes = (totalPlaytime / (1000 * 60)) % 60;
            long hours = (totalPlaytime / (1000 * 60 * 60)) % 24;
            long days = (totalPlaytime / (1000 * 60 * 60 * 24));

            String playtimeFormatted = String.format("%dd %02dh %02dm %02ds", days, hours, minutes, seconds);

            // Create the properties dialog
            ContentDialog dialog = new ContentDialog(getContext(), R.layout.shortcut_properties_dialog);
            dialog.setTitle("Properties");

            TextView playCountTextView = dialog.findViewById(R.id.play_count);
            TextView playtimeTextView = dialog.findViewById(R.id.playtime);

            playCountTextView.setText("Number of times played: " + playCount);
            playtimeTextView.setText("Playtime: " + playtimeFormatted);

            Button resetPropertiesButton = dialog.findViewById(R.id.reset_properties);

            resetPropertiesButton.setOnClickListener(v -> {
                playtimePrefs.edit().remove(playtimeKey).remove(playCountKey).apply();
                Toast.makeText(getContext(), "Properties reset successfully.", Toast.LENGTH_SHORT).show();
                dialog.dismiss();
            });

            dialog.show();
        }




    }

    private ShortcutInfo buildScreenShortCut(String shortLabel, String longLabel, int containerId, String shortcutPath, Icon icon, String uuid) {
        Intent intent = new Intent(getActivity(), XServerDisplayActivity.class);
        intent.setAction(Intent.ACTION_VIEW);
        intent.putExtra("container_id", containerId);
        intent.putExtra("shortcut_path", shortcutPath);

        return new ShortcutInfo.Builder(getActivity(), uuid)
                .setShortLabel(shortLabel)
                .setLongLabel(longLabel)
                .setIcon(icon)
                .setIntent(intent)
                .build();
    }

    private void addShortcutToScreen(Shortcut shortcut) {
        ShortcutManager shortcutManager = getSystemService(requireContext(), ShortcutManager.class);
        if (shortcutManager != null && shortcutManager.isRequestPinShortcutSupported())
            shortcutManager.requestPinShortcut(buildScreenShortCut(shortcut.name, shortcut.name, shortcut.container.id,
                    shortcut.file.getPath(), Icon.createWithBitmap(shortcut.icon), shortcut.getExtra("uuid")), null);
    }

    public static void disableShortcutOnScreen(Context context, Shortcut shortcut) {
        ShortcutManager shortcutManager = getSystemService(context, ShortcutManager.class);
        try {
            shortcutManager.disableShortcuts(Collections.singletonList(shortcut.getExtra("uuid")),
                    context.getString(R.string.shortcut_not_available));
        } catch (Exception e) {}
    }

    public void updateShortcutOnScreen(String shortLabel,
                                       String longLabel,
                                       int    containerId,
                                       String shortcutPath,
                                       Icon   icon,
                                       String uuid) {

        ShortcutManager sm =
                androidx.core.content.ContextCompat.getSystemService(
                        requireContext(), ShortcutManager.class);

        if (sm == null) {                    // ⇦ grace-fully bail out on devices
            Log.w("ShortcutsFragment",       //    that don’t expose ShortcutManager
                    "ShortcutManager not available; cannot update pinned shortcut");
            return;
        }

        for (ShortcutInfo info : sm.getPinnedShortcuts()) {
            if (uuid.equals(info.getId())) {
                sm.updateShortcuts(Collections.singletonList(
                        buildScreenShortCut(shortLabel, longLabel,
                                containerId, shortcutPath, icon, uuid)));
                break;
            }
        }
    }

    private static boolean safeDelete(@Nullable File f) {
        try {
            return f != null && f.exists() && f.delete();
        } catch (Exception e) {
            Log.e("ShortcutsFragment", "Delete failed for: " + (f != null ? f.getAbsolutePath() : "null"), e);
            return false;
        }
    }

    /** Delete a sibling .lnk that matches the .desktop basename. */
    private boolean deletePairedLnkForShortcut(Shortcut shortcut) {
        if (shortcut == null || shortcut.file == null) return false;
        File dir = shortcut.file.getParentFile();
        if (dir == null) return false;

        String base = FileUtils.getBasename(shortcut.file.getName()); // strips extension
        File lnk = new File(dir, base + ".lnk");
        boolean deleted = safeDelete(lnk);
        if (deleted) {
            Log.d("ShortcutsFragment", "Paired .lnk removed: " + lnk.getAbsolutePath());
        } else if (lnk.exists()) {
            Log.w("ShortcutsFragment", "Paired .lnk exists but could not be removed: " + lnk.getAbsolutePath());
        } else {
            Log.d("ShortcutsFragment", "No paired .lnk found for " + base);
        }
        return deleted;
    }
}
