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
import com.winlator.cmod.container.PendingConfigImport;
import com.winlator.cmod.contents.DependencyImportSession;
import android.widget.ProgressBar;
import android.content.DialogInterface;
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

    private PendingConfigImport pendingImport;
    private AlertDialog importDialog, resolutionDialog;
    private TextView importProgressText;
    private ProgressBar importProgressBar;
    private boolean importDialogHidden, showingResolution;
    private boolean observedImportApplying;
    private final PendingConfigImport.Observer importObserver = () -> {
        View view = getView();
        if (view != null) view.post(this::refreshImportProgress);
    };

    private MenuItem favoriteItem;

    private static final String PREF_FAVORITE_UUID = "favorite_uuid";

    private ImageView favoriteActionView;


    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(true);
        preloaderDialog = new PreloaderDialog(getActivity());
        pendingImport = PendingConfigImport.get(requireContext());
    }

    @Override
    public void onDestroyView() {
        pendingImport.detach(importObserver);
        pendingImport.pause();
        importDialogHidden = true;
        if (importDialog != null) importDialog.dismiss();
        if (resolutionDialog != null) resolutionDialog.dismiss();
        importDialog = resolutionDialog = null;
        showingResolution = false;
        super.onDestroyView();
        preloaderDialog.close();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        pendingImport.observe(importObserver);
        observedImportApplying |= pendingImport.state() == PendingConfigImport.State.APPLYING;
        importDialogHidden = false;
        manager = new ContainerManager(getContext());
        loadContainersList();
        MainActivity.setTabTitle(requireActivity(), R.string.containers);
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
        frameLayout.findViewById(R.id.BTCreateContainer).setOnClickListener(v ->
                new AlertDialog.Builder(requireContext()).setTitle(R.string.container_add)
                        .setItems(new String[]{getString(R.string.container_import_zip),
                                getString(R.string.container_import_community), getString(R.string.container_new)},
                                (dialog, which) -> {
                                    if (which == 0) importCommunityZip();
                                    else if (which == 1) getParentFragmentManager().beginTransaction()
                                            .addToBackStack(null)
                                            .replace(R.id.FLFragmentContainer, new CommunityConfigsFragment()).commit();
                                    else createContainer();
                                }).show());
        recyclerView.setLayoutManager(new LinearLayoutManager(recyclerView.getContext()));
        recyclerView.addItemDecoration(new DividerItemDecoration(recyclerView.getContext(), DividerItemDecoration.VERTICAL));
        return frameLayout;
    }

    private void createContainer() {
        if (!ImageFs.find(requireContext()).isValid()) return;
        getParentFragmentManager().beginTransaction()
                .setCustomAnimations(R.anim.slide_in_up, R.anim.slide_out_down, R.anim.slide_in_down, R.anim.slide_out_up)
                .addToBackStack(null)
                .replace(R.id.FLFragmentContainer, new ContainerDetailFragment()).commit();
    }

    private void importCommunityZip() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/zip", "application/x-zip-compressed",
                "application/octet-stream"});
        startActivityForResult(intent, REQUEST_CODE_IMPORT_COMMUNITY_CONFIG);
    }

    private void loadContainersList() {
        ArrayList<Container> containers = manager.getContainers();
        recyclerView.setAdapter(new ContainersAdapter(containers));
        emptyTextView.setVisibility(containers.isEmpty() ? View.VISIBLE : View.GONE);
    }


    @Override
    public void onResume() {
        super.onResume();
        // Clear toolbar actions inherited from another tab.
        requireActivity().invalidateOptionsMenu();
        if (pendingImport != null) { importDialogHidden = false; refreshImportProgress(); }
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        menu.clear();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem menuItem) {
        switch (menuItem.getItemId()) {
            case R.id.containers_menu_add:
                createContainer();
                return true;
            case R.id.containers_menu_import_config:
                importCommunityZip();
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
                            .setTitle(R.string.favorite)
                            .setMessage("No favorite assigned. Choose one now?")
                            .setPositiveButton("Choose", (d, w) -> {
                                getParentFragmentManager().beginTransaction()
                                        .setCustomAnimations(R.anim.slide_in_up, R.anim.slide_out_down,
                                                R.anim.slide_in_down, R.anim.slide_out_up)
                                        .addToBackStack(null)
                                        .replace(R.id.FLFragmentContainer, new ShortcutsFragment())
                                        .commit();
                            })
                            .setNegativeButton(R.string.cancel, null)
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
        Activity hostActivity = getActivity();
        if (ContentOperationRegistry.hasActiveOperations()) {
            Toast.makeText(requireContext(),
                    getString(R.string.community_wait_contents),
                    Toast.LENGTH_LONG).show();
            ContentOperationRegistry.runWhenIdle(() -> {
                if (hostActivity == null || hostActivity.isFinishing()) return;
                hostActivity.runOnUiThread(() -> {
                    if (isAdded()) readCommunityConfig(manifestReader);
                });
            });
            return;
        }
        if (pendingImport.isRunning()) {
            importDialogHidden = false;
            refreshImportProgress();
            return;
        }
        showingResolution = true;
        if (resolutionDialog != null) { resolutionDialog.dismiss(); resolutionDialog = null; }
        if (importDialog != null) { importDialog.dismiss(); importDialog = null; }
        preloaderDialog.show(R.string.importing_container);
        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                JSONObject manifest = manifestReader.read();
                pendingImport.offer(manifest);
                if (hostActivity == null || hostActivity.isFinishing() || hostActivity.isDestroyed()) return;
                hostActivity.runOnUiThread(() -> {
                    preloaderDialog.close();
                    if (!isAdded() || getView() == null) return;
                    showingResolution = false;
                    importDialogHidden = false;
                    pendingImport.start();
                });
            } catch (Exception e) {
                Log.e("ContainersFragment", "Unable to read community config", e);
                if (hostActivity != null && !hostActivity.isFinishing()) hostActivity.runOnUiThread(() -> {
                    preloaderDialog.close();
                    if (isAdded()) ContentDialog.alert(requireContext(), R.string.community_invalid_config, null);
                });
            }
        });
    }

    private void refreshImportProgress() {
        if (!isAdded() || getView() == null || importDialogHidden) return;
        PendingConfigImport.State state = pendingImport.state();
        if (state == PendingConfigImport.State.IDLE || state == PendingConfigImport.State.CANCELLED) {
            if (importDialog != null) { importDialog.dismiss(); importDialog = null; }
            if (observedImportApplying && state == PendingConfigImport.State.IDLE) {
                manager.reloadContainers();
                loadContainersList();
            }
            observedImportApplying = false;
            return;
        }
        if (state == PendingConfigImport.State.READY) {
            if (importDialog != null) { importDialog.dismiss(); importDialog = null; }
            if (!showingResolution) {
                showingResolution = true;
                showImportDependencyReview(pendingImport.manifest(), pendingImport.resolutions(), null);
            }
            return;
        }
        if (importDialog == null) {
            LinearLayout body = new LinearLayout(requireContext());
            body.setOrientation(LinearLayout.VERTICAL);
            int padding = (int)com.winlator.cmod.core.UnitUtils.dpToPx(24);
            body.setPadding(padding, padding, padding, padding);
            importProgressText = new TextView(requireContext());
            importProgressText.setTextSize(16);
            body.addView(importProgressText, new LinearLayout.LayoutParams(-1, -2));
            importProgressBar = new ProgressBar(requireContext(), null, android.R.attr.progressBarStyleHorizontal);
            importProgressBar.setMax(100);
            body.addView(importProgressBar, new LinearLayout.LayoutParams(-1, -2));
            importDialog = new AlertDialog.Builder(requireContext()).setTitle(R.string.community_import_dependencies)
                    .setView(body).setPositiveButton(R.string.download_pause, null)
                    .setNegativeButton(R.string.cancel, (d, w) -> abortCommunityImport()).create();
            importDialog.setCanceledOnTouchOutside(false);
            importDialog.setOnCancelListener(d -> { importDialogHidden = true; pendingImport.pause(); importDialog = null; });
            importDialog.show();
            importDialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(v -> {
                if (pendingImport.isRunning()) pendingImport.pause();
                else { showingResolution = false; pendingImport.start(); }
            });
        }
        boolean running = pendingImport.isRunning();
        boolean applying = state == PendingConfigImport.State.APPLYING;
        observedImportApplying |= applying;
        boolean stopping = state == PendingConfigImport.State.STOPPING;
        DependencyImportSession.Stage phase = pendingImport.stage();
        boolean installing = running && phase == DependencyImportSession.Stage.INSTALLING;
        importDialog.getButton(DialogInterface.BUTTON_POSITIVE).setText(running ? R.string.download_pause : R.string.download_resume);
        importDialog.getButton(DialogInterface.BUTTON_POSITIVE).setEnabled(!stopping && !installing);
        importDialog.getButton(DialogInterface.BUTTON_NEGATIVE).setEnabled(!installing);
        importDialog.getButton(DialogInterface.BUTTON_POSITIVE).setVisibility(applying ? View.GONE : View.VISIBLE);
        importDialog.getButton(DialogInterface.BUTTON_NEGATIVE).setVisibility(applying ? View.GONE : View.VISIBLE);
        importDialog.setCancelable(!applying);
        importProgressBar.setIndeterminate(running && phase != DependencyImportSession.Stage.DOWNLOADING);
        importProgressBar.setProgress(pendingImport.percent());
        int label = applying ? R.string.creating_container
                : state == PendingConfigImport.State.PAUSED ? R.string.community_import_paused
                : state == PendingConfigImport.State.FAILED ? R.string.community_import_retry
                : stopping ? R.string.download_stopping
                : phase == DependencyImportSession.Stage.CATALOG ? R.string.community_import_catalog
                : phase == DependencyImportSession.Stage.LOCAL ? R.string.community_import_local
                : phase == DependencyImportSession.Stage.QUEUED ? R.string.download_queued
                : installing ? R.string.installing_content : R.string.downloading_file;
        String text = getString(label);
        if (!pendingImport.name().isEmpty()) text += "\n" + pendingImport.name();
        if (state == PendingConfigImport.State.FAILED && pendingImport.error() != null) text += "\n" + pendingImport.error();
        if (running && phase == DependencyImportSession.Stage.DOWNLOADING) text += "\n" + pendingImport.percent() + "%";
        importProgressText.setText(text);
    }

    private void abortCommunityImport() {
        importDialogHidden = true;
        showingResolution = false;
        pendingImport.cancel();
        if (importDialog != null) { importDialog.dismiss(); importDialog = null; }
    }

    private void showImportDependencyReview(JSONObject manifest,
            List<CommunityConfigManager.ContentResolution> resolutions,
            List<com.winlator.cmod.contents.ContentProfile> choices) {
        try {
            List<CommunityConfigManager.DependencyReview> rows =
                    CommunityConfigManager.reviewDependencies(manifest, resolutions, choices);
            long missing = rows.stream().filter(row -> row.missing).count();
            if (choices == null && missing == 0) {
                resolveImportedContents(manifest, resolutions, new ArrayList<>(), 0);
                return;
            }
            StringBuilder text = new StringBuilder(getString(choices == null
                    ? R.string.community_review_missing : R.string.community_review_ready));
            text.append("\n\n").append(getString(R.string.community_review_counts, rows.size() - (int)missing, (int)missing));
            for (CommunityConfigManager.DependencyReview row : rows) {
                text.append("\n\n").append(importRoleLabel(row.role)).append("\n");
                if (row.missing) text.append(getString(R.string.community_review_not_found, row.requested));
                else if (row.substituted) text.append(getString(R.string.community_review_substitution, row.requested, row.selected));
                else text.append(getString(R.string.community_review_available, row.selected));
            }
            TextView details = new TextView(requireContext());
            int padding = (int)com.winlator.cmod.core.UnitUtils.dpToPx(24);
            details.setPadding(padding, padding / 2, padding, padding / 2);
            details.setTextSize(15);
            details.setText(text);
            details.setTextIsSelectable(true);
            ScrollView scroll = new ScrollView(requireContext());
            scroll.addView(details);
            AlertDialog dialog = new AlertDialog.Builder(requireContext()).setTitle(R.string.community_review_title)
                    .setView(scroll)
                    .setPositiveButton(choices == null ? R.string.community_review_choose : R.string.community_review_create,
                            (d, which) -> {
                                if (choices == null) resolveImportedContents(manifest, resolutions, new ArrayList<>(), 0);
                                else installImportedConfiguration(manifest, resolutions, choices);
                            })
                    .setNegativeButton(R.string.cancel, (d, which) -> abortCommunityImport()).create();
            showCommunityDialog(dialog);
        } catch (Exception failure) {
            Log.e("ContainersFragment", "Unable to review dependencies", failure);
            showImportApplyFailure(R.string.community_apply_failed);
        }
    }

    private String importRoleLabel(String role) {
        switch (role) {
            case "wineRuntime": return getString(R.string.wine_and_proton);
            case "box64": return "Box64";
            case "fexcore": return "FEXCore";
            case "dxvk": return "DXVK";
            case "vkd3d": return "VKD3D";
            case "graphicsDriver": return getString(R.string.graphics_driver);
            default: return getString(R.string.wrappers);
        }
    }

    private void resolveImportedContents(JSONObject manifest,
                                         List<CommunityConfigManager.ContentResolution> resolutions,
                                         List<com.winlator.cmod.contents.ContentProfile> choices,
                                         int index) {
        resolveImportedContents(manifest, resolutions, choices, index, false);
    }

    private void resolveImportedContents(JSONObject manifest,
            List<CommunityConfigManager.ContentResolution> resolutions,
            List<com.winlator.cmod.contents.ContentProfile> choices, int index, boolean chooseAlternative) {
        if (index >= resolutions.size()) {
            showImportDependencyReview(manifest, resolutions, choices);
            return;
        }
        CommunityConfigManager.ContentResolution current = resolutions.get(index);
        if (current.isMissing() && !chooseAlternative
                && current.failure != DependencyImportSession.Failure.UNAVAILABLE) {
            int message = current.failure == DependencyImportSession.Failure.CATALOG ? R.string.community_catalog_incomplete
                    : current.failure == DependencyImportSession.Failure.DOWNLOAD ? R.string.community_dependency_download_failed
                    : R.string.community_dependency_invalid;
            AlertDialog dialog = new AlertDialog.Builder(requireContext())
                    .setTitle(R.string.runtime_content_missing)
                    .setMessage(getString(message, current.requestedName()))
                    .setPositiveButton(R.string.community_retry_action, (d, w) -> {
                        showingResolution = false;
                        pendingImport.start();
                    })
                    .setNegativeButton(R.string.community_choose_alternative,
                            (d, w) -> resolveImportedContents(manifest, resolutions, choices, index, true))
                    .setNeutralButton(R.string.cancel, (d, w) -> abortCommunityImport()).create();
            showCommunityDialog(dialog);
            return;
        }
        if (current.exact != null) {
            choices.add(current.exact);
            resolveImportedContents(manifest, resolutions, choices, index + 1);
            return;
        }

        String message = getString(R.string.runtime_required_by_config, current.requestedName());
        String role = current.reference.optString("role", "");
        if ("graphicsDriver".equals(role) || "wrapper".equals(role)) {
            // A missing external driver/wrapper must not silently turn into
            // another build or an unavailable saved identifier.
            AlertDialog dialog = new AlertDialog.Builder(requireContext())
                    .setTitle(R.string.runtime_content_missing)
                    .setMessage(message + "\n\n" + getString(R.string.runtime_exact_unavailable))
                    .setPositiveButton("graphicsDriver".equals(role)
                            ? R.string.runtime_use_system_driver : R.string.runtime_use_builtin_wrapper,
                            (dialogInterface, which) -> {
                                choices.add(null);
                                resolveImportedContents(manifest, resolutions, choices, index + 1);
                            })
                    .setNegativeButton(R.string.cancel, (d, w) -> abortCommunityImport())
                    .create();
            showCommunityDialog(dialog);
            return;
        }
        if (current.similar != null) {
            message += "\n\n" + getString(R.string.runtime_closest_installed,
                    com.winlator.cmod.contents.ContentsManager.getEntryName(current.similar), current.similarity);
            AlertDialog dialog = new AlertDialog.Builder(requireContext())
                    .setTitle(R.string.runtime_content_missing)
                    .setMessage(message)
                    .setPositiveButton(R.string.runtime_use_closest, (dialogInterface, which) -> {
                        choices.add(current.similar);
                        resolveImportedContents(manifest, resolutions, choices, index + 1);
                    })
                    .setNegativeButton(R.string.runtime_use_default, (dialogInterface, which) -> {
                        choices.add(null);
                        resolveImportedContents(manifest, resolutions, choices, index + 1);
                    })
                    .setNeutralButton(R.string.cancel, (d, w) -> abortCommunityImport())
                    .create();
            showCommunityDialog(dialog);
        } else if ("wineRuntime".equals(current.reference.optString("role", ""))
                && !current.requestedName().toLowerCase(java.util.Locale.ENGLISH).contains("arm64ec")
                && !current.requestedName().toLowerCase(java.util.Locale.ENGLISH).matches(".*(^|[-_])x86($|[-_]).*")) {
            AlertDialog dialog = new AlertDialog.Builder(requireContext())
                    .setTitle(R.string.runtime_content_missing)
                    .setMessage(message + "\n\n" + getString(R.string.runtime_closest_builtin,
                            com.winlator.cmod.core.WineInfo.MAIN_WINE_VERSION.identifier()))
                    .setPositiveButton(R.string.runtime_use_closest, (dialogInterface, which) -> {
                        // Null intentionally maps wineRuntime to the bundled default.
                        choices.add(null);
                        resolveImportedContents(manifest, resolutions, choices, index + 1);
                    })
                    .setNegativeButton(R.string.cancel, (d, w) -> abortCommunityImport())
                    .create();
            showCommunityDialog(dialog);
        } else {
            AlertDialog dialog = new AlertDialog.Builder(requireContext())
                    .setTitle(R.string.runtime_content_missing)
                    .setMessage(message + "\n\n" + getString(R.string.runtime_no_compatible_installed))
                    .setPositiveButton(R.string.runtime_use_default, (dialogInterface, which) -> {
                        choices.add(null);
                        resolveImportedContents(manifest, resolutions, choices, index + 1);
                    })
                    .setNegativeButton(R.string.cancel, (d, w) -> abortCommunityImport())
                    .create();
            showCommunityDialog(dialog);
        }
    }

    private void showCommunityDialog(AlertDialog dialog) {
        resolutionDialog = dialog;
        dialog.setOnCancelListener(d -> abortCommunityImport());
        dialog.show();
        if (dialog.getWindow() != null) {
            // Purple-bordered background + blur, matching the artwork dialogs.
            // Light mode uses a lighter dim so the blur does not go too dark.
            boolean darkMode = AppUtils.isDarkMode(requireContext());
            dialog.getWindow().setBackgroundDrawableResource(darkMode
                    ? R.drawable.artwork_dialog_background
                    : R.drawable.artwork_dialog_background_light);
            if (!darkMode) dialog.getWindow().setDimAmount(0.25f);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                dialog.getWindow().setBackgroundBlurRadius(48);
            }
            dialog.getWindow().addFlags(
                    android.view.WindowManager.LayoutParams.FLAG_BLUR_BEHIND);
        }
    }

    private void installImportedConfiguration(JSONObject manifest,
                                              List<CommunityConfigManager.ContentResolution> resolutions,
                                              List<com.winlator.cmod.contents.ContentProfile> choices) {
        if (!pendingImport.beginApply()) return;
        refreshImportProgress();
        Context appContext = requireContext().getApplicationContext();
        ContainerManager targetManager = manager;
        android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
        ContentOperationRegistry.Token operation = ContentOperationRegistry.begin(ContentOperationRegistry.Kind.INSTALL);
        new Thread(() -> {
            try {
                CommunityConfigManager.validateImportSelections(appContext, manifest, resolutions, choices);
                JSONObject config = CommunityConfigManager.applyResolution(appContext, manifest, resolutions, choices);
                JSONObject metadata = manifest.optJSONObject("metadata");
                String gameName = metadata != null ? metadata.optString("gameName", "") : "";
                if (!gameName.trim().isEmpty()) config.put("name", gameName.trim());
                com.winlator.cmod.contents.ContentsManager contents =
                        new com.winlator.cmod.contents.ContentsManager(appContext);
                contents.syncContents();
                targetManager.createContainerAsync(config, contents, pendingImport.creationJournal(), created -> {
                    operation.close();
                    // Complete the retained operation even if this Fragment was destroyed.
                    if (created != null) pendingImport.complete();
                    else pendingImport.applyFailed();
                    if (!isAdded() || getView() == null) return;
                    showingResolution = created == null;
                    if (created == null) {
                        showImportApplyFailure(R.string.community_create_failed);
                    } else {
                        loadContainersList();
                        Toast.makeText(appContext, getString(R.string.community_import_complete), Toast.LENGTH_SHORT).show();
                    }
                });
            } catch (Exception failure) {
                operation.close();
                Log.e("ContainersFragment", "Unable to apply community config", failure);
                main.post(() -> {
                    pendingImport.applyFailed();
                    if (!isAdded() || getView() == null) return;
                    showImportApplyFailure(R.string.community_apply_failed);
                });
            }
        }, "ConfigImportApply").start();
    }

    private void showImportApplyFailure(int message) {
        showingResolution = true;
        if (importDialog != null) { importDialog.dismiss(); importDialog = null; }
        AlertDialog dialog = new AlertDialog.Builder(requireContext()).setMessage(message)
                .setPositiveButton(R.string.community_retry_action, (d, w) -> {
                    showingResolution = false;
                    pendingImport.start();
                })
                .setNegativeButton(R.string.cancel, (d, w) -> abortCommunityImport()).create();
        showCommunityDialog(dialog);
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
        builder.setPositiveButton(R.string.ok, (dialog, which) -> {
            openFilePicker(); // Proceed to file picker
        });
        builder.setNegativeButton(R.string.cancel, (dialog, which) -> dialog.dismiss());
        builder.show();
    }

    // Show confirmation dialog before importing the selected container
    private void showImportConfirmationDialog(Uri uri, File importDir) {
        AlertDialog.Builder builder = new AlertDialog.Builder(getContext());
        builder.setTitle("Confirm Import");
        builder.setMessage("You selected: " + importDir.getPath() + ". Proceed to import the container?");
        builder.setPositiveButton(R.string.dxvk_import_conf, (dialog, which) -> {
            importContainer(uri); // Proceed with the import
        });
        builder.setNegativeButton(R.string.cancel, (dialog, which) -> dialog.dismiss());
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
            CommunityConfigExportDialogFragment.show(getParentFragmentManager(), container.id);
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
                    .setTitle(R.string.favorite)
                    .setMessage("No favorite assigned. Choose one now?")
                    .setPositiveButton("Choose", (d, w) -> {
                        getParentFragmentManager().beginTransaction()
                                .setCustomAnimations(R.anim.slide_in_up, R.anim.slide_out_down,
                                        R.anim.slide_in_down, R.anim.slide_out_up)
                                .addToBackStack(null)
                                .replace(R.id.FLFragmentContainer, new ShortcutsFragment())
                                .commit();
                    })
                    .setNegativeButton(R.string.cancel, null)
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
