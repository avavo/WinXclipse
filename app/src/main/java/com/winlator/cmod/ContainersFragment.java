package com.winlator.cmod;

import static com.winlator.cmod.core.AppUtils.showToast;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.app.ActivityManager;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.PopupMenu;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.content.res.AppCompatResources;
import androidx.appcompat.widget.AppCompatImageView;
import androidx.core.view.ViewCompat;
import androidx.core.content.FileProvider;
import androidx.documentfile.provider.DocumentFile;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.preference.PreferenceManager;
import androidx.recyclerview.widget.DividerItemDecoration;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.winlator.cmod.R;
import com.winlator.cmod.container.Container;
import com.winlator.cmod.container.ContainerManager;
import com.winlator.cmod.container.CommunityConfigManager;
import com.winlator.cmod.container.Shortcut;
import com.winlator.cmod.contentdialog.ContentDialog;
import com.winlator.cmod.contentdialog.StorageInfoDialog;
import com.winlator.cmod.core.AppUtils;
import com.winlator.cmod.core.FileUtils;
import com.winlator.cmod.core.GPUInformation;
import com.winlator.cmod.core.ContentOperationRegistry;
import com.winlator.cmod.core.PreloaderDialog;
import com.winlator.cmod.xenvironment.ImageFs;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;

import org.json.JSONObject;

public class ContainersFragment extends Fragment {
    private static final int REQUEST_CODE_IMPORT_CONTAINER = 1070;
    private static final int REQUEST_CODE_IMPORT_COMMUNITY_CONFIG = 1071;
    private RecyclerView recyclerView;
    private TextView emptyTextView;
    private ContainerManager manager;
    private PreloaderDialog preloaderDialog;

    private MenuItem favoriteItem;

    private static final String PREF_FAVORITE_UUID = "favorite_uuid";

