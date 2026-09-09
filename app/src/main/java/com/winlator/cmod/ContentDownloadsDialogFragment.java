package com.winlator.cmod;

import android.app.Dialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.FragmentManager;

import com.winlator.cmod.core.AppUtils;

/** Keeps the complete content catalog inside the container editor. */
public class ContentDownloadsDialogFragment extends DialogFragment {
    private static final String ARG_CATEGORY = "category";
    private Runnable dismissCallback;

    public static ContentDownloadsDialogFragment newInstance(int category) {
        ContentDownloadsDialogFragment dialog = new ContentDownloadsDialogFragment();
        Bundle args = new Bundle();
        args.putInt(ARG_CATEGORY, category);
        dialog.setArguments(args);
        return dialog;
    }

    public ContentDownloadsDialogFragment onClosed(Runnable callback) {
        dismissCallback = callback;
        return this;
    }

    public static void showFixed(FragmentManager manager, int category, Runnable callback) {
        newInstance(category).onClosed(callback).show(manager,
                "content-downloads-" + category);
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setStyle(STYLE_NO_TITLE, 0);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.content_downloads_dialog, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        view.setBackgroundResource(AppUtils.isDarkMode(requireContext())
                ? R.drawable.artwork_dialog_background
                : R.drawable.artwork_dialog_background_light);
        view.findViewById(R.id.BTCloseContentDownloads).setOnClickListener(v -> dismiss());
        ((android.widget.TextView) view.findViewById(R.id.TVContentDownloadsTitle))
                .setText(ContentsFragment.getCategoryTitle(requireContext(),
                        getArguments() != null ? getArguments().getInt(ARG_CATEGORY, 0) : 0));
        if (savedInstanceState == null) {
            int category = getArguments() != null ? getArguments().getInt(ARG_CATEGORY, 0) : 0;
            getChildFragmentManager().beginTransaction()
                    .replace(R.id.FLContentDownloads,
                            ContentsFragment.newInstance(category, true, true))
                    .commit();
        }
    }

    @Override
    public void onStart() {
        super.onStart();
        Dialog dialog = getDialog();
        if (dialog == null) return;
        Window window = dialog.getWindow();
        if (window == null) return;
        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        WindowManager.LayoutParams params = new WindowManager.LayoutParams();
        params.copyFrom(window.getAttributes());
        params.width = WindowManager.LayoutParams.MATCH_PARENT;
        params.height = (int) (getResources().getDisplayMetrics().heightPixels * 0.90f);
        window.setAttributes(params);
    }

    @Override
    public void onDismiss(@NonNull android.content.DialogInterface dialog) {
        super.onDismiss(dialog);
        if (getParentFragment() instanceof ContainerDetailFragment) {
            ((ContainerDetailFragment) getParentFragment()).refreshDownloadedContent();
        }
        if (dismissCallback != null) dismissCallback.run();
    }
}
