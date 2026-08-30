# Changelog

All notable changes between WinXclipse releases, newest first.

## 0.9.1

### New

- Community Configs catalog backed by the GitHub `community-configs` release, with automatic ZIP download, exact/fuzzy local-content resolution, persistent staging, and offline fallback through a saved index and validated cache.
- Community game search in the toolbar filters cards by normalized game name, including case, spacing, punctuation, and Arabic/Roman sequel-number equivalence.
- Community exports carry the required game name, Discord handle, expected FPS, fixed detected phone model, hardware metadata, notes, content requirements, and an optional embedded cover in a version-free ZIP filename.
- VSync modes (`100%`, `50%`, and `Off`) are available for containers, shortcuts, and the live Display sidebar.
- External Android storage and configured Wine drive roots are available from File Manager.
- Input Controls now includes controller auto-grab (plug and play), master vibration, and vibration testing.
- The Winlator HUD includes an independent battery-percentage option.
- The first visit to Community Configs explains how to export a tested config and links directly to the project Discord for submission.
- The About dialog now exposes the project Discord beside the GitHub link.
- Experimental Performance adds a per-game Translation Turbo switch and a manual 6144 MB unified-memory VRAM cap.

### Changed

- Community entries are grouped by normalized game identity. Matching configs share the first embedded cover found before browser artwork is requested, support a user-selected cover, and expose device choices plus on-demand container details in an English AMOLED interface.
- Bundled Box64 is `0.4.3-260519-024717c`; old `0.4.4` selections migrate to the bundled version and installed-content detection uses the exact content identity.
- New-container input defaults are opt-in, the WoW64 CPU-list fallback uses all available cores, and lower-memory devices select stronger RAM-reclamation baselines.
- MangoHUD mode uses the stable Android-native HUD path adapted from Bannerlator instead of injecting an unavailable guest Vulkan layer.
- Runtime/content names are deduplicated and existing containers migrate automatically when an installed profile's old identifier is normalized.
- The obsolete touchscreen-haptic option and runtime feedback path were removed; physical-controller vibration remains independently configurable.
- Existing Community Config ZIPs recover their selected external Xclipse driver automatically: installed packages are checked first, then Downloads, then the maintained driver catalog. Older exports such as Xclipse 920 Old do not need to be recreated.
- Wine sessions now use fixed landscape orientation, so device rotation cannot turn the guest desktop vertical.
- The Video dialog documents Mailbox, FIFO, Immediate, and Relaxed behavior; all bundled Vulkan wrappers were checked for the four modes.

### Performance

- Wine 2D navigation uploads only the accumulated changed rectangle of a drawable instead of re-uploading a full window for small menu/highlight changes; padded-stride uploads are packed correctly.
- Present notifications no longer hold the drawable render lock while sending X11 events, reducing input/render contention.
- Opaque Wine/game windows and full-screen post-processing passes render with blending disabled, and content fully hidden below the highest opaque full-screen window is skipped while later menus and popups remain visible.
- Repeated startup service rewrites and temporary diagnostic logging were removed from the normal launch path.
- Container filesystem deletion runs off the UI thread.
- The Wine/Proton catalog is populated immediately from its local cache and throttles network refreshes; verified DOS drive maps and the shared PulseAudio payload are reused between launches.
- Storage-provider copies use buffered 64 KiB transfers instead of 1 KiB chunks, improving config/driver import and large-file I/O.
- The Shortcuts screen opens from the sidebar without crawling every Wine drive on the UI thread; existing entries and artwork load through a fast background path while new `.lnk` files are discovered separately.
- New Box86/Box64 and FEXCore **Turbo (Aggressive)** presets provide larger translation blocks and relaxed compatibility checks without changing Stability, Compatibility, Intermediate, or Performance. Experimental Translation Turbo can apply the same opt-in group per game without destroying the selected preset.
- DXVK 1.x state caches, DXVK 2.x shader caches, and VKD3D-Proton shader caches now share a persistent fast-internal-storage directory; disabled renderer logs no longer create files.

### Fixed