    private ImageView favoriteActionView;


    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(true);
        preloaderDialog = new PreloaderDialog(getActivity());
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        preloaderDialog.close();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        manager = new ContainerManager(getContext());
        loadContainersList();
        ((AppCompatActivity) getActivity()).getSupportActionBar().setTitle(R.string.containers);
        Bundle arguments = getArguments();
        if (arguments != null) {
            String configPath = arguments.getString("community_config_path", "");
            if (!configPath.isEmpty()) {
                arguments.remove("community_config_path");
                view.post(() -> readCommunityConfig(new File(configPath)));
                return;
            }
            String configUri = arguments.getString("community_config_uri", "");
            if (!configUri.isEmpty()) {
                arguments.remove("community_config_uri");
                view.post(() -> readCommunityConfig(Uri.parse(configUri)));
            }
        }
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        FrameLayout frameLayout = (FrameLayout) inflater.inflate(R.layout.containers_fragment, container, false);
        recyclerView = frameLayout.findViewById(R.id.RecyclerView);
        emptyTextView = frameLayout.findViewById(R.id.TVEmptyText);
        recyclerView.setLayoutManager(new LinearLayoutManager(recyclerView.getContext()));
        recyclerView.addItemDecoration(new DividerItemDecoration(recyclerView.getContext(), DividerItemDecoration.VERTICAL));
        return frameLayout;
    }

    private void loadContainersList() {
        ArrayList<Container> containers = manager.getContainers();
        recyclerView.setAdapter(new ContainersAdapter(containers));
        if (containers.isEmpty()) emptyTextView.setVisibility(View.VISIBLE);
    }


    @Override
    public void onResume() {
        super.onResume();
        // re-create the menu so the icon always matches current favorite
        requireActivity().invalidateOptionsMenu();
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        menu.clear();
        inflater.inflate(R.menu.containers_menu, menu);

        // Other items tinting...
        MenuItem bigPictureItem = menu.findItem(R.id.action_big_picture_mode);
        if (bigPictureItem != null && bigPictureItem.getIcon() != null) {
            bigPictureItem.getIcon().mutate();
            bigPictureItem.getIcon().setColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN);
        }

        favoriteItem = menu.findItem(R.id.action_favorite_star);
        if (favoriteItem != null) {
            // Ensure it shows in the toolbar
            favoriteItem.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);

            // Create an ImageView as the action view
            AppCompatImageView iv = new AppCompatImageView(requireContext());
            int pad = dp(8);
            iv.setPadding(pad, pad, pad, pad);
            iv.setAdjustViewBounds(true);
            iv.setClickable(true);
            iv.setFocusable(true);
            iv.setContentDescription(getString(R.string.app_name)); // or "Favorite shortcut"
            ViewCompat.setTooltipText(iv, "Favorite");

            favoriteItem.setActionView(iv);
            favoriteActionView = iv;

            // Click => launch (or prompt to choose)
            iv.setOnClickListener(v -> handleFavoriteClick());

            // Long‑press => clear (if set)
            iv.setOnLongClickListener(v -> {
                v.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
                Shortcut fav = getFavoriteShortcut();
                if (fav == null) {
                    Toast.makeText(getContext(), "No favorite assigned.", Toast.LENGTH_SHORT).show();
                } else {
                    new AlertDialog.Builder(getContext())
                            .setTitle("Clear favorite")
                            .setMessage("Remove the current favorite?")
                            .setPositiveButton("Remove", (d, w) -> clearFavorite())
                            .setNegativeButton("Cancel", null)
                            .show();
                }
                return true;
            });
        }

        // Finally, paint the current icon into the view
        applyFavoriteIcon();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem menuItem) {
        switch (menuItem.getItemId()) {
            case R.id.containers_menu_add:
                if (!ImageFs.find(getContext()).isValid()) return false;
                FragmentManager fragmentManager = getParentFragmentManager();
                fragmentManager.beginTransaction()
                        .setCustomAnimations(R.anim.slide_in_up, R.anim.slide_out_down, R.anim.slide_in_down, R.anim.slide_out_up)
                        .addToBackStack(null)
                        .replace(R.id.FLFragmentContainer, new ContainerDetailFragment())
                        .commit();
                return true;

            case R.id.containers_menu_import_config:
                Intent importConfig = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                importConfig.addCategory(Intent.CATEGORY_OPENABLE);
                importConfig.setType("*/*");
                startActivityForResult(importConfig, REQUEST_CODE_IMPORT_COMMUNITY_CONFIG);
                return true;

//            case R.id.containers_menu_import:
//                showImportInfoDialog();
//                return true;

            case R.id.action_big_picture_mode:
                toggleBigPictureMode();
                return true;

//            case R.id.action_terminal:  // New case for TerminalActivity
//                openTerminal();
//                return true;

//            case R.id.action_copy_dev_libs:
//                copyDevLibs();
//                return true;

            case R.id.action_favorite_star: {
                Shortcut fav = getFavoriteShortcut();
                if (fav == null) {
                    new AlertDialog.Builder(getContext())
                            .setTitle("Favorite")
                            .setMessage("No favorite assigned. Choose one now?")
                            .setPositiveButton("Choose", (d, w) -> {
                                getParentFragmentManager().beginTransaction()
                                        .setCustomAnimations(R.anim.slide_in_up, R.anim.slide_out_down,
                                                R.anim.slide_in_down, R.anim.slide_out_up)
                                        .addToBackStack(null)
                                        .replace(R.id.FLFragmentContainer, new ShortcutsFragment())
                                        .commit();
                            })
                            .setNegativeButton("Cancel", null)
                            .show();
                } else {
                    runShortcut(fav);
                }
                return true;
            }


            default:
                return super.onOptionsItemSelected(menuItem);
        }
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_CODE_IMPORT_CONTAINER && resultCode == Activity.RESULT_OK) {
            Uri uri = data != null ? data.getData() : null;
            if (uri != null) {
                File importDir = FileUtils.getFileFromUri(getContext(), uri);
                if (importDir == null || !importDir.isDirectory()) {
                    AppUtils.showToast(getContext(), "Invalid container directory.");
                } else showImportConfirmationDialog(uri, importDir);
            }
            return;
        }
        if (requestCode != REQUEST_CODE_IMPORT_COMMUNITY_CONFIG
                || resultCode != Activity.RESULT_OK || data == null || data.getData() == null) return;
        readCommunityConfig(data.getData());
    }

    private interface CommunityManifestReader {
        JSONObject read() throws Exception;
    }

    private void readCommunityConfig(Uri uri) {
        readCommunityConfig(() -> CommunityConfigManager.readConfig(
                requireContext().getApplicationContext(), uri));
    }

    private void readCommunityConfig(File file) {
        readCommunityConfig(() -> {
            try {
                return CommunityConfigManager.readConfig(file);
            }
            finally {
                File parent = file.getParentFile();
                if (parent != null && "pending_community_configs".equals(parent.getName())) {
                    FileUtils.delete(file);
                }
            }
        });
    }

    private void readCommunityConfig(CommunityManifestReader manifestReader) {
        Context appContext = requireContext().getApplicationContext();
        Activity hostActivity = getActivity();
        if (ContentOperationRegistry.hasActiveOperations()) {
            Toast.makeText(requireContext(),
                    "Waiting for the required downloads/installations to finish…",
                    Toast.LENGTH_LONG).show();
            ContentOperationRegistry.runWhenIdle(() -> {
                if (hostActivity == null || hostActivity.isFinishing()) return;
                hostActivity.runOnUiThread(() -> {
                    if (isAdded()) readCommunityConfig(manifestReader);
                });
            });
            return;
        }
        preloaderDialog.allowBackground();
        preloaderDialog.show(R.string.importing_container);
        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                JSONObject manifest = manifestReader.read();
                List<CommunityConfigManager.ContentResolution> resolutions =
                        CommunityConfigManager.resolveContents(appContext, manifest);
                if (hostActivity == null || hostActivity.isFinishing()) return;
                hostActivity.runOnUiThread(() -> {
                    preloaderDialog.close();
                    if (!isAdded()) return;
                    resolveImportedContents(manifest, resolutions, new ArrayList<>(), 0);
                });
            } catch (Exception e) {
                Log.e("ContainersFragment", "Unable to import community config", e);
                if (hostActivity != null && !hostActivity.isFinishing()) hostActivity.runOnUiThread(() -> {
                    preloaderDialog.close();
                    if (isAdded()) ContentDialog.alert(requireContext(),
                            "Invalid or unsupported community config.", null);
                });
            }
        });
    }

    private void resolveImportedContents(JSONObject manifest,
                                         List<CommunityConfigManager.ContentResolution> resolutions,
                                         List<com.winlator.cmod.contents.ContentProfile> choices,
                                         int index) {
        if (index >= resolutions.size()) {
            installImportedConfiguration(manifest, resolutions, choices);
            return;
        }
        CommunityConfigManager.ContentResolution current = resolutions.get(index);
        if (current.exact != null) {
            choices.add(current.exact);
            resolveImportedContents(manifest, resolutions, choices, index + 1);
            return;
        }

        String message = "Required by config: " + current.requestedName();
        if (current.similar != null) {
            message += "\n\nClosest compatible installed content: "
                    + com.winlator.cmod.contents.ContentsManager.getEntryName(current.similar)
                    + " (" + current.similarity + "% match)";
            AlertDialog dialog = new AlertDialog.Builder(requireContext())
                    .setTitle("Content not found")
                    .setMessage(message)
                    .setPositiveButton("Use closest", (dialogInterface, which) -> {
                        choices.add(current.similar);
                        resolveImportedContents(manifest, resolutions, choices, index + 1);
                    })
                    .setNegativeButton("Continue without", (dialogInterface, which) -> {
                        choices.add(null);
                        resolveImportedContents(manifest, resolutions, choices, index + 1);
                    })
                    .setNeutralButton(android.R.string.cancel, null)
                    .create();
            showCommunityDialog(dialog);
        } else if ("wineRuntime".equals(current.reference.optString("role", ""))
                && !current.requestedName().toLowerCase(java.util.Locale.ENGLISH).contains("arm64ec")
                && !current.requestedName().toLowerCase(java.util.Locale.ENGLISH).matches(".*(^|[-_])x86($|[-_]).*")) {
            AlertDialog dialog = new AlertDialog.Builder(requireContext())
                    .setTitle("Content not found")
                    .setMessage(message + "\n\nClosest compatible built-in runtime: "
                            + com.winlator.cmod.core.WineInfo.MAIN_WINE_VERSION.identifier())
                    .setPositiveButton("Use closest", (dialogInterface, which) -> {
                        // Null intentionally maps wineRuntime to the bundled default.
                        choices.add(null);
                        resolveImportedContents(manifest, resolutions, choices, index + 1);
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .create();
            showCommunityDialog(dialog);
        } else {
            AlertDialog dialog = new AlertDialog.Builder(requireContext())
                    .setTitle("Content not found")
                    .setMessage(message + "\n\nNo sufficiently similar installed content was found.")
                    .setPositiveButton("Continue without", (dialogInterface, which) -> {
                        choices.add(null);
                        resolveImportedContents(manifest, resolutions, choices, index + 1);
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .create();
            showCommunityDialog(dialog);
        }
    }

    private void showCommunityDialog(AlertDialog dialog) {
        dialog.show();
        if (AppUtils.isDarkMode(requireContext()) && dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(
                    R.drawable.content_dialog_background_dark);
        }
    }

    private void installImportedConfiguration(JSONObject manifest,
                                              List<CommunityConfigManager.ContentResolution> resolutions,
                                              List<com.winlator.cmod.contents.ContentProfile> choices) {
        try {
            JSONObject config = CommunityConfigManager.applyResolution(
                    requireContext(), manifest, resolutions, choices);
            JSONObject metadata = manifest.optJSONObject("metadata");
            String gameName = metadata != null ? metadata.optString("gameName", "") : "";
            if (!gameName.trim().isEmpty()) config.put("name", gameName.trim());
            ContentOperationRegistry.Token operation =
                    ContentOperationRegistry.begin(ContentOperationRegistry.Kind.INSTALL);
            preloaderDialog.allowBackground();
            preloaderDialog.show(R.string.creating_container);
            com.winlator.cmod.contents.ContentsManager contents =
                    new com.winlator.cmod.contents.ContentsManager(requireContext());
            contents.syncContents();
            manager.createContainerAsync(config, contents, created -> {
                operation.close();
                preloaderDialog.close();
                if (!isAdded()) return;
                if (created == null) {
                    ContentDialog.alert(requireContext(), "Could not create the container from this config.", null);
                } else {
                    loadContainersList();
                    Toast.makeText(requireContext(), "Community config imported.", Toast.LENGTH_SHORT).show();
                }
            });
        } catch (Exception e) {
            Log.e("ContainersFragment", "Unable to apply community config", e);
            ContentDialog.alert(requireContext(), "Could not apply this community config.", null);
        }
    }

    private void runShortcut(Shortcut s) {
        Intent intent = new Intent(getActivity(), XServerDisplayActivity.class);
        intent.setAction(Intent.ACTION_VIEW);
        intent.putExtra("container_id", s.container.id);
        intent.putExtra("shortcut_path", s.file.getPath());
        startActivity(intent);
    }

    private void copyDevLibs() {
        // Use requireActivity() or getActivity() to get the context
        android.content.res.AssetManager assetManager = requireActivity().getAssets();
        String sourceDir = "devLibs";
        String destDir = new java.io.File(requireActivity().getFilesDir(), "imagefs/opt/proton-9.0-arm64ec/lib/wine/aarch64-unix").getAbsolutePath();

        try {
            // Create the destination directory if it doesn't exist
            java.io.File destDirFile = new java.io.File(destDir);
            if (!destDirFile.exists()) {
                if (!destDirFile.mkdirs()) {
                    // If directory creation fails, show an error and exit
                    android.widget.Toast.makeText(requireActivity(), "Failed to create destination directory.", android.widget.Toast.LENGTH_SHORT).show();
                    return;
                }
            }

            // List all files in the assets/devLibs folder
            String[] files = assetManager.list(sourceDir);
            if (files == null || files.length == 0) {
                android.widget.Toast.makeText(requireActivity(), "No dev libs found in assets.", android.widget.Toast.LENGTH_SHORT).show();
                return;
            }

            // Loop through each file and copy it
            for (String filename : files) {
                java.io.InputStream in = null;
                java.io.OutputStream out = null;
                try {
                    in = assetManager.open(sourceDir + "/" + filename);
                    java.io.File outFile = new java.io.File(destDir, filename);
                    out = new java.io.FileOutputStream(outFile);

                    // Copy the file in a buffered way
                    byte[] buffer = new byte[1024];
                    int read;
                    while ((read = in.read(buffer)) != -1) {
                        out.write(buffer, 0, read);
                    }
                } catch (java.io.IOException e) {
                    // Log the error for debugging
                    android.util.Log.e("CopyDevLibs", "Failed to copy asset file: " + filename, e);
                    throw new java.io.IOException("Failed to copy " + filename, e); // Rethrow to be caught by the outer catch block
                } finally {
                    // Ensure streams are closed
                    if (in != null) {
                        try {
                            in.close();
                        } catch (java.io.IOException e) {
                            // ignore
                        }
                    }
                    if (out != null) {
                        try {
                            out.flush();
                            out.close();
                        } catch (java.io.IOException e) {
                            // ignore
                        }
                    }
                }
            }

            // If the loop completes without throwing an exception, it was successful
            android.widget.Toast.makeText(requireActivity(), "Dev libs copied successfully!", android.widget.Toast.LENGTH_SHORT).show();

        } catch (java.io.IOException e) {
            // If any file fails, show a general error message
            android.util.Log.e("CopyDevLibs", "An error occurred during asset copying.", e);
            android.widget.Toast.makeText(requireActivity(), "Error copying dev libs.", android.widget.Toast.LENGTH_SHORT).show();
        }
    }


    private void openTerminal() {
        Intent intent = new Intent(getContext(), TerminalActivity.class);
        startActivity(intent);
    }


    // Show dialog to inform user about the import process
    private void showImportInfoDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(getContext());
        builder.setTitle("Import Container");
        builder.setMessage("This option will allow you to restore an exported container. To proceed, click OK and select your 'xuser-' directory. " +
                "The container's settings will need to be configured after a successful import, but all files and shortcuts should be restored if you are restoring a real container. " +
                "Beware, the directory you select will be copied into the app's storage directory, so be sure you have enough space. You can delete your copy afterward.");
        builder.setPositiveButton("OK", (dialog, which) -> {
            openFilePicker(); // Proceed to file picker
        });
        builder.setNegativeButton("Cancel", (dialog, which) -> dialog.dismiss());
        builder.show();
    }

    // Show confirmation dialog before importing the selected container
    private void showImportConfirmationDialog(Uri uri, File importDir) {
        AlertDialog.Builder builder = new AlertDialog.Builder(getContext());
        builder.setTitle("Confirm Import");
        builder.setMessage("You selected: " + importDir.getPath() + ". Proceed to import the container?");
        builder.setPositiveButton("Import", (dialog, which) -> {
            importContainer(uri); // Proceed with the import
        });
        builder.setNegativeButton("Cancel", (dialog, which) -> dialog.dismiss());
        builder.show();
    }

    private void openFilePicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivityForResult(intent, REQUEST_CODE_IMPORT_CONTAINER);
    }


    private void importContainer(Uri uri) {
        if (uri == null) return;

        // Get the directory path directly from the Uri using FileUtils
        File importDir = FileUtils.getFileFromUri(getContext(), uri);
        if (importDir == null || !importDir.isDirectory()) {
            AppUtils.showToast(getContext(), "Invalid container directory.");
            return;
        }

        preloaderDialog.show(R.string.importing_container);

        // Capture the activity up-front: the fragment can be detached while the
        // background import (which may take minutes) is still running.
        android.app.Activity importActivity = getActivity();
        if (importActivity == null) {
            preloaderDialog.close();
            return;
        }

        // Run the import operation on a background thread
        new Thread(() -> {
            try {
                // Now use the import directory directly for importing the container
                manager.importContainer(importDir, () -> {
                    // This callback runs when the import operation completes
                    importActivity.runOnUiThread(() -> {
                        // Load containers and close preloader dialog on the UI thread
                        if (!isAdded()) return;
                        loadContainersList();
                        AppUtils.showToast(getContext(), "Container imported successfully.");
                        preloaderDialog.close(); // Move this inside the callback
                    });
                });
            } catch (Exception e) {
                importActivity.runOnUiThread(() -> {
                    preloaderDialog.close(); // Ensure dialog closes on error
                    AppUtils.showToast(getContext(), "Error importing container: " + e.getMessage());
                });
            }
        }).start();
    }


    private boolean copyDocumentFileToDirectory(DocumentFile sourceDir, File targetDir) {
        if (!sourceDir.isDirectory()) return false;

        for (DocumentFile file : sourceDir.listFiles()) {
            File targetFile = new File(targetDir, file.getName());
            if (file.isDirectory()) {
                if (!targetFile.mkdirs()) return false;
                if (!copyDocumentFileToDirectory(file, targetFile)) return false;
            } else {
                try (InputStream in = getContext().getContentResolver().openInputStream(file.getUri());
                     OutputStream out = new FileOutputStream(targetFile)) {
                    byte[] buffer = new byte[1024];
                    int len;
                    while ((len = in.read(buffer)) > 0) {
                        out.write(buffer, 0, len);
                    }
                } catch (IOException e) {
                    return false;
                }
            }
        }
        return true;
    }



    private void toggleBigPictureMode() {
        // Start BigPictureActivity without passing shortcut data explicitly
        Intent intent = new Intent(getContext(), BigPictureActivity.class);
        startActivity(intent);
        getActivity().overridePendingTransition(R.anim.fade_in, R.anim.fade_out);
    }

    @Nullable
    private Shortcut getFavoriteShortcut() {
        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(getContext());
        String uuid = sp.getString(PREF_FAVORITE_UUID, null);
        if (uuid == null || uuid.isEmpty()) return null;

        if (manager == null) manager = new ContainerManager(getContext());
        for (Shortcut sc : manager.loadShortcuts()) {
            if (uuid.equals(sc.getExtra("uuid"))) return sc;
        }
        return null;
    }

    private void setFavorite(@NonNull Shortcut sc) {
        if (sc.getExtra("uuid").isEmpty()) sc.genUUID();
        PreferenceManager.getDefaultSharedPreferences(getContext())
                .edit().putString(PREF_FAVORITE_UUID, sc.getExtra("uuid")).apply();
        requireActivity().invalidateOptionsMenu();
    }

    private void clearFavorite() {
        PreferenceManager.getDefaultSharedPreferences(getContext())
                .edit().remove(PREF_FAVORITE_UUID).apply();
        requireActivity().invalidateOptionsMenu();
    }


    private class ContainersAdapter extends RecyclerView.Adapter<ContainersAdapter.ViewHolder> {
        private final List<Container> data;

        private class ViewHolder extends RecyclerView.ViewHolder {
            private final ImageView runButton; // Changed to ImageButton
            private final ImageView menuButton; // Changed to ImageButton
            private final ImageView imageView;
            private final TextView title;

            private static final String PREF_FAVORITE_UUID = "favorite_uuid";

            private ViewHolder(View view) {
                super(view);
                this.runButton = view.findViewById(R.id.BTRun); // Find by correct ID
                this.imageView = view.findViewById(R.id.ImageView);
                this.title = view.findViewById(R.id.TVTitle);
                this.menuButton = view.findViewById(R.id.BTMenu);
            }
        }

        public ContainersAdapter(List<Container> data) {
            this.data = data;
        }

        @Override
        public final ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            return new ViewHolder(LayoutInflater.from(parent.getContext()).inflate(R.layout.container_list_item, parent, false));
        }

        @Override
        public void onViewRecycled(@NonNull ViewHolder holder) {
            holder.runButton.setOnClickListener(null); // Remove listeners
            holder.menuButton.setOnClickListener(null); // Remove listeners
            super.onViewRecycled(holder);
        }

        @Override
        public void onBindViewHolder(final ViewHolder holder, int position) {
            final Container item = data.get(position); // Use 'item' instead of undefined 'container'
            holder.imageView.setImageResource(R.drawable.icon_container);
            holder.title.setText(item.getName());

            holder.runButton.setOnClickListener(view -> runContainer(item)); // Correct item reference

            holder.menuButton.setOnClickListener(view -> showListItemMenu(view, item));
        }

        @Override
        public final int getItemCount() {
            return data.size();
        }

        private void runContainer(Container container) {
            proceedWithLaunch(container);
        }
        private void proceedWithLaunch(Container container) {
            final Context context = getContext();

            if (!XrActivity.isEnabled(getContext())) {
                Intent intent = new Intent(context, XServerDisplayActivity.class);
                intent.putExtra("container_id", container.id);
                requireActivity().startActivity(intent);
            } else {
                XrActivity.openIntent(getActivity(), container.id, null);
            }
        }

        private void showListItemMenu(View anchorView, Container container) {
            final Context context = getContext();
            PopupMenu listItemMenu = new PopupMenu(context, anchorView);
            listItemMenu.inflate(R.menu.container_popup_menu);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) listItemMenu.setForceShowIcon(true);

            listItemMenu.setOnMenuItemClickListener((menuItem) -> {
                switch (menuItem.getItemId()) {
                    case R.id.container_edit:
                        FragmentManager fragmentManager = getParentFragmentManager();
                        fragmentManager.beginTransaction()
                                .setCustomAnimations(R.anim.slide_in_up, R.anim.slide_out_down, R.anim.slide_in_down, R.anim.slide_out_up)
                                .addToBackStack(null)
                                .replace(R.id.FLFragmentContainer, new ContainerDetailFragment(container.id))
                                .commit();
                        break;
                    case R.id.container_duplicate:
                        ContentDialog.confirm(getContext(), R.string.do_you_want_to_duplicate_this_container, () -> {
                            preloaderDialog.show(R.string.duplicating_container);
                            manager.duplicateContainerAsync(container, () -> {
                                preloaderDialog.close();
                                loadContainersList();
                            });
                        });
                        break;
                    case R.id.container_remove:
                        ContentDialog.confirm(getContext(), R.string.do_you_want_to_remove_this_container, () -> {
                            preloaderDialog.show(R.string.removing_container);
                            manager.removeContainerAsync(container, () -> {
                                // Read only this container's existing desktop entries. A full
                                // mounted-drive discovery is unnecessary during deletion.
                                for (Shortcut shortcut : manager.loadShortcutsForContainer(container))
                                    ShortcutsFragment.disableShortcutOnScreen(context, shortcut);
                            }, () -> {
                                preloaderDialog.close();
                                loadContainersList();
                            });
                        });
                        break;
                    case R.id.container_info:
                        (new StorageInfoDialog(getActivity(), container)).show();
                        break;
                    case R.id.container_reconfigure:
                        ContentDialog.confirm(getContext(), R.string.do_you_want_to_reconfigure_wine, () -> {
                            new File(container.getRootDir(), ".wine/.update-timestamp").delete();
                        });
                        break;
                    case R.id.container_export:
                        exportContainer(container);
                        break;
                    case R.id.container_export_config:
                        showCommunityConfigExportDialog(container);
                        break;
                }
                return true;
            });
            listItemMenu.show();
        }

        private void exportContainer(Container container) {
            File backupDir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Winlator/Backups/Containers");
            preloaderDialog.show(R.string.exporting_container);

            manager.exportContainer(container, () -> {
                preloaderDialog.close(); // Ensure the dialog is closed after operation
                showToast("Container exported successfully to " + backupDir.getPath());
            });
        }

        private void showCommunityConfigExportDialog(Container container) {
            Context context = requireContext();
            LinearLayout fields = new LinearLayout(context);
            fields.setOrientation(LinearLayout.VERTICAL);
            int pad = dp(20);
            fields.setPadding(pad, pad / 2, pad, 0);

            CommunityConfigManager.DeviceProfile deviceProfile =
                    CommunityConfigManager.getCurrentDeviceProfile();
            // Never pre-fill this from the generic container name: typing it is
            // the deliberate confirmation that this is the actual game title.
            EditText game = configField(fields, "Game name (required)", "");
            EditText discord = configField(fields, "Your @ on Discord (required)", "");
            EditText fps = configField(fields, "Expected average FPS (required)", "");
            EditText device = configField(fields, "Phone model (detected, fixed)",
                    deviceProfile.fullLabel());
            device.setFocusable(false);
            device.setCursorVisible(false);
            device.setLongClickable(false);
            device.setKeyListener(null);
            EditText notes = configField(fields,
                    "Notes: required fix, preset or variable (optional)", "");
            notes.setMinLines(2);

            TextView compatibilityTitle = new TextView(context);
            compatibilityTitle.setText("Compatibility status (required)");
            compatibilityTitle.setTextSize(15);
            compatibilityTitle.setPadding(0, dp(12), 0, dp(4));
            fields.addView(compatibilityTitle);
            RadioGroup compatibility = new RadioGroup(context);
            compatibility.setOrientation(RadioGroup.VERTICAL);
            addCompatibilityChoice(compatibility, "Boots but crashes");
            addCompatibilityChoice(compatibility, "Menu only");
            addCompatibilityChoice(compatibility, "Playable");
            fields.addView(compatibility, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            ScrollView scroll = new ScrollView(context);
            scroll.addView(fields);
            AlertDialog dialog = new AlertDialog.Builder(context)
                    .setTitle("Export Community Config")
                    .setMessage("Fill in the game name, your Discord @, expected average FPS and compatibility status. "
                            + "The detected phone model cannot be changed. In Notes, mention any required fix, preset or variable; otherwise leave it blank.")
                    .setView(scroll)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton("Export", null)
                    .create();
            dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                    .setOnClickListener(v -> {
                        if (game.getText().toString().trim().isEmpty()) {
                            game.setError("Enter the game's real name"); return;
                        }
                        if (game.getText().toString().trim().matches("(?i)container[- _]*\\d+")) {
                            game.setError("Replace the generic container name with the game name"); return;
                        }
                        if (discord.getText().toString().trim().isEmpty()) {
                            discord.setError("Enter your Discord @"); return;
                        }
                        if (fps.getText().toString().trim().isEmpty()) {
                            fps.setError("Enter the expected average FPS"); return;
                        }
                        RadioButton selectedCompatibility = compatibility.findViewById(
                                compatibility.getCheckedRadioButtonId());
                        if (selectedCompatibility == null) {
                            Toast.makeText(context, "Select a compatibility status",
                                    Toast.LENGTH_SHORT).show();
                            compatibilityTitle.setTextColor(Color.rgb(255, 107, 107));
                            return;
                        }
                        dialog.dismiss();
                        CommunityConfigManager.Metadata metadata = new CommunityConfigManager.Metadata();
                        metadata.gameName = game.getText().toString();
                        metadata.fps = fps.getText().toString();
                        metadata.device = deviceProfile.device;
                        metadata.model = deviceProfile.model;
                        metadata.soc = deviceProfile.soc;
                        metadata.gpu = deviceProfile.gpu;
                        metadata.discord = discord.getText().toString();
                        // Discord is the public author identity; a separate real-name
                        // field is unnecessary, but keep the legacy manifest key useful.
                        metadata.author = metadata.discord;
                        metadata.notes = notes.getText().toString();
                        metadata.compatibility = String.valueOf(
                                selectedCompatibility.getTag());
                        if (metadata.gpu.isEmpty()) metadata.gpu = GPUInformation.getRenderer();
                        ActivityManager.MemoryInfo memory = new ActivityManager.MemoryInfo();
                        ActivityManager activityManager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
                        if (activityManager != null) activityManager.getMemoryInfo(memory);
                        metadata.ram = String.format(Locale.US, "%.1f GB", memory.totalMem / 1073741824.0);
                        exportCommunityConfig(container, metadata);
                    }));
            dialog.show();
        }

        private void addCompatibilityChoice(RadioGroup group, String label) {
            RadioButton button = new RadioButton(group.getContext());
            button.setId(View.generateViewId());
            button.setText(label);
            button.setTag(label);
            group.addView(button, new RadioGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        private EditText configField(LinearLayout parent, String hint, String value) {
            EditText input = new EditText(parent.getContext());
            input.setHint(hint);
            input.setText(value);
            input.setSingleLine(!hint.startsWith("Notes"));
            parent.addView(input, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return input;
        }

        private void exportCommunityConfig(Container container, CommunityConfigManager.Metadata metadata) {
            Context appContext = requireContext().getApplicationContext();
            Activity hostActivity = getActivity();
            preloaderDialog.show(R.string.exporting_container);
            Executors.newSingleThreadExecutor().execute(() -> {
                try {
                    File output = CommunityConfigManager.exportConfig(appContext, container, metadata);
                    if (hostActivity == null || hostActivity.isFinishing()) return;
                    hostActivity.runOnUiThread(() -> {
                        preloaderDialog.close();
                        if (!isAdded()) return;
                        new AlertDialog.Builder(requireContext())
                                .setTitle("Community Config exported")
                                .setMessage("Saved to:\n" + output.getAbsolutePath())
                                .setPositiveButton("Share to Discord", (dialog, which) -> shareConfig(output))
                                .setNegativeButton(android.R.string.ok, null)
                                .show();
                    });
                } catch (Exception e) {
                    Log.e("ContainersFragment", "Unable to export community config", e);
                    if (hostActivity != null && !hostActivity.isFinishing()) hostActivity.runOnUiThread(() -> {
                        preloaderDialog.close();
                        if (isAdded()) ContentDialog.alert(requireContext(),
                                "Could not export this configuration.", null);
                    });
                }
            });
        }

        private void shareConfig(File output) {
            Uri uri = FileProvider.getUriForFile(requireContext(),
                    requireContext().getPackageName() + ".fileprovider", output);
            Intent share = new Intent(Intent.ACTION_SEND);
            share.setType("application/zip");
            share.putExtra(Intent.EXTRA_STREAM, uri);
            share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(share, "Share community config"));
        }

        private void showToast(String message) {
            if (getActivity() != null) {
                getActivity().runOnUiThread(() -> {
                    Toast.makeText(getActivity(), message, Toast.LENGTH_SHORT).show();
                });
            }
        }
    }

    private void handleFavoriteClick() {
        Shortcut fav = getFavoriteShortcut();
        if (fav == null) {
            new AlertDialog.Builder(getContext())
                    .setTitle("Favorite")
                    .setMessage("No favorite assigned. Choose one now?")
                    .setPositiveButton("Choose", (d, w) -> {
                        getParentFragmentManager().beginTransaction()
                                .setCustomAnimations(R.anim.slide_in_up, R.anim.slide_out_down,
                                        R.anim.slide_in_down, R.anim.slide_out_up)
                                .addToBackStack(null)
                                .replace(R.id.FLFragmentContainer, new ShortcutsFragment())
                                .commit();
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
        } else {
            runShortcut(fav);
        }
    }

    private void applyFavoriteIcon() {
        if (favoriteItem == null) return;

        Shortcut hit = getFavoriteShortcut();
        Drawable drawable;
        if (hit != null && hit.icon != null) {
            int size = dp(24);
            Bitmap scaled = Bitmap.createScaledBitmap(hit.icon, size, size, true);
            drawable = new BitmapDrawable(getResources(), scaled);
            drawable.setTintList(null);
        } else {
            drawable = AppCompatResources.getDrawable(requireContext(), R.drawable.ic_star_border);
            drawable.mutate().setColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN);
        }

        if (favoriteActionView != null) {
            favoriteActionView.setImageDrawable(drawable);
        } else {
            favoriteItem.setIcon(drawable);
        }
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
