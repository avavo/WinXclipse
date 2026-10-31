package com.winlator.cmod.widget;

import android.app.Application;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.ToggleButton;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.winlator.cmod.R;
import com.winlator.cmod.core.EnvVars;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class, qualifiers = "w320dp-h640dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.LEGACY)
public class PopupLayoutTest {
    private Context context() { return new ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.AppTheme_Dark); }
    private View inflate(int layout) { return LayoutInflater.from(context()).inflate(layout, null); }
    private void measure(View view, int width, int height) {
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.AT_MOST));
        view.layout(0, 0, view.getMeasuredWidth(), view.getMeasuredHeight());
    }
    private Rect rect(ViewGroup root, View child) {
        Rect rect = new Rect(0, 0, child.getWidth(), child.getHeight());
        root.offsetDescendantRectToMyCoords(child, rect);
        return rect;
    }
    private void inside(ViewGroup root, View child) {
        Rect actual = rect(root, child);
        assertTrue("No size for " + child.getId(), actual.width() > 0 && actual.height() > 0);
        assertTrue("Outside viewport: " + actual, new Rect(0, 0, root.getWidth(), root.getHeight()).contains(actual));
    }

    @Test public void zinkRowsDoNotOverlapAndNamesCanBeDraggedWithoutChangingValues() {
        EnvVarsView vars = new EnvVarsView(context());
        vars.add("ZINK_DESCRIPTORS", "lazy");
        vars.add("ZINK_DEBUG", "compact");
        vars.add("MESA_SHADER_CACHE_DISABLE", "false");
        vars.add("MESA_SHADER_CACHE_MAX_SIZE", "2048MB");
        String longName = "CUSTOM_VARIABLE_".repeat(20);
        vars.add(longName, "unchanged");
        measure(vars, 288, 1000);
        LinearLayout list = (LinearLayout)vars.getChildAt(0);
        int previousBottom = 0;
        for (int i = 0; i < list.getChildCount(); i++) {
            ViewGroup row = (ViewGroup)list.getChildAt(i);
            assertTrue(row.getTop() >= previousBottom);
            previousBottom = row.getBottom();
            inside(row, row.findViewById(R.id.HSVVariableName));
            inside(row, row.findViewById(R.id.BTRemove));
            int id = i == 0 ? R.id.Spinner : i == 1 ? R.id.LLMultiSelectArea : i == 2 ? R.id.ToggleButton : R.id.EditText;
            inside(row, row.findViewById(id));
            assertFalse(Rect.intersects(rect(row, row.findViewById(R.id.HSVVariableName)), rect(row, row.findViewById(id))));
        }
        HorizontalScrollView name = list.getChildAt(4).findViewById(R.id.HSVVariableName);
        name.scrollTo(Integer.MAX_VALUE, 0);
        assertTrue("Long names must be scrollable", name.getScrollX() > 0);
        EnvVars saved = new EnvVars(vars.getEnvVars());
        assertEquals("lazy", saved.get("ZINK_DESCRIPTORS"));
        assertEquals("compact", saved.get("ZINK_DEBUG"));
        assertEquals("2048MB", saved.get("MESA_SHADER_CACHE_MAX_SIZE"));
        assertEquals("unchanged", saved.get(longName));
        ToggleButton toggle = list.getChildAt(2).findViewById(R.id.ToggleButton);
        assertNull(toggle.getBackgroundTintList());
        toggle.performClick();
        assertEquals("true", new EnvVars(vars.getEnvVars()).get("MESA_SHADER_CACHE_DISABLE"));
    }

    @Test public void presetRowsKeepHelpAndEditorsInsideAtLargeFontSize() {
        Context base = context();
        Configuration config = new Configuration(base.getResources().getConfiguration());
        config.fontScale = 1.6f;
        Context large = new ContextThemeWrapper(base.createConfigurationContext(config), R.style.AppTheme_Dark);
        ViewGroup row = (ViewGroup)LayoutInflater.from(large).inflate(R.layout.box86_64_env_var_list_item, null);
        ((TextView)row.findViewById(R.id.TextView)).setText("FEX_INTERPRETER_LONG_OPTION_NAME".repeat(15));
        row.findViewById(R.id.EditText).setVisibility(View.VISIBLE);
        measure(row, 256, 500);
        inside(row, row.findViewById(R.id.BTHelp));
        inside(row, row.findViewById(R.id.EditText));
        HorizontalScrollView name = row.findViewById(R.id.HSVVariableName);
        name.scrollTo(Integer.MAX_VALUE, 0);
        assertTrue(name.getScrollX() > 0);
    }

    @Test public void downloadButtonsAreCenteredWithTheirVersionSelectors() {
        ViewGroup root = (ViewGroup)inflate(R.layout.dxvk_config_dialog);
        measure(root, 288, 520);
        for (int[] pair : new int[][]{{R.id.SVersion, R.id.BTDownloadDXVKContent}, {R.id.SVKD3DVersion, R.id.BTDownloadVKD3DContent}}) {
            View spinner = root.findViewById(pair[0]), button = root.findViewById(pair[1]);
            inside(root, button);
            assertEquals(rect(root, spinner).centerY(), rect(root, button).centerY());
            assertTrue(button.getWidth() >= 48 && button.getHeight() >= 48);
        }
        ViewGroup body = (ViewGroup)root.getChildAt(0);
        assertEquals(root.getWidth() - root.getPaddingLeft() - root.getPaddingRight(), body.getWidth());
    }

    private ViewGroup catalog(int count) {
        ViewGroup root = (ViewGroup)inflate(R.layout.content_downloads_dialog);
        FrameLayout body = root.findViewById(R.id.FLContentDownloads);
        ViewGroup contents = (ViewGroup)LayoutInflater.from(root.getContext()).inflate(R.layout.contents_fragment, body, false);
        body.addView(contents);
        DownloadCatalogLayout.prepareEmbedded(contents);
        contents.findViewById(R.id.TVContentType).setVisibility(View.GONE);
        contents.findViewById(R.id.LLContentType).setVisibility(View.GONE);
        contents.findViewById(R.id.VContentTypeDivider).setVisibility(View.GONE);
        RecyclerView list = contents.findViewById(R.id.RecyclerView);
        list.setLayoutManager(new LinearLayoutManager(root.getContext()));
        list.setAdapter(new RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            @Override public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int type) {
                return new RecyclerView.ViewHolder(LayoutInflater.from(parent.getContext()).inflate(R.layout.content_list_item, parent, false)) {};
            }
            @Override public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
                ((TextView)holder.itemView.findViewById(R.id.TVVersionName)).setText("Proton 11.0-1-arm64ec " + position);
                ((TextView)holder.itemView.findViewById(R.id.TVVersionCode)).setText("515099447");
                holder.itemView.findViewById(R.id.BTMenu).setVisibility(View.GONE);
            }
            @Override public int getItemCount() { return count; }
        });
        measure(root, 288, 640);
        return root;
    }

    @Test public void shortCatalogWrapsItsRowsInsteadOfTakingNinetyPercentOfScreen() {
        ViewGroup root = catalog(1);
        assertTrue("Short list wastes vertical space: " + root.getHeight(), root.getHeight() < 320);
        inside(root, root.findViewById(R.id.BTCloseContentDownloads));
        inside(root, root.findViewById(R.id.BTInstallContent));
        inside(root, root.findViewById(R.id.RecyclerView));
    }

    @Test public void longCatalogScrollsWhileCloseAndInstallRemainVisible() {
        ViewGroup root = catalog(100);
        assertTrue(root.getHeight() <= Math.round(640 * .74f));
        inside(root, root.findViewById(R.id.BTCloseContentDownloads));
        inside(root, root.findViewById(R.id.BTInstallContent));
        RecyclerView list = root.findViewById(R.id.RecyclerView);
        assertTrue(list.canScrollVertically(1));
        list.scrollToPosition(99);
        measure(root, 288, 640);
        assertNotNull(list.getLayoutManager().findViewByPosition(99));
        inside(root, root.findViewById(R.id.BTInstallContent));
    }

    @Test public void transferProgressDoesNotSqueezeOrOverlapThePackageName() {
        ViewGroup row = (ViewGroup)inflate(R.layout.content_list_item);
        ((TextView)row.findViewById(R.id.TVVersionName)).setText("Proton 11.0-1-arm64ec");
        ((TextView)row.findViewById(R.id.TVVersionCode)).setText("515099447");
        ((TextView)row.findViewById(R.id.TVProgressStatus)).setText("Downloading 75%");
        row.findViewById(R.id.BTMenu).setVisibility(View.GONE);
        row.findViewById(R.id.LLTransferProgress).setVisibility(View.VISIBLE);
        measure(row, 256, 500);
        assertTrue(row.findViewById(R.id.TVVersionName).getWidth() >= 120);
        inside(row, row.findViewById(R.id.BTDownload));
        inside(row, row.findViewById(R.id.Progress));
        inside(row, row.findViewById(R.id.BTDiscardDownload));
        assertTrue(rect(row, row.findViewById(R.id.LLTransferProgress)).top >= rect(row, row.findViewById(R.id.TVVersionCode)).bottom);
    }
}
