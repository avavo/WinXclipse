package com.winlator.cmod.core;

import android.content.Context;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.Log;

import androidx.annotation.NonNull;

import com.winlator.cmod.R;
import com.winlator.cmod.contents.ContentProfile;
import com.winlator.cmod.contents.ContentsManager;
import com.winlator.cmod.xenvironment.ImageFs;

import java.io.File;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class WineInfo implements Parcelable {
    public static final WineInfo MAIN_WINE_VERSION = new WineInfo("proton", "9.0", "arm64ec");
    private static final Pattern pattern = Pattern.compile("^(wine|proton|Proton)\\-([0-9\\.]+)\\-?([0-9\\.]+)?\\-(x86|x86_64|arm64ec)$");
    public final String version;
    public final String type;
    public String subversion;
    public final String path;
    private String arch;

    public WineInfo(String type, String version, String arch) {
        this.type = type;
        this.version = version;
        this.subversion = null;
        this.arch = arch;
        this.path = null;
    }

    public WineInfo(String type, String version, String subversion, String arch, String path) {
        this.type = type;
        this.version = version;
        this.subversion = subversion != null && !subversion.isEmpty() ? subversion : null;
        this.arch = arch;
        this.path = path;
    }

    public WineInfo(String type, String version, String arch, String path) {
        this.type = type;
        this.version = version;
        this.arch = arch;
        this.path = path;
    }

    private WineInfo(Parcel in) {
        type = in.readString();
        version = in.readString();
        subversion = in.readString();
        arch = in.readString();
        path = in.readString();
    }

    public String getArch() {
        return arch;
    }

    public void setArch(String arch) {
        this.arch = arch;
    }

    public boolean isWin64() {
        return arch.equals("x86_64") || arch.equals("arm64ec");
    }

    public boolean isArm64EC() { return arch.equals("arm64ec"); }

    public String identifier() {
        if (type.equals("proton"))
            return "proton-" + fullVersion() + "-"+ arch;
        else
            return "wine-" + fullVersion() + "-" + arch;
    }

    public String fullVersion() {
        return version+(subversion != null ? "-"+subversion : "");
    }

    @NonNull
    @Override
    public String toString() {
        if (type.equals("proton"))
            return "Proton "+fullVersion()+(this == MAIN_WINE_VERSION ? " (Custom)" : "");
        else
            return "Wine "+fullVersion()+(this == MAIN_WINE_VERSION ? " (Custom)" : "");
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final Parcelable.Creator<WineInfo> CREATOR = new Parcelable.Creator<WineInfo>() {
        public WineInfo createFromParcel(Parcel in) {
            return new WineInfo(in);
        }

        public WineInfo[] newArray(int size) {
            return new WineInfo[size];
        }
    };

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(type);
        dest.writeString(version);
        dest.writeString(subversion);
        dest.writeString(arch);
        dest.writeString(path);
    }

    @NonNull
    public static WineInfo fromIdentifier(Context context, ContentsManager contentsManager, String identifier) {
        ImageFs imageFs = ImageFs.find(context);
        String path = "";

        Log.d("WineInfo", "Creating WineInfo from identifier " + identifier);

        if (identifier.equals(MAIN_WINE_VERSION.identifier())) return new WineInfo(MAIN_WINE_VERSION.type, MAIN_WINE_VERSION.version, MAIN_WINE_VERSION.arch, imageFs.getRootDir().getPath() + "/opt/" + MAIN_WINE_VERSION.identifier());

        ContentProfile selectedEntry = contentsManager.getProfileByEntryName(identifier);
        ContentProfile wineProfile = findInstalledRuntimeProfile(contentsManager, identifier);

        // getProfileByEntryName also sees downloadable catalog entries. An
        // uninstalled entry has no usable bin/lib tree and must not masquerade
        // as a launchable Proton runtime.
        if (selectedEntry != null && wineProfile == null) {
            Log.w("WineInfo", "Ignoring uninstalled runtime profile " + identifier);
        }

        if (wineProfile != null && (wineProfile.type == ContentProfile.ContentType.CONTENT_TYPE_WINE || wineProfile.type == ContentProfile.ContentType.CONTENT_TYPE_PROTON)) {
            String profileVersion = wineProfile.verName.toLowerCase(Locale.ENGLISH);
            String profileArch;
            if (profileVersion.endsWith("-arm64ec")) profileArch = "arm64ec";
            else if (profileVersion.endsWith("-x86_64")) profileArch = "x86_64";
            else if (profileVersion.endsWith("-x86")) profileArch = "x86";
            else profileArch = profileVersion.contains("arm64ec") ? "arm64ec" : "x86_64";

            String archSuffix = "-" + profileArch;
            String version = profileVersion.endsWith(archSuffix)
                    ? profileVersion.substring(0, profileVersion.length() - archSuffix.length())
                    : profileVersion;
            String type = wineProfile.type == ContentProfile.ContentType.CONTENT_TYPE_PROTON
                    ? "proton" : "wine";
            return new WineInfo(type, version, profileArch,
                    contentsManager.getInstallDir(context, wineProfile).getPath());
        }

        Matcher matcher = pattern.matcher(identifier);

        if (matcher.find()) {
            String[] wineVersions = context.getResources().getStringArray(R.array.wine_entries);
            for (String wineVersion : wineVersions) {
                if (path.isEmpty() && wineVersion.contains(identifier)) {
                    path = imageFs.getRootDir().getPath() + "/opt/" + identifier;
                    break;
                }
            }

            if (path.isEmpty()) {
                File legacyDir = new File(imageFs.getRootDir(), "opt/" + identifier);
                if (legacyDir.isDirectory()) path = legacyDir.getPath();
            }

            if (path.isEmpty()) {
                // Keep an explicit expected path instead of the empty string,
                // which previously produced commands such as "/wine" and hid
                // the actual missing-runtime error in startup logs.
                path = new File(imageFs.getRootDir(), "opt/" + identifier).getPath();
                Log.e("WineInfo", "Runtime is not installed: " + identifier
                        + " (expected " + path + ")");
            }

            return new WineInfo(matcher.group(1), matcher.group(2), matcher.group(3),
                    matcher.group(4), path);
        }
        else return new WineInfo(MAIN_WINE_VERSION.type, MAIN_WINE_VERSION.version, MAIN_WINE_VERSION.arch, imageFs.getRootDir().getPath() + "/opt/" + MAIN_WINE_VERSION.identifier());
    }

    /** Resolves both current content entry names and the identifiers stored by
     * older containers (for example proton-9.0-x86_64) to an installed WCP. */
    public static ContentProfile findInstalledRuntimeProfile(ContentsManager contentsManager,
                                                             String identifier) {
        ContentProfile direct = contentsManager.getProfileByEntryName(identifier);
        if (direct != null && contentsManager.isInstalledProfile(direct)
                && (direct.type == ContentProfile.ContentType.CONTENT_TYPE_WINE
                || direct.type == ContentProfile.ContentType.CONTENT_TYPE_PROTON)) {
            return direct;
        }

        Matcher matcher = pattern.matcher(identifier == null ? "" : identifier);
        if (!matcher.find()) return null;
        String subversion = matcher.group(3);
        String profileVersion = matcher.group(2)
                + (subversion != null && !subversion.isEmpty() ? "-" + subversion : "")
                + "-" + matcher.group(4);
        ContentProfile.ContentType profileType = matcher.group(1).equalsIgnoreCase("proton")
                ? ContentProfile.ContentType.CONTENT_TYPE_PROTON
                : ContentProfile.ContentType.CONTENT_TYPE_WINE;

        ContentProfile best = null;
        for (ContentProfile profile : contentsManager.getProfiles(profileType)) {
            if (profileVersion.equalsIgnoreCase(profile.verName)
                    && contentsManager.isInstalledProfile(profile)
                    && (best == null || profile.verCode > best.verCode)) {
                best = profile;
            }
        }
        return best;
    }

    public static boolean isMainWineVersion(String wineVersion) {
        return wineVersion == null ||wineVersion.equals(MAIN_WINE_VERSION.identifier());
    }
}
