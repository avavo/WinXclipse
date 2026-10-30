package com.winlator.cmod;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.Build;
import android.os.Environment;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.text.Editable;
import android.text.TextWatcher;
import android.text.format.Formatter;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.preference.PreferenceManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.winlator.cmod.container.Container;
import com.winlator.cmod.container.ContainerManager;
import com.winlator.cmod.container.Shortcut;
import com.winlator.cmod.contentdialog.ContentDialog;
import com.winlator.cmod.core.AppUtils;
import com.winlator.cmod.core.TarCompressorUtils;
import com.winlator.cmod.core.ExeIconExtractor;
import com.winlator.cmod.core.FileUtils;
import com.winlator.cmod.core.FileTransfer;
import com.winlator.cmod.core.StorageUsage;
import com.winlator.cmod.core.ShortcutArtworkManager;
import com.winlator.cmod.widget.FileProgressDialog;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public class FileManagerFragment extends Fragment {
    private static final int REQUEST_ADD_STORAGE_FOLDER = 1042;
    private static final String PREF_ADDED_STORAGE_FOLDERS = "file_manager_added_storage_folders";
    private File currentDir;
    private File currentRoot;
    private String rootLabel;
    private int rootIcon = R.drawable.ic_drive_storage;
    private int listingGeneration;
    private final AtomicBoolean transferActive = new AtomicBoolean(false);
    private RecyclerView recyclerViewFiles;
    private TextView tvCurrentPath;
    private TextView tvDriveName;
    private TextView tvNoItems;
    private ImageView ivDriveIcon;
    private ContainerManager containerManager;
    private FileProgressDialog fileProgressDialog;
    private static final List<File> clipboardFiles = new ArrayList<>();
    private static boolean isCutOperation;
    private com.google.android.material.floatingactionbutton.FloatingActionButton fabPaste;
    private final java.util.Set<File> selectedFiles = new java.util.HashSet<>();
    private final AtomicBoolean isCancelled = new AtomicBoolean(false);
    private long lastUIUpdateTime = 0;
    private String searchQuery = "";
    private SortOrder sortOrder = SortOrder.NAME_ASC;
    private boolean showHiddenFiles = false;
    private final android.os.Handler searchHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private Thread searchThread;
    private View pbSearch;

    private enum SortOrder { NAME_ASC, NAME_DESC, DATE_ASC, DATE_DESC, SIZE_ASC, SIZE_DESC }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        containerManager = new ContainerManager(getContext());
        if (savedInstanceState != null && savedInstanceState.containsKey("directory")) {
            currentDir = new File(savedInstanceState.getString("directory"));
            currentRoot = new File(savedInstanceState.getString("root", currentDir.getAbsolutePath()));
            rootLabel = savedInstanceState.getString("root_label");
            rootIcon = savedInstanceState.getInt("root_icon", R.drawable.ic_drive_storage);
        }
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.file_manager_fragment, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        fileProgressDialog = new FileProgressDialog(getActivity());
        fileProgressDialog.setOnCancelListener(() -> isCancelled.set(true));
        
        if (getActivity() instanceof AppCompatActivity activity) {
            if (activity.getSupportActionBar() != null) {
                MainActivity.setTabTitle(activity, R.string.file_manager);
            }
        }

        tvCurrentPath = view.findViewById(R.id.TVCurrentPath);
        tvDriveName = view.findViewById(R.id.TVDriveName);
        tvNoItems = view.findViewById(R.id.TVNoItems);
        ivDriveIcon = view.findViewById(R.id.IVDriveIcon);
        recyclerViewFiles = view.findViewById(R.id.RecyclerViewFiles);
        recyclerViewFiles.setLayoutManager(new LinearLayoutManager(getContext()));

        view.findViewById(R.id.BTUpDir).setOnClickListener(v -> navigateUp());
        view.findViewById(R.id.LLDriveSelect).setOnClickListener(v -> showDriveMenu());
        view.findViewById(R.id.BTNewFolder).setOnClickListener(v -> createNewFolder());
        view.findViewById(R.id.BTInfo).setOnClickListener(v -> showHelpDialog());

        View searchArea = view.findViewById(R.id.LLSearchArea);
        EditText etSearch = view.findViewById(R.id.ETSearch);
        pbSearch = view.findViewById(R.id.PBSearch);
        view.findViewById(R.id.BTSearch).setOnClickListener(v -> {
            searchArea.setVisibility(View.VISIBLE);
            etSearch.requestFocus();
            AppUtils.showKeyboard((AppCompatActivity) getActivity());
        });

        view.findViewById(R.id.BTCloseSearch).setOnClickListener(v -> {
            searchArea.setVisibility(View.GONE);
            etSearch.setText("");
            searchQuery = "";
            cancelSearch();
            loadFiles();
        });

        etSearch.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                searchQuery = s.toString();
                searchHandler.removeCallbacksAndMessages(null);
                searchHandler.postDelayed(() -> loadFiles(), 300);
            }
            @Override
            public void afterTextChanged(Editable s) {}
        });

        fabPaste = view.findViewById(R.id.fabPaste);
        fabPaste.setOnClickListener(v -> pasteFiles());
        fabPaste.setOnLongClickListener(v -> {
            clipboardFiles.clear();
            fabPaste.setVisibility(View.GONE);
            Toast.makeText(getContext(), "Clipboard cleared", Toast.LENGTH_SHORT).show();
            return true;
        });
        fabPaste.setVisibility(currentDir != null && !clipboardFiles.isEmpty() ? View.VISIBLE : View.GONE);

        requireActivity().getOnBackPressedDispatcher().addCallback(getViewLifecycleOwner(), new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                View searchArea = view.findViewById(R.id.LLSearchArea);
                if (searchArea != null && searchArea.getVisibility() == View.VISIBLE) {
                    view.findViewById(R.id.BTCloseSearch).performClick();
                } else if (!navigateUp()) {
                    setEnabled(false);
                    requireActivity().onBackPressed();
                }
            }
        });

        if (currentDir == null || !currentDir.isDirectory() || !currentDir.canRead()) showStorageHome();
        else {
            updateDriveButtonLabel(currentDir);
            loadFiles();
        }
        refreshStorageUsage();
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle out) {
        super.onSaveInstanceState(out);
        if (currentDir != null) {
            out.putString("directory", currentDir.getAbsolutePath());
            out.putString("root", currentRoot != null ? currentRoot.getAbsolutePath() : currentDir.getAbsolutePath());
            out.putString("root_label", rootLabel);
            out.putInt("root_icon", rootIcon);
        }
    }

    @Override public void onResume() {
        super.onResume();
        if (getView() != null) refreshStorageUsage();
    }

    @Override
    public void onDestroyView() {
        isCancelled.set(true);
        if (fileProgressDialog != null) fileProgressDialog.dismiss();
        if (getActivity() != null) getActivity().getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        super.onDestroyView();
        cancelSearch();
        searchHandler.removeCallbacksAndMessages(null);
    }

    private void loadFiles() {
        if (getView() == null) return;
        if (currentDir == null) { showStorageHome(); return; }
        if (!selectedFiles.isEmpty()) {
            selectedFiles.clear();
            updateSelectionUI();
        }
        tvCurrentPath.setText(currentDir.getAbsolutePath());
        cancelSearch();
        pbSearch.setVisibility(View.VISIBLE);
        
        final int generation = listingGeneration;
        final View loadingView = getView();
        final File dirToLoad = currentDir;
        final String query = searchQuery.trim().toLowerCase(java.util.Locale.ROOT);
        final boolean finalShowHidden = showHiddenFiles;
        
        searchThread = new Thread(() -> {
            List<File> fileList = new ArrayList<>();
            if (query.isEmpty()) {
                File[] files = dirToLoad.listFiles();
                if (files != null) {
                    for (File file : files) {
                        if (!finalShowHidden && file.getName().startsWith(".")) continue;
                        fileList.add(file);
                    }
                }
            } else {
                searchDeep(dirToLoad, query, fileList, finalShowHidden, new java.util.HashSet<>());
            }
            
            if (Thread.currentThread().isInterrupted()) return;
            
            sortFiles(fileList);
            
            if (getActivity() != null) {
                getActivity().runOnUiThread(() -> {
                    if (generation != listingGeneration || getView() != loadingView) return;
                    pbSearch.setVisibility(View.GONE);
                    recyclerViewFiles.setAdapter(new FileAdapter(fileList, !query.isEmpty()));
                    tvNoItems.setVisibility(fileList.isEmpty() ? View.VISIBLE : View.GONE);
                });
            }
        });
        searchThread.start();
    }

    private void cancelSearch() {
        listingGeneration++;
        if (searchThread != null) {
            searchThread.interrupt();
            searchThread = null;
        }
    }

    private void searchDeep(File directory, String query, List<File> results, boolean showHidden, java.util.Set<String> visited) {
        if (Thread.currentThread().isInterrupted()) return;
        File[] files = directory.listFiles();
        if (files == null) return;

        // First pass: local directory items
        for (File file : files) {
            if (Thread.currentThread().isInterrupted()) return;
            String name = file.getName();
            if (!showHidden && name.startsWith(".")) continue;
            
            if (name.toLowerCase(java.util.Locale.ROOT).contains(query)) {
                results.add(file);
            }
            if (results.size() >= 5000) return;
        }

        // Second pass: recursion
        for (File file : files) {
            if (Thread.currentThread().isInterrupted()) return;
            if (file.isDirectory()) {
                String name = file.getName();
                if (!showHidden && name.startsWith(".")) continue;
                
                try {
                    String canonicalPath = file.getCanonicalPath();
                    if (!visited.contains(canonicalPath)) {
                        visited.add(canonicalPath);
                        searchDeep(file, query, results, showHidden, visited);
                    }
                } catch (IOException ignored) {}
            }
            if (results.size() >= 5000) return;
        }
    }

    private void sortFiles(List<File> fileList) {
        Collections.sort(fileList, (f1, f2) -> {
            // Folders always at top
            if (f1.isDirectory() && !f2.isDirectory()) return -1;
            if (!f1.isDirectory() && f2.isDirectory()) return 1;
            
            int result = 0;
            switch (sortOrder) {
                case NAME_ASC: result = f1.getName().compareToIgnoreCase(f2.getName()); break;
                case NAME_DESC: result = f2.getName().compareToIgnoreCase(f1.getName()); break;
                case DATE_ASC: result = Long.compare(f1.lastModified(), f2.lastModified()); break;
                case DATE_DESC: result = Long.compare(f2.lastModified(), f1.lastModified()); break;
                case SIZE_ASC: 
                    if (f1.isDirectory()) result = f1.getName().compareToIgnoreCase(f2.getName());
                    else result = Long.compare(f1.length(), f2.length()); 
                    break;
                case SIZE_DESC: 
                    if (f1.isDirectory()) result = f2.getName().compareToIgnoreCase(f1.getName());
                    else result = Long.compare(f2.length(), f1.length()); 
                    break;
            }
            
            if (result == 0) result = f1.getName().compareToIgnoreCase(f2.getName());
            return result;
        });
    }

    private void navigateTo(File dir) {
        if (dir != null && dir.isDirectory() && dir.canRead()) {
            currentDir = dir;
            loadFiles();
        }
    }

    private boolean navigateUp() {
        if (currentDir == null) return false;
        if (currentRoot == null || currentDir.equals(currentRoot)) {
            showStorageHome();
            return true;
        }
        File parent = currentDir.getParentFile();
        if (parent != null && parent.canRead()) navigateTo(parent);
        else showStorageHome();
        return true;
    }

    private static class StorageEntry {
        final String title, detail;
        final int icon;
        final Runnable open;
        StorageEntry(String title, String detail, int icon, Runnable open) {
            this.title = title; this.detail = detail; this.icon = icon; this.open = open;
        }
    }

    private List<StorageEntry> storageEntries() {
        List<StorageEntry> entries = new ArrayList<>();
        File downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        File internal = Environment.getExternalStorageDirectory();
        File imagefs = com.winlator.cmod.xenvironment.ImageFs.find(requireContext()).getRootDir();
        entries.add(new StorageEntry(getString(R.string.drive_d), getString(R.string.storage_downloads),
                R.drawable.ic_drive_storage, () -> openStorage(downloads, getString(R.string.drive_d), R.drawable.ic_drive_storage)));
        entries.add(new StorageEntry(getString(R.string.drive_c), getString(R.string.storage_wine),
                R.drawable.ic_drive_wine, this::handleDriveCSelection));
        entries.add(new StorageEntry(getString(R.string.drive_z), getString(R.string.storage_system),
                R.drawable.ic_x11_icon, () -> openStorage(imagefs, getString(R.string.drive_z), R.drawable.ic_x11_icon)));
        entries.add(new StorageEntry(getString(R.string.drive_e), getString(R.string.storage_container_drives),
                R.drawable.ic_drive_storage, this::handleDriveESelection));
        entries.add(new StorageEntry(getString(R.string.storage_internal), internal.getAbsolutePath(),
                R.drawable.ic_drive_storage, () -> openStorage(internal, getString(R.string.storage_internal), R.drawable.ic_drive_storage)));
        for (Map.Entry<String, File> entry : getAdditionalStorageRoots().entrySet()) {
            entries.add(new StorageEntry(entry.getKey(), entry.getValue().getAbsolutePath(), R.drawable.ic_drive_storage,
                    () -> openStorage(entry.getValue(), entry.getKey(), R.drawable.ic_drive_storage)));
        }
        entries.add(new StorageEntry(getString(R.string.storage_add), getString(R.string.storage_add_detail),
                R.drawable.ic_nav_add_folder, this::addStorageFolder));
        return entries;
    }

    private void showStorageHome() {
        cancelSearch();
        searchHandler.removeCallbacksAndMessages(null);
        currentDir = null;
        currentRoot = null;
        rootLabel = null;
        selectedFiles.clear();
        updateSelectionUI();
        View view = getView();
        if (view == null) return;
        view.findViewById(R.id.LLSearchArea).setVisibility(View.GONE);
        view.findViewById(R.id.LLStorageUsage).setVisibility(View.VISIBLE);
        view.findViewById(R.id.BTSearch).setVisibility(View.GONE);
        view.findViewById(R.id.BTNewFolder).setVisibility(View.GONE);
        view.findViewById(R.id.BTUpDir).setEnabled(false);
        fabPaste.setVisibility(View.GONE);
        pbSearch.setVisibility(View.GONE);
        tvNoItems.setVisibility(View.GONE);
        tvCurrentPath.setText("");
        tvCurrentPath.setVisibility(View.GONE);
        tvDriveName.setText(R.string.storage_locations);
        ivDriveIcon.setImageResource(R.drawable.ic_drive_storage);
        List<StorageEntry> entries = storageEntries();
        recyclerViewFiles.setAdapter(new RecyclerView.Adapter<FileAdapter.ViewHolder>() {
            @NonNull @Override public FileAdapter.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int type) {
                return new FileAdapter.ViewHolder(LayoutInflater.from(parent.getContext()).inflate(R.layout.file_list_item, parent, false));
            }
            @Override public void onBindViewHolder(@NonNull FileAdapter.ViewHolder holder, int index) {
                StorageEntry entry = entries.get(index);
                holder.ivIcon.setImageResource(entry.icon);
                holder.tvFileName.setText(entry.title);
                holder.tvFileDetails.setText(entry.detail);
                holder.btFileMenu.setVisibility(View.GONE);
                holder.itemView.setOnClickListener(v -> entry.open.run());
            }
            @Override public int getItemCount() { return entries.size(); }
        });
        refreshStorageUsage();
    }

    private void openStorage(File root, String label, int icon) {
        if (!root.isDirectory() || !root.canRead()) {
            Toast.makeText(requireContext(), R.string.storage_unavailable, Toast.LENGTH_SHORT).show();
            return;
        }
        currentRoot = root;
        rootLabel = label;
        rootIcon = icon;
        searchQuery = "";
        ((EditText) requireView().findViewById(R.id.ETSearch)).setText("");
        searchHandler.removeCallbacksAndMessages(null);
        requireView().findViewById(R.id.LLSearchArea).setVisibility(View.GONE);
        requireView().findViewById(R.id.LLStorageUsage).setVisibility(View.GONE);
        requireView().findViewById(R.id.BTSearch).setVisibility(View.VISIBLE);
        requireView().findViewById(R.id.BTNewFolder).setVisibility(View.VISIBLE);
        requireView().findViewById(R.id.BTUpDir).setEnabled(true);
        fabPaste.setVisibility(clipboardFiles.isEmpty() ? View.GONE : View.VISIBLE);
        currentDir = root;
        updateDriveButtonLabel(root);
        loadFiles();
    }

    private void addStorageFolder() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        startActivityForResult(intent, REQUEST_ADD_STORAGE_FOLDER);
    }

    private void handleDriveESelection() {
        LinkedHashMap<String, File> roots = new LinkedHashMap<>();
        for (Container container : containerManager.getContainers()) {
            for (String[] drive : container.drivesIterator()) {
                File root = new File(drive[1]);
                if ("E".equalsIgnoreCase(drive[0]) && root.isDirectory() && root.canRead())
                    roots.put(container.getName() + " · " + root.getAbsolutePath(), root);
            }
        }
        if (roots.isEmpty()) {
            ContentDialog.alert(requireContext(), getString(R.string.storage_e_unavailable), null);
            return;
        }
        List<File> paths = new ArrayList<>(roots.values());
        ContentDialog.showSingleChoiceList(requireContext(), R.string.drive_e,
                roots.keySet().toArray(new String[0]), index -> openStorage(paths.get(index), getString(R.string.drive_e), R.drawable.ic_drive_storage));
    }

    private void showDriveMenu() {
        PopupMenu popup = new PopupMenu(requireContext(), tvDriveName);
        popup.getMenu().add(0, 1, 0, R.string.storage_locations);
        List<StorageEntry> entries = storageEntries();
        for (int i = 0; i < entries.size(); i++) popup.getMenu().add(0, 100 + i, i + 1, entries.get(i).title);
        if (currentDir != null) {
            popup.getMenu().add(0, 2, entries.size() + 1, R.string.sort);
            popup.getMenu().add(0, 3, entries.size() + 2, showHiddenFiles ? R.string.hide_hidden_files : R.string.show_hidden_files);
        }
        popup.setOnMenuItemClickListener(item -> {
            int id = item.getItemId();
            if (id == 1) showStorageHome();
            else if (id == 2) showSortMenu();
            else if (id == 3) { showHiddenFiles = !showHiddenFiles; loadFiles(); }
            else if (id >= 100 && id < 100 + entries.size()) entries.get(id - 100).open.run();
            return true;
        });
        popup.show();
    }

    private void refreshStorageUsage() {
        View view = getView();
        if (view == null) return;
        TextView label = view.findViewById(R.id.TVStorageUsage);
        try {
            android.os.StatFs stat = new android.os.StatFs(Environment.getExternalStorageDirectory().getAbsolutePath());
            StorageUsage usage = new StorageUsage(stat.getTotalBytes(), stat.getAvailableBytes());
            boolean percent = "percent".equals(PreferenceManager.getDefaultSharedPreferences(requireContext())
                    .getString(StorageUsage.PREFERENCE, "gb"));
            java.util.Locale locale = getResources().getConfiguration().getLocales().get(0);
            String used = String.format(locale, percent ? "%.1f%%" : "%.1f GB",
                    percent ? usage.usedPercent() : StorageUsage.gigabytes(usage.used));
            String free = String.format(locale, percent ? "%.1f%%" : "%.1f GB",
                    percent ? usage.availablePercent() : StorageUsage.gigabytes(usage.available));
            ((android.widget.ProgressBar) view.findViewById(R.id.PBStorageUsage)).setProgress(usage.progress());
            label.setText(getString(R.string.storage_used_free, used, free));
        } catch (RuntimeException error) {
            ((android.widget.ProgressBar) view.findViewById(R.id.PBStorageUsage)).setProgress(0);
            label.setText(R.string.storage_unavailable);
        }
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_ADD_STORAGE_FOLDER || resultCode != Activity.RESULT_OK
                || data == null || data.getData() == null) return;

        Uri uri = data.getData();
        try {
            requireContext().getContentResolver().takePersistableUriPermission(uri,
                    data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION
                            | Intent.FLAG_GRANT_WRITE_URI_PERMISSION));
        }
        catch (SecurityException ignored) {
            // MANAGE_EXTERNAL_STORAGE can still expose the resolved path.
        }

        String path = FileUtils.getFilePathFromUriUsingSAF(requireContext(), uri);
        File folder = path != null ? new File(path) : null;
        if (folder == null || !folder.isDirectory() || !folder.canRead()) {
            Toast.makeText(requireContext(), "This folder cannot be accessed directly.",
                    Toast.LENGTH_LONG).show();
            return;
        }

        SharedPreferences preferences = PreferenceManager
                .getDefaultSharedPreferences(requireContext());
        Set<String> stored = new LinkedHashSet<>(preferences.getStringSet(
                PREF_ADDED_STORAGE_FOLDERS, Collections.emptySet()));
        stored.add(folder.getAbsolutePath());
        preferences.edit().putStringSet(PREF_ADDED_STORAGE_FOLDERS, stored).apply();
        openStorage(folder, folder.getName(), R.drawable.ic_drive_storage);
    }

    /** Physical SD/USB volumes plus readable paths already assigned to a
     * container.  This keeps the standalone file manager and Wine drive list
     * consistent without hard-coding /storage/XXXX-XXXX mount names. */
    private LinkedHashMap<String, File> getAdditionalStorageRoots() {
        LinkedHashMap<String, File> roots = new LinkedHashMap<>();
        Context context = getContext();
        if (context != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                StorageManager manager = context.getSystemService(StorageManager.class);
                if (manager != null) {
                    for (StorageVolume volume : manager.getStorageVolumes()) {
                        if (volume.isPrimary()) continue;
                        File directory = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                                ? volume.getDirectory()
                                : volume.getUuid() != null ? new File("/storage", volume.getUuid()) : null;
                        if (directory != null && directory.isDirectory() && directory.canRead())
                            roots.put(getString(R.string.storage_external, volume.getDescription(context)), directory);
                    }
                }
            }
            catch (RuntimeException ignored) {
            }
        }
        for (Container container : containerManager.getContainers()) {
            for (String[] drive : container.drivesIterator()) {
                File directory = new File(drive[1]);
                if (directory.isDirectory() && directory.canRead()
                        && !directory.equals(Environment.getExternalStorageDirectory())
                        && !directory.equals(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)))
                    roots.putIfAbsent(getString(R.string.storage_mapped_drive, drive[0], container.getName()), directory);
            }
        }
        Context rootContext = getContext();
        if (rootContext != null) {
            Set<String> stored = PreferenceManager.getDefaultSharedPreferences(rootContext)
                    .getStringSet(PREF_ADDED_STORAGE_FOLDERS, Collections.emptySet());
            for (String path : stored) {
                File directory = new File(path);
                if (directory.isDirectory() && directory.canRead()) {
                    String name = directory.getName();
                    roots.putIfAbsent(getString(R.string.storage_added, name.isEmpty() ? path : name),
                            directory);
                }
            }
        }
        return roots;
    }

    private void showSortMenu() {
        PopupMenu popupMenu = new PopupMenu(requireContext(), tvDriveName);
        popupMenu.getMenu().add(0, 1, 0, R.string.by_name);
        popupMenu.getMenu().add(0, 2, 0, R.string.by_date);
        popupMenu.getMenu().add(0, 3, 0, R.string.by_size);

        popupMenu.setOnMenuItemClickListener(item -> {
            int itemId = item.getItemId();
            if (itemId == 1) sortOrder = sortOrder == SortOrder.NAME_ASC ? SortOrder.NAME_DESC : SortOrder.NAME_ASC;
            else if (itemId == 2) sortOrder = sortOrder == SortOrder.DATE_ASC ? SortOrder.DATE_DESC : SortOrder.DATE_ASC;
            else if (itemId == 3) sortOrder = sortOrder == SortOrder.SIZE_ASC ? SortOrder.SIZE_DESC : SortOrder.SIZE_ASC;
            loadFiles();
            return true;
        });
        popupMenu.show();
    }

    private void handleDriveCSelection() {
        ArrayList<Container> containers = containerManager.getContainers();
        if (containers.isEmpty()) {
            ContentDialog.alert(getContext(), "You need to create a container first to access Drive C:.", null);
            return;
        }

        String[] containerNames = new String[containers.size()];
        for (int i = 0; i < containers.size(); i++) containerNames[i] = containers.get(i).getName();

        ContentDialog.showSingleChoiceList(getContext(), R.string.select_container, containerNames, index -> {
            Container container = containers.get(index);
            File driveC = new File(container.getRootDir(), ".wine/drive_c");
            if (driveC.exists()) {
                openStorage(driveC, getString(R.string.drive_c) + " · " + container.getName(), R.drawable.ic_drive_wine);
            } else {
                ContentDialog.alert(getContext(), "The Wine system files for '" + container.getName() + "' have not been initialized yet.", null);
            }
        });
    }

    private void updateDriveButtonLabel(File file) {
        requireView().findViewById(R.id.LLStorageUsage).setVisibility(View.GONE);
        tvCurrentPath.setVisibility(View.VISIBLE);
        tvDriveName.setText(rootLabel != null ? rootLabel : getString(R.string.drives));
        ivDriveIcon.setImageResource(rootIcon);
    }

    private void showFileOptions(File file, View anchor) {
        PopupMenu popupMenu = new PopupMenu(requireContext(), anchor);
        String nameLower = file.getName().toLowerCase(java.util.Locale.ROOT);
        if (file.isDirectory()) {
            popupMenu.getMenu().add(0, 8, 0, R.string.new_folder);
        } else {
            if (nameLower.endsWith(".exe")) {
                popupMenu.getMenu().add(0, 1, 0, R.string.run);
                popupMenu.getMenu().add(0, 10, 0, "Create Shortcut");
            }
            if (nameLower.endsWith(".tar") || nameLower.endsWith(".tar.xz") || nameLower.endsWith(".tar.zst") ||
                nameLower.endsWith(".txz") || nameLower.endsWith(".tzst") || nameLower.endsWith(".zip")) {
                popupMenu.getMenu().add(0, 9, 0, "Extract");
            }
        }
        popupMenu.getMenu().add(0, 2, 0, R.string.rename);
        popupMenu.getMenu().add(0, 3, 0, "Delete");
        // Use literal English labels to avoid mixed auto-translation (Copy was showing as Copiar)
        popupMenu.getMenu().add(0, 4, 0, "Copy");
        popupMenu.getMenu().add(0, 5, 0, "Cut");
        // Dedicated Move for Drive C (same as Cut but labelled Move for clarity inside the emulated C:)
        boolean isDriveCFile = file.getAbsolutePath().contains(".wine/drive_c")
                || currentDir.getAbsolutePath().contains(".wine/drive_c");
        if (isDriveCFile) popupMenu.getMenu().add(0, 12, 0, "Move");
        if (!selectedFiles.isEmpty()) popupMenu.getMenu().add(0, 11, 0, "Apply to selected (" + selectedFiles.size() + ")");
        popupMenu.getMenu().add(0, 6, 0, R.string.share);
        popupMenu.getMenu().add(0, 7, 0, R.string.properties);

        popupMenu.setOnMenuItemClickListener(item -> {
            int itemId = item.getItemId();
            if (itemId == 1) {
                selectContainerForFile(file);
                return true;
            } else if (itemId == 10) {
                selectContainerForShortcut(file);
                return true;
            } else if (itemId == 2) {
                renameFile(file);
                return true;
            } else if (itemId == 3) {
                removeFiles(Collections.singletonList(file));
                return true;
            } else if (itemId == 4) {
                copyFiles(Collections.singletonList(file));
                return true;
            } else if (itemId == 5) {
                cutFiles(Collections.singletonList(file));
                return true;
            } else if (itemId == 11) {
                showSelectionOptions(anchor);
                return true;
            } else if (itemId == 12) {
                // Move inside Drive C – same operation as Cut, just clearer label
                cutFiles(Collections.singletonList(file));
                Toast.makeText(getContext(), "Cut for move: choose destination then tap Paste", Toast.LENGTH_SHORT).show();
                return true;
            } else if (itemId == 6) {
                shareFile(file);
                return true;
            } else if (itemId == 7) {
                showProperties(file);
                return true;
            } else if (itemId == 8) {
                createNewFolder(file);
                return true;
            } else if (itemId == 9) {
                extractArchive(file);
                return true;
            }
            return false;
        });
        popupMenu.show();
    }

    private void selectContainerForFile(File file) {
        ArrayList<Container> containers = containerManager.getContainers();
        if (containers.isEmpty()) {
            ContentDialog.alert(getContext(), "You need to create a container first to run .exe files.", null);
            return;
        }
        if (containers.size() == 1) {
            runFileDirectly(file, containers.get(0));
            return;
        }

        String[] containerNames = new String[containers.size()];
        for (int i = 0; i < containers.size(); i++) containerNames[i] = containers.get(i).getName();

        ContentDialog.showSingleChoiceList(getContext(), R.string.select_container, containerNames, index -> {
            Container container = containers.get(index);
            runFileDirectly(file, container);
        });
    }

    private void selectContainerForShortcut(File file) {
        ArrayList<Container> containers = containerManager.getContainers();
        if (containers.isEmpty()) {
            ContentDialog.alert(getContext(), "You need to create a container first to create a shortcut.", null);
            return;
        }
        if (containers.size() == 1) {
            createShortcut(file, containers.get(0));
            return;
        }

        String[] containerNames = new String[containers.size()];
        for (int i = 0; i < containers.size(); i++) containerNames[i] = containers.get(i).getName();

        ContentDialog.showSingleChoiceList(getContext(), R.string.select_container, containerNames, index -> {
            Container container = containers.get(index);
            createShortcut(file, container);
        });
    }

    private void createShortcut(File file, Container container) {
        try {
            String displayName = getSmartDisplayName(file);
            String execPath = toDesktopWindowsPath(file, container);
            String workDir = toDesktopPath(file, container);
            
            File desktopDir = container.getDesktopDir();
            if (!desktopDir.exists() && !desktopDir.mkdirs()) {
                Toast.makeText(getContext(), "Could not create desktop directory", Toast.LENGTH_SHORT).show();
                return;
            }
            
            File desktopFile = new File(desktopDir, displayName + ".desktop");
            String escapedExecPath = com.winlator.cmod.core.StringUtils.escapeFileDOSPath(execPath);
            String winePrefix = getContainerWineHome(container) + "/.wine";
            String content = "[Desktop Entry]\n"
                    + "Name=" + displayName + "\n"
                    + "Exec=env WINEPREFIX=\"" + winePrefix + "\" wine " + escapedExecPath + "\n"
                    + "Type=Application\n"
                    + (!workDir.isEmpty() ? "Path=" + workDir + "\n" : "")
                    + "Icon=" + displayName + "\n"
                    + "container_id=" + container.id + "\n";
            if (!Shortcut.writeDesktopFileWithBackup(desktopFile, content)) {
                Toast.makeText(getContext(), "Failed to create shortcut", Toast.LENGTH_SHORT).show();
                return;
            }
            Toast.makeText(getContext(), "Shortcut created on container desktop!", Toast.LENGTH_SHORT).show();

            if (file.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".exe")) {
                File iconDir64 = container.getIconsDir(64);
                if (!iconDir64.exists()) iconDir64.mkdirs();
                File iconDest = new File(iconDir64, displayName + ".png");
                ExeIconExtractor.extractAsync(file, iconDest, false, null);
            }

            // New shortcuts always start in browser artwork mode and fetch
            // the cover immediately (forced: bypasses the already-fetched
            // checks), instead of waiting for a later list bind that may
            // never run or hit the session negative cache.
            try {
                Shortcut artworkShortcut = new Shortcut(container, desktopFile);
                ShortcutArtworkManager.setMode(artworkShortcut,
                        ShortcutArtworkManager.MODE_BROWSER);
                Context artworkContext = getContext();
                if (artworkContext != null) {
                    ShortcutArtworkManager.ensure(artworkContext, artworkShortcut, true, null);
                }
            }
            catch (Exception ignored) {}
        } catch (Exception e) {
            Toast.makeText(getContext(), "Failed to create shortcut", Toast.LENGTH_SHORT).show();
        }
    }

    private String getSmartDisplayName(File file) {
        String name = file.getName();
        int extIndex = name.lastIndexOf(".");
        if (extIndex > 0) name = name.substring(0, extIndex);
        return name;
    }

    private String getContainerWineHome(Container container) {
        File imagefs = new File(requireContext().getFilesDir(), "imagefs");
        String rootPath = container.getRootDir().getAbsolutePath();
        String imagefsPath = imagefs.getAbsolutePath();
        if (rootPath.startsWith(imagefsPath)) {
            return rootPath.substring(imagefsPath.length());
        }
        return "/home/" + com.winlator.cmod.xenvironment.ImageFs.USER;
    }

    private String toDesktopWindowsPath(File file, Container container) {
        String filePath = file.getAbsolutePath();
        File driveC = new File(container.getRootDir(), ".wine/drive_c");
        String driveCPath = driveC.getAbsolutePath();
        
        if (filePath.startsWith(driveCPath)) {
            String rel = filePath.substring(driveCPath.length()).replace('/', '\\');
            if (rel.startsWith("\\")) rel = rel.substring(1);
            return "C:\\" + rel;
        }

        for (String[] drive : container.drivesIterator()) {
            String driveLetter = drive[0].toUpperCase();
            String drivePath = drive[1];
            if (filePath.startsWith(drivePath)) {
                String rel = filePath.substring(drivePath.length()).replace('/', '\\');
                if (rel.startsWith("\\")) rel = rel.substring(1);
                return driveLetter + ":\\" + rel;
            }
        }
        
        return "Z:" + filePath.replace('/', '\\');
    }

    private String toDesktopPath(File file, Container container) {
        File parent = file.getParentFile();
        if (parent == null) return "";
        String parentPath = parent.getAbsolutePath();

        for (String[] drive : container.drivesIterator()) {
            String driveLetter = drive[0].toLowerCase(java.util.Locale.ROOT);
            String drivePath = drive[1];
            if (parentPath.startsWith(drivePath)) {
                String rel = parentPath.substring(drivePath.length());
                if (rel.startsWith("/")) rel = rel.substring(1);
                String basePath = getContainerWineHome(container) + "/.wine/dosdevices/" + driveLetter + ":";
                return rel.isEmpty() ? basePath : basePath + "/" + rel;
            }
        }
        return parentPath;
    }

    private void runFileDirectly(File file, Container container) {
        Context context = getContext();
        if (context != null) {
            Intent intent = new Intent(context, XServerDisplayActivity.class);
            intent.putExtra("container_id", container.id);
            intent.putExtra("shortcut_path", file.getPath());
            context.startActivity(intent);
        }
    }

    private void renameFile(File file) {
        ContentDialog.prompt(getContext(), R.string.rename, file.getName(), newName -> {
            File newFile = new File(file.getParentFile(), newName);
            if (file.renameTo(newFile)) {
                loadFiles();
            } else {
                Toast.makeText(getContext(), "Rename failed", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private boolean deleteRecursiveSafe(File file, AtomicLong deletedCount, long totalItems) {
        if (isCancelled.get()) return false;

        if (!com.winlator.cmod.core.FileUtils.isSymlink(file) && file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    if (!deleteRecursiveSafe(child, deletedCount, totalItems)) return false;
                }
            }
        }

        String fileName = file.getName();
        boolean deleted = file.delete();
        if (deleted) {
            long count = deletedCount.incrementAndGet();
            long currentTime = System.currentTimeMillis();
            if (currentTime - lastUIUpdateTime > 100) {
                fileProgressDialog.update(fileName, count, totalItems, count + " / " + totalItems + " items");
                lastUIUpdateTime = currentTime;
            }
        }
        return deleted;
    }

    private void copyFiles(java.util.Collection<File> files) {
        clipboardFiles.clear();
        clipboardFiles.addAll(files);
        isCutOperation = false;
        fabPaste.setVisibility(View.VISIBLE);
        Toast.makeText(getContext(), "Copied " + files.size() + " items", Toast.LENGTH_SHORT).show();
        clearSelection();
    }

    private void cutFiles(java.util.Collection<File> files) {
        clipboardFiles.clear();
        clipboardFiles.addAll(files);
        isCutOperation = true;
        fabPaste.setVisibility(View.VISIBLE);
        Toast.makeText(getContext(), "Cut " + files.size() + " items", Toast.LENGTH_SHORT).show();
        clearSelection();
    }

    private void clearSelection() {
        selectedFiles.clear();
        updateSelectionUI();
        if (recyclerViewFiles.getAdapter() != null) {
            recyclerViewFiles.getAdapter().notifyDataSetChanged();
        }
    }

    private void updateSelectionUI() {
        if (getActivity() instanceof AppCompatActivity activity && activity.getSupportActionBar() != null) {
            if (selectedFiles.isEmpty()) {
                MainActivity.setTabTitle(activity, R.string.file_manager);
            } else {
                activity.getSupportActionBar().setTitle(selectedFiles.size() + " selected");
            }
        }
    }

    private void pasteFiles() {
        if (currentDir == null || transferActive.get()) return;
        if (clipboardFiles.isEmpty()) {
            fabPaste.setVisibility(View.GONE);
            return;
        }

        int conflictCount = 0;
        String singleConflictName = null;
        for (File source : new ArrayList<>(clipboardFiles)) {
            File destination = new File(currentDir, source.getName());
            if (pathExists(destination) && !sameCanonicalPath(source, destination)) {
                conflictCount++;
                singleConflictName = source.getName();
            }
        }

        if (conflictCount == 0) {
            executePaste(currentDir, false);
            return;
        }

        ContentDialog dialog = new ContentDialog(requireContext());
        dialog.setTitle(R.string.replace_existing_title);
        dialog.setMessage(conflictCount == 1
                ? getString(R.string.replace_existing_single, singleConflictName)
                : getString(R.string.replace_existing_multiple, conflictCount));
        ((TextView) dialog.findViewById(R.id.BTConfirm)).setText(R.string.replace);
        dialog.setOnConfirmCallback(() -> executePaste(currentDir, true));
        dialog.show();
    }

    private void executePaste(File destinationDir, boolean replaceExisting) {
        if (!transferActive.compareAndSet(false, true)) return;
        final boolean moveOperation = isCutOperation;
        final List<File> sources = new ArrayList<>(clipboardFiles);
        final View operationView = getView();
        final Activity owner = requireActivity();
        final Context context = requireContext();
        final FileProgressDialog progress = fileProgressDialog;
        progress.show(moveOperation ? R.string.moving_file : R.string.copying_file);
        isCancelled.set(false);
        AppUtils.keepScreenOn(owner);
        new Thread(() -> {
            List<File> completed = new ArrayList<>();
            List<String> failures = new ArrayList<>();
            long[] lastUpdate = {0};
            int[] index = {0};
            FileTransfer transfer = new FileTransfer(isCancelled, (name, bytes) -> {
                long now = android.os.SystemClock.uptimeMillis();
                if (now - lastUpdate[0] >= 100) {
                    progress.updateIndeterminate(name, context.getString(R.string.file_transfer_progress,
                            Formatter.formatFileSize(context, bytes), index[0] + 1, sources.size()));
                    lastUpdate[0] = now;
                }
            });
            try {
                for (File source : sources) {
                    if (isCancelled.get()) break;
                    progress.updateIndeterminate(source.getName(), context.getString(R.string.file_transfer_item, index[0] + 1, sources.size()));
                    try {
                        FileTransfer.Result result = transfer.transfer(source, new File(destinationDir, source.getName()), moveOperation, replaceExisting);
                        if (!moveOperation || result.sourceRemoved) completed.add(source);
                        else if (pathExists(source)) failures.add(source.getName());
                        if (result.retainedBackup != null) failures.add(result.retainedBackup.getAbsolutePath());
                        if (result.retainedSource != null) failures.add(result.retainedSource.getAbsolutePath());
                    } catch (IOException | RuntimeException error) {
                        android.util.Log.e("FileManager", "Transfer failed: " + source, error);
                        if (!isCancelled.get()) failures.add(source.getName());
                    }
                    index[0]++;
                }
            } finally {
                transferActive.set(false);
                owner.runOnUiThread(() -> {
                    owner.getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                    progress.dismiss();
                    if (moveOperation) clipboardFiles.removeAll(completed);
                    if (getView() != operationView || !isAdded()) return;
                    fabPaste.setVisibility(currentDir != null && !clipboardFiles.isEmpty() ? View.VISIBLE : View.GONE);
                    if (!failures.isEmpty()) ContentDialog.alert(context,
                            (isCancelled.get() ? context.getString(R.string.file_operation_cancelled) + "\n\n" : "")
                                    + context.getString(R.string.file_transfer_errors, String.join("\n", failures)), null);
                    else if (isCancelled.get()) Toast.makeText(context, R.string.file_operation_cancelled, Toast.LENGTH_SHORT).show();
                    loadFiles();
                    refreshStorageUsage();
                });
            }
        }, "FileTransfer").start();
    }

    private void removeFiles(java.util.Collection<File> files) {
        ContentDialog.confirm(getContext(), "Do you want to delete " + files.size() + " items?", () -> {
            if (!transferActive.compareAndSet(false, true)) return;
            fileProgressDialog.show(R.string.deleting_file);
            isCancelled.set(false);
            if (getActivity() != null) AppUtils.keepScreenOn(getActivity());

            new Thread(() -> {
                AtomicLong deletedCount = new AtomicLong(0);
                long totalItems = 0;
                List<File> sources = new ArrayList<>(files);
                
                for (File file : sources) {
                    if (file.isDirectory() && !com.winlator.cmod.core.FileUtils.isSymlink(file)) {
                        long[] info = getDirectoryInfo(file, new java.util.HashSet<>());
                        totalItems += info[1] + info[2] + 1;
                    } else {
                        totalItems += 1;
                    }
                }

                final long finalTotal = totalItems;
                boolean allSuccess = true;
                for (File file : sources) {
                    if (isCancelled.get()) {
                        allSuccess = false;
                        break;
                    }
                    if (!deleteRecursiveSafe(file, deletedCount, finalTotal)) allSuccess = false;
                }

                final boolean finalSuccess = allSuccess;
                transferActive.set(false);
                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> {
                        if (getActivity() != null) getActivity().getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                        fileProgressDialog.dismiss();
                        clearSelection();
                        if (isCancelled.get()) {
                            Toast.makeText(getContext(), "Operation cancelled", Toast.LENGTH_SHORT).show();
                        } else if (finalSuccess) {
                            loadFiles();
                        } else {
                            Toast.makeText(getContext(), "Remove failed for some items", Toast.LENGTH_SHORT).show();
                            loadFiles();
                        }
                    });
                }
            }).start();
        });
    }

    private void showSelectionOptions(View anchor) {
        PopupMenu popupMenu = new PopupMenu(requireContext(), anchor);
        popupMenu.getMenu().add(0, 1, 0, "Copy");
        popupMenu.getMenu().add(0, 2, 0, "Cut");
        // Also expose Move when selection is inside Drive C
        boolean anyOnDriveC = false;
        for (File f : selectedFiles) if (f.getAbsolutePath().contains(".wine/drive_c")) { anyOnDriveC = true; break; }
        if (anyOnDriveC) popupMenu.getMenu().add(0, 5, 0, "Move");
        popupMenu.getMenu().add(0, 3, 0, "Delete");
        popupMenu.getMenu().add(0, 4, 0, "Clear Selection");

        popupMenu.setOnMenuItemClickListener(item -> {
            int itemId = item.getItemId();
            if (itemId == 1) copyFiles(new ArrayList<>(selectedFiles));
            else if (itemId == 2) cutFiles(new ArrayList<>(selectedFiles));
            else if (itemId == 5) { cutFiles(new ArrayList<>(selectedFiles)); Toast.makeText(getContext(), "Cut for move: choose destination then tap Paste", Toast.LENGTH_SHORT).show(); }
            else if (itemId == 3) removeFiles(new ArrayList<>(selectedFiles));
            else if (itemId == 4) clearSelection();
            return true;
        });
        popupMenu.show();
    }

    private boolean pathExists(File file) {
        return java.nio.file.Files.exists(file.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS);
    }

    private boolean sameCanonicalPath(File first, File second) {
        try {
            return first.getAbsoluteFile().getParentFile().getCanonicalFile().equals(
                    second.getAbsoluteFile().getParentFile().getCanonicalFile()) && first.getName().equals(second.getName());
        } catch (IOException error) { return first.getAbsoluteFile().equals(second.getAbsoluteFile()); }
    }

    private void createNewFolder() {
        createNewFolder(currentDir);
    }

    private void createNewFolder(File parentDir) {
        ContentDialog.prompt(getContext(), R.string.new_folder, "", folderName -> {
            File newDir = new File(parentDir, folderName);
            if (!newDir.exists() && newDir.mkdirs()) {
                loadFiles();
            } else {
                Toast.makeText(getContext(), "Failed to create folder", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void shareFile(File file) {
        try {
            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType("*/*");
            intent.putExtra(Intent.EXTRA_STREAM, androidx.core.content.FileProvider.getUriForFile(requireContext(), requireContext().getPackageName() + ".fileprovider", file));
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(intent, getString(R.string.share)));
        } catch (Exception e) {
            Toast.makeText(getContext(), "Failed to share file", Toast.LENGTH_SHORT).show();
        }
    }

    private void showProperties(File file) {
        if (file.isFile()) {
            displayProperties(file, file.length(), 0, 0);
        } else {
            Toast.makeText(getContext(), "Calculating folder size...", Toast.LENGTH_SHORT).show();
            new Thread(() -> {
                long[] info = getDirectoryInfo(file, new java.util.HashSet<>());
                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> displayProperties(file, info[0], info[1], info[2]));
                }
            }).start();
        }
    }

    private void showHelpDialog() {
        String html = "<b>Navigation:</b><br/>" +
                "• <b>Tap Folder:</b> Open and navigate into it.<br/>" +
                "• <b>Tap File:</b> Open actions menu (Run, Rename, Delete, etc.).<br/>" +
                "• <b>Back Gesture:</b> Go up one folder level.<br/><br/>" +
                "<b>Selection & Clipboard:</b><br/>" +
                "• <b>Long Press:</b> Start multi-selecting items.<br/>" +
                "• <b>Hold Selected:</b> Open menu for all selected items.<br/>" +
                "• <b>Hold Paste Button:</b> Clear the clipboard.<br/><br/>" +
                "<b>Features:</b><br/>" +
                "• <b>Drive Selector:</b> Switch between D: (Downloads), Internal Storage, C: (Wine), and Z: (System).<br/>" +
                "• <b>Sorting & View:</b> Change sort order or toggle hidden files in the Drive menu.<br/>" +
                "• <b>Archives:</b> Extract files into a new folder named after the archive.<br/>" +
                "• <b>Search:</b> Deep search files in the current folder.";

        ContentDialog dialog = new ContentDialog(requireContext());
        dialog.setTitle("Usage Instructions");
        dialog.setMessage(html);
        dialog.findViewById(R.id.BTCancel).setVisibility(View.GONE);
        dialog.show();
    }

    private void extractArchive(File file) {
        String nameLower = file.getName().toLowerCase(java.util.Locale.ROOT);
        TarCompressorUtils.Type type = null;
        boolean isZip = nameLower.endsWith(".zip");
        if (nameLower.endsWith(".tar.xz") || nameLower.endsWith(".txz")) type = TarCompressorUtils.Type.XZ;
        else if (nameLower.endsWith(".tar.zst") || nameLower.endsWith(".tzst")) type = TarCompressorUtils.Type.ZSTD;

        String folderName = file.getName().replaceAll("(?i)\\.(tar\\.xz|tar\\.zst|tar|txz|tzst|zip)$", "");
        if (folderName.equals(file.getName())) folderName += "_extracted";
        File outputDir = new File(currentDir, folderName);
        if (!outputDir.exists()) outputDir.mkdirs();

        Toast.makeText(getContext(), "Extracting to " + folderName + "...", Toast.LENGTH_SHORT).show();
        
        TarCompressorUtils.Type finalType = type;
        new Thread(() -> {
            boolean success;
            if (isZip) {
                success = TarCompressorUtils.extractZip(file, outputDir);
            } else if (finalType != null) {
                success = TarCompressorUtils.extract(finalType, file, outputDir);
            } else {
                success = TarCompressorUtils.extractTar(file, outputDir, null);
            }

            if (getActivity() != null) {
                getActivity().runOnUiThread(() -> {
                    if (success) {
                        Toast.makeText(getContext(), "Extraction complete", Toast.LENGTH_SHORT).show();
                        loadFiles();
                    } else {
                        Toast.makeText(getContext(), "Extraction failed", Toast.LENGTH_SHORT).show();
                    }
                });
            }
        }).start();
    }

    private void displayProperties(File file, long totalSize, long totalDirs, long totalFiles) {
        if (getContext() == null) return;
        StringBuilder sb = new StringBuilder();
        sb.append(getString(R.string.name)).append(": ").append(file.getName()).append("\n");
        sb.append("Path: ").append(file.getAbsolutePath()).append("\n");

        sb.append(getString(R.string.size)).append(": ").append(Formatter.formatFileSize(getContext(), totalSize));
        sb.append(" (").append(java.text.NumberFormat.getInstance().format(totalSize)).append(" bytes)\n");

        if (file.isDirectory()) {
            sb.append("Contents: ").append(totalDirs).append(" folders, ").append(totalFiles).append(" files\n");
        } else {
            sb.append("Executable: ").append(file.canExecute() ? "Yes" : "No").append("\n");
        }

        sb.append("Last Modified: ").append(java.text.DateFormat.getDateTimeInstance().format(new java.util.Date(file.lastModified())));

        ContentDialog dialog = new ContentDialog(requireContext());
        dialog.setTitle(R.string.properties);
        dialog.setMessage(sb.toString());
        dialog.findViewById(R.id.BTCancel).setVisibility(View.GONE);
        dialog.show();
    }

    private long[] getDirectoryInfo(File directory, java.util.Set<String> visited) {
        long totalSize = 0;
        long totalDirs = 0;
        long totalFiles = 0;

        try {
            String canonicalPath = directory.getCanonicalPath();
            if (visited.contains(canonicalPath)) return new long[]{0, 0, 0};
            visited.add(canonicalPath);
        } catch (java.io.IOException e) {
            return new long[]{0, 0, 0};
        }

        File[] files = directory.listFiles();
        if (files != null) {
            for (File file : files) {
                if (FileUtils.isSymlink(file)) {
                    totalFiles++;
                } else if (file.isDirectory()) {
                    totalDirs++;
                    long[] subDirInfo = getDirectoryInfo(file, visited);
                    totalSize += subDirInfo[0];
                    totalDirs += subDirInfo[1];
                    totalFiles += subDirInfo[2];
                } else {
                    totalSize += file.length();
                    totalFiles++;
                }
            }
        }
        return new long[]{totalSize, totalDirs, totalFiles};
    }

    private class FileAdapter extends RecyclerView.Adapter<FileAdapter.ViewHolder> {
        private final List<File> files;
        private final boolean isSearchMode;

        public FileAdapter(List<File> files) {
            this(files, false);
        }

        public FileAdapter(List<File> files, boolean isSearchMode) {
            this.files = files;
            this.isSearchMode = isSearchMode;
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new ViewHolder(LayoutInflater.from(parent.getContext()).inflate(R.layout.file_list_item, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            File file = files.get(position);
            holder.tvFileName.setText(file.getName());
            
            String nameLower = file.getName().toLowerCase(java.util.Locale.ROOT);
            if (file.isDirectory()) {
                holder.ivIcon.setImageResource(R.drawable.ic_folder);
                if (isSearchMode) {
                    holder.tvFileDetails.setText(getRelativePath(file));
                } else {
                    String[] list = file.list();
                    int itemCount = list != null ? list.length : 0;
                    holder.tvFileDetails.setText(getString(R.string.num_items, itemCount));
                }
            } else {
                if (nameLower.endsWith(".exe")) holder.ivIcon.setImageResource(R.drawable.ic_drive_wine);
                else if (nameLower.endsWith(".so") || nameLower.contains(".so."))
                    holder.ivIcon.setImageResource(R.drawable.ic_settings);
                else if (nameLower.endsWith(".txz") || nameLower.endsWith(".tzst") || nameLower.endsWith(".tar") || nameLower.endsWith(".zip"))
                    holder.ivIcon.setImageResource(R.drawable.ic_picture_in_picture_alt);
                else holder.ivIcon.setImageResource(R.drawable.ic_file);

                if (isSearchMode) {
                    holder.tvFileDetails.setText(getRelativePath(file));
                } else {
                    holder.tvFileDetails.setText(Formatter.formatFileSize(getContext(), file.length()));
                }
            }

            if (com.winlator.cmod.core.FileUtils.isSymlink(file)) {
                holder.ivIcon.setAlpha(0.5f);
            } else {
                holder.ivIcon.setAlpha(1.0f);
            }

            if (selectedFiles.contains(file)) {
                holder.itemView.setBackgroundColor(0x332196f3);
            } else {
                holder.itemView.setBackgroundColor(0);
            }

            holder.btFileMenu.setVisibility(View.GONE);

            holder.itemView.setOnClickListener(v -> {
                if (!selectedFiles.isEmpty()) {
                    toggleSelection(file, position);
                } else if (file.isDirectory()) {
                    navigateTo(file);
                } else {
                    showFileOptions(file, v);
                }
            });

            holder.itemView.setOnLongClickListener(v -> {
                if (selectedFiles.contains(file)) {
                    showFileOptions(file, v);
                } else {
                    toggleSelection(file, position);
                }
                return true;
            });
        }

        private void toggleSelection(File file, int position) {
            if (selectedFiles.contains(file)) {
                selectedFiles.remove(file);
            } else {
                selectedFiles.add(file);
            }
            notifyItemChanged(position);
            updateSelectionUI();
        }

        private String getRelativePath(File file) {
            String path = file.getAbsolutePath();
            String rootPath = currentDir.getAbsolutePath();
            if (path.startsWith(rootPath)) {
                String rel = path.substring(rootPath.length());
                if (rel.startsWith("/")) rel = rel.substring(1);
                return rel.isEmpty() ? "./" : rel;
            }
            return path;
        }

        @Override
        public int getItemCount() {
            return files.size();
        }

        static class ViewHolder extends RecyclerView.ViewHolder {
            ImageView ivIcon;
            TextView tvFileName;
            TextView tvFileDetails;
            ImageView btFileMenu;

            public ViewHolder(@NonNull View itemView) {
                super(itemView);
                ivIcon = itemView.findViewById(R.id.IVIcon);
                tvFileName = itemView.findViewById(R.id.TVFileName);
                tvFileDetails = itemView.findViewById(R.id.TVFileDetails);
                btFileMenu = itemView.findViewById(R.id.BTFileMenu);
            }
        }
    }
}
