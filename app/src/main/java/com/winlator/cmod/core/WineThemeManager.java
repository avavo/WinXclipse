package com.winlator.cmod.core;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;

import com.winlator.cmod.R;
import com.winlator.cmod.xenvironment.ImageFs;
import com.winlator.cmod.xserver.ScreenInfo;

import java.io.File;

public abstract class WineThemeManager {
    public static final int DESKTOP_THEME_REVISION = 3;
    public enum Theme {SYSTEM, LIGHT, DARK}
    public enum BackgroundType {IMAGE, COLOR}
    public static final String DEFAULT_DESKTOP_THEME = Theme.SYSTEM+","+BackgroundType.IMAGE+",#0277bd";

    public static class ThemeInfo {
        public final Theme theme;
        public final BackgroundType backgroundType;
        public final int backgroundColor;

        public ThemeInfo(String value) {
            Theme parsedTheme = Theme.SYSTEM;
            BackgroundType parsedBackgroundType = BackgroundType.IMAGE;
            int parsedBackgroundColor;
            try {
                parsedBackgroundColor = Color.parseColor("#0277bd");
            } catch (Exception e) {
                parsedBackgroundColor = 0xff0277bd;
            }

            try {
                String[] values = value.split(",");
                if (values.length > 0 && values[0] != null) parsedTheme = Theme.valueOf(values[0]);
                if (values.length < 3) {
                    parsedBackgroundColor = Color.parseColor(values.length > 1 ? values[1] : "#0277bd");
                    parsedBackgroundType = BackgroundType.IMAGE;
                }
                else {
                    parsedBackgroundType = BackgroundType.valueOf(values[1]);
                    parsedBackgroundColor = Color.parseColor(values[2]);
                }
            }
            catch (IllegalArgumentException | IndexOutOfBoundsException | NullPointerException ignored) {}

            theme = parsedTheme;
            backgroundType = parsedBackgroundType;
            backgroundColor = parsedBackgroundColor;
        }
    }

