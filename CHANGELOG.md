# Changelog

All notable changes between WinXclipse releases, newest first.

## 0.9

### New

- **AMD FSR 1.1 support**: full FSR1 pipeline (EASU upscaling + RCAS sharpening) ported from the official AMD shader (`ffx_fsr1.h` v1.20210629). Configurable per container in the Display section and live-applied from the in-session sidebar Display menu; EASU always runs first in the effect chain. Five quality presets from Fidelity to Ultra, each forcing a matching internal render scale (1.5x/1.7x/2.0x), with letterbox-aware EASU mapping of the scene content region.
- Video configuration tab (ported from Winlator Ludashi) for containers and shortcuts: renderer selection (Vulkan/OpenGL/GDI) as the primary graphics backend switch, GPU name, present mode, texture filtering (now hosting the FSR entry), and a red/blue channel swap option.
- Audio driver configuration dialog with volume control; MIDI soundfont selection moved inside it. PulseAudio becomes the default driver.
- Shortcut artwork system with three configurable sources — **Browser** (SteamGridDB search with EXE-icon fallback, default on first boot), **EXE icon**, and **Custom** — settable globally or overridden per shortcut.
- New dark glassy touchscreen control style: gradient button bodies with thin light borders, four-petal D-pad with outward chevron arrows and per-direction press highlighting, tick-marked analog sticks, and a matching trackpad.
- Complete gamepad skin set for the touchscreen controls as image assets — face buttons (A/B/X/Y), shoulders (L1/R1), triggers (L2/R2), stick clicks (L3/R3), Start/Select, a four-way D-pad, an analog stick base ring with tick marks, and a trackpad — selectable per element in the controls editor (D-pad, stick, and trackpad elements accept skins) and pre-applied to both bundled Virtual Gamepad profiles.
- **Refresh rate** option (Auto/60/90/120/144 Hz) in the video configuration dialog for containers and shortcuts; requested from the display when the game session starts.
- Update checks run on every launch, prompting at most once every 4 days when a newer GitHub release is available.
- Exclusive XInput mode for containers and shortcuts; when off, XInput and DInput can be enabled together with the simultaneous-use warning.
- Contextual **?** help buttons across theme, rotation lock, performance, DXVK/VKD3D, video, and graphics dialogs.
- Experimental Performance tuning dialog with individual feature switches; companion stacks integrated into our own build instead of prebuilt binaries: the MdiEx runtime (device classification, Xclipse policy engine, scheduler nice-biasing, memory shim in passive mode), the NRAMV unified-memory manager driven from Java, and LayerCache Helix built as our own Vulkan layer library for pipeline warmup and texture caching.
- Bundled packages: `Dxvk-2.6.2-arm64ec-gplasync.wcp`, `dxvk-1.7.3-async`, and `vkd3d-proton-3.0.1b.wcp` install automatically on first boot; dedicated ARM64EC DXVK builds selectable (2.3.1-arm64ec-gplasync, 1.10.3-arm64ec-async).
- DDraw wrapper gained a **None** option restoring the builtin ddraw.dll, alongside CnC-DDraw/Dd7To9/WineD3D.
- Guest stdout/stderr piped to logcat again, so Wine debug channels produce readable backend logs.
- HUD setting to disable the blinking RAM warning and its high-memory dialog.
- HUD gains SOC load and GPU temperature elements selectable in the HUD config dialog.

### Changed

