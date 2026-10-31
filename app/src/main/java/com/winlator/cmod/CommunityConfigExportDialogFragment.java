package com.winlator.cmod;

import android.app.ActivityManager;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.GridLayout;
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
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.FileProvider;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.FragmentManager;
import com.bumptech.glide.Glide;
import com.winlator.cmod.container.CommunityConfigManager;
import com.winlator.cmod.container.Container;
import com.winlator.cmod.container.ContainerManager;
import com.winlator.cmod.core.AppUtils;
import com.winlator.cmod.core.CommunityCover;
import com.winlator.cmod.core.InterfaceMotion;
import com.winlator.cmod.core.ShortcutArtworkManager;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** One editable draft used by ZIP export and ordinary Android sharing. */
public final class CommunityConfigExportDialogFragment extends DialogFragment {
    private static final String TAG = "CommunityConfigExport";
    private static final ExecutorService IO = Executors.newFixedThreadPool(2);
    private final Handler main = new Handler(Looper.getMainLooper());
    private ActivityResultLauncher<String> picker;
    private EditText game, author, fps, notes;
    private RadioGroup compatibility;
    private ImageView preview;
    private TextView coverStatus;
    private Container container;
    private File selectedCover;
    private int coverRevision;
    private boolean coverPending, exporting, searching;
    private AlertDialog choicesDialog;

    public static void show(FragmentManager manager, int containerId) {
        if (manager.findFragmentByTag(TAG) != null || manager.isStateSaved()) return;
        CommunityConfigExportDialogFragment dialog = new CommunityConfigExportDialogFragment();
        Bundle args = new Bundle(); args.putInt("container", containerId); dialog.setArguments(args);
        dialog.show(manager, TAG);
    }

