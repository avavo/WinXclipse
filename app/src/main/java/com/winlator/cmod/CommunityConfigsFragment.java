package com.winlator.cmod;

import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.winlator.cmod.contents.Downloader;
import com.winlator.cmod.core.ContentOperationRegistry;
import com.winlator.cmod.core.FileUtils;
import com.winlator.cmod.core.ShortcutArtworkManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Read-only in-app catalog backed by the project's community-configs release. */
public class CommunityConfigsFragment extends Fragment {
    private static final String RELEASE_API =
            "https://api.github.com/repos/avavo/WinXclipse/releases/tags/community-configs";
    private RecyclerView recyclerView;
    private TextView emptyView;
    private final List<Item> items = new ArrayList<>();

    private static final class Item {
        String name;
        String url;
        String coverUrl;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.community_configs_fragment, container, false);
        recyclerView = view.findViewById(R.id.RecyclerView);
        emptyView = view.findViewById(R.id.TVEmptyText);
        recyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
        recyclerView.setAdapter(new Adapter());
        return view;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        ((AppCompatActivity) requireActivity()).getSupportActionBar().setTitle("Community Configs");
        refresh();
    }

    private void refresh() {
        android.app.Activity hostActivity = getActivity();
        new Thread(() -> {
            List<Item> loaded = new ArrayList<>();
            try {
                String json = Downloader.downloadString(RELEASE_API);
                JSONObject release = new JSONObject(json == null ? "{}" : json);
                JSONArray assets = release.optJSONArray("assets");
                Map<String, String> covers = new HashMap<>();
                if (assets != null) {
                    for (int i = 0; i < assets.length(); i++) {
                        JSONObject asset = assets.getJSONObject(i);
                        String name = asset.optString("name", "");
                        if (name.toLowerCase(Locale.ENGLISH).endsWith(".png")) {
                            covers.put(baseName(name), asset.optString("browser_download_url", ""));
                        }
                    }
                    for (int i = 0; i < assets.length(); i++) {
                        JSONObject asset = assets.getJSONObject(i);
                        String fileName = asset.optString("name", "");
                        if (!fileName.toLowerCase(Locale.ENGLISH).endsWith(".zip")) continue;
                        Item item = new Item();
                        item.name = displayName(fileName);
                        item.url = asset.optString("browser_download_url", "");
                        item.coverUrl = covers.get(baseName(fileName));
                        if (!item.url.isEmpty()) loaded.add(item);
                    }
                }
            } catch (Exception ignored) {}
            if (hostActivity == null || hostActivity.isFinishing()) return;
            hostActivity.runOnUiThread(() -> {
                if (!isAdded()) return;
                items.clear();
                items.addAll(loaded);
                recyclerView.getAdapter().notifyDataSetChanged();
                emptyView.setText(items.isEmpty()
                        ? "No community configs are published in the community-configs release yet."
                        : "");
                emptyView.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
            });
        }).start();
    }

    private final class Adapter extends RecyclerView.Adapter<Holder> {
        @NonNull @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new Holder(LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.content_list_item, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull Holder holder, int position) {
            Item item = items.get(position);
            holder.title.setText(item.name);
            holder.detail.setText("Portable WinXclipse config");
            holder.menu.setVisibility(View.GONE);
            holder.download.setVisibility(View.VISIBLE);
            holder.progress.setVisibility(View.GONE);
            holder.icon.setBackground(null);
            holder.icon.setTag(item.url);
            holder.icon.setImageResource(R.drawable.cover_art_placeholder);
            loadArtwork(item, holder);
            holder.download.setOnClickListener(v -> download(item, holder));
            holder.itemView.setOnClickListener(v -> download(item, holder));
        }

        @Override public int getItemCount() { return items.size(); }
    }

    private static final class Holder extends RecyclerView.ViewHolder {
        final ImageView icon;
        final TextView title;
        final TextView detail;
        final ImageButton menu;
        final ImageButton download;
        final ProgressBar progress;
        Holder(View view) {
            super(view);
            icon = view.findViewById(R.id.IVIcon);
            title = view.findViewById(R.id.TVVersionName);
            detail = view.findViewById(R.id.TVVersionCode);
            menu = view.findViewById(R.id.BTMenu);
            download = view.findViewById(R.id.BTDownload);
            progress = view.findViewById(R.id.Progress);
        }
    }

    private void loadArtwork(Item item, Holder holder) {
        File dir = new File(requireContext().getFilesDir(), "community_config_artwork");
        File file = new File(dir, Integer.toHexString(item.name.hashCode()) + ".png");
        if (file.isFile()) {
            if (item.url.equals(holder.icon.getTag()))
                holder.icon.setImageBitmap(BitmapFactory.decodeFile(file.getAbsolutePath()));
            return;
        }
        if (item.coverUrl != null && !item.coverUrl.isEmpty()) {
            new Thread(() -> {
                boolean ok = (dir.isDirectory() || dir.mkdirs())
                        && Downloader.downloadFile(item.coverUrl, file);
                if (ok && getActivity() != null) requireActivity().runOnUiThread(() -> {
                    if (holder.getBindingAdapterPosition() != RecyclerView.NO_POSITION
                            && item.url.equals(holder.icon.getTag()))
                        holder.icon.setImageBitmap(BitmapFactory.decodeFile(file.getAbsolutePath()));
                });
            }).start();
        } else {
            ShortcutArtworkManager.ensureForGame(requireContext().getApplicationContext(),
                    item.name, file, success -> {
                        if (success && holder.getBindingAdapterPosition() != RecyclerView.NO_POSITION
                                && item.url.equals(holder.icon.getTag())) {
                            holder.icon.setImageBitmap(BitmapFactory.decodeFile(file.getAbsolutePath()));
                        }
                    });
        }
    }

    private void download(Item item, Holder holder) {
        if (holder.progress.getVisibility() == View.VISIBLE) return;
        holder.download.setVisibility(View.GONE);
        holder.progress.setVisibility(View.VISIBLE);
        holder.progress.setProgress(0);
        ContentOperationRegistry.Token operation =
                ContentOperationRegistry.begin(ContentOperationRegistry.Kind.DOWNLOAD);
        File output = new File(requireContext().getCacheDir(),
                "community-" + System.currentTimeMillis() + ".zip");
        android.app.Activity hostActivity = getActivity();
        new Thread(() -> {
            boolean ok = Downloader.downloadFile(item.url, output, progress -> {
                if (hostActivity != null) hostActivity.runOnUiThread(() -> {
                    if (isAdded() && item.url.equals(holder.icon.getTag()))
                        holder.progress.setProgress(progress);
                });
            });
            operation.close();
            if (hostActivity == null || hostActivity.isFinishing()) {
                FileUtils.delete(output);
                return;
            }
            hostActivity.runOnUiThread(() -> {
                if (!isAdded()) {
                    FileUtils.delete(output);
                    return;
                }
                if (!ok) {
                    FileUtils.delete(output);
                    holder.progress.setVisibility(View.GONE);
                    holder.download.setVisibility(View.VISIBLE);
                    android.widget.Toast.makeText(requireContext(), R.string.download_failed,
                            android.widget.Toast.LENGTH_SHORT).show();
                    return;
                }
                ContainersFragment fragment = new ContainersFragment();
                Bundle args = new Bundle();
                args.putString("community_config_uri", Uri.fromFile(output).toString());
                fragment.setArguments(args);
                getParentFragmentManager().beginTransaction()
                        .addToBackStack(null)
                        .replace(R.id.FLFragmentContainer, fragment)
                        .commit();
            });
        }).start();
    }

    private static String baseName(String name) {
        return name.replaceFirst("(?i)\\.(zip|png)$", "").toLowerCase(Locale.ENGLISH);
    }
    private static String displayName(String name) {
        return name.replaceFirst("(?i)\\.zip$", "").replace('_', ' ').replace('-', ' ').trim();
    }
}