- Community configs no longer disappear between catalog selection and import when another screen clears its own cache. GitHub API rate limits fall back to the public assets page, saved index, or verified cached ZIPs.
- Imported community containers no longer reuse transient install state that could make them open and immediately close; default/bundled content IDs no longer produce false missing-content prompts.
- Wine-created `.lnk` shortcuts resolve quoted, relative, environment, Unix, and Wine paths correctly and refresh their names, executable targets, and artwork.
- Touchscreen mouse handling, pointer grabs, press/release ordering, dragging, Wine context menus, and shortcut creation were aligned with the working Winlator Mali behavior.
- Proton x86_64 startup preserves the shared Box64 executable, launches the installed Wine binary by absolute path, and keeps guest libraries out of Android's native `LD_LIBRARY_PATH`.
- Conventional x86/x86_64 Wine no longer receives ARM64EC DXVK DLLs. The selector filters incompatible entries, launch migrates old incompatible defaults in memory, and missing DXVK/VKD3D DLLs are reinstalled instead of trusting a stale marker.
- Disabling VKD3D restores Wine's D3D12 files after DXVK extraction, preventing legacy DXVK packs from silently reintroducing a stale `d3d12.dll`.
- Pure win32 prefixes no longer create a synthetic `syswow64`; WoW64 prefixes still populate required 32-bit DLLs.
- FSR and other post-processing effects retry offscreen targets with core RGBA when a driver rejects BGRA, then fall back to direct rendering instead of a black screen if framebuffer creation still fails.
- Container IDs are selected from both memory and the filesystem, preventing an imported or newly created container from overwriting `xuser-1`; valid on-disk metadata is preserved during defensive saves.
- The startup controller-assignment notice was removed completely.
- Rockstar titles no longer tear across the screen when panning: the selected present mode is honored independently from 100%/50%/Off pacing, mailbox keeps the newest complete image without serializing the game like FIFO, Android compositor frames are synchronized to the display, and the unsafe single mutable AHardwareBuffer presentation path was removed.
- Live/sidebar VSync values no longer remain as stale overrides after editing a container or shortcut, and `Off` keeps guest FPS uncapped without allowing partial Android compositor frames.
- Mailbox, FIFO, and Relaxed keep required presentation waits even if an old advanced config requested they be bypassed; only Immediate can opt into that tearing-prone fast path.
- VKD3D-Proton receives the selected Vulkan present mode explicitly, including the correct `FIFO_RELAXED` spelling, so DX12 follows the same Mailbox/FIFO/Immediate/Relaxed choice as DXVK and the wrapper.

## 0.9

### New

- **AMD FSR 1.1 support**: full FSR1 pipeline (EASU upscaling + RCAS sharpening) ported from the official AMD shader (`ffx_fsr1.h` v1.20210629). Configurable per container in the Display section and live-applied from the in-session sidebar Display menu; EASU always runs first in the effect chain. Five quality presets from Fidelity to Ultra, each forcing a matching internal render scale (1.5x/1.7x/2.0x), with letterbox-aware EASU mapping of the scene content region.
- Video configuration tab for containers and shortcuts: renderer selection (Vulkan/OpenGL/GDI) as the primary graphics backend switch, GPU name, present mode, texture filtering (now hosting the FSR entry), and a red/blue channel swap option.
- Audio driver configuration dialog with volume control; MIDI soundfont selection moved inside it. PulseAudio becomes the default driver.
- Shortcut artwork system with three configurable sources ÔÇö **Browser** (SteamGridDB search with EXE-icon fallback, default on first boot), **EXE icon**, and **Custom** ÔÇö settable globally or overridden per shortcut.
- New dark glassy touchscreen control style: gradient button bodies with thin light borders, four-petal D-pad with outward chevron arrows and per-direction press highlighting, tick-marked analog sticks, and a matching trackpad.
- Complete gamepad skin set for the touchscreen controls as image assets ÔÇö face buttons (A/B/X/Y), shoulders (L1/R1), triggers (L2/R2), stick clicks (L3/R3), Start/Select, a four-way D-pad, an analog stick base ring with tick marks, and a trackpad ÔÇö selectable per element in the controls editor (D-pad, stick, and trackpad elements accept skins) and pre-applied to both bundled Virtual Gamepad profiles.
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
- New screen sizes for modern tall panels: 2340x1080 (19.5:9), 2560x1440 (16:9), and 3120x1440 (19.5:9) selectable in container and shortcut settings.

### Changed

