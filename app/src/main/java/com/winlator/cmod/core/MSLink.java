package com.winlator.cmod.core;

import android.content.Context;
import android.util.Log;

import com.winlator.cmod.container.Container;
import com.winlator.cmod.container.Shortcut;
import com.winlator.cmod.xenvironment.ImageFs;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

public abstract class MSLink {
    public static final byte SW_SHOWNORMAL = 1;
    public static final byte SW_SHOWMAXIMIZED = 3;
    public static final byte SW_SHOWMINNOACTIVE = 7;
    private static final int HasLinkTargetIDList = 1<<0;

    private static final int HasLinkInfo = 1 << 1;
    private static final int HasRelativePath = 1<<3;
    private static final int HasArguments = 1<<5;
    private static final int HasIconLocation = 1<<6;
    private static final int IsUnicode = 1<<7;
    private static final int ForceNoLinkInfo = 1<<8;

    public static final class Options {
        public String targetPath;
        public String cmdArgs;
        public String iconLocation;
        public int iconIndex;
        public int fileSize;
        public int showCommand = SW_SHOWNORMAL;
    }

    private static int charToHexDigit(char chr) {
        return chr >= 'A' ? chr - 'A' + 10 : chr - '0';
    }

    private static byte twoCharsToByte(char chr1, char chr2) {
        return (byte)(charToHexDigit(Character.toUpperCase(chr1)) * 16 + charToHexDigit(Character.toUpperCase(chr2)));
    }

    private static byte[] convertCLSIDtoDATA(String str) {
        return new byte[]{
            twoCharsToByte(str.charAt(6), str.charAt(7)),
            twoCharsToByte(str.charAt(4), str.charAt(5)),
            twoCharsToByte(str.charAt(2), str.charAt(3)),
            twoCharsToByte(str.charAt(0), str.charAt(1)),
            twoCharsToByte(str.charAt(11), str.charAt(12)),
            twoCharsToByte(str.charAt(9), str.charAt(10)),
            twoCharsToByte(str.charAt(16), str.charAt(17)),
            twoCharsToByte(str.charAt(14), str.charAt(15)),
            twoCharsToByte(str.charAt(19), str.charAt(20)),
            twoCharsToByte(str.charAt(21), str.charAt(22)),
            twoCharsToByte(str.charAt(24), str.charAt(25)),
            twoCharsToByte(str.charAt(26), str.charAt(27)),
            twoCharsToByte(str.charAt(28), str.charAt(29)),
            twoCharsToByte(str.charAt(30), str.charAt(31)),
            twoCharsToByte(str.charAt(32), str.charAt(33)),
            twoCharsToByte(str.charAt(34), str.charAt(35))
        };
    }

    private static byte[] stringToByteArray(String str) {
        byte[] bytes = new byte[str.length()];
        for (int i = 0; i < bytes.length; i++) bytes[i] = (byte)str.charAt(i);
        return bytes;
    }

    private static byte[] intToByteArray(int value) {
        return ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array();
    }

    private static byte[] stringSizePaddedToByteArray(String str) {
        ByteBuffer buffer = ByteBuffer.allocate(str.length() + 2).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putShort((short)str.length());
        for (int i = 0; i < str.length(); i++) buffer.put((byte)str.charAt(i));
        return buffer.array();
    }

    private static byte[] generateIDLIST(byte[] bytes) {
        ByteBuffer buffer = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort((short)(bytes.length + 2));
        return ArrayUtils.concat(buffer.array(), bytes);
    }

    public static void createFile(String targetPath, File outputFile) {
        Options options = new Options();
        options.targetPath = targetPath;
        createFile(options, outputFile);
    }

