package com.winlator.cmod;

import static com.winlator.cmod.core.AppUtils.showToast;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.drawable.Drawable;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.BatteryManager;
import android.os.FileObserver;
import android.os.Handler;
import android.os.Looper;
import android.os.Environment;
import android.util.Log;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.PointerIcon;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.AnimationUtils;
import android.view.animation.LayoutAnimationController;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.AdapterView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.preference.PreferenceManager;
import androidx.recyclerview.widget.DefaultItemAnimator;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.navigation.NavigationView;
import com.winlator.cmod.box86_64.rc.RCFile;
import com.winlator.cmod.box86_64.rc.RCManager;
import com.winlator.cmod.container.Container;
import com.winlator.cmod.container.ContainerManager;
import com.winlator.cmod.container.Shortcut;
import com.winlator.cmod.contentdialog.ContentDialog;
import com.winlator.cmod.contentdialog.ControllerAssignmentDialog;
import com.winlator.cmod.contentdialog.DXVKConfigDialog;
import com.winlator.cmod.contentdialog.ExperimentalPerformanceDialog;
import com.winlator.cmod.contentdialog.DebugDialog;
import com.winlator.cmod.contentdialog.GraphicsDriverConfigDialog;
import com.winlator.cmod.contentdialog.ScreenEffectDialog;
import com.winlator.cmod.contentdialog.VKD3DConfigDialog;
import com.winlator.cmod.contents.ContentProfile;
import com.winlator.cmod.contents.ContentsManager;
import com.winlator.cmod.contents.XclipseDriverManager;
import com.winlator.cmod.contents.CustomWrapperManager;
import com.winlator.cmod.core.AppUtils;
import com.winlator.cmod.core.DefaultVersion;
import com.winlator.cmod.core.CpuClusters;
import com.winlator.cmod.core.EnvVars;
import com.winlator.cmod.core.EnvironmentManager;
import com.winlator.cmod.core.FileUtils;
import com.winlator.cmod.core.GPUInformation;
import com.winlator.cmod.core.WinXclipsePolicy;
import com.winlator.cmod.core.RamOptimizerXclipse;
import com.winlator.cmod.core.KeyValueSet;
import com.winlator.cmod.core.OnExtractFileListener;
import com.winlator.cmod.core.PreloaderDialog;
import com.winlator.cmod.core.ContentOperationRegistry;
import com.winlator.cmod.core.Callback;
import com.winlator.cmod.core.ProcessHelper;
import com.winlator.cmod.core.StringUtils;
import com.winlator.cmod.core.TarCompressorUtils;
import com.winlator.cmod.core.Win32AppWorkarounds;
import com.winlator.cmod.core.WineInfo;
import com.winlator.cmod.core.WineRegistryEditor;
import com.winlator.cmod.core.WineRequestHandler;
import com.winlator.cmod.core.WineStartMenuCreator;
import com.winlator.cmod.core.WineThemeManager;
import com.winlator.cmod.core.WineUtils;
import com.winlator.cmod.fexcore.FEXCoreManager;
import com.winlator.cmod.inputcontrols.ControllerManager;
import com.winlator.cmod.inputcontrols.ControlsProfile;
import com.winlator.cmod.inputcontrols.ExternalController;
import com.winlator.cmod.inputcontrols.GamepadState;
import com.winlator.cmod.inputcontrols.InputControlsManager;
import com.winlator.cmod.inputcontrols.MotionControls;
import com.winlator.cmod.math.Mathf;
import com.winlator.cmod.math.XForm;
import com.winlator.cmod.midi.MidiHandler;
import com.winlator.cmod.midi.MidiManager;
import com.winlator.cmod.renderer.EffectComposer;
import com.winlator.cmod.renderer.GLRenderer;
import com.winlator.cmod.renderer.effects.CRTEffect;
import com.winlator.cmod.renderer.effects.ColorEffect;
import com.winlator.cmod.renderer.effects.FSREffect;
import com.winlator.cmod.renderer.effects.FSREasuEffect;
import com.winlator.cmod.renderer.effects.FXAAEffect;
import com.winlator.cmod.renderer.effects.HDREffect;
import com.winlator.cmod.renderer.effects.NTSCCombinedEffect;
import com.winlator.cmod.renderer.effects.ToonEffect;
import com.winlator.cmod.widget.HudDataSource;
import com.winlator.cmod.widget.ThemedSpinnerAdapter;

import java.util.Arrays;
import com.winlator.cmod.widget.WinlatorHUD;
import com.winlator.cmod.widget.InputControlsView;
import com.winlator.cmod.widget.LogView;
import com.winlator.cmod.widget.MagnifierView;
import com.winlator.cmod.widget.TouchpadView;
import com.winlator.cmod.widget.WinetricksFloatingView;
import com.winlator.cmod.widget.XServerView;
import com.winlator.cmod.winhandler.MouseEventFlags;
import com.winlator.cmod.winhandler.InlineTaskManagerPanel;
import com.winlator.cmod.winhandler.TaskManagerDialog;
import com.winlator.cmod.winhandler.WinHandler;
import com.winlator.cmod.xconnector.UnixSocketConfig;
import com.winlator.cmod.xenvironment.ImageFs;
import com.winlator.cmod.xenvironment.XEnvironment;
import com.winlator.cmod.xenvironment.components.ALSAServerComponent;
import com.winlator.cmod.xenvironment.components.BionicProgramLauncherComponent;
import com.winlator.cmod.xenvironment.components.GlibcProgramLauncherComponent;
import com.winlator.cmod.xenvironment.components.GuestProgramLauncherComponent;
import com.winlator.cmod.xenvironment.components.NetworkInfoUpdateComponent;
import com.winlator.cmod.xenvironment.components.PulseAudioComponent;
import com.winlator.cmod.xenvironment.components.SysVSharedMemoryComponent;
import com.winlator.cmod.xenvironment.components.XServerComponent;
import com.winlator.cmod.xserver.Pointer;
import com.winlator.cmod.xserver.Property;
import com.winlator.cmod.xserver.ScreenInfo;
import com.winlator.cmod.xserver.Window;
import com.winlator.cmod.xserver.WindowManager;
import com.winlator.cmod.xserver.XServer;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Locale;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import cn.sherlock.com.sun.media.sound.SF2Soundbank;

public class XServerDisplayActivity extends AppCompatActivity implements NavigationView.OnNavigationItemSelectedListener {
    private XServerView xServerView;
    private InputControlsView inputControlsView;
    private TouchpadView touchpadView;
    private XEnvironment environment;
    private DrawerLayout drawerLayout;
    private ContainerManager containerManager;
    protected Container container;
    private XServer xServer;
    private InputControlsManager inputControlsManager;
    private ImageFs imageFs;
    private WinlatorHUD frameRating = null;
    private HudDataSource hudDataSource = null;
    private long mRamOptSession = 0;
    private Runnable editInputControlsCallback;
    private Shortcut shortcut;
    private String graphicsDriver = Container.DEFAULT_GRAPHICS_DRIVER;
    private String wineRenderer = "vulkan";
    private HashMap<String, String> graphicsDriverConfig;
    private String audioDriver = Container.DEFAULT_AUDIO_DRIVER;
    private int audioVolume = 100;
    private String emulator = Container.DEFAULT_EMULATOR;
    private String dxwrapper = Container.DEFAULT_DXWRAPPER;
    private String ddrawrapper = Container.DEFAULT_DDRAWRAPPER;
    private KeyValueSet dxwrapperConfig;
    private KeyValueSet xperfConfig = new KeyValueSet(ExperimentalPerformanceDialog.DEFAULT_CONFIG);
    private String startupSelection;
    private float activeDisplayRefreshRate = 60f;
    private WineInfo wineInfo;
    private final EnvVars envVars = new EnvVars();
    private boolean firstTimeBoot = false;
    private static final String CONTAINER_COMMON_ASSET_REVISION = "1";
    private static final String PULSEAUDIO_ASSET_REVISION = "1";
    private SharedPreferences preferences;
    private OnExtractFileListener onExtractFileListener;
    private WinHandler winHandler;
    private WineRequestHandler wineRequestHandler;
    private float globalCursorSpeed = 1.0f;
    private MagnifierView magnifierView;
    private DebugDialog debugDialog;
    private short taskAffinityMask = 0;
    private short taskAffinityMaskWoW64 = 0;
    private int frameRatingWindowId = -1;
    private boolean pointerCaptureRequested = false; // Flag to track if pointer capture was requested
    private final float[] xform = XForm.getInstance();
    private ContentsManager contentsManager;
    private boolean navigationFocused = false;
    private MidiHandler midiHandler;
    private String midiSoundFont = "";
    private String lc_all = "";
    private String vkbasaltConfig = "";
    /** Effective BCN transcode path armed for this launch, shown with HUD API. */
    private volatile String bcnTranscodeHudMode = "";
    private volatile String bcnTranscodeBaseMode = "";
    private volatile String bcnTelemetryState = "";
    private volatile boolean bcnTelemetryRequested;
    private volatile boolean sawShortcutProcess;
    private volatile boolean observedShortcutApplication;
    private long shortcutIdleSinceMs;
    private static final long SHORTCUT_IDLE_CLOSE_DELAY_MS = 5000L;
    // Backstop para launch que nunca gera processo (stub travado, path obsoleto):
    // sem app por 2 min apos o start.exe aparecer, fecha em vez de prender a sessao.
    private static final long SHORTCUT_NEVER_STARTED_CLOSE_DELAY_MS = 120000L;
    private volatile boolean launchedAsShortcut;
    private volatile long shortcutLaunchMs;
    private volatile boolean sawStartExe;
    private volatile boolean automaticLifecycleClose;
    private volatile String lifecycleCloseReason = "";
    private volatile boolean lifecycleLogWritten;
    private final java.util.concurrent.atomic.AtomicBoolean sessionStopped = new java.util.concurrent.atomic.AtomicBoolean(false);
    PreloaderDialog preloaderDialog = null;
    private Runnable configChangedCallback = null;
    private boolean isPaused = false;
    private boolean isRelativeMouseMovement;
    private InlineTaskManagerPanel inlineTaskManagerPanel;
    private final Handler sidebarHandler = new Handler(Looper.getMainLooper());
    private final Callback<String> bcnTelemetryCallback = this::handleBcnTelemetryLine;
    private final Runnable bcnLayerMapProbe = new Runnable() {
        @Override public void run() {
            if (!bcnTelemetryRequested || isFinishing() || isDestroyed()) return;
            if (isBcnLayerMapped()) updateBcnTelemetryState("LOADED");
            if (!"ACTIVE".equals(bcnTelemetryState) && !bcnTelemetryState.startsWith("ERROR"))
                sidebarHandler.postDelayed(this, 1000L);
        }
    };
    private final Runnable shortcutExitProbe = new Runnable() {
        @Override public void run() {
            if (sessionStopped.get() || isFinishing() || isDestroyed()) return;
            if (!launchedAsShortcut && shortcut == null) return;
            boolean hasApplication = false;
            boolean startSeen = false;
            for (String process : ProcessHelper.listRunningWineProcessNames()) {
                String name = process == null ? "" : process.toLowerCase(Locale.US).trim();
                if (name.isEmpty()) continue;
                if (name.equals("start.exe")) startSeen = true;
                if (!isBaseWineProcess(name)) hasApplication = true;
            }
            if (startSeen) sawStartExe = true;
            long now = android.os.SystemClock.elapsedRealtime();
            if (hasApplication) {
                sawShortcutProcess = true;
                observedShortcutApplication = true;
                shortcutIdleSinceMs = 0L;
            }
            else if (sawShortcutProcess) {
                if (shortcutIdleSinceMs == 0L) shortcutIdleSinceMs = now;
                else if (now - shortcutIdleSinceMs >= SHORTCUT_IDLE_CLOSE_DELAY_MS) {
                    automaticLifecycleClose = true;
                    lifecycleCloseReason = observedShortcutApplication
                            ? "The game exited or crashed; only Wine base/crash-defender processes remained for 5 seconds."
                            : "The game did not start; only Wine base/crash-defender processes remained for 5 seconds.";
                    Log.i("WineLifecycle", lifecycleCloseReason);
                    finishSession();
                    return;
                }
            }
            else if (launchedAsShortcut && sawStartExe
                    && now - shortcutLaunchMs >= SHORTCUT_NEVER_STARTED_CLOSE_DELAY_MS) {
                automaticLifecycleClose = true;
                lifecycleCloseReason = "The game did not start; launcher stub finished without an application process within 2 minutes.";
                Log.i("WineLifecycle", lifecycleCloseReason);
                finishSession();
                return;
            }
            sidebarHandler.postDelayed(this, 500L);
        }
    };
    private int aggressiveWineTrimAttempts;
    private final Runnable aggressiveWineTrimProbe = new Runnable() {
        @Override public void run() {
            if (!String.valueOf(Container.STARTUP_SELECTION_AGGRESSIVE).equals(startupSelection)
                    || sessionStopped.get() || isFinishing() || isDestroyed()) return;
            int terminated = ProcessHelper.terminateWineProcessesByName("tabtip.exe");
            if (terminated > 0) {
                Log.i("WineStartup", "Aggressive policy removed " + terminated
                        + " idle tabtip.exe process(es)");
            }
            // Some Proton builds register tablet input after explorer starts.
            // Probe only during startup so a later user-launched process is not
            // policed continuously throughout the game session.
            if (++aggressiveWineTrimAttempts < 4)
                sidebarHandler.postDelayed(this, 2500L);
        }
    };
    private TextView sidebarTimeView;
    private TextView sidebarBatteryView;
    private TextView sidebarControllerProfileView;
    private TextView sidebarFpsLimitView;
    private TextView sidebarFrameGenerationView;
    private View sidebarFrameGenerationButton;
    private ImageButton sidebarPauseButton;
    private final Runnable sidebarStatusRunnable = new Runnable() {
        @Override public void run() {
            if (sidebarTimeView != null)
                sidebarTimeView.setText(new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date()));
            if (sidebarBatteryView != null) {
                Intent battery = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
                if (battery != null) {
                    int level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                    int scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
                    int percent = level >= 0 && scale > 0 ? Math.round(level * 100f / scale) : -1;
                    sidebarBatteryView.setText(percent >= 0 ? percent + "%" : "--%");
                }
            }
            sidebarHandler.postDelayed(this, 30_000);
        }
    };

    // Inside the XServerDisplayActivity class
    private SensorManager sensorManager;
    private Sensor gyroSensor;
    private ExternalController controller;

    // Playtime stats tracking
    private long startTime;
    private SharedPreferences playtimePrefs;
    private String shortcutName;
    private Handler handler;
    private Runnable savePlaytimeRunnable;
    private static final long SAVE_INTERVAL_MS = 30000;
    private boolean launchBlockedByContentOperation;

    private Handler  timeoutHandler = new Handler(Looper.getMainLooper());
    private Runnable hideControlsRunnable;

    private boolean isDarkMode;

    private String screenEffectProfile;

    private GlibcProgramLauncherComponent glibcLauncher; // Reference to GlibcProgramLauncherComponent
    private BionicProgramLauncherComponent bionicLauncher; // Reference to BionicProgramLauncherComponent
    private FileObserver restartTriggerObserver;

    private Win32AppWorkarounds win32AppWorkarounds;
    private EnvVars overrideEnvVars;

    private WinetricksFloatingView winetricksFloatingView;

    private boolean capturePointerOnNextFocus = false;

    private boolean capturePointerOnDrawerClose = false;

    boolean isMouseDisabled;

    private AudioDeviceCallback audioDeviceCallback;
    private AudioManager audioManager;
    /** BT/USB/headset connects fire bursts of add/remove events; collapse
     * them into a single PulseAudio restart. */
    private static final long PULSE_RESTART_DEBOUNCE_MS = 2000L;
    private final Runnable pulseAudioRestartRunnable = () -> {
        if (environment == null || isFinishing() || isDestroyed()) return;
        PulseAudioComponent pulse = environment.getComponent(PulseAudioComponent.class);
        if (pulse == null) return;
        Log.i("AudioDeviceCallback", "Recreating PulseAudio sink on new route.");
        new Thread(pulse::restart, "PulseAudioRestart").start();
    };

    private static final String APP_DATA_DIR = "/data/data/" + BuildConfig.APPLICATION_ID;
    private static final String[] MEDIACONV_ENV_VARS = {
            "MEDIACONV_AUDIO_DUMP_FILE=" + APP_DATA_DIR + "/files/imagefs/home/xuser/audio.dmp",
            "MEDIACONV_VIDEO_DUMP_FILE=" + APP_DATA_DIR + "/files/imagefs/home/xuser/video.dmp",
            "MEDIACONV_VIDEO_TRANSCODED_FILE=" + APP_DATA_DIR + "/files/imagefs/home/xuser/transcoded.mkv",
            "MEDIACONV_AUDIO_TRANSCODED_FILE=" + APP_DATA_DIR + "/files/imagefs/home/xuser/transcoded.wav",
            "MEDIACONV_BLANK_AUDIO_FILE=" + APP_DATA_DIR + "/files/imagefs/home/xuser/blank.wav",
            "MEDIACONV_BLANK_VIDEO_FILE=" + APP_DATA_DIR + "/files/imagefs/home/xuser/blank.mkv",
    };


    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (configChangedCallback != null) {
            configChangedCallback.run();
            configChangedCallback = null;
        }
    }


    private final SensorEventListener gyroListener = new SensorEventListener() {
        @Override
        public void onSensorChanged(SensorEvent event) {
            if (event.sensor.getType() == Sensor.TYPE_GYROSCOPE) {
                float gyroX = event.values[0]; // Rotation around the X-axis
                float gyroY = event.values[1]; // Rotation around the Y-axis

                winHandler.updateGyroData(gyroX, gyroY); // Send gyro data to WinHandler
            }
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {
            // No action needed
        }
    };


    @Override
    public void onCreate(Bundle savedInstanceState) {
        isDarkMode = AppUtils.isDarkMode(this);
        setTheme(isDarkMode ? R.style.AppThemeFullscreen_Dark : R.style.AppThemeFullscreen);
        super.onCreate(savedInstanceState);
        if (ContentOperationRegistry.hasActiveOperations()) {
            launchBlockedByContentOperation = true;
            new androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle("Content operation in progress")
                    .setMessage("Wait for downloads and installations to finish before entering Wine. Active: "
                            + ContentOperationRegistry.describe())
                    .setCancelable(false)
                    .setPositiveButton(android.R.string.ok, (dialog, which) -> finish())
                    .show();
            return;
        }
        // Wine sessions stay landscape but follow either landscape sensor
        // side. This lets the user put the phone's volume buttons above or
        // below without ever rotating the guest desktop into portrait.
        setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        AppUtils.hideSystemUI(this);
        AppUtils.keepScreenOn(this);
        setContentView(R.layout.xserver_display_activity);

        ControllerManager.getInstance().init(this);

        setupAudioDeviceListener();



        preloaderDialog = new PreloaderDialog(this);
        preferences = PreferenceManager.getDefaultSharedPreferences(this);

        boolean isOpenWithAndroidBrowser = preferences.getBoolean("open_with_android_browser", false);
        boolean isShareAndroidClipboard = preferences.getBoolean("share_android_clipboard", false);

        // Initialize the WinHandler after context is set up
        winHandler = new WinHandler(this);
//        winHandler.initializeController();
        controller = winHandler.getCurrentController();

        if (isOpenWithAndroidBrowser || isShareAndroidClipboard)
            wineRequestHandler = new WineRequestHandler(this);

        if (controller != null) {
            int triggerType = preferences.getInt("trigger_type", ExternalController.TRIGGER_IS_AXIS); // Default to TRIGGER_IS_AXIS
            controller.setTriggerType((byte) triggerType); // Cast to byte if needed
        }



        // Check if xinputDisabled extra is passed
        boolean xinputDisabledFromShortcut = false;




        // Initialize SensorManager
        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        gyroSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);

        boolean gyroEnabled = preferences.getBoolean("gyro_enabled", true);

        if (gyroEnabled) {
            // Register the sensor event listener
            sensorManager.registerListener(gyroListener, gyroSensor, SensorManager.SENSOR_DELAY_GAME);
        }



        // Record the start time
        startTime = System.currentTimeMillis();

        // Initialize handler for periodic saving
        handler = new Handler(Looper.getMainLooper());
        savePlaytimeRunnable = new Runnable() {
            @Override
            public void run() {
                savePlaytimeData();
                handler.postDelayed(this, SAVE_INTERVAL_MS);
            }
        };
        handler.postDelayed(savePlaytimeRunnable, SAVE_INTERVAL_MS);


        // Handler and Runnable to manage timeout for hiding controls

        boolean isTimeoutEnabled = preferences.getBoolean("touchscreen_timeout_enabled", true);

        hideControlsRunnable = () -> {
            if (isTimeoutEnabled) {
                inputControlsView.setVisibility(View.GONE);
                Log.d("XServerDisplayActivity", "Touchscreen controls hidden after timeout.");
            }
        };


        contentsManager = new ContentsManager(this);
        contentsManager.syncContents();

        drawerLayout = findViewById(R.id.DrawerLayout);

        drawerLayout.addDrawerListener(new DrawerLayout.SimpleDrawerListener() {
            @Override public void onDrawerOpened(@NonNull View drawerView) {
                if (xServerView != null) {
                    updateSidebarFrameGenerationState(xServerView.getRenderer());
                }
            }
            @Override public void onDrawerClosed(@NonNull View drawerView) {
                // If the user left Relative Mouse enabled, recapture.
                if (isRelativeMouseMovement && !pointerCaptureRequested) {
                    drawerLayout.postDelayed(() -> ensurePointerCapture("drawer-closed"), 2000);
                }
            }
        });


        drawerLayout.setOnApplyWindowInsetsListener((view, windowInsets) -> windowInsets.replaceSystemWindowInsets(0, 0, 0, 0));
        drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED);


        navigationView = findViewById(R.id.NavigationView);

        if (isDarkMode) {
            navigationView.setItemTextColor(ContextCompat.getColorStateList(this, R.color.white));
            navigationView.setBackgroundResource(R.color.content_dialog_background_dark);
        }

        enableLogs = preferences.getBoolean("enable_wine_debug", false)
                || preferences.getBoolean("enable_box86_64_logs", false);
        Menu menu = navigationView.getMenu();
        markNestedMenuItems(menu, INPUT_IDS);
        markNestedMenuItems(menu, DISPLAY_IDS);
        menu.findItem(R.id.main_menu_logs).setVisible(enableLogs);
        menu.findItem(R.id.main_menu_logs).setEnabled(enableLogs);
        allowMagnifier = !XrActivity.isEnabled(this);




        navigationView.setNavigationItemSelectedListener(this);
        navigationView.setPointerIcon(PointerIcon.getSystemIcon(this, PointerIcon.TYPE_ARROW));
        navigationView.setOnFocusChangeListener((v, hasFocus) -> navigationFocused = hasFocus);


        // restore persisted states (default collapsed = false)
        expInput   = preferences.getBoolean(PREF_EXP_INPUT,   false);
        expDisplay = preferences.getBoolean(PREF_EXP_DISPLAY, false);

        applyGroup(menu, R.id.group_input,   R.id.header_input,   expInput);
        applyGroup(menu, R.id.group_display, R.id.header_display, expDisplay);

        // The redesigned full-height header owns the in-session navigation.
        // Keep the old menu objects as action dispatchers, but do not render them.
        for (int i = 0; i < menu.size(); i++) menu.getItem(i).setVisible(false);

        // tune RV
        RecyclerView rv = navRecycler();
        if (rv != null) {
            rv.setItemAnimator(null);               // no default blink
            rv.setHasFixedSize(true);
            rv.setOverScrollMode(View.OVER_SCROLL_NEVER);
            Drawable bg = navigationView.getBackground();
            if (bg != null) rv.setBackground(bg);
        }

        imageFs = ImageFs.find(this);
        File fakeInputDir = new File(imageFs.getRootDir(), "dev/input");
        if (!fakeInputDir.isDirectory()) fakeInputDir.mkdirs();
        winHandler.setFakeInputPath(fakeInputDir.getAbsolutePath());

        String screenSize = Container.DEFAULT_SCREEN_SIZE;
//        if (!isGenerateWineprefix()) {
        containerManager = new ContainerManager(this);
        container = containerManager.getContainerById(getIntent().getIntExtra("container_id", 0));
//            containerManager.activateContainer(container);

        // Log shortcut_path
        String shortcutPath = getIntent().getStringExtra("shortcut_path");
        Log.d("XServerDisplayActivity", "Shortcut Path: " + shortcutPath);


        // Determine container ID
        int containerId = getIntent().getIntExtra("container_id", 0);
        Log.d("XServerDisplayActivity", "Container ID from Intent: " + containerId);
        if (containerId == 0) {
            Log.d("XServerDisplayActivity", "Container ID is 0, attempting to parse from .desktop file");
            // Proceed with .desktop file parsing
        }


        // If container_id is 0, read from the .desktop file
        if (containerId == 0 && shortcutPath != null && !shortcutPath.isEmpty()) {
            File shortcutFile = new File(shortcutPath);
            containerId = parseContainerIdFromDesktopFile(shortcutFile);
            Log.d("XServerDisplayActivity", "Parsed Container ID from .desktop file: " + containerId);
        }


        // Initialize playtime tracking
        playtimePrefs = getSharedPreferences("playtime_stats", MODE_PRIVATE);
        shortcutName = getIntent().getStringExtra("shortcut_name");

        // Ensure shortcutPath is not null before proceeding
        if (shortcutPath != null && !shortcutPath.isEmpty()) {
            if (shortcutName == null || shortcutName.isEmpty()) {
                shortcutName = parseShortcutNameFromDesktopFile(new File(shortcutPath));
                Log.d("XServerDisplayActivity", "Parsed Shortcut Name from .desktop file: " + shortcutName);
            }
        } else {
            Log.d("XServerDisplayActivity", "No shortcut path provided, skipping shortcut parsing.");
        }


        // Increment play count at the start of a session
        incrementPlayCount();

        // Log the final container_id
        Log.d("XServerDisplayActivity", "Final Container ID: " + containerId);

        // Retrieve the container and check if it's null
        container = containerManager.getContainerById(containerId);

        if (container == null) {
            Log.e("XServerDisplayActivity", "Failed to retrieve container with ID: " + containerId);
            finish();  // Gracefully exit the activity to avoid crashing
            return;
        }

        containerManager.activateContainer(container);

        if (shortcutPath != null && !shortcutPath.isEmpty()) {
            shortcut = new Shortcut(container, new File(shortcutPath));
        }

        firstTimeBoot = container.getExtra("appVersion").isEmpty();

        String wineVersion = container.getWineVersion();
        ContentProfile installedRuntime = WineInfo.findInstalledRuntimeProfile(
                contentsManager, wineVersion);
        if (installedRuntime != null) {
            String normalizedWineVersion = ContentsManager.getEntryName(installedRuntime);
            if (!normalizedWineVersion.equals(wineVersion)) {
                Log.i("WineStartup", "Migrating runtime identifier " + wineVersion
                        + " to " + normalizedWineVersion);
                container.setWineVersion(normalizedWineVersion);
                container.saveData();
                wineVersion = normalizedWineVersion;
            }
        }
        wineInfo = WineInfo.fromIdentifier(this, contentsManager, wineVersion);

        imageFs.setWinePath(wineInfo.path);

        // Self-heal the WINEPREFIX registries: wineserver saves them on
        // exit and a hard kill (OOM/task removal) can truncate the files, which
        // makes every later launch fail with "not a valid registry file" and a
        // 32/64-bit wineserver mismatch. Use the container's actual arch so
        // win32 (x86) prefixes get #arch=win32.
        ContainerManager.ensureValidPrefixRegistries(new File(imageFs.getRootDir(), ImageFs.WINEPREFIX), wineInfo.isWin64());

        ProcessHelper.removeAllDebugCallbacks();
        ProcessHelper.addDebugCallback(bcnTelemetryCallback);
        if (enableLogs) {
            LogView.setFilename(getExecutable());
            ProcessHelper.addDebugCallback(debugDialog = new DebugDialog(this));
        }

        // Retrieve secondary executable and delay
        String secondaryExec = shortcut != null ? shortcut.getExtra("secondaryExec") : null;
        int execDelay = 0;
        if (shortcut != null) {
            try {
                execDelay = Integer.parseInt(shortcut.getExtra("execDelay", "0"));
            }
            catch (NumberFormatException e) {
                Log.e("XServerDisplayActivity", "Invalid execDelay extra", e);
            }
        }

        // Debug logging for secondaryExec and execDelay
        Log.d("XServerDisplayActivity", "Secondary Exec: " + secondaryExec);
        Log.d("XServerDisplayActivity", "Execution Delay: " + execDelay);

        // If a secondary executable is specified, schedule it
        if (secondaryExec != null && !secondaryExec.isEmpty() && execDelay > 0) {
            scheduleSecondaryExecution(secondaryExec, execDelay);
            Log.d("XServerDisplayActivity", "Scheduling secondary execution: " + secondaryExec + " with delay: " + execDelay);
        } else {
            Log.d("XServerDisplayActivity", "No valid secondary executable or delay is zero, skipping scheduling.");
        }

        graphicsDriver = container.getGraphicsDriver();
        String graphicsDriverConfig = container.getGraphicsDriverConfig();
        audioDriver = container.getAudioDriver();
        try {
            audioVolume = Integer.parseInt(container.getExtra("audioVolume", "100"));
        }
        catch (NumberFormatException ignored) {
            audioVolume = 100;
        }
        audioVolume = Math.max(0, Math.min(100, audioVolume));
        emulator = container.getEmulator();
        midiSoundFont = container.getMIDISoundFont();
        dxwrapper = container.getDXWrapper();
        ddrawrapper = container.getDDrawWrapper();
        String dxwrapperConfig = container.getDXWrapperConfig();
        screenSize = container.getScreenSize();
        winHandler.setInputType((byte) container.getInputType());
        lc_all = container.getLC_ALL();