- Max Device Memory exists only in the Graphics Driver configuration; the DDraw wrapper moved inside the combined DXVK + VKD3D dialog (default WineD3D); default DXVK changed to async-1.10.3; BCN layer files left by v0.8.x are cleaned up on update.
- Aggressive startup selection protects winebus/winehid/MountMgr/PlugPlay while disabling non-critical Wine services; corrected the Wine `Ndis` registry key.
- Separate WoW64 CPU list restored so 32-bit processes can be pinned to different cores than 64-bit processes.
- FEXCore presets match the bionic base: Intermediate sets `FEX_X87REDUCEDPRECISION=1`; Performance adds `FEX_DYNAMICL1CACHE=1` + `FEX_DISABLEL2CACHE=1`.
- BCn emulation controls match the Winlator-Mali reference exactly (none/partial/full/auto → `WRAPPER_EMULATE_BCN` 0/1/2/3); wrappers with native BCN handling (GameNative, Kirimu, Ref4ik-v6) no longer load the shared Leegao layer on top of their own implementation, and stale layer files are removed when switching; Kirimu locks its software-only profile.
- Per-model Exynos/Xclipse tuning: Experimental Performance VRAM cap follows WGP count (2048 MB fixed on 530/540/550/920, RAM-following on 940/950, full 4092 MB on 960); BCn backend defaults to software on RDNA2 and compute on RDNA3/RDNA4; low-WGP RDNA2 defaults to BCn-to-ASTC transcoding; CPU cluster detection reads cpufreq data so WoW64 pinning lands on performance cores, with the LITTLE-less Exynos 2600 allowed to use all ten cores.
- Vendored internals rebranded (`rox_`/`wxp_` prefixes, file renames) with wire-format env keys preserved.
- First boot installs base files immediately; storage permissions are requested after installation completes.
- Follow-system theme enabled by default; landscape lock option added.
- Texture filter gains a fourth **None** mode (bilinear/nearest/FSR/None); FSR choices now persist as `rendererFilterMode`/`fsrUpscale`/`fsrQuality` runtime extras, with the legacy `fsrMode` key only read once to migrate old configs (quality modes imply EASU upscale).
- Gyroscope input is enabled by default.
- LayerCache Helix (Cache Xclipse pipeline/texture caches) defaults to off under Experimental Performance; enable it in the tuning dialog.
- About dialog and release notes credit FEX-Emu and AMD FidelityFX Super Resolution.

### Performance

- Control overlay shaders are cached instead of allocated per frame; rumble/turbo poller reduced from ~200 to ~60 wakeups/s; playtime persisted every 30 s; cover art decoded downsampled; failed artwork lookups remembered per session; debug logging removed from controller motion-event and stylus hover hot paths.
- Native X11 blits (`copyAreaOp`, `fillRect`, `drawLine`) process whole 32-bit pixels instead of byte loops — NEON-friendly and lighter on the shared LPDDR bus.
- The evshim input shim caches its `/proc/self/fd/N` procfs verdict per file descriptor instead of resolving it on every read/ioctl.
- ALSA pacer thread no longer requests maximum SCHED_FIFO; EGL display resolved once across EGLImage calls; JNI method lookups run once.
- Unified-memory-aware VRAM cap under Experimental Performance when Max Device Memory is unset (2048 MB on 8 GB-class devices, 4092 MB on 12 GB-class), backed by known per-SoC RAM tiers.
- Opt-in `vblank_mode=0` skips vsync waits for OpenGL/zink titles; per-frame `glGetError` validation is debug-only now; GPU renderer detection results are cached.

### Fixed

- ARM64EC guests dying silently before creating any window under Proton arm64ec + FEXCore: the launcher requested the legacy `libarm64ecfex.dll` bridge instead of `libwow64fex.dll`.
- Builtin wined3d pinned to the GL renderer under ARM64EC Proton (its Vulkan swapchain path died with "Unsupported alpha mode" + failed `vkDestroySurfaceKHR` on Xclipse devices); `extra_libs.tzst` extracted whenever missing so the GL renderer can load libGL.
- Bundled content installer re-ran on every launch (success-marker check dropped during refactor); uninstall clears markers again.
- memshim malloc interpose leaking into libwinlator.so caused an emutls stack overflow (macro name mismatch MDIEX_ vs WXP_).
- Inverted exclusive XInput behavior fixed: exclusive mode locks XInput-only; controller-fix installation verifies all three files including dinput.dll before marking complete, retrying otherwise.
- Startup Selection inverted condition (Essential vs Aggressive behaved identically); playtime autosave double-scheduling after onResume.
- Video tab renderer choice persists (startup no longer forces GL back); GPU Name no longer leaks from the Wine-tab spoof spinner; VKD3D extraction matches "-0"-suffixed bundled archives; dead DXVK 1.7.2 duplicate entries cleaned up automatically.
- NPE confirming container creation and opening Video Config in create-container mode; missing/corrupted JSON assets and local files surface as handled errors instead of NullPointerExceptions; downloads no longer crash when the Contents screen closes mid-transfer.
- ? help popups measure wrapped text correctly and use themed backgrounds on dark/light; HUD °C mojibake; follow-system-theme checkbox default mismatch between UI and runtime.
- Container failing to start on Android 10+ with `error=13, Permission denied` when launching the Wine/PulseAudio binaries: `targetSdkVersion` lowered to 28, keeping exec() of binaries inside the app data directory allowed under the Android W^X policy.
- Downloads screen offered removal of the APK-embedded bundles (Box64/DXVK/FEXCore/VKD3D installed on first boot), which containers depend on: removal is no longer offered for embedded content (Info stays), and every bundled install is recorded so future bundles are protected automatically.
- Proton catalog entries showing blank names (the proton-11.0-2 sdk35 packages) or raw file names: bundled catalog metadata is now authoritative, giving clean names to 11.0-2 (arm64ec/x86_64) and 9.0-x86_64.
- Wine/Proton `.tzst` archives saved straight into Downloads (no embedded content profile) install normally now: a profile is synthesized from the archive's bin/lib layout, and the import picker accepts every common zstd MIME type.
- Xclipse driver download catalog sources releases from ExynosTools and the WinXclipse `drivers_0.9` release (the retired MdiEx repository and its cached entries are dropped).

