package com.winlator.cmod.inputcontrols;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.AssetManager;
import android.media.MediaScannerConnection;
import android.os.Environment;
import android.util.JsonReader;

import androidx.preference.PreferenceManager;

import com.winlator.cmod.core.AppUtils;
import com.winlator.cmod.core.FileUtils;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;

public class InputControlsManager {
    private final Context context;
    private ArrayList<ControlsProfile> profiles;
    private int maxProfileId;
    private boolean profilesLoaded = false;

    public InputControlsManager(Context context) {
        this.context = context;
    }

    public static File getProfilesDir(Context context) {
        File profilesDir = new File(context.getFilesDir(), "profiles");
        if (!profilesDir.isDirectory()) profilesDir.mkdir();
        return profilesDir;
    }

    public ArrayList<ControlsProfile> getProfiles() {
        return getProfiles(false);
    }

    public ArrayList<ControlsProfile> getProfiles(boolean ignoreTemplates) {
        if (!profilesLoaded) loadProfiles(ignoreTemplates);
        return profiles;
    }

    private static final String REMOVED_DEFAULTS = "inputcontrols_removed_defaults";
    private static final String ASSET_ID = "inputcontrols_asset_id_";

    /** Missing factory layouts are installed without overwriting edited/imported profiles. */
    private void copyAssetProfilesIfNeeded() {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
        java.util.Set<String> removed = preferences.getStringSet(REMOVED_DEFAULTS, Collections.emptySet());
        File directory = getProfilesDir(context);
        File[] files = directory.listFiles();
        if (files == null) return;
        for (File file : files) {
            ControlsProfile profile = loadProfile(context, file);
            if (profile != null) maxProfileId = Math.max(maxProfileId, profile.id);
        }
        try {
            String[] assets = context.getAssets().list("inputcontrols/profiles");
            if (assets == null) return;
            for (String asset : assets) {
                if (removed.contains(asset)) continue;
                int installedId = preferences.getInt(ASSET_ID + asset, -1);
                if (installedId >= 0 && ControlsProfile.getProfileFile(context, installedId).isFile()) continue;
                String source = "inputcontrols/profiles/" + asset;
                JSONObject data;
                try (InputStream stream = context.getAssets().open(source)) {
                    data = new JSONObject(new String(readAsset(stream), StandardCharsets.UTF_8));
                }
                int id = data.getInt("id");
                File destination = ControlsProfile.getProfileFile(context, id);
                ControlsProfile existing = loadProfile(context, destination);
                // Adopt pre-existing bundled layouts once; updates must retain user edits.
                if (installedId < 0 && existing != null && data.getString("name").equals(existing.getName())) {
                    preferences.edit().putInt(ASSET_ID + asset, id).apply();
                    continue;
                }
                if (destination.exists()) { id = ++maxProfileId; destination = ControlsProfile.getProfileFile(context,id); }
                maxProfileId = Math.max(maxProfileId,id);
                data.put("id",id);
                FileUtils.writeString(destination,data.toString());
                if (loadProfile(context,destination) != null)
                    preferences.edit().putInt(ASSET_ID + asset,id).apply();
            }
        } catch (IOException | JSONException error) {
            android.util.Log.w("InputControls","Unable to install bundled control presets",error);
        }
    }

    private static byte[] readAsset(InputStream stream) throws IOException {
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[8192]; int count;
        while ((count=stream.read(buffer))!=-1) output.write(buffer,0,count);
        return output.toByteArray();
    }

    /** Explicit recovery restores missing defaults and leaves custom layouts untouched. */
    public int restoreDefaultProfiles() {
        int before = getProfiles().size();
        PreferenceManager.getDefaultSharedPreferences(context).edit().remove(REMOVED_DEFAULTS).apply();
        loadProfiles(false);
        return Math.max(0,profiles.size()-before);
    }

    public void removeAllProfiles() {
        getProfiles();
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
        java.util.Set<String> removed = new java.util.HashSet<>(preferences.getStringSet(REMOVED_DEFAULTS,Collections.emptySet()));
        try {
            String[] assets = context.getAssets().list("inputcontrols/profiles");
            if (assets != null) Collections.addAll(removed,assets);
        } catch(IOException error) { android.util.Log.w("InputControls","Unable to list defaults",error); }
        SharedPreferences.Editor editor = preferences.edit().putStringSet(REMOVED_DEFAULTS,removed);
        for (String asset : removed) editor.remove(ASSET_ID+asset);
        editor.apply();
        File[] files = getProfilesDir(context).listFiles();
        if (files != null) for (File file : files)
            if (file.isFile() && file.getName().matches("controls-[0-9]+\\.icp") && !file.delete())
                android.util.Log.w("InputControls","Unable to remove " + file.getName());
        loadProfiles(false);
    }

