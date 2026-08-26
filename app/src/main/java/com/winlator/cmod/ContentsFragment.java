package com.winlator.cmod;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
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
import com.winlator.cmod.contents.XclipseDriverManager;
import com.winlator.cmod.contents.CustomWrapperManager;
import com.winlator.cmod.contents.ExternalDownloadCatalog;
import com.winlator.cmod.core.AppUtils;
import com.winlator.cmod.core.FileUtils;
import com.winlator.cmod.core.PreloaderDialog;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ConcurrentHashMap;

public class ContentsFragment extends Fragment {
    private static final int CATEGORY_XCLIPSE_DRIVERS = 100;
    private static final int CATEGORY_WRAPPERS = 101;
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
    private final ConcurrentHashMap<String, ContentProfile> pendingRemoteProfiles =
            new ConcurrentHashMap<>();

    private boolean isDarkMode;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(false);
        manager = new ContentsManager(getContext());
        manager.syncContents();
        driverManager = new XclipseDriverManager(requireContext());
        wrapperManager = new CustomWrapperManager(requireContext());
        externalCatalog = new ExternalDownloadCatalog(requireContext());
        remoteDrivers = externalCatalog.getCachedDrivers();

        // Initialize isDarkMode based on shared preferences or theme
        isDarkMode = AppUtils.isDarkMode(requireContext());
    }

    @Override
    public void onDestroy() {
        FileUtils.clear(getContext().getCacheDir());
        super.onDestroy();
    }

    @Override
    public void onResume() {
        super.onResume();

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
        }).start();
        new Thread(() -> {
            List<ExternalDownloadCatalog.Item> refreshed = externalCatalog.refreshDrivers();
            Activity activity = getActivity();
            if (activity == null) return;
            remoteDrivers = refreshed;
            activity.runOnUiThread(() -> {
                if (isAdded() && selectedCategory == CATEGORY_XCLIPSE_DRIVERS) loadContentList();
            });
        }).start();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        ((AppCompatActivity) getActivity()).getSupportActionBar().setTitle(R.string.contents);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        ViewGroup layout = (ViewGroup) inflater.inflate(R.layout.contents_fragment, container, false);

        sContentType = layout.findViewById(R.id.SContentType);
        updateContentTypeSpinner(sContentType);
        emptyText = layout.findViewById(R.id.TVEmptyText);

        installButton = layout.findViewById(R.id.BTInstallContent);
        installButton.setOnClickListener(v -> handleInstallButton());

        recyclerView = layout.findViewById(R.id.RecyclerView);
        recyclerView.setLayoutManager(new LinearLayoutManager(recyclerView.getContext()));
        recyclerView.addItemDecoration(new DividerItemDecoration(recyclerView.getContext(), DividerItemDecoration.VERTICAL));
        loadContentList();

        return layout;
    }

    private void updateContentTypeSpinner(Spinner spinner) {
        List<String> typeList = new ArrayList<>();
        for (ContentProfile.ContentType type : ContentProfile.ContentType.values())
            typeList.add(type.toString());
        typeList.add(getString(R.string.xclipse_drivers));
        typeList.add(getString(R.string.wrappers));
        spinner.setAdapter(new com.winlator.cmod.widget.ThemedSpinnerAdapter<>(spinner.getContext(), typeList));

        // Set the popup background based on the theme
        spinner.setPopupBackgroundResource(isDarkMode ? R.drawable.content_dialog_background_dark : R.drawable.content_dialog_background);

        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                int contentTypeCount = ContentProfile.ContentType.values().length;
                if (position < contentTypeCount) {
                    selectedCategory = position;
                    currentContentType = ContentProfile.ContentType.values()[position];
                    if (installButton != null) installButton.setText(R.string.install_content);
                }
                else if (position == contentTypeCount) {
                    selectedCategory = CATEGORY_XCLIPSE_DRIVERS;
                    if (installButton != null) installButton.setText(R.string.install_driver);
                }
                else {
                    selectedCategory = CATEGORY_WRAPPERS;
                    if (installButton != null) installButton.setText(R.string.install_wrapper);
                }
                loadContentList();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {

            }
        });
    }

    private void updateContentsListView() {
        List<ContentProfile> profiles = manager.getProfiles(currentContentType);
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
            PreloaderDialog preloaderDialog = new PreloaderDialog(getActivity());
            preloaderDialog.showOnUiThread(R.string.installing_content);
            preloaderDialog.setProgress(1);
            // Capture the activity: installation can outlive the fragment.
            android.app.Activity hostActivity = getActivity();
            try {
                ContentsManager.OnInstallFinishedCallback callback = new ContentsManager.OnInstallFinishedCallback() {
                    private boolean isExtracting = true;

                    @Override
                    public void onFailed(ContentsManager.InstallFailedReason reason, Exception e) {
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
                        hostActivity.runOnUiThread(() -> ContentDialog.alert(getContext(), getString(R.string.install_failed) + ": " + getString(msgId), preloaderDialog::closeOnUiThread));
                    }

                    @Override
                    public void onSucceed(ContentProfile profile) {
                        if (isExtracting) {
                            ContentsManager.OnInstallFinishedCallback callback1 = this;
                            if (hostActivity == null) { preloaderDialog.closeOnUiThread(); return; }
                            hostActivity.runOnUiThread(() -> {
                                // The preloader is a fullscreen dialog. Keeping it open here
                                // placed the required content confirmation behind it, making
                                // installation look frozen forever after extraction.
                                preloaderDialog.close();
                                ContentInfoDialog dialog = new ContentInfoDialog(getContext(), profile);
                                ((TextView) dialog.findViewById(R.id.BTConfirm)).setText(R.string._continue);
                                dialog.setOnConfirmCallback(() -> {
                                    isExtracting = false;
                                    List<ContentProfile.ContentFile> untrustedFiles = manager.getUnTrustedContentFiles(profile);
                                    if (!untrustedFiles.isEmpty()) {
                                        ContentUntrustedDialog untrustedDialog = new ContentUntrustedDialog(getContext(), untrustedFiles);
                                        untrustedDialog.setOnCancelCallback(preloaderDialog::closeOnUiThread);
                                        untrustedDialog.setOnConfirmCallback(() -> {
                                            preloaderDialog.show(R.string.installing_content);
                                            preloaderDialog.setProgress(98);
                                            manager.finishInstallContent(profile, callback1);
                                        });
                                        untrustedDialog.show();
                                    } else {
                                        preloaderDialog.show(R.string.installing_content);
                                        preloaderDialog.setProgress(98);
                                        manager.finishInstallContent(profile, callback1);
                                    }
                                });
                                dialog.setOnCancelCallback(preloaderDialog::closeOnUiThread);
                                dialog.show();
                            });

                        } else {
                            preloaderDialog.closeOnUiThread();
                            if (hostActivity == null) return;
                            hostActivity.runOnUiThread(() -> {
                                ContentDialog.alert(getContext(), R.string.content_installed_success, null);
                                manager.syncContents();
                                boolean flashAfter = currentContentType == profile.type;
                                currentContentType = profile.type;
                                AppUtils.setSpinnerSelectionFromValue(sContentType, currentContentType.toString());
                                if (flashAfter) loadContentList();
                            });
                        }
                    }
                };
                Executors.newSingleThreadExecutor().execute(() -> {
                    manager.extraContentFile(contentUri, expectedProfile, callback,
                            preloaderDialog::setProgress);
                });
            } catch (Exception e) {
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
        PreloaderDialog preloader = new PreloaderDialog(requireActivity());
        preloader.show(R.string.downloading_content);
        preloader.setProgress(0);
        android.app.Activity hostActivity = getActivity();
        android.content.Context hostContext = requireContext().getApplicationContext();
        new Thread(() -> {
            String rawName = Uri.parse(url).getLastPathSegment();
            if (rawName == null || rawName.isEmpty()) rawName = "wrapper.tzst";
            int query = rawName.indexOf('?');
            if (query >= 0) rawName = rawName.substring(0, query);
            File output = new File(hostContext.getCacheDir(),
                    "remote-wrapper-" + System.currentTimeMillis() + "-" + rawName.replaceAll("[^A-Za-z0-9._-]", "_"));
            boolean downloaded = Downloader.downloadFile(url, output, preloader::setProgress);
            if (hostActivity == null || hostActivity.isFinishing()) {
                FileUtils.delete(output);
                return;
            }
            hostActivity.runOnUiThread(() -> {
                preloader.close();
                if (downloaded) promptAndInstallWrapper(Uri.fromFile(output), output);
                else {
                    FileUtils.delete(output);
                    ContentDialog.alert(requireContext(), R.string.download_failed, null);
                }
            });
        }).start();
    }

    private void promptAndInstallWrapper(Uri source, @Nullable File temporaryFile) {
        String sourceName = source.getLastPathSegment();
        if (sourceName == null) sourceName = "Wrapper";
        String initialName = ExternalDownloadCatalog.stripPackageSuffix(new File(sourceName).getName())
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
                })
                .setPositiveButton(android.R.string.ok, null)
                .create();
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
                    android.app.Activity hostActivity = getActivity();
                    new Thread(() -> {
                        String id = wrapperManager.install(source, name);
                        if (temporaryFile != null) FileUtils.delete(temporaryFile);
                        if (hostActivity == null || hostActivity.isFinishing()) return;
                        hostActivity.runOnUiThread(() -> {
                            preloader.close();
                            if (!isAdded()) return;
                            if (id == null) ContentDialog.alert(requireContext(), R.string.unable_to_install_wrapper, null);
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
        PreloaderDialog preloader = new PreloaderDialog(requireActivity());
        preloader.show(R.string.installing_content);
        preloader.setIndeterminate();
        android.app.Activity hostActivity = getActivity();
        new Thread(() -> {
            String id = driverManager.installDriver(source);
            if (temporaryFile != null) FileUtils.delete(temporaryFile);
            if (hostActivity == null || hostActivity.isFinishing()) return;
            hostActivity.runOnUiThread(() -> {
                preloader.close();
                if (!isAdded()) return;
                if (id == null || id.isEmpty())
                    ContentDialog.alert(requireContext(), R.string.unable_to_install_driver, null);
                else {
                    ContentDialog.alert(requireContext(), R.string.content_installed_success, null);
                    loadContentList();
                }
            });
        }).start();
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
            for (String id : wrapperManager.getInstalledIds()) {
                installed.add(new ExternalDownloadCatalog.Item(
                        CustomWrapperManager.toDisplayName(id), id, null));
            }
            showExternalItems(installed);
            return;
        }
        List<ContentProfile> profiles = manager.getProfiles(currentContentType);
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
            final ProgressBar progress;

            ViewHolder(@NonNull View view) {
                super(view);
                icon = view.findViewById(R.id.IVIcon);
                title = view.findViewById(R.id.TVVersionName);
                detail = view.findViewById(R.id.TVVersionCode);
                menu = view.findViewById(R.id.BTMenu);
                download = view.findViewById(R.id.BTDownload);
                progress = view.findViewById(R.id.Progress);
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
            holder.icon.setBackground(null);
            holder.icon.setImageResource(selectedCategory == CATEGORY_WRAPPERS
                    ? R.drawable.icon_settings : R.drawable.icon_debug);
            holder.title.setText(item.name);
            holder.detail.setText(item.detail);
            holder.detail.setVisibility(item.detail == null || item.detail.isEmpty() ? View.GONE : View.VISIBLE);
            holder.progress.setVisibility(View.GONE);
            holder.menu.setVisibility(installed ? View.VISIBLE : View.GONE);
            holder.download.setVisibility(installed ? View.GONE : View.VISIBLE);
            holder.menu.setOnClickListener(v -> ContentDialog.confirm(requireContext(),
                    R.string.do_you_want_to_remove_this_content, () -> {
                        if (selectedCategory == CATEGORY_WRAPPERS) wrapperManager.remove(item.detail);
                        else driverManager.removeDriver(item.detail);
                        loadContentList();
                    }));
            holder.download.setOnClickListener(v -> {
                holder.download.setVisibility(View.GONE);
                holder.progress.setVisibility(View.VISIBLE);
                holder.progress.setProgress(0);
                PreloaderDialog preloader = new PreloaderDialog(requireActivity());
                preloader.show(R.string.downloading_content);
                preloader.setProgress(0);
                android.app.Activity hostActivity = getActivity();
                android.content.Context hostContext = requireContext().getApplicationContext();
                new Thread(() -> {
                    File output = new File(hostContext.getCacheDir(),
                            "xclipse-driver-" + System.currentTimeMillis() + ".zip");
                    boolean downloaded = Downloader.downloadFile(item.url, output, progress -> {
                        preloader.setProgress(progress);
                        if (hostActivity != null) hostActivity.runOnUiThread(() -> holder.progress.setProgress(progress));
                    });
                    if (hostActivity == null || hostActivity.isFinishing()) {
                        FileUtils.delete(output);
                        return;
                    }
                    hostActivity.runOnUiThread(() -> {
                        preloader.close();
                        holder.progress.setVisibility(View.GONE);
                        holder.download.setVisibility(View.VISIBLE);
                        if (downloaded) installDriver(Uri.fromFile(output), output);
                        else {
                            FileUtils.delete(output);
                            ContentDialog.alert(requireContext(), R.string.download_failed, null);
                        }
                    });
                }).start();
            });
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
            private final ProgressBar progressBar;

            public ViewHolder(@NonNull View view) {
                super(view);

                ivIcon = view.findViewById(R.id.IVIcon);
                tvVersionName = view.findViewById(R.id.TVVersionName);
                tvVersionCode = view.findViewById(R.id.TVVersionCode);
                ibMenu = view.findViewById(R.id.BTMenu);
                ibDownload = view.findViewById(R.id.BTDownload);
                progressBar = view.findViewById(R.id.Progress);
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

            int iconId = switch (profile.type) {
                case CONTENT_TYPE_WINE -> R.drawable.icon_wine;
                case CONTENT_TYPE_PROTON -> R.drawable.icon_wine;
                default -> R.drawable.icon_settings;
            };
            holder.ivIcon.setBackground(null);
            holder.ivIcon.setImageResource(iconId);

            holder.tvVersionName.setText(getContext().getString(R.string.version) + ": " + profile.verName);
            holder.tvVersionCode.setText(getContext().getString(R.string.version_code) + ": " + profile.verCode);
            holder.ibMenu.setVisibility(profile.remoteUrl == null ? View.VISIBLE : View.GONE);
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
                            if (profile.type == ContentProfile.ContentType.CONTENT_TYPE_WINE || profile.type == ContentProfile.ContentType.CONTENT_TYPE_PROTON) {
                                ContainerManager containerManager = new ContainerManager(getContext());
                                for (Container container : containerManager.getContainers()) {
                                    if (container.getWineVersion().equals(ContentsManager.getEntryName(profile))) {
                                        ContentDialog.alert(getContext(), String.format(getString(R.string.unable_to_remove_content_since_container_using), container.getName()), null);
                                        return;
                                    }
                                }
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
            holder.ibDownload.setVisibility((profile.remoteUrl != null) && (holder.progressBar.getVisibility() == View.GONE) ? View.VISIBLE : View.GONE);
            holder.ibDownload.setOnClickListener(v -> {
                holder.ibDownload.setVisibility(View.GONE);
                holder.progressBar.setVisibility(View.VISIBLE);
                holder.progressBar.setProgress(0);

                PreloaderDialog downloadDialog = new PreloaderDialog(requireActivity());
                downloadDialog.show(R.string.downloading_content);
                downloadDialog.setProgress(0);

                Intent intent = new Intent();
                Context appContext = requireContext().getApplicationContext();
                new Thread(() -> {
                    long timestamp = System.currentTimeMillis();
                    File output = new File(appContext.getCacheDir(), "temp_" + timestamp);
                    boolean downloaded = Downloader.downloadFile(profile.remoteUrl, output, progress -> {
                        downloadDialog.setProgress(progress);
                        if (getActivity() != null) {
                            getActivity().runOnUiThread(() -> holder.progressBar.setProgress(progress));
                        }
                    });
                    if (downloaded) {
                        intent.setData(Uri.parse(output.getAbsolutePath()));
                        pendingRemoteProfiles.put(intent.getData().toString(), profile);
                    } else {
                        FileUtils.delete(output);
                    }
                    // The fragment can be detached while the download runs;
                    // getActivity() is then null and must not be dereferenced.
                    Activity host = getActivity();
                    if (host == null) return;
                    host.runOnUiThread(() -> {
                        downloadDialog.close();
                        holder.progressBar.setVisibility(View.GONE);
                        holder.ibDownload.setVisibility(View.VISIBLE);
                        if (downloaded) {
                            onActivityResult(MainActivity.OPEN_FILE_REQUEST_CODE, Activity.RESULT_OK, intent);
                        } else {
                            ContentDialog.alert(getContext(), R.string.download_failed, null);
                        }
                    });
                }).start();
            });
        }

        @Override
        public int getItemCount() {
            return data.size();
        }
    }
}