## 0.8.6

### New

- Downloads area (renamed from Contents) with dynamically refreshed Xclipse driver catalogs from ExynosTools and MdiEx releases, plus custom wrapper installation from a local file or HTTP/HTTPS URL (`.tzst`, `.tstz`, `.tzts`, `.zst`, or direct `.so`), named by the user as `Wrapper-Name`; remote catalogs stay cached when refresh fails.
- Real GPU renderer detection recognizing the Xclipse family (530, 540, 550, 920, 940, 950) with AMD RDNA generation mapping.
- Replaced the BCn runtime layer with the custom Leegao layer and added the Mali reference wrapper as a separate **Wrapper-BCN** option; wrappers without native BCN controls route through the shared Leegao compute layer, with the full activation flow ported (automatic/full emulation, compute/software selection, cache control, ASTC/ETC2 transcoding); layer enabled only after library and manifest verification, with stale files removed.
- Reworked in-session sidebar: time/battery header, pause/resume, help, terminal, and power-style exit actions; grouped input options; dedicated Controller action toggling touchscreen controls; Virtual Gamepad 2 profile; direct HUD enable/disable with separate config; FPS limiter (Unlimited/30/45/60/90/120) applied at guest presentation stage.
- Lightweight expandable Task Manager in the sidebar: total CPU and per-core frequencies, RAM usage, process list with PID/memory, and window focus/terminate/CPU-affinity actions.
- Opt-in Experimental Performance checkbox applying compatibility/performance environment flags only when selected.
- Three installable package identities (Main `com.win.xclipse`, AnTuTu, Geekbench 6) with providers keyed to the active application ID.

### Changed

- Runtime cleanup: Box64 `0.4.3-260519` bundled (older removed), FEXCore 2608 default (2505/2507 removed), DXVK `1.11.1-sarek` added while 2.2.116 and bundled GPLAsync variants removed; new Settings/containers/shortcuts start with the Compatibility FEXCore preset; VKD3D starts None; Show FPS enabled by default; ASTC/ETC2 transcoding opt-in.
- Bundled wrappers re-extracted after an application update; replaced user-installed wrappers re-applied on next launch.

### Fixed

- HUD renderer/API detection now reports DXVK/VKD3D/WineD3D/Vulkan/OpenGL from the active runtime instead of forced fallbacks, and wrapper families separately from the graphics API; unrelated destroyed windows no longer clear HUD renderer state.
- Battery power reporting uses real charge-counter and voltage data when exposed.
- Repeated online artwork retries while scrolling; recycled-bitmap risk between artwork download and reload; corrupted stored shortcut inputs handled safely.

## 0.8.5

### New

- Dynamic WinXclipse release catalogs for Wine/Proton, FEXCore, Box64, DXVK, and VKD3D packages, installed from `.wcp`, `.wcp.xz`, and compatible archives, with numeric download/install progress.
- Proton 10/11 and ARM64EC runtime handling improvements.
- FEXCore preset management (create/rename/duplicate/delete/import/export) selectable in Settings, Containers, and Shortcuts.
- Combined DXVK + VKD3D configuration; advanced wrapper configuration with Vulkan information, Present Modes, memory controls, and texture-transcoding options.
- Winlator HUD and MangoHud selection; automatic update checking through GitHub releases.
- New visual identity: AMOLED dark mode, ice-white light mode, purple accents; reorganized app/container navigation; USB-C/file-provider fixes and file-manager performance improvements.

## 0.7.6

- First organized source-based build: earlier APK-only modifications moved into the source tree and the project identity was established.
- Added the Xclipse GPU Drivers area, backend-log fixes, driver-selector cleanup, and Exynos/Xclipse-oriented metadata.

## 0.7.5

- Initial WinXclipse visual identity; expanded the original wrapper selection through APK-level modifications.