    public static void createFile(Options options, File outputFile) {
        byte[] HeaderSize = new byte[]{0x4c, 0x00, 0x00, 0x00};
        byte[] LinkCLSID = convertCLSIDtoDATA("00021401-0000-0000-c000-000000000046");

        int linkFlags = HasLinkTargetIDList | ForceNoLinkInfo;
        if (options.cmdArgs != null && !options.cmdArgs.isEmpty()) linkFlags |= HasArguments;
        if (options.iconLocation != null && !options.iconLocation.isEmpty()) linkFlags |= HasIconLocation;

        byte[] LinkFlags = intToByteArray(linkFlags);

        byte[] FileAttributes, prefixOfTarget;
        options.targetPath = options.targetPath.replaceAll("/+", "\\\\");
        if (options.targetPath.endsWith("\\")) {
            FileAttributes = new byte[]{0x10, 0x00, 0x00, 0x00};
            prefixOfTarget = new byte[]{0x31, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00};
            options.targetPath = options.targetPath.replaceAll("\\\\+$", "");
        }
        else {
            FileAttributes = new byte[]{0x20, 0x00, 0x00, 0x00};
            prefixOfTarget = new byte[]{0x32, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00};
        }

        byte[] CreationTime, AccessTime, WriteTime;
        CreationTime = AccessTime = WriteTime = new byte[]{0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00};

        byte[] FileSize = intToByteArray(options.fileSize);
        byte[] IconIndex = intToByteArray(options.iconIndex);
        byte[] ShowCommand = intToByteArray(options.showCommand);
        byte[] Hotkey = new byte[]{0x00, 0x00};
        byte[] Reserved1 = new byte[]{0x00, 0x00};
        byte[] Reserved2 = new byte[]{0x00, 0x00, 0x00, 0x00};
        byte[] Reserved3 = new byte[]{0x00, 0x00, 0x00, 0x00};

        byte[] CLSIDComputer = convertCLSIDtoDATA("20d04fe0-3aea-1069-a2d8-08002b30309d");
        byte[] CLSIDNetwork = convertCLSIDtoDATA("208d2c60-3aea-1069-a2d7-08002b30309d");

        byte[] itemData, prefixRoot, targetRoot, targetLeaf;
        if (options.targetPath.startsWith("\\")) {
            prefixRoot = new byte[]{(byte)0xc3, 0x01, (byte)0x81};
            targetRoot = stringToByteArray(options.targetPath);
            targetLeaf = !options.targetPath.endsWith("\\") ? stringToByteArray(options.targetPath.substring(options.targetPath.lastIndexOf("\\") + 1)) : null;
            itemData = ArrayUtils.concat(new byte[]{0x1f, 0x58}, CLSIDNetwork);
        }
        else {
            prefixRoot = new byte[]{0x2f};
            int index = options.targetPath.indexOf("\\");
            targetRoot = stringToByteArray(options.targetPath.substring(0, index+1));
            targetLeaf = stringToByteArray(options.targetPath.substring(index+1));
            itemData = ArrayUtils.concat(new byte[]{0x1f, 0x50}, CLSIDComputer);
        }

        targetRoot = ArrayUtils.concat(targetRoot, new byte[21]);

        byte[] endOfString = new byte[]{0x00};
        byte[] IDListItems = ArrayUtils.concat(generateIDLIST(itemData), generateIDLIST(ArrayUtils.concat(prefixRoot, targetRoot, endOfString)));
        if (targetLeaf != null) IDListItems = ArrayUtils.concat(IDListItems, generateIDLIST(ArrayUtils.concat(prefixOfTarget, targetLeaf, endOfString)));
        byte[] IDList = generateIDLIST(IDListItems);

        byte[] TerminalID = new byte[]{0x00, 0x00};

        byte[] StringData = new byte[0];
        if ((linkFlags & HasArguments) != 0) StringData = ArrayUtils.concat(StringData, stringSizePaddedToByteArray(options.cmdArgs));
        if ((linkFlags & HasIconLocation) != 0) StringData = ArrayUtils.concat(StringData, stringSizePaddedToByteArray(options.iconLocation));

        try (FileOutputStream os = new FileOutputStream(outputFile)) {
            os.write(HeaderSize);
            os.write(LinkCLSID);
            os.write(LinkFlags);
            os.write(FileAttributes);
            os.write(CreationTime);
            os.write(AccessTime);
            os.write(WriteTime);
            os.write(FileSize);
            os.write(IconIndex);
            os.write(ShowCommand);
            os.write(Hotkey);
            os.write(Reserved1);
            os.write(Reserved2);
            os.write(Reserved3);
            os.write(IDList);
            os.write(TerminalID);

            if (StringData.length > 0) os.write(StringData);
        }
        catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * Parses a .lnk file to extract the target path.
     * @param lnkFile The .lnk file to parse.
     * @return The absolute path to the shortcut's target, or null if not found.
     * @throws IOException If there's an error reading the file.
     */
    public static String parse(File lnkFile) throws IOException {
        try (FileInputStream fis = new FileInputStream(lnkFile)) {
            if (lnkFile.length() < 76 || lnkFile.length() > 8 * 1024 * 1024) return null;
            byte[] fileBytes = new byte[(int) lnkFile.length()];
            int total = 0;
            while (total < fileBytes.length) {
                int count = fis.read(fileBytes, total, fileBytes.length - total);
                if (count < 0) break;
                total += count;
            }
            if (total != fileBytes.length || readIntLittleEndian(fileBytes, 0) != 0x4c) return null;

            int linkFlags = readIntLittleEndian(fileBytes, 20);
            int currentOffset = 76;
            if ((linkFlags & HasLinkTargetIDList) != 0) {
                int idListSize = readShortLittleEndian(fileBytes, currentOffset);
                currentOffset += idListSize + 2;
            }

            // The old parser read +28 as an ANSI path. In Shell Link files +16
            // is LocalBasePath and +28 is LocalBasePathUnicode (only when the
            // extended header is present). Wine commonly emits the Unicode form.
            if ((linkFlags & HasLinkInfo) != 0) {
                int linkInfoSize = readIntLittleEndian(fileBytes, currentOffset);
                int headerSize = readIntLittleEndian(fileBytes, currentOffset + 4);
                if (linkInfoSize >= 0x1c && currentOffset + linkInfoSize <= fileBytes.length) {
                    String base = readOffsetAnsi(fileBytes, currentOffset, linkInfoSize,
                            readIntLittleEndian(fileBytes, currentOffset + 16));
                    String suffix = readOffsetAnsi(fileBytes, currentOffset, linkInfoSize,
                            readIntLittleEndian(fileBytes, currentOffset + 24));
                    if (headerSize >= 0x24) {
                        String unicodeBase = readOffsetUnicode(fileBytes, currentOffset, linkInfoSize,
                                readIntLittleEndian(fileBytes, currentOffset + 28));
                        String unicodeSuffix = readOffsetUnicode(fileBytes, currentOffset, linkInfoSize,
                                readIntLittleEndian(fileBytes, currentOffset + 32));
                        if (!unicodeBase.isEmpty()) base = unicodeBase;
                        if (!unicodeSuffix.isEmpty()) suffix = unicodeSuffix;
                    }
                    String path = joinWindowsPath(base, suffix);
                    if (isUsableTarget(path)) return path;
                    currentOffset += linkInfoSize;
                }
            }

            // StringData may contain a relative target even when LinkInfo is absent.
            if ((linkFlags & HasRelativePath) != 0 && currentOffset + 2 <= fileBytes.length) {
                int chars = readShortLittleEndian(fileBytes, currentOffset);
                String relative = (linkFlags & IsUnicode) != 0
                        ? readFixedUnicode(fileBytes, currentOffset + 2, chars)
                        : readFixedAnsi(fileBytes, currentOffset + 2, chars);
                if (isUsableTarget(relative)) return relative;
            }

            Log.w("MSLinkParser", "Structured parse failed; scanning ANSI and Unicode paths.");
            for (int i = 0; i < fileBytes.length - 4; i++) {
                if (isDriveLetter(fileBytes[i]) && fileBytes[i + 1] == ':'
                        && fileBytes[i + 2] == '\\') {
                    String path = readNullTerminatedAnsi(fileBytes, i, fileBytes.length);
                    if (isUsableTarget(path)) return path;
                }
                if (i + 5 < fileBytes.length && isDriveLetter(fileBytes[i])
                        && fileBytes[i + 1] == 0 && fileBytes[i + 2] == ':'
                        && fileBytes[i + 3] == 0 && fileBytes[i + 4] == '\\'
                        && fileBytes[i + 5] == 0) {
                    String path = readNullTerminatedUnicode(fileBytes, i, fileBytes.length);
                    if (isUsableTarget(path)) return path;
                }
            }

            return null;
        }
    }

    /** Converts a Wine-created .lnk into the Android-visible .desktop shortcut. */
    public static File createDesktopFile(File lnkFile, Context context, Container container)
            throws IOException {
        String targetPath = parse(lnkFile);
        if (!isUsableTarget(targetPath)) return null;
        File desktopDir = container.getDesktopDir();
        if (!desktopDir.isDirectory() && !desktopDir.mkdirs()) return null;
        String name = FileUtils.getBasename(lnkFile.getName()).replace('\n', ' ').replace('\r', ' ').trim();
        if (name.isEmpty()) return null;
        File desktopFile = new File(desktopDir, name + ".desktop");
        if (desktopFile.isFile()) return desktopFile;

        String prefix = new File(context.getFilesDir(), "imagefs" + ImageFs.WINEPREFIX)
                .getAbsolutePath();
        String escapedTarget = StringUtils.escapeFileDOSPath(targetPath);
        String executable = FileUtils.getName(targetPath);
        String workDir = windowsWorkingDirectory(container, targetPath);
        String content = "[Desktop Entry]\n"
                + "Name=" + name + "\n"
                + "Exec=env WINEPREFIX=\"" + prefix + "\" wine " + escapedTarget + "\n"
                + "Type=Application\n"
                + "StartupNotify=true\n"
                + (workDir.isEmpty() ? "" : "Path=" + workDir + "\n")
                + "Icon=\n"
                + "StartupWMClass=" + executable + "\n\n"
                + "[Extra Data]\n"
                + "container_id=" + container.id + "\n";
        return Shortcut.writeDesktopFileWithBackup(desktopFile, content)
                ? desktopFile : null;
    }

    private static String windowsWorkingDirectory(Container container, String targetPath) {
        if (targetPath == null || targetPath.length() < 3 || targetPath.charAt(1) != ':') return "";
        int slash = targetPath.lastIndexOf('\\');
        if (slash <= 2) return "";
        String drive = String.valueOf(Character.toLowerCase(targetPath.charAt(0))) + ":";
        String relative = targetPath.substring(3, slash).replace('\\', File.separatorChar);
        return new File(new File(container.getRootDir(), ".wine/dosdevices/" + drive), relative)
                .getAbsolutePath();
    }

    private static String readOffsetAnsi(byte[] data, int block, int size, int offset) {
        if (offset <= 0 || offset >= size) return "";
        return readNullTerminatedAnsi(data, block + offset, block + size);
    }

    private static String readOffsetUnicode(byte[] data, int block, int size, int offset) {
        if (offset <= 0 || offset >= size) return "";
        return readNullTerminatedUnicode(data, block + offset, block + size);
    }

    private static String readNullTerminatedAnsi(byte[] data, int offset, int limit) {
        int length = 0;
        int end = Math.min(data.length, limit);
        while (offset + length < end && data[offset + length] != 0) length++;
        return offset >= 0 && offset + length <= end
                ? new String(data, offset, length, StandardCharsets.ISO_8859_1) : "";
    }

    private static String readNullTerminatedUnicode(byte[] data, int offset, int limit) {
        int end = Math.min(data.length, limit);
        int length = 0;
        while (offset + length + 1 < end
                && (data[offset + length] != 0 || data[offset + length + 1] != 0)) length += 2;
        return offset >= 0 && offset + length <= end
                ? new String(data, offset, length, StandardCharsets.UTF_16LE) : "";
    }

    private static String readFixedAnsi(byte[] data, int offset, int chars) {
        int length = Math.max(0, Math.min(chars, data.length - offset));
        return offset >= 0 && offset <= data.length
                ? new String(data, offset, length, StandardCharsets.ISO_8859_1) : "";
    }

    private static String readFixedUnicode(byte[] data, int offset, int chars) {
        int length = Math.max(0, Math.min(chars * 2, data.length - offset));
        return offset >= 0 && offset <= data.length
                ? new String(data, offset, length, StandardCharsets.UTF_16LE) : "";
    }

    private static String joinWindowsPath(String base, String suffix) {
        if (base == null) base = "";
        if (suffix == null) suffix = "";
        if (base.isEmpty()) return suffix;
        if (suffix.isEmpty() || base.toLowerCase(java.util.Locale.ENGLISH)
                .endsWith(suffix.toLowerCase(java.util.Locale.ENGLISH))) return base;
        if (base.endsWith("\\") || suffix.startsWith("\\")) return base + suffix;
        return base + "\\" + suffix;
    }

    private static boolean isDriveLetter(byte value) {
        char c = (char) (value & 0xff);
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z');
    }

    private static boolean isUsableTarget(String value) {
        if (value == null) return false;
        String lower = value.trim().toLowerCase(java.util.Locale.ENGLISH);
        return lower.endsWith(".exe") || lower.endsWith(".bat")
                || lower.endsWith(".cmd") || lower.endsWith(".com")
                || lower.endsWith(".msi");
    }

    private static int readIntLittleEndian(byte[] data, int offset) {
        if (offset + 3 >= data.length) return 0;
        return (data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8) | ((data[offset + 2] & 0xFF) << 16) | ((data[offset + 3] & 0xFF) << 24);
    }

    private static int readShortLittleEndian(byte[] data, int offset) {
        if (offset + 1 >= data.length) return 0;
        return (data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8);
    }
}
