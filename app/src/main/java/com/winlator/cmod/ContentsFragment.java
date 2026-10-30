package com.winlator.cmod;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.preference.PreferenceManager;
import androidx.recyclerview.widget.DividerItemDecoration;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.winlator.cmod.R;
import com.winlator.cmod.container.Container;
import com.winlator.cmod.container.ContainerManager;
import com.winlator.cmod.contentdialog.ContentDialog;
import com.winlator.cmod.contentdialog.ContentInfoDialog;
import com.winlator.cmod.contentdialog.ContentUntrustedDialog;
import com.winlator.cmod.contents.ContentProfile;
import com.winlator.cmod.contents.ContentsManager;
import com.winlator.cmod.contents.Downloader;
import com.winlator.cmod.contents.ResumableDownload;
import com.winlator.cmod.contents.XclipseDriverManager;
import com.winlator.cmod.contents.CustomWrapperManager;
import com.winlator.cmod.contents.ExternalDownloadCatalog;
import com.winlator.cmod.core.AppUtils;
import com.winlator.cmod.core.ContentOperationRegistry;
import com.winlator.cmod.core.FileUtils;
import com.winlator.cmod.core.PreloaderDialog;
import com.winlator.cmod.core.WineInfo;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

public class ContentsFragment extends Fragment {
    public static final String ARG_INITIAL_CATEGORY = "initial_category";
    public static final String ARG_EMBEDDED = "embedded";
    public static final String ARG_LOCK_CATEGORY = "lock_category";
    private static final long REMOTE_REFRESH_INTERVAL_MS = 15L * 60L * 1000L;
    public static final int CATEGORY_XCLIPSE_DRIVERS = 100;
    public static final int CATEGORY_WRAPPERS = 101;
    public static final int CATEGORY_RUNTIMES = 102;
    private static final int[] CATEGORY_ORDER = {CATEGORY_RUNTIMES,
            ContentProfile.ContentType.CONTENT_TYPE_DXVK.ordinal(),
            ContentProfile.ContentType.CONTENT_TYPE_VKD3D.ordinal(),
            ContentProfile.ContentType.CONTENT_TYPE_BOX64.ordinal(),
            ContentProfile.ContentType.CONTENT_TYPE_WOWBOX64.ordinal(),
            ContentProfile.ContentType.CONTENT_TYPE_FEXCORE.ordinal(),
            CATEGORY_XCLIPSE_DRIVERS, CATEGORY_WRAPPERS};
    private static final int IMPORT_CONTENT = 0;
    private static final int IMPORT_DRIVER = 1;
    private static final int IMPORT_WRAPPER = 2;
    private RecyclerView recyclerView;
    private View emptyText;
    private ContentsManager manager;
    private ContentProfile.ContentType currentContentType = ContentProfile.ContentType.CONTENT_TYPE_WINE;
    private Spinner sContentType;
    private Button installButton;
    private int selectedCategory;
    private int importMode = IMPORT_CONTENT;
    private XclipseDriverManager driverManager;
    private CustomWrapperManager wrapperManager;
    private ExternalDownloadCatalog externalCatalog;
    private volatile List<ExternalDownloadCatalog.Item> remoteDrivers = new ArrayList<>();
    private volatile List<ExternalDownloadCatalog.Item> remoteWrappers = new ArrayList<>();
    private final ConcurrentHashMap<String, ContentProfile> pendingRemoteProfiles =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> pendingRemoteTransferKeys =
            new ConcurrentHashMap<>();

    private static final int TRANSFER_DOWNLOADING = 0;
    private static final int TRANSFER_INSTALLING = 1;
    private static final int TRANSFER_WAITING_CONFIRMATION = 2;
    private static final int TRANSFER_PAUSED = 3;
    private static final int TRANSFER_FAILED = 4;
    private static final int TRANSFER_STOPPING = 5;
    private static final ConcurrentHashMap<String, TransferUiState> ACTIVE_TRANSFERS =
            new ConcurrentHashMap<>();
    private static final CopyOnWriteArraySet<Runnable> TRANSFER_LISTENERS = new CopyOnWriteArraySet<>();
    private final Runnable transferListener = () -> {
        View view = getView();
        if (view != null) view.post(this::refreshTransferRows);
    };

    private static final class TransferUiState {
        volatile int stage;
        volatile int progress;
        volatile ResumableDownload.Control control;
        File output;

        TransferUiState(int stage, int progress) {
            this.stage = stage;
            this.progress = progress;
        }
    }

    private boolean isDarkMode;
    private boolean embedded;
    private boolean lockCategory;

    public static ContentsFragment newInstance(int initialCategory, boolean embedded) {
        return newInstance(initialCategory, embedded, false);
    }

    public static ContentsFragment newInstance(int initialCategory, boolean embedded,
                                               boolean lockCategory) {
        ContentsFragment fragment = new ContentsFragment();
        Bundle args = new Bundle();
        args.putInt(ARG_INITIAL_CATEGORY, initialCategory);
        args.putBoolean(ARG_EMBEDDED, embedded);
        args.putBoolean(ARG_LOCK_CATEGORY, lockCategory);
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(false);
        Bundle args = getArguments();
        if (args != null) {
            selectedCategory = args.getInt(ARG_INITIAL_CATEGORY, 0);
            embedded = args.getBoolean(ARG_EMBEDDED, false);
            lockCategory = args.getBoolean(ARG_LOCK_CATEGORY, false);
        }
        manager = new ContentsManager(getContext());
        String bundledJson = FileUtils.readString(requireContext(), ContentsManager.REMOTE_PROFILES);
        manager.setRemoteProfiles(manager.getCachedRemoteProfiles(bundledJson));
        driverManager = new XclipseDriverManager(requireContext());
        wrapperManager = new CustomWrapperManager(requireContext());
        externalCatalog = new ExternalDownloadCatalog(requireContext());
        remoteDrivers = externalCatalog.getCachedDrivers();
        remoteWrappers = externalCatalog.getCachedWrappers();

        // Initialize isDarkMode based on shared preferences or theme
        isDarkMode = AppUtils.isDarkMode(requireContext());
    }