- Max Device Memory exists only in the Graphics Driver configuration; the DDraw wrapper moved inside the combined DXVK + VKD3D dialog (default WineD3D); default DXVK changed to async-1.10.3; BCN layer files left by v0.8.x are cleaned up on update.
- Aggressive startup selection protects winebus/winehid/MountMgr/PlugPlay while disabling non-critical Wine services; corrected the Wine `Ndis` registry key.
- Separate WoW64 CPU list restored so 32-bit processes can be pinned to different cores than 64-bit processes.
- FEXCore presets match the bionic base: Intermediate sets `FEX_X87REDUCEDPRECISION=1`; Performance adds `FEX_DYNAMICL1CACHE=1` + `FEX_DISABLEL2CACHE=1`.
- BCn emulation controls match the Winlator-Mali reference exactly (none/partial/full/auto ÔåÆ `WRAPPER_EMULATE_BCN` 0/1/2/3); wrappers with native BCN handling (GameNative, Kirimu, Ref4ik-v6) no longer load the shared Leegao layer on top of their own implementation, and stale layer files are removed when switching; Kirimu locks its software-only profile.
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
- Native X11 blits (`copyAreaOp`, `fillRect`, `drawLine`) process whole 32-bit pixels instead of byte loops ÔÇö NEON-friendly and lighter on the shared LPDDR bus.
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
- ? help popups measure wrapped text correctly and use themed backgrounds on dark/light; HUD ┬░C mojibake; follow-system-theme checkbox default mismatch between UI and runtime.
- Container failing to start on Android 10+ with `error=13, Permission denied` when launching the Wine/PulseAudio binaries: `targetSdkVersion` lowered to 28, keeping exec() of binaries inside the app data directory allowed under the Android W^X policy.
- Downloads screen offered removal of the APK-embedded bundles (Box64/DXVK/FEXCore/VKD3D installed on first boot), which containers depend on: removal is no longer offered for embedded content (Info stays), and every bundled install is recorded so future bundles are protected automatically.
- Proton catalog entries showing blank names (the proton-11.0-2 sdk35 packages) or raw file names: bundled catalog metadata is now authoritative, giving clean names to 11.0-2 (arm64ec/x86_64) and 9.0-x86_64.
- Wine/Proton `.tzst` archives saved straight into Downloads (no embedded content profile) install normally now: a profile is synthesized from the archive's bin/lib layout, and the import picker accepts every common zstd MIME type.
- Xclipse driver download catalog sources releases from ExynosTools and the WinXclipse `drivers_0.9` release (the retired MdiEx repository and its cached entries are dropped).
- First-boot progress moves continuously through imagefs extraction (78%), Wine runtime install (93%) and finalization (96ÔÇô100%) instead of appearing stalled.
- X server hardening: DestroySubWindows no longer falls through to ReparentWindow (protocol desync); SYNC AwaitFence blocks without deadlocking the whole server; GrabPointer reads its event-mask from the correct offset so grabs keep delivering button/motion events; malformed client requests (bad enum values, out-of-range indices, oversized properties) return protocol errors instead of killing every connection; large replies survive partial socket writes.
- Imported containers keep their original configuration ÔÇö the copied `.container` file is loaded instead of being overwritten with defaults; the DDraw wrapper selection no longer clobbers the DXVK/VKD3D config when loading; content application copies via temp+rename so a failed install never deletes working DLLs; pinned-tag driver catalogs list correctly.
- Wine registry editor performs complete reads before writing and only replaces system.reg/user.reg with a fully valid clone.
- Input fixes: multi-binding elements no longer lose their last binding on load; range-button bindings are restored on touch up instead of being wiped into the saved profile; controller axes are clamped after a sensitivity boost (sticks no longer invert at the extreme); disconnected controllers no longer crash the bindings screen.
- A game exiting normally now tears down the X/audio/handler components and saves playtime exactly like the Exit action does; shortcut extras (execDelay, inputType, sharpness, rcfileId, controlsProfile) parse defensively instead of crashing at launch; several background-thread guards across Saves/Containers/Contents/Settings screens prevent crashes when leaving mid-operation.
- Containers created from raw Wine/Proton `.tzst` downloads boot correctly: the prefix is now built from the bundled Proton pattern (a full win64 prefix with valid registry headers) instead of the shared imagefs overlay, which left system.reg/user.reg invalid and made wine fail with "64-bit installation ... 32-bit wineserver".
- An out-of-spec PutImage no longer sends BadMatch to the client ÔÇö Wine treats that as fatal and tore down every session ~2s after start; inconsistent payloads are logged and skipped instead, and depth-1 cursor/mask blits use the packed planar size.
- Guest stdout/stderr and the process exit code are always mirrored to logcat under the `WineProc` tag (previously they only appeared with the wine-debug setting enabled), making startup failures diagnosable without changing any setting.
- The fakeinput bridge library copy is size-verified on every launch with one retry; a truncated copy used to be LD_PRELOAD'd into wineserver and killed it with SIGBUS.

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
