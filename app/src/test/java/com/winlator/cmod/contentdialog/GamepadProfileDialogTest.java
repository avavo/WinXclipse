package com.winlator.cmod.contentdialog;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Application;
import android.content.SharedPreferences;
import android.os.Looper;
import android.widget.Spinner;

import androidx.preference.PreferenceManager;

import com.winlator.cmod.R;
import com.winlator.cmod.inputcontrols.ExternalController;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.shadows.ShadowToast;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.LinkedHashSet;

import static org.junit.Assert.*;

/** Exercises real profile files and dialog callbacks without starting Wine or native code. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class, qualifiers = "w320dp-h480dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.LEGACY)
public class GamepadProfileDialogTest {
    private ActivityController<Activity> activityController;
    private Activity activity;
    private ContentDialog dialog;
    private GamepadConfiguratorDialog configurator;
    private ExternalController controller;
    private SharedPreferences preferences;
    private File profiles;

    @Before public void setUp() throws Exception {
        activityController = Robolectric.buildActivity(Activity.class).setup();
        activity = activityController.get();
        activity.setTheme(R.style.AppTheme);
        preferences = PreferenceManager.getDefaultSharedPreferences(activity);
        preferences.edit().clear().commit();
        profiles = new File(activity.getFilesDir(), "gamepad_profiles");
        assertTrue(profiles.isDirectory() || profiles.mkdirs());
        controller = new ExternalController();
        dialog = new ContentDialog(activity, R.layout.dialog_gamepad_configurator);
        configurator = new GamepadConfiguratorDialog(activity, controller, dialog);
        ShadowToast.reset();
    }

    @After public void tearDown() {
        dialog.dismiss();
        activityController.pause().stop().destroy();
    }

    private void show(String... names) {
        preferences.edit().putStringSet("gamepad_profiles", new LinkedHashSet<>(Arrays.asList(names))).commit();
        configurator.setupProfileControls();
        dialog.show();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    private void write(String name, String json) throws Exception {
        Files.write(new File(profiles, name + ".json").toPath(), json.getBytes(StandardCharsets.UTF_8));
    }

    private String document(String mappings) { return "{\"buttonMappings\":[" + mappings + "]}"; }

    private void clickLoad() {
        dialog.findViewById(R.id.btn_load_profile).performClick();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    private void assertLoadFailed() {
        assertEquals(activity.getString(R.string.gamepad_profile_load_failed), ShadowToast.getTextOfLatestToast());
    }

    @Test @Config(qualifiers = "pt-w320dp-h480dp-mdpi")
    public void emptyListHasLocalizedLabelAndNoLoadAction() {
        show();
        Spinner spinner = dialog.findViewById(R.id.spinner_profile);
        assertEquals(activity.getString(R.string.gamepad_profiles_empty), spinner.getSelectedItem().toString());
        assertFalse(dialog.findViewById(R.id.btn_load_profile).isEnabled());
        clickLoad();
        assertNull(ShadowToast.getTextOfLatestToast());
    }

    @Test public void oldEnglishPlaceholderCanBeARealProfileName() throws Exception {
        String name = "-- No Profiles --";
        write(name, document("{\"buttonIndex\":0,\"mappedIndex\":1}"));
        show(name);
        assertTrue(dialog.findViewById(R.id.btn_load_profile).isEnabled());
        clickLoad();
        assertEquals(1, controller.getMappedButton((byte)0));
        assertEquals(activity.getString(R.string.gamepad_profile_loaded), ShadowToast.getTextOfLatestToast());
    }

    @Test @Config(qualifiers = "es-w320dp-h480dp-mdpi")
    public void localizedPlaceholderCanAlsoBeARealProfileName() throws Exception {
        String name = activity.getString(R.string.gamepad_profiles_empty);
        write(name, document("{\"buttonIndex\":0,\"mappedIndex\":2}"));
        show(name);
        clickLoad();
        assertEquals(2, controller.getMappedButton((byte)0));
        assertEquals(activity.getString(R.string.gamepad_profile_loaded), ShadowToast.getTextOfLatestToast());
    }

    @Test public void malformedLaterMappingDoesNotPartlyApplyOrReportSuccess() throws Exception {
        controller.setButtonMapping((byte)0, (byte)3);
        controller.setButtonMapping((byte)1, (byte)4);
        write("broken", document("{\"buttonIndex\":0,\"mappedIndex\":1},{\"buttonIndex\":1}"));
        show("broken");
        clickLoad();
        assertEquals(3, controller.getMappedButton((byte)0));
        assertEquals(4, controller.getMappedButton((byte)1));
        assertLoadFailed();
    }

    @Test public void missingFileDoesNotReportLoaded() {
        show("missing");
        clickLoad();
        assertLoadFailed();
    }

    @Test public void invalidIndicesAndDuplicatesNeverPartlyApply() throws Exception {
        String[] invalid = {
                "{\"buttonIndex\":1,\"mappedIndex\":12}",
                "{\"buttonIndex\":-1,\"mappedIndex\":2}",
                "{\"buttonIndex\":1.5,\"mappedIndex\":2}",
                "{\"buttonIndex\":1,\"mappedIndex\":2.5}",
                "{\"buttonIndex\":0,\"mappedIndex\":2}"
        };
        for (String mapping : invalid) {
            controller.setButtonMapping((byte)0, (byte)3);
            write("invalid", document("{\"buttonIndex\":0,\"mappedIndex\":1}," + mapping));
            assertFalse(mapping, configurator.loadProfile("invalid"));
            assertEquals(mapping, 3, controller.getMappedButton((byte)0));
            assertLoadFailed();
        }
    }

    @Test public void oversizedProfileIsRejectedBeforeParsing() throws Exception {
        byte[] large = new byte[65537];
        Arrays.fill(large, (byte)' ');
        Files.write(new File(profiles, "large.json").toPath(), large);
        assertFalse(configurator.loadProfile("large"));
        assertLoadFailed();
    }

    @Test public void overwriteFailureDoesNotReportSavedOrReplaceExistingDirectory() throws Exception {
        File blocked = new File(profiles, "blocked.json");
        assertTrue(blocked.mkdir());
        File keep = new File(blocked, "keep");
        Files.write(keep.toPath(), new byte[]{42});
        show("blocked");
        configurator.setupMappingSpinners();
        dialog.findViewById(R.id.btn_save_profile).performClick();
        AlertDialog prompt = ShadowAlertDialog.getLatestAlertDialog();
        assertNotNull(prompt);
        prompt.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals(activity.getString(R.string.gamepad_profile_save_failed), ShadowToast.getTextOfLatestToast());
        assertArrayEquals(new byte[]{42}, Files.readAllBytes(keep.toPath()));
        assertArrayEquals(new String[]{"blocked.json"}, profiles.list());
    }

    @Test public void unicodeProfileRoundTripsAllButtonsAndKeepsItsName() throws Exception {
        String name = "Ação ñ クラシック";
        for (byte i = 0; i < 12; i++) controller.setButtonMapping(i, (byte)((i + 1) % 12));
        configurator.setupMappingSpinners();
        // Axis triggers intentionally keep their own mapping in the UI.
        Spinner leftTrigger = dialog.findViewById(R.id.spinner_button_l2);
        Spinner rightTrigger = dialog.findViewById(R.id.spinner_button_r2);
        leftTrigger.setSelection(11); // R3 -> button index 9
        rightTrigger.setSelection(0); // A -> button index 0
        assertTrue(configurator.saveProfile(name));
        assertTrue(new String(Files.readAllBytes(new File(profiles, name + ".json").toPath()),
                StandardCharsets.UTF_8).contains(name));
        for (byte i = 0; i < 12; i++) controller.setButtonMapping(i, i);
        assertTrue(configurator.loadProfile(name));
        for (byte i = 0; i < 10; i++) assertEquals((i + 1) % 12, controller.getMappedButton(i));
        assertEquals(9, controller.getMappedButton((byte)10));
        assertEquals(0, controller.getMappedButton((byte)11));
        show(name);
        assertEquals(name, ((Spinner)dialog.findViewById(R.id.spinner_profile)).getSelectedItem().toString());
    }

    @Test public void pathNamesCannotReadOrOverwriteOutsideProfiles() throws Exception {
        File outside = new File(activity.getFilesDir(), "outside.json");
        byte[] original = document("{\"buttonIndex\":0,\"mappedIndex\":1}").getBytes(StandardCharsets.UTF_8);
        Files.write(outside.toPath(), original);
        configurator.setupMappingSpinners();
        for (String name : new String[]{"../outside", "..\\outside", "", "  "}) {
            assertFalse(configurator.loadProfile(name));
            assertFalse(configurator.saveProfile(name));
            assertArrayEquals(original, Files.readAllBytes(outside.toPath()));
            assertTrue(preferences.getStringSet("gamepad_profiles", new LinkedHashSet<>()).isEmpty());
        }
    }
}