//      isRelativeMouseMovement = container.isRelativeMouseMovement();

        // Log the entire intent to verify the extras
        Intent intent = getIntent();
        Log.d("XServerDisplayActivity", "Intent Extras: " + intent.getExtras());

        if (shortcut != null) {
            graphicsDriver = shortcut.getExtra("graphicsDriver", container.getGraphicsDriver());
            graphicsDriverConfig = shortcut.getExtra("graphicsDriverConfig", container.getGraphicsDriverConfig());
            audioDriver = shortcut.getExtra("audioDriver", container.getAudioDriver());
            midiSoundFont = shortcut.getExtra("midiSoundFont", container.getMIDISoundFont());
            try {
                audioVolume = Integer.parseInt(shortcut.getExtra(
                        "audioVolume", String.valueOf(audioVolume)));
            }
            catch (NumberFormatException ignored) {
            }
            audioVolume = Math.max(0, Math.min(100, audioVolume));
            emulator = shortcut.getExtra("emulator", container.getEmulator());
            dxwrapper = shortcut.getExtra("dxwrapper", container.getDXWrapper());
            ddrawrapper = shortcut.getExtra("ddrawrapper", container.getDDrawWrapper());
            dxwrapperConfig = shortcut.getExtra("dxwrapperConfig", container.getDXWrapperConfig());
            screenSize = shortcut.getExtra("screenSize", container.getScreenSize());
            lc_all = shortcut.getExtra("lc_all", container.getLC_ALL());
            String inputType = shortcut.getExtra("inputType");
            if (!inputType.isEmpty()) {
                try {
                    winHandler.setInputType(Byte.parseByte(inputType));
                }
                catch (NumberFormatException e) {
                    Log.e("XServerDisplayActivity", "Invalid inputType extra: " + inputType, e);
                }
            }
            String xinputDisabledString = shortcut.getExtra("disableXinput", "false");
//                isRelativeMouseMovement = shortcut.getExtra("relativeMouseMovement", container.isRelativeMouseMovement() ? "1" : "0").equals("1") ? true : false;
            xinputDisabledFromShortcut = parseBoolean(xinputDisabledString);
            // Pass the value to WinHandler
            winHandler.setXInputDisabled(xinputDisabledFromShortcut);
            Log.d("XServerDisplayActivity", "XInput Disabled from Shortcut: " + xinputDisabledFromShortcut);
        }
        // vkBasalt via Video tab (Container + Shortcut) - moved from shortcut-only
        {
            String sharpnessEffect = shortcut != null
                    ? shortcut.getExtra("sharpnessEffect", container.getExtra("sharpnessEffect", "None"))
                    : container.getExtra("sharpnessEffect", "None");
            if (!sharpnessEffect.equals("None")) {
                double sharpnessLevel;
                double sharpnessDenoise;
                try {
                    String v = shortcut != null
                            ? shortcut.getExtra("sharpnessLevel", container.getExtra("sharpnessLevel", "100"))
                            : container.getExtra("sharpnessLevel", "100");
                    sharpnessLevel = Double.parseDouble(v);
                }
                catch (NumberFormatException e) {
                    sharpnessLevel = 100.0;
                }
                try {
                    String v = shortcut != null
                            ? shortcut.getExtra("sharpnessDenoise", container.getExtra("sharpnessDenoise", "100"))
                            : container.getExtra("sharpnessDenoise", "100");
                    sharpnessDenoise = Double.parseDouble(v);
                }
                catch (NumberFormatException e) {
                    sharpnessDenoise = 100.0;
                }
                vkbasaltConfig = "effects=" + sharpnessEffect.toLowerCase() + ";" + "casSharpness=" + sharpnessLevel / 100 + ";" + "dlsSharpness=" + sharpnessLevel / 100  + ";" + "dlsDenoise=" + sharpnessDenoise / 100 + ";" + "enableOnLaunch=True";
            }
        }

        boolean dinputEnabled = (winHandler.getInputType() & WinHandler.FLAG_INPUT_TYPE_DINPUT) != 0;
        boolean exclusiveXInput = shortcut != null
                ? "1".equals(shortcut.getExtra("exclusiveXInput", container.isExclusiveXInput() ? "1" : "0"))
                : container.isExclusiveXInput();
        WineUtils.setJoystickRegistryKeys(container, dinputEnabled, exclusiveXInput);

        graphicsDriver = Container.normalizeGraphicsDriver(graphicsDriver);
        wineRenderer = resolveWineRenderer();

        this.graphicsDriverConfig = GraphicsDriverConfigDialog.parseGraphicsDriverConfig(graphicsDriverConfig);
        applyPreferredRefreshRate();
        String configuredDDrawWrapper = DXVKConfigDialog.parseConfig(dxwrapperConfig).get("ddrawrapper");
        if (!configuredDDrawWrapper.isEmpty()) this.ddrawrapper = configuredDDrawWrapper;

        if (dxwrapper.equals("dxvk") || dxwrapper.equals("vkd3d")) {
            this.dxwrapperConfig = DXVKConfigDialog.parseConfig(dxwrapperConfig);
            String selectedDxvk = this.dxwrapperConfig.get("version", DefaultVersion.DXVK);
            if (!wineInfo.isArm64EC()
                    && selectedDxvk.toLowerCase(Locale.ENGLISH).contains("arm64ec")) {
                // ARM64EC DLLs cannot be loaded by conventional x86/x86_64 Wine.
                // Older containers inherited the ARM64EC default even after the
                // runtime was changed; use the equivalent x86 async package.
                Log.w("DXWrapperExtraction", "Replacing incompatible DXVK "
                        + selectedDxvk + " with " + DefaultVersion.DXVK_X86
                        + " for " + wineInfo.getArch());
                this.dxwrapperConfig.put("version", DefaultVersion.DXVK_X86);
            }
            // Older containers used a separate VKD3D wrapper. Migrate them to
            // the combined DXVK + VKD3D pipeline without changing their JSON.
            if (dxwrapper.equals("vkd3d")) this.dxwrapper = "dxvk";
        }



        if (!wineInfo.isWin64()) {
            onExtractFileListener = (file, size) -> {
                String path = file.getPath();
                if (path.contains("system32/")) return null;
                return new File(path.replace("syswow64/", "system32/"));
            };
        }


        preloaderDialog.show(R.string.starting_up);

        // Real FSR render scaling: with an upscale preset active, the guest
        // renders at display/factor (the X screen itself is reduced), so the
        // game shades fewer pixels and FPS actually improves. EASU then
        // upscales the true low-res output to the physical panel. Without
        // this, presets only shrank the final composite - lower resolution
        // on screen with no performance gain.
        sessionStartFsrMode = resolveFsrState();
        // Store original display size for live FSR resize (RE2 / GPU-bound case).
        try {
            ScreenInfo orig = new ScreenInfo(screenSize);
            originalScreenWidth = orig.width;
            originalScreenHeight = orig.height;
        } catch (Exception ignored) {}
        if (!"off".equals(sessionStartFsrMode) && !"on".equals(sessionStartFsrMode)) {
            float factor = GraphicsDriverConfigDialog.fsrFactorForMode(sessionStartFsrMode);
            ScreenInfo configured = new ScreenInfo(screenSize);
            int guestW = Math.max(64, (Math.round(configured.width / factor)) & ~1);
            int guestH = Math.max(64, (Math.round(configured.height / factor)) & ~1);
            if (guestW < configured.width && guestH < configured.height) {
                fsrGuestWidth = guestW;
                fsrGuestHeight = guestH;
                // Keep original for live switching back to native or to another preset.
                if (originalScreenWidth == 0) {
                    originalScreenWidth = configured.width;
                    originalScreenHeight = configured.height;
                }
                screenSize = guestW + "x" + guestH;
                Log.i("FSRDebug", "FSR guest render scale: mode=" + sessionStartFsrMode
                        + " factor=" + factor + " guest=" + guestW + "x" + guestH
                        + " (configured " + configured.width + "x" + configured.height + ")");
            }
        } else {
            // No FSR upscale at start: original == guest (native).
            if (originalScreenWidth == 0) {
                try {
                    ScreenInfo c = new ScreenInfo(screenSize);
                    originalScreenWidth = c.width;
                    originalScreenHeight = c.height;
                } catch (Exception ignored) {}
            }
        }

        inputControlsManager = new InputControlsManager(this);
        xServer = new XServer(new ScreenInfo(screenSize));
        xServer.setWinHandler(winHandler);

        boolean[] winStarted = {false};

        // Add the OnWindowModificationListener for dynamic workarounds
        xServer.windowManager.addOnWindowModificationListener(new WindowManager.OnWindowModificationListener() {
            @Override
            public void onUpdateWindowContent(Window window) {
                if (!winStarted[0] && window.isApplicationWindow()) {
                    xServerView.getRenderer().setCursorVisible(true);
                    preloaderDialog.closeOnUiThread();
                    winStarted[0] = true;
                }
                if (frameRatingWindowId == window.id) frameRating.update();
            }

            private void setProcessAffinity(Window window, int processAffinity) {

                int processId = window.getProcessId();

                if (processId > 0) {
                    winHandler.setProcessAffinity(processId, processAffinity);
                } else if (!window.getClassName().isEmpty()) {
                    winHandler.setProcessAffinity(window.getClassName(), processAffinity);
                }
            }

            @Override
            public void onMapWindow(Window window) {
                if (!window.isApplicationWindow()) return;

                String cpuList64 = container.getCPUList(true);
                String cpuList32 = container.getCPUListWoW64(true);

                // A shortcut may override either architecture independently.
                if (shortcut != null) {
                    cpuList64 = shortcut.getExtra("cpuList", cpuList64);
                    cpuList32 = shortcut.getExtra("cpuListWoW64", cpuList32);
                }

                int affinityMask = ProcessHelper.getAffinityMask(
                        window.isWoW64() ? cpuList32 : cpuList64
                );
                if (affinityMask > 0) setProcessAffinity(window, affinityMask);
            }

            @Override
            public void onModifyWindowProperty(Window window, Property property) {
                changeFrameRatingVisibility(window, property);
            }

            @Override
            public void onUnmapWindow(Window window) {
                changeFrameRatingVisibility(window, null);
            }
        });

        if (!midiSoundFont.equals("")) {
            InputStream in = null;
            InputStream finalIn = in;
            MidiManager.OnMidiLoadedCallback callback = new MidiManager.OnMidiLoadedCallback() {
                @Override
                public void onSuccess(SF2Soundbank soundbank) {
                    midiHandler = new MidiHandler();
                    midiHandler.setSoundBank(soundbank);
                    midiHandler.start();
                }

                @Override
                public void onFailed(Exception e) {
                    try {
                        finalIn.close();
                    } catch (Exception e2) {}
                }
            };
            try {
                if (midiSoundFont.equals(MidiManager.DEFAULT_SF2_FILE)) {
                    in = getAssets().open(MidiManager.SF2_ASSETS_DIR + "/" + midiSoundFont);
                    MidiManager.load(in, callback);
                } else
                    MidiManager.load(new File(MidiManager.getSoundFontDir(this), midiSoundFont), callback);
            } catch (Exception e) {}
        }

        // Check if a profile is defined by the shortcut
        String controlsProfile = shortcut != null ? shortcut.getExtra("controlsProfile", "") : "";

        Runnable runnable = () -> {
            setupUI();
            if (controlsProfile.isEmpty()) {
                // No profile defined, run the simulated dialog confirmation for input controls
                simulateConfirmInputControlsDialog();
            }
            Executors.newSingleThreadExecutor().execute(() -> {

                setupWineSystemFiles();
                extractArm64ecInputDLLs(); // REQUIRED: Uses updated xinput1_3 main.c from x86_64 build, prevents crashes with 3+ players, avoids need for input shim dlls.
                extractGraphicsDriverFiles();
                changeWineAudioDriver();

                try {
                    setupXEnvironment();
                } catch (PackageManager.NameNotFoundException e) {
                    throw new RuntimeException(e);
                }

            });
        };

        runnable.run();
    }

    // Method to parse container_id from .desktop file
    private int parseContainerIdFromDesktopFile(File desktopFile) {
        int containerId = 0;
        if (desktopFile.exists()) {
            try (BufferedReader reader = new BufferedReader(new FileReader(desktopFile))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("container_id=")) {
                        containerId = Integer.parseInt(line.split("=")[1].trim());
                        break;
                    }
                }
            } catch (IOException | NumberFormatException e) {
                Log.e("XServerDisplayActivity", "Error parsing container_id from .desktop file", e);
            }
        }
        return containerId;
    }

    private boolean parseBoolean(String value) {
        // Return true for "true", "1", "yes" (case-insensitive)
        if ("true".equalsIgnoreCase(value) || "1".equals(value) || "yes".equalsIgnoreCase(value)) {
            return true;
        }
        // Return false for any other value, including "false", "0", "no"
        return false;
    }





    // Inside XServerDisplayActivity class
    private void handleCapturedPointer(MotionEvent event) {
        boolean handled = false;

        // Special: mouse 'Back' -> release capture and open drawer
        if (event.getAction() == MotionEvent.ACTION_BUTTON_PRESS
                && event.getActionButton() == MotionEvent.BUTTON_BACK) {
            if (pointerCaptureRequested && touchpadView != null) {
                touchpadView.releasePointerCapture();
                pointerCaptureRequested = false;
//                Toast.makeText(this, "Capture released", Toast.LENGTH_SHORT).show();
            }
            onBackPressed();
            return;
        }

        // Captured primary clicks do not always arrive as BUTTON_PRESS on
        // Samsung/Android. Route both BUTTON_* and DOWN/UP forms through the
        // same state-guarded injector used by the non-captured path.
        if (touchpadView != null && touchpadView.onExternalPrimaryButtonEvent(event)) return;

        int actionButton = event.getActionButton();
        switch (event.getAction()) {
            case MotionEvent.ACTION_BUTTON_PRESS:
                if (actionButton == MotionEvent.BUTTON_SECONDARY) {
                    if (xServer.isRelativeMouseMovement())
                        xServer.getWinHandler().mouseEvent(MouseEventFlags.RIGHTDOWN, 0, 0, 0);
                    else
                        xServer.injectPointerButtonPress(Pointer.Button.BUTTON_RIGHT);
                } else if (actionButton == MotionEvent.BUTTON_TERTIARY) {
                    if (xServer.isRelativeMouseMovement())
                        xServer.getWinHandler().mouseEvent(MouseEventFlags.MIDDLEDOWN, 0, 0, 0);
                    else
                        xServer.injectPointerButtonPress(Pointer.Button.BUTTON_MIDDLE); // Add this line for middle mouse button press
                }
                handled = true;
                break;
            case MotionEvent.ACTION_BUTTON_RELEASE:
                if (actionButton == MotionEvent.BUTTON_SECONDARY) {
                    if (xServer.isRelativeMouseMovement())
                        xServer.getWinHandler().mouseEvent(MouseEventFlags.RIGHTUP, 0, 0, 0);
                    else
                        xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_RIGHT);
                } else if (actionButton == MotionEvent.BUTTON_TERTIARY) {
                    if (xServer.isRelativeMouseMovement())
                        xServer.getWinHandler().mouseEvent(MouseEventFlags.MIDDLEUP, 0, 0, 0);
                    else
                        xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_MIDDLE); // Add this line for middle mouse button release
                }
                handled = true;
                break;
            case MotionEvent.ACTION_MOVE:
            case MotionEvent.ACTION_HOVER_MOVE:
                float[] transformedPoint = XForm.transformPoint(xform, event.getX(), event.getY());
                if (xServer.isRelativeMouseMovement())
                    xServer.getWinHandler().mouseEvent(MouseEventFlags.MOVE, (int)transformedPoint[0], (int)transformedPoint[1], 0);
                else
                    xServer.injectPointerMoveDelta((int)transformedPoint[0], (int)transformedPoint[1]);
                handled = true;
                break;
            case MotionEvent.ACTION_SCROLL:
                float scrollY = event.getAxisValue(MotionEvent.AXIS_VSCROLL);
                if (scrollY <= -1.0f) {
                    if (xServer.isRelativeMouseMovement())
                        xServer.getWinHandler().mouseEvent(MouseEventFlags.WHEEL, 0, 0, (int)scrollY * 270);
                    else {
                        xServer.injectPointerButtonPress(Pointer.Button.BUTTON_SCROLL_DOWN);
                        xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_SCROLL_DOWN);
                    }
                } else if (scrollY >= 1.0f) {
                    if (xServer.isRelativeMouseMovement())
                        xServer.getWinHandler().mouseEvent(MouseEventFlags.WHEEL, 0, 0,(int)scrollY * 270);
                    else {
                        xServer.injectPointerButtonPress(Pointer.Button.BUTTON_SCROLL_UP);
                        xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_SCROLL_UP);
                    }
                }
                handled = true;
                break;
        }
    }

    private void ensurePointerCapture(String reason) {
        if (!isRelativeMouseMovement || touchpadView == null) return;

        final int[] tries = {0};
        Runnable attempt = new Runnable() {
            @Override public void run() {
                if (!hasWindowFocus()) { touchpadView.postDelayed(this, 50); return; }
                if (!touchpadView.isAttachedToWindow()) { touchpadView.postDelayed(this, 50); return; }

                // Make sure the view can take focus
                touchpadView.setFocusableInTouchMode(true);
                touchpadView.requestFocus();

                touchpadView.requestPointerCapture();
                touchpadView.setOnCapturedPointerListener((v, e) -> { handleCapturedPointer(e); return true; });
                pointerCaptureRequested = true;
            }
        };
        // Try quickly a few times to dodge transient focus transitions
        touchpadView.postDelayed(attempt, 50); // First attempt
    }


    public boolean onCapturedPointerEvent(MotionEvent event) {
        handleCapturedPointer(event);
        return true;
    }




    //    private void setCustomCursor() {
