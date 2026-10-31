package com.winlator.cmod;

import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.text.format.Formatter;
import android.view.*;
import android.widget.*;
import androidx.activity.OnBackPressedCallback;
import androidx.annotation.*;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.*;
import com.winlator.cmod.contentdialog.ContentDialog;
import com.winlator.cmod.core.*;
import com.winlator.cmod.widget.FileProgressDialog;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Browses a user-granted document tree without trying to convert its IDs into paths. */
public class DocumentBrowserFragment extends Fragment {
    private String tree;
    private ArrayList<String> directories, names;
    private final List<Entry> entries = new ArrayList<>();
    private final Map<String, Entry> selected = new LinkedHashMap<>();
    private final AtomicBoolean cancelled = new AtomicBoolean(), active = new AtomicBoolean();
    private RecyclerView list;
    private FileProgressDialog progress;
    private DocumentTransferController transfers;
    private int generation, sort;
    private boolean hidden, loading, writable;
    private String query = "";
    private android.os.CancellationSignal listingCancellation;
    private StorageAccess storage;
    private static final class Entry {
        final TreeTransfer.Node node; final TreeTransfer.Info info; final StorageClipboard.Item ref;
        Entry(TreeTransfer.Node node, TreeTransfer.Info info, StorageClipboard.Item ref) { this.node = node; this.info = info; this.ref = ref; }
    }
    public static DocumentBrowserFragment create(Uri tree, String label) {
        DocumentBrowserFragment fragment = new DocumentBrowserFragment(); Bundle args = new Bundle();
        args.putString("tree", tree.toString()); args.putString("label", label); fragment.setArguments(args); return fragment;
    }
    @Override public void onCreate(Bundle state) {
        super.onCreate(state); tree = requireArguments().getString("tree");
        storage = new StorageAccess(requireContext().getApplicationContext().getContentResolver());
        directories = state == null ? null : state.getStringArrayList("directories");
        names = state == null ? null : state.getStringArrayList("names");
        if (directories == null || names == null || directories.isEmpty() || directories.size() != names.size()) {
            directories = new ArrayList<>(); names = new ArrayList<>();
            Uri uri = Uri.parse(tree);
            directories.add(android.provider.DocumentsContract.buildDocumentUriUsingTree(uri,
                    android.provider.DocumentsContract.getTreeDocumentId(uri)).toString());
            names.add(requireArguments().getString("label", getString(R.string.storage_locations)));
        }
        if (state != null) { hidden = state.getBoolean("hidden"); sort = state.getInt("sort"); }
    }
    @Nullable @Override public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup parent, Bundle state) {
        return inflater.inflate(R.layout.file_manager_fragment, parent, false);
    }
    @Override public void onViewCreated(@NonNull View view, Bundle state) {
        progress = new FileProgressDialog(requireActivity()); progress.setOnCancelListener(() -> { if (transfers != null) transfers.cancel(); });
        transfers = new DocumentTransferController(this, progress, active, cancelled, this::load);
        list = view.findViewById(R.id.RecyclerViewFiles); list.setLayoutManager(new LinearLayoutManager(requireContext()));
        MainActivity.setTabTitle(requireActivity(), R.string.file_manager);
        view.findViewById(R.id.LLStorageUsage).setVisibility(View.GONE);
        view.findViewById(R.id.TVCurrentPath).setVisibility(View.VISIBLE);
        ((TextView)view.findViewById(R.id.TVDriveName)).setText(names.get(0));
        ((ImageView)view.findViewById(R.id.IVDriveIcon)).setImageResource(R.drawable.ic_drive_storage);
        view.findViewById(R.id.BTUpDir).setEnabled(true);
        view.findViewById(R.id.BTUpDir).setOnClickListener(v -> handleBack());
        view.findViewById(R.id.LLDriveSelect).setOnClickListener(v -> locationsMenu());
        view.findViewById(R.id.BTNewFolder).setOnClickListener(v -> ContentDialog.prompt(requireContext(), R.string.new_folder, "", name ->
                action(R.string.new_folder, () -> {
                    TreeTransfer.validName(name); TreeTransfer.Node parent = current();
                    if (TreeTransfer.find(parent, name) != null) throw new java.io.IOException("Folder already exists");
                    TreeTransfer.Node created = parent.create(name, new TreeTransfer.Info(name,
                            android.provider.DocumentsContract.Document.MIME_TYPE_DIR, true, 0, 0, true, true, true));
                    if (!created.info().name.equals(name)) { created.delete(); throw new java.io.IOException("Provider changed folder name"); }
                })));
        view.findViewById(R.id.BTInfo).setOnClickListener(v -> {
            if (selected.isEmpty()) ContentDialog.alert(requireContext(), getString(R.string.storage_document_help), null);
            else options(new ArrayList<>(selected.values()), v);
        });
        view.findViewById(R.id.BTSearch).setOnClickListener(v -> { view.findViewById(R.id.LLSearchArea).setVisibility(View.VISIBLE); view.findViewById(R.id.ETSearch).requestFocus(); });
        view.findViewById(R.id.BTCloseSearch).setOnClickListener(v -> {
            view.findViewById(R.id.LLSearchArea).setVisibility(View.GONE); ((EditText)view.findViewById(R.id.ETSearch)).setText("");
        });
        ((EditText)view.findViewById(R.id.ETSearch)).addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { query = s.toString(); render(); }
            @Override public void afterTextChanged(Editable s) {}
        });
        view.findViewById(R.id.fabPaste).setOnClickListener(v -> transfers.paste(current()));
        view.findViewById(R.id.fabPaste).setOnLongClickListener(v -> { StorageClipboard.clear(); render(); return true; });
        requireActivity().getOnBackPressedDispatcher().addCallback(getViewLifecycleOwner(), new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() { handleBack(); }
        });
        load();
    }
    @Override public void onSaveInstanceState(@NonNull Bundle state) {
        super.onSaveInstanceState(state); state.putStringArrayList("directories", directories); state.putStringArrayList("names", names);
        state.putBoolean("hidden", hidden); state.putInt("sort", sort);
    }
    @Override public void onDestroyView() {
        generation++; cancelled.set(true); if (transfers != null) transfers.cancel(); if (listingCancellation != null) listingCancellation.cancel(); if (progress != null) progress.dismiss();
        list = null; super.onDestroyView();
    }
    private StorageAccess access() { return storage; }
    private TreeTransfer.Node current() { return access().document(Uri.parse(tree), Uri.parse(directories.get(directories.size() - 1))); }
    public void handleBack() {
        if (getView() == null || active.get()) return;
        if (!selected.isEmpty()) { selected.clear(); render(); return; }
        if (requireView().findViewById(R.id.LLSearchArea).getVisibility() == View.VISIBLE) { requireView().findViewById(R.id.BTCloseSearch).performClick(); return; }
        if (directories.size() > 1) { directories.remove(directories.size() - 1); names.remove(names.size() - 1); load(); }
        else home();
    }
    private void home() {
        if (!getParentFragmentManager().isStateSaved()) getParentFragmentManager().beginTransaction()
                .replace(R.id.FLFragmentContainer, new FileManagerFragment()).commit();
    }
    private void locationsMenu() {
        PopupMenu menu = new PopupMenu(requireContext(), requireView().findViewById(R.id.TVDriveName));
        menu.getMenu().add(0, 1, 0, R.string.storage_locations);
        menu.getMenu().add(0, 2, 1, hidden ? R.string.hide_hidden_files : R.string.show_hidden_files);
        menu.getMenu().add(0, 3, 2, R.string.sort);
        menu.getMenu().add(0, 4, 3, R.string.file_refresh);
        menu.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == 1) home();
            else if (item.getItemId() == 2) { hidden = !hidden; render(); }
            else if (item.getItemId() == 4) load();
            else ContentDialog.showSingleChoiceList(requireContext(), R.string.sort,
                    new String[]{getString(R.string.by_name), getString(R.string.by_date), getString(R.string.by_size)}, value -> { sort = value; render(); });
            return true;
        }); menu.show();
    }
    private void load() {
        if (getView() == null) return;
        int request = ++generation; View view = requireView(); Activity owner = requireActivity();
        if (listingCancellation != null) listingCancellation.cancel();
        listingCancellation = new android.os.CancellationSignal();
        StorageAccess access = new StorageAccess(requireContext().getContentResolver(), listingCancellation);
        TreeTransfer.Node folder = access.document(Uri.parse(tree), Uri.parse(directories.get(directories.size() - 1))); selected.clear();
        loading = true; writable = false; entries.clear(); render();
        view.findViewById(R.id.PBSearch).setVisibility(View.VISIBLE);
        ((TextView)view.findViewById(R.id.TVCurrentPath)).setText(String.join(" / ", names));
        new Thread(() -> {
            List<Entry> result = new ArrayList<>(); Exception failure = null; TreeTransfer.Info directory = null;
            try {
                directory = folder.info();
                for (TreeTransfer.Node child : folder.children()) {
                    TreeTransfer.Info info = child.listedInfo(); result.add(new Entry(child, info, access.reference(child, info)));
                }
            } catch (Exception error) { failure = error; }
            Exception error = failure; TreeTransfer.Info folderInfo = directory;
            owner.runOnUiThread(() -> {
                if (getView() != view || generation != request) return;
                view.findViewById(R.id.PBSearch).setVisibility(View.GONE);
                loading = false; writable = error == null && folderInfo != null && folderInfo.create;
                view.findViewById(R.id.BTNewFolder).setEnabled(writable);
                entries.clear(); if (error == null) entries.addAll(result);
                render();
                if (error != null) { android.util.Log.e("DocumentBrowser", "Cannot load directory", error);
                    ((TextView)view.findViewById(R.id.TVNoItems)).setText(R.string.storage_document_unavailable); }
            });
        }, "DocumentListing").start();
    }
    private void render() {
        if (getView() == null || list == null) return;
        List<Entry> visible = new ArrayList<>(); String text = query.toLowerCase(Locale.ROOT).trim();
        for (Entry entry : entries) if ((hidden || !entry.info.name.startsWith(".")) && entry.info.name.toLowerCase(Locale.ROOT).contains(text)) visible.add(entry);
        Comparator<Entry> order = sort == 1 ? Comparator.comparingLong(entry -> -entry.info.modified)
                : sort == 2 ? Comparator.comparingLong(entry -> -entry.info.size) : Comparator.comparing(entry -> entry.info.name, String.CASE_INSENSITIVE_ORDER);
        visible.sort(Comparator.<Entry, Boolean>comparing(entry -> !entry.info.directory).thenComparing(order));
        list.setAdapter(new Adapter(visible));
        MainActivity.setTabTitle(requireActivity(), selected.isEmpty() ? getString(R.string.file_manager) : getString(R.string.file_selected, selected.size()));
        ((ImageView)requireView().findViewById(R.id.BTInfo)).setImageResource(selected.isEmpty() ? R.drawable.ic_nav_info : android.R.drawable.ic_menu_more);
        requireView().findViewById(R.id.BTInfo).setContentDescription(getString(selected.isEmpty() ? R.string.menu_section_help : R.string.options));
        TextView empty = requireView().findViewById(R.id.TVNoItems); empty.setText(R.string.no_items_to_display); empty.setVisibility(!loading && visible.isEmpty() ? View.VISIBLE : View.GONE);
        requireView().findViewById(R.id.fabPaste).setVisibility(StorageClipboard.isEmpty() ? View.GONE : View.VISIBLE);
        requireView().findViewById(R.id.fabPaste).setEnabled(writable && !active.get());
        requireView().findViewById(R.id.BTNewFolder).setEnabled(writable && !active.get());
    }
    private void options(List<Entry> targets, View anchor) {
        PopupMenu menu = new PopupMenu(requireContext(), anchor);
        menu.getMenu().add(0, 1, 0, R.string.copy);
        if (targets.size() == 1 && targets.get(0).info.rename) menu.getMenu().add(0, 3, 2, R.string.rename);
        boolean deletable = true; for (Entry target : targets) deletable &= target.info.delete;
        if (deletable) menu.getMenu().add(0, 2, 1, R.string.cut);
        if (deletable) menu.getMenu().add(0, 4, 3, R.string.remove);
        if (targets.size() == 1 && !targets.get(0).info.directory) menu.getMenu().add(0, 5, 4, R.string.share);
        menu.setOnMenuItemClickListener(item -> {
            int id = item.getItemId();
            if (id == 1 || id == 2) {
                List<StorageClipboard.Item> refs = new ArrayList<>(); for (Entry target : targets) refs.add(target.ref);
                StorageClipboard.set(refs, id == 2); selected.clear(); render();
                Toast.makeText(requireContext(), R.string.file_clipboard_ready, Toast.LENGTH_SHORT).show();
            } else if (id == 3) {
                Entry entry = targets.get(0);
                ContentDialog.prompt(requireContext(), R.string.rename, entry.info.name, name -> action(R.string.rename, () -> {
                    TreeTransfer.validName(name); TreeTransfer.Node parent = current();
                    TreeTransfer.Node existing = TreeTransfer.find(parent, name);
                    if (existing != null && !existing.key().equals(entry.node.key())) throw new java.io.IOException("Name already exists");
                    if (!entry.node.rename(name).info().name.equals(name)) throw new java.io.IOException("Provider changed the requested name");
                }));
            } else if (id == 4) ContentDialog.confirm(requireContext(), getString(R.string.file_delete_selected, targets.size()), () ->
                    action(R.string.deleting_file, () -> { for (Entry entry : targets) { if (cancelled.get()) break; entry.node.delete(); } }));
            else {
                Entry entry = targets.get(0); Intent share = new Intent(Intent.ACTION_SEND); share.setType(entry.info.mime);
                Uri uri = Uri.parse(entry.ref.document); share.putExtra(Intent.EXTRA_STREAM, uri); share.setClipData(ClipData.newRawUri(entry.info.name, uri));
                share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                try { startActivity(Intent.createChooser(share, getString(R.string.share))); }
                catch (RuntimeException error) { Toast.makeText(requireContext(), R.string.file_operation_failed, Toast.LENGTH_SHORT).show(); }
            }
            return true;
        }); menu.show();
    }
    private interface Operation { void run() throws Exception; }
    private void action(int title, Operation operation) {
        if (!active.compareAndSet(false, true)) return;
        Activity owner = requireActivity(); View view = requireView(); cancelled.set(false); progress.show(title);
        new Thread(() -> {
            Exception failure = null; try { operation.run(); } catch (Exception error) { failure = error; }
            Exception error = failure;
            owner.runOnUiThread(() -> {
                active.set(false); progress.dismiss(); if (getView() != view) return;
                if (error != null) { android.util.Log.e("DocumentBrowser", "Operation failed", error); Toast.makeText(owner, R.string.file_operation_failed, Toast.LENGTH_LONG).show(); }
                load();
            });
        }, "DocumentOperation").start();
    }
    private final class Adapter extends RecyclerView.Adapter<Holder> {
        final List<Entry> items; Adapter(List<Entry> items) { this.items = items; }
        @NonNull @Override public Holder onCreateViewHolder(@NonNull ViewGroup parent, int type) {
            return new Holder(LayoutInflater.from(parent.getContext()).inflate(R.layout.file_list_item, parent, false));
        }
        @Override public int getItemCount() { return items.size(); }
        @Override public void onBindViewHolder(@NonNull Holder holder, int position) {
            Entry entry = items.get(position); ((TextView)holder.itemView.findViewById(R.id.TVFileName)).setText(entry.info.name);
            ((TextView)holder.itemView.findViewById(R.id.TVFileDetails)).setText(entry.info.directory ? getString(R.string.file_folder)
                    : entry.info.size >= 0 ? Formatter.formatFileSize(requireContext(), entry.info.size) : getString(R.string.file_size_unknown));
            ((ImageView)holder.itemView.findViewById(R.id.IVIcon)).setImageResource(entry.info.directory ? R.drawable.ic_folder : R.drawable.ic_file);
            holder.itemView.setBackgroundColor(selected.containsKey(entry.ref.document) ? 0x332196f3 : 0);
            holder.itemView.setOnClickListener(v -> {
                if (!selected.isEmpty()) toggle(entry);
                else if (entry.info.directory) { directories.add(entry.ref.document); names.add(entry.info.name); load(); }
                else options(Collections.singletonList(entry), v);
            });
            holder.itemView.setOnLongClickListener(v -> {
                if (selected.containsKey(entry.ref.document)) options(new ArrayList<>(selected.values()), v); else toggle(entry);
                return true;
            });
        }
        private void toggle(Entry entry) { if (selected.remove(entry.ref.document) == null) selected.put(entry.ref.document, entry); render(); }
    }
    private static class Holder extends RecyclerView.ViewHolder { Holder(View view) { super(view); } }
}