    @Override public void onCreate(@Nullable Bundle state) {
        super.onCreate(state);
        picker = registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
            if (uri != null) selectOwnCover(uri);
        });
    }

    @NonNull @Override public Dialog onCreateDialog(@Nullable Bundle state) {
        Context context = requireContext();
        container = new ContainerManager(context).getContainerById(requireArguments().getInt("container"));
        if (container == null) return new AlertDialog.Builder(context).setMessage(R.string.community_invalid_config)
                .setPositiveButton(android.R.string.ok, null).create();
        LinearLayout fields = new LinearLayout(context);
        fields.setOrientation(LinearLayout.VERTICAL); fields.setPadding(dp(20), dp(8), dp(20), dp(8));
        game = field(fields, R.string.config_export_game, value(state, "game"));
        game.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(160)});
        author = field(fields, R.string.config_export_author, value(state, "author"));
        fps = field(fields, R.string.config_export_fps, value(state, "fps"));
        fps.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        CommunityConfigManager.DeviceProfile profile = CommunityConfigManager.getCurrentDeviceProfile();
        TextView device = new TextView(context); device.setText(profile.fullLabel());
        device.setPadding(0, dp(12), 0, dp(12)); fields.addView(device);
        preview = new ImageView(context); preview.setScaleType(ImageView.ScaleType.FIT_CENTER);
        preview.setContentDescription(getString(R.string.config_export_cover));
        fields.addView(preview, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(180)));
        coverStatus = new TextView(context); coverStatus.setGravity(Gravity.CENTER); fields.addView(coverStatus);
        Button search = button(fields, R.string.config_export_find_cover);
        search.setOnClickListener(v -> searchCovers());
        Button own = button(fields, R.string.config_export_own_cover);
        own.setOnClickListener(v -> { if (!exporting) picker.launch("image/*"); });
        Button remove = button(fields, R.string.config_export_no_cover);
        remove.setOnClickListener(v -> {
            if (exporting) return;
            coverRevision++; coverPending = false; selectedCover = null;
            showCover(null); updateExportButton();
        });
        notes = field(fields, R.string.config_export_notes, value(state, "notes"));
        notes.setSingleLine(false); notes.setMinLines(2); notes.setMaxLines(5);
        TextView status = new TextView(context); status.setText(R.string.config_export_compatibility);
        status.setPadding(0, dp(12), 0, dp(4)); fields.addView(status);
        compatibility = new RadioGroup(context);
        choice(R.string.config_export_crashes, "Boots but crashes", state);
        choice(R.string.config_export_menu_only, "Menu only", state);
        choice(R.string.config_export_playable, "Playable", state);
        fields.addView(compatibility);
        ScrollView scroll = new ScrollView(context); scroll.setFillViewport(false); scroll.addView(fields);
        AlertDialog dialog = new AlertDialog.Builder(context).setTitle(R.string.export_community_config)
                .setMessage(R.string.config_export_explanation).setView(scroll)
                .setNegativeButton(R.string.cancel, null).setPositiveButton(R.string.export, null).create();
        dialog.setOnShowListener(ignored -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> export());
            updateExportButton(); InterfaceMotion.apply(fields);
            int width = context.getResources().getDisplayMetrics().widthPixels;
            int height = context.getResources().getDisplayMetrics().heightPixels;
            fields.measure(View.MeasureSpec.makeMeasureSpec(Math.min(dp(640), width - dp(64)), View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            ViewGroup.LayoutParams layout = scroll.getLayoutParams();
            layout.height = Math.min(fields.getMeasuredHeight(), Math.round(height * 0.60f));
            scroll.setLayoutParams(layout);
        });
        if (state != null) {
            String path = state.getString("cover", "");
            if (!path.isEmpty()) selectedCover = new File(path);
            if (selectedCover != null && !selectedCover.isFile()) selectedCover = null;
            showCover(selectedCover);
        } else {
            showCover(null);
            loadAutomaticCover();
        }
        return dialog;
    }

    @Override public void onStart() {
        super.onStart();
        Dialog dialog = getDialog();
        if (dialog != null && dialog.getWindow() != null) {
            dialog.getWindow().setLayout(Math.min(dp(680), getResources().getDisplayMetrics().widthPixels - dp(32)),
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            dialog.getWindow().setBackgroundDrawableResource(AppUtils.isDarkMode(requireContext())
                    ? R.drawable.artwork_dialog_background : R.drawable.artwork_dialog_background_light);
        }
    }

    @Override public void onSaveInstanceState(@NonNull Bundle state) {
        super.onSaveInstanceState(state);
        if (game == null) return;
        state.putString("game", game.getText().toString()); state.putString("author", author.getText().toString());
        state.putString("fps", fps.getText().toString()); state.putString("notes", notes.getText().toString());
        state.putString("cover", selectedCover == null ? "" : selectedCover.getAbsolutePath());
        RadioButton checked = compatibility.findViewById(compatibility.getCheckedRadioButtonId());
        if (checked != null) state.putString("compatibility", String.valueOf(checked.getTag()));
    }

    @Override public void onDestroyView() {
        coverRevision++; // Ignore any image completion targeting the old views.
        if (choicesDialog != null) choicesDialog.dismiss();
        choicesDialog = null; preview = null;
        super.onDestroyView();
    }

    private static String value(Bundle state, String key) { return state == null ? "" : state.getString(key, ""); }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private EditText field(LinearLayout parent, int hint, String value) {
        EditText field = new EditText(parent.getContext()); field.setHint(hint); field.setText(value); field.setSingleLine(true);
        parent.addView(field, new LinearLayout.LayoutParams(-1, -2)); return field;
    }
    private Button button(LinearLayout parent, int text) {
        Button button = new Button(parent.getContext()); button.setText(text); button.setAllCaps(false);
        parent.addView(button, new LinearLayout.LayoutParams(-1, -2)); return button;
    }
    private void choice(int label, String stored, Bundle state) {
        RadioButton button = new RadioButton(requireContext()); button.setId(View.generateViewId());
        button.setText(label); button.setTag(stored); compatibility.addView(button);
        if (stored.equals(value(state, "compatibility"))) compatibility.check(button.getId());
    }
    private void showCover(File file) {
        if (preview == null) return;
        if (file == null) { Glide.with(this).clear(preview); preview.setImageResource(R.drawable.icon_image_picker); }
        else Glide.with(this).load(file).into(preview);
        coverStatus.setText(file == null ? R.string.config_export_cover_empty : R.string.config_export_cover_preview);
    }
    private void updateExportButton() {
        Dialog dialog = getDialog();
        if (dialog instanceof AlertDialog && ((AlertDialog)dialog).getButton(AlertDialog.BUTTON_POSITIVE) != null)
            ((AlertDialog)dialog).getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(!coverPending && !exporting);
    }
    private interface ImageWork { Bitmap load() throws Exception; }
    private void loadCover(ImageWork work, boolean reportFailure) {
        if (exporting) return;
        Context app = requireContext().getApplicationContext();
        int revision = ++coverRevision; coverPending = true; updateExportButton();
        coverStatus.setText(R.string.config_export_cover_loading);
        IO.execute(() -> {
            File file = null;
            try {
                Bitmap bitmap = work.load();
                if (bitmap != null) {
                    // Drafts survive picker navigation/rotation. Old drafts are disposable cache.
                    File dir = new File(app.getCacheDir(), "config-export-covers");
                    if (!dir.isDirectory() && !dir.mkdirs()) throw new java.io.IOException("Cannot create cover cache");
                    File[] old = dir.listFiles();
                    if (old != null) for (File entry : old)
                        if (entry.isFile() && entry.lastModified() < System.currentTimeMillis() - 7L * 86400000L) entry.delete();
                    byte[] png = CommunityCover.png(bitmap);
                    file = File.createTempFile("cover-", ".png", dir);
                    try (FileOutputStream out = new FileOutputStream(file)) { out.write(png); }
                }
            } catch (Exception error) { if (file != null) file.delete(); file = null; }
            File result = file;
            main.post(() -> {
                if (!isAdded() || preview == null || revision != coverRevision) { if (result != null) result.delete(); return; }
                coverPending = false;
                if (result != null) selectedCover = result;
                showCover(selectedCover); updateExportButton();
                if (result == null && reportFailure) Toast.makeText(requireContext(), R.string.config_export_cover_failed, Toast.LENGTH_LONG).show();
            });
        });
    }
    private void loadAutomaticCover() {
        Container current = container;
        loadCover(() -> CommunityConfigManager.defaultCover(current, current.getName()), false);
    }
    private void selectOwnCover(Uri uri) {
        Context app = requireContext().getApplicationContext();
        loadCover(() -> {
            try (InputStream input = app.getContentResolver().openInputStream(uri)) {
                if (input == null) throw new java.io.IOException("Cannot open cover");
                return CommunityCover.decode(input);
            }
        }, true);
    }
    private void searchCovers() {
        if (searching || exporting) return;
        String query = game.getText().toString().trim();
        if (query.isEmpty()) { game.setError(getString(R.string.config_export_game_required)); return; }
        Context app = requireContext().getApplicationContext(); searching = true;
        Toast.makeText(requireContext(), R.string.config_export_searching, Toast.LENGTH_SHORT).show();
        IO.execute(() -> {
            List<ShortcutArtworkManager.CoverChoice> found = null;
            try { found = ShortcutArtworkManager.findCoverChoices(app, query); } catch (Exception ignored) {}
            List<ShortcutArtworkManager.CoverChoice> result = found;
            main.post(() -> {
                searching = false;
                if (!isAdded() || preview == null || exporting) return;
                if (result == null || result.isEmpty()) {
                    Toast.makeText(requireContext(), R.string.config_export_search_empty, Toast.LENGTH_LONG).show(); return;
                }
                showChoices(result);
            });
        });
    }
    private void showChoices(List<ShortcutArtworkManager.CoverChoice> choices) {
        Context context = requireContext(); GridLayout grid = new GridLayout(context); grid.setColumnCount(3);
        int width = Math.min(dp(600), getResources().getDisplayMetrics().widthPixels - dp(64)) / 3;
        for (ShortcutArtworkManager.CoverChoice choice : choices) {
            LinearLayout cell = new LinearLayout(context); cell.setOrientation(LinearLayout.VERTICAL); cell.setPadding(dp(4), dp(4), dp(4), dp(4));
            ImageView image = new ImageView(context); image.setScaleType(ImageView.ScaleType.FIT_CENTER);
            image.setContentDescription(choice.game); cell.addView(image, new LinearLayout.LayoutParams(width, width * 3 / 2));
            Glide.with(this).load(choice.thumbnail).override(192, 288).placeholder(R.drawable.icon_image_picker).into(image);
            TextView title = new TextView(context); title.setText(choice.game); title.setMaxLines(2); cell.addView(title, new LinearLayout.LayoutParams(width, -2));
            cell.setFocusable(true); cell.setClickable(true); cell.setContentDescription(choice.game);
            cell.setBackgroundResource(R.drawable.combo_box);
            cell.setOnClickListener(v -> {
                if (choicesDialog != null) choicesDialog.dismiss();
                loadCover(() -> ShortcutArtworkManager.loadCoverChoice(choice.url), true);
            }); grid.addView(cell);
        }
        ScrollView scroll = new ScrollView(context); scroll.addView(grid);
        choicesDialog = new AlertDialog.Builder(context).setTitle(R.string.config_export_choose_cover).setView(scroll)
                .setNegativeButton(R.string.cancel, null).create(); choicesDialog.show();
        if (choicesDialog.getWindow() != null) choicesDialog.getWindow().setLayout(Math.min(dp(640), getResources().getDisplayMetrics().widthPixels - dp(32)),
                Math.round(getResources().getDisplayMetrics().heightPixels * 0.75f));
    }

    private void export() {
        if (exporting || coverPending || container == null) return;
        String title = game.getText().toString().trim();
        if (title.isEmpty() || title.matches("(?i)container[- _]*\\d+")) {
            game.setError(getString(R.string.config_export_game_required)); return;
        }
        RadioButton status = compatibility.findViewById(compatibility.getCheckedRadioButtonId());
        if (status == null) { Toast.makeText(requireContext(), R.string.config_export_status_required, Toast.LENGTH_SHORT).show(); return; }
        CommunityConfigManager.Metadata metadata = new CommunityConfigManager.Metadata();
        metadata.gameName = title; metadata.author = author.getText().toString().trim();
        metadata.fps = fps.getText().toString().trim(); metadata.notes = notes.getText().toString();
        metadata.compatibility = String.valueOf(status.getTag()); metadata.coverFile = selectedCover; metadata.useAutomaticCover = false;
        ActivityManager manager = (ActivityManager)requireContext().getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo memory = new ActivityManager.MemoryInfo();
        if (manager != null) manager.getMemoryInfo(memory);
        metadata.ram = String.format(Locale.US, "%.1f GB", memory.totalMem / 1073741824.0);
        Context app = requireContext().getApplicationContext(); Container current = container;
        exporting = true; setCancelable(false); updateExportButton();
        ((AlertDialog)requireDialog()).getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(false);
        IO.execute(() -> {
            File output = null;
            try { output = CommunityConfigManager.exportConfig(app, current, metadata); }
            catch (Exception error) { android.util.Log.e(TAG, "Configuration export failed", error); }
            File result = output;
            main.post(() -> {
                exporting = false;
                if (!isAdded() || getDialog() == null) return;
                setCancelable(true); updateExportButton();
                ((AlertDialog)requireDialog()).getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(true);
                if (result == null) { Toast.makeText(requireContext(), R.string.config_export_failed, Toast.LENGTH_LONG).show(); return; }
                android.app.Activity host = requireActivity();
                new AlertDialog.Builder(host).setTitle(R.string.config_export_complete)
                        .setMessage(getString(R.string.config_export_saved, result.getAbsolutePath()))
                        .setPositiveButton(R.string.config_export_share, (d, which) -> share(host, result))
                        .setNegativeButton(android.R.string.ok, null).show();
                dismiss();
            });
        });
    }
    private static void share(android.app.Activity host, File output) {
        if (host.isFinishing() || host.isDestroyed()) return;
        Uri uri = FileProvider.getUriForFile(host, host.getPackageName() + ".fileprovider", output);
        Intent intent = new Intent(Intent.ACTION_SEND).setType("application/zip").putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try { host.startActivity(Intent.createChooser(intent, host.getString(R.string.config_export_share))); }
        catch (android.content.ActivityNotFoundException unavailable) {
            Toast.makeText(host, R.string.config_export_share_unavailable, Toast.LENGTH_LONG).show();
        }
    }
}
