package com.winlator.cmod.widget;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.PopupWindow;

import androidx.annotation.Nullable;

import com.winlator.cmod.MainActivity;
import com.winlator.cmod.R;
import com.winlator.cmod.core.AppUtils;
import com.winlator.cmod.core.FileUtils;
import com.winlator.cmod.core.ImageUtils;
import com.winlator.cmod.core.UnitUtils;
import com.winlator.cmod.core.WineThemeManager;

import java.io.File;

public class ImagePickerView extends View implements View.OnClickListener {
    private final Bitmap icon;
    private Bitmap preview;
    private WineThemeManager.Theme previewTheme = WineThemeManager.Theme.SYSTEM;

    public ImagePickerView(Context context) {
        this(context, null);
    }

    public ImagePickerView(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public ImagePickerView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);

        icon = BitmapFactory.decodeResource(context.getResources(), R.drawable.icon_image_picker);
        reloadPreview();

        setBackgroundResource(R.drawable.combo_box);
        setClickable(true);
        setFocusable(true);
        setOnClickListener(this);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        int width = getWidth();
        int height = getHeight();
        if (width == 0 || height == 0) return;

        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        float inset = UnitUtils.dpToPx(2);
        float radius = UnitUtils.dpToPx(7);
        int saveCount = canvas.save();
        Path clip = new Path();
        clip.addRoundRect(new RectF(inset, inset, width - inset, height - inset),
                radius, radius, Path.Direction.CW);
        canvas.clipPath(clip);
        if (preview != null) {
            float sourceRatio = (float) preview.getWidth() / preview.getHeight();
            float targetRatio = (float) width / height;
            int cropWidth = preview.getWidth();
            int cropHeight = preview.getHeight();
            if (sourceRatio > targetRatio) cropWidth = Math.round(cropHeight * targetRatio);
            else cropHeight = Math.round(cropWidth / targetRatio);
            int left = (preview.getWidth() - cropWidth) / 2;
            int top = (preview.getHeight() - cropHeight) / 2;
            canvas.drawBitmap(preview, new Rect(left, top, left + cropWidth, top + cropHeight),
                    new RectF(inset, inset, width - inset, height - inset), paint);
        }

        // Keep the picker affordance visible without hiding the wallpaper preview.
        float iconSize = UnitUtils.dpToPx(30);
        float padding = UnitUtils.dpToPx(8);
        paint.setColor(0x99000000);
        canvas.drawRoundRect(width - iconSize - padding * 2, height - iconSize - padding * 2,
                width - padding / 2, height - padding / 2, padding, padding, paint);
        paint.setColor(0xFFFFFFFF);
        Rect srcRect = new Rect(0, 0, icon.getWidth(), icon.getHeight());
        RectF dstRect = new RectF(width - iconSize - padding, height - iconSize - padding,
                width - padding, height - padding);
        canvas.drawBitmap(icon, srcRect, dstRect, paint);
        canvas.restoreToCount(saveCount);
    }

    @Override
    public void onClick(View anchor) {
        final Context context = getContext();
        final File userWallpaperFile = WineThemeManager.getUserWallpaperFile(context);

        View view = LayoutInflater.from(context).inflate(R.layout.image_picker_view, null);
        ImageView imageView = view.findViewById(R.id.ImageView);
        // The popup must show the same resolved light/dark/custom image as the
        // inline preview instead of falling back to the old blue wallpaper.
        reloadPreview();
        imageView.setImageBitmap(preview);

        final PopupWindow[] popupWindow = {null};
        View browseButton = view.findViewById(R.id.BTBrowse);
        browseButton.setOnClickListener((v) -> {
            MainActivity activity = (MainActivity)context;
            Intent intent = new Intent(Intent.ACTION_PICK);
            intent.setType("image/*");
            activity.setOpenFileCallback((data) -> {
                Bitmap bitmap = ImageUtils.getBitmapFromUri(context, data, 1280);
                if (bitmap == null) return;

                ImageUtils.save(bitmap, userWallpaperFile, Bitmap.CompressFormat.PNG, 100);
                popupWindow[0].dismiss();
                reloadPreview();
                invalidate();
            });
            activity.startActivityForResult(intent, MainActivity.OPEN_FILE_REQUEST_CODE);
        });

        View removeButton = view.findViewById(R.id.BTRemove);
        if (userWallpaperFile.isFile()) {
            removeButton.setVisibility(View.VISIBLE);
            removeButton.setOnClickListener((v) -> {
                FileUtils.delete(userWallpaperFile);
                popupWindow[0].dismiss();
                reloadPreview();
                invalidate();
            });
        }

        popupWindow[0] = AppUtils.showPopupWindow(anchor, view, 200, 240);
    }

    private void reloadPreview() {
        File wallpaperFile = WineThemeManager.getUserWallpaperFile(getContext());
        WineThemeManager.Theme resolvedTheme = WineThemeManager.getResolvedTheme(
                getContext(), previewTheme);
        preview = wallpaperFile.isFile()
                ? BitmapFactory.decodeFile(wallpaperFile.getPath())
                : BitmapFactory.decodeResource(getResources(),
                        resolvedTheme == WineThemeManager.Theme.DARK
                                ? R.drawable.wine_wallpaper_dark
                                : R.drawable.wine_wallpaper_light);
    }

    public void setPreviewTheme(WineThemeManager.Theme theme) {
        previewTheme = theme != null ? theme : WineThemeManager.Theme.SYSTEM;
        reloadPreview();
        invalidate();
    }
}