    public void loadProfiles(boolean ignoreTemplates) {
        File profilesDir = InputControlsManager.getProfilesDir(context);
        copyAssetProfilesIfNeeded();

        ArrayList<ControlsProfile> profiles = new ArrayList<>();
        File[] files = profilesDir.listFiles();
        if (files != null) {
            for (File file : files) {
                ControlsProfile profile = loadProfile(context, file);
                // Skip unreadable/corrupt profile files instead of crashing the listing.
                if (profile == null || profile.getName() == null) continue;
                if (!(ignoreTemplates && profile.isTemplate())) profiles.add(profile);
                maxProfileId = Math.max(maxProfileId, profile.id);
            }
        }

        Collections.sort(profiles);
        this.profiles = profiles;
        profilesLoaded = true;
    }

    public ControlsProfile createProfile(String name) {
        ControlsProfile profile = new ControlsProfile(context, ++maxProfileId);
        profile.setName(name);
        profile.save();
        profiles.add(profile);
        return profile;
    }

    public ControlsProfile duplicateProfile(ControlsProfile source) {
        String newName;
        for (int i = 1;;i++) {
            newName = source.getName() + " ("+i+")";
            boolean found = false;
            for (ControlsProfile profile : profiles) {
                if (profile.getName().equals(newName)) {
                    found = true;
                    break;
                }
            }
            if (!found) break;
        }

        int newId = ++maxProfileId;
        File newFile = ControlsProfile.getProfileFile(context, newId);

        try {
            JSONObject data = new JSONObject(FileUtils.readString(ControlsProfile.getProfileFile(context, source.id)));
            data.put("id", newId);
            data.put("name", newName);
            if (data.has("template")) data.remove("template");
            FileUtils.writeString(newFile, data.toString());
        }
        catch (JSONException e) {}

        ControlsProfile profile = loadProfile(context, newFile);
        profiles.add(profile);
        return profile;
    }

    public void removeProfile(ControlsProfile profile) {
        File file = ControlsProfile.getProfileFile(context, profile.id);
        if (file.isFile() && file.delete()) {
            SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
            java.util.Set<String> removed = new java.util.HashSet<>(preferences.getStringSet(REMOVED_DEFAULTS,Collections.emptySet()));
            for (java.util.Map.Entry<String,?> entry : preferences.getAll().entrySet())
                if (entry.getKey().startsWith(ASSET_ID) && entry.getValue() instanceof Integer
                        && ((Integer)entry.getValue()) == profile.id)
                    removed.add(entry.getKey().substring(ASSET_ID.length()));
            SharedPreferences.Editor editor = preferences.edit().putStringSet(REMOVED_DEFAULTS,removed);
            for (String asset : removed) editor.remove(ASSET_ID+asset);
            editor.apply();
            profiles.remove(profile);
        }
    }

    public ControlsProfile importProfile(JSONObject data) {
        try {
            if (!data.has("id") || !data.has("name")) return null;
            int newId = ++maxProfileId;
            File newFile = ControlsProfile.getProfileFile(context, newId);
            data.put("id", newId);
            FileUtils.writeString(newFile, data.toString());
            ControlsProfile newProfile = loadProfile(context, newFile);

            int foundIndex = -1;
            for (int i = 0; i < profiles.size(); i++) {
                ControlsProfile profile = profiles.get(i);
                if (profile.getName().equals(newProfile.getName())) {
                    foundIndex = i;
                    break;
                }
            }

            if (foundIndex != -1) {
                profiles.set(foundIndex, newProfile);
            }
            else profiles.add(newProfile);
            return newProfile;
        }
        catch (JSONException e) {
            return null;
        }
    }

    public File exportProfile(ControlsProfile profile) {
        File downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        File destination = new File(downloadsDir, "Winlator/profiles/"+profile.getName()+".icp");
        FileUtils.copy(ControlsProfile.getProfileFile(context, profile.id), destination);
        MediaScannerConnection.scanFile(context, new String[]{destination.getAbsolutePath()}, null, null);
        return destination.isFile() ? destination : null;
    }

    public static ControlsProfile loadProfile(Context context, File file) {
        try {
            return loadProfile(context, new FileInputStream(file));
        }
        catch (FileNotFoundException e) {
            return null;
        }
    }

    public static ControlsProfile loadProfile(Context context, InputStream inStream) {
        try (JsonReader reader = new JsonReader(new InputStreamReader(inStream, StandardCharsets.UTF_8))) {
            int profileId = 0;
            String profileName = null;
            float cursorSpeed = Float.NaN;

            reader.beginObject();
            while (reader.hasNext()) {
                String name = reader.nextName();

                if (name.equals("id")) {
                    profileId = reader.nextInt();
                }
                else if (name.equals("name")) {
                    profileName = reader.nextString();
                }
                else if (name.equals("cursorSpeed")) {
                    cursorSpeed = (float) reader.nextDouble();
                }
                else {
                    reader.skipValue();
                }
            }
            reader.endObject();

            ControlsProfile profile = new ControlsProfile(context, profileId);
            profile.setName(profileName);
            profile.setCursorSpeed(cursorSpeed);
            return profile;
        }
        catch (IOException | IllegalStateException | NumberFormatException e) {
            return null;
        }
    }

    public ControlsProfile getProfile(int id) {
        for (ControlsProfile profile : getProfiles()) if (profile.id == id) return profile;
        return null;
    }
}