//        View decorView = getWindow().getDecorView();
//        Bitmap transparentCursorBitmap = BitmapFactory.decodeResource(getResources(), R.drawable.transparent_cursor);
//        PointerIcon transparentCursorIcon = PointerIcon.create(transparentCursorBitmap, 0, 0);
//        decorView.setPointerIcon(transparentCursorIcon);
//    }
    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == MainActivity.EDIT_INPUT_CONTROLS_REQUEST_CODE && resultCode == Activity.RESULT_OK) {
            if (editInputControlsCallback != null) {
                editInputControlsCallback.run();
                editInputControlsCallback = null;
            }
        }
    }


    @Override
    public void onResume() {
        super.onResume();
        if (launchBlockedByContentOperation) return;
        boolean gyroEnabled = preferences.getBoolean("gyro_enabled", true);

        if (gyroEnabled) {
            // Re-register the sensor listener when the activity is resumed
            sensorManager.registerListener(gyroListener, gyroSensor, SensorManager.SENSOR_DELAY_GAME);
        }

        if (environment != null) {
            xServerView.onResume();
            if (xServerView.getRenderer().getLSFGManager().isActive()) {
                xServerView.getRenderer().startApexChoreographer();
            }
            environment.onResume();
            // Proactive audio stream check
            ALSAServerComponent alsaComponent = environment.getComponent(ALSAServerComponent.class);
            if (alsaComponent != null) {
                Log.d("XServerDisplayActivity", "onResume: Proactively checking audio stream health.");
                alsaComponent.notifyAudioDeviceChanged();
            }
        }
        startTime = System.currentTimeMillis();
        handler.removeCallbacks(savePlaytimeRunnable);
        handler.postDelayed(savePlaytimeRunnable, SAVE_INTERVAL_MS);
        ProcessHelper.resumeAllWineProcesses();
    }

    @Override
    public void onPause() {
        super.onPause();
        if (launchBlockedByContentOperation) return;
        boolean gyroEnabled = preferences.getBoolean("gyro_enabled", true);

        if (gyroEnabled) {
            // Unregister the sensor listener when the activity is paused
            sensorManager.unregisterListener(gyroListener);
        }

        // Check if we are entering Picture-in-Picture mode
        if (!isInPictureInPictureMode()) {
            // Only pause environment and xServerView if not in PiP mode
            if (environment != null) {
                xServerView.getRenderer().stopApexChoreographer();
                environment.onPause();
                xServerView.onPause();
            }
        }

        savePlaytimeData();
        handler.removeCallbacks(savePlaytimeRunnable);
        ProcessHelper.pauseAllWineProcesses();
    }


    private void savePlaytimeData() {
        long endTime = System.currentTimeMillis();
        long playtime = endTime - startTime;

        // Ensure that playtime is not negative
        if (playtime < 0) {
            playtime = 0;
        }

        SharedPreferences.Editor editor = playtimePrefs.edit();
        String playtimeKey = shortcutName + "_playtime";

        // Accumulate the playtime into totalPlaytime
        long totalPlaytime = playtimePrefs.getLong(playtimeKey, 0) + playtime;
        editor.putLong(playtimeKey, totalPlaytime);
        editor.apply();

        // Reset startTime to the current time for the next interval
        startTime = System.currentTimeMillis();
    }


    private void incrementPlayCount() {
        SharedPreferences.Editor editor = playtimePrefs.edit();
        String playCountKey = shortcutName + "_play_count";
        int playCount = playtimePrefs.getInt(playCountKey, 0) + 1;
        editor.putInt(playCountKey, playCount);
        editor.apply();
    }

    @Override
    protected void onDestroy() {
        sidebarHandler.removeCallbacks(sidebarStatusRunnable);
        sidebarHandler.removeCallbacks(bcnLayerMapProbe);
        sidebarHandler.removeCallbacks(shortcutExitProbe);
        sidebarHandler.removeCallbacks(aggressiveWineTrimProbe);
        ProcessHelper.removeDebugCallback(bcnTelemetryCallback);
        if (xServerView != null) xServerView.getRenderer().stopApexChoreographer();
        if (launchBlockedByContentOperation) {
            super.onDestroy();
            return;
        }
        if (inlineTaskManagerPanel != null) inlineTaskManagerPanel.stop();
        if (hudDataSource != null) {
            hudDataSource.stop();
            hudDataSource = null;
        }
        if (preloaderDialog != null) preloaderDialog.close();
        try { RamOptimizerXclipse.shutdownFor(mRamOptSession); } catch (Throwable ignored) {}
        super.onDestroy();
    }

    public void exitApp() {

        preloaderDialog.show(R.string.closing_app);


        new Handler(Looper.getMainLooper()).postDelayed(() -> {


            Executors.newSingleThreadExecutor().execute(() -> {

            stopSessionServices();

            // Return to the application without killing/restarting its process. This
            // also works when the Wine session was launched by an Android shortcut.
            runOnUiThread(() -> {
                preloaderDialog.close();
                Intent intent = new Intent(this, MainActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                startActivity(intent);
                finish();
            });
            });
        }, 1000);
    }

    private void stopSessionServices() {
        if (!sessionStopped.compareAndSet(false, true)) return;

        sidebarHandler.removeCallbacks(aggressiveWineTrimProbe);
        // Snapshot processes and recent output before stopping the Wine
        // environment; after teardown the information is no longer available.
        writeWineLifecycleLogIfNeeded();

        if (audioManager != null && audioDeviceCallback != null) {
            audioManager.unregisterAudioDeviceCallback(audioDeviceCallback);
        }

        savePlaytimeData();
        handler.removeCallbacks(savePlaytimeRunnable);

        if (midiHandler != null) midiHandler.stop();
        if (sensorManager != null) sensorManager.unregisterListener(gyroListener);
        if (environment != null) environment.stopEnvironmentComponents();
        if (winHandler != null) winHandler.stop();
        if (wineRequestHandler != null) wineRequestHandler.stop();
        ProcessHelper.terminateAllWineProcesses();
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (launchBlockedByContentOperation) return;
        savePlaytimeData();
        handler.removeCallbacks(savePlaytimeRunnable);



    }


    private void releasePointerCaptureIfNeeded(String reason) {
        if (pointerCaptureRequested && touchpadView != null) {
            touchpadView.releasePointerCapture();
            touchpadView.setOnCapturedPointerListener(null);
            pointerCaptureRequested = false;
            Log.d("PointerCapture", "Released: " + reason);
        }
    }

    @Override
    public void onBackPressed() {
        if (environment != null) {
            releasePointerCaptureIfNeeded("open-drawer/back");
            if (!drawerLayout.isDrawerOpen(GravityCompat.START)) {
                drawerLayout.openDrawer(GravityCompat.START);
            } else {
                drawerLayout.closeDrawers();
            }
        }
    }

    private void openXServerDrawer() {
        if (environment != null) {
            releasePointerCaptureIfNeeded("open-drawer/shortcut");
            if (!drawerLayout.isDrawerOpen(GravityCompat.START)) {
                drawerLayout.openDrawer(GravityCompat.START);
            } else {
                drawerLayout.closeDrawers();
            }
        }
    }


    // Fields
    private static final String PREF_EXP_INPUT   = "drawer_exp_input";
    private static final String PREF_EXP_DISPLAY = "drawer_exp_display";

    private boolean expInput   = false;
    private boolean expDisplay = false;

    private NavigationView navigationView;

    private LayoutAnimationController navLayoutAnim;

    private boolean enableLogs = false;
    private boolean allowMagnifier = true;

    private static final int ANIM_DURATION = 300; // ms
    private static final float SLIDE_DP = 0f;     // small vertical shift

    private static final float COLLAPSE_TRANSLATION_DP = 6f;

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    @Nullable
    private RecyclerView navRecycler() {
        return findNavRecycler(navigationView);
    }

    private Set<CharSequence> titlesForIds(Menu menu, int[] itemIds) {
        HashSet<CharSequence> set = new HashSet<>();
        for (int id : itemIds) {
            MenuItem mi = menu.findItem(id);
            if (mi != null && mi.getTitle() != null) set.add(mi.getTitle());
        }
        return set;
    }

    @Nullable
    private TextView rowTitle(View row) {
        // Try Material id, then fallback to first TextView
        int textId = getResources().getIdentifier("design_menu_item_text", "id", getPackageName());
        View v = textId != 0 ? row.findViewById(textId) : null;
        if (v instanceof TextView) return (TextView) v;

        if (row instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) row;
            for (int i = 0; i < vg.getChildCount(); i++) {
                View c = vg.getChildAt(i);
                if (c instanceof TextView) return (TextView) c;
            }
        }
        return null;
    }

    private List<View> findVisibleRowsForTitles(Set<CharSequence> wantedTitles) {
        ArrayList<View> rows = new ArrayList<>();
        RecyclerView rv = navRecycler();
        if (rv == null) return rows;

        for (int i = 0; i < rv.getChildCount(); i++) {
            View row = rv.getChildAt(i);
            TextView tv = rowTitle(row);
            if (tv != null && tv.getText() != null && wantedTitles.contains(tv.getText())) {
                rows.add(row);
            }
        }
        return rows;
    }

    private void animateInGroupItems(int[] itemIds) {
        RecyclerView rv = navRecycler();
        if (rv == null) return;

        // Wait one frame so the rows are laid out after setGroupVisible(true)
        rv.post(() -> {
            Menu menu = navigationView.getMenu();
            Set<CharSequence> titles = titlesForIds(menu, itemIds);
            List<View> rows = findVisibleRowsForTitles(titles);

            float startTrans = dp(SLIDE_DP);
            for (View row : rows) {
                row.setAlpha(0f);
                row.setTranslationY(startTrans);
                row.animate()
                        .alpha(1f)
                        .translationY(0f)
                        .setDuration(ANIM_DURATION)
                        .withLayer()
                        .start();
            }
        });
    }

    private void animateOutGroupItems(int[] itemIds, Runnable after) {
        RecyclerView rv = navRecycler();
        if (rv == null) { after.run(); return; }

        Menu menu = navigationView.getMenu();
        Set<CharSequence> titles = titlesForIds(menu, itemIds);
        List<View> rows = findVisibleRowsForTitles(titles);

        if (rows.isEmpty()) { after.run(); return; }

        final int[] remaining = { rows.size() };
        float endTrans = dp(SLIDE_DP);

        for (View row : rows) {
            row.animate()
                    .alpha(0f)
                    .translationY(endTrans)
                    .setDuration(ANIM_DURATION)
                    .withLayer()
                    .withEndAction(() -> {
                        if (--remaining[0] == 0) {
                            after.run();
                        }
                    })
                    .start();
        }
    }

    private static final int[] INPUT_IDS = {
            R.id.main_menu_relative_mouse,
            R.id.main_menu_keyboard,
            R.id.main_menu_input_controls,
            R.id.main_menu_controller_assignment,
            R.id.main_menu_motion_controls
    };

    private static final int[] DISPLAY_IDS = {
            R.id.main_menu_screen_effects,
            R.id.main_menu_toggle_fullscreen,
            R.id.main_menu_magnifier,
            R.id.main_menu_pip_mode
    };

    private void markNestedMenuItems(Menu menu, int[] itemIds) {
        for (int id : itemIds) {
            MenuItem item = menu.findItem(id);
            if (item == null || item.getTitle() == null) continue;
            String title = item.getTitle().toString();
            if (!title.startsWith("Ã¢â€ Â³")) item.setTitle("Ã¢â€ Â³  " + title);
        }
    }


    @Nullable
    private RecyclerView findNavRecycler(View root) {
        if (root instanceof RecyclerView) return (RecyclerView) root;
        if (root instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) root;
            for (int i = 0; i < vg.getChildCount(); i++) {
                RecyclerView rv = findNavRecycler(vg.getChildAt(i));
                if (rv != null) return rv;
            }
        }
        return null;
    }

    private void applyGroup(Menu menu, int groupId, int headerId, boolean expanded) {
        menu.setGroupVisible(groupId, expanded);

        MenuItem header = menu.findItem(headerId);
        if (header != null) {
            header.setCheckable(true);
            header.setChecked(expanded);   // visual cue
        }

        RecyclerView rv = findNavRecycler(navigationView);
        if (rv != null) {
            RecyclerView.Adapter<?> ad = rv.getAdapter();
            if (ad != null) ad.notifyDataSetChanged();
            rv.requestLayout();
            rv.invalidateItemDecorations();
            rv.postInvalidateOnAnimation();
        } else {
            navigationView.invalidate();
            navigationView.postInvalidateOnAnimation();
        }
    }


    private void persistSection(String key, boolean value) {
        preferences.edit().putBoolean(key, value).apply();
    }

    private void expandGroup(Menu menu, int groupId, int headerId, int[] itemIds) {
        // Show items first, then animate them in.
        applyGroup(menu, groupId, headerId, true);
        animateInGroupItems(itemIds);
    }

    private void collapseGroup(Menu menu, int groupId, int headerId, int[] itemIds) {
        RecyclerView rv = navRecycler();
        if (rv == null) { applyGroup(menu, groupId, headerId, false); return; }

        // Find the currently visible rows for this group
        Set<CharSequence> titles = titlesForIds(menu, itemIds);
        List<View> rows = findVisibleRowsForTitles(titles);
        if (rows.isEmpty()) { applyGroup(menu, groupId, headerId, false); return; }

        rv.suppressLayout(true);

        final int[] remaining = { rows.size() };
        float endTrans = dp(COLLAPSE_TRANSLATION_DP);

        for (View row : rows) {
            final View r = row;
            final int startH = r.getHeight();
            if (startH <= 0) { if (--remaining[0] == 0) finishCollapse(menu, groupId, headerId, rv); continue; }

            // Height animator
            ValueAnimator hAnim = ValueAnimator.ofInt(startH, 0);
            hAnim.addUpdateListener(a -> {
                int h = (int) a.getAnimatedValue();
                RecyclerView.LayoutParams lp = (RecyclerView.LayoutParams) r.getLayoutParams();
                lp.height = h;
                r.setLayoutParams(lp);
            });

            // Alpha + slight slide
            ObjectAnimator aAnim = ObjectAnimator.ofFloat(r, View.ALPHA, 1f, 0f);
            ObjectAnimator tAnim = ObjectAnimator.ofFloat(r, View.TRANSLATION_Y, 0f, endTrans);

            AnimatorSet set = new AnimatorSet();
            set.setDuration(ANIM_DURATION);
            set.setInterpolator(new AccelerateDecelerateInterpolator());
            set.playTogether(hAnim, aAnim, tAnim);
            set.addListener(new AnimatorListenerAdapter() {
                @Override public void onAnimationEnd(Animator animation) {
                    // Restore params so RV can recycle properly next time
                    RecyclerView.LayoutParams lp = (RecyclerView.LayoutParams) r.getLayoutParams();
                    lp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
                    r.setLayoutParams(lp);
                    r.setAlpha(1f);
                    r.setTranslationY(0f);

                    if (--remaining[0] == 0) {
                        finishCollapse(menu, groupId, headerId, rv);
                    }
                }
            });
            set.start();
        }
    }

    private void finishCollapse(Menu menu, int groupId, int headerId, RecyclerView rv) {
        applyGroup(menu, groupId, headerId, false); // hides group + notifies adapter
        rv.suppressLayout(false);
    }
    @Override
    public boolean onNavigationItemSelected(@NonNull MenuItem item) {
        final GLRenderer renderer = xServerView.getRenderer();
        final int id = item.getItemId();
        final Menu menu = navigationView.getMenu();

        switch (id) {
            // ---- Section headers (toggle, do NOT close drawer) ----
            case R.id.header_input: {
                boolean wasExpanded = expInput;
                expInput = !expInput;
                persistSection(PREF_EXP_INPUT, expInput);
                if (wasExpanded) {
                    collapseGroup(menu, R.id.group_input, R.id.header_input, INPUT_IDS);
                } else {
                    expandGroup(menu, R.id.group_input, R.id.header_input, INPUT_IDS);
                }
                return true;
            }

            case R.id.header_display: {
                boolean wasExpanded = expDisplay;
                expDisplay = !expDisplay;
                persistSection(PREF_EXP_DISPLAY, expDisplay);
                if (wasExpanded) {
                    collapseGroup(menu, R.id.group_display, R.id.header_display, DISPLAY_IDS);
                } else {
                    expandGroup(menu, R.id.group_display, R.id.header_display, DISPLAY_IDS);
                }
                return true;
            }

            // ---- Top-level quick actions ----
            case R.id.main_menu_relative_mouse:
                isRelativeMouseMovement = !isRelativeMouseMovement;
                container.setRelativeMouseMovement(isRelativeMouseMovement);
                xServer.setRelativeMouseMovement(isRelativeMouseMovement);
                item.setChecked(isRelativeMouseMovement);
                if (!isRelativeMouseMovement) {
                    releasePointerCaptureIfNeeded("toggle-off");
                    touchpadView.setOnCapturedPointerListener(null);
                    Toast.makeText(this, R.string.relative_mouse_disabled, Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(this, R.string.relative_mouse_enabled, Toast.LENGTH_SHORT).show();
                }
                drawerLayout.closeDrawers();
                return true;

            case R.id.main_menu_keyboard:
                AppUtils.showKeyboard(this);
                drawerLayout.closeDrawers();
                return true;

            case R.id.main_menu_task_manager:
                new TaskManagerDialog(this).show();
                drawerLayout.closeDrawers();
                return true;

            case R.id.main_menu_hud:
                showHUDConfigDialog();
                drawerLayout.closeDrawers();
                return true;

            case R.id.main_menu_terminal:
                openTerminal();
                drawerLayout.closeDrawers();
                return true;

            case R.id.main_menu_pause:
                if (isPaused) {
                    ProcessHelper.resumeAllWineProcesses();
                    item.setIcon(R.drawable.icon_pause);
                } else {
                    ProcessHelper.pauseAllWineProcesses();
                    item.setIcon(R.drawable.icon_play);
                }
                isPaused = !isPaused;
                drawerLayout.closeDrawers();
                return true;

            case R.id.main_menu_logs:
                debugDialog.show();
                drawerLayout.closeDrawers();
                return true;

            case R.id.main_menu_touchpad_help:
                showTouchpadHelpDialog();
                drawerLayout.closeDrawers();
                return true;

            // ---- INPUT group ----
            case R.id.main_menu_input_controls:
                showInputControlsDialog();
                drawerLayout.closeDrawers();
                return true;

            case R.id.main_menu_controller_assignment:
                ControllerAssignmentDialog.show(this, winHandler);
                winHandler.clearIgnoredDevices();
                drawerLayout.closeDrawers();
                return true;

            case R.id.main_menu_motion_controls:
                MotionControls.getInstance(this)
                        .attach(winHandler)
                        .showContentDialog(this, null);
                drawerLayout.closeDrawers();
                return true;

            // ---- DISPLAY group ----
            case R.id.main_menu_toggle_fullscreen:
                renderer.toggleFullscreen();
                touchpadView.toggleFullscreen();
                drawerLayout.closeDrawers();
                return true;

            case R.id.main_menu_pip_mode:
                enterPictureInPictureMode();
                drawerLayout.closeDrawers();
                return true;

            case R.id.main_menu_magnifier:
                if (magnifierView == null) {
                    FrameLayout container = findViewById(R.id.FLXServerDisplay);
                    magnifierView = new MagnifierView(this);
                    magnifierView.setZoomButtonCallback(value -> {
                        renderer.setMagnifierZoom(Mathf.clamp(renderer.getMagnifierZoom() + value, 1.0f, 3.0f));
                        magnifierView.setZoomValue(renderer.getMagnifierZoom());
                    });
                    magnifierView.setZoomValue(renderer.getMagnifierZoom());
                    magnifierView.setHideButtonCallback(() -> {
                        container.removeView(magnifierView);
                        magnifierView = null;
                    });
                    container.addView(magnifierView);
                }
                drawerLayout.closeDrawers();
                return true;

            case R.id.main_menu_screen_effects:
                ScreenEffectDialog dlg = new ScreenEffectDialog(this);
                dlg.setOnConfirmCallback(() -> {
                    GLRenderer r = xServerView.getRenderer();
                    ColorEffect color = r.getEffectComposer().getEffect(ColorEffect.class);
                    dlg.applyEffects(color, r);
                });
                dlg.show();
                drawerLayout.closeDrawers();
                return true;

            case R.id.main_menu_exit:
                exitApp();
                return true;
        }

        return true;
    }



    private void openTerminal() {
        Intent intent = new Intent(this, TerminalActivity.class);
        startActivity(intent);
    }

    /** Requests the display mode closest to the configured refreshRate. Factory default = 60 Hz. */
    private void applyPreferredRefreshRate() {
        String rate = graphicsDriverConfig.getOrDefault("refreshRate", "60");
        android.view.Window window = getWindow();
        if (window == null) return;
        android.view.WindowManager.LayoutParams params = window.getAttributes();
        float targetFps = 0;
        try {
            if (rate.isEmpty() || "auto".equals(rate)) {
                android.view.Display display = getWindowManager().getDefaultDisplay();
                if (display != null) {
                    android.view.Display.Mode[] modes = display.getSupportedModes();
                    android.view.Display.Mode maxMode = null;
                    for (android.view.Display.Mode m : modes) {
                        if (maxMode == null || m.getRefreshRate() > maxMode.getRefreshRate()) maxMode = m;
                    }
                    if (maxMode != null) {
                        params.preferredDisplayModeId = maxMode.getModeId();
                        params.preferredRefreshRate = maxMode.getRefreshRate();
                        targetFps = maxMode.getRefreshRate();
                    }
                }
                if (targetFps == 0) targetFps = 120f;
            } else {
                float preferred = Float.parseFloat(rate);
                params.preferredRefreshRate = preferred;
                targetFps = preferred;
                android.view.Display display = getWindowManager().getDefaultDisplay();
                if (display != null) {
                    for (android.view.Display.Mode m : display.getSupportedModes()) {
                        if (Math.abs(m.getRefreshRate() - preferred) < 0.5f) {
                            params.preferredDisplayModeId = m.getModeId();
                            break;
                        }
                    }
                }
            }
            window.setAttributes(params);
            if (targetFps > 0) activeDisplayRefreshRate = targetFps;
        } catch (Exception ignored) {}
    }

    private String resolveVsyncMode() {
        String configured;
        if (graphicsDriverConfig.containsKey("vsyncMode")) {
            configured = graphicsDriverConfig.get("vsyncMode");
        }
        else if (graphicsDriverConfig.containsKey("vblankOff")) {
            configured = "1".equals(graphicsDriverConfig.get("vblankOff")) ? "off" : "100";
        }
        else configured = "off";
        String value = shortcut != null
                ? shortcut.getExtra("vsyncMode", container.getExtra("vsyncMode", configured))
                : container.getExtra("vsyncMode", configured);
        return "off".equals(value) || "50".equals(value) ? value : "100";
    }

    /**
     * Presentation mode and frame pacing are deliberately independent.  In
     * particular, VSync Off means "do not throttle guest Present requests";
     * it must not silently turn a requested mailbox swapchain into immediate.
     */
    private String resolvePresentMode() {
        String value = graphicsDriverConfig != null
                ? graphicsDriverConfig.getOrDefault("presentMode", "mailbox")
                : "mailbox";
        value = value == null ? "mailbox" : value.trim().toLowerCase(Locale.ENGLISH);
        switch (value) {
            case "fifo":
            case "immediate":
            case "relaxed":
            case "mailbox":
                return value;
            default:
                return "mailbox";
        }
    }

    private int getStoredFpsLimit() {
        try {
            String value = shortcut != null
                    ? shortcut.getExtra("fpsLimit", container.getExtra("fpsLimit", "0"))
                    : container.getExtra("fpsLimit", "0");
            return Math.max(0, Integer.parseInt(value));
        }
        catch (Exception ignored) {
            return 0;
        }
    }

    private void setFpsLimit(GLRenderer renderer, int limit, boolean persist) {
        int requestedLimit = Math.max(0, limit);
        // FPS Limiter and VSync are independent settings. VSync controls the
        // presentation backend; it must never rewrite or clamp this limiter.
        renderer.setFpsLimit(requestedLimit);
        com.winlator.cmod.xserver.extensions.PresentExtension present =
                xServer.getExtension(com.winlator.cmod.xserver.extensions.PresentExtension.MAJOR_OPCODE);
        if (present != null) {
            present.setRefreshRate(activeDisplayRefreshRate);
            present.setVsyncMode(resolveVsyncMode());
            present.setFrameRateLimit(requestedLimit);
        }
        if (persist) {
            persistRuntimeVideoOption("fpsLimit", String.valueOf(requestedLimit));
            if (requestedLimit > 0)
                persistRuntimeVideoOption("fpsLimitValue", String.valueOf(requestedLimit));
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (!hasFocus) {
            if (pointerCaptureRequested && touchpadView != null) {
                touchpadView.releasePointerCapture();
                pointerCaptureRequested = false;
                Log.d("PointerCapture", "Released due to focus loss.");
            }
        } else {
            if (isRelativeMouseMovement && !pointerCaptureRequested) {
                ensurePointerCapture("focus-gained");
            }
        }
    }

    private void extractArm64ecInputDLLs() {
        String inputAsset = "arm64ec_input_dlls.tzst";
        String wineVersion = container.getWineVersion();
        Log.d("XServerDisplayActivity", "ARM64EC input DLL check: " + wineVersion);

        // These DLLs belong to the bundled Proton 9 build. Injecting them into
        // arbitrary Proton 10/11 packages can mix incompatible Wine DLLs and
        // cause a black screen. External runtimes use Mali's fake-input layer.
        if (wineInfo != null && wineInfo.isArm64EC()
                && WineInfo.MAIN_WINE_VERSION.identifier().equals(wineVersion)) {
            if ("1".equals(container.getExtra("arm64ecInputDllsVersion"))) {
                Log.d("XServerDisplayActivity", "ARM64EC input DLLs already current; skipping extraction.");
                return;
            }
            File wineFolder = new File(imageFs.getWinePath() + "/lib/wine/");
            Log.d("XServerDisplayActivity", "Extracting ARM64EC input DLLs to " + wineFolder.getPath());
            boolean success = TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this, inputAsset, wineFolder);
            if (!success) {
                Log.d("XServerDisplayActivity", "Failed to extract input dlls");
            }
            else {
                container.putExtra("arm64ecInputDllsVersion", "1");
                container.saveData();
            }
        }
        else {
            Log.d("XServerDisplayActivity", "Runtime is not ARM64EC; skipping ARM64EC input DLLs.");
        }
    }

    private void setupWineSystemFiles() {
        String appVersion = String.valueOf(AppUtils.getVersionCode(this));
        String imgVersion = String.valueOf(imageFs.getVersion());
        boolean containerDataChanged = false;
        boolean prefixMetadataChanged = false;

        if (!container.getExtra("appVersion").equals(appVersion) || !container.getExtra("imgVersion").equals(imgVersion)) {
            applyGeneralPatches(container);
            container.putExtra("appVersion", appVersion);
            container.putExtra("imgVersion", imgVersion);
            containerDataChanged = true;
            prefixMetadataChanged = true;
        }

        String dxwrapper = this.dxwrapper;
        String resolvedWrapper = dxwrapper;
        boolean resolvedWrapperReady = true;
        if (dxwrapper.equals("dxvk")) {
            String dxvkEntry = "dxvk-" + dxwrapperConfig.get("version", DefaultVersion.DXVK);
            String vkd3dVersion = dxwrapperConfig.get("vkd3dVersion", DefaultVersion.VKD3D);
            boolean disableVkd3d = "none".equalsIgnoreCase(vkd3dVersion);
            String vkd3dEntry = disableVkd3d ? null : "vkd3d-" + vkd3dVersion;
            resolvedWrapper = disableVkd3d ? dxvkEntry : dxvkEntry + "+" + vkd3dEntry;
            String storedWrapper = container.getExtra("dxwrapper");
            boolean wrapperFilesReady = hasRequiredDxWrapperFiles(!disableVkd3d);
            if (!resolvedWrapper.equals(storedWrapper) || !wrapperFilesReady) {
                extractDXWrapperFiles(dxvkEntry);
                if (!disableVkd3d) extractDXWrapperFiles(vkd3dEntry);
                // Some legacy DXVK packs also contain a d3d12.dll. When
                // VKD3D is explicitly disabled, restore Wine's implementation
                // after DXVK extraction so the stale file cannot win again.
                if (disableVkd3d) {
                    restoreOriginalDllFiles("d3d12.dll", "d3d12core.dll");
                    removeStaleVkd3dDlls();
                }
                wrapperFilesReady = hasRequiredDxWrapperFiles(!disableVkd3d);
            }
            if (!wrapperFilesReady) {
                resolvedWrapperReady = false;
                Log.e("DXWrapperExtraction", "Required DXVK/VKD3D DLLs are still missing; installation will retry next launch");
            }
        } else if (!resolvedWrapper.equals(container.getExtra("dxwrapper"))) {
            extractDXWrapperFiles(resolvedWrapper);
        }

        if (resolvedWrapperReady && !resolvedWrapper.equals(container.getExtra("dxwrapper"))) {
            container.putExtra("dxwrapper", resolvedWrapper);
            containerDataChanged = true;
        }

        String ddrawrapper = this.ddrawrapper;

        if (!ddrawrapper.equals(container.getExtra("ddrawrapper"))) {
            extractDDrawrapperFiles(ddrawrapper);
            container.putExtra("ddrawrapper", ddrawrapper);
            containerDataChanged = true;
        }

        if (ddrawrapper.equals("cnc-ddraw")) envVars.put("CNC_DDRAW_CONFIG_FILE", "C:\\windows\\syswow64\\ddraw.ini");

        String wincomponents = shortcut != null ? shortcut.getExtra("wincomponents", container.getWinComponents()) : container.getWinComponents();
        if (!wincomponents.equals(container.getExtra("wincomponents"))) {
            extractWinComponentFiles();
            container.putExtra("wincomponents", wincomponents);
            containerDataChanged = true;
        }

        String desktopTheme = container.getDesktopTheme();
        String themeKey = desktopTheme+","+xServer.screenInfo;
        String storedTheme = container.getExtra("desktopTheme");
        if (!themeKey.equals(storedTheme)) {
            WineThemeManager.apply(this, new WineThemeManager.ThemeInfo(desktopTheme), xServer.screenInfo);
            container.putExtra("desktopTheme", themeKey);
            containerDataChanged = true;
        }

        WineStartMenuCreator.create(this, container);
        WineUtils.createDosdevicesSymlinks(container);

        // The bionic WFM reliably executes the context-menu command that writes
        // a Shell Link. Re-copy it once for existing prefixes as well.
        if (!"3".equals(container.getExtra("wfmFixVersion"))) {
            File windowsDir = new File(container.getRootDir(), ".wine/drive_c/windows");
            File wfmFile = new File(windowsDir, "wfm.exe");
            File cdioFile = new File(windowsDir, "libcdio.dll");
            FileUtils.copy(this, "wfm.exe", wfmFile);
            FileUtils.copy(this, "libcdio.dll", cdioFile);
            if (wfmFile.length() == 291840L && cdioFile.length() == 187392L) {
                container.putExtra("wfmFixVersion", "3");
                containerDataChanged = true;
            }
        }

        if (shortcut != null)
            startupSelection = shortcut.getExtra("startupSelection", String.valueOf(container.getStartupSelection()));
        else
            startupSelection = String.valueOf(container.getStartupSelection());

        byte selection = Container.STARTUP_SELECTION_ESSENTIAL;
        try {
            selection = Byte.parseByte(startupSelection);
        }
        catch (NumberFormatException ignored) {}
        if (selection < Container.STARTUP_SELECTION_NORMAL
                || selection > Container.STARTUP_SELECTION_AGGRESSIVE)
            selection = Container.STARTUP_SELECTION_ESSENTIAL;
        // Updating all service keys rewrites system.reg many times. Doing that on
        // every launch made x86 prefixes spend a very long time on "Starting up".
        // Reapply only after a prefix/image update or when the selected policy
        // has not actually been written to this prefix yet.
        String appliedSelection = container.getExtra("startupSelectionApplied");
        // Bump the revision so existing prefixes also receive the corrected
        // Aggressive idle-service policy instead of keeping stale registry data.
        String startupPolicyRevision = selection + ":lean-3";
        if (prefixMetadataChanged || !startupPolicyRevision.equals(appliedSelection)) {
            WineUtils.changeServicesStatus(container, selection);
            container.putExtra("startupSelectionApplied", startupPolicyRevision);
            containerDataChanged = true;
        }
        if (!startupSelection.equals(container.getExtra("startupSelection"))) {
            container.putExtra("startupSelection", startupSelection);
            containerDataChanged = true;
        }

        if (!"1".equals(container.getExtra("controllerFixVersion"))) {
            File system32Dir = new File(container.getRootDir(), ".wine/drive_c/windows/system32");
            FileUtils.copy(this, "controllerfix/dinput.dll", new File(system32Dir, "dinput.dll"));
            FileUtils.copy(this, "controllerfix/dinput8.dll", new File(system32Dir, "dinput8.dll"));
            FileUtils.copy(this, "controllerfix/xidi.ini", new File(system32Dir, "xidi.ini"));
            // FileUtils.copy swallows IO errors; only mark as applied when the
            // files really landed so a failed copy is retried on next launch.
            if (new File(system32Dir, "dinput.dll").isFile()
                    && new File(system32Dir, "dinput8.dll").isFile()
                    && new File(system32Dir, "xidi.ini").isFile()) {
                container.putExtra("controllerFixVersion", "1");
                containerDataChanged = true;
            }
        }

        if (containerDataChanged) container.saveData();
    }

    private void setupXEnvironment() throws PackageManager.NameNotFoundException {

        // Set environment variables
        envVars.put("LC_ALL", lc_all);
        envVars.put("MESA_DEBUG", "silent");
        envVars.put("MESA_NO_ERROR", "1");
        envVars.put("WINEPREFIX", imageFs.wineprefix);
//        Log.d("Winetricks", "WINEPREFIX: " + imageFs.wineprefix);

        boolean enableWineDebug = preferences.getBoolean("enable_wine_debug", false);
        String wineDebugChannels = preferences.getString("wine_debug_channels", SettingsFragment.DEFAULT_WINE_DEBUG_CHANNELS);
        envVars.put("WINEDEBUG", enableWineDebug && !wineDebugChannels.isEmpty()
                ? "+" + wineDebugChannels.replace(",", ",+")
                : "-all"
        );

        // Clear any temporary directory
        String rootPath = imageFs.getRootDir().getPath();
        FileUtils.clear(imageFs.getTmpDir());


        // Create the appropriate launcher based on the container type
        GuestProgramLauncherComponent guestProgramLauncherComponent;

        bionicLauncher = new BionicProgramLauncherComponent(
                contentsManager,
                contentsManager.getProfileByEntryName(container.getWineVersion()),
                shortcut
        );
        guestProgramLauncherComponent = bionicLauncher;
        glibcLauncher = null; // We're not using glibc in this case

        // Additional container checks and environment configuration
        if (container != null) {
            bionicLauncher.setContainer(this.container);
            bionicLauncher.setWineInfo(this.wineInfo);
            boolean wow64Mode = container.isWoW64Mode();
            // Construct the guest executable command
            String guestExecutable = "wine explorer /desktop=shell," + xServer.screenInfo + " " + getWineStartCommand();
            // (Alternatively: "wine wineboot -u" or anything else you want)

//            Log.d("Winetricks", "Guest executable: " + guestExecutable);

            // Set up the guest program parameters
            guestProgramLauncherComponent.setWoW64Mode(wow64Mode);
            guestProgramLauncherComponent.setGuestExecutable(guestExecutable);

            // Merge in containerÃ¢â‚¬â„¢s environment variables
            envVars.putAll(container.getEnvVars());

            // Merge in shortcut environment variables if present
            if (shortcut != null) envVars.putAll(shortcut.getExtra("envVars"));

            // If WINEESYNC is not defined, default to "1"
            if (!envVars.has("WINEESYNC")) {
                envVars.put("WINEESYNC", "1");
            }

            // Bind any drive paths the container defines
            ArrayList<String> bindingPaths = new ArrayList<>();
            for (String[] drive : container.drivesIterator()) {
                bindingPaths.add(drive[1]);
            }
            guestProgramLauncherComponent.setBindingPaths(bindingPaths.toArray(new String[0]));

            // Box86/64 presets from container or shortcut
            guestProgramLauncherComponent.setBox64Preset(
                    shortcut != null
                            ? shortcut.getExtra("box64Preset", container.getBox64Preset())
                            : container.getBox64Preset()
            );
        }

        // Merge overrideEnvVars if present
        if (overrideEnvVars != null) {
            envVars.putAll(overrideEnvVars);
            overrideEnvVars.clear(); // Clear overrideEnvVars as per smali logic
        }

        boolean enableGstreamer = container.isGstreamerWorkaround();
        if (shortcut != null && shortcut.hasExtra("gstreamerWorkaround")) {
            enableGstreamer = shortcut.getExtra("gstreamerWorkaround").equals("1");
        }

        if (enableGstreamer) {
            for (String envVar : Container.MEDIACONV_ENV_VARS) {
                String[] parts = envVar.split("=", 2);
                if (parts.length == 2) {
                    envVars.put(parts[0], parts[1]);
                }
            }
        }

        boolean experimentalPerformance = "1".equals(
                container.getExtra("experimentalPerformance", "0"));
        if (shortcut != null && shortcut.hasExtra("experimentalPerformance")) {
            experimentalPerformance = "1".equals(shortcut.getExtra("experimentalPerformance"));
        }
        String xperfRaw = shortcut != null && shortcut.hasExtra("xperfConfig")
                ? shortcut.getExtra("xperfConfig")
                : container.getExtra("xperfConfig", "");
        xperfConfig = ExperimentalPerformanceDialog.parseConfig(xperfRaw);
        CpuClusters.setPerformancePinningEnabled(!"0".equals(xperfConfig.get("wow64Pin")));
        if (experimentalPerformance) {
            // Opt-in and fully reversible runtime defaults. Each piece is
            // individually switchable via the Experimental Performance tuning
            // dialog (container extra "xperfConfig", shortcut-overridable);
            // keys default to the historical behaviour except perfcache,
            // which now stays off unless explicitly enabled.
            // WRAPPER_MAX_IMAGE_COUNT is applied here on purpose: it runs after
            // extractGraphicsDriverFiles(), so the opt-in value overrides the
            // present-mode-derived swapchain limit.

            if ("1".equals(xperfConfig.get("mdiex"))) {
                try { envVars.put("MDIEX_PROFILE", WinXclipsePolicy.nativeProfileName()); }
                catch (Throwable t) { Log.w("GraphicsDriverExtraction", "WinXclipsePolicy unavailable", t); }
            }
            if ("1".equals(xperfConfig.get("perfcache")) && GPUInformation.isXclipse())
                installPerfCacheLayer(envVars);
            if ("1".equals(xperfConfig.get("translationTurbo")))
                applyTranslationTurbo(envVars);
        }

        // NRAMV unified-memory manager runs in our process for every session;
        // its baseline trim level follows device RAM while live escalation is
        // driven by the HUD RAM alert through RamOptimizerXclipse.escalate().
        // Video tab choices apply regardless of the Experimental master switch.
        // vblank_mode=0 is only valid for an explicitly immediate swapchain.
        // Applying it merely because the independent FPS pacing control is Off
        // defeats mailbox and produces the exact high-FPS horizontal tear that
        // the mailbox selection is meant to avoid.
        if ("off".equals(resolveVsyncMode()) && "immediate".equals(resolvePresentMode()))
            envVars.put("vblank_mode", "0");
        if ("1".equals(graphicsDriverConfig.getOrDefault("unlimitedImages", "0")))
            envVars.put("WRAPPER_MAX_IMAGE_COUNT", "0");
        applyRamOptimizerProfile();

        // Create our overall XEnvironment with various components
        environment = new XEnvironment(this, imageFs);
        environment.addComponent(
                new SysVSharedMemoryComponent(
                        xServer,
                        UnixSocketConfig.createSocket(rootPath, UnixSocketConfig.SYSVSHM_SERVER_PATH)
                )
        );
        environment.addComponent(
                new XServerComponent(
                        xServer,
                        UnixSocketConfig.createSocket(rootPath, UnixSocketConfig.XSERVER_PATH)
                )
        );


        environment.addComponent(new NetworkInfoUpdateComponent());

        // Audio driver logic
        if (audioDriver.equals("alsa") || audioDriver.equals("alsa-reflector")) {
            envVars.put("ANDROID_ALSA_SERVER", rootPath + UnixSocketConfig.ALSA_SERVER_PATH);
            envVars.put("ANDROID_ASERVER_USE_SHM", "true");

            // Determine the mode based on the driver name
            boolean useReflector = audioDriver.equals("alsa-reflector");

            // Add the component and pass the mode directly into the constructor
            environment.addComponent(
                    new ALSAServerComponent(
                            UnixSocketConfig.createSocket(rootPath, UnixSocketConfig.ALSA_SERVER_PATH),
                            useReflector
                    )
            );
        }
        else if (audioDriver.equals("pulseaudio")) {
            envVars.put("PULSE_SERVER", rootPath + UnixSocketConfig.PULSE_SERVER_PATH);
            environment.addComponent(
                    new PulseAudioComponent(
                            UnixSocketConfig.createSocket(rootPath, UnixSocketConfig.PULSE_SERVER_PATH),
                            audioVolume
                    )
            );
        }

        // RC (box86_64rc) file handling
        RCManager manager = new RCManager(this);
        manager.loadRCFiles();
        int rcfileId = container.getRCFileId();
        if (shortcut != null) {
            try {
                rcfileId = Integer.parseInt(shortcut.getExtra("rcfileId", String.valueOf(container.getRCFileId())));
            }
            catch (NumberFormatException e) {
                Log.e("XServerDisplayActivity", "Invalid rcfileId extra", e);
            }
        }
        RCFile rcfile = manager.getRcfile(rcfileId);

        File file = new File(container.getRootDir(), ".box64rc");
        String str = rcfile == null ? "" : rcfile.generateBox86_64rc();
        FileUtils.writeString(file, str);

        // Let Box64 inside Wine see this config
        envVars.put("BOX64_RCFILE", file.getAbsolutePath());

        // Pass final envVars to the launcher
        guestProgramLauncherComponent.setEnvVars(envVars);
        guestProgramLauncherComponent.setTerminationCallback((status) -> {
            if (shortcut == null) {
                finishSession();
                return;
            }
            // start.exe may return before a launcher-created child game. Let
            // the process probe decide when the shortcut has really become
            // idle instead of tearing down a still-running child immediately.
            sidebarHandler.post(() -> {
                sawShortcutProcess = true;
                shortcutIdleSinceMs = 0L;
                sidebarHandler.removeCallbacks(shortcutExitProbe);
                sidebarHandler.post(shortcutExitProbe);
            });
        });

        // Add the launcher to our environment
        environment.addComponent(guestProgramLauncherComponent);

        // Generate fexcore per app settings
        FEXCoreManager.createAppConfigFiles(this);

        // Start all environment components (XServer, Audio, etc.)
        environment.startEnvironmentComponents();

        if (String.valueOf(Container.STARTUP_SELECTION_AGGRESSIVE).equals(startupSelection)) {
            aggressiveWineTrimAttempts = 0;
            sidebarHandler.removeCallbacks(aggressiveWineTrimProbe);
            sidebarHandler.postDelayed(aggressiveWineTrimProbe, 3500L);
        }

        // A Wine desktop is intentionally persistent. A shortcut, however,
        // should leave as soon as its application (and any launcher children)
        // has been gone for five continuous seconds. Base Wine services and
        // crash reporters do not keep the session alive. A stale shortcut_path
        // that failed to resolve still counts as a shortcut launch so a dead
        // session cannot linger forever either.
        launchedAsShortcut = shortcut != null
                || (getIntent() != null && getIntent().hasExtra("shortcut_path"));
        shortcutLaunchMs = android.os.SystemClock.elapsedRealtime();
        sawStartExe = false;
        if (launchedAsShortcut) {
            sawShortcutProcess = false;
            observedShortcutApplication = false;
            shortcutIdleSinceMs = 0L;
            automaticLifecycleClose = false;
            lifecycleCloseReason = "";
            lifecycleLogWritten = false;
            sidebarHandler.removeCallbacks(shortcutExitProbe);
            sidebarHandler.postDelayed(shortcutExitProbe, 1000L);
        }

        // (Optionally) run Winetricks after setup, if you wish
        // runWinetricksAfterSetup();


        // Start the WinHandler
        winHandler.start();

        // Properly initialize the WineRequestHandler with all necessary context before starting it
        if (wineRequestHandler != null) {
            wineRequestHandler.setContainer(this.container);
            wineRequestHandler.setShortcut(this.shortcut);
            wineRequestHandler.setEnvVars(this.envVars);
            wineRequestHandler.setWineInfo(this.wineInfo);
            wineRequestHandler.start();
        }

        // Clear envVars if needed
        // envVars.clear();

        // Reset dxwrapper config
        dxwrapperConfig = null;


    }



    private void createWineWrappers(Container container, ContentsManager contentsManager) {
        String wineBinPath;
        String wineLibPath;
        String box64Path = imageFs.getRootDir().getPath() + "/usr/local/bin/box64";
        String usrLocalBin = imageFs.getRootDir().getPath() + "/usr/local/bin";

        // Determine if the container is using a contents profile Wine version
        ContentProfile profile = contentsManager.getProfileByEntryName(container.getWineVersion());
        if (profile != null && (profile.type == ContentProfile.ContentType.CONTENT_TYPE_WINE
                || profile.type == ContentProfile.ContentType.CONTENT_TYPE_PROTON)) {
            File profileInstallDir = contentsManager.getInstallDir(this, profile);
            wineBinPath = profileInstallDir.getPath() + "/" + profile.wineBinPath;
            wineLibPath = profileInstallDir.getPath() + "/" + profile.wineLibPath;
        } else {
            wineBinPath = imageFs.getWinePath() + "/bin";
            wineLibPath = imageFs.getWinePath() + "/lib/wine";
        }

        // Fetch stored environment variables
        Map<String, String> envVars = EnvironmentManager.getEnvVars();

        // Build environment export section dynamically
        StringBuilder dynamicEnvExports = new StringBuilder("#!" + imageFs.getRootDir() + "/usr/bin/dash\n");
        for (Map.Entry<String, String> entry : envVars.entrySet()) {
            dynamicEnvExports.append("export ").append(entry.getKey()).append("=\"")
                    .append(entry.getValue().replace("\"", "\\\"")).append("\"\n");
        }

        // Define the wine and wine64 wrappers to open explorer.exe with the desktop shell
        String wineExecContent = dynamicEnvExports.toString() +
                "exec \"" + box64Path + "\" \"" + wineBinPath + "/wine\" explorer.exe /desktop=shell," + xServer.screenInfo + " \"$@\"";
        createWrapperScript(usrLocalBin + "/wine", wineExecContent);
        createWrapperScript(usrLocalBin + "/wine64", wineExecContent);

        // Define the wineserver wrapper with a different exec command
        String wineserverContent = dynamicEnvExports.toString() +
                "exec \"" + box64Path + "\" \"" + wineBinPath + "/wineserver\" \"$@\"";
        createWrapperScript(usrLocalBin + "/wineserver", wineserverContent);
    }

    private void createWrapperScript(String path, String content) {
        File scriptFile = new File(path);
        FileUtils.writeString(scriptFile, content);
        scriptFile.setExecutable(true);
    }


    private static final int MAX_LOG_LINES = 1000;
    private static final int BATCH_SIZE = 10;


    private void appendBufferedLog(BufferedReader reader, TextView outputView, boolean isError) throws IOException {
        ArrayDeque<String> logBuffer = new ArrayDeque<>(MAX_LOG_LINES);
        StringBuilder batchBuffer = new StringBuilder();
        String line;
        int batchCount = 0;

        while ((line = reader.readLine()) != null) {
            String finalLine = (isError ? "Error: " : "") + line;

            if (logBuffer.size() >= MAX_LOG_LINES) {
                logBuffer.pollFirst(); // Remove the oldest line
            }
            logBuffer.addLast(finalLine);

            batchBuffer.append(finalLine).append("\n");
            batchCount++;

            if (batchCount >= BATCH_SIZE) {
                String logContent = String.join("\n", logBuffer);
                runOnUiThread(() -> outputView.setText(logContent));
                batchBuffer.setLength(0); // Clear batch buffer
                batchCount = 0;
            }
        }

        // Final update if there are remaining lines
        if (batchBuffer.length() > 0) {
            String logContent = String.join("\n", logBuffer);
            runOnUiThread(() -> outputView.setText(logContent));
        }
    }



    private void setupUI() {
        FrameLayout rootView = findViewById(R.id.FLXServerDisplay);
        xServerView = new XServerView(this, xServer);
        final GLRenderer renderer = xServerView.getRenderer();
        renderer.setCursorVisible(false);
        setFpsLimit(renderer, getStoredFpsLimit(), false);

        int textureFilterMode = 0;
        try {
            String value = shortcut != null
                    ? shortcut.getExtra("rendererFilterMode", container.getExtra("rendererFilterMode", "0"))
                    : container.getExtra("rendererFilterMode", "0");
            textureFilterMode = Integer.parseInt(value);
        }
        catch (Exception ignored) {}
        textureFilterMode = Math.max(0, Math.min(textureFilterMode, 3));
        renderer.setTextureFilterMode(textureFilterMode);
        boolean swapRedBlue = shortcut != null
                ? "1".equals(shortcut.getExtra("rendererSwapRB", container.getExtra("rendererSwapRB", "0")))
                : "1".equals(container.getExtra("rendererSwapRB", "0"));
        renderer.setSwapRedBlue(swapRedBlue);
        boolean fakeHdr = shortcut != null
                ? "1".equals(shortcut.getExtra("fakeHDR", container.getExtra("fakeHDR", "0")))
                : "1".equals(container.getExtra("fakeHDR", "0"));
        if (fakeHdr) renderer.getEffectComposer().addEffect(new HDREffect());
        // FSR is controlled by the texture filter selection (2 = FSR). The
        // upscale/mode keys come from the driver config; runtime menu changes
        // are persisted as extras that take precedence over the config.
        // resolveFsrState() applies the same legacy fsrMode migration used
        // for the guest render scale decided at session start.
        String fsrState = resolveFsrState();
        if (!"off".equals(fsrState)) renderer.setTextureFilterMode(2);
        applyFsrRuntime(renderer, fsrState);

        if (shortcut != null) {
            if (shortcut.getExtra("forceFullscreen", "0").equals("1")) renderer.setForceFullscreenWMClass(shortcut.wmClass);
            renderer.setUnviewableWMClasses("explorer.exe");
        }

        xServer.setRenderer(renderer);
        rootView.addView(xServerView);

        int frameGenerationProfile = parseFrameGenerationProfile(
                getRuntimeVideoOption("frameGenerationProfile", "balanced"));
        int frameGenerationTarget = parseFrameGenerationTarget(
                getRuntimeVideoOption("frameGenerationTargetFPS", "60"));
        float frameGenerationMultiplier = parseFrameGenerationMultiplier(
                getRuntimeVideoOption("frameGenerationMultiplier", "auto"));
        int frameGenerationBackend = parseFrameGenerationBackend(
                getRuntimeVideoOption("frameGenerationBackend", "gles"));
        // APK 0.9.5 parity: no low-latency extrapolation mode; always interpolate.
        boolean frameGenerationLowLatency = false;
        boolean frameGenerationEnabled = "1".equals(
                getRuntimeVideoOption("frameGenerationEnabled", "0"))
                && isFrameGenerationCompatible();
        if (frameGenerationEnabled) {
            // A shortcut can start Wine while GLSurfaceView is still creating
            // its EGL context. Replaying a persisted Apex request in that
            // window left some Samsung drivers in a crash loop, so arm it only
            // after the first compositor frames have established the surface.
            xServerView.postDelayed(() -> {
                if (isFinishing() || isDestroyed() || xServerView == null) return;
                renderer.setApex(true, frameGenerationQuality(frameGenerationProfile),
                        frameGenerationMultiplier, frameGenerationTarget,
                        frameGenerationStability(frameGenerationProfile),
                        frameGenerationBackend, frameGenerationLowLatency);
                updateSidebarFrameGenerationState(renderer);
            }, 1200L);
        }
        else {
            renderer.setApex(false, frameGenerationQuality(frameGenerationProfile),
                    frameGenerationMultiplier, frameGenerationTarget,
                    frameGenerationStability(frameGenerationProfile),
                    frameGenerationBackend, frameGenerationLowLatency);
        }

        globalCursorSpeed = preferences.getFloat("cursor_speed", 1.0f);
        touchpadView = new TouchpadView(this, xServer, timeoutHandler, hideControlsRunnable);
        isMouseDisabled = preferences.getBoolean("touchscreen_mouse_disabled", false);
        touchpadView.setTouchscreenMouseDisabled(isMouseDisabled);
        touchpadView.setSensitivity(globalCursorSpeed);
        touchpadView.setFourFingersTapCallback(() -> {
            if (!drawerLayout.isDrawerOpen(GravityCompat.START)) drawerLayout.openDrawer(GravityCompat.START);
        });
        rootView.addView(touchpadView);


        inputControlsView = new InputControlsView(this, timeoutHandler, hideControlsRunnable);
        inputControlsView.setOverlayOpacity(preferences.getFloat("overlay_opacity", InputControlsView.DEFAULT_OVERLAY_OPACITY));
        inputControlsView.setTouchpadView(touchpadView);
        inputControlsView.setXServer(xServer);
        inputControlsView.setVisibility(View.GONE);
        rootView.addView(inputControlsView);


        startTouchscreenTimeout();

        boolean isTimeoutEnabled = preferences.getBoolean("touchscreen_timeout_enabled", false);
        if (isTimeoutEnabled) {
            startTouchscreenTimeout();
        }

        String runtimeHudMode = getRuntimeHudMode();
        if (container != null && container.isShowFPS()
                && !"dxvk".equals(runtimeHudMode)) {
            hudDataSource = new HudDataSource(this);
            frameRating = new WinlatorHUD(this);
            frameRating.setDataSource(hudDataSource);
            frameRating.setMangoStyle("mangohud".equals(runtimeHudMode));
            frameRating.setWrapperName(graphicsDriver);
            frameRating.onRendererDetected(getHudApiName());
            frameRating.setPhoneGpuName(getPhoneGpuName());
            renderer.setWinlatorHUD(frameRating);
            frameRating.enableByUser();
            rootView.addView(frameRating);
            frameRating.postDelayed(() -> frameRating.setPhoneGpuName(getPhoneGpuName()), 500);
        }

        // Get the fullscreen stretched extra from the shortcut if available
        String shortcutFullscreenStretched = shortcut != null ? shortcut.getExtra("fullscreenStretched") : null;

        // Proceed based on container and shortcut settings
        boolean shouldStretch = false;

        if (shortcut != null && shortcutFullscreenStretched != null) {
            // Shortcut exists and has a valid setting
            shouldStretch = shortcutFullscreenStretched.equals("1");
        } else if (container != null && container.isFullscreenStretched()) {
            // No shortcut or shortcut doesn't override, use the container's setting
            shouldStretch = true;
        }

        if (shouldStretch) {
            // Toggle fullscreen mode based on the final decision
            renderer.toggleFullscreen();
            touchpadView.toggleFullscreen();
        }

        if (shortcut != null) {
            String controlsProfile = shortcut.getExtra("controlsProfile");
            if (!controlsProfile.isEmpty()) {
                try {
                    ControlsProfile profile = inputControlsManager.getProfile(Integer.parseInt(controlsProfile));
                    if (profile != null) showInputControls(profile);
                }
                catch (NumberFormatException e) {
                    Log.e("XServerDisplayActivity", "Invalid controlsProfile extra: " + controlsProfile, e);
                }
            }

            String simTouchScreen = shortcut.getExtra("simTouchScreen");
            touchpadView.setSimTouchScreen(simTouchScreen.equals("1"));
        }

        AppUtils.observeSoftKeyboardVisibility(drawerLayout, renderer::setScreenOffsetYRelativeToCursor);
        setupModernSidebar(renderer);
    }

    /**
     * Ends the current Wine session without dropping the user on the Android launcher.
     * A pinned shortcut can start this activity as the root of its own task, so there
     * is no MainActivity underneath it to reveal when Wine exits.
     */
    private void finishSession() {
        Executors.newSingleThreadExecutor().execute(() -> {
            // The guest exited on its own: tear the environment down exactly like
            // exitApp() would, otherwise X/audio/handler components keep running
            // while the activity is already gone.
            stopSessionServices();
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;

                preloaderDialog.close();
                if (isTaskRoot()) {
                    Intent mainIntent = new Intent(this, MainActivity.class);
                    mainIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP
                            | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                    startActivity(mainIntent);
                }
                finish();
            });
        });
    }

    private void setupModernSidebar(GLRenderer renderer) {
        if (navigationView == null || navigationView.getHeaderCount() == 0) return;
        View sidebar = navigationView.getHeaderView(0);
        sidebarTimeView = sidebar.findViewById(R.id.TVSidebarTime);
        sidebarBatteryView = sidebar.findViewById(R.id.TVSidebarBattery);
        sidebarControllerProfileView = sidebar.findViewById(R.id.TVSidebarControllerProfile);
        sidebarFpsLimitView = sidebar.findViewById(R.id.TVSidebarFpsLimit);
        sidebarFrameGenerationView = sidebar.findViewById(R.id.TVSidebarFrameGeneration);
        sidebarFrameGenerationButton = sidebar.findViewById(R.id.BTSidebarFrameGeneration);
        sidebarPauseButton = sidebar.findViewById(R.id.BTSidebarPause);

        updateSidebarFpsLabel(renderer.getFpsLimit());
        updateSidebarFrameGenerationState(renderer);
        ControlsProfile defaultProfile = getSidebarControllerProfile();
        updateSidebarControllerLabel(defaultProfile);
        if (inputControlsView.getProfile() == null
                && preferences.getBoolean("sidebar_controller_enabled", true)
                && defaultProfile != null) {
            showInputControls(defaultProfile);
        }

        View input = sidebar.findViewById(R.id.BTSidebarInput);
        View inputMore = sidebar.findViewById(R.id.BTSidebarInputMore);
        input.setOnClickListener(this::showSidebarInputMenu);
        inputMore.setOnClickListener(this::showSidebarInputMenu);

        sidebar.findViewById(R.id.BTSidebarController).setOnClickListener(v -> {
            if (inputControlsView.getProfile() != null) {
                hideInputControls();
                preferences.edit().putBoolean("sidebar_controller_enabled", false).apply();
            } else {
                ControlsProfile profile = getSidebarControllerProfile();
                if (profile != null) {
                    showInputControls(profile);
                    preferences.edit().putBoolean("sidebar_controller_enabled", true).apply();
                }
            }
            updateSidebarControllerLabel(inputControlsView.getProfile() != null
                    ? inputControlsView.getProfile() : getSidebarControllerProfile());
        });
        sidebar.findViewById(R.id.BTSidebarControllerMore).setOnClickListener(v ->
                showInputControlsDialog());

        sidebar.findViewById(R.id.BTSidebarFpsLimiter).setOnClickListener(v ->
                toggleSidebarFpsLimiter(renderer));
        sidebar.findViewById(R.id.BTSidebarFpsLimiterMore).setOnClickListener(v ->
                showSidebarFpsLimiter(renderer));
        sidebarFrameGenerationButton.setOnClickListener(v ->
                toggleSidebarFrameGeneration(renderer));
        sidebar.findViewById(R.id.BTSidebarDisplay).setOnClickListener(this::showSidebarDisplayMenu);

        sidebar.findViewById(R.id.BTSidebarHud).setOnClickListener(v -> toggleSidebarHud());
        sidebar.findViewById(R.id.BTSidebarHudMore).setOnClickListener(v -> showHUDConfigDialog());
        sidebar.findViewById(R.id.BTSidebarTaskManager).setOnClickListener(v -> openInlineTaskManager());
        sidebar.findViewById(R.id.BTCloseTaskManager).setOnClickListener(v -> closeInlineTaskManager(false));
        sidebar.findViewById(R.id.BTSidebarTerminal).setOnClickListener(v -> {
            openTerminal();
            drawerLayout.closeDrawers();
        });
        sidebar.findViewById(R.id.BTSidebarExit).setOnClickListener(v -> exitApp());
        sidebarPauseButton.setOnClickListener(v -> toggleSidebarPause());
        sidebar.findViewById(R.id.BTSidebarHelp).setOnClickListener(v -> showTouchpadHelpDialog());

        sidebarHandler.removeCallbacks(sidebarStatusRunnable);
        sidebarHandler.post(sidebarStatusRunnable);
    }

    /** Applies the app's dialog-button look (matches Cancel/OK) to a button. */
    private static void styleDialogButton(View button) {
        button.setBackgroundResource(R.drawable.button_neutral);
        if (button instanceof android.widget.Button) {
            android.widget.Button b = (android.widget.Button) button;
            b.setTextColor(0xFFFFFFFF);
            b.setAllCaps(false);
        }
    }

    private void showSidebarInputMenu(View anchor) {
        final ContentDialog dialog = new ContentDialog(this, R.layout.sidebar_input_dialog);
        dialog.setTitle(R.string.input_controls);
        dialog.setIcon(R.drawable.icon_settings);

        CheckBox cbRelative = dialog.findViewById(R.id.CBInputRelativeMouse);
        cbRelative.setChecked(isRelativeMouseMovement);

        styleDialogButton(dialog.findViewById(R.id.BTInputKeyboard));
        styleDialogButton(dialog.findViewById(R.id.BTInputInputControls));
        styleDialogButton(dialog.findViewById(R.id.BTInputControllers));
        styleDialogButton(dialog.findViewById(R.id.BTInputMotion));

        dialog.findViewById(R.id.BTInputKeyboard).setOnClickListener(v -> {
            dialog.dismiss();
            AppUtils.showKeyboard(this);
        });
        dialog.findViewById(R.id.BTInputInputControls).setOnClickListener(v -> {
            dialog.dismiss();
            showInputControlsDialog();
        });
        dialog.findViewById(R.id.BTInputControllers).setOnClickListener(v -> {
            dialog.dismiss();
            ControllerAssignmentDialog.show(this, winHandler);
            winHandler.clearIgnoredDevices();
        });
        dialog.findViewById(R.id.BTInputMotion).setOnClickListener(v -> {
            dialog.dismiss();
            MotionControls.getInstance(this).attach(winHandler).showContentDialog(this, null);
        });

        dialog.setOnConfirmCallback(() -> {
            if (cbRelative.isChecked() != isRelativeMouseMovement) {
                isRelativeMouseMovement = cbRelative.isChecked();
                container.setRelativeMouseMovement(isRelativeMouseMovement);
                xServer.setRelativeMouseMovement(isRelativeMouseMovement);
                if (!isRelativeMouseMovement) {
                    releasePointerCaptureIfNeeded("toggle-off");
                    touchpadView.setOnCapturedPointerListener(null);
                    Toast.makeText(this, R.string.relative_mouse_disabled, Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(this, R.string.relative_mouse_enabled, Toast.LENGTH_SHORT).show();
                }
            }
        });
        dialog.show();
    }

    private void showSidebarDisplayMenu(View anchor) {
        GLRenderer renderer = getXServerView().getRenderer();
        final ContentDialog dialog = new ContentDialog(this, R.layout.sidebar_display_dialog);
        dialog.setTitle(R.string.menu_section_display);
        dialog.setIcon(R.drawable.icon_settings);

        CheckBox cbHdr = dialog.findViewById(R.id.CBDisplayFakeHdr);
        cbHdr.setChecked(renderer.getEffectComposer().getEffect(HDREffect.class) != null);

        CheckBox cbFXAA = dialog.findViewById(R.id.CBDisplayFXAA);
        CheckBox cbCRT = dialog.findViewById(R.id.CBDisplayCRTShader);
        CheckBox cbToon = dialog.findViewById(R.id.CBDisplayToonShader);
        CheckBox cbNTSC = dialog.findViewById(R.id.CBDisplayNTSCEffect);
        if (cbFXAA != null) cbFXAA.setChecked(renderer.getEffectComposer().getEffect(com.winlator.cmod.renderer.effects.FXAAEffect.class) != null);
        if (cbCRT != null) cbCRT.setChecked(renderer.getEffectComposer().getEffect(com.winlator.cmod.renderer.effects.CRTEffect.class) != null);
        if (cbToon != null) cbToon.setChecked(renderer.getEffectComposer().getEffect(com.winlator.cmod.renderer.effects.ToonEffect.class) != null);
        if (cbNTSC != null) cbNTSC.setChecked(renderer.getEffectComposer().getEffect(com.winlator.cmod.renderer.effects.NTSCCombinedEffect.class) != null);

        Spinner sFilter = dialog.findViewById(R.id.SDisplayTextureFilter);
        sFilter.setAdapter(new ThemedSpinnerAdapter<>(this, Arrays.asList(
                getString(R.string.bilinear), getString(R.string.nearest_neighbor),
                "FSR", "None")));
        int filterMode = renderer.getTextureFilterMode();
        sFilter.setSelection(filterMode >= 0 && filterMode <= 3 ? filterMode : 0);

        Spinner sVsyncLimit = dialog.findViewById(R.id.SDisplayVsyncLimit);
        sVsyncLimit.setAdapter(new ThemedSpinnerAdapter<>(this,
                Arrays.asList(getResources().getStringArray(R.array.video_vsync_limit_entries))));
        String currentVsyncMode = resolveVsyncMode();
        sVsyncLimit.setSelection("off".equals(currentVsyncMode) ? 2
                : "50".equals(currentVsyncMode) ? 1 : 0);

        Spinner sFsrUpscale = dialog.findViewById(R.id.SDisplayFsrUpscale);
        sFsrUpscale.setAdapter(new ThemedSpinnerAdapter<>(this, Arrays.asList("Off", "On")));
        View llFsrUpscale = dialog.findViewById(R.id.LLDisplayFsrUpscale);

        Spinner sFsrMode = dialog.findViewById(R.id.SDisplayFsrMode);
        sFsrMode.setAdapter(new ThemedSpinnerAdapter<>(this, Arrays.asList(
                "Fidelity (1.3x)", "Quality (1.5x)", "Balanced (1.7x)",
                "Performance (2.0x)", "Ultra Performance (2.5x)")));
        View llFsrMode = dialog.findViewById(R.id.LLDisplayFsrMode);

        int modeIndex = 0;
        switch (fsrRuntimeState) {
            case "fidelity": modeIndex = 0; break;
            case "quality": modeIndex = 1; break;
            case "balanced": modeIndex = 2; break;
            case "performance": modeIndex = 3; break;
            case "ultraperformance": modeIndex = 4; break;
        }
        sFsrMode.setSelection(modeIndex);
        boolean upscaleOn = !"off".equals(fsrRuntimeState) && !"on".equals(fsrRuntimeState);
        sFsrUpscale.setSelection(upscaleOn ? 1 : 0);

        // Even locked, show upscaling state linked to shortcut/container data
        // and block texture filter change ONLY when upscaling is ON (not just sharpen-only "on")
        sFsrUpscale.setEnabled(false);
        sFsrUpscale.setClickable(false);
        sFsrUpscale.setAlpha(0.6f);
        sFsrMode.setEnabled(false);
        sFsrMode.setClickable(false);
        sFsrMode.setAlpha(0.6f);

        Runnable updateVisibility = () -> {
            boolean fsrSelected = sFilter.getSelectedItemPosition() == 2;
            // Upscaling is ON only when state is a quality mode (not "on" or "off")
            boolean upscaleOnInner = fsrSelected && !"off".equals(fsrRuntimeState) && !"on".equals(fsrRuntimeState);
            // Show upscaling state (linked to shortcut) even though locked
            llFsrUpscale.setVisibility(fsrSelected ? View.VISIBLE : View.GONE);
            llFsrMode.setVisibility(upscaleOnInner ? View.VISIBLE : View.GONE);
            // Block texture filter change ONLY when upscaling is ON (sharpen-only "on" allows filter change)
            boolean blockFilter = upscaleOnInner;
            sFilter.setEnabled(!blockFilter);
            sFilter.setClickable(!blockFilter);
            sFilter.setAlpha(blockFilter ? 0.6f : 1.0f);
        };
        updateVisibility.run();
        sFilter.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View v, int pos, long id) { updateVisibility.run(); }
            @Override public void onNothingSelected(AdapterView<?> p) {}
        });

        CheckBox cbFrameGeneration = dialog.findViewById(R.id.CBDisplayFrameGeneration);
        Spinner sFrameGenerationProfile = dialog.findViewById(
                R.id.SDisplayFrameGenerationProfile);
        Spinner sFrameGenerationMultiplier = dialog.findViewById(
                R.id.SDisplayFrameGenerationMultiplier);
        EditText etFrameGenerationTarget = dialog.findViewById(
                R.id.ETDisplayFrameGenerationAutoFPS);
        View llFrameGenerationAutoFps = dialog.findViewById(
                R.id.LLDisplayFrameGenerationAutoFPS);
        View llFrameGenerationSettings = dialog.findViewById(
                R.id.LLDisplayFrameGenerationSettings);
        sFrameGenerationProfile.setAdapter(new ThemedSpinnerAdapter<>(this,
                Arrays.asList(getResources().getStringArray(
                        R.array.frame_generation_profile_entries))));
        sFrameGenerationMultiplier.setAdapter(new ThemedSpinnerAdapter<>(this,
                Arrays.asList(getResources().getStringArray(
                        R.array.frame_generation_multiplier_entries))));
        int storedFrameGenerationProfile = parseFrameGenerationProfile(
                getRuntimeVideoOption("frameGenerationProfile", "balanced"));
        float storedFrameGenerationMultiplier = parseFrameGenerationMultiplier(
                getRuntimeVideoOption("frameGenerationMultiplier", "auto"));
        int storedFrameGenerationTarget = parseFrameGenerationTarget(
                getRuntimeVideoOption("frameGenerationTargetFPS", "60"));
        sFrameGenerationProfile.setSelection(storedFrameGenerationProfile);
        sFrameGenerationMultiplier.setSelection(
                frameGenerationMultiplierIndex(storedFrameGenerationMultiplier));
        etFrameGenerationTarget.setText(String.valueOf(storedFrameGenerationTarget));
        boolean frameGenerationCompatible = isFrameGenerationCompatible();
        boolean frameGenerationConfigured = "1".equals(
                getRuntimeVideoOption("frameGenerationEnabled", "0"));
        cbFrameGeneration.setChecked(frameGenerationConfigured && frameGenerationCompatible);
        cbFrameGeneration.setEnabled(frameGenerationCompatible);
        cbFrameGeneration.setAlpha(frameGenerationCompatible ? 1.0f : 0.55f);
        dialog.findViewById(R.id.BTDisplayFrameGenerationHelp).setOnClickListener(v ->
                AppUtils.showHelpBox(this, v, frameGenerationCompatible
                        ? R.string.frame_generation_help : R.string.frame_generation_vulkan_only));
        dialog.findViewById(R.id.BTDisplayFrameGenerationProfileHelp).setOnClickListener(v ->
                AppUtils.showHelpBox(this, v, R.string.frame_generation_profile_help));
        llFrameGenerationSettings.setVisibility(
                cbFrameGeneration.isChecked() ? View.VISIBLE : View.GONE);
        cbFrameGeneration.setOnCheckedChangeListener((button, checked) ->
            llFrameGenerationSettings.setVisibility(checked ? View.VISIBLE : View.GONE));
        llFrameGenerationAutoFps.setVisibility(
                sFrameGenerationMultiplier.getSelectedItemPosition() == 0
                        ? View.VISIBLE : View.GONE);
        sFrameGenerationMultiplier.setOnItemSelectedListener(
                new AdapterView.OnItemSelectedListener() {
                    @Override public void onItemSelected(AdapterView<?> parent, View view,
                            int position, long id) {
                        llFrameGenerationAutoFps.setVisibility(position == 0
                                ? View.VISIBLE : View.GONE);
                    }
                    @Override public void onNothingSelected(AdapterView<?> parent) {}
                });

        // vkBasalt in sidebar Display as well (now also in Video tab)
        Spinner sVkEffect = dialog.findViewById(R.id.SDisplayVkBasaltEffect);
        SeekBar sbLevel = dialog.findViewById(R.id.SBDisplaySharpnessLevel);
        SeekBar sbDenoise = dialog.findViewById(R.id.SBDisplaySharpnessDenoise);
        TextView tvLevel = dialog.findViewById(R.id.TVDisplaySharpnessLevel);
        TextView tvDenoise = dialog.findViewById(R.id.TVDisplaySharpnessDenoise);
        if (sVkEffect != null) {
            sVkEffect.setAdapter(new ThemedSpinnerAdapter<>(this, Arrays.asList(getResources().getStringArray(R.array.vkbasalt_sharpness_entries))));
            String curEff = shortcut != null ? shortcut.getExtra("sharpnessEffect", container.getExtra("sharpnessEffect", "None")) : container.getExtra("sharpnessEffect", "None");
            AppUtils.setSpinnerSelectionFromValue(sVkEffect, curEff);
            String curLev = shortcut != null ? shortcut.getExtra("sharpnessLevel", container.getExtra("sharpnessLevel", "100")) : container.getExtra("sharpnessLevel", "100");
            String curDen = shortcut != null ? shortcut.getExtra("sharpnessDenoise", container.getExtra("sharpnessDenoise", "100")) : container.getExtra("sharpnessDenoise", "100");
            int lev = 100, den = 100;
            try { lev = Integer.parseInt(curLev); } catch (Exception ignored) {}
            try { den = Integer.parseInt(curDen); } catch (Exception ignored) {}
            if (sbLevel != null) { sbLevel.setProgress(Math.max(0, Math.min(100, lev))); }
            if (sbDenoise != null) { sbDenoise.setProgress(Math.max(0, Math.min(100, den))); }
            if (tvLevel != null) tvLevel.setText(lev + "%");
            if (tvDenoise != null) tvDenoise.setText(den + "%");
            if (sbLevel != null) sbLevel.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar s, int p, boolean f) { if (tvLevel != null) tvLevel.setText(p + "%"); }
                @Override public void onStartTrackingTouch(SeekBar s) {}
                @Override public void onStopTrackingTouch(SeekBar s) {}
            });
            if (sbDenoise != null) sbDenoise.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar s, int p, boolean f) { if (tvDenoise != null) tvDenoise.setText(p + "%"); }
                @Override public void onStartTrackingTouch(SeekBar s) {}
                @Override public void onStopTrackingTouch(SeekBar s) {}
            });
        }

        // HDR config button
        Button btnHdrConfig = dialog.findViewById(R.id.BTDisplayHdrConfig);
        if (btnHdrConfig != null) {
            btnHdrConfig.setOnClickListener(v -> {
                dialog.dismiss();
                openHdrConfigDialog();
            });
        }

        CheckBox cbSwapRB = dialog.findViewById(R.id.CBDisplaySwapRB);
        cbSwapRB.setChecked(renderer.isSwapRedBlue());

        styleDialogButton(dialog.findViewById(R.id.BTDisplayScreenEffects));
        styleDialogButton(dialog.findViewById(R.id.BTDisplayFullscreen));
        styleDialogButton(dialog.findViewById(R.id.BTDisplayMagnifier));
        styleDialogButton(dialog.findViewById(R.id.BTDisplayPip));

        dialog.findViewById(R.id.BTDisplayScreenEffects).setOnClickListener(v -> {
            dialog.dismiss();
            openScreenEffectsDialog();
        });
        dialog.findViewById(R.id.BTDisplayFullscreen).setOnClickListener(v -> {
            dialog.dismiss();
            renderer.toggleFullscreen();
            touchpadView.toggleFullscreen();
        });
        dialog.findViewById(R.id.BTDisplayMagnifier).setOnClickListener(v -> {
            dialog.dismiss();
            toggleMagnifier();
        });
        dialog.findViewById(R.id.BTDisplayPip).setOnClickListener(v -> {
            dialog.dismiss();
            enterPictureInPictureMode();
        });

        dialog.setOnConfirmCallback(() -> {
            setFakeHdrEnabled(renderer, cbHdr.isChecked(), true);
            int frameGenerationProfile = Math.max(0,
                    Math.min(2, sFrameGenerationProfile.getSelectedItemPosition()));
            float frameGenerationMultiplier = frameGenerationMultiplierValue(
                    sFrameGenerationMultiplier.getSelectedItemPosition());
            int frameGenerationTarget = parseFrameGenerationTarget(
                    etFrameGenerationTarget.getText().toString().trim());
            applyFrameGenerationSetting(renderer,
                    cbFrameGeneration.isChecked() && frameGenerationCompatible,
                    frameGenerationProfile, frameGenerationMultiplier,
                    frameGenerationTarget, true);
            // FXAA/CRT/Toon/NTSC now in sidebar below Fake HDR (moved from ScreenEffectDialog)
            if (cbFXAA != null) {
                boolean on = cbFXAA.isChecked();
                com.winlator.cmod.renderer.effects.FXAAEffect fxaa = renderer.getEffectComposer().getEffect(com.winlator.cmod.renderer.effects.FXAAEffect.class);
                if (on && fxaa == null) renderer.getEffectComposer().addEffect(new com.winlator.cmod.renderer.effects.FXAAEffect());
                else if (!on && fxaa != null) renderer.getEffectComposer().removeEffect(fxaa);
            }
            if (cbCRT != null) {
                boolean on = cbCRT.isChecked();
                com.winlator.cmod.renderer.effects.CRTEffect crt = renderer.getEffectComposer().getEffect(com.winlator.cmod.renderer.effects.CRTEffect.class);
                if (on && crt == null) renderer.getEffectComposer().addEffect(new com.winlator.cmod.renderer.effects.CRTEffect());
                else if (!on && crt != null) renderer.getEffectComposer().removeEffect(crt);
            }
            if (cbToon != null) {
                boolean on = cbToon.isChecked();
                com.winlator.cmod.renderer.effects.ToonEffect toon = renderer.getEffectComposer().getEffect(com.winlator.cmod.renderer.effects.ToonEffect.class);
                if (on && toon == null) renderer.getEffectComposer().addEffect(new com.winlator.cmod.renderer.effects.ToonEffect());
                else if (!on && toon != null) renderer.getEffectComposer().removeEffect(toon);
            }
            if (cbNTSC != null) {
                boolean on = cbNTSC.isChecked();
                com.winlator.cmod.renderer.effects.NTSCCombinedEffect ntsc = renderer.getEffectComposer().getEffect(com.winlator.cmod.renderer.effects.NTSCCombinedEffect.class);
                if (on && ntsc == null) renderer.getEffectComposer().addEffect(new com.winlator.cmod.renderer.effects.NTSCCombinedEffect());
                else if (!on && ntsc != null) renderer.getEffectComposer().removeEffect(ntsc);
            }
            // Texture filter now enabled (FSR as anti-aliasing only, no upscaling in sidebar)
            int filterSelection = sFilter.getSelectedItemPosition();
            renderer.setTextureFilterMode(Math.max(0, Math.min(filterSelection, 3)));
            persistRuntimeVideoOption("rendererFilterMode", String.valueOf(filterSelection));
            if (filterSelection == 2) {
                if ("off".equals(fsrRuntimeState)) applyFsrRuntime(renderer, "on");
            } else {
                applyFsrRuntime(renderer, "off");
            }
            // vkBasalt in sidebar Display as well (mirrors Video tab)
            if (sVkEffect != null && sbLevel != null && sbDenoise != null) {
                String eff = (String) sVkEffect.getSelectedItem();
                String lev = String.valueOf(sbLevel.getProgress());
                String den = String.valueOf(sbDenoise.getProgress());
                if (shortcut != null) {
                    String cEff = container.getExtra("sharpnessEffect", "None");
                    String cLev = container.getExtra("sharpnessLevel", "100");
                    String cDen = container.getExtra("sharpnessDenoise", "100");
                    shortcut.putExtra("sharpnessEffect", !eff.equals(cEff) ? eff : null);
                    shortcut.putExtra("sharpnessLevel", !lev.equals(cLev) ? lev : null);
                    shortcut.putExtra("sharpnessDenoise", !den.equals(cDen) ? den : null);
                    shortcut.saveData();
                } else {
                    container.putExtra("sharpnessEffect", eff);
                    container.putExtra("sharpnessLevel", lev);
                    container.putExtra("sharpnessDenoise", den);
                    container.saveData();
                }
                double l = 100, d = 100;
                try { l = Double.parseDouble(lev); } catch (Exception ignored) {}
                try { d = Double.parseDouble(den); } catch (Exception ignored) {}
                if (!"None".equals(eff)) {
                    vkbasaltConfig = "effects=" + eff.toLowerCase(Locale.ENGLISH) + ";" + "casSharpness=" + l/100 + ";" + "dlsSharpness=" + l/100 + ";" + "dlsDenoise=" + d/100 + ";" + "enableOnLaunch=True";
                } else {
                    vkbasaltConfig = "";
                }
            }
            renderer.setSwapRedBlue(cbSwapRB.isChecked());
            persistRuntimeVideoOption("rendererSwapRB", cbSwapRB.isChecked() ? "1" : "0");
            String vsyncMode = sVsyncLimit.getSelectedItemPosition() == 2 ? "off"
                    : sVsyncLimit.getSelectedItemPosition() == 1 ? "50" : "100";
            persistRuntimeVideoOption("vsyncMode", vsyncMode);
            setFpsLimit(renderer, renderer.getFpsLimit(), false);
        });
        dialog.show();
    }

    private void openScreenEffectsDialog() {
        ScreenEffectDialog dlg = new ScreenEffectDialog(this);
        dlg.setOnConfirmCallback(() -> {
            GLRenderer r = xServerView.getRenderer();
            ColorEffect color = r.getEffectComposer().getEffect(ColorEffect.class);
            dlg.applyEffects(color, r);
        });
        dlg.show();
    }

    private void openHdrConfigDialog() {
        final ContentDialog dialog = new ContentDialog(this, R.layout.hdr_config_dialog);
        dialog.setTitle(R.string.fake_hdr);
        dialog.setIcon(R.drawable.icon_settings);

        // HDR configuration controls
        SeekBar sbBrightness = dialog.findViewById(R.id.SBHdrBrightness);
        SeekBar sbContrast = dialog.findViewById(R.id.SBHdrContrast);
        SeekBar sbGamma = dialog.findViewById(R.id.SBHdrGamma);
        SeekBar sbSaturation = dialog.findViewById(R.id.SBHdrSaturation);
        TextView tvBrightness = dialog.findViewById(R.id.TVHdrBrightness);
        TextView tvContrast = dialog.findViewById(R.id.TVHdrContrast);
        TextView tvGamma = dialog.findViewById(R.id.TVHdrGamma);
        TextView tvSaturation = dialog.findViewById(R.id.TVHdrSaturation);

        // Load current settings from ColorEffect if exists
        GLRenderer renderer = xServerView.getRenderer();
        com.winlator.cmod.renderer.effects.ColorEffect colorEffect = 
                renderer.getEffectComposer().getEffect(com.winlator.cmod.renderer.effects.ColorEffect.class);
        float brightness = 0, contrast = 0, gamma = 1.0f, saturation = 1.0f;
        if (colorEffect != null) {
            brightness = colorEffect.getBrightness() * 100;
            contrast = colorEffect.getContrast() * 100;
            gamma = colorEffect.getGamma();
            saturation = colorEffect.getSaturation();
        }
        sbBrightness.setProgress((int)Math.round(brightness));
        sbContrast.setProgress((int)Math.round(contrast));
        sbGamma.setProgress((int)Math.round((gamma - 0.5f) * 100 / 2.0f));
        sbSaturation.setProgress((int)Math.round((saturation - 0.5f) * 100 / 1.5f));
        tvBrightness.setText((int)Math.round(brightness) + "%");
        tvContrast.setText((int)Math.round(contrast) + "%");
        tvGamma.setText(String.format(Locale.US, "%.2f", gamma));
        tvSaturation.setText(String.format(Locale.US, "%.2f", saturation));

        sbBrightness.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean f) { if (f) tvBrightness.setText(p + "%"); }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });
        sbContrast.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean f) { if (f) tvContrast.setText(p + "%"); }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });
        sbGamma.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean f) {
                if (f) tvGamma.setText(String.format(Locale.US, "%.2f", 0.5f + p / 100f * 2.0f));
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });
        sbSaturation.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean f) {
                if (f) tvSaturation.setText(String.format(Locale.US, "%.2f", 0.5f + p / 100f * 1.5f));
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });

        dialog.setOnConfirmCallback(() -> {
            GLRenderer r = xServerView.getRenderer();
            com.winlator.cmod.renderer.effects.ColorEffect ce = 
                    r.getEffectComposer().getEffect(com.winlator.cmod.renderer.effects.ColorEffect.class);
            float b = sbBrightness.getProgress() / 100f;
            float c = sbContrast.getProgress() / 100f;
            float g = 0.5f + sbGamma.getProgress() / 100f * 2.0f;
            float s = 0.5f + sbSaturation.getProgress() / 100f * 1.5f;
            if (ce == null && (b != 0 || c != 0 || g != 1.0f || s != 1.0f)) {
                ce = new com.winlator.cmod.renderer.effects.ColorEffect();
                r.getEffectComposer().addEffect(ce);
            }
            if (ce != null) {
                ce.setBrightness(b);
                ce.setContrast(c);
                ce.setGamma(g);
                ce.setSaturation(s);
                if (b == 0 && c == 0 && g == 1.0f && s == 1.0f) {
                    r.getEffectComposer().removeEffect(ce);
                }
            }
            r.xServerView.requestRender();
        });
        
        Button btnReset = dialog.findViewById(R.id.BTHdrReset);
        if (btnReset != null) {
            btnReset.setOnClickListener(v -> {
                sbBrightness.setProgress(0);
                sbContrast.setProgress(0);
                sbGamma.setProgress(25);
                sbSaturation.setProgress(25);
                tvBrightness.setText("0%");
                tvContrast.setText("0%");
                tvGamma.setText("1.00");
                tvSaturation.setText("1.00");
            });
        }
        dialog.show();
    }

    private void toggleMagnifier() {
        GLRenderer renderer = getXServerView().getRenderer();
        if (magnifierView == null) {
            FrameLayout container = findViewById(R.id.FLXServerDisplay);
            magnifierView = new MagnifierView(this);
            magnifierView.setZoomButtonCallback(value -> {
                renderer.setMagnifierZoom(Mathf.clamp(renderer.getMagnifierZoom() + value, 1.0f, 3.0f));
                magnifierView.setZoomValue(renderer.getMagnifierZoom());
            });
            magnifierView.setZoomValue(renderer.getMagnifierZoom());
            magnifierView.setHideButtonCallback(() -> {
                container.removeView(magnifierView);
                magnifierView = null;
            });
            container.addView(magnifierView);
        }
    }

    private void setFakeHdrEnabled(GLRenderer renderer, boolean enabled, boolean persist) {
        HDREffect current = renderer.getEffectComposer().getEffect(HDREffect.class);
        if (enabled && current == null) renderer.getEffectComposer().addEffect(new HDREffect());
        else if (!enabled && current != null) renderer.getEffectComposer().removeEffect(current);
        if (persist) persistRuntimeVideoOption("fakeHDR", enabled ? "1" : "0");
    }

    /** Current FSR state: "off", "on" (sharpen only) or quality/balanced/performance. */
    private String fsrRuntimeState = "off";
    /** Guest (X screen) resolution chosen at session start when an FSR
     * upscale preset is active: real render scaling. 0 = not scaled. */
    private int fsrGuestWidth;
    private int fsrGuestHeight;
    /** Original display resolution before FSR guest scaling (for live resize). */
    private int originalScreenWidth;
    private int originalScreenHeight;
    /** Upscale preset active at session start, so live preset changes can
     * tell the user that a different render scale needs a restart. */
    private String sessionStartFsrMode;

    /**
     * Resolves the effective FSR state from extras and driver config,
     * including the legacy fsrMode migration shared with setupUI.
     * Returns "off", "on" (sharpen only) or a quality token.
     */
    private String resolveFsrState() {
        boolean fsrOn = resolveTextureFilterMode() == 2;
        String upscaleExtra = shortcut != null
                ? shortcut.getExtra("fsrUpscale", container.getExtra("fsrUpscale", null))
                : container.getExtra("fsrUpscale", null);
        String qualityExtra = shortcut != null
                ? shortcut.getExtra("fsrQuality", container.getExtra("fsrQuality", null))
                : container.getExtra("fsrQuality", null);
        // Legacy migration: old builds stored an explicit fsrMode key (driver
        // config or shortcut extra) instead of the texture filter selection.
        // Carry it over, but only while the filter was never changed away from
        // FSR: the new UI persists its selection as the rendererFilterMode
        // extra, whose presence means fsrMode must no longer override it.
        String legacyFsr = GraphicsDriverConfigDialog.normalizeFsrValue(
                shortcut != null
                        ? shortcut.getExtra("fsrMode", graphicsDriverConfig.getOrDefault("fsrMode", "off"))
                        : graphicsDriverConfig.getOrDefault("fsrMode", "off"));
        boolean filterNeverPersisted = !(shortcut != null
                ? shortcut.hasExtra("rendererFilterMode") || container.hasExtra("rendererFilterMode")
                : container.hasExtra("rendererFilterMode"));
        if (filterNeverPersisted && !legacyFsr.equals("off")) {
            fsrOn = true;
            if (!legacyFsr.equals("on")) {
                // A legacy quality mode implies EASU upscale at that mode,
                // mirroring the VideoConfigDialog migration; "on" maps to
                // sharpen-only (fsrUpscale falls back to "0" below).
                if (upscaleExtra == null) upscaleExtra = "0";
                if (qualityExtra == null) qualityExtra = legacyFsr;
            }
        }
        if (!fsrOn) return "off";
        // FSR upscaling is opt-in; an unset value keeps native resolution.
        boolean fsrUpscale = !"0".equals(
                upscaleExtra != null ? upscaleExtra
                        : graphicsDriverConfig.getOrDefault("fsrUpscale", "0"));
        if (!fsrUpscale) return "on";
        return GraphicsDriverConfigDialog.normalizeFsrValue(
                qualityExtra != null ? qualityExtra
                        : graphicsDriverConfig.getOrDefault("fsrQuality", "balanced"));
    }

    private int resolveTextureFilterMode() {
        try {
            String value = shortcut != null
                    ? shortcut.getExtra("rendererFilterMode", container.getExtra("rendererFilterMode", "0"))
                    : container.getExtra("rendererFilterMode", "0");
            return Math.max(0, Math.min(Integer.parseInt(value), 3));
        }
        catch (Exception e) {
            return 0;
        }
    }

    /**
     * Applies an FSR1 state live: "off", "on" (RCAS sharpening at native
     * resolution) or fidelity/quality/balanced/performance/ultraperformance
     * (EASU upscale at display/1.3, /1.5, /1.7, /2.0 or /2.5 plus RCAS).
     * Keeps the texture filter selection in sync and persists the choice.
     */
    /**
     * Applies an FSR1 state live: "off", "on" (RCAS sharpening at native
     * resolution) or fidelity/quality/balanced/performance/ultraperformance
     * (EASU upscale at display/1.3, /1.5, /1.7, /2.0 or /2.5 plus RCAS).
     * Keeps the texture filter selection in sync and persists the choice.
     * FIX: live mode now actually resizes the X desktop (xServer) so RE2 /
     * other GPU-bound games see a smaller swapchain and gain FPS without
     * requiring a full container restart.
     */
    private void applyFsrRuntime(GLRenderer renderer, String state) {
        fsrRuntimeState = state == null ? "off" : state;
        EffectComposer composer = renderer.getEffectComposer();
        if ("on".equals(fsrRuntimeState)) {
            renderer.setTextureFilterMode(2);
            // Sharpen only: restore native desktop if we were in guest mode.
            if (originalScreenWidth > 0 && originalScreenHeight > 0 && xServer != null
                    && (xServer.screenInfo.width != originalScreenWidth || xServer.screenInfo.height != originalScreenHeight)) {
                try {
                    xServer.updateScreenSize(originalScreenWidth, originalScreenHeight);
                    fsrGuestWidth = 0; fsrGuestHeight = 0;
                    Log.i("FSRDebug", "FSR sharpen-only: restored native " + originalScreenWidth + "x" + originalScreenHeight);
                } catch (Exception e) { Log.w("FSRDebug", "Failed to restore native for sharpen-only", e); }
            }
            composer.setFsrEffects(null, new FSREffect(1.0f), 0, 0);
            Log.i("FSRDebug", "FSR1 sharpening only (no upscale)");
        } else if (!"off".equals(fsrRuntimeState)) {
            float stops = GraphicsDriverConfigDialog.fsrStopsForMode(fsrRuntimeState);
            renderer.setTextureFilterMode(2);
            float factor = GraphicsDriverConfigDialog.fsrFactorForMode(fsrRuntimeState);
            int targetW = 0, targetH = 0;
            if (originalScreenWidth > 0 && originalScreenHeight > 0) {
                targetW = Math.max(64, (Math.round(originalScreenWidth / factor)) & ~1);
                targetH = Math.max(64, (Math.round(originalScreenHeight / factor)) & ~1);
            } else if (fsrGuestWidth > 0 && fsrGuestHeight > 0) {
                // Fallback when original not known (should not happen).
                targetW = fsrGuestWidth; targetH = fsrGuestHeight;
            }
            if (targetW > 0 && targetH > 0 && originalScreenWidth > 0) {
                // Live desktop resize for true FPS gain (RE2 heavy 3D).
                if (xServer != null && (xServer.screenInfo.width != targetW || xServer.screenInfo.height != targetH)) {
                    try {
                        xServer.updateScreenSize(targetW, targetH);
                        Log.i("FSRDebug", "FSR live resize: " + originalScreenWidth + "x" + originalScreenHeight + " -> " + targetW + "x" + targetH + " factor=" + factor);
                    } catch (Exception e) { Log.w("FSRDebug", "Live resize failed", e); }
                }
                fsrGuestWidth = targetW; fsrGuestHeight = targetH;
                composer.setFsrEffects(new FSREasuEffect(), new FSREffect(stops), targetW, targetH);
                Log.i("FSRDebug", "FSR1 upscale (guest render scale LIVE): state=" + fsrRuntimeState + " guest=" + targetW + "x" + targetH + " stops=" + stops);
            } else if (fsrGuestWidth > 0 && fsrGuestHeight > 0) {
                // Legacy path: guest known from startup, keep 1:1.
                composer.setFsrEffects(new FSREasuEffect(), new FSREffect(stops), fsrGuestWidth, fsrGuestHeight);
                Log.i("FSRDebug", "FSR1 upscale (guest render scale): state=" + fsrRuntimeState + " guest=" + fsrGuestWidth + "x" + fsrGuestHeight + " stops=" + stops);
            } else {
                composer.setFsrEffects(new FSREasuEffect(), new FSREffect(stops), factor);
                Log.i("FSRDebug", "FSR1 upscale active: state=" + fsrRuntimeState + " factor=" + factor + " stops=" + stops);
            }
        } else {
            // FSR off: restore native desktop for full-res rendering.
            if (originalScreenWidth > 0 && originalScreenHeight > 0 && xServer != null
                    && (xServer.screenInfo.width != originalScreenWidth || xServer.screenInfo.height != originalScreenHeight)) {
                try {
                    xServer.updateScreenSize(originalScreenWidth, originalScreenHeight);
                    Log.i("FSRDebug", "FSR off: restored native " + originalScreenWidth + "x" + originalScreenHeight);
                } catch (Exception e) { Log.w("FSRDebug", "Failed to restore native on FSR off", e); }
            }
            fsrGuestWidth = 0; fsrGuestHeight = 0;
            composer.setFsrEffects(null, null, 0, 0);
            Log.i("FSRDebug", "FSR off");
        }
    }

    private void persistRuntimeVideoOption(String key, String value) {
        if (shortcut != null) {
            shortcut.putExtra(key, value);
            shortcut.saveData();
        }
        else if (container != null) {
            container.putExtra(key, value);
            container.saveData();
        }
    }

    /** Last non-zero limit chosen in the sidebar. The tile can therefore be
     * toggled without losing the user's configured value. */
    private int getConfiguredFpsLimit() {
        int current = getStoredFpsLimit();
        String fallback = String.valueOf(current > 0 ? current : 60);
        try {
            int value = Integer.parseInt(getRuntimeVideoOption("fpsLimitValue", fallback));
            return Math.max(15, Math.min(240, value));
        }
        catch (Exception ignored) {
            return 60;
        }
    }

    private String getRuntimeVideoOption(String key, String fallback) {
        if (shortcut != null) {
            return shortcut.getExtra(key,
                    container != null ? container.getExtra(key, fallback) : fallback);
        }
        return container != null ? container.getExtra(key, fallback) : fallback;
    }

    private String resolveWineRenderer() {
        String configured = shortcut != null
                ? shortcut.getExtra("renderer", container.getExtra("renderer", ""))
                : container.getExtra("renderer", "");
        if (configured == null || configured.trim().isEmpty()) {
            File userRegistry = new File(container.getRootDir(), ".wine/user.reg");
            try (WineRegistryEditor editor = new WineRegistryEditor(userRegistry)) {
                editor.setCreateKeyIfNotExist(false);
                configured = editor.getStringValue("Software\\Wine\\Direct3D",
                        "renderer", "vulkan");
            }
            catch (Throwable error) {
                Log.w("XServerDisplayActivity", "Unable to read Wine renderer", error);
                configured = "vulkan";
            }
        }
        String normalized = StringUtils.parseIdentifier(configured);
        if ("opengl".equals(normalized)) normalized = "gl";
        if (!"gl".equals(normalized) && !"gdi".equals(normalized)
                && !"vulkan".equals(normalized)) normalized = "vulkan";
        return normalized;
    }

    private boolean isFrameGenerationCompatible() {
        return "vulkan".equals(wineRenderer);
    }

    private static int parseFrameGenerationProfile(String value) {
        if (value == null) return 1;
        switch (value.trim().toLowerCase(Locale.US)) {
            case "fast": return 0;
            case "balanced": return 1;
            case "quality":
            case "stable": return 2;
            case "ultra":
            case "ultra_quality": return 2;
        }
        try {
            // Migration from the former six-profile list:
            // Fast/Smooth -> Fast, Balanced/Enhanced -> Balanced,
            // Clear/Extreme -> Quality.
            int legacy = Integer.parseInt(value);
            if (legacy <= 1) return 0;
            if (legacy <= 3) return 1;
            return 2;
        }
        catch (Exception ignored) {
            return 1;
        }
    }

    private static String frameGenerationProfileStorageValue(int profile) {
        switch (Math.max(0, Math.min(2, profile))) {
            case 0: return "fast";
            case 2: return "quality";
            default: return "balanced";
        }
    }

    private static int parseFrameGenerationTarget(String value) {
        try {
            return Math.max(15, Math.min(240, Integer.parseInt(value)));
        }
        catch (Exception ignored) {
            return 60;
        }
    }

    private static float parseFrameGenerationMultiplier(String value) {
        if (value == null || "auto".equalsIgnoreCase(value)) return 0.0f;
        try {
            float parsed = Float.parseFloat(value);
            if (parsed < 1.5f) return 0.0f;
            return Math.min(5.0f, Math.round(parsed * 2.0f) / 2.0f);
        }
        catch (Exception ignored) {
            return 0.0f;
        }
    }

    private static int parseFrameGenerationBackend(String value) {
        return com.winlator.cmod.renderer.lsfg.LSFGManager.BACKEND_GLES;
    }

    private static int frameGenerationQuality(int profile) {
        return Math.max(0, Math.min(2, profile));
    }

    private static float frameGenerationStability(int profile) {
        switch (profile) {
            case 0: return 0.15f; // Fast: aggressive warp, minimal fallback.
            case 2: return 0.90f; // Quality: conservative fallback, less deformation.
            default: return 0.55f; // Balanced.
        }
    }

    private static int frameGenerationMultiplierIndex(float multiplier) {
        if (multiplier < 1.5f) return 0;
        return Math.max(1, Math.min(8, Math.round((multiplier - 1.0f) * 2.0f)));
    }

    private static float frameGenerationMultiplierValue(int index) {
        if (index <= 0) return 0.0f;
        return 1.0f + Math.max(1, Math.min(8, index)) * 0.5f;
    }

    private static String frameGenerationMultiplierStorageValue(float multiplier) {
        if (multiplier < 1.5f) return "auto";
        return multiplier == Math.round(multiplier)
                ? String.valueOf(Math.round(multiplier))
                : String.format(Locale.US, "%.1f", multiplier);
    }

    private void applyFrameGenerationSetting(GLRenderer renderer, boolean enabled,
            int profile, float multiplier, int targetFPS, boolean persist) {
        profile = Math.max(0, Math.min(2, profile));
        multiplier = parseFrameGenerationMultiplier(
                frameGenerationMultiplierStorageValue(multiplier));
        targetFPS = parseFrameGenerationTarget(String.valueOf(targetFPS));
        boolean safeEnabled = enabled && isFrameGenerationCompatible();
        int backend = parseFrameGenerationBackend(
                getRuntimeVideoOption("frameGenerationBackend", "gles"));
        boolean lowLatency = false;
        renderer.setApex(safeEnabled, frameGenerationQuality(profile), multiplier, targetFPS,
                frameGenerationStability(profile), backend, lowLatency);
        if (persist) {
            persistRuntimeVideoOption("frameGenerationEnabled", safeEnabled ? "1" : "0");
            persistRuntimeVideoOption("frameGenerationProfile",
                    frameGenerationProfileStorageValue(profile));
            persistRuntimeVideoOption("frameGenerationMultiplier",
                    frameGenerationMultiplierStorageValue(multiplier));
            persistRuntimeVideoOption("frameGenerationTargetFPS", String.valueOf(targetFPS));
        }
        updateSidebarFrameGenerationState(renderer);

        if (safeEnabled && sidebarFrameGenerationView != null) {
            sidebarFrameGenerationView.postDelayed(() -> {
                boolean active = renderer.isApexEnabled();
                updateSidebarFrameGenerationState(renderer);
                if (!active) {
                    Toast.makeText(this, R.string.frame_generation_unavailable,
                            Toast.LENGTH_LONG).show();
                }
            }, 1000);
        }
    }

    private void toggleSidebarFrameGeneration(GLRenderer renderer) {
        boolean configured = "1".equals(
                getRuntimeVideoOption("frameGenerationEnabled", "0"))
                && isFrameGenerationCompatible();
        if (!configured) {
            Toast.makeText(this, isFrameGenerationCompatible()
                            ? R.string.frame_generation_configure_first
                            : R.string.frame_generation_vulkan_only,
                    Toast.LENGTH_LONG).show();
            updateSidebarFrameGenerationState(renderer);
            return;
        }
        // The requested state changes synchronously; the active state changes
        // later on the GL thread.  Reading the latter here could invert a click
        // made while the initial enable request was still queued.
        boolean enable = !renderer.isApexRequestedEnabled();
        int profile = parseFrameGenerationProfile(
                getRuntimeVideoOption("frameGenerationProfile", "balanced"));
        float multiplier = parseFrameGenerationMultiplier(
                getRuntimeVideoOption("frameGenerationMultiplier", "auto"));
        int targetFPS = parseFrameGenerationTarget(
                getRuntimeVideoOption("frameGenerationTargetFPS", "60"));
        applyFrameGenerationSetting(renderer, enable, profile, multiplier, targetFPS, false);
    }

    private void updateSidebarFrameGenerationState(GLRenderer renderer) {
        boolean configured = "1".equals(
                getRuntimeVideoOption("frameGenerationEnabled", "0"))
                && isFrameGenerationCompatible();
        if (sidebarFrameGenerationButton != null) {
            sidebarFrameGenerationButton.setEnabled(configured);
            sidebarFrameGenerationButton.setClickable(configured);
            sidebarFrameGenerationButton.setAlpha(configured ? 1.0f : 0.45f);
        }
        if (sidebarFrameGenerationView != null) {
            sidebarFrameGenerationView.setText(!configured
                    ? R.string.frame_generation_locked
                    : renderer.isApexRequestedEnabled() ? R.string.on : R.string.off);
        }
    }

    private void showSidebarFpsLimiter(GLRenderer renderer) {
        final int[] values = {24, 30, 45, 60, 75, 90, 120};
        final String[] labels = {
                "24 FPS", "30 FPS", "45 FPS", "60 FPS",
                "75 FPS", "90 FPS", "120 FPS", "Custom…"
        };
        int configured = getConfiguredFpsLimit();
        int checked = labels.length - 1;
        for (int index = 0; index < values.length; index++) {
            if (values[index] == configured) {
                checked = index;
                break;
            }
        }
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("FPS Limiter")
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    dialog.dismiss();
                    if (which < values.length) {
                        applySidebarFpsLimitChoice(renderer, values[which]);
                    }
                    else {
                        showCustomSidebarFpsLimiter(renderer);
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void showCustomSidebarFpsLimiter(GLRenderer renderer) {
        EditText input = new EditText(this);
        input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        input.setSelectAllOnFocus(true);
        input.setText(String.valueOf(getConfiguredFpsLimit()));
        int sidePadding = Math.round(24 * getResources().getDisplayMetrics().density);
        LinearLayout holder = new LinearLayout(this);
        holder.setPadding(sidePadding, 0, sidePadding, 0);
        holder.addView(input, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        androidx.appcompat.app.AlertDialog dialog =
                new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("FPS Limiter")
                .setMessage("Choose a limit from 15 to 240 FPS. The main tile turns it on or off.")
                .setView(holder)
                .setPositiveButton(R.string.ok, null)
                .setNegativeButton(R.string.cancel, null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(
                androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            try {
                int configured = Math.max(15, Math.min(240,
                        Integer.parseInt(input.getText().toString().trim())));
                applySidebarFpsLimitChoice(renderer, configured);
                dialog.dismiss();
            }
            catch (Exception invalid) {
                input.setError("15-240");
            }
        }));
        dialog.show();
    }

    private void applySidebarFpsLimitChoice(GLRenderer renderer, int configured) {
        persistRuntimeVideoOption("fpsLimitValue", String.valueOf(configured));
        if (renderer.getFpsLimit() > 0) setFpsLimit(renderer, configured, true);
        updateSidebarFpsLabel(renderer.getFpsLimit());
    }

    private void toggleSidebarFpsLimiter(GLRenderer renderer) {
        int next = renderer.getFpsLimit() > 0 ? 0 : getConfiguredFpsLimit();
        setFpsLimit(renderer, next, true);
        updateSidebarFpsLabel(next);
    }

    private void updateSidebarFpsLabel(int limit) {
        if (sidebarFpsLimitView != null)
            sidebarFpsLimitView.setText(limit > 0 ? limit + " FPS" : "Off");
    }

    private String getRuntimeHudMode() {
        String containerMode = container != null ? container.getHudMode() : "winlator";
        return shortcut != null ? shortcut.getExtra("hudMode", containerMode) : containerMode;
    }

    private ControlsProfile getSidebarControllerProfile() {
        ArrayList<ControlsProfile> profiles = inputControlsManager.getProfiles(true);
        int preferredId = preferences.getInt("sidebar_controller_profile_id", -1);
        ControlsProfile fallback = null;
        ControlsProfile legacyFallback = null;
        for (ControlsProfile profile : profiles) {
            if (profile.id == preferredId) return profile;
            if ("Virtual Gamepad 2".equalsIgnoreCase(profile.getName())) fallback = profile;
            else if ("Virtual Gamepad".equalsIgnoreCase(profile.getName())) legacyFallback = profile;
        }
        if (fallback != null) return fallback;
        if (legacyFallback != null) return legacyFallback;
        return profiles.isEmpty() ? null : profiles.get(0);
    }

    private void updateSidebarControllerLabel(ControlsProfile profile) {
        if (sidebarControllerProfileView != null)
            sidebarControllerProfileView.setText(profile != null ? profile.getName() : getString(R.string.disabled));
    }

    private void ensureWinlatorHud() {
        if (frameRating != null) return;
        FrameLayout root = findViewById(R.id.FLXServerDisplay);
        hudDataSource = new HudDataSource(this);
        frameRating = new WinlatorHUD(this);
        frameRating.setDataSource(hudDataSource);
        frameRating.setWrapperName(graphicsDriver);
        frameRating.onRendererDetected(getHudApiName());
        frameRating.setPhoneGpuName(getPhoneGpuName());
        if (xServerView != null) xServerView.getRenderer().setWinlatorHUD(frameRating);
        root.addView(frameRating);
        frameRating.postDelayed(() -> frameRating.setPhoneGpuName(getPhoneGpuName()), 500);
    }

    private void toggleSidebarHud() {
        if (container == null) return;
        boolean enabled = !container.isShowFPS();
        container.setShowFPS(enabled);
        container.saveData();
        if ("winlator".equals(container.getHudMode())) {
            ensureWinlatorHud();
            if (enabled) frameRating.enableByUser(); else frameRating.disableByUser();
        } else {
            AppUtils.showToast(this, enabled
                    ? "HUD enabled for the next launch."
                    : "HUD disabled for the next launch.");
        }
    }

    private void toggleSidebarPause() {
        if (isPaused) {
            ProcessHelper.resumeAllWineProcesses();
            sidebarPauseButton.setImageResource(R.drawable.icon_pause);
        } else {
            ProcessHelper.pauseAllWineProcesses();
            sidebarPauseButton.setImageResource(R.drawable.icon_play);
        }
        isPaused = !isPaused;
    }

    private void openInlineTaskManager() {
        View sidebar = navigationView.getHeaderView(0);
        LinearLayout main = sidebar.findViewById(R.id.LLSidebarMain);
        FrameLayout host = sidebar.findViewById(R.id.FLSidebarTaskManager);
        ImageButton close = sidebar.findViewById(R.id.BTCloseTaskManager);
        main.setVisibility(View.GONE);
        sidebarTimeView.setVisibility(View.GONE);
        close.setVisibility(View.VISIBLE);
        host.setVisibility(View.VISIBLE);
        if (inlineTaskManagerPanel == null) inlineTaskManagerPanel = new InlineTaskManagerPanel(this);
        if (inlineTaskManagerPanel.getParent() == null) host.addView(inlineTaskManagerPanel,
                new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT));
        inlineTaskManagerPanel.start();
    }

    public void closeInlineTaskManager(boolean closeDrawer) {
        if (inlineTaskManagerPanel != null) inlineTaskManagerPanel.stop();
        if (navigationView != null && navigationView.getHeaderCount() > 0) {
            View sidebar = navigationView.getHeaderView(0);
            sidebar.findViewById(R.id.LLSidebarMain).setVisibility(View.VISIBLE);
            sidebar.findViewById(R.id.FLSidebarTaskManager).setVisibility(View.GONE);
            sidebar.findViewById(R.id.BTCloseTaskManager).setVisibility(View.GONE);
            if (sidebarTimeView != null) sidebarTimeView.setVisibility(View.VISIBLE);
        }
        if (closeDrawer && drawerLayout != null) drawerLayout.closeDrawers();
    }



    private ActivityResultLauncher<Intent> controlsEitorActivityResultLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (editInputControlsCallback != null) {
                    editInputControlsCallback.run();
                    editInputControlsCallback = null;
                }
            }
    );

    private String parseShortcutNameFromDesktopFile(File desktopFile) {
        String shortcutName = "";
        if (desktopFile.exists()) {
            try (BufferedReader reader = new BufferedReader(new FileReader(desktopFile))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("Name=")) {
                        shortcutName = line.split("=")[1].trim();
                        break;
                    }
                }
            } catch (IOException e) {
                Log.e("XServerDisplayActivity", "Error reading shortcut name from .desktop file", e);
            }
        }
        return shortcutName;
    }

    private void showInputControlsDialog() {
        final ContentDialog dialog = new ContentDialog(this, R.layout.input_controls_dialog);
        dialog.setTitle(R.string.input_controls);
        dialog.setIcon(R.drawable.icon_input_controls);

        final Spinner sProfile = dialog.findViewById(R.id.SProfile);

        dialog.getWindow().setBackgroundDrawableResource(isDarkMode ? R.drawable.content_dialog_background_dark : R.drawable.content_dialog_background);
        sProfile.setPopupBackgroundResource(isDarkMode ? R.drawable.content_dialog_background_dark : R.drawable.content_dialog_background);

        Runnable loadProfileSpinner = () -> {
            ArrayList<ControlsProfile> profiles = inputControlsManager.getProfiles(true);
            ArrayList<String> profileItems = new ArrayList<>();
            int selectedPosition = 0;
            profileItems.add("-- "+getString(R.string.disabled)+" --");
            for (int i = 0; i < profiles.size(); i++) {
                ControlsProfile profile = profiles.get(i);
                if (inputControlsView.getProfile() != null && profile.id == inputControlsView.getProfile().id)
                    selectedPosition = i + 1;
                profileItems.add(profile.getName());
            }

            sProfile.setAdapter(new ThemedSpinnerAdapter<>(dialog.getContext(), profileItems));
            sProfile.setSelection(selectedPosition);
        };
        loadProfileSpinner.run();

        final CheckBox cbSimTouchScreen = dialog.findViewById(R.id.CBSimulateTouchScreen);
        cbSimTouchScreen.setChecked(touchpadView.isSimTouchScreen());

        final CheckBox cbShowTouchscreenControls = dialog.findViewById(R.id.CBShowTouchscreenControls);
        cbShowTouchscreenControls.setChecked(inputControlsView.isShowTouchscreenControls());

        final CheckBox cbEnableTimeout = dialog.findViewById(R.id.CBEnableTimeout);
        cbEnableTimeout.setChecked(preferences.getBoolean("touchscreen_timeout_enabled", false));

        final ControllerManager controllerManager = ControllerManager.getInstance();
        final CheckBox cbAutoGrabController = dialog.findViewById(R.id.CBAutoGrabController);
        cbAutoGrabController.setChecked(controllerManager.isAutoGrabEnabled());

        final CheckBox cbMasterVibration = dialog.findViewById(R.id.CBMasterVibration);
        cbMasterVibration.setChecked(controllerManager.isMasterVibrationEnabled());
        final Button btTestVibration = dialog.findViewById(R.id.BTTestVibration);
        btTestVibration.setEnabled(cbMasterVibration.isChecked());
        cbMasterVibration.setOnCheckedChangeListener((button, enabled) ->
                btTestVibration.setEnabled(enabled));
        btTestVibration.setOnClickListener(view -> {
            if (!cbMasterVibration.isChecked()) {
                Toast.makeText(this, "Vibration OFF", Toast.LENGTH_SHORT).show();
                return;
            }
            testControllerVibration(controllerManager);
        });

        final CheckBox cbDisableTouchscreenMouse = dialog.findViewById(R.id.CBDisableTouchscreenMouse);
        cbDisableTouchscreenMouse.setChecked(preferences.getBoolean("touchscreen_mouse_disabled", false));


        final Runnable updateProfile = () -> {
            int position = sProfile.getSelectedItemPosition();
            if (position > 0) {
                showInputControls(inputControlsManager.getProfiles().get(position - 1));
            }
            else hideInputControls();
        };

        dialog.findViewById(R.id.BTSettings).setOnClickListener((v) -> {
            int position = sProfile.getSelectedItemPosition();
            Intent intent = new Intent(this, MainActivity.class);
            intent.putExtra("edit_input_controls", true);
            intent.putExtra("selected_profile_id", position > 0 ? inputControlsManager.getProfiles().get(position - 1).id : 0);
            editInputControlsCallback = () -> {
                hideInputControls();
                inputControlsManager.loadProfiles(true);
                loadProfileSpinner.run();
                updateProfile.run();
            };
            controlsEitorActivityResultLauncher.launch(intent);
        });

        dialog.setOnConfirmCallback(() -> {
            inputControlsView.setShowTouchscreenControls(cbShowTouchscreenControls.isChecked());
            boolean isTimeoutEnabled = cbEnableTimeout.isChecked();
            boolean isMouseDisabled = cbDisableTouchscreenMouse.isChecked();
            SharedPreferences.Editor editor = preferences.edit();
            editor.putBoolean("touchscreen_timeout_enabled", isTimeoutEnabled);
            editor.putBoolean("touchscreen_mouse_disabled", isMouseDisabled);
            editor.apply();
            controllerManager.setAutoGrabEnabled(cbAutoGrabController.isChecked());
            controllerManager.setMasterVibrationEnabled(cbMasterVibration.isChecked());

            if (isTimeoutEnabled) {
                startTouchscreenTimeout(); // Start the timeout functionality if enabled
            } else {
                touchpadView.setOnTouchListener(null); // Disable the listener if timeout is disabled
            }
            if (isMouseDisabled) {
                touchpadView.setTouchscreenMouseDisabled(true);
            } else
                touchpadView.setTouchscreenMouseDisabled(false);

            int position = sProfile.getSelectedItemPosition();
            if (position > 0) {
                ControlsProfile selected = inputControlsManager.getProfiles(true).get(position - 1);
                preferences.edit()
                        .putInt("sidebar_controller_profile_id", selected.id)
                        .putBoolean("sidebar_controller_enabled", true)
                        .apply();
                showInputControls(selected);
                updateSidebarControllerLabel(selected);
            }
            else {
                preferences.edit().putBoolean("sidebar_controller_enabled", false).apply();
                hideInputControls();
                updateSidebarControllerLabel(getSidebarControllerProfile());
            }
            touchpadView.setSimTouchScreen(cbSimTouchScreen.isChecked());
            updateProfile.run();
        });

        dialog.setOnCancelCallback(updateProfile::run);

        dialog.setCanceledOnTouchOutside(false);
        dialog.show();
    }

    private void testControllerVibration(ControllerManager controllerManager) {
        controllerManager.scanForDevices();
        boolean didVibrate = false;
        for (int slot = 0; slot < 4; slot++) {
            if (!controllerManager.isSlotEnabled(slot)
                    || !controllerManager.isVibrationEnabled(slot)) continue;
            InputDevice device = controllerManager.getAssignedDeviceForSlot(slot);
            if (device == null) continue;
            android.os.Vibrator vibrator = device.getVibrator();
            if (vibrator == null || !vibrator.hasVibrator()) continue;
            try {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    vibrator.vibrate(android.os.VibrationEffect.createOneShot(300,
                            android.os.VibrationEffect.DEFAULT_AMPLITUDE));
                }
                else vibrator.vibrate(300);
                didVibrate = true;
            }
            catch (Exception ignored) {}
        }
        if (didVibrate) return;

        // Keep the same useful fallback as Controller Manager when no assigned
        // physical controller exposes an Android vibrator.
        android.os.Vibrator phone = (android.os.Vibrator)
                getSystemService(Context.VIBRATOR_SERVICE);
        if (phone == null || !phone.hasVibrator()) return;
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                phone.vibrate(android.os.VibrationEffect.createOneShot(250,
                        android.os.VibrationEffect.DEFAULT_AMPLITUDE));
            }
            else phone.vibrate(250);
        }
        catch (Exception ignored) {}
    }

    private void showHUDConfigDialog() {
        if (container != null && !"winlator".equals(container.getHudMode())) {
            AppUtils.showToast(this, "Select Winlator HUD in the container settings to customize it here.");
            return;
        }
        if (frameRating == null) {
            FrameLayout rootView = findViewById(R.id.FLXServerDisplay);
            hudDataSource = new HudDataSource(this);
            frameRating = new WinlatorHUD(this);
            frameRating.setDataSource(hudDataSource);
            frameRating.setWrapperName(graphicsDriver);
            frameRating.onRendererDetected(getHudApiName());
            frameRating.setPhoneGpuName(getPhoneGpuName());
            if (xServerView != null) xServerView.getRenderer().setWinlatorHUD(frameRating);
            rootView.addView(frameRating);
            frameRating.postDelayed(() -> frameRating.setPhoneGpuName(getPhoneGpuName()), 500);
            if (container != null && !container.isShowFPS()) frameRating.disableByUser();
        }

        final ContentDialog dialog = new ContentDialog(this, R.layout.hud_config_dialog);
        dialog.setTitle("HUD Settings");
        dialog.setIcon(R.drawable.ic_hud);

        CheckBox cbEnable = dialog.findViewById(R.id.CBHudEnable);
        CheckBox cbFps = dialog.findViewById(R.id.CBHudFPS);
        CheckBox cbGpu = dialog.findViewById(R.id.CBHudGPU);
        CheckBox cbCpu = dialog.findViewById(R.id.CBHudCPU);
        CheckBox cbRam = dialog.findViewById(R.id.CBHudRAM);
        CheckBox cbBatt = dialog.findViewById(R.id.CBHudBattery);
        CheckBox cbBattPct = dialog.findViewById(R.id.CBHudBatteryPct);
        CheckBox cbRend = dialog.findViewById(R.id.CBHudRenderer);
        CheckBox cbGraph = dialog.findViewById(R.id.CBHudGraph);
        CheckBox cbVert = dialog.findViewById(R.id.CBHudVertical);
        CheckBox cbMono = dialog.findViewById(R.id.CBHudMono);
        CheckBox cbBorder = dialog.findViewById(R.id.CBHudBorder);
        CheckBox cbCompact = dialog.findViewById(R.id.CBHudCompact);
        CheckBox cbWrapper = dialog.findViewById(R.id.CBHudWrapper);
        CheckBox cbLocked = dialog.findViewById(R.id.CBHudLocked);
        CheckBox cbCpuTemp = dialog.findViewById(R.id.CBHudCPUTemp);
        CheckBox cbSoc = dialog.findViewById(R.id.CBHudSOC);
        CheckBox cbGpuTemp = dialog.findViewById(R.id.CBHudGPUTemp);
        CheckBox cbPhoneGpu = dialog.findViewById(R.id.CBHudPhoneGPU);
        CheckBox cbFgLatency = dialog.findViewById(R.id.CBHudFGLatency);
        CheckBox cbFgStatus = dialog.findViewById(R.id.CBHudFGStatus);
        CheckBox cbRamWarning = dialog.findViewById(R.id.CBHudRamWarning);
        SeekBar sbAlpha = dialog.findViewById(R.id.SBHudAlpha);
        SeekBar sbScale = dialog.findViewById(R.id.SBHudScale);
        TextView tvAlpha = dialog.findViewById(R.id.TVHudAlpha);
        TextView tvScale = dialog.findViewById(R.id.TVHudScale);
        Spinner spPreset = dialog.findViewById(R.id.SPHudPreset);

        frameRating.syncCheckboxes(cbFps, cbGpu, cbCpu, cbBatt, cbGraph, cbRend,
                cbRam, cbBattPct, cbMono, cbBorder, cbCompact, cbWrapper, cbLocked,
                cbCpuTemp, cbSoc, cbGpuTemp, cbPhoneGpu, cbFgLatency, cbFgStatus);
        cbEnable.setChecked(frameRating.isUserEnabled());
        cbVert.setChecked(frameRating.isVertical());

        int initialAlpha = Math.round(frameRating.getHudAlpha() * 100);
        sbAlpha.setProgress(initialAlpha);
        tvAlpha.setText(initialAlpha + "%");
        float initialScale = frameRating.getHudScale();
        sbScale.setProgress(Math.round((initialScale - 0.5f) / 1.5f * 100));
        tvScale.setText(String.format(Locale.US, "%.1fx", initialScale));

        String[] presets = {"Custom", "Top Left", "Top Center", "Top Right", "Middle Left",
                "Center", "Middle Right", "Bottom Left", "Bottom Center", "Bottom Right"};
        spPreset.setAdapter(new ThemedSpinnerAdapter<>(dialog.getContext(), presets));

        cbEnable.setOnCheckedChangeListener((button, checked) -> {
            if (checked) frameRating.enableByUser(); else frameRating.disableByUser();
            if (container != null) {
                container.setShowFPS(checked);
                container.saveData();
            }
        });
        cbFps.setOnCheckedChangeListener((v, checked) -> frameRating.toggleElement(0, checked));
        cbGpu.setOnCheckedChangeListener((v, checked) -> frameRating.toggleElement(2, checked));
        cbCpu.setOnCheckedChangeListener((v, checked) -> frameRating.toggleElement(3, checked));
        cbBatt.setOnCheckedChangeListener((v, checked) -> frameRating.toggleElement(4, checked));
        cbGraph.setOnCheckedChangeListener((v, checked) -> frameRating.toggleElement(5, checked));
        cbRend.setOnCheckedChangeListener((v, checked) -> frameRating.toggleElement(6, checked));
        cbRam.setOnCheckedChangeListener((v, checked) -> frameRating.toggleElement(7, checked));
        cbBattPct.setOnCheckedChangeListener((v, checked) -> frameRating.toggleElement(8, checked));
        cbMono.setOnCheckedChangeListener((v, checked) -> frameRating.toggleElement(9, checked));
        cbBorder.setOnCheckedChangeListener((v, checked) -> frameRating.toggleElement(10, checked));
        cbCompact.setOnCheckedChangeListener((v, checked) -> frameRating.toggleElement(11, checked));
        cbWrapper.setOnCheckedChangeListener((v, checked) -> frameRating.toggleElement(12, checked));
        cbLocked.setOnCheckedChangeListener((v, checked) -> frameRating.toggleElement(13, checked));
        cbCpuTemp.setOnCheckedChangeListener((v, checked) -> frameRating.toggleElement(14, checked));
        cbSoc.setOnCheckedChangeListener((v, checked) -> frameRating.toggleElement(15, checked));
        cbGpuTemp.setOnCheckedChangeListener((v, checked) -> frameRating.toggleElement(16, checked));
        cbPhoneGpu.setOnCheckedChangeListener((v, checked) -> frameRating.toggleElement(17, checked));
        cbFgLatency.setOnCheckedChangeListener((v, checked) -> frameRating.toggleElement(18, checked));
        cbFgStatus.setOnCheckedChangeListener((v, checked) -> frameRating.toggleElement(19, checked));
        cbVert.setOnCheckedChangeListener((v, checked) -> frameRating.setVertical(checked));
        cbRamWarning.setChecked(!frameRating.isRamWarningEnabled());
        cbRamWarning.setOnCheckedChangeListener((v, checked) -> frameRating.setRamWarningEnabled(!checked));
        dialog.findViewById(R.id.BTHudRamWarningHelp).setOnClickListener(view ->
                AppUtils.showHelpBox(this, view, R.string.ram_warning_help));

        sbAlpha.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) frameRating.setHudAlpha(progress / 100f);
                tvAlpha.setText(progress + "%");
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        sbScale.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                float scale = 0.5f + progress / 100f * 1.5f;
                if (fromUser) frameRating.setHudScale(scale);
                tvScale.setText(String.format(Locale.US, "%.1fx", scale));
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        spPreset.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                if (position > 0) frameRating.setPositionPreset(position - 1);
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) {}
        });
        dialog.findViewById(R.id.BTHudReset).setOnClickListener(v -> frameRating.forceReset());
        dialog.show();
    }

    public void showInputControlsFromControllerManager() {
        showInputControlsDialog();
    }

    private void simulateConfirmInputControlsDialog() {
        // Simulate setting the relative mouse movement and touchscreen controls from preferences

        boolean isShowTouchscreenControls = preferences.getBoolean("show_touchscreen_controls_enabled", false); // default is false (hidden)
        inputControlsView.setShowTouchscreenControls(isShowTouchscreenControls);

        boolean isTimeoutEnabled = preferences.getBoolean("touchscreen_timeout_enabled", false);
        // Apply these settings as if the user confirmed the dialog
        SharedPreferences.Editor editor = preferences.edit();
        editor.putBoolean("touchscreen_timeout_enabled", isTimeoutEnabled);
        editor.apply();

        // If no profile is selected, hide the controls
        int selectedProfileIndex = preferences.getInt("selected_profile_index", -1); // Default to -1 for no profile

        if (selectedProfileIndex >= 0 && selectedProfileIndex < inputControlsManager.getProfiles().size()) {
            // A profile is selected, show the controls
            ControlsProfile profile = inputControlsManager.getProfiles().get(selectedProfileIndex);
            showInputControls(profile);
        } else {
            // No profile selected, ensure the controls are hidden
            hideInputControls();
        }

        // Timeout logic should only apply if the controls are visible
        if (isTimeoutEnabled && inputControlsView.getVisibility() == View.VISIBLE) {
            startTouchscreenTimeout(); // Start timeout if enabled and controls are visible
        } else {
            touchpadView.setOnTouchListener(null); // Disable the timeout listener if not needed
        }

        Log.d("XServerDisplayActivity", "Input controls simulated confirmation executed.");
    }

    @SuppressLint("ClickableViewAccessibility")
    private void startTouchscreenTimeout() {
        boolean isTimeoutEnabled = preferences.getBoolean("touchscreen_timeout_enabled", false);

        if (isTimeoutEnabled) {
            // Show controls initially and set up touch event listeners
            inputControlsView.setVisibility(View.VISIBLE);
            Log.d("XServerDisplayActivity", "Timeout is enabled, setting up timeout logic.");

            // Attach the OnTouchListener to reset the timeout on touch events
            touchpadView.setOnTouchListener((v, event) -> {
                int action = event.getAction();
                if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE) {
                    // Reset the timeout on any touch event
                    //Log.d("XServerDisplayActivity", "Touch detected, resetting timeout.");

                    // Keep the controls visible
                    inputControlsView.setVisibility(View.VISIBLE);

                    // Remove any pending hide callbacks and reset the timeout
                    timeoutHandler.removeCallbacks(hideControlsRunnable);
                    timeoutHandler.postDelayed(hideControlsRunnable, 5000); // Reset timeout
                }

                return false; // Allow the touch event to propagate
            });

            // Reset the timeout when the controls are initially displayed
            timeoutHandler.removeCallbacks(hideControlsRunnable);
            timeoutHandler.postDelayed(hideControlsRunnable, 5000); // Hide after 5 seconds of inactivity
        } else {
            // If timeout is disabled, keep the controls always visible
            Log.d("XServerDisplayActivity", "Timeout is disabled, controls will stay visible.");

            inputControlsView.setVisibility(View.VISIBLE); // Ensure controls are visible
            timeoutHandler.removeCallbacks(hideControlsRunnable); // Remove any existing hide callbacks
            touchpadView.setOnTouchListener(null); // Remove the touch listener
        }
    }

    private void showInputControls(ControlsProfile profile) {
        inputControlsView.setVisibility(View.VISIBLE);
        inputControlsView.requestFocus();
        inputControlsView.setProfile(profile);

        touchpadView.setSensitivity(profile.getCursorSpeed() * globalCursorSpeed);
        touchpadView.setPointerButtonRightEnabled(false);

        inputControlsView.invalidate();

        // If the selected profile is a virtual gamepad, we must enable the P1 slot.
        if (profile.isVirtualGamepad()) {
            ControllerManager controllerManager = ControllerManager.getInstance();
            // Ensure Player 1 slot is enabled so a vjoy device is created for it.
            controllerManager.setSlotEnabled(0, true);
            // Clear any physical device from P1 to prevent conflicts.
            controllerManager.unassignSlot(0);
            // Update its internal state.
            if (winHandler != null) {
                winHandler.refreshControllerMappings();
                winHandler.sendVirtualGamepadState(profile.getGamepadState());
                winHandler.sendGamepadState();
            }
        }

    }

    private void hideInputControls() {
        ControlsProfile hiddenProfile = inputControlsView.getProfile();
        if (hiddenProfile != null && hiddenProfile.isVirtualGamepad() && winHandler != null) {
            winHandler.sendVirtualGamepadState(new GamepadState());
        }
        inputControlsView.setShowTouchscreenControls(true);
        inputControlsView.setVisibility(View.GONE);
        inputControlsView.setProfile(null);

        touchpadView.setSensitivity(globalCursorSpeed);
        touchpadView.setPointerButtonLeftEnabled(true);
        touchpadView.setPointerButtonRightEnabled(true);

        // If the profile we are hiding was a virtual gamepad...
        if (hiddenProfile != null && hiddenProfile.isVirtualGamepad()) {
            ControllerManager controllerManager = ControllerManager.getInstance();
            // ...and if no physical controller is assigned to P1...
            if (controllerManager.getAssignedDeviceForSlot(0) == null) {
                // ...then disable the slot so we don't have an orphaned vjoy device.
                controllerManager.setSlotEnabled(0, false);
            }
        }

        inputControlsView.invalidate();
    }

    private void extractGraphicsDriverFiles() {
        String currentWrapperVersion = graphicsDriverConfig.getOrDefault("version", DefaultVersion.WRAPPER);
        String selectedDriverVersion = currentWrapperVersion;
        if (shortcut != null) {
            currentWrapperVersion = shortcut.getExtra("wrapperGraphicsDriverVersion", currentWrapperVersion);
            selectedDriverVersion = currentWrapperVersion;
        }
        String xclipseDriverId = selectedDriverVersion == null
                || selectedDriverVersion.trim().equalsIgnoreCase(DefaultVersion.WRAPPER)
                ? DefaultVersion.WRAPPER : selectedDriverVersion.trim();
        Log.d("GraphicsDriverExtraction", "Xclipse driver ID: " + xclipseDriverId);

        File rootDir = imageFs.getRootDir();

        // Extra system libs (libGL.so.1 for the wined3d GL renderer / zink,
        // vkBasalt, etc.). The bionic base extracts these on first boot; ours
        // only did so inside the experimental-BCN flow, leaving containers
        // without OpenGL and killing wined3d-GL sessions with
        // "Failed to load libGL".
        if (!new File(rootDir, "usr/lib/libGL.so.1.5.0").isFile()) {
            Log.i("GraphicsDriverExtraction", "Extracting extra system libraries");
            TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this,
                    "graphics_driver/extra_libs.tzst", rootDir);
        }

        if (dxwrapper.equals("dxvk")) {
            java.io.File containerRoot = container != null ? container.getRootDir() : null;
            java.io.File shortcutConf = shortcut != null ? shortcut.getDxvkConfFile() : null;
            int driverMaxMemMb = 0;
            try {
                driverMaxMemMb = Integer.parseInt(graphicsDriverConfig.getOrDefault("maxDeviceMemory", "0"));
            } catch (Exception ignored) {}
            DXVKConfigDialog.setEnvVars(this, dxwrapperConfig, envVars, containerRoot, driverMaxMemMb, shortcutConf);
        }

        boolean showFps = container != null && container.isShowFPS();
        String hudMode = getRuntimeHudMode();
        boolean useDxvkHud = showFps && "dxvk".equals(hudMode);
        // Bannerlator's stable HUD path is native to Android. The MangoHud
        // choice uses WinlatorHUD/HudDataSource instead of injecting a missing
        // Vulkan layer into the guest process.
        envVars.put("MANGOHUD", "0");
        envVars.put("DXVK_HUD", useDxvkHud
                ? "devinfo,fps,frametimes,gpuload,version,api" : "0");
        envVars.remove("MANGOHUD_CONFIG");

        boolean useDRI3 = preferences.getBoolean("use_dri3", true);
        if (!useDRI3) envVars.put("MESA_VK_WSI_DEBUG", "sw");

        envVars.put("VK_ICD_FILENAMES", imageFs.getShareDir() + "/vulkan/icd.d/wrapper_icd.aarch64.json");
        envVars.put("GALLIUM_DRIVER", "zink");
        envVars.put("LIBGL_KOPPER_DISABLE", "true");

        String mainWrapperSelection = this.graphicsDriver;
        boolean experimentalBCN = container != null
                && "1".equals(container.getExtra("experimentalBCN", "0"));
        if (shortcut != null && shortcut.hasExtra("experimentalBCN")) {
            experimentalBCN = "1".equals(shortcut.getExtra("experimentalBCN", "0"));
        }
        boolean requestedAstcTranscode = "1".equals(
                graphicsDriverConfig.getOrDefault("astcTranscode", "0"));
        boolean requestedEtc2Transcode = "1".equals(
                graphicsDriverConfig.getOrDefault("etc2Transcode", "0"));
        boolean computeBcnMode = !"software".equalsIgnoreCase(
                graphicsDriverConfig.getOrDefault("bcnEmulationType",
                        "1".equals(graphicsDriverConfig.getOrDefault("bcnSoftwareSwitch", "0"))
                                ? "software" : GPUInformation.defaultBcnEmulationType()));
        if ((requestedAstcTranscode || requestedEtc2Transcode) && !computeBcnMode) {
            // Transcode (encode_etc2/astc_compute) só existe no backend compute.
            // Config antiga/inválida com Type=Software + transcode marcava
            // BCN TRANSCODE ERROR e o jogo saía branco/preto sem decodificar
            // nada. Intenção do usuário (transcode ligado) vence o backend.
            computeBcnMode = true;
            Log.w("GraphicsDriverExtraction",
                    "Transcode requested with software BCN backend; forcing compute");
        }
        if ("1".equals(graphicsDriverConfig.getOrDefault("astcAutoDefault", "0"))
                && computeBcnMode && !requestedAstcTranscode && !requestedEtc2Transcode) {
            requestedAstcTranscode = true;
            Log.i("GraphicsDriverExtraction",
                    "ASTC transcode auto-default via tuning dialog");
        }
        // RE3 Remake exercita uploads BC1-BC7 com staging copies: o par
        // hibrido Wrapper-Default + camada July13 e o unico com suporte real
        // a transcode (WRAPPER_BCN_GPU/ASTC + encode_etc2/astc_compute).
        // O par alternativo wrapper-default-transcode/leegao_bcn_transcode_compat
        // nao expoe WRAPPER_BCN/ASTC nem transcode no wrapper, entao o layer
        // desabilita o transcode ("not supported") e o RE3 sai branco em ETC2
        // e preto em ASTC. Por isso o transcode usa sempre o par hibrido.
        String lastInstalledMainWrapper = container.getExtra("lastInstalledMainWrapper");
        CustomWrapperManager customWrapperManager = new CustomWrapperManager(this);
        String wrapperRevision = BuildConfig.VERSION_CODE + ":"
                + customWrapperManager.getRevision(mainWrapperSelection);
        if ("wrapper-default".equalsIgnoreCase(mainWrapperSelection)) {
            // Built-in assets do not have a downloadable-content revision.
            // Include the matched pair revision so an APK update refreshes an
            // already-created container instead of retaining an older wrapper.
            // Sufixo -4 força re-extração em containers presos no par quebrado
            // mali-re3-transcode-pair-1.
            wrapperRevision += ":july13-pair-4";
        }
        String lastInstalledMainWrapperRevision =
                container.getExtra("lastInstalledMainWrapperRevision", "");
        if (firstTimeBoot || !mainWrapperSelection.equals(lastInstalledMainWrapper)
                || !wrapperRevision.equals(lastInstalledMainWrapperRevision)) {
            if (mainWrapperSelection.toLowerCase().startsWith("wrapper")) {
                String assetPath = resolveBundledWrapperAsset(mainWrapperSelection);
                Log.d("GraphicsDriverExtraction", "WRAPPER selection changed or first boot. Extracting: " + assetPath);
                boolean success = TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this, assetPath, rootDir);
                if (!success) {
                    success = customWrapperManager.apply(mainWrapperSelection, rootDir);
                    Log.d("GraphicsDriverExtraction", "Custom wrapper extracted=" + success);
                }
                if (success) {
                    container.putExtra("lastInstalledMainWrapper", mainWrapperSelection);
                    container.putExtra("lastInstalledMainWrapperRevision", wrapperRevision);
                    container.saveData();
                }
                else Log.e("GraphicsDriverExtraction", "Unable to extract wrapper asset: " + assetPath);
            }
        }

        boolean nativeBcnWrapper = isNativeBcnWrapper(mainWrapperSelection);
        boolean dedicatedBcnWrapper = "wrapper-default".equalsIgnoreCase(mainWrapperSelection);

        File bcnLayerLibrary = new File(rootDir, "usr/lib/libbcn_layer.so");
        File bcnLayerManifest = new File(rootDir,
                "usr/share/vulkan/implicit_layer.d/libbcn_layer.json");
        final String bcnLayerAsset = dedicatedBcnWrapper ? "graphics_driver/leegao_bcn_july13.tzst"
                        : "graphics_driver/leegao_bcn.tzst";
        // The version marker identifies the complete wrapper/layer pair. This
        // forces the matching layer to be restored when users switch stacks.
        // Sufixo -4 limpa containers presos no leegao-mali-re3-transcode-compat-1.
        final String bcnLayerVersion = dedicatedBcnWrapper ? "leegao-july13-wrapper-default-4"
                        : "leegao-winmali-2";
        boolean bcnLayerReady = bcnLayerLibrary.isFile() && bcnLayerManifest.isFile();

        if (experimentalBCN && !nativeBcnWrapper && (!bcnLayerReady
                || !bcnLayerVersion.equals(container.getExtra("bcnLayerVersion", "")))) {
            Log.i("GraphicsDriverExtraction", "Installing opt-in BCN compatibility layer: "
                    + bcnLayerAsset);
            TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this,
                    "graphics_driver/extra_libs.tzst", rootDir);
            TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this,
                    "layers.tzst", rootDir);
            File downloadedBcnLayer = new File(getFilesDir(), bcnLayerAsset);
            // Keep Wrapper-Default reproducible: its layer must always come from
            // the APK and must not be replaced by a legacy downloaded layer.
            boolean extracted = !dedicatedBcnWrapper && downloadedBcnLayer.isFile()
                    ? TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD,
                            downloadedBcnLayer, rootDir)
                    : TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this,
                            bcnLayerAsset, rootDir);
            bcnLayerReady = extracted && bcnLayerLibrary.isFile()
                    && bcnLayerManifest.isFile();
            container.putExtra("bcnLayerVersion", bcnLayerReady ? bcnLayerVersion : null);
            container.saveData();
        }

        if (!experimentalBCN || nativeBcnWrapper || !bcnLayerReady) {
            if (bcnLayerManifest.isFile() && !bcnLayerManifest.delete()) {
                Log.w("GraphicsDriverExtraction", "Unable to remove inactive BCN layer manifest");
            }
            if (bcnLayerLibrary.isFile() && !bcnLayerLibrary.delete()) {
                Log.w("GraphicsDriverExtraction", "Unable to remove inactive BCN layer library");
            }
            bcnLayerReady = false;
        }

        if (!DefaultVersion.WRAPPER.equals(xclipseDriverId)) {
            XclipseDriverManager driverManager = new XclipseDriverManager(this);
            driverManager.setDriverById(envVars, imageFs, xclipseDriverId);
        }

        envVars.put("WRAPPER_VK_VERSION",
                graphicsDriverConfig.getOrDefault("vulkanVersion", "1.3"));
        envVars.put("WRAPPER_EXTENSION_BLACKLIST", graphicsDriverConfig.getOrDefault("blacklistedExtensions", ""));

        String gpuName = graphicsDriverConfig.getOrDefault("gpuName", "Device");
        if (!"Device".equals(gpuName)) envVars.put("WRAPPER_DEVICE_NAME", gpuName);

        String maxDeviceMemory = graphicsDriverConfig.getOrDefault("maxDeviceMemory", "0");
        try {
            if (Integer.parseInt(maxDeviceMemory) > 0) {
                envVars.put("WRAPPER_VMEM_MAX_SIZE", maxDeviceMemory);
                envVars.put("UTIL_LAYER_VMEM_MAX_SIZE", maxDeviceMemory);
            }
            else if (isExperimentalPerformanceActive() && "1".equals(xperfConfig.get("vramCap"))) {
                String capMode = xperfConfig.get("vramCapMode");
                int vramCap = "2048".equals(capMode) ? 2048
                        : "3072".equals(capMode) ? 3072
                        : "4092".equals(capMode) ? 4092
                        : "6144".equals(capMode) ? 6144
                        : suggestVramCap();
                if (vramCap > 0) {
                    envVars.put("WRAPPER_VMEM_MAX_SIZE", String.valueOf(vramCap));
                    envVars.put("UTIL_LAYER_VMEM_MAX_SIZE", String.valueOf(vramCap));
                    Log.i("GraphicsDriverExtraction",
                            "Unified-memory VRAM cap: " + vramCap + " MB");
                }
            }
        }
        catch (NumberFormatException e) {
            Log.w("GraphicsDriverExtraction", "Invalid max device memory: " + maxDeviceMemory);
        }

        // Do not derive the swapchain mode from the FPS/VSync limiter. Mailbox
        // is the high-throughput tear-free path: Wine may render above the
        // panel rate while the WSI keeps only the newest complete image.
        String presentMode = resolvePresentMode();
        envVars.put("MESA_VK_WSI_PRESENT_MODE", presentMode);
        envVars.put("VKD3D_SWAPCHAIN_PRESENT_MODE",
                "relaxed".equals(presentMode)
                        ? "FIFO_RELAXED" : presentMode.toUpperCase(Locale.ENGLISH));
        envVars.put("WRAPPER_MAX_IMAGE_COUNT", presentMode.contains("immediate") ? "1" : "0");
        envVars.put("WRAPPER_RESOURCE_TYPE", graphicsDriverConfig.getOrDefault("resourceType", "auto"));

        if ("wrapper-default".equalsIgnoreCase(mainWrapperSelection)) {
            // The coherent Mali/Ludashi base exposes the Ref4ik-era feature
            // switches while using the BCN-family hook ABI.  Disabling the
            // legacy OpConstantComposite rewrite is Mali's stable default and
            // avoids corrupting shaders in titles that build large pipelines
            // during startup (including the RDR path under test).
            envVars.put("WRAPPER_NO_PATCH_OPCONSTCOMP", "1");
        }

        String legacyFrameSync = graphicsDriverConfig.getOrDefault("frameSync", "Normal");
        boolean syncFrame = "1".equals(graphicsDriverConfig.getOrDefault("syncFrame", "0"))
                || "Always".equals(legacyFrameSync);
        // forcesync is an explicit compatibility option. Enabling it for every
        // mailbox frame serializes the queue and turns mailbox into FIFO in
        // practice, reducing FPS for no benefit once the real mode is honored.
        if (syncFrame && useDRI3) envVars.put("MESA_VK_WSI_DEBUG", "forcesync");
        String disablePresentWait = graphicsDriverConfig.getOrDefault("disablePresentWait",
                "Never".equals(legacyFrameSync) ? "1" : "0");
        // Every synchronized Vulkan present mode must keep presentation waits.
        // Bypassing them can recycle an image while the compositor still owns
        // it. Only immediate is intentionally allowed to trade integrity for
        // throughput; mailbox, FIFO and relaxed keep their defined semantics.
        if (!"immediate".equals(presentMode)) disablePresentWait = "0";
        envVars.put("WRAPPER_DISABLE_PRESENT_WAIT", disablePresentWait);

        envVars.remove("ENABLE_BCN_COMPUTE");
        envVars.remove("DISABLE_BCN_COMPUTE");
        envVars.remove("BCN_COMPUTE_AUTO");
        envVars.remove("WRAPPER_EMULATE_BCN");
        envVars.remove("WRAPPER_USE_BCN_CACHE");
        envVars.remove("BCN_DISABLE_DISK_CACHE");
        envVars.remove("BCN_TRANSCODE_TO_ASTC");
        envVars.remove("BCN_TRANSCODE_TO_ETC2");
        envVars.remove("BCN_COMPUTE_IMAGE_VIEW");
        envVars.remove("BCN_LAYER_LOG_LEVEL");
        envVars.remove("BCN_PROFILE_TRANSFERS");
        envVars.remove("WRAPPER_DIAG");
        String effectiveBcnTranscodeMode = "";
        if (experimentalBCN) {
            String bcnEmulation = graphicsDriverConfig.getOrDefault("bcnEmulation", "auto");
            boolean computeEmulation = computeBcnMode;
            String bcnEmulationCache = graphicsDriverConfig.getOrDefault("bcnEmulationCache", "1");
            boolean astcTranscode = requestedAstcTranscode;
            boolean etc2Transcode = requestedEtc2Transcode;

            // Winlator-Mali mapping: none->0, partial->1, full->2, auto->3.
            String emulateBcn;
            boolean computeLayerActive = false;
            switch (bcnEmulation.toLowerCase(Locale.ENGLISH)) {
                case "none":
                    emulateBcn = "0";
                    break;
                case "partial":
                    emulateBcn = "1";
                    break;
                case "full":
                    emulateBcn = "2";
                    computeLayerActive = computeEmulation;
                    break;
                default:
                    emulateBcn = "3";
                    computeLayerActive = computeEmulation;
                    break;
            }
            envVars.put("WRAPPER_EMULATE_BCN", emulateBcn);

            // ASTC/ETC2 are implemented by the compute layer even when BCn
            // emulation itself is set to none/partial. Previously those modes
            // wrote DISABLE_BCN_COMPUTE, which prevented transcoding entirely.
            boolean transcodeRequested = astcTranscode || etc2Transcode;
            if (computeEmulation && transcodeRequested) computeLayerActive = true;
            computeLayerActive = computeLayerActive && bcnLayerReady && !nativeBcnWrapper;
            if (computeLayerActive) {
                envVars.put("ENABLE_BCN_COMPUTE", "1");
                // Sessão de referência funcionando (RE3 com ASTC ok no Mali):
                // BCN_COMPUTE_AUTO=1, sem BCN_COMPUTE_IMAGE_VIEW e sem
                // BCN_PROFILE_TRANSFERS. AUTO=0 forçado + storage-image
                // deixava o layer em LOADED sem nenhum transcode ativo e com
                // texturas brancas; o auto do layer escolhe por textura.
                envVars.put("BCN_COMPUTE_AUTO", "1");
                if (transcodeRequested) {
                    envVars.put("BCN_LAYER_LOG_LEVEL", "info,error");
                    // Diagnóstico do wrapper (1 bloco por vkCreateDevice no
                    // logcat): mostra caps nativas e a linha "BCn: emulate=
                    // ASTC= transcode= cache=", necessária para descobrir por
                    // que o RE3 deixa umas texturas brancas com o layer em
                    // LOADED mas sem nenhum transcode ativo.
                    envVars.put("WRAPPER_DIAG", "1");
                }
            }
            else {
                envVars.put("DISABLE_BCN_COMPUTE", "1");
            }
            if (computeLayerActive) {
                if (astcTranscode) {
                    envVars.put("BCN_TRANSCODE_TO_ASTC", "1");
                    effectiveBcnTranscodeMode = "BCN→ASTC";
                }
                else if (etc2Transcode) {
                    envVars.put("BCN_TRANSCODE_TO_ETC2", "1");
                    effectiveBcnTranscodeMode = "BCN→ETC2";
                }
            }
            else if (transcodeRequested) {
                // Do not silently claim that a checked option reached Vulkan
                // when the layer, backend, or compatible wrapper is missing.
                effectiveBcnTranscodeMode = "BCN TRANSCODE ERROR";
            }
            envVars.put("WRAPPER_USE_BCN_CACHE", bcnEmulationCache);
            envVars.put("BCN_DISABLE_DISK_CACHE",
                    "0".equals(bcnEmulationCache) ? "1" : "0");
        }
        bcnTranscodeBaseMode = effectiveBcnTranscodeMode;
        bcnTelemetryRequested = effectiveBcnTranscodeMode.startsWith("BCN→");
        bcnTelemetryState = bcnTelemetryRequested ? "ARMED" : "";
        bcnTranscodeHudMode = bcnTelemetryRequested
                ? effectiveBcnTranscodeMode + " [ARMED]" : effectiveBcnTranscodeMode;
        sidebarHandler.removeCallbacks(bcnLayerMapProbe);
        if (bcnTelemetryRequested) sidebarHandler.post(bcnLayerMapProbe);
        Log.i("GraphicsDriverExtraction", "Experimental BCN="
                + experimentalBCN + ", layerReady=" + bcnLayerReady
                + ", nativeWrapper=" + nativeBcnWrapper
                + ", dedicatedWrapper=" + dedicatedBcnWrapper
                + ", transcode=" + (effectiveBcnTranscodeMode.isEmpty()
                        ? "off" : effectiveBcnTranscodeMode)
                + ", astcEnv=" + envVars.get("BCN_TRANSCODE_TO_ASTC")
                + ", etc2Env=" + envVars.get("BCN_TRANSCODE_TO_ETC2")
                + ", layer=" + bcnLayerAsset);
        if (frameRating != null) frameRating.onRendererDetected(getHudApiName());

        if (!vkbasaltConfig.isEmpty()) {
            envVars.put("ENABLE_VKBASALT", "1");
            envVars.put("VKBASALT_CONFIG", vkbasaltConfig);
        }
    }

    private static boolean isNativeBcnWrapper(String wrapper) {
        if (wrapper == null) return false;
        String id = wrapper.toLowerCase(Locale.ENGLISH);
        return id.contains("gamenative")
                || id.contains("kirimu") || id.contains("ref4ik");
    }

    private boolean isExperimentalPerformanceActive() {
        boolean experimental = container != null
                && "1".equals(container.getExtra("experimentalPerformance", "0"));
        if (shortcut != null && shortcut.hasExtra("experimentalPerformance")) {
            experimental = "1".equals(shortcut.getExtra("experimentalPerformance"));
        }
        return experimental;
    }

    private static String resolveBundledWrapperAsset(String wrapper) {
        String id = wrapper == null ? "" : wrapper.toLowerCase(Locale.ENGLISH);
        switch (id) {
            case "wrapper-default":
                return "graphics_driver/wrapper-default.tzst";
            case "wrapper-cmod-v1":
                return "graphics_driver/wrapper.tzst";
            case "wrapper-ludashi-2-4":
                return "graphics_driver/wrapper-ld24.tzst";
            default:
                return "graphics_driver/" + id + ".tzst";
        }
    }

    /**
     * Opt-in translator settings for games where raw throughput matters more
     * than strict x86 memory ordering. Kept behind the per-container master and
     * its own switch so a title that depends on stronger ordering can disable
     * the complete group without rebuilding its presets.
     */
    private static void applyTranslationTurbo(EnvVars vars) {
        for (String prefix : new String[]{"BOX64", "BOX86"}) {
            vars.put(prefix + "_DYNAREC_SAFEFLAGS", "0");
            vars.put(prefix + "_DYNAREC_FASTNAN", "1");
            vars.put(prefix + "_DYNAREC_FASTROUND", "1");
            vars.put(prefix + "_DYNAREC_X87DOUBLE", "0");
            vars.put(prefix + "_DYNAREC_BIGBLOCK", "3");
            vars.put(prefix + "_DYNAREC_STRONGMEM", "0");
            vars.put(prefix + "_DYNAREC_FORWARD", "1024");
            vars.put(prefix + "_DYNAREC_CALLRET", "1");
            vars.put(prefix + "_DYNAREC_WAIT", "1");
        }
        vars.put("FEX_TSOENABLED", "0");
        vars.put("FEX_VECTORTSOENABLED", "0");
        vars.put("FEX_MEMCPYSETTSOENABLED", "0");
        vars.put("FEX_HALFBARRIERTSOENABLED", "0");
        vars.put("FEX_X87REDUCEDPRECISION", "1");
        vars.put("FEX_MULTIBLOCK", "1");
        vars.put("FEX_DYNAMICL1CACHE", "1");
        vars.put("FEX_DISABLEL2CACHE", "1");
        vars.put("FEX_MAXINST", "10000");
        vars.put("FEX_SMC_CHECKS", "none");
    }

    /** Vulkan layer manifest for LayerCache Helix; library_path must match
     * the CMake output name so the loader finds the .so next to it. */
    private static final String PERF_CACHE_LAYER_JSON =
            "{\n" +
            "  \"file_format_version\": \"1.2.0\",\n" +
            "  \"layer\": {\n" +
            "    \"name\": \"VK_LAYER_PERFCACHE_HELIX\",\n" +
            "    \"type\": \"GLOBAL\",\n" +
            "    \"library_path\": \"libVkLayer_PerfCache.so\",\n" +
            "    \"api_version\": \"1.3.0\",\n" +
            "    \"implementation_version\": \"116\",\n" +
            "    \"description\": \"LayerCache Helix pipeline/texture caches\",\n" +
            "    \"disable_environment\": { \"PERFCACHE_DISABLE\": \"1\" },\n" +
            "    \"enable_environment\": { \"ENABLE_PERFCACHE_LAYER\": \"1\" }\n" +
            "  }\n" +
            "}\n";

    /** Deploys the LayerCache Helix manifest + library into imagefs and turns
     * the layer on through its enable_environment key. Cache directories are
     * pre-created under the guest's /usr/var/cache (writable by our uid) and
     * exported explicitly, so the layer never falls back to unwritable
     * hardcoded paths. VK_LAYER_PATH is composed with the launcher's default
     * layer directories instead of replacing them, keeping the BCN/util
     * layers discoverable while Helix is enabled. */
    private void installPerfCacheLayer(EnvVars envVars) {
        try {
            File rootDir = imageFs.getRootDir();
            File usrLib = new File(rootDir, "usr/lib");
            if (!usrLib.isDirectory()) usrLib.mkdirs();
            File soSrc = new File(getApplicationInfo().nativeLibraryDir, "libVkLayer_PerfCache.so");
            if (!soSrc.isFile()) return;
            File soDst = new File(usrLib, "libVkLayer_PerfCache.so");
            copyFile(soSrc, soDst);
            FileUtils.writeString(new File(usrLib, "VkLayer_perfcache.json"), PERF_CACHE_LAYER_JSON);

            // Pre-create the guest-visible cache dirs the layer defaults to.
            String cacheBase = rootDir.getPath() + "/usr/var/cache/layercache";
            for (String sub : new String[]{"pipeline", "textures"}) {
                File dir = new File(cacheBase, sub);
                if (!dir.isDirectory()) dir.mkdirs();
            }

            String implicitDir = rootDir.getPath() + "/usr/share/vulkan/implicit_layer.d";
            String explicitDir = rootDir.getPath() + "/usr/share/vulkan/explicit_layer.d";
            envVars.put("VK_LAYER_PATH",
                    usrLib.getAbsolutePath() + ":" + implicitDir + ":" + explicitDir);
            envVars.put("PERFCACHE_PIPELINE_CACHE_DIR", cacheBase + "/pipeline");
            envVars.put("PERFCACHE_TEXTURE_CACHE_DIR", cacheBase + "/textures");
            envVars.put("PERFCACHE_METRICS_PATH", cacheBase + "/metrics.jsonl");
            envVars.put("ENABLE_PERFCACHE_LAYER", "1");
            Log.i("GraphicsDriverExtraction", "LayerCache Helix deployed to " + usrLib);
        }
        catch (Exception e) {
            Log.w("GraphicsDriverExtraction", "PerfCache layer deployment failed", e);
        }
    }

    /** Starts the RAM Optimizer Xclipse engine. Less physical RAM selects a
     * more aggressive baseline because the shared CPU/GPU pool must be
     * reclaimed earlier and more frequently. */
    private void applyRamOptimizerProfile() {
        try {
            mRamOptSession = RamOptimizerXclipse.beginSession();
            if (RamOptimizerXclipse.nativeInit() != 0) return;

            boolean aggro = isExperimentalPerformanceActive()
                    && "1".equals(xperfConfig.get("ramAggro"));
            int baseline;
            if (aggro) baseline = RamOptimizerXclipse.PROFILE_AGGRESSIVE;
            else {
                ActivityManager activityManager = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
                long totalMB = 0;
                if (activityManager != null) {
                    ActivityManager.MemoryInfo memoryInfo = new ActivityManager.MemoryInfo();
                    activityManager.getMemoryInfo(memoryInfo);
                    totalMB = memoryInfo.totalMem / (1024L * 1024L);
                }
                if (totalMB > 0 && totalMB < 6500) baseline = RamOptimizerXclipse.PROFILE_AGGRESSIVE;
                else if (totalMB > 0 && totalMB < 10240) baseline = RamOptimizerXclipse.PROFILE_MEDIUM;
                else baseline = RamOptimizerXclipse.PROFILE_LIGHT;
            }
            RamOptimizerXclipse.setBaselineProfile(baseline);
            RamOptimizerXclipse.restoreBaseline();
            Log.i("GraphicsDriverExtraction", "RAM Optimizer baseline=" + baseline + " version=" + RamOptimizerXclipse.nativeVersion());
        }
        catch (Throwable t) {
            Log.w("GraphicsDriverExtraction", "RAM Optimizer unavailable", t);
        }
    }

    /**
     * RAM and VRAM are the same LPDDR pool on Exynos, so reporting the whole
     * device memory as VRAM invites games to over-commit and triggers OOM
     * kills of the Wine tree. Under the opt-in performance profile the real
     * device memory is queried: 8 GB-class devices get a conservative 2048 MB
     * cap, 12 GB-class (and above) keep the 4092 MB ceiling. Fixed-tier SoCs
     * back this up when the kernel report is unavailable.
     */    private int suggestVramCap() {
        ActivityManager activityManager = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        long totalMB = 0;
        if (activityManager != null) {
            ActivityManager.MemoryInfo memoryInfo = new ActivityManager.MemoryInfo();
            activityManager.getMemoryInfo(memoryInfo);
            totalMB = memoryInfo.totalMem / (1024 * 1024);
        }
        if (totalMB <= 0) {
            // Fall back to the known per-SoC RAM tier when the kernel report
            // is unavailable (e.g. some low-level builds).
            int typicalRamGB = GPUInformation.getTypicalRamGB();
            if (typicalRamGB <= 0) return 0;
            totalMB = typicalRamGB * 1024L;
        }
        // Android reports usable RAM below the marketing size (a "12 GB"
        // device usually lands near 11 GB), so split the tiers at 10 GB.
        int ramBased = totalMB >= 10240 ? 4092 : 2048;
        // A small GPU can never consume what the RAM formula grants, and a
        // big one should not be capped below its share: intersect both views.
        int modelCap = GPUInformation.getModelVramCapMB();
        return modelCap > 0 ? Math.min(ramBased, modelCap) : ramBased;
    }

    private void copyFile(File sourceFile, File destFile) throws IOException {
        try (InputStream inputStream = new FileInputStream(sourceFile);
             OutputStream outputStream = new FileOutputStream(destFile)) {
            byte[] buffer = new byte[1024];
            int length;
            while ((length = inputStream.read(buffer)) > 0) {
                outputStream.write(buffer, 0, length);
            }
        }
    }

    private void showTouchpadHelpDialog() {
        ContentDialog dialog = new ContentDialog(this, R.layout.touchpad_help_dialog);
        dialog.setTitle(R.string.touchpad_help);
        dialog.setIcon(R.drawable.icon_help);
        dialog.findViewById(R.id.BTCancel).setVisibility(View.GONE);
        dialog.show();
    }