    public static boolean apply(Context context, ThemeInfo themeInfo, ScreenInfo screenInfo) {
        File rootDir = ImageFs.find(context).getRootDir();
        File userRegFile = new File(rootDir, ImageFs.WINEPREFIX+"/user.reg");
        String background = Color.red(themeInfo.backgroundColor)+" "+Color.green(themeInfo.backgroundColor)+" "+Color.blue(themeInfo.backgroundColor);
        Theme resolvedTheme = getResolvedTheme(context, themeInfo.theme);
        boolean wallpaperReady = true;

        if (themeInfo.backgroundType == BackgroundType.IMAGE) {
            wallpaperReady = createWallpaperBMPFile(context, screenInfo, resolvedTheme);
        }

        try (WineRegistryEditor registryEditor = new WineRegistryEditor(userRegFile)) {
            if (themeInfo.backgroundType == BackgroundType.IMAGE && wallpaperReady) {
                registryEditor.setStringValue("Control Panel\\Desktop", "Wallpaper", ImageFs.CACHE_PATH+"/wallpaper.bmp");
                // The old icon-style wallpaper was intentionally generated at
                // 480p and centered.  The new full desktop artwork must cover
                // the Wine desktop even if a game changes its resolution.
                registryEditor.setStringValue("Control Panel\\Desktop", "WallpaperStyle", "2");
                registryEditor.setStringValue("Control Panel\\Desktop", "TileWallpaper", "0");
            }
            else {
                registryEditor.removeValue("Control Panel\\Desktop", "Wallpaper");
                registryEditor.removeValue("Control Panel\\Desktop", "WallpaperStyle");
                registryEditor.removeValue("Control Panel\\Desktop", "TileWallpaper");
            }

            if (resolvedTheme == Theme.LIGHT) {
                registryEditor.setStringValue("Control Panel\\Colors", "ActiveBorder", "245 245 245");
                registryEditor.setStringValue("Control Panel\\Colors", "ActiveTitle", "96 125 139");
                registryEditor.setStringValue("Control Panel\\Colors", "Background", background);
                registryEditor.setStringValue("Control Panel\\Colors", "ButtonAlternateFace", "245 245 245");
                registryEditor.setStringValue("Control Panel\\Colors", "ButtonDkShadow", "158 158 158");
                registryEditor.setStringValue("Control Panel\\Colors", "ButtonFace", "245 245 245");
                registryEditor.setStringValue("Control Panel\\Colors", "ButtonHilight", "224 224 224");
                registryEditor.setStringValue("Control Panel\\Colors", "ButtonLight", "255 255 255");
                registryEditor.setStringValue("Control Panel\\Colors", "ButtonShadow", "158 158 158");
                registryEditor.setStringValue("Control Panel\\Colors", "ButtonText", "0 0 0");
                registryEditor.setStringValue("Control Panel\\Colors", "GradientActiveTitle", "96 125 139");
                registryEditor.setStringValue("Control Panel\\Colors", "GradientInactiveTitle", "117 117 117");
                registryEditor.setStringValue("Control Panel\\Colors", "GrayText", "158 158 158");
                registryEditor.setStringValue("Control Panel\\Colors", "Hilight", "2 136 209");
                registryEditor.setStringValue("Control Panel\\Colors", "HilightText", "255 255 255");
                registryEditor.setStringValue("Control Panel\\Colors", "HotTrackingColor", "2 136 209");
                registryEditor.setStringValue("Control Panel\\Colors", "InactiveBorder", "255 255 255");
                registryEditor.setStringValue("Control Panel\\Colors", "InactiveTitle", "117 117 117");
                registryEditor.setStringValue("Control Panel\\Colors", "InactiveTitleText", "200 200 200");
                registryEditor.setStringValue("Control Panel\\Colors", "InfoText", "0 0 0");
                registryEditor.setStringValue("Control Panel\\Colors", "InfoWindow", "255 255 255");
                registryEditor.setStringValue("Control Panel\\Colors", "Menu", "245 245 245");
                registryEditor.setStringValue("Control Panel\\Colors", "MenuBar", "245 245 245");
                registryEditor.setStringValue("Control Panel\\Colors", "MenuHilight", "2 136 209");
                registryEditor.setStringValue("Control Panel\\Colors", "MenuText", "0 0 0");
                registryEditor.setStringValue("Control Panel\\Colors", "Scrollbar", "245 245 245");
                registryEditor.setStringValue("Control Panel\\Colors", "TitleText", "255 255 255");
                registryEditor.setStringValue("Control Panel\\Colors", "Window", "245 245 245");
                registryEditor.setStringValue("Control Panel\\Colors", "WindowFrame", "158 158 158");
                registryEditor.setStringValue("Control Panel\\Colors", "WindowText", "0 0 0");
            }
            else {
                registryEditor.setStringValue("Control Panel\\Colors", "ActiveBorder", "48 48 48");
                registryEditor.setStringValue("Control Panel\\Colors", "ActiveTitle", "33 33 33");
                registryEditor.setStringValue("Control Panel\\Colors", "Background", background);
                registryEditor.setStringValue("Control Panel\\Colors", "ButtonAlternateFace", "33 33 33");
                registryEditor.setStringValue("Control Panel\\Colors", "ButtonDkShadow", "0 0 0");
                registryEditor.setStringValue("Control Panel\\Colors", "ButtonFace", "33 33 33");
                registryEditor.setStringValue("Control Panel\\Colors", "ButtonHilight", "48 48 48");
                registryEditor.setStringValue("Control Panel\\Colors", "ButtonLight", "48 48 48");
                registryEditor.setStringValue("Control Panel\\Colors", "ButtonShadow", "0 0 0");
                registryEditor.setStringValue("Control Panel\\Colors", "ButtonText", "255 255 255");
                registryEditor.setStringValue("Control Panel\\Colors", "GradientActiveTitle", "33 33 33");
                registryEditor.setStringValue("Control Panel\\Colors", "GradientInactiveTitle", "33 33 33");
                registryEditor.setStringValue("Control Panel\\Colors", "GrayText", "117 117 117");
                registryEditor.setStringValue("Control Panel\\Colors", "Hilight", "2 136 209");
                registryEditor.setStringValue("Control Panel\\Colors", "HilightText", "255 255 255");
                registryEditor.setStringValue("Control Panel\\Colors", "HotTrackingColor", "2 136 209");
                registryEditor.setStringValue("Control Panel\\Colors", "InactiveBorder", "48 48 48");
                registryEditor.setStringValue("Control Panel\\Colors", "InactiveTitle", "33 33 33");
                registryEditor.setStringValue("Control Panel\\Colors", "InactiveTitleText", "117 117 117");
                registryEditor.setStringValue("Control Panel\\Colors", "InfoText", "255 255 255");
                registryEditor.setStringValue("Control Panel\\Colors", "InfoWindow", "255 255 255");
                registryEditor.setStringValue("Control Panel\\Colors", "Menu", "33 33 33");
                registryEditor.setStringValue("Control Panel\\Colors", "MenuBar", "48 48 48");
                registryEditor.setStringValue("Control Panel\\Colors", "MenuHilight", "2 136 209");
                registryEditor.setStringValue("Control Panel\\Colors", "MenuText", "255 255 255");
                registryEditor.setStringValue("Control Panel\\Colors", "Scrollbar", "48 48 48");
                registryEditor.setStringValue("Control Panel\\Colors", "TitleText", "255 255 255");
                registryEditor.setStringValue("Control Panel\\Colors", "Window", "48 48 48");
                registryEditor.setStringValue("Control Panel\\Colors", "WindowFrame", "0 0 0");
                registryEditor.setStringValue("Control Panel\\Colors", "WindowText", "255 255 255");
            }
        }
        return wallpaperReady;
    }

