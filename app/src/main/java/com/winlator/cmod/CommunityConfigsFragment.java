package com.winlator.cmod;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.winlator.cmod.container.CommunityConfigManager;
import com.winlator.cmod.contents.Downloader;
import com.winlator.cmod.core.AppUtils;
import com.winlator.cmod.core.FileUtils;
import com.winlator.cmod.core.ShortcutArtworkManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.InputStream;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Read-only in-app catalog backed by the project's community-configs release. */
public class CommunityConfigsFragment extends Fragment {
    private static final String RELEASE_API =
            "https://api.github.com/repos/avavo/WinXclipse/releases/tags/community-configs";
    private static final String RELEASE_ASSETS_PAGE =
            "https://github.com/avavo/WinXclipse/releases/expanded_assets/community-configs";
    private static final String RELEASE_INDEX_CACHE = "community_configs_release.json";
    private RecyclerView recyclerView;
    private TextView emptyView;
    private final List<GameItem> items = new ArrayList<>();
    private GameItem pendingArtworkGame;
    private ActivityResultLauncher<String> artworkPicker;

    private static final class ConfigOption {
        String assetName;
        String url;
        String coverUrl;
        String updatedAt;
        File archive;
        JSONObject manifest;
        JSONObject metadata;
    }

    private static final class GameItem {
        String key;
        String name;
        String coverUrl;
        File coverFile;
        File customCoverFile;
        final List<ConfigOption> configs = new ArrayList<>();
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        artworkPicker = registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
            GameItem game = pendingArtworkGame;
            pendingArtworkGame = null;
            if (uri == null || game == null || game.customCoverFile == null) return;
            Context appContext = requireContext().getApplicationContext();
            new Thread(() -> {
                boolean saved = saveSelectedArtwork(appContext, uri, game.customCoverFile);
                android.app.Activity host = getActivity();
                if (host == null || host.isFinishing()) return;
                host.runOnUiThread(() -> {
                    if (!isAdded()) return;
                    RecyclerView.Adapter<?> adapter = recyclerView != null ? recyclerView.getAdapter() : null;
                    if (adapter != null) adapter.notifyDataSetChanged();
                    Toast.makeText(requireContext(), saved
                            ? "Community cover updated."
                            : "This image could not be used.", Toast.LENGTH_SHORT).show();
                });
            }, "CommunityCustomArtwork").start();
        });
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.community_configs_fragment, container, false);
        recyclerView = view.findViewById(R.id.RecyclerView);
        emptyView = view.findViewById(R.id.TVEmptyText);
        recyclerView.setLayoutManager(new GridLayoutManager(requireContext(), 2));
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
        emptyView.setText("Loading community configs…");
        emptyView.setVisibility(View.VISIBLE);
        android.app.Activity hostActivity = getActivity();
        android.content.Context appContext = requireContext().getApplicationContext();
        new Thread(() -> {
            List<GameItem> loaded = new ArrayList<>();
            try {
                JSONObject release = loadReleaseIndex(appContext);
                JSONArray assets = release.optJSONArray("assets");
                Map<String, String> covers = collectReleaseCovers(assets);
                Map<String, GameItem> games = new LinkedHashMap<>();
                if (assets != null) {
                    for (int i = 0; i < assets.length(); i++) {
                        JSONObject asset = assets.getJSONObject(i);
                        String assetName = asset.optString("name", "");
                        if (!assetName.toLowerCase(Locale.ENGLISH).endsWith(".zip")) continue;
                        ConfigOption option = loadOption(appContext, asset, covers);
                        if (option == null) continue;
                        addOption(games, option);
                    }
                }

                // Last-resort offline mode. The validated ZIP cache is enough to
                // rebuild the cards even when both GitHub endpoints are unavailable.
                if (games.isEmpty()) {
                    for (ConfigOption option : loadCachedOptions(appContext)) {
                        addOption(games, option);
                    }
                }

                File artDir = new File(appContext.getFilesDir(), "community_config_artwork");
                File customArtDir = new File(appContext.getFilesDir(),
                        "community_config_custom_artwork");
                if (!artDir.isDirectory()) artDir.mkdirs();
                if (!customArtDir.isDirectory()) customArtDir.mkdirs();
                for (GameItem game : games.values()) {
                    game.configs.sort((a, b) -> b.updatedAt.compareTo(a.updatedAt));
                    game.coverFile = new File(artDir,
                            Integer.toHexString(game.key.hashCode()) + ".png");
                    game.customCoverFile = new File(customArtDir,
                            Integer.toHexString(game.key.hashCode()) + ".png");
                    // Always inspect every config belonging to this game before
                    // allowing a release/browser image to become the fallback.
                    boolean embedded = extractFirstEmbeddedCover(game.configs, game.coverFile);
                    if (!embedded) {
                        for (ConfigOption option : game.configs) {
                            if (option.coverUrl != null && !option.coverUrl.isEmpty()) {
                                game.coverUrl = option.coverUrl;
                                break;
                            }
                        }
                        // Also accept one game-level release cover when archive
                        // names carry a phone suffix (Game-SM-S926.zip).
                        if (game.coverUrl == null || game.coverUrl.isEmpty()) {
                            for (Map.Entry<String, String> cover : covers.entrySet()) {
                                if (normalizeGameName(displayName(cover.getKey())).equals(game.key)) {
                                    game.coverUrl = cover.getValue();
                                    break;
                                }
                            }
                        }
                    }
                    loaded.add(game);
                }
                loaded.sort(Comparator.comparing(a -> a.name.toLowerCase(Locale.ENGLISH)));
            } catch (Exception ignored) {}

            if (hostActivity == null || hostActivity.isFinishing()) return;
            hostActivity.runOnUiThread(() -> {
                if (!isAdded()) return;
                items.clear();
                items.addAll(loaded);
                RecyclerView.Adapter<?> adapter = recyclerView.getAdapter();
                if (adapter != null) adapter.notifyDataSetChanged();
                emptyView.setText(items.isEmpty()
                        ? "No valid community configs are published yet."
                        : "");
                emptyView.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
            });
        }, "CommunityConfigCatalog").start();
    }

    private static JSONObject loadReleaseIndex(Context context) {
        File cachedIndex = new File(context.getFilesDir(), RELEASE_INDEX_CACHE);
        JSONObject release = parseRelease(Downloader.downloadString(RELEASE_API));
        if (hasAssets(release)) {
            FileUtils.writeString(cachedIndex, release.toString());
            return release;
        }

        release = releaseFromExpandedAssets(Downloader.downloadString(RELEASE_ASSETS_PAGE));
        if (hasAssets(release)) {
            FileUtils.writeString(cachedIndex, release.toString());
            return release;
        }

        release = parseRelease(FileUtils.readString(cachedIndex));
        return release != null ? release : new JSONObject();
    }

    private static JSONObject parseRelease(String json) {
        if (json == null || json.trim().isEmpty()) return null;
        try {
            JSONObject release = new JSONObject(json);
            return release.optJSONArray("assets") != null ? release : null;
        }
        catch (Exception ignored) {
            return null;
        }
    }

    private static boolean hasAssets(JSONObject release) {
        JSONArray assets = release != null ? release.optJSONArray("assets") : null;
        return assets != null && assets.length() > 0;
    }

    private static JSONObject releaseFromExpandedAssets(String html) {
        JSONObject release = new JSONObject();
        JSONArray assets = new JSONArray();
        if (html == null || html.isEmpty()) {
            try { release.put("assets", assets); } catch (Exception ignored) {}
            return release;
        }
        Pattern linkPattern = Pattern.compile(
                "href=[\\\"']([^\\\"']*/releases/download/community-configs/[^\\\"']+)[\\\"']",
                Pattern.CASE_INSENSITIVE);
        Matcher matcher = linkPattern.matcher(html);
        Set<String> seen = new HashSet<>();
        while (matcher.find()) {
            String path = matcher.group(1).replace("&amp;", "&");
            String url = path.startsWith("http") ? path : "https://github.com" + path;
            if (!seen.add(url)) continue;
            String segment = Uri.parse(url).getLastPathSegment();
            String name = segment == null ? "" : Uri.decode(segment);
            if (!name.toLowerCase(Locale.ENGLISH).matches(
                    ".*\\.(?:zip|png|jpe?g|webp|bmp)$")) continue;
            try {
                JSONObject asset = new JSONObject();
                asset.put("name", name);
                asset.put("browser_download_url", url);
                asset.put("updated_at", "");
                assets.put(asset);
            }
            catch (Exception ignored) {}
        }
        try { release.put("assets", assets); } catch (Exception ignored) {}
        return release;
    }

    private static List<ConfigOption> loadCachedOptions(Context context) {
        List<ConfigOption> options = new ArrayList<>();
        File directory = new File(context.getCacheDir(), "community_config_catalog");
        File[] archives = directory.listFiles((dir, name) ->
                name.toLowerCase(Locale.ENGLISH).endsWith(".zip"));
        if (archives == null) return options;
        for (File archive : archives) {
            try {
                ConfigOption option = new ConfigOption();
                option.archive = archive;
                option.manifest = CommunityConfigManager.readConfig(archive);
                option.metadata = option.manifest.optJSONObject("metadata");
                if (option.metadata == null) option.metadata = new JSONObject();
                option.assetName = option.metadata.optString("gameName", archive.getName()) + ".zip";
                option.url = "";
                option.coverUrl = "";
                option.updatedAt = String.format(Locale.ENGLISH, "%019d", archive.lastModified());
                options.add(option);
            }
            catch (Exception ignored) {}
        }
        return options;
    }

    private static void addOption(Map<String, GameItem> games, ConfigOption option) {
        String rawGame = option.metadata.optString("gameName", displayName(option.assetName));
        String gameName = stripTrailingVersion(rawGame);
        if (gameName.isEmpty()) gameName = displayName(option.assetName);
        String key = normalizeGameName(gameName);
        if (key.isEmpty()) return;
        GameItem game = games.get(key);
        if (game == null) {
            game = new GameItem();
            game.key = key;
            game.name = gameName;
            games.put(key, game);
        }
        for (ConfigOption existing : game.configs) {
            if (existing.manifest.toString().equals(option.manifest.toString())) return;
        }
        game.configs.add(option);
    }

    private ConfigOption loadOption(android.content.Context context, JSONObject asset,
                                    Map<String, String> covers) {
        String assetName = asset.optString("name", "");
        String url = asset.optString("browser_download_url", "");
        String updatedAt = asset.optString("updated_at", "");
        if (url.isEmpty()) return null;
        File cacheDir = new File(context.getCacheDir(), "community_config_catalog");
        if (!cacheDir.isDirectory() && !cacheDir.mkdirs()) return null;
        File archive = new File(cacheDir,
                Integer.toHexString((url + "|" + updatedAt).hashCode()) + ".zip");
        if (!archive.isFile() && !Downloader.downloadFile(url, archive)) {
            FileUtils.delete(archive);
            return null;
        }
        try {
            ConfigOption option = new ConfigOption();
            option.assetName = assetName;
            option.url = url;
            option.updatedAt = updatedAt;
            option.archive = archive;
            option.manifest = CommunityConfigManager.readConfig(archive);
            option.metadata = option.manifest.optJSONObject("metadata");
            if (option.metadata == null) option.metadata = new JSONObject();
            option.coverUrl = covers.get(baseName(assetName));
            return option;
        } catch (Exception ignored) {
            FileUtils.delete(archive);
            return null;
        }
    }

    private static boolean extractFirstEmbeddedCover(List<ConfigOption> configs, File output) {
        for (ConfigOption option : configs) {
            if (option.archive != null && option.archive.isFile()
                    && CommunityConfigManager.extractEmbeddedCover(option.archive, output)) {
                return true;
            }
        }
        return false;
    }

    private static Map<String, String> collectReleaseCovers(JSONArray assets) throws Exception {
        Map<String, String> covers = new HashMap<>();
        if (assets == null) return covers;
        for (int i = 0; i < assets.length(); i++) {
            JSONObject asset = assets.getJSONObject(i);
            String name = asset.optString("name", "");
            if (isImageName(name)) {
                covers.put(baseName(name), asset.optString("browser_download_url", ""));
            }
        }
        return covers;
    }

    private final class Adapter extends RecyclerView.Adapter<Holder> {
        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new Holder(LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.community_config_list_item, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull Holder holder, int position) {
            GameItem item = items.get(position);
            holder.title.setText(item.name);
            holder.detail.setText(latestPhoneLabel(item));
            holder.icon.setTag(item.key);
            holder.icon.setImageResource(R.drawable.cover_art_placeholder);
            loadArtwork(item, holder);
            holder.itemView.setOnClickListener(v -> showConfigDialog(item));
        }

        @Override public int getItemCount() { return items.size(); }
    }

    private static final class Holder extends RecyclerView.ViewHolder {
        final ImageView icon;
        final TextView title;
        final TextView detail;

        Holder(View view) {
            super(view);
            icon = view.findViewById(R.id.IVIcon);
            title = view.findViewById(R.id.TVVersionName);
            detail = view.findViewById(R.id.TVVersionCode);
        }
    }

    private void loadArtwork(GameItem item, Holder holder) {
        if (item.customCoverFile != null && item.customCoverFile.isFile()) {
            if (item.key.equals(holder.icon.getTag())) holder.icon.setImageBitmap(
                    BitmapFactory.decodeFile(item.customCoverFile.getAbsolutePath()));
            return;
        }
        if (item.coverFile.isFile()) {
            if (item.key.equals(holder.icon.getTag()))
                holder.icon.setImageBitmap(BitmapFactory.decodeFile(item.coverFile.getAbsolutePath()));
            return;
        }
        if (item.coverUrl != null && !item.coverUrl.isEmpty()) {
            new Thread(() -> {
                boolean ok = Downloader.downloadFile(item.coverUrl, item.coverFile);
                if (ok && getActivity() != null) requireActivity().runOnUiThread(() -> {
                    if (holder.getBindingAdapterPosition() != RecyclerView.NO_POSITION
                            && item.key.equals(holder.icon.getTag()))
                        holder.icon.setImageBitmap(BitmapFactory.decodeFile(item.coverFile.getAbsolutePath()));
                });
            }, "CommunityConfigCover").start();
            return;
        }
        ShortcutArtworkManager.ensureForGame(requireContext().getApplicationContext(),
                item.name, item.coverFile, success -> {
                    if (success && holder.getBindingAdapterPosition() != RecyclerView.NO_POSITION
                            && item.key.equals(holder.icon.getTag())) {
                        holder.icon.setImageBitmap(BitmapFactory.decodeFile(item.coverFile.getAbsolutePath()));
                    }
                });
    }

    private void showConfigDialog(GameItem game) {
        if (game.configs.isEmpty()) return;
        ConfigOption[] selected = {game.configs.size() == 1 ? game.configs.get(0) : null};
        boolean[] showingContainerConfig = {false};
        LinearLayout body = new LinearLayout(requireContext());
        body.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        body.setPadding(pad, pad / 2, pad, 0);

        TextView details = new TextView(requireContext());
        details.setTextSize(15);
        details.setTextIsSelectable(true);

        Button showConfig = createDialogActionButton("Show configuration");
        showConfig.setVisibility(selected[0] == null ? View.GONE : View.VISIBLE);

        LinearLayout artworkActions = new LinearLayout(requireContext());
        artworkActions.setOrientation(LinearLayout.HORIZONTAL);
        artworkActions.setGravity(Gravity.END);
        artworkActions.setPadding(0, 0, 0, dp(4));
        Button changeArtwork = createDialogActionButton("Change cover");
        changeArtwork.setOnClickListener(v -> {
            pendingArtworkGame = game;
            AlertDialog activeDialog = (AlertDialog) body.getTag();
            if (activeDialog != null) activeDialog.dismiss();
            artworkPicker.launch("image/*");
        });
        artworkActions.addView(changeArtwork, actionButtonLayoutParams(false));
        Button restoreArtwork = createDialogActionButton("Restore cover");
        restoreArtwork.setVisibility(game.customCoverFile != null
                && game.customCoverFile.isFile() ? View.VISIBLE : View.GONE);
        restoreArtwork.setOnClickListener(v -> {
            if (game.customCoverFile != null) FileUtils.delete(game.customCoverFile);
            restoreArtwork.setVisibility(View.GONE);
            RecyclerView.Adapter<?> adapter = recyclerView.getAdapter();
            if (adapter != null) adapter.notifyDataSetChanged();
            Toast.makeText(requireContext(), "Default cover restored.", Toast.LENGTH_SHORT).show();
        });
        artworkActions.addView(restoreArtwork, actionButtonLayoutParams(true));

        LinearLayout configActions = new LinearLayout(requireContext());
        configActions.setGravity(Gravity.END);
        configActions.addView(showConfig, actionButtonLayoutParams(false));

        TextView containerDetails = new TextView(requireContext());
        containerDetails.setTextSize(14);
        containerDetails.setTextIsSelectable(true);
        containerDetails.setPadding(0, dp(8), 0, dp(8));
        containerDetails.setVisibility(View.GONE);
        showConfig.setOnClickListener(v -> {
            if (selected[0] == null) return;
            showingContainerConfig[0] = !showingContainerConfig[0];
            containerDetails.setText(formatContainerDetails(selected[0]));
            containerDetails.setVisibility(showingContainerConfig[0] ? View.VISIBLE : View.GONE);
            showConfig.setText(showingContainerConfig[0]
                    ? "Hide configuration" : "Show configuration");
        });

        if (game.configs.size() > 1) {
            TextView prompt = new TextView(requireContext());
            prompt.setText("Choose the phone configuration:");
            prompt.setPadding(0, 0, 0, dp(8));
            body.addView(prompt);
            RadioGroup choices = new RadioGroup(requireContext());
            for (ConfigOption option : game.configs) {
                RadioButton button = new RadioButton(requireContext());
                button.setText(phoneLabel(option));
                button.setTag(option);
                choices.addView(button);
            }
            choices.setOnCheckedChangeListener((group, checkedId) -> {
                RadioButton button = group.findViewById(checkedId);
                if (button == null) return;
                selected[0] = (ConfigOption) button.getTag();
                details.setText(formatDetails(selected[0]));
                details.setVisibility(View.VISIBLE);
                showingContainerConfig[0] = false;
                containerDetails.setVisibility(View.GONE);
                showConfig.setText("Show configuration");
                showConfig.setVisibility(View.VISIBLE);
                AlertDialog activeDialog = (AlertDialog) body.getTag();
                if (activeDialog != null)
                    activeDialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
            });
            body.addView(choices);
            details.setVisibility(View.GONE);
        } else {
            details.setText(formatDetails(selected[0]));
        }
        details.setPadding(0, dp(12), 0, dp(8));
        body.addView(artworkActions);
        body.addView(details);
        body.addView(configActions);
        body.addView(containerDetails);

        ScrollView scroll = new ScrollView(requireContext());
        scroll.addView(body);
        AlertDialog dialog = new AlertDialog.Builder(requireContext())
                .setTitle(game.name)
                .setView(scroll)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton("Install", null)
                .create();
        body.setTag(dialog);
        dialog.setOnShowListener(ignored -> {
            Button install = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            install.setEnabled(selected[0] != null);
            install.setOnClickListener(v -> {
                if (selected[0] == null) return;
                dialog.dismiss();
                openInstaller(selected[0]);
            });
        });
        dialog.show();
        if (AppUtils.isDarkMode(requireContext()) && dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(
                    R.drawable.content_dialog_background_dark);
        }
    }

    private static boolean saveSelectedArtwork(Context context, Uri uri, File output) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (InputStream input = context.getContentResolver().openInputStream(uri)) {
                if (input == null) return false;
                BitmapFactory.decodeStream(input, null, bounds);
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0
                    || bounds.outWidth > 16384 || bounds.outHeight > 16384) return false;
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = 1;
            int largest = Math.max(bounds.outWidth, bounds.outHeight);
            while (largest / options.inSampleSize > 2048) options.inSampleSize *= 2;
            Bitmap bitmap;
            try (InputStream input = context.getContentResolver().openInputStream(uri)) {
                if (input == null) return false;
                bitmap = BitmapFactory.decodeStream(input, null, options);
            }
            if (bitmap == null) return false;
            File parent = output.getParentFile();
            boolean ready = parent != null && (parent.isDirectory() || parent.mkdirs());
            boolean saved = ready && FileUtils.saveBitmapToFile(bitmap, output);
            bitmap.recycle();
            if (!saved) FileUtils.delete(output);
            return saved;
        }
        catch (Exception ignored) {
            return false;
        }
    }

    private void openInstaller(ConfigOption option) {
        // ContentsFragment used to clear the shared Android cache as it was
        // destroyed during this navigation. Persist only the already-validated
        // manifest before switching screens so the install cannot lose its
        // source midway through the fragment transaction.
        File pendingDir = new File(requireContext().getFilesDir(),
                "pending_community_configs");
        if (!pendingDir.isDirectory() && !pendingDir.mkdirs()) {
            Toast.makeText(requireContext(), "Could not prepare this configuration.",
                    Toast.LENGTH_LONG).show();
            return;
        }
        File pendingManifest = new File(pendingDir,
                "pending-" + System.nanoTime() + ".json");
        if (!FileUtils.writeString(pendingManifest, option.manifest.toString())) {
            Toast.makeText(requireContext(), "Could not prepare this configuration.",
                    Toast.LENGTH_LONG).show();
            return;
        }

        ContainersFragment fragment = new ContainersFragment();
        Bundle args = new Bundle();
        args.putString("community_config_path", pendingManifest.getAbsolutePath());
        fragment.setArguments(args);
        getParentFragmentManager().beginTransaction()
                .addToBackStack(null)
                .replace(R.id.FLFragmentContainer, fragment)
                .commit();
    }

    private String formatDetails(ConfigOption option) {
        JSONObject data = option.metadata;
        return "Phone: " + valueOrUnknown(phoneLabel(option))
                + "\nRAM: " + valueOrUnknown(data.optString("ram"))
                + "\nGPU: " + valueOrUnknown(data.optString("gpu"))
                + "\nCPU: " + valueOrUnknown(data.optString("soc"))
                + "\nExpected average FPS: " + valueOrUnknown(data.optString("fps"))
                + "\nTested by: " + discordLabel(data)
                + "\n\nNotes / fixes / presets / variables:\n"
                + optionalValue(data.optString("notes"));
    }

    private String formatContainerDetails(ConfigOption option) {
        JSONObject data = option.manifest.optJSONObject("container");
        if (data == null) return "Container configuration unavailable.";
        ArrayList<String> lines = new ArrayList<>();
        addConfigLine(lines, "Resolution", data.optString("screenSize"));
        addConfigLine(lines, "Wine", data.optString("wineVersion"));
        addConfigLine(lines, "Emulator", data.optString("emulator"));
        addConfigLine(lines, "Graphics driver", data.optString("graphicsDriver"));
        addConfigLine(lines, "Graphics config", data.optString("graphicsDriverConfig"));
        addConfigLine(lines, "DX wrapper", data.optString("dxwrapper"));
        addConfigLine(lines, "DX wrapper config", data.optString("dxwrapperConfig"));
        addConfigLine(lines, "Box64", data.optString("box64Version"));
        addConfigLine(lines, "Box64 preset", data.optString("box64Preset"));
        addConfigLine(lines, "FEXCore", data.optString("fexcoreVersion"));
        addConfigLine(lines, "FEXCore preset", data.optString("fexcorePreset"));
        addConfigLine(lines, "CPU list", data.optString("cpuList"));
        addConfigLine(lines, "WoW64 CPU list", data.optString("cpuListWoW64"));
        addConfigLine(lines, "Audio", data.optString("audioDriver"));
        addConfigLine(lines, "Windows components", data.optString("wincomponents"));
        addConfigLine(lines, "Environment variables", data.optString("envVars"));
        return lines.isEmpty() ? "No container settings were included." : String.join("\n", lines);
    }

    private static void addConfigLine(List<String> lines, String label, String value) {
        String clean = value == null ? "" : value.trim();
        if (!clean.isEmpty()) lines.add(label + ": " + clean);
    }

    private static String latestPhoneLabel(GameItem game) {
        return game.configs.isEmpty() ? "Unknown phone" : phoneLabel(game.configs.get(0));
    }

    private static String phoneLabel(ConfigOption option) {
        String device = option.metadata.optString("device", "").trim();
        String model = option.metadata.optString("model", "").trim();
        if (device.isEmpty()) return model.isEmpty() ? "Unknown phone" : model;
        if (model.isEmpty() || device.toUpperCase(Locale.ENGLISH)
                .contains(model.toUpperCase(Locale.ENGLISH))) return device;
        return device + " | " + model;
    }

    private static String discordLabel(JSONObject data) {
        String discord = data.optString("discord", data.optString("author", "")).trim();
        if (discord.isEmpty()) return "Not provided";
        return discord.startsWith("@") ? discord : "@" + discord;
    }

    private static String valueOrUnknown(String value) {
        String clean = value == null ? "" : value.trim();
        return clean.isEmpty() ? "Not provided" : clean;
    }

    private static String optionalValue(String value) {
        String clean = value == null ? "" : value.trim();
        return clean.isEmpty() ? "None" : clean;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    /** Compact AMOLED action: transparent surface, purple outline and no gray Button chrome. */
    private Button createDialogActionButton(String text) {
        Button button = new Button(requireContext());
        button.setText(text);
        button.setAllCaps(false);
        button.setTextSize(14);
        button.setTextColor(getResources().getColor(R.color.colorAccentDark));
        button.setGravity(Gravity.CENTER);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setMinHeight(0);
        button.setMinimumHeight(0);
        button.setPadding(dp(16), 0, dp(16), 0);

        int accent = getResources().getColor(R.color.colorAccentDark);
        GradientDrawable normal = new GradientDrawable();
        normal.setColor(Color.TRANSPARENT);
        normal.setStroke(dp(1), accent);
        normal.setCornerRadius(dp(10));
        GradientDrawable pressed = new GradientDrawable();
        pressed.setColor(Color.argb(52, Color.red(accent), Color.green(accent), Color.blue(accent)));
        pressed.setStroke(dp(1), accent);
        pressed.setCornerRadius(dp(10));
        StateListDrawable background = new StateListDrawable();
        background.addState(new int[]{android.R.attr.state_pressed}, pressed);
        background.addState(new int[]{}, normal);
        button.setBackground(background);
        return button;
    }

    private LinearLayout.LayoutParams actionButtonLayoutParams(boolean hasLeadingMargin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(40));
        if (hasLeadingMargin) params.setMarginStart(dp(8));
        return params;
    }

    private static boolean isImageName(String name) {
        String lower = name.toLowerCase(Locale.ENGLISH);
        return lower.endsWith(".png") || lower.endsWith(".jpg")
                || lower.endsWith(".jpeg") || lower.endsWith(".webp");
    }

    private static String baseName(String name) {
        return name.replaceFirst("(?i)\\.(zip|png|jpe?g|webp)$", "")
                .toLowerCase(Locale.ENGLISH);
    }

    private static String displayName(String name) {
        return name.replaceFirst("(?i)\\.zip$", "")
                .replace('_', ' ').replace('-', ' ').trim();
    }

    private static String stripTrailingVersion(String name) {
        return name.trim().replaceFirst(
                "(?i)[\\s._-]+v?\\d+(?:\\.\\d+){1,3}(?:[-._][a-z0-9]+)?$", "").trim();
    }

    /**
     * Catalog identity is case/spacing/punctuation independent. Standalone
     * Roman numerals I-X are canonicalized so e.g. "GTA V" and "gta 5 " share
     * one card and the same set of covers/configurations.
     */
    private static String normalizeGameName(String value) {
        String ascii = Normalizer.normalize(value == null ? "" : value.trim(),
                Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        String[] tokens = ascii.toLowerCase(Locale.ENGLISH)
                .replaceAll("[^a-z0-9]+", " ").trim().split("\\s+");
        StringBuilder key = new StringBuilder();
        for (String token : tokens) {
            if (token.isEmpty()) continue;
            String numeric = romanToArabic(token);
            if (numeric == null && token.matches("\\d+")) {
                numeric = token.replaceFirst("^0+(?!$)", "");
            }
            key.append(numeric != null ? numeric : token);
        }
        return key.toString();
    }

    private static String romanToArabic(String token) {
        switch (token) {
            case "i": return "1";
            case "ii": return "2";
            case "iii": return "3";
            case "iv": return "4";
            case "v": return "5";
            case "vi": return "6";
            case "vii": return "7";
            case "viii": return "8";
            case "ix": return "9";
            case "x": return "10";
            default: return null;
        }
    }
}