//    @Override
//    public boolean dispatchGenericMotionEvent(MotionEvent event) {
//        return !winHandler.onGenericMotionEvent(event) && !touchpadView.onExternalMouseEvent(event) && super.dispatchGenericMotionEvent(event);
//    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent event) {
        // If we hold capture, captured events will come through the listener/Activity.
        // Skip the external path to avoid duplication.
        if (pointerCaptureRequested &&
                (event.getSource() & InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE) {
            // Still allow WinHandler to see gyro/other sources if you rely on it
            boolean handledByWinHandler = winHandler != null && winHandler.onGenericMotionEvent(event);
            return handledByWinHandler || super.dispatchGenericMotionEvent(event);
        }

        if (winHandler != null && winHandler.onGenericMotionEvent(event)) return true;
        if (touchpadView != null && touchpadView.onExternalMouseEvent(event)) return true;
        return super.dispatchGenericMotionEvent(event);
    }


    private static final int RECAPTURE_DELAY_MS = 10000; // 10 seconds

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {

        // A number of Android ROMs expose the physical secondary mouse button
        // as KEYCODE_BACK instead of MotionEvent.BUTTON_SECONDARY.  Consume it
        // only when its source is a mouse, leaving the phone Back key intact.
        int eventSource = event.getSource();
        boolean fromMouse = (eventSource & InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE
                || (eventSource & InputDevice.SOURCE_MOUSE_RELATIVE) == InputDevice.SOURCE_MOUSE_RELATIVE;
        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK
                && fromMouse
                && xServer != null) {
            boolean pressed = event.getAction() == KeyEvent.ACTION_DOWN;
            if (xServer.isRelativeMouseMovement()) {
                xServer.getWinHandler().mouseEvent(
                        pressed ? MouseEventFlags.RIGHTDOWN : MouseEventFlags.RIGHTUP,
                        0, 0, 0);
            }
            else if (pressed != xServer.pointer.isButtonPressed(Pointer.Button.BUTTON_RIGHT)) {
                if (pressed) xServer.injectPointerButtonPress(Pointer.Button.BUTTON_RIGHT);
                else xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_RIGHT);
            }
            return true;
        }

        // Handle the PlayStation or Xbox Home button to open the drawer
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            if (event.getKeyCode() == KeyEvent.KEYCODE_BUTTON_MODE || event.getKeyCode() == KeyEvent.KEYCODE_HOME) {
                openXServerDrawer(); // Method to open the XServer drawer
                return true; // Indicate the event was handled
            }
        }

        if (event.getAction() == KeyEvent.ACTION_DOWN && event.getKeyCode() == KeyEvent.KEYCODE_VOLUME_DOWN) {
            // Release pointer capture when Volume Down key is pressed
            if (touchpadView != null && pointerCaptureRequested) {
                touchpadView.releasePointerCapture();
                touchpadView.setOnCapturedPointerListener(null);
                pointerCaptureRequested = false;

                // Show toast message for pointer release
                showToast(this, "Pointer capture released for 10 seconds");

                // Schedule recapture after 10 seconds
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    if (touchpadView != null) {
                        touchpadView.requestPointerCapture();
                        touchpadView.setOnCapturedPointerListener(new View.OnCapturedPointerListener() {
                            @Override
                            public boolean onCapturedPointer(View view, MotionEvent event) {
                                handleCapturedPointer(event);
                                return true;
                            }
                        });
                        pointerCaptureRequested = true;

                        // Show toast message for pointer recapture
                        showToast(this, "Pointer re-captured. If not working, press again to release and re-capture");
                    }
                }, RECAPTURE_DELAY_MS);

                return true; // Indicate that the event was handled
            }
        }

        // Check if the floating view is visible and forward the key event to it**