    /** Resolves Follow Android while preserving an explicit Light/Dark override. */
    public static Theme getResolvedTheme(Context context, Theme configuredTheme) {
        if (configuredTheme == Theme.LIGHT || configuredTheme == Theme.DARK) {
            return configuredTheme;
        }
        // Follow Android must follow the device configuration itself, even if
        // WinXclipse's own interface has a manual Light/Dark override.
        int nightMode = android.content.res.Resources.getSystem().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
        if (nightMode == android.content.res.Configuration.UI_MODE_NIGHT_YES) return Theme.DARK;
        if (nightMode == android.content.res.Configuration.UI_MODE_NIGHT_NO) return Theme.LIGHT;
        return AppUtils.isDarkMode(context) ? Theme.DARK : Theme.LIGHT;
    }

    private static boolean createWallpaperBMPFile(Context context, ScreenInfo screenInfo,
                                                   Theme resolvedTheme) {
        int outputWidth = Math.max(1, screenInfo.width);
        int outputHeight = Math.max(1, screenInfo.height);

        Bitmap outputBitmap = Bitmap.createBitmap(outputWidth, outputHeight, Bitmap.Config.ARGB_8888);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        Canvas canvas = new Canvas(outputBitmap);

        File userWallpaperFile = getUserWallpaperFile(context);
        Bitmap image = userWallpaperFile.isFile() ? BitmapFactory.decodeFile(userWallpaperFile.getPath()) : null;
        boolean customWallpaper = image != null;
        if (!customWallpaper) {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inScaled = false;
            image = BitmapFactory.decodeResource(context.getResources(),
                    resolvedTheme == Theme.DARK
                            ? R.drawable.wine_wallpaper_dark
                            : R.drawable.wine_wallpaper_light,
                    options);
        }

        if (image != null) {
            // Center-crop instead of distorting the artwork on 4:3, 16:10 or
            // ultrawide container resolutions.
            int sourceWidth = image.getWidth();
            int sourceHeight = image.getHeight();
            float sourceAspect = (float)sourceWidth / sourceHeight;
            float targetAspect = (float)outputWidth / outputHeight;
            Rect srcRect;
            if (sourceAspect > targetAspect) {
                int cropWidth = Math.max(1, Math.round(sourceHeight * targetAspect));
                int left = (sourceWidth - cropWidth) / 2;
                srcRect = new Rect(left, 0, left + cropWidth, sourceHeight);
            }
            else {
                int cropHeight = Math.max(1, Math.round(sourceWidth / targetAspect));
                int top = (sourceHeight - cropHeight) / 2;
                srcRect = new Rect(0, top, sourceWidth, top + cropHeight);
            }
            Rect dstRect = new Rect(0, 0, outputWidth, outputHeight);
            canvas.drawBitmap(image, srcRect, dstRect, paint);
            image.recycle();
        }
        else {
            // Keep a deterministic fallback if either the custom or bundled
            // image cannot be decoded; never leave Wine with a stale BMP.
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(resolvedTheme == Theme.DARK ? 0xff120925 : 0xfff4a18c);
            canvas.drawRect(0, 0, outputWidth, outputHeight * 0.5f, paint);
            paint.setColor(resolvedTheme == Theme.DARK ? 0xff0b1740 : 0xff322080);
            canvas.drawRect(0, outputHeight * 0.5f, outputWidth, outputHeight, paint);
        }

        ImageFs imageFs = ImageFs.find(context);
        File outputFile = new File(imageFs.getRootDir(), ImageFs.CACHE_PATH+"/wallpaper.bmp");
        File parent = outputFile.getParentFile();
        boolean parentReady = parent != null && (parent.isDirectory() || parent.mkdirs());
        boolean written = parentReady && MSBitmap.create(outputBitmap, outputFile);
        outputBitmap.recycle();
        return written && outputFile.isFile() && outputFile.length() > 54;
    }

    public static File getUserWallpaperFile(Context context) {
        return new File(ImageFs.find(context).getRootDir(), ImageFs.CONFIG_PATH+"/user-wallpaper.png");
    }
}
