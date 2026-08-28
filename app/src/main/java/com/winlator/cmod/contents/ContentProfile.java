package com.winlator.cmod.contents;

import androidx.annotation.NonNull;

import java.util.Locale;
import java.util.List;
import java.io.File;

public class ContentProfile {
    public static final String MARK_TYPE = "type";
    public static final String MARK_ID = "contentId";
    public static final String MARK_VERSION_NAME = "versionName";
    public static final String MARK_VERSION_CODE = "versionCode";
    public static final String MARK_DESC = "description";
    public static final String MARK_FILE_LIST = "files";
    public static final String MARK_FILE_SOURCE = "source";
    public static final String MARK_FILE_TARGET = "target";
    public static final String MARK_WINE = "wine";
    public static final String MARK_WINE_BINPATH = "binPath";
    public static final String MARK_WINE_LIBPATH = "libPath";
    public static final String MARK_WINE_PREFIX_PACK = "prefixPack";
    public static final String MARK_PROTON = "proton";
    public static final String MARK_PROTON_BINPATH = "binPath";
    public static final String MARK_PROTON_LIBPATH = "libPath";
    public static final String MARK_PROTON_PREFIX_PACK = "prefixPack";

    public enum ContentType {
        CONTENT_TYPE_WINE("Wine"),
        CONTENT_TYPE_PROTON("Proton"),
        CONTENT_TYPE_DXVK("DXVK"),
        CONTENT_TYPE_VKD3D("VKD3D"),
        CONTENT_TYPE_BOX64("Box64"),
        CONTENT_TYPE_WOWBOX64("WOWBox64"),
        CONTENT_TYPE_FEXCORE("FEXCore");

        final String typeName;

        ContentType(String typeName) {
            this.typeName = typeName;
        }

        @NonNull
        @Override
        public String toString() {
            return typeName;
        }

        public static ContentType getTypeByName(String name) {
            for (ContentType type : ContentType.values())
                if (type.typeName.toLowerCase().equals(name.toLowerCase()))
                    return type;
            return null;
        }
    }

    public static class ContentFile {
        public String source;
        public String target;
    }

    public ContentType type;
    public String verName;
    public int verCode;
    public String desc;
    public List<ContentFile> fileList;
    public String wineLibPath;
    public String wineBinPath;
    public String winePrefixPack;
    public String protonLibPath;

    public String protonBinPath;
    public String protonPrefixPack;
    public String remoteUrl;
    public String contentId;
    /** Temporary extraction directory, populated only during installation. */
    File stagingDir;

    void setStagingDir(File stagingDir) {
        this.stagingDir = stagingDir;
    }

    File getStagingDir() {
        return stagingDir;
    }

    /**
     * Stable identifier used by exported/community configurations.  Unlike an
     * installation path it remains portable across devices and app updates.
     * Third-party packages without an id receive the same deterministic id as
     * soon as their profile is read.
     */
    public String getContentId() {
        if (contentId == null || contentId.trim().isEmpty()) {
            contentId = buildContentId(type, verName, verCode);
        }
        return contentId;
    }

    public static String buildContentId(ContentType type, String versionName, int versionCode) {
        String typePart = type != null ? type.toString().toLowerCase(Locale.ENGLISH) : "unknown";
        String versionPart = versionName == null ? "unknown" : versionName.trim().toLowerCase(Locale.ENGLISH);
        versionPart = versionPart.replaceAll("[^a-z0-9._+-]+", "-").replaceAll("^-+|-+$", "");
        if (versionPart.isEmpty()) versionPart = "unknown";
        return "winxclipse.content." + typePart + "." + versionPart + "." + Math.max(0, versionCode);
    }
}