//        if (winetricksFloatingView != null && winetricksFloatingView.getVisibility() == View.VISIBLE) {
//            if (winetricksFloatingView.dispatchKeyEvent(event)) {
//                return true; // Indicate the floating view handled the event
//            }
//        }

        if (inputControlsView != null && inputControlsView.onKeyEvent(event)) return true;
        if (winHandler != null && winHandler.onKeyEvent(event)) return true;
        if (xServer != null && xServer.keyboard.onKeyEvent(event)) return true;
        return !ExternalController.isGameController(event.getDevice())
                && super.dispatchKeyEvent(event);
    }



    public InputControlsView getInputControlsView() {
        return inputControlsView;
    }

    private void generateWineprefix() {
        Intent intent = getIntent();

        final File rootDir = imageFs.getRootDir();
        final File installedWineDir = imageFs.getInstalledWineDir();
        wineInfo = intent.getParcelableExtra("wine_info");
        envVars.put("WINEARCH", wineInfo.isWin64() ? "win64" : "win32");
        imageFs.setWinePath(wineInfo.path);

        final File containerPatternDir = new File(installedWineDir, "/preinstall/container-pattern");
        if (containerPatternDir.isDirectory()) FileUtils.delete(containerPatternDir);
        containerPatternDir.mkdirs();

        File linkFile = new File(rootDir, ImageFs.HOME_PATH);
        linkFile.delete();
        FileUtils.symlink(".."+FileUtils.toRelativePath(rootDir.getPath(), containerPatternDir.getPath()), linkFile.getPath());

        GuestProgramLauncherComponent guestProgramLauncherComponent = environment.getComponent(GuestProgramLauncherComponent.class);
//        guestProgramLauncherComponent.setGuestExecutable(wineInfo.getExecutable(this, false)+" explorer /desktop=shell,"+Container.DEFAULT_SCREEN_SIZE+" winecfg");
        guestProgramLauncherComponent.setGuestExecutable("wineboot -u explorer /desktop=shell,"+Container.DEFAULT_SCREEN_SIZE+" winecfg");

        preloaderDialog = new PreloaderDialog(this);
        guestProgramLauncherComponent.setTerminationCallback((status) -> Executors.newSingleThreadExecutor().execute(() -> {
            if (status > 0) {
                showToast(this, R.string.unable_to_install_wine);
                FileUtils.delete(new File(installedWineDir, "/preinstall"));
                AppUtils.restartApplication(this);
                return;
            }

            preloaderDialog.showOnUiThread(R.string.finishing_installation);
            FileUtils.writeString(new File(rootDir, ImageFs.WINEPREFIX+"/.update-timestamp"), "disable\n");

            File userDir = new File(rootDir, ImageFs.WINEPREFIX+"/drive_c/users/xuser");
            File[] userFiles = userDir.listFiles();
            if (userFiles != null) {
                for (File userFile : userFiles) {
                    if (FileUtils.isSymlink(userFile)) {
                        String path = userFile.getPath();
                        userFile.delete();
                        (new File(path)).mkdirs();
                    }
                }
            }

            String suffix = wineInfo.fullVersion()+"-"+wineInfo.getArch();
            File containerPatternFile = new File(installedWineDir, "/preinstall/container-pattern-"+suffix+".tzst");
            TarCompressorUtils.compress(TarCompressorUtils.Type.ZSTD, new File(rootDir, ImageFs.WINEPREFIX), containerPatternFile, MainActivity.CONTAINER_PATTERN_COMPRESSION_LEVEL);

            if (!containerPatternFile.renameTo(new File(installedWineDir, containerPatternFile.getName())) ||
                    !(new File(wineInfo.path)).renameTo(new File(installedWineDir, wineInfo.identifier()))) {
                containerPatternFile.delete();
            }

            FileUtils.delete(new File(installedWineDir, "/preinstall"));

            preloaderDialog.closeOnUiThread();
            AppUtils.restartApplication(this, R.id.main_menu_settings);
        }));
    }

    private static final String TAG = "DXWrapperExtraction";

    /** Locates a bundled dxwrapper archive, preferring xz over legacy zstd and
     *  probing the "-<build>" suffix variant used by some vkd3d packages. */
    private String findDxWrapperAsset(String base) {
        for (String candidate : new String[]{base + ".txz", base + ".tzst", base + "-0.txz", base + "-0.tzst"}) {
            try {
                getAssets().open(candidate).close();
                return candidate;
            }
            catch (IOException ignored) {}
        }
        return base + ".txz";
    }

    private boolean extractWrapperArchive(String assetFile, File dest) {
        TarCompressorUtils.Type type = assetFile.endsWith(".txz")
                ? TarCompressorUtils.Type.XZ : TarCompressorUtils.Type.ZSTD;
        return TarCompressorUtils.extract(type, this, assetFile, dest, onExtractFileListener);
    }

    private void extractDXWrapperFiles(String dxwrapper) {
        final String[] dlls = {"d3d10.dll", "d3d10_1.dll", "d3d10core.dll", "d3d11.dll", "d3d12.dll", "d3d12core.dll", "d3d8.dll", "d3d9.dll", "dxgi.dll"};

        File rootDir = imageFs.getRootDir();
        File windowsDir = new File(rootDir, ImageFs.WINEPREFIX + "/drive_c/windows");

        if (dxwrapper.contains("vkd3d")) {
            ContentProfile profile = contentsManager.getProfileByEntryName(dxwrapper);
            if (profile != null) {
                Log.d(TAG, "Applying user-defined VKD3D content profile: " + dxwrapper);
                contentsManager.applyContent(profile);
            } else {
                // Bundled archives may carry a "-<build>" suffix (e.g. vkd3d-2.14.1-0).
                String assetFile = findDxWrapperAsset("dxwrapper/" + dxwrapper);
                Log.d(TAG, "Extracting VKD3D archive: " + assetFile);
                extractWrapperArchive(assetFile, windowsDir);
            }
            Log.d(TAG, "Finished VKD3D extraction for " + dxwrapper);
        } else if (dxwrapper.contains("dxvk")) {
            Log.d(TAG, "Extracting DXVK wrapper files, version: " + dxwrapper);

            ContentProfile profile = contentsManager.getProfileByEntryName(dxwrapper);
            if (profile == null) {
                // Clean selector entries carry no "-<verCode>" suffix (e.g.
                // "dxvk-1.7.2"), which the entry-name parser cannot split;
                // fall back to matching by version name alone.
                profile = contentsManager.getProfile(ContentProfile.ContentType.CONTENT_TYPE_DXVK,
                        dxwrapper.substring(dxwrapper.indexOf('-') + 1));
            }
            if (profile != null) {
                Log.d(TAG, "Applying user-defined DXVK content profile: " + dxwrapper);
                contentsManager.applyContent(profile);
            } else {
                Log.d(TAG, "Extracting fallback DXVK archive: " + dxwrapper);
                extractWrapperArchive(findDxWrapperAsset("dxwrapper/" + dxwrapper), windowsDir);

                if (compareVersion(StringUtils.parseNumber(dxwrapper), "2.4") < 0) {
                    Log.d(TAG, "Extracting d8vk as part of DXVK version " + dxwrapper);
                    extractWrapperArchive(findDxWrapperAsset("dxwrapper/d8vk-" + DefaultVersion.D8VK), windowsDir);
                }
            }
        } else if (dxwrapper.contains("wined3d")) {
            Log.d(TAG, "Restoring original DLL files for wined3d.");
            restoreOriginalDllFiles(dlls);
        }
    }

    private void extractDDrawrapperFiles(String ddrawrapper) {
        final String[] dlls = {"ddraw.dll","d3dimm.dll"};
        final String[] glideDlls = {"glide.dll", "glide2x.dll", "glide3x.dll", "3DfxSpl.dll", "3DfxSpl2.dll", "3DfxSpl3.dll"};

        File rootDir = imageFs.getRootDir();
        File windowsDir = new File(rootDir, ImageFs.WINEPREFIX + "/drive_c/windows");

        Log.d("XServerDisplayActivity", "Deleting glide dlls before extraction");
        for (String glideDLL : glideDlls) {
            FileUtils.delete(new File(windowsDir + "/syswow64/" + glideDLL));
        }

        if (ddrawrapper.equals("wined3d") || ddrawrapper.equals("none")) {
            Log.d("XserverDisplayActivity", "Restoring original dlls for WineD3D/None");
            restoreOriginalDllFiles(dlls);
        }
        else {
            Log.d("XServerDisplayActivity", "Extracting ddrawrapper " + ddrawrapper);
            TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this, "ddrawrapper/" + ddrawrapper + ".tzst", windowsDir, onExtractFileListener);
        }

        Log.d("XServerDisplayActivity", "Extracting nglide wrapper");
        TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this, "ddrawrapper/nglide.tzst", windowsDir, onExtractFileListener);
    }


    private static int compareVersion(String varA, String varB) {
        final String[] levelsA = varA.split("\\.");
        final String[] levelsB = varB.split("\\.");
        int minLen = Math.min(levelsA.length, levelsB.length);
        int numA, numB;

        for (int i = 0; i < minLen; i++) {
            numA = Integer.parseInt(levelsA[i]);
            numB = Integer.parseInt(levelsB[i]);
            if (numA != numB)
                return numA - numB;
        }

        if (levelsA.length != levelsB.length)
            return levelsA.length - levelsB.length;

        return 0;
    }

    private void extractWinComponentFiles() {
        Log.d("XServerDisplayActivity", "Extracting WinComponents");
        File rootDir = imageFs.getRootDir();
        File windowsDir = new File(rootDir, ImageFs.WINEPREFIX+"/drive_c/windows");
        File systemRegFile = new File(rootDir, ImageFs.WINEPREFIX+"/system.reg");

        try {
            JSONObject wincomponentsJSONObject = new JSONObject(FileUtils.readString(this, "wincomponents/wincomponents.json"));
            ArrayList<String> dlls = new ArrayList<>();
            String wincomponents = shortcut != null ? shortcut.getExtra("wincomponents", container.getWinComponents()) : container.getWinComponents();

            Iterator<String[]> oldWinComponentsIter = new KeyValueSet(container.getExtra("wincomponents", Container.FALLBACK_WINCOMPONENTS)).iterator();

            for (String[] wincomponent : new KeyValueSet(wincomponents)) {
                if (wincomponent[1].equals(oldWinComponentsIter.next()[1]) && !firstTimeBoot) continue;
                String identifier = wincomponent[0];
                boolean useNative = wincomponent[1].equals("1");

                if (!wineInfo.isArm64EC() && identifier.contains("opengl") && useNative)
                    continue;

                if (useNative) {
                    TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, this, "wincomponents/"+identifier+".tzst", windowsDir, onExtractFileListener);
                }
                else {
                    JSONArray dlnames = wincomponentsJSONObject.getJSONArray(identifier);
                    for (int i = 0; i < dlnames.length(); i++) {
                        String dlname = dlnames.getString(i);
                        dlls.add(!dlname.endsWith(".exe") ? dlname+".dll" : dlname);
                    }
                }
                Log.d("XServerDisplayActivity", "Setting wincomponent " + identifier + " to " + String.valueOf(useNative));
                WineUtils.overrideWinComponentDlls(this, container, identifier, useNative);
                WineUtils.setWinComponentRegistryKeys(systemRegFile, identifier, useNative, this);
            }

            if (!dlls.isEmpty()) restoreOriginalDllFiles(dlls.toArray(new String[0]));
        }
        catch (JSONException e) {}
    }

    private void restoreOriginalDllFiles(final String... dlls) {
        File rootDir = imageFs.getRootDir();
        File windowsDir = new File(rootDir, ImageFs.WINEPREFIX+"/drive_c/windows");
        File system32dlls = null;
        File syswow64dlls = null;

        if (wineInfo.isArm64EC())
            system32dlls = new File(imageFs.getWinePath() + "/lib/wine/aarch64-windows");
        else if (!wineInfo.isWin64())
            system32dlls = new File(imageFs.getWinePath() + "/lib/wine/i386-windows");
        else
            system32dlls = new File(imageFs.getWinePath() + "/lib/wine/x86_64-windows");

        syswow64dlls = new File(imageFs.getWinePath() + "/lib/wine/i386-windows");


        for (String dll : dlls) {
            File srcFile = new File(system32dlls, dll);
            File dstFile = new File(windowsDir, "system32/" + dll);
            FileUtils.copy(srcFile, dstFile);
            // Win32 prefixes have no syswow64; avoid creating spurious WOW64 files
            if (wineInfo.isWin64()) {
                srcFile = new File(syswow64dlls, dll);
                dstFile = new File(windowsDir, "syswow64/" + dll);
                FileUtils.copy(srcFile, dstFile);
            }
        }
   }

    private boolean isGenerateWineprefix() {
        return getIntent().getBooleanExtra("generate_wineprefix", false);
    }

    private String getWineStartCommand() {
        // Initialize overrideEnvVars if not already done
        EnvVars envVars = getOverrideEnvVars();

        // Define default arguments
        String args = "";

        if (shortcut != null) {
            String execArgs = shortcut.getExtra("execArgs");
            execArgs = !execArgs.isEmpty() ? " " + execArgs : "";

            if (shortcut.path.endsWith(".lnk")) {
                args += "\"" + shortcut.path + "\"" + execArgs;
            } else {
                String exeDir = FileUtils.getDirname(shortcut.path);
                String filename = FileUtils.getName(shortcut.path);

                int dotIndex = filename.lastIndexOf(".");
                int spaceIndex = (dotIndex != -1) ? filename.indexOf(" ", dotIndex) : -1;

                if (spaceIndex != -1) {
                    execArgs = filename.substring(spaceIndex + 1) + execArgs;
                    filename = filename.substring(0, spaceIndex);
                }

                args += "/dir " + StringUtils.escapeDOSPath(exeDir) + " \"" + filename + "\"" + execArgs;
            }
        } else {
            // Append EXTRA_EXEC_ARGS from overrideEnvVars if it exists
            if (envVars.has("EXTRA_EXEC_ARGS")) {
                args += " " + envVars.get("EXTRA_EXEC_ARGS");
                envVars.remove("EXTRA_EXEC_ARGS"); // Remove the key after use
            } else {
                args += "\"wfm.exe\"";
            }
        }
        // Construct the final command
        String command = "winhandler.exe " + args;

        return command;
    }

    private String getExecutable() {
        String filename = "";
        if (shortcut != null) {
            filename = FileUtils.getName(shortcut.path);
        }
//        else if (isGenerateWineprefix()) {
//            filename = "wineboot.exe";
//        }
        else
            filename = "wfm.exe";
        return filename;
    }


    public XServer getXServer() {
        return xServer;
    }

    public WinHandler getWinHandler() {
        return winHandler;
    }

    public XServerView getXServerView() {
        return xServerView;
    }

    public Container getContainer() {
        return container;
    }

    public void setDXWrapper(String dxwrapper) {
        this.dxwrapper = dxwrapper;
    }

    public EnvVars getOverrideEnvVars() {
        if (overrideEnvVars == null) {
            overrideEnvVars = new EnvVars();
        }
        return overrideEnvVars;
    }

    private void changeWineAudioDriver() {
        if (!audioDriver.equals(container.getExtra("audioDriver"))) {
            File rootDir = imageFs.getRootDir();
            File userRegFile = new File(rootDir, ImageFs.WINEPREFIX+"/user.reg");
            try (WineRegistryEditor registryEditor = new WineRegistryEditor(userRegFile)) {
                if (audioDriver.equals("alsa") || audioDriver.equals("alsa-reflector")) {
                    registryEditor.setStringValue("Software\\Wine\\Drivers", "Audio", "alsa");
                }
                else if (audioDriver.equals("pulseaudio")) {
                    registryEditor.setStringValue("Software\\Wine\\Drivers", "Audio", "pulse");
                }
            }
            container.putExtra("audioDriver", audioDriver);
            container.saveData();
        }
    }

    private void applyGeneralPatches(Container container) {
        File rootDir = imageFs.getRootDir();
        File commonFont = new File(rootDir,
                ImageFs.WINEPREFIX + "/drive_c/windows/Fonts/Arial.TTF");
        File commonIcon = new File(rootDir,
                ImageFs.WINEPREFIX + "/drive_c/windows/system32/image.ico");
        boolean commonReady = commonFont.isFile() && commonIcon.isFile();
        String appliedCommonRevision = container.getExtra("commonPatchRevision", "");
        boolean commonUpdateRequired = !appliedCommonRevision.isEmpty()
                && !CONTAINER_COMMON_ASSET_REVISION.equals(appliedCommonRevision);
        if (!commonReady || commonUpdateRequired) {
            commonReady = TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD,
                    this, "container_pattern_common.tzst", rootDir)
                    && commonFont.isFile() && commonIcon.isFile();
        }
        if (commonReady) {
            container.putExtra("commonPatchRevision", CONTAINER_COMMON_ASSET_REVISION);
        }
        // PulseAudio is shared by every container. Extracting the same archive
        // for each container's first launch added avoidable work to Proton
        // boot1 and rewrote hundreds of files. Keep one verified shared copy.
        File pulseDir = new File(getFilesDir(), "pulseaudio");
        File pulseMarker = new File(pulseDir, ".asset-revision");
        File pulseBinary = new File(pulseDir, "libpulseaudio.so");
        if (!pulseBinary.isFile()
                || !PULSEAUDIO_ASSET_REVISION.equals(FileUtils.readString(pulseMarker))) {
            boolean extracted = TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD,
                    this, "pulseaudio.tzst", pulseDir);
            if (extracted && pulseBinary.isFile()) {
                FileUtils.writeString(pulseMarker, PULSEAUDIO_ASSET_REVISION);
            }
        }
        WineUtils.applySystemTweaks(this, wineInfo);
        container.putExtra("graphicsDriver", null);
        container.putExtra("desktopTheme", null);
    }

    private void changeFrameRatingVisibility(Window window, Property property) {
        if (frameRating == null) return;

        if (property != null) {
            String propertyName = property.nameAsString();
            if (frameRatingWindowId == -1 && (propertyName.contains("_UTIL_LAYER") || propertyName.contains("_MESA_DRV"))) {
                frameRatingWindowId = window.id;
                Log.d("XServerDisplayActivity", "Showing hud for Window " + window.getName());
                frameRating.onRendererDetected(getHudApiName());
                frameRating.update();
            }
            if (propertyName.contains("_UTIL_LAYER_ENGINE_NAME")
                    || propertyName.contains("_MESA_DRV_ENGINE_NAME"))
                frameRating.onRendererDetected(normalizeHudApi(property.toString()));
            if (propertyName.contains("_UTIL_LAYER_GPU_NAME")) {
                frameRating.setGpuName(property.toString());
            }
        }
        else if (frameRatingWindowId == window.id) {
            frameRatingWindowId = -1;
            Log.d("XServerDisplayActivity", "Hiding hud for Window " + window.getName());
            frameRating.onRendererGone();
            frameRating.reset();
        }
    }

    private void removeStaleVkd3dDlls() {
        File windowsDir = new File(imageFs.getRootDir(), ImageFs.WINEPREFIX + "/drive_c/windows");
        String sysArch = wineInfo.isArm64EC() ? "aarch64-windows" : (!wineInfo.isWin64() ? "i386-windows" : "x86_64-windows");
        File systemSource = new File(imageFs.getWinePath() + "/lib/wine/" + sysArch);
        File wow64Source = new File(imageFs.getWinePath() + "/lib/wine/i386-windows");
        for (String dll : new String[]{"d3d12.dll", "d3d12core.dll"}) {
            if (!new File(systemSource, dll).exists())
                FileUtils.delete(new File(windowsDir, "system32/" + dll));
            if (wineInfo.isWin64() && !new File(wow64Source, dll).exists())
                FileUtils.delete(new File(windowsDir, "syswow64/" + dll));
        }
    }

    private boolean hasRequiredDxWrapperFiles(boolean requireVkd3d) {
        File windowsDir = new File(imageFs.getRootDir(),
                ImageFs.WINEPREFIX + "/drive_c/windows");
        ArrayList<File> architectureDirs = new ArrayList<>();
        architectureDirs.add(new File(windowsDir, "system32"));
        if (wineInfo.isWin64()) architectureDirs.add(new File(windowsDir, "syswow64"));
        for (File directory : architectureDirs) {
            if (!new File(directory, "dxgi.dll").isFile()
                    || !new File(directory, "d3d11.dll").isFile()) return false;
            if (requireVkd3d && !new File(directory, "d3d12.dll").isFile()) return false;
        }
        return true;
    }

    private String getHudApiName() {
        String api;
        if ("gdi".equals(wineRenderer)) api = "GDI";
        else if ("gl".equals(wineRenderer)) api = "OpenGL / WineD3D";
        else {
            String wrapper = dxwrapper == null ? "" : dxwrapper.toLowerCase(Locale.US);
            if (wrapper.contains("vkd3d")) api = "Vulkan / VKD3D";
            else if (wrapper.contains("dxvk")) api = "Vulkan / DXVK";
            else if (wrapper.contains("wined3d")) api = "Vulkan / WineD3D";
            else api = wrapper.isEmpty() ? "Vulkan" : "Vulkan / " + wrapper;
        }
        return appendBcnHudMode(api);
    }

    private String appendBcnHudMode(String api) {
        String mode = bcnTranscodeHudMode;
        return mode == null || mode.isEmpty() ? api : api + " · " + mode;
    }

    private void handleBcnTelemetryLine(String line) {
        if (!bcnTelemetryRequested || line == null) return;
        String lower = line.toLowerCase(Locale.US);
        if (lower.contains("texturecompressionetc2 is not supported")) {
            updateBcnTelemetryState("ERROR: ETC2 UNSUPPORTED");
            return;
        }
        if (lower.contains("texturecompressionastc_ldr is not supported")) {
            updateBcnTelemetryState("ERROR: ASTC UNSUPPORTED");
            return;
        }
        if (bcnTranscodeBaseMode.contains("ASTC")
                && (lower.contains("shaderint8 is not supported")
                || lower.contains("shaderint16 is not supported"))) {
            updateBcnTelemetryState("ERROR: ASTC SHADER FEATURE");
            return;
        }
        if ((lower.contains("encode_etc2") || lower.contains("etc2"))
                && (lower.contains("failed") || lower.contains("error"))) {
            updateBcnTelemetryState("ERROR: ETC2 ENCODE");
            return;
        }
        if ((lower.contains("encode_astc") || lower.contains("astc"))
                && (lower.contains("failed") || lower.contains("error"))) {
            updateBcnTelemetryState("ERROR: ASTC ENCODE");
            return;
        }
        if ((lower.contains("failed to create image")
                || lower.contains("failed to create image view")
                || lower.contains("failed to allocate staging buffer"))
                && (bcnTranscodeBaseMode.contains("ETC2")
                || bcnTranscodeBaseMode.contains("ASTC"))) {
            updateBcnTelemetryState("ERROR: TRANSCODE RESOURCE");
            return;
        }
        boolean expectedEtc2 = bcnTranscodeBaseMode.contains("ETC2");
        boolean expectedAstc = bcnTranscodeBaseMode.contains("ASTC");
        boolean transcodeLine = lower.contains("transcode:")
                || lower.contains("encode_etc2_compute")
                || lower.contains("encode_astc_compute");
        if (transcodeLine && (lower.contains("transcode:")
                || (expectedEtc2 && lower.contains("etc2"))
                || (expectedAstc && lower.contains("astc")))) {
            updateBcnTelemetryState("ACTIVE");
        }
    }

    private void updateBcnTelemetryState(String state) {
        if (!bcnTelemetryRequested || state == null || state.isEmpty()) return;
        String current = bcnTelemetryState;
        if (("ACTIVE".equals(current) || current.startsWith("ERROR"))
                && "LOADED".equals(state)) return;
        if (state.equals(current)) return;
        bcnTelemetryState = state;
        bcnTranscodeHudMode = bcnTranscodeBaseMode + " [" + state + "]";
        Log.i("BcnTelemetry", bcnTranscodeHudMode);
        runOnUiThread(() -> {
            if (frameRating != null) frameRating.onRendererDetected(getHudApiName());
        });
    }

    private boolean isBcnLayerMapped() {
        for (String pid : ProcessHelper.listRunningWineProcesses()) {
            File maps = new File("/proc/" + pid + "/maps");
            try (BufferedReader reader = new BufferedReader(new FileReader(maps))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.contains("libbcn_layer.so")) return true;
                }
            }
            catch (IOException ignored) {
            }
        }
        return false;
    }

    private static boolean isBaseWineProcess(String name) {
        if (isStrictBaseWineProcess(name)) return true;
        // Crash Defender/reporters may deliberately survive the game and must
        // not keep a completed shortcut session open forever.
        return name.contains("crash") || name.contains("defender")
                || name.contains("werfault") || name.contains("winedbg");
    }

    private static boolean isStrictBaseWineProcess(String name) {
        switch (name) {
            case "wine":
            case "wine64":
            case "wine-preloader":
            case "wine64-preloader":
            case "wineserver":
            case "start.exe":
            case "services.exe":
            case "winedevice.exe":
            case "wineboot.exe":
            case "explorer.exe":
            case "plugplay.exe":
            case "svchost.exe":
            case "rpcss.exe":
            case "winhandler.exe":
            case "wfm.exe":
            case "tabtip.exe":
            case "winemenubuilder.exe":
            case "winedbg.exe":
                return true;
            default:
                return false;
        }
    }

    private void writeWineLifecycleLogIfNeeded() {
        if (lifecycleLogWritten || preferences == null
                || !preferences.getBoolean("enable_wine_lifecycle_logs", false)) return;

        ArrayList<String> processes = ProcessHelper.listRunningWineProcessDetails();
        ArrayList<String> nonStandard = new ArrayList<>();
        for (String detail : processes) {
            int separator = detail.indexOf(':');
            String name = (separator >= 0 ? detail.substring(separator + 1) : detail)
                    .toLowerCase(Locale.US).trim();
            if (!isStrictBaseWineProcess(name)) nonStandard.add(detail);
        }
        if (!automaticLifecycleClose && nonStandard.isEmpty()) return;

        File directory = new File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS), "WinXclipse/logs");
        if (!directory.exists() && !directory.mkdirs()) {
            Log.w("WineLifecycle", "Could not create lifecycle log directory: " + directory);
            return;
        }
        String stamp = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)
                .format(new Date());
        File logFile = new File(directory, "wine_lifecycle_" + stamp + ".txt");
        StringBuilder report = new StringBuilder(4096);
        report.append("WinXclipse Wine lifecycle diagnostic\n")
                .append("Time: ").append(stamp).append('\n')
                .append("Shortcut: ").append(shortcutName == null ? "(container)" : shortcutName)
                .append('\n')
                .append("Automatic close: ").append(automaticLifecycleClose).append('\n')
                .append("Reason: ").append(lifecycleCloseReason.isEmpty()
                        ? "Session stopped while non-standard guest processes were active."
                        : lifecycleCloseReason).append('\n')
                .append("Observed game/application process: ")
                .append(observedShortcutApplication).append("\n\n")
                .append("Processes that were about to be closed:\n");
        if (processes.isEmpty()) report.append("(none)\n");
        else for (String process : processes) report.append("- ").append(process).append('\n');
        report.append("\nNon-standard processes:\n");
        if (nonStandard.isEmpty()) report.append("(none; only base/crash-defender processes remained)\n");
        else for (String process : nonStandard) report.append("- ").append(process).append('\n');

        List<String> recentLines = ProcessHelper.getRecentDebugLines();
        int first = Math.max(0, recentLines.size() - 200);
        report.append("\nLast guest output lines:\n");
        if (first == recentLines.size()) report.append("(none)\n");
        else for (int index = first; index < recentLines.size(); index++)
            report.append(recentLines.get(index)).append('\n');

        lifecycleLogWritten = FileUtils.writeString(logFile, report.toString());
        if (lifecycleLogWritten)
            Log.i("WineLifecycle", "Diagnostic saved to " + logFile.getAbsolutePath());
        else Log.w("WineLifecycle", "Failed to save diagnostic to " + logFile);
    }

    private String normalizeHudApi(String value) {
        if (value == null) return getHudApiName();
        String clean = value.replace("\u0000", "").trim();
        String lower = clean.toLowerCase(Locale.US);
        String api;
        if ("gdi".equals(wineRenderer)) api = "GDI";
        else if ("gl".equals(wineRenderer)) api = "OpenGL / WineD3D";
        else if (lower.contains("vkd3d") || lower.contains("d3d12")) api = "Vulkan / VKD3D";
        else if (lower.contains("dxvk")) api = "Vulkan / DXVK";
        else if (lower.contains("wined3d")) api = "Vulkan / WineD3D";
        else if (lower.contains("opengl")) api = "OpenGL";
        else api = clean.isEmpty() ? "Vulkan" : clean;
        return appendBcnHudMode(api);
    }

    private String getPhoneGpuName() {
        try {
            String name = GPUInformation.getRendererName();
            if (name == null || name.trim().isEmpty()
                    || "unknown".equalsIgnoreCase(name.trim())) {
                GPUInformation.invalidateCache();
                name = GPUInformation.getRendererName();
            }
            return name == null || name.trim().isEmpty()
                    || "unknown".equalsIgnoreCase(name.trim())
                    ? "Unknown GPU" : name.trim();
        }
        catch (Throwable error) {
            Log.w("XServerDisplayActivity", "Unable to query phone GPU", error);
            return "Unknown GPU";
        }
    }

    private void scheduleSecondaryExecution(String secondaryExec, int delaySeconds) {
        if (winHandler != null) {
            winHandler.execWithDelay(secondaryExec, delaySeconds);
            Log.d("XServerDisplayActivity", "Scheduled secondary execution: " + secondaryExec + " with delay: " + delaySeconds);
        } else {
            Log.e("XServerDisplayActivity", "WinHandler is null, cannot schedule secondary execution.");
        }
    }

    public String getScreenEffectProfile() {
        return screenEffectProfile;
    }

    public void setScreenEffectProfile(String screenEffectProfile) {
        this.screenEffectProfile = screenEffectProfile;
    }


    private void setupAudioDeviceListener() {
        // Get the Android AudioManager system service
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);

        // Create the callback instance
        audioDeviceCallback = new AudioDeviceCallback() {
            @Override
            public void onAudioDevicesAdded(AudioDeviceInfo[] addedDevices) {
                logAudioDevices("added", addedDevices);
                if (isRecorderSubmixOnly(addedDevices)) {
                    Log.i("AudioDeviceCallback",
                            "Recorder submix change only; keeping PulseAudio daemon alive.");
                    return;
                }
                if (environment != null) {
                    ALSAServerComponent alsaComponent = environment.getComponent(ALSAServerComponent.class);
                    if (alsaComponent != null) {
                        Log.d("AudioDeviceCallback", "Audio device added. Triggering rebuild.");
                        alsaComponent.notifyAudioDeviceChanged();
                    }
                    schedulePulseAudioRestart();
                }
            }

            @Override
            public void onAudioDevicesRemoved(AudioDeviceInfo[] removedDevices) {
                logAudioDevices("removed", removedDevices);
                if (isRecorderSubmixOnly(removedDevices)) {
                    Log.i("AudioDeviceCallback",
                            "Recorder submix change only; keeping PulseAudio daemon alive.");
                    return;
                }
                if (environment != null) {
                    ALSAServerComponent alsaComponent = environment.getComponent(ALSAServerComponent.class);
                    if (alsaComponent != null) {
                        Log.d("AudioDeviceCallback", "Audio device removed. Triggering rebuild.");
                        alsaComponent.notifyAudioDeviceChanged();
                    }
                    schedulePulseAudioRestart();
                }
            }
        };

        // Register the callback with the system.
        audioManager.registerAudioDeviceCallback(audioDeviceCallback, new Handler(Looper.getMainLooper()));
    }

    /** Screen recording announces a virtual capture device. Restarting the
     * PulseAudio daemon for that alone is what mutes the game while
     * recording, so submix-only changes are ignored. */
    private static boolean isRecorderSubmixOnly(AudioDeviceInfo[] devices) {
        if (devices == null || devices.length == 0) return false;
        for (AudioDeviceInfo device : devices) {
            if (device == null) continue;
            if (device.getType() != AudioDeviceInfo.TYPE_REMOTE_SUBMIX) return false;
        }
        return true;
    }

    private static void logAudioDevices(String action, AudioDeviceInfo[] devices) {
        if (devices == null) return;
        for (AudioDeviceInfo device : devices) {
            if (device == null) continue;
            CharSequence name = device.getProductName();
            Log.i("AudioDeviceCallback", "Audio device " + action + ": type=" + device.getType()
                    + " sink=" + device.isSink() + " source=" + device.isSource()
                    + " name=" + (name != null ? name : "?"));
        }
    }

    /** PulseAudio's AAudio sink goes stale on route changes (BT, headset,
     * USB audio, recorder submix) and stays silent until recreated. */
    private void schedulePulseAudioRestart() {
        if (environment == null || isFinishing() || isDestroyed()) return;
        if (environment.getComponent(PulseAudioComponent.class) == null) return;
        handler.removeCallbacks(pulseAudioRestartRunnable);
        handler.postDelayed(pulseAudioRestartRunnable, PULSE_RESTART_DEBOUNCE_MS);
    }
}