    @Override
    public void onStart() {
        super.onStart();
        TRANSFER_LISTENERS.add(transferListener);
        refreshTransferRows();
    }

    @Override
    public void onStop() {
        TRANSFER_LISTENERS.remove(transferListener);
        super.onStop();
    }

    @Override
    public void onDestroy() {
        // This screen shares Context.getCacheDir() with the rest of the app.
        // Never clear the whole directory here: Community Configs, artwork and
        // other screens may be handing a cached file to the next fragment.
        if (!ContentOperationRegistry.hasActiveOperations() && getContext() != null) {
            File[] cached = getContext().getCacheDir().listFiles();
            if (cached != null) {
                for (File file : cached) {
                    String name = file.getName();
                    if (name.startsWith("remote-wrapper-")
                            || name.startsWith("xclipse-driver-")
                            || name.startsWith("temp_")) {
                        FileUtils.delete(file);
                    }
                }
            }
        }
        super.onDestroy();
    }

    @Override
    public void onResume() {
        super.onResume();

        if (manager.shouldRefreshRemoteProfiles(REMOTE_REFRESH_INTERVAL_MS)) {
            new Thread(() -> {
                Activity activity = getActivity();
                if (activity == null) return;
                String bundledJson = FileUtils.readString(activity, ContentsManager.REMOTE_PROFILES);
                if (bundledJson == null) return;
                String refreshedJson = manager.refreshRemoteProfiles(bundledJson);
                activity.runOnUiThread(() -> {
                    if (!isAdded()) return;
                    manager.setRemoteProfiles(refreshedJson);
                    loadContentList();
                });
            }, "ContentCatalogRefresh").start();
        }
        new Thread(() -> {
            List<ExternalDownloadCatalog.Item> refreshed = externalCatalog
                    .refreshDriversIfStale(REMOTE_REFRESH_INTERVAL_MS);
            List<ExternalDownloadCatalog.Item> refreshedWrappers = externalCatalog
                    .refreshWrappersIfStale(REMOTE_REFRESH_INTERVAL_MS);
            Activity activity = getActivity();
            if (activity == null) return;
            remoteDrivers = refreshed;
            remoteWrappers = refreshedWrappers;
            activity.runOnUiThread(() -> {
                if (isAdded() && (selectedCategory == CATEGORY_XCLIPSE_DRIVERS
                        || selectedCategory == CATEGORY_WRAPPERS)) loadContentList();
            });
        }, "DriverCatalogRefresh").start();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        if (!embedded && getActivity() instanceof AppCompatActivity) {
            ((AppCompatActivity) getActivity()).getSupportActionBar().setTitle(R.string.contents);
        }
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        ViewGroup layout = (ViewGroup) inflater.inflate(R.layout.contents_fragment, container, false);

        int requestedCategory = selectedCategory;
        sContentType = layout.findViewById(R.id.SContentType);
        updateContentTypeSpinner(sContentType);
        int initialPosition = categoryPosition(requestedCategory);
        selectedCategory = CATEGORY_ORDER[initialPosition];
        if (selectedCategory >= 0 && selectedCategory < ContentProfile.ContentType.values().length)
            currentContentType = ContentProfile.ContentType.values()[selectedCategory];
        emptyText = layout.findViewById(R.id.TVEmptyText);

        installButton = layout.findViewById(R.id.BTInstallContent);
        installButton.setOnClickListener(v -> handleInstallButton());

        recyclerView = layout.findViewById(R.id.RecyclerView);
        recyclerView.setLayoutManager(new LinearLayoutManager(recyclerView.getContext()));
        recyclerView.addItemDecoration(new DividerItemDecoration(recyclerView.getContext(), DividerItemDecoration.VERTICAL));
        sContentType.setSelection(initialPosition);
        if (lockCategory) {
            sContentType.setEnabled(false);
            layout.findViewById(R.id.TVContentType).setVisibility(View.GONE);
            layout.findViewById(R.id.LLContentType).setVisibility(View.GONE);
        }
        loadContentList();

        return layout;
    }

    private void updateContentTypeSpinner(Spinner spinner) {
        List<String> typeList = new ArrayList<>();
        for (int category : CATEGORY_ORDER) typeList.add(getCategoryTitle(requireContext(), category));
        spinner.setAdapter(new com.winlator.cmod.widget.ThemedSpinnerAdapter<>(spinner.getContext(), typeList));

        // Set the popup background based on the theme
        spinner.setPopupBackgroundResource(isDarkMode ? R.drawable.content_dialog_background_dark : R.drawable.content_dialog_background);

        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                selectedCategory = CATEGORY_ORDER[position];
                if (selectedCategory >= 0 && selectedCategory < ContentProfile.ContentType.values().length)
                    currentContentType = ContentProfile.ContentType.values()[selectedCategory];
                if (installButton != null) installButton.setText(selectedCategory == CATEGORY_XCLIPSE_DRIVERS
                        ? R.string.install_driver : selectedCategory == CATEGORY_WRAPPERS
                        ? R.string.install_wrapper : selectedCategory == CATEGORY_RUNTIMES
                        ? R.string.install_runtime : R.string.install_content);
                loadContentList();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {

            }
        });
    }

    private void updateContentsListView() {
        List<ContentProfile> profiles = categoryProfiles();
        if (profiles.isEmpty()) {
            recyclerView.setVisibility(View.GONE);
            emptyText.setVisibility(View.VISIBLE);
        }
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        if (requestCode == MainActivity.OPEN_FILE_REQUEST_CODE && resultCode == Activity.RESULT_OK) {
            if (data == null || data.getData() == null) return;
            Uri contentUri = data.getData();
            if (importMode == IMPORT_DRIVER) {
                importMode = IMPORT_CONTENT;
                installDriver(contentUri, null);
                return;
            }
            if (importMode == IMPORT_WRAPPER) {
                importMode = IMPORT_CONTENT;
                promptAndInstallWrapper(contentUri, null);
                return;
            }
            ContentProfile expectedProfile = pendingRemoteProfiles.remove(contentUri.toString());
            String transferKey = pendingRemoteTransferKeys.remove(contentUri.toString());
            ContentOperationRegistry.Token installOperation =
                    ContentOperationRegistry.begin(ContentOperationRegistry.Kind.INSTALL);
            PreloaderDialog preloaderDialog = new PreloaderDialog(getActivity());
            if (transferKey == null) {
                preloaderDialog.showOnUiThread(R.string.installing_content);
                preloaderDialog.setProgress(1);
                preloaderDialog.allowBackground();
            }
            else updateTransfer(transferKey, TRANSFER_INSTALLING, 1);
            // Capture the activity: installation can outlive the fragment.
            android.app.Activity hostActivity = getActivity();
            try {
                ContentsManager.OnInstallFinishedCallback callback = new ContentsManager.OnInstallFinishedCallback() {
                    private boolean isExtracting = true;

                    @Override
                    public void onFailed(ContentsManager.InstallFailedReason reason, Exception e) {
                        installOperation.close();
                        finishTransfer(transferKey);
                        int msgId = switch (reason) {
                            case ERROR_BADTAR -> R.string.file_cannot_be_recognied;
                            case ERROR_NOPROFILE -> R.string.profile_not_found_in_content;
                            case ERROR_BADPROFILE -> R.string.profile_cannot_be_recognized;
                            case ERROR_EXIST -> R.string.content_already_exist;
                            case ERROR_MISSINGFILES -> R.string.content_is_incomplete;
                            case ERROR_UNTRUSTPROFILE -> R.string.content_cannot_be_trusted;
                            default -> R.string.unable_to_install_content;
                        };
                        if (hostActivity == null) { preloaderDialog.closeOnUiThread(); return; }
                        String message = hostActivity.getString(R.string.install_failed) + ": "
                                + hostActivity.getString(msgId);
                        hostActivity.runOnUiThread(() -> {
                            if (!isAdded()) {
                                preloaderDialog.close();
                                return;
                            }
                            ContentDialog.alert(requireContext(), message, preloaderDialog::closeOnUiThread);
                        });
                    }

                    @Override
                    public void onSucceed(ContentProfile profile) {
                        if (isExtracting) {
                            ContentsManager.OnInstallFinishedCallback callback1 = this;
                            if (hostActivity == null) {
                                manager.discardStagedContent(profile);
                                installOperation.close();
                                updateTransfer(transferKey, TRANSFER_PAUSED, 99);
                                preloaderDialog.closeOnUiThread();
                                return;
                            }
                            hostActivity.runOnUiThread(() -> {
                                if (!isAdded()) {
                                    manager.discardStagedContent(profile);
                                    installOperation.close();
                                    updateTransfer(transferKey, TRANSFER_PAUSED, 99);
                                    preloaderDialog.close();
                                    return;
                                }
                                // The preloader is a fullscreen dialog. Keeping it open here
                                // placed the required content confirmation behind it, making
                                // installation look frozen forever after extraction.
                                if (transferKey == null) preloaderDialog.close();
                                else updateTransfer(transferKey, TRANSFER_WAITING_CONFIRMATION, 97);
                                ContentInfoDialog dialog = new ContentInfoDialog(getContext(), profile);
                                ((TextView) dialog.findViewById(R.id.BTConfirm)).setText(R.string._continue);
                                dialog.setOnConfirmCallback(() -> {
                                    isExtracting = false;
                                    List<ContentProfile.ContentFile> untrustedFiles = manager.getUnTrustedContentFiles(profile);
                                    if (!untrustedFiles.isEmpty()) {
                                        ContentUntrustedDialog untrustedDialog = new ContentUntrustedDialog(getContext(), untrustedFiles);
                                        untrustedDialog.setOnCancelCallback(() -> {
                                            manager.discardStagedContent(profile);
                                            installOperation.close();
                                            finishTransfer(transferKey);
                                            preloaderDialog.closeOnUiThread();
                                        });
                                        untrustedDialog.setOnConfirmCallback(() -> {
                                            if (transferKey == null) {
                                                preloaderDialog.show(R.string.installing_content);
                                                preloaderDialog.setProgress(98);
                                            }
                                            else updateTransfer(transferKey, TRANSFER_INSTALLING, 98);
                                            manager.finishInstallContent(profile, callback1);
                                        });
                                        untrustedDialog.show();
                                    } else {
                                        if (transferKey == null) {
                                            preloaderDialog.show(R.string.installing_content);
                                            preloaderDialog.setProgress(98);
                                        }
                                        else updateTransfer(transferKey, TRANSFER_INSTALLING, 98);
                                        manager.finishInstallContent(profile, callback1);
                                    }
                                });
                                dialog.setOnCancelCallback(() -> {
                                    manager.discardStagedContent(profile);
                                    installOperation.close();
                                    finishTransfer(transferKey);
                                    preloaderDialog.closeOnUiThread();
                                });
                                dialog.show();
                            });

                        } else {
                            installOperation.close();
                            finishTransfer(transferKey);
                            preloaderDialog.closeOnUiThread();
                            if (hostActivity == null) return;
                            hostActivity.runOnUiThread(() -> {
                                if (!isAdded()) return;
                                ContentDialog.alert(requireContext(), R.string.content_installed_success, null);
                                manager.syncContents();
                                sContentType.setSelection(categoryPosition(profile.type.ordinal()));
                                loadContentList();
                            });
                        }
                    }
                };
                new Thread(() -> {
                    manager.extraContentFile(contentUri, expectedProfile, callback,
                            progress -> {
                                if (transferKey == null) preloaderDialog.setProgress(progress);
                                else updateTransfer(transferKey, TRANSFER_INSTALLING, progress);
                            });
                }, "ContentInstaller").start();
            } catch (Exception e) {
                installOperation.close();
                finishTransfer(transferKey);
                preloaderDialog.closeOnUiThread();
                AppUtils.showToast(getContext(), R.string.unable_to_import_profile);
            }
        }
    }

    private void handleInstallButton() {
        if (selectedCategory == CATEGORY_XCLIPSE_DRIVERS) {
            importMode = IMPORT_DRIVER;
            openDocument(new String[]{"application/zip", "application/octet-stream"});
            return;
        }
        if (selectedCategory == CATEGORY_WRAPPERS) {
            new AlertDialog.Builder(requireContext())
                    .setTitle(R.string.install_wrapper)
                    .setItems(new String[]{getString(R.string.select_file), getString(R.string.download_from_url)},
                            (dialog, which) -> {
                                if (which == 0) {
                                    importMode = IMPORT_WRAPPER;
                                    openDocument(new String[]{"application/octet-stream", "application/x-zstd", "application/zstd"});
                                }
                                else promptWrapperUrl();
                            })
                    .show();
            return;
        }
        importMode = IMPORT_CONTENT;
        openDocument(new String[]{"application/octet-stream", "application/x-xz",
                "application/x-zstd", "application/zstd",
                "application/x-zstd-compressed-tar", "application/x-compressed-tar"});
    }

    public static String getCategoryTitle(Context context, int category) {
        if (category == CATEGORY_RUNTIMES) return context.getString(R.string.wine_and_proton);
        if (category == CATEGORY_XCLIPSE_DRIVERS) return context.getString(R.string.xclipse_drivers);
        if (category == CATEGORY_WRAPPERS) return context.getString(R.string.wrappers);
        ContentProfile.ContentType[] values = ContentProfile.ContentType.values();
        if (category >= 0 && category < values.length) return values[category].toString();
        return context.getString(R.string.contents);
    }

    private static int categoryPosition(int category) {
        if (category == ContentProfile.ContentType.CONTENT_TYPE_WINE.ordinal()
                || category == ContentProfile.ContentType.CONTENT_TYPE_PROTON.ordinal()) return 0;
        for (int i = 0; i < CATEGORY_ORDER.length; i++) if (CATEGORY_ORDER[i] == category) return i;
        return 0;
    }

    private List<ContentProfile> categoryProfiles() {
        if (selectedCategory != CATEGORY_RUNTIMES) return manager.getProfiles(currentContentType);
        List<ContentProfile> profiles = new ArrayList<>(manager.getProfiles(ContentProfile.ContentType.CONTENT_TYPE_WINE));
        profiles.addAll(manager.getProfiles(ContentProfile.ContentType.CONTENT_TYPE_PROTON));
        return profiles;
    }

    private void openDocument(String[] mimeTypes) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes);
        startActivityForResult(intent, MainActivity.OPEN_FILE_REQUEST_CODE);
    }

    private void promptWrapperUrl() {
        EditText input = new EditText(requireContext());
        input.setHint("https://example.com/wrapper.tzst");
        input.setSingleLine(true);
        AlertDialog dialog = new AlertDialog.Builder(requireContext())
                .setTitle(R.string.remote_url)
                .setView(input)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(DialogInterface.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    String url = input.getText().toString().trim();
                    if (!url.matches("(?i)^https?://.+\\.(?:tzst|tstz|tzts|zst|so)(?:\\?.*)?$")) {
                        input.setError(getString(R.string.invalid_url));
                        return;
                    }
                    dialog.dismiss();
                    downloadWrapperFromUrl(url);
                }));
        dialog.show();
    }

    private void downloadWrapperFromUrl(String url) {
        android.app.Activity hostActivity = getActivity();
        android.content.Context hostContext = requireContext().getApplicationContext();
        ContentOperationRegistry.Token downloadOperation =
                ContentOperationRegistry.begin(ContentOperationRegistry.Kind.DOWNLOAD);
        AppUtils.showToast(requireContext(), R.string.downloading_content);
        new Thread(() -> {
            String rawName = Uri.parse(url).getLastPathSegment();
            if (rawName == null || rawName.isEmpty()) rawName = "wrapper.tzst";
            int query = rawName.indexOf('?');
            if (query >= 0) rawName = rawName.substring(0, query);
            File output = new File(hostContext.getCacheDir(),
                    "remote-wrapper-" + System.currentTimeMillis() + "-" + rawName.replaceAll("[^A-Za-z0-9._-]", "_"));
            boolean downloaded = Downloader.downloadFile(url, output, null);
            if (hostActivity == null || hostActivity.isFinishing()) {
                FileUtils.delete(output);
                downloadOperation.close();
                return;
            }
            hostActivity.runOnUiThread(() -> {
                if (!isAdded()) {
                    FileUtils.delete(output);
                    downloadOperation.close();
                    return;
                }
                if (downloaded) promptAndInstallWrapper(Uri.fromFile(output), output);
                else {
                    FileUtils.delete(output);
                    ContentDialog.alert(requireContext(), R.string.download_failed, null);
                }
                downloadOperation.close();
            });
        }).start();
    }

    private void promptAndInstallWrapper(Uri source, @Nullable File temporaryFile) {
        promptAndInstallWrapper(source, temporaryFile, null);
    }

    private void promptAndInstallWrapper(Uri source, @Nullable File temporaryFile,
                                         @Nullable String suggestedName) {
        promptAndInstallWrapper(source, temporaryFile, suggestedName, null);
    }

    private void promptAndInstallWrapper(Uri source, @Nullable File temporaryFile,
                                         @Nullable String suggestedName, @Nullable String transferKey) {
        String sourceName = source.getLastPathSegment();
        if (sourceName == null) sourceName = "Wrapper";
        String initialName = suggestedName != null && !suggestedName.trim().isEmpty()
                ? suggestedName.trim().replaceFirst("(?i)^wrapper[-_ ]*", "")
                : ExternalDownloadCatalog.stripPackageSuffix(new File(sourceName).getName())
                        .replaceFirst("(?i)^wrapper[-_ ]*", "");
        EditText input = new EditText(requireContext());
        input.setHint(R.string.wrapper_name);
        input.setSingleLine(true);
        input.setText(initialName);
        input.selectAll();
        AlertDialog dialog = new AlertDialog.Builder(requireContext())
                .setTitle(R.string.wrapper_name)
                .setView(input)
                .setNegativeButton(android.R.string.cancel, (d, w) -> {
                    if (temporaryFile != null) FileUtils.delete(temporaryFile);
                    finishTransfer(transferKey);
                })
                .setPositiveButton(android.R.string.ok, null)
                .create();
        dialog.setOnCancelListener(ignored -> {
            if (temporaryFile != null) FileUtils.delete(temporaryFile);
            finishTransfer(transferKey);
        });
        dialog.setOnShowListener(ignored -> dialog.getButton(DialogInterface.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    String name = input.getText().toString().trim();
                    if (name.isEmpty()) {
                        input.setError(getString(R.string.wrapper_name));
                        return;
                    }
                    dialog.dismiss();
                    PreloaderDialog preloader = new PreloaderDialog(requireActivity());
                    preloader.show(R.string.installing_content);
                    preloader.setIndeterminate();
                    preloader.allowBackground();
                    android.app.Activity hostActivity = getActivity();
                    ContentOperationRegistry.Token installOperation =
                            ContentOperationRegistry.begin(ContentOperationRegistry.Kind.INSTALL);
                    updateTransfer(transferKey, TRANSFER_INSTALLING, 1);
                    new Thread(() -> {
                        String id = null;
                        try {
                            id = wrapperManager.install(source, name);
                        }
                        catch (RuntimeException e) {
                            Log.e("ContentsFragment", "Wrapper installation failed", e);
                        }
                        if (temporaryFile != null) FileUtils.delete(temporaryFile);
                        if (hostActivity == null || hostActivity.isFinishing()) {
                            installOperation.close();
                            finishTransfer(transferKey);
                            return;
                        }
                        String installedId = id;
                        hostActivity.runOnUiThread(() -> {
                            installOperation.close();
                            finishTransfer(transferKey);
                            preloader.close();
                            if (!isAdded()) return;
                            if (installedId == null) ContentDialog.alert(requireContext(), R.string.unable_to_install_wrapper, null);
                            else {
                                ContentDialog.alert(requireContext(), R.string.content_installed_success, null);
                                loadContentList();
                            }
                        });
                    }).start();
                }));
        dialog.show();
    }

    private void installDriver(Uri source, @Nullable File temporaryFile) {
        installDriver(source, temporaryFile, null);
    }

    private void installDriver(Uri source, @Nullable File temporaryFile,
                               @Nullable String transferKey) {
        PreloaderDialog preloader = new PreloaderDialog(requireActivity());
        if (transferKey == null) {
            preloader.show(R.string.installing_content);
            preloader.setIndeterminate();
            preloader.allowBackground();
        }
        else updateTransfer(transferKey, TRANSFER_INSTALLING, 1);
        android.app.Activity hostActivity = getActivity();
        ContentOperationRegistry.Token installOperation =
                ContentOperationRegistry.begin(ContentOperationRegistry.Kind.INSTALL);
        new Thread(() -> {
            String id = null;
            try {
                id = driverManager.installDriver(source, progress ->
                        updateTransfer(transferKey, TRANSFER_INSTALLING, progress));
            }
            catch (RuntimeException e) {
                Log.e("ContentsFragment", "Driver installation failed", e);
            }
            if (temporaryFile != null) FileUtils.delete(temporaryFile);
            if (hostActivity == null || hostActivity.isFinishing()) {
                installOperation.close();
                finishTransfer(transferKey);
                return;
            }
            String installedId = id;
            hostActivity.runOnUiThread(() -> {
                installOperation.close();
                finishTransfer(transferKey);
                preloader.close();
                if (!isAdded()) return;
                if (installedId == null || installedId.isEmpty())
                    ContentDialog.alert(requireContext(), R.string.unable_to_install_driver, null);
                else {
                    ContentDialog.alert(requireContext(), R.string.content_installed_success, null);
                    loadContentList();
                }
            });
        }).start();
    }

    private String contentTransferKey(ContentProfile profile) {
        return "content:" + (profile.remoteUrl != null
                ? profile.remoteUrl : ContentsManager.getEntryName(profile));
    }

    private String externalTransferKey(ExternalDownloadCatalog.Item item) {
        String prefix = selectedCategory == CATEGORY_WRAPPERS ? "wrapper:" : "driver:";
        return prefix + (item.url != null ? item.url : item.detail);
    }

    private File downloadOutput(String address) {
        String name = Uri.parse(address).getLastPathSegment();
        return ResumableDownload.outputFile(new File(requireContext().getFilesDir(), "content-downloads"),
                address, name == null || name.isEmpty() ? "package.wcp" : name);
    }

    private TransferUiState recoverTransfer(String key, String address, File output) {
        TransferUiState state = ACTIVE_TRANSFERS.get(key);
        if (state == null && ResumableDownload.hasSavedDownload(address, output)) {
            state = new TransferUiState(TRANSFER_PAUSED, ResumableDownload.savedProgress(output));
            state.output = output;
            TransferUiState previous = ACTIVE_TRANSFERS.putIfAbsent(key, state);
            if (previous != null) state = previous;
        }
        return state;
    }

    private interface DownloadReady { void accept(File output); }

    private void startDownload(String key, String address, File output, DownloadReady ready) {
        TransferUiState state;
        synchronized (ACTIVE_TRANSFERS) {
            TransferUiState previous = ACTIVE_TRANSFERS.get(key);
            if (previous != null && previous.stage != TRANSFER_PAUSED && previous.stage != TRANSFER_FAILED) return;
            state = new TransferUiState(TRANSFER_DOWNLOADING, ResumableDownload.savedProgress(output));
            state.control = new ResumableDownload.Control();
            state.output = output;
            ACTIVE_TRANSFERS.put(key, state);
        }
        notifyTransfers();
        ContentOperationRegistry.Token operation = ContentOperationRegistry.begin(ContentOperationRegistry.Kind.DOWNLOAD);
        ResumableDownload.Control control = state.control;
        new Thread(() -> {
            ResumableDownload.Result result = ResumableDownload.download(address, output, control, progress -> {
                state.progress = progress;
                notifyTransfers();
            });
            if (result != ResumableDownload.Result.COMPLETE) {
                if (result == ResumableDownload.Result.CANCELLED) finishTransfer(key);
                else {
                    state.control = null;
                    updateTransfer(key, result == ResumableDownload.Result.PAUSED ? TRANSFER_PAUSED : TRANSFER_FAILED,
                            ResumableDownload.savedProgress(output));
                }
                operation.close();
                return;
            }
            if (control.isCancelled()) {
                finishTransfer(key);
                operation.close();
                return;
            }
            Activity host = getActivity();
            if (host == null || host.isFinishing()) {
                state.control = null;
                updateTransfer(key, TRANSFER_PAUSED, 99);
                operation.close();
                return;
            }
            host.runOnUiThread(() -> {
                try {
                    state.control = null;
                    if (control.isCancelled()) finishTransfer(key);
                    else if (!isAdded() || control.isStopping()) updateTransfer(key, TRANSFER_PAUSED, 99);
                    else ready.accept(output);
                }
                catch (RuntimeException error) {
                    Log.e("ContentsFragment", "Unable to open downloaded package", error);
                    updateTransfer(key, TRANSFER_FAILED, 99);
                }
                finally { operation.close(); }
            });
        }, "ContentDownload").start();
    }

    private void downloadContent(ContentProfile profile, String key, File output) {
        startDownload(key, profile.remoteUrl, output, file -> {
            Intent intent = new Intent().setData(Uri.fromFile(file));
            pendingRemoteProfiles.put(intent.getData().toString(), profile);
            pendingRemoteTransferKeys.put(intent.getData().toString(), key);
            importMode = IMPORT_CONTENT;
            onActivityResult(MainActivity.OPEN_FILE_REQUEST_CODE, Activity.RESULT_OK, intent);
        });
    }

    private static void notifyTransfers() {
        for (Runnable listener : TRANSFER_LISTENERS) listener.run();
    }

    private void updateTransfer(@Nullable String key, int stage, int progress) {
        if (key == null) return;
        TransferUiState state = ACTIVE_TRANSFERS.get(key);
        if (state == null) return;
        state.stage = stage;
        state.progress = Math.max(0, Math.min(100, progress));
        notifyTransfers();
    }

    private void finishTransfer(@Nullable String key) {
        if (key == null) return;
        TransferUiState state = ACTIVE_TRANSFERS.remove(key);
        if (state != null && state.output != null) ResumableDownload.discard(state.output);
        notifyTransfers();
    }

    private void refreshTransferRows() {
        if (!isAdded() || recyclerView == null) return;
        RecyclerView.Adapter<?> adapter = recyclerView.getAdapter();
        if (adapter != null) adapter.notifyDataSetChanged();
    }

    private void bindTransfer(View progressLayout, ProgressBar progressBar,
                              TextView progressStatus, String key, @Nullable TransferUiState state) {
        boolean active = state != null;
        progressLayout.setVisibility(active ? View.VISIBLE : View.GONE);
        if (!active) return;
        progressBar.setProgress(state.progress);
        if (state.stage == TRANSFER_WAITING_CONFIRMATION) {
            progressStatus.setText(R.string.waiting_install_confirmation);
        }
        else if (state.stage == TRANSFER_PAUSED) progressStatus.setText(getString(R.string.download_paused, state.progress));
        else if (state.stage == TRANSFER_FAILED) progressStatus.setText(R.string.download_retry);
        else if (state.stage == TRANSFER_STOPPING) progressStatus.setText(R.string.download_stopping);
        else {
            int format = state.stage == TRANSFER_INSTALLING
                    ? R.string.install_progress : R.string.download_progress;
            progressStatus.setText(getString(format, state.progress));
        }
        View cancel = progressLayout.findViewById(R.id.BTDiscardDownload);
        cancel.setVisibility(state.stage == TRANSFER_INSTALLING || state.stage == TRANSFER_WAITING_CONFIRMATION
                ? View.GONE : View.VISIBLE);
        cancel.setEnabled(state.stage != TRANSFER_STOPPING);
        cancel.setOnClickListener(v -> {
            ResumableDownload.Control control = state.control;
            if (control != null) {
                state.stage = TRANSFER_STOPPING;
                control.cancel();
                notifyTransfers();
            }
            else finishTransfer(key);
        });
    }

    private void bindDownloadButton(ImageButton button, @Nullable TransferUiState state, boolean available,
                                    Runnable start) {
        boolean canDownload = state == null || state.stage == TRANSFER_DOWNLOADING
                || state.stage == TRANSFER_PAUSED || state.stage == TRANSFER_FAILED;
        button.setVisibility(available && canDownload ? View.VISIBLE : View.GONE);
        boolean running = state != null && state.stage == TRANSFER_DOWNLOADING;
        button.setImageResource(running ? R.drawable.icon_download_pause : state != null
                ? R.drawable.icon_download_resume : R.drawable.icon_popup_menu_download);
        button.setContentDescription(getString(running ? R.string.download_pause
                : state != null ? R.string.download_resume : R.string.download_start));
        button.setOnClickListener(v -> {
            ResumableDownload.Control control = state == null ? null : state.control;
            if (running && control != null) {
                state.stage = TRANSFER_STOPPING;
                control.pause();
                notifyTransfers();
            }
            else start.run();
        });
    }

    private void loadContentList() {
        if (selectedCategory == CATEGORY_XCLIPSE_DRIVERS) {
            List<ExternalDownloadCatalog.Item> drivers = new ArrayList<>();
            for (String id : driverManager.enumerateInstalledDrivers()) {
                String name = driverManager.getDriverName(id);
                String version = driverManager.getDriverVersion(id);
                if (name == null || name.isEmpty()) name = id;
                if (version != null && !version.isEmpty() && !name.contains(version)) {
                    name += " " + version;
                }
                drivers.add(new ExternalDownloadCatalog.Item(name, id, null));
            }
            if (remoteDrivers != null) drivers.addAll(remoteDrivers);
            showExternalItems(drivers);
            return;
        }
        if (selectedCategory == CATEGORY_WRAPPERS) {
            List<ExternalDownloadCatalog.Item> installed = new ArrayList<>();
            java.util.HashSet<String> installedIds = new java.util.HashSet<>();
            for (String id : wrapperManager.getInstalledIds()) {
                installedIds.add(CustomWrapperManager.toIdentifier(id));
                installed.add(new ExternalDownloadCatalog.Item(
                        CustomWrapperManager.toDisplayName(id), id, null));
            }
            if (remoteWrappers != null) {
                for (ExternalDownloadCatalog.Item item : remoteWrappers) {
                    if (!installedIds.contains(CustomWrapperManager.toIdentifier(item.name))) {
                        installed.add(item);
                    }
                }
            }
            showExternalItems(installed);
            return;
        }
        List<ContentProfile> profiles = categoryProfiles();
        if (profiles.isEmpty()) {
            emptyText.setVisibility(View.VISIBLE);
            recyclerView.setVisibility(View.GONE);
        } else {
            emptyText.setVisibility(View.GONE);
            recyclerView.setVisibility(View.VISIBLE);
            recyclerView.setAdapter(new ContentItemAdapter(profiles));
        }
    }

    private void showExternalItems(List<ExternalDownloadCatalog.Item> items) {
        if (items == null || items.isEmpty()) {
            emptyText.setVisibility(View.VISIBLE);
            recyclerView.setVisibility(View.GONE);
        }
        else {
            emptyText.setVisibility(View.GONE);
            recyclerView.setVisibility(View.VISIBLE);
            recyclerView.setAdapter(new ExternalItemAdapter(items));
        }
    }

    private class ExternalItemAdapter extends RecyclerView.Adapter<ExternalItemAdapter.ViewHolder> {
        private final List<ExternalDownloadCatalog.Item> data;

        private class ViewHolder extends RecyclerView.ViewHolder {
            final ImageView icon;
            final TextView title;
            final TextView detail;
            final ImageButton menu;
            final ImageButton download;
            final View transferProgress;
            final ProgressBar progress;
            final TextView progressStatus;

            ViewHolder(@NonNull View view) {
                super(view);
                icon = view.findViewById(R.id.IVIcon);
                title = view.findViewById(R.id.TVVersionName);
                detail = view.findViewById(R.id.TVVersionCode);
                menu = view.findViewById(R.id.BTMenu);
                download = view.findViewById(R.id.BTDownload);
                transferProgress = view.findViewById(R.id.LLTransferProgress);
                progress = view.findViewById(R.id.Progress);
                progressStatus = view.findViewById(R.id.TVProgressStatus);
            }
        }

        ExternalItemAdapter(List<ExternalDownloadCatalog.Item> data) {
            this.data = data;
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new ViewHolder(LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.content_list_item, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            ExternalDownloadCatalog.Item item = data.get(position);
            boolean installed = item.url == null;
            holder.itemView.setAlpha(installed ? 1.0f : 0.58f);
            holder.icon.setBackground(null);
            holder.icon.setImageResource(selectedCategory == CATEGORY_WRAPPERS
                    ? R.drawable.icon_settings : R.drawable.icon_debug);
            holder.title.setText(item.name);
            holder.detail.setText(item.detail);
            holder.detail.setVisibility(item.detail == null || item.detail.isEmpty() ? View.GONE : View.VISIBLE);
            String transferKey = externalTransferKey(item);
            File output = installed ? null : downloadOutput(item.url);
            TransferUiState transfer = installed ? ACTIVE_TRANSFERS.get(transferKey)
                    : recoverTransfer(transferKey, item.url, output);
            bindTransfer(holder.transferProgress, holder.progress, holder.progressStatus, transferKey, transfer);
            holder.menu.setVisibility(installed && transfer == null ? View.VISIBLE : View.GONE);
            holder.menu.setOnClickListener(v -> ContentDialog.confirm(requireContext(),
                    R.string.do_you_want_to_remove_this_content, () -> {
                        if (selectedCategory == CATEGORY_WRAPPERS) wrapperManager.remove(item.detail);
                        else driverManager.removeDriver(item.detail);
                        loadContentList();
                    }));
            final boolean wrapperDownload = selectedCategory == CATEGORY_WRAPPERS;
            bindDownloadButton(holder.download, transfer, !installed, () ->
                    startDownload(transferKey, item.url, output, file -> {
                        if (wrapperDownload) {
                            updateTransfer(transferKey, TRANSFER_WAITING_CONFIRMATION, 100);
                            promptAndInstallWrapper(Uri.fromFile(file), file, item.name, transferKey);
                        }
                        else installDriver(Uri.fromFile(file), file, transferKey);
                    }));
        }

        @Override
        public int getItemCount() {
            return data.size();
        }
    }

    private class ContentItemAdapter extends RecyclerView.Adapter<ContentItemAdapter.ViewHolder> {
        private final List<ContentProfile> data;

        private static class ViewHolder extends RecyclerView.ViewHolder {
            private final ImageView ivIcon;
            private final TextView tvVersionName;
            private final TextView tvVersionCode;
            private final ImageButton ibMenu;
            private final ImageButton ibDownload;
            private final View transferProgress;
            private final ProgressBar progressBar;
            private final TextView progressStatus;

            public ViewHolder(@NonNull View view) {
                super(view);

                ivIcon = view.findViewById(R.id.IVIcon);
                tvVersionName = view.findViewById(R.id.TVVersionName);
                tvVersionCode = view.findViewById(R.id.TVVersionCode);
                ibMenu = view.findViewById(R.id.BTMenu);
                ibDownload = view.findViewById(R.id.BTDownload);
                transferProgress = view.findViewById(R.id.LLTransferProgress);
                progressBar = view.findViewById(R.id.Progress);
                progressStatus = view.findViewById(R.id.TVProgressStatus);
            }
        }

        public ContentItemAdapter(List<ContentProfile> data) {
            this.data = data;
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new ContentItemAdapter.ViewHolder(LayoutInflater.from(parent.getContext()).inflate(R.layout.content_list_item, parent, false));
        }

        @Override
        public void onViewRecycled(@NonNull ViewHolder holder) {
            holder.ibMenu.setOnClickListener(null);
            super.onViewRecycled(holder);
        }

        @SuppressLint("StringFormatInvalid")
        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            final ContentProfile profile = data.get(position);
            boolean installed = manager.isInstalledProfile(profile);
            holder.itemView.setAlpha(installed ? 1.0f : 0.58f);

            int iconId = switch (profile.type) {
                case CONTENT_TYPE_WINE -> R.drawable.icon_wine;
                case CONTENT_TYPE_PROTON -> R.drawable.icon_wine;
                default -> R.drawable.icon_settings;
            };
            holder.ivIcon.setBackground(null);
            holder.ivIcon.setImageResource(iconId);

            holder.tvVersionName.setText((selectedCategory == CATEGORY_RUNTIMES ? profile.type.toString()
                    : getString(R.string.version)) + ": " + profile.verName);
            holder.tvVersionCode.setText(getContext().getString(R.string.version_code) + ": " + profile.verCode);
            String transferKey = contentTransferKey(profile);
            File output = profile.remoteUrl == null ? null : downloadOutput(profile.remoteUrl);
            TransferUiState transfer = profile.remoteUrl == null ? ACTIVE_TRANSFERS.get(transferKey)
                    : recoverTransfer(transferKey, profile.remoteUrl, output);
            bindTransfer(holder.transferProgress, holder.progressBar, holder.progressStatus, transferKey, transfer);
            holder.ibMenu.setVisibility(profile.remoteUrl == null && transfer == null ? View.VISIBLE : View.GONE);
            // APK-embedded bundles are reinstalled on every launch and
            // containers depend on them; only Info is offered for them.
            boolean bundledContent = profile.remoteUrl == null
                    && MainActivity.isBundledContent(getContext(), profile);
            holder.ibMenu.setOnClickListener(v -> {
                PopupMenu selectionMenu = new PopupMenu(getContext(), holder.ibMenu);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                    selectionMenu.setForceShowIcon(true);
                selectionMenu.inflate(R.menu.content_popup_menu);
                if (bundledContent)
                    selectionMenu.getMenu().findItem(R.id.remove_content).setVisible(false);
                selectionMenu.setOnMenuItemClickListener(item -> {
                    int itemId = item.getItemId();
                    if (itemId == R.id.content_info) {
                        new ContentInfoDialog(getContext(), profile).show();
                    } else if (itemId == R.id.remove_content) {
                        ContentDialog.confirm(getContext(), R.string.do_you_want_to_remove_this_content, () -> {
                            ContentProfile replacement = null;
                            boolean isRuntime = profile.type == ContentProfile.ContentType.CONTENT_TYPE_WINE
                                    || profile.type == ContentProfile.ContentType.CONTENT_TYPE_PROTON;
                            if (isRuntime) {
                                // Wine and Proton share one runtime pool: removing
                                // the last installed runtime would leave every
                                // container without anything to launch.
                                outer:
                                for (ContentProfile.ContentType runtimeType : new ContentProfile.ContentType[]{ContentProfile.ContentType.CONTENT_TYPE_WINE, ContentProfile.ContentType.CONTENT_TYPE_PROTON}) {
                                    for (ContentProfile other : manager.getProfiles(runtimeType)) {
                                        if (ContentsManager.getEntryName(other).equals(ContentsManager.getEntryName(profile))) continue;
                                        if (manager.isInstalledProfile(other)) { replacement = other; break outer; }
                                    }
                                }
                                if (replacement == null) {
                                    ContentDialog.alert(getContext(), getString(R.string.unable_to_remove_last_runtime), null);
                                    return;
                                }
                                if (!profile.bakedInRuntime) {
                                    ContainerManager containerManager = new ContainerManager(getContext());
                                    for (Container container : containerManager.getContainers()) {
                                        if (container.getWineVersion().equals(ContentsManager.getEntryName(profile))) {
                                            ContentDialog.alert(getContext(), String.format(getString(R.string.unable_to_remove_content_since_container_using), container.getName()), null);
                                            return;
                                        }
                                    }
                                }
                            }
                            if (profile.bakedInRuntime) {
                                // Point every container on the built-in runtime
                                // at the replacement, then delete the 2.4 GB
                                // tree off the UI thread.
                                ContainerManager containerManager = new ContainerManager(getContext());
                                String replacementEntry = ContentsManager.getEntryName(replacement);
                                int moved = 0;
                                for (Container container : containerManager.getContainers()) {
                                    String wineVersion = container.getWineVersion();
                                    if (WineInfo.isMainWineVersion(wineVersion)                                            || wineVersion.equals(ContentsManager.getEntryName(profile))) {
                                        container.setWineVersion(replacementEntry);
                                        container.saveData();
                                        moved++;
                                    }
                                }
                                final int movedCount = moved;
                                final ContentProfile finalReplacement = replacement;
                                android.app.Activity hostActivity = getActivity();
                                PreloaderDialog preloader = new PreloaderDialog(requireActivity());
                                preloader.show(R.string.installing_content);
                                preloader.setIndeterminate();
                                preloader.allowBackground();
                                new Thread(() -> {
                                    manager.removeContent(profile);
                                    if (hostActivity == null) return;
                                    hostActivity.runOnUiThread(() -> {
                                        if (!isAdded()) { preloader.close(); return; }
                                        preloader.close();
                                        PreferenceManager.getDefaultSharedPreferences(getContext())
                                                .edit()
                                                .remove(MainActivity.PREF_INSTALLED_ASSET_CONTENTS)
                                                .apply();
                                        loadContentList();
                                        if (movedCount > 0) AppUtils.showToast(getContext(), String.format(
                                                getString(R.string.containers_moved_to_runtime),
                                                ContentsManager.getEntryName(finalReplacement)));
                                    });
                                }, "RemoveBakedInRuntime").start();
                                return;
                            }
                            manager.removeContent(profile);
                            // Drop the bundled-asset success markers so an
                            // uninstalled embedded bundle is offered for
                            // reinstall on the next app launch.
                            PreferenceManager.getDefaultSharedPreferences(getContext())
                                    .edit()
                                    .remove(MainActivity.PREF_INSTALLED_ASSET_CONTENTS)
                                    .apply();
                            loadContentList();
                        });
                    }
                    return true;
                });
                selectionMenu.show();
            });
            bindDownloadButton(holder.ibDownload, transfer, profile.remoteUrl != null,
                    () -> downloadContent(profile, transferKey, output));
        }

        @Override
        public int getItemCount() {
            return data.size();
        }
    }
}
